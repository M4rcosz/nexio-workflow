package com.nexio.workflow.domain.model;

import com.nexio.workflow.domain.model.enums.ExecutionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Registro de uma execucao de {@link WorkflowDefinition}, com o resultado de cada no percorrido.
 */
@Document(collection = "workflow_executions")
public class WorkflowExecution {

    @Id
    private String id;

    private String workflowId;

    private ExecutionStatus status;

    private Map<String, Object> triggerPayload = new LinkedHashMap<>();

    private List<ExecutionStep> steps = new ArrayList<>();

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

    public Map<String, Object> getTriggerPayload() {
        return triggerPayload;
    }

    public void setTriggerPayload(Map<String, Object> triggerPayload) {
        this.triggerPayload = triggerPayload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(triggerPayload);
    }

    public List<ExecutionStep> getSteps() {
        return steps;
    }

    public void setSteps(List<ExecutionStep> steps) {
        this.steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
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
