package com.nexio.workflow.infrastructure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.nexio.workflow.AbstractMongoIntegrationTest;
import com.nexio.workflow.WriteValidationTestConfig;
import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.exception.WorkflowConcurrentlyModifiedException;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.ExecutionStep;
import com.nexio.workflow.domain.model.WorkflowExecution;
import com.nexio.workflow.domain.model.enums.ExecutionStatus;
import com.nexio.workflow.domain.model.enums.StepStatus;
import com.nexio.workflow.infrastructure.config.MongoConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Teste de integracao do {@link WorkflowExecutionMongoAdapter} contra um MongoDB real
 * (Testcontainers).
 *
 * <p>O adaptador e injetado tipado como {@link WorkflowExecutionPort}: o teste exercita o contrato
 * da porta, e nao a implementacao.</p>
 *
 * <p>Nomeado {@code ...Test} e nao {@code ...IT} de proposito: o surefire so executa
 * {@code *Test.java} e este teste precisa rodar no {@code ./mvnw test}.</p>
 */
@DataMongoTest
@Import({MongoConfig.class,
        WriteValidationTestConfig.class,
        WorkflowExecutionMongoAdapter.class,
        WorkflowExecutionWriteValidationCallback.class})
class WorkflowExecutionMongoAdapterTest extends AbstractMongoIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-01-15T10:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private WorkflowExecutionPort port;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private WorkflowExecutionMongoRepository repository;

    @Test
    void saveAndFindByIdRoundTripsTheWholeAggregate() {
        WorkflowExecution saved = port.save(execution("exec-save", "wf-1", BASE));

        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();

        Optional<WorkflowExecution> found = port.findById("exec-save");

        assertThat(found).isPresent();
        WorkflowExecution reread = found.orElseThrow();
        assertThat(reread.getWorkflowId()).isEqualTo("wf-1");
        assertThat(reread.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(reread.getStartedAt()).isEqualTo(BASE);
        assertThat(reread.getTriggerPayload()).containsEntry("orderId", 42);
        assertThat(reread.getSteps()).extracting(ExecutionStep::nodeId).containsExactly("start");
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertThat(port.findById("exec-inexistente")).isEmpty();
    }

    /**
     * Caminho de atualizacao: ler, mexer no agregado e gravar de novo. E o unico caminho que o
     * Sprint 3 usa e o unico onde {@code @Version}, os getters imutaveis e a hidratacao por campo
     * interagem -- todos os outros testes salvavam um agregado recem construido.
     */
    @Test
    void updatingARereadAggregateBumpsTheVersionAndKeepsEveryStep() {
        port.save(execution("exec-update", "wf-1", BASE));

        WorkflowExecution reread = port.findById("exec-update").orElseThrow();
        assertThat(reread.getVersion()).isZero();
        reread.addStep(new ExecutionStep("segundo", StepStatus.SUCCESS, Map.of("statusCode", 204),
                null, BASE.plusSeconds(10)));

        WorkflowExecution updated = port.save(reread);

        assertThat(updated.getVersion()).isOne();
        assertThat(updated.getSteps()).extracting(ExecutionStep::nodeId).containsExactly("start", "segundo");

        WorkflowExecution afterUpdate = port.findById("exec-update").orElseThrow();
        assertThat(afterUpdate.getVersion()).isOne();
        assertThat(afterUpdate.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(afterUpdate.getCreatedAt()).isEqualTo(updated.getCreatedAt());
        assertThat(afterUpdate.getSteps()).extracting(ExecutionStep::nodeId).containsExactly("start", "segundo");
        assertThat(afterUpdate.getSteps().get(1).output()).containsEntry("statusCode", 204);
    }

    /**
     * Duas leituras do mesmo documento, duas gravacoes: o que acontece com a segunda.
     *
     * <p>RESULTADO OBSERVADO: a segunda gravacao falha e <b>nao</b> altera o documento. O
     * {@code @Version} nao e decorativo: a atualizacao vai ao banco com o filtro
     * {@code {_id, version: 0}}, a primeira gravacao ja levou a versao para 1, e o filtro da
     * segunda nao casa com documento algum. A escrita perdida vira erro em vez de sobrescrever em
     * silencio o passo gravado pela primeira.</p>
     *
     * <p>O que chega ao chamador e {@link WorkflowConcurrentlyModifiedException}, e nao a
     * {@code OptimisticLockingFailureException} do Spring Data que a origina. Este teste declara a
     * variavel como {@link WorkflowExecutionPort} exatamente para exercitar o contrato da porta, e o
     * contrato diz que nenhum tipo de infraestrutura atravessa -- afirmar aqui o tipo do Spring Data
     * era afirmar a violacao. A traducao mora no adaptador, que e onde esse vocabulario pode
     * aparecer.</p>
     */
    @Test
    void secondSaveOfAStaleInstanceFailsWithTheDomainConflict() {
        port.save(execution("exec-concorrente", "wf-1", BASE));

        WorkflowExecution first = port.findById("exec-concorrente").orElseThrow();
        WorkflowExecution second = port.findById("exec-concorrente").orElseThrow();
        assertThat(first.getVersion()).isZero();
        assertThat(second.getVersion()).isZero();

        first.addStep(new ExecutionStep("primeiro", StepStatus.SUCCESS, Map.of(), null, BASE.plusSeconds(10)));
        assertThat(port.save(first).getVersion()).isOne();

        second.addStep(new ExecutionStep("segundo", StepStatus.SUCCESS, Map.of(), null, BASE.plusSeconds(20)));
        assertThatExceptionOfType(WorkflowConcurrentlyModifiedException.class)
                .isThrownBy(() -> port.save(second))
                .satisfies(error -> {
                    assertThat(error.workflowId()).isEqualTo("exec-concorrente");
                    assertThat(error.getMessage()).doesNotContain("workflow_executions");
                    assertThat(error.getCause()).isInstanceOf(OptimisticLockingFailureException.class);
                });

        WorkflowExecution persisted = port.findById("exec-concorrente").orElseThrow();
        assertThat(persisted.getVersion()).isOne();
        assertThat(persisted.getSteps()).extracting(ExecutionStep::nodeId).containsExactly("start", "primeiro");
    }

    /**
     * Responde se as estruturas aninhadas continuam imutaveis depois de relidas do banco.
     *
     * <p>RESULTADO OBSERVADO: <b>depende do campo</b>, e a assimetria e real.</p>
     * <ul>
     *   <li>{@code triggerPayload}: a visao devolvida pelo getter e imutavel, porque o proprio
     *       getter embrulha em {@code unmodifiableMap}. Mas o mapa <b>aninhado</b> dentro dela e um
     *       {@code LinkedHashMap} comum e aceita mutacao, que fica visivel na entidade. A copia
     *       profunda do {@code MapSanitizer} so roda no setter, e o Spring Data nao usa setter na
     *       hidratacao: escreve direto no campo o que o conversor montou.</li>
     *   <li>{@code steps[].output}: imutavel ate o fundo, inclusive relido. O construtor compacto
     *       do record {@code ExecutionStep} <b>roda</b> na hidratacao, entao o
     *       {@code MapSanitizer.copy} refaz a estrutura toda como imutavel.</li>
     * </ul>
     *
     * <p>Ou seja, no caminho de leitura ganha quem constroi o objeto: record com construtor
     * compacto fica imutavel, campo escrito diretamente nao. Este teste trava esse comportamento
     * para que uma mudanca futura no mapeamento nao o inverta em silencio.</p>
     */
    @Test
    void hydrationLeavesNestedTriggerPayloadMutableButStepOutputImmutable() {
        WorkflowExecution execution = pendingExecution("exec-mutabilidade", "wf-1");
        execution.markRunning(BASE);
        execution.addStep(new ExecutionStep("start", StepStatus.SUCCESS,
                Map.of("body", Map.of("total", 10)), null, BASE));
        execution.markSucceeded(BASE.plusSeconds(5));
        port.save(execution);

        WorkflowExecution reread = port.findById("exec-mutabilidade").orElseThrow();

        Map<String, Object> payload = reread.getTriggerPayload();
        assertThat(payload).isNotEmpty();
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> payload.put("novo", 1));

        @SuppressWarnings("unchecked")
        Map<String, Object> nestedHeaders = (Map<String, Object>) payload.get("headers");
        assertThatCode(() -> nestedHeaders.put("x_injetado", "sim")).doesNotThrowAnyException();
        assertThat(reread.getTriggerPayload().get("headers"))
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("x_injetado", "sim");

        Map<String, Object> output = reread.getSteps().getFirst().output();
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> output.put("novo", 1));
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedOutput = (Map<String, Object>) output.get("body");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> nestedOutput.put("novo", 1));
    }

    /**
     * Semeia fora de ordem de insercao de proposito: se o adaptador esquecesse a ordenacao, a
     * asercao cairia na ordem natural de leitura da colecao.
     */
    @Test
    void findByWorkflowIdReturnsOnlyThatWorkflowNewestFirst() {
        seed(execution("exec-meio", "wf-1", BASE.plus(1, ChronoUnit.HOURS)), BASE.plus(1, ChronoUnit.HOURS));
        seed(execution("exec-antigo", "wf-1", BASE), BASE);
        seed(execution("exec-recente", "wf-1", BASE.plus(2, ChronoUnit.HOURS)), BASE.plus(2, ChronoUnit.HOURS));
        seed(execution("exec-outro-wf", "wf-2", BASE.plus(3, ChronoUnit.HOURS)), BASE.plus(3, ChronoUnit.HOURS));

        List<WorkflowExecution> found = port.findByWorkflowId("wf-1", PageQuery.firstPage());

        assertThat(found)
                .extracting(WorkflowExecution::getId)
                .containsExactly("exec-recente", "exec-meio", "exec-antigo");
        assertThat(found).extracting(WorkflowExecution::getWorkflowId).containsOnly("wf-1");
    }

    /**
     * Uma execucao recem disparada ainda esta PENDING e tem {@code startedAt} nulo. Como o nulo
     * ordena como menor valor no MongoDB, ordenar por {@code startedAt} a jogava para o fim da
     * lista de "mais recentes primeiro" -- justamente a execucao que o usuario acabou de disparar e
     * esta esperando ver. A ordenacao passou a ser por {@code createdAt}, que existe desde o
     * primeiro save.
     *
     * <p>O teste anterior nao pegava isso porque so semeava execucoes que ja tinham passado por
     * {@code markRunning}.</p>
     */
    @Test
    void findByWorkflowIdPutsJustTriggeredPendingExecutionFirst() {
        seed(execution("exec-antigo", "wf-1", BASE), BASE);
        seed(execution("exec-recente", "wf-1", BASE.plus(2, ChronoUnit.HOURS)), BASE.plus(2, ChronoUnit.HOURS));
        WorkflowExecution pending = seed(pendingExecution("exec-recem-disparada", "wf-1"),
                BASE.plus(3, ChronoUnit.HOURS));

        assertThat(pending.getStartedAt()).isNull();
        assertThat(pending.getStatus()).isEqualTo(ExecutionStatus.PENDING);

        assertThat(port.findByWorkflowId("wf-1", PageQuery.firstPage()))
                .extracting(WorkflowExecution::getId)
                .containsExactly("exec-recem-disparada", "exec-recente", "exec-antigo");
    }

    /**
     * A colecao cresce um documento por disparo: a porta so devolve o recorte pedido.
     */
    @Test
    void findByWorkflowIdHonoursLimitAndOffset() {
        seed(execution("exec-1", "wf-1", BASE), BASE);
        seed(execution("exec-2", "wf-1", BASE.plus(1, ChronoUnit.HOURS)), BASE.plus(1, ChronoUnit.HOURS));
        seed(execution("exec-3", "wf-1", BASE.plus(2, ChronoUnit.HOURS)), BASE.plus(2, ChronoUnit.HOURS));

        assertThat(port.findByWorkflowId("wf-1", new PageQuery(2, 0)))
                .extracting(WorkflowExecution::getId)
                .containsExactly("exec-3", "exec-2");
        assertThat(port.findByWorkflowId("wf-1", new PageQuery(2, 2)))
                .extracting(WorkflowExecution::getId)
                .containsExactly("exec-1");
    }

    @Test
    void findByWorkflowIdReturnsEmptyForUnknownWorkflow() {
        port.save(execution("exec-unico", "wf-1", BASE));

        assertThat(port.findByWorkflowId("wf-sem-execucoes", PageQuery.firstPage())).isEmpty();
    }

    /**
     * Consulta declarada desde o inicio e nunca exercitada: o bootstrap do contexto so prova que
     * ela e derivavel, nao que ela casa com o campo certo.
     */
    @Test
    void findByStatusMatchesTheExecutionStatusField() {
        port.save(execution("exec-ok-1", "wf-1", BASE));
        port.save(execution("exec-ok-2", "wf-2", BASE));
        port.save(failedExecution("exec-falha", "wf-1", BASE));
        port.save(pendingExecution("exec-pendente", "wf-1"));

        assertThat(repository.findByStatus(ExecutionStatus.SUCCESS))
                .extracting(WorkflowExecution::getId)
                .containsExactlyInAnyOrder("exec-ok-1", "exec-ok-2");
        assertThat(repository.findByStatus(ExecutionStatus.FAILED))
                .extracting(WorkflowExecution::getId)
                .containsExactly("exec-falha");
        assertThat(repository.findByStatus(ExecutionStatus.PENDING))
                .extracting(WorkflowExecution::getId)
                .containsExactly("exec-pendente");
        assertThat(repository.findByStatus(ExecutionStatus.RUNNING)).isEmpty();
    }

    /**
     * O payload do gatilho e mapa livre vindo de fora: a politica estrita vale na escrita, aplicada
     * pelo callback de persistencia, independentemente de quem chamou o save.
     *
     * <p>A recusa sai como {@link InvalidWorkflowException} porque descreve o que o chamador
     * enviou: o {@code IllegalArgumentException} cru atravessava a porta e virava erro interno na
     * resposta.</p>
     */
    @Test
    void saveRejectsOperatorKeysInTriggerPayloadThroughTheWriteCallback() {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId("exec-hostil");
        execution.setWorkflowId("wf-1");
        execution.setTriggerPayload(Map.of("$where", "1"));

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> port.save(execution))
                .withMessageContaining("'$'");
        assertThat(port.findById("exec-hostil")).isEmpty();
    }

    /**
     * O teste que a {@code docs/adr/0004-execution-step-persistence.md} nomeia como condicao para a
     * decisao valer.
     *
     * <p>O {@code $push} passa por fora do {@code BeforeConvertCallback}, que era a costura por onde
     * toda escrita da execucao passava. Sem a validacao explicita dentro de {@code appendStep}, uma
     * chave de operador chegaria gravada dentro de {@code steps[].output} -- e {@code output} e o
     * corpo da resposta de um servico de terceiro, o dado menos confiavel que chega a gravacao neste
     * projeto. Sem este teste, a ADR seria so uma intencao.</p>
     */
    @Test
    void appendStepRejectsOperatorKeysInStepOutput() {
        port.save(execution("exec-append-hostil", "wf-1", BASE));

        ExecutionStep hostile = new ExecutionStep("no-http", StepStatus.SUCCESS,
                Map.of("$where", "1"), null, BASE.plusSeconds(10));

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> port.appendStep("exec-append-hostil", hostile))
                .withMessageContaining("'$'");

        assertThat(port.findById("exec-append-hostil").orElseThrow().getSteps())
                .extracting(ExecutionStep::nodeId)
                .containsExactly("start");
    }

    /**
     * A recusa vale em qualquer profundidade do {@code output}: um corpo de resposta aninhado e o
     * caso normal, nao a excecao.
     */
    @Test
    void appendStepRejectsOperatorKeysNestedInsideTheStepOutput() {
        port.save(execution("exec-append-aninhado", "wf-1", BASE));

        ExecutionStep hostile = new ExecutionStep("no-http", StepStatus.SUCCESS,
                Map.of("body", Map.of("dados", List.of(Map.of("$ne", 1)))), null, BASE.plusSeconds(10));

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> port.appendStep("exec-append-aninhado", hostile))
                .withMessageContaining("'$'");
        assertThat(port.findById("exec-append-aninhado").orElseThrow().getSteps()).hasSize(1);
    }

    /**
     * Responde se o acrescimo de passo reescreve o documento.
     *
     * <p>RESULTADO OBSERVADO: <b>nao</b>. Duas instancias lidas antes de qualquer acrescimo, dois
     * acrescimos, e os dois passos sobrevivem -- porque o que vai ao servidor e um {@code $push} do
     * elemento novo, e nao o array inteiro de uma copia que ja nasceu velha. E a diferenca
     * exata para {@code secondSaveOfAStaleInstanceFailsWithTheDomainConflict}, logo acima, onde o
     * mesmo roteiro por {@code save} derruba a segunda gravacao.</p>
     *
     * <p>A versao continua zero depois dos dois acrescimos, e isso tambem e o esperado: o
     * {@code $push} nao passa pelo {@code @Version}. Para um array so de acrescimos e aceitavel --
     * o {@code $push} e atomico no servidor e dois passos concorrentes nao se sobrescrevem --, e e
     * justamente por isso que o {@code status} continua indo por {@code save}, onde o controle de
     * concorrencia importa.</p>
     */
    @Test
    void appendStepAddsOnlyTheNewElementSoConcurrentAppendsBothSurvive() {
        port.save(execution("exec-append-concorrente", "wf-1", BASE));

        WorkflowExecution first = port.findById("exec-append-concorrente").orElseThrow();
        WorkflowExecution second = port.findById("exec-append-concorrente").orElseThrow();
        assertThat(first.getVersion()).isZero();
        assertThat(second.getVersion()).isZero();

        port.appendStep(first.getId(),
                new ExecutionStep("primeiro", StepStatus.SUCCESS, Map.of("statusCode", 200), null, BASE.plusSeconds(10)));
        port.appendStep(second.getId(),
                new ExecutionStep("segundo", StepStatus.SUCCESS, Map.of("statusCode", 204), null, BASE.plusSeconds(20)));

        WorkflowExecution persisted = port.findById("exec-append-concorrente").orElseThrow();
        assertThat(persisted.getSteps())
                .extracting(ExecutionStep::nodeId)
                .containsExactly("start", "primeiro", "segundo");
        assertThat(persisted.getSteps().get(2).output()).containsEntry("statusCode", 204);
        // A versao vai a 2, e nao fica em 0: o updateFirst do Spring Data incrementa a @Version de
        // toda entidade versionada, entao o $push NAO passa por fora do bloqueio otimista como a
        // ADR 0004 supunha. O que o $push preserva e o resto do documento -- nenhum dos dois
        // agregados lidos na versao 0 sobrescreve o passo do outro --, e e isso que este teste
        // afirma. A consequencia do incremento vive na engine, que precisa reler antes de gravar o
        // estado terminal.
        assertThat(persisted.getVersion()).isEqualTo(2L);
        assertThat(persisted.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(persisted.getTriggerPayload()).containsEntry("orderId", 42);
    }

    /**
     * O teto de passos deixou de ser garantido pelo agregado no momento da montagem e passou a
     * depender desta verificacao no adaptador -- a ADR registra que e um lugar pior para uma
     * invariante de dominio e por que o preco vale a pena.
     *
     * <p>A checagem vai no filtro da propria atualizacao ({@code steps.199} nao pode existir), e nao
     * numa contagem previa: contar antes e empurrar depois deixaria a janela em que dois acrescimos
     * concorrentes leem a mesma contagem e ambos passam.</p>
     */
    @Test
    void appendStepStopsAtTheStepCeiling() {
        WorkflowExecution execution = pendingExecution("exec-append-teto", "wf-1");
        execution.markRunning(BASE);
        List<ExecutionStep> full = new ArrayList<>();
        for (int i = 0; i < WorkflowExecution.MAX_STEPS; i++) {
            full.add(new ExecutionStep("no-" + i, StepStatus.SUCCESS, Map.of(), null, BASE));
        }
        execution.setSteps(full);
        port.save(execution);

        ExecutionStep excedente = new ExecutionStep("no-excedente", StepStatus.SUCCESS, Map.of(), null, BASE);

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> port.appendStep("exec-append-teto", excedente))
                .withMessageContaining(String.valueOf(WorkflowExecution.MAX_STEPS));

        assertThat(port.findById("exec-append-teto").orElseThrow().getSteps())
                .hasSize(WorkflowExecution.MAX_STEPS);
    }

    /**
     * Documento inexistente e execucao apagada no meio do caminho -- a remocao em cascata de uma
     * definicao faz isso. Sai como "nao encontrado" e nao como falha de validacao porque nao ha nada
     * de errado com o passo, e porque marcar a execucao como FAILED, que e o que a engine faz diante
     * de uma recusa de validacao, e exatamente o que nao da para fazer com um documento que sumiu.
     */
    @Test
    void appendStepOnAnUnknownExecutionFailsAsNotFound() {
        ExecutionStep step = new ExecutionStep("no-http", StepStatus.SUCCESS, Map.of(), null, BASE);

        assertThatExceptionOfType(WorkflowNotFoundException.class)
                .isThrownBy(() -> port.appendStep("exec-que-nunca-existiu", step))
                .satisfies(error -> assertThat(error.workflowId()).isEqualTo("exec-que-nunca-existiu"));
    }

    @Test
    void deleteByWorkflowIdReturnsTheCountAndSparesOtherWorkflows() {
        port.save(execution("exec-1", "wf-1", BASE));
        port.save(execution("exec-2", "wf-1", BASE.plus(1, ChronoUnit.HOURS)));
        port.save(execution("exec-3", "wf-2", BASE.plus(2, ChronoUnit.HOURS)));

        assertThat(port.deleteByWorkflowId("wf-1")).isEqualTo(2L);

        assertThat(port.findByWorkflowId("wf-1", PageQuery.firstPage())).isEmpty();
        assertThat(port.findById("exec-1")).isEmpty();
        assertThat(port.findByWorkflowId("wf-2", PageQuery.firstPage()))
                .extracting(WorkflowExecution::getId).containsExactly("exec-3");
    }

    @Test
    void deleteByWorkflowIdReturnsZeroForUnknownWorkflow() {
        port.save(execution("exec-sobrevivente", "wf-1", BASE));

        assertThat(port.deleteByWorkflowId("wf-nunca-existiu")).isZero();
        assertThat(port.findByWorkflowId("wf-1", PageQuery.firstPage())).hasSize(1);
    }

    /**
     * Persiste e carimba {@code createdAt} com um valor controlado.
     *
     * <p>A auditoria preenche {@code createdAt} com o relogio do momento do save, e varios saves
     * seguidos podem cair no mesmo milissegundo, o que deixaria a ordenacao ambigua. O carimbo
     * posterior torna o teste deterministico sem abrir mao de exercitar a consulta real.</p>
     */
    private WorkflowExecution seed(WorkflowExecution execution, Instant createdAt) {
        WorkflowExecution saved = port.save(execution);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(saved.getId())),
                new Update().set("createdAt", createdAt),
                WorkflowExecution.class);
        saved.setCreatedAt(createdAt);
        return saved;
    }

    private WorkflowExecution execution(String id, String workflowId, Instant startedAt) {
        WorkflowExecution execution = pendingExecution(id, workflowId);
        execution.markRunning(startedAt);
        execution.addStep(new ExecutionStep("start", StepStatus.SUCCESS, Map.of("statusCode", 200), null, startedAt));
        execution.markSucceeded(startedAt.plusSeconds(5));
        return execution;
    }

    private WorkflowExecution failedExecution(String id, String workflowId, Instant startedAt) {
        WorkflowExecution execution = pendingExecution(id, workflowId);
        execution.markRunning(startedAt);
        execution.addStep(new ExecutionStep("start", StepStatus.FAILED, Map.of(), "boom", startedAt));
        execution.markFailed("boom", startedAt.plusSeconds(5));
        return execution;
    }

    private WorkflowExecution pendingExecution(String id, String workflowId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", 42);
        payload.put("headers", Map.of("x_trace", "abc-123"));
        payload.put("tags", List.of("a", "b"));

        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(id);
        execution.setWorkflowId(workflowId);
        execution.setTriggerPayload(payload);
        return execution;
    }
}
