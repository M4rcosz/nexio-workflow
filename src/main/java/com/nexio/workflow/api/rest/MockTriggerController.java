package com.nexio.workflow.api.rest;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ponto de entrada temporario para disparar workflows por evento simulado.
 *
 * <p>Restrito ao perfil {@code dev} de proposito: o endpoint aceita corpo arbitrario, nao tem
 * autenticacao e ainda nao faz nada com o que recebe. Enquanto for placeholder, ele nao pode
 * existir numa instancia publicada -- seria uma superficie aberta sem contrapartida.</p>
 *
 * <p>O teto de tamanho do corpo nao mora aqui: e aplicado a toda requisicao pelo
 * {@code RequestSizeLimitFilter}.</p>
 *
 * <p>TODO issue #25: ligar ao {@code TriggerWorkflowUseCase}, definir o contrato do corpo, exigir
 * autenticacao e so entao remover o {@code @Profile("dev")}.</p>
 */
@RestController
@RequestMapping("/api/v1/mock")
@Profile("dev")
public class MockTriggerController {

    /**
     * Aceita o disparo e responde 202 sem processar nada.
     *
     * @param body payload do evento simulado, ignorado ate a issue #25
     * @return resposta 202 com o aviso de que a implementacao ainda nao existe
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, String>> trigger(@RequestBody Map<String, Object> body) {
        // placeholder -- implementar na issue #25
        return ResponseEntity.accepted()
                .body(Map.of("status", "accepted", "message", "not implemented yet"));
    }
}
