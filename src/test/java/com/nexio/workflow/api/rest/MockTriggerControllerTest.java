package com.nexio.workflow.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexio.workflow.api.graphql.SecretRedactor;
import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.CurrentActorPort;
import com.nexio.workflow.application.usecase.TriggerWorkflowUseCase;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.ExecutionStep;
import com.nexio.workflow.domain.model.WorkflowExecution;
import com.nexio.workflow.domain.model.enums.StepStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Teste do endpoint REST de disparo simulado.
 *
 * <p>Roda com o perfil {@code dev} ativo porque o controlador so existe nele -- e essa restricao e
 * parte do contrato, nao acidente: o endpoint nao tem autenticacao e agora dispara chamadas HTTP de
 * saida.</p>
 *
 * <p>O foco esta no que a camada REST acrescenta: o mapeamento de erro e a redacao da resposta. A
 * logica do disparo tem os proprios testes no caso de uso, entao ele entra dublado.</p>
 */
@WebMvcTest(controllers = MockTriggerController.class)
@ActiveProfiles("dev")
class MockTriggerControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TriggerWorkflowUseCase triggerWorkflowUseCase;

    @MockitoBean
    private CurrentActorPort currentActorPort;

    /**
     * Workflow inexistente vira {@code 404}, e nao {@code 500}.
     *
     * <p>E o defeito que o mapeamento de erro existe para impedir, e que ja apareceu duas vezes
     * neste projeto: sem tradutor, erro de entrada do usuario chega como erro de servidor. Um
     * {@code 500} manda o cliente reportar um incidente e o operador procurar um defeito que nao
     * existe.</p>
     */
    @Test
    void anUnknownWorkflowIsNotFoundInsteadOfAServerError() throws Exception {
        when(currentActorPort.currentActor()).thenReturn(ActorId.ANONYMOUS);
        when(triggerWorkflowUseCase.execute(any(), eq("nao-existe"), any()))
                .thenThrow(new WorkflowNotFoundException("nao-existe"));

        mockMvc.perform(post("/api/v1/mock/trigger/nao-existe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    /** Workflow desativado e recusa de entrada: {@code 400}, com o motivo legivel. */
    @Test
    void aDisabledWorkflowIsABadRequestWithAReadableReason() throws Exception {
        when(currentActorPort.currentActor()).thenReturn(ActorId.ANONYMOUS);
        when(triggerWorkflowUseCase.execute(any(), eq("wf-1"), any()))
                .thenThrow(new InvalidWorkflowException("O workflow 'wf-1' esta desativado"));

        mockMvc.perform(post("/api/v1/mock/trigger/wf-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("desativado")));
    }

    /**
     * A resposta do REST sai redigida igual a do GraphQL.
     *
     * <p>E a razao de o controlador devolver o mesmo {@code WorkflowExecutionResponse}: um DTO
     * proprio para o REST duplicaria o formato e, pior, duplicaria a responsabilidade de mascarar --
     * e a segunda copia e a que esquece. O teste existe para travar isso: se alguem trocar o tipo de
     * retorno por um mapa montado a mao, a credencial aparece aqui.</p>
     */
    @Test
    void theRestResponseIsRedactedExactlyLikeTheGraphQlOne() throws Exception {
        when(currentActorPort.currentActor()).thenReturn(ActorId.ANONYMOUS);
        when(triggerWorkflowUseCase.execute(any(), eq("wf-1"), any()))
                .thenReturn(execution());

        mockMvc.perform(post("/api/v1/mock/trigger/wf-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-secreto\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerPayload.token").value(SecretRedactor.REDACTED))
                .andExpect(jsonPath("$.triggerPayload.pedido").value("PED-1"))
                .andExpect(jsonPath("$.steps[0].output.access_token").value(SecretRedactor.REDACTED));
    }

    /**
     * Responde {@code 200} e nao {@code 202}.
     *
     * <p>A execucao e sincrona: {@code 202} anunciaria um processamento assincrono que nao existe e
     * mandaria o cliente consultar depois um resultado que ele ja tem em maos.</p>
     */
    @Test
    void respondsOkBecauseTheExecutionIsAlreadyFinished() throws Exception {
        when(currentActorPort.currentActor()).thenReturn(ActorId.ANONYMOUS);
        when(triggerWorkflowUseCase.execute(any(), eq("wf-1"), any())).thenReturn(execution());

        mockMvc.perform(post("/api/v1/mock/trigger/wf-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    /** Disparo sem corpo e legitimo: nem todo evento carrega dado. */
    @Test
    void acceptsATriggerWithoutABody() throws Exception {
        when(currentActorPort.currentActor()).thenReturn(ActorId.ANONYMOUS);
        when(triggerWorkflowUseCase.execute(any(), eq("wf-1"), eq(null))).thenReturn(execution());

        mockMvc.perform(post("/api/v1/mock/trigger/wf-1"))
                .andExpect(status().isOk());
    }

    private static WorkflowExecution execution() {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId("exec-1");
        execution.setWorkflowId("wf-1");
        execution.setTriggerPayload(Map.of("token", "tok-secreto", "pedido", "PED-1"));
        execution.setSteps(List.of(new ExecutionStep("login", StepStatus.SUCCESS,
                Map.of("access_token", "ya29.secreto"), null, NOW)));
        execution.setCreatedAt(NOW);
        execution.markRunning(NOW);
        execution.markSucceeded(NOW);
        return execution;
    }
}
