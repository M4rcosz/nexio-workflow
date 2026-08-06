package com.nexio.workflow.domain.model;

import com.nexio.workflow.domain.model.enums.NodeType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Value object embutido em {@link WorkflowDefinition} que representa um no do grafo do workflow.
 *
 * @param id            identificador do no dentro do workflow
 * @param type          tipo do no
 * @param config        parametros especificos do no
 * @param nextOnSuccess proximo no quando a execucao do no e bem sucedida (nos nao condicionais)
 * @param nextOnTrue    proximo no quando a condicao avalia para verdadeiro (nos CONDITION)
 * @param nextOnFalse   proximo no quando a condicao avalia para falso (nos CONDITION)
 */
public record WorkflowNode(
        String id,
        NodeType type,
        Map<String, Object> config,
        String nextOnSuccess,
        String nextOnTrue,
        String nextOnFalse
) {

    public WorkflowNode {
        config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }
}
