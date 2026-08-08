package com.nexio.workflow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.nexio.workflow.domain.model.ExternalDataSanitizer.Sanitized;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Teste da adaptacao de dado externo a politica estrita de gravacao.
 */
class ExternalDataSanitizerTest {

    /**
     * Orcamento de entradas do corpo, ja descontado o que e gravado ao lado dele.
     *
     * <p>O teto de {@value MapSanitizer#MAX_ENTRIES} entradas da politica estrita vale para o
     * {@code output} inteiro, somando todos os niveis -- e o {@code output} de um no HTTP tem
     * {@code statusCode}, {@code headers}, {@code truncated} e {@code droppedKeys} ao lado do
     * {@code body}. Passar o teto cheio como orcamento do corpo estoura o limite por poucas
     * entradas, que foi exatamente o que o teste de propriedade pegou.</p>
     */
    private static final int ENTRIES = MapSanitizer.MAX_ENTRIES - 8;

    /** O corpo fica um nivel dentro do {@code output}, entao sobra um nivel a menos para ele. */
    private static final int DEPTH = MapSanitizer.MAX_DEPTH - 1;

    /**
     * A garantia que justifica a classe inteira: o que sai daqui sempre pode ser gravado.
     *
     * <p>Este e o teste que importa. Todos os outros descrevem <i>como</i> cada caso e adaptado;
     * este afirma a propriedade -- para qualquer entrada, inclusive as que a politica estrita
     * recusaria de todas as maneiras ao mesmo tempo, a saida passa pelo
     * {@link MapSanitizer#validate(Map, String)}. Se um dia a politica estrita ganhar uma regra
     * nova e esta classe nao acompanhar, e aqui que aparece.</p>
     */
    @ParameterizedTest
    @MethodSource("hostileBodies")
    void whateverComesOutCanAlwaysBeStored(String description, Object body) {
        Sanitized sanitized = ExternalDataSanitizer.sanitize(body, ENTRIES, DEPTH);

        Map<String, Object> output = Map.of(
                "statusCode", 200,
                "body", sanitized.value() == null ? Map.of() : sanitized.value(),
                "truncated", sanitized.truncated(),
                "droppedKeys", sanitized.droppedKeys());

        assertThatCode(() -> MapSanitizer.validate(output, "steps[].output"))
                .as(description)
                .doesNotThrowAnyException();
    }

    static List<org.junit.jupiter.params.provider.Arguments> hostileBodies() {
        return List.of(
                arguments("resposta HAL", Map.of("_links", Map.of("self", "https://x.test"), "total", 150)),
                arguments("JSON Schema", Map.of("$ref", "#/x", "$schema", "http://json-schema.org")),
                arguments("chave com $ aninhada", Map.of("a", Map.of("$where", "1"))),
                arguments("chave em branco", singletonMapOf("   ", "x")),
                arguments("chave longa demais", singletonMapOf("k".repeat(200), "x")),
                arguments("chave nao textual", Map.of(42, "x")),
                arguments("texto acima do teto", Map.of("t", "a".repeat(MapSanitizer.MAX_STRING_LENGTH + 500))),
                arguments("marcador de redacao", Map.of("t", MapSanitizer.REDACTED_MARKER)),
                arguments("inteiro grande", Map.of("n", new BigInteger("9".repeat(40)))),
                arguments("tipo sem codec", Map.of("o", new StringBuilder("x"))),
                arguments("entradas demais", wideMap(600)),
                arguments("aninhamento fundo", deepMap(40)),
                arguments("lista aninhada funda", deepList(40)),
                arguments("lista com tipo estranho", Map.of("l", Arrays.asList(1, new StringBuilder("x"), 3))),
                arguments("lista enorme", Map.of("l", numbers(600))),
                arguments("tudo junto", hostileMix()),
                arguments("nulo", null),
                arguments("lista na raiz", numbers(5)),
                arguments("texto na raiz", "so um texto"),
                arguments("mapa vazio", Map.of()));
    }

    /** {@code _links} e {@code _embedded} de qualquer API HAL, o caso que motivou a classe. */
    @Test
    void dropsTheKeysOfAHalResponseAndKeepsTheRest() {
        Sanitized sanitized = ExternalDataSanitizer.sanitize(
                Map.of("_links", Map.of("self", "https://x.test"),
                        "_embedded", Map.of("itens", List.of()),
                        "total", 150,
                        "status", "pago"),
                ENTRIES, DEPTH);

        assertThat(asMap(sanitized.value()))
                .containsEntry("total", 150)
                .containsEntry("status", "pago")
                .doesNotContainKey("_links")
                .doesNotContainKey("_embedded");
        assertThat(sanitized.droppedKeys()).isEqualTo(2);
        assertThat(sanitized.truncated()).isFalse();
    }

    @Test
    void dropsDollarPrefixedKeysAnywhereInTheTree() {
        Sanitized sanitized = ExternalDataSanitizer.sanitize(
                Map.of("dados", Map.of("$where", "isto e injecao", "ok", 1)), ENTRIES, DEPTH);

        assertThat(asMap(asMap(sanitized.value()).get("dados")))
                .containsEntry("ok", 1)
                .doesNotContainKey("$where");
        assertThat(sanitized.droppedKeys()).isEqualTo(1);
    }

    /** Inteiro grande vira {@link BigDecimal}: mesmo numero, e este tem codec BSON. */
    @Test
    void convertsABigIntegerInsteadOfDroppingIt() {
        BigInteger huge = new BigInteger("123456789012345678901234567890");

        Sanitized sanitized = ExternalDataSanitizer.sanitize(Map.of("n", huge), ENTRIES, DEPTH);

        assertThat(asMap(sanitized.value())).containsEntry("n", new BigDecimal(huge));
        assertThat(sanitized.droppedKeys()).isZero();
    }

    @Test
    void truncatesALongStringAndSaysSo() {
        String longText = "a".repeat(MapSanitizer.MAX_STRING_LENGTH + 1000);

        Sanitized sanitized = ExternalDataSanitizer.sanitize(Map.of("t", longText), ENTRIES, DEPTH);

        String stored = (String) asMap(sanitized.value()).get("t");
        assertThat(stored).hasSize(MapSanitizer.MAX_STRING_LENGTH)
                .endsWith(ExternalDataSanitizer.TRUNCATION_MARKER);
        assertThat(sanitized.truncated()).isTrue();
    }

    /**
     * A quebra de linha dentro de um valor sobrevive.
     *
     * <p>Ao contrario do texto de sistema, que tem os caracteres de controle trocados por espaco
     * antes de ir para o log, aqui a quebra de linha e conteudo do campo. Trocar seria alterar o
     * dado que se estava tentando registrar.</p>
     */
    @Test
    void keepsControlCharactersInsideAValueBecauseTheyAreContent() {
        Sanitized sanitized = ExternalDataSanitizer.sanitize(
                Map.of("descricao", "linha 1\nlinha 2"), ENTRIES, DEPTH);

        assertThat(asMap(sanitized.value())).containsEntry("descricao", "linha 1\nlinha 2");
    }

    @Test
    void stopsAtTheEntryBudgetAndSaysSo() {
        Sanitized sanitized = ExternalDataSanitizer.sanitize(wideMap(600), ENTRIES, DEPTH);

        assertThat(asMap(sanitized.value())).hasSize(ENTRIES);
        assertThat(sanitized.truncated()).isTrue();
    }

    @Test
    void stopsAtTheDepthBudgetAndSaysSo() {
        Sanitized sanitized = ExternalDataSanitizer.sanitize(deepMap(40), ENTRIES, 5);

        assertThat(sanitized.truncated()).isTrue();
        assertThatCode(() -> MapSanitizer.validate(
                Map.of("body", sanitized.value()), "steps[].output")).doesNotThrowAnyException();
    }

    /**
     * Elemento de lista que nao pode ser gravado vira {@code null} em vez de sumir.
     *
     * <p>Remover encolheria a lista e deslocaria os indices seguintes: uma condicao lendo
     * {@code #outputs['x']['body']['itens'][2]} passaria a ler outro elemento sem que nada
     * indicasse a troca.</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void replacesAnUnstorableListElementWithNullToKeepTheIndexes() {
        Sanitized sanitized = ExternalDataSanitizer.sanitize(
                Map.of("itens", Arrays.asList("a", new StringBuilder("x"), "c")), ENTRIES, DEPTH);

        assertThat((List<Object>) asMap(sanitized.value()).get("itens"))
                .containsExactly("a", null, "c");
    }

    /** Valor nulo dentro do corpo e dado legitimo e atravessa: nao e ausencia de campo. */
    @Test
    void keepsNullValuesBecauseTheyAreLegitimateJson() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("canceladoEm", null);
        body.put("total", 10);

        Sanitized sanitized = ExternalDataSanitizer.sanitize(body, ENTRIES, DEPTH);

        assertThat(asMap(sanitized.value())).containsEntry("canceladoEm", null);
        assertThat(sanitized.droppedKeys()).isZero();
    }

    /**
     * Todo escalar que a politica estrita aceita atravessa intacto.
     *
     * <p>E a verificacao de paridade entre as duas listas, feita por comportamento e nao por
     * reflexao: se o {@link MapSanitizer} aceitar um tipo novo, o caso entra aqui e a falha aponta
     * qual e.</p>
     */
    @Test
    void passesThroughEveryScalarTheStrictPolicyAccepts() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bool", true);
        body.put("int", 1);
        body.put("long", 2L);
        body.put("double", 1.5d);
        body.put("float", 1.5f);
        body.put("short", (short) 3);
        body.put("byte", (byte) 4);
        body.put("bigDecimal", new BigDecimal("1.23"));
        body.put("instant", Instant.parse("2026-01-01T00:00:00Z"));

        Sanitized sanitized = ExternalDataSanitizer.sanitize(body, ENTRIES, DEPTH);

        assertThat(asMap(sanitized.value())).isEqualTo(body);
        assertThat(sanitized.droppedKeys()).isZero();
        assertThat(sanitized.truncated()).isFalse();
    }

    /** {@link Date} e mutavel: normalizado para {@link Instant}, como o {@link MapSanitizer} faz. */
    @Test
    void normalisesADateToAnInstant() {
        Instant moment = Instant.parse("2026-01-01T00:00:00Z");

        Sanitized sanitized = ExternalDataSanitizer.sanitize(
                Map.of("quando", Date.from(moment)), ENTRIES, DEPTH);

        assertThat(asMap(sanitized.value())).containsEntry("quando", moment);
    }

    /**
     * O marcador de redacao nao pode ser gravado nem vindo de fora.
     *
     * <p>A politica estrita o recusa como valor para impedir que uma leitura redigida volte por
     * cima da credencial de verdade. Um servico devolvendo exatamente este texto e improvavel, mas
     * a garantia da classe e que <i>nada</i> impede a gravacao.</p>
     */
    @Test
    void dropsTheRedactionMarkerComingFromOutside() {
        Sanitized sanitized = ExternalDataSanitizer.sanitize(
                Map.of("campo", MapSanitizer.REDACTED_MARKER, "ok", 1), ENTRIES, DEPTH);

        assertThat(asMap(sanitized.value())).containsEntry("ok", 1).doesNotContainKey("campo");
        assertThat(sanitized.droppedKeys()).isEqualTo(1);
    }

    private static org.junit.jupiter.params.provider.Arguments arguments(String d, Object body) {
        return org.junit.jupiter.params.provider.Arguments.of(d, body);
    }

    private static Map<String, Object> singletonMapOf(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static Map<String, Object> wideMap(int size) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            map.put("chave" + i, i);
        }
        return map;
    }

    private static Map<String, Object> deepMap(int depth) {
        Map<String, Object> current = new LinkedHashMap<>(Map.of("fundo", 1));
        for (int i = 0; i < depth; i++) {
            current = singletonMapOf("nivel" + i, current);
        }
        return current;
    }

    private static Object deepList(int depth) {
        Object current = List.of(1, 2);
        for (int i = 0; i < depth; i++) {
            current = List.of(current);
        }
        return Map.of("l", current);
    }

    private static List<Object> numbers(int size) {
        List<Object> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(i);
        }
        return list;
    }

    private static Map<String, Object> hostileMix() {
        Map<String, Object> map = wideMap(300);
        map.put("_links", Map.of("self", "x"));
        map.put("$ref", "#/x");
        map.put("fundo", deepMap(30));
        map.put("texto", "a".repeat(MapSanitizer.MAX_STRING_LENGTH + 10));
        map.put("grande", new BigInteger("9".repeat(50)));
        map.put("estranho", new StringBuilder("x"));
        map.put("marcador", MapSanitizer.REDACTED_MARKER);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
