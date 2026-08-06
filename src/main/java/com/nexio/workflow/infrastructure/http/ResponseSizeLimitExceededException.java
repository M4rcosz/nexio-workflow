package com.nexio.workflow.infrastructure.http;

import java.io.IOException;

/**
 * Sinaliza que a resposta de uma requisicao de saida passou do teto de bytes configurado.
 *
 * <p>E uma {@link IOException} de proposito: lancada de dentro do fluxo de leitura do corpo, o
 * {@code RestClient} a envolve em {@code ResourceAccessException}, ou seja, a chamada falha como
 * qualquer outro erro de I/O em vez de devolver um corpo pela metade que passaria por valido.</p>
 *
 * <p>A mensagem cita apenas o limite, nunca a URL nem o tamanho real recebido.</p>
 */
public class ResponseSizeLimitExceededException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int limitBytes;

    /**
     * Cria a excecao.
     *
     * @param limitBytes teto de bytes que foi ultrapassado
     */
    public ResponseSizeLimitExceededException(int limitBytes) {
        super("Resposta da requisicao de saida excede o limite de " + limitBytes + " bytes");
        this.limitBytes = limitBytes;
    }

    /**
     * Teto que foi ultrapassado.
     *
     * @return limite em bytes
     */
    public int limitBytes() {
        return limitBytes;
    }
}
