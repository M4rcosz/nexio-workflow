package com.nexio.workflow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.nexio.workflow.AbstractMongoIntegrationTest;
import com.nexio.workflow.WriteValidationTestConfig;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.model.enums.ExecutionStatus;
import com.nexio.workflow.domain.model.enums.HttpMethod;
import com.nexio.workflow.domain.model.enums.NodeType;
import com.nexio.workflow.domain.model.enums.StepStatus;
import com.nexio.workflow.domain.model.enums.TriggerType;
import com.nexio.workflow.infrastructure.config.MongoConfig;
import com.nexio.workflow.infrastructure.mongodb.WorkflowDefinitionWriteValidationCallback;
import com.nexio.workflow.infrastructure.mongodb.WorkflowExecutionWriteValidationCallback;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Teste de ida e volta do mapeamento do dominio contra um MongoDB real (Testcontainers).
 *
 * <p>As asercoes olham o {@link Document} BSON cru, e nao apenas a entidade relida, porque as
 * duvidas levantadas na revisao do modelo so podem ser respondidas pelo documento efetivamente
 * gravado.</p>
 *
 * <p>Nomeado {@code ...Test} e nao {@code ...IT} de proposito: o surefire so executa
 * {@code *Test.java} e este teste precisa rodar no {@code ./mvnw test}.</p>
 */
@DataMongoTest
@Import({MongoConfig.class,
        WriteValidationTestConfig.class,
        WorkflowDefinitionWriteValidationCallback.class,
        WorkflowExecutionWriteValidationCallback.class})
class DomainMappingRoundTripTest extends AbstractMongoIntegrationTest {

    private static final String DEFINITIONS = DEFINITIONS_COLLECTION;
    private static final String EXECUTIONS = EXECUTIONS_COLLECTION;

    @Autowired
    private MongoTemplate mongoTemplate;

    /**
     * Documenta o nome de campo com que {@code WorkflowNode.id} e realmente gravado dentro do array
     * embutido {@code nodes}.
     *
     * <p>RESULTADO OBSERVADO: o campo e gravado como <b>{@code _id}</b>, e nao como {@code id}.
     * O {@code BasicMongoPersistentProperty} trata qualquer propriedade chamada {@code id} como
     * propriedade de identidade, inclusive em tipos embutidos que nao sao {@code @Document}, e
     * reescreve o nome do campo para {@code _id}. Enquanto o componente do record se chamava
     * {@code id}, os nos eram gravados como {@code nodes._id} e qualquer consulta por
     * {@code nodes.id} casava zero documentos silenciosamente. Por isso o componente foi renomeado
     * para {@code nodeId}. Este teste trava a correcao: o campo tem que ser gravado com o proprio
     * nome e {@code _id} nao pode aparecer dentro do array embutido.</p>
     */
    @Test
    void embeddedNodeIdIsPersistedUnderItsOwnName() {
        WorkflowDefinition definition = sampleDefinition("wf-node-id");
        definition.validateGraph();
        mongoTemplate.save(definition);

        Document raw = rawById(DEFINITIONS, "wf-node-id");
        assertThat(raw).isNotNull();
        List<Document> nodes = raw.getList("nodes", Document.class);

        assertThat(nodes).hasSize(3);
        assertThat(nodes.getFirst().keySet()).contains("nodeId").doesNotContain("_id");
        assertThat(nodes.getFirst().getString("nodeId")).isEqualTo("start");

        // A consulta por nodes.nodeId agora acha; nenhum no e gravado sob _id.
        assertThat(mongoTemplate.getCollection(DEFINITIONS)
                .countDocuments(new Document("_id", "wf-node-id").append("nodes.nodeId", "start"))).isOne();
        assertThat(mongoTemplate.getCollection(DEFINITIONS)
                .countDocuments(new Document("_id", "wf-node-id").append("nodes._id", "start"))).isZero();

        WorkflowDefinition reread = mongoTemplate.findById("wf-node-id", WorkflowDefinition.class);
        assertThat(reread).isNotNull();
        assertThat(reread.getNodes()).extracting(WorkflowNode::nodeId).containsExactly("start", "check", "done");
    }

    /**
     * Com o {@code DefaultMongoTypeMapper(null)} registrado no MongoConfig, nenhum hint
     * {@code _class} pode sobrar no documento, em nenhum nivel de aninhamento.
     */
    @Test
    void noClassHintIsWrittenAnywhere() {
        WorkflowDefinition definition = sampleDefinition("wf-no-class");
        definition.validateGraph();
        mongoTemplate.save(definition);
        mongoTemplate.save(sampleExecution("exec-no-class"));

        assertNoClassHint(rawById(DEFINITIONS, "wf-no-class"), "definition");
        assertNoClassHint(rawById(EXECUTIONS, "exec-no-class"), "execution");
    }

    /**
     * Com {@code @Version}, o Spring Data reconhece o documento como novo mesmo com id atribuido
     * pela aplicacao, e a auditoria dispara.
     */
    @Test
    void auditingFiresWithApplicationAssignedId() {
        WorkflowDefinition definition = sampleDefinition("wf-auditing");
        definition.validateGraph();

        WorkflowDefinition saved = mongoTemplate.save(definition);

        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Document raw = rawById(DEFINITIONS, "wf-auditing");
        assertThat(raw).isNotNull();
        assertThat(raw.getDate("createdAt")).isNotNull();
        assertThat(raw.getDate("updatedAt")).isNotNull();
        assertThat(((Number) raw.get("version")).longValue()).isZero();

        Instant createdAt = saved.getCreatedAt();
        saved.setDescription("descricao alterada");
        WorkflowDefinition updated = mongoTemplate.save(saved);

        assertThat(updated.getVersion()).isOne();
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void workflowExecutionRoundTripsScalarFieldsAndMaps() {
        WorkflowExecution execution = sampleExecution("exec-round-trip");
        WorkflowExecution saved = mongoTemplate.save(execution);

        Document raw = rawById(EXECUTIONS, "exec-round-trip");
        assertThat(raw).isNotNull();
        assertThat(raw.getString("_id")).isEqualTo("exec-round-trip");
        assertThat(raw.getString("status")).isEqualTo("SUCCESS");
        assertThat(raw.get("triggerPayload", Document.class).getInteger("orderId")).isEqualTo(42);
        assertThat(raw.getList("steps", Document.class)).hasSize(1);

        WorkflowExecution reread = mongoTemplate.findById("exec-round-trip", WorkflowExecution.class);
        assertThat(reread).isNotNull();
        assertThat(reread.getId()).isEqualTo(saved.getId());
        assertThat(reread.getWorkflowId()).isEqualTo("wf-round-trip");
        assertThat(reread.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(reread.getVersion()).isZero();
        assertThat(reread.getStartedAt()).isEqualTo(saved.getStartedAt());
        assertThat(reread.getFinishedAt()).isEqualTo(saved.getFinishedAt());
        assertThat(reread.getErrorMessage()).isNull();

        Map<String, Object> payload = reread.getTriggerPayload();
        assertThat(payload).containsEntry("orderId", 42);
        assertThat(payload.get("headers")).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("x_trace", "abc-123");
        assertThat(payload.get("tags")).asInstanceOf(InstanceOfAssertFactories.list(String.class))
                .containsExactly("a", "b");

        assertThat(reread.getSteps()).hasSize(1);
        ExecutionStep step = reread.getSteps().getFirst();
        assertThat(step.nodeId()).isEqualTo("start");
        assertThat(step.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(step.output()).containsEntry("statusCode", 200);
        assertThat(step.executedAt()).isEqualTo(saved.getSteps().getFirst().executedAt());
    }

    /**
     * As anotacoes {@code @CompoundIndex} so viram indice porque
     * {@code spring.data.mongodb.auto-index-creation} foi ligado no perfil {@code test}.
     *
     * <p>Alem dos dois indices originais, dois foram acrescentados para consultas derivadas que
     * varriam a colecao inteira: {@code def_trigger_type}, porque {@code triggerConfig.type} nao e
     * prefixo de {@code def_enabled_trigger} e portanto nao era servido por ele, e
     * {@code exec_status_created}, para {@code findByStatus} numa colecao que cresce sem teto.</p>
     */
    @Test
    void compoundIndexesAreCreated() {
        mongoTemplate.save(sampleExecution("exec-index"));
        WorkflowDefinition definition = sampleDefinition("wf-index");
        definition.validateGraph();
        mongoTemplate.save(definition);

        assertThat(indexNames(DEFINITIONS)).contains("def_enabled_trigger", "def_trigger_type");
        assertThat(indexNames(EXECUTIONS)).contains("exec_workflow_created_id", "exec_status_created");

        assertThat(indexKeys(DEFINITIONS, "def_trigger_type"))
                .isEqualTo(new Document("triggerConfig.type", 1));
        assertThat(indexKeys(EXECUTIONS, "exec_status_created"))
                .isEqualTo(new Document("status", 1).append("createdAt", 1));
    }

    /**
     * Responde, contra um MongoDB real, se o Spring Data usa o setter publico
     * {@code setTriggerPayload} na hidratacao ou se escreve direto no campo.
     *
     * <p>RESULTADO OBSERVADO: escreve <b>direto no campo</b>. Este documento e inserido cru, com
     * chaves que a politica estrita rejeita ({@code $where}, {@code _class}, {@code weird key!!}),
     * e mesmo assim {@code findById} devolve a entidade com o payload intacto -- prova de que o
     * setter (e portanto o {@code MapSanitizer}) nao roda na leitura. O Spring Data so usa
     * acessores quando a propriedade e anotada com {@code @AccessType(PROPERTY)}; o padrao e acesso
     * direto ao campo.</p>
     *
     * <p>Os construtores compactos dos records, ao contrario, <b>rodam</b> na hidratacao: e por
     * isso que {@code ExecutionStep.output} tambem precisa da copia leniente. Com a regra estrita
     * ali, este mesmo documento derrubava {@code findById} e, junto com ele, qualquer
     * {@code findAll}/{@code findByWorkflowId} que o incluisse -- sem nenhum caminho pela aplicacao
     * para ler ou corrigir o registro.</p>
     */
    @Test
    void malformedDocumentsStayReadableBecauseHydrationIsLenient() {
        Document hostilePayload = new Document("$where", "1")
                .append("_class", "java.lang.String")
                .append("weird key!!", 1);
        Document raw = new Document("_id", "exec-malformado")
                .append("workflowId", "wf-malformado")
                .append("status", "PENDING")
                .append("triggerPayload", hostilePayload)
                .append("steps", List.of(new Document("nodeId", "start")
                        .append("status", "SUCCESS")
                        .append("output", new Document("$bad", 1))))
                .append("version", 0L);
        mongoTemplate.getCollection(EXECUTIONS).insertOne(raw);
        mongoTemplate.save(sampleExecution("exec-vizinho"));

        WorkflowExecution reread = mongoTemplate.findById("exec-malformado", WorkflowExecution.class);

        assertThat(reread).isNotNull();
        assertThat(reread.getTriggerPayload())
                .containsEntry("$where", "1")
                .containsEntry("_class", "java.lang.String")
                .containsEntry("weird key!!", 1);
        assertThat(reread.getSteps()).hasSize(1);
        assertThat(reread.getSteps().getFirst().output()).containsEntry("$bad", 1);

        // e o documento malformado nao contamina a listagem: os vizinhos continuam legiveis
        assertThat(mongoTemplate.findAll(WorkflowExecution.class))
                .extracting(WorkflowExecution::getId)
                .contains("exec-malformado", "exec-vizinho");
    }

    /**
     * A escrita, ao contrario da leitura, e estrita: o callback de persistencia recusa o payload
     * com chave de operador antes de qualquer coisa chegar ao banco.
     *
     * <p>O tipo esperado e {@link InvalidWorkflowException} e nao {@code IllegalArgumentException}
     * de proposito, e a asercao de tipo aqui e o ponto do teste tanto quanto a de mensagem: o
     * callback roda dentro de {@code port.save(...)}, fora do try/catch dos casos de uso, entao
     * enquanto ele lancava a excecao generica toda violacao de chave virava INTERNAL_ERROR para o
     * cliente -- erro de entrada reportado como falha de servidor. E este o tipo que o
     * {@code WorkflowExceptionResolver} mapeia para BAD_REQUEST.</p>
     */
    @Test
    void writesAreStrictEvenThoughReadsAreLenient() {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId("exec-escrita-estrita");
        execution.setWorkflowId("wf-round-trip");
        execution.setTriggerPayload(Map.of("$where", "1"));

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> mongoTemplate.save(execution))
                .withMessageContaining("'$'");
        assertThat(rawById(EXECUTIONS, "exec-escrita-estrita")).isNull();
    }

    /**
     * O grafo passou a ser validado no proprio caminho de persistencia: antes disso
     * {@code validateGraph()} nao tinha nenhum chamador de producao e um grafo ciclico podia ser
     * gravado sem que nada reclamasse.
     */
    @Test
    void cyclicGraphCannotBePersisted() {
        WorkflowDefinition definition = sampleDefinition("wf-ciclico");
        definition.setNodes(List.of(
                WorkflowNode.httpRequest("start", "https://exemplo.test", HttpMethod.GET, null, null, "check"),
                WorkflowNode.httpRequest("check", "https://exemplo.test/c", HttpMethod.GET, null, null, "start")));

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> mongoTemplate.save(definition))
                .withMessageContaining("ciclo");
        assertThat(rawById(DEFINITIONS, "wf-ciclico")).isNull();
    }

    /**
     * A auditoria preenche {@code createdAt} ja no primeiro save, ainda PENDING, que e o que torna
     * possivel ordenar o historico por ele em vez de por {@code startedAt}.
     */
    @Test
    void executionGetsCreatedAtOnTheVeryFirstSave() {
        WorkflowExecution pending = new WorkflowExecution();
        pending.setId("exec-pendente");
        pending.setWorkflowId("wf-round-trip");

        WorkflowExecution saved = mongoTemplate.save(pending);

        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(saved.getStartedAt()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(rawById(EXECUTIONS, "exec-pendente").getDate("createdAt")).isNotNull();
    }

    private List<String> indexNames(String collection) {
        List<String> names = new ArrayList<>();
        mongoTemplate.getCollection(collection).listIndexes().forEach(index -> names.add(index.getString("name")));
        return names;
    }

    private Document indexKeys(String collection, String indexName) {
        for (Document index : mongoTemplate.getCollection(collection).listIndexes()) {
            if (indexName.equals(index.getString("name"))) {
                return index.get("key", Document.class);
            }
        }
        return null;
    }

    private void assertNoClassHint(Document raw, String label) {
        assertThat(raw).isNotNull();
        assertNoClassHint(raw, label, raw.toJson());
    }

    private void assertNoClassHint(Object value, String path, String json) {
        switch (value) {
            case Document document -> document.forEach((key, child) -> {
                assertThat(key)
                        .withFailMessage("hint _class encontrado em '%s' do documento %s", path, json)
                        .isNotEqualTo("_class");
                assertNoClassHint(child, path + "." + key, json);
            });
            case List<?> list -> {
                for (int i = 0; i < list.size(); i++) {
                    assertNoClassHint(list.get(i), path + "[" + i + "]", json);
                }
            }
            default -> {
                // valores escalares nao carregam hint de tipo
            }
        }
    }

    private Document rawById(String collection, String id) {
        return mongoTemplate.getCollection(collection).find(new Document("_id", id)).first();
    }

    private WorkflowDefinition sampleDefinition(String id) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("x_trace", "abc-123");
        Map<String, Object> httpConfig = new LinkedHashMap<>();
        httpConfig.put("url", "https://example.test/hook");
        httpConfig.put("method", "POST");
        httpConfig.put("headers", headers);

        List<WorkflowNode> nodes = new ArrayList<>();
        nodes.add(WorkflowNode.httpRequest(
                "start", "https://example.test/hook", HttpMethod.POST, headers, null, "check"));
        nodes.add(WorkflowNode.condition("check", "status == 200", "done", "done"));
        nodes.add(WorkflowNode.httpRequest(
                "done", "https://example.test/done", HttpMethod.GET, null, null, null));

        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(id);
        definition.setName("round trip");
        definition.setDescription("definicao usada no teste de mapeamento");
        definition.setEnabled(true);
        definition.setTriggerConfig(new TriggerConfig(TriggerType.MOCK_EVENT, Map.of("event", "order_created")));
        definition.setNodes(nodes);
        definition.setStartNodeId("start");
        return definition;
    }

    private WorkflowExecution sampleExecution(String id) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", 42);
        payload.put("headers", Map.of("x_trace", "abc-123"));
        payload.put("tags", List.of("a", "b"));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(id);
        execution.setWorkflowId("wf-round-trip");
        execution.setTriggerPayload(payload);
        execution.markRunning(now);
        execution.addStep(new ExecutionStep("start", StepStatus.SUCCESS, Map.of("statusCode", 200), null, now));
        execution.markSucceeded(now);
        return execution;
    }
}
