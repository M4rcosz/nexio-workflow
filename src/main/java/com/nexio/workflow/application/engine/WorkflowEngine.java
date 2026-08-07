package com.nexio.workflow.application.engine;

import com.nexio.workflow.application.port.out.WorkflowExecutionPort;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.model.ExecutionStep;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowExecution;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.NodeType;
import com.nexio.workflow.domain.model.enums.StepStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Percorre o grafo de uma {@link WorkflowDefinition} e registra a execucao.
 *
 * <p>A execucao e <b>sincrona</b>: quem chama recebe a execucao ja terminada, com os passos. Nao ha
 * fronteira assincrona onde uma excecao possa se perder, e por isso nao existe execucao presa em
 * RUNNING sem dono -- ver {@code docs/adr/0005-synchronous-execution.md}, inclusive para o que
 * passa a ser obrigatorio no dia em que o disparo virar assincrono.</p>
 *
 * <p><b>Uma chamada a {@link #execute(WorkflowDefinition, Map)} sempre termina com uma execucao
 * gravada em estado terminal.</b> Grafo cuja raiz nao resolve, aresta apontando para no que nao
 * existe, tipo de no sem executor registrado, executor que estourou: tudo isso vira FAILED com
 * mensagem, e nao excecao subindo. A alternativa -- deixar o problema estrutural escapar como
 * excecao -- daria ao operador um erro de servidor no disparo e nenhum registro do que aconteceu,
 * que e a pior das duas respostas para quem esta olhando o historico depois.</p>
 *
 * <p>Tres limites param a caminhada, e os tres sao independentes:</p>
 * <ul>
 *   <li><b>Teto de tempo total.</b> Os tempos limite do cliente HTTP sao <i>por no</i>: garantem
 *       que uma chamada termina, nao que a execucao termina. Cinquenta nos de 15s somam 750s dentro
 *       de um request. O teto e verificado antes de cada no e, estourado, a execucao termina FAILED
 *       com mensagem propria. Ele <b>nao interrompe um no em andamento</b> -- a engine nao mata
 *       thread de executor --, entao o tempo real maximo e o teto mais a duracao do ultimo no; o
 *       que limita aquele ultimo no continua sendo o timeout por requisicao.</li>
 *   <li><b>Teto de passos</b> ({@value WorkflowExecution#MAX_STEPS}). E o unico limite que protege
 *       de reentrar num no indefinidamente. {@code validateGraph()} recusa ciclo na escrita, mas
 *       isso vale para o que foi gravado <i>depois</i> da regra existir: uma definicao antiga, ou
 *       gravada antes de uma regra de validacao nascer, pode ciclar em tempo de execucao. O teto e
 *       o anteparo, e por isso ele para a caminhada em vez de deixar o {@code addStep} do agregado
 *       estourar.</li>
 *   <li><b>Falha de no.</b> Qualquer no com desfecho {@link NodeOutcome#FAILURE} encerra a
 *       caminhada; nao existe "continuar apesar do erro" no Sprint 3.</li>
 * </ul>
 *
 * <p><b>O que esta classe deliberadamente nao faz:</b> nao verifica se a definicao esta habilitada
 * nem se o gatilho recebido corresponde ao configurado, e nao recebe {@code ActorId}. Isso e do
 * caso de uso de disparo (issue #24), que e a fronteira; a engine e chamada com uma definicao que
 * alguem ja decidiu executar.</p>
 */
public class WorkflowEngine {

    private final Map<NodeType, NodeExecutor> executorsByType;
    private final WorkflowExecutionPort executionPort;
    private final Clock clock;
    private final Duration maxExecutionDuration;

    /**
     * Cria a engine com injecao por construtor, sem nenhum tipo de infraestrutura.
     *
     * <p>O {@link Clock} entra como dependencia e nao como {@code Instant.now()} espalhado pelo
     * codigo porque o teto de tempo total precisa ser verificavel: sem ele, o unico teste possivel
     * do caminho de estouro seria um teste que dorme.</p>
     *
     * @param nodeExecutors        executores disponiveis, um por tipo de no; pode vir vazia
     *                             enquanto nenhum executor concreto existe
     * @param executionPort        porta de persistencia das execucoes
     * @param clock                relogio usado para os carimbos e para o teto de tempo
     * @param maxExecutionDuration teto de tempo total de uma execucao
     * @throws IllegalStateException    quando dois executores declaram o mesmo tipo de no
     * @throws IllegalArgumentException quando o teto de tempo nao e positivo
     */
    public WorkflowEngine(List<NodeExecutor> nodeExecutors,
                          WorkflowExecutionPort executionPort,
                          Clock clock,
                          Duration maxExecutionDuration) {
        this.executorsByType = indexByType(nodeExecutors);
        this.executionPort = Objects.requireNonNull(executionPort, "executionPort nao pode ser nulo");
        this.clock = Objects.requireNonNull(clock, "clock nao pode ser nulo");
        this.maxExecutionDuration = requirePositive(maxExecutionDuration);
    }

    /**
     * Executa a definicao com o payload do gatilho e devolve a execucao terminada.
     *
     * <p>A execucao nasce PENDING e vai a RUNNING antes da primeira gravacao, respeitando a maquina
     * de estados do agregado. O documento e criado ja RUNNING de proposito: no modelo sincrono
     * ninguem consegue observar a janela PENDING, e uma ida ao banco so para registra-la custaria
     * um round-trip por disparo em troca de nada.</p>
     *
     * <p>Cada passo e acrescentado por {@code appendStep}, que grava so o elemento novo do array; o
     * espelho em memoria existe porque a gravacao final do estado terminal e um {@code save} do
     * agregado inteiro, e um agregado sem os passos apagaria o que foi empurrado. So entra no
     * espelho o passo que a gravacao aceitou.</p>
     *
     * @param definition     definicao a executar
     * @param triggerPayload payload do gatilho, pode ser nulo
     * @return execucao terminada, em SUCCESS ou FAILED, com os passos registrados
     * @throws InvalidWorkflowException quando o proprio payload do gatilho viola a politica de
     *         escrita -- ai nao existe execucao para registrar, porque nem a criacao passou
     */
    public WorkflowExecution execute(WorkflowDefinition definition, Map<String, Object> triggerPayload) {
        Objects.requireNonNull(definition, "definition nao pode ser nulo");
        WorkflowExecution execution = createRunning(definition, triggerPayload);
        String failure = walk(definition, execution);
        Instant finishedAt = clock.instant();

        WorkflowExecution terminal = reloadForTerminalWrite(execution);
        if (failure == null) {
            terminal.markSucceeded(finishedAt);
        } else {
            terminal.markFailed(failure, finishedAt);
        }
        return executionPort.save(terminal);
    }

    /**
     * Rele a execucao antes de gravar o estado terminal.
     *
     * <p><b>Nao e zelo: sem isto nenhuma execucao com um no sequer termina.</b> O
     * {@code appendStep} do adaptador usa {@code updateFirst}, e o Spring Data <b>incrementa a
     * {@code @Version}</b> em toda atualizacao de entidade versionada. Depois de N passos o
     * documento esta na versao N enquanto o agregado em memoria continua na versao com que nasceu,
     * e o {@code save} final -- que passa pelo bloqueio otimista -- e recusado com conflito. O
     * defeito atravessa camadas: os testes de unidade da engine usam porta falsa, que nao simula o
     * incremento, entao a suite inteira passava com toda execucao quebrada.</p>
     *
     * <p>A releitura tambem torna o espelho em memoria desnecessario para <i>esta</i> gravacao: o
     * documento relido ja traz os passos que o {@code $push} acrescentou. O espelho continua
     * existindo porque a engine precisa contar os passos durante a caminhada sem ir ao banco a cada
     * no.</p>
     *
     * <p>A janela entre a releitura e o {@code save} continua protegida pela versao: se alguem
     * alterar a execucao nesse intervalo -- um cancelamento, no dia em que existir --, o conflito
     * volta a ser levantado, que e o comportamento desejado. O que se corrigiu foi o conflito com
     * as proprias gravacoes da engine, que nunca foi concorrencia de verdade.</p>
     */
    private WorkflowExecution reloadForTerminalWrite(WorkflowExecution execution) {
        return executionPort.findById(execution.getId()).orElse(execution);
    }

    private WorkflowExecution createRunning(WorkflowDefinition definition, Map<String, Object> triggerPayload) {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(UUID.randomUUID().toString());
        execution.setWorkflowId(definition.getId());
        execution.setTriggerPayload(triggerPayload);
        execution.markRunning(clock.instant());
        return executionPort.save(execution);
    }

    /**
     * Caminha pelo grafo a partir do no inicial.
     *
     * @return {@code null} quando a caminhada chegou ao fim, ou a mensagem da falha que a
     *         interrompeu
     */
    private String walk(WorkflowDefinition definition, WorkflowExecution execution) {
        Map<String, WorkflowNode> byId = new HashMap<>();
        for (WorkflowNode node : definition.getNodes()) {
            byId.put(node.nodeId(), node);
        }
        String currentId;
        try {
            currentId = definition.resolveStartNodeId();
        } catch (IllegalArgumentException e) {
            return "Nao foi possivel resolver o no inicial do workflow: " + e.getMessage();
        }
        Instant deadline = execution.getStartedAt().plus(maxExecutionDuration);
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        while (currentId != null) {
            String halt = haltReason(execution, deadline, currentId);
            if (halt != null) {
                return halt;
            }
            WorkflowNode node = byId.get(currentId);
            if (node == null) {
                return "Aresta do grafo aponta para o no inexistente '" + currentId
                        + "': a definicao gravada nao satisfaz mais a validacao de arestas";
            }
            NodeExecutionResult result = runNode(node, execution, outputs);
            String rejected = recordStep(execution, node, result);
            if (rejected != null) {
                return rejected;
            }
            outputs.put(node.nodeId(), result.output());
            if (result.outcome() == NodeOutcome.FAILURE) {
                return "No '" + node.nodeId() + "' falhou: " + result.error();
            }
            String mismatch = mismatchReason(node, result);
            if (mismatch != null) {
                return mismatch;
            }
            currentId = nextNodeId(node, result.outcome());
        }
        return null;
    }

    /**
     * Verifica, antes de executar um no, os dois limites que independem do que o no faz.
     *
     * <p>O teto de passos e conferido primeiro porque ele e o anteparo do ciclo em tempo de
     * execucao: um grafo que ciclasse rapido estouraria o numero de passos muito antes do tempo, e
     * a mensagem certa e a que aponta o motivo real de parar.</p>
     */
    private String haltReason(WorkflowExecution execution, Instant deadline, String currentId) {
        if (execution.getSteps().size() >= WorkflowExecution.MAX_STEPS) {
            return "Limite de " + WorkflowExecution.MAX_STEPS + " passos atingido antes do no '"
                    + currentId + "': o grafo provavelmente reentra em nos ja executados";
        }
        if (clock.instant().isAfter(deadline)) {
            return "Tempo limite total de execucao (" + maxExecutionDuration + ") excedido antes do no '"
                    + currentId + "'";
        }
        return null;
    }

    /**
     * Executa um no, transformando em falha tudo o que o executor nao tratou.
     *
     * <p>O {@code catch} de {@link RuntimeException} nao e zelo generico: sem ele, um defeito de
     * executor (issues #22 e #23) sobe pela engine e a execucao fica gravada RUNNING para sempre,
     * exatamente o estado orfao que a ADR 0005 diz nao existir no modelo sincrono. O nome simples da
     * excecao entra na mensagem e o resto da pilha nao: a mensagem vira campo do documento e
     * resposta ao chamador.</p>
     */
    private NodeExecutionResult runNode(WorkflowNode node,
                                        WorkflowExecution execution,
                                        Map<String, Map<String, Object>> outputs) {
        NodeExecutor executor = executorsByType.get(node.type());
        if (executor == null) {
            return NodeExecutionResult.failure(
                    "nenhum executor registrado para o tipo " + node.type());
        }
        NodeExecutionContext context = new NodeExecutionContext(
                execution.getId(), execution.getTriggerPayload(), outputs);
        try {
            NodeExecutionResult result = executor.execute(node, context);
            return result == null
                    ? NodeExecutionResult.failure("o executor de " + node.type() + " devolveu resultado nulo")
                    : result;
        } catch (RuntimeException e) {
            return NodeExecutionResult.failure(
                    "o executor de " + node.type() + " lancou " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
        }
    }

    /**
     * Grava o passo e o espelha no agregado.
     *
     * <p>A gravacao vem primeiro, e o espelho so recebe o que ela aceitou. A ordem importa porque o
     * {@code output} pode ser o corpo de um servico de terceiro e {@code appendStep} aplica a
     * politica estrita: se o passo for recusado e ainda assim entrar no agregado, o {@code save}
     * final e recusado pelo mesmo motivo -- e ai a execucao termina sem estado terminal gravado,
     * presa em RUNNING.</p>
     *
     * @return {@code null} quando o passo foi gravado, ou a mensagem da recusa
     */
    private String recordStep(WorkflowExecution execution, WorkflowNode node, NodeExecutionResult result) {
        StepStatus status = result.outcome() == NodeOutcome.FAILURE ? StepStatus.FAILED : StepStatus.SUCCESS;
        ExecutionStep step = new ExecutionStep(
                node.nodeId(), status, result.output(), result.error(), clock.instant());
        try {
            executionPort.appendStep(execution.getId(), step);
        } catch (InvalidWorkflowException e) {
            return "Passo do no '" + node.nodeId() + "' recusado na gravacao: " + e.getMessage();
        }
        execution.addStep(step);
        return null;
    }

    /**
     * Recusa o desfecho que nao combina com o tipo do no.
     *
     * <p>A checagem e simetrica, e as duas metades tem consequencia concreta. Um no CONDITION que
     * devolvesse {@link NodeOutcome#SUCCESS} seguiria por {@code nextOnSuccess}, que a validacao
     * proibe de existir num CONDITION: a caminhada terminaria ali, em silencio, e a execucao seria
     * gravada como SUCCESS tendo pulado metade do workflow. Um no HTTP que devolvesse um ramo de
     * condicao seguiria por {@code nextOnTrue}, que num no nao condicional e sempre nulo, com o
     * mesmo desfecho. Falhar e a unica resposta que nao inventa um caminho.</p>
     */
    private String mismatchReason(WorkflowNode node, NodeExecutionResult result) {
        boolean conditionalNode = node.type() == NodeType.CONDITION;
        if (result.isConditional() == conditionalNode) {
            return null;
        }
        return "Executor do no '" + node.nodeId() + "' devolveu o desfecho " + result.outcome()
                + ", incompativel com um no do tipo " + node.type();
    }

    private String nextNodeId(WorkflowNode node, NodeOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> node.nextOnSuccess();
            case CONDITION_TRUE -> node.nextOnTrue();
            case CONDITION_FALSE -> node.nextOnFalse();
            case FAILURE -> null;
        };
    }

    /**
     * Indexa os executores por tipo uma unica vez, na construcao.
     *
     * <p>Dois executores para o mesmo tipo derrubam a criacao da engine em vez de um deles vencer
     * pela ordem da lista: a lista chega do contexto do Spring, a ordem dela nao e contratada em
     * lugar nenhum, e o efeito de escolher em silencio seria todo no daquele tipo passar a ser
     * executado por outra implementacao depois de qualquer mudanca de classpath.</p>
     */
    private static Map<NodeType, NodeExecutor> indexByType(List<NodeExecutor> nodeExecutors) {
        Objects.requireNonNull(nodeExecutors, "nodeExecutors nao pode ser nulo");
        Map<NodeType, NodeExecutor> byType = new EnumMap<>(NodeType.class);
        for (NodeExecutor executor : nodeExecutors) {
            NodeType type = Objects.requireNonNull(
                    executor.supportedType(), "supportedType do executor nao pode ser nulo");
            NodeExecutor previous = byType.put(type, executor);
            if (previous != null) {
                throw new IllegalStateException(
                        "Dois executores declaram o tipo de no " + type + ": "
                                + previous.getClass().getSimpleName() + " e "
                                + executor.getClass().getSimpleName());
            }
        }
        return byType;
    }

    private static Duration requirePositive(Duration maxExecutionDuration) {
        Objects.requireNonNull(maxExecutionDuration, "maxExecutionDuration nao pode ser nulo");
        if (maxExecutionDuration.isZero() || maxExecutionDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "O teto de tempo total da execucao precisa ser positivo: " + maxExecutionDuration);
        }
        return maxExecutionDuration;
    }
}
