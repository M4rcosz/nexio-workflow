package com.nexio.workflow.domain.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ajusta dado vindo de fora ate ele caber na politica estrita do {@link MapSanitizer}, em vez de
 * recusa-lo.
 *
 * <p>Existe por uma assimetria que so aparece quando o executor de no HTTP entra em cena. O
 * {@link MapSanitizer} estrito e a regra certa para o que <b>o usuario escreve</b>: uma chave
 * comecando com {@code $} na config de um no e erro dele, e recusar na escrita e devolver o erro a
 * quem pode corrigi-lo. O corpo de resposta de um servico de terceiro nao e nada disso. Ninguem do
 * lado de ca escolheu aquelas chaves, e a recusa nao chega a quem poderia mudar a resposta -- chega
 * como execucao FALHADA.</p>
 *
 * <p>E nao e caso raro. Medindo a politica estrita contra o que uma API devolve de verdade:
 * {@code _links} e {@code _embedded} de qualquer API HAL sao recusados porque chave nao pode
 * comecar com {@code _}; {@code $ref} e {@code $schema} de JSON Schema pelo {@code $}; um corpo com
 * mais de {@value MapSanitizer#MAX_ENTRIES} entradas somando todos os niveis cabe folgado nos 256 KB
 * que o cliente aceita; e o {@code BigInteger} que o Jackson produz para um inteiro grande nao esta
 * entre os escalares aceitos. <b>A chamada teria dado certo e a execucao morreria na gravacao do
 * passo</b> -- depois do efeito colateral do outro lado ja ter acontecido, que e o pior momento
 * possivel para descobrir um problema de formato.</p>
 *
 * <p><b>O que sai, sai contado.</b> Chave impossivel de gravar e removida e entra em
 * {@link Sanitized#droppedKeys()}; corte por tamanho, por profundidade ou por numero de entradas
 * levanta {@link Sanitized#truncated()}. A perda fica visivel no proprio passo, em vez de o
 * historico mostrar um objeto que parece completo. Chave recusada e <b>removida e nao renomeada</b>:
 * um esquema de escape ({@code _links} virando {@code u_links}) colide quando os dois nomes existem
 * e vira superficie de API que quem escreve expressao precisa decorar.</p>
 *
 * <p><b>Isto nao afrouxa nada na fronteira de escrita.</b> O {@link MapSanitizer} estrito continua
 * valendo para tudo que o usuario manda, e continua sendo ele que decide o que pode ser gravado --
 * o teste que importa nesta classe e que a saida dela sempre passa por
 * {@link MapSanitizer#validate(Map, String)}. Aqui e adaptacao de dado que ja chegou, e a diferenca
 * entre as duas situacoes e quem consegue corrigir o problema.</p>
 */
public final class ExternalDataSanitizer {

    /**
     * Escalares que a politica estrita aceita e que chegam prontos.
     *
     * <p>Repetida aqui de proposito, e coberta por um teste que compara as duas listas: a lista do
     * {@link MapSanitizer} e privada porque e a regra de gravacao, e abri-la para esta classe faria
     * a regra parecer configuravel.</p>
     */
    private static final Set<Class<?>> ALLOWED_SCALARS = Set.of(
            Boolean.class, Integer.class, Long.class, Double.class, Float.class,
            Short.class, Byte.class, BigDecimal.class, Instant.class);

    /** Sentinela de "nao da para gravar isto", distinta de {@code null}, que e valor valido. */
    private static final Object DROP = new Object();

    /**
     * Resultado da adaptacao.
     *
     * @param value       valor pronto para gravar, ja dentro da politica estrita
     * @param droppedKeys quantas chaves foram removidas por serem impossiveis de gravar
     * @param truncated   se alguma coisa foi cortada por tamanho, profundidade ou numero de entradas
     */
    public record Sanitized(Object value, int droppedKeys, boolean truncated) {
    }

    /** Marcador que substitui o fim de um texto cortado. */
    public static final String TRUNCATION_MARKER = "...[truncado]";

    private ExternalDataSanitizer() {
        throw new AssertionError("Classe utilitaria nao deve ser instanciada");
    }

    /**
     * Adapta um valor externo para que caiba na politica estrita.
     *
     * <p>Nunca lanca excecao: qualquer coisa que nao caiba e cortada ou removida. E o ponto da
     * classe -- se ela pudesse falhar, o problema que ela resolve continuaria existindo, so mais
     * abaixo.</p>
     *
     * @param source     valor a adaptar (mapa, lista, texto, escalar ou nulo)
     * @param maxEntries orcamento de entradas disponivel, ja descontado o que o chamador vai gravar
     *                   ao lado deste valor
     * @param maxDepth   profundidade ainda disponivel, contando a partir de onde este valor sera
     *                   colocado
     * @return valor adaptado, com a contabilidade do que se perdeu
     */
    public static Sanitized sanitize(Object source, int maxEntries, int maxDepth) {
        Budget budget = new Budget(Math.max(0, maxEntries), Math.max(0, maxDepth));
        Object value = sanitizeValue(source, budget, 0);
        if (value == DROP) {
            // O sentinela nunca pode sair daqui. Dentro de um mapa ele vira chave removida e dentro
            // de uma lista vira null, mas o valor da raiz nao tinha tratamento: ele saia como um
            // java.lang.Object cru, a politica estrita o recusava como "tipo nao suportado" e a
            // execucao morria na gravacao do passo -- exatamente o que esta classe existe para
            // impedir, e com droppedKeys zerado afirmando que nada se perdeu. Alcancavel: um
            // servico respondendo text/plain com o proprio marcador de redacao no corpo, ou
            // qualquer chamador passando maxDepth zero.
            budget.dropped++;
            value = null;
        }
        return new Sanitized(value, budget.dropped, budget.truncated);
    }

    private static Object sanitizeValue(Object source, Budget budget, int depth) {
        return switch (source) {
            case null -> null;
            case Map<?, ?> map -> sanitizeMap(map, budget, depth);
            case List<?> list -> sanitizeList(list, budget, depth);
            case String text -> sanitizeString(text, budget);
            // O Jackson produz BigInteger para inteiro que nao cabe em long, e ele nao esta entre
            // os escalares aceitos. BigDecimal esta, representa o mesmo numero sem perda e tem
            // codec BSON, entao a conversao preserva o valor em vez de descartar o campo.
            case BigInteger big -> new BigDecimal(big);
            case Date date -> date.toInstant();
            case Character letter -> String.valueOf(letter);
            default -> ALLOWED_SCALARS.contains(source.getClass()) ? source : DROP;
        };
    }

    private static Object sanitizeMap(Map<?, ?> source, Budget budget, int depth) {
        if (depth >= budget.maxDepth) {
            // Devolve DROP, e nao um mapa vazio. Mapa vazio continua sendo um mapa: ele seria
            // inserido sob a chave e a politica estrita contaria mais um nivel de aninhamento
            // exatamente onde o limite ja estourou -- o corte por profundidade nao cortaria nada.
            // Descoberto pelo teste de propriedade desta classe, nao por leitura.
            budget.truncated = true;
            return DROP;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !isStorableKey(key)) {
                budget.dropped++;
                continue;
            }
            if (!budget.spendEntry()) {
                return Collections.unmodifiableMap(sanitized);
            }
            Object value = sanitizeValue(entry.getValue(), budget, depth + 1);
            if (value == DROP) {
                budget.dropped++;
                budget.refundEntry();
                continue;
            }
            sanitized.put(key, value);
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private static Object sanitizeList(List<?> source, Budget budget, int depth) {
        if (depth >= budget.maxDepth) {
            // Mesma razao do mapa: lista vazia ainda e um nivel de aninhamento.
            budget.truncated = true;
            return DROP;
        }
        List<Object> sanitized = new ArrayList<>();
        for (Object item : source) {
            if (!budget.spendEntry()) {
                break;
            }
            Object value = sanitizeValue(item, budget, depth + 1);
            // Elemento de lista que nao da para gravar vira null em vez de sumir: remover encolhe a
            // lista e desloca os indices seguintes, e uma expressao que le a terceira posicao
            // passaria a ler outro elemento em silencio.
            sanitized.add(value == DROP ? null : value);
        }
        return Collections.unmodifiableList(sanitized);
    }

    /**
     * Corta o texto no limite estrito.
     *
     * <p>Nao reaproveita {@code TextSanitizer.truncateSystemText}: aquele metodo troca caractere de
     * controle por espaco, o que e certo para mensagem de erro que vai para o log -- uma quebra de
     * linha ali forja uma linha inteira -- e errado aqui. Este texto e conteudo de um campo de
     * resposta, quebra de linha dentro dele e dado legitimo, e o destino e um valor BSON e uma
     * string JSON, os dois com escape proprio.</p>
     */
    private static Object sanitizeString(String text, Budget budget) {
        // O marcador de redacao e recusado como valor pela politica estrita, para impedir que uma
        // leitura redigida volte por cima do segredo de verdade numa escrita. Um servico de
        // terceiro devolvendo exatamente este texto e bizarro mas possivel, e ele nao pode ser
        // gravado; sai como chave removida, pelo mesmo criterio das demais.
        if (MapSanitizer.REDACTED_MARKER.equals(text)) {
            return DROP;
        }
        if (text.length() <= MapSanitizer.MAX_STRING_LENGTH) {
            return text;
        }
        budget.truncated = true;
        return text.substring(0, MapSanitizer.MAX_STRING_LENGTH - TRUNCATION_MARKER.length())
                + TRUNCATION_MARKER;
    }

    /** Reproduz exatamente as recusas de chave da politica estrita. */
    private static boolean isStorableKey(String key) {
        return !key.isBlank()
                && key.charAt(0) != '$'
                && key.charAt(0) != '_'
                && key.length() <= MapSanitizer.MAX_KEY_LENGTH;
    }

    /** Contabilidade do que ainda cabe e do que ja se perdeu. */
    private static final class Budget {

        private final int maxDepth;
        private int remainingEntries;
        private int dropped;
        private boolean truncated;

        private Budget(int maxEntries, int maxDepth) {
            this.remainingEntries = maxEntries;
            this.maxDepth = maxDepth;
        }

        private boolean spendEntry() {
            if (remainingEntries <= 0) {
                truncated = true;
                return false;
            }
            remainingEntries--;
            return true;
        }

        private void refundEntry() {
            remainingEntries++;
        }
    }
}
