package com.nexio.workflow.infrastructure.http;

/**
 * Sinaliza que uma URL de destino foi recusada pelo {@link HttpTargetValidator}.
 *
 * <p>A mensagem e sempre uma constante curta vinda de {@link Reason}: nunca contem a URL, o host
 * nem o endereco IP resolvido. Isso e deliberado. No Sprint 3 o executor de no HTTP grava a
 * mensagem em {@code ExecutionStep.error} e a devolve pela API GraphQL, entao qualquer detalhe
 * interpolado aqui viraria um oraculo de varredura: quem cria o workflow poderia apontar um no
 * para {@code http://10.0.0.7:8080} e ler na resposta se o host existe, se resolve e qual IP tem.
 * O detalhe util para diagnostico vai para o log do servidor.</p>
 *
 * <p>Pelo mesmo motivo {@link Reason#UNRESOLVABLE_HOST} e {@link Reason#BLOCKED_ADDRESS}
 * compartilham exatamente o mesmo texto: separa-los permitiria enumerar nomes internos de DNS
 * (nome que nao resolve x nome que resolve para a rede interna) so pela mensagem devolvida.</p>
 */
public class HttpTargetNotAllowedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Motivo estruturado da recusa. Serve para log e para tratamento programatico; o texto
     * exposto ao cliente e o {@link #message()}.
     */
    public enum Reason {

        /** URL nula, em branco, relativa ou sintaticamente invalida. */
        MALFORMED_URL("URL de destino invalida"),

        /** Esquema fora da lista permitida ({@code file:}, {@code data:}, {@code ftp:}, ...). */
        UNSUPPORTED_SCHEME("Esquema de URL nao suportado: use https"),

        /** Esquema {@code http} com a flag {@code nexio.http.allow-insecure-http} desligada. */
        INSECURE_SCHEME_DISABLED("Esquema http desabilitado neste ambiente: use https"),

        /** URL com userinfo embutido ({@code https://user:senha@host}). */
        EMBEDDED_CREDENTIALS("URL de destino nao pode conter credenciais"),

        /** URL com fragmento ({@code #ancora}). */
        FRAGMENT_NOT_ALLOWED("URL de destino nao pode conter fragmento"),

        /** Porta explicita invalida. */
        INVALID_PORT("Porta de destino invalida"),

        /** Host que nao resolve. Texto identico ao de {@link #BLOCKED_ADDRESS} de proposito. */
        UNRESOLVABLE_HOST("Host de destino nao permitido"),

        /** Host que resolve para endereco interno, de loopback, link-local ou multicast. */
        BLOCKED_ADDRESS("Host de destino nao permitido");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        /**
         * Texto seguro para exibicao.
         *
         * @return mensagem constante, sem nenhum dado vindo da URL recusada
         */
        public String message() {
            return message;
        }
    }

    private final Reason reason;

    /**
     * Cria a excecao a partir do motivo.
     *
     * @param reason motivo estruturado da recusa
     */
    public HttpTargetNotAllowedException(Reason reason) {
        super(reason.message());
        this.reason = reason;
    }

    /**
     * Motivo estruturado da recusa.
     *
     * @return motivo
     */
    public Reason reason() {
        return reason;
    }
}
