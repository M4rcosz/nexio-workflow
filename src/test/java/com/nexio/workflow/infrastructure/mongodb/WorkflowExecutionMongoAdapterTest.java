package com.nexio.workflow.infrastructure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.nexio.workflow.application.port.out.PageQuery;
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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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
@Import({MongoConfig.class,
        WorkflowExecutionMongoAdapter.class,
        WorkflowExecutionWriteValidationCallback.class})
class WorkflowExecutionMongoAdapterTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    private static final Instant BASE = Instant.parse("2026-01-15T10:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private WorkflowExecutionPort port;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private WorkflowExecutionMongoRepository repository;

    @BeforeEach
    void cleanCollection() {
        mongoTemplate.remove(new Query(), WorkflowExecution.class);
    }

    @Test
    void saveAndFindByIdRoundTripsTheWholeAggregate() {
        WorkflowExecution saved = port.save(execution("exec-save", "wf-1", BASE));

        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();

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
        seed(execution("exec-meio", "wf-1", BASE.plus(1, ChronoUnit.HOURS)), BASE.plus(1, ChronoUnit.HOURS));
        seed(execution("exec-antigo", "wf-1", BASE), BASE);
        seed(execution("exec-recente", "wf-1", BASE.plus(2, ChronoUnit.HOURS)), BASE.plus(2, ChronoUnit.HOURS));
        seed(execution("exec-outro-wf", "wf-2", BASE.plus(3, ChronoUnit.HOURS)), BASE.plus(3, ChronoUnit.HOURS));

        List<WorkflowExecution> found = port.findByWorkflowId("wf-1", PageQuery.firstPage());

        assertThat(found)
                .extracting(WorkflowExecution::getId)
                .containsExactly("exec-recente", "exec-meio", "exec-antigo");
        assertThat(found).extracting(WorkflowExecution::getWorkflowId).containsOnly("wf-1");
    }

    /**
     * Uma execucao recem disparada ainda esta PENDING e tem {@code startedAt} nulo. Como o nulo
     * ordena como menor valor no MongoDB, ordenar por {@code startedAt} a jogava para o fim da
     * lista de "mais recentes primeiro" -- justamente a execucao que o usuario acabou de disparar e
     * esta esperando ver. A ordenacao passou a ser por {@code createdAt}, que existe desde o
     * primeiro save.
     *
     * <p>O teste anterior nao pegava isso porque so semeava execucoes que ja tinham passado por
     * {@code markRunning}.</p>
     */
    @Test
    void findByWorkflowIdPutsJustTriggeredPendingExecutionFirst() {
        seed(execution("exec-antigo", "wf-1", BASE), BASE);
        seed(execution("exec-recente", "wf-1", BASE.plus(2, ChronoUnit.HOURS)), BASE.plus(2, ChronoUnit.HOURS));
        WorkflowExecution pending = seed(pendingExecution("exec-recem-disparada", "wf-1"),
                BASE.plus(3, ChronoUnit.HOURS));

        assertThat(pending.getStartedAt()).isNull();
        assertThat(pending.getStatus()).isEqualTo(ExecutionStatus.PENDING);

        assertThat(port.findByWorkflowId("wf-1", PageQuery.firstPage()))
                .extracting(WorkflowExecution::getId)
                .containsExactly("exec-recem-disparada", "exec-recente", "exec-antigo");
    }

    /**
     * A colecao cresce um documento por disparo: a porta so devolve o recorte pedido.
     */
    @Test
    void findByWorkflowIdHonoursLimitAndOffset() {
        seed(execution("exec-1", "wf-1", BASE), BASE);
        seed(execution("exec-2", "wf-1", BASE.plus(1, ChronoUnit.HOURS)), BASE.plus(1, ChronoUnit.HOURS));
        seed(execution("exec-3", "wf-1", BASE.plus(2, ChronoUnit.HOURS)), BASE.plus(2, ChronoUnit.HOURS));

        assertThat(port.findByWorkflowId("wf-1", new PageQuery(2, 0)))
                .extracting(WorkflowExecution::getId)
                .containsExactly("exec-3", "exec-2");
        assertThat(port.findByWorkflowId("wf-1", new PageQuery(2, 2)))
                .extracting(WorkflowExecution::getId)
                .containsExactly("exec-1");
    }

    @Test
    void findByWorkflowIdReturnsEmptyForUnknownWorkflow() {
        port.save(execution("exec-unico", "wf-1", BASE));

        assertThat(port.findByWorkflowId("wf-sem-execucoes", PageQuery.firstPage())).isEmpty();
    }

    /**
     * Consulta declarada desde o inicio e nunca exercitada: o bootstrap do contexto so prova que
     * ela e derivavel, nao que ela casa com o campo certo.
     */
    @Test
    void findByStatusMatchesTheExecutionStatusField() {
        port.save(execution("exec-ok-1", "wf-1", BASE));
        port.save(execution("exec-ok-2", "wf-2", BASE));
        port.save(failedExecution("exec-falha", "wf-1", BASE));
        port.save(pendingExecution("exec-pendente", "wf-1"));

        assertThat(repository.findByStatus(ExecutionStatus.SUCCESS))
                .extracting(WorkflowExecution::getId)
                .containsExactlyInAnyOrder("exec-ok-1", "exec-ok-2");
        assertThat(repository.findByStatus(ExecutionStatus.FAILED))
                .extracting(WorkflowExecution::getId)
                .containsExactly("exec-falha");
        assertThat(repository.findByStatus(ExecutionStatus.PENDING))
                .extracting(WorkflowExecution::getId)
                .containsExactly("exec-pendente");
        assertThat(repository.findByStatus(ExecutionStatus.RUNNING)).isEmpty();
    }

    /**
     * O payload do gatilho e mapa livre vindo de fora: a politica estrita vale na escrita, aplicada
     * pelo callback de persistencia, independentemente de quem chamou o save.
     */
    @Test
    void saveRejectsOperatorKeysInTriggerPayloadThroughTheWriteCallback() {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId("exec-hostil");
        execution.setWorkflowId("wf-1");
        execution.setTriggerPayload(Map.of("$where", "1"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> port.save(execution))
                .withMessageContaining("'$'");
        assertThat(port.findById("exec-hostil")).isEmpty();
    }

    @Test
    void deleteByWorkflowIdReturnsTheCountAndSparesOtherWorkflows() {
        port.save(execution("exec-1", "wf-1", BASE));
        port.save(execution("exec-2", "wf-1", BASE.plus(1, ChronoUnit.HOURS)));
        port.save(execution("exec-3", "wf-2", BASE.plus(2, ChronoUnit.HOURS)));

        assertThat(port.deleteByWorkflowId("wf-1")).isEqualTo(2L);

        assertThat(port.findByWorkflowId("wf-1", PageQuery.firstPage())).isEmpty();
        assertThat(port.findById("exec-1")).isEmpty();
        assertThat(port.findByWorkflowId("wf-2", PageQuery.firstPage()))
                .extracting(WorkflowExecution::getId).containsExactly("exec-3");
    }

    @Test
    void deleteByWorkflowIdReturnsZeroForUnknownWorkflow() {
        port.save(execution("exec-sobrevivente", "wf-1", BASE));

        assertThat(port.deleteByWorkflowId("wf-nunca-existiu")).isZero();
        assertThat(port.findByWorkflowId("wf-1", PageQuery.firstPage())).hasSize(1);
    }

    /**
     * Persiste e carimba {@code createdAt} com um valor controlado.
     *
     * <p>A auditoria preenche {@code createdAt} com o relogio do momento do save, e varios saves
     * seguidos podem cair no mesmo milissegundo, o que deixaria a ordenacao ambigua. O carimbo
     * posterior torna o teste deterministico sem abrir mao de exercitar a consulta real.</p>
     */
    private WorkflowExecution seed(WorkflowExecution execution, Instant createdAt) {
        WorkflowExecution saved = port.save(execution);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(saved.getId())),
                new Update().set("createdAt", createdAt),
                WorkflowExecution.class);
        saved.setCreatedAt(createdAt);
        return saved;
    }

    private WorkflowExecution execution(String id, String workflowId, Instant startedAt) {
        WorkflowExecution execution = pendingExecution(id, workflowId);
        execution.markRunning(startedAt);
        execution.addStep(new ExecutionStep("start", StepStatus.SUCCESS, Map.of("statusCode", 200), null, startedAt));
        execution.markSucceeded(startedAt.plusSeconds(5));
        return execution;
    }

    private WorkflowExecution failedExecution(String id, String workflowId, Instant startedAt) {
        WorkflowExecution execution = pendingExecution(id, workflowId);
        execution.markRunning(startedAt);
        execution.addStep(new ExecutionStep("start", StepStatus.FAILED, Map.of(), "boom", startedAt));
        execution.markFailed("boom", startedAt.plusSeconds(5));
        return execution;
    }

    private WorkflowExecution pendingExecution(String id, String workflowId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", 42);
        payload.put("headers", Map.of("x_trace", "abc-123"));
        payload.put("tags", List.of("a", "b"));

        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(id);
        execution.setWorkflowId(workflowId);
        execution.setTriggerPayload(payload);
        return execution;
    }
}
