package com.nexio.workflow.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Teste unitario do {@link GetWorkflowUseCase} com a porta de saida dublada.
 */
@ExtendWith(MockitoExtension.class)
class GetWorkflowUseCaseTest {

    private static final String ID = "wf-1";

    private static final ActorId ACTOR = new ActorId("ator-do-teste");

    @Mock
    private WorkflowDefinitionPort port;

    private GetWorkflowUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetWorkflowUseCase(port);
    }

    @Test
    void returnsTheDefinitionFoundByThePort() {
        WorkflowDefinition stored = WorkflowFixtures.storedDefinition(ID);
        when(port.findById(ID)).thenReturn(Optional.of(stored));

        assertThat(useCase.execute(ACTOR, ID)).isSameAs(stored);

        verify(port).findById(ID);
        verifyNoMoreInteractions(port);
    }

    /**
     * A ausencia vira excecao do dominio carregando o id procurado, para que a camada de API possa
     * mapea-la sem raspar a mensagem.
     */
    @Test
    void throwsWhenTheDefinitionDoesNotExist() {
        when(port.findById(ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(WorkflowNotFoundException.class)
                .isThrownBy(() -> useCase.execute(ACTOR, ID))
                .matches(e -> ID.equals(e.workflowId()))
                .withMessageContaining(ID);
    }
}
