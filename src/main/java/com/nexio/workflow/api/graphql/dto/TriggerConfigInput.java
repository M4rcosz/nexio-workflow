package com.nexio.workflow.api.graphql.dto;

import com.nexio.workflow.domain.model.enums.TriggerType;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Entrada do campo {@code trigger} das mutations de workflow.
 *
 * <p>O nome do record e o nome de cada componente casam exatamente com o {@code input
 * TriggerConfigInput} do schema: a vinculacao do spring-graphql e por nome, entao renomear um
 * componente aqui deixa o campo correspondente chegando nulo, sem erro nenhum em tempo de
 * compilacao.</p>
 *
 * <p>O enum {@link TriggerType} e reaproveitado do dominio de proposito. Ele nao e um tipo de
 * transporte disfarcado: e a mesma lista fechada de valores que o schema declara, e duplicar o enum
 * na camada de API criaria duas listas para manter sincronizadas, com uma traducao no meio que so
 * quebraria quando alguem acrescentasse um valor em um lado. A regra que a fronteira precisa
 * garantir e a inversa -- nenhum tipo de <i>GraphQL</i> pode chegar ao caso de uso --, e ela
 * continua valida.</p>
 *
 * <p>{@code config} nao tem restricao de Bean Validation porque a regra dele nao cabe em anotacao:
 * chave iniciada por {@code $} ou {@code _}, profundidade, numero de entradas e tipos aceitos sao
 * verificados por {@code MapSanitizer} na escrita.</p>
 *
 * @param type   tipo do gatilho, obrigatorio
 * @param config parametros do gatilho, pode ser nulo
 */
public record TriggerConfigInput(
        @NotNull(message = "type do gatilho e obrigatorio")
        TriggerType type,

        Map<String, Object> config
) {
}
