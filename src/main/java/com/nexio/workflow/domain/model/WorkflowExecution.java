package com.nexio.workflow.domain.model;

import com.nexio.workflow.domain.model.enums.ExecutionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Registro de uma execucao de {@link WorkflowDefinition}, com o resultado de cada no percorrido.
 *
 * <p>O historico e ordenado por {@code createdAt}, e nao por {@code startedAt}: a execucao nasce
 * PENDING e so ganha {@code startedAt} em {@link #markRunning(Instant)}, e no MongoDB o nulo
 * ordena como o menor valor. Ordenar por {@code startedAt} jogaria a execucao recem disparada para
 * o fim de uma lista "mais recentes primeiro" -- justamente a que o usuario esta esperando ver.</p>
 *
 * <p>O indice {@code exec_status_created} existe para a consulta por estado. A colecao cresce um
 * documento por disparo e nao tem teto, entao {@code findByStatus} sem indice seria uma varredura
 * que piora sozinha com o tempo. O {@code createdAt} entra como segunda chave porque o uso previsto
 * e recolher execucoes travadas em RUNNING ha mais de X, que e um filtro por estado com recorte
 * por data.</p>
 */
@Document(collection = "workflow_executions")
// O '_id' no fim nao e enfeite: 'createdAt' nao e unico -- varios disparos do mesmo workflow
// caem no mesmo instante -- e ordenacao com empate nao tem ordem definida. Com skip/limit por
// cima disso, duas paginas consecutivas podem repetir uma execucao e pular outra, e o cliente nao
// tem como perceber. O '_id' e o criterio de desempate que torna a ordem total. Ele precisa estar
// no indice porque o sort so e servido pelo indice se casar com ele; sem isso a consulta passaria
// a ordenar em memoria. O nome do indice mudou junto com a definicao de proposito: alterar a
// definicao mantendo o nome derruba toda instancia no meio de um rollout com IndexOptionsConflict
// -- ver MongoIndexInitializer.
@CompoundIndex(name = "exec_workflow_created_id", def = "{'workflowId': 1, 'createdAt': -1, '_id': -1}")
@CompoundIndex(name = "exec_status_created", def = "{'status': 1, 'createdAt': 1}")
public class WorkflowExecution {

    /**
     * Numero maximo de passos registrados em uma execucao. Um ciclo no grafo faria o documento
     * crescer indefinidamente ate o limite de 16MB do BSON.
     */
    public static final int MAX_STEPS = 200;

    /**
     * Tamanho maximo da mensagem de erro guardada. O texto vem de {@code getMessage()} de excecao
     * e e cortado, nunca rejeitado: ver {@link TextSanitizer}.
     */
    public static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    @Id
    private String id;

    private String workflowId;

    private ExecutionStatus status = ExecutionStatus.PENDING;

    private Map<String, Object> triggerPayload = new LinkedHashMap<>();

    private List<ExecutionStep> steps = new ArrayList<>();

    /**
     * Versao de bloqueio otimista. Tambem faz o Spring Data reconhecer o documento como novo
     * mesmo com id atribuido pela aplicacao.
     */
    @Version
    private Long version;

    /**
     * Momento em que a execucao foi registrada. Preenchido pela auditoria do Spring Data e usado
     * como criterio de ordenacao do historico, porque existe desde o primeiro save, ainda PENDING.
     */
    @CreatedDate
    private Instant createdAt;

    private Instant startedAt;

    private Instant finishedAt;

    private String errorMessage;

    public WorkflowExecution() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    /**
     * Devolve o estado atual da execucao.
     *
     * <p>Nao existe {@code setStatus} publico de proposito: o estado so muda pelas transicoes
     * {@link #markRunning(Instant)}, {@link #markSucceeded(Instant)} e
     * {@link #markFailed(String, Instant)}, que verificam a maquina de estados. A hidratacao pelo
     * Spring Data nao precisa do setter porque o mapeamento escreve direto no campo.</p>
     *
     * @return estado atual da execucao
     */
    public ExecutionStatus getStatus() {
        return status;
    }

    /**
     * Devolve o payload do gatilho como visao imutavel: o mapa interno nunca escapa.
     *
     * @return mapa imutavel com o payload do gatilho
     */
    public Map<String, Object> getTriggerPayload() {
        return Collections.unmodifiableMap(triggerPayload);
    }

    /**
     * Copia em profundidade o payload do gatilho, que vem de fonte nao confiavel.
     *
     * <p>A copia e leniente ({@link MapSanitizer#copy(Map, String)}); a politica estrita e aplicada
     * na escrita, pelo callback de persistencia.</p>
     *
     * @param triggerPayload payload recebido, pode ser nulo
     */
    public void setTriggerPayload(Map<String, Object> triggerPayload) {
        this.triggerPayload = MapSanitizer.copy(triggerPayload, "triggerPayload");
    }

    /**
     * Devolve os passos como visao imutavel; use {@link #addStep(ExecutionStep)} para registrar novos.
     *
     * @return lista imutavel de passos
     */
    public List<ExecutionStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    /**
     * Substitui a lista de passos, aplicando o mesmo teto de {@link #addStep(ExecutionStep)}.
     *
     * <p>Sem o teto aqui, quem chamasse {@code setSteps} passaria direto pelo limite que
     * {@code addStep} protege. A hidratacao do Spring Data nao passa por este setter (o mapeamento
     * escreve direto no campo), entao a regra vale so para codigo da aplicacao e nao impede reler
     * um documento antigo acima do limite.</p>
     *
     * @param steps passos da execucao, pode ser nulo
     * @throws IllegalArgumentException quando a lista excede {@value #MAX_STEPS} passos
     */
    public void setSteps(List<ExecutionStep> steps) {
        if (steps != null && steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException(
                    "Limite de " + MAX_STEPS + " passos excedido na execucao '" + id + "': " + steps.size());
        }
        this.steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
    }

    /**
     * Registra um passo executado.
     *
     * @param step passo a registrar
     * @throws IllegalStateException quando o limite de {@value #MAX_STEPS} passos e atingido
     */
    public void addStep(ExecutionStep step) {
        Objects.requireNonNull(step, "step nao pode ser nulo");
        if (steps.size() >= MAX_STEPS) {
            throw new IllegalStateException(
                    "Limite de " + MAX_STEPS + " passos atingido na execucao '" + id + "'");
        }
        steps.add(step);
    }

    /**
     * Marca a execucao como em andamento.
     *
     * @param startedAt momento de inicio
     * @throws IllegalStateException quando a execucao nao esta PENDING
     */
    public void markRunning(Instant startedAt) {
        Objects.requireNonNull(startedAt, "startedAt nao pode ser nulo");
        requireCurrentStatus(ExecutionStatus.PENDING, ExecutionStatus.RUNNING);
        this.status = ExecutionStatus.RUNNING;
        this.startedAt = startedAt;
    }

    /**
     * Marca a execucao como concluida com sucesso.
     *
     * @param finishedAt momento de termino
     * @throws IllegalStateException quando a execucao nao esta RUNNING
     */
    public void markSucceeded(Instant finishedAt) {
        Objects.requireNonNull(finishedAt, "finishedAt nao pode ser nulo");
        requireCurrentStatus(ExecutionStatus.RUNNING, ExecutionStatus.SUCCESS);
        this.status = ExecutionStatus.SUCCESS;
        this.finishedAt = finishedAt;
        this.errorMessage = null;
    }

    /**
     * Marca a execucao como falha.
     *
     * <p>A mensagem e higienizada e cortada em {@value #MAX_ERROR_MESSAGE_LENGTH} caracteres, nunca
     * rejeitada: lancar aqui seria falhar ao registrar uma falha e perder a causa original.</p>
     *
     * @param errorMessage mensagem de erro
     * @param finishedAt   momento de termino
     * @throws IllegalStateException quando a execucao nao esta RUNNING
     */
    public void markFailed(String errorMessage, Instant finishedAt) {
        Objects.requireNonNull(errorMessage, "errorMessage nao pode ser nulo");
        Objects.requireNonNull(finishedAt, "finishedAt nao pode ser nulo");
        requireCurrentStatus(ExecutionStatus.RUNNING, ExecutionStatus.FAILED);
        this.status = ExecutionStatus.FAILED;
        this.errorMessage = TextSanitizer.truncateSystemText(errorMessage, MAX_ERROR_MESSAGE_LENGTH);
        this.finishedAt = finishedAt;
    }

    /**
     * Guarda da maquina de estados {@code PENDING -> RUNNING -> (SUCCESS | FAILED)}.
     *
     * <p>SUCCESS e FAILED sao terminais. Sem esta guarda, um retry assincrono que chamasse
     * {@code markSucceeded} depois de um {@code markFailed} apagaria a mensagem de erro e a
     * execucao reportaria sucesso para uma rodada que falhou.</p>
     *
     * @param required estado exigido para a transicao
     * @param target   estado de destino, usado na mensagem de erro
     * @throws IllegalStateException quando a execucao nao esta no estado exigido
     */
    private void requireCurrentStatus(ExecutionStatus required, ExecutionStatus target) {
        if (status != required) {
            throw new IllegalStateException(
                    "Transicao de estado invalida na execucao '" + id + "': " + status + " -> " + target
                            + ". Transicoes validas: PENDING -> RUNNING -> SUCCESS ou FAILED");
        }
    }

    /**
     * Devolve a versao de bloqueio otimista.
     *
     * <p>Nao existe {@code setVersion} publico de proposito: a versao pertence a infraestrutura de
     * persistencia. Uma mutacao de atualizacao que vinculasse um {@code version} enviado pelo
     * cliente anularia o bloqueio otimista, e um {@code null} faria o Spring Data tratar a
     * entidade como nova e inserir por cima. A hidratacao nao precisa do setter, porque o
     * mapeamento escreve direto no campo.</p>
     *
     * @return versao atual, {@code null} enquanto o documento nunca foi gravado
     */
    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Define a mensagem de erro, higienizada e cortada em {@value #MAX_ERROR_MESSAGE_LENGTH}
     * caracteres.
     *
     * @param errorMessage mensagem de erro, pode ser nula
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = TextSanitizer.truncateSystemText(errorMessage, MAX_ERROR_MESSAGE_LENGTH);
    }
}
