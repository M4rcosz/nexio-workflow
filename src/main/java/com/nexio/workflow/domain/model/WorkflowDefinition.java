package com.nexio.workflow.domain.model;

import com.nexio.workflow.domain.model.enums.NodeType;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Definicao de um workflow: o gatilho e o grafo de nos que sera executado.
 */
@Document(collection = "workflow_definitions")
@CompoundIndex(name = "def_enabled_trigger", def = "{'enabled': 1, 'triggerConfig.type': 1}")
public class WorkflowDefinition {

    /** Numero maximo de nos permitido em um workflow. */
    public static final int MAX_NODES = 50;

    private static final Pattern NODE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final int COLOR_WHITE = 0;
    private static final int COLOR_GREY = 1;
    private static final int COLOR_BLACK = 2;

    @Id
    private String id;

    private String name;

    private String description;

    private boolean enabled;

    private TriggerConfig triggerConfig;

    private List<WorkflowNode> nodes = new ArrayList<>();

    private String startNodeId;

    /**
     * Versao de bloqueio otimista. Tambem faz o Spring Data reconhecer o documento como novo:
     * sem ela, com id atribuido pela aplicacao, a auditoria de {@code createdAt} nunca dispararia.
     */
    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public WorkflowDefinition() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public TriggerConfig getTriggerConfig() {
        return triggerConfig;
    }

    public void setTriggerConfig(TriggerConfig triggerConfig) {
        this.triggerConfig = triggerConfig;
    }

    /**
     * Devolve os nos como visao imutavel: a colecao interna nunca escapa.
     *
     * @return lista imutavel de nos
     */
    public List<WorkflowNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public void setNodes(List<WorkflowNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
    }

    public String getStartNodeId() {
        return startNodeId;
    }

    public void setStartNodeId(String startNodeId) {
        this.startNodeId = startNodeId;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Valida as invariantes do grafo de nos.
     *
     * <p>Nao e chamado por {@link #setNodes(List)} de proposito: a entidade precisa continuar
     * hidratavel a partir do MongoDB sem disparar validacao. Os casos de uso invocam este metodo
     * explicitamente antes de persistir.</p>
     *
     * @throws IllegalArgumentException quando alguma invariante do grafo e violada
     */
    public void validateGraph() {
        validateNodeIds();
        Map<String, WorkflowNode> byId = new HashMap<>();
        for (WorkflowNode node : nodes) {
            byId.put(node.nodeId(), node);
        }
        validateEdges(byId);
        validateStartNode(byId);
        validateAcyclic(byId);
    }

    private void validateNodeIds() {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("O workflow precisa ter ao menos um no");
        }
        if (nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException(
                    "O workflow excede o limite de " + MAX_NODES + " nos: " + nodes.size());
        }
        Set<String> seen = new HashSet<>();
        for (WorkflowNode node : nodes) {
            String nodeId = node.nodeId();
            if (nodeId.isBlank()) {
                throw new IllegalArgumentException("Existe no com id em branco");
            }
            if (!NODE_ID_PATTERN.matcher(nodeId).matches()) {
                throw new IllegalArgumentException(
                        "Id de no invalido '" + nodeId + "': deve casar com ^[A-Za-z0-9_-]{1,64}$");
            }
            if (!seen.add(nodeId)) {
                throw new IllegalArgumentException("Id de no duplicado: '" + nodeId + "'");
            }
        }
    }

    private void validateEdges(Map<String, WorkflowNode> byId) {
        for (WorkflowNode node : nodes) {
            requireTarget(byId, node.nodeId(), "nextOnSuccess", node.nextOnSuccess());
            requireTarget(byId, node.nodeId(), "nextOnTrue", node.nextOnTrue());
            requireTarget(byId, node.nodeId(), "nextOnFalse", node.nextOnFalse());
            if (node.type() == NodeType.CONDITION) {
                validateConditionNode(node);
            } else {
                validateNonConditionNode(node);
            }
        }
    }

    private void validateConditionNode(WorkflowNode node) {
        if (node.nextOnTrue() == null || node.nextOnFalse() == null) {
            throw new IllegalArgumentException(
                    "No CONDITION '" + node.nodeId() + "' precisa definir nextOnTrue e nextOnFalse");
        }
        if (node.nextOnSuccess() != null) {
            throw new IllegalArgumentException(
                    "No CONDITION '" + node.nodeId() + "' nao pode definir nextOnSuccess");
        }
    }

    private void validateNonConditionNode(WorkflowNode node) {
        if (node.nextOnTrue() != null || node.nextOnFalse() != null) {
            throw new IllegalArgumentException(
                    "No '" + node.nodeId() + "' do tipo " + node.type()
                            + " nao pode definir nextOnTrue nem nextOnFalse");
        }
    }

    private void requireTarget(Map<String, WorkflowNode> byId, String nodeId, String edge, String target) {
        if (target != null && !byId.containsKey(target)) {
            throw new IllegalArgumentException(
                    "Aresta " + edge + " do no '" + nodeId + "' aponta para no inexistente: '" + target + "'");
        }
    }

    private void validateStartNode(Map<String, WorkflowNode> byId) {
        if (startNodeId != null) {
            if (!byId.containsKey(startNodeId)) {
                throw new IllegalArgumentException(
                        "startNodeId '" + startNodeId + "' nao corresponde a nenhum no");
            }
            return;
        }
        Set<String> withInbound = new HashSet<>();
        for (WorkflowNode node : nodes) {
            addTarget(withInbound, node.nextOnSuccess());
            addTarget(withInbound, node.nextOnTrue());
            addTarget(withInbound, node.nextOnFalse());
        }
        List<String> roots = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            if (!withInbound.contains(node.nodeId())) {
                roots.add(node.nodeId());
            }
        }
        if (roots.size() != 1) {
            throw new IllegalArgumentException(
                    "Sem startNodeId definido, o grafo precisa ter exatamente um no sem aresta de entrada, "
                            + "mas foram encontrados " + roots.size());
        }
    }

    private void addTarget(Set<String> targets, String target) {
        if (target != null) {
            targets.add(target);
        }
    }

    private void validateAcyclic(Map<String, WorkflowNode> byId) {
        Map<String, Integer> colors = new HashMap<>();
        for (WorkflowNode node : nodes) {
            colors.put(node.nodeId(), COLOR_WHITE);
        }
        for (WorkflowNode node : nodes) {
            if (colors.get(node.nodeId()) == COLOR_WHITE) {
                depthFirstSearch(node.nodeId(), byId, colors);
            }
        }
    }

    private void depthFirstSearch(String rootId, Map<String, WorkflowNode> byId, Map<String, Integer> colors) {
        Deque<String> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            String current = stack.peek();
            if (colors.get(current) == COLOR_WHITE) {
                colors.put(current, COLOR_GREY);
                for (String next : successors(byId.get(current))) {
                    Integer color = colors.get(next);
                    if (color != null && color == COLOR_GREY) {
                        throw new IllegalArgumentException(
                                "O grafo do workflow possui ciclo: aresta de '" + current + "' para '" + next + "'");
                    }
                    if (color != null && color == COLOR_WHITE) {
                        stack.push(next);
                    }
                }
            } else {
                colors.put(current, COLOR_BLACK);
                stack.pop();
            }
        }
    }

    private List<String> successors(WorkflowNode node) {
        List<String> targets = new ArrayList<>(3);
        addTargetTo(targets, node.nextOnSuccess());
        addTargetTo(targets, node.nextOnTrue());
        addTargetTo(targets, node.nextOnFalse());
        return targets;
    }

    private void addTargetTo(List<String> targets, String target) {
        if (target != null) {
            targets.add(target);
        }
    }
}
