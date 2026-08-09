package com.nexio.workflow.application.usecase;

import com.nexio.workflow.application.engine.WorkflowEngine;
import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowExecution;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dispara um workflow e devolve a execucao ja terminada.
 *
 * <p>A execucao e sincrona: quem chama espera a caminhada inteira e recebe o resultado, com os
 * passos -- ver {@code docs/adr/0005-synchronous-execution.md}, inclusive para o que passa a ser
 * obrigatorio no dia em que o disparo virar assincrono.</p>
 *
 * <p><b>Workflow desligado nao dispara.</b> A flag {@code enabled} e o mecanismo de pausa do
 * produto, e ignora-la aqui a tornaria decorativa: quem desligou um workflow porque o servico do
 * outro lado esta com problema espera que desligar tenha efeito. A recusa e
 * {@link InvalidWorkflowException} e nao {@link WorkflowNotFoundException} de proposito -- o
 * workflow existe, e dizer "nao encontrado" mandaria quem chamou procurar um erro de
 * identificador que nao existe.</p>
 *
 * <p>Nenhuma execucao e criada quando a recusa acontece aqui. E deliberado: um registro de execucao
 * para um disparo que nunca comecou polui o historico com linhas que nao representam trabalho
 * nenhum, e o historico e justamente onde alguem vai procurar o que de fato rodou.</p>
 */
@Service
public class TriggerWorkflowUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(TriggerWorkflowUseCase.class);

    private final WorkflowDefinitionPort workflowDefinitionPort;
    private final WorkflowEngine engine;

    /**
     * Cria o caso de uso com injecao por construtor.
     *
     * @param workflowDefinitionPort porta de persistencia das definicoes
     * @param engine                 engine de execucao
     */
    public TriggerWorkflowUseCase(WorkflowDefinitionPort workflowDefinitionPort, WorkflowEngine engine) {
        this.workflowDefinitionPort = workflowDefinitionPort;
        this.engine = engine;
    }

    /**
     * Dispara o workflow com o payload informado.
     *
     * @param actor          autor da operacao, obtido da infraestrutura e nunca da entrada do cliente
     * @param workflowId     identificador da definicao a executar
     * @param triggerPayload payload do evento, pode ser nulo
     * @return execucao terminada, em SUCCESS ou FAILED, com os passos registrados
     * @throws WorkflowNotFoundException quando nao existe definicao com o identificador
     * @throws InvalidWorkflowException  quando o workflow esta desligado, ou quando o proprio
     *                                   payload viola a politica de escrita
     */
    public WorkflowExecution execute(ActorId actor, String workflowId, Map<String, Object> triggerPayload) {
        Objects.requireNonNull(actor, "actor nao pode ser nulo");
        Objects.requireNonNull(workflowId, "workflowId nao pode ser nulo");

        WorkflowDefinition definition = workflowDefinitionPort.findById(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowId));
        if (!definition.isEnabled()) {
            throw new InvalidWorkflowException(
                    "O workflow '" + workflowId + "' esta desativado e nao pode ser disparado");
        }

        WorkflowExecution execution = engine.execute(definition, triggerPayload);
        LOG.info("Workflow disparado: workflowId={} executionId={} status={} passos={} ator={}",
                workflowId, execution.getId(), execution.getStatus(), execution.getSteps().size(),
                actor.value());
        return execution;
    }
}
