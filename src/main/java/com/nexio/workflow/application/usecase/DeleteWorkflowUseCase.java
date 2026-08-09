package com.nexio.workflow.application.usecase;

import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.application.port.out.WorkflowSchedulePort;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.TextSanitizer;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>A remocao cascateia para as execucoes.</b> {@code WorkflowExecutionPort.deleteByWorkflowId}
 * existe documentado como "evitando execucoes orfas quando a definicao e apagada" e nao tinha
 * chamador nenhum: apagar a definicao deixava para tras todo o historico dela, apontando para um
 * {@code workflowId} que nao existe mais, sem nada no sistema capaz de encontrar ou remover esses
 * documentos.</p>
 *
 * <p><b>Sem {@code @Transactional}, e a ordem e deliberada.</b> Transacao no MongoDB exige replica
 * set, e os ambientes de desenvolvimento e teste rodam um no unico: a anotacao nao daria
 * atomicidade, so quebraria em tempo de execucao. O que resta e escolher qual metade fica exposta se
 * a outra falhar, e a definicao sai primeiro: com a definicao ja removida, uma falha na segunda
 * operacao deixa execucoes orfas -- documentos inertes, que ninguem consulta porque a consulta parte
 * do workflow. Na ordem inversa, uma falha ao remover a definicao apagaria o historico de um
 * workflow que continua vivo e em uso, que e perda de dado de verdade. A contagem sai no log em
 * INFO e a falha em WARN com o identificador, para que a orfandade seja um fato registrado e nao um
 * silencio.</p>
 */
@Service
public class DeleteWorkflowUseCase {

    /** Tamanho maximo do identificador interpolado em log. */
    private static final int MAX_ID_IN_LOG = 64;

    private static final Logger LOG = LoggerFactory.getLogger(DeleteWorkflowUseCase.class);

    private final WorkflowDefinitionPort workflowDefinitionPort;
    private final WorkflowExecutionPort workflowExecutionPort;

    private final WorkflowSchedulePort schedulePort;

    /**
     * Cria o caso de uso com injecao por construtor.
     *
     * @param workflowDefinitionPort porta de persistencia das definicoes
     * @param workflowExecutionPort  porta de persistencia das execucoes
     */
    public DeleteWorkflowUseCase(
            WorkflowDefinitionPort workflowDefinitionPort,
            WorkflowExecutionPort workflowExecutionPort,
            WorkflowSchedulePort schedulePort) {
        this.workflowDefinitionPort = workflowDefinitionPort;
        this.workflowExecutionPort = workflowExecutionPort;
        this.schedulePort = schedulePort;
    }

    /**
     * Remove a definicao pelo identificador e, em seguida, as execucoes dela.
     *
     * <p>A falha da cascata nao derruba a operacao: a definicao ja saiu, e devolver erro para um
     * pedido cujo efeito principal aconteceu levaria o cliente a repetir a remocao e receber
     * {@code NOT_FOUND}. O que fica e o registro em WARN.</p>
     *
     * @param actor autor da operacao, obtido da infraestrutura e nunca da entrada do cliente
     * @param id    identificador da definicao
     * @throws WorkflowNotFoundException quando nao existia definicao com o identificador
     */
    public void execute(ActorId actor, String id) {
        Objects.requireNonNull(actor, "actor nao pode ser nulo");
        Objects.requireNonNull(id, "id nao pode ser nulo");
        if (!workflowDefinitionPort.deleteById(id)) {
            throw new WorkflowNotFoundException(id);
        }
        // Cancela antes da cascata: enquanto o agendamento estiver vivo, o cron pode disparar um
        // workflow que acabou de deixar de existir -- e o disparo criaria execucoes novas depois de
        // a limpeza ter passado por elas.
        schedulePort.unregister(id);
        String safeId = TextSanitizer.truncateSystemText(id, MAX_ID_IN_LOG);
        try {
            long removed = workflowExecutionPort.deleteByWorkflowId(id);
            LOG.info("Definicao '{}' removida junto com {} execucoes", safeId, removed);
        } catch (RuntimeException e) {
            LOG.warn("Definicao '{}' removida, mas a remocao das execucoes falhou: "
                    + "as execucoes desse workflow ficaram orfas", safeId, e);
        }
    }
}
