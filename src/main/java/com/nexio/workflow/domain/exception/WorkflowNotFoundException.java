package com.nexio.workflow.domain.exception;

import com.nexio.workflow.domain.model.TextSanitizer;

/**
 * Sinaliza que nenhuma definicao de workflow existe para o identificador informado.
 *
 * <p>E nao verificada de proposito: "o registro nao existe" e o resultado normal de um id que veio
 * do cliente, e obrigar cada chamador a declarar {@code throws} so espalharia ruido por toda a
 * cadeia. O identificador fica exposto em {@link #workflowId()} para que a camada de API possa
 * mapea-lo para o erro do protocolo sem ter que raspar a mensagem.</p>
 *
 * <p>Vive no dominio e nao conhece HTTP nem GraphQL: nao carrega status, codigo de resposta nem
 * qualquer outro vocabulario de transporte. Quem traduz para o protocolo e a camada de API.</p>
 *
 * <p>O identificador passa por {@link TextSanitizer#truncateSystemText(String, int)} antes de ser
 * guardado. Ele vem do cliente e o destino dele e o log do servidor e a resposta de erro: sem o
 * corte, um id de um megabyte viraria uma linha de log de um megabyte, e uma quebra de linha
 * embutida forjaria uma linha de log inteira. Trunca em vez de rejeitar porque a excecao ja esta
 * relatando uma falha e estourar aqui dentro perderia a falha original.</p>
 */
public class WorkflowNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Tamanho maximo do identificador guardado; um id legitimo e um UUID de 36 caracteres. */
    public static final int MAX_ID_LENGTH = 64;

    private final String workflowId;

    /**
     * Cria a excecao para o identificador procurado.
     *
     * @param workflowId identificador da definicao que nao foi encontrada, pode ser nulo
     */
    public WorkflowNotFoundException(String workflowId) {
        this(workflowId, null);
    }

    /**
     * Cria a excecao para o identificador procurado, com a causa original.
     *
     * @param workflowId identificador da definicao que nao foi encontrada, pode ser nulo
     * @param cause      causa original, pode ser nula
     */
    public WorkflowNotFoundException(String workflowId, Throwable cause) {
        super("Workflow nao encontrado: " + TextSanitizer.truncateSystemText(workflowId, MAX_ID_LENGTH), cause);
        this.workflowId = TextSanitizer.truncateSystemText(workflowId, MAX_ID_LENGTH);
    }

    /**
     * Identificador que nao foi encontrado, ja higienizado e dentro de
     * {@value #MAX_ID_LENGTH} caracteres.
     *
     * @return identificador procurado, {@code null} quando a busca foi por um id nulo
     */
    public String workflowId() {
        return workflowId;
    }
}
