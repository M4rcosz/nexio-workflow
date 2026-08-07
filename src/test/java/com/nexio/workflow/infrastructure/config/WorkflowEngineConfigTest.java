package com.nexio.workflow.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nexio.workflow.application.engine.NodeExecutionContext;
import com.nexio.workflow.application.engine.NodeExecutionResult;
import com.nexio.workflow.application.engine.NodeExecutor;
import com.nexio.workflow.application.engine.WorkflowEngine;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.NodeType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cobre a montagem da {@link WorkflowEngine} como bean.
 *
 * <p>O caso sem executor nenhum e o caso de hoje: os executores chegam nas issues #22 e #23. A
 * injecao direta de {@code List<NodeExecutor>} <b>falha</b> quando nenhum bean daquele tipo existe,
 * e a falha seria a aplicacao inteira nao subir por causa de uma implementacao que ainda nao foi
 * escrita. Este teste trava a escolha oposta -- subir com a engine vazia -- porque ela e invisivel
 * no codigo: um {@code ObjectProvider} trocado de volta por {@code List} compila igual.</p>
 */
class WorkflowEngineConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubPortConfig.class, WorkflowEngineConfig.class);

    @Test
    void buildsTheEngineWithTheDefaultCapAndNoExecutorsRegistered() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(WorkflowEngine.class);
            assertThat(context.getBean(WorkflowEngineProperties.class).maxExecutionDuration())
                    .hasSeconds(30);
        });
    }

    @Test
    void buildsTheEngineWithTheExecutorsPresentInTheContext() {
        runner.withUserConfiguration(OneExecutorConfig.class)
                .run(context -> assertThat(context).hasSingleBean(WorkflowEngine.class));
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
    static class OneExecutorConfig {

        @Bean
        NodeExecutor conditionExecutor() {
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
