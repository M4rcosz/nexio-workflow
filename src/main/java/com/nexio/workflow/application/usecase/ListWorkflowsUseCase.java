package com.nexio.workflow.application.usecase;

import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Caso de uso de listagem das definicoes de workflow.
 *
 * <p>As duas consultas da porta tem contratos diferentes de proposito: {@code findAll(PageQuery)} e
 * paginada e {@code findEnabled()} nao e, porque o agendador precisa de <i>todas</i> as habilitadas
 * para registrar os cron -- uma pagina qualquer ali significaria workflow silenciosamente nunca
 * disparado. Este caso de uso serve consulta de usuario, onde a regra e a oposta: um metodo que
 * recebe {@link PageQuery} nao pode devolver lista sem teto dependendo de uma flag.</p>
 *
 * <p>A conciliacao escolhida foi <b>aplicar o recorte em memoria</b> sobre o resultado de
 * {@code findEnabled()}, e nao mudar a porta nem devolver a lista inteira. O motivo e o custo: a
 * colecao de definicoes nao cresce por disparo, ela e limitada ao numero de workflows que alguem
 * cadastrou, e cada documento e limitado pelo teto de {@code MAX_NODES}. Trazer essa colecao e
 * cortar aqui e barato, e evita duplicar a consulta na porta so para diferenciar quem pagina de
 * quem nao pagina. Se um dia esse volume deixar de ser desprezivel, a correcao e uma consulta
 * paginada por {@code enabled} na porta, e a assinatura publica daqui nao muda.</p>
 *
 * <p>Nenhum dos dois caminhos garante ordem estavel: {@code findAll} tambem vai ao banco sem
 * ordenacao, entao paginar em memoria nao piora o que ja valia. Ordenacao explicita e assunto de
 * quando a API expuser criterio de ordem.</p>
 */
@Service
public class ListWorkflowsUseCase {

    private final WorkflowDefinitionPort workflowDefinitionPort;

    /**
     * Cria o caso de uso com injecao por construtor.
     *
     * @param workflowDefinitionPort porta de persistencia das definicoes
     */
    public ListWorkflowsUseCase(WorkflowDefinitionPort workflowDefinitionPort) {
        this.workflowDefinitionPort = workflowDefinitionPort;
    }

    /**
     * Lista as definicoes dentro do recorte informado.
     *
     * @param page        recorte de paginacao, nunca nulo
     * @param enabledOnly {@code true} para restringir as definicoes habilitadas
     * @return lista de definicoes, sempre dentro do limite do recorte
     */
    public List<WorkflowDefinition> execute(PageQuery page, boolean enabledOnly) {
        Objects.requireNonNull(page, "page nao pode ser nulo");
        if (!enabledOnly) {
            return workflowDefinitionPort.findAll(page);
        }
        return slice(workflowDefinitionPort.findEnabled(), page);
    }

    private List<WorkflowDefinition> slice(List<WorkflowDefinition> all, PageQuery page) {
        if (page.offset() >= all.size()) {
            return List.of();
        }
        int end = (int) Math.min((long) page.offset() + page.limit(), all.size());
        return List.copyOf(all.subList(page.offset(), end));
    }
}
