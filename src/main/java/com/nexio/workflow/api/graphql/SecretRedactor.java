package com.nexio.workflow.api.graphql;

import com.nexio.workflow.domain.model.MapSanitizer;
import java.net.URI;
import java.net.URISyntaxException;
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
 *
 * <p><b>Nome de chave nao e a unica forma de guardar credencial.</b> A chave {@code url} e
 * inofensiva e o valor dela nao e: {@code https://api.exemplo.test/v1?api_key=...} entrega a chave
 * de API por dentro de um campo que nenhuma regra de nome pega, e {@code https://user:senha@host/}
 * entrega o par inteiro. Por isso todo valor textual passa por {@link #redactUrl(String)}, que
 * quando o texto e um URI mascara o {@code userinfo} e o valor de cada parametro de consulta cujo
 * <b>nome</b> case com o mesmo padrao das chaves. So o trecho ofensivo sai: o restante do endereco
 * continua legivel, que e o que torna a resposta util para diagnostico.</p>
 *
 * <p><b>O que deliberadamente nao existe aqui e heuristica de formato de segredo</b> -- procurar
 * prefixos como {@code sk_live_} ou {@code AKIA}, ou medir entropia do valor. Essa familia de regra
 * so acerta os provedores que alguem lembrou de listar, envelhece a cada troca de formato, e o custo
 * do erro e assimetrico no sentido ruim: um campo legitimo de alta entropia (um hash de corpo, um id
 * de correlacao, um payload em base64) sairia mascarado numa resposta que o usuario precisa ler. O
 * criterio continua sendo o nome -- da chave ou do parametro --, que e declarado por quem escreveu a
 * config e nao adivinhado.</p>
 */
public final class SecretRedactor {

    /**
     * Marcador fixo que substitui o valor de uma chave sensivel.
     *
     * <p>A constante vive no {@link MapSanitizer}, no dominio, e nao aqui: a validacao de escrita
     * precisa recusar este texto como valor, e o dominio nao pode importar da camada de API. Aqui
     * fica so o apelido, para que a leitura continue se referindo a ele pelo nome do lugar onde e
     * aplicado.</p>
     */
    public static final String REDACTED = MapSanitizer.REDACTED_MARKER;

    /**
     * Chaves consideradas sensiveis, em dois grupos com regras de casamento diferentes.
     *
     * <p>O primeiro grupo casa em qualquer posicao, que e o que faz {@code X-Api-Key},
     * {@code clientSecret} e {@code Set-Cookie} serem pegos: sao termos longos o bastante para que
     * aparecer no meio de outra palavra ja signifique credencial.</p>
     *
     * <p>O segundo grupo -- {@code sig}, {@code pin}, {@code pwd} -- so casa quando delimitado. Com
     * casamento por conteudo, {@code sig} pega {@code design} e {@code pin} pega {@code mapping} e
     * {@code spinner}, e nenhum deles e credencial. Isso nao e o erro barato que a regra de nome
     * assume em outros pontos: desde que a escrita recusa o marcador, um campo redigido por engano
     * deixa de ser so uma resposta menos informativa e passa a quebrar a proxima escrita do cliente
     * que devolva o objeto inteiro. O delimitador e {@code [^a-z0-9]}, e nao {@code \b}, porque
     * {@code _} conta como caractere de palavra na expressao regular e {@code session_pin} precisa
     * casar.</p>
     */
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "authorization|api[-_]?key|access[-_]?key|private[-_]?key|token|secret|password|passwd"
                    + "|credential|cookie|set-cookie|bearer|signature|session[-_]?id"
                    + "|(?<![a-z0-9])(?:sig|pin|pwd)(?![a-z0-9])",
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
            case String text -> redactUrl(text);
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

    /**
     * Mascara as partes de um endereco que podem carregar credencial.
     *
     * <p>Trabalha sobre o texto original, recortado pelas posicoes que o {@link URI} identificou, em
     * vez de remontar o endereco a partir dos componentes: remontar reescreveria a codificacao
     * percentual do que nao foi tocado, e a resposta deixaria de ser o que esta gravado.</p>
     *
     * <p>Texto que nao e endereco sai inalterado, e isso cobre a maioria dos valores de uma config
     * ({@code POST}, {@code application/json}, um corpo qualquer): sem {@code userinfo} e sem
     * consulta nao ha o que mascarar, e um texto que o {@link URI} nem consegue analisar volta como
     * veio.</p>
     */
    private static String redactUrl(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return value;
        }
        String result = value;
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null) {
            int start = result.indexOf('?') + 1;
            result = result.substring(0, start) + redactQuery(rawQuery)
                    + result.substring(start + rawQuery.length());
        }
        String rawUserInfo = uri.getRawUserInfo();
        int userInfoStart = rawUserInfo == null ? -1 : result.indexOf(rawUserInfo + "@");
        if (userInfoStart >= 0) {
            result = result.substring(0, userInfoStart) + REDACTED
                    + result.substring(userInfoStart + rawUserInfo.length());
        }
        return result;
    }

    /**
     * Mascara o valor de cada parametro cujo nome indique credencial, preservando os demais.
     *
     * <p>O parametro sem {@code =} fica como esta: nao ha valor para mascarar, e o nome sozinho e o
     * que o usuario escreveu.</p>
     */
    private static String redactQuery(String rawQuery) {
        String[] parameters = rawQuery.split("&", -1);
        StringBuilder redacted = new StringBuilder(rawQuery.length());
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                redacted.append('&');
            }
            String parameter = parameters[i];
            int separator = parameter.indexOf('=');
            if (separator >= 0 && isSensitive(parameter.substring(0, separator))) {
                redacted.append(parameter, 0, separator + 1).append(REDACTED);
            } else {
                redacted.append(parameter);
            }
        }
        return redacted.toString();
    }
}
