package com.nexio.workflow.application.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexio.workflow.AbstractMongoIntegrationTest;
import com.nexio.workflow.WriteValidationTestConfig;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowExecution;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.ExecutionStatus;
import com.nexio.workflow.domain.model.enums.HttpMethod;
import com.nexio.workflow.domain.model.enums.NodeType;
import com.nexio.workflow.infrastructure.config.MongoConfig;
import com.nexio.workflow.infrastructure.mongodb.WorkflowExecutionMongoAdapter;
import com.nexio.workflow.infrastructure.mongodb.WorkflowExecutionWriteValidationCallback;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;

/**
 * Exercita a engine contra o MongoDB real, e nao contra uma porta de mentira.
 *
 * <p>Existe por causa de um defeito que nenhum teste de unidade da engine podia enxergar. Todos eles
 * usam uma porta falsa, e uma porta falsa nao reproduz o que o Spring Data faz com a
 * {@code @Version}: {@code updateFirst} <b>incrementa</b> a versao do documento. Ou seja, os
 * {@code appendStep} de uma execucao deixavam o documento em versao N enquanto o agregado em memoria
 * continuava na versao 0, e a gravacao final do estado terminal batia em bloqueio otimista -- toda
 * execucao com pelo menos um no falhava, e a suite inteira passava.</p>
 *
 * <p>E exatamente a lacuna que a revisao de backend apontou: cada camada testada com a de baixo
 * mockada nao pega o defeito que mora <i>entre</i> elas.</p>
 */
@DataMongoTest
@Import({MongoConfig.class,
        WriteValidationTestConfig.class,
        WorkflowExecutionMongoAdapter.class,
        WorkflowExecutionWriteValidationCallback.class})
class WorkflowEngineIntegrationTest extends AbstractMongoIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private WorkflowExecutionPort executionPort;

    /**
     * Um workflow de tres nos termina em SUCCESS com os tres passos gravados.
     *
     * <p>Antes da correcao esta execucao morria com o conflito de versao na gravacao final. O teste
     * cobre o caminho inteiro -- criacao, tres {@code appendStep} e o estado terminal -- porque o
     * defeito so aparece quando o numero de acrescimos e maior que zero.</p>
     */
    @Test
    void executionWithSeveralStepsFinishesWithoutAVersionConflict() {
        WorkflowExecution finished = engine().execute(definition(), Map.of("orderId", 42));

        assertThat(finished.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(finished.getSteps()).extracting(step -> step.nodeId())
                .containsExactly("start", "meio", "fim");

        WorkflowExecution reread = executionPort.findById(finished.getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(reread.getSteps()).hasSize(3);
        assertThat(reread.getTriggerPayload()).containsEntry("orderId", 42);
        assertThat(reread.getFinishedAt()).isNotNull();
    }

    /** O estado terminal gravado tem que ser o que ficou no banco, e nao so o do objeto devolvido. */
    @Test
    void aFailingNodeIsRecordedAsFailedInTheStoredDocument() {
        WorkflowEngine engine = new WorkflowEngine(
                List.of(new FixedExecutor(NodeExecutionResult.failure("servico fora do ar"))),
                executionPort,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));

        WorkflowExecution finished = engine.execute(definition(), Map.of());

        assertThat(finished.getStatus()).isEqualTo(ExecutionStatus.FAILED);

        WorkflowExecution reread = executionPort.findById(finished.getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(reread.getErrorMessage()).contains("start");
        assertThat(reread.getSteps()).hasSize(1);
    }

    private WorkflowEngine engine() {
        return new WorkflowEngine(
                List.of(new FixedExecutor(NodeExecutionResult.success(Map.of("statusCode", 200)))),
                executionPort,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
    }

    private static WorkflowDefinition definition() {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId("wf-engine-integracao");
        definition.setName("execucao de ponta a ponta");
        definition.setNodes(List.of(
                WorkflowNode.httpRequest("start", "https://exemplo.test/1", HttpMethod.GET, null, null, "meio"),
                WorkflowNode.httpRequest("meio", "https://exemplo.test/2", HttpMethod.GET, null, null, "fim"),
                WorkflowNode.httpRequest("fim", "https://exemplo.test/3", HttpMethod.GET, null, null, null)));
        definition.setStartNodeId("start");
        return definition;
    }

    /**
     * Apagar a execucao no meio da caminhada nao deixa o registro preso em RUNNING.
     *
     * <p>E alcancavel hoje: apagar o workflow durante o disparo faz a cascata do
     * {@code DeleteWorkflowUseCase} levar as execucoes junto. Antes da correcao o
     * {@code appendStep} lancava {@code WorkflowNotFoundException}, o {@code recordStep} so pegava
     * {@code InvalidWorkflowException}, e a excecao escapava do {@code execute()} inteiro: nenhum
     * estado terminal era gravado, a execucao ficava RUNNING para sempre -- o orfao que a ADR 0005
     * afirma nao existir no modelo sincrono -- e quem disparou recebia NOT_FOUND citando um id de
     * execucao que nunca enviou.</p>
     */
    @Test
    void anExecutionDeletedMidWalkEndsFailedInsteadOfEscapingAsAnException() {
        WorkflowEngine engine = new WorkflowEngine(
                List.of(new DeletingExecutor(executionPort)),
                executionPort,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));

        WorkflowExecution finished = engine.execute(definition(), Map.of());

        assertThat(finished.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(finished.getErrorMessage()).isNotBlank();
        assertThat(executionPort.findById(finished.getId())).isEmpty();
    }

    /**
     * Um no CONDITION real escolhe o ramo, e a caminhada segue por ele.
     *
     * <p>Os testes de unidade do executor conferem o desfecho que ele devolve e os testes de
     * unidade da engine conferem a aresta que ela escolhe para cada desfecho, cada um com o outro
     * lado dublado. O que nenhum dos dois cobre e a juncao: que o {@code CONDITION_TRUE} produzido
     * pelo executor de verdade e o mesmo que a engine de verdade traduz em {@code nextOnTrue}. E a
     * mesma lacuna entre camadas que deixou passar o conflito de versao.</p>
     */
    @ParameterizedTest
    @CsvSource({"150, ramo-verdadeiro", "10, ramo-falso"})
    void aRealConditionNodeSteersTheWalkDownTheBranchItChose(int total, String expectedSecondStep) {
        WorkflowEngine engine = new WorkflowEngine(
                List.of(new ConditionNodeExecutor(),
                        new FixedExecutor(NodeExecutionResult.success(Map.of("statusCode", 200)))),
                executionPort,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));

        WorkflowExecution finished = engine.execute(branchingDefinition(), Map.of("total", total));

        assertThat(finished.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(finished.getSteps()).extracting(step -> step.nodeId())
                .containsExactly("checa", expectedSecondStep);
        assertThat(finished.getSteps().getFirst().output())
                .containsEntry("result", total > 100);
    }

    private static WorkflowDefinition branchingDefinition() {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId("wf-condicao-integracao");
        definition.setName("condicao escolhe o ramo");
        definition.setNodes(List.of(
                WorkflowNode.condition("checa", "total > 100", "ramo-verdadeiro", "ramo-falso"),
                WorkflowNode.httpRequest("ramo-verdadeiro", "https://exemplo.test/caro",
                        HttpMethod.POST, null, null, null),
                WorkflowNode.httpRequest("ramo-falso", "https://exemplo.test/barato",
                        HttpMethod.POST, null, null, null)));
        definition.setStartNodeId("checa");
        definition.validateGraph();
        return definition;
    }

    /** Apaga a execucao antes de devolver, simulando a cascata de remocao do workflow. */
    private record DeletingExecutor(WorkflowExecutionPort port) implements NodeExecutor {

        @Override
        public NodeType supportedType() {
            return NodeType.HTTP_REQUEST;
        }

        @Override
        public NodeExecutionResult execute(WorkflowNode node, NodeExecutionContext context) {
            port.deleteByWorkflowId("wf-engine-integracao");
            return NodeExecutionResult.success(Map.of());
        }
    }

    /** Executor de no que devolve sempre o mesmo resultado: o que esta sob teste e a engine. */
    private record FixedExecutor(NodeExecutionResult result) implements NodeExecutor {

        @Override
        public NodeType supportedType() {
            return NodeType.HTTP_REQUEST;
        }

        @Override
        public NodeExecutionResult execute(WorkflowNode node, NodeExecutionContext context) {
            return result;
        }
    }
}
