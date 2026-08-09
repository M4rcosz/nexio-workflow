package com.nexio.workflow.infrastructure.scheduler;

import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.application.port.out.WorkflowSchedulePort;
import com.nexio.workflow.application.usecase.TriggerWorkflowUseCase;
import com.nexio.workflow.domain.model.CronExpressions;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.enums.TriggerType;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * Agenda os workflows do tipo SCHEDULE e os dispara na hora marcada.
 *
 * <h2>Dois pools, e a razao de nao ser um</h2>
 *
 * <p>O {@link TaskScheduler} decide <i>quando</i> disparar; o {@link TaskExecutor} e onde a execucao
 * de fato roda. Se o workflow rodasse na propria thread do agendador, uma execucao de trinta
 * segundos atrasaria o horario de todos os outros crons -- e o agendador tem poucas threads
 * justamente porque a tarefa dele e curta.</p>
 *
 * <h2>Por que aqui a execucao e assincrona e no disparo manual nao</h2>
 *
 * <p>A ADR 0005 escolheu execucao sincrona, e ela continua valendo para o {@code triggerWorkflow}:
 * quem clicou disparar esta esperando o resultado, e devolve-lo com os passos e a mensagem de erro e
 * mais util do que devolver um id para consultar depois. Num disparo por cron <b>nao ha ninguem
 * esperando</b>, e o unico efeito de rodar sincronamente seria prender a thread do agendador. A
 * razao para ser assincrono e "ninguem esta esperando", que e verdade aqui e falsa la.</p>
 *
 * <p>O preco e o que a ADR 0005 avisou: com uma fronteira assincrona, uma queda do processo no meio
 * da caminhada deixa a execucao presa em RUNNING, sem ninguem para grava-la como falha. Isso vale
 * <b>so para o caminho agendado</b> e esta declarado como item aberto -- a correcao e uma varredura
 * que marca como FAILED as execucoes RUNNING mais velhas que o teto de tempo da engine.</p>
 *
 * <h2>Duas coisas que este servico deliberadamente nao faz</h2>
 *
 * <p><b>Nao coordena instancias.</b> Cada instancia da aplicacao agenda os mesmos crons, entao com N
 * instancias o workflow dispara N vezes por horario. Nao ha eleicao de lider nem trava distribuida.
 * Isso e aceitavel enquanto o servico roda em uma instancia so, e deixa de ser no minuto em que
 * escalar horizontalmente -- fica registrado aqui e no guia porque um agendador que duplica efeito
 * colateral e o tipo de defeito que so aparece em producao.</p>
 *
 * <p><b>Nao recupera horario perdido.</b> Se a aplicacao estava fora do ar as 8h, o disparo das 8h
 * nao acontece depois. E o comportamento do {@link CronTrigger}, e a alternativa -- reprocessar o
 * que passou na subida -- costuma ser pior do que a omissao: o efeito colateral acontece atrasado,
 * possivelmente em rajada.</p>
 */
public class CronTriggerService implements WorkflowSchedulePort, ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CronTriggerService.class);

    /**
     * Ator dos disparos agendados.
     *
     * <p>Distinto do anonimo de proposito: no historico, "quem disparou isto" tem duas respostas
     * possiveis -- uma pessoa pela API ou o agendador -- e elas nao devem se confundir.</p>
     */
    public static final ActorId SCHEDULER_ACTOR = new ActorId("scheduler");

    private final TaskScheduler taskScheduler;
    private final TaskExecutor executionExecutor;
    private final TriggerWorkflowUseCase triggerWorkflowUseCase;
    private final WorkflowDefinitionPort workflowDefinitionPort;

    /**
     * Piso de intervalo reconferido no agendamento.
     *
     * <p>Nao basta conferir na escrita: uma definicao gravada antes de a regra existir pode ter um
     * cron de segundo a segundo, e agenda-la significaria 86.400 execucoes por dia por workflow.
     * Mesmo raciocinio da reconferencia da gramatica das condicoes no executor -- o anteparo de
     * execucao nao pode depender do que ja esta no banco.</p>
     */
    private final Duration minInterval;

    /** Agendamentos vivos, por id de workflow. */
    private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    /**
     * Workflows com uma execucao agendada em andamento.
     *
     * <p>Impede sobreposicao: um cron de trinta em trinta segundos sobre um workflow que leva um
     * minuto acumularia execucoes ate esgotar o pool, e cada uma repetiria o mesmo efeito colateral.
     * Pular e mais seguro do que empilhar, e o salto e registrado no log -- silenciosamente pular
     * seria tao confuso quanto empilhar.</p>
     */
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();

    /**
     * Cria o servico.
     *
     * @param taskScheduler          agendador que decide o horario
     * @param executionExecutor      pool onde a execucao roda
     * @param triggerWorkflowUseCase caso de uso de disparo
     * @param workflowDefinitionPort porta de persistencia das definicoes
     */
    public CronTriggerService(TaskScheduler taskScheduler,
                              TaskExecutor executionExecutor,
                              TriggerWorkflowUseCase triggerWorkflowUseCase,
                              WorkflowDefinitionPort workflowDefinitionPort) {
        this(taskScheduler, executionExecutor, triggerWorkflowUseCase, workflowDefinitionPort,
                CronExpressions.MIN_INTERVAL);
    }

    /**
     * Cria o servico com um piso de intervalo explicito.
     *
     * @param taskScheduler          agendador que decide o horario
     * @param executionExecutor      pool onde a execucao roda
     * @param triggerWorkflowUseCase caso de uso de disparo
     * @param workflowDefinitionPort porta de persistencia das definicoes
     * @param minInterval            intervalo minimo entre dois disparos do mesmo workflow
     */
    public CronTriggerService(TaskScheduler taskScheduler,
                              TaskExecutor executionExecutor,
                              TriggerWorkflowUseCase triggerWorkflowUseCase,
                              WorkflowDefinitionPort workflowDefinitionPort,
                              Duration minInterval) {
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "taskScheduler nao pode ser nulo");
        this.executionExecutor = Objects.requireNonNull(executionExecutor, "executionExecutor nao pode ser nulo");
        this.triggerWorkflowUseCase =
                Objects.requireNonNull(triggerWorkflowUseCase, "triggerWorkflowUseCase nao pode ser nulo");
        this.workflowDefinitionPort =
                Objects.requireNonNull(workflowDefinitionPort, "workflowDefinitionPort nao pode ser nulo");
        this.minInterval = Objects.requireNonNull(minInterval, "minInterval nao pode ser nulo");
    }

    /**
     * Agenda, na subida, todos os workflows SCHEDULE habilitados.
     *
     * <p>Um cron invalido gravado antes de a validacao de escrita existir e registrado e ignorado --
     * a aplicacao sobe. Derrubar a subida por causa de um documento antigo trocaria um workflow que
     * nao dispara por um servico inteiro que nao atende.</p>
     *
     * @param args argumentos da aplicacao, nao usados
     */
    @Override
    public void run(ApplicationArguments args) {
        int registered = 0;
        for (WorkflowDefinition definition : workflowDefinitionPort.findEnabled()) {
            if (isScheduled(definition) && register(definition)) {
                registered++;
            }
        }
        LOG.info("Agendamentos registrados na subida: {}", registered);
    }

    /**
     * Agenda o workflow, substituindo um agendamento anterior do mesmo id.
     *
     * <p>Substituir e o comportamento certo para o {@code updateWorkflow}: sem isso, editar o cron
     * deixaria os dois agendamentos vivos e o workflow passaria a disparar nos dois horarios.</p>
     *
     * @param definition definicao a agendar
     * @return {@code true} quando ficou agendado
     */
    @Override
    public boolean register(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition nao pode ser nulo");
        String workflowId = definition.getId();
        if (!isScheduled(definition) || !definition.isEnabled()) {
            unregister(workflowId);
            return false;
        }
        String cron;
        try {
            cron = CronExpressions.require(definition.getTriggerConfig().config(), minInterval);
        } catch (IllegalArgumentException e) {
            LOG.warn("Workflow '{}' nao agendado: {}", workflowId, e.getMessage());
            unregister(workflowId);
            return false;
        }
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> submit(workflowId), new CronTrigger(cron));
        replace(workflowId, future);
        LOG.info("Workflow '{}' agendado com cron '{}'", workflowId, cron);
        return true;
    }

    /**
     * Cancela o agendamento de um workflow. Idempotente.
     *
     * @param workflowId identificador do workflow
     * @return {@code true} quando havia um agendamento para cancelar
     */
    @Override
    public boolean unregister(String workflowId) {
        ScheduledFuture<?> previous = scheduled.remove(workflowId);
        running.remove(workflowId);
        if (previous == null) {
            return false;
        }
        // false: nao interrompe uma execucao em andamento. Matar a thread no meio da caminhada
        // deixaria a execucao gravada em RUNNING para sempre, que e exatamente o orfao que a ADR
        // 0005 quer evitar. O agendamento para de disparar; o disparo em curso termina e se registra.
        previous.cancel(false);
        LOG.info("Agendamento do workflow '{}' cancelado", workflowId);
        return true;
    }

    /** Quantidade de agendamentos vivos, para diagnostico e teste. */
    public int scheduledCount() {
        return scheduled.size();
    }

    /**
     * Entrega a execucao ao pool, pulando quando a anterior ainda nao terminou.
     *
     * <p>Roda na thread do agendador e precisa ser curta: tudo o que ela faz e decidir e submeter.
     * A rejeicao do pool cheio e tratada aqui porque deixar {@link RejectedExecutionException}
     * escapar mataria a tarefa agendada -- o {@link ScheduledFuture} para de disparar depois de uma
     * excecao, e o workflow deixaria de rodar em silencio para sempre.</p>
     */
    private void submit(String workflowId) {
        AtomicBoolean inFlight = running.computeIfAbsent(workflowId, id -> new AtomicBoolean());
        if (!inFlight.compareAndSet(false, true)) {
            LOG.warn("Disparo agendado do workflow '{}' pulado: a execucao anterior ainda esta rodando",
                    workflowId);
            return;
        }
        try {
            executionExecutor.execute(() -> runAndRelease(workflowId, inFlight));
        } catch (RejectedExecutionException e) {
            inFlight.set(false);
            LOG.warn("Disparo agendado do workflow '{}' recusado: pool de execucao cheio", workflowId);
        }
    }

    private void runAndRelease(String workflowId, AtomicBoolean inFlight) {
        try {
            triggerWorkflowUseCase.execute(SCHEDULER_ACTOR, workflowId, Map.of());
        } catch (RuntimeException e) {
            // Nao ha quem receba esta excecao: nao existe requisicao do outro lado. Sem este catch
            // ela subiria para o pool, viraria uma linha de "uncaught exception" sem contexto, e o
            // motivo do workflow agendado nao ter rodado ficaria invisivel. Workflow apagado ou
            // desativado entre o agendamento e o disparo cai aqui e e o caso comum.
            LOG.warn("Disparo agendado do workflow '{}' falhou: {}", workflowId, e.getMessage());
        } finally {
            inFlight.set(false);
        }
    }

    private void replace(String workflowId, ScheduledFuture<?> future) {
        ScheduledFuture<?> previous = scheduled.put(workflowId, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private static boolean isScheduled(WorkflowDefinition definition) {
        return definition.getTriggerConfig() != null
                && definition.getTriggerConfig().type() == TriggerType.SCHEDULE;
    }
}
