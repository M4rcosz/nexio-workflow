package com.nexio.workflow.infrastructure.config;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tetos de custo por consulta GraphQL.
 *
 * <p><b>Existem porque nao ha autenticacao nem limite de requisicoes.</b> Em GraphQL o cliente
 * monta a consulta, e o custo dela nao tem relacao com o tamanho do que ele envia: um documento de
 * poucas linhas com campos aninhados repetidos, ou com o mesmo campo pedido dezenas de vezes por
 * apelido, faz o servidor executar um trabalho arbitrariamente grande. Enquanto qualquer um puder
 * chamar o endpoint sem se identificar e sem cota, o unico ponto onde esse custo pode ser contido e
 * antes da execucao -- que e o que estas duas instrumentacoes fazem: graphql-java calcula
 * profundidade e complexidade sobre o documento ja validado e recusa o que passar do teto, sem
 * resolver campo algum.</p>
 *
 * <p>Os dois tetos medem coisas diferentes e nenhum substitui o outro: a profundidade limita o
 * aninhamento e nao ve repeticao, a complexidade conta campos e nao ve aninhamento. Uma consulta
 * rasa com centenas de apelidos passa pelo limite de profundidade; uma consulta estreita e funda
 * passa pelo de complexidade.</p>
 *
 * <p>O valor de {@value #MAX_DEPTH} para a profundidade nao e o "cerca de 10" que a folga do schema
 * sugeriria, e o numero nao foi escolhido no olho: a consulta de introspeccao padrao -- a que o
 * GraphiQL e todo cliente de esquema enviam, com os niveis encadeados de {@code ofType} -- tem
 * profundidade 13 medida, entao qualquer teto abaixo disso quebraria a introspeccao antes de barrar
 * abuso nenhum. O schema de negocio hoje nao passa de quatro niveis, entao os 14 sao folga inteira
 * para os tipos de execucao do Sprint 3 e continuam muito abaixo do que uma consulta hostil
 * precisaria. O teste {@code GraphQlSchemaTest} executa a introspeccao com esta configuracao
 * ligada, para que baixar o numero quebre um teste em vez do GraphiQL.</p>
 *
 * <p>Isto e contencao de custo, nao controle de acesso, e nao substitui autenticacao nem limite de
 * requisicoes: um cliente pode repetir a consulta mais barata quantas vezes quiser. Quando essas
 * duas pecas existirem, os tetos daqui continuam valendo -- eles limitam o custo de <i>uma</i>
 * consulta, que e uma pergunta diferente de quantas consultas cada um pode fazer.</p>
 */
@Configuration
public class GraphQlQueryCostConfig {

    /** Profundidade maxima de aninhamento aceita em uma consulta. */
    public static final int MAX_DEPTH = 14;

    /** Complexidade maxima (numero de campos a resolver) aceita em uma consulta. */
    public static final int MAX_COMPLEXITY = 200;

    /**
     * Recusa consultas mais aninhadas que {@value #MAX_DEPTH} niveis.
     *
     * @return instrumentacao de profundidade maxima
     */
    @Bean
    public MaxQueryDepthInstrumentation maxQueryDepthInstrumentation() {
        return new MaxQueryDepthInstrumentation(MAX_DEPTH);
    }

    /**
     * Recusa consultas com complexidade acima de {@value #MAX_COMPLEXITY}.
     *
     * @return instrumentacao de complexidade maxima
     */
    @Bean
    public MaxQueryComplexityInstrumentation maxQueryComplexityInstrumentation() {
        return new MaxQueryComplexityInstrumentation(MAX_COMPLEXITY);
    }
}
