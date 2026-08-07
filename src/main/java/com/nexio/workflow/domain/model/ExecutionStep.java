package com.nexio.workflow.domain.model;

import com.nexio.workflow.domain.model.enums.StepStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Value object embutido em {@link WorkflowExecution} que registra o resultado de um no executado.
 *
 * <p>O construtor compacto usa {@link MapSanitizer#copy(Map, String)}, que e leniente, porque
 * tambem roda na hidratacao do documento; a validacao estrita do output acontece na escrita, no
 * callback de persistencia.</p>
 *
 * <p>Pelo mesmo motivo o {@code error} e higienizado e cortado em
 * {@value #MAX_ERROR_LENGTH} caracteres em vez de rejeitado: o texto vem de excecao, e recusar um
 * passo por causa do tamanho da mensagem faria perder o registro da falha -- alem de tornar
 * ilegivel qualquer documento antigo com mensagem maior.</p>
 *
 * @param nodeId     identificador do no executado
 * @param status     estado final do passo
 * @param output     dados produzidos pelo no
 * @param error      mensagem de erro quando o passo falha, nulo caso contrario
 * @param executedAt momento em que o passo foi executado
 */
public record ExecutionStep(
        String nodeId,
        StepStatus status,
        Map<String, Object> output,
        String error,
        Instant executedAt
) {

    /** Tamanho maximo da mensagem de erro do passo. */
    public static final int MAX_ERROR_LENGTH = 2000;

    public ExecutionStep {
        Objects.requireNonNull(nodeId, "nodeId do passo nao pode ser nulo");
        Objects.requireNonNull(status, "status do passo nao pode ser nulo");
        output = MapSanitizer.copy(output, "steps.output");
        error = TextSanitizer.truncateSystemText(error, MAX_ERROR_LENGTH);
    }
}
