package com.nexio.workflow.infrastructure.http;

import com.nexio.workflow.application.engine.NodeExecutionContext;
import com.nexio.workflow.application.engine.NodeExecutionResult;
import com.nexio.workflow.application.engine.NodeExecutor;
import com.nexio.workflow.domain.model.ExternalDataSanitizer;
import com.nexio.workflow.domain.model.MapSanitizer;
import com.nexio.workflow.domain.model.TextSanitizer;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.NodeType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Executa um no {@code HTTP_REQUEST}: valida o destino, chama, e registra o que voltou.
 *
 * <h2>A ordem importa</h2>
 *
 * <p>Validar, <b>fixar</b> e so entao chamar. A fixacao no meio nao e detalhe: sem ela o cliente
 * resolveria o host uma segunda vez ao abrir a conexao, e quem controla o DNS do dominio responde
 * endereco publico na validacao e {@code 169.254.169.254} na conexao. Ver
 * {@link PinnedDnsResolver}. Por isso este executor usa
 * {@link HttpTargetValidator#validateAndResolve(String)} e nunca
 * {@link HttpTargetValidator#validate(String)}, que joga os enderecos fora.</p>
 *
 * <p>A limpeza da fixacao fica em {@code finally} e nao e opcional: thread de servidor e
 * reaproveitada, e uma fixacao esquecida faria a proxima execucao daquela thread conectar no
 * endereco validado para outro workflow.</p>
 *
 * <h2>O formato do que fica gravado</h2>
 *
 * <pre>{@code
 * { statusCode: 200,
 *   headers: { 'content-type': 'application/json' },
 *   body: { ...corpo da resposta... },
 *   truncated: false,
 *   droppedKeys: 0 }
 * }</pre>
 *
 * <p>O formato e sempre este, inclusive na falha: um {@code 500} e o passo mais interessante da
 * execucao inteira, e guardar o corpo so no sucesso jogaria fora justamente o diagnostico.</p>
 *
 * <p><b>O corpo passa pelo {@link ExternalDataSanitizer} e nao pela politica estrita.</b> A politica
 * estrita e a regra certa para o que o usuario escreve e a regra errada para a resposta de um
 * terceiro: {@code _links} de qualquer API HAL, {@code $ref} de JSON Schema, corpo com mais de
 * {@value MapSanitizer#MAX_ENTRIES} entradas ou um inteiro grande demais para {@code long} seriam
 * todos recusados na gravacao do passo -- ou seja, a chamada teria dado certo, o efeito colateral do
 * outro lado ja teria acontecido, e a execucao morreria por causa do formato da resposta. O que nao
 * cabe e cortado e contado em {@code truncated} e {@code droppedKeys}, para que a perda apareca no
 * proprio passo.</p>
 *
 * <p>Dos cabecalhos de resposta fica so uma lista curta. {@code location} esta nela porque
 * redirecionamento nao e seguido: sem ele um {@code 302} viraria um passo sem explicacao nenhuma.
 * O que nao esta na lista nao chega a ser gravado, entao {@code set-cookie} e eco de credencial
 * nao vazam por omissao de regra -- a diferenca entre uma lista de permissao e o
 * {@code SecretRedactor}, que e lista de bloqueio por nome.</p>
 */
public class HttpRequestNodeExecutor implements NodeExecutor {

    /**
     * Cabecalhos de resposta que valem a pena guardar.
     *
     * <p>{@code content-type} explica como o corpo foi interpretado; {@code location} explica um
     * {@code 3xx}; o identificador de correlacao e o que permite pedir ajuda ao dono da API do
     * outro lado.</p>
     */
    private static final List<String> KEPT_HEADERS =
            List.of("content-type", "location", "x-request-id", "x-correlation-id");

    /**
     * Entradas reservadas para o que e gravado ao lado do corpo.
     *
     * <p>O teto de {@value MapSanitizer#MAX_ENTRIES} entradas da politica estrita vale para o
     * {@code output} inteiro, somando todos os niveis. Dar o teto cheio de orcamento ao corpo
     * estoura o limite por poucas entradas -- {@code statusCode}, {@code headers} e os cabecalhos
     * guardados, {@code truncated} e {@code droppedKeys} tambem contam.</p>
     */
    private static final int RESERVED_ENTRIES = 8;

    /** O corpo fica um nivel dentro do {@code output}, entao sobra um nivel a menos para ele. */
    private static final int BODY_MAX_DEPTH = MapSanitizer.MAX_DEPTH - 1;

    private static final int MAX_ERROR_LENGTH = 200;

    private final RestClient restClient;
    private final HttpTargetValidator targetValidator;

    /**
     * Cria o executor.
     *
     * @param restClient      cliente de saida ja endurecido
     * @param targetValidator guarda de SSRF
     */
    public HttpRequestNodeExecutor(RestClient restClient, HttpTargetValidator targetValidator) {
        this.restClient = Objects.requireNonNull(restClient, "restClient nao pode ser nulo");
        this.targetValidator =
                Objects.requireNonNull(targetValidator, "targetValidator nao pode ser nulo");
    }

    @Override
    public NodeType supportedType() {
        return NodeType.HTTP_REQUEST;
    }

    @Override
    public NodeExecutionResult execute(WorkflowNode node, NodeExecutionContext context) {
        HttpTargetValidator.ValidatedTarget target;
        try {
            target = targetValidator.validateAndResolve(node.url());
        } catch (HttpTargetNotAllowedException e) {
            // A validacao de escrita ja recusa o destino interno declarado como literal, mas nao o
            // nome que so resolve para endereco interno na hora do disparo -- e o endereco pode ter
            // mudado desde a gravacao. Por isso a checagem roda de novo aqui, e a recusa e falha de
            // no, nao excecao subindo.
            return NodeExecutionResult.failure(
                    "Destino recusado no no '" + node.nodeId() + "': " + e.getMessage());
        }

        HttpHeaders requestHeaders;
        try {
            requestHeaders = requestHeaders(node);
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.failure(
                    "Cabecalho invalido no no '" + node.nodeId() + "': " + e.getMessage());
        }

        PinnedDnsResolver.pin(target.uri().getHost(), target.addresses());
        try {
            return call(node, target, requestHeaders);
        } catch (RuntimeException e) {
            // Tempo limite, conexao recusada, TLS recusado, corpo acima do teto: tudo isso e falha
            // esperada de uma chamada a terceiro, e o contrato do NodeExecutor pede que volte como
            // resultado de falha em vez de excecao.
            return NodeExecutionResult.failure(
                    "Falha ao chamar o destino do no '" + node.nodeId() + "': " + describe(e));
        } finally {
            PinnedDnsResolver.clear();
        }
    }

    private NodeExecutionResult call(WorkflowNode node,
                                     HttpTargetValidator.ValidatedTarget target,
                                     HttpHeaders requestHeaders) {
        HttpMethod method = HttpMethod.valueOf(node.method().name());
        RestClient.RequestBodySpec request = restClient.method(method)
                .uri(target.uri())
                .headers(headers -> headers.addAll(requestHeaders));
        if (!node.body().isEmpty()) {
            request.contentType(MediaType.APPLICATION_JSON).body(node.body());
        }

        // exchange(fn) fecha a resposta depois de aplicar a funcao. A sobrecarga que recebe
        // `close` existe para quem quer manter o fluxo aberto, e passar false aqui vazaria uma
        // conexao do pool por requisicao.
        return request.exchange((clientRequest, response) ->
                result(node, response.getStatusCode(), response.getHeaders(), readBody(response)));
    }

    /**
     * Le o corpo conforme o tipo declarado pelo servidor.
     *
     * <p>JSON vira mapa, e e o unico formato que uma condicao consegue percorrer. Qualquer outra
     * coisa vira texto: uma pagina de erro em HTML e o caso mais comum de resposta que nao e JSON,
     * e ela e exatamente o que alguem quer ler ao investigar. Corpo ilegivel nao derruba o passo --
     * o codigo de status sozinho ja e informacao.</p>
     */
    private static Object readBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        MediaType contentType = response.getHeaders().getContentType();
        try {
            if (contentType != null && contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                return response.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() { });
            }
            return response.bodyTo(String.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static NodeExecutionResult result(WorkflowNode node,
                                              HttpStatusCode status,
                                              HttpHeaders responseHeaders,
                                              Object body) {
        Map<String, Object> keptHeaders = keptHeaders(responseHeaders);
        int budget = MapSanitizer.MAX_ENTRIES - RESERVED_ENTRIES - keptHeaders.size();
        ExternalDataSanitizer.Sanitized sanitized =
                ExternalDataSanitizer.sanitize(body, budget, BODY_MAX_DEPTH);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("statusCode", status.value());
        output.put("headers", keptHeaders);
        output.put("body", sanitized.value());
        output.put("truncated", sanitized.truncated());
        output.put("droppedKeys", sanitized.droppedKeys());

        if (status.is2xxSuccessful()) {
            return NodeExecutionResult.success(output);
        }
        // 3xx entra aqui de proposito: redirecionamento nao e seguido, porque segui-lo em silencio
        // anularia a validacao de destino -- um 302 para o servico de metadados da nuvem sairia sem
        // passar por checagem nenhuma. O `location` fica gravado para que o passo seja explicavel.
        return NodeExecutionResult.failure(
                "O no '" + node.nodeId() + "' recebeu HTTP " + status.value(), output);
    }

    private static Map<String, Object> keptHeaders(HttpHeaders headers) {
        Map<String, Object> kept = new LinkedHashMap<>();
        for (String name : KEPT_HEADERS) {
            String value = headers.getFirst(name);
            if (value != null) {
                // Sem redacao aqui: a politica do projeto e que o que esta gravado fica intacto e a
                // redacao acontece na leitura, porque e o valor gravado que a execucao usa. Ver
                // SecretRedactor. Nenhum nome desta lista carrega credencial por convencao.
                kept.put(name, TextSanitizer.truncateSystemText(value, MapSanitizer.MAX_STRING_LENGTH));
            }
        }
        return kept;
    }

    /**
     * Monta os cabecalhos da requisicao a partir do que o no declara.
     *
     * <p>Recusa quebra de linha em nome e valor. Sem isso, um valor com {@code \r\n} injeta
     * cabecalhos inteiros -- ou uma segunda requisicao -- dentro da primeira, e o valor vem da
     * config de um no, que e entrada do usuario.</p>
     */
    private static HttpHeaders requestHeaders(WorkflowNode node) {
        HttpHeaders headers = new HttpHeaders();
        for (Map.Entry<String, Object> entry : node.headers().entrySet()) {
            String name = entry.getKey();
            Object rawValue = entry.getValue();
            if (rawValue == null) {
                continue;
            }
            String value = String.valueOf(rawValue);
            if (hasControlCharacter(name) || hasControlCharacter(value)) {
                throw new IllegalArgumentException(
                        "o cabecalho '" + TextSanitizer.truncateSystemText(name, 64)
                                + "' tem caractere de controle no nome ou no valor");
            }
            headers.add(name, value);
        }
        return headers;
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Descreve a falha sem repetir a URL.
     *
     * <p>A mensagem vai para a {@code errorMessage} da execucao, que hoje qualquer um le. O nome da
     * excecao e a mensagem dela dizem o que houve; a URL ja esta na definicao de quem pode ve-la.</p>
     */
    private static String describe(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return TextSanitizer.truncateSystemText(message, MAX_ERROR_LENGTH);
    }
}
