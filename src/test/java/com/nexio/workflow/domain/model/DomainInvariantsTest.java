package com.nexio.workflow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.nexio.workflow.domain.model.enums.ExecutionStatus;
import com.nexio.workflow.domain.model.enums.NodeType;
import com.nexio.workflow.domain.model.enums.StepStatus;
import com.nexio.workflow.domain.model.enums.TriggerType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

/**
 * Cobre as invariantes de dominio adicionadas ao modelo: validacao de mapas nao confiaveis,
 * imutabilidade das colecoes expostas e validacao do grafo de nos.
 */
class DomainInvariantsTest {

    @Test
    void shouldRejectClassHintKey() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("_class", "java.lang.Runtime");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorkflowNode("n1", NodeType.HTTP_REQUEST, payload, null, null, null))
                .withMessageContaining("_class");
    }

    @Test
    void shouldRejectOperatorAndDottedKeys() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TriggerConfig(TriggerType.MOCK_EVENT, Map.of("$where", "1")))
                .withMessageContaining("'$'");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TriggerConfig(TriggerType.MOCK_EVENT, Map.of("a.b", "1")))
                .withMessageContaining("'.'");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TriggerConfig(TriggerType.MOCK_EVENT, Map.of("com espaco", "1")))
                .withMessageContaining("Chave invalida");
    }

    @Test
    void shouldRejectUnsupportedValueTypeNamingThePath() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("body", Map.of("bad", new StringBuilder("x")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExecutionStep("n1", StepStatus.SUCCESS, nested, null, Instant.now()))
                .withMessageContaining("steps.output.body.bad")
                .withMessageContaining("StringBuilder");
    }

    @Test
    void shouldRejectTooManyEntriesAndTooDeepNesting() {
        Map<String, Object> wide = new LinkedHashMap<>();
        for (int i = 0; i <= MapSanitizer.MAX_ENTRIES; i++) {
            wide.put("k" + i, i);
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TriggerConfig(TriggerType.MOCK_EVENT, wide))
                .withMessageContaining("entradas excedido");

        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        for (int i = 0; i < MapSanitizer.MAX_DEPTH + 1; i++) {
            Map<String, Object> child = new LinkedHashMap<>();
            cursor.put("child", child);
            cursor = child;
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TriggerConfig(TriggerType.MOCK_EVENT, deep))
                .withMessageContaining("aninhamento excedido");
    }

    @Test
    void shouldDeepCopyNestedStructures() {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("x_trace", "original");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("headers", headers);

        WorkflowNode node = new WorkflowNode("n1", NodeType.HTTP_REQUEST, config, null, null, null);
        headers.put("x_trace", "mutado depois da validacao");

        Map<?, ?> nestedCopy = (Map<?, ?>) node.config().get("headers");
        assertThat(nestedCopy).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("x_trace", "original");
        assertThatCode(nestedCopy::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRequireNonNullInvariants() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TriggerConfig(null, Map.of()));
        assertThatNullPointerException()
                .isThrownBy(() -> new WorkflowNode(null, NodeType.HTTP_REQUEST, Map.of(), null, null, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new WorkflowNode("n1", null, Map.of(), null, null, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new ExecutionStep(null, StepStatus.SUCCESS, Map.of(), null, Instant.now()));
        assertThatNullPointerException()
                .isThrownBy(() -> new ExecutionStep("n1", null, Map.of(), null, Instant.now()));
    }

    @Test
    void shouldExposeUnmodifiableCollectionsAndTrackLifecycle() {
        WorkflowExecution execution = new WorkflowExecution();
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.PENDING);

        ExecutionStep step = new ExecutionStep("n1", StepStatus.SUCCESS, Map.of(), null, Instant.now());
        execution.addStep(step);
        assertThatCode(() -> execution.getSteps().add(step)).isInstanceOf(UnsupportedOperationException.class);
        assertThatCode(() -> execution.getTriggerPayload().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);

        Instant now = Instant.now();
        execution.markRunning(now);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        execution.markFailed("boom", now);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage()).isEqualTo("boom");
        execution.markSucceeded(now);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(execution.getErrorMessage()).isNull();
    }

    @Test
    void shouldCapStepsAtTheLimit() {
        WorkflowExecution execution = new WorkflowExecution();
        for (int i = 0; i < WorkflowExecution.MAX_STEPS; i++) {
            execution.addStep(new ExecutionStep("n" + i, StepStatus.SUCCESS, Map.of(), null, Instant.now()));
        }
        assertThatIllegalStateException()
                .isThrownBy(() -> execution.addStep(
                        new ExecutionStep("extra", StepStatus.SUCCESS, Map.of(), null, Instant.now())))
                .withMessageContaining("Limite de 200 passos");
    }

    @Test
    void shouldAcceptValidGraph() {
        WorkflowDefinition definition = definitionWith(validNodes(), "start");
        assertThatCode(definition::validateGraph).doesNotThrowAnyException();

        WorkflowDefinition withoutStart = definitionWith(validNodes(), null);
        assertThatCode(withoutStart::validateGraph).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectInvalidGraphs() {
        WorkflowDefinition empty = definitionWith(List.of(), null);
        assertThatIllegalArgumentException().isThrownBy(empty::validateGraph)
                .withMessageContaining("ao menos um no");

        List<WorkflowNode> duplicated = List.of(
                new WorkflowNode("a", NodeType.HTTP_REQUEST, Map.of(), null, null, null),
                new WorkflowNode("a", NodeType.HTTP_REQUEST, Map.of(), null, null, null));
        assertThatIllegalArgumentException().isThrownBy(definitionWith(duplicated, null)::validateGraph)
                .withMessageContaining("duplicado");

        List<WorkflowNode> badId = List.of(
                new WorkflowNode("a b", NodeType.HTTP_REQUEST, Map.of(), null, null, null));
        assertThatIllegalArgumentException().isThrownBy(definitionWith(badId, null)::validateGraph)
                .withMessageContaining("Id de no invalido");

        List<WorkflowNode> dangling = List.of(
                new WorkflowNode("a", NodeType.HTTP_REQUEST, Map.of(), "ghost", null, null));
        assertThatIllegalArgumentException().isThrownBy(definitionWith(dangling, null)::validateGraph)
                .withMessageContaining("no inexistente");

        assertThatIllegalArgumentException()
                .isThrownBy(definitionWith(validNodes(), "ghost")::validateGraph)
                .withMessageContaining("startNodeId");
    }

    @Test
    void shouldRejectConditionEdgeMismatch() {
        List<WorkflowNode> conditionWithoutBranches = List.of(
                new WorkflowNode("c", NodeType.CONDITION, Map.of(), "d", null, null),
                new WorkflowNode("d", NodeType.HTTP_REQUEST, Map.of(), null, null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(definitionWith(conditionWithoutBranches, null)::validateGraph)
                .withMessageContaining("nextOnTrue e nextOnFalse");

        List<WorkflowNode> httpWithBranches = List.of(
                new WorkflowNode("h", NodeType.HTTP_REQUEST, Map.of(), null, "d", "d"),
                new WorkflowNode("d", NodeType.HTTP_REQUEST, Map.of(), null, null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(definitionWith(httpWithBranches, null)::validateGraph)
                .withMessageContaining("nao pode definir nextOnTrue");
    }

    @Test
    void shouldRejectCycles() {
        List<WorkflowNode> cycle = List.of(
                new WorkflowNode("a", NodeType.HTTP_REQUEST, Map.of(), "b", null, null),
                new WorkflowNode("b", NodeType.HTTP_REQUEST, Map.of(), "c", null, null),
                new WorkflowNode("c", NodeType.HTTP_REQUEST, Map.of(), "a", null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(definitionWith(cycle, "a")::validateGraph)
                .withMessageContaining("ciclo");

        List<WorkflowNode> selfLoop = List.of(
                new WorkflowNode("a", NodeType.HTTP_REQUEST, Map.of(), "a", null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(definitionWith(selfLoop, "a")::validateGraph)
                .withMessageContaining("ciclo");
    }

    @Test
    void shouldAcceptDiamondShapedGraph() {
        List<WorkflowNode> diamond = List.of(
                new WorkflowNode("a", NodeType.CONDITION, Map.of(), null, "b", "c"),
                new WorkflowNode("b", NodeType.HTTP_REQUEST, Map.of(), "d", null, null),
                new WorkflowNode("c", NodeType.HTTP_REQUEST, Map.of(), "d", null, null),
                new WorkflowNode("d", NodeType.HTTP_REQUEST, Map.of(), null, null, null));
        assertThatCode(definitionWith(diamond, "a")::validateGraph).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectTooManyNodes() {
        List<WorkflowNode> nodes = new ArrayList<>();
        for (int i = 0; i <= WorkflowDefinition.MAX_NODES; i++) {
            nodes.add(new WorkflowNode("n" + i, NodeType.HTTP_REQUEST, Map.of(), null, null, null));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(definitionWith(nodes, null)::validateGraph)
                .withMessageContaining("limite de 50 nos");
    }

    private List<WorkflowNode> validNodes() {
        return List.of(
                new WorkflowNode("start", NodeType.HTTP_REQUEST, Map.of(), "check", null, null),
                new WorkflowNode("check", NodeType.CONDITION, Map.of(), null, "done", "done"),
                new WorkflowNode("done", NodeType.HTTP_REQUEST, Map.of(), null, null, null));
    }

    private WorkflowDefinition definitionWith(List<WorkflowNode> nodes, String startNodeId) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setNodes(nodes);
        definition.setStartNodeId(startNodeId);
        return definition;
    }
}
