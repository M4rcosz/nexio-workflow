package com.nexio.workflow.infrastructure.http;

import com.nexio.workflow.infrastructure.http.HttpTargetNotAllowedException.Reason;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guarda de SSRF para as requisicoes de saida disparadas por um no de workflow.
 *
 * <p>Um no {@code HTTP_REQUEST} recebe a URL de quem monta o workflow, ou seja, de fonte nao
 * confiavel. Sem esta checagem o servico se torna um proxy para a rede onde ele roda: o alvo
 * classico e {@code http://169.254.169.254/latest/meta-data/iam/security-credentials/} (metadados
 * da nuvem, que devolvem credenciais), mas {@code 127.0.0.1}, {@code 10.x}, {@code 192.168.x},
 * {@code 172.16-31.x}, {@code ::1} e {@code fc00::/7} expoem igualmente servicos internos sem
 * autenticacao.</p>
 *
 * <p>Regras aplicadas, nesta ordem:</p>
 * <ol>
 *   <li>esquema na lista permitida: {@code https} sempre, {@code http} apenas quando a flag
 *       {@code nexio.http.allow-insecure-http} estiver ligada (padrao desligada, ligada so no
 *       perfil de dev/teste). Todo o resto e recusado, incluindo {@code file:}, {@code data:},
 *       {@code jar:}, {@code ftp:} e {@code gopher:};</li>
 *   <li>sem credenciais embutidas no userinfo;</li>
 *   <li>sem fragmento;</li>
 *   <li>porta explicita valida;</li>
 *   <li><b>todos</b> os enderecos devolvidos por {@link InetAddress#getAllByName(String)} precisam
 *       ser publicos. Checar so o primeiro nao adianta: um host controlado pelo atacante pode
 *       publicar varios registros A/AAAA e a pilha de rede pode escolher qualquer um deles.</li>
 * </ol>
 *
 * <p><b>Sobre a mensagem de erro:</b> a recusa nunca inclui a URL, o host ou o IP resolvido. Ver
 * {@link HttpTargetNotAllowedException}. O detalhe fica no log do servidor, com as strings de
 * origem externa higienizadas contra forja de linha de log.</p>
 *
 * <p><b>DNS rebinding, fechado na issue #23.</b> Sozinha, esta checagem seria de pre-voo: entre a
 * resolucao feita aqui e a conexao aberta pelo cliente existiria uma segunda resolucao, e um
 * servidor autoritativo hostil responderia endereco publico na primeira e {@code 169.254.169.254}
 * na segunda (TTL zero). Por isso {@link #validateAndResolve(String)} <b>devolve os enderecos</b>
 * que aprovou, e o {@code HttpRequestNodeExecutor} os fixa no {@link PinnedDnsResolver} antes de
 * fazer a requisicao: a segunda consulta deixa de existir. Quem chamar apenas
 * {@link #validate(String)} descarta os enderecos e continua exposto ao rebinding -- e o motivo de
 * o executor nao usar aquele metodo.</p>
 */
public class HttpTargetValidator {

    private static final Logger LOG = LoggerFactory.getLogger(HttpTargetValidator.class);

    private static final String SCHEME_HTTPS = "https";
    private static final String SCHEME_HTTP = "http";

    /** Tamanho maximo de texto de origem externa interpolado em linha de log. */
    private static final int MAX_LOGGED_LENGTH = 200;

    private final boolean allowInsecureHttp;

    /**
     * Cria o validador.
     *
     * @param allowInsecureHttp quando verdadeiro, aceita tambem {@code http}; ligado apenas em
     *                          dev/teste, onde chamar um servico local em texto claro e normal
     */
    public HttpTargetValidator(boolean allowInsecureHttp) {
        this.allowInsecureHttp = allowInsecureHttp;
    }

    /**
     * Valida a URL de destino de uma requisicao de saida.
     *
     * @param url URL informada na configuracao do no
     * @return a URI ja parseada, pronta para uso pelo cliente HTTP
     * @throws HttpTargetNotAllowedException quando a URL viola alguma das regras; a mensagem e
     *         uma constante e nao repete nada da entrada
     */
    public URI validate(String url) {
        return validateAndResolve(url).uri();
    }

    /**
     * Valida a URL e devolve tambem os enderecos aprovados, para que a conexao seja fixada neles.
     *
     * <p>Existe porque descartar os enderecos ja resolvidos era o que deixava o DNS rebinding em
     * aberto: quem so recebe a URI precisa resolver o host de novo na hora de conectar, e a segunda
     * consulta pode responder outra coisa. Devolvendo os enderecos, o executor fixa a conexao no
     * que foi aprovado -- ver {@link PinnedDnsResolver}.</p>
     *
     * @param url URL informada na configuracao do no
     * @return URI validada e os enderecos que a checagem aprovou
     * @throws HttpTargetNotAllowedException quando a URL viola alguma das regras
     */
    public ValidatedTarget validateAndResolve(String url) {
        URI uri = validateSyntax(url);
        return new ValidatedTarget(uri, List.of(validateAddresses(uri.getHost())));
    }

    /**
     * Destino aprovado.
     *
     * @param uri       URI validada
     * @param addresses enderecos aprovados, todos publicos, nunca vazio
     */
    public record ValidatedTarget(URI uri, List<InetAddress> addresses) {
    }

    private URI validateSyntax(String url) {
        if (url == null || url.isBlank()) {
            throw reject(Reason.MALFORMED_URL, "url nula ou em branco");
        }

        URI uri;
        try {
            uri = new URI(withoutPlaceholders(url.trim()));
        } catch (URISyntaxException e) {
            throw reject(Reason.MALFORMED_URL, "sintaxe invalida: " + forLog(url));
        }

        validateScheme(uri, url);

        if (uri.getRawUserInfo() != null) {
            throw reject(Reason.EMBEDDED_CREDENTIALS, "userinfo embutido em " + forLog(uri.getScheme()));
        }
        // O fragmento nunca vai para a rede: se veio, a URL foi montada a partir de algo que nao e
        // um endpoint de API (link de navegador, template mal interpolado) e o mais seguro e recusar
        // em vez de descartar em silencio e chamar um alvo diferente do que o autor escreveu.
        if (uri.getRawFragment() != null) {
            throw reject(Reason.FRAGMENT_NOT_ALLOWED, "fragmento presente em " + forLog(url));
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // getHost() nulo com autoridade presente e o caso do host baseado em registro (por
            // exemplo com '_'), que cada parser interpreta de um jeito. Ambiguidade aqui e o
            // ingrediente de qualquer bypass por divergencia de parser, entao recusamos.
            throw reject(Reason.MALFORMED_URL, "host ausente ou ambiguo em " + forLog(url));
        }

        // Porta 0 nao e endereco de servico; qualquer outra porta valida e aceita de proposito:
        // restringir a 80/443 quebraria APIs legitimas em 8443/8080, e a protecao real vem da
        // checagem de endereco abaixo, nao da porta. Varredura de portas por mensagem de erro ja
        // esta bloqueada porque a recusa nao devolve detalhe algum.
        if (uri.getPort() == 0) {
            throw reject(Reason.INVALID_PORT, "porta 0 em " + forLog(url));
        }

        return uri;
    }

    /**
     * Aplica as regras que nao dependem de rede, mais a checagem de endereco quando o host ja e um
     * literal IP.
     *
     * <p>Existe para a validacao de escrita. O {@link #validate(String)} completo resolve DNS, e
     * chamar aquilo no {@code createWorkflow} tornaria a criacao de workflow dependente de rede e
     * transformaria todo host que nao resolve -- inclusive os nomes reservados que os proprios
     * testes usam -- em erro de validacao. Aqui a resolucao so acontece quando nao ha resolucao a
     * fazer: {@code http://169.254.169.254/} e recusado na escrita porque um literal nao precisa de
     * consulta a DNS, enquanto {@code https://api.exemplo.com/} passa e e verificado no disparo.</p>
     *
     * <p>Isso <b>nao</b> enfraquece a protecao: a validacao completa no disparo continua sendo
     * obrigatoria de qualquer forma, porque o endereco pode mudar entre a escrita e a execucao
     * (DNS rebinding). O que a escrita faz e adiantar o que da para adiantar sem pagar uma consulta
     * a DNS por save.</p>
     *
     * @param url endereco a validar
     * @return URI ja validado sintaticamente
     */
    public URI validateWithoutResolving(String url) {
        URI uri = validateSyntax(url);
        String host = uri.getHost();
        if (isIpLiteral(host)) {
            validateAddresses(host);
        }
        return uri;
    }

    /**
     * Informa se o host e um literal IP, e portanto verificavel sem consultar DNS.
     *
     * <p>O IPv6 chega entre colchetes vindo do {@link URI}, e o IPv4 e reconhecido pela forma
     * numerica. Nome que nao case com nenhum dos dois exige resolucao e fica para o disparo.</p>
     */
    private static boolean isIpLiteral(String host) {
        return host.startsWith("[") || IP_LITERAL_CANDIDATE.matcher(host).matches();
    }

    /**
     * Candidato a literal IP: qualquer host feito so de digitos e pontos, ou em hexadecimal.
     *
     * <p>O padrao anterior exigia os quatro octetos ({@code ^\d{1,3}(\.\d{1,3}){3}$}) e por isso
     * deixava passar a forma decimal de 32 bits: {@code http://2852039166/} e
     * {@code 169.254.169.254} escrito como um numero so. O {@link URI} devolve {@code "2852039166"}
     * como host -- rotulo unico todo numerico e aceito pela gramatica --, o padrao antigo nao casava,
     * nenhuma checagem de endereco rodava e a definicao era gravada apontando para o servico de
     * metadados da nuvem.</p>
     *
     * <p>Por isso o criterio deixou de tentar reconhecer o formato e passou a ser o inverso: host so
     * com digitos e pontos <b>nao pode</b> ser nome de DNS valido, entao vai para a checagem de
     * endereco, que resolve sem consultar rede quando o valor e literal. Errar para o lado de
     * checar custa nada; errar para o lado de nao checar custou este furo.</p>
     */
    private static final Pattern IP_LITERAL_CANDIDATE =
            Pattern.compile("^[0-9.]+$|^0[xX][0-9a-fA-F]+$");

    /**
     * Troca os marcadores {@code {{...}}} por um rotulo neutro antes da analise sintatica.
     *
     * <p>Sem isto, <b>nenhuma URL templatizada passava pela validacao de escrita</b>: {@code &#123;} e
     * {@code &#125;} nao sao caracteres validos num URI, o {@code new URI} estourava e
     * {@code http://api.exemplo.test/pedidos/&#123;&#123;trigger.id&#125;&#125;} era recusada como
     * "URL de destino invalida" no {@code createWorkflow}. O templating ficava inutilizavel de ponta
     * a ponta, e nenhum teste via porque o resolvedor e o caminho de escrita eram exercitados
     * separadamente -- so o teste de ponta a ponta juntou os dois.</p>
     *
     * <p>A substituicao nao enfraquece a checagem de destino. O que ela protege e o host, e o
     * marcador no host ja foi recusado antes, por {@code Placeholders.validateUrlTemplate}: o
     * marcador so e permitido depois da autoridade, entao o que sobra aqui esta no caminho ou na
     * consulta e nao muda para onde a requisicao vai. O rotulo e alfanumerico para nao introduzir
     * outro problema de sintaxe onde havia um marcador.</p>
     */
    private static String withoutPlaceholders(String url) {
        return PLACEHOLDER.matcher(url).replaceAll("marcador");
    }

    /** Marcador de template; mesma sintaxe do {@code Placeholders} do dominio. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[^{}]*\\}\\}");

    private void validateScheme(URI uri, String rawUrl) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw reject(Reason.MALFORMED_URL, "url sem esquema: " + forLog(rawUrl));
        }
        String normalized = scheme.toLowerCase(Locale.ROOT);
        if (SCHEME_HTTPS.equals(normalized)) {
            return;
        }
        if (SCHEME_HTTP.equals(normalized)) {
            if (!allowInsecureHttp) {
                throw reject(Reason.INSECURE_SCHEME_DISABLED, "http desabilitado por configuracao");
            }
            return;
        }
        throw reject(Reason.UNSUPPORTED_SCHEME, "esquema recusado: " + forLog(normalized));
    }

    private InetAddress[] validateAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException | IllegalArgumentException e) {
            throw reject(Reason.UNRESOLVABLE_HOST, "host nao resolvido: " + forLog(host));
        }
        if (addresses.length == 0) {
            throw reject(Reason.UNRESOLVABLE_HOST, "resolucao vazia para: " + forLog(host));
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw reject(Reason.BLOCKED_ADDRESS,
                        "host " + forLog(host) + " resolve para endereco interno "
                                + address.getHostAddress());
            }
        }
        return addresses;
    }

    /**
     * Decide se um endereco resolvido e proibido como destino.
     *
     * <p>Cobre loopback ({@code 127.0.0.0/8}, {@code ::1}), link-local ({@code 169.254.0.0/16},
     * onde vivem os metadados da nuvem, e {@code fe80::/10}), site-local ({@code 10/8},
     * {@code 172.16/12}, {@code 192.168/16} e {@code fec0::/10}), any-local ({@code 0.0.0.0},
     * {@code ::}) e multicast.</p>
     */
    private static boolean isBlocked(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address)
                || isReservedIpv4(address);
    }

    /**
     * Cobre {@code fc00::/7}, o espaco privado de IPv6 em uso hoje.
     *
     * <p>Precisa de checagem propria: {@link Inet6Address#isSiteLocalAddress()} responde apenas
     * por {@code fec0::/10}, que esta obsoleto, e devolve falso para {@code fc00::/7}.</p>
     */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        return (Byte.toUnsignedInt(address.getAddress()[0]) & 0xFE) == 0xFC;
    }

    /**
     * Faixas IPv4 que as checagens do {@link InetAddress} nao cobrem: {@code 0.0.0.0/8} ("esta
     * rede", que varias pilhas tratam como local), {@code 100.64.0.0/10} (CGNAT, rede do provedor)
     * e tudo de {@code 240.0.0.0/4} para cima, que inclui o broadcast {@code 255.255.255.255}.
     */
    private static boolean isReservedIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] octets = address.getAddress();
        int first = Byte.toUnsignedInt(octets[0]);
        int second = Byte.toUnsignedInt(octets[1]);
        return first == 0
                || first >= 240
                || (first == 100 && second >= 64 && second <= 127);
    }

    private static HttpTargetNotAllowedException reject(Reason reason, String detail) {
        LOG.warn("Destino HTTP recusado: motivo={} detalhe={}", reason, detail);
        return new HttpTargetNotAllowedException(reason);
    }

    /**
     * Higieniza texto de origem externa antes de interpola-lo em linha de log: sem isso uma URL
     * com quebra de linha forjaria registros inteiros no log.
     */
    private static String forLog(String value) {
        int limit = Math.min(value.length(), MAX_LOGGED_LENGTH);
        StringBuilder safe = new StringBuilder(limit + 3);
        for (int i = 0; i < limit; i++) {
            char current = value.charAt(i);
            safe.append(Character.isISOControl(current) ? '?' : current);
        }
        if (value.length() > limit) {
            safe.append("...");
        }
        return safe.toString();
    }
}
