package com.nexio.workflow.api.graphql.dto;

import com.nexio.workflow.api.graphql.SecretRedactor;
import com.nexio.workflow.domain.model.enums.TriggerType;
import java.util.Map;

/**
 * Representacao de leitura da configuracao de gatilho.
 *
 * <p>A config do gatilho passa pela mesma redacao da config de no, pelo mesmo motivo: e outro mapa
 * livre preenchido pelo usuario, e nada impede que o gatilho de um dia guarde um segredo
 * compartilhado ali. Redigir os dois custa uma linha e evita ter que lembrar deste ponto quando
 * isso acontecer.</p>
 *
 * @param type   tipo do gatilho
 * @param config parametros do gatilho, ja com as chaves sensiveis mascaradas
 */
public record TriggerConfigResponse(
        TriggerType type,
        Map<String, Object> config
) {

    public TriggerConfigResponse {
        config = SecretRedactor.redact(config);
    }
}
