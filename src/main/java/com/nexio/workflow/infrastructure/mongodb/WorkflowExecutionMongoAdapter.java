package com.nexio.workflow.infrastructure.mongodb;

import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.exception.WorkflowConcurrentlyModifiedException;
import com.nexio.workflow.domain.model.WorkflowExecution;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
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

    /**
     * {@inheritDoc}
     *
     * <p>A falha de bloqueio otimista e traduzida aqui, e nao repassada: a porta promete nao vazar
     * tipo do Spring Data, e {@code OptimisticLockingFailureException} e exatamente isso. A mensagem
     * original fica de fora porque carrega o nome da colecao e o filtro BSON cru da atualizacao que
     * falhou.</p>
     */
    @Override
    public WorkflowExecution save(WorkflowExecution execution) {
        try {
            return repository.save(execution);
        } catch (OptimisticLockingFailureException e) {
            throw new WorkflowConcurrentlyModifiedException(execution.getId(), e);
        }
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
     *
     * <p>O recorte vai sem ordem propria porque a consulta ja tem a dela no nome. Um {@code Sort} no
     * {@code Pageable} nao substituiria aquela ordem, seria somado a ela como desempate, e o par
     * {@code createdAt} decrescente mais {@code _id} deixaria de casar com o indice.</p>
     */
    @Override
    public List<WorkflowExecution> findByWorkflowId(String workflowId, PageQuery page) {
        return repository.findByWorkflowIdOrderByCreatedAtDesc(workflowId, OffsetPageable.unsorted(page));
    }

    @Override
    public long deleteByWorkflowId(String workflowId) {
        return repository.deleteByWorkflowId(workflowId);
    }
}
