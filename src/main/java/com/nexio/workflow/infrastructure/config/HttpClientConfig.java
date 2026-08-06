package com.nexio.workflow.infrastructure.config;

import com.nexio.workflow.infrastructure.http.HttpTargetValidator;
import com.nexio.workflow.infrastructure.http.ResponseSizeLimitInterceptor;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
     * Tempo limite total da requisicao, aplicado a leitura da resposta.
     *
     * <p>Complementa o de conexao e cobre o caso mais comum: o remoto conecta, responde os
     * cabecalhos e depois goteja o corpo indefinidamente (Slowloris ao contrario). Sem ele, a
     * conexao rapida deixaria a execucao presa para sempre mesmo assim.</p>
     */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Cliente HTTP do JDK que serve de base para o {@link RestClient}.
     *
     * <p>Politicas aplicadas:</p>
     * <ul>
     *   <li>{@link HttpClient.Redirect#NEVER}: a validacao de URL do
     *       {@link HttpTargetValidator} acontece antes da requisicao, entao seguir redirecionamento
     *       de forma transparente anularia a validacao inteira -- um {@code 302} para
     *       {@code http://169.254.169.254/} sairia dos metadados da nuvem sem passar por checagem
     *       nenhuma. Com {@code NEVER} o {@code 3xx} volta como resposta normal e quem decide
     *       segui-lo (revalidando o destino) e o executor;</li>
     *   <li>sem {@code CookieHandler}: um cliente com armazenamento de cookies e estado
     *       compartilhado entre todas as requisicoes de todos os workflows. O {@code Set-Cookie}
     *       de um no seria reenviado por outro, de outro workflow e de outro dono. O
     *       {@link HttpClient} so guarda cookies quando um handler e registrado, e aqui nenhum e;</li>
     *   <li>sem {@code Authenticator} e sem proxy ({@link HttpClient.Builder#NO_PROXY}): nenhuma
     *       credencial ambiente e nenhuma autenticacao de proxy sao configuradas, e o cliente nao
     *       adiciona cabecalho {@code Authorization} algum. Credencial padrao aqui seria enviada a
     *       qualquer host que o workflow apontasse, incluindo um controlado por terceiro.</li>
     * </ul>
     *
     * @return cliente HTTP endurecido
     */
    @Bean
    public HttpClient outboundHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
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
     * <p>O tempo limite de leitura vai no {@link JdkClientHttpRequestFactory} porque no
     * {@link HttpClient} do JDK ele e por requisicao, e nao do cliente. O interceptor de tamanho
     * entra aqui porque so ele enxerga o corpo da resposta antes dos conversores de mensagem.</p>
     *
     * @param builder                       construtor ja configurado pelo Spring Boot (conversores
     *                                      de mensagem com o {@code ObjectMapper} da aplicacao)
     * @param outboundHttpClient            cliente HTTP endurecido
     * @param responseSizeLimitInterceptor  interceptor de teto de corpo
     * @return {@link RestClient} pronto para o executor de no HTTP do Sprint 3
     */
    @Bean
    public RestClient outboundRestClient(RestClient.Builder builder,
                                         HttpClient outboundHttpClient,
                                         ResponseSizeLimitInterceptor responseSizeLimitInterceptor) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(outboundHttpClient);
        requestFactory.setReadTimeout(REQUEST_TIMEOUT);
        return builder
                .requestFactory(requestFactory)
                .requestInterceptor(responseSizeLimitInterceptor)
                .build();
    }
}
