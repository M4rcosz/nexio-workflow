package com.nexio.workflow.api.graphql;

import com.nexio.workflow.api.graphql.dto.CreateWorkflowInput;
import com.nexio.workflow.api.graphql.dto.UpdateWorkflowInput;
import com.nexio.workflow.api.graphql.dto.WorkflowDefinitionResponse;
import com.nexio.workflow.api.graphql.mapper.WorkflowGraphQlMapper;
import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.usecase.CreateWorkflowUseCase;
import com.nexio.workflow.application.usecase.DeleteWorkflowUseCase;
import com.nexio.workflow.application.usecase.GetWorkflowUseCase;
import com.nexio.workflow.application.usecase.ListWorkflowsUseCase;
import com.nexio.workflow.application.usecase.UpdateWorkflowUseCase;
import com.nexio.workflow.application.usecase.command.CreateWorkflowCommand;
import com.nexio.workflow.application.usecase.command.UpdateWorkflowCommand;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * Ponto de entrada GraphQL das definicoes de workflow.
 *
 * <p>Faz tres coisas por operacao -- traduzir a entrada, delegar ao caso de uso, traduzir a saida
 * -- e nada mais. Nenhuma regra de negocio mora aqui: quem decide o que e valido e o dominio, quem
 * orquestra e o caso de uso, e quem traduz e o {@link WorkflowGraphQlMapper}. A consequencia
 * pratica e que trocar o protocolo (ou expor um segundo) nao toca em nada abaixo desta classe.</p>
 *
 * <p>As anotacoes de validacao nos argumentos nao sao decorativas: o
 * {@code AnnotatedControllerConfigurer} do spring-graphql monta um validador a partir do bean
 * {@code Validator} do contexto e valida, antes de invocar o metodo, todo parametro anotado com
 * {@code @Valid} ou com alguma restricao. Sem elas, um input invalido chegaria ao caso de uso. Nao
 * ha {@code @Validated} na classe de proposito: ele faria o Spring criar um proxy para validar de
 * novo o que ja seria validado aqui.</p>
 *
 * <p>{@code limit} e {@code offset} chegam como {@code Integer} e nao como {@code int}: o schema ja
 * da valor padrao, mas o cliente pode enviar {@code limit: null} explicitamente, e um nulo
 * vinculado a primitivo estoura na vinculacao, antes de qualquer validacao, com um erro que nao diz
 * nada. Nulo aqui e tratado como "nao informou", que e o que ele significa.</p>
 */
@Controller
public class WorkflowResolver {

    /**
     * Teto do identificador aceito nos argumentos. Um id legitimo e o UUID gerado na criacao; o
     * limite existe para que um texto arbitrario de tamanho ilimitado nao chegue a virar consulta
     * nem linha de log.
     */
    private static final int MAX_ID_LENGTH = 64;

    /**
     * Teto do deslocamento aceito na listagem.
     *
     * <p>Paginacao por deslocamento vira {@code skip} no MongoDB, e {@code skip} nao pula: o
     * servidor percorre e descarta um documento por unidade. Sem teto, {@code offset: 2147483647} e
     * uma varredura completa pedida em quatro bytes, repetivel a vontade e por qualquer um. O limite
     * fica bem acima do numero de definicoes que um uso legitimo cadastra -- e ninguem chega na
     * pagina {@value #MAX_OFFSET} clicando "proxima" --, entao ele nao aparece para quem esta
     * paginando de verdade. Quando existir busca sobre um volume maior que isso, a resposta e
     * paginacao por cursor, nao um teto maior.</p>
     */
    private static final int MAX_OFFSET = 10_000;

    private final CreateWorkflowUseCase createWorkflowUseCase;
    private final UpdateWorkflowUseCase updateWorkflowUseCase;
    private final DeleteWorkflowUseCase deleteWorkflowUseCase;
    private final GetWorkflowUseCase getWorkflowUseCase;
    private final ListWorkflowsUseCase listWorkflowsUseCase;

    /**
     * Cria o resolver com injecao por construtor.
     *
     * @param createWorkflowUseCase caso de uso de criacao
     * @param updateWorkflowUseCase caso de uso de atualizacao parcial
     * @param deleteWorkflowUseCase caso de uso de remocao
     * @param getWorkflowUseCase    caso de uso de consulta por identificador
     * @param listWorkflowsUseCase  caso de uso de listagem
     */
    public WorkflowResolver(
            CreateWorkflowUseCase createWorkflowUseCase,
            UpdateWorkflowUseCase updateWorkflowUseCase,
            DeleteWorkflowUseCase deleteWorkflowUseCase,
            GetWorkflowUseCase getWorkflowUseCase,
            ListWorkflowsUseCase listWorkflowsUseCase) {
        this.createWorkflowUseCase = createWorkflowUseCase;
        this.updateWorkflowUseCase = updateWorkflowUseCase;
        this.deleteWorkflowUseCase = deleteWorkflowUseCase;
        this.getWorkflowUseCase = getWorkflowUseCase;
        this.listWorkflowsUseCase = listWorkflowsUseCase;
    }

    /**
     * Lista as definicoes dentro do recorte informado.
     *
     * @param limit       quantidade maxima de registros, de 1 a {@value PageQuery#MAX_LIMIT}
     * @param offset      quantidade de registros a pular, de 0 a {@value #MAX_OFFSET}
     * @param enabledOnly {@code true} para restringir as definicoes habilitadas
     * @return definicoes encontradas
     */
    @QueryMapping
    public List<WorkflowDefinitionResponse> workflows(
            @Argument @Min(1) @Max(PageQuery.MAX_LIMIT) Integer limit,
            @Argument @Min(0) @Max(MAX_OFFSET) Integer offset,
            @Argument Boolean enabledOnly) {
        PageQuery page = new PageQuery(
                limit == null ? PageQuery.DEFAULT_LIMIT : limit,
                offset == null ? 0 : offset);
        return WorkflowGraphQlMapper.toResponses(
                listWorkflowsUseCase.execute(page, Boolean.TRUE.equals(enabledOnly)));
    }

    /**
     * Busca uma definicao pelo identificador.
     *
     * @param id identificador da definicao
     * @return definicao encontrada
     */
    @QueryMapping
    public WorkflowDefinitionResponse workflow(@Argument @NotBlank @Size(max = MAX_ID_LENGTH) String id) {
        return WorkflowGraphQlMapper.toResponse(getWorkflowUseCase.execute(id));
    }

    /**
     * Cria uma nova definicao.
     *
     * @param input dados da definicao a criar
     * @return definicao criada
     */
    @MutationMapping
    public WorkflowDefinitionResponse createWorkflow(@Argument @Valid CreateWorkflowInput input) {
        CreateWorkflowCommand command = translate(() -> WorkflowGraphQlMapper.toCommand(input));
        return WorkflowGraphQlMapper.toResponse(createWorkflowUseCase.execute(command));
    }

    /**
     * Aplica uma atualizacao parcial sobre uma definicao existente.
     *
     * @param id    identificador da definicao
     * @param input campos a alterar; o que nao foi enviado permanece como esta
     * @return definicao atualizada
     */
    @MutationMapping
    public WorkflowDefinitionResponse updateWorkflow(
            @Argument @NotBlank @Size(max = MAX_ID_LENGTH) String id,
            @Argument @Valid UpdateWorkflowInput input) {
        UpdateWorkflowCommand command = translate(() -> WorkflowGraphQlMapper.toCommand(input));
        return WorkflowGraphQlMapper.toResponse(updateWorkflowUseCase.execute(id, command));
    }

    /**
     * Executa a traducao da entrada convertendo a recusa do dominio em excecao de dominio.
     *
     * <p>A traducao nao e uma copia inocente de campos: montar {@code WorkflowNode} e
     * {@code TriggerConfig} dispara os construtores compactos dos records, e a copia defensiva dos
     * mapas livres feita ali recusa estrutura patologica -- entrada aninhada mais fundo do que o
     * proprio BSON aceita, por exemplo, que o analisador do graphql-java deixa passar sem
     * dificuldade. Esse ponto fica fora de qualquer caso de uso, entao a recusa saia daqui como
     * {@code IllegalArgumentException} e chegava ao cliente como erro interno.</p>
     *
     * <p>O tratamento e local, e nao um braco generico para {@code IllegalArgumentException} no
     * resolver de erros: la ele valeria para o servico inteiro e transformaria qualquer bug de
     * programacao -- {@code IllegalArgumentException} tambem sai de biblioteca -- em "entrada
     * invalida", que e a leitura errada e some com a falha do log.</p>
     *
     * @param translation traducao a executar
     * @param <T>         tipo do comando produzido
     * @return o comando traduzido
     * @throws InvalidWorkflowException quando a entrada e recusada pelo dominio
     */
    private <T> T translate(Supplier<T> translation) {
        try {
            return translation.get();
        } catch (IllegalArgumentException e) {
            throw new InvalidWorkflowException(e.getMessage(), e);
        }
    }

    /**
     * Remove uma definicao.
     *
     * @param id identificador da definicao
     * @return sempre {@code true}; a ausencia vira erro {@code NOT_FOUND}, nao {@code false}
     */
    @MutationMapping
    public boolean deleteWorkflow(@Argument @NotBlank @Size(max = MAX_ID_LENGTH) String id) {
        deleteWorkflowUseCase.execute(id);
        return true;
    }

    /**
     * Habilita uma definicao.
     *
     * @param id identificador da definicao
     * @return definicao atualizada
     */
    @MutationMapping
    public WorkflowDefinitionResponse activateWorkflow(@Argument @NotBlank @Size(max = MAX_ID_LENGTH) String id) {
        return setEnabled(id, true);
    }

    /**
     * Desabilita uma definicao.
     *
     * @param id identificador da definicao
     * @return definicao atualizada
     */
    @MutationMapping
    public WorkflowDefinitionResponse deactivateWorkflow(@Argument @NotBlank @Size(max = MAX_ID_LENGTH) String id) {
        return setEnabled(id, false);
    }

    private WorkflowDefinitionResponse setEnabled(String id, boolean enabled) {
        UpdateWorkflowCommand command = UpdateWorkflowCommand.builder().enabled(enabled).build();
        return WorkflowGraphQlMapper.toResponse(updateWorkflowUseCase.execute(id, command));
    }
}
