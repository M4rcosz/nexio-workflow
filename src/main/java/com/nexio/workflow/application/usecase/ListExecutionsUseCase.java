package com.nexio.workflow.application.usecase;

import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.WorkflowExecution;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Lista as execucoes de uma definicao, da mais recente para a mais antiga.
 *
 * <p>Confere que a definicao existe antes de listar. Sem isso, um identificador inventado devolveria
 * uma lista vazia -- indistinguivel de um workflow real que nunca foi disparado --, e as duas
 * situacoes pedem reacoes opostas de quem chamou: corrigir o identificador numa, esperar na
 * outra.</p>
 *
 * <p>A ordem e total, e nao apenas por {@code createdAt}: o desempate por identificador esta no
 * contrato da porta porque {@code createdAt} nao e unico e a paginacao por deslocamento sobre uma
 * ordem com empate pode repetir uma execucao numa pagina e pular outra na seguinte.</p>
 */
@Service
public class ListExecutionsUseCase {

    private final WorkflowExecutionPort workflowExecutionPort;
    private final WorkflowDefinitionPort workflowDefinitionPort;

    /**
     * Cria o caso de uso com injecao por construtor.
     *
     * @param workflowExecutionPort  porta de persistencia das execucoes
     * @param workflowDefinitionPort porta de persistencia das definicoes
     */
    public ListExecutionsUseCase(WorkflowExecutionPort workflowExecutionPort,
                                 WorkflowDefinitionPort workflowDefinitionPort) {
        this.workflowExecutionPort = workflowExecutionPort;
        this.workflowDefinitionPort = workflowDefinitionPort;
    }

    /**
     * Lista o recorte pedido das execucoes de uma definicao.
     *
     * @param actor      autor da operacao, obtido da infraestrutura e nunca da entrada do cliente
     * @param workflowId identificador da definicao
     * @param page       recorte de paginacao, nunca nulo
     * @return execucoes do recorte, vazia quando o workflow existe e nao foi disparado
     * @throws WorkflowNotFoundException quando nao existe definicao com o identificador
     */
    public List<WorkflowExecution> execute(ActorId actor, String workflowId, PageQuery page) {
        Objects.requireNonNull(actor, "actor nao pode ser nulo");
        Objects.requireNonNull(workflowId, "workflowId nao pode ser nulo");
        Objects.requireNonNull(page, "page nao pode ser nulo");

        if (!workflowDefinitionPort.existsById(workflowId)) {
            throw new WorkflowNotFoundException(workflowId);
        }
        return workflowExecutionPort.findByWorkflowId(workflowId, page);
    }
}
