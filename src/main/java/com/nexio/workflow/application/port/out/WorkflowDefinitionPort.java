package com.nexio.workflow.application.port.out;

import com.nexio.workflow.domain.model.WorkflowDefinition;
import java.util.List;
import java.util.Optional;

/**
 * Porta de saida para persistencia de {@link WorkflowDefinition}.
 *
 * <p>A interface expoe apenas tipos do JDK e do dominio: nenhum detalhe de infraestrutura
 * (Spring Data, MongoDB) pode vazar para a camada de aplicacao.</p>
 */
public interface WorkflowDefinitionPort {

    /**
     * Persiste uma definicao, criando ou atualizando conforme o identificador.
     *
     * @param definition definicao a ser gravada
     * @return a definicao persistida, com campos de auditoria e versao atualizados
     */
    WorkflowDefinition save(WorkflowDefinition definition);

    /**
     * Busca uma definicao pelo identificador.
     *
     * @param id identificador da definicao
     * @return a definicao encontrada ou {@link Optional#empty()} quando nao existe
     */
    Optional<WorkflowDefinition> findById(String id);

    /**
     * Lista todas as definicoes cadastradas.
     *
     * @return lista de definicoes, vazia quando nao ha registros
     */
    List<WorkflowDefinition> findAll();

    /**
     * Lista somente as definicoes habilitadas, usadas pelos gatilhos em tempo de execucao.
     *
     * @return lista de definicoes habilitadas, vazia quando nao ha registros
     */
    List<WorkflowDefinition> findEnabled();

    /**
     * Remove uma definicao pelo identificador.
     *
     * @param id identificador da definicao
     * @return {@code true} quando a definicao existia e foi removida, {@code false} quando nao existia
     */
    boolean deleteById(String id);

    /**
     * Verifica a existencia de uma definicao sem carregar o documento completo.
     *
     * @param id identificador da definicao
     * @return {@code true} quando a definicao existe
     */
    boolean existsById(String id);
}
