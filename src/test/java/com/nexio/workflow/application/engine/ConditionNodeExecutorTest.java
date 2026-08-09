package com.nexio.workflow.application.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.NodeType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Teste da avaliacao de nos CONDITION.
 */
class ConditionNodeExecutorTest {

    private final ConditionNodeExecutor executor = new ConditionNodeExecutor();

    private static final Map<String, Object> TRIGGER =
            Map.of("total", 150, "status", "pago", "cliente", Map.of("plano", "ouro"));

    @Test
    void answersForConditionNodes() {
        assertThat(executor.supportedType()).isEqualTo(NodeType.CONDITION);
    }

    @ParameterizedTest
    @CsvSource({
        "total > 100,                          CONDITION_TRUE",
        "total > 1000,                         CONDITION_FALSE",
        "status == 'pago',                     CONDITION_TRUE",
        "status == 'cancelado',                CONDITION_FALSE",
        "total > 100 and status == 'pago',     CONDITION_TRUE",
        "total > 100 and status == 'aberto',   CONDITION_FALSE",
        "cliente['plano'] == 'ouro',           CONDITION_TRUE"
    })
    void readsThePayloadDirectlyBecauseItIsTheRootObject(String expression, NodeOutcome expected) {
        assertThat(evaluate(expression).outcome()).isEqualTo(expected);
    }

    /** {@code #trigger} e o mesmo mapa da raiz, para quem prefere dizer de onde o dado veio. */
    @ParameterizedTest
    @ValueSource(strings = {"#trigger['total'] > 100", "#trigger['cliente']['plano'] == 'ouro'"})
    void alsoReadsThePayloadThroughTheTriggerVariable(String expression) {
        assertThat(evaluate(expression).outcome()).isEqualTo(NodeOutcome.CONDITION_TRUE);
    }

    /** A saida de um no anterior e a outra metade do que uma condicao tem para decidir. */
    @Test
    void readsTheOutputOfAnEarlierNode() {
        NodeExecutionContext context = new NodeExecutionContext(
                "exec-1", TRIGGER, Map.of("consulta", Map.of("statusCode", 200)));

        NodeExecutionResult result = executor.execute(
                node("#outputs['consulta']['statusCode'] == 200"), context);

        assertThat(result.outcome()).isEqualTo(NodeOutcome.CONDITION_TRUE);
    }

    /** O passo guarda o booleano avaliado: e o que responde por que a execucao foi por ali. */
    @Test
    void recordsTheEvaluatedValueInTheStepOutput() {
        assertThat(evaluate("total > 100").output()).containsEntry("result", true);
        assertThat(evaluate("total > 1000").output()).containsEntry("result", false);
    }

    /**
     * A condicao que nao pode ser avaliada <b>falha</b>, e nao vira o ramo falso.
     *
     * <p>Seguir por {@code nextOnFalse} faria a execucao parecer que decidiu. Um workflow que cobra
     * quando {@code total > 100} e nao consegue ler {@code total} passaria a rodar em silencio o
     * ramo do "nao cobrar", indistinguivel de um pedido barato de verdade, e o historico nao
     * registraria nada errado.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "ausente > 100",
        "total / 0 > 1",
        "status > 100"
    })
    void failsInsteadOfSilentlyTakingTheFalseBranch(String expression) {
        NodeExecutionResult result = evaluate(expression);

        assertThat(result.outcome()).isEqualTo(NodeOutcome.FAILURE);
        assertThat(result.error()).contains("condicao").contains("checa");
    }

    /** Expressao que produz outra coisa que nao booleano tambem falha, pelo mesmo motivo. */
    @ParameterizedTest
    @ValueSource(strings = {"total + 1", "status", "#trigger['ausente']"})
    void failsWhenTheExpressionDoesNotProduceABoolean(String expression) {
        NodeExecutionResult result = evaluate(expression);

        assertThat(result.outcome()).isEqualTo(NodeOutcome.FAILURE);
        assertThat(result.error()).contains("em vez de verdadeiro ou falso");
    }

    /**
     * A definicao gravada antes de a gramatica existir nao derruba a execucao com excecao.
     *
     * <p>A validacao de escrita recusa isto hoje, mas ela vale para o que foi gravado <i>depois</i>
     * de a regra existir -- o mesmo raciocinio que faz a engine manter o teto de passos apesar de a
     * escrita ja recusar ciclo.</p>
     */
    @Test
    void turnsAnExpressionThatNoLongerParsesIntoANodeFailure() {
        NodeExecutionResult result = evaluate("total >");

        assertThat(result.outcome()).isEqualTo(NodeOutcome.FAILURE);
        assertThat(result.error()).isNotBlank();
    }

    /**
     * O contexto de avaliacao recusa alcancar tipo, construtor e metodo.
     *
     * <p>A validacao de escrita ja recusa estas expressoes, entao nenhuma deveria chegar aqui. O
     * teste existe porque as duas defesas sao independentes de proposito: se um dia alguem afrouxar
     * a lista de permissao, o resultado precisa continuar sendo uma falha de no, e nao
     * {@code Runtime.exec}.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "T(java.lang.Runtime).getRuntime().exec('id') != null",
        "new java.lang.String('x') == 'x'",
        "''.getClass().name == 'x'"
    })
    void theEvaluationContextStillRefusesCodeExecutionOnItsOwn(String expression) {
        assertThat(evaluate(expression).outcome()).isEqualTo(NodeOutcome.FAILURE);
    }

    /** O acessor de mapa e de leitura: a expressao nao altera o payload do gatilho. */
    @Test
    void cannotWriteBackIntoThePayload() {
        NodeExecutionResult result = evaluate("total = 1");

        assertThat(result.outcome()).isEqualTo(NodeOutcome.FAILURE);
        assertThat(TRIGGER).containsEntry("total", 150);
    }

    private NodeExecutionResult evaluate(String expression) {
        return executor.execute(node(expression), new NodeExecutionContext("exec-1", TRIGGER, Map.of()));
    }

    private static WorkflowNode node(String expression) {
        return WorkflowNode.condition("checa", expression, "sim", "nao");
    }

    /**
     * Expressao gravada antes da regra de gramatica falha o no, e falha depressa.
     *
     * <p>O Javadoc do executor ja dizia que uma definicao antiga pode conter expressao hoje
     * recusada, mas o codigo so tratava o caso de ela nao <i>compilar</i>. Selecao aninhada compila
     * perfeitamente: 84 caracteres avaliam por 49 segundos, a cada disparo, numa thread de
     * requisicao, sem nada que interrompa -- o teto de tempo da engine e conferido entre nos. O
     * limite de tempo neste teste e o que distingue "recusou" de "avaliou": sem a reconferencia da
     * gramatica ele estoura.</p>
     */
    @Test
    void refusesALegacyExpressionThatWouldTakeSecondsToEvaluate() {
        String bomb = "#t['i'].?[#t['i'].?[#t['i'].?[#t['i'].?[true].size>0].size>0].size>0].size>0";
        java.util.List<Integer> itens = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            itens.add(i);
        }
        NodeExecutionContext context = new NodeExecutionContext(
                "exec-1", Map.of("i", itens), Map.of());

        long start = System.nanoTime();
        NodeExecutionResult result = executor.execute(
                WorkflowNode.condition("checa", bomb, "sim", "nao"), context);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.outcome()).isEqualTo(NodeOutcome.FAILURE);
        assertThat(elapsedMs).as("a expressao foi avaliada em vez de recusada").isLessThan(2_000);
    }
}
