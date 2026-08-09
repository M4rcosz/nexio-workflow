package com.nexio.workflow.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades do cliente HTTP de saida, prefixo {@code nexio.http}.
 *
 * <p>Tempo limite e teto de corpo de resposta ficam como constante no {@link HttpClientConfig} de
 * proposito: sao mitigacoes, e expo-las em arquivo de configuracao criaria o caminho trivial para
 * desliga-las (um {@code timeout: 0} em producao) sem passar por revisao de codigo.</p>
 *
 * <p>O tamanho do pool <b>e</b> configuravel, e a diferenca nao e arbitraria: um pool pequeno demais
 * nao desliga protecao nenhuma, ele derruba disponibilidade -- as chamadas passam a falhar
 * esperando emprestimo de conexao. Isso e ajuste de operacao, que depende do numero de threads de
 * requisicao e do perfil dos destinos, e precisa poder ser mudado sem recompilar.</p>
 *
 * @param allowInsecureHttp      permite {@code http} alem de {@code https} no
 *                               {@code HttpTargetValidator}. Padrao {@code false}; ligado apenas no
 *                               bloco de perfil de teste/dev, onde se fala com servico local em
 *                               texto claro
 * @param maxConnections         teto de conexoes de saida simultaneas somando todos os destinos
 * @param maxConnectionsPerRoute teto de conexoes simultaneas para um mesmo destino
 */
@ConfigurationProperties(prefix = "nexio.http")
public record HttpClientProperties(
        boolean allowInsecureHttp,
        Integer maxConnections,
        Integer maxConnectionsPerRoute) {

    /** Padrao do teto total, alinhado ao numero de threads de requisicao do servidor. */
    public static final int DEFAULT_MAX_CONNECTIONS = 200;

    /** Padrao do teto por destino: um workflow costuma falar com poucos hosts, e em rajada. */
    public static final int DEFAULT_MAX_CONNECTIONS_PER_ROUTE = 50;

    public HttpClientProperties {
        maxConnections = positiveOrDefault(maxConnections, DEFAULT_MAX_CONNECTIONS, "max-connections");
        maxConnectionsPerRoute = positiveOrDefault(
                maxConnectionsPerRoute, DEFAULT_MAX_CONNECTIONS_PER_ROUTE, "max-connections-per-route");
    }

    private static int positiveOrDefault(Integer value, int fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("nexio.http." + name + " precisa ser positivo: " + value);
        }
        return value;
    }
}
