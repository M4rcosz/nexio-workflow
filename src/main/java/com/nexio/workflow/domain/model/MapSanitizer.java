package com.nexio.workflow.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Utilitario interno do dominio que valida e copia mapas vindos de fontes nao confiaveis
 * (payload de gatilho, config de no, config de trigger e output de passo).
 *
 * <p>Regras aplicadas recursivamente:</p>
 * <ul>
 *   <li>a chave {@code _class} e rejeitada: o Spring Data honra esse hint na leitura e faria
 *       {@code Class.forName} sobre um valor controlado por terceiros;</li>
 *   <li>chaves iniciadas por {@code $} ou contendo {@code .} sao rejeitadas, pois o MongoDB as
 *       trata como operadores ou caminhos e habilitam injecao de operador;</li>
 *   <li>chaves precisam casar com {@code ^[A-Za-z0-9_][A-Za-z0-9_-]{0,63}$};</li>
 *   <li>no maximo {@value #MAX_ENTRIES} entradas no total e {@value #MAX_DEPTH} niveis de aninhamento;</li>
 *   <li>somente {@code Map}, {@code List}, {@code String}, {@code Number}, {@code Boolean},
 *       {@code Date}, {@code Instant} e {@code null} sao aceitos como valores;</li>
 *   <li>mapas e listas aninhados sao copiados em profundidade e devolvidos imutaveis, de modo que
 *       o chamador nao consiga alterar a estrutura depois da validacao.</li>
 * </ul>
 */
final class MapSanitizer {

    /** Numero maximo de entradas somando todos os niveis da estrutura. */
    static final int MAX_ENTRIES = 200;

    /** Profundidade maxima de aninhamento de mapas e listas. */
    static final int MAX_DEPTH = 10;

    private static final String CLASS_HINT_KEY = "_class";

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_-]{0,63}$");

    private MapSanitizer() {
        throw new AssertionError("Classe utilitaria nao deve ser instanciada");
    }

    /**
     * Valida e copia em profundidade um mapa nao confiavel.
     *
     * @param source mapa de origem, pode ser nulo
     * @param path   nome logico do campo, usado nas mensagens de erro
     * @return copia imutavel e validada, ou mapa vazio quando a origem e nula
     * @throws IllegalArgumentException quando alguma regra de validacao e violada
     */
    static Map<String, Object> sanitize(Map<String, Object> source, String path) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return copyMap(source, path, 1, new int[1]);
    }

    private static Map<String, Object> copyMap(Map<?, ?> source, String path, int depth, int[] entryCount) {
        checkDepth(path, depth);
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = validateKey(entry.getKey(), path);
            String childPath = path.isEmpty() ? key : path + "." + key;
            countEntry(childPath, entryCount);
            copy.put(key, copyValue(entry.getValue(), childPath, depth, entryCount));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<Object> copyList(List<?> source, String path, int depth, int[] entryCount) {
        checkDepth(path, depth);
        List<Object> copy = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            String childPath = path + "[" + i + "]";
            countEntry(childPath, entryCount);
            copy.add(copyValue(source.get(i), childPath, depth, entryCount));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Object copyValue(Object value, String path, int depth, int[] entryCount) {
        return switch (value) {
            case null -> null;
            case Map<?, ?> map -> copyMap(map, path, depth + 1, entryCount);
            case List<?> list -> copyList(list, path, depth + 1, entryCount);
            case String s -> s;
            case Number n -> n;
            case Boolean b -> b;
            case Date d -> new Date(d.getTime());
            case Instant i -> i;
            default -> throw new IllegalArgumentException(
                    "Valor de tipo nao suportado em '" + path + "': " + value.getClass().getName()
                            + ". Tipos aceitos: Map, List, String, Number, Boolean, Date, Instant ou null");
        };
    }

    private static String validateKey(Object rawKey, String path) {
        if (!(rawKey instanceof String key)) {
            throw new IllegalArgumentException(
                    "Chave nao textual em '" + path + "': "
                            + (rawKey == null ? "null" : rawKey.getClass().getName()));
        }
        String childPath = path.isEmpty() ? key : path + "." + key;
        if (CLASS_HINT_KEY.equals(key)) {
            throw new IllegalArgumentException(
                    "Chave reservada '_class' nao e permitida em '" + childPath + "'");
        }
        if (key.startsWith("$")) {
            throw new IllegalArgumentException(
                    "Chave nao pode comecar com '$' em '" + childPath + "'");
        }
        if (key.indexOf('.') >= 0) {
            throw new IllegalArgumentException(
                    "Chave nao pode conter '.' em '" + childPath + "'");
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Chave invalida em '" + childPath + "': deve casar com ^[A-Za-z0-9_][A-Za-z0-9_-]{0,63}$");
        }
        return key;
    }

    private static void countEntry(String path, int[] entryCount) {
        entryCount[0]++;
        if (entryCount[0] > MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "Limite de " + MAX_ENTRIES + " entradas excedido em '" + path + "'");
        }
    }

    private static void checkDepth(String path, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "Limite de " + MAX_DEPTH + " niveis de aninhamento excedido em '" + path + "'");
        }
    }
}
