package com.nexio.workflow.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Teste unitario do {@link ListWorkflowsUseCase} com a porta de saida dublada.
 *
 * <p>O que estes testes fixam e <b>qual</b> metodo da porta cada caminho chama. A porta expoe duas
 * consultas de habilitadas com contratos diferentes: uma paginada e uma sem recorte, esta ultima
 * existente so para o agendador, que precisa de todas as definicoes para registrar os cron. Chamar a
 * sem recorte para servir uma consulta de usuario faz o {@code limit} nao reduzir trabalho nenhum --
 * e como uma unica consulta GraphQL pode repetir o mesmo campo dezenas de vezes por apelido, cada
 * repeticao vira mais uma carga completa da colecao. E um defeito que nenhuma asercao sobre o
 * resultado enxerga: o recorte em memoria devolve exatamente a mesma lista.</p>
 */
@ExtendWith(MockitoExtension.class)
class ListWorkflowsUseCaseTest {

    private static final ActorId ACTOR = new ActorId("ator-do-teste");

    @Mock
    private WorkflowDefinitionPort port;

    @Captor
    private ArgumentCaptor<PageQuery> page;

    private ListWorkflowsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ListWorkflowsUseCase(port);
    }

    /**
     * Sem a flag, o recorte vai inteiro para a porta: quem pagina e o banco.
     */
    @Test
    void delegatesThePageQueryToThePortWhenListingEverything() {
        PageQuery requested = new PageQuery(5, 10);
        List<WorkflowDefinition> expected = definitions(3);
        when(port.findAll(requested)).thenReturn(expected);

        assertThat(useCase.execute(ACTOR, requested, false)).isEqualTo(expected);

        verify(port).findAll(page.capture());
        assertThat(page.getValue().limit()).isEqualTo(5);
        assertThat(page.getValue().offset()).isEqualTo(10);
        verify(port, never()).findEnabled();
        verify(port, never()).findEnabled(any());
        verifyNoMoreInteractions(port);
    }

    /**
     * Com a flag, o recorte tambem vai inteiro para a porta, pela consulta paginada de habilitadas.
     * A afirmacao que importa e a negativa: {@code findEnabled()} sem recorte -- o metodo do
     * agendador -- nao pode ser tocado por um caminho que serve requisicao de usuario.
     */
    @Test
    void delegatesThePageQueryToThePaginatedPortQueryWhenListingOnlyEnabled() {
        PageQuery requested = new PageQuery(3, 4);
        List<WorkflowDefinition> expected = definitions(3);
        when(port.findEnabled(requested)).thenReturn(expected);

        assertThat(useCase.execute(ACTOR, requested, true)).isEqualTo(expected);

        verify(port).findEnabled(page.capture());
        assertThat(page.getValue().limit()).isEqualTo(3);
        assertThat(page.getValue().offset()).isEqualTo(4);
        verify(port, never()).findEnabled();
        verify(port, never()).findAll(any());
        verifyNoMoreInteractions(port);
    }

    /**
     * O caso de uso nao mexe no que a porta devolveu: quem recorta e o banco, e recortar de novo
     * aqui esconderia uma porta que ignorasse o {@code limit}.
     */
    @Test
    void returnsWhatThePortReturnedWithoutSlicingItAgain() {
        List<WorkflowDefinition> fromPort = definitions(10);
        when(port.findEnabled(any())).thenReturn(fromPort);

        assertThat(useCase.execute(ACTOR, new PageQuery(3, 0), true)).isEqualTo(fromPort);
    }

    @Test
    void returnsEmptyWhenThePortHasNothingInThePage() {
        when(port.findEnabled(any())).thenReturn(List.of());

        assertThat(useCase.execute(ACTOR, new PageQuery(10, 50), true)).isEmpty();
    }

    private List<WorkflowDefinition> definitions(int count) {
        List<WorkflowDefinition> definitions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            definitions.add(WorkflowFixtures.storedDefinition("wf-" + i));
        }
        return List.copyOf(definitions);
    }
}
