package com.nexio.workflow.api.graphql.mapper;

import com.nexio.workflow.api.graphql.dto.CreateWorkflowInput;
import com.nexio.workflow.api.graphql.dto.TriggerConfigInput;
import com.nexio.workflow.api.graphql.dto.TriggerConfigResponse;
import com.nexio.workflow.api.graphql.dto.UpdateWorkflowInput;
import com.nexio.workflow.api.graphql.dto.WorkflowDefinitionResponse;
import com.nexio.workflow.api.graphql.dto.WorkflowNodeInput;
import com.nexio.workflow.api.graphql.dto.WorkflowNodeResponse;
import com.nexio.workflow.application.usecase.command.CreateWorkflowCommand;
import com.nexio.workflow.application.usecase.command.UpdateWorkflowCommand;
import com.nexio.workflow.domain.model.TriggerConfig;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.graphql.data.ArgumentValue;

/**
 * Traducao entre os tipos da API GraphQL e os tipos da aplicacao e do dominio.
 *
 * <p>E uma classe propria, e nao metodos privados do resolver, para que a fronteira tenha um lugar
 * so: enquanto a traducao mora aqui, "nenhum tipo de GraphQL chega ao caso de uso" e uma coisa que
 * da para verificar olhando os imports de um arquivo. Diluida no resolver, ela vira uma regra que
 * so vale enquanto todo mundo lembrar dela.</p>
 *
 * <p>E utilitaria e sem estado de proposito: nao ha nada para injetar, e um bean a mais so
 * apareceria como dependencia a montar em cada teste.</p>
 *
 * <p>A conversao de {@link UpdateWorkflowInput} e o ponto onde "nao enviado" vira
 * {@code Patch.unchanged()} e "enviado como nulo" vira {@code Patch.of(null)}. A pergunta e sempre
 * {@link ArgumentValue#isOmitted()}: {@code isPresent()} responde "o valor nao e nulo", que
 * apagaria justamente a distincao que o {@code ArgumentValue} existe para carregar.</p>
 */
public final class WorkflowGraphQlMapper {

    private WorkflowGraphQlMapper() {
        throw new AssertionError("Classe utilitaria nao deve ser instanciada");
    }

    /**
     * Converte a entrada de criacao no comando do caso de uso.
     *
     * @param input entrada da mutation, nunca nula
     * @return comando de criacao
     */
    public static CreateWorkflowCommand toCommand(CreateWorkflowInput input) {
        return new CreateWorkflowCommand(
                input.name(),
                input.description(),
                Boolean.TRUE.equals(input.enabled()),
                toDomain(input.trigger()),
                toDomain(input.nodes()),
                input.startNodeId());
    }

    /**
     * Converte a entrada de atualizacao parcial no comando do caso de uso.
     *
     * @param input entrada da mutation, nunca nula
     * @return comando de atualizacao, com um recorte presente por campo enviado
     */
    public static UpdateWorkflowCommand toCommand(UpdateWorkflowInput input) {
        UpdateWorkflowCommand.Builder builder = UpdateWorkflowCommand.builder();
        if (!input.name().isOmitted()) {
            builder.name(input.name().value());
        }
        if (!input.description().isOmitted()) {
            builder.description(input.description().value());
        }
        if (!input.trigger().isOmitted()) {
            builder.triggerConfig(toDomain(input.trigger().value()));
        }
        if (!input.nodes().isOmitted()) {
            builder.nodes(toDomain(input.nodes().value()));
        }
        if (!input.startNodeId().isOmitted()) {
            builder.startNodeId(input.startNodeId().value());
        }
        if (!input.enabled().isOmitted()) {
            builder.enabled(input.enabled().value());
        }
        return builder.build();
    }

    /**
     * Converte a definicao do dominio na representacao de leitura.
     *
     * @param definition definicao persistida, nunca nula
     * @return representacao de leitura, ja com as configs redigidas
     */
    public static WorkflowDefinitionResponse toResponse(WorkflowDefinition definition) {
        List<WorkflowNodeResponse> nodes = new ArrayList<>(definition.getNodes().size());
        for (WorkflowNode node : definition.getNodes()) {
            nodes.add(new WorkflowNodeResponse(
                    node.nodeId(),
                    node.type(),
                    node.url(),
                    node.method(),
                    node.headers(),
                    node.body(),
                    node.expression(),
                    node.config(),
                    node.nextOnSuccess(),
                    node.nextOnTrue(),
                    node.nextOnFalse()));
        }
        return new WorkflowDefinitionResponse(
                definition.getId(),
                definition.getName(),
                definition.getDescription(),
                toResponse(definition.getTriggerConfig()),
                nodes,
                definition.getStartNodeId(),
                definition.isEnabled(),
                toOffsetDateTime(definition.getCreatedAt()),
                toOffsetDateTime(definition.getUpdatedAt()));
    }

    /**
     * Converte uma lista de definicoes na representacao de leitura.
     *
     * @param definitions definicoes persistidas, nunca nula
     * @return lista de representacoes de leitura
     */
    public static List<WorkflowDefinitionResponse> toResponses(List<WorkflowDefinition> definitions) {
        List<WorkflowDefinitionResponse> responses = new ArrayList<>(definitions.size());
        for (WorkflowDefinition definition : definitions) {
            responses.add(toResponse(definition));
        }
        return List.copyOf(responses);
    }

    private static TriggerConfigResponse toResponse(TriggerConfig triggerConfig) {
        if (triggerConfig == null) {
            return null;
        }
        return new TriggerConfigResponse(triggerConfig.type(), triggerConfig.config());
    }

    private static TriggerConfig toDomain(TriggerConfigInput input) {
        if (input == null) {
            return null;
        }
        return new TriggerConfig(input.type(), input.config());
    }

    private static List<WorkflowNode> toDomain(List<WorkflowNodeInput> inputs) {
        if (inputs == null) {
            return List.of();
        }
        List<WorkflowNode> nodes = new ArrayList<>(inputs.size());
        for (WorkflowNodeInput input : inputs) {
            nodes.add(new WorkflowNode(
                    input.id(),
                    input.type(),
                    input.url(),
                    input.method(),
                    input.headers(),
                    input.body(),
                    input.expression(),
                    input.config(),
                    input.nextOnSuccess(),
                    input.nextOnTrue(),
                    input.nextOnFalse()));
        }
        return List.copyOf(nodes);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
