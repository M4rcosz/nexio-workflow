package com.nexio.workflow.api.graphql.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Representacao de leitura de uma definicao de workflow.
 *
 * <p>O agregado do dominio nao e devolvido diretamente por dois motivos concretos, e nao por
 * simetria de camadas: os nomes divergem ({@code nodeId} contra {@code id}, {@code triggerConfig}
 * contra {@code trigger}) e o agregado carrega {@code version}, que e detalhe de persistencia e nao
 * faz parte de contrato nenhum. Devolve-lo exigiria um {@code @SchemaMapping} para cada divergencia
 * e deixaria o schema publico refem do nome dos campos da entidade.</p>
 *
 * <p>As datas saem como {@link OffsetDateTime} e nao como {@code Instant}: o scalar {@code DateTime}
 * do graphql-java-extended-scalars serializa {@code OffsetDateTime} e {@code ZonedDateTime} e
 * recusa {@code Instant} com {@code CoercingSerializeException} -- o erro apareceria so em tempo de
 * execucao, na primeira consulta. A conversao e para UTC, que e como o {@code Instant} ja
 * estava.</p>
 *
 * @param id          identificador da definicao
 * @param name        nome do workflow
 * @param description descricao, pode ser nula
 * @param trigger     configuracao do gatilho
 * @param nodes       nos do grafo
 * @param startNodeId no inicial declarado, pode ser nulo
 * @param enabled     se o workflow esta habilitado
 * @param createdAt   momento da criacao
 * @param updatedAt   momento da ultima alteracao
 */
public record WorkflowDefinitionResponse(
        String id,
        String name,
        String description,
        TriggerConfigResponse trigger,
        List<WorkflowNodeResponse> nodes,
        String startNodeId,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public WorkflowDefinitionResponse {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
