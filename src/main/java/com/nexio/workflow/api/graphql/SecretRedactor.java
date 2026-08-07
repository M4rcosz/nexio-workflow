package com.nexio.workflow.api.graphql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Substitui, na saida, o valor de toda chave cujo nome indique credencial.
 *
 * <p>Existe porque a {@code config} de um no HTTP_REQUEST e um espaco livre onde o usuario guarda a
 * requisicao inteira, cabecalhos inclusive: e o lugar natural do {@code Authorization} e da chave
 * de API do servico chamado. Sem redacao, quem consegue fazer {@code workflow(id)} ou
 * {@code workflows} le todos esses segredos de volta em texto claro -- e, enquanto nao houver
 * autenticacao, isso e todo mundo. Uma consulta de leitura nao deveria ser um caminho para extrair
 * credencial.</p>
 *
 * <p><b>A redacao e so da leitura.</b> Ela roda na construcao do DTO de resposta, depois da
 * persistencia e fora do caminho de escrita: o que esta gravado continua intacto, e e o valor
 * gravado que a execucao do no usa. Redigir na escrita destruiria a credencial e quebraria o
 * workflow.</p>
 *
 * <p>A comparacao e por conteudo da chave, sem diferenciar maiuscula de minuscula: cabecalho HTTP
 * nao tem forma canonica ({@code Authorization}, {@code authorization}, {@code X-Api-Key},
 * {@code x_api_key} sao a mesma coisa na pratica) e casar por igualdade exata deixaria passar toda
 * variacao que alguem resolvesse escrever. Um falso positivo aqui custa um campo mascarado numa
 * resposta; um falso negativo custa uma credencial vazada.</p>
 *
 * <p>Desce por mapas e listas aninhados porque e exatamente ai que o segredo mora: o
 * {@code Authorization} nao esta na raiz da config, esta em {@code headers.Authorization}.</p>
 */
public final class SecretRedactor {

    /** Marcador fixo que substitui o valor de uma chave sensivel. */
    public static final String REDACTED = "***REDACTED***";

    /**
     * Chaves consideradas sensiveis. O casamento e por conteudo, entao {@code X-Api-Key},
     * {@code clientSecret} e {@code Set-Cookie} tambem sao pegos.
     */
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "authorization|api[-_]?key|token|secret|password|passwd|credential|cookie|set-cookie",
            Pattern.CASE_INSENSITIVE);

    private SecretRedactor() {
        throw new AssertionError("Classe utilitaria nao deve ser instanciada");
    }

    /**
     * Devolve uma copia do mapa com o valor de toda chave sensivel trocado pelo marcador.
     *
     * @param source mapa a redigir, pode ser nulo
     * @return copia imutavel ja redigida, ou mapa vazio quando a origem e nula
     */
    public static Map<String, Object> redact(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            redacted.put(key, isSensitive(key) ? REDACTED : redactValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(redacted);
    }

    /**
     * Informa se o nome da chave indica credencial.
     *
     * @param key nome da chave, pode ser nulo
     * @return {@code true} quando o valor da chave deve ser mascarado
     */
    public static boolean isSensitive(String key) {
        return key != null && SENSITIVE_KEY.matcher(key).find();
    }

    private static Object redactValue(Object value) {
        return switch (value) {
            case null -> null;
            case Map<?, ?> map -> redactUnknownMap(map);
            case List<?> list -> redactList(list);
            default -> value;
        };
    }

    private static Map<String, Object> redactUnknownMap(Map<?, ?> source) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            redacted.put(key, isSensitive(key) ? REDACTED : redactValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(redacted);
    }

    private static List<Object> redactList(List<?> source) {
        List<Object> redacted = new ArrayList<>(source.size());
        for (Object item : source) {
            redacted.add(redactValue(item));
        }
        return Collections.unmodifiableList(redacted);
    }
}
