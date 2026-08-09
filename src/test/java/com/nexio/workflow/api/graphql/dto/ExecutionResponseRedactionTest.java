package com.nexio.workflow.api.graphql.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexio.workflow.api.graphql.SecretRedactor;
import com.nexio.workflow.domain.model.ExecutionStep;
import com.nexio.workflow.domain.model.WorkflowExecution;
import com.nexio.workflow.domain.model.enums.ExecutionStatus;
import com.nexio.workflow.domain.model.enums.StepStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Teste da redacao aplicada as respostas de execucao.
 *
 * <p>Vale por si: e o unico lugar do sistema em que a credencial vazada nao foi escrita por
 * ninguem. Na config de um no, o segredo esta la porque alguem o digitou. No {@code output} de um
 * passo ele chega sozinho -- basta um no HTTP chamar um endpoint de autenticacao e a resposta
 * inteira vai para o historico.</p>
 */
class ExecutionResponseRedactionTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * A resposta de um endpoint de autenticacao nao volta em texto claro na leitura do historico.
     *
     * <p>Ninguem escreveu credencial neste workflow: ele so chamou um endereco. O caminho de
     * vazamento e mais curto do que o da config de um no, e por isso a redacao do {@code output} nao
     * e opcional.</p>
     */
    @Test
    void masksTheCredentialsAThirdPartyResponseBroughtBackOnItsOwn() {
        ExecutionStep step = new ExecutionStep("login", StepStatus.SUCCESS,
                Map.of("statusCode", 200,
                        "body", Map.of(
                                "access_token", "ya29.super-secreto",
                                "refresh_token", "1//refresh-secreto",
                                "expires_in", 3600)),
                null, NOW);

        ExecutionStepResponse response = ExecutionStepResponse.from(step);

        Map<String, Object> body = asMap(response.output().get("body"));
        assertThat(body)
                .containsEntry("access_token", SecretRedactor.REDACTED)
                .containsEntry("refresh_token", SecretRedactor.REDACTED)
                .containsEntry("expires_in", 3600);
        assertThat(response.output()).containsEntry("statusCode", 200);
    }

    /**
     * O payload do gatilho tambem e redigido: ele e enviado por quem dispara e fica gravado para
     * sempre.
     *
     * <p>Webhook de provedor de pagamento manda a assinatura da requisicao junto do corpo, e evento
     * de integracao carrega o token de quem o emitiu.</p>
     */
    @Test
    void masksTheSecretsCarriedByTheTriggerPayload() {
        WorkflowExecutionResponse response = WorkflowExecutionResponse.from(
                execution(Map.of(
                        "signature", "sha256=abcdef",
                        "token", "tok-secreto",
                        "pedidoId", "PED-1"), List.of()));

        assertThat(response.triggerPayload())
                .containsEntry("signature", SecretRedactor.REDACTED)
                .containsEntry("token", SecretRedactor.REDACTED)
                .containsEntry("pedidoId", "PED-1");
    }

    /** Os passos dentro da execucao passam pela redacao junto: nao ha caminho por fora. */
    @Test
    void redactsTheStepsNestedInsideTheExecution() {
        ExecutionStep step = new ExecutionStep("login", StepStatus.SUCCESS,
                Map.of("api_key", "chave-secreta"), null, NOW);

        WorkflowExecutionResponse response =
                WorkflowExecutionResponse.from(execution(Map.of(), List.of(step)));

        assertThat(response.steps()).hasSize(1);
        assertThat(response.steps().getFirst().output())
                .containsEntry("api_key", SecretRedactor.REDACTED);
    }

    /**
     * A mensagem de erro passa pela redacao de endereco.
     *
     * <p>A falha de um no HTTP pode citar o endereco chamado, e endereco carrega credencial no
     * {@code userinfo} e em parametro de consulta -- exatamente o furo que a varredura de valor
     * fechou para o campo {@code url}.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "https://api.exemplo.test/v1?api_key=chave-secreta",
        "https://usuario:senha@interno.exemplo.test/hook"
    })
    void masksCredentialsInsideAnErrorMessageThatQuotesAnAddress(String url) {
        WorkflowExecution execution = execution(Map.of(), List.of());
        execution.markRunning(NOW);
        execution.markFailed("Falha ao chamar " + url, NOW);

        WorkflowExecutionResponse response = WorkflowExecutionResponse.from(execution);

        assertThat(response.errorMessage())
                .contains(SecretRedactor.REDACTED)
                .doesNotContain("chave-secreta")
                .doesNotContain("senha");
    }

    /** Passo sem erro continua sem erro: a redacao de nulo nao pode virar excecao nem texto. */
    @Test
    void leavesAnAbsentErrorAlone() {
        ExecutionStepResponse response = ExecutionStepResponse.from(
                new ExecutionStep("no", StepStatus.SUCCESS, Map.of(), null, NOW));

        assertThat(response.error()).isNull();
        assertThat(WorkflowExecutionResponse.from(execution(Map.of(), List.of())).errorMessage())
                .isNull();
    }

    /**
     * A redacao esta no construtor canonico, e nao na fabrica.
     *
     * <p>E o que torna impossivel devolver o valor cru: a construcao direta -- que qualquer resolver
     * futuro pode escrever sem passar pelo {@code from} -- passa pelo mesmo construtor compacto.</p>
     */
    @Test
    void redactsEvenWhenTheRecordIsBuiltDirectlyInsteadOfThroughTheFactory() {
        ExecutionStepResponse response = new ExecutionStepResponse(
                "no", StepStatus.SUCCESS, Map.of("password", "s3nh4"), null,
                NOW.atOffset(java.time.ZoneOffset.UTC));

        assertThat(response.output()).containsEntry("password", SecretRedactor.REDACTED);
    }

    private static WorkflowExecution execution(Map<String, Object> payload, List<ExecutionStep> steps) {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId("exec-1");
        execution.setWorkflowId("wf-1");
        execution.setTriggerPayload(payload);
        execution.setSteps(steps);
        execution.setCreatedAt(NOW);
        return execution;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
