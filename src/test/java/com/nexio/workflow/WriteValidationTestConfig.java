package com.nexio.workflow;

import com.nexio.workflow.infrastructure.http.HttpTargetValidator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Fornece o {@link HttpTargetValidator} aos testes de fatia que importam os callbacks de escrita.
 *
 * <p>Existe porque o {@code WorkflowDefinitionWriteValidationCallback} passou a validar o destino
 * das URLs e ganhou o validador como dependencia de construtor. Nas fatias {@code @DataMongoTest} o
 * bean nao vem junto: quem o declara e o {@code HttpClientConfig}, que e configuracao da camada web
 * e nao entra na fatia de dados. Sem isto, todo teste que importa o callback falha ao subir o
 * contexto -- e falha por dependencia faltando, um erro que nao diz nada sobre o que o teste
 * verifica.</p>
 *
 * <p>{@code allowInsecureHttp} vem {@code true} para acompanhar o que o perfil {@code test} define
 * no {@code application.yml}: os testes apontam para servico local, e exigir https ali recusaria
 * fixture legitima por um motivo que nada tem a ver com o que esta sendo verificado.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class WriteValidationTestConfig {

    /**
     * Validador de destino com a mesma politica do perfil de teste.
     *
     * @return validador que aceita http em texto claro
     */
    @Bean
    public HttpTargetValidator httpTargetValidator() {
        return new HttpTargetValidator(true);
    }
}
