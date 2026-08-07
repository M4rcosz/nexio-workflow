package com.nexio.workflow.application.usecase;

import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Caso de uso de consulta de uma definicao de workflow por identificador.
 *
 * <p>Lanca {@link WorkflowNotFoundException} em vez de devolver {@code Optional}: a porta ja
 * oferece a versao opcional para quem precisa decidir sobre a ausencia, e todo chamador deste caso
 * de uso pediu um workflow especifico -- para eles "nao existe" e falha, nao resultado. Devolver
 * {@code Optional} aqui so empurraria o mesmo {@code orElseThrow} para dentro de cada resolver.</p>
 */
@Service
public class GetWorkflowUseCase {

    private final WorkflowDefinitionPort workflowDefinitionPort;

    /**
     * Cria o caso de uso com injecao por construtor.
     *
     * @param workflowDefinitionPort porta de persistencia das definicoes
     */
    public GetWorkflowUseCase(WorkflowDefinitionPort workflowDefinitionPort) {
        this.workflowDefinitionPort = workflowDefinitionPort;
    }

    /**
     * Busca a definicao pelo identificador.
     *
     * @param actor autor da operacao, obtido da infraestrutura e nunca da entrada do cliente
     * @param id    identificador da definicao
     * @return a definicao encontrada
     * @throws WorkflowNotFoundException quando nao existe definicao com o identificador
     */
    public WorkflowDefinition execute(ActorId actor, String id) {
        Objects.requireNonNull(actor, "actor nao pode ser nulo");
        Objects.requireNonNull(id, "id nao pode ser nulo");
        return workflowDefinitionPort.findById(id)
                .orElseThrow(() -> new WorkflowNotFoundException(id));
    }
}
