package com.nexio.workflow.application.engine;

import com.nexio.workflow.domain.model.ConditionExpressionValidator;
import com.nexio.workflow.domain.model.TextSanitizer;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.NodeType;
import java.util.Map;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.ExpressionException;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * Avalia a expressao de um no CONDITION e devolve o ramo escolhido.
 *
 * <p>O resultado nunca e "verdadeiro por padrao": ou a expressao produz um booleano, ou o no falha.
 * Ver {@link #execute(WorkflowNode, NodeExecutionContext)} para o porque de a falha nao virar
 * simplesmente o ramo falso.</p>
 *
 * <h2>O que a expressao enxerga</h2>
 *
 * <p>O objeto raiz e o payload do gatilho, entao o caso comum se escreve direto:</p>
 *
 * <pre>{@code
 * total > 100 and status == 'pago'
 * }</pre>
 *
 * <p>A saida de um no ja executado vem pela variavel {@code #outputs}, indexada pelo id do no:</p>
 *
 * <pre>{@code
 * #outputs['consulta-cliente']['statusCode'] == 200
 * }</pre>
 *
 * <p>{@code #trigger} tambem existe e e o mesmo mapa da raiz. Serve para desambiguar quando o
 * payload tem uma chave que se confunde com outra coisa, e para quem prefere escrever de onde o
 * dado veio.</p>
 *
 * <h2>Por que este contexto de avaliacao</h2>
 *
 * <p>{@link SimpleEvaluationContext} nao resolve tipo, nao chama construtor e nao invoca metodo:
 * {@code T(java.lang.Runtime).getRuntime()} e {@code ''.getClass()} morrem aqui, que e a decisao
 * central da ADR 0002 e a razao de nunca usarmos {@code StandardEvaluationContext}.</p>
 *
 * <p>O acessor e {@code new MapAccessor(false)} -- o {@code false} e o que impede escrita. Sem
 * acessor nenhum, {@code forReadOnlyDataBinding()} nao resolveria {@code total} contra um
 * {@code Map} (ele so le propriedade de bean), e o unico jeito de ler o payload seria
 * {@code #trigger['total']}. O acessor esta aqui para que a forma curta funcione, e nao para
 * afrouxar nada: a lista de permissao de construtos continua sendo a mesma, aplicada na escrita.</p>
 *
 * <p><b>O que protege do custo da avaliacao nao esta neste arquivo.</b> Esta em
 * {@code ConditionExpressionValidator}, que roda na gravacao da definicao e recusa selecao,
 * projecao, lista literal, {@code matches} e todo o resto que itera. Sem ele, 84 caracteres de
 * selecao aninhada avaliam por 49 segundos. Por isso o executor pode parsear e avaliar sem relogio
 * nenhum.</p>
 */
public class ConditionNodeExecutor implements NodeExecutor {

    /**
     * Teto do trecho de mensagem de erro do SpEL repassado adiante.
     *
     * <p>A mensagem entra no passo e na {@code errorMessage} da execucao, que hoje qualquer um le.
     * As mensagens do SpEL citam o nome da propriedade que a expressao pediu e o tipo do objeto,
     * nao o conteudo do payload, mas o corte existe para que uma mensagem futura mais falante nao
     * vire um vazamento por acidente.</p>
     */
    private static final int MAX_ERROR_LENGTH = 200;

    /**
     * O acessor nao guarda estado e pode ser compartilhado; o contexto, que guarda as variaveis de
     * cada execucao, e novo a cada no.
     */
    private static final MapAccessor READ_ONLY_MAP_ACCESS = new MapAccessor(false);

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    @Override
    public NodeType supportedType() {
        return NodeType.CONDITION;
    }

    /**
     * Avalia a expressao do no no estado atual da execucao.
     *
     * <p>Tres desfechos, e o terceiro e o que merece explicacao:</p>
     * <ul>
     *   <li>expressao produz {@code true} ou {@code false}: o desfecho e o ramo correspondente;</li>
     *   <li>expressao produz outra coisa -- um numero, um texto, {@code null} porque a chave nao
     *       existe no payload: o no <b>falha</b>;</li>
     *   <li>a avaliacao estoura (comparar {@code null} com numero, dividir por zero, indexar o que
     *       nao e mapa): o no <b>falha</b>.</li>
     * </ul>
     *
     * <p>Nos dois ultimos a tentacao e tratar como "condicao falsa" e seguir pelo ramo
     * {@code nextOnFalse}. Seria pior de longe: uma condicao quebrada e um erro de configuracao, e
     * mandar a execucao pelo ramo falso a faz parecer que decidiu. Um workflow que envia cobranca
     * quando {@code total > 100} e falha em ler {@code total} passaria a executar em silencio o
     * ramo do "nao cobrar", indistinguivel de um pedido barato de verdade -- e o historico nao
     * registraria nada errado. Falhar deixa o passo com o motivo escrito e a execucao FAILED.</p>
     *
     * <p>O {@code output} do passo guarda o booleano avaliado. E o unico dado que o no produz e e o
     * que responde "por que a execucao foi por aqui" quando alguem abre o historico depois.</p>
     *
     * <p>O parse acontece aqui e nao na leitura da definicao. Ele custa microssegundos e, mais
     * importante, precisa acontecer de novo: uma definicao gravada antes de a regra de gramatica
     * existir pode conter expressao que hoje seria recusada, entao o {@link ParseException} e
     * tratado como falha de no em vez de ser assumido impossivel.</p>
     *
     * @param node    no CONDITION a avaliar
     * @param context estado da execucao visivel para o no
     * @return o ramo escolhido, ou falha com o motivo
     */
    @Override
    public NodeExecutionResult execute(WorkflowNode node, NodeExecutionContext context) {
        SimpleEvaluationContext evaluationContext =
                SimpleEvaluationContext.forPropertyAccessors(READ_ONLY_MAP_ACCESS).build();
        evaluationContext.setVariable("trigger", context.triggerPayload());
        evaluationContext.setVariable("outputs", context.outputs());

        Object value;
        try {
            // A gramatica e conferida de novo aqui, e nao so na escrita. O Javadoc abaixo ja
            // explicava que uma definicao gravada antes da regra pode conter expressao hoje
            // recusada -- mas o codigo so tratava o caso de ela nao compilar. Uma expressao antiga
            // com selecao aninhada compila perfeitamente e avalia por 49 segundos, a cada disparo,
            // numa thread de requisicao, sem nada que a interrompa. E o mesmo raciocinio que faz a
            // engine manter o teto de passos apesar de a escrita ja recusar ciclo: o anteparo de
            // execucao nao pode depender do que ja esta no banco. Custa uma caminhada na arvore de
            // uma expressao de no maximo 512 caracteres.
            ConditionExpressionValidator.validate(node.expression(), node.nodeId());
            value = PARSER.parseExpression(node.expression())
                    .getValue(evaluationContext, context.triggerPayload());
        } catch (RuntimeException e) {
            // O catch e de RuntimeException, e nao so de ParseException e EvaluationException,
            // porque o SpEL nao embrulha tudo o que a avaliacao pode lancar: `total / 0` sai como
            // ArithmeticException crua, sem passar por SpelEvaluationException -- descoberto por
            // teste, nao por leitura. E alcancavel com `total / quantidade` e um payload com
            // quantidade zero, ou seja, por dado de entrada comum. A engine ainda embrulharia isso
            // na rede de seguranca dela, mas o resultado seria "Falha interna na execucao:
            // ArithmeticException", que nao diz qual no nem o que houve. O contrato do
            // NodeExecutor pede que a falha esperada volte como failure(), e divisao por zero numa
            // condicao e falha esperada.
            return NodeExecutionResult.failure(
                    "Nao foi possivel avaliar a condicao do no '" + node.nodeId() + "': "
                            + describe(e));
        }
        if (!(value instanceof Boolean result)) {
            return NodeExecutionResult.failure(
                    "A condicao do no '" + node.nodeId() + "' produziu "
                            + (value == null ? "nulo" : "um valor do tipo " + value.getClass().getSimpleName())
                            + " em vez de verdadeiro ou falso");
        }
        return NodeExecutionResult.condition(result, Map.of("result", result));
    }

    /**
     * Usa a mensagem curta do SpEL, que descreve o problema sem a posicao interna nem a expressao
     * remontada.
     */
    private static String describe(Exception e) {
        String message = e instanceof ExpressionException spel ? spel.getSimpleMessage() : e.getMessage();
        return TextSanitizer.truncateSystemText(message, MAX_ERROR_LENGTH);
    }
}
