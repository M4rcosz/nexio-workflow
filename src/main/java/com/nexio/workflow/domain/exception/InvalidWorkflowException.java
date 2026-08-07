package com.nexio.workflow.domain.exception;

import com.nexio.workflow.domain.model.TextSanitizer;
import com.nexio.workflow.domain.model.WorkflowDefinition;

/**
 * Sinaliza que uma definicao de workflow viola alguma invariante do dominio.
 *
 * <p>E a traducao, na fronteira do caso de uso, do {@link IllegalArgumentException} que
 * {@link WorkflowDefinition#validateGraph()} e os setters do agregado lancam. O tipo existe para
 * que o chamador tenha algo especifico do dominio para tratar: {@code IllegalArgumentException}
 * tambem e lancada por bibliotecas e por erro de programacao, e mapear aquele tipo generico para
 * "entrada invalida do usuario" transformaria qualquer bug interno em erro de validacao.</p>
 *
 * <p>A mensagem e sempre a do dominio, repassada inteira: ela ja diz qual invariante caiu e em qual
 * no, e reescreve-la aqui so afastaria o texto do lugar onde a regra mora. Vive no dominio e nao
 * conhece HTTP nem GraphQL; quem traduz para o protocolo e a camada de API.</p>
 *
 * <p>A mensagem passa por {@link TextSanitizer#truncateSystemText(String, int)} porque parte do
 * texto do dominio interpola dado do usuario que ainda nao passou por validacao alguma -- o id de
 * no da mensagem "Id de no invalido" e justamente aquele que acabou de ser recusado, sem teto de
 * tamanho e sem restricao de caracteres. Como esse texto vai para o log e para a resposta de erro,
 * ele sai daqui sem caractere de controle e dentro de {@value #MAX_MESSAGE_LENGTH} caracteres.</p>
 */
public class InvalidWorkflowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Tamanho maximo da mensagem exposta; as mensagens do dominio ficam bem abaixo disso. */
    public static final int MAX_MESSAGE_LENGTH = 500;

    /**
     * Cria a excecao com a mensagem da invariante violada.
     *
     * @param message descricao da violacao, normalmente vinda do proprio dominio
     */
    public InvalidWorkflowException(String message) {
        this(message, null);
    }

    /**
     * Cria a excecao com a mensagem da invariante violada e a causa original.
     *
     * @param message descricao da violacao, normalmente vinda do proprio dominio
     * @param cause   excecao do dominio que originou a falha, pode ser nula
     */
    public InvalidWorkflowException(String message, Throwable cause) {
        super(TextSanitizer.truncateSystemText(message, MAX_MESSAGE_LENGTH), cause);
    }
}
