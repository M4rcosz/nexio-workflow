package com.nexio.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexio.workflow.api.graphql.MockMvcGraphQlTransport;
import com.nexio.workflow.infrastructure.http.HttpTargetValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Um workflow inteiro, de ponta a ponta: disparo por evento, no HTTP, condicao e o ramo escolhido.
 *
 * <p>E o teste que nenhum dos outros substitui, porque tudo o que ele exercita ja tem teste
 * <i>isolado</i> e o que costuma quebrar e a juncao. Aqui passam, numa unica execucao: a mutation
 * GraphQL, o caso de uso, a engine, a resolucao de marcadores, a chamada HTTP de verdade contra um
 * servidor de verdade, a adaptacao do corpo de resposta, a condicao lendo a saida do no anterior, a
 * escolha do ramo, a gravacao de cada passo no MongoDB e a leitura de volta ja redigida. Este
 * projeto ja foi mordido quatro vezes por defeitos que moram <i>entre</i> camadas e que toda camada,
 * testada sozinha, considerava resolvidos.</p>
 *
 * <h2>Por que o validador de destino e substituido</h2>
 *
 * <p>O servidor de apoio roda em {@code 127.0.0.1}, e o {@link HttpTargetValidator} de producao
 * recusa loopback -- que e precisamente o trabalho dele. O duble aprova o destino e aponta para a
 * porta do servidor de teste; o validador de verdade tem a propria suite, incluindo os literais em
 * forma decimal e as faixas de IPv6. Substituir aqui e trocar "nao consigo testar o fluxo" por
 * "testo o fluxo com a checagem de destino coberta em outro lugar".</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EndToEndWorkflowTest.LoopbackTargetConfig.class)
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class EndToEndWorkflowTest extends AbstractMongoIntegrationTest {

    private static final HttpServer SERVER;
    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    static {
        // Bloco estatico e nao @BeforeAll: o contexto do Spring e criado antes dos @BeforeAll do
        // usuario, e a configuracao do duble precisa da porta ja escolhida.
        try {
            SERVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SERVER.createContext("/pedido", exchange -> respond(exchange, "{\"total\":150,\"moeda\":\"BRL\"}"));
        SERVER.createContext("/cobranca-alta", exchange -> respond(exchange, "{\"cobrado\":true}"));
        SERVER.createContext("/cobranca-baixa", exchange -> respond(exchange, "{\"cobrado\":false}"));
        SERVER.start();
    }

    private static final String CREATE = """
            mutation Criar($input: CreateWorkflowInput!) {
              createWorkflow(input: $input) { id }
            }
            """;

    private static final String TRIGGER = """
            mutation Disparar($id: ID!, $payload: JSON) {
              triggerWorkflow(id: $id, payload: $payload) {
                id status errorMessage
                steps { nodeId status output }
              }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${spring.graphql.path:/graphql}")
    private String graphQlPath;

    private GraphQlTester graphQlTester;

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection("workflow_definitions").deleteMany(new Document());
        mongoTemplate.getCollection("workflow_executions").deleteMany(new Document());
        RECEIVED.clear();
        graphQlTester = MockMvcGraphQlTransport.tester(mockMvc, objectMapper, graphQlPath);
    }

    /**
     * Consulta o pedido, avalia o total e cobra pelo ramo caro.
     *
     * <p>A condicao le a saida do no anterior, e nao o payload: e o caso que prova que os nos
     * conversam. E a URL do primeiro no e templatizada, entao o caminho recebido pelo servidor
     * tambem prova que o marcador foi resolvido com o valor do evento.</p>
     */
    @Test
    void aTriggeredWorkflowCallsTheServiceEvaluatesTheConditionAndTakesTheExpensiveBranch() {
        String id = createWorkflow();

        graphQlTester.document(TRIGGER)
                .variable("id", id)
                .variable("payload", Map.of("pedidoId", "PED-1"))
                .execute()
                .path("triggerWorkflow.status").entity(String.class).isEqualTo("SUCCESS")
                .path("triggerWorkflow.steps[*].nodeId").entityList(String.class)
                .containsExactly("consulta", "checa", "cobranca-alta");

        // O marcador foi resolvido com o valor do evento antes de a requisicao sair.
        assertThat(RECEIVED).containsExactly("/pedido/PED-1", "/cobranca-alta");
    }

    /** O corpo do servico de terceiro chega ao passo, e a condicao decide a partir dele. */
    @Test
    void theResponseBodyReachesTheStepAndDrivesTheCondition() {
        String id = createWorkflow();

        List<Map<String, Object>> steps = graphQlTester.document(TRIGGER)
                .variable("id", id)
                .variable("payload", Map.of("pedidoId", "PED-9"))
                .execute()
                .path("triggerWorkflow.steps")
                .entityList(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { })
                .get();

        Map<String, Object> consulta = asMap(steps.getFirst().get("output"));
        assertThat(consulta).containsEntry("statusCode", 200);
        assertThat(asMap(consulta.get("body"))).containsEntry("total", 150).containsEntry("moeda", "BRL");
        // O passo da condicao guarda o booleano avaliado: e o que explica por que foi por ali.
        assertThat(asMap(steps.get(1).get("output"))).containsEntry("result", true);
    }

    /** A execucao fica gravada e e legivel depois pelo historico. */
    @Test
    void theExecutionIsStoredAndReadableAfterwards() {
        String id = createWorkflow();
        String executionId = graphQlTester.document(TRIGGER)
                .variable("id", id)
                .variable("payload", Map.of("pedidoId", "PED-2"))
                .execute()
                .path("triggerWorkflow.id").entity(String.class).get();

        graphQlTester.document("""
                query Buscar($id: ID!) {
                  execution(id: $id) { id workflowId status steps { nodeId status } }
                }
                """)
                .variable("id", executionId)
                .execute()
                .path("execution.status").entity(String.class).isEqualTo("SUCCESS")
                .path("execution.steps[*].nodeId").entityList(String.class)
                .containsExactly("consulta", "checa", "cobranca-alta");
    }

    private String createWorkflow() {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("name", "cobranca por valor");
        input.put("enabled", true);
        input.put("trigger", Map.of("type", "MOCK_EVENT", "config", Map.of()));
        input.put("startNodeId", "consulta");
        input.put("nodes", List.of(
                Map.of("id", "consulta", "type", "HTTP_REQUEST",
                        "url", "http://servico.test/pedido/{{trigger.pedidoId}}",
                        "method", "GET", "nextOnSuccess", "checa"),
                Map.of("id", "checa", "type", "CONDITION",
                        "expression", "#outputs['consulta']['body']['total'] > 100",
                        "nextOnTrue", "cobranca-alta", "nextOnFalse", "cobranca-baixa"),
                Map.of("id", "cobranca-alta", "type", "HTTP_REQUEST",
                        "url", "http://servico.test/cobranca-alta", "method", "POST"),
                Map.of("id", "cobranca-baixa", "type", "HTTP_REQUEST",
                        "url", "http://servico.test/cobranca-baixa", "method", "POST")));

        return graphQlTester.document(CREATE)
                .variable("input", input)
                .execute()
                .path("createWorkflow.id").entity(String.class).get();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        RECEIVED.add(exchange.getRequestURI().getPath());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** Aprova o destino e aponta para o servidor de teste. Ver o cabecalho da classe. */
    @TestConfiguration
    static class LoopbackTargetConfig {

        @Bean
        HttpTargetValidator httpTargetValidator() {
            return new HttpTargetValidator(true) {
                @Override
                public ValidatedTarget validateAndResolve(String url) {
                    URI original = URI.create(url);
                    String path = original.getRawPath() == null ? "" : original.getRawPath();
                    URI target = URI.create(
                            "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                                    + ":" + SERVER.getAddress().getPort() + path);
                    return new ValidatedTarget(target, List.of(InetAddress.getLoopbackAddress()));
                }
            };
        }
    }

}
