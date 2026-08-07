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
 * <p>Os dois caminhos vao paginados para o banco, e e isso que importa aqui. A porta ainda expoe
 * {@code findEnabled()} sem recorte, porque o agendador precisa de <i>todas</i> as habilitadas para
 * registrar os cron -- uma pagina qualquer ali significaria workflow silenciosamente nunca
 * disparado --, mas esse metodo nao serve requisicao de usuario e nao e chamado daqui.</p>
 *
 * <p>O recorte ja foi aplicado em memoria sobre {@code findEnabled()}, com o argumento de que a
 * colecao de definicoes nao cresce por disparo. O argumento estava errado no que importava: o custo
 * nao e o tamanho da colecao, e o fato de o {@code limit} nao reduzir trabalho nenhum. Uma unica
 * consulta GraphQL pode repetir o mesmo campo dezenas de vezes por apelido, e cada repeticao
 * disparava uma carga completa da colecao habilitada -- {@code limit: 1} custava o mesmo que
 * {@code limit: 100}. Com {@code findEnabled(PageQuery)} o recorte chega ao banco.</p>
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
        return enabledOnly
                ? workflowDefinitionPort.findEnabled(page)
                : workflowDefinitionPort.findAll(page);
    }
}
