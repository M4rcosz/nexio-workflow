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
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

/**
 * Cobre as invariantes de dominio do modelo: a politica leniente da copia usada na hidratacao, a
 * politica estrita usada na escrita, a imutabilidade das colecoes expostas, a maquina de estados da
 * execucao e a validacao do grafo de nos.
 */
class DomainInvariantsTest {

    // --- politica leniente: e a que roda na hidratacao ---

    /**
     * Os construtores compactos dos records rodam quando o Spring Data instancia um documento lido
     * do MongoDB. Se aplicassem a regra estrita, um unico documento malformado tornaria ilegiveis
     * tanto ele quanto qualquer listagem que o incluisse. Aqui eles precisam aceitar tudo.
     */
    @Test
    void copyShouldAcceptWhatTheStrictPolicyRejects() {
        Map<String, Object> hostile = new LinkedHashMap<>();
        hostile.put("$where", "1");
        hostile.put("_class", "java.lang.String");
        hostile.put("weird key!!", 1);
        hostile.put("a.b", "ponto");

        WorkflowNode node = new WorkflowNode("n1", NodeType.HTTP_REQUEST, hostile, null, null, null);

        assertThat(node.config())
                .containsEntry("$where", "1")
                .containsEntry("_class", "java.lang.String")
                .containsEntry("weird key!!", 1)
                .containsEntry("a.b", "ponto");
    }

    /**
     * Tipos que so o driver produz (BSON) chegam pela leitura e nao podem derrubar a copia.
     */
    @Test
    void copyShouldPassThroughBsonSpecificTypes() {
        ObjectId objectId = new ObjectId();
        Map<String, Object> bson = new LinkedHashMap<>();
        bson.put("decimal", Decimal128.parse("10.5"));
        bson.put("objectId", objectId);
        bson.put("binary", new Binary(new byte[]{1, 2, 3}));

        WorkflowExecution execution = new WorkflowExecution();
        assertThatCode(() -> execution.setTriggerPayload(bson)).doesNotThrowAnyException();
        assertThat(execution.getTriggerPayload()).containsEntry("objectId", objectId);
    }

    /**
     * {@link Date} e mutavel e escaparia por referencia pelos getters, anulando a copia defensiva;
     * a copia normaliza para {@link Instant}, que e imutavel.
     */
    @Test
    void copyShouldNormalizeDatesToInstant() {
        Instant moment = Instant.parse("2026-08-06T12:00:00Z");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("occurredAt", Date.from(moment));

        WorkflowExecution execution = new WorkflowExecution();
        execution.setTriggerPayload(payload);

        assertThat(execution.getTriggerPayload()).containsEntry("occurredAt", moment);
    }

    @Test
    void copyShouldStillGuardAgainstPathologicalStructures() {
        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        for (int i = 0; i < MapSanitizer.COPY_MAX_DEPTH + 1; i++) {
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
        headers.put("x_trace", "mutado depois da copia");

        Map<?, ?> nestedCopy = (Map<?, ?>) node.config().get("headers");
        assertThat(nestedCopy).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("x_trace", "original");
        assertThatCode(nestedCopy::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    // --- politica estrita: e a que roda na escrita ---

    @Test
    void validateShouldRejectOperatorAndUnderscoreKeys() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MapSanitizer.validate(Map.of("$where", "1"), "config"))
                .withMessageContaining("'$'");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MapSanitizer.validate(Map.of("_class", "java.lang.Runtime"), "config"))
                .withMessageContaining("'_'");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MapSanitizer.validate(Map.of("_id", "x"), "config"))
                .withMessageContaining("'_'");
    }

    /**
     * O payload de gatilho e JSON externo arbitrario: a regra antiga
     * ({@code ^[A-Za-z0-9_][A-Za-z0-9_-]{0,63}$}) rejeitava webhook legitimo. O ponto tambem passa,
     * porque o {@code MongoConfig} configura {@code setMapKeyDotReplacement}.
     */
    @Test
    void validateShouldAcceptLegitimateWebhookKeys() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("@timestamp", "2026-08-06T12:00:00Z");
        payload.put("descrição", "ok");
        payload.put("preço médio", 10.5);
        payload.put("urn:ietf:params:scim:schemas:core:2.0:User", Map.of("ativo", true));
        payload.put("com.exemplo.evento", "criado");
        payload.put("k".repeat(MapSanitizer.MAX_KEY_LENGTH), "chave longa mas dentro do teto");

        assertThatCode(() -> MapSanitizer.validate(payload, "triggerPayload")).doesNotThrowAnyException();
    }

    @Test
    void validateShouldRejectOversizedKeysAndStrings() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MapSanitizer.validate(
                        Map.of("k".repeat(MapSanitizer.MAX_KEY_LENGTH + 1), "v"), "config"))
                .withMessageContaining("excede " + MapSanitizer.MAX_KEY_LENGTH + " caracteres");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> MapSanitizer.validate(
                        Map.of("a", "x".repeat(MapSanitizer.MAX_STRING_LENGTH + 1)), "config"))
                .withMessageContaining("excede " + MapSanitizer.MAX_STRING_LENGTH + " caracteres");
    }

    /**
     * {@code Number} aberto deixava passar numero mutavel e numero sem codec BSON, que so
     * explodiriam la dentro do {@code save()}, longe da fronteira de confianca.
     */
    @Test
    void validateShouldRejectNumbersOutsideTheAllowList() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MapSanitizer.validate(Map.of("contador", new AtomicInteger(1)), "config"))
                .withMessageContaining("AtomicInteger");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MapSanitizer.validate(Map.of("grande", BigInteger.TEN), "config"))
                .withMessageContaining("BigInteger");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MapSanitizer.validate(Map.of("decimal", Decimal128.parse("1.5")), "config"))
                .withMessageContaining("Decimal128");
    }

    @Test
    void validateShouldRejectUnsupportedValueTypeNamingThePath() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("body", Map.of("bad", new StringBuilder("x")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> MapSanitizer.validate(nested, "steps.output"))
                .withMessageContaining("steps.output.body.bad")
                .withMessageContaining("StringBuilder");
    }

    @Test
    void validateShouldRejectTooManyEntriesAndTooDeepNesting() {
        Map<String, Object> wide = new LinkedHashMap<>();
        for (int i = 0; i <= MapSanitizer.MAX_ENTRIES; i++) {
            wide.put("k" + i, i);
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MapSanitizer.validate(wide, "config"))
                .withMessageContaining("entradas excedido");

        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        for (int i = 0; i < MapSanitizer.MAX_DEPTH + 1; i++) {
            Map<String, Object> child = new LinkedHashMap<>();
            cursor.put("child", child);
            cursor = child;
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MapSanitizer.validate(deep, "config"))
                .withMessageContaining("aninhamento excedido");
    }

    /**
     * A chave vem de fora e cai em log dentro da mensagem de erro: sem higienizacao, ela forja
     * linhas de log inteiras. A mensagem tambem nao pode carregar uma chave gigante.
     */
    @Test
    void validateShouldSanitizeOffendingKeysInMessages() {
        String forged = "$x\n2026-08-06 ERROR [audit] linha forjada";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(forged, "1");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> MapSanitizer.validate(payload, "triggerPayload"))
                .satisfies(error -> {
                    assertThat(error.getMessage()).doesNotContain("\n").doesNotContain("\r");
                    assertThat(error.getMessage()).contains("$x?2026-08-06 ERROR [audit] linha forjada");
                });

        String huge = "$" + "k".repeat(500);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MapSanitizer.validate(Map.of(huge, "1"), "triggerPayload"))
                .satisfies(error -> assertThat(error.getMessage()).hasSizeLessThan(200));
    }

    // --- entidades ---

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
    void shouldExposeUnmodifiableCollections() {
        WorkflowExecution execution = new WorkflowExecution();
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.PENDING);

        ExecutionStep step = new ExecutionStep("n1", StepStatus.SUCCESS, Map.of(), null, Instant.now());
        execution.addStep(step);
        assertThatCode(() -> execution.getSteps().add(step)).isInstanceOf(UnsupportedOperationException.class);
        assertThatCode(() -> execution.getTriggerPayload().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * A maquina de estados e {@code PENDING -> RUNNING -> (SUCCESS | FAILED)}, com os terminais
     * finais. O teste anterior afirmava o contrario -- que {@code markSucceeded} depois de
     * {@code markFailed} virava SUCCESS e limpava a mensagem de erro --, o que sob retry
     * assincrono faria a execucao reportar sucesso para uma rodada que falhou.
     */
    @Test
    void shouldEnforceExecutionStateMachine() {
        Instant now = Instant.now();

        WorkflowExecution fromPending = new WorkflowExecution();
        assertThatIllegalStateException()
                .isThrownBy(() -> fromPending.markSucceeded(now))
                .withMessageContaining("PENDING -> SUCCESS");
        assertThatIllegalStateException()
                .isThrownBy(() -> fromPending.markFailed("boom", now))
                .withMessageContaining("PENDING -> FAILED");

        fromPending.markRunning(now);
        assertThat(fromPending.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThatIllegalStateException()
                .isThrownBy(() -> fromPending.markRunning(now))
                .withMessageContaining("RUNNING -> RUNNING");

        fromPending.markFailed("boom", now);
        assertThat(fromPending.getStatus()).isEqualTo(ExecutionStatus.FAILED);

        assertThatIllegalStateException()
                .isThrownBy(() -> fromPending.markSucceeded(now))
                .withMessageContaining("FAILED -> SUCCESS");
        assertThat(fromPending.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(fromPending.getErrorMessage()).isEqualTo("boom");

        WorkflowExecution succeeded = new WorkflowExecution();
        succeeded.markRunning(now);
        succeeded.markSucceeded(now);
        assertThatIllegalStateException()
                .isThrownBy(() -> succeeded.markFailed("tarde demais", now))
                .withMessageContaining("SUCCESS -> FAILED");
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

    /**
     * {@code setSteps} passava direto pelo teto que {@code addStep} protege.
     */
    @Test
    void setStepsShouldEnforceTheSameCapAsAddStep() {
        List<ExecutionStep> steps = new ArrayList<>();
        for (int i = 0; i <= WorkflowExecution.MAX_STEPS; i++) {
            steps.add(new ExecutionStep("n" + i, StepStatus.SUCCESS, Map.of(), null, Instant.now()));
        }
        WorkflowExecution execution = new WorkflowExecution();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> execution.setSteps(steps))
                .withMessageContaining("Limite de 200 passos");
        assertThat(execution.getSteps()).isEmpty();
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

    /**
     * {@code setNodes} nunca verificava o teto de nos, entao quem so chamasse o setter passava por
     * cima do limite que {@code validateGraph} protege.
     */
    @Test
    void setNodesShouldEnforceTheNodeCap() {
        List<WorkflowNode> nodes = new ArrayList<>();
        for (int i = 0; i <= WorkflowDefinition.MAX_NODES; i++) {
            nodes.add(new WorkflowNode("n" + i, NodeType.HTTP_REQUEST, Map.of(), null, null, null));
        }
        WorkflowDefinition definition = new WorkflowDefinition();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> definition.setNodes(nodes))
                .withMessageContaining("limite de 50 nos");
        assertThat(definition.getNodes()).isEmpty();
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
