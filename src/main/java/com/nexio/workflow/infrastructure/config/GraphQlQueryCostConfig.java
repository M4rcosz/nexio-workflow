package com.nexio.workflow.infrastructure.config;

import graphql.analysis.FieldComplexityCalculator;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import org.springframework.context.annotation.Bean;
import com.nexio.workflow.domain.model.WorkflowExecution;
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

    /**
     * Complexidade maxima aceita em uma consulta, medida pelo {@link #LIST_AWARE_COMPLEXITY}.
     *
     * <p>O numero e o custo da consulta legitima mais cara que o schema atual permite, com folga. A
     * pior delas e {@code workflows(limit: 100)} pedindo todos os campos de
     * {@code WorkflowDefinition}: sete campos escalares, mais {@code trigger} com os seus dois, mais
     * {@code nodes} com os seus seis, dao 17 por registro, e {@code 1 + 100 * 17} da 1701. Um teto
     * de 2000 aceita essa consulta e ainda deixa margem para os campos que o Sprint 3 vai
     * acrescentar, sem chegar perto de aceitar duas delas no mesmo documento.</p>
     *
     * <p>O valor subiu de 200 junto com a troca do calculo, e nao apesar dela: com o calculo antigo
     * uma consulta valia o mesmo com {@code limit: 1} ou {@code limit: 100}, entao 200 parecia
     * apertado e nao era -- 99 apelidos de {@code workflows(limit: 1)} somavam 198 e passavam. Sob o
     * calculo novo, esses mesmos 99 apelidos com {@code limit: 100} somam 9999 e sao recusados.</p>
     */
    public static final int MAX_COMPLEXITY = 2000;

    /**
     * Multiplica o custo dos campos filhos pelo {@code limit} pedido.
     *
     * <p>O calculo padrao do graphql-java e {@code 1 + childComplexity} e <b>nao</b> olha
     * cardinalidade: {@code workflows(limit: 100) { id }} pontuava 2, exatamente o mesmo que
     * {@code workflows(limit: 1) { id }}. Com isso o unico argumento que decide quanto trabalho o
     * servidor faz era invisivel para a instrumentacao, e o teto media o tamanho do documento em vez
     * do custo dele -- que e justamente o que a instrumentacao existe para nao fazer.</p>
     *
     * <p>Ausencia do argumento conta como um: campo que nao pagina devolve um registro, e o padrao
     * declarado no schema chega aqui como valor presente, entao o caso de {@code limit} ausente e o
     * dos campos que nunca tiveram esse argumento.</p>
     */
    private static final FieldComplexityCalculator LIST_AWARE_COMPLEXITY = (env, childComplexity) -> {
        Object limit = env.getArguments().get("limit");
        if (limit instanceof Number number) {
            return 1 + number.intValue() * childComplexity;
        }
        return 1 + serverImposedCardinality(env.getFieldDefinition().getName()) * childComplexity;
    };

    /**
     * Cardinalidade dos campos de lista que o cliente <b>nao</b> controla.
     *
     * <p>Tratar "sem {@code limit}" como um era a metade errada da regra. Vale para o campo escalar
     * e para o objeto unico, mas nao para a lista cujo tamanho o servidor decide: {@code steps} nao
     * tem argumento nenhum e pode trazer {@value WorkflowExecution#MAX_STEPS} elementos, cada um com
     * um {@code output} que e o corpo de resposta de um terceiro. Com cardinalidade um,
     * {@code executions(limit: 100) &#123; steps &#123; ... &#125; &#125;} pontuava 1401 contra o
     * teto de {@value #MAX_COMPLEXITY} -- passava folgado enquanto pedia ate cem documentos de
     * varios megabytes cada, que ainda seriam copiados em profundidade pela redacao antes de
     * serializar.</p>
     *
     * <p>E o mesmo defeito que motivou o calculo por {@code limit}, um nivel abaixo: o que decide
     * quanto trabalho o servidor faz continuava invisivel para a instrumentacao. A diferenca e que
     * aqui quem escolhe o tamanho e o servidor, entao o numero tem que vir do dominio.</p>
     */
    private static int serverImposedCardinality(String fieldName) {
        return STEPS_FIELD.equals(fieldName) ? WorkflowExecution.MAX_STEPS : 1;
    }

    private static final String STEPS_FIELD = "steps";

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
     * Recusa um documento que dispare mais de um workflow.
     *
     * <p>Regra propria e nao um numero de complexidade: ver {@link SingleTriggerInstrumentation}
     * para o porque de o teto de complexidade nao conseguir expressar isto sem quebrar o uso
     * legitimo.</p>
     *
     * @return instrumentacao que limita disparos por documento
     */
    @Bean
    public SingleTriggerInstrumentation singleTriggerInstrumentation() {
        return new SingleTriggerInstrumentation();
    }

    /**
     * Recusa consultas com complexidade acima de {@value #MAX_COMPLEXITY}.
     *
     * @return instrumentacao de complexidade maxima
     */
    @Bean
    public MaxQueryComplexityInstrumentation maxQueryComplexityInstrumentation() {
        return new MaxQueryComplexityInstrumentation(MAX_COMPLEXITY, LIST_AWARE_COMPLEXITY);
    }
}
