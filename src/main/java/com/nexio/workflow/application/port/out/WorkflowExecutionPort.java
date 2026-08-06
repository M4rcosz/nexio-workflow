package com.nexio.workflow.application.port.out;

import com.nexio.workflow.domain.model.WorkflowExecution;
import java.util.List;
import java.util.Optional;

/**
 * Porta de saida para persistencia de {@link WorkflowExecution}.
 *
 * <p>A interface expoe apenas tipos do JDK e do dominio: nenhum detalhe de infraestrutura
 * (Spring Data, MongoDB) pode vazar para a camada de aplicacao.</p>
 */
public interface WorkflowExecutionPort {

    /**
     * Persiste uma execucao, criando ou atualizando conforme o identificador.
     *
     * @param execution execucao a ser gravada
     * @return a execucao persistida, com versao atualizada
     */
    WorkflowExecution save(WorkflowExecution execution);

    /**
     * Busca uma execucao pelo identificador.
     *
     * @param id identificador da execucao
     * @return a execucao encontrada ou {@link Optional#empty()} quando nao existe
     */
    Optional<WorkflowExecution> findById(String id);

    /**
     * Lista as execucoes de uma definicao, da mais recente para a mais antiga.
     *
     * @param workflowId identificador da definicao de workflow
     * @return lista de execucoes, vazia quando nao ha registros
     */
    List<WorkflowExecution> findByWorkflowId(String workflowId);

    /**
     * Remove todas as execucoes de uma definicao, evitando execucoes orfas quando a definicao e apagada.
     *
     * @param workflowId identificador da definicao de workflow
     * @return quantidade de execucoes removidas
     */
    long deleteByWorkflowId(String workflowId);
}
