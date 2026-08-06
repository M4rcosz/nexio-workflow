package com.nexio.workflow.domain.model;

import com.nexio.workflow.domain.model.enums.ExecutionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Registro de uma execucao de {@link WorkflowDefinition}, com o resultado de cada no percorrido.
 */
@Document(collection = "workflow_executions")
@CompoundIndex(name = "exec_workflow_started", def = "{'workflowId': 1, 'startedAt': -1}")
public class WorkflowExecution {

    /**
     * Numero maximo de passos registrados em uma execucao. Um ciclo no grafo faria o documento
     * crescer indefinidamente ate o limite de 16MB do BSON.
     */
    public static final int MAX_STEPS = 200;

    @Id
    private String id;

    private String workflowId;

    private ExecutionStatus status = ExecutionStatus.PENDING;

    private Map<String, Object> triggerPayload = new LinkedHashMap<>();

    private List<ExecutionStep> steps = new ArrayList<>();

    /**
     * Versao de bloqueio otimista. Tambem faz o Spring Data reconhecer o documento como novo
     * mesmo com id atribuido pela aplicacao.
     */
    @Version
    private Long version;

    private Instant startedAt;

    private Instant finishedAt;

    private String errorMessage;

    public WorkflowExecution() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    /**
     * Devolve o payload do gatilho como visao imutavel: o mapa interno nunca escapa.
     *
     * @return mapa imutavel com o payload do gatilho
     */
    public Map<String, Object> getTriggerPayload() {
        return Collections.unmodifiableMap(triggerPayload);
    }

    /**
     * Valida e copia em profundidade o payload do gatilho, que vem de fonte nao confiavel.
     *
     * @param triggerPayload payload recebido, pode ser nulo
     * @throws IllegalArgumentException quando o payload viola as regras de chaves, tipos ou limites
     */
    public void setTriggerPayload(Map<String, Object> triggerPayload) {
        this.triggerPayload = MapSanitizer.sanitize(triggerPayload, "triggerPayload");
    }

    /**
     * Devolve os passos como visao imutavel; use {@link #addStep(ExecutionStep)} para registrar novos.
     *
     * @return lista imutavel de passos
     */
    public List<ExecutionStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public void setSteps(List<ExecutionStep> steps) {
        this.steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
    }

    /**
     * Registra um passo executado.
     *
     * @param step passo a registrar
     * @throws IllegalStateException quando o limite de {@value #MAX_STEPS} passos e atingido
     */
    public void addStep(ExecutionStep step) {
        Objects.requireNonNull(step, "step nao pode ser nulo");
        if (steps.size() >= MAX_STEPS) {
            throw new IllegalStateException(
                    "Limite de " + MAX_STEPS + " passos atingido na execucao '" + id + "'");
        }
        steps.add(step);
    }

    /**
     * Marca a execucao como em andamento.
     *
     * @param startedAt momento de inicio
     */
    public void markRunning(Instant startedAt) {
        this.status = ExecutionStatus.RUNNING;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt nao pode ser nulo");
    }

    /**
     * Marca a execucao como concluida com sucesso.
     *
     * @param finishedAt momento de termino
     */
    public void markSucceeded(Instant finishedAt) {
        this.status = ExecutionStatus.SUCCESS;
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt nao pode ser nulo");
        this.errorMessage = null;
    }

    /**
     * Marca a execucao como falha.
     *
     * @param errorMessage mensagem de erro
     * @param finishedAt   momento de termino
     */
    public void markFailed(String errorMessage, Instant finishedAt) {
        this.status = ExecutionStatus.FAILED;
        this.errorMessage = Objects.requireNonNull(errorMessage, "errorMessage nao pode ser nulo");
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt nao pode ser nulo");
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
