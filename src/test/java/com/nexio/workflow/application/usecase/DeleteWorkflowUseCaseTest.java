package com.nexio.workflow.application.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Teste unitario do {@link DeleteWorkflowUseCase} com as portas de saida dubladas.
 */
@ExtendWith(MockitoExtension.class)
class DeleteWorkflowUseCaseTest {

    private static final String ID = "wf-1";

    @Mock
    private WorkflowDefinitionPort definitionPort;

    @Mock
    private WorkflowExecutionPort executionPort;

    private DeleteWorkflowUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new DeleteWorkflowUseCase(definitionPort, executionPort);
    }

    /**
     * Uma unica ida ao banco por colecao: a remocao da definicao ja informa se havia o que remover,
     * entao o {@code existsById} previo -- que seria uma corrida entre verificar e agir -- nao
     * acontece.
     */
    @Test
    void deletesWithoutCheckingExistenceFirst() {
        when(definitionPort.deleteById(ID)).thenReturn(true);

        assertThatCode(() -> useCase.execute(ID)).doesNotThrowAnyException();

        verify(definitionPort).deleteById(ID);
        verify(definitionPort, never()).existsById(ID);
        verifyNoMoreInteractions(definitionPort);
    }

    /**
     * A cascata, e a ordem dela. Sem a segunda chamada o historico fica apontando para um workflow
     * que nao existe mais, sem nada no sistema capaz de encontrar esses documentos. A ordem importa
     * porque nao ha transacao: apagando a definicao primeiro, uma falha adiante deixa execucoes
     * inertes; na ordem inversa, deixaria um workflow vivo sem historico.
     */
    @Test
    void removesTheExecutionsAfterTheDefinition() {
        when(definitionPort.deleteById(ID)).thenReturn(true);
        when(executionPort.deleteByWorkflowId(ID)).thenReturn(3L);

        useCase.execute(ID);

        InOrder order = inOrder(definitionPort, executionPort);
        order.verify(definitionPort).deleteById(ID);
        order.verify(executionPort).deleteByWorkflowId(ID);
        order.verifyNoMoreInteractions();
    }

    /**
     * A falha da cascata nao derruba a operacao: a definicao ja saiu, e devolver erro para um pedido
     * cujo efeito principal aconteceu levaria o cliente a repetir a remocao e receber
     * {@code NOT_FOUND}. O que sobra do problema e a linha de WARN.
     */
    @Test
    void reportsSuccessEvenWhenTheCascadeFails() {
        when(definitionPort.deleteById(ID)).thenReturn(true);
        when(executionPort.deleteByWorkflowId(ID)).thenThrow(new IllegalStateException("banco fora"));

        assertThatCode(() -> useCase.execute(ID)).doesNotThrowAnyException();

        verify(executionPort).deleteByWorkflowId(ID);
    }

    /**
     * Nada existia para remover: as execucoes tambem nao sao tocadas. Chamar a cascata aqui apagaria
     * o historico de um id que o chamador digitou errado.
     */
    @Test
    void throwsWhenThePortReportsNothingWasRemovedAndSparesTheExecutions() {
        when(definitionPort.deleteById(ID)).thenReturn(false);

        assertThatExceptionOfType(WorkflowNotFoundException.class)
                .isThrownBy(() -> useCase.execute(ID))
                .matches(e -> ID.equals(e.workflowId()));

        verify(definitionPort).deleteById(ID);
        verifyNoMoreInteractions(definitionPort);
        verifyNoInteractions(executionPort);
    }
}
