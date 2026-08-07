package com.nexio.workflow.domain.model;

import com.nexio.workflow.domain.model.enums.NodeType;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Definicao de um workflow: o gatilho e o grafo de nos que sera executado.
 *
 * <p>Sao dois indices sobre {@code triggerConfig.type} de proposito. O composto
 * {@code def_enabled_trigger} serve a consulta por habilitadas e a combinacao
 * {@code enabled + tipo}, mas <b>nao</b> serve a consulta por tipo sozinho: {@code triggerConfig
 * .type} nao e prefixo de {@code {enabled, triggerConfig.type}}, entao {@code findByTriggerConfig
 * Type} varreria a colecao inteira. O {@code def_trigger_type} cobre esse caso.</p>
 */
@Document(collection = "workflow_definitions")
@CompoundIndex(name = "def_enabled_trigger", def = "{'enabled': 1, 'triggerConfig.type': 1}")
@CompoundIndex(name = "def_trigger_type", def = "{'triggerConfig.type': 1}")
public class WorkflowDefinition {

    /** Numero maximo de nos permitido em um workflow. */
    public static final int MAX_NODES = 50;

    /** Tamanho maximo do nome, que vem do usuario. */
    public static final int MAX_NAME_LENGTH = 200;

    /** Tamanho maximo da descricao, que vem do usuario. */
    public static final int MAX_DESCRIPTION_LENGTH = 2000;

    /** Tamanho maximo de {@code startNodeId}, alinhado ao teto do proprio id de no. */
    public static final int MAX_NODE_ID_LENGTH = 64;

    /**
     * Tamanho maximo da URL de um no HTTP_REQUEST.
     *
     * <p>2048 e o limite pratico que navegadores e servidores adotam ha muito tempo; uma URL de
     * workflow legitima fica ordens de grandeza abaixo disso. O teto existe declarado porque antes
     * ele era acidental: a URL era um valor de string dentro de {@code config} e herdava o
     * {@code MapSanitizer.MAX_STRING_LENGTH}, que nao foi escolhido pensando em endereco.</p>
     */
    public static final int MAX_URL_LENGTH = 2048;

    /**
     * Tamanho maximo da expressao de um no CONDITION.
     *
     * <p>Mesma historia da URL -- o limite era herdado por acidente -- mas aqui ele tambem e uma
     * medida de contencao: a expressao vai ser avaliada por SpEL, e o custo de avaliacao cresce com
     * o tamanho da expressao. Um teto baixo e a defesa mais barata contra expressao patologica, e
     * 512 caracteres e muito mais do que uma condicao de workflow legivel precisa. Ver
     * {@code docs/adr/0002-spel-sandbox.md}.</p>
     */
    public static final int MAX_EXPRESSION_LENGTH = 512;

    private static final Pattern NODE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final int COLOR_WHITE = 0;
    private static final int COLOR_GREY = 1;
    private static final int COLOR_BLACK = 2;

    @Id
    private String id;

    private String name;

    private String description;

    private boolean enabled;

    private TriggerConfig triggerConfig;

    private List<WorkflowNode> nodes = new ArrayList<>();

    private String startNodeId;

    /**
     * Versao de bloqueio otimista. Tambem faz o Spring Data reconhecer o documento como novo:
     * sem ela, com id atribuido pela aplicacao, a auditoria de {@code createdAt} nunca dispararia.
     */
    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public WorkflowDefinition() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    /**
     * Define o nome do workflow, aplicando o teto de {@value #MAX_NAME_LENGTH} caracteres.
     *
     * <p>Rejeita em vez de cortar: o nome vem do usuario, que pode corrigi-lo. A hidratacao do
     * Spring Data nao passa por este setter (o mapeamento escreve direto no campo), entao a regra
     * vale so para codigo da aplicacao e nao impede reler um documento antigo acima do limite.</p>
     *
     * @param name nome do workflow, pode ser nulo
     * @throws IllegalArgumentException quando o nome excede {@value #MAX_NAME_LENGTH} caracteres
     */
    public void setName(String name) {
        this.name = TextSanitizer.requireWithin(name, MAX_NAME_LENGTH, "name");
    }

    public String getDescription() {
        return description;
    }

    /**
     * Define a descricao do workflow, aplicando o teto de {@value #MAX_DESCRIPTION_LENGTH}
     * caracteres.
     *
     * @param description descricao do workflow, pode ser nula
     * @throws IllegalArgumentException quando a descricao excede {@value #MAX_DESCRIPTION_LENGTH}
     *         caracteres
     */
    public void setDescription(String description) {
        this.description = TextSanitizer.requireWithin(description, MAX_DESCRIPTION_LENGTH, "description");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public TriggerConfig getTriggerConfig() {
        return triggerConfig;
    }

    public void setTriggerConfig(TriggerConfig triggerConfig) {
        this.triggerConfig = triggerConfig;
    }

    /**
     * Devolve os nos como visao imutavel: a colecao interna nunca escapa.
     *
     * @return lista imutavel de nos
     */
    public List<WorkflowNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * Substitui os nos do workflow, aplicando o teto de {@value #MAX_NODES} nos.
     *
     * <p>O teto tambem e verificado em {@link #validateGraph()}, mas ali so vale para quem chama a
     * validacao; aqui ele fecha o caminho de quem chama apenas o setter. A hidratacao do Spring
     * Data nao passa por este setter (o mapeamento escreve direto no campo), entao a regra vale so
     * para codigo da aplicacao e nao impede reler um documento antigo acima do limite.</p>
     *
     * @param nodes nos do workflow, pode ser nulo
     * @throws IllegalArgumentException quando a lista excede {@value #MAX_NODES} nos
     */
    public void setNodes(List<WorkflowNode> nodes) {
        if (nodes != null && nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException(
                    "O workflow excede o limite de " + MAX_NODES + " nos: " + nodes.size());
        }
        this.nodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
    }

    public String getStartNodeId() {
        return startNodeId;
    }

    /**
     * Define o no inicial, aplicando o teto de {@value #MAX_NODE_ID_LENGTH} caracteres.
     *
     * <p>O valor so e confrontado com os nos existentes em {@link #validateGraph()}; aqui o teto
     * fecha o caminho de quem chama apenas o setter, para que um texto arbitrario de tamanho
     * ilimitado nao chegue ao documento.</p>
     *
     * @param startNodeId identificador do no inicial, pode ser nulo
     * @throws IllegalArgumentException quando excede {@value #MAX_NODE_ID_LENGTH} caracteres
     */
    public void setStartNodeId(String startNodeId) {
        this.startNodeId = TextSanitizer.requireWithin(startNodeId, MAX_NODE_ID_LENGTH, "startNodeId");
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Valida as invariantes do grafo de nos.
     *
     * <p>Nao e chamado por {@link #setNodes(List)} de proposito: a entidade precisa continuar
     * hidratavel a partir do MongoDB sem disparar validacao. Quem garante a chamada e o callback de
     * persistencia, que roda em todo save independentemente do caso de uso que o disparou.</p>
     *
     * <p>A ordem das verificacoes importa. A deteccao de ciclo roda <b>antes</b> da resolucao do no
     * inicial: um grafo inteiramente ciclico sem {@code startNodeId} nao tem no algum sem aresta de
     * entrada, e a checagem do no inicial reportava "precisa ter exatamente um no sem aresta de
     * entrada, mas foram encontrados 0" -- uma mensagem que aponta para o lugar errado. Com o ciclo
     * verificado primeiro, o erro relatado e o ciclo, que e a causa real.</p>
     *
     * @throws IllegalArgumentException quando alguma invariante do grafo e violada
     */
    public void validateGraph() {
        validateNodeIds();
        Map<String, WorkflowNode> byId = indexById();
        validateEdges(byId);
        validateAcyclic(byId);
        String effectiveStart = validateStartNode(byId);
        validateReachability(effectiveStart, byId);
        validateNodeParameters();
    }

    /**
     * Verifica que cada no traz os parametros do seu tipo e nenhum dos que nao usa.
     *
     * <p>Roda <b>depois</b> das checagens estruturais pelo mesmo motivo que a deteccao de ciclo roda
     * antes da resolucao do no inicial: quando os dois problemas existem, o relatado deve ser a
     * causa mais externa. Um grafo ciclico cujos nos tambem estao sem {@code url} tem dois defeitos,
     * e "precisa definir url" manda o autor consertar o no enquanto o ciclo -- que invalida o
     * desenho inteiro -- continua la.</p>
     */
    private void validateNodeParameters() {
        for (WorkflowNode node : nodes) {
            if (node.type() == NodeType.CONDITION) {
                validateConditionParameters(node);
            } else if (node.type() == NodeType.HTTP_REQUEST) {
                validateHttpRequestParameters(node);
            }
        }
    }

    /**
     * Aplica a politica estrita de {@link MapSanitizer} sobre os mapas livres da definicao.
     *
     * <p>Existe pelo mesmo motivo de {@link #validateGraph()} ser um metodo publico chamado pelo
     * caso de uso: sem uma chamada na fronteira de escrita, a unica coisa que roda esta validacao e
     * o callback de persistencia, e uma chave {@code $where} na config de um no chegava ao cliente
     * como erro interno, com pilha inteira no log, para o que e erro de digitacao dele.</p>
     *
     * <p>Nao e chamado por {@link #setNodes(List)} nem pelos construtores: a entidade precisa
     * continuar hidratavel a partir do MongoDB sem disparar regra estrita, que e a razao de a copia
     * do {@link MapSanitizer} ser leniente.</p>
     *
     * @throws IllegalArgumentException quando algum mapa livre viola a politica de escrita
     */
    public void validateConfigs() {
        for (WorkflowNode node : nodes) {
            MapSanitizer.validate(node.headers(), "nodes[" + node.nodeId() + "].headers");
            MapSanitizer.validate(node.body(), "nodes[" + node.nodeId() + "].body");
            MapSanitizer.validate(node.config(), "nodes[" + node.nodeId() + "].config");
        }
        if (triggerConfig != null) {
            MapSanitizer.validate(triggerConfig.config(), "triggerConfig.config");
        }
    }

    /**
     * Resolve o no por onde a execucao comeca, aplicando exatamente a regra de
     * {@link #validateGraph()}.
     *
     * <p>E publico porque a engine precisa comecar a caminhada onde a validacao diz que o grafo
     * comeca. A regra tem duas partes -- o {@code startNodeId} declarado tem precedencia e, sem
     * ele, vale o unico no sem aresta de entrada --, e reimplementa-la na engine criaria duas
     * fontes da mesma regra: no dia em que uma delas mudar, a engine passa a executar um workflow
     * diferente do que a validacao aprovou, sem nada reclamar.</p>
     *
     * <p>Nao valida o resto do grafo de proposito: quem chama isto ja tem a definicao gravada, e
     * recusar a execucao inteira por causa de uma regra que nasceu depois da gravacao seria pior
     * do que executar o que da para executar. As invariantes estruturais continuam sendo garantidas
     * na escrita.</p>
     *
     * @return identificador do no inicial efetivo
     * @throws IllegalArgumentException quando o {@code startNodeId} nao corresponde a nenhum no ou
     *         quando, sem ele, o grafo nao tem exatamente uma raiz
     */
    public String resolveStartNodeId() {
        return validateStartNode(indexById());
    }

    private Map<String, WorkflowNode> indexById() {
        Map<String, WorkflowNode> byId = new HashMap<>();
        for (WorkflowNode node : nodes) {
            byId.put(node.nodeId(), node);
        }
        return byId;
    }

    private void validateNodeIds() {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("O workflow precisa ter ao menos um no");
        }
        if (nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException(
                    "O workflow excede o limite de " + MAX_NODES + " nos: " + nodes.size());
        }
        Set<String> seen = new HashSet<>();
        for (WorkflowNode node : nodes) {
            String nodeId = node.nodeId();
            if (nodeId.isBlank()) {
                throw new IllegalArgumentException("Existe no com id em branco");
            }
            if (!NODE_ID_PATTERN.matcher(nodeId).matches()) {
                throw new IllegalArgumentException(
                        "Id de no invalido '" + nodeId + "': deve casar com ^[A-Za-z0-9_-]{1,64}$");
            }
            if (!seen.add(nodeId)) {
                throw new IllegalArgumentException("Id de no duplicado: '" + nodeId + "'");
            }
        }
    }

    private void validateEdges(Map<String, WorkflowNode> byId) {
        for (WorkflowNode node : nodes) {
            requireTarget(byId, node.nodeId(), "nextOnSuccess", node.nextOnSuccess());
            requireTarget(byId, node.nodeId(), "nextOnTrue", node.nextOnTrue());
            requireTarget(byId, node.nodeId(), "nextOnFalse", node.nextOnFalse());
            if (node.type() == NodeType.CONDITION) {
                validateConditionNode(node);
            } else {
                validateNonConditionNode(node);
            }
        }
    }

    private void validateConditionNode(WorkflowNode node) {
        if (node.nextOnTrue() == null || node.nextOnFalse() == null) {
            throw new IllegalArgumentException(
                    "No CONDITION '" + node.nodeId() + "' precisa definir nextOnTrue e nextOnFalse");
        }
        if (node.nextOnSuccess() != null) {
            throw new IllegalArgumentException(
                    "No CONDITION '" + node.nodeId() + "' nao pode definir nextOnSuccess");
        }
    }

    private void validateNonConditionNode(WorkflowNode node) {
        if (node.nextOnTrue() != null || node.nextOnFalse() != null) {
            throw new IllegalArgumentException(
                    "No '" + node.nodeId() + "' do tipo " + node.type()
                            + " nao pode definir nextOnTrue nem nextOnFalse");
        }
    }

    private void validateConditionParameters(WorkflowNode node) {
        if (node.expression() == null || node.expression().isBlank()) {
            throw new IllegalArgumentException(
                    "No CONDITION '" + node.nodeId() + "' precisa definir expression");
        }
        if (node.expression().length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException(
                    "expression do no '" + node.nodeId() + "' excede "
                            + MAX_EXPRESSION_LENGTH + " caracteres");
        }
        requireAbsent(node.url() == null, node, "url");
        requireAbsent(node.method() == null, node, "method");
        requireAbsent(node.headers().isEmpty(), node, "headers");
        requireAbsent(node.body().isEmpty(), node, "body");
    }

    private void validateHttpRequestParameters(WorkflowNode node) {
        if (node.url() == null || node.url().isBlank()) {
            throw new IllegalArgumentException(
                    "No HTTP_REQUEST '" + node.nodeId() + "' precisa definir url");
        }
        if (node.url().length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(
                    "url do no '" + node.nodeId() + "' excede " + MAX_URL_LENGTH + " caracteres");
        }
        requireAbsent(node.expression() == null, node, "expression");
    }

    /**
     * Recusa um parametro que nao pertence ao tipo do no.
     *
     * <p>A regra e simetrica de proposito -- o CONDITION recusa {@code url} tanto quanto o
     * HTTP_REQUEST recusa {@code expression}. Aceitar em silencio o campo que o tipo nao usa deixa
     * o autor convencido de ter configurado alguma coisa que nunca vai ser lida, e e assim que
     * nasce o workflow que "esta certo" e nao faz nada.</p>
     */
    private void requireAbsent(boolean absent, WorkflowNode node, String field) {
        if (!absent) {
            throw new IllegalArgumentException(
                    "No '" + node.nodeId() + "' do tipo " + node.type()
                            + " nao pode definir " + field);
        }
    }

    private void requireTarget(Map<String, WorkflowNode> byId, String nodeId, String edge, String target) {
        if (target != null && !byId.containsKey(target)) {
            throw new IllegalArgumentException(
                    "Aresta " + edge + " do no '" + nodeId + "' aponta para no inexistente: '" + target + "'");
        }
    }

    /**
     * Resolve o no por onde a execucao comeca: o {@code startNodeId} declarado ou, na ausencia
     * dele, o unico no sem aresta de entrada.
     *
     * @param byId nos indexados por identificador
     * @return identificador do no inicial efetivo
     * @throws IllegalArgumentException quando o {@code startNodeId} nao existe ou quando, sem ele,
     *         o grafo nao tem exatamente uma raiz
     */
    private String validateStartNode(Map<String, WorkflowNode> byId) {
        if (startNodeId != null) {
            if (!byId.containsKey(startNodeId)) {
                throw new IllegalArgumentException(
                        "startNodeId '" + startNodeId + "' nao corresponde a nenhum no");
            }
            return startNodeId;
        }
        Set<String> withInbound = new HashSet<>();
        for (WorkflowNode node : nodes) {
            addTarget(withInbound, node.nextOnSuccess());
            addTarget(withInbound, node.nextOnTrue());
            addTarget(withInbound, node.nextOnFalse());
        }
        List<String> roots = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            if (!withInbound.contains(node.nodeId())) {
                roots.add(node.nodeId());
            }
        }
        if (roots.size() != 1) {
            throw new IllegalArgumentException(
                    "Sem startNodeId definido, o grafo precisa ter exatamente um no sem aresta de entrada, "
                            + "mas foram encontrados " + roots.size()
                            + " (nenhuma raiz normalmente indica ciclo; mais de uma, partes soltas)");
        }
        return roots.getFirst();
    }

    private void addTarget(Set<String> targets, String target) {
        if (target != null) {
            targets.add(target);
        }
    }

    /**
     * Exige que todo no seja alcancavel a partir do no inicial.
     *
     * <p>Rejeita em vez de apenas avisar. Um no fora do alcance do inicio nunca sera executado:
     * nao ha caminho que chegue nele, entao ele nao e um "trecho ainda desconectado", e lixo que
     * ja passou por toda a validacao e vai ficar guardado dando a impressao de que faz parte do
     * workflow. Sem {@code startNodeId} a checagem de raiz unica ja cobria a maior parte disso,
     * mas com {@code startNodeId} definido ilhas inteiras passavam sem nenhuma reclamacao. Um
     * aviso nao serviria: o modelo nao tem para onde emitir aviso que chegue a quem editou, e a
     * definicao seria gravada do mesmo jeito.</p>
     *
     * @param startId identificador do no inicial efetivo
     * @param byId    nos indexados por identificador
     * @throws IllegalArgumentException quando existe no inalcancavel
     */
    private void validateReachability(String startId, Map<String, WorkflowNode> byId) {
        Set<String> reachable = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(startId);
        reachable.add(startId);
        while (!pending.isEmpty()) {
            WorkflowNode current = byId.get(pending.pop());
            for (String next : successors(current)) {
                if (reachable.add(next)) {
                    pending.push(next);
                }
            }
        }
        List<String> unreachable = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            if (!reachable.contains(node.nodeId())) {
                unreachable.add(node.nodeId());
            }
        }
        if (!unreachable.isEmpty()) {
            throw new IllegalArgumentException(
                    "Os nos " + unreachable + " nao sao alcancaveis a partir de '" + startId
                            + "' e nunca seriam executados");
        }
    }

    private void validateAcyclic(Map<String, WorkflowNode> byId) {
        Map<String, Integer> colors = new HashMap<>();
        for (WorkflowNode node : nodes) {
            colors.put(node.nodeId(), COLOR_WHITE);
        }
        for (WorkflowNode node : nodes) {
            if (colors.get(node.nodeId()) == COLOR_WHITE) {
                depthFirstSearch(node.nodeId(), byId, colors);
            }
        }
    }

    private void depthFirstSearch(String rootId, Map<String, WorkflowNode> byId, Map<String, Integer> colors) {
        Deque<String> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            String current = stack.peek();
            if (colors.get(current) == COLOR_WHITE) {
                colors.put(current, COLOR_GREY);
                for (String next : successors(byId.get(current))) {
                    Integer color = colors.get(next);
                    if (color != null && color == COLOR_GREY) {
                        throw new IllegalArgumentException(
                                "O grafo do workflow possui ciclo: aresta de '" + current + "' para '" + next + "'");
                    }
                    if (color != null && color == COLOR_WHITE) {
                        stack.push(next);
                    }
                }
            } else {
                colors.put(current, COLOR_BLACK);
                stack.pop();
            }
        }
    }

    private List<String> successors(WorkflowNode node) {
        List<String> targets = new ArrayList<>(3);
        addTargetTo(targets, node.nextOnSuccess());
        addTargetTo(targets, node.nextOnTrue());
        addTargetTo(targets, node.nextOnFalse());
        return targets;
    }

    private void addTargetTo(List<String> targets, String target) {
        if (target != null) {
            targets.add(target);
        }
    }
}
