package com.nexio.workflow.application.usecase.command;

import com.nexio.workflow.domain.model.TriggerConfig;
import com.nexio.workflow.domain.model.WorkflowNode;
import java.util.List;

/**
 * Dados de entrada da criacao de um workflow.
 *
 * <p>E um tipo da camada de aplicacao, e nao o DTO da API: a aplicacao nao pode importar nada de
 * {@code api/}, senao trocar o protocolo (ou expor um segundo) passaria a mexer no caso de uso. Ele
 * tambem nao e uma lista solta de parametros, que ja seriam seis e todos {@code String} vizinhas de
 * {@code String} -- trocar dois de lugar compilaria sem reclamacao alguma.</p>
 *
 * <p><b>Nao existe componente {@code id} de proposito.</b> O identificador e gerado pelo caso de
 * uso. Aceitar um id do cliente na criacao deixaria o chamador escolher qual documento ele esta
 * tentando escrever: o {@code @Version} do agregado faz o save de um id ja existente falhar com
 * {@code DuplicateKeyException} em vez de sobrescrever, mas o certo e o campo nao chegar ate aqui,
 * e nao depender do bloqueio otimista como ultima linha de defesa.</p>
 *
 * @param name          nome do workflow
 * @param description   descricao do workflow, pode ser nula
 * @param enabled       se o workflow ja nasce habilitado
 * @param triggerConfig configuracao do gatilho
 * @param nodes         nos do grafo; copiado na construcao, nunca nulo depois dela
 * @param startNodeId   no inicial declarado, pode ser nulo para que o grafo o resolva sozinho
 */
public record CreateWorkflowCommand(
        String name,
        String description,
        boolean enabled,
        TriggerConfig triggerConfig,
        List<WorkflowNode> nodes,
        String startNodeId
) {

    public CreateWorkflowCommand {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
