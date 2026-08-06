package com.nexio.workflow.domain.model;

import com.nexio.workflow.domain.model.enums.NodeType;
import java.util.Map;
import java.util.Objects;

/**
 * Value object embutido em {@link WorkflowDefinition} que representa um no do grafo do workflow.
 *
 * <p>O componente chama-se {@code nodeId} e nao {@code id} de proposito: o Spring Data MongoDB trata
 * qualquer propriedade chamada {@code id} como propriedade de identidade e a persiste como {@code _id},
 * mesmo em documentos embedados. Com o nome {@code id} os nos eram gravados como {@code nodes._id} e
 * toda consulta por {@code nodes.id} casava zero documentos silenciosamente. O nome tambem fica
 * consistente com {@link ExecutionStep#nodeId()}.
 *
 * <p>O construtor compacto usa {@link MapSanitizer#copy(Map, String)}, que e leniente, e nao a
 * validacao estrita: este construtor tambem roda quando o Spring Data hidrata o documento lido do
 * MongoDB, e regra estrita ali derrubaria a leitura da colecao inteira. A validacao estrita da
 * config acontece na escrita, no callback de persistencia.</p>
 *
 * @param nodeId        identificador do no dentro do workflow
 * @param type          tipo do no
 * @param config        parametros especificos do no
 * @param nextOnSuccess proximo no quando a execucao do no e bem sucedida (nos nao condicionais)
 * @param nextOnTrue    proximo no quando a condicao avalia para verdadeiro (nos CONDITION)
 * @param nextOnFalse   proximo no quando a condicao avalia para falso (nos CONDITION)
 */
public record WorkflowNode(
        String nodeId,
        NodeType type,
        Map<String, Object> config,
        String nextOnSuccess,
        String nextOnTrue,
        String nextOnFalse
) {

    public WorkflowNode {
        Objects.requireNonNull(nodeId, "nodeId do no nao pode ser nulo");
        Objects.requireNonNull(type, "type do no nao pode ser nulo");
        config = MapSanitizer.copy(config, "nodes.config");
    }
}
