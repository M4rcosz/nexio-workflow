package com.nexio.workflow.domain.exception;

import com.nexio.workflow.domain.model.TextSanitizer;

/**
 * Sinaliza que o registro mudou entre a leitura e a gravacao de uma atualizacao.
 *
 * <p>E a traducao, na fronteira da persistencia, da falha de bloqueio otimista. O tipo existe porque
 * o resultado precisa chegar ao cliente como "tente de novo": duas chamadas concorrentes de
 * atualizacao sao o caso normal de dois operadores mexendo no mesmo workflow, e sem um tipo proprio
 * o perdedor recebia erro interno -- resposta diante da qual nenhum cliente le de novo e reenvia,
 * porque ela diz que o problema e do servidor e nao dele.</p>
 *
 * <p>Vive no dominio e nao conhece HTTP, GraphQL nem Spring Data: o adaptador de persistencia e o
 * unico lugar que sabe qual excecao de infraestrutura significa isto, e a camada de API e a unica
 * que sabe qual codigo de protocolo corresponde.</p>
 *
 * <p><b>A mensagem da causa nao entra na mensagem exposta.</b> O texto de
 * {@code OptimisticLockingFailureException} descreve a operacao que falhou pelo nome da colecao e
 * pelo filtro BSON cru, incluindo o {@code _id} e a versao esperada -- descricao do interior do
 * banco, para uma excecao cujo destino e a resposta a quem chamou. A causa continua encadeada, para
 * o log.</p>
 */
public class WorkflowConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Tamanho maximo do identificador guardado; um id legitimo e um UUID de 36 caracteres. */
    public static final int MAX_ID_LENGTH = 64;

    private final String workflowId;

    /**
     * Cria a excecao para o registro que mudou embaixo da atualizacao.
     *
     * @param workflowId identificador do registro em conflito, pode ser nulo
     * @param cause      falha de bloqueio otimista original, pode ser nula
     */
    public WorkflowConcurrentlyModifiedException(String workflowId, Throwable cause) {
        super("Registro '" + TextSanitizer.truncateSystemText(workflowId, MAX_ID_LENGTH)
                + "' foi modificado por outra requisicao: releia e reenvie a alteracao", cause);
        this.workflowId = TextSanitizer.truncateSystemText(workflowId, MAX_ID_LENGTH);
    }

    /**
     * Identificador em conflito, ja higienizado e dentro de {@value #MAX_ID_LENGTH} caracteres.
     *
     * @return identificador do registro, {@code null} quando a gravacao era de um id nulo
     */
    public String workflowId() {
        return workflowId;
    }
}
