package com.nexio.workflow.api.graphql.dto;

import com.nexio.workflow.api.graphql.SecretRedactor;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.NodeType;
import java.util.Map;

/**
 * Representacao de leitura de um no do grafo.
 *
 * <p>O componente chama-se {@code id} e o do dominio chama-se {@code nodeId}; ver
 * {@link WorkflowNodeInput} para o motivo de os dois nomes diferirem.</p>
 *
 * <p><b>A redacao da config acontece no construtor canonico, e nao em quem monta o DTO.</b> E o que
 * torna impossivel devolver a config crua: nao existe caminho de construcao que escape do
 * construtor compacto, entao nenhum resolver futuro consegue esquecer de redigir, nem por descuido
 * nem por atalho. A alternativa -- redigir no mapper -- so protege enquanto todo mundo passar pelo
 * mapper, e a primeira construcao direta vaza tudo em silencio.</p>
 *
 * <p>A redacao vale so para o que sai: {@link WorkflowNode} continua com a config original, que e a
 * que a execucao usa.</p>
 *
 * @param id            identificador do no dentro do workflow
 * @param type          tipo do no
 * @param config        parametros do no, ja com as chaves sensiveis mascaradas
 * @param nextOnSuccess proximo no em caso de sucesso
 * @param nextOnTrue    proximo no quando a condicao e verdadeira
 * @param nextOnFalse   proximo no quando a condicao e falsa
 */
public record WorkflowNodeResponse(
        String id,
        NodeType type,
        Map<String, Object> config,
        String nextOnSuccess,
        String nextOnTrue,
        String nextOnFalse
) {

    public WorkflowNodeResponse {
        config = SecretRedactor.redact(config);
    }
}
