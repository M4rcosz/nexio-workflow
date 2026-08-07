package com.nexio.workflow.infrastructure.mongodb;

import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.model.MapSanitizer;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

/**
 * Aplica as invariantes de escrita de {@link WorkflowDefinition} em todo save.
 *
 * <p>Existe porque {@code validateGraph()} nao tinha nenhum chamador de producao -- so testes --,
 * o que deixava um grafo ciclico ser gravado sem que nada reclamasse. Como callback, a validacao
 * roda dentro do proprio caminho de persistencia: nao ha caso de uso que consiga esquece-la.</p>
 *
 * <p>A validacao estrita dos mapas livres mora aqui, e nao nos construtores do dominio, porque
 * aqueles construtores tambem rodam na leitura; ver {@link MapSanitizer}.</p>
 *
 * <p><b>A falha sai como {@link InvalidWorkflowException} e nao como o
 * {@code IllegalArgumentException} cru do dominio.</b> Este callback e rede de seguranca, mas o que
 * ele pega e entrada invalida do usuario do mesmo jeito, e nenhum {@code try} do caso de uso envolve
 * o {@code save()}: sem a traducao, toda regra que so este ponto verificasse voltaria ao cliente
 * como erro interno, com pilha inteira no log a cada requisicao. O tipo do dominio e o que o
 * resolver de erro do GraphQL ja sabe transformar em resposta de entrada invalida.</p>
 */
@Component
public class WorkflowDefinitionWriteValidationCallback implements BeforeConvertCallback<WorkflowDefinition> {

    @Override
    public WorkflowDefinition onBeforeConvert(WorkflowDefinition definition, String collection) {
        try {
            definition.validateGraph();
            definition.validateConfigs();
        } catch (IllegalArgumentException e) {
            throw new InvalidWorkflowException(e.getMessage(), e);
        }
        return definition;
    }
}
