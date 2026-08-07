package com.nexio.workflow.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.CurrentActorPort;
import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.usecase.CreateWorkflowUseCase;
import com.nexio.workflow.application.usecase.DeleteWorkflowUseCase;
import com.nexio.workflow.application.usecase.GetWorkflowUseCase;
import com.nexio.workflow.application.usecase.ListWorkflowsUseCase;
import com.nexio.workflow.application.usecase.UpdateWorkflowUseCase;
import com.nexio.workflow.application.usecase.command.CreateWorkflowCommand;
import com.nexio.workflow.application.usecase.command.UpdateWorkflowCommand;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.enums.NodeType;
import com.nexio.workflow.domain.model.enums.TriggerType;
import com.nexio.workflow.infrastructure.config.GraphQLScalarsConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Teste da fatia GraphQL: schema, vinculacao de argumentos, validacao, traducao de erro e redacao
 * de segredo, com os casos de uso dublados.
 *
 * <p>Nao sobe Mongo de proposito. O que esta sob teste aqui e a fronteira -- o que entra pelo
 * schema e o que sai por ele --, e as regras dos casos de uso ja tem teste proprio; um container so
 * tornaria esta suite lenta sem cobrir nada a mais.</p>
 *
 * <p>{@link GraphQLScalarsConfig} precisa de {@code @Import} explicito: a fatia
 * {@code @GraphQlTest} so inclui {@code @Controller} e alguns tipos especificos, e uma
 * {@code @Configuration} comum fica de fora -- sem os scalars {@code JSON} e {@code DateTime} o
 * schema nem carregaria. O {@code WorkflowExceptionResolver}, ao contrario, entra sozinho: ele e um
 * {@code DataFetcherExceptionResolver}, que e um dos tipos que a fatia inclui por padrao.</p>
 *
 * <p>O {@link CurrentActorPort} entra dublado, e nao como o {@code AnonymousActorProvider} real, por
 * uma razao de metodo: com o provedor real o ator seria {@link ActorId#ANONYMOUS}, e um teste de
 * propagacao que compara com o unico valor que o sistema inteiro produz nao prova propagacao
 * nenhuma -- passaria igual se o resolver montasse a constante por conta propria. Dublado, o valor
 * so pode ter chegado ao caso de uso vindo da porta.</p>
 */
@GraphQlTest(WorkflowResolver.class)
@Import(GraphQLScalarsConfig.class)
class WorkflowResolverTest {

    private static final String CREATE_MUTATION = """
            mutation Criar($input: CreateWorkflowInput!) {
              createWorkflow(input: $input) {
                id
                name
                enabled
                startNodeId
                nodes { id type nextOnSuccess }
              }
            }
            """;

    private static final String READ_CONFIG_QUERY =
            "{ workflow(id: \"wf-1\") { nodes { url headers config } } }";

    /**
     * Ator devolvido pela porta. Deliberadamente diferente de {@link ActorId#ANONYMOUS} e sem
     * relacao nenhuma com o conteudo dos documentos GraphQL enviados aqui: e assim que a asercao de
     * propagacao distingue "veio da porta" de "coincidiu com o padrao".
     */
    private static final ActorId ACTOR = new ActorId("ator-da-infraestrutura");

    @MockitoBean
    private CurrentActorPort currentActorPort;

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

    @BeforeEach
    void stubTheActor() {
        when(currentActorPort.currentActor()).thenReturn(ACTOR);
    }

    @Test
    void createsWorkflowAndReturnsIt() {
        when(createWorkflowUseCase.execute(any(), any())).thenReturn(GraphQlFixtures.storedDefinition());

        graphQlTester.document(CREATE_MUTATION)
                .variable("input", validCreateInput())
                .execute()
                .path("createWorkflow.id").entity(String.class).isEqualTo(GraphQlFixtures.ID)
                .path("createWorkflow.name").entity(String.class).isEqualTo("cobranca diaria")
                .path("createWorkflow.enabled").entity(Boolean.class).isEqualTo(true)
                .path("createWorkflow.startNodeId").entity(String.class).isEqualTo("start")
                .path("createWorkflow.nodes").entityList(Object.class).hasSize(2);

        ArgumentCaptor<CreateWorkflowCommand> command = ArgumentCaptor.forClass(CreateWorkflowCommand.class);
        verify(createWorkflowUseCase).execute(eq(ACTOR), command.capture());
        CreateWorkflowCommand sent = command.getValue();
        assertThat(sent.name()).isEqualTo("cobranca diaria");
        assertThat(sent.enabled()).isFalse();
        assertThat(sent.startNodeId()).isEqualTo("start");
        assertThat(sent.triggerConfig().type()).isEqualTo(TriggerType.MOCK_EVENT);
        assertThat(sent.nodes()).hasSize(2);
        assertThat(sent.nodes().getFirst().nodeId()).isEqualTo("start");
        assertThat(sent.nodes().getFirst().type()).isEqualTo(NodeType.HTTP_REQUEST);
        assertThat(sent.nodes().getFirst().nextOnSuccess()).isEqualTo("end");
        assertThat(sent.nodes().getFirst().config()).containsEntry("url", "https://exemplo.test");
    }

    /**
     * O ator que chega ao caso de uso e o que a porta devolveu, e o documento GraphQL nao tem
     * influencia nenhuma sobre ele.
     *
     * <p>A entrada deste teste tenta se passar por outro: o nome, a descricao e a config livre do no
     * carregam {@code "admin"} e uma chave {@code actorId}. Nada disso alcanca o parametro -- e o
     * ponto inteiro da {@code docs/adr/0006-actor-propagation.md}. Um ator escolhido por quem chama
     * seria o {@code tenantId} da ADR 0001 outra vez: identificador de quem pede, informado por quem
     * pede, que no dia da autorizacao viraria um seletor de dados alheios.</p>
     */
    @Test
    void theActorReachingTheUseCaseComesFromThePortAndNotFromTheInput() {
        when(createWorkflowUseCase.execute(any(), any())).thenReturn(GraphQlFixtures.storedDefinition());

        graphQlTester.document(CREATE_MUTATION)
                .variable("input", createInputImpersonatingAnotherActor())
                .execute()
                .path("createWorkflow.id").entity(String.class).isEqualTo(GraphQlFixtures.ID);

        ArgumentCaptor<ActorId> actor = ArgumentCaptor.forClass(ActorId.class);
        verify(createWorkflowUseCase).execute(actor.capture(), any(CreateWorkflowCommand.class));
        verify(currentActorPort).currentActor();
        assertThat(actor.getValue()).isEqualTo(ACTOR);
        assertThat(actor.getValue().value()).doesNotContain("admin");
    }

    /**
     * A outra metade da mesma garantia, e a mais forte: nao existe campo por onde tentar. O schema
     * recusa {@code actorId} dentro do input antes de qualquer resolucao, entao a defesa nao depende
     * de o resolver lembrar de ignorar um campo -- ele nao tem o que ignorar.
     */
    @Test
    void theSchemaHasNoFieldThroughWhichAClientCouldChooseItsActor() {
        graphQlTester.document("""
                        mutation {
                          createWorkflow(input: {
                            name: "tentativa",
                            actorId: "admin",
                            trigger: { type: MOCK_EVENT },
                            nodes: [{ id: "start", type: HTTP_REQUEST, config: {} }]
                          }) { id }
                        }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    assertThat(errors.getFirst().getMessage()).contains("actorId");
                });

        verifyNoInteractions(createWorkflowUseCase);
    }

    /**
     * Entrada invalida para antes do caso de uso. Se a validacao nao estivesse ligada, o nome em
     * branco chegaria ao dominio e o erro viria de outro lugar -- por isso o teste afirma tambem que
     * o caso de uso nao foi chamado.
     */
    @Test
    void rejectsInvalidCreateInputWithoutCallingTheUseCase() {
        graphQlTester.document(CREATE_MUTATION)
                .variable("input", createInputWithBlankName())
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.BAD_REQUEST.name());
                    assertThat(errors.getFirst().getMessage()).contains("name e obrigatorio");
                });

        verifyNoInteractions(createWorkflowUseCase);
    }

    /**
     * A validacao desce nos inputs aninhados. Sem {@code @Valid} nos componentes, as anotacoes de
     * {@code WorkflowNodeInput} existiriam sem nunca rodar -- que e o modo silencioso de nao ter
     * validacao nenhuma.
     */
    @Test
    void cascadesValidationIntoNestedNodeInputs() {
        graphQlTester.document(CREATE_MUTATION)
                .variable("input", createInputWithBlankNodeId())
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.BAD_REQUEST.name());
                    assertThat(errors.getFirst().getMessage()).contains("id do no e obrigatorio");
                });

        verifyNoInteractions(createWorkflowUseCase);
    }

    /** A mesma cascata precisa atravessar o {@code ArgumentValue} da atualizacao parcial. */
    @Test
    void cascadesValidationIntoNodesSentInAnUpdate() {
        graphQlTester.document("""
                        mutation {
                          updateWorkflow(id: "wf-1", input: {
                            nodes: [{ id: " ", type: HTTP_REQUEST, config: {} }]
                          }) { id }
                        }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.BAD_REQUEST.name());
                    assertThat(errors.getFirst().getMessage()).contains("id do no e obrigatorio");
                });

        verifyNoInteractions(updateWorkflowUseCase);
    }

    @Test
    void returnsNotFoundWithTheDomainMessage() {
        when(getWorkflowUseCase.execute(ACTOR, "wf-404")).thenThrow(new WorkflowNotFoundException("wf-404"));

        graphQlTester.document("{ workflow(id: \"wf-404\") { id } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.NOT_FOUND.name());
                    assertThat(errors.getFirst().getMessage()).isEqualTo("Workflow nao encontrado: wf-404");
                });
    }

    /**
     * O que nao esta mapeado nao pode virar mensagem para o cliente. Uma excecao inesperada carrega
     * detalhe de infraestrutura, e mapear "tudo o mais" para a mensagem original transformaria cada
     * bug em um relatorio para quem estivesse sondando o servico.
     */
    @Test
    void unmappedExceptionsDoNotLeakInternals() {
        when(getWorkflowUseCase.execute(ACTOR, GraphQlFixtures.ID))
                .thenThrow(new IllegalStateException("falha em mongodb://nexio:senha@10.0.0.7:27017"));

        graphQlTester.document("{ workflow(id: \"wf-1\") { id } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.INTERNAL_ERROR.name());
                    assertThat(errors.getFirst().getMessage()).doesNotContain("mongodb", "senha");
                });
    }

    /**
     * O caso que justifica {@code ArgumentValue} e {@code Patch}: {@code description: null} e uma
     * instrucao para limpar o campo, e precisa chegar ao comando como recorte presente com valor
     * nulo.
     */
    @Test
    void sendingDescriptionAsNullClearsIt() {
        when(updateWorkflowUseCase.execute(eq(ACTOR), eq(GraphQlFixtures.ID), any()))
                .thenReturn(GraphQlFixtures.storedDefinition());

        graphQlTester.document("""
                        mutation {
                          updateWorkflow(id: "wf-1", input: { description: null }) { id }
                        }
                        """)
                .execute()
                .path("updateWorkflow.id").entity(String.class).isEqualTo(GraphQlFixtures.ID);

        UpdateWorkflowCommand command = capturedUpdateCommand();
        assertThat(command.description().present()).isTrue();
        assertThat(command.description().value()).isNull();
        assertThat(command.name().present()).isFalse();
    }

    /**
     * A outra metade da mesma distincao: omitir o campo nao pode ser lido como "apague". Com um
     * record de tipos simples os dois testes veriam exatamente o mesmo valor nulo.
     */
    @Test
    void omittingDescriptionLeavesItAlone() {
        when(updateWorkflowUseCase.execute(eq(ACTOR), eq(GraphQlFixtures.ID), any()))
                .thenReturn(GraphQlFixtures.storedDefinition());

        graphQlTester.document("""
                        mutation {
                          updateWorkflow(id: "wf-1", input: { name: "novo nome" }) { id }
                        }
                        """)
                .execute()
                .path("updateWorkflow.id").entity(String.class).isEqualTo(GraphQlFixtures.ID);

        UpdateWorkflowCommand command = capturedUpdateCommand();
        assertThat(command.description().present()).isFalse();
        assertThat(command.name().present()).isTrue();
        assertThat(command.name().value()).isEqualTo("novo nome");
    }

    /**
     * Nulo explicito nem sempre e instrucao valida: {@code name} e nao nulo na leitura, entao
     * limpa-lo deixaria o schema mentindo na proxima consulta.
     */
    @Test
    void rejectsExplicitNullOnAFieldThatCannotBeCleared() {
        graphQlTester.document("""
                        mutation {
                          updateWorkflow(id: "wf-1", input: { name: null }) { id }
                        }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors.getFirst().getErrorType())
                        .hasToString(ErrorType.BAD_REQUEST.name()));

        verifyNoInteractions(updateWorkflowUseCase);
    }

    @Test
    void deleteReturnsTrue() {
        graphQlTester.document("mutation { deleteWorkflow(id: \"wf-1\") }")
                .execute()
                .path("deleteWorkflow").entity(Boolean.class).isEqualTo(true);

        verify(deleteWorkflowUseCase).execute(ACTOR, GraphQlFixtures.ID);
    }

    @Test
    void deleteOfUnknownWorkflowIsNotFound() {
        doThrow(new WorkflowNotFoundException("wf-404")).when(deleteWorkflowUseCase).execute(ACTOR, "wf-404");

        graphQlTester.document("mutation { deleteWorkflow(id: \"wf-404\") }")
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors.getFirst().getErrorType())
                        .hasToString(ErrorType.NOT_FOUND.name()));
    }

    @Test
    void listPassesLimitOffsetAndEnabledOnlyToTheUseCase() {
        when(listWorkflowsUseCase.execute(any(), any(), anyBoolean()))
                .thenReturn(List.of(GraphQlFixtures.storedDefinition()));

        graphQlTester.document("{ workflows(limit: 5, offset: 10, enabledOnly: true) { id } }")
                .execute()
                .path("workflows").entityList(Object.class).hasSize(1);

        ArgumentCaptor<PageQuery> page = ArgumentCaptor.forClass(PageQuery.class);
        ArgumentCaptor<Boolean> enabledOnly = ArgumentCaptor.forClass(Boolean.class);
        verify(listWorkflowsUseCase).execute(eq(ACTOR), page.capture(), enabledOnly.capture());
        assertThat(page.getValue()).isEqualTo(new PageQuery(5, 10));
        assertThat(enabledOnly.getValue()).isTrue();
    }

    /** Sem argumento, o recorte e o padrao declarado no schema -- nunca "sem recorte". */
    @Test
    void listWithoutArgumentsUsesTheSchemaDefaults() {
        when(listWorkflowsUseCase.execute(any(), any(), anyBoolean())).thenReturn(List.of());

        graphQlTester.document("{ workflows { id } }")
                .execute()
                .path("workflows").entityList(Object.class).hasSize(0);

        verify(listWorkflowsUseCase).execute(ACTOR, PageQuery.firstPage(), false);
    }

    @Test
    void listRejectsLimitAboveTheServerMaximum() {
        graphQlTester.document("{ workflows(limit: 1000) { id } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.BAD_REQUEST.name());
                });

        verifyNoInteractions(listWorkflowsUseCase);
    }

    /**
     * O deslocamento tambem tem teto, e o {@code limit} nao o cobre. Deslocamento vira {@code skip}
     * no MongoDB, e {@code skip} nao pula: o servidor percorre e descarta um documento por unidade.
     * Sem {@code @Max}, {@code offset: 2147483647} pedia uma varredura completa em quatro bytes,
     * repetivel a vontade -- com um {@code limit} perfeitamente comportado do lado.
     */
    @Test
    void listRejectsAnOffsetAboveTheServerMaximum() {
        graphQlTester.document("{ workflows(offset: " + Integer.MAX_VALUE + ") { id } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.BAD_REQUEST.name());
                    assertThat(errors.getFirst().getMessage()).contains("offset");
                });

        verifyNoInteractions(listWorkflowsUseCase);
    }

    /** A entrada mais funda que o mapper aceita e recusada como entrada invalida, nao como bug. */
    @Test
    void rejectsNodeConfigNestedDeeperThanTheDomainAccepts() {
        graphQlTester.document(CREATE_MUTATION)
                .variable("input", createInputWithOverlyNestedConfig())
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.BAD_REQUEST.name());
                });

        verifyNoInteractions(createWorkflowUseCase);
    }

    /**
     * D1: os cabecalhos do no voltam com o segredo mascarado e o resto intacto.
     *
     * <p>Depois que {@code headers} virou campo declarado, ele deixou de estar dentro de
     * {@code config} e passou a ter caminho proprio na resposta -- e caminho proprio no
     * {@code WorkflowNodeResponse}, que e onde a redacao e aplicada. O teste segue o campo para
     * onde ele foi: se apontasse para {@code config.headers}, passaria a consultar um caminho que
     * nao existe mais e nao verificaria redacao nenhuma.</p>
     */
    @Test
    void redactsNestedAuthorizationHeaderOnRead() {
        when(getWorkflowUseCase.execute(ACTOR, GraphQlFixtures.ID))
                .thenReturn(GraphQlFixtures.definitionWithSecretHeader());

        graphQlTester.document(READ_CONFIG_QUERY)
                .execute()
                .path("workflow.nodes[0].headers.Authorization")
                .entity(String.class).isEqualTo(SecretRedactor.REDACTED)
                .path("workflow.nodes[0].headers['Content-Type']")
                .entity(String.class).isEqualTo("application/json")
                .path("workflow.nodes[0].url")
                .entity(String.class).isEqualTo("https://exemplo.test");
    }

    /** A redacao e da leitura: o que esta gravado -- e o que a execucao usa -- nao e tocado. */
    @Test
    void redactionDoesNotTouchTheStoredDefinition() {
        WorkflowDefinition stored = GraphQlFixtures.definitionWithSecretHeader();
        when(getWorkflowUseCase.execute(ACTOR, GraphQlFixtures.ID)).thenReturn(stored);

        graphQlTester.document(READ_CONFIG_QUERY)
                .execute()
                .path("workflow.nodes[0].headers.Authorization")
                .entity(String.class).isEqualTo(SecretRedactor.REDACTED);

        assertThat(headersOfFirstNode(stored)).containsEntry("Authorization", "Bearer super-secreto");
    }

    private static Map<String, Object> headersOfFirstNode(WorkflowDefinition definition) {
        return definition.getNodes().getFirst().headers();
    }

    @Test
    void activateSendsOnlyTheEnabledPatch() {
        when(updateWorkflowUseCase.execute(eq(ACTOR), eq(GraphQlFixtures.ID), any()))
                .thenReturn(GraphQlFixtures.storedDefinition());

        graphQlTester.document("mutation { activateWorkflow(id: \"wf-1\") { id enabled } }")
                .execute()
                .path("activateWorkflow.enabled").entity(Boolean.class).isEqualTo(true);

        UpdateWorkflowCommand command = capturedUpdateCommand();
        assertThat(command.enabled().present()).isTrue();
        assertThat(command.enabled().value()).isTrue();
        assertThat(command.name().present()).isFalse();
        assertThat(command.nodes().present()).isFalse();
    }

    @Test
    void deactivateSendsOnlyTheEnabledPatch() {
        when(updateWorkflowUseCase.execute(eq(ACTOR), eq(GraphQlFixtures.ID), any()))
                .thenReturn(GraphQlFixtures.storedDefinition());

        graphQlTester.document("mutation { deactivateWorkflow(id: \"wf-1\") { id } }")
                .execute()
                .path("deactivateWorkflow.id").entity(String.class).isEqualTo(GraphQlFixtures.ID);

        UpdateWorkflowCommand command = capturedUpdateCommand();
        assertThat(command.enabled().present()).isTrue();
        assertThat(command.enabled().value()).isFalse();
    }

    private UpdateWorkflowCommand capturedUpdateCommand() {
        ArgumentCaptor<UpdateWorkflowCommand> command = ArgumentCaptor.forClass(UpdateWorkflowCommand.class);
        verify(updateWorkflowUseCase).execute(eq(ACTOR), eq(GraphQlFixtures.ID), command.capture());
        return command.getValue();
    }

    private static Map<String, Object> validCreateInput() {
        return Map.of(
                "name", "cobranca diaria",
                "trigger", Map.of("type", "MOCK_EVENT", "config", Map.of()),
                "startNodeId", "start",
                "nodes", List.of(
                        Map.of(
                                "id", "start",
                                "type", "HTTP_REQUEST",
                                "config", Map.of("url", "https://exemplo.test"),
                                "nextOnSuccess", "end"),
                        Map.of(
                                "id", "end",
                                "type", "HTTP_REQUEST",
                                "config", Map.of())));
    }

    /**
     * Entrada que tenta se passar por outro ator pelos unicos meios que o schema oferece: texto
     * livre e mapa livre. Nenhum deles e lido como identidade em lugar nenhum.
     */
    private static Map<String, Object> createInputImpersonatingAnotherActor() {
        return Map.of(
                "name", "admin",
                "description", "actorId=admin",
                "trigger", Map.of("type", "MOCK_EVENT"),
                "nodes", List.of(Map.of(
                        "id", "start",
                        "type", "HTTP_REQUEST",
                        "url", "https://exemplo.test",
                        "config", Map.of("actorId", "admin"))));
    }

    private static Map<String, Object> createInputWithBlankNodeId() {
        return Map.of(
                "name", "cobranca diaria",
                "trigger", Map.of("type", "MOCK_EVENT"),
                "nodes", List.of(Map.of(
                        "id", " ",
                        "type", "HTTP_REQUEST",
                        "config", Map.of())));
    }

    /**
     * Aninhamento acima do que a copia defensiva do dominio tolera. O analisador do graphql-java
     * aceita valor de variavel muito mais fundo do que isso sem reclamar, entao este e um documento
     * que qualquer cliente consegue enviar.
     */
    private static Map<String, Object> createInputWithOverlyNestedConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> cursor = config;
        for (int i = 0; i < 150; i++) {
            Map<String, Object> child = new LinkedHashMap<>();
            cursor.put("filho", child);
            cursor = child;
        }
        return Map.of(
                "name", "config funda demais",
                "trigger", Map.of("type", "MOCK_EVENT"),
                "nodes", List.of(Map.of(
                        "id", "start",
                        "type", "HTTP_REQUEST",
                        "config", config)));
    }

    private static Map<String, Object> createInputWithBlankName() {
        return Map.of(
                "name", "   ",
                "trigger", Map.of("type", "MOCK_EVENT"),
                "nodes", List.of(Map.of(
                        "id", "start",
                        "type", "HTTP_REQUEST",
                        "config", Map.of())));
    }
}
