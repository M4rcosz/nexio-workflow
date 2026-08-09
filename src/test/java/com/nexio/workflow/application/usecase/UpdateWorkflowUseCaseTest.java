package com.nexio.workflow.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.application.port.out.WorkflowSchedulePort;
import com.nexio.workflow.application.usecase.command.UpdateWorkflowCommand;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Teste unitario do {@link UpdateWorkflowUseCase} com a porta de saida dublada.
 */
@ExtendWith(MockitoExtension.class)
class UpdateWorkflowUseCaseTest {

    private static final String ID = "wf-1";

    private static final ActorId ACTOR = new ActorId("ator-do-teste");

    @Mock
    private WorkflowDefinitionPort port;

    @Mock
    private WorkflowSchedulePort schedulePort;

    @Captor
    private ArgumentCaptor<WorkflowDefinition> saved;

    private UpdateWorkflowUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new UpdateWorkflowUseCase(port, schedulePort);
    }

    @Test
    void appliesOnlyTheFieldsThatWereSent() {
        WorkflowDefinition stored = WorkflowFixtures.storedDefinition(ID);
        when(port.findById(ID)).thenReturn(Optional.of(stored));
        when(port.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        useCase.execute(ACTOR, ID, UpdateWorkflowCommand.builder().name("novo nome").enabled(false).build());

        verify(port).save(saved.capture());
        WorkflowDefinition captured = saved.getValue();
        assertThat(captured.getName()).isEqualTo("novo nome");
        assertThat(captured.isEnabled()).isFalse();
        assertThat(captured.getDescription()).isEqualTo("dispara a cobranca");
        assertThat(captured.getTriggerConfig()).isEqualTo(WorkflowFixtures.mockEventTrigger());
        assertThat(captured.getNodes()).hasSize(2);
    }

    /**
     * O campo enviado com valor nulo e limpo de verdade -- e o que separa "nao enviou" de "mandou
     * apagar", e o motivo de o comando usar {@code Patch} em vez de nulo.
     */
    @Test
    void clearsAFieldSentAsNull() {
        WorkflowDefinition stored = WorkflowFixtures.storedDefinition(ID);
        when(port.findById(ID)).thenReturn(Optional.of(stored));
        when(port.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        useCase.execute(ACTOR, ID, UpdateWorkflowCommand.builder().description(null).build());

        verify(port).save(saved.capture());
        assertThat(saved.getValue().getDescription()).isNull();
    }

    /**
     * A atualizacao muta o agregado carregado. Montar um {@link WorkflowDefinition} novo com o mesmo
     * id apagaria {@code createdAt} e zeraria a versao, e o Spring Data reinseriria o documento por
     * cima, sem bloqueio otimista nenhum.
     */
    @Test
    void mutatesTheLoadedAggregateInsteadOfReplacingIt() {
        WorkflowDefinition stored = WorkflowFixtures.storedDefinition(ID);
        Instant createdAt = stored.getCreatedAt();
        when(port.findById(ID)).thenReturn(Optional.of(stored));
        when(port.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        useCase.execute(ACTOR, ID, UpdateWorkflowCommand.builder().name("outro").build());

        verify(port).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(stored);
        assertThat(saved.getValue().getId()).isEqualTo(ID);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(createdAt);
    }

    /**
     * Trocar o grafo junto com o no inicial precisa passar: a validacao roda uma vez, ao fim de
     * todas as mutacoes, e nao a cada campo.
     */
    @Test
    void allowsReplacingNodesAndStartNodeTogether() {
        WorkflowDefinition stored = WorkflowFixtures.storedDefinition(ID);
        when(port.findById(ID)).thenReturn(Optional.of(stored));
        when(port.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        useCase.execute(ACTOR, ID, UpdateWorkflowCommand.builder()
                .nodes(WorkflowFixtures.otherValidNodes())
                .startNodeId("inicio")
                .build());

        verify(port).save(saved.capture());
        assertThat(saved.getValue().getStartNodeId()).isEqualTo("inicio");
        assertThat(saved.getValue().getNodes()).extracting("nodeId").containsExactly("inicio", "fim");
    }

    @Test
    void throwsWhenTheDefinitionDoesNotExist() {
        when(port.findById(ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(WorkflowNotFoundException.class)
                .isThrownBy(() -> useCase.execute(ACTOR, ID, UpdateWorkflowCommand.builder().name("x").build()))
                .matches(e -> ID.equals(e.workflowId()));

        verify(port, never()).save(any());
    }

    @Test
    void translatesGraphViolationIntoInvalidWorkflowException() {
        when(port.findById(ID)).thenReturn(Optional.of(WorkflowFixtures.storedDefinition(ID)));

        UpdateWorkflowCommand command = UpdateWorkflowCommand.builder()
                .nodes(WorkflowFixtures.cyclicNodes())
                .startNodeId(null)
                .build();

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> useCase.execute(ACTOR, ID, command))
                .withMessageContaining("ciclo")
                .withCauseInstanceOf(IllegalArgumentException.class);

        verify(port, never()).save(any());
    }

    /**
     * A politica estrita dos mapas livres tambem e chamada aqui, antes do save. Sem esta chamada a
     * verificacao so acontecia dentro de {@code save()}, fora do {@code try} que traduz a falha, e
     * uma chave {@code $where} enviada na config de um no voltava ao cliente como erro interno.
     */
    @Test
    void translatesFreeFormConfigViolationIntoInvalidWorkflowExceptionBeforeSaving() {
        when(port.findById(ID)).thenReturn(Optional.of(WorkflowFixtures.storedDefinition(ID)));

        UpdateWorkflowCommand command = UpdateWorkflowCommand.builder()
                .nodes(WorkflowFixtures.nodesWithOperatorKeyInConfig())
                .build();

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> useCase.execute(ACTOR, ID, command))
                .withMessageContaining("'$'")
                .withCauseInstanceOf(IllegalArgumentException.class);

        verify(port, never()).save(any());
    }

    @Test
    void translatesFieldLimitViolationIntoInvalidWorkflowException() {
        when(port.findById(ID)).thenReturn(Optional.of(WorkflowFixtures.storedDefinition(ID)));
        String tooLong = "d".repeat(WorkflowDefinition.MAX_DESCRIPTION_LENGTH + 1);

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> useCase.execute(ACTOR, ID, UpdateWorkflowCommand.builder()
                        .description(tooLong)
                        .build()))
                .withMessageContaining("description");

        verify(port, never()).save(any());
    }
}
