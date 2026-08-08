package com.nexio.workflow.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.nexio.workflow.infrastructure.http.HttpTargetNotAllowedException;
import com.nexio.workflow.infrastructure.http.HttpTargetValidator;
import com.nexio.workflow.infrastructure.http.ResponseSizeLimitExceededException;
import com.nexio.workflow.infrastructure.http.ResponseSizeLimitInterceptor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import com.nexio.workflow.infrastructure.http.HttpRequestNodeExecutor;
import com.nexio.workflow.infrastructure.http.PinnedDnsResolver;

/**
 * Testes do cliente HTTP de saida.
 *
 * <p>O servidor de apoio e o {@code com.sun.net.httpserver.HttpServer}, que ja vem no JDK: da para
 * exercitar redirecionamento e teto de corpo sem acrescentar dependencia de teste (WireMock e
 * afins) so para isso.</p>
 *
 * <p>O que <b>nao</b> e coberto aqui: o tempo limite de leitura de 10s. Verifica-lo de verdade
 * exige um servidor que segure a resposta pelos 10s inteiros, o que deixaria a suite mais lenta
 * do que o valor do teste; ele e aplicado no {@code RequestConfig} do cliente e revisado por
 * leitura.</p>
 *
 * <p><b>Nota sobre a troca de cliente (issue #23).</b> O {@code CloseableHttpClient} do httpclient5
 * nao expoe a propria configuracao para inspecao, ao contrario do {@code HttpClient} do JDK. As
 * afirmacoes que antes eram feitas lendo propriedade do bean passaram a ser feitas por
 * comportamento, contra o servidor de apoio -- que e uma forma melhor de afirma-las de qualquer
 * modo: {@code followRedirects(NEVER)} nunca provou que um {@code 302} nao e seguido, so que a
 * opcao estava marcada.</p>
 */
class HttpClientConfigTest {

    private static final int OVER_LIMIT_BYTES = ResponseSizeLimitInterceptor.DEFAULT_MAX_RESPONSE_BYTES + 4096;

    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static String baseUrl;
    private static final AtomicInteger REDIRECT_TARGET_HITS = new AtomicInteger();
    private static final AtomicReference<String> SENT_COOKIE = new AtomicReference<>();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class,
                    RestClientAutoConfiguration.class))
            .withUserConfiguration(HttpClientConfig.class);

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/small", exchange -> respondWithLength(exchange, "ok".repeat(16)));
        server.createContext("/chunked-oversized", exchange -> respondChunked(exchange, OVER_LIMIT_BYTES));
        server.createContext("/declared-oversized", exchange -> respondWithLength(exchange,
                "x".repeat(OVER_LIMIT_BYTES)));
        server.createContext("/sets-cookie", exchange -> {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie != null) {
                SENT_COOKIE.set(cookie);
            }
            exchange.getResponseHeaders().add("Set-Cookie", "sessao=segredo; Path=/");
            respondWithLength(exchange, "ok");
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/redirect-target");
            exchange.sendResponseHeaders(HttpStatus.FOUND.value(), -1);
            exchange.close();
        });
        server.createContext("/redirect-target", exchange -> {
            REDIRECT_TARGET_HITS.incrementAndGet();
            respondWithLength(exchange, "seguiu o redirecionamento");
        });
        serverExecutor = Executors.newFixedThreadPool(4);
        server.setExecutor(serverExecutor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    // --- politicas do cliente de saida ---

    @Test
    void shouldExposeHardenedHttpClientAndRestClient() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RestClient.class)
                    .hasSingleBean(CloseableHttpClient.class)
                    .hasSingleBean(PinnedDnsResolver.class)
                    .hasSingleBean(HttpTargetValidator.class)
                    .hasSingleBean(HttpRequestNodeExecutor.class)
                    .hasSingleBean(ResponseSizeLimitInterceptor.class);

            assertThat(context.getBean(ResponseSizeLimitInterceptor.class).maxResponseBytes())
                    .isEqualTo(256 * 1024);
        });
    }

    /**
     * O {@code Set-Cookie} de uma resposta nao volta na requisicao seguinte.
     *
     * <p>Substitui, por comportamento, o que antes era afirmado lendo {@code cookieHandler()} do
     * cliente do JDK. O risco e concreto e nao teorico: um cliente com armazenamento de cookies e
     * estado compartilhado por todas as requisicoes de todos os workflows, entao o cookie de sessao
     * que um no recebeu seria reenviado por um no de <i>outro</i> workflow, de outro dono, para
     * outro host.</p>
     */
    @Test
    void shouldNotCarryCookiesFromOneResponseIntoTheNextRequest() {
        contextRunner.run(context -> {
            RestClient restClient = context.getBean(RestClient.class);
            SENT_COOKIE.set(null);

            restClient.get().uri(baseUrl + "/sets-cookie").retrieve().toBodilessEntity();
            restClient.get().uri(baseUrl + "/sets-cookie").retrieve().toBodilessEntity();

            assertThat(SENT_COOKIE.get())
                    .as("o cookie da primeira resposta voltou na segunda requisicao")
                    .isNull();
        });
    }

    @Test
    void timeoutsShouldBeBounded() {
        assertThat(HttpClientConfig.CONNECT_TIMEOUT).hasSeconds(5);
        assertThat(HttpClientConfig.REQUEST_TIMEOUT).hasSeconds(10);
    }

    // --- flag de http inseguro ---

    @Test
    void validatorShouldRejectHttpByDefault() {
        contextRunner.run(context -> {
            HttpTargetValidator validator = context.getBean(HttpTargetValidator.class);
            assertThat(catchThrowable(() -> validator.validate("http://8.8.8.8/hook")))
                    .isInstanceOf(HttpTargetNotAllowedException.class);
        });
    }

    @Test
    void validatorShouldAcceptHttpWhenPropertyIsOn() {
        contextRunner.withPropertyValues("nexio.http.allow-insecure-http=true").run(context -> {
            HttpTargetValidator validator = context.getBean(HttpTargetValidator.class);
            assertThat(validator.validate("http://8.8.8.8/hook")).isNotNull();
        });
    }

    // --- comportamento real contra um servidor HTTP ---

    @Test
    void shouldReadResponseUnderTheSizeLimit() {
        contextRunner.run(context -> {
            String body = context.getBean(RestClient.class)
                    .get()
                    .uri(baseUrl + "/small")
                    .retrieve()
                    .body(String.class);

            assertThat(body).isEqualTo("ok".repeat(16));
        });
    }

    /**
     * Resposta sem {@code Content-Length} (chunked): o unico jeito de barra-la e contando os bytes
     * lidos, que e exatamente o que o interceptor faz.
     */
    @Test
    void shouldRejectOversizedChunkedResponse() {
        contextRunner.run(context -> {
            RestClient restClient = context.getBean(RestClient.class);

            Throwable thrown = catchThrowable(() -> restClient.get()
                    .uri(baseUrl + "/chunked-oversized")
                    .retrieve()
                    .body(String.class));

            assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
                    .isInstanceOf(ResponseSizeLimitExceededException.class);
        });
    }

    @Test
    void shouldRejectOversizedDeclaredResponse() {
        contextRunner.run(context -> {
            RestClient restClient = context.getBean(RestClient.class);

            Throwable thrown = catchThrowable(() -> restClient.get()
                    .uri(baseUrl + "/declared-oversized")
                    .retrieve()
                    .body(String.class));

            assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
                    .isInstanceOf(ResponseSizeLimitExceededException.class);
        });
    }

    /**
     * Seguir o {@code 302} de forma transparente anularia a validacao de destino feita antes da
     * requisicao, entao o {@code 3xx} tem que voltar como resposta comum.
     */
    @Test
    void shouldNotFollowRedirects() {
        contextRunner.run(context -> {
            REDIRECT_TARGET_HITS.set(0);

            var response = context.getBean(RestClient.class)
                    .get()
                    .uri(baseUrl + "/redirect")
                    .retrieve()
                    .toBodilessEntity();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(response.getHeaders().getFirst("Location")).endsWith("/redirect-target");
            assertThat(REDIRECT_TARGET_HITS).hasValue(0);
        });
    }

    private static void respondWithLength(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(HttpStatus.OK.value(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Corpo sem tamanho declarado: {@code sendResponseHeaders} com length zero liga o
     * {@code Transfer-Encoding: chunked}.
     */
    private static void respondChunked(HttpExchange exchange, int size) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(HttpStatus.OK.value(), 0);
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'x');
        try (OutputStream out = exchange.getResponseBody()) {
            int written = 0;
            while (written < size) {
                int length = Math.min(chunk.length, size - written);
                out.write(chunk, 0, length);
                written += length;
            }
        }
    }
}
