package com.nexio.workflow.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Definicao de um workflow: o gatilho e o grafo de nos que sera executado.
 */
@Document(collection = "workflow_definitions")
public class WorkflowDefinition {

    @Id
    private String id;

    private String name;

    private String description;

    private boolean enabled;

    private TriggerConfig triggerConfig;

    private List<WorkflowNode> nodes = new ArrayList<>();

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public WorkflowDefinition() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public TriggerConfig getTriggerConfig() {
        return triggerConfig;
    }

    public void setTriggerConfig(TriggerConfig triggerConfig) {
        this.triggerConfig = triggerConfig;
    }

    public List<WorkflowNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<WorkflowNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
