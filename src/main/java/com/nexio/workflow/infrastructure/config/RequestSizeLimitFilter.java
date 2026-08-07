package com.nexio.workflow.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Recusa com 413 qualquer requisicao cujo corpo passe de {@value #DEFAULT_MAX_REQUEST_BYTES} bytes.
 *
 * <p>Existe porque o {@code maxPostSize} do Tomcat <b>nao</b> cobre este caso: aquele teto so vale
 * para corpos {@code application/x-www-form-urlencoded}, que o proprio container precisa analisar
 * para montar os parametros da requisicao. Um corpo {@code application/json} -- que e o formato de
 * tudo que esta aplicacao recebe, do GraphQL ao gatilho simulado -- passa direto por ele. Sem este
 * filtro, quem posta decide sozinho quanta memoria o processo aloca.</p>
 *
 * <p>O corte acontece em dois pontos, e o segundo e o que realmente limita:</p>
 * <ol>
 *   <li>{@code Content-Length} declarado acima do teto: recusa imediata, sem ler byte algum;</li>
 *   <li>contagem dos bytes efetivamente lidos do fluxo. O cabecalho e so uma declaracao do cliente:
 *       requisicao com {@code Transfer-Encoding: chunked} nao tem {@code Content-Length} nenhum, e
 *       nada impede um cliente de anunciar 10 bytes e enviar 10GB. Confiar so no cabecalho seria
 *       nao ter limite.</li>
 * </ol>
 *
 * <p>O corpo e lido de uma vez, ate o teto mais um byte, e entregue adiante em memoria. Ler antes
 * de chamar a cadeia e o que permite responder 413 de verdade: se a contagem falhasse dentro de um
 * fluxo preguicoso, o erro estouraria ja dentro do conversor de mensagem do Spring MVC e viraria
 * 400, longe da causa. Como o teto e pequeno, o custo do buffer e limitado a
 * {@value #DEFAULT_MAX_REQUEST_BYTES} bytes por requisicao em andamento.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** Teto padrao do corpo de requisicao: 256KB. */
    public static final int DEFAULT_MAX_REQUEST_BYTES = 256 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(RequestSizeLimitFilter.class);

    private final int maxRequestBytes;

    /**
     * Cria o filtro com o teto padrao de {@value #DEFAULT_MAX_REQUEST_BYTES} bytes.
     */
    public RequestSizeLimitFilter() {
        this(DEFAULT_MAX_REQUEST_BYTES);
    }

    /**
     * Cria o filtro com teto explicito.
     *
     * @param maxRequestBytes numero maximo de bytes aceitos no corpo da requisicao
     * @throws IllegalArgumentException quando o teto nao e positivo
     */
    public RequestSizeLimitFilter(int maxRequestBytes) {
        if (maxRequestBytes <= 0) {
            throw new IllegalArgumentException("maxRequestBytes deve ser positivo: " + maxRequestBytes);
        }
        this.maxRequestBytes = maxRequestBytes;
    }

    /**
     * Teto aplicado por este filtro.
     *
     * @return limite em bytes
     */
    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxRequestBytes) {
            reject(request, response, "Content-Length declarado de " + declaredLength + " bytes");
            return;
        }
        if (!mayHaveBody(request, declaredLength)) {
            filterChain.doFilter(request, response);
            return;
        }
        byte[] body = readAtMostLimit(request.getInputStream());
        if (body == null) {
            reject(request, response, "corpo acima do teto");
            return;
        }
        filterChain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    /**
     * Sem {@code Content-Length} e sem {@code Transfer-Encoding} o HTTP nao define corpo algum:
     * nesses casos nada e lido nem embrulhado.
     */
    private boolean mayHaveBody(HttpServletRequest request, long declaredLength) {
        return declaredLength > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
    }

    /**
     * Le no maximo {@code maxRequestBytes} bytes e verifica se ainda sobrou algo no fluxo.
     *
     * @param source fluxo do corpo da requisicao
     * @return os bytes lidos, ou {@code null} quando o corpo passa do teto
     */
    private byte[] readAtMostLimit(InputStream source) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int remaining = maxRequestBytes;
        while (remaining > 0) {
            int read = source.read(chunk, 0, Math.min(chunk.length, remaining));
            if (read == -1) {
                return buffer.toByteArray();
            }
            buffer.write(chunk, 0, read);
            remaining -= read;
        }
        return source.read() == -1 ? buffer.toByteArray() : null;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason)
            throws IOException {
        LOG.warn("Requisicao {} {} recusada: {} acima do teto de {} bytes",
                request.getMethod(), request.getRequestURI(), reason, maxRequestBytes);
        response.resetBuffer();
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"error\":\"Corpo da requisicao excede o limite de " + maxRequestBytes + " bytes\"}");
        response.flushBuffer();
    }

    /**
     * Requisicao que entrega o corpo ja lido em memoria, para que o resto da cadeia continue
     * enxergando um fluxo normal.
     */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new BufferedServletInputStream(new ByteArrayInputStream(body));
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }
    }

    /**
     * Fluxo de servlet sobre os bytes ja lidos.
     */
    private static final class BufferedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream source;

        private BufferedServletInputStream(ByteArrayInputStream source) {
            this.source = source;
        }

        @Override
        public int read() {
            return source.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return source.read(buffer, offset, length);
        }

        @Override
        public int available() {
            return source.available();
        }

        @Override
        public boolean isFinished() {
            return source.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Leitura assincrona nao e suportada no corpo bufferizado");
        }
    }
}
