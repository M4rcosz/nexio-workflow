package com.nexio.workflow.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.nexio.workflow.application.engine.TemplateResolver.TemplateResolutionException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Teste da substituicao de marcadores {@code {{...}}}.
 */
class TemplateResolverTest {

    private static final NodeExecutionContext CONTEXT = new NodeExecutionContext(
            "exec-1",
            Map.of("pedidoId", "PED-1",
                    "total", 150,
                    "cliente", Map.of("plano", "ouro", "nome", "Ana"),
                    "itens", List.of(1, 2)),
            Map.of("consulta", Map.of("statusCode", 200, "body", Map.of("saldo", 42))));

    @ParameterizedTest
    @CsvSource({
        "https://api.test/pedidos/{{trigger.pedidoId}},      https://api.test/pedidos/PED-1",
        "https://api.test/p/{{trigger.cliente.plano}},       https://api.test/p/ouro",
        "https://api.test/x?ref={{trigger.pedidoId}},        https://api.test/x?ref=PED-1",
        "https://api.test/{{trigger.total}},                 https://api.test/150",
        "https://api.test/fixo,                              https://api.test/fixo"
    })
    void resolvesTriggerFieldsInTheUrl(String template, String expected) {
        assertThat(TemplateResolver.resolveUrl(template, CONTEXT)).isEqualTo(expected);
    }

    /** A saida de um no anterior e a outra metade do que parametriza uma chamada. */
    @Test
    void resolvesTheOutputOfAnEarlierNode() {
        assertThat(TemplateResolver.resolveUrl(
                "https://api.test/saldo/{{steps.consulta.body.saldo}}", CONTEXT))
                .isEqualTo("https://api.test/saldo/42");
    }

    /**
     * O valor entra codificado: sem isso o destino final deixa de ser o que o autor escreveu.
     *
     * <p>{@code ../../admin} sobe caminho e {@code &admin=true} acrescenta parametro -- os dois
     * dentro de um host que a validacao aprovou, o que e exatamente o tipo de desvio que a validacao
     * de destino nao pega.</p>
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "../../admin        | https://api.test/p/..%2F..%2Fadmin",
        "a/b                | https://api.test/p/a%2Fb",
        "a?x=1              | https://api.test/p/a%3Fx=1",
        "a#frag             | https://api.test/p/a%23frag"
    })
    void percentEncodesAValueSubstitutedIntoThePath(String value, String expected) {
        NodeExecutionContext context = new NodeExecutionContext(
                "exec-1", Map.of("id", value.trim()), Map.of());

        assertThat(TemplateResolver.resolveUrl("https://api.test/p/{{trigger.id}}", context))
                .isEqualTo(expected.trim());
    }

    @Test
    void percentEncodesAValueSubstitutedIntoTheQuery() {
        NodeExecutionContext context = new NodeExecutionContext(
                "exec-1", Map.of("ref", "a&admin=true"), Map.of());

        assertThat(TemplateResolver.resolveUrl("https://api.test/x?ref={{trigger.ref}}", context))
                .isEqualTo("https://api.test/x?ref=a%26admin%3Dtrue");
    }

    /**
     * A substituicao e de uma passada so.
     *
     * <p>Este e o teste de seguranca da classe. O payload do gatilho vem de fora, entao um valor
     * que por acaso -- ou de proposito -- contenha {@code {{...}}} nao pode virar marcador numa
     * segunda passada: quem dispara escolheria o texto e leria o que quisesse do contexto. A
     * implementacao ingenua, que repete ate nao sobrar chave, tem exatamente esse furo.</p>
     */
    @Test
    void doesNotResolveAPlaceholderThatCameFromTheDataItself() {
        NodeExecutionContext context = new NodeExecutionContext(
                "exec-1", Map.of("id", "{{trigger.segredo}}", "segredo", "nao-me-leia"), Map.of());

        String resolved = TemplateResolver.resolveUrl("https://api.test/p/{{trigger.id}}", context);

        assertThat(resolved).doesNotContain("nao-me-leia");
        assertThat(TemplateResolver.resolveValues(Map.of("h", "{{trigger.id}}"), context))
                .containsEntry("h", "{{trigger.segredo}}");
    }

    /**
     * Marcador sem valor falha o no; nao vira texto vazio.
     *
     * <p>Vazio produziria {@code https://api.test/pedidos/} -- outro endereco, possivelmente uma
     * colecao inteira em vez de um item, e possivelmente um {@code DELETE} nela.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "https://api.test/p/{{trigger.naoExiste}}",
        "https://api.test/p/{{trigger.cliente.naoExiste}}",
        "https://api.test/p/{{steps.naoExiste.campo}}",
        "https://api.test/p/{{steps.consulta.naoExiste}}"
    })
    void failsOnAMissingValueInsteadOfSubstitutingNothing(String template) {
        assertThatExceptionOfType(TemplateResolutionException.class)
                .isThrownBy(() -> TemplateResolver.resolveUrl(template, CONTEXT))
                .withMessageContaining("nao encontrou valor");
    }

    /** Marcador apontando para objeto tambem falha: nao ha texto sensato para colocar na URL. */
    @ParameterizedTest
    @ValueSource(strings = {"{{trigger.cliente}}", "{{trigger.itens}}"})
    void failsWhenThePlaceholderPointsAtAnObject(String placeholder) {
        assertThatExceptionOfType(TemplateResolutionException.class)
                .isThrownBy(() -> TemplateResolver.resolveUrl("https://api.test/" + placeholder, CONTEXT))
                .withMessageContaining("objeto");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{pedidoId}}", "{{outro.campo}}", "{{steps.consulta}}", "{{}}"})
    void refusesAPlaceholderThatNamesNoKnownSource(String placeholder) {
        assertThatExceptionOfType(TemplateResolutionException.class)
                .isThrownBy(() -> TemplateResolver.resolveUrl("https://api.test/" + placeholder, CONTEXT));
    }

    /**
     * No corpo, um valor que e exatamente um marcador preserva o tipo.
     *
     * <p>Sem isso todo campo numerico chegaria ao servico de destino como texto, e a validacao do
     * outro lado recusaria um corpo perfeitamente correto.</p>
     */
    @Test
    void keepsTheTypeWhenTheWholeValueIsASinglePlaceholder() {
        Map<String, Object> resolved = TemplateResolver.resolveValues(
                Map.of("total", "{{trigger.total}}", "texto", "pedido {{trigger.pedidoId}}"), CONTEXT);

        assertThat(resolved).containsEntry("total", 150);
        assertThat(resolved).containsEntry("texto", "pedido PED-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesInsideNestedMapsAndLists() {
        Map<String, Object> resolved = TemplateResolver.resolveValues(
                Map.of("cliente", Map.of("plano", "{{trigger.cliente.plano}}"),
                        "refs", List.of("{{trigger.pedidoId}}", "fixo")), CONTEXT);

        assertThat(asMap(resolved.get("cliente"))).containsEntry("plano", "ouro");
        assertThat((List<Object>) resolved.get("refs")).containsExactly("PED-1", "fixo");
    }

    /** Valor sem marcador nenhum atravessa intacto, sem copia nem analise. */
    @Test
    void leavesTextWithoutPlaceholdersAlone() {
        assertThat(TemplateResolver.resolveValues(
                Map.of("a", "sem marcador", "b", 1, "c", true), CONTEXT))
                .containsEntry("a", "sem marcador")
                .containsEntry("b", 1)
                .containsEntry("c", true);
    }

    /** Chaves soltas nao sao marcador: {@code {x}} e texto comum e continua texto comum. */
    @Test
    void treatsSingleBracesAsOrdinaryText() {
        assertThat(TemplateResolver.resolveUrl("https://api.test/{x}", CONTEXT))
                .isEqualTo("https://api.test/{x}");
    }

    /**
     * Dois marcadores seguidos sao dois marcadores, e nao um so.
     *
     * <p>Com {@code .*} no meio do padrao, {@code {{a}}x{{b}}} casaria do primeiro {@code &#123;&#123;}
     * ao ultimo {@code &#125;&#125;} e o texto entre eles sumiria.</p>
     */
    @Test
    void treatsTwoAdjacentPlaceholdersAsTwo() {
        NodeExecutionContext context = new NodeExecutionContext(
                "exec-1", Map.of("a", "1", "b", "2"), Map.of());

        assertThat(TemplateResolver.resolveUrl("https://api.test/{{trigger.a}}x{{trigger.b}}", context))
                .isEqualTo("https://api.test/1x2");
    }

    /** O texto resolvido tem teto: o produto de marcadores por tamanho de valor nao e ilimitado. */
    @Test
    void refusesAResolvedTextAboveTheLimit() {
        NodeExecutionContext context = new NodeExecutionContext(
                "exec-1", Map.of("grande", "a".repeat(4096)), Map.of());
        String template = "https://api.test/" + "{{trigger.grande}}".repeat(3);

        assertThatExceptionOfType(TemplateResolutionException.class)
                .isThrownBy(() -> TemplateResolver.resolveUrl(template, context))
                .withMessageContaining("passou de");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
