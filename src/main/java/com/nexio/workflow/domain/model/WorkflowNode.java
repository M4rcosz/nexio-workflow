package com.nexio.workflow.domain.model;

import com.nexio.workflow.domain.model.enums.HttpMethod;
import com.nexio.workflow.domain.model.enums.NodeType;
import java.util.Map;
import java.util.Objects;

/**
 * Value object embutido em {@link WorkflowDefinition} que representa um no do grafo do workflow.
 *
 * <p>O componente chama-se {@code nodeId} e nao {@code id} de proposito: o Spring Data MongoDB trata
 * qualquer propriedade chamada {@code id} como propriedade de identidade e a persiste como {@code _id},
 * mesmo em documentos embedados. Com o nome {@code id} os nos eram gravados como {@code nodes._id} e
 * toda consulta por {@code nodes.id} casava zero documentos silenciosamente. O nome tambem fica
 * consistente com {@link ExecutionStep#nodeId()}.
 *
 * <p><b>Os parametros do no sao campos declarados, e nao chaves dentro de {@code config}.</b> O
 * schema ja fazia esse argumento para as arestas do grafo: rota escondida em blob nao tipado passa
 * por um grafo "validado" carregando um ciclo, porque a validacao so enxerga o que esta declarado.
 * O mesmo vale para {@code url} e {@code expression}, por dois motivos distintos e igualmente
 * concretos -- ver {@code docs/adr/0003-typed-http-node-fields.md}:</p>
 *
 * <ul>
 *   <li>{@code url} e o campo que o {@code HttpTargetValidator} precisa enxergar. Enquanto ele
 *       morava num mapa livre, a validacao de destino so podia rodar no disparo, quando o autor do
 *       workflow ja foi embora; declarado, ele e recusado na escrita.</li>
 *   <li>{@code expression} e o campo que o {@code ConditionNodeExecutor} avalia. O
 *       {@code docs/adr/0002-spel-sandbox.md} exige valida-lo na escrita, e o dominio nao valida o
 *       que nao consegue ver.</li>
 * </ul>
 *
 * <p>{@code config} continua existindo, para o que e especifico do tipo de no e que o dominio nao
 * precisa inspecionar. O criterio e esse: se o dominio le o campo para validar ou proteger alguma
 * coisa, ele e declarado; se e carga opaca repassada adiante, fica em {@code config}.</p>
 *
 * <p>O construtor compacto usa {@link MapSanitizer#copy(Map, String)}, que e leniente, e nao a
 * validacao estrita: este construtor tambem roda quando o Spring Data hidrata o documento lido do
 * MongoDB, e regra estrita ali derrubaria a leitura da colecao inteira. Vale para {@code config} e
 * igualmente para {@code headers} e {@code body}, que sao mapas livres pelo mesmo motivo. A
 * validacao estrita acontece na escrita, em {@link WorkflowDefinition#validateConfigs()} e no
 * callback de persistencia.</p>
 *
 * <p>Pela mesma razao o construtor <b>nao</b> aplica as regras por tipo de no -- exigir {@code url}
 * num HTTP_REQUEST, exigir {@code expression} num CONDITION. Elas moram em
 * {@link WorkflowDefinition#validateGraph()}, que so roda na escrita. Se estivessem aqui, um unico
 * documento gravado antes da regra existir tornaria a colecao inteira ilegivel.</p>
 *
 * @param nodeId        identificador do no dentro do workflow
 * @param type          tipo do no
 * @param url           endereco chamado pelos nos HTTP_REQUEST
 * @param method        verbo HTTP usado pelos nos HTTP_REQUEST
 * @param headers       cabecalhos enviados pelos nos HTTP_REQUEST
 * @param body          corpo enviado pelos nos HTTP_REQUEST
 * @param expression    condicao avaliada pelos nos CONDITION
 * @param config        parametros adicionais que o dominio nao inspeciona
 * @param nextOnSuccess proximo no quando a execucao do no e bem sucedida (nos nao condicionais)
 * @param nextOnTrue    proximo no quando a condicao avalia para verdadeiro (nos CONDITION)
 * @param nextOnFalse   proximo no quando a condicao avalia para falso (nos CONDITION)
 */
public record WorkflowNode(
        String nodeId,
        NodeType type,
        String url,
        HttpMethod method,
        Map<String, Object> headers,
        Map<String, Object> body,
        String expression,
        Map<String, Object> config,
        String nextOnSuccess,
        String nextOnTrue,
        String nextOnFalse
) {

    public WorkflowNode {
        Objects.requireNonNull(nodeId, "nodeId do no nao pode ser nulo");
        Objects.requireNonNull(type, "type do no nao pode ser nulo");
        headers = MapSanitizer.copy(headers, "nodes.headers");
        body = MapSanitizer.copy(body, "nodes.body");
        config = MapSanitizer.copy(config, "nodes.config");
    }

    /**
     * Monta um no HTTP_REQUEST, deixando nulos os campos que esse tipo nao aceita.
     *
     * <p>As fabricas existem porque o construtor canonico tem onze componentes e a maioria e nula
     * em qualquer no concreto: chamado direto, ele vira uma fila de {@code null} posicionais onde
     * trocar {@code expression} por {@code url} compila sem reclamar. Aqui o tipo do no decide
     * quais campos existem, que e a mesma regra que
     * {@link WorkflowDefinition#validateGraph()} aplica -- a diferenca e que a fabrica a torna
     * dificil de violar, e a validacao continua sendo quem a garante.</p>
     *
     * <p>O construtor canonico continua publico: o Spring Data precisa dele para hidratar, e os
     * testes de invariante precisam conseguir montar exatamente o no invalido que verificam.</p>
     *
     * @param nodeId        identificador do no dentro do workflow
     * @param url           endereco chamado
     * @param method        verbo HTTP
     * @param headers       cabecalhos enviados
     * @param body          corpo enviado
     * @param nextOnSuccess proximo no em caso de sucesso
     * @return no HTTP_REQUEST
     */
    public static WorkflowNode httpRequest(
            String nodeId,
            String url,
            HttpMethod method,
            Map<String, Object> headers,
            Map<String, Object> body,
            String nextOnSuccess) {
        return new WorkflowNode(
                nodeId, NodeType.HTTP_REQUEST, url, method, headers, body,
                null, null, nextOnSuccess, null, null);
    }

    /**
     * Monta um no CONDITION, deixando nulos os campos que esse tipo nao aceita.
     *
     * @param nodeId      identificador do no dentro do workflow
     * @param expression  condicao avaliada
     * @param nextOnTrue  proximo no quando a condicao e verdadeira
     * @param nextOnFalse proximo no quando a condicao e falsa
     * @return no CONDITION
     */
    public static WorkflowNode condition(
            String nodeId,
            String expression,
            String nextOnTrue,
            String nextOnFalse) {
        return new WorkflowNode(
                nodeId, NodeType.CONDITION, null, null, null, null,
                expression, null, null, nextOnTrue, nextOnFalse);
    }
}
