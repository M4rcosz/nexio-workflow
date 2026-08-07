package com.nexio.workflow.api.graphql.dto;

import com.nexio.workflow.application.usecase.command.Patch;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.graphql.data.ArgumentValue;

/**
 * Entrada da mutation {@code updateWorkflow}, com todos os campos opcionais.
 *
 * <p><b>Cada componente e um {@link ArgumentValue} porque o record precisa distinguir "nao enviei
 * o campo" de "enviei o campo com valor nulo".</b> Um record de tipos simples nao consegue: o
 * spring-graphql vincula argumento ausente e argumento explicitamente nulo do mesmo jeito, e o
 * componente fica nulo nos dois casos. Com isso, {@code description} omitida e
 * {@code description: null} chegariam identicas ao mapper, que teria que escolher uma leitura para
 * as duas -- ou nunca daria para limpar a descricao, ou toda atualizacao parcial apagaria o que nao
 * foi enviado. {@link Patch}, do lado da aplicacao, existe exatamente para separar os dois casos, e
 * {@code ArgumentValue} e a peca que o spring-graphql oferece para o mesmo problema do lado da
 * API: {@link ArgumentValue#isOmitted()} responde o que o valor sozinho nao responde.</p>
 *
 * <p>A leitura correta e {@code !isOmitted()}, e nao {@code isPresent()}:
 * {@link ArgumentValue#isPresent()} devolve "o valor nao e nulo", que e justamente a pergunta que
 * confunde os dois casos.</p>
 *
 * <p>As restricoes declaradas nos componentes valem para o valor de dentro do container: o
 * extrator que o spring-graphql registra e {@code @UnwrapByDefault} e nao entrega valor algum
 * quando o argumento foi omitido. Na pratica, {@code @NotBlank} em {@code name} recusa
 * {@code name: null} sem recusar a atualizacao que nao mexe no nome -- e e o que impede que um
 * update esvazie um campo que o schema declara como nao nulo na leitura.</p>
 *
 * <p><b>A cascata para os inputs aninhados precisa do {@code @Valid} no argumento de tipo</b> --
 * {@code ArgumentValue<@Valid TriggerConfigInput>} e
 * {@code ArgumentValue<List<@Valid WorkflowNodeInput>>} --, e nao no componente, ao lado das outras
 * anotacoes. No componente ela simplesmente nao roda: o alvo da cascata seria o container, nao o
 * valor de dentro. O detalhe importa porque a falha e silenciosa, e as anotacoes de
 * {@link WorkflowNodeInput} ficariam decorativas; {@code UpdateWorkflowInputValidationTest} fixa
 * esse comportamento.</p>
 *
 * <p>{@code description} e {@code startNodeId} nao levam {@code @NotNull} de proposito: sao os dois
 * campos onde nulo e uma instrucao valida ("limpe a descricao", "volte a resolver o no inicial
 * sozinho"), e sao a razao de toda esta maquinaria existir.</p>
 *
 * @param name        novo nome; nulo e recusado
 * @param description nova descricao; nulo limpa o campo
 * @param trigger     nova configuracao de gatilho; nulo e recusado
 * @param nodes       novo grafo de nos; nulo ou vazio e recusado
 * @param startNodeId novo no inicial; nulo volta a resolucao automatica
 * @param enabled     novo estado de habilitacao; nulo e recusado
 */
public record UpdateWorkflowInput(
        @NotBlank(message = "name nao pode ser vazio")
        @Size(max = WorkflowDefinition.MAX_NAME_LENGTH,
                message = "name excede " + WorkflowDefinition.MAX_NAME_LENGTH + " caracteres")
        ArgumentValue<String> name,

        @Size(max = WorkflowDefinition.MAX_DESCRIPTION_LENGTH,
                message = "description excede " + WorkflowDefinition.MAX_DESCRIPTION_LENGTH + " caracteres")
        ArgumentValue<String> description,

        @NotNull(message = "trigger nao pode ser nulo")
        ArgumentValue<@Valid TriggerConfigInput> trigger,

        @NotEmpty(message = "o workflow precisa ter ao menos um no")
        @Size(max = WorkflowDefinition.MAX_NODES,
                message = "o workflow excede o limite de " + WorkflowDefinition.MAX_NODES + " nos")
        ArgumentValue<List<@Valid WorkflowNodeInput>> nodes,

        @Size(max = WorkflowDefinition.MAX_NODE_ID_LENGTH,
                message = "startNodeId excede " + WorkflowDefinition.MAX_NODE_ID_LENGTH + " caracteres")
        ArgumentValue<String> startNodeId,

        @NotNull(message = "enabled nao pode ser nulo")
        ArgumentValue<Boolean> enabled
) {
}
