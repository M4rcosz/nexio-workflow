package com.nexio.workflow.infrastructure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.domain.model.TriggerConfig;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.NodeType;
import com.nexio.workflow.domain.model.enums.TriggerType;
import com.nexio.workflow.infrastructure.config.MongoConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Teste de integracao do {@link WorkflowDefinitionMongoAdapter} contra um MongoDB real
 * (Testcontainers).
 *
 * <p>O adaptador e injetado tipado como {@link WorkflowDefinitionPort}: o teste exercita o
 * contrato da porta, e nao a implementacao, entao qualquer troca de tecnologia de persistencia
 * continua tendo que passar por aqui.</p>
 *
 * <p>Nomeado {@code ...Test} e nao {@code ...IT} de proposito: o surefire so executa
 * {@code *Test.java} e este teste precisa rodar no {@code ./mvnw test}.</p>
 */
@Testcontainers
@DataMongoTest
@Import({MongoConfig.class, WorkflowDefinitionMongoAdapter.class})
class WorkflowDefinitionMongoAdapterTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    @Autowired
    private WorkflowDefinitionPort port;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanCollection() {
        mongoTemplate.remove(new Query(), WorkflowDefinition.class);
    }

    @Test
    void saveAndFindByIdRoundTripsTheWholeAggregate() {
        WorkflowDefinition saved = port.save(definition("wf-save", "cobranca diaria", true, TriggerType.SCHEDULE));

        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();

        Optional<WorkflowDefinition> found = port.findById("wf-save");

        assertThat(found).isPresent();
        WorkflowDefinition reread = found.orElseThrow();
        assertThat(reread.getName()).isEqualTo("cobranca diaria");
        assertThat(reread.isEnabled()).isTrue();
        assertThat(reread.getStartNodeId()).isEqualTo("start");
        assertThat(reread.getTriggerConfig().type()).isEqualTo(TriggerType.SCHEDULE);
        assertThat(reread.getNodes()).extracting(WorkflowNode::nodeId).containsExactly("start", "check", "done");
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertThat(port.findById("wf-inexistente")).isEmpty();
    }

    @Test
    void findAllReturnsEveryDefinition() {
        port.save(definition("wf-a", "a", true, TriggerType.MOCK_EVENT));
        port.save(definition("wf-b", "b", false, TriggerType.SCHEDULE));

        assertThat(port.findAll())
                .extracting(WorkflowDefinition::getId)
                .containsExactlyInAnyOrder("wf-a", "wf-b");
    }

    @Test
    void findEnabledReturnsOnlyEnabledDefinitions() {
        port.save(definition("wf-on-1", "habilitada 1", true, TriggerType.MOCK_EVENT));
        port.save(definition("wf-off", "desabilitada", false, TriggerType.MOCK_EVENT));
        port.save(definition("wf-on-2", "habilitada 2", true, TriggerType.SCHEDULE));

        assertThat(port.findEnabled())
                .extracting(WorkflowDefinition::getId)
                .containsExactlyInAnyOrder("wf-on-1", "wf-on-2");
    }

    @Test
    void existsByIdDistinguishesPresentFromAbsent() {
        port.save(definition("wf-exists", "existe", true, TriggerType.MOCK_EVENT));

        assertThat(port.existsById("wf-exists")).isTrue();
        assertThat(port.existsById("wf-nao-existe")).isFalse();
    }

    /**
     * Razao de ser da assinatura {@code boolean deleteById}: o chamador precisa distinguir
     * "removi" de "nao havia nada para remover" sem uma consulta extra.
     */
    @Test
    void deleteByIdReturnsTrueAndRemovesTheDocument() {
        port.save(definition("wf-delete", "para apagar", true, TriggerType.MOCK_EVENT));

        assertThat(port.deleteById("wf-delete")).isTrue();
        assertThat(port.findById("wf-delete")).isEmpty();
        assertThat(port.existsById("wf-delete")).isFalse();
    }

    @Test
    void deleteByIdReturnsFalseForUnknownId() {
        port.save(definition("wf-sobrevivente", "nao pode sumir", true, TriggerType.MOCK_EVENT));

        assertThat(port.deleteById("wf-nunca-existiu")).isFalse();
        assertThat(port.findAll()).extracting(WorkflowDefinition::getId).containsExactly("wf-sobrevivente");
    }

    @Test
    void deleteByIdRemovesOnlyTheTargetDocument() {
        port.save(definition("wf-alvo", "alvo", true, TriggerType.MOCK_EVENT));
        port.save(definition("wf-vizinho", "vizinho", true, TriggerType.MOCK_EVENT));

        assertThat(port.deleteById("wf-alvo")).isTrue();
        assertThat(port.deleteById("wf-alvo")).isFalse();
        assertThat(port.findAll()).extracting(WorkflowDefinition::getId).containsExactly("wf-vizinho");
    }

    private WorkflowDefinition definition(String id, String name, boolean enabled, TriggerType triggerType) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("x_trace", "abc-123");
        Map<String, Object> httpConfig = new LinkedHashMap<>();
        httpConfig.put("url", "https://example.test/hook");
        httpConfig.put("method", "POST");
        httpConfig.put("headers", headers);

        List<WorkflowNode> nodes = new ArrayList<>();
        nodes.add(new WorkflowNode("start", NodeType.HTTP_REQUEST, httpConfig, "check", null, null));
        nodes.add(new WorkflowNode("check", NodeType.CONDITION, Map.of("expr", "status == 200"),
                null, "done", "done"));
        nodes.add(new WorkflowNode("done", NodeType.HTTP_REQUEST, Map.of(), null, null, null));

        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(id);
        definition.setName(name);
        definition.setDescription("definicao usada no teste do adaptador");
        definition.setEnabled(enabled);
        definition.setTriggerConfig(new TriggerConfig(triggerType, Map.of("event", "order_created")));
        definition.setNodes(nodes);
        definition.setStartNodeId("start");
        definition.validateGraph();
        return definition;
    }
}
