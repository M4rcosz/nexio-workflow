package com.nexio.workflow.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades do cliente HTTP de saida, prefixo {@code nexio.http}.
 *
 * <p>So a permissao de {@code http} em texto claro e configuravel. Tempo limite e teto de corpo
 * de resposta ficam como constante no {@link HttpClientConfig} de proposito: sao mitigacoes, e
 * expo-las em arquivo de configuracao criaria o caminho trivial para desliga-las (um
 * {@code timeout: 0} em producao) sem passar por revisao de codigo.</p>
 *
 * @param allowInsecureHttp permite {@code http} alem de {@code https} no
 *                          {@code HttpTargetValidator}. Padrao {@code false}; ligado apenas no
 *                          bloco de perfil de teste/dev, onde se fala com servico local em texto
 *                          claro
 */
@ConfigurationProperties(prefix = "nexio.http")
public record HttpClientProperties(boolean allowInsecureHttp) {
}
