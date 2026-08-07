package com.nexio.workflow.api.graphql.dto;

import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.NodeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Entrada de um no do grafo nas mutations de workflow.
 *
 * <p><b>O componente chama-se {@code id} e o do dominio chama-se {@code nodeId}.</b> Os dois nomes
 * diferem por um motivo que nao pode se perder: no dominio o nome {@code id} e proibido, porque o
 * Spring Data MongoDB trata qualquer propriedade chamada {@code id} como identidade e a grava como
 * {@code _id}, inclusive em documento embedado -- com esse nome os nos iam para {@code nodes._id} e
 * toda consulta por {@code nodes.id} casava zero documentos em silencio (ver
 * {@link WorkflowNode}). No GraphQL esse problema nao existe, e o campo e, para quem chama, o
 * identificador do no dentro do workflow: chamar de {@code nodeId} no schema seria carregar para o
 * contrato publico uma limitacao do banco. A traducao entre os dois nomes mora no mapper, e e o
 * unico lugar onde ela acontece.</p>
 *
 * <p>O formato do id ({@code ^[A-Za-z0-9_-]{1,64}$}), a existencia do alvo de cada aresta, a regra
 * dos nos CONDITION, ausencia de ciclo e alcancabilidade sao verificados por
 * {@link WorkflowDefinition#validateGraph()}. Nao sao repetidos aqui: seriam uma segunda copia da
 * mesma regra, que diverge da primeira no primeiro ajuste. O que fica em Bean Validation e so o que
 * o dominio nao teria como recusar antes de montar o agregado.</p>
 *
 * @param id            identificador do no dentro do workflow, obrigatorio
 * @param type          tipo do no, obrigatorio
 * @param config        parametros do no, obrigatorio
 * @param nextOnSuccess proximo no em caso de sucesso, para nos nao condicionais
 * @param nextOnTrue    proximo no quando a condicao e verdadeira, para nos CONDITION
 * @param nextOnFalse   proximo no quando a condicao e falsa, para nos CONDITION
 */
public record WorkflowNodeInput(
        @NotBlank(message = "id do no e obrigatorio")
        @Size(max = WorkflowDefinition.MAX_NODE_ID_LENGTH,
                message = "id do no excede " + WorkflowDefinition.MAX_NODE_ID_LENGTH + " caracteres")
        String id,

        @NotNull(message = "type do no e obrigatorio")
        NodeType type,

        @NotNull(message = "config do no e obrigatoria")
        Map<String, Object> config,

        @Size(max = WorkflowDefinition.MAX_NODE_ID_LENGTH)
        String nextOnSuccess,

        @Size(max = WorkflowDefinition.MAX_NODE_ID_LENGTH)
        String nextOnTrue,

        @Size(max = WorkflowDefinition.MAX_NODE_ID_LENGTH)
        String nextOnFalse
) {
}
