package com.nexio.workflow.infrastructure.config;

import com.nexio.workflow.application.engine.ConditionNodeExecutor;
import com.nexio.workflow.application.engine.NodeExecutor;
import com.nexio.workflow.application.engine.WorkflowEngine;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Monta a {@link WorkflowEngine} como bean.
 *
 * <p>A engine e criada aqui e nao anotada com {@code @Service} por dois motivos, e os dois sao
 * sobre manter a classe livre de Spring: o teto de tempo chega como {@link java.time.Duration} pura
 * em vez de um {@code @Value} no construtor, e o {@link Clock} chega como parametro em vez de um
 * {@code Instant.now()} escondido -- o que torna o caminho de estouro de tempo testavel sem teste
 * que dorme.</p>
 *
 * <p>O {@link ObjectProvider} nao e preciosismo. A injecao direta de {@code List<NodeExecutor>}
 * falha quando <b>nenhum</b> bean daquele tipo existe. Desde a issue #22 existe o executor de
 * CONDITION e o de HTTP_REQUEST ainda falta (issue #23), mas o {@link ObjectProvider} fica: a
 * escolha nao era sobre o dia de hoje, e sim sobre subir a aplicacao com a engine sem executor
 * nenhum e falhar apenas a execucao que esbarrar num tipo de no sem executor registrado -- a mesma
 * regra que vale para um tipo de no novo cujo executor ainda nao foi implantado.</p>
 */
@Configuration
@EnableConfigurationProperties(WorkflowEngineProperties.class)
public class WorkflowEngineConfig {

    /**
     * Registra o executor de nos CONDITION.
     *
     * <p>Declarado aqui em vez de anotado com {@code @Component} pelo mesmo motivo da engine: a
     * classe vive na camada de aplicacao e nao carrega anotacao de Spring. Ela nao depende de
     * infraestrutura nenhuma -- so do SpEL --, e por isso e a unica implementacao de
     * {@code NodeExecutor} que nao precisa morar em {@code infrastructure}.</p>
     *
     * @return executor de condicoes
     */
    @Bean
    public ConditionNodeExecutor conditionNodeExecutor() {
        return new ConditionNodeExecutor();
    }

    /**
     * Cria a engine com os executores disponiveis no contexto.
     *
     * @param nodeExecutors executores registrados, possivelmente nenhum
     * @param executionPort porta de persistencia das execucoes
     * @param properties    propriedades do prefixo {@code nexio.engine}
     * @return engine pronta para o caso de uso de disparo da issue #24
     */
    @Bean
    public WorkflowEngine workflowEngine(ObjectProvider<NodeExecutor> nodeExecutors,
                                         WorkflowExecutionPort executionPort,
                                         WorkflowEngineProperties properties) {
        return new WorkflowEngine(
                nodeExecutors.orderedStream().toList(),
                executionPort,
                Clock.systemUTC(),
                properties.maxExecutionDuration());
    }
}
