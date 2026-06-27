package com.nexio.workflow.application.port.out;

import com.nexio.workflow.domain.model.WorkflowDefinition;
import java.util.List;
import java.util.Optional;

public interface WorkflowDefinitionPort {
    WorkflowDefinition save(WorkflowDefinition workflow);
    Optional<WorkflowDefinition> findById(String id);
    List<WorkflowDefinition> findAllByTenantId(String tenantId);
    List<WorkflowDefinition> findAllActiveByTriggerType(String triggerType);
    void deleteById(String id);
}
