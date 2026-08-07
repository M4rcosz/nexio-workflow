package com.nexio.workflow.application.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Estado da execucao visivel para um {@link NodeExecutor} enquanto ele roda um no.
 *
 * <p>Sao tres componentes, e cada um esta aqui por um chamador concreto:</p>
 * <ul>
 *   <li>{@code executionId} e o que correlaciona um log de executor com o documento da execucao.
 *       Sem ele, a linha de log de um no HTTP nao diz de qual disparo ela veio;</li>
 *   <li>{@code triggerPayload} e {@code outputs} sao o que uma expressao de no CONDITION referencia
 *       (issue #22): "o campo X do evento" e "o que o no anterior devolveu" sao as duas unicas
 *       coisas que uma condicao de workflow tem para decidir.</li>
 * </ul>
 *
 * <p>Nao carrega a {@code WorkflowDefinition} nem a {@code WorkflowExecution}: dar o agregado
 * inteiro a um executor seria dar a ele o {@code status}, as transicoes de estado e a lista de
 * passos, ou seja, a possibilidade de um executor de no marcar a propria execucao como concluida.
 * O no recebe o que precisa ler e nada que possa escrever.</p>
 *
 * <p>Os mapas sao expostos como visao imutavel. Vale notar que a imutabilidade e profunda na
 * pratica, mas nao por esforco deste record: os mapas que chegam aqui ja vem copiados em
 * profundidade por {@code MapSanitizer.copy}, no {@code setTriggerPayload} da execucao e no
 * construtor de {@code NodeExecutionResult}.</p>
 *
 * @param executionId    identificador da execucao em andamento
 * @param triggerPayload payload que disparou a execucao
 * @param outputs        saidas dos nos ja executados, indexadas pelo id do no
 */
public record NodeExecutionContext(
        String executionId,
        Map<String, Object> triggerPayload,
        Map<String, Map<String, Object>> outputs
) {

    public NodeExecutionContext {
        Objects.requireNonNull(executionId, "executionId do contexto nao pode ser nulo");
        triggerPayload = unmodifiableCopy(triggerPayload);
        outputs = unmodifiableCopy(outputs);
    }

    /**
     * Devolve a saida de um no ja executado.
     *
     * @param nodeId identificador do no
     * @return saida registrada, ou mapa vazio quando o no ainda nao rodou nesta execucao
     */
    public Map<String, Object> outputOf(String nodeId) {
        return outputs.getOrDefault(nodeId, Map.of());
    }

    /**
     * Copia rasa e imutavel.
     *
     * <p>Nao usa {@code Map.copyOf}: ele recusa valor nulo, e {@code null} e valor legitimo tanto
     * num payload de gatilho quanto na saida de um no -- o {@code MapSanitizer} o aceita
     * explicitamente. Um {@code Map.copyOf} aqui transformaria um campo nulo do evento num
     * {@code NullPointerException} no meio da execucao.</p>
     */
    private static <V> Map<String, V> unmodifiableCopy(Map<String, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
