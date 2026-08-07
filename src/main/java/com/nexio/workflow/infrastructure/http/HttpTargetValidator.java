package com.nexio.workflow.infrastructure.http;

import com.nexio.workflow.infrastructure.http.HttpTargetNotAllowedException.Reason;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
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
 * <p><b>Limitacao conhecida (DNS rebinding):</b> esta e uma checagem de pre-voo. Entre a
 * resolucao feita aqui e a conexao aberta pelo cliente HTTP existe uma segunda resolucao de DNS,
 * e um servidor autoritativo hostil pode responder um endereco publico na primeira e
 * {@code 127.0.0.1} na segunda (TTL zero). Fechar essa janela exige fixar a conexao no IP ja
 * validado -- conectar no endereco aprovado e enviar o host original no cabecalho {@code Host} e
 * no SNI -- o que so pode ser feito onde o socket e realmente aberto. Isso fica adiado para o
 * Sprint 3, junto com o {@code HttpRequestNodeExecutor}. Ate la o validador reduz a superficie
 * (bloqueia URL interna direta e redirecionamento e recusado por
 * {@code HttpClient.Redirect.NEVER}), mas nao elimina o rebinding.</p>
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
        URI uri = validateSyntax(url);
        validateAddresses(uri.getHost());
        return uri;
    }

    private URI validateSyntax(String url) {
        if (url == null || url.isBlank()) {
            throw reject(Reason.MALFORMED_URL, "url nula ou em branco");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
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
        return host.startsWith("[") || IPV4_LITERAL.matcher(host).matches();
    }

    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

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

    private void validateAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
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
