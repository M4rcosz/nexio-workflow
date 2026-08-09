package com.nexio.workflow.api.graphql.dto;

import com.nexio.workflow.api.graphql.SecretRedactor;
import com.nexio.workflow.domain.model.WorkflowExecution;
import com.nexio.workflow.domain.model.enums.ExecutionStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Representacao de leitura de uma execucao.
 *
 * <p><b>O {@code triggerPayload} e redigido, e nao e paranoia.</b> Ele e o evento que disparou o
 * workflow, enviado por quem chamou: um webhook de provedor de pagamento manda a assinatura da
 * requisicao junto do corpo, um evento de integracao carrega o token que autentica quem o emitiu, e
 * os dois viajam com nomes -- {@code signature}, {@code token} -- que a redacao ja conhece. O
 * payload fica gravado para sempre no documento da execucao, e sem redacao qualquer leitura do
 * historico o devolve inteiro.</p>
 *
 * <p>A {@code errorMessage} passa por {@link SecretRedactor#redactUrlsIn(String)} porque a falha de
 * um no HTTP pode citar o endereco chamado, e endereco carrega credencial em {@code userinfo} e em
 * parametro de consulta. A mensagem e uma frase com o endereco dentro, e nao o endereco sozinho --
 * {@code redactUrl} devolveria o texto intacto.</p>
 *
 * <p>Como no {@link WorkflowNodeResponse}, a redacao acontece no construtor canonico: nao ha
 * caminho de construcao que a contorne.</p>
 *
 * <p>As datas saem como {@link OffsetDateTime} e nao como {@code Instant} pelo mesmo motivo do
 * {@link WorkflowDefinitionResponse}: o scalar {@code DateTime} recusa {@code Instant} com
 * {@code CoercingSerializeException}, e o erro so aparece na primeira consulta que pedir o campo.
 * A primeira versao desta classe usava {@code Instant} e toda consulta de execucao que selecionasse
 * {@code createdAt} falhava -- pego pelo teste de integracao, nao pelo compilador.</p>
 *
 * @param id             identificador da execucao
 * @param workflowId     identificador da definicao executada
 * @param status         estado da execucao
 * @param triggerPayload payload do evento, ja com os valores sensiveis mascarados
 * @param steps          passos executados, cada um ja redigido
 * @param createdAt      criacao do registro
 * @param startedAt      inicio da caminhada
 * @param finishedAt     fim da caminhada, nulo enquanto nao terminou
 * @param errorMessage   motivo da falha, nulo quando nao falhou
 */
public record WorkflowExecutionResponse(
        String id,
        String workflowId,
        ExecutionStatus status,
        Map<String, Object> triggerPayload,
        List<ExecutionStepResponse> steps,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String errorMessage
) {

    public WorkflowExecutionResponse {
        triggerPayload = SecretRedactor.redact(triggerPayload);
        steps = steps == null ? List.of() : List.copyOf(steps);
        errorMessage = SecretRedactor.redactUrlsIn(errorMessage);
    }

    /**
     * Converte uma execucao do dominio.
     *
     * @param execution execucao a converter
     * @return representacao ja redigida
     */
    public static WorkflowExecutionResponse from(WorkflowExecution execution) {
        return new WorkflowExecutionResponse(
                execution.getId(),
                execution.getWorkflowId(),
                execution.getStatus(),
                execution.getTriggerPayload(),
                execution.getSteps().stream().map(ExecutionStepResponse::from).toList(),
                utc(execution.getCreatedAt()),
                utc(execution.getStartedAt()),
                utc(execution.getFinishedAt()),
                execution.getErrorMessage());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
