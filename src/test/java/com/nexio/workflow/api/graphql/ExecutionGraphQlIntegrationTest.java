package com.nexio.workflow.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCollection;
import com.nexio.workflow.AbstractMongoIntegrationTest;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Teste ponta a ponta das operacoes de execucao, entrando pelo endpoint GraphQL.
 *
 * <p>Entra pelo {@link MockMvc} pelo mesmo motivo do {@code WorkflowGraphQlIntegrationTest}: a
 * cadeia de filtros e as instrumentacoes de custo por consulta so ficam sob teste quando a
 * requisicao atravessa a pilha do servlet.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExecutionGraphQlIntegrationTest extends AbstractMongoIntegrationTest {

    private static final String DEFINITIONS_COLLECTION = "workflow_definitions";
    private static final String EXECUTIONS_COLLECTION = "workflow_executions";

    private static final String EXECUTIONS_QUERY = """
            query Listar($workflowId: ID!, $limit: Int, $offset: Int) {
              executions(workflowId: $workflowId, limit: $limit, offset: $offset) {
                id
                workflowId
                status
                triggerPayload
                steps { nodeId status output error executedAt }
                createdAt
                errorMessage
              }
            }
            """;

    private static final String EXECUTION_QUERY = """
            query Buscar($id: ID!) {
              execution(id: $id) { id workflowId status triggerPayload steps { nodeId output } }
            }
            """;

    private static final String TRIGGER_MUTATION = """
            mutation Disparar($id: ID!, $payload: JSON) {
              triggerWorkflow(id: $id, payload: $payload) {
                id workflowId status triggerPayload steps { nodeId status output } errorMessage
              }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${spring.graphql.path:/graphql}")
    private String graphQlPath;

    private GraphQlTester graphQlTester;

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection(DEFINITIONS_COLLECTION).deleteMany(new Document());
        mongoTemplate.getCollection(EXECUTIONS_COLLECTION).deleteMany(new Document());
        graphQlTester = MockMvcGraphQlTransport.tester(mockMvc, objectMapper, graphQlPath);
    }

    /**
     * A paginacao nao repete nem pula execucoes quando varias compartilham o mesmo instante.
     *
     * <p>E o defeito que uma revisao apontou. A consulta ordenava so por {@code createdAt}, que nao
     * e unico -- varios disparos do mesmo workflow caem no mesmo instante --, e ordenacao com empate
     * nao tem ordem definida. Com {@code skip}/{@code limit} por cima, duas paginas consecutivas
     * podiam devolver a mesma execucao e omitir outra, sem que o cliente tivesse como perceber a
     * perda. O desempate por identificador torna a ordem total.</p>
     *
     * <p>As dez execucoes deste teste tem <b>exatamente</b> o mesmo {@code createdAt} de proposito:
     * e a unica configuracao em que o defeito e observavel.</p>
     */
    @Test
    void pagingThroughExecutionsWithIdenticalTimestampsLosesNothing() {
        seedDefinition("wf-1");
        Instant sameInstant = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 10; i++) {
            seedExecution("exec-" + i, "wf-1", sameInstant);
        }

        List<String> firstPage = idsOf(page("wf-1", 4, 0));
        List<String> secondPage = idsOf(page("wf-1", 4, 4));
        List<String> thirdPage = idsOf(page("wf-1", 4, 8));

        // A ordem exata, e nao apenas a ausencia de repeticao: "nenhuma pagina repetiu" e afirmacao
        // fraca demais para este defeito.
        //
        // Uma observacao que so apareceu ao tentar quebrar este teste de proposito, e que vale
        // registrar porque muda o que ele prova: trocar a consulta de volta para `...CreatedAtDesc`
        // (sem o desempate) **nao** o derruba. Quem entrega a ordem e o indice
        // `exec_workflow_created_id`, cuja definicao termina em `_id: -1` -- percorre-lo ja devolve
        // os empatados em identificador decrescente, mesmo quando a consulta nao pede. So removendo
        // o desempate dos **dois** lados, indice e ordenacao, o teste falha.
        //
        // Isso nao torna o desempate na consulta decorativo: e o contrario. Ordem que existe por
        // acaso do plano escolhido nao e ordem garantida -- outro plano, uma varredura de colecao ou
        // um cluster particionado devolveriam outra coisa, e o MongoDB nao promete nada sobre
        // empate. O que este teste trava e a combinacao dos dois.
        assertThat(firstPage).containsExactly("exec-9", "exec-8", "exec-7", "exec-6");
        assertThat(secondPage).containsExactly("exec-5", "exec-4", "exec-3", "exec-2");
        assertThat(thirdPage).containsExactly("exec-1", "exec-0");
    }

    /** A ordem e a mais recente primeiro, com o identificador desempatando. */
    @Test
    void listsTheMostRecentFirst() {
        seedDefinition("wf-1");
        seedExecution("exec-antiga", "wf-1", Instant.parse("2026-01-01T00:00:00Z"));
        seedExecution("exec-nova", "wf-1", Instant.parse("2026-01-02T00:00:00Z"));

        assertThat(idsOf(page("wf-1", 20, 0))).containsExactly("exec-nova", "exec-antiga");
    }

    /**
     * Listar execucoes de um workflow que nao existe e NOT_FOUND.
     *
     * <p>Lista vazia seria indistinguivel de um workflow real que nunca foi disparado.</p>
     */
    @Test
    void listingExecutionsOfAnUnknownWorkflowIsNotFound() {
        graphQlTester.document(EXECUTIONS_QUERY)
                .variable("workflowId", "nao-existe")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.NOT_FOUND.name());
                });
    }

    @Test
    void anUnknownExecutionIsNotFound() {
        graphQlTester.document(EXECUTION_QUERY)
                .variable("id", "nao-existe")
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors.getFirst().getErrorType())
                        .hasToString(ErrorType.NOT_FOUND.name()));
    }

    /**
     * O que o historico devolve sai mascarado, e o que esta gravado continua intacto.
     *
     * <p>As duas metades importam. A primeira e a protecao: o {@code output} de um passo e o corpo
     * de resposta de um terceiro, e um endpoint de autenticacao devolve {@code access_token} sem que
     * ninguem tenha escrito credencial no workflow. A segunda e a politica do projeto -- a redacao e
     * so da leitura, porque e o valor gravado que a execucao usa.</p>
     */
    @Test
    void secretsInsideAnExecutionAreRedactedOnReadButStayIntactInTheDocument() {
        seedDefinition("wf-1");
        executions().insertOne(new Document("_id", "exec-1")
                .append("workflowId", "wf-1")
                .append("status", "SUCCESS")
                .append("triggerPayload", new Document("signature", "sha256=abc").append("pedido", "PED-1"))
                .append("steps", List.of(new Document("nodeId", "login")
                        .append("status", "SUCCESS")
                        .append("output", new Document("body", new Document("access_token", "ya29.secreto")))
                        .append("executedAt", Date.from(Instant.parse("2026-01-01T00:00:00Z")))))
                .append("createdAt", Date.from(Instant.parse("2026-01-01T00:00:00Z")))
                .append("version", 0L));

        Map<String, Object> payload = graphQlTester.document(EXECUTION_QUERY)
                .variable("id", "exec-1")
                .execute()
                .path("execution.triggerPayload").entity(MAP).get();
        Map<String, Object> output = graphQlTester.document(EXECUTION_QUERY)
                .variable("id", "exec-1")
                .execute()
                .path("execution.steps[0].output").entity(MAP).get();

        assertThat(payload)
                .containsEntry("signature", SecretRedactor.REDACTED)
                .containsEntry("pedido", "PED-1");
        assertThat(asMap(output.get("body")))
                .containsEntry("access_token", SecretRedactor.REDACTED);

        Document stored = executions().find(new Document("_id", "exec-1")).first();
        assertThat(stored.get("triggerPayload", Document.class))
                .containsEntry("signature", "sha256=abc");
    }

    /**
     * Disparar um workflow desativado e BAD_REQUEST, e nao cria execucao nenhuma.
     *
     * <p>A ausencia do registro e metade da afirmacao: um registro para um disparo que nunca comecou
     * polui o historico com linhas que nao representam trabalho.</p>
     */
    @Test
    void triggeringADisabledWorkflowIsRejectedAndLeavesNoExecutionBehind() {
        seedDefinition("wf-1", false);

        graphQlTester.document(TRIGGER_MUTATION)
                .variable("id", "wf-1")
                .variable("payload", Map.of("total", 150))
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.BAD_REQUEST.name());
                    assertThat(errors.getFirst().getMessage()).contains("desativado");
                });

        assertThat(executions().countDocuments()).isZero();
    }

    @Test
    void triggeringAnUnknownWorkflowIsNotFound() {
        graphQlTester.document(TRIGGER_MUTATION)
                .variable("id", "nao-existe")
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors.getFirst().getErrorType())
                        .hasToString(ErrorType.NOT_FOUND.name()));
    }

    /**
     * Um disparo de verdade grava a execucao e devolve o resultado da condicao.
     *
     * <p>Fecha o circuito inteiro: mutation GraphQL, caso de uso, engine, executor de condicao,
     * gravacao do passo e leitura de volta. O no CONDITION e o escolhido porque nao precisa de rede
     * -- um no HTTP dependeria de um servidor externo dentro de um teste de API.</p>
     */
    @Test
    void triggeringAWorkflowRunsItAndStoresTheExecution() {
        seedConditionWorkflow("wf-1");

        String executionId = graphQlTester.document(TRIGGER_MUTATION)
                .variable("id", "wf-1")
                .variable("payload", Map.of("total", 150))
                .execute()
                .path("triggerWorkflow.status").entity(String.class).isEqualTo("SUCCESS")
                .path("triggerWorkflow.steps[0].nodeId").entity(String.class).isEqualTo("checa")
                .path("triggerWorkflow.id").entity(String.class).get();

        Document stored = executions().find(new Document("_id", executionId)).first();
        assertThat(stored).isNotNull();
        assertThat(stored.getString("status")).isEqualTo("SUCCESS");
        assertThat(stored.getList("steps", Document.class)).hasSize(1);
    }

    /** O teto de deslocamento e do servidor: paginacao sem limite e uma varredura em quatro bytes. */
    @Test
    void rejectsAnOffsetAboveTheServerMaximum() {
        seedDefinition("wf-1");

        graphQlTester.document(EXECUTIONS_QUERY)
                .variable("workflowId", "wf-1")
                .variable("offset", 10_001)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors.getFirst().getErrorType())
                        .hasToString(ErrorType.BAD_REQUEST.name()));
    }

    private static final org.springframework.core.ParameterizedTypeReference<Map<String, Object>> MAP =
            new org.springframework.core.ParameterizedTypeReference<>() { };

    private List<Map<String, Object>> page(String workflowId, int limit, int offset) {
        return graphQlTester.document(EXECUTIONS_QUERY)
                .variable("workflowId", workflowId)
                .variable("limit", limit)
                .variable("offset", offset)
                .execute()
                .path("executions")
                .entityList(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { })
                .get();
    }

    private static List<String> idsOf(List<Map<String, Object>> executions) {
        return executions.stream().map(e -> (String) e.get("id")).toList();
    }

    private void seedDefinition(String id) {
        seedDefinition(id, true);
    }

    private void seedDefinition(String id, boolean enabled) {
        mongoTemplate.getCollection(DEFINITIONS_COLLECTION).insertOne(new Document("_id", id)
                .append("name", "workflow " + id)
                .append("enabled", enabled)
                .append("triggerConfig", new Document("type", "MOCK_EVENT").append("config", new Document()))
                .append("nodes", List.of())
                .append("version", 0L)
                .append("createdAt", Date.from(Instant.parse("2026-01-01T00:00:00Z")))
                .append("updatedAt", Date.from(Instant.parse("2026-01-01T00:00:00Z"))));
    }

    private void seedConditionWorkflow(String id) {
        mongoTemplate.getCollection(DEFINITIONS_COLLECTION).insertOne(new Document("_id", id)
                .append("name", "condicao")
                .append("enabled", true)
                .append("triggerConfig", new Document("type", "MOCK_EVENT").append("config", new Document()))
                .append("nodes", List.of(new Document("nodeId", "checa")
                        .append("type", "CONDITION")
                        .append("expression", "total > 100")
                        .append("config", new Document())))
                .append("startNodeId", "checa")
                .append("version", 0L)
                .append("createdAt", Date.from(Instant.parse("2026-01-01T00:00:00Z")))
                .append("updatedAt", Date.from(Instant.parse("2026-01-01T00:00:00Z"))));
    }

    private void seedExecution(String id, String workflowId, Instant createdAt) {
        executions().insertOne(new Document("_id", id)
                .append("workflowId", workflowId)
                .append("status", "SUCCESS")
                .append("triggerPayload", new Document())
                .append("steps", List.of())
                .append("createdAt", Date.from(createdAt))
                .append("version", 0L));
    }

    private MongoCollection<Document> executions() {
        return mongoTemplate.getCollection(EXECUTIONS_COLLECTION);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
