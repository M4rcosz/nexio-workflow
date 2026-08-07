package com.nexio.workflow.application.usecase;

import com.nexio.workflow.domain.model.TriggerConfig;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.HttpMethod;
import com.nexio.workflow.domain.model.enums.NodeType;
import com.nexio.workflow.domain.model.enums.TriggerType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Montagens comuns aos testes dos casos de uso.
 *
 * <p>O grafo padrao e valido de proposito: os testes que exercitam recusa montam a violacao que
 * lhes interessa a partir dele, e assim o que difere entre o caso feliz e o caso de erro fica
 * visivel no proprio teste.</p>
 */
final class WorkflowFixtures {

    private WorkflowFixtures() {
        throw new AssertionError("Classe utilitaria nao deve ser instanciada");
    }

    /**
     * Grafo valido de dois nos: {@code start} segue para {@code end}.
     *
     * @return lista de nos
     */
    static List<WorkflowNode> validNodes() {
        return List.of(
                WorkflowNode.httpRequest("start", "https://exemplo.test", HttpMethod.GET, null, null, "end"),
                WorkflowNode.httpRequest("end", "https://exemplo.test/fim", HttpMethod.GET, null, null, null));
    }

    /**
     * Outro grafo valido, com ids diferentes de {@link #validNodes()}.
     *
     * @return lista de nos
     */
    static List<WorkflowNode> otherValidNodes() {
        return List.of(
                WorkflowNode.httpRequest("inicio", "https://exemplo.test/inicio", HttpMethod.GET, null, null, "fim"),
                WorkflowNode.httpRequest("fim", "https://exemplo.test/fim", HttpMethod.GET, null, null, null));
    }

    /**
     * Grafo ciclico, recusado por {@code validateGraph()}.
     *
     * @return lista de nos com ciclo entre {@code a} e {@code b}
     */
    static List<WorkflowNode> cyclicNodes() {
        return List.of(
                WorkflowNode.httpRequest("a", "https://exemplo.test/a", HttpMethod.GET, null, null, "b"),
                WorkflowNode.httpRequest("b", "https://exemplo.test/b", HttpMethod.GET, null, null, "a"));
    }

    /**
     * Grafo valido cuja config carrega um operador do MongoDB, recusado por
     * {@code validateConfigs()}.
     *
     * @return lista de nos com {@code $where} na config do primeiro
     */
    static List<WorkflowNode> nodesWithOperatorKeyInConfig() {
        return List.of(
                new WorkflowNode("start", NodeType.HTTP_REQUEST, "https://exemplo.test", HttpMethod.GET,
                        null, null, null, Map.of("$where", "1"), "end", null, null),
                WorkflowNode.httpRequest("end", "https://exemplo.test/fim", HttpMethod.GET, null, null, null));
    }

    /**
     * Gatilho por evento simulado, sem parametros.
     *
     * @return configuracao de gatilho
     */
    static TriggerConfig mockEventTrigger() {
        return new TriggerConfig(TriggerType.MOCK_EVENT, Map.of());
    }

    /**
     * Definicao ja persistida, com campos de auditoria e versao preenchidos como viriam do banco.
     *
     * @param id identificador da definicao
     * @return definicao valida e "carregada"
     */
    static WorkflowDefinition storedDefinition(String id) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(id);
        definition.setName("cobranca diaria");
        definition.setDescription("dispara a cobranca");
        definition.setEnabled(true);
        definition.setTriggerConfig(mockEventTrigger());
        definition.setNodes(validNodes());
        definition.setStartNodeId("start");
        definition.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        definition.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        return definition;
    }
}
