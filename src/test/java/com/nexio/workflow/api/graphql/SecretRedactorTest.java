package com.nexio.workflow.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
        "password", "passwd", "credential", "Cookie", "Set-Cookie"
    })
    void masksEveryKeyThatNamesACredential(String key) {
        Map<String, Object> redacted = SecretRedactor.redact(Map.of(key, "valor sensivel"));

        assertThat(redacted).containsEntry(key, SecretRedactor.REDACTED);
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
}
