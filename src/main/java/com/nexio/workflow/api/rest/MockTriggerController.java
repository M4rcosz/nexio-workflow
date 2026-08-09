package com.nexio.workflow.api.rest;

import com.nexio.workflow.api.graphql.dto.WorkflowExecutionResponse;
import com.nexio.workflow.application.port.out.CurrentActorPort;
import com.nexio.workflow.application.usecase.TriggerWorkflowUseCase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dispara um workflow por evento simulado, para exercitar a engine sem um gatilho de verdade.
 *
 * <p><b>Continua restrito ao perfil {@code dev}.</b> O endpoint nao tem autenticacao, e agora ele
 * <i>faz</i> alguma coisa: executa o workflow, o que significa fazer requisicoes HTTP de saida.
 * Antes o risco de publica-lo era uma superficie aberta que nao fazia nada; agora seria um
 * disparador anonimo de chamadas externas. O {@code @Profile("dev")} sai quando houver
 * autenticacao, e nao antes.</p>
 *
 * <p>Responde {@code 200} com a execucao ja terminada, e nao {@code 202}: a execucao e sincrona --
 * ver {@code docs/adr/0005-synchronous-execution.md} -- e devolver {@code 202} anunciaria um
 * processamento assincrono que nao existe, mandando o cliente consultar depois um resultado que ele
 * ja tem em maos.</p>
 *
 * <p>O corpo devolvido e o mesmo {@link WorkflowExecutionResponse} do GraphQL, de proposito: e ele
 * que carrega a redacao nos construtores. Montar um DTO proprio para o REST duplicaria o formato e,
 * mais importante, duplicaria a responsabilidade de mascarar -- e a segunda copia e a que esquece.
 * O {@code triggerPayload} e o {@code output} de cada passo saem mascarados por aqui exatamente
 * como saem por la.</p>
 *
 * <p>O teto de tamanho do corpo nao mora aqui: e aplicado a toda requisicao pelo
 * {@code RequestSizeLimitFilter}. O mapeamento de erro fica no {@link RestExceptionHandler}.</p>
 */
@RestController
@RequestMapping("/api/v1/mock")
@Profile("dev")
@Validated
public class MockTriggerController {

    /** Mesmo teto de identificador dos resolvers GraphQL, e pelo mesmo motivo. */
    private static final int MAX_ID_LENGTH = 64;

    private final TriggerWorkflowUseCase triggerWorkflowUseCase;
    private final CurrentActorPort currentActorPort;

    /**
     * Cria o controlador com injecao por construtor.
     *
     * @param triggerWorkflowUseCase caso de uso de disparo
     * @param currentActorPort       porta que informa o ator da requisicao em curso
     */
    public MockTriggerController(TriggerWorkflowUseCase triggerWorkflowUseCase,
                                 CurrentActorPort currentActorPort) {
        this.triggerWorkflowUseCase = triggerWorkflowUseCase;
        this.currentActorPort = currentActorPort;
    }

    /**
     * Dispara o workflow com o evento recebido e devolve a execucao terminada.
     *
     * @param id   identificador da definicao a executar
     * @param body payload do evento simulado, pode ser omitido
     * @return execucao terminada, ja redigida
     */
    @PostMapping("/trigger/{id}")
    public ResponseEntity<WorkflowExecutionResponse> trigger(
            @PathVariable @NotBlank @Size(max = MAX_ID_LENGTH) String id,
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(WorkflowExecutionResponse.from(
                triggerWorkflowUseCase.execute(currentActorPort.currentActor(), id, body)));
    }
}
