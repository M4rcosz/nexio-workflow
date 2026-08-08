package com.nexio.workflow.domain.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * Teste da gramatica aceita numa expressao de no CONDITION.
 */
class ConditionExpressionValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "total > 100",
        "total > 100 and status == 'pago'",
        "status == 'pago' or total <= 0",
        "!(status == 'cancelado')",
        "#trigger['total'] > 100",
        "#trigger['cliente']['plano'] == 'ouro'",
        "#outputs['consulta']['statusCode'] == 200",
        "#trigger['desconto'] ?: 0 > 10",
        "total == null ? false : total > 100",
        "(total + frete) * 2 - 1 > 100",
        "total % 2 == 0",
        "total / 2 >= 50",
        "true",
        "1.5 < 2.0",
        "#trigger['ausente'] == null"
    })
    void acceptsWhatACondicaoActuallyNeeds(String expression) {
        assertThatCode(() -> ConditionExpressionValidator.validate(expression, "no"))
                .doesNotThrowAnyException();
    }

    /**
     * O construto que motivou a lista de permissao inteira.
     *
     * <p>Estas quatro selecoes aninhadas tem 84 caracteres -- confortavelmente abaixo do teto de
     * {@value WorkflowDefinition#MAX_EXPRESSION_LENGTH} -- e avaliaram por 49 segundos contra uma
     * lista de 200 itens. Nada no contexto de avaliacao impede isso: {@code SimpleEvaluationContext}
     * limita o que a expressao <i>alcanca</i>, nao quanto ela <i>custa</i>, e o teto de tempo da
     * engine e conferido entre nos e nunca interrompe um no em andamento. Como {@code createWorkflow}
     * e o disparo sao anonimos, era negacao de servico sem autenticacao.</p>
     */
    @Test
    void refusesTheNestedSelectionThatEvaluatesForFortyNineSeconds() {
        String bomb = "#t['i'].?[#t['i'].?[#t['i'].?[#t['i'].?[true].size>0].size>0].size>0].size>0";

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ConditionExpressionValidator.validate(bomb, "condicao"))
                .withMessageContaining("condicao")
                .withMessageContaining("selecao");
    }

    /**
     * A mesma bomba sem payload nenhum: a lista literal fornece os elementos.
     *
     * <p>Importa porque a defesa "o payload e limitado pelo {@link MapSanitizer}" nao vale. Quem
     * escreve a expressao nao precisa de dado de entrada para faze-la iterar.</p>
     */
    @Test
    void refusesTheSameBombWrittenWithLiteralListsAndNoPayloadAtAll() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ConditionExpressionValidator.validate(
                        "{1,2,3}.?[{1,2,3}.?[{1,2,3}.?[true].size>0].size>0].size>0", "condicao"))
                .withMessageContaining("selecao");
    }

    /**
     * Execucao remota de codigo, recusada na escrita alem de ja morrer na avaliacao.
     *
     * <p>Nao e redundancia inutil: o contexto de avaliacao e a defesa que precisa continuar
     * correta, e esta camada faz a tentativa virar BAD_REQUEST na criacao em vez de um workflow
     * gravado que so falha quando alguem dispara.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "T(java.lang.Runtime).getRuntime().exec('id')",
        "T(java.lang.System).getenv()",
        "new java.lang.ProcessBuilder('sh').start()",
        "''.getClass().forName('java.lang.Runtime')",
        "#trigger.toString() == 'x'",
        "@dataSource.connection != null",
        "#umaFuncao('x')"
    })
    void refusesEveryFormThatReachesOutsideTheExpression(String expression) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ConditionExpressionValidator.validate(expression, "no"));
    }

    /**
     * {@code matches} sai por retrocesso catastrofico, e nao por iterar sobre colecao.
     *
     * <p>O padrao e o texto comparado vem os dois de fora, e vinte caracteres bastam para travar a
     * avaliacao. E a unica recusa da lista que custa algo util a quem escreve workflow.</p>
     */
    @Test
    void refusesMatchesBecauseBothOperandsComeFromOutside() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ConditionExpressionValidator.validate(
                        "status matches '(a+)+$'", "no"))
                .withMessageContaining("matches");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "total ![#this]",
        "{1,2,3}",
        "{chave:'valor'}",
        "total = 1",
        "total ^ 2 > 100",
        "total instanceof T(java.lang.Integer)"
    })
    void refusesTheRemainingConstructsOutsideTheAllowList(String expression) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ConditionExpressionValidator.validate(expression, "no"));
    }

    /** A expressao que nem compila e recusada na criacao, e nao no disparo numero 4000. */
    @ParameterizedTest
    @ValueSource(strings = {"total >", "((((", "'sem fechar", "and and"})
    void refusesWhatDoesNotEvenParse(String expression) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ConditionExpressionValidator.validate(expression, "no"))
                .withMessageContaining("nao e uma expressao valida");
    }

    /** A mensagem localiza o no e mostra o trecho recusado: quem escreveu precisa achar o erro. */
    @Test
    void namesTheNodeAndShowsTheOffendingFragment() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ConditionExpressionValidator.validate(
                        "total > 100 and #trigger.size() > 0", "checa-total"))
                .withMessageContaining("checa-total")
                .withMessageContaining("size()");
    }

    /**
     * O construto proibido escondido no meio da arvore tambem e pego.
     *
     * <p>O teste existe porque conferir so o no raiz passaria: a raiz aqui e um {@code and}
     * perfeitamente legitimo.</p>
     */
    @Test
    void findsTheForbiddenConstructBuriedDeepInTheTree() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ConditionExpressionValidator.validate(
                        "total > 0 and (status == 'pago' or #t['i'].?[true].size > 0)", "no"));
    }

    /**
     * Confere que a expressao permitida realmente avalia no contexto que o executor monta.
     *
     * <p>Sem isto, a lista de permissao poderia estar aceitando forma que o executor nao resolve --
     * uma gramatica aprovada na escrita e quebrada na execucao, que e o pior dos dois mundos: o
     * autor recebe "ok" e o erro aparece no disparo.</p>
     */
    @Test
    void whatIsAcceptedHereAlsoEvaluatesInTheContextTheExecutorBuilds() {
        Map<String, Object> trigger = Map.of("total", 150, "status", "pago");
        SimpleEvaluationContext context =
                SimpleEvaluationContext.forPropertyAccessors(new MapAccessor(false)).build();
        context.setVariable("trigger", trigger);
        context.setVariable("outputs", Map.of("consulta", Map.of("statusCode", 200)));

        List<String> accepted = List.of(
                "total > 100 and status == 'pago'",
                "#trigger['total'] > 100",
                "#outputs['consulta']['statusCode'] == 200");

        SpelExpressionParser parser = new SpelExpressionParser();
        for (String expression : accepted) {
            ConditionExpressionValidator.validate(expression, "no");
            assertThatCode(() -> parser.parseExpression(expression).getValue(context, trigger))
                    .as(expression)
                    .doesNotThrowAnyException();
        }
    }
}
