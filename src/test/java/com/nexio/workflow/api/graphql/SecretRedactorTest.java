package com.nexio.workflow.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Teste unitario da redacao de chaves sensiveis.
 */
class SecretRedactorTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "Authorization", "authorization", "AUTHORIZATION",
        "api-key", "api_key", "apikey", "X-Api-Key",
        "token", "refresh_token", "secret", "clientSecret",
        "password", "passwd", "credential", "Cookie", "Set-Cookie",
        "access_key", "accessKey", "AWS_ACCESS_KEY_ID", "privateKey", "private_key",
        "pwd", "bearer", "signature", "x-sig", "sessionId", "session_id", "pin"
    })
    void masksEveryKeyThatNamesACredential(String key) {
        Map<String, Object> redacted = SecretRedactor.redact(Map.of(key, "valor sensivel"));

        assertThat(redacted).containsEntry(key, SecretRedactor.REDACTED);
    }

    /**
     * Os termos curtos da lista so casam delimitados, entao palavra comum que apenas os contem nao
     * e redigida.
     *
     * <p>O teste existe porque o custo do falso positivo mudou: desde que a escrita recusa o
     * marcador, redigir {@code mapping} por engano nao deixa so a resposta menos informativa --
     * quebra a proxima escrita de qualquer cliente que leia o workflow, edite um campo e devolva o
     * objeto inteiro, que e o que toda tela de CRUD gerada faz.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {"design", "mapping", "spinner", "shipping", "designation"})
    void keepsWordsThatMerelyContainAShortSensitiveTermUntouched(String key) {
        Map<String, Object> redacted = SecretRedactor.redact(Map.of(key, "valor comum"));

        assertThat(redacted).containsEntry(key, "valor comum");
    }

    /** O delimitador aceita {@code _} e {@code -}, que e como estes nomes aparecem na pratica. */
    @ParameterizedTest
    @ValueSource(strings = {"card_pin", "session-pin", "x_sig", "request.sig"})
    void masksShortSensitiveTermsWhenDelimited(String key) {
        Map<String, Object> redacted = SecretRedactor.redact(Map.of(key, "valor sensivel"));

        assertThat(redacted).containsEntry(key, SecretRedactor.REDACTED);
    }

    /**
     * Um {@code url} nulo passa pela redacao intacto, em vez de derrubar a leitura.
     *
     * <p>O teste existe por um defeito com consequencia desproporcional ao tamanho. Todo no CONDITION
     * tem {@code url} nulo -- a validacao por tipo exige isso --, e {@code new URI(null)} lanca
     * NullPointerException, nao URISyntaxException, entao o {@code catch} do metodo nao pegava. Como
     * a redacao roda no construtor do DTO de resposta, gravar um unico workflow com no CONDITION
     * passava na escrita e depois quebrava toda consulta {@code workflows}, que percorre as
     * definicoes uma a uma -- inclusive a consulta necessaria para achar e apagar o workflow
     * envenenado. Uma mutation anonima, negacao de servico permanente na leitura.</p>
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void leavesAnAbsentUrlAloneInsteadOfFailingTheWholeRead(String url) {
        assertThat(SecretRedactor.redactUrl(url)).isEqualTo(url);
    }

    @Test
    void keepsHarmlessKeysUntouched() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("url", "https://exemplo.test");
        config.put("method", "POST");
        config.put("timeoutMs", 5000);

        assertThat(SecretRedactor.redact(config)).isEqualTo(config);
    }

    /** O segredo nao fica na raiz da config: fica em {@code headers.Authorization}. */
    @Test
    void descendsIntoNestedMaps() {
        Map<String, Object> config = Map.of(
                "url", "https://exemplo.test",
                "headers", Map.of(
                        "Authorization", "Bearer super-secreto",
                        "Accept", "application/json"));

        Map<String, Object> redacted = SecretRedactor.redact(config);

        assertThat(redacted).containsEntry("url", "https://exemplo.test");
        assertThat(asMap(redacted.get("headers")))
                .containsEntry("Authorization", SecretRedactor.REDACTED)
                .containsEntry("Accept", "application/json");
    }

    @Test
    void descendsIntoListsOfMaps() {
        Map<String, Object> config = Map.of(
                "retries", List.of(Map.of("token", "abc"), Map.of("delayMs", 100)));

        List<?> retries = (List<?>) SecretRedactor.redact(config).get("retries");

        assertThat(asMap(retries.getFirst())).containsEntry("token", SecretRedactor.REDACTED);
        assertThat(asMap(retries.get(1))).containsEntry("delayMs", 100);
    }

    /**
     * Mascara a chave inteira, e nao so um trecho: devolver o comeco do valor ainda entregaria o
     * esquema de autenticacao e, com frequencia, o suficiente para reconhecer o segredo.
     */
    @Test
    void replacesTheWholeValueRegardlessOfItsType() {
        Map<String, Object> redacted = SecretRedactor.redact(
                Map.of("credentials", Map.of("password", "s3nh4")));

        assertThat(redacted).containsEntry("credentials", SecretRedactor.REDACTED);
    }

    /**
     * O furo que a regra de nome de chave nao fecha: {@code url} e uma chave inofensiva e o valor
     * dela nao e. A chave de API viaja como parametro de consulta de um campo que nenhuma lista de
     * nomes sensiveis pega, e a resposta de leitura a devolvia inteira.
     */
    @Test
    void masksTheValueOfASensitiveQueryParameterKeepingTheRestOfTheUrl() {
        Map<String, Object> redacted = SecretRedactor.redact(Map.of(
                "url", "https://api.exemplo.test/v1/cobrancas?api_key=chave-secreta&pagina=2"));

        assertThat(redacted).containsEntry("url",
                "https://api.exemplo.test/v1/cobrancas?api_key=" + SecretRedactor.REDACTED + "&pagina=2");
    }

    /** A outra metade das credenciais que cabem num endereco: o par no {@code userinfo}. */
    @Test
    void masksTheUserInfoOfAnUrl() {
        Map<String, Object> redacted = SecretRedactor.redact(Map.of(
                "url", "https://usuario:senha@interno.exemplo.test/hook"));

        assertThat(redacted).containsEntry("url",
                "https://" + SecretRedactor.REDACTED + "@interno.exemplo.test/hook");
    }

    /**
     * So o trecho ofensivo sai. Mascarar o endereco inteiro deixaria a resposta inutil justamente
     * para quem precisa conferir para onde o no chama.
     */
    @Test
    void keepsBenignUrlsAndBenignParametersIntact() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("url", "https://api.exemplo.test/v1/cobrancas?pagina=2&ordem=desc");
        config.put("callback", "https://exemplo.test/hook");
        config.put("method", "POST");

        assertThat(SecretRedactor.redact(config)).isEqualTo(config);
    }

    /** Texto que nao e endereco atravessa sem analise nenhuma. */
    @Test
    void leavesPlainTextValuesAlone() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("body", "total > 100 e status == pago");
        config.put("contentType", "application/json");

        assertThat(SecretRedactor.redact(config)).isEqualTo(config);
    }

    @Test
    void nullOrEmptyBecomesAnEmptyMap() {
        assertThat(SecretRedactor.redact(null)).isEmpty();
        assertThat(SecretRedactor.redact(Map.of())).isEmpty();
    }

    @Test
    void resultIsImmutable() {
        Map<String, Object> redacted = SecretRedactor.redact(Map.of("url", "https://exemplo.test"));

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> redacted.put("outro", "valor"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    /**
     * Endereco citado entre aspas dentro de uma frase tambem e mascarado.
     *
     * <p>O padrao {@code \S+} engolia a aspa final, {@code new URI} recusava o caractere, o
     * {@code catch} devolvia o texto exatamente como veio e a credencial saia inteira. A frase entre
     * aspas e das formas mais comuns de mensagem de cliente HTTP, e este texto vira a
     * {@code errorMessage} de uma execucao, que hoje qualquer um le.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "I/O error for \"https://api.test/v1?api_key=chave-secreta\"",
        "falhou em (https://api.test/v1?api_key=chave-secreta)",
        "destino https://api.test/v1?api_key=chave-secreta.",
        "destino https://api.test/v1?api_key=chave-secreta, tentando de novo",
        "<https://api.test/v1?api_key=chave-secreta>"
    })
    void masksAnAddressQuotedInsideASentence(String message) {
        String redacted = SecretRedactor.redactUrlsIn(message);

        assertThat(redacted).contains(SecretRedactor.REDACTED).doesNotContain("chave-secreta");
    }

    /** A pontuacao que cercava o endereco continua no texto: so o segredo sai. */
    @Test
    void keepsThePunctuationAroundARedactedAddress() {
        String redacted = SecretRedactor.redactUrlsIn(
                "falhou para \"https://api.test/v1?api_key=segredo\" ok");

        assertThat(redacted).isEqualTo(
                "falhou para \"https://api.test/v1?api_key=" + SecretRedactor.REDACTED + "\" ok");
    }
}
