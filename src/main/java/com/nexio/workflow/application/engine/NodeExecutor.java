package com.nexio.workflow.application.engine;

import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.NodeType;

/**
 * Ponto de extensao da engine: executa um no de um tipo e devolve o desfecho.
 *
 * <p>Um tipo de no novo entra no sistema declarando um bean que implemente esta interface, sem
 * tocar na {@link WorkflowEngine}: a engine indexa os executores por {@link #supportedType()} uma
 * unica vez, na construcao, e depois so consulta o mapa. Nao ha {@code if} por tipo dentro da
 * caminhada, e nao ha varredura de lista por no executado.</p>
 *
 * <p>As implementacoes vivem na infraestrutura quando dependem dela -- o executor HTTP da issue #23
 * precisa do {@code RestClient} e do {@code HttpTargetValidator} --, e a dependencia aponta na
 * direcao certa: quem conhece a infraestrutura conhece esta interface, e nao o contrario.</p>
 */
public interface NodeExecutor {

    /**
     * Tipo de no que esta implementacao executa.
     *
     * <p>Um tipo e atendido por no maximo um executor: a engine recusa dois executores registrados
     * para o mesmo tipo na construcao, em vez de escolher um deles pela ordem em que o Spring
     * entregou a lista.</p>
     *
     * @return tipo atendido, nunca nulo
     */
    NodeType supportedType();

    /**
     * Executa um no.
     *
     * <p>O contrato pede que a falha esperada volte como {@link NodeExecutionResult#failure(String)}
     * e nao como excecao: o desfecho de um no e informacao de negocio que vira um passo registrado,
     * enquanto uma excecao atravessando a engine seria a execucao terminando sem registro nenhum.
     * A engine ainda assim embrulha qualquer {@code RuntimeException} que escape daqui no mesmo
     * desfecho de falha, porque um executor com defeito nao pode deixar a execucao sem desfecho --
     * mas isso e rede de seguranca, nao o caminho previsto.</p>
     *
     * @param node    no a executar, ja validado na escrita da definicao
     * @param context estado da execucao visivel para o no
     * @return desfecho do no, nunca nulo
     */
    NodeExecutionResult execute(WorkflowNode node, NodeExecutionContext context);
}
