package com.nexio.workflow.application.engine;

import com.nexio.workflow.domain.model.MapSanitizer;
import com.nexio.workflow.domain.model.Placeholders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import org.springframework.web.util.UriUtils;

/**
 * Substitui os marcadores {@code {{...}}} pelos valores da execucao em curso.
 *
 * <p>Duas origens, e sao as mesmas que uma condicao enxerga -- "o campo X do evento" e "o que o no
 * anterior devolveu" sao tudo o que um no tem para se parametrizar:</p>
 *
 * <pre>{@code
 * {{trigger.pedidoId}}                 // payload do gatilho
 * {{trigger.cliente.plano}}            // caminho pontilhado
 * {{steps.consulta.body.total}}        // saida de um no ja executado
 * }</pre>
 *
 * <h2>Tres decisoes que valem mais do que parecem</h2>
 *
 * <p><b>1. Campo ausente falha o no.</b> Nao vira texto vazio. Substituir por vazio produziria uma
 * requisicao para {@code https://api.exemplo.test/pedidos/} -- um endereco diferente do pretendido,
 * possivelmente uma listagem inteira em vez de um item, e possivelmente um {@code DELETE} nela. A
 * mesma logica que faz uma condicao que nao avalia falhar em vez de virar o ramo falso: erro de
 * configuracao nao pode virar uma acao que parece deliberada.</p>
 *
 * <p><b>2. A substituicao e de uma passada so.</b> Um valor que por acaso contenha {@code {{x}}}
 * <b>nao</b> e resolvido de novo. Reprocessar ate nao sobrar chave e a implementacao ingenua e e
 * uma vulnerabilidade: o payload do gatilho vem de fora, entao quem dispara escolheria o texto que
 * vira marcador na segunda passada e leria o que quisesse do contexto -- injecao de template
 * classica. Uma passada fecha isso por construcao.</p>
 *
 * <p><b>3. Na URL o valor e codificado; fora dela, nao.</b> Sem codificacao,
 * {@code {{trigger.id}}} valendo {@code ../../admin} sobe caminho e {@code &admin=true} acrescenta
 * parametro -- em ambos os casos o destino final deixa de ser o que o autor escreveu, dentro de um
 * host que a validacao aprovou. A codificacao escolhida depende de onde o marcador esta: segmento
 * de caminho antes do {@code ?}, parametro de consulta depois. Em cabecalho e corpo nao ha
 * codificacao porque nao ha gramatica a proteger: o corpo vira JSON pelo serializador e o cabecalho
 * ja recusa caractere de controle.</p>
 *
 * <p>No corpo, um valor que e <b>exatamente</b> um marcador preserva o tipo: {@code {{trigger.total}}}
 * com {@code total} igual a {@code 150} manda o numero {@code 150}, e nao a string {@code "150"}.
 * Um marcador no meio de texto vira texto, que e a unica coisa que faz sentido ali.</p>
 */
public final class TemplateResolver {

    /** Prefixo que enderecaa o payload do gatilho. */
    private static final String TRIGGER = "trigger";

    /** Prefixo que enderecaa a saida dos nos ja executados. */
    private static final String STEPS = "steps";

    /**
     * Teto do texto resolvido.
     *
     * <p>Cada marcador pode trazer ate {@value MapSanitizer#MAX_STRING_LENGTH} caracteres, e o
     * numero de marcadores ja e limitado na escrita. Este teto e o anteparo do produto dos dois,
     * e vale tambem para as definicoes gravadas antes de a regra de contagem existir.</p>
     */
    public static final int MAX_RESOLVED_LENGTH = 8192;

    private TemplateResolver() {
        throw new AssertionError("Classe utilitaria nao deve ser instanciada");
    }

    /**
     * Falha de resolucao: marcador que nao encontra valor, ou resultado grande demais.
     *
     * <p>E {@code RuntimeException} propria para que o executor a distinga de uma falha de rede e
     * componha uma mensagem que diz qual marcador nao resolveu -- que e a unica informacao util
     * para quem vai corrigir o workflow.</p>
     */
    public static class TemplateResolutionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Cria a excecao.
         *
         * @param message motivo da falha
         */
        public TemplateResolutionException(String message) {
            super(message);
        }
    }

    /**
     * Resolve os marcadores de uma URL, codificando cada valor conforme a posicao.
     *
     * @param template URL possivelmente com marcadores
     * @param context  estado da execucao
     * @return URL resolvida
     * @throws TemplateResolutionException quando um marcador nao encontra valor ou o resultado
     *                                     passa de {@value #MAX_RESOLVED_LENGTH} caracteres
     */
    public static String resolveUrl(String template, NodeExecutionContext context) {
        if (!Placeholders.present(template)) {
            return template;
        }
        int queryStart = template.indexOf('?');
        Matcher matcher = Placeholders.PLACEHOLDER.matcher(template);
        StringBuilder resolved = new StringBuilder(template.length());
        int last = 0;
        while (matcher.find()) {
            resolved.append(template, last, matcher.start());
            String value = String.valueOf(lookup(matcher.group(1), context));
            boolean inQuery = queryStart >= 0 && matcher.start() > queryStart;
            resolved.append(inQuery
                    ? UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8)
                    : UriUtils.encodePathSegment(value, StandardCharsets.UTF_8));
            last = matcher.end();
        }
        resolved.append(template, last, template.length());
        return requireWithinLimit(resolved.toString());
    }

    /**
     * Resolve os marcadores de um mapa livre (cabecalhos, corpo), sem codificar.
     *
     * @param source  mapa possivelmente com marcadores
     * @param context estado da execucao
     * @return copia com os marcadores resolvidos
     * @throws TemplateResolutionException quando um marcador nao encontra valor
     */
    public static Map<String, Object> resolveValues(Map<String, Object> source, NodeExecutionContext context) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), context));
        }
        return resolved;
    }

    private static Object resolveValue(Object value, NodeExecutionContext context) {
        return switch (value) {
            case null -> null;
            case String text -> resolveText(text, context);
            case Map<?, ?> map -> resolveNestedMap(map, context);
            case List<?> list -> resolveList(list, context);
            default -> value;
        };
    }

    private static Map<String, Object> resolveNestedMap(Map<?, ?> source, NodeExecutionContext context) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            resolved.put(String.valueOf(entry.getKey()), resolveValue(entry.getValue(), context));
        }
        return resolved;
    }

    private static List<Object> resolveList(List<?> source, NodeExecutionContext context) {
        List<Object> resolved = new ArrayList<>(source.size());
        for (Object item : source) {
            resolved.add(resolveValue(item, context));
        }
        return resolved;
    }

    /**
     * Resolve um texto, preservando o tipo quando ele e exatamente um marcador.
     *
     * <p>{@code {{trigger.total}}} sozinho manda o numero; {@code "pedido {{trigger.id}}"} manda
     * texto. Sem isso, todo campo numerico do corpo chegaria ao servico de destino como string, e a
     * validacao do outro lado recusaria.</p>
     */
    private static Object resolveText(String text, NodeExecutionContext context) {
        Matcher whole = Placeholders.PLACEHOLDER.matcher(text);
        if (whole.matches()) {
            return lookup(whole.group(1), context);
        }
        Matcher matcher = Placeholders.PLACEHOLDER.matcher(text);
        StringBuilder resolved = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            resolved.append(text, last, matcher.start())
                    .append(String.valueOf(lookup(matcher.group(1), context)));
            last = matcher.end();
        }
        if (last == 0) {
            return text;
        }
        resolved.append(text, last, text.length());
        return requireWithinLimit(resolved.toString());
    }

    /**
     * Busca o valor de um caminho pontilhado.
     *
     * <p>{@code trigger.a.b} desce pelo payload; {@code steps.no.a.b} desce pela saida do no.</p>
     */
    private static Object lookup(String rawPath, NodeExecutionContext context) {
        String path = rawPath.trim();
        String[] parts = path.split("\\.");
        if (parts.length < 2) {
            throw new TemplateResolutionException("marcador '" + path
                    + "' precisa comecar com 'trigger.' ou 'steps.<no>.'");
        }
        Object current;
        int from;
        if (TRIGGER.equals(parts[0])) {
            current = context.triggerPayload();
            from = 1;
        } else if (STEPS.equals(parts[0])) {
            if (parts.length < 3) {
                throw new TemplateResolutionException("marcador '" + path
                        + "' precisa nomear o no e o campo, como 'steps.<no>.<campo>'");
            }
            current = context.outputOf(parts[1]);
            from = 2;
        } else {
            throw new TemplateResolutionException("marcador '" + path
                    + "' precisa comecar com 'trigger.' ou 'steps.<no>.'");
        }
        for (int i = from; i < parts.length; i++) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(parts[i])) {
                throw new TemplateResolutionException("marcador '" + path + "' nao encontrou valor");
            }
            current = map.get(parts[i]);
        }
        if (current == null) {
            throw new TemplateResolutionException("marcador '" + path + "' encontrou valor nulo");
        }
        if (current instanceof Map || current instanceof List) {
            throw new TemplateResolutionException("marcador '" + path
                    + "' aponta para um objeto e nao para um valor simples");
        }
        return current;
    }

    private static String requireWithinLimit(String resolved) {
        if (resolved.length() > MAX_RESOLVED_LENGTH) {
            throw new TemplateResolutionException("o texto resolvido passou de "
                    + MAX_RESOLVED_LENGTH + " caracteres: " + resolved.length());
        }
        return resolved;
    }
}
