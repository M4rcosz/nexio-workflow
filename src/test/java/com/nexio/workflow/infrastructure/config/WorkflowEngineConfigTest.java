package com.nexio.workflow.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nexio.workflow.application.engine.ConditionNodeExecutor;
import com.nexio.workflow.application.engine.NodeExecutionContext;
import com.nexio.workflow.application.engine.NodeExecutionResult;
import com.nexio.workflow.application.engine.NodeExecutor;
import com.nexio.workflow.application.engine.WorkflowEngine;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.NodeType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cobre a montagem da {@link WorkflowEngine} como bean.
 *
 * <p>A injecao direta de {@code List<NodeExecutor>} <b>falha</b> quando nenhum bean daquele tipo
 * existe, e a falha seria a aplicacao inteira nao subir por causa de uma implementacao que ainda
 * nao foi escrita -- foi assim ate a issue #22. O {@link ObjectProvider} trava a escolha oposta,
 * subir com a engine sem executor, e continua valendo enquanto o executor de HTTP_REQUEST nao
 * existir (issue #23). O teste importa porque a escolha e invisivel no codigo: um
 * {@code ObjectProvider} trocado de volta por {@code List} compila igual.</p>
 */
class WorkflowEngineConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubPortConfig.class, WorkflowEngineConfig.class);

    @Test
    void buildsTheEngineWithTheDefaultCapAndTheConditionExecutorRegistered() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(WorkflowEngine.class);
            assertThat(context).hasSingleBean(ConditionNodeExecutor.class);
            assertThat(context.getBean(WorkflowEngineProperties.class).maxExecutionDuration())
                    .hasSeconds(30);
        });
    }

    /**
     * Dois executores para o mesmo tipo de no derrubam a subida, em vez de um deles vencer em
     * silencio.
     *
     * <p>O teste passou a ser possivel quando a issue #22 registrou um executor de verdade, e
     * comecou pegando um caso real: o duble deste arquivo tambem declarava CONDITION, entao a
     * primeira execucao da suite depois do registro falhou aqui. E o comportamento certo -- com a
     * escolha por ordem de lista, qual dos dois executores roda dependeria da ordem em que o Spring
     * entregou os beans, que ninguem controla e nada declara.</p>
     */
    @Test
    void refusesToStartWhenTwoExecutorsClaimTheSameNodeType() {
        runner.withUserConfiguration(DuplicateConditionExecutorConfig.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class));
    }

    @Test
    void refusesToStartWhenTheCapIsConfiguredOutOfRange() {
        runner.withPropertyValues("nexio.engine.max-execution-duration=2h")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("nao pode passar de"));
    }

    @Configuration(proxyBeanMethods = false)
    static class StubPortConfig {

        @Bean
        WorkflowExecutionPort workflowExecutionPort() {
            return mock(WorkflowExecutionPort.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateConditionExecutorConfig {

        @Bean
        NodeExecutor outroExecutorDeCondicao() {
            return new NodeExecutor() {

                @Override
                public NodeType supportedType() {
                    return NodeType.CONDITION;
                }

                @Override
                public NodeExecutionResult execute(WorkflowNode node, NodeExecutionContext context) {
                    return NodeExecutionResult.condition(true, Map.of());
                }
            };
        }
    }
}
