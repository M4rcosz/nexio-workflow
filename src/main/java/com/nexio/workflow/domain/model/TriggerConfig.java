package com.nexio.workflow.domain.model;

import com.nexio.workflow.domain.model.enums.TriggerType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
        config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }
}
