package com.nexio.workflow.infrastructure.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Limita o tamanho do corpo de resposta das requisicoes de saida.
 *
 * <p>Sem este teto, um servidor remoto escolhido por quem monta o workflow decide sozinho quanta
 * memoria o processo aloca: a resposta e lida inteira para converter em objeto e, pior, e gravada
 * em {@code ExecutionStep.output}, que vai para o MongoDB (documento de 16MB) e depois para a
 * resposta GraphQL. Um unico no apontado para um arquivo grande derruba a JVM ou envenena a
 * colecao de execucoes.</p>
 *
 * <p>O corte e feito em dois pontos, e o segundo e o que importa:</p>
 * <ol>
 *   <li>{@code Content-Length} acima do teto: falha imediata, sem ler byte algum do corpo;</li>
 *   <li>contagem real dos bytes lidos, num {@link FilterInputStream} que envolve o corpo. O
 *       cabecalho e apenas uma dica do remoto: resposta com {@code Transfer-Encoding: chunked}
 *       nao tem {@code Content-Length}, e nada impede o servidor de declarar 10 bytes e enviar
 *       10GB. Confiar so no cabecalho seria nao ter limite nenhum.</li>
 * </ol>
 *
 * <p>Ao estourar o limite a leitura falha em vez de truncar: um corpo cortado no meio seria
 * gravado como saida aparentemente valida e so quebraria depois, longe da causa.</p>
 */
public class ResponseSizeLimitInterceptor implements ClientHttpRequestInterceptor {

    /** Teto padrao do corpo de resposta: 256KB. */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 256 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(ResponseSizeLimitInterceptor.class);

    private final int maxResponseBytes;

    /**
     * Cria o interceptor com o teto padrao de {@value #DEFAULT_MAX_RESPONSE_BYTES} bytes.
     */
    public ResponseSizeLimitInterceptor() {
        this(DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * Cria o interceptor com teto explicito.
     *
     * @param maxResponseBytes numero maximo de bytes aceitos no corpo da resposta
     * @throws IllegalArgumentException quando o teto nao e positivo
     */
    public ResponseSizeLimitInterceptor(int maxResponseBytes) {
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes deve ser positivo: " + maxResponseBytes);
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * Teto aplicado por este interceptor.
     *
     * @return limite em bytes
     */
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        long declaredLength = response.getHeaders().getContentLength();
        if (declaredLength > maxResponseBytes) {
            LOG.warn("Resposta de saida recusada: Content-Length {} acima do teto de {} bytes",
                    declaredLength, maxResponseBytes);
            response.close();
            throw new ResponseSizeLimitExceededException(maxResponseBytes);
        }
        return new SizeLimitedResponse(response, maxResponseBytes);
    }

    /**
     * Resposta que delega tudo ao original e so troca o corpo por um fluxo com contador.
     */
    private static final class SizeLimitedResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final int maxResponseBytes;

        private SizeLimitedResponse(ClientHttpResponse delegate, int maxResponseBytes) {
            this.delegate = delegate;
            this.maxResponseBytes = maxResponseBytes;
        }

        @Override
        public InputStream getBody() throws IOException {
            return new SizeLimitedInputStream(delegate.getBody(), maxResponseBytes);
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Fluxo que conta os bytes efetivamente entregues e falha ao passar do teto.
     */
    private static final class SizeLimitedInputStream extends FilterInputStream {

        private final int maxResponseBytes;
        private long readCount;

        private SizeLimitedInputStream(InputStream source, int maxResponseBytes) {
            super(source);
            this.maxResponseBytes = maxResponseBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        /**
         * {@code skip} tambem consome bytes do remoto, entao entra na conta: sem isso bastaria
         * pular o corpo para ignorar o teto.
         */
        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) {
                count(skipped);
            }
            return skipped;
        }

        private void count(long amount) throws IOException {
            readCount += amount;
            if (readCount > maxResponseBytes) {
                LOG.warn("Resposta de saida recusada: corpo passou do teto de {} bytes", maxResponseBytes);
                throw new ResponseSizeLimitExceededException(maxResponseBytes);
            }
        }
    }
}
