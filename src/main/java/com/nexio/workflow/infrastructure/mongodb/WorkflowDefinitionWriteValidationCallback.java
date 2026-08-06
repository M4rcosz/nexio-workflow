package com.nexio.workflow.infrastructure.mongodb;

import com.nexio.workflow.domain.model.MapSanitizer;
import com.nexio.workflow.domain.model.TriggerConfig;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowNode;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

/**
 * Aplica as invariantes de escrita de {@link WorkflowDefinition} em todo save.
 *
 * <p>Existe porque {@code validateGraph()} nao tinha nenhum chamador de producao -- so testes --,
 * o que deixava um grafo ciclico ser gravado sem que nada reclamasse. Como callback, a validacao
 * roda dentro do proprio caminho de persistencia: nao ha caso de uso que consiga esquece-la.</p>
 *
 * <p>A validacao estrita dos mapas livres mora aqui, e nao nos construtores do dominio, porque
 * aqueles construtores tambem rodam na leitura; ver {@link MapSanitizer}.</p>
 */
@Component
public class WorkflowDefinitionWriteValidationCallback implements BeforeConvertCallback<WorkflowDefinition> {

    @Override
    public WorkflowDefinition onBeforeConvert(WorkflowDefinition definition, String collection) {
        definition.validateGraph();
        for (WorkflowNode node : definition.getNodes()) {
            MapSanitizer.validate(node.config(), "nodes[" + node.nodeId() + "].config");
        }
        TriggerConfig triggerConfig = definition.getTriggerConfig();
        if (triggerConfig != null) {
            MapSanitizer.validate(triggerConfig.config(), "triggerConfig.config");
        }
        return definition;
    }
}
