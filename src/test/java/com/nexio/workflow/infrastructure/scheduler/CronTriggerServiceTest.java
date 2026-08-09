package com.nexio.workflow.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.application.usecase.TriggerWorkflowUseCase;
import com.nexio.workflow.domain.model.TriggerConfig;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowExecution;
import com.nexio.workflow.domain.model.enums.TriggerType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Teste do agendamento por cron.
 *
 * <p>Usa uma expressao no piso de trinta segundos nos casos que so precisam de um agendamento vivo,
 * e um {@code TaskScheduler} de verdade nos que precisam ver a tarefa disparar -- com espera ativa
 * curta em vez de {@code sleep} fixo.</p>
 */
class CronTriggerServiceTest {

    private ThreadPoolTaskScheduler scheduler;
    private ThreadPoolTaskExecutor executor;
    private TriggerWorkflowUseCase triggerUseCase;
    private WorkflowDefinitionPort definitionPort;
    private CronTriggerService service;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.initialize();
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.initialize();
        triggerUseCase = mock(TriggerWorkflowUseCase.class);
        definitionPort = mock(WorkflowDefinitionPort.class);
        when(definitionPort.findEnabled()).thenReturn(List.of());
        // Piso de um segundo: o piso de producao e trinta, e um teste que precise ve-lo disparar
        // teria de esperar trinta segundos de relogio real.
        service = new CronTriggerService(scheduler, executor, triggerUseCase, definitionPort,
                Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
        executor.shutdown();
    }

    @Test
    void registersAnEnabledScheduleWorkflow() {
        assertThat(service.register(scheduleDefinition("wf-1", "*/30 * * * * *", true))).isTrue();
        assertThat(service.scheduledCount()).isEqualTo(1);
    }

    /** Workflow desabilitado nao fica agendado: desligar tem que ter efeito no agendador tambem. */
    @Test
    void doesNotScheduleADisabledWorkflow() {
        assertThat(service.register(scheduleDefinition("wf-1", "*/30 * * * * *", false))).isFalse();
        assertThat(service.scheduledCount()).isZero();
    }

    /** Workflow que nao e SCHEDULE nao tem o que agendar. */
    @Test
    void doesNotScheduleAWorkflowOfAnotherTriggerType() {
        WorkflowDefinition definition = scheduleDefinition("wf-1", "*/30 * * * * *", true);
        definition.setTriggerConfig(new TriggerConfig(TriggerType.MOCK_EVENT, Map.of()));

        assertThat(service.register(definition)).isFalse();
        assertThat(service.scheduledCount()).isZero();
    }

    /**
     * Reagendar substitui em vez de acumular.
     *
     * <p>Sem isso, editar o cron deixaria os dois agendamentos vivos e o workflow passaria a
     * disparar nos dois horarios -- um efeito colateral duplicado que ninguem pediu.</p>
     */
    @Test
    void reschedulingReplacesInsteadOfAccumulating() {
        service.register(scheduleDefinition("wf-1", "*/30 * * * * *", true));
        service.register(scheduleDefinition("wf-1", "0 */5 * * * *", true));

        assertThat(service.scheduledCount()).isEqualTo(1);
    }

    /**
     * Passar a desabilitado cancela o agendamento pelo mesmo metodo que agenda.
     *
     * <p>O caso de uso de atualizacao chama {@code register} sempre, sem saber em qual direcao a
     * definicao mudou; e o servico que traduz "nao vale mais" em cancelamento.</p>
     */
    @Test
    void registeringADisabledDefinitionCancelsAPreviousSchedule() {
        service.register(scheduleDefinition("wf-1", "*/30 * * * * *", true));

        service.register(scheduleDefinition("wf-1", "*/30 * * * * *", false));

        assertThat(service.scheduledCount()).isZero();
    }

    @Test
    void unregisterIsIdempotent() {
        service.register(scheduleDefinition("wf-1", "*/30 * * * * *", true));

        assertThat(service.unregister("wf-1")).isTrue();
        assertThat(service.unregister("wf-1")).isFalse();
        assertThat(service.scheduledCount()).isZero();
    }

    /** Cron invalido nao agenda e nao derruba nada: o log fica com o motivo. */
    @Test
    void anInvalidCronIsRefusedWithoutBreakingAnything() {
        assertThat(service.register(scheduleDefinition("wf-1", "nao e cron", true))).isFalse();
        assertThat(service.scheduledCount()).isZero();
    }

    /** Na subida, os workflows SCHEDULE habilitados ficam agendados. */
    @Test
    void schedulesEveryEnabledScheduleWorkflowOnStartup() {
        WorkflowDefinition agendado = scheduleDefinition("wf-1", "*/30 * * * * *", true);
        WorkflowDefinition manual = scheduleDefinition("wf-2", "*/30 * * * * *", true);
        manual.setTriggerConfig(new TriggerConfig(TriggerType.MOCK_EVENT, Map.of()));
        when(definitionPort.findEnabled()).thenReturn(List.of(agendado, manual));

        service.run(null);

        assertThat(service.scheduledCount()).isEqualTo(1);
    }

    /** O disparo agendado chega ao caso de uso, com o ator do agendador. */
    @Test
    void firesTheWorkflowThroughTheUseCase() {
        when(triggerUseCase.execute(any(), any(), any())).thenReturn(new WorkflowExecution());
        service.register(scheduleDefinition("wf-1", "* * * * * *", true));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(triggerUseCase).execute(eq(CronTriggerService.SCHEDULER_ACTOR), eq("wf-1"), any()));
    }

    /**
     * Um disparo nao comeca enquanto o anterior do mesmo workflow nao termina.
     *
     * <p>Sem isso, um cron de segundo a segundo sobre um workflow lento acumula execucoes ate
     * esgotar o pool, e cada uma repete o mesmo efeito colateral. O executor deste teste tem duas
     * threads, entao sem a trava a segunda invocacao entraria imediatamente.</p>
     */
    @Test
    void skipsAScheduledRunWhileThePreviousOneIsStillGoing() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        when(triggerUseCase.execute(any(), any(), any())).thenAnswer(invocation -> {
            started.incrementAndGet();
            hold.await(5, TimeUnit.SECONDS);
            return new WorkflowExecution();
        });

        service.register(scheduleDefinition("wf-1", "* * * * * *", true));
        await().atMost(Duration.ofSeconds(5)).until(() -> started.get() >= 1);
        // Tempo suficiente para varios horarios passarem com a primeira execucao ainda presa.
        Thread.sleep(2_500);

        assertThat(started.get()).as("execucoes sobrepostas do mesmo workflow").isEqualTo(1);
        hold.countDown();
    }

    /**
     * Uma falha no disparo agendado nao mata o agendamento.
     *
     * <p>Este e o defeito silencioso que o {@code catch} do servico evita: uma excecao que escapa da
     * tarefa faz o {@code ScheduledFuture} parar de disparar, e o workflow deixaria de rodar para
     * sempre sem nada indicar.</p>
     */
    @Test
    void aFailedRunDoesNotKillTheSchedule() {
        AtomicInteger attempts = new AtomicInteger();
        when(triggerUseCase.execute(any(), any(), any())).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("workflow desativado");
        });

        service.register(scheduleDefinition("wf-1", "* * * * * *", true));

        await().atMost(Duration.ofSeconds(6)).until(() -> attempts.get() >= 2);
        assertThat(service.scheduledCount()).isEqualTo(1);
    }

    /** Cancelar impede os disparos seguintes. */
    @Test
    void unregisterStopsFurtherRuns() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        when(triggerUseCase.execute(any(), any(), any())).thenAnswer(invocation -> {
            runs.incrementAndGet();
            return new WorkflowExecution();
        });
        service.register(scheduleDefinition("wf-1", "* * * * * *", true));
        await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() >= 1);

        service.unregister("wf-1");
        int afterCancel = runs.get();
        Thread.sleep(2_500);

        assertThat(runs.get()).isEqualTo(afterCancel);
    }

    @Test
    void neverTriggersAWorkflowThatWasNeverRegistered() {
        verify(triggerUseCase, never()).execute(any(), any(), any());
    }

    private static WorkflowDefinition scheduleDefinition(String id, String cron, boolean enabled) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(id);
        definition.setName("agendado " + id);
        definition.setEnabled(enabled);
        definition.setTriggerConfig(new TriggerConfig(TriggerType.SCHEDULE, Map.of("cron", cron)));
        return definition;
    }
}
