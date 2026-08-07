package com.nexio.workflow.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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
 * <p>O ponto delicado e a conciliacao entre as duas consultas da porta: {@code findAll} e paginada
 * e {@code findEnabled} nao e. O que precisa valer e que o recorte seja respeitado nos dois
 * caminhos, para que a flag {@code enabledOnly} nunca transforme uma consulta paginada em uma
 * listagem sem teto.</p>
 */
@ExtendWith(MockitoExtension.class)
class ListWorkflowsUseCaseTest {

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

        assertThat(useCase.execute(requested, false)).isEqualTo(expected);

        verify(port).findAll(page.capture());
        assertThat(page.getValue().limit()).isEqualTo(5);
        assertThat(page.getValue().offset()).isEqualTo(10);
        verify(port, never()).findEnabled();
        verifyNoMoreInteractions(port);
    }

    /**
     * Com a flag, a consulta e a de habilitadas -- que a porta expoe sem paginacao de proposito,
     * para o agendador -- e o recorte e aplicado aqui, para que o metodo nunca devolva mais do que
     * o {@code limit} pedido.
     */
    @Test
    void appliesThePageQueryInMemoryWhenListingOnlyEnabled() {
        when(port.findEnabled()).thenReturn(definitions(10));

        List<WorkflowDefinition> result = useCase.execute(new PageQuery(3, 4), true);

        assertThat(result).extracting(WorkflowDefinition::getId).containsExactly("wf-4", "wf-5", "wf-6");
        verify(port).findEnabled();
        verify(port, never()).findAll(any());
        verifyNoMoreInteractions(port);
    }

    @Test
    void returnsTheLastPartialPageWhenOnlyEnabled() {
        when(port.findEnabled()).thenReturn(definitions(5));

        List<WorkflowDefinition> result = useCase.execute(new PageQuery(4, 3), true);

        assertThat(result).extracting(WorkflowDefinition::getId).containsExactly("wf-3", "wf-4");
    }

    @Test
    void returnsEmptyWhenTheOffsetIsPastTheEnabledResults() {
        when(port.findEnabled()).thenReturn(definitions(2));

        assertThat(useCase.execute(new PageQuery(10, 50), true)).isEmpty();
    }

    /**
     * O recorte nunca compartilha estado com o que a porta devolveu: mexer na lista de origem depois
     * nao pode alterar o resultado ja entregue ao chamador.
     */
    @Test
    void doesNotShareTheListReturnedByThePort() {
        List<WorkflowDefinition> mutable = new ArrayList<>(definitions(3));
        when(port.findEnabled()).thenReturn(mutable);

        List<WorkflowDefinition> result = useCase.execute(new PageQuery(3, 0), true);
        mutable.clear();

        assertThat(result).hasSize(3);
    }

    private List<WorkflowDefinition> definitions(int count) {
        List<WorkflowDefinition> definitions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            definitions.add(WorkflowFixtures.storedDefinition("wf-" + i));
        }
        return List.copyOf(definitions);
    }
}
