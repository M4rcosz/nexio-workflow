package com.nexio.workflow.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utilitario do dominio para os mapas livres do modelo (payload de gatilho, config de no, config de
 * trigger e output de passo).
 *
 * <p>Sao duas operacoes com politicas deliberadamente diferentes, porque leitura e escrita tem
 * requisitos opostos:</p>
 *
 * <ul>
 *   <li>{@link #copy(Map, String)} e <b>leniente</b> e roda na hidratacao. Os construtores
 *       compactos dos records ({@link WorkflowNode}, {@link TriggerConfig},
 *       {@link ExecutionStep}) executam quando o Spring Data instancia o documento vindo do
 *       MongoDB, entao qualquer regra estrita ali derruba a leitura: um unico documento malformado
 *       faria {@code findById} estourar e, pior, faria toda consulta de lista falhar em bloco, sem
 *       nenhum caminho pela aplicacao para ler ou corrigir o registro. Esta operacao so copia em
 *       profundidade, normaliza datas e mantem guardas estruturais generosas contra entrada
 *       patologica.</li>
 *   <li>{@link #validate(Map, String)} e <b>estrita</b> e roda apenas na escrita, a partir dos
 *       callbacks de persistencia. E o ponto onde o dado nao confiavel entra no sistema, e o unico
 *       lugar onde vale rejeitar.</li>
 * </ul>
 *
 * <p>Regras da validacao estrita, aplicadas recursivamente:</p>
 * <ul>
 *   <li>chaves nao podem comecar com {@code $} (injecao de operador do MongoDB) nem com {@code _}
 *       (cobre {@code _class}, que o Spring Data honraria como hint de tipo na leitura, e
 *       {@code _id} na mesma regra);</li>
 *   <li>pontos sao aceitos: o {@code MongoConfig} configura {@code setMapKeyDotReplacement}, que
 *       existe exatamente para tornar chaves com ponto seguras de gravar;</li>
 *   <li>chave com no maximo {@value #MAX_KEY_LENGTH} caracteres e valor texto com no maximo
 *       {@value #MAX_STRING_LENGTH} caracteres;</li>
 *   <li>no maximo {@value #MAX_ENTRIES} entradas somando todos os niveis e {@value #MAX_DEPTH}
 *       niveis de aninhamento;</li>
 *   <li>somente {@code Map}, {@code List}, {@code String}, {@code Boolean}, {@code Date},
 *       {@code Instant}, {@code null} e os numeros imutaveis com codec BSON
 *       ({@code Integer}, {@code Long}, {@code Double}, {@code Float}, {@code Short},
 *       {@code Byte} e {@code BigDecimal}) sao aceitos como valores. O tipo {@code Number} aberto
 *       nao serve: {@code AtomicInteger} e {@code AtomicLong} sao {@code Number}, sao mutaveis
 *       (o que anularia a copia defensiva) e {@code BigInteger} nao tem codec, entao passariam na
 *       validacao para so quebrar depois, dentro do {@code save()}.</li>
 * </ul>
 */
public final class MapSanitizer {

    /** Numero maximo de entradas, somando todos os niveis, aceito na validacao estrita. */
    public static final int MAX_ENTRIES = 200;

    /** Profundidade maxima de aninhamento aceita na validacao estrita. */
    public static final int MAX_DEPTH = 10;

    /** Tamanho maximo de uma chave na validacao estrita. */
    public static final int MAX_KEY_LENGTH = 128;

    /** Tamanho maximo de um valor textual na validacao estrita. */
    public static final int MAX_STRING_LENGTH = 4096;

    /**
     * Profundidade maxima tolerada na copia leniente. E o limite de aninhamento do proprio BSON,
     * entao nenhum documento que o MongoDB consiga devolver esbarra nele.
     */
    static final int COPY_MAX_DEPTH = 100;

    /**
     * Numero maximo de entradas tolerado na copia leniente. Guarda estrutural contra entrada
     * patologica, folgada o bastante para qualquer documento legitimo de ate 16MB.
     */
    static final int COPY_MAX_ENTRIES = 200_000;

    /** Tamanho maximo de chave interpolada em mensagem de erro. */
    private static final int MAX_KEY_IN_MESSAGE = 64;

    /** Tipos escalares aceitos pela validacao estrita, todos imutaveis e com codec BSON. */
    private static final Set<Class<?>> ALLOWED_SCALARS = Set.of(
            Boolean.class,
            Integer.class,
            Long.class,
            Double.class,
            Float.class,
            Short.class,
            Byte.class,
            BigDecimal.class,
            Date.class,
            Instant.class);

    private MapSanitizer() {
        throw new AssertionError("Classe utilitaria nao deve ser instanciada");
    }

    /**
     * Copia em profundidade um mapa livre, sem aplicar regra de negocio.
     *
     * <p>Aceita qualquer valor representavel em BSON, inclusive tipos que a validacao estrita
     * rejeita ({@code org.bson.types.Decimal128}, {@code Binary}, {@code ObjectId}), justamente
     * para nunca falhar em cima de algo que o MongoDB devolveu. Datas sao normalizadas para
     * {@link Instant}: {@link Date} e mutavel e escaparia por referencia pelos getters, anulando
     * a copia defensiva.</p>
     *
     * @param source mapa de origem, pode ser nulo
     * @param path   nome logico do campo, usado nas mensagens de erro
     * @return copia imutavel em profundidade, ou mapa vazio quando a origem e nula
     * @throws IllegalArgumentException apenas quando a estrutura e patologica (mais de
     *         {@value #COPY_MAX_DEPTH} niveis ou {@value #COPY_MAX_ENTRIES} entradas)
     */
    public static Map<String, Object> copy(Map<String, Object> source, String path) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return copyMap(source, path, 1, new int[1]);
    }

    /**
     * Aplica a politica estrita sobre um mapa livre. Nao altera nada: so aceita ou rejeita.
     *
     * @param source mapa a validar, pode ser nulo
     * @param path   nome logico do campo, usado nas mensagens de erro
     * @throws IllegalArgumentException quando alguma regra de chave, tipo, tamanho ou limite e violada
     */
    public static void validate(Map<String, Object> source, String path) {
        if (source == null || source.isEmpty()) {
            return;
        }
        validateMap(source, path, 1, new int[1]);
    }

    private static Map<String, Object> copyMap(Map<?, ?> source, String path, int depth, int[] entryCount) {
        checkLimit(depth <= COPY_MAX_DEPTH, "Limite de " + COPY_MAX_DEPTH + " niveis de aninhamento", path);
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String childPath = childPath(path, key);
            countCopyEntry(childPath, entryCount);
            copy.put(key, copyValue(entry.getValue(), childPath, depth, entryCount));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<Object> copyList(List<?> source, String path, int depth, int[] entryCount) {
        checkLimit(depth <= COPY_MAX_DEPTH, "Limite de " + COPY_MAX_DEPTH + " niveis de aninhamento", path);
        List<Object> copy = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            String childPath = path + "[" + i + "]";
            countCopyEntry(childPath, entryCount);
            copy.add(copyValue(source.get(i), childPath, depth, entryCount));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Object copyValue(Object value, String path, int depth, int[] entryCount) {
        return switch (value) {
            case null -> null;
            case Map<?, ?> map -> copyMap(map, path, depth + 1, entryCount);
            case List<?> list -> copyList(list, path, depth + 1, entryCount);
            case Date date -> date.toInstant();
            default -> value;
        };
    }

    private static void validateMap(Map<?, ?> source, String path, int depth, int[] entryCount) {
        checkLimit(depth <= MAX_DEPTH, "Limite de " + MAX_DEPTH + " niveis de aninhamento", path);
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = validateKey(entry.getKey(), path);
            String childPath = childPath(path, key);
            countEntry(childPath, entryCount);
            validateValue(entry.getValue(), childPath, depth, entryCount);
        }
    }

    private static void validateList(List<?> source, String path, int depth, int[] entryCount) {
        checkLimit(depth <= MAX_DEPTH, "Limite de " + MAX_DEPTH + " niveis de aninhamento", path);
        for (int i = 0; i < source.size(); i++) {
            String childPath = path + "[" + i + "]";
            countEntry(childPath, entryCount);
            validateValue(source.get(i), childPath, depth, entryCount);
        }
    }

    private static void validateValue(Object value, String path, int depth, int[] entryCount) {
        switch (value) {
            case null -> {
                // null e um valor aceito
            }
            case Map<?, ?> map -> validateMap(map, path, depth + 1, entryCount);
            case List<?> list -> validateList(list, path, depth + 1, entryCount);
            case String text -> validateString(text, path);
            default -> validateScalar(value, path);
        }
    }

    /**
     * Exige que o escalar seja exatamente um dos tipos aceitos. A comparacao e por classe exata, e
     * nao por {@code instanceof}: subclasses mutaveis de {@link Date} (por exemplo
     * {@code java.sql.Timestamp}) e numeros sem codec ficariam de fora do contrato ao passar por
     * {@code instanceof}, mas seriam aceitos aqui.
     */
    private static void validateScalar(Object value, String path) {
        if (!ALLOWED_SCALARS.contains(value.getClass())) {
            throw new IllegalArgumentException(
                    "Valor de tipo nao suportado em '" + path + "': " + value.getClass().getName()
                            + ". Tipos aceitos: Map, List, String, Boolean, Integer, Long, Double, Float, "
                            + "Short, Byte, BigDecimal, Date, Instant ou null");
        }
    }

    private static void validateString(String value, String path) {
        if (value.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(
                    "Valor textual em '" + path + "' excede " + MAX_STRING_LENGTH
                            + " caracteres: " + value.length());
        }
    }

    private static String validateKey(Object rawKey, String path) {
        if (!(rawKey instanceof String key)) {
            throw new IllegalArgumentException(
                    "Chave nao textual em '" + path + "': "
                            + (rawKey == null ? "null" : rawKey.getClass().getName()));
        }
        String childPath = childPath(path, key);
        if (key.isBlank()) {
            throw new IllegalArgumentException("Chave em branco em '" + path + "'");
        }
        if (key.charAt(0) == '$') {
            throw new IllegalArgumentException(
                    "Chave nao pode comecar com '$' em '" + childPath + "'");
        }
        if (key.charAt(0) == '_') {
            throw new IllegalArgumentException(
                    "Chave nao pode comecar com '_' em '" + childPath + "'");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Chave em '" + childPath + "' excede " + MAX_KEY_LENGTH + " caracteres: " + key.length());
        }
        return key;
    }

    private static void countEntry(String path, int[] entryCount) {
        entryCount[0]++;
        checkLimit(entryCount[0] <= MAX_ENTRIES, "Limite de " + MAX_ENTRIES + " entradas", path);
    }

    private static void countCopyEntry(String path, int[] entryCount) {
        entryCount[0]++;
        checkLimit(entryCount[0] <= COPY_MAX_ENTRIES, "Limite de " + COPY_MAX_ENTRIES + " entradas", path);
    }

    private static void checkLimit(boolean satisfied, String limitDescription, String path) {
        if (!satisfied) {
            throw new IllegalArgumentException(limitDescription + " excedido em '" + path + "'");
        }
    }

    /**
     * Monta o caminho de um filho higienizando a chave: chaves vem de fonte externa e sao
     * interpoladas em mensagens de erro que terminam em log, entao uma chave como
     * {@code "$x\n2026-08-06 ERROR [audit] falso"} forjaria linhas de log inteiras.
     */
    private static String childPath(String path, String key) {
        String safeKey = describeKey(key);
        return path.isEmpty() ? safeKey : path + "." + safeKey;
    }

    private static String describeKey(String key) {
        int limit = Math.min(key.length(), MAX_KEY_IN_MESSAGE);
        StringBuilder safe = new StringBuilder(limit + 3);
        for (int i = 0; i < limit; i++) {
            char current = key.charAt(i);
            safe.append(Character.isISOControl(current) ? '?' : current);
        }
        if (key.length() > limit) {
            safe.append("...");
        }
        return safe.toString();
    }
}
