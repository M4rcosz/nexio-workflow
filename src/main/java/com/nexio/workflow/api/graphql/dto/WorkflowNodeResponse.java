package com.nexio.workflow.api.graphql.dto;

import com.nexio.workflow.api.graphql.SecretRedactor;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.HttpMethod;
import com.nexio.workflow.domain.model.enums.NodeType;
import java.util.Map;

/**
 * Representacao de leitura de um no do grafo.
 *
 * <p>O componente chama-se {@code id} e o do dominio chama-se {@code nodeId}; ver
 * {@link WorkflowNodeInput} para o motivo de os dois nomes diferirem.</p>
 *
 * <p><b>A redacao acontece no construtor canonico, e nao em quem monta o DTO.</b> E o que torna
 * impossivel devolver o valor cru: nao existe caminho de construcao que escape do construtor
 * compacto, entao nenhum resolver futuro consegue esquecer de redigir, nem por descuido nem por
 * atalho. A alternativa -- redigir no mapper -- so protege enquanto todo mundo passar pelo mapper,
 * e a primeira construcao direta vaza tudo em silencio.</p>
 *
 * <p><b>{@code url} tambem passa pela redacao, e nao so os mapas.</b> Quando os parametros HTTP
 * viraram campos declarados, {@code url} deixou de ser uma chave dentro de {@code config} e parou
 * de passar por {@link SecretRedactor#redact(Map)}: sem tratamento proprio, promover o campo teria
 * reaberto exatamente o vazamento que a varredura de valor tinha fechado, porque
 * {@code https://api.exemplo.test/v1?api_key=...} carrega a credencial num campo que nenhuma regra
 * de nome pega.</p>
 *
 * <p>A redacao vale so para o que sai: {@link WorkflowNode} continua com os valores originais, que
 * sao os que a execucao usa.</p>
 *
 * @param id            identificador do no dentro do workflow
 * @param type          tipo do no
 * @param url           endereco chamado, ja com userinfo e parametros sensiveis mascarados
 * @param method        verbo HTTP
 * @param headers       cabecalhos, ja com as chaves sensiveis mascaradas
 * @param body          corpo, ja com as chaves sensiveis mascaradas
 * @param expression    condicao avaliada pelos nos CONDITION
 * @param config        parametros adicionais, ja com as chaves sensiveis mascaradas
 * @param nextOnSuccess proximo no em caso de sucesso
 * @param nextOnTrue    proximo no quando a condicao e verdadeira
 * @param nextOnFalse   proximo no quando a condicao e falsa
 */
public record WorkflowNodeResponse(
        String id,
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

    public WorkflowNodeResponse {
        url = SecretRedactor.redactUrl(url);
        headers = SecretRedactor.redact(headers);
        body = SecretRedactor.redact(body);
        config = SecretRedactor.redact(config);
    }
}
