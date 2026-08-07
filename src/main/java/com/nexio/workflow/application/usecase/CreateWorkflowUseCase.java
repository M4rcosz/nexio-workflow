package com.nexio.workflow.application.usecase;

import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.application.usecase.command.CreateWorkflowCommand;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Caso de uso de criacao de uma definicao de workflow.
 *
 * <p>Depende so da porta de saida: nenhuma referencia a repositorio, a MongoDB ou ao protocolo de
 * entrada aparece aqui. A injecao e por construtor e sem {@code @Autowired} -- com um unico
 * construtor o Spring ja o usa, e o campo final deixa a dependencia obrigatoria e imutavel.</p>
 *
 * <p><b>A validacao das invariantes e do dominio, nao daqui.</b> O agregado ja recusa nome,
 * descricao e {@code startNodeId} acima do teto nos proprios setters, e
 * {@link WorkflowDefinition#validateGraph()} ja cobre id de no duplicado ou fora do formato, aresta
 * apontando para no inexistente, regra dos nos CONDITION, ciclo, alcancabilidade e o limite de nos.
 * Repetir qualquer uma dessas checagens aqui criaria duas fontes da mesma regra, que divergem no
 * primeiro ajuste.</p>
 *
 * <p>O que este caso de uso faz e <b>chamar</b> a validacao do grafo e a dos mapas livres, e
 * traduzir a falha. A chamada explicita existe pela fronteira: sem ela a unica coisa que roda essas
 * validacoes e o callback de escrita do MongoDB, e o chamador receberia um
 * {@code IllegalArgumentException} cru, vindo de dentro da persistencia, embrulhado no que a
 * infraestrutura resolvesse embrulhar. O callback continua existindo, mas como rede de seguranca de
 * todo save -- inclusive de um caso de uso futuro que esqueca de validar --, e nao como a
 * verificacao principal.</p>
 *
 * <p>{@code validateConfigs()} entrou nessa lista depois: a chamada faltava, e o argumento da
 * fronteira valia inteiro para ela. Uma chave {@code $where} na config de um no passava por este
 * metodo sem nada acontecer, so para estourar dentro de {@code save()} -- fora deste
 * {@code try} -- e virar erro interno com pilha no log, para o que e entrada invalida do usuario.</p>
 */
@Service
public class CreateWorkflowUseCase {

    private final WorkflowDefinitionPort workflowDefinitionPort;

    /**
     * Cria o caso de uso com injecao por construtor.
     *
     * @param workflowDefinitionPort porta de persistencia das definicoes
     */
    public CreateWorkflowUseCase(WorkflowDefinitionPort workflowDefinitionPort) {
        this.workflowDefinitionPort = workflowDefinitionPort;
    }

    /**
     * Cria e persiste uma nova definicao de workflow.
     *
     * <p>O identificador e gerado aqui, sempre: o comando nem sequer tem campo para ele. Um id
     * escolhido pelo cliente seria a escolha de qual documento a escrita mira, e o certo e que essa
     * decisao nunca saia do servidor.</p>
     *
     * @param command dados da definicao a criar
     * @return a definicao persistida, com id, versao e {@code createdAt} preenchidos
     * @throws InvalidWorkflowException quando alguma invariante do dominio e violada
     */
    public WorkflowDefinition execute(CreateWorkflowCommand command) {
        Objects.requireNonNull(command, "command nao pode ser nulo");
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(UUID.randomUUID().toString());
        try {
            definition.setName(command.name());
            definition.setDescription(command.description());
            definition.setEnabled(command.enabled());
            definition.setTriggerConfig(command.triggerConfig());
            definition.setNodes(command.nodes());
            definition.setStartNodeId(command.startNodeId());
            definition.validateGraph();
            definition.validateConfigs();
        } catch (IllegalArgumentException e) {
            throw new InvalidWorkflowException(e.getMessage(), e);
        }
        return workflowDefinitionPort.save(definition);
    }
}
