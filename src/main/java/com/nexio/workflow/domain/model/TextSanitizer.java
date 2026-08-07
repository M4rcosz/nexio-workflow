package com.nexio.workflow.domain.model;

/**
 * Utilitario do dominio para os campos de texto livre do modelo.
 *
 * <p>O {@link MapSanitizer} limita o que entra nos mapas livres, mas nada limitava os campos de
 * texto soltos das entidades ({@code name}, {@code description}, {@code errorMessage},
 * {@code ExecutionStep.error}). Sem teto, cada um deles e um caminho direto para um documento
 * gigante -- ou, no caso das mensagens de erro, para um log inflado por texto de excecao.</p>
 *
 * <p>Sao duas politicas, porque as origens sao diferentes:</p>
 * <ul>
 *   <li>{@link #requireWithin(String, int, String)} <b>rejeita</b>. Serve ao que vem do usuario
 *       ({@code name}, {@code description}, {@code startNodeId}): quem enviou esta na frente do
 *       teclado e pode corrigir, e aceitar em silencio um valor cortado seria gravar algo que
 *       ninguem pediu.</li>
 *   <li>{@link #truncateSystemText(String, int)} <b>trunca</b>. Serve ao que o sistema mesmo gera a
 *       partir de texto de excecao ({@code errorMessage}, {@code ExecutionStep.error}): lancar ali
 *       seria estourar uma excecao no meio do registro de uma falha, e o resultado seria perder a
 *       falha original -- justamente a informacao que se estava tentando guardar.</li>
 * </ul>
 */
public final class TextSanitizer {

    /** Marcador acrescentado ao texto de sistema cortado, para que o corte fique explicito. */
    public static final String TRUNCATION_MARKER = "... [truncado]";

    private TextSanitizer() {
        throw new AssertionError("Classe utilitaria nao deve ser instanciada");
    }

    /**
     * Exige que um texto de origem externa caiba no limite.
     *
     * @param value     texto a verificar, pode ser nulo
     * @param maxLength tamanho maximo aceito
     * @param field     nome logico do campo, usado na mensagem de erro
     * @return o proprio texto, inalterado
     * @throws IllegalArgumentException quando o texto passa do limite
     */
    public static String requireWithin(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(
                    "O campo '" + field + "' excede " + maxLength + " caracteres: " + value.length());
        }
        return value;
    }

    /**
     * Higieniza e corta um texto gerado pelo proprio sistema.
     *
     * <p>Os caracteres de controle saem antes do corte. Eles chegam de {@code getMessage()} de
     * excecao, que pode carregar conteudo de terceiros, e o destino desse texto e o log da
     * aplicacao e a resposta GraphQL: uma quebra de linha ali forja uma linha de log inteira. Cada
     * caractere de controle vira um espaco em vez de sumir, senao palavras de linhas diferentes
     * ficariam coladas e a mensagem perderia sentido.</p>
     *
     * @param value     texto a higienizar, pode ser nulo
     * @param maxLength tamanho maximo do resultado, ja contando o {@value #TRUNCATION_MARKER}
     * @return texto sem caracteres de controle e dentro do limite, ou {@code null} quando a origem
     *         e nula
     */
    public static String truncateSystemText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String clean = stripControlCharacters(value);
        if (clean.length() <= maxLength) {
            return clean;
        }
        int keep = Math.max(0, maxLength - TRUNCATION_MARKER.length());
        return clean.substring(0, keep) + TRUNCATION_MARKER;
    }

    private static String stripControlCharacters(String value) {
        StringBuilder clean = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            clean.append(Character.isISOControl(current) ? ' ' : current);
        }
        return clean.toString().trim();
    }
}
