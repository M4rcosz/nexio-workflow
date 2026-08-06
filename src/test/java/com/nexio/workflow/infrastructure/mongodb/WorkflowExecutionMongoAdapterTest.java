package com.nexio.workflow.infrastructure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.model.ExecutionStep;
import com.nexio.workflow.domain.model.WorkflowExecution;
import com.nexio.workflow.domain.model.enums.ExecutionStatus;
import com.nexio.workflow.domain.model.enums.StepStatus;
import com.nexio.workflow.infrastructure.config.MongoConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Teste de integracao do {@link WorkflowExecutionMongoAdapter} contra um MongoDB real
 * (Testcontainers).
 *
 * <p>O adaptador e injetado tipado como {@link WorkflowExecutionPort}: o teste exercita o contrato
 * da porta, e nao a implementacao.</p>
 *
 * <p>Nomeado {@code ...Test} e nao {@code ...IT} de proposito: o surefire so executa
 * {@code *Test.java} e este teste precisa rodar no {@code ./mvnw test}.</p>
 */
@Testcontainers
@DataMongoTest
@Import({MongoConfig.class, WorkflowExecutionMongoAdapter.class})
class WorkflowExecutionMongoAdapterTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    private static final Instant BASE = Instant.parse("2026-01-15T10:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private WorkflowExecutionPort port;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanCollection() {
        mongoTemplate.remove(new Query(), WorkflowExecution.class);
    }

    @Test
    void saveAndFindByIdRoundTripsTheWholeAggregate() {
        WorkflowExecution saved = port.save(execution("exec-save", "wf-1", BASE));

        assertThat(saved.getVersion()).isZero();

        Optional<WorkflowExecution> found = port.findById("exec-save");

        assertThat(found).isPresent();
        WorkflowExecution reread = found.orElseThrow();
        assertThat(reread.getWorkflowId()).isEqualTo("wf-1");
        assertThat(reread.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(reread.getStartedAt()).isEqualTo(BASE);
        assertThat(reread.getTriggerPayload()).containsEntry("orderId", 42);
        assertThat(reread.getSteps()).extracting(ExecutionStep::nodeId).containsExactly("start");
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertThat(port.findById("exec-inexistente")).isEmpty();
    }

    /**
     * Semeia fora de ordem de insercao de proposito: se o adaptador esquecesse a ordenacao, a
     * asercao cairia na ordem natural de leitura da colecao.
     */
    @Test
    void findByWorkflowIdReturnsOnlyThatWorkflowNewestFirst() {
        port.save(execution("exec-meio", "wf-1", BASE.plus(1, ChronoUnit.HOURS)));
        port.save(execution("exec-antigo", "wf-1", BASE));
        port.save(execution("exec-recente", "wf-1", BASE.plus(2, ChronoUnit.HOURS)));
        port.save(execution("exec-outro-wf", "wf-2", BASE.plus(3, ChronoUnit.HOURS)));

        List<WorkflowExecution> found = port.findByWorkflowId("wf-1");

        assertThat(found)
                .extracting(WorkflowExecution::getId)
                .containsExactly("exec-recente", "exec-meio", "exec-antigo");
        assertThat(found).extracting(WorkflowExecution::getWorkflowId).containsOnly("wf-1");
    }

    @Test
    void findByWorkflowIdReturnsEmptyForUnknownWorkflow() {
        port.save(execution("exec-unico", "wf-1", BASE));

        assertThat(port.findByWorkflowId("wf-sem-execucoes")).isEmpty();
    }

    @Test
    void deleteByWorkflowIdReturnsTheCountAndSparesOtherWorkflows() {
        port.save(execution("exec-1", "wf-1", BASE));
        port.save(execution("exec-2", "wf-1", BASE.plus(1, ChronoUnit.HOURS)));
        port.save(execution("exec-3", "wf-2", BASE.plus(2, ChronoUnit.HOURS)));

        assertThat(port.deleteByWorkflowId("wf-1")).isEqualTo(2L);

        assertThat(port.findByWorkflowId("wf-1")).isEmpty();
        assertThat(port.findById("exec-1")).isEmpty();
        assertThat(port.findByWorkflowId("wf-2")).extracting(WorkflowExecution::getId).containsExactly("exec-3");
    }

    @Test
    void deleteByWorkflowIdReturnsZeroForUnknownWorkflow() {
        port.save(execution("exec-sobrevivente", "wf-1", BASE));

        assertThat(port.deleteByWorkflowId("wf-nunca-existiu")).isZero();
        assertThat(port.findByWorkflowId("wf-1")).hasSize(1);
    }

    private WorkflowExecution execution(String id, String workflowId, Instant startedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", 42);
        payload.put("headers", Map.of("x_trace", "abc-123"));
        payload.put("tags", List.of("a", "b"));

        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(id);
        execution.setWorkflowId(workflowId);
        execution.setTriggerPayload(payload);
        execution.markRunning(startedAt);
        execution.addStep(new ExecutionStep("start", StepStatus.SUCCESS, Map.of("statusCode", 200), null, startedAt));
        execution.markSucceeded(startedAt.plusSeconds(5));
        return execution;
    }
}
