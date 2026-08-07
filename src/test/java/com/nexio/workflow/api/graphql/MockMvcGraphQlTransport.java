package com.nexio.workflow.api.graphql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.graphql.GraphQlRequest;
import org.springframework.graphql.GraphQlResponse;
import org.springframework.graphql.client.GraphQlTransport;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Transporte de {@link GraphQlTester} que entrega a consulta ao {@link MockMvc}.
 *
 * <p>Existe porque nao ha, nesta versao do Spring Boot, um {@code GraphQlTester} pronto sobre
 * {@code MockMvc}: o {@code HttpGraphQlTester} so existe sobre um {@code WebTestClient}, que vive no
 * spring-test mas depende das classes reativas do spring-webflux, e o
 * {@code ExecutionGraphQlServiceTester} chama o servico de execucao direto, pulando a pilha do
 * servlet inteira. Nenhuma das duas serve: a primeira arrastaria a pilha reativa para dentro de uma
 * aplicacao deliberadamente servlet, e a segunda deixaria {@code RequestSizeLimitFilter} e as
 * instrumentacoes de custo por consulta configurados sem nunca serem exercitados.</p>
 *
 * <p>O {@code MockMvc} atravessa a cadeia de filtros completa, o {@code GraphQlHttpHandler} e a
 * serializacao do corpo exatamente como a de um cliente; o que ele nao tem e o conector, ou seja, o
 * socket de verdade -- e o conector nao e codigo desta aplicacao.</p>
 *
 * <p>Assincronia nao e simulada: o {@link Mono} devolvido ja vem resolvido, porque o
 * {@code GraphQlTester} bloqueia nele em seguida e {@code MockMvc} e sincrono por natureza.</p>
 */
final class MockMvcGraphQlTransport implements GraphQlTransport {

    private static final TypeReference<Map<String, Object>> RESPONSE_BODY = new TypeReference<>() { };

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final String path;

    private MockMvcGraphQlTransport(MockMvc mockMvc, ObjectMapper objectMapper, String path) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.path = path;
    }

    /**
     * Monta um {@code GraphQlTester} que entra pelo endpoint GraphQL da aplicacao.
     *
     * @param mockMvc      cadeia de servlet montada pelo {@code @AutoConfigureMockMvc}
     * @param objectMapper serializador do contexto, o mesmo que a aplicacao usa
     * @param path         caminho do endpoint GraphQL
     * @return tester pronto para uso
     */
    static GraphQlTester tester(MockMvc mockMvc, ObjectMapper objectMapper, String path) {
        return GraphQlTester.builder(new MockMvcGraphQlTransport(mockMvc, objectMapper, path)).build();
    }

    @Override
    public Mono<GraphQlResponse> execute(GraphQlRequest request) {
        try {
            MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_GRAPHQL_RESPONSE, MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request.toMap())))
                    .andReturn()
                    .getResponse();
            return Mono.just(GraphQlTransport.createResponse(body(response)));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    @Override
    public Flux<GraphQlResponse> executeSubscription(GraphQlRequest request) {
        throw new UnsupportedOperationException("O schema nao publica subscription");
    }

    /**
     * Le o corpo como resposta GraphQL, exigindo antes o 200.
     *
     * <p>Erro de execucao e de validacao sai como 200 com a lista {@code errors}, que e o que os
     * testes conferem; qualquer outro status significa que a requisicao morreu antes disso -- num
     * filtro, por exemplo --, e ai o corpo nao e uma resposta GraphQL e desembrulha-lo esconderia a
     * causa.</p>
     */
    private Map<String, Object> body(MockHttpServletResponse response) throws Exception {
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        if (response.getStatus() != HttpStatus.OK.value()) {
            throw new IllegalStateException(
                    "Resposta HTTP " + response.getStatus() + " de " + path + ": " + content);
        }
        return objectMapper.readValue(content, RESPONSE_BODY);
    }
}
