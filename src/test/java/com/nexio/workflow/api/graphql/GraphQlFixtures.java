package com.nexio.workflow.api.graphql;

import com.nexio.workflow.domain.model.TriggerConfig;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.HttpMethod;
import com.nexio.workflow.domain.model.enums.TriggerType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Montagens comuns aos testes da camada GraphQL.
 *
 * <p>As definicoes vem com {@code createdAt} e {@code updatedAt} preenchidos porque o schema
 * declara os dois como nao nulos: uma definicao sem datas faria a consulta falhar por um motivo que
 * nada tem a ver com o que o teste esta afirmando.</p>
 */
final class GraphQlFixtures {

    static final String ID = "wf-1";

    private GraphQlFixtures() {
        throw new AssertionError("Classe utilitaria nao deve ser instanciada");
    }

    /**
     * Definicao valida, como viria do banco.
     *
     * @return definicao com dois nos e datas de auditoria preenchidas
     */
    static WorkflowDefinition storedDefinition() {
        return storedDefinition(List.of(
                WorkflowNode.httpRequest("start", "https://exemplo.test", HttpMethod.GET, null, null, "end"),
                WorkflowNode.httpRequest("end", "https://exemplo.test/fim", HttpMethod.GET, null, null, null)));
    }

    /**
     * Definicao cujo primeiro no carrega um cabecalho {@code Authorization} aninhado.
     *
     * @return definicao com segredo em {@code nodes[0].config.headers.Authorization}
     */
    static WorkflowDefinition definitionWithSecretHeader() {
        return storedDefinition(List.of(
                WorkflowNode.httpRequest("start", "https://exemplo.test", HttpMethod.GET,
                        Map.of(
                                "Authorization", "Bearer super-secreto",
                                "Content-Type", "application/json"),
                        null, null)));
    }

    private static WorkflowDefinition storedDefinition(List<WorkflowNode> nodes) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(ID);
        definition.setName("cobranca diaria");
        definition.setDescription("dispara a cobranca");
        definition.setEnabled(true);
        definition.setTriggerConfig(new TriggerConfig(TriggerType.MOCK_EVENT, Map.of()));
        definition.setNodes(nodes);
        definition.setStartNodeId("start");
        definition.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        definition.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        return definition;
    }
}
