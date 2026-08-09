package com.nexio.workflow.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Teste das regras sobre a expressao cron de um workflow SCHEDULE.
 */
class CronExpressionsTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "0 0 8 * * MON-FRI",
        "0 */5 * * * *",
        "0 0 0 1 1 *",
        "0 30 9 * * *"
    })
    void acceptsExpressionsAWorkflowActuallyNeeds(String cron) {
        assertThat(CronExpressions.require(Map.of("cron", cron))).isEqualTo(cron);
    }

    /**
     * O formato do Spring tem seis campos, comecando por segundos.
     *
     * <p>O exemplo que estava no Javadoc do {@code TriggerConfig} -- {@code "0 8 * * 1-5"}, no
     * formato de cinco campos do cron do Unix -- e recusado. A diferenca e silenciosa e cara: cinco
     * campos ou nao analisam, ou analisam significando outra coisa.</p>
     */
    @Test
    void refusesTheFiveFieldUnixFormat() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> CronExpressions.require(Map.of("cron", "0 8 * * 1-5")))
                .withMessageContaining("seis campos");
    }

    /**
     * Um cron de segundo a segundo e recusado.
     *
     * <p>Sao 86.400 execucoes por dia por workflow, cada uma capaz de 200 chamadas HTTP de saida e
     * cada uma gravando um documento -- e, como {@code createWorkflow} e anonimo, e trabalho
     * agendado numa linha de configuracao que ninguem precisa continuar pedindo.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {"* * * * * *", "*/5 * * * * *", "*/29 * * * * *"})
    void refusesAnIntervalBelowTheFloor(String cron) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> CronExpressions.require(Map.of("cron", cron)))
                .withMessageContaining("minimo");
    }

    /** Exatamente no piso e aceito: o limite recusa o que passa dele, nao o que o alcanca. */
    @Test
    void acceptsExactlyTheFloor() {
        assertThat(CronExpressions.require(Map.of("cron", "*/30 * * * * *")))
                .isEqualTo("*/30 * * * * *");
    }

    /**
     * A expressao que nunca dispara e recusada.
     *
     * <p>{@code 0 0 0 30 2 *} -- 30 de fevereiro -- analisa sem erro e simplesmente nao acontece.
     * Aceitar produziria um workflow que parece agendado, esta habilitado e nunca roda, que e das
     * falhas mais dificeis de perceber.</p>
     */
    @Test
    void refusesAnExpressionThatCanNeverFire() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> CronExpressions.require(Map.of("cron", "0 0 0 30 2 *")))
                .withMessageContaining("nunca dispara");
    }

    @Test
    void refusesAMissingOrEmptyCron() {
        Map<String, Object> semCron = new HashMap<>();
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> CronExpressions.require(semCron))
                .withMessageContaining("precisa de triggerConfig.config.cron");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> CronExpressions.require(Map.of("cron", "   ")));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> CronExpressions.require(null));
    }

    /** Valor que nao e texto tambem e recusado: {@code cron} nao e um numero nem um objeto. */
    @Test
    void refusesANonTextualCron() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> CronExpressions.require(Map.of("cron", 42)));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> CronExpressions.require(Map.of("cron", Map.of("a", 1))));
    }

    @Test
    void refusesGarbage() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> CronExpressions.require(Map.of("cron", "nao e cron")))
                .withMessageContaining("cron invalido");
    }

    /** A mensagem de erro nao ecoa uma expressao gigante de volta para o log. */
    @Test
    void truncatesAnAbsurdlyLongExpressionInsteadOfEchoingIt() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> CronExpressions.require(Map.of("cron", "0 ".repeat(200))))
                .withMessageContaining("excede");
    }
}
