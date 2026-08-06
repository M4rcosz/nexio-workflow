package com.nexio.workflow.domain.model;

import com.nexio.workflow.domain.model.enums.StepStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Value object embutido em {@link WorkflowExecution} que registra o resultado de um no executado.
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

    public ExecutionStep {
        output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
    }
}
