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
     * Lista as definicoes cadastradas dentro do recorte informado.
     *
     * @param page recorte de paginacao, nunca nulo
     * @return lista de definicoes, vazia quando nao ha registros no recorte
     */
    List<WorkflowDefinition> findAll(PageQuery page);

    /**
     * Lista todas as definicoes habilitadas, usadas pelos gatilhos em tempo de execucao.
     *
     * <p>Unico metodo de listagem sem paginacao, de proposito: o agendador precisa registrar o cron
     * de todas as definicoes habilitadas de uma vez, e uma pagina qualquer significaria workflows
     * silenciosamente nunca disparados. A colecao tambem nao cresce por disparo -- ela e limitada
     * pelo numero de workflows que alguem cadastrou, e o teto de {@code MAX_NODES} limita o tamanho
     * de cada documento.</p>
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
