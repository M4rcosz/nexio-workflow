package com.nexio.workflow.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexio.workflow.application.usecase.CreateWorkflowUseCase;
import com.nexio.workflow.application.usecase.GetExecutionUseCase;
import com.nexio.workflow.application.usecase.ListExecutionsUseCase;
import com.nexio.workflow.application.usecase.TriggerWorkflowUseCase;
import com.nexio.workflow.application.usecase.DeleteWorkflowUseCase;
import com.nexio.workflow.application.usecase.GetWorkflowUseCase;
import com.nexio.workflow.application.usecase.ListWorkflowsUseCase;
import com.nexio.workflow.application.usecase.UpdateWorkflowUseCase;
import com.nexio.workflow.infrastructure.config.GraphQLScalarsConfig;
import com.nexio.workflow.infrastructure.config.GraphQlQueryCostConfig;
import com.nexio.workflow.infrastructure.security.AnonymousActorProvider;
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
 *
 * <p>O {@code AnonymousActorProvider} entra por {@code @Import} pelo mesmo motivo dos scalars: a
 * fatia {@code @GraphQlTest} nao carrega {@code @Component}, e sem ele o resolver -- que agora
 * recebe a porta do ator por construtor -- nao teria como ser instanciado. Aqui vale o provedor
 * real: nada nestes testes olha o ator, e um duble so acrescentaria ruido.</p>
 */
@GraphQlTest({WorkflowResolver.class, ExecutionResolver.class})
@Import({GraphQLScalarsConfig.class, GraphQlQueryCostConfig.class, AnonymousActorProvider.class})
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

    @MockitoBean
    private TriggerWorkflowUseCase triggerWorkflowUseCase;

    @MockitoBean
    private GetExecutionUseCase getExecutionUseCase;

    @MockitoBean
    private ListExecutionsUseCase listExecutionsUseCase;

    @Autowired
    private GraphQlTester graphQlTester;

    /**
     * O teto e calibrado por esta consulta: {@code 1 + 100 * 17} da 1701, abaixo dos
     * {@value GraphQlQueryCostConfig#MAX_COMPLEXITY} configurados. Se ela passasse a ser recusada, o
     * teto teria deixado de ser contencao de abuso para virar limitacao do produto.
     */
    @Test
    void acceptsAFullPageWithEveryFieldSelected() {
        when(listWorkflowsUseCase.execute(any(), any(), anyBoolean())).thenReturn(List.of());

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
        when(listWorkflowsUseCase.execute(any(), any(), anyBoolean())).thenReturn(List.of());

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

    /**
     * Uma pagina de execucoes pedindo os passos e recusada.
     *
     * <p>O calculo tratava "campo sem {@code limit}" como cardinalidade um, e {@code steps} nao tem
     * argumento nenhum. Com isso {@code executions(limit: 100) { steps { ... } }} pontuava 1401 --
     * passava com folga -- enquanto pedia ate cem documentos de execucao, cada um com ate
     * {@value com.nexio.workflow.domain.model.WorkflowExecution#MAX_STEPS} passos cujo
     * {@code output} e o corpo de resposta de um terceiro. Depois de lidos, todos ainda seriam
     * copiados em profundidade pela redacao antes de serializar.</p>
     *
     * <p>E o mesmo defeito que motivou o calculo por {@code limit}, um nivel abaixo: quem escolhe o
     * tamanho aqui e o servidor, entao a cardinalidade tem que vir do dominio.</p>
     */
    @Test
    void refusesAPageOfExecutionsThatAlsoAsksForTheirSteps() {
        graphQlTester.document("""
                { executions(workflowId: "w", limit: 100) {
                    id workflowId status triggerPayload createdAt startedAt finishedAt errorMessage
                    steps { nodeId status output error executedAt }
                  } }
                """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());

        verifyNoInteractions(listExecutionsUseCase);
    }

    /**
     * Uma execucao unica com todos os passos continua cabendo: e a leitura normal do historico.
     *
     * <p>A afirmacao e que o caso de uso <i>foi chamado</i>, e nao que a resposta veio sem erro: o
     * duble devolve nulo e o resolver estoura depois. O que esta sob teste e a decisao tomada antes
     * de qualquer campo ser resolvido, e chegar ao caso de uso e exatamente a prova de que a
     * consulta passou pela instrumentacao.</p>
     */
    @Test
    void stillAcceptsASingleExecutionWithAllItsSteps() {
        graphQlTester.document("""
                { execution(id: "e") { id status steps { nodeId status output error executedAt } } }
                """)
                .execute()
                .errors()
                .satisfy(errors -> { });

        verify(getExecutionUseCase).execute(any(), any());
    }

    /** Uma pagina de execucoes sem os passos tambem: e a listagem que um painel faz. */
    @Test
    void stillAcceptsAPageOfExecutionsWithoutSteps() {
        when(listExecutionsUseCase.execute(any(), any(), any())).thenReturn(List.of());

        graphQlTester.document("""
                { executions(workflowId: "w", limit: 100) { id status createdAt finishedAt } }
                """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isEmpty());
    }

    /**
     * Um documento nao pode disparar mais de um workflow.
     *
     * <p>{@code triggerWorkflow(id: "x") { id }} tem o formato mais barato do schema e vale 2, entao
     * mil apelidos cabiam no teto de complexidade num documento de uns 40 KB. Campos de mutation na
     * raiz executam em serie, ou seja, uma requisicao anonima comprava mil execucoes sincronas na
     * mesma thread -- cada uma ate o teto de tempo da engine, cada uma capaz de duzentas chamadas
     * HTTP de saida.</p>
     */
    @Test
    void refusesADocumentThatTriggersMoreThanOneWorkflow() {
        graphQlTester.document("""
                mutation {
                  a: triggerWorkflow(id: "w") { id }
                  b: triggerWorkflow(id: "w") { id }
                }
                """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    assertThat(errors.getFirst().getMessage()).contains("no maximo");
                });

        verifyNoInteractions(triggerWorkflowUseCase);
    }

    /**
     * Esconder os disparos dentro de um fragmento nao contorna a regra.
     *
     * <p>Contar so os campos diretos da raiz seria uma regra que qualquer cliente desfaz em uma
     * linha.</p>
     */
    @Test
    void countsTriggersHiddenInsideAFragment() {
        graphQlTester.document("""
                mutation { ...Disparos }
                fragment Disparos on Mutation {
                  a: triggerWorkflow(id: "w") { id }
                  b: triggerWorkflow(id: "w") { id }
                }
                """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());

        verifyNoInteractions(triggerWorkflowUseCase);
    }

    /** Um disparo unico continua passando: a regra limita abuso, nao uso. */
    @Test
    void stillAcceptsASingleTrigger() {
        graphQlTester.document("mutation { triggerWorkflow(id: \"w\") { id } }")
                .execute()
                .errors()
                .satisfy(errors -> { });

        verify(triggerWorkflowUseCase).execute(any(), any(), any());
    }
}
