package com.nexio.workflow.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propriedades da engine de execucao, prefixo {@code nexio.engine}.
 *
 * <p>O teto de tempo total e configuravel porque ele depende do parque de servicos que os nos HTTP
 * chamam, que muda de ambiente para ambiente. O que <b>nao</b> e configuravel e desliga-lo: o valor
 * precisa ser positivo e nao pode passar de {@code 5m}. O teto existe porque os tempos limite do
 * cliente HTTP sao por no e nao limitam a execucao em conjunto (ver
 * {@code docs/adr/0005-synchronous-execution.md}); um {@code 1h} em producao devolveria o problema
 * inteiro sem passar por revisao de codigo, que e o mesmo argumento pelo qual o
 * {@link HttpClientProperties} nao expoe os tempos limite por requisicao.</p>
 *
 * <p>A recusa acontece na ligacao das propriedades, entao um valor invalido derruba a subida da
 * aplicacao em vez de aparecer no primeiro disparo.</p>
 *
 * @param maxExecutionDuration teto de tempo total de uma execucao, contado do inicio dela e
 *                             verificado antes de cada no. Padrao de 30s: da folga para uns poucos
 *                             nos HTTP no tempo limite de requisicao (10s) sem chegar perto da soma
 *                             dos tempos limite de um grafo cheio, que e o que ele existe para
 *                             evitar
 */
@ConfigurationProperties(prefix = "nexio.engine")
public record WorkflowEngineProperties(@DefaultValue("30s") Duration maxExecutionDuration) {

    /** Maior teto aceito em configuracao; acima disso o teto deixa de ser mitigacao. */
    public static final Duration MAX_CONFIGURABLE_DURATION = Duration.ofMinutes(5);

    public WorkflowEngineProperties {
        if (maxExecutionDuration == null || maxExecutionDuration.isZero() || maxExecutionDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "nexio.engine.max-execution-duration precisa ser positivo: " + maxExecutionDuration);
        }
        if (maxExecutionDuration.compareTo(MAX_CONFIGURABLE_DURATION) > 0) {
            throw new IllegalArgumentException(
                    "nexio.engine.max-execution-duration nao pode passar de " + MAX_CONFIGURABLE_DURATION
                            + ": " + maxExecutionDuration);
        }
    }
}
