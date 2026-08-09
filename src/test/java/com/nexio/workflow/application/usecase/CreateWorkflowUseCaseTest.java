package com.nexio.workflow.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.application.port.out.WorkflowSchedulePort;
import com.nexio.workflow.application.usecase.command.CreateWorkflowCommand;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Teste unitario do {@link CreateWorkflowUseCase} com a porta de saida dublada.
 *
 * <p>O que interessa aqui e o comportamento do caso de uso, nao o do banco: o que foi entregue a
 * porta e verificado com {@link ArgumentCaptor}, porque so olhar o retorno deixaria passar um
 * agregado montado errado que o duble devolvesse intacto.</p>
 */
@ExtendWith(MockitoExtension.class)
class CreateWorkflowUseCaseTest {

    private static final ActorId ACTOR = new ActorId("ator-do-teste");

    @Mock
    private WorkflowDefinitionPort port;

    @Mock
    private WorkflowSchedulePort schedulePort;

    @Captor
    private ArgumentCaptor<WorkflowDefinition> saved;

    private CreateWorkflowUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateWorkflowUseCase(port, schedulePort);
    }

    @Test
    void persistsTheDefinitionBuiltFromTheCommand() {
        when(port.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowDefinition result = useCase.execute(ACTOR, command());

        verify(port).save(saved.capture());
        WorkflowDefinition captured = saved.getValue();
        assertThat(captured.getName()).isEqualTo("cobranca diaria");
        assertThat(captured.getDescription()).isEqualTo("dispara a cobranca");
        assertThat(captured.isEnabled()).isTrue();
        assertThat(captured.getTriggerConfig()).isEqualTo(WorkflowFixtures.mockEventTrigger());
        assertThat(captured.getNodes()).hasSize(2);
        assertThat(captured.getStartNodeId()).isEqualTo("start");
        assertThat(result).isSameAs(captured);
    }

    /**
     * O identificador nasce no caso de uso e e diferente a cada criacao.
     */
    @Test
    void generatesTheIdentifierItself() {
        when(port.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String first = useCase.execute(ACTOR, command()).getId();
        String second = useCase.execute(ACTOR, command()).getId();

        assertThat(first).isNotBlank().isNotEqualTo(second);
        assertThat(UUID.fromString(first)).hasToString(first);
    }

    /**
     * O comando nao tem por onde receber um id: nao existe o componente. Sem isso, o chamador
     * escolheria qual documento a escrita mira.
     */
    @Test
    void doesNotAcceptAClientSuppliedIdentifier() {
        assertThat(CreateWorkflowCommand.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain("id");
    }

    /**
     * A falha do grafo chega ao chamador como excecao do dominio, com a mensagem original e sem
     * que nada tenha sido gravado; o callback de escrita nao precisa ser acionado para isso.
     */
    @Test
    void translatesGraphViolationIntoInvalidWorkflowException() {
        CreateWorkflowCommand cyclic = new CreateWorkflowCommand("ciclico", null, true,
                WorkflowFixtures.mockEventTrigger(), WorkflowFixtures.cyclicNodes(), null);

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> useCase.execute(ACTOR, cyclic))
                .withMessageContaining("ciclo")
                .withCauseInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(port);
    }

    /**
     * A recusa do setter do agregado (teto de tamanho) segue o mesmo caminho de traducao.
     */
    @Test
    void translatesFieldLimitViolationIntoInvalidWorkflowException() {
        String tooLong = "n".repeat(WorkflowDefinition.MAX_NAME_LENGTH + 1);
        CreateWorkflowCommand command = new CreateWorkflowCommand(tooLong, null, true,
                WorkflowFixtures.mockEventTrigger(), WorkflowFixtures.validNodes(), "start");

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> useCase.execute(ACTOR, command))
                .withMessageContaining("name");

        verifyNoInteractions(port);
    }

    /**
     * A politica estrita dos mapas livres tambem e chamada aqui, e nao so no callback de escrita.
     *
     * <p>A verificacao existia so dentro do {@code save()}, ou seja, fora do {@code try} que traduz
     * a falha: uma chave {@code $where} na config de um no atravessava este metodo sem nada
     * acontecer, para estourar la dentro da persistencia como {@code IllegalArgumentException} cru.
     * O chamador recebia erro interno para o que e erro de digitacao dele, e o servidor registrava
     * uma pilha inteira por requisicao. A asercao decisiva e a negativa: nada foi entregue a porta,
     * o que so vale se a recusa acontecer <b>antes</b> do save.</p>
     */
    @Test
    void translatesFreeFormConfigViolationIntoInvalidWorkflowExceptionBeforeSaving() {
        CreateWorkflowCommand command = new CreateWorkflowCommand("config hostil", null, true,
                WorkflowFixtures.mockEventTrigger(), WorkflowFixtures.nodesWithOperatorKeyInConfig(), "start");

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> useCase.execute(ACTOR, command))
                .withMessageContaining("'$'")
                .withCauseInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(port);
    }

    /**
     * Sem nos nao ha grafo: quem valida e o dominio, o caso de uso so traduz.
     */
    @Test
    void rejectsAnEmptyGraph() {
        CreateWorkflowCommand command = new CreateWorkflowCommand("vazio", null, false,
                WorkflowFixtures.mockEventTrigger(), List.of(), null);

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> useCase.execute(ACTOR, command))
                .withMessageContaining("ao menos um no");

        verifyNoInteractions(port);
    }

    private CreateWorkflowCommand command() {
        return new CreateWorkflowCommand("cobranca diaria", "dispara a cobranca", true,
                WorkflowFixtures.mockEventTrigger(), WorkflowFixtures.validNodes(), "start");
    }
}
