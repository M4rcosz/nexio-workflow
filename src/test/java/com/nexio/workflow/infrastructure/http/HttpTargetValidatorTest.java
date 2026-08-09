package com.nexio.workflow.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.nexio.workflow.infrastructure.http.HttpTargetNotAllowedException.Reason;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Testes do guarda de SSRF das requisicoes de saida.
 *
 * <p>Os casos aceitos usam IP publico literal ({@code 8.8.8.8}, {@code 1.1.1.1}) em vez de nome:
 * {@link java.net.InetAddress#getAllByName(String)} devolve literais sem consultar DNS, entao a
 * suite nao depende de rede nem de resolvedor. Os casos recusados por endereco tambem sao
 * literais, com a unica excecao de {@code localhost}, que vem do arquivo de hosts do sistema e e
 * o caso realista de "nome que resolve para dentro".</p>
 */
class HttpTargetValidatorTest {

    private final HttpTargetValidator strictValidator = new HttpTargetValidator(false);
    private final HttpTargetValidator permissiveValidator = new HttpTargetValidator(true);

    // --- lista de esquemas ---

    @Test
    void shouldAcceptHttpsTarget() {
        URI uri = strictValidator.validate("https://8.8.8.8/api/v1/hook?x=1");

        assertThat(uri).isEqualTo(URI.create("https://8.8.8.8/api/v1/hook?x=1"));
    }

    @Test
    void shouldAcceptUppercaseSchemeBecauseSchemeIsCaseInsensitive() {
        assertThat(strictValidator.validate("HTTPS://1.1.1.1/")).isNotNull();
    }

    @Test
    void shouldRejectHttpWhenInsecureFlagIsOff() {
        assertReason(strictValidator, "http://8.8.8.8/hook", Reason.INSECURE_SCHEME_DISABLED);
    }

    @Test
    void shouldAcceptHttpWhenInsecureFlagIsOn() {
        assertThat(permissiveValidator.validate("http://8.8.8.8/hook"))
                .isEqualTo(URI.create("http://8.8.8.8/hook"));
    }

    /**
     * A flag so afrouxa o {@code http}: nenhum outro esquema entra por ela.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "file:///etc/passwd",
        "file://8.8.8.8/share",
        "data:text/plain;base64,aGVsbG8=",
        "ftp://8.8.8.8/pub",
        "gopher://8.8.8.8:70/_GET",
        "jar:https://8.8.8.8/a.jar!/x",
        "netdoc:///etc/passwd"
    })
    void shouldRejectSchemesOutsideTheAllowList(String url) {
        assertReason(strictValidator, url, Reason.UNSUPPORTED_SCHEME);
        assertReason(permissiveValidator, url, Reason.UNSUPPORTED_SCHEME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not a url", "/apenas/caminho", "https://", "https:///caminho"})
    void shouldRejectMalformedUrls(String url) {
        assertReason(strictValidator, url, Reason.MALFORMED_URL);
    }

    @Test
    void shouldRejectNullUrl() {
        assertReason(strictValidator, null, Reason.MALFORMED_URL);
    }

    // --- guarda de SSRF ---

    /**
     * {@code localhost} e o caso realista: nome comum, resolvido pelo arquivo de hosts, apontando
     * para dentro do proprio processo (Actuator, Mongo, qualquer servico sem autenticacao).
     */
    @Test
    void shouldRejectHostnameResolvingToLoopback() {
        assertReason(strictValidator, "https://localhost:8080/actuator/health", Reason.BLOCKED_ADDRESS);
    }

    /**
     * O alvo classico de SSRF em nuvem: o endereco de metadados devolve credenciais da instancia
     * sem exigir autenticacao nenhuma.
     */
    @Test
    void shouldRejectCloudMetadataAddress() {
        assertReason(strictValidator,
                "https://169.254.169.254/latest/meta-data/iam/security-credentials/",
                Reason.BLOCKED_ADDRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://127.0.0.1/",
        "https://127.1.2.3/",
        "https://10.0.0.1/",
        "https://192.168.1.1/",
        "https://172.16.0.1/",
        "https://172.31.255.254/",
        "https://0.0.0.0/",
        "https://0.1.2.3/",
        "https://100.64.0.1/",
        "https://224.0.0.1/",
        "https://255.255.255.255/",
        "https://[::1]/",
        "https://[fc00::1]/",
        "https://[fd12:3456:789a::1]/",
        "https://[fe80::1]/",
        "https://[ff02::1]/"
    })
    void shouldRejectInternalAddresses(String url) {
        assertReason(strictValidator, url, Reason.BLOCKED_ADDRESS);
    }

    // --- higiene da URL ---

    @Test
    void shouldRejectEmbeddedCredentials() {
        assertReason(strictValidator, "https://admin:s3nha@8.8.8.8/api", Reason.EMBEDDED_CREDENTIALS);
        assertReason(strictValidator, "https://admin@8.8.8.8/api", Reason.EMBEDDED_CREDENTIALS);
    }

    @Test
    void shouldRejectFragment() {
        assertReason(strictValidator, "https://8.8.8.8/api#ancora", Reason.FRAGMENT_NOT_ALLOWED);
    }

    @Test
    void shouldRejectPortZero() {
        assertReason(strictValidator, "https://8.8.8.8:0/api", Reason.INVALID_PORT);
    }

    /**
     * Porta nao padrao continua valida: restringir a 80/443 quebraria API legitima em 8443 e a
     * protecao real vem da checagem de endereco.
     */
    @Test
    void shouldAcceptNonStandardPortOnPublicAddress() {
        assertThat(strictValidator.validate("https://8.8.8.8:8443/api")).isNotNull();
    }

    // --- a mensagem nao pode virar oraculo ---

    /**
     * O Sprint 3 vai gravar esta mensagem em {@code ExecutionStep.error} e devolve-la pelo
     * GraphQL. Se ela repetisse o host ou o IP resolvido, o proprio guarda de SSRF viraria a
     * ferramenta de varredura: bastaria criar um workflow por alvo e ler a resposta.
     */
    @Test
    void rejectionMessageShouldNotEchoHostOrAddress() {
        HttpTargetNotAllowedException metadata = catchThrowableOfType(HttpTargetNotAllowedException.class,
                () -> strictValidator.validate("https://169.254.169.254/latest/meta-data/"));
        HttpTargetNotAllowedException loopback = catchThrowableOfType(HttpTargetNotAllowedException.class,
                () -> strictValidator.validate("https://localhost:27017/admin"));

        assertThat(metadata.getMessage())
                .doesNotContain("169.254.169.254", "meta-data")
                .isEqualTo(Reason.BLOCKED_ADDRESS.message());
        assertThat(loopback.getMessage())
                .doesNotContain("localhost", "127.0.0.1", "27017", "admin")
                .isEqualTo(Reason.BLOCKED_ADDRESS.message());
    }

    /**
     * Distinguir "nao resolve" de "resolve para dentro" permitiria enumerar nomes internos de DNS
     * so pela mensagem devolvida, entao os dois motivos compartilham o mesmo texto.
     */
    @Test
    void unresolvableAndBlockedShouldShareTheSameMessage() {
        assertThat(Reason.UNRESOLVABLE_HOST.message()).isEqualTo(Reason.BLOCKED_ADDRESS.message());
    }

    @Test
    void everyReasonMessageShouldBeShortAndConstant() {
        for (Reason reason : Reason.values()) {
            assertThat(reason.message()).isNotBlank().hasSizeLessThan(80);
        }
    }

    private static void assertReason(HttpTargetValidator validator, String url, Reason expected) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(HttpTargetNotAllowedException.class)
                .extracting(thrown -> ((HttpTargetNotAllowedException) thrown).reason())
                .isEqualTo(expected);
    }

    /**
     * A forma decimal de 32 bits de um endereco interno e recusada tambem na escrita.
     *
     * <p>{@code 2852039166} e {@code 169.254.169.254} escrito como um numero so, e o
     * {@link java.net.URI} devolve isso como host porque rotulo unico todo numerico e valido na
     * gramatica. O reconhecimento de literal exigia os quatro octetos, entao esse host era tratado
     * como nome, nenhuma checagem de endereco rodava e a definicao era gravada apontando para o
     * servico de metadados da nuvem. Enquanto a issue #23 nao existe, a validacao de escrita e o
     * unico portao de SSRF do sistema.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "http://2852039166/latest/meta-data/",
        "http://169.254.169.254/latest/meta-data/",
        "http://127.0.0.1/admin",
        "http://[::1]/admin"
    })
    void rejectsInternalLiteralTargetsWithoutResolvingNames(String url) {
        HttpTargetValidator permissive = new HttpTargetValidator(true);
        assertThatExceptionOfType(HttpTargetNotAllowedException.class)
                .isThrownBy(() -> permissive.validateWithoutResolving(url));
    }

    /** Nome que precisa de DNS passa na escrita: quem o verifica e o disparo. */
    @Test
    void letsARealHostnameThroughTheResolutionFreeCheck() {
        HttpTargetValidator permissive = new HttpTargetValidator(true);
        assertThatCode(() -> permissive.validateWithoutResolving("https://api.exemplo.test/v1"))
                .doesNotThrowAnyException();
    }

    /**
     * URL com marcador de template passa pela validacao de sintaxe.
     *
     * <p>{@code &#123;} e {@code &#125;} nao sao caracteres validos num URI, entao o
     * {@code new URI} estourava e <b>toda</b> URL templatizada era recusada como "URL de destino
     * invalida" no {@code createWorkflow} -- o templating ficava inutilizavel de ponta a ponta. Nem
     * os testes do resolvedor nem os do executor viam: os dois exercitavam a resolucao, e quem
     * quebrava era o caminho de escrita. So o teste de ponta a ponta juntou as duas metades.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "https://api.exemplo.test/pedidos/{{trigger.pedidoId}}",
        "https://api.exemplo.test/x?ref={{trigger.ref}}",
        "https://api.exemplo.test/{{trigger.a}}/{{steps.no.b}}"
    })
    void acceptsAUrlCarryingTemplatePlaceholders(String url) {
        assertThatCode(() -> new HttpTargetValidator(false).validateWithoutResolving(url))
                .doesNotThrowAnyException();
    }

    /**
     * O marcador nao serve de disfarce para um destino interno.
     *
     * <p>A substituicao troca o marcador por um rotulo neutro para conseguir analisar a sintaxe; ela
     * nao pode fazer um host interno literal deixar de ser recusado.</p>
     */
    @Test
    void stillRefusesAnInternalHostWhenTheUrlAlsoHasPlaceholders() {
        assertThatExceptionOfType(HttpTargetNotAllowedException.class)
                .isThrownBy(() -> new HttpTargetValidator(true)
                        .validateWithoutResolving("http://169.254.169.254/{{trigger.a}}"));
    }
}
