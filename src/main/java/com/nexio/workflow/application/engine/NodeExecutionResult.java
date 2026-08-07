package com.nexio.workflow.application.engine;

import com.nexio.workflow.domain.model.ExecutionStep;
import com.nexio.workflow.domain.model.MapSanitizer;
import java.util.Map;
import java.util.Objects;

/**
 * O que um {@link NodeExecutor} devolve a engine: desfecho, dados produzidos e, quando falhou, o
 * motivo.
 *
 * <p>Os tres componentes existem porque a engine faz exatamente tres coisas com o retorno de um no:
 * escolhe a proxima aresta pelo {@link #outcome()}, monta o {@link ExecutionStep} com o
 * {@link #output()} e, no caminho de falha, compoe a mensagem da execucao com o {@link #error()}.
 * Nada alem disso e consultado, e por isso nada alem disso e pedido -- este e o contrato que as
 * issues #22 (CONDITION) e #23 (HTTP_REQUEST) vao implementar, e cada campo a mais aqui e um campo
 * que os dois executores precisam preencher sem que ninguem leia.</p>
 *
 * <p><b>O {@code output} acompanha tambem a falha</b>, e nao so o sucesso. Um no HTTP que recebeu
 * {@code 500} falhou e ao mesmo tempo tem a informacao mais util da execucao inteira -- o corpo e o
 * codigo da resposta. Amarrar o {@code output} ao sucesso jogaria fora justamente o diagnostico do
 * unico passo que alguem vai querer olhar depois.</p>
 *
 * <p>O mapa e copiado em profundidade pela politica <b>leniente</b> do {@link MapSanitizer}, e nao
 * pela estrita, pelo mesmo motivo dos records do dominio: aqui e copia defensiva, nao fronteira de
 * escrita. O {@code output} de um no HTTP e o corpo de um servico de terceiro, o dado menos
 * confiavel que este sistema manipula, e recusa-lo neste ponto perderia o passo inteiro em vez de
 * registrar a falha. Quem recusa e a gravacao, em {@code WorkflowExecutionPort.appendStep} --
 * ver {@code docs/adr/0004-execution-step-persistence.md}.</p>
 *
 * @param outcome desfecho do no, nunca nulo
 * @param output  dados produzidos pelo no, nunca nulo (vazio quando o no nao produz nada)
 * @param error   motivo da falha, nulo quando o desfecho nao e {@link NodeOutcome#FAILURE}
 */
public record NodeExecutionResult(NodeOutcome outcome, Map<String, Object> output, String error) {

    public NodeExecutionResult {
        Objects.requireNonNull(outcome, "outcome do resultado nao pode ser nulo");
        output = MapSanitizer.copy(output, "nodeResult.output");
    }

    /**
     * Resultado de um no nao condicional que cumpriu o que devia.
     *
     * @param output dados produzidos pelo no, pode ser nulo
     * @return resultado com desfecho {@link NodeOutcome#SUCCESS}
     */
    public static NodeExecutionResult success(Map<String, Object> output) {
        return new NodeExecutionResult(NodeOutcome.SUCCESS, output, null);
    }

    /**
     * Resultado de um no CONDITION avaliado sem erro.
     *
     * <p>A fabrica recebe o {@code boolean} que a expressao produziu e nao o {@link NodeOutcome}
     * correspondente: o executor de condicao tem em maos um booleano, e obriga-lo a traduzir e
     * abrir espaco para inverter os dois valores num {@code if} de uma linha.</p>
     *
     * @param value  valor da condicao avaliada
     * @param output dados produzidos pela avaliacao, pode ser nulo
     * @return resultado com desfecho {@link NodeOutcome#CONDITION_TRUE} ou
     *         {@link NodeOutcome#CONDITION_FALSE}
     */
    public static NodeExecutionResult condition(boolean value, Map<String, Object> output) {
        return new NodeExecutionResult(
                value ? NodeOutcome.CONDITION_TRUE : NodeOutcome.CONDITION_FALSE, output, null);
    }

    /**
     * Resultado de um no que falhou, sem dados a registrar.
     *
     * @param error motivo da falha
     * @return resultado com desfecho {@link NodeOutcome#FAILURE}
     */
    public static NodeExecutionResult failure(String error) {
        return failure(error, Map.of());
    }

    /**
     * Resultado de um no que falhou, preservando o que ele chegou a produzir.
     *
     * @param error  motivo da falha
     * @param output dados produzidos antes ou durante a falha, pode ser nulo
     * @return resultado com desfecho {@link NodeOutcome#FAILURE}
     */
    public static NodeExecutionResult failure(String error, Map<String, Object> output) {
        return new NodeExecutionResult(NodeOutcome.FAILURE, output, error);
    }

    /**
     * Indica se o desfecho e um dos dois ramos de uma condicao.
     *
     * @return {@code true} para {@link NodeOutcome#CONDITION_TRUE} e
     *         {@link NodeOutcome#CONDITION_FALSE}
     */
    public boolean isConditional() {
        return outcome == NodeOutcome.CONDITION_TRUE || outcome == NodeOutcome.CONDITION_FALSE;
    }
}
