package com.nexio.workflow.application.port.out;

import com.nexio.workflow.domain.model.WorkflowDefinition;

/**
 * Porta de saida do agendamento: mantem o agendador em dia com o que foi gravado.
 *
 * <p>Existe para que os casos de uso de escrita nao conhecam o agendador. Sem ela, o
 * {@code CreateWorkflowUseCase} importaria uma classe de {@code infrastructure.scheduler} e a regra
 * de dependencia do projeto -- aplicacao nao conhece infraestrutura -- cairia no primeiro
 * {@code register}. Com ela, o caso de uso diz <i>o que</i> precisa acontecer e quem sabe <i>como</i>
 * continua do outro lado da fronteira.</p>
 *
 * <p>Sem tipo de framework na assinatura, como as demais portas: nada de {@code ScheduledFuture},
 * {@code TaskScheduler} ou {@code CronTrigger} atravessa aqui.</p>
 *
 * <p><b>As duas operacoes sao idempotentes e nunca lancam por causa do agendamento.</b> Isso e
 * deliberado: um cron que nao pode ser agendado nao pode desfazer uma gravacao que ja aconteceu. O
 * caso de uso grava e depois avisa; se o aviso falhar, o workflow existe e nao dispara -- que e
 * ruim, mas menos ruim do que um {@code createWorkflow} que devolve erro tendo gravado. A expressao
 * invalida, que e a causa provavel, ja foi recusada bem antes, na validacao de escrita.</p>
 */
public interface WorkflowSchedulePort {

    /**
     * Poe o agendamento do workflow em dia com a definicao informada.
     *
     * <p>Chamado depois de criar e depois de atualizar. Substitui o agendamento anterior do mesmo
     * workflow -- sem isso, editar o cron deixaria os dois vivos e o workflow dispararia nos dois
     * horarios. Definicao que nao e SCHEDULE, ou que esta desabilitada, tem o agendamento removido:
     * o mesmo metodo cobre "passou a valer" e "deixou de valer", porque quem chama sabe que a
     * definicao mudou e nao precisa saber em qual direcao.</p>
     *
     * @param definition definicao ja gravada
     * @return {@code true} quando o workflow ficou agendado
     */
    boolean register(WorkflowDefinition definition);

    /**
     * Remove o agendamento de um workflow.
     *
     * <p>Chamado na remocao. Nao interrompe uma execucao em andamento: o agendamento para de
     * disparar e o disparo em curso termina e se registra.</p>
     *
     * @param workflowId identificador do workflow
     * @return {@code true} quando havia agendamento
     */
    boolean unregister(String workflowId);
}
