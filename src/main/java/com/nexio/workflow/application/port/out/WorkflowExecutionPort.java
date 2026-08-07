package com.nexio.workflow.application.port.out;

import com.nexio.workflow.domain.model.ExecutionStep;
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
     * Acrescenta um passo a uma execucao ja gravada, sem reescrever o restante do documento.
     *
     * <p>Existe como metodo proprio porque a alternativa -- {@code execution.addStep(...)} seguido
     * de {@code save} a cada no -- reescreve o documento inteiro, inclusive todos os passos
     * anteriores, uma vez por no: o custo e quadratico no numero de nos. Ver
     * {@code docs/adr/0004-execution-step-persistence.md}.</p>
     *
     * <p><b>A implementacao e obrigada a validar o passo antes de grava-lo</b>, aplicando a politica
     * estrita de {@code MapSanitizer} sobre o {@code output} e o teto de
     * {@code WorkflowExecution.MAX_STEPS}. Nao e detalhe de implementacao: e o preco de nao passar
     * pelo caminho de {@code save}, que era o unico ponto por onde toda escrita da execucao passava.
     * O {@code output} de um passo e a resposta de um servico de terceiro, o dado menos confiavel
     * que chega a gravacao neste projeto.</p>
     *
     * <p>Nao devolve a execucao atualizada de proposito: devolver o documento reescrito traria de
     * volta o custo que este metodo existe para evitar. Quem precisa do estado depois releia.</p>
     *
     * @param executionId identificador da execucao
     * @param step        passo a acrescentar
     * @throws com.nexio.workflow.domain.exception.InvalidWorkflowException quando o passo viola a
     *         politica de escrita ou quando a execucao ja atingiu o teto de passos
     * @throws com.nexio.workflow.domain.exception.WorkflowNotFoundException quando nao existe
     *         execucao com esse identificador
     */
    void appendStep(String executionId, ExecutionStep step);

    /**
     * Busca uma execucao pelo identificador.
     *
     * @param id identificador da execucao
     * @return a execucao encontrada ou {@link Optional#empty()} quando nao existe
     */
    Optional<WorkflowExecution> findById(String id);

    /**
     * Lista as execucoes de uma definicao dentro do recorte informado, da mais recente para a mais
     * antiga.
     *
     * <p>A paginacao e obrigatoria porque esta colecao cresce um documento por disparo, sem teto.</p>
     *
     * @param workflowId identificador da definicao de workflow
     * @param page       recorte de paginacao, nunca nulo
     * @return lista de execucoes, vazia quando nao ha registros no recorte
     */
    List<WorkflowExecution> findByWorkflowId(String workflowId, PageQuery page);

    /**
     * Remove todas as execucoes de uma definicao, evitando execucoes orfas quando a definicao e apagada.
     *
     * @param workflowId identificador da definicao de workflow
     * @return quantidade de execucoes removidas
     */
    long deleteByWorkflowId(String workflowId);
}
