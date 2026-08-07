package com.nexio.workflow.api.graphql.dto;

import com.nexio.workflow.domain.model.WorkflowDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Entrada da mutation {@code createWorkflow}.
 *
 * <p>Nao tem componente {@code id}: o identificador e gerado pelo caso de uso. Aceitar um id do
 * cliente na criacao seria deixar o chamador escolher qual documento a escrita mira. Tambem nao tem
 * {@code tenantId}: o sistema nao e multi-tenant (ver {@code docs/adr/0001-no-multi-tenancy.md}).
 * </p>
 *
 * <p>As anotacoes de Bean Validation aqui nao substituem as invariantes do dominio, que continuam
 * sendo a fonte da verdade e recusam o que passar por elas. Elas existem para que o erro comum --
 * campo obrigatorio faltando -- volte como {@code BAD_REQUEST} apontando o campo, antes de montar
 * agregado nenhum. Os tetos repetem as constantes de {@link WorkflowDefinition} em vez de numeros
 * soltos, para que mexer no limite do dominio nao deixe uma copia desatualizada aqui.</p>
 *
 * <p>{@code @Valid} nos componentes aninhados existe porque, sem ele, as anotacoes dentro de
 * {@link TriggerConfigInput} e {@link WorkflowNodeInput} simplesmente nao rodam: Bean Validation
 * nao desce em objeto aninhado por conta propria.</p>
 *
 * @param name        nome do workflow, obrigatorio
 * @param description descricao, opcional
 * @param trigger     configuracao do gatilho, obrigatoria
 * @param nodes       nos do grafo, ao menos um
 * @param startNodeId no inicial declarado, opcional
 * @param enabled     se o workflow ja nasce habilitado; ausente equivale a {@code false}
 */
public record CreateWorkflowInput(
        @NotBlank(message = "name e obrigatorio")
        @Size(max = WorkflowDefinition.MAX_NAME_LENGTH,
                message = "name excede " + WorkflowDefinition.MAX_NAME_LENGTH + " caracteres")
        String name,

        @Size(max = WorkflowDefinition.MAX_DESCRIPTION_LENGTH,
                message = "description excede " + WorkflowDefinition.MAX_DESCRIPTION_LENGTH + " caracteres")
        String description,

        @NotNull(message = "trigger e obrigatorio")
        @Valid
        TriggerConfigInput trigger,

        @NotEmpty(message = "o workflow precisa ter ao menos um no")
        @Size(max = WorkflowDefinition.MAX_NODES,
                message = "o workflow excede o limite de " + WorkflowDefinition.MAX_NODES + " nos")
        @Valid
        List<WorkflowNodeInput> nodes,

        @Size(max = WorkflowDefinition.MAX_NODE_ID_LENGTH,
                message = "startNodeId excede " + WorkflowDefinition.MAX_NODE_ID_LENGTH + " caracteres")
        String startNodeId,

        Boolean enabled
) {
}
