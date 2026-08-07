package com.nexio.workflow.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Cobre o teto de tempo total como propriedade: o padrao, a conversao e os dois limites que a
 * ligacao recusa.
 *
 * <p>Os limites sao testados aqui e nao so na engine porque o ponto deles e o momento: um valor
 * invalido precisa derrubar a subida da aplicacao, e nao aparecer no primeiro disparo, que pode ser
 * dias depois de alguem ter editado a configuracao.</p>
 */
class WorkflowEnginePropertiesTest {

    @Test
    void bindsTheDefaultOfThirtySecondsWhenNothingIsConfigured() {
        assertThat(bind(Map.of()).maxExecutionDuration()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void bindsTheConfiguredValueInTheUsualShorthand() {
        assertThat(bind(Map.of("nexio.engine.max-execution-duration", "45s")).maxExecutionDuration())
                .isEqualTo(Duration.ofSeconds(45));
    }

    /**
     * Zero e negativo nao "desligam" o teto, fazem toda execucao falhar antes do primeiro no. Sao
     * recusados por serem, dos dois jeitos, uma configuracao que ninguem quis.
     */
    @Test
    void rejectsNonPositiveValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorkflowEngineProperties(Duration.ZERO))
                .withMessageContaining("positivo");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorkflowEngineProperties(Duration.ofSeconds(-1)))
                .withMessageContaining("positivo");
    }

    /**
     * O teto grande demais e o caminho trivial para desligar a mitigacao sem passar por revisao de
     * codigo -- o mesmo motivo pelo qual o {@link HttpClientProperties} nao expoe os tempos limite
     * por requisicao. Recusar na ligacao faz o erro aparecer na subida.
     */
    @Test
    void rejectsAValueLargeEnoughToStopBeingACap() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorkflowEngineProperties(Duration.ofHours(1)))
                .withMessageContaining("nao pode passar de");

        assertThatExceptionOfType(BindException.class)
                .isThrownBy(() -> bind(Map.of("nexio.engine.max-execution-duration", "1h")));
    }

    private WorkflowEngineProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("nexio.engine", WorkflowEngineProperties.class);
    }
}
