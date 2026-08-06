package com.nexio.workflow.infrastructure.mongodb;

import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.model.WorkflowExecution;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Adaptador que implementa {@link WorkflowExecutionPort} sobre o Spring Data MongoDB.
 *
 * <p>Este e o unico ponto do sistema que conhece {@link WorkflowExecutionMongoRepository}: a
 * assinatura dos metodos publicos so expoe tipos do JDK e do dominio, entao nada do Spring Data
 * vaza para a camada de aplicacao.</p>
 */
@Repository
public class WorkflowExecutionMongoAdapter implements WorkflowExecutionPort {

    private final WorkflowExecutionMongoRepository repository;

    /**
     * Cria o adaptador com injecao por construtor.
     *
     * @param repository repositorio Spring Data das execucoes
     */
    public WorkflowExecutionMongoAdapter(WorkflowExecutionMongoRepository repository) {
        this.repository = repository;
    }

    @Override
    public WorkflowExecution save(WorkflowExecution execution) {
        return repository.save(execution);
    }

    @Override
    public Optional<WorkflowExecution> findById(String id) {
        return repository.findById(id);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A ordem decrescente por {@code createdAt} e a util para o historico de execucoes e e
     * servida pelo indice composto {@code exec_workflow_created} ja declarado na entidade. O
     * recorte do dominio vira um {@link OffsetPageable} aqui dentro: nenhum tipo do Spring Data
     * aparece na assinatura da porta.</p>
     */
    @Override
    public List<WorkflowExecution> findByWorkflowId(String workflowId, PageQuery page) {
        return repository.findByWorkflowIdOrderByCreatedAtDesc(workflowId, OffsetPageable.of(page));
    }

    @Override
    public long deleteByWorkflowId(String workflowId) {
        return repository.deleteByWorkflowId(workflowId);
    }
}
