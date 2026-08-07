package com.nexio.workflow.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.tuple;

import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.model.ExecutionStep;
import com.nexio.workflow.domain.model.MapSanitizer;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowExecution;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.ExecutionStatus;
import com.nexio.workflow.domain.model.enums.HttpMethod;
import com.nexio.workflow.domain.model.enums.NodeType;
import com.nexio.workflow.domain.model.enums.StepStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Cobre a caminhada da {@link WorkflowEngine}: o caminho feliz, os dois ramos de um no CONDITION e
 * cada um dos motivos pelos quais ela para antes do fim.
 *
 * <p>Os executores sao dubles. A engine nao pode depender do executor de condicao (issue #22) nem
 * do de HTTP (issue #23) para ser exercitada -- se dependesse, cada teste de caminhada estaria na
 * verdade testando SpEL ou rede.</p>
 *
 * <p>O relogio tambem e duble, e por um motivo especifico: o teto de tempo total so tem um caminho
 * de teste que nao envolva dormir se quem mede o tempo for injetavel. Aqui o no "lento" adianta o
 * relogio, que e a forma mais direta de dizer no proprio teste o que aconteceu.</p>
 */
class WorkflowEngineTest {

    private static final Instant BASE = Instant.parse("2026-01-15T10:00:00Z");
    private static final Duration CAP = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock(BASE);
    private final RecordingExecutionPort port = new RecordingExecutionPort();

    // --- caminho feliz ---

    @Test
    void linearGraphRunsEveryNodeInOrderAndFinishesAsSuccess() {
        WorkflowEngine engine = engine(httpExecutor(node -> NodeExecutionResult.success(
                Map.of("statusCode", 200, "no", node.nodeId()))));

        WorkflowExecution execution = engine.execute(linearDefinition(), Map.of("orderId", 42));

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(execution.getErrorMessage()).isNull();
        assertThat(execution.getWorkflowId()).isEqualTo("wf-1");
        assertThat(execution.getStartedAt()).isEqualTo(BASE);
        assertThat(execution.getFinishedAt()).isNotNull();
        assertThat(execution.getSteps())
                .extracting(ExecutionStep::nodeId, ExecutionStep::status)
                .containsExactly(
                        tuple("start", StepStatus.SUCCESS),
                        tuple("end", StepStatus.SUCCESS));
        assertThat(execution.getSteps().getFirst().output()).containsEntry("statusCode", 200);
    }

    /**
     * O passo vai ao banco pelo acrescimo parcial, e nao por um {@code save} por no: e a decisao da
     * {@code docs/adr/0004-execution-step-persistence.md}, e sem esta asercao ela seria so uma
     * intencao do lado da engine.
     *
     * <p>Os dois {@code save} sao a criacao da execucao ja RUNNING e a gravacao do estado terminal.
     * Um terceiro significaria que voltou a existir gravacao de agregado inteiro dentro da
     * caminhada.</p>
     */
    @Test
    void stepsAreAppendedThroughThePortAndTheAggregateIsSavedOnlyTwice() {
        WorkflowEngine engine = engine(httpExecutor(node -> NodeExecutionResult.success(Map.of())));

        WorkflowExecution execution = engine.execute(linearDefinition(), Map.of());

        assertThat(port.appendedSteps).extracting(ExecutionStep::nodeId).containsExactly("start", "end");
        assertThat(port.appendedExecutionIds).containsOnly(execution.getId());
        assertThat(port.saveCount).isEqualTo(2);
        assertThat(port.statusOnEachSave).containsExactly(ExecutionStatus.RUNNING, ExecutionStatus.SUCCESS);
        assertThat(port.findById(execution.getId()).orElseThrow().getSteps()).hasSize(2);
    }

    /**
     * O contexto e o contrato que as issues #22 e #23 recebem. Uma condicao so tem duas coisas para
     * decidir -- o evento que disparou e o que os nos anteriores devolveram --, e este teste trava
     * as duas.
     */
    @Test
    void contextCarriesTriggerPayloadAndOutputsOfAlreadyExecutedNodes() {
        List<NodeExecutionContext> seen = new ArrayList<>();
        WorkflowEngine engine = engine(new FakeNodeExecutor(NodeType.HTTP_REQUEST, (node, context) -> {
            seen.add(context);
            return NodeExecutionResult.success(Map.of("de", node.nodeId()));
        }));

        WorkflowExecution execution = engine.execute(linearDefinition(), Map.of("orderId", 42));

        assertThat(seen).hasSize(2);
        assertThat(seen.getFirst().executionId()).isEqualTo(execution.getId());
        assertThat(seen.getFirst().triggerPayload()).containsEntry("orderId", 42);
        assertThat(seen.getFirst().outputs()).isEmpty();
        assertThat(seen.get(1).outputOf("start")).containsEntry("de", "start");
        assertThat(seen.get(1).outputOf("no-que-nao-rodou")).isEmpty();
    }

    // --- ramificacao ---

    @Test
    void conditionNodeFollowsNextOnTrue() {
        WorkflowEngine engine = engine(
                conditionExecutor(true),
                httpExecutor(node -> NodeExecutionResult.success(Map.of())));

        WorkflowExecution execution = engine.execute(branchingDefinition(), Map.of());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(execution.getSteps()).extracting(ExecutionStep::nodeId)
                .containsExactly("checa", "caminho-verdadeiro");
    }

    @Test
    void conditionNodeFollowsNextOnFalse() {
        WorkflowEngine engine = engine(
                conditionExecutor(false),
                httpExecutor(node -> NodeExecutionResult.success(Map.of())));

        WorkflowExecution execution = engine.execute(branchingDefinition(), Map.of());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(execution.getSteps()).extracting(ExecutionStep::nodeId)
                .containsExactly("checa", "caminho-falso");
        assertThat(execution.getSteps()).extracting(ExecutionStep::status)
                .containsOnly(StepStatus.SUCCESS);
    }

    /**
     * Condicao falsa nao e falha. A distincao existe porque tratar "falso" como erro faria todo
     * workflow com ramo negativo terminar FAILED.
     */
    @Test
    void falseConditionIsRecordedAsASuccessfulStep() {
        WorkflowEngine engine = engine(
                conditionExecutor(false),
                httpExecutor(node -> NodeExecutionResult.success(Map.of())));

        WorkflowExecution execution = engine.execute(branchingDefinition(), Map.of());

        assertThat(execution.getSteps().getFirst().status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(execution.getSteps().getFirst().error()).isNull();
    }

    // --- paradas ---

    @Test
    void failingNodeStopsTheWalkAndNamesTheNodeInTheExecutionMessage() {
        WorkflowEngine engine = engine(httpExecutor(node -> node.nodeId().equals("start")
                ? NodeExecutionResult.failure("resposta 500 do servico de cobranca", Map.of("statusCode", 500))
                : NodeExecutionResult.success(Map.of())));

        WorkflowExecution execution = engine.execute(linearDefinition(), Map.of());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage())
                .contains("'start'")
                .contains("resposta 500 do servico de cobranca");
        assertThat(execution.getSteps()).extracting(ExecutionStep::nodeId).containsExactly("start");
        assertThat(execution.getSteps().getFirst().status()).isEqualTo(StepStatus.FAILED);
        assertThat(execution.getSteps().getFirst().output()).containsEntry("statusCode", 500);
    }

    /**
     * Um executor com defeito nao pode terminar a execucao sem desfecho.
     *
     * <p>RESULTADO OBSERVADO: a excecao vira passo FALHO e execucao FAILED, e nao sobe. Se subisse,
     * a execucao ficaria gravada RUNNING para sempre -- o estado orfao que a
     * {@code docs/adr/0005-synchronous-execution.md} diz nao existir no modelo sincrono. A mensagem
     * leva o nome simples da excecao e nao a pilha: ela vira campo do documento e resposta ao
     * chamador.</p>
     */
    @Test
    void runtimeExceptionFromAnExecutorBecomesAFailedStepInsteadOfEscaping() {
        WorkflowEngine engine = engine(httpExecutor(node -> {
            throw new IllegalStateException("pool de conexoes esgotado");
        }));

        WorkflowExecution execution = engine.execute(linearDefinition(), Map.of());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage())
                .contains("'start'")
                .contains("IllegalStateException")
                .contains("pool de conexoes esgotado")
                .doesNotContain("com.nexio.workflow.application.engine");
        assertThat(execution.getSteps()).hasSize(1);
    }

    /**
     * Tipo de no sem executor registrado: a execucao falha, nomeando o no e o tipo.
     *
     * <p>DECISAO: falhar, e nao pular o no e seguir. Pular gravaria SUCCESS numa execucao que nao
     * fez o que o workflow manda -- o mesmo "esta certo e nao faz nada" que a validacao de
     * parametros por tipo de no ja recusa na escrita. Nao lancar tambem e decisao: o problema e de
     * implantacao (um executor que ainda nao existe, como hoje, ou que nao subiu), e quem precisa
     * ver isso e quem olha o historico da execucao.</p>
     */
    @Test
    void nodeTypeWithoutARegisteredExecutorFailsTheExecutionAndRecordsTheStep() {
        WorkflowEngine engine = engine(conditionExecutor(true));

        WorkflowExecution execution = engine.execute(linearDefinition(), Map.of());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage())
                .contains("'start'")
                .contains("nenhum executor registrado")
                .contains("HTTP_REQUEST");
        assertThat(execution.getSteps()).extracting(ExecutionStep::nodeId).containsExactly("start");
        assertThat(execution.getSteps().getFirst().status()).isEqualTo(StepStatus.FAILED);
    }

    /**
     * O teto de passos e o anteparo do ciclo em tempo de execucao.
     *
     * <p>A definicao usada aqui cicla e nunca passaria por {@code validateGraph()}. E de proposito:
     * ela representa o documento gravado antes da regra de ciclo existir, que e o unico jeito de um
     * grafo ciclico chegar a engine -- e o unico motivo de o teto ser um anteparo e nao teoria.</p>
     */
    @Test
    void stepCeilingStopsAGraphThatCyclesAtRuntime() {
        WorkflowEngine engine = engine(httpExecutor(node -> NodeExecutionResult.success(Map.of())));

        WorkflowExecution execution = engine.execute(cyclicDefinition(), Map.of());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage())
                .contains("Limite de " + WorkflowExecution.MAX_STEPS + " passos");
        assertThat(execution.getSteps()).hasSize(WorkflowExecution.MAX_STEPS);
        assertThat(port.appendedSteps).hasSize(WorkflowExecution.MAX_STEPS);
    }

    /**
     * Teto de tempo total: o primeiro no consome mais que o teto e o segundo nao chega a rodar.
     *
     * <p>A mensagem e distinta da de falha de no de proposito -- quem le o historico precisa
     * distinguir "o servico respondeu erro" de "a execucao inteira demorou demais", que tem causa e
     * conserto diferentes.</p>
     */
    @Test
    void totalTimeCapStopsTheWalkWithItsOwnMessage() {
        WorkflowEngine engine = engine(httpExecutor(node -> {
            clock.advance(Duration.ofSeconds(40));
            return NodeExecutionResult.success(Map.of());
        }));

        WorkflowExecution execution = engine.execute(linearDefinition(), Map.of());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage())
                .contains("Tempo limite total de execucao")
                .contains("PT30S")
                .contains("'end'");
        assertThat(execution.getSteps()).extracting(ExecutionStep::nodeId).containsExactly("start");
    }

    /**
     * O teto nao interrompe um no em andamento -- a engine nao mata thread de executor. O primeiro
     * no roda inteiro mesmo estourando o teto sozinho, e e o proximo que nao comeca.
     */
    @Test
    void theCapNeverInterruptsANodeAlreadyRunning() {
        WorkflowEngine engine = engine(httpExecutor(node -> {
            clock.advance(Duration.ofHours(1));
            return NodeExecutionResult.success(Map.of("terminou", true));
        }));

        WorkflowExecution execution = engine.execute(linearDefinition(), Map.of());

        assertThat(execution.getSteps()).hasSize(1);
        assertThat(execution.getSteps().getFirst().output()).containsEntry("terminou", true);
    }

    /**
     * Aresta que aponta para no inexistente. {@code validateEdges()} recusa isso na escrita, entao
     * so chega aqui documento gravado antes da regra -- e a resposta e a mesma de qualquer outro
     * problema estrutural descoberto no meio da caminhada: execucao FAILED, com o registro do que
     * ja tinha rodado.
     */
    @Test
    void danglingEdgeInAStoredDefinitionFailsTheExecution() {
        WorkflowDefinition definition = definition(List.of(
                WorkflowNode.httpRequest("start", "https://exemplo.test", HttpMethod.GET, null, null, "sumiu")),
                "start");
        WorkflowEngine engine = engine(httpExecutor(node -> NodeExecutionResult.success(Map.of())));

        WorkflowExecution execution = engine.execute(definition, Map.of());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage()).contains("'sumiu'");
        assertThat(execution.getSteps()).extracting(ExecutionStep::nodeId).containsExactly("start");
    }

    /**
     * Grafo sem raiz resolvivel. Nao lanca: a engine sempre termina com uma execucao gravada em
     * estado terminal, senao o operador recebe erro de servidor e nenhum registro do disparo.
     */
    @Test
    void unresolvableStartNodeIsRecordedAsAFailedExecutionInsteadOfThrowing() {
        WorkflowDefinition definition = definition(List.of(
                WorkflowNode.httpRequest("start", "https://exemplo.test", HttpMethod.GET, null, null, null)),
                "nao-existe");
        WorkflowEngine engine = engine(httpExecutor(node -> NodeExecutionResult.success(Map.of())));

        WorkflowExecution execution = engine.execute(definition, Map.of());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage())
                .contains("no inicial")
                .contains("nao-existe");
        assertThat(execution.getSteps()).isEmpty();
    }

    /**
     * Desfecho incompativel com o tipo do no.
     *
     * <p>Um no HTTP que devolvesse ramo de condicao seguiria por {@code nextOnTrue}, que num no nao
     * condicional e sempre nulo: a caminhada terminaria ali e a execucao seria gravada SUCCESS
     * tendo pulado o resto do workflow. Falhar e a unica resposta que nao inventa caminho.</p>
     */
    @Test
    void conditionalOutcomeFromANonConditionalNodeFailsTheExecution() {
        WorkflowEngine engine = engine(httpExecutor(node -> NodeExecutionResult.condition(true, Map.of())));

        WorkflowExecution execution = engine.execute(linearDefinition(), Map.of());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage())
                .contains("'start'")
                .contains("CONDITION_TRUE")
                .contains("HTTP_REQUEST");
        assertThat(execution.getSteps()).hasSize(1);
    }

    /**
     * O simetrico: um no CONDITION que devolve SUCCESS seguiria por {@code nextOnSuccess}, que a
     * validacao proibe de existir num CONDITION.
     */
    @Test
    void plainSuccessFromAConditionNodeFailsTheExecution() {
        WorkflowEngine engine = engine(
                new FakeNodeExecutor(NodeType.CONDITION, (node, context) -> NodeExecutionResult.success(Map.of())),
                httpExecutor(node -> NodeExecutionResult.success(Map.of())));

        WorkflowExecution execution = engine.execute(branchingDefinition(), Map.of());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage()).contains("'checa'").contains("SUCCESS");
    }

    /**
     * Saida hostil vinda de um servico de terceiro: a gravacao do passo a recusa, e a engine
     * transforma a recusa em execucao FAILED.
     *
     * <p>RESULTADO OBSERVADO: a recusa nao sobe, e o passo recusado nao entra no agregado. As duas
     * coisas sao a mesma decisao: se o passo entrasse em memoria, o {@code save} final seria
     * recusado pelo mesmo motivo e a execucao ficaria gravada RUNNING, sem estado terminal
     * nenhum.</p>
     */
    @Test
    void stepRejectedByThePersistenceValidationFailsTheExecutionWithoutEscaping() {
        WorkflowEngine engine = engine(httpExecutor(node -> NodeExecutionResult.success(
                Map.of("$where", "1"))));

        WorkflowExecution execution = engine.execute(linearDefinition(), Map.of());

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getErrorMessage())
                .contains("'start'")
                .contains("recusado na gravacao");
        assertThat(execution.getSteps()).isEmpty();
        assertThat(port.appendedSteps).isEmpty();
        assertThat(port.findById(execution.getId()).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.FAILED);
    }

    // --- construcao ---

    /**
     * Dois executores para o mesmo tipo derrubam a construcao. A lista chega do contexto do Spring e
     * a ordem dela nao e contratada em lugar nenhum: escolher em silencio faria todo no daquele tipo
     * trocar de implementacao ao sabor do classpath.
     */
    @Test
    void twoExecutorsForTheSameNodeTypeAreRejectedAtConstruction() {
        assertThatIllegalStateException()
                .isThrownBy(() -> engine(
                        httpExecutor(node -> NodeExecutionResult.success(Map.of())),
                        httpExecutor(node -> NodeExecutionResult.success(Map.of()))))
                .withMessageContaining("HTTP_REQUEST");
    }

    @Test
    void engineWithoutAnyExecutorStillBuilds() {
        assertThatCode(this::engine).doesNotThrowAnyException();
    }

    // --- montagens ---

    private WorkflowEngine engine(NodeExecutor... executors) {
        return new WorkflowEngine(List.of(executors), port, clock, CAP);
    }

    private FakeNodeExecutor httpExecutor(Function<WorkflowNode, NodeExecutionResult> body) {
        return new FakeNodeExecutor(NodeType.HTTP_REQUEST, (node, context) -> body.apply(node));
    }

    private FakeNodeExecutor conditionExecutor(boolean value) {
        return new FakeNodeExecutor(NodeType.CONDITION,
                (node, context) -> NodeExecutionResult.condition(value, Map.of("avaliou", value)));
    }

    private WorkflowDefinition linearDefinition() {
        return definition(List.of(
                WorkflowNode.httpRequest("start", "https://exemplo.test", HttpMethod.GET, null, null, "end"),
                WorkflowNode.httpRequest("end", "https://exemplo.test/fim", HttpMethod.GET, null, null, null)),
                "start");
    }

    private WorkflowDefinition branchingDefinition() {
        return definition(List.of(
                WorkflowNode.condition("checa", "#trigger['orderId'] > 0",
                        "caminho-verdadeiro", "caminho-falso"),
                WorkflowNode.httpRequest("caminho-verdadeiro", "https://exemplo.test/v",
                        HttpMethod.GET, null, null, null),
                WorkflowNode.httpRequest("caminho-falso", "https://exemplo.test/f",
                        HttpMethod.GET, null, null, null)),
                "checa");
    }

    private WorkflowDefinition cyclicDefinition() {
        return definition(List.of(
                WorkflowNode.httpRequest("a", "https://exemplo.test/a", HttpMethod.GET, null, null, "b"),
                WorkflowNode.httpRequest("b", "https://exemplo.test/b", HttpMethod.GET, null, null, "a")),
                "a");
    }

    private WorkflowDefinition definition(List<WorkflowNode> nodes, String startNodeId) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId("wf-1");
        definition.setName("cobranca diaria");
        definition.setNodes(nodes);
        definition.setStartNodeId(startNodeId);
        return definition;
    }

    /**
     * Executor de mentira: declara um tipo e delega o corpo ao teste.
     */
    private static final class FakeNodeExecutor implements NodeExecutor {

        private final NodeType type;
        private final Body body;

        private FakeNodeExecutor(NodeType type, Body body) {
            this.type = type;
            this.body = body;
        }

        @Override
        public NodeType supportedType() {
            return type;
        }

        @Override
        public NodeExecutionResult execute(WorkflowNode node, NodeExecutionContext context) {
            return body.run(node, context);
        }

        @FunctionalInterface
        private interface Body {
            NodeExecutionResult run(WorkflowNode node, NodeExecutionContext context);
        }
    }

    /**
     * Porta de mentira que guarda o que foi gravado e <b>aplica a mesma politica estrita do
     * adaptador real</b> no acrescimo de passo.
     *
     * <p>A validacao esta aqui porque sem ela o duble seria mais permissivo que a producao
     * exatamente no ponto que a {@code docs/adr/0004-execution-step-persistence.md} diz ser o mais
     * arriscado, e o teste da saida hostil passaria por nao verificar nada.</p>
     */
    private static final class RecordingExecutionPort implements WorkflowExecutionPort {

        private final Map<String, WorkflowExecution> stored = new LinkedHashMap<>();
        private final List<ExecutionStep> appendedSteps = new ArrayList<>();
        private final List<String> appendedExecutionIds = new ArrayList<>();
        private final List<ExecutionStatus> statusOnEachSave = new ArrayList<>();
        private int saveCount;

        @Override
        public WorkflowExecution save(WorkflowExecution execution) {
            saveCount++;
            statusOnEachSave.add(execution.getStatus());
            stored.put(execution.getId(), execution);
            return execution;
        }

        @Override
        public void appendStep(String executionId, ExecutionStep step) {
            if (!stored.containsKey(executionId)) {
                throw new IllegalStateException("execucao inexistente: " + executionId);
            }
            try {
                MapSanitizer.validate(step.output(), "steps[].output");
            } catch (IllegalArgumentException e) {
                throw new InvalidWorkflowException(e.getMessage(), e);
            }
            if (appendedSteps.size() >= WorkflowExecution.MAX_STEPS) {
                throw new InvalidWorkflowException("Limite de passos atingido");
            }
            appendedExecutionIds.add(executionId);
            appendedSteps.add(step);
        }

        @Override
        public Optional<WorkflowExecution> findById(String id) {
            return Optional.ofNullable(stored.get(id));
        }

        @Override
        public List<WorkflowExecution> findByWorkflowId(String workflowId, PageQuery page) {
            return List.copyOf(stored.values());
        }

        @Override
        public long deleteByWorkflowId(String workflowId) {
            return 0L;
        }
    }

    /**
     * Relogio que so anda quando o teste manda.
     */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("o relogio de teste nao muda de fuso");
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
