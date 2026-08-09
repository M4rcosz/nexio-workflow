package com.nexio.workflow.application.usecase;

import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.WorkflowExecution;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Consulta de uma execucao por identificador.
 *
 * <p>Lanca {@link WorkflowNotFoundException} em vez de devolver {@code Optional}, pelo mesmo motivo
 * do {@link GetWorkflowUseCase}: quem chama pediu uma execucao especifica, e para ele "nao existe"
 * e falha, nao resultado.</p>
 *
 * <p><b>Nao ha verificacao de dono, porque nao ha dono.</b> Enquanto {@code WorkflowExecution} nao
 * tiver {@code ownerId} e nao houver autenticacao, qualquer um que saiba o identificador le a
 * execucao inteira -- inclusive o payload do gatilho e a saida de cada passo. O {@code actor} ja
 * atravessa a fronteira para que o dia da autorizacao seja uma mudanca dentro deste metodo, e nao
 * uma mudanca de assinatura em toda a cadeia; ver {@code docs/adr/0006-actor-propagation.md}.</p>
 */
@Service
public class GetExecutionUseCase {

    private final WorkflowExecutionPort workflowExecutionPort;

    /**
     * Cria o caso de uso com injecao por construtor.
     *
     * @param workflowExecutionPort porta de persistencia das execucoes
     */
    public GetExecutionUseCase(WorkflowExecutionPort workflowExecutionPort) {
        this.workflowExecutionPort = workflowExecutionPort;
    }

    /**
     * Busca a execucao pelo identificador.
     *
     * @param actor autor da operacao, obtido da infraestrutura e nunca da entrada do cliente
     * @param id    identificador da execucao
     * @return a execucao encontrada
     * @throws WorkflowNotFoundException quando nao existe execucao com o identificador
     */
    public WorkflowExecution execute(ActorId actor, String id) {
        Objects.requireNonNull(actor, "actor nao pode ser nulo");
        Objects.requireNonNull(id, "id nao pode ser nulo");
        return workflowExecutionPort.findById(id)
                .orElseThrow(() -> new WorkflowNotFoundException(id));
    }
}
