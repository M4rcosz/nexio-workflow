package com.nexio.workflow.domain.model;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sintaxe dos marcadores {@code {{...}}} e as regras de escrita que valem sobre eles.
 *
 * <p>Fica no dominio porque a validacao de escrita precisa enxergar o marcador: o
 * {@link WorkflowDefinition} recusa marcador no lugar errado da URL, e o dominio nao valida o que
 * nao conhece. A <i>resolucao</i> mora na camada de aplicacao, que e quem tem o estado da
 * execucao.</p>
 *
 * <h2>Por que o marcador nao pode estar no host</h2>
 *
 * <p>Este e o ponto onde o templating encosta na protecao contra SSRF. A validacao de escrita
 * resolve o host e recusa endereco interno; um host templatizado
 * ({@code https://{{trigger.host}}/x}) so existe no disparo, entao <b>nao ha o que validar na
 * escrita</b> -- a checagem inteira teria de ser adiada.</p>
 *
 * <p>Adiar nao seria inseguro: a validacao de execucao e obrigatoria e agora fixa a conexao no
 * endereco aprovado. Mas custaria duas coisas concretas. A primeira e a resposta ao autor: hoje
 * quem escreve um destino interno descobre no {@code createWorkflow}, e nao num disparo qualquer
 * semanas depois. A segunda e auditoria: com o host fixo, ler a definicao gravada diz para quais
 * hosts aquele workflow fala. Com host templatizado, a resposta passa a ser "depende do evento", e
 * nenhuma consulta ao banco recupera essa informacao.</p>
 *
 * <p>Caminho e consulta templatizados nao tem nenhum desses problemas: o destino continua sendo o
 * mesmo host, validado na escrita e revalidado no disparo.</p>
 */
public final class Placeholders {

    /**
     * Um marcador: {@code {{ caminho.pontilhado }}}.
     *
     * <p>O conteudo e {@code [^{}]*} e nao {@code .*}: sem isso {@code {{a}}b{{c}}} casaria como um
     * marcador so, do primeiro {@code {{} ao ultimo {@code }}}. Tambem nao ha quantificador
     * aninhado, entao o casamento e linear no tamanho do texto -- o mesmo cuidado que tirou
     * {@code matches} da gramatica das condicoes.</p>
     */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)\\}\\}");

    /**
     * Teto de marcadores num unico texto.
     *
     * <p>Cada marcador vira uma substituicao por um valor que pode ter ate
     * {@value MapSanitizer#MAX_STRING_LENGTH} caracteres, entao sem teto o texto resolvido cresce
     * pelo produto dos dois. O limite e generoso para uso legitimo e fecha a multiplicacao.</p>
     */
    public static final int MAX_PLACEHOLDERS = 20;

    private Placeholders() {
        throw new AssertionError("Classe utilitaria nao deve ser instanciada");
    }

    /**
     * Informa se o texto contem ao menos um marcador.
     *
     * @param value texto a inspecionar, pode ser nulo
     * @return {@code true} quando ha marcador
     */
    public static boolean present(String value) {
        return value != null && PLACEHOLDER.matcher(value).find();
    }

    /**
     * Conta os marcadores do texto.
     *
     * @param value texto a inspecionar, pode ser nulo
     * @return quantidade de marcadores
     */
    public static int count(String value) {
        if (value == null) {
            return 0;
        }
        Matcher matcher = PLACEHOLDER.matcher(value);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Recusa marcador antes do fim da autoridade da URL, e recusa excesso de marcadores.
     *
     * <p>A conferencia e textual e nao usa {@link URI}: {@code https://{{trigger.host}}/x} nem
     * chega a ser um URI valido, entao perguntar ao parser onde termina a autoridade nao funciona
     * justamente no caso que precisa ser recusado. O corte e o primeiro {@code /} depois de
     * {@code ://}, ou o fim do texto quando nao ha caminho.</p>
     *
     * @param url    valor do campo {@code url} do no
     * @param nodeId identificador do no, usado so para localizar o erro
     * @throws IllegalArgumentException quando ha marcador no esquema, no host ou na porta, ou
     *                                  quando ha mais de {@value #MAX_PLACEHOLDERS} marcadores
     */
    public static void validateUrlTemplate(String url, String nodeId) {
        if (url == null) {
            return;
        }
        int placeholders = count(url);
        if (placeholders > MAX_PLACEHOLDERS) {
            throw new IllegalArgumentException("url do no '" + nodeId + "' tem " + placeholders
                    + " marcadores e o maximo e " + MAX_PLACEHOLDERS);
        }
        if (placeholders == 0) {
            return;
        }
        int authorityEnd = authorityEnd(url);
        Matcher matcher = PLACEHOLDER.matcher(url);
        if (matcher.find() && matcher.start() < authorityEnd) {
            throw new IllegalArgumentException("url do no '" + nodeId
                    + "' usa marcador no esquema, no host ou na porta. O marcador so e permitido no"
                    + " caminho e na consulta: com o host fixo, o destino e validado contra SSRF na"
                    + " escrita e a definicao gravada mostra com quais hosts o workflow fala");
        }
    }

    /**
     * Recusa excesso de marcadores num texto qualquer (cabecalho, corpo).
     *
     * @param value  texto a conferir
     * @param field  nome logico do campo, usado na mensagem
     * @param nodeId identificador do no
     * @throws IllegalArgumentException quando ha mais de {@value #MAX_PLACEHOLDERS} marcadores
     */
    public static void validatePlaceholderCount(String value, String field, String nodeId) {
        int placeholders = count(value);
        if (placeholders > MAX_PLACEHOLDERS) {
            throw new IllegalArgumentException(field + " do no '" + nodeId + "' tem " + placeholders
                    + " marcadores e o maximo e " + MAX_PLACEHOLDERS);
        }
    }

    /**
     * Posicao onde a autoridade termina: o primeiro {@code /} depois de {@code ://}, ou o fim.
     *
     * @param url texto da URL
     * @return indice do primeiro caractere que ja nao pertence a autoridade
     */
    public static int authorityEnd(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            // Sem "://" nao da para separar autoridade de caminho, e um marcador em qualquer
            // posicao poderia acabar no host. Trata o texto inteiro como autoridade, ou seja,
            // recusa qualquer marcador -- errar para o lado de recusar custa uma mensagem, errar
            // para o outro custa a validacao de destino.
            return url.length();
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? url.length() : pathStart;
    }
}
