package com.nexio.workflow.application.usecase;

import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Caso de uso de remocao de uma definicao de workflow.
 *
 * <p>Usa o retorno booleano de {@code deleteById} para decidir sobre a ausencia, em vez do
 * {@code existsById} seguido de {@code deleteById} que o desenho original previa. Aquele par e uma
 * corrida entre verificar e agir: entre as duas idas ao banco outra requisicao pode remover o mesmo
 * documento, e o resultado e um sucesso relatado para uma remocao que nao removeu nada -- ou, na
 * ordem inversa, uma excecao de "nao encontrado" para uma definicao que existia quando o pedido
 * chegou. Uma unica operacao, que ja informa quantos documentos saiu, responde as duas coisas de
 * uma vez e ainda economiza uma ida ao banco.</p>
 *
 * <p><b>Sem {@code @Transactional}.</b> Transacao no MongoDB exige replica set e os ambientes de
 * desenvolvimento e teste rodam um no unico, entao a anotacao so quebraria em tempo de execucao.
 * Ela tambem nao teria o que proteger: e uma unica operacao sobre um unico documento.</p>
 */
@Service
public class DeleteWorkflowUseCase {

    private final WorkflowDefinitionPort workflowDefinitionPort;

    /**
     * Cria o caso de uso com injecao por construtor.
     *
     * @param workflowDefinitionPort porta de persistencia das definicoes
     */
    public DeleteWorkflowUseCase(WorkflowDefinitionPort workflowDefinitionPort) {
        this.workflowDefinitionPort = workflowDefinitionPort;
    }

    /**
     * Remove a definicao pelo identificador.
     *
     * @param id identificador da definicao
     * @throws WorkflowNotFoundException quando nao existia definicao com o identificador
     */
    public void execute(String id) {
        Objects.requireNonNull(id, "id nao pode ser nulo");
        if (!workflowDefinitionPort.deleteById(id)) {
            throw new WorkflowNotFoundException(id);
        }
    }
}
