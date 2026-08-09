package com.nexio.workflow.api.graphql;

import com.nexio.workflow.api.graphql.dto.WorkflowExecutionResponse;
import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.CurrentActorPort;
import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.usecase.GetExecutionUseCase;
import com.nexio.workflow.application.usecase.ListExecutionsUseCase;
import com.nexio.workflow.application.usecase.TriggerWorkflowUseCase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * Ponto de entrada GraphQL das execucoes.
 *
 * <p>Separado do {@link WorkflowResolver} porque sao dois recursos: um cuida da receita e o outro
 * do historico de quem a rodou. Juntar os dois numa classe so daria um controlador com oito
 * dependencias cujo unico tema em comum e "GraphQL".</p>
 *
 * <p>Vale aqui tudo o que vale la, e pelos mesmos motivos: o ator vem de {@link CurrentActorPort} e
 * nunca de argumento; as anotacoes de validacao sao efetivas porque o
 * {@code AnnotatedControllerConfigurer} valida antes de invocar o metodo; e {@code limit} e
 * {@code offset} chegam como {@code Integer} para que um {@code null} explicito do cliente
 * signifique "nao informou" em vez de estourar na vinculacao.</p>
 *
 * <p><b>Toda a redacao acontece nos construtores dos DTOs de resposta, e nao aqui.</b> Este resolver
 * nao pode esquecer de mascarar nada porque nao tem como: {@code WorkflowExecutionResponse} e
 * {@code ExecutionStepResponse} redigem no construtor canonico, entao nao existe caminho de
 * construcao que devolva o valor cru. Isso importa mais nas execucoes do que nas definicoes -- o
 * {@code output} de um passo e o corpo de resposta de um terceiro, guardado inteiro, e a resposta
 * de um endpoint de autenticacao traz {@code token} sem que ninguem tenha escrito credencial no
 * workflow.</p>
 */
@Controller
public class ExecutionResolver {

    /** Mesmo teto de identificador do {@link WorkflowResolver}, e pelo mesmo motivo. */
    private static final int MAX_ID_LENGTH = 64;

    /**
     * Teto do deslocamento aceito na listagem.
     *
     * <p>Vale o argumento do {@link WorkflowResolver}, e com mais forca: a colecao de execucoes
     * cresce um documento por disparo, sem teto, enquanto a de definicoes cresce por cadastro. Um
     * {@code skip} sem limite aqui e uma varredura completa pedida em quatro bytes sobre a colecao
     * que mais cresce.</p>
     */
    private static final int MAX_OFFSET = 10_000;

    private final TriggerWorkflowUseCase triggerWorkflowUseCase;
    private final GetExecutionUseCase getExecutionUseCase;
    private final ListExecutionsUseCase listExecutionsUseCase;
    private final CurrentActorPort currentActorPort;

    /**
     * Cria o resolver com injecao por construtor.
     *
     * @param triggerWorkflowUseCase caso de uso de disparo
     * @param getExecutionUseCase    caso de uso de consulta por identificador
     * @param listExecutionsUseCase  caso de uso de listagem
     * @param currentActorPort       porta que informa o ator da requisicao em curso
     */
    public ExecutionResolver(TriggerWorkflowUseCase triggerWorkflowUseCase,
                             GetExecutionUseCase getExecutionUseCase,
                             ListExecutionsUseCase listExecutionsUseCase,
                             CurrentActorPort currentActorPort) {
        this.triggerWorkflowUseCase = triggerWorkflowUseCase;
        this.getExecutionUseCase = getExecutionUseCase;
        this.listExecutionsUseCase = listExecutionsUseCase;
        this.currentActorPort = currentActorPort;
    }

    /**
     * Lista as execucoes de um workflow, da mais recente para a mais antiga.
     *
     * @param workflowId identificador da definicao
     * @param limit      quantidade maxima de registros, de 1 a {@value PageQuery#MAX_LIMIT}
     * @param offset     quantidade de registros a pular, de 0 a {@value #MAX_OFFSET}
     * @return execucoes encontradas, ja redigidas
     */
    @QueryMapping
    public List<WorkflowExecutionResponse> executions(
            @Argument @NotBlank @Size(max = MAX_ID_LENGTH) String workflowId,
            @Argument @Min(1) @Max(PageQuery.MAX_LIMIT) Integer limit,
            @Argument @Min(0) @Max(MAX_OFFSET) Integer offset) {
        PageQuery page = new PageQuery(
                limit == null ? PageQuery.DEFAULT_LIMIT : limit,
                offset == null ? 0 : offset);
        return listExecutionsUseCase.execute(actor(), workflowId, page).stream()
                .map(WorkflowExecutionResponse::from)
                .toList();
    }

    /**
     * Busca uma execucao pelo identificador.
     *
     * @param id identificador da execucao
     * @return execucao encontrada, ja redigida
     */
    @QueryMapping
    public WorkflowExecutionResponse execution(
            @Argument @NotBlank @Size(max = MAX_ID_LENGTH) String id) {
        return WorkflowExecutionResponse.from(getExecutionUseCase.execute(actor(), id));
    }

    /**
     * Dispara um workflow e devolve a execucao ja terminada.
     *
     * <p>Bloqueia ate a caminhada acabar, porque a execucao e sincrona. O teto de tempo da engine e
     * o que impede um disparo de prender a thread indefinidamente -- ver
     * {@code docs/adr/0005-synchronous-execution.md}.</p>
     *
     * @param id      identificador da definicao a executar
     * @param payload evento que dispara a execucao, pode ser omitido
     * @return execucao terminada, ja redigida
     */
    @MutationMapping
    public WorkflowExecutionResponse triggerWorkflow(
            @Argument @NotBlank @Size(max = MAX_ID_LENGTH) String id,
            @Argument Map<String, Object> payload) {
        return WorkflowExecutionResponse.from(
                triggerWorkflowUseCase.execute(actor(), id, payload));
    }

    private ActorId actor() {
        return currentActorPort.currentActor();
    }
}
