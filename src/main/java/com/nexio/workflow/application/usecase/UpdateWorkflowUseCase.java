package com.nexio.workflow.application.usecase;

import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.application.usecase.command.UpdateWorkflowCommand;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Caso de uso de atualizacao parcial de uma definicao de workflow.
 *
 * <p>Carrega o agregado, aplica so os campos enviados e grava o mesmo objeto. <b>Nao</b> monta um
 * {@link WorkflowDefinition} novo com o mesmo id: um objeto recem-construido tem {@code createdAt}
 * nulo e {@code version} nula, e gravar isso apagaria a data de criacao e faria o Spring Data
 * tratar o documento como novo, inserindo por cima e jogando fora o bloqueio otimista -- duas
 * atualizacoes concorrentes passariam as duas, e a ultima venceria em silencio.</p>
 *
 * <p>Como todas as invariantes ja moram no agregado, aqui so acontece a chamada de
 * {@link WorkflowDefinition#validateGraph()} e a traducao da falha; ver
 * {@link CreateWorkflowUseCase} para o porque de a validacao ser chamada no caso de uso mesmo
 * existindo o callback de escrita.</p>
 *
 * <p>A validacao roda depois de todas as mutacoes, e nao a cada campo, de proposito: trocar
 * {@code nodes} e {@code startNodeId} na mesma atualizacao passa por um estado intermediario
 * invalido, e validar no meio recusaria uma alteracao perfeitamente valida.</p>
 *
 * <p><b>Sem {@code @Transactional}.</b> Seria a anotacao obvia neste ponto, mas transacao no
 * MongoDB exige replica set e o ambiente de desenvolvimento e de teste roda um no unico: a
 * anotacao nao daria atomicidade nenhuma, so faria o metodo estourar em tempo de execucao. Ela
 * tambem nao seria necessaria: a leitura nao muda nada e a escrita e um unico documento, ja atomica
 * no MongoDB. Quem protege a concorrencia aqui e o {@code @Version} do agregado.</p>
 */
@Service
public class UpdateWorkflowUseCase {

    private final WorkflowDefinitionPort workflowDefinitionPort;

    /**
     * Cria o caso de uso com injecao por construtor.
     *
     * @param workflowDefinitionPort porta de persistencia das definicoes
     */
    public UpdateWorkflowUseCase(WorkflowDefinitionPort workflowDefinitionPort) {
        this.workflowDefinitionPort = workflowDefinitionPort;
    }

    /**
     * Aplica uma atualizacao parcial sobre a definicao existente.
     *
     * @param id      identificador da definicao a atualizar
     * @param command campos a alterar; o que nao foi enviado permanece como esta
     * @return a definicao persistida, com {@code updatedAt} e versao renovados
     * @throws WorkflowNotFoundException quando nao existe definicao com o identificador
     * @throws InvalidWorkflowException  quando o resultado da atualizacao viola alguma invariante
     */
    public WorkflowDefinition execute(String id, UpdateWorkflowCommand command) {
        Objects.requireNonNull(id, "id nao pode ser nulo");
        Objects.requireNonNull(command, "command nao pode ser nulo");
        WorkflowDefinition definition = workflowDefinitionPort.findById(id)
                .orElseThrow(() -> new WorkflowNotFoundException(id));
        try {
            command.name().ifPresent(definition::setName);
            command.description().ifPresent(definition::setDescription);
            command.enabled().ifPresent(definition::setEnabled);
            command.triggerConfig().ifPresent(definition::setTriggerConfig);
            command.nodes().ifPresent(definition::setNodes);
            command.startNodeId().ifPresent(definition::setStartNodeId);
            definition.validateGraph();
        } catch (IllegalArgumentException e) {
            throw new InvalidWorkflowException(e.getMessage(), e);
        }
        return workflowDefinitionPort.save(definition);
    }
}
