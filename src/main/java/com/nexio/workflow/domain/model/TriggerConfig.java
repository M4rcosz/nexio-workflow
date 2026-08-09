package com.nexio.workflow.domain.model;

import com.nexio.workflow.domain.model.enums.TriggerType;
import java.util.Map;
import java.util.Objects;

/**
 * Value object embutido em {@link WorkflowDefinition} que descreve como o workflow e disparado.
 *
 * <p>O construtor compacto usa {@link MapSanitizer#copy(Map, String)}, que e leniente, porque
 * tambem roda na hidratacao do documento; a validacao estrita da config acontece na escrita, no
 * callback de persistencia.</p>
 *
 * @param type   tipo do gatilho
 * @param config parametros especificos do gatilho. Para SCHEDULE, {@code {"cron": "0 0 8 * * MON-FRI"}}
 *               -- <b>seis</b> campos comecando por segundos, que e o formato do Spring. O exemplo
 *               que estava aqui antes tinha cinco campos, no formato do cron do Unix, e nao e
 *               aceito; ver {@link CronExpressions}
 */
public record TriggerConfig(
        TriggerType type,
        Map<String, Object> config
) {

    public TriggerConfig {
        Objects.requireNonNull(type, "type do gatilho nao pode ser nulo");
        config = MapSanitizer.copy(config, "triggerConfig.config");
    }
}
