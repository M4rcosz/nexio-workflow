package com.nexio.workflow.application.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Teste unitario do {@link DeleteWorkflowUseCase} com a porta de saida dublada.
 */
@ExtendWith(MockitoExtension.class)
class DeleteWorkflowUseCaseTest {

    private static final String ID = "wf-1";

    @Mock
    private WorkflowDefinitionPort port;

    private DeleteWorkflowUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new DeleteWorkflowUseCase(port);
    }

    /**
     * Uma unica ida ao banco: a remocao ja informa se havia o que remover, entao o
     * {@code existsById} previo -- que seria uma corrida entre verificar e agir -- nao acontece.
     */
    @Test
    void deletesInASingleRoundTripWithoutCheckingExistenceFirst() {
        when(port.deleteById(ID)).thenReturn(true);

        assertThatCode(() -> useCase.execute(ID)).doesNotThrowAnyException();

        verify(port).deleteById(ID);
        verify(port, never()).existsById(ID);
        verifyNoMoreInteractions(port);
    }

    @Test
    void throwsWhenThePortReportsNothingWasRemoved() {
        when(port.deleteById(ID)).thenReturn(false);

        assertThatExceptionOfType(WorkflowNotFoundException.class)
                .isThrownBy(() -> useCase.execute(ID))
                .matches(e -> ID.equals(e.workflowId()));

        verify(port).deleteById(ID);
        verifyNoMoreInteractions(port);
    }
}
