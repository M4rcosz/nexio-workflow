package com.nexio.workflow.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexio.workflow.application.engine.WorkflowEngine;
import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowExecution;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Testes dos casos de uso de execucao.
 */
class ExecutionUseCasesTest {

    private final WorkflowDefinitionPort definitionPort = mock(WorkflowDefinitionPort.class);
    private final WorkflowExecutionPort executionPort = mock(WorkflowExecutionPort.class);
    private final WorkflowEngine engine = mock(WorkflowEngine.class);

    private final TriggerWorkflowUseCase trigger = new TriggerWorkflowUseCase(definitionPort, engine);
    private final GetExecutionUseCase get = new GetExecutionUseCase(executionPort);
    private final ListExecutionsUseCase list = new ListExecutionsUseCase(executionPort, definitionPort);

    @Test
    void triggerRunsTheEngineAndReturnsTheFinishedExecution() {
        WorkflowDefinition definition = definition(true);
        WorkflowExecution execution = new WorkflowExecution();
        when(definitionPort.findById("wf-1")).thenReturn(Optional.of(definition));
        when(engine.execute(eq(definition), any())).thenReturn(execution);

        WorkflowExecution result = trigger.execute(ActorId.ANONYMOUS, "wf-1", Map.of("total", 150));

        assertThat(result).isSameAs(execution);
    }

    /**
     * Workflow desligado nao dispara, e nenhuma execucao e criada.
     *
     * <p>Ignorar {@code enabled} tornaria a flag decorativa: quem desligou um workflow porque o
     * servico do outro lado esta com problema espera que desligar tenha efeito. E a recusa nao pode
     * deixar rastro no historico -- um registro para um disparo que nunca comecou polui justamente o
     * lugar onde alguem procura o que de fato rodou.</p>
     */
    @Test
    void triggerRefusesADisabledWorkflowWithoutCreatingAnExecution() {
        when(definitionPort.findById("wf-1")).thenReturn(Optional.of(definition(false)));

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> trigger.execute(ActorId.ANONYMOUS, "wf-1", Map.of()))
                .withMessageContaining("desativado");

        verify(engine, never()).execute(any(), any());
    }

    /**
     * Workflow desligado e BAD_REQUEST e nao NOT_FOUND.
     *
     * <p>O workflow existe. Dizer "nao encontrado" mandaria quem chamou procurar um erro de
     * identificador que nao existe, e esconderia a unica acao que resolve: reativar.</p>
     */
    @Test
    void aDisabledWorkflowIsNotReportedAsMissing() {
        when(definitionPort.findById("wf-1")).thenReturn(Optional.of(definition(false)));

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> trigger.execute(ActorId.ANONYMOUS, "wf-1", Map.of()));
    }

    @Test
    void triggerReportsAnUnknownWorkflowAsNotFound() {
        when(definitionPort.findById("nao-existe")).thenReturn(Optional.empty());

        assertThatExceptionOfType(WorkflowNotFoundException.class)
                .isThrownBy(() -> trigger.execute(ActorId.ANONYMOUS, "nao-existe", Map.of()));
    }

    @Test
    void triggerAcceptsAnAbsentPayload() {
        WorkflowDefinition definition = definition(true);
        when(definitionPort.findById("wf-1")).thenReturn(Optional.of(definition));
        when(engine.execute(eq(definition), eq(null))).thenReturn(new WorkflowExecution());

        assertThat(trigger.execute(ActorId.ANONYMOUS, "wf-1", null)).isNotNull();
    }

    @Test
    void getReturnsTheExecution() {
        WorkflowExecution execution = new WorkflowExecution();
        when(executionPort.findById("exec-1")).thenReturn(Optional.of(execution));

        assertThat(get.execute(ActorId.ANONYMOUS, "exec-1")).isSameAs(execution);
    }

    @Test
    void getReportsAnUnknownExecutionAsNotFound() {
        when(executionPort.findById("nao-existe")).thenReturn(Optional.empty());

        assertThatExceptionOfType(WorkflowNotFoundException.class)
                .isThrownBy(() -> get.execute(ActorId.ANONYMOUS, "nao-existe"));
    }

    /**
     * Listar as execucoes de um workflow inexistente e NOT_FOUND, e nao lista vazia.
     *
     * <p>Lista vazia seria indistinguivel de um workflow real que nunca foi disparado, e as duas
     * situacoes pedem reacoes opostas de quem chamou: corrigir o identificador numa, esperar na
     * outra.</p>
     */
    @Test
    void listReportsAnUnknownWorkflowAsNotFoundInsteadOfReturningAnEmptyList() {
        when(definitionPort.existsById("nao-existe")).thenReturn(false);

        assertThatExceptionOfType(WorkflowNotFoundException.class)
                .isThrownBy(() -> list.execute(ActorId.ANONYMOUS, "nao-existe", new PageQuery(20, 0)));

        verify(executionPort, never()).findByWorkflowId(any(), any());
    }

    @Test
    void listReturnsAnEmptyListForAWorkflowThatExistsButWasNeverTriggered() {
        when(definitionPort.existsById("wf-1")).thenReturn(true);
        when(executionPort.findByWorkflowId(eq("wf-1"), any())).thenReturn(List.of());

        assertThat(list.execute(ActorId.ANONYMOUS, "wf-1", new PageQuery(20, 0))).isEmpty();
    }

    /**
     * O ator e obrigatorio nos tres casos de uso.
     *
     * <p>Uma revisao apontou que nenhum caso de uso conferia o ator. Enquanto ele so vira linha de
     * log, um nulo passa despercebido; no dia em que virar criterio de autorizacao, um nulo aceito e
     * uma verificacao que nao roda. A falha tem que ser agora, quando e barata.</p>
     */
    @Test
    void everyExecutionUseCaseRefusesANullActor() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> trigger.execute(null, "wf-1", Map.of()));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> get.execute(null, "exec-1"));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> list.execute(null, "wf-1", new PageQuery(20, 0)));
    }

    private static WorkflowDefinition definition(boolean enabled) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId("wf-1");
        definition.setName("workflow");
        definition.setEnabled(enabled);
        return definition;
    }
}
