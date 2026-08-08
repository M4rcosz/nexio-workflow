package com.nexio.workflow.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.nexio.workflow.application.engine.NodeExecutionContext;
import com.nexio.workflow.application.engine.NodeExecutionResult;
import com.nexio.workflow.domain.model.MapSanitizer;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.HttpMethod;
import com.nexio.workflow.domain.model.enums.NodeType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Teste do executor de nos HTTP contra um servidor de verdade.
 *
 * <p>O servidor e o {@link HttpServer} do JDK em {@code 127.0.0.1}, e por isso o
 * {@link HttpTargetValidator} de producao nao serve aqui: ele recusa loopback, que e exatamente o
 * trabalho dele. O duble {@link FixedTargetValidator} aprova o destino e devolve o endereco do
 * servidor de teste; o validador de verdade tem a propria suite.</p>
 *
 * <p>O resolvedor de DNS montado neste teste <b>recusa qualquer nome</b>. E deliberado: se alguma
 * requisicao chegar ao servidor, a unica explicacao possivel e que a conexao usou o endereco
 * fixado, e nao uma segunda consulta. E o que prova que o DNS rebinding esta fechado.</p>
 */
class HttpRequestNodeExecutorTest {

    private HttpServer server;
    private HttpRequestNodeExecutor executor;
    private final AtomicReference<Handler> handler = new AtomicReference<>();
    private final List<String> receivedHeaders = new ArrayList<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedMethod = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    /** O que o servidor de teste responde numa chamada. */
    private record Response(int status, String contentType, String body) {
    }

    private interface Handler {
        Response handle();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::respond);
        server.start();
        executor = new HttpRequestNodeExecutor(restClient(), new FixedTargetValidator(port()));
        handler.set(() -> new Response(200, "application/json", "{\"total\":150}"));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        PinnedDnsResolver.clear();
    }

    /**
     * A requisicao chega ao servidor mesmo com toda resolucao de nome falhando.
     *
     * <p>Este e o teste do DNS rebinding, escrito ao contrario: o resolvedor delegado recusa
     * {@code servico.test}, entao uma segunda consulta de DNS -- que e a janela que o rebinding
     * usa -- faria a chamada falhar. Ela nao falha, logo a conexao saiu para o endereco que a
     * validacao aprovou.</p>
     */
    @Test
    void connectsToTheValidatedAddressEvenWhenNameResolutionWouldFail() {
        NodeExecutionResult result = executor.execute(getNode(), context());

        assertThat(result.outcome().name()).isEqualTo("SUCCESS");
        assertThat(result.output()).containsEntry("statusCode", 200);
        assertThat(asMap(result.output().get("body"))).containsEntry("total", 150);
    }

    /** A fixacao nao pode sobreviver a chamada: thread de servidor e reaproveitada. */
    @Test
    void clearsThePinAfterTheCallSoTheNextExecutionOnThisThreadIsNotAffected() throws Exception {
        executor.execute(getNode(), context());

        DnsResolver resolver = new PinnedDnsResolver(new RefusingDnsResolver());
        assertThatCode(() -> resolver.resolve("servico.test"))
                .as("a fixacao vazou para depois da chamada")
                .isInstanceOf(UnknownHostException.class);
    }

    /** Um corpo HAL chega inteiro ao passo, sem as chaves que a gravacao nao aceita. */
    @Test
    void storesAHalResponseInsteadOfKillingTheExecution() {
        handler.set(() -> new Response(200, "application/json",
                "{\"_links\":{\"self\":{\"href\":\"/x\"}},\"total\":150,\"status\":\"pago\"}"));

        NodeExecutionResult result = executor.execute(getNode(), context());

        assertThat(result.outcome().name()).isEqualTo("SUCCESS");
        assertThat(asMap(result.output().get("body")))
                .containsEntry("total", 150)
                .containsEntry("status", "pago")
                .doesNotContainKey("_links");
        assertThat(result.output()).containsEntry("droppedKeys", 1);
    }

    /**
     * A garantia que faz a execucao nao morrer por causa do formato da resposta.
     *
     * <p>Todo {@code output} produzido aqui tem que passar pela politica estrita, senao o
     * {@code appendStep} recusa o passo e a execucao falha <i>depois</i> de a chamada ja ter dado
     * certo -- com o efeito colateral do outro lado ja aplicado.</p>
     */
    @Test
    void everyOutputItProducesCanBeStored() {
        List<String> bodies = List.of(
                "{\"_links\":{\"self\":\"x\"},\"$ref\":\"#/y\"}",
                "{\"n\":" + "9".repeat(40) + "}",
                "{\"t\":\"" + "a".repeat(MapSanitizer.MAX_STRING_LENGTH + 100) + "\"}",
                wideJson(600),
                deepJson(40),
                "[1,2,3]",
                "{}",
                "nao e json");

        for (String body : bodies) {
            handler.set(() -> new Response(200, "application/json", body));
            NodeExecutionResult result = executor.execute(getNode(), context());

            assertThatCode(() -> MapSanitizer.validate(result.output(), "steps[].output"))
                    .as(body.substring(0, Math.min(40, body.length())))
                    .doesNotThrowAnyException();
        }
    }

    /** Um {@code 500} falha o no, mas o corpo fica gravado: e o passo que alguem vai querer ler. */
    @Test
    void aServerErrorFailsTheNodeWhileKeepingTheResponseBody() {
        handler.set(() -> new Response(500, "application/json", "{\"erro\":\"banco fora do ar\"}"));

        NodeExecutionResult result = executor.execute(getNode(), context());

        assertThat(result.outcome().name()).isEqualTo("FAILURE");
        assertThat(result.error()).contains("500");
        assertThat(result.output()).containsEntry("statusCode", 500);
        assertThat(asMap(result.output().get("body"))).containsEntry("erro", "banco fora do ar");
    }

    /**
     * Redirecionamento nao e seguido, e o {@code location} fica gravado.
     *
     * <p>Segui-lo em silencio anularia a validacao de destino: um {@code 302} para o servico de
     * metadados da nuvem sairia sem passar por checagem nenhuma. Sem gravar o {@code location}, o
     * passo seria um {@code 302} sem explicacao.</p>
     */
    @Test
    void doesNotFollowARedirectAndRecordsWhereItPointed() {
        handler.set(() -> new Response(302, null, ""));

        NodeExecutionResult result = executor.execute(getNode(), context());

        assertThat(result.outcome().name()).isEqualTo("FAILURE");
        assertThat(result.output()).containsEntry("statusCode", 302);
        assertThat(asMap(result.output().get("headers")))
                .containsEntry("location", "http://169.254.169.254/latest/meta-data/");
        // Uma requisicao so: se o redirecionamento tivesse sido seguido, o servidor de teste teria
        // sido chamado de novo -- ou, pior, o servico de metadados da nuvem teria sido.
        assertThat(requestCount.get()).isEqualTo(1);
    }

    /** Resposta que nao e JSON vira texto: pagina de erro em HTML e o caso mais comum. */
    @Test
    void keepsANonJsonBodyAsText() {
        handler.set(() -> new Response(503, "text/html", "<html>manutencao</html>"));

        NodeExecutionResult result = executor.execute(getNode(), context());

        assertThat(result.output()).containsEntry("body", "<html>manutencao</html>");
    }

    @Test
    void sendsTheDeclaredHeadersAndBody() {
        WorkflowNode node = new WorkflowNode("chama", NodeType.HTTP_REQUEST,
                "http://servico.test/pedidos", HttpMethod.POST,
                Map.of("X-Chave", "valor-1"), Map.of("total", 150),
                null, Map.of(), null, null, null);

        executor.execute(node, context());

        assertThat(receivedMethod.get()).isEqualTo("POST");
        assertThat(receivedHeaders).anyMatch(h -> h.equalsIgnoreCase("X-Chave: valor-1"));
        assertThat(receivedBody.get()).contains("\"total\":150");
    }

    /**
     * Quebra de linha num cabecalho e recusada antes de a requisicao sair.
     *
     * <p>Sem isso, um valor com {@code \r\n} injeta cabecalhos inteiros dentro da requisicao, e o
     * valor vem da config de um no, ou seja, de entrada do usuario.</p>
     */
    @Test
    void refusesAHeaderValueCarryingALineBreak() {
        WorkflowNode node = new WorkflowNode("chama", NodeType.HTTP_REQUEST,
                "http://servico.test/x", HttpMethod.GET,
                Map.of("X-Mau", "valor\r\nX-Injetado: sim"), Map.of(),
                null, Map.of(), null, null, null);

        NodeExecutionResult result = executor.execute(node, context());

        assertThat(result.outcome().name()).isEqualTo("FAILURE");
        assertThat(result.error()).contains("Cabecalho invalido");
        assertThat(receivedHeaders).isEmpty();
    }

    /** Destino recusado pela validacao vira falha de no, e nao excecao subindo pela engine. */
    @Test
    void aRefusedTargetBecomesANodeFailure() {
        HttpRequestNodeExecutor strict =
                new HttpRequestNodeExecutor(restClient(), new HttpTargetValidator(true));

        NodeExecutionResult result = strict.execute(
                node("http://169.254.169.254/latest/meta-data/"), context());

        assertThat(result.outcome().name()).isEqualTo("FAILURE");
        assertThat(result.error()).contains("Destino recusado");
    }

    /** Servidor fora do ar e falha esperada de chamada a terceiro, nao erro interno. */
    @Test
    void aConnectionFailureBecomesANodeFailure() {
        server.stop(0);

        NodeExecutionResult result = executor.execute(getNode(), context());

        assertThat(result.outcome().name()).isEqualTo("FAILURE");
        assertThat(result.error()).contains("Falha ao chamar o destino");
    }

    @Test
    void answersForHttpRequestNodes() {
        assertThat(executor.supportedType()).isEqualTo(NodeType.HTTP_REQUEST);
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        receivedMethod.set(exchange.getRequestMethod());
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (name.startsWith("X-")) {
                receivedHeaders.add(name + ": " + String.join(",", values));
            }
        });
        receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        Response response = handler.get().handle();
        if (response.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
        }
        if (response.status() == 302) {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
        }
        exchange.getResponseHeaders().add("Set-Cookie", "sessao=segredo");
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private int port() {
        return server.getAddress().getPort();
    }

    /**
     * Cliente igual ao de producao, exceto por um detalhe deliberado: o resolvedor delegado recusa
     * todo nome, para que so a fixacao possa explicar uma conexao bem-sucedida.
     */
    private static RestClient restClient() {
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new PinnedDnsResolver(new RefusingDnsResolver()))
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.of(Duration.ofSeconds(2)))
                                .build())
                        .build())
                .disableRedirectHandling()
                .disableCookieManagement()
                .build();
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(client))
                .build();
    }

    private static NodeExecutionContext context() {
        return new NodeExecutionContext("exec-1", Map.of(), Map.of());
    }

    private WorkflowNode getNode() {
        return node("http://servico.test/pedidos");
    }

    private static WorkflowNode node(String url) {
        return WorkflowNode.httpRequest("chama", url, HttpMethod.GET, null, null, null);
    }

    private static String wideJson(int size) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < size; i++) {
            json.append(i > 0 ? "," : "").append("\"c").append(i).append("\":").append(i);
        }
        return json.append("}").toString();
    }

    private static String deepJson(int depth) {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            json.append("{\"n\":");
        }
        json.append("1");
        json.append("}".repeat(depth));
        return json.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** Aprova o destino e devolve o endereco do servidor de teste. */
    private static final class FixedTargetValidator extends HttpTargetValidator {

        private final int port;

        private FixedTargetValidator(int port) {
            super(true);
            this.port = port;
        }

        @Override
        public ValidatedTarget validateAndResolve(String url) {
            URI original = URI.create(url);
            URI target = URI.create("http://" + original.getHost() + ":" + port + original.getRawPath());
            return new ValidatedTarget(target, List.of(InetAddress.getLoopbackAddress()));
        }
    }

    /** Recusa toda resolucao: qualquer consulta de DNS neste teste e um defeito. */
    private static final class RefusingDnsResolver implements DnsResolver {

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            throw new UnknownHostException("resolucao de nome nao deveria acontecer: " + host);
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }
}
