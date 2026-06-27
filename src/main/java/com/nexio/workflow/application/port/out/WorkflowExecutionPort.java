package com.nexio.workflow.application.port.out;

import com.nexio.workflow.domain.model.WorkflowExecution;
import java.util.List;
import java.util.Optional;

public interface WorkflowExecutionPort {
    WorkflowExecution save(WorkflowExecution execution);
    Optional<WorkflowExecution> findById(String id);
    List<WorkflowExecution> findAllByWorkflowDefinitionId(String workflowDefinitionId);
}
