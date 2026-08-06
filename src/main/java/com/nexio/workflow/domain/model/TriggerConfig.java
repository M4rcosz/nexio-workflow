package com.nexio.workflow.domain.model;

import com.nexio.workflow.domain.model.enums.TriggerType;
import java.util.Map;
import java.util.Objects;

/**
 * Value object embutido em {@link WorkflowDefinition} que descreve como o workflow e disparado.
 *
 * @param type   tipo do gatilho
 * @param config parametros especificos do gatilho, por exemplo {@code {"cron": "0 8 * * 1-5"}} para SCHEDULE
 */
public record TriggerConfig(
        TriggerType type,
        Map<String, Object> config
) {

    public TriggerConfig {
        Objects.requireNonNull(type, "type do gatilho nao pode ser nulo");
        config = MapSanitizer.sanitize(config, "triggerConfig.config");
    }
}
