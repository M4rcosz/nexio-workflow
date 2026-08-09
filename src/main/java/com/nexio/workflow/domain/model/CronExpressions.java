package com.nexio.workflow.domain.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.scheduling.support.CronExpression;

/**
 * Regras sobre a expressao cron de um workflow do tipo SCHEDULE.
 *
 * <p>Mora no dominio pelo mesmo motivo do {@link ConditionExpressionValidator}: a validacao de
 * escrita precisa recusar a expressao invalida no {@code createWorkflow}, e o dominio nao valida o
 * que nao conhece. A alternativa que a issue #27 sugere -- tratar e logar o cron invalido na subida
 * da aplicacao -- e a rede de seguranca, nao a defesa principal: quem digitou o cron errado nao le o
 * log do servidor, e um workflow que existe, esta habilitado e nunca dispara e das falhas mais
 * dificeis de perceber que este sistema pode ter.</p>
 *
 * <p><b>Sobre o formato:</b> o Spring usa cron de <b>seis</b> campos, comecando por segundos --
 * {@code 0 0 8 * * MON-FRI}, e nao os cinco campos do cron do Unix. O exemplo que estava no Javadoc
 * do {@link TriggerConfig} ({@code "0 8 * * 1-5"}) tem cinco campos e nao e aceito. A diferenca e
 * silenciosa e cara: cinco campos ou sao recusados ou, quando por acaso analisam, agendam outra
 * coisa.</p>
 */
public final class CronExpressions {

    /** Chave onde a expressao mora dentro de {@code triggerConfig.config}. */
    public static final String CRON_KEY = "cron";

    /** Teto de tamanho, alinhado ao que uma expressao de seis campos precisa com folga. */
    public static final int MAX_LENGTH = 128;

    /**
     * Intervalo minimo entre dois disparos do mesmo workflow.
     *
     * <p>Um cron de segundo a segundo ({@code * * * * * *}) agenda 86.400 execucoes por dia por
     * workflow, cada uma capaz de {@value WorkflowExecution#MAX_STEPS} chamadas HTTP de saida, e
     * cada uma gravando um documento. Como {@code createWorkflow} e anonimo hoje, isso e uma bomba
     * de trabalho agendada em uma linha de configuracao -- e, ao contrario de um disparo manual,
     * ninguem precisa continuar pedindo. O piso e conferido de verdade, comparando os dois proximos
     * horarios que a expressao produz, e nao por casamento de texto.</p>
     */
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(30);

    private CronExpressions() {
        throw new AssertionError("Classe utilitaria nao deve ser instanciada");
    }

    /**
     * Extrai e valida a expressao cron da configuracao de um gatilho SCHEDULE.
     *
     * @param config configuracao do gatilho, pode ser nula
     * @return a expressao textual, ja validada
     * @throws IllegalArgumentException quando falta, nao e texto, nao analisa, nunca dispara ou
     *                                  dispara com intervalo menor que {@link #MIN_INTERVAL}
     */
    public static String require(Map<String, Object> config) {
        return require(config, MIN_INTERVAL);
    }

    /**
     * Como {@link #require(Map)}, com o piso de intervalo informado.
     *
     * <p>O piso e parametro e nao constante lida aqui dentro pelo mesmo motivo que a
     * {@code WorkflowEngine} recebe o teto de tempo e o {@code Clock} por construtor: uma regra
     * temporal amarrada a uma constante so e testavel por teste que dorme o tempo real dela.</p>
     *
     * @param config      configuracao do gatilho, pode ser nula
     * @param minInterval intervalo minimo exigido entre dois disparos
     * @return a expressao textual, ja validada
     * @throws IllegalArgumentException quando falta, nao e texto, nao analisa, nunca dispara ou
     *                                  dispara com intervalo menor que {@code minInterval}
     */
    public static String require(Map<String, Object> config, Duration minInterval) {
        Object raw = config == null ? null : config.get(CRON_KEY);
        if (!(raw instanceof String cron) || cron.isBlank()) {
            throw new IllegalArgumentException(
                    "Workflow do tipo SCHEDULE precisa de triggerConfig.config.cron com a expressao cron");
        }
        if (cron.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "cron excede " + MAX_LENGTH + " caracteres: " + cron.length());
        }
        CronExpression parsed = parse(cron);
        requireInterval(parsed, cron, minInterval);
        return cron;
    }

    /**
     * Analisa a expressao, traduzindo a recusa do Spring em mensagem util.
     *
     * @param cron expressao textual
     * @return expressao analisada
     * @throws IllegalArgumentException quando a expressao nao e valida
     */
    public static CronExpression parse(String cron) {
        try {
            return CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cron invalido: '"
                    + TextSanitizer.truncateSystemText(cron, MAX_LENGTH)
                    + "'. O formato tem seis campos comecando por segundos, por exemplo"
                    + " '0 0 8 * * MON-FRI' para as 8h em dias uteis", e);
        }
    }

    /**
     * Recusa a expressao que dispara depressa demais, e a que nunca dispara.
     *
     * <p>"Nunca dispara" e um caso real e silencioso: {@code 0 0 0 30 2 *} (30 de fevereiro) analisa
     * sem erro e simplesmente nao acontece. Recusar na escrita evita o workflow que parece agendado
     * e nunca roda.</p>
     */
    private static void requireInterval(CronExpression parsed, String cron, Duration minInterval) {
        LocalDateTime first = parsed.next(LocalDateTime.now());
        if (first == null) {
            throw new IllegalArgumentException("cron '" + TextSanitizer.truncateSystemText(cron, MAX_LENGTH)
                    + "' nunca dispara: nao existe data futura que satisfaca a expressao");
        }
        LocalDateTime second = parsed.next(first);
        if (second == null) {
            // Dispara uma vez so e nunca mais. E legitimo, e nao ha intervalo para conferir.
            return;
        }
        Duration interval = Duration.between(first, second);
        if (interval.compareTo(minInterval) < 0) {
            throw new IllegalArgumentException("cron '" + TextSanitizer.truncateSystemText(cron, MAX_LENGTH)
                    + "' dispara a cada " + interval.toSeconds() + "s, e o minimo e "
                    + minInterval.toSeconds() + "s");
        }
    }
}
