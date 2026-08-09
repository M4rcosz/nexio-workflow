package com.nexio.workflow.infrastructure.config;

import com.nexio.workflow.infrastructure.http.HttpRequestNodeExecutor;
import com.nexio.workflow.infrastructure.http.HttpTargetValidator;
import com.nexio.workflow.infrastructure.http.PinnedDnsResolver;
import com.nexio.workflow.infrastructure.http.ResponseSizeLimitInterceptor;
import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuracao do cliente HTTP de saida usado pelos nos de workflow.
 *
 * <p><b>Por que {@code HttpClientConfig} e nao {@code WebClientConfig}</b> (a issue #11 pede o
 * segundo nome): o projeto usa {@code spring-boot-starter-web}, ou seja, Spring MVC com
 * bloqueio. {@code WebClient} nao esta no classpath: so vem com o starter de WebFlux, e trazer a
 * pilha reativa inteira -- Reactor, Netty e um segundo modelo de concorrencia -- para fazer uma
 * chamada sincrona dentro de um executor de no seria custo e superficie sem contrapartida. O
 * equivalente sincrono e o {@link RestClient}, que ja vem no {@code spring-web} e tem a mesma API
 * fluente.</p>
 *
 * <p><b>Por que o motor por baixo e o httpclient5 e nao o {@code HttpClient} do JDK</b> (issue #23,
 * ver {@code docs/adr/0007-outbound-http-client.md}): o cliente do JDK nao expoe ponto de extensao
 * para resolucao de nome, e sem isso a conexao nao pode ser fixada no endereco que a validacao de
 * SSRF aprovou. Ficava uma segunda resolucao de DNS entre validar e conectar, que e a janela do
 * DNS rebinding. O {@link PinnedDnsResolver} entra exatamente ai. A API que o resto do codigo usa
 * continua sendo o {@link RestClient}; o que mudou foi so a fabrica de requisicao.</p>
 *
 * <p>Cada ajuste abaixo e mitigacao de um achado da revisao de seguranca deste codigo. Nenhum e
 * preferencia de estilo: remover qualquer um reabre o achado correspondente.</p>
 */
@Configuration
@EnableConfigurationProperties(HttpClientProperties.class)
public class HttpClientConfig {

    /**
     * Tempo limite para estabelecer a conexao TCP/TLS.
     *
     * <p>Sem teto, um host que aceita o pacote e nunca responde prende a thread da execucao ate o
     * limite do sistema operacional (minutos). Como a URL vem de quem monta o workflow, isso e um
     * caminho direto de exaustao de recurso: bastam algumas execucoes para consumir o pool.</p>
     */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Tempo limite <b>entre leituras</b> da resposta.
     *
     * <p><b>Correcao:</b> este Javadoc dizia "tempo limite total da requisicao" e afirmava cobrir o
     * remoto que "goteja o corpo indefinidamente". Isso e falso, e a revisao de backend pegou. O
     * {@code responseTimeout} do httpclient5 vira o tempo limite de leitura do socket: ele limita a
     * <b>inatividade entre leituras</b>, nao a duracao da resposta. Um servidor que manda um byte a
     * cada 9 segundos nunca o dispara, e o teto de tempo da engine nao ajuda porque e conferido
     * entre nos.</p>
     *
     * <p>Ou seja, o pior caso real de uma requisicao <b>nao</b> e {@code teto + um no}: e ilimitado.
     * Fica registrado aqui e na correcao da ADR 0005 em vez de corrigido no codigo porque o conserto
     * e um prazo de parede por no, aplicado na contagem de bytes da leitura -- ver o item aberto.
     * Anunciar um limite que nao existe e pior do que declarar a ausencia dele.</p>
     */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Resolvedor que fixa a conexao no endereco ja aprovado pela validacao de SSRF.
     *
     * <p>E bean para que o executor de no HTTP e o cliente compartilhem a mesma instancia. Sem
     * fixacao ativa ele delega ao resolvedor padrao do sistema, entao instala-lo no cliente nao
     * muda o comportamento de nenhuma chamada que nao passe pelo executor.</p>
     *
     * @return resolvedor com fixacao por thread
     */
    @Bean
    public PinnedDnsResolver pinnedDnsResolver() {
        return new PinnedDnsResolver();
    }

    /**
     * Gerenciador de conexoes com a resolucao de nome sob controle.
     *
     * <p>O {@code DnsResolver} so pode ser instalado aqui, e nao no cliente: e o gerenciador de
     * conexoes que abre o socket, entao e ele que resolve o nome. E a razao tecnica de a issue #23
     * ter trocado o cliente de saida.</p>
     *
     * @param pinnedDnsResolver resolvedor com fixacao por thread
     * @param properties        propriedades do prefixo {@code nexio.http}
     * @return gerenciador de conexoes configurado
     */
    @Bean
    public PoolingHttpClientConnectionManager outboundConnectionManager(
            PinnedDnsResolver pinnedDnsResolver, HttpClientProperties properties) {
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinnedDnsResolver)
                // O padrao do httpclient5 e 25 conexoes no total e 5 por rota. Com o disparo
                // sincrono, cada execucao segura uma thread de requisicao do Tomcat -- que sao 200
                // por padrao --, entao seis disparos simultaneos para o mesmo host ja passam do
                // limite por rota: o sexto espera o tempo de emprestimo e vira falha de no
                // culpando um destino que estava saudavel. Foi a unica configuracao em que o padrao
                // do cliente novo difere do comportamento do cliente do JDK de forma visivel.
                .setMaxConnTotal(properties.maxConnections())
                .setMaxConnPerRoute(properties.maxConnectionsPerRoute())
                // Conexao ociosa reaproveitada que o outro lado ja fechou aparece como falha
                // esporadica e inexplicavel de no. A revalidacao troca isso por um ida e volta.
                .setValidateAfterInactivity(TimeValue.ofSeconds(5))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(CONNECT_TIMEOUT))
                        .build())
                .build();
    }

    /**
     * Cliente HTTP endurecido que serve de base para o {@link RestClient}.
     *
     * <p>Politicas aplicadas. Cada uma existe por um achado de revisao de seguranca deste codigo, e
     * todas foram transplantadas do cliente do JDK sem afrouxar nada:</p>
     * <ul>
     *   <li>{@code disableRedirectHandling()}: a validacao de URL do {@link HttpTargetValidator}
     *       acontece antes da requisicao, entao seguir redirecionamento de forma transparente
     *       anularia a validacao inteira -- um {@code 302} para {@code http://169.254.169.254/}
     *       sairia dos metadados da nuvem sem passar por checagem nenhuma. Desligado, o {@code 3xx}
     *       volta como resposta normal e quem decide segui-lo (revalidando o destino) e o
     *       executor;</li>
     *   <li>{@code disableCookieManagement()}: um cliente com armazenamento de cookies e estado
     *       compartilhado entre todas as requisicoes de todos os workflows. O {@code Set-Cookie} de
     *       um no seria reenviado por outro, de outro workflow e de outro dono;</li>
     *   <li>{@code disableAuthCaching()} e nenhum {@code CredentialsProvider}: nenhuma credencial
     *       ambiente e configurada, e o cliente nao adiciona cabecalho {@code Authorization} algum.
     *       Credencial padrao aqui seria enviada a qualquer host que o workflow apontasse,
     *       incluindo um controlado por terceiro;</li>
     *   <li>sem {@code useSystemProperties()}: e o que manteria o cliente livre de proxy de
     *       ambiente. Um {@code https_proxy} definido no host faria toda requisicao de saida passar
     *       por um intermediario que a validacao de destino nao enxerga;</li>
     *   <li>{@code disableAutomaticRetries()}: superficie que a <b>troca de cliente trouxe</b>, e
     *       nao uma politica transplantada -- por isso passou despercebida. O httpclient5 reexecuta
     *       por padrao uma vez os metodos idempotentes em erro de E/S, e um {@code PUT} ou
     *       {@code DELETE} cuja resposta se perdeu depois de o servidor processar seria reenviado:
     *       o efeito colateral acontece duas vezes e a execucao registra um passo so. Reexecutar uma
     *       requisicao escrita pelo usuario contra um terceiro qualquer e decisao de produto, nao
     *       padrao de biblioteca a herdar; se um dia houver repeticao, ela mora na engine, onde vira
     *       passo registrado.</li>
     * </ul>
     *
     * @param connectionManager gerenciador de conexoes com o resolvedor fixado
     * @return cliente HTTP endurecido
     */
    @Bean
    public CloseableHttpClient outboundHttpClient(PoolingHttpClientConnectionManager connectionManager) {
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableAutomaticRetries()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(REQUEST_TIMEOUT))
                        .setConnectionRequestTimeout(Timeout.of(CONNECT_TIMEOUT))
                        .build())
                .build();
    }

    /**
     * Registra o executor de nos HTTP_REQUEST.
     *
     * <p>Ao contrario do executor de condicao, este mora na infraestrutura: depende do cliente de
     * saida e do guarda de SSRF, que sao detalhes de infraestrutura. A dependencia aponta na
     * direcao certa -- quem conhece a infraestrutura conhece a interface {@code NodeExecutor}, e
     * nao o contrario.</p>
     *
     * @param outboundRestClient cliente de saida endurecido
     * @param httpTargetValidator guarda de SSRF
     * @return executor de nos HTTP
     */
    @Bean
    public HttpRequestNodeExecutor httpRequestNodeExecutor(RestClient outboundRestClient,
                                                           HttpTargetValidator httpTargetValidator) {
        return new HttpRequestNodeExecutor(outboundRestClient, httpTargetValidator);
    }

    /**
     * Interceptor que limita o corpo da resposta.
     *
     * @return interceptor com o teto padrao de
     *         {@value ResponseSizeLimitInterceptor#DEFAULT_MAX_RESPONSE_BYTES} bytes
     */
    @Bean
    public ResponseSizeLimitInterceptor responseSizeLimitInterceptor() {
        return new ResponseSizeLimitInterceptor();
    }

    /**
     * Validador de destino usado pelo executor de no HTTP antes de cada requisicao.
     *
     * @param properties propriedades do prefixo {@code nexio.http}
     * @return validador configurado conforme o perfil ativo
     */
    @Bean
    public HttpTargetValidator httpTargetValidator(HttpClientProperties properties) {
        return new HttpTargetValidator(properties.allowInsecureHttp());
    }

    /**
     * Cliente sincrono de saida.
     *
     * <p>Os tempos limite ficam no {@link RequestConfig} e no {@link ConnectionConfig} do cliente,
     * e nao na fabrica de requisicao: a fabrica do Spring os sobrescreveria se fossem definidos nos
     * dois lugares. O interceptor de tamanho entra aqui porque so ele enxerga o corpo da resposta
     * antes dos conversores de mensagem.</p>
     *
     * @param builder                       construtor ja configurado pelo Spring Boot (conversores
     *                                      de mensagem com o {@code ObjectMapper} da aplicacao)
     * @param outboundHttpClient            cliente HTTP endurecido
     * @param responseSizeLimitInterceptor  interceptor de teto de corpo
     * @return {@link RestClient} usado pelo executor de no HTTP
     */
    @Bean
    public RestClient outboundRestClient(RestClient.Builder builder,
                                         CloseableHttpClient outboundHttpClient,
                                         ResponseSizeLimitInterceptor responseSizeLimitInterceptor) {
        return builder
                .requestFactory(new HttpComponentsClientHttpRequestFactory(outboundHttpClient))
                .requestInterceptor(responseSizeLimitInterceptor)
                .build();
    }
}
