package com.nexio.workflow.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexio.workflow.application.usecase.CreateWorkflowUseCase;
import com.nexio.workflow.application.usecase.DeleteWorkflowUseCase;
import com.nexio.workflow.application.usecase.GetWorkflowUseCase;
import com.nexio.workflow.application.usecase.ListWorkflowsUseCase;
import com.nexio.workflow.application.usecase.UpdateWorkflowUseCase;
import com.nexio.workflow.infrastructure.config.GraphQLScalarsConfig;
import com.nexio.workflow.infrastructure.config.GraphQlQueryCostConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Afirma o que o teto de custo por consulta aceita e o que ele recusa.
 *
 * <p>Existe por causa de um furo que a configuracao tinha e nenhum teste via: o calculo padrao do
 * graphql-java e {@code 1 + childComplexity} e nao olha cardinalidade, entao
 * {@code workflows(limit: 100) { id }} pontuava 2 -- exatamente o mesmo que
 * {@code workflows(limit: 1) { id }}. O unico argumento que decide quanto trabalho o servidor faz
 * era invisivel para a instrumentacao que existe para limitar esse trabalho, e dezenas de apelidos
 * do mesmo campo cabiam folgados no teto.</p>
 *
 * <p>Os casos de uso sao dublados de proposito: o que esta sob teste e a decisao tomada <i>antes</i>
 * de qualquer campo ser resolvido. Numa consulta recusada, nenhum deles chega a ser chamado, e e
 * isso que a asercao negativa afirma.</p>
 */
@GraphQlTest(WorkflowResolver.class)
@Import({GraphQLScalarsConfig.class, GraphQlQueryCostConfig.class})
class GraphQlQueryCostTest {

    /** A consulta legitima mais cara do schema: a pagina cheia com todos os campos. */
    private static final String FULL_PAGE_QUERY = """
            {
              workflows(limit: 100) {
                id
                name
                description
                enabled
                startNodeId
                trigger { type config }
                nodes { id type config nextOnSuccess nextOnTrue nextOnFalse }
                createdAt
                updatedAt
              }
            }
            """;

    @MockitoBean
    private CreateWorkflowUseCase createWorkflowUseCase;

    @MockitoBean
    private UpdateWorkflowUseCase updateWorkflowUseCase;

    @MockitoBean
    private DeleteWorkflowUseCase deleteWorkflowUseCase;

    @MockitoBean
    private GetWorkflowUseCase getWorkflowUseCase;

    @MockitoBean
    private ListWorkflowsUseCase listWorkflowsUseCase;

    @Autowired
    private GraphQlTester graphQlTester;

    /**
     * O teto e calibrado por esta consulta: {@code 1 + 100 * 17} da 1701, abaixo dos
     * {@value GraphQlQueryCostConfig#MAX_COMPLEXITY} configurados. Se ela passasse a ser recusada, o
     * teto teria deixado de ser contencao de abuso para virar limitacao do produto.
     */
    @Test
    void acceptsAFullPageWithEveryFieldSelected() {
        when(listWorkflowsUseCase.execute(any(), anyBoolean())).thenReturn(List.of());

        graphQlTester.document(FULL_PAGE_QUERY)
                .execute()
                .errors().verify()
                .path("workflows").entityList(Object.class).hasSize(0);
    }

    /**
     * O caso que o calculo antigo nao via. Vinte apelidos do mesmo campo, cada um pedindo a pagina
     * cheia de registros: sob {@code 1 + childComplexity} cada apelido valia 3 e o documento inteiro
     * somava 60, folgado dentro de qualquer teto plausivel, enquanto mandava o servidor produzir
     * 2000 registros. Contando a cardinalidade, cada apelido vale 201 e o documento e recusado antes
     * de resolver campo algum.
     */
    @Test
    void rejectsTheSameFieldRepeatedByAliasWithTheMaximumLimit() {
        StringBuilder document = new StringBuilder("{\n");
        for (int i = 0; i < 20; i++) {
            document.append("  a").append(i).append(": workflows(limit: 100) { id name }\n");
        }
        document.append("}\n");

        graphQlTester.document(document.toString())
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getMessage()).contains("complexity");
                });

        verifyNoInteractions(listWorkflowsUseCase);
    }

    /**
     * A outra metade da mesma regra: o mesmo numero de apelidos pedindo um registro cada continua
     * passando. Sem isto, "recusa apelidos demais" poderia estar sendo cumprido por um teto que
     * simplesmente ficou baixo demais para qualquer consulta com repeticao.
     */
    @Test
    void acceptsTheSameNumberOfAliasesWhenEachAsksForOneRecord() {
        when(listWorkflowsUseCase.execute(any(), anyBoolean())).thenReturn(List.of());

        StringBuilder document = new StringBuilder("{\n");
        for (int i = 0; i < 20; i++) {
            document.append("  a").append(i).append(": workflows(limit: 1) { id name }\n");
        }
        document.append("}\n");

        graphQlTester.document(document.toString())
                .execute()
                .errors().verify()
                .path("a0").entityList(Object.class).hasSize(0);
    }
}
