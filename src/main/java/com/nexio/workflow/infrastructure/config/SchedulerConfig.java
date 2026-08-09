package com.nexio.workflow.infrastructure.config;

import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.application.usecase.TriggerWorkflowUseCase;
import com.nexio.workflow.infrastructure.scheduler.CronTriggerService;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Pools do disparo agendado.
 *
 * <p>Sao dois, e a separacao e o ponto: o agendador so decide horario e precisa continuar pontual,
 * enquanto a execucao de um workflow pode levar dezenas de segundos. Com um pool so, uma execucao
 * longa atrasaria o horario de todos os outros crons.</p>
 */
@Configuration
public class SchedulerConfig {

    /**
     * Threads do agendador.
     *
     * <p>A tarefa agendada e curta de proposito -- decide e submete --, entao poucas threads bastam
     * mesmo com muitos workflows agendados.</p>
     */
    public static final int SCHEDULER_POOL_SIZE = 2;

    /** Threads que executam workflows agendados. */
    public static final int EXECUTION_POOL_SIZE = 8;

    /**
     * Tamanho da fila de execucoes agendadas esperando thread.
     *
     * <p>A fila e <b>limitada</b> de proposito. Uma fila ilimitada nao evita a sobrecarga, ela a
     * esconde: as execucoes acumulam, a memoria cresce e os disparos saem cada vez mais atrasados em
     * relacao ao horario pedido -- um workflow das 8h rodando as 10h e pior do que um workflow que
     * nao rodou e disse por que. Cheia, a submissao e recusada e o salto vai para o log.</p>
     */
    public static final int EXECUTION_QUEUE_CAPACITY = 100;

    /**
     * Agendador dos gatilhos por cron.
     *
     * @return agendador configurado
     */
    @Bean
    public ThreadPoolTaskScheduler workflowTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SCHEDULER_POOL_SIZE);
        scheduler.setThreadNamePrefix("wf-cron-");
        // Nao espera as tarefas no desligamento: o que esta em andamento e uma execucao de workflow,
        // que roda no outro pool, e o que esta agendado so precisa parar de disparar.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    /**
     * Pool onde as execucoes agendadas rodam.
     *
     * <p>{@code AbortPolicy} e a rejeicao escolhida: com a fila cheia, a submissao falha e o
     * {@code CronTriggerService} registra o salto. As alternativas sao piores aqui --
     * {@code CallerRunsPolicy} executaria o workflow na thread do agendador, que e exatamente o que
     * a separacao de pools existe para impedir, e {@code DiscardPolicy} descartaria em silencio.</p>
     *
     * @return pool de execucao configurado
     */
    @Bean
    public ThreadPoolTaskExecutor workflowExecutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(EXECUTION_POOL_SIZE);
        executor.setMaxPoolSize(EXECUTION_POOL_SIZE);
        executor.setQueueCapacity(EXECUTION_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("wf-exec-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // Espera a execucao em andamento terminar no desligamento: ela ja gravou um documento em
        // RUNNING, e matar a thread aqui e o caminho mais curto para o orfao que a ADR 0005 evita.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(45);
        return executor;
    }

    /**
     * Servico que agenda e cancela os gatilhos por cron.
     *
     * @param scheduler              agendador
     * @param executor               pool de execucao
     * @param triggerWorkflowUseCase caso de uso de disparo
     * @param workflowDefinitionPort porta de persistencia das definicoes
     * @return servico de agendamento
     */
    @Bean
    public CronTriggerService cronTriggerService(ThreadPoolTaskScheduler scheduler,
                                                 ThreadPoolTaskExecutor executor,
                                                 TriggerWorkflowUseCase triggerWorkflowUseCase,
                                                 WorkflowDefinitionPort workflowDefinitionPort) {
        return new CronTriggerService(scheduler, executor, triggerWorkflowUseCase, workflowDefinitionPort);
    }
}
