package com.nexio.workflow.infrastructure.mongodb;

import com.mongodb.client.result.UpdateResult;
import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.exception.WorkflowConcurrentlyModifiedException;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.ExecutionStep;
import com.nexio.workflow.domain.model.MapSanitizer;
import com.nexio.workflow.domain.model.WorkflowExecution;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Adaptador que implementa {@link WorkflowExecutionPort} sobre o Spring Data MongoDB.
 *
 * <p>Este e o unico ponto do sistema que conhece {@link WorkflowExecutionMongoRepository}: a
 * assinatura dos metodos publicos so expoe tipos do JDK e do dominio, entao nada do Spring Data
 * vaza para a camada de aplicacao.</p>
 */
@Repository
public class WorkflowExecutionMongoAdapter implements WorkflowExecutionPort {

    /** Nome do array de passos no documento; usado nos filtros e na atualizacao parcial. */
    private static final String STEPS_FIELD = "steps";

    private final WorkflowExecutionMongoRepository repository;
    private final MongoOperations mongoOperations;

    /**
     * Cria o adaptador com injecao por construtor.
     *
     * <p>As duas dependencias convivem porque servem a caminhos diferentes: o repositorio derivado
     * cobre as consultas e o {@code save} com {@code @Version}, e o {@link MongoOperations} e o
     * unico caminho para uma atualizacao parcial de documento, que e o que {@link #appendStep} faz.
     * O repositorio nao expoe {@code $push}.</p>
     *
     * @param repository      repositorio Spring Data das execucoes
     * @param mongoOperations operacoes de baixo nivel, usadas so pelo acrescimo de passo
     */
    public WorkflowExecutionMongoAdapter(WorkflowExecutionMongoRepository repository,
                                         MongoOperations mongoOperations) {
        this.repository = repository;
        this.mongoOperations = mongoOperations;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A falha de bloqueio otimista e traduzida aqui, e nao repassada: a porta promete nao vazar
     * tipo do Spring Data, e {@code OptimisticLockingFailureException} e exatamente isso. A mensagem
     * original fica de fora porque carrega o nome da colecao e o filtro BSON cru da atualizacao que
     * falhou.</p>
     */
    @Override
    public WorkflowExecution save(WorkflowExecution execution) {
        try {
            return repository.save(execution);
        } catch (OptimisticLockingFailureException e) {
            throw new WorkflowConcurrentlyModifiedException(execution.getId(), e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>A gravacao e um {@code $push}: o servidor acrescenta um elemento ao array e nao toca no
     * resto do documento. E esse -- preservar o resto do documento -- o ganho real, e nao economia
     * de bloqueio.</p>
     *
     * <p><b>A versao E incrementada.</b> O {@code updateFirst} do Spring Data adiciona um
     * {@code $inc} na propriedade {@code @Version} de toda entidade versionada. A ADR 0004 supunha o
     * contrario e a correcao 1 dela retrata isso; a suposicao errada custou caro, porque depois de N
     * passos o documento fica na versao N enquanto o agregado em memoria continua na versao com que
     * nasceu, e o {@code save} final -- que passa pelo bloqueio otimista -- e recusado. Toda execucao
     * com um no sequer falhava. Por isso a
     * {@link com.nexio.workflow.application.engine.WorkflowEngine} rele a execucao antes de gravar o
     * estado terminal: <b>quem remover aquela releitura por parecer zelo desnecessario reintroduz o
     * defeito</b>.</p>
     *
     * <p>O que o {@code $push} de fato dispensa e o {@code BeforeConvertCallback}, que <b>nao</b>
     * roda.</p>
     *
     * <p>O callback que nao roda e o motivo de a validacao aparecer explicitamente aqui. Ele e a
     * costura que garante que nenhuma escrita da execucao escapa da politica estrita do
     * {@link MapSanitizer}; um {@code $push} cru gravaria uma chave {@code $where} dentro de
     * {@code steps[].output} sem nada reclamar, e o {@code output} e a resposta de um servico de
     * terceiro -- o dado menos confiavel que chega a gravacao neste projeto.</p>
     *
     * <p>O teto de passos vai no <b>filtro</b>, e nao numa contagem previa: {@code steps.N} com
     * {@code $exists: false} significa "o array tem menos de N+1 elementos" e e avaliado pelo
     * servidor dentro da mesma operacao atomica. Contar antes e empurrar depois deixaria a janela
     * em que dois acrescimos concorrentes leem a mesma contagem e ambos passam.</p>
     *
     * <p>Por isso o filtro que nao casa e ambiguo -- ou a execucao nao existe, ou ela ja esta no
     * teto --, e a segunda consulta existe so para dizer qual dos dois foi. Ela custa uma ida ao
     * banco apenas no caminho de erro.</p>
     */
    @Override
    public void appendStep(String executionId, ExecutionStep step) {
        Objects.requireNonNull(executionId, "executionId nao pode ser nulo");
        Objects.requireNonNull(step, "step nao pode ser nulo");
        try {
            MapSanitizer.validate(step.output(), "steps[].output");
        } catch (IllegalArgumentException e) {
            throw new InvalidWorkflowException(e.getMessage(), e);
        }
        Query query = Query.query(Criteria.where("_id").is(executionId)
                .and(STEPS_FIELD + "." + (WorkflowExecution.MAX_STEPS - 1)).exists(false));
        UpdateResult result = mongoOperations.updateFirst(
                query, new Update().push(STEPS_FIELD, step), WorkflowExecution.class);
        if (result.getMatchedCount() == 0) {
            throw appendRejection(executionId);
        }
    }

    /**
     * Explica um {@code $push} que nao casou com documento nenhum.
     *
     * <p>A execucao inexistente sai como {@link WorkflowNotFoundException} e nao como falha de
     * validacao porque nao ha nada de errado com o passo: o documento sumiu entre o inicio da
     * execucao e o passo atual, o que acontece quando a definicao e apagada no meio do caminho e a
     * remocao cascateia para as execucoes. A engine trata a recusa de validacao marcando a execucao
     * como FAILED, e seria justamente o que <b>nao</b> daria para fazer com um documento que nao
     * existe mais.</p>
     */
    private RuntimeException appendRejection(String executionId) {
        boolean exists = mongoOperations.exists(
                Query.query(Criteria.where("_id").is(executionId)), WorkflowExecution.class);
        if (exists) {
            return new InvalidWorkflowException(
                    "Limite de " + WorkflowExecution.MAX_STEPS + " passos atingido na execucao '"
                            + executionId + "'");
        }
        return new WorkflowNotFoundException(executionId);
    }

    @Override
    public Optional<WorkflowExecution> findById(String id) {
        return repository.findById(id);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A ordem decrescente por {@code createdAt} e a util para o historico de execucoes e e
     * servida pelo indice composto {@code exec_workflow_created} ja declarado na entidade. O
     * recorte do dominio vira um {@link OffsetPageable} aqui dentro: nenhum tipo do Spring Data
     * aparece na assinatura da porta.</p>
     *
     * <p>O recorte vai sem ordem propria porque a consulta ja tem a dela no nome. Um {@code Sort} no
     * {@code Pageable} nao substituiria aquela ordem, seria somado a ela como desempate, e o par
     * {@code createdAt} decrescente mais {@code _id} deixaria de casar com o indice.</p>
     */
    @Override
    public List<WorkflowExecution> findByWorkflowId(String workflowId, PageQuery page) {
        return repository.findByWorkflowIdOrderByCreatedAtDesc(workflowId, OffsetPageable.unsorted(page));
    }

    @Override
    public long deleteByWorkflowId(String workflowId) {
        return repository.deleteByWorkflowId(workflowId);
    }
}
