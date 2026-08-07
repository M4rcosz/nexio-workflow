package com.nexio.workflow.infrastructure.mongodb;

import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.model.MapSanitizer;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.NodeType;
import com.nexio.workflow.infrastructure.http.HttpTargetNotAllowedException;
import com.nexio.workflow.infrastructure.http.HttpTargetValidator;
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
 *
 * <p><b>A validacao de destino das URLs roda aqui, e nao no caso de uso como as demais checagens de
 * fronteira de escrita.</b> Nao e preferencia: o {@link HttpTargetValidator} e infraestrutura, e a
 * camada de aplicacao nao pode depender dela. Dar a volta com uma porta so para essa checagem seria
 * uma porta com um unico metodo e um unico implementador, e este callback ja e a costura por onde
 * todo save obrigatoriamente passa -- que e a propriedade que interessa.</p>
 *
 * <p>Duas consequencias que precisam ficar registradas:</p>
 *
 * <ul>
 *   <li>A checagem usada aqui e {@link HttpTargetValidator#validateWithoutResolving(String)}, e nao
 *       a completa. O ADR previa resolver DNS na escrita e aceitava o custo; na pratica isso torna
 *       {@code createWorkflow} dependente de rede e faz de todo nome que nao resolve um erro de
 *       validacao, o que atinge inclusive os nomes reservados usados em teste. A versao sem
 *       resolucao pega tudo que nao precisa de DNS -- esquema, credencial embutida, fragmento, e o
 *       literal IP interno como {@code http://169.254.169.254/} -- e deixa o nome para o disparo,
 *       onde a resolucao tem que acontecer de qualquer jeito.</li>
 *   <li><b>Isto nao substitui a validacao no disparo.</b> O endereco pode mudar entre a escrita e a
 *       execucao (DNS rebinding), entao o {@code HttpRequestNodeExecutor} continua obrigado a
 *       validar e a fixar a conexao no IP ja validado. A checagem daqui e defesa em profundidade;
 *       quem a tratar como redundante e remover a do disparo reabre o buraco inteiro.</li>
 * </ul>
 *
 * <p>O {@code catch} inclui {@link HttpTargetNotAllowedException} explicitamente porque ela estende
 * {@code RuntimeException} e nao {@code IllegalArgumentException}: sem ela na lista, recusar um
 * destino interno voltaria ao cliente como erro interno -- a mesma falha que a traducao acima
 * existe para evitar, reintroduzida pela porta dos fundos.</p>
 */
@Component
public class WorkflowDefinitionWriteValidationCallback implements BeforeConvertCallback<WorkflowDefinition> {

    private final HttpTargetValidator httpTargetValidator;

    /**
     * Cria o callback com injecao por construtor.
     *
     * @param httpTargetValidator validador de destino das requisicoes de saida
     */
    public WorkflowDefinitionWriteValidationCallback(HttpTargetValidator httpTargetValidator) {
        this.httpTargetValidator = httpTargetValidator;
    }

    @Override
    public WorkflowDefinition onBeforeConvert(WorkflowDefinition definition, String collection) {
        try {
            definition.validateGraph();
            definition.validateConfigs();
            validateTargets(definition);
        } catch (IllegalArgumentException | HttpTargetNotAllowedException e) {
            throw new InvalidWorkflowException(e.getMessage(), e);
        }
        return definition;
    }

    private void validateTargets(WorkflowDefinition definition) {
        for (WorkflowNode node : definition.getNodes()) {
            if (node.type() == NodeType.HTTP_REQUEST) {
                httpTargetValidator.validateWithoutResolving(node.url());
            }
        }
    }
}
