package com.nexio.workflow.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCollection;
import com.nexio.workflow.AbstractMongoIntegrationTest;
import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.infrastructure.config.RequestSizeLimitFilter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Teste ponta a ponta do CRUD de workflow: consulta GraphQL entrando pelo endpoint e MongoDB real.
 *
 * <p><b>O tester entra pelo {@link MockMvc} e nao pelo {@code ExecutionGraphQlServiceTester} de
 * proposito.</b> O segundo chama o servico de execucao direto e pula a pilha do servlet inteira: com
 * ele, o {@link RequestSizeLimitFilter} e as instrumentacoes de custo por consulta continuariam
 * configurados sem nunca serem exercitados por teste nenhum -- configuracao morta, que so e
 * descoberta quebrada em producao. O {@code MockMvc} atravessa a cadeia de filtros completa, entao
 * essas pecas ficam sob teste; a unica coisa que um socket de verdade acrescentaria e o conector, e
 * ele nao vale arrastar a pilha reativa (exigida pelo {@code WebTestClient}, de que o
 * {@code HttpGraphQlTester} depende) para dentro de uma aplicacao deliberadamente servlet. Ver
 * {@link MockMvcGraphQlTransport}.</p>
 *
 * <p>O {@link MongoTemplate} esta injetado porque boa parte do que este teste afirma nao aparece na
 * resposta do GraphQL: o nome da chave dos nos dentro do documento, a descricao que sumiu, o salto
 * da versao e o segredo que continua gravado em texto claro so existem no documento cru. Conferir a
 * resposta contra ela mesma provaria apenas que o servidor e coerente consigo.</p>
 *
 * <p>Nomeado {@code ...Test} e nao {@code ...IT} pelo mesmo motivo dos demais testes de integracao:
 * o surefire so executa {@code *Test.java} e este teste precisa rodar no {@code ./mvnw test}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowGraphQlIntegrationTest extends AbstractMongoIntegrationTest {

    private static final String CREATE_MUTATION = """
            mutation Criar($input: CreateWorkflowInput!) {
              createWorkflow(input: $input) {
                id
                name
                description
                enabled
                startNodeId
                trigger { type config }
                nodes { id type config nextOnSuccess nextOnTrue nextOnFalse }
                createdAt
                updatedAt
              }
            }
            """;

    private static final String READ_QUERY = """
            query Ler($id: ID!) {
              workflow(id: $id) {
                id
                name
                description
                enabled
                startNodeId
                trigger { type }
                nodes { id type config nextOnSuccess }
              }
            }
            """;

    private static final String LIST_QUERY = """
            query Listar($limit: Int!, $offset: Int!, $enabledOnly: Boolean!) {
              workflows(limit: $limit, offset: $offset, enabledOnly: $enabledOnly) { id name enabled }
            }
            """;

    private static final String RENAME_MUTATION = """
            mutation Renomear($id: ID!, $name: String!) {
              updateWorkflow(id: $id, input: { name: $name }) { id name description }
            }
            """;

    private static final String CLEAR_DESCRIPTION_MUTATION = """
            mutation LimparDescricao($id: ID!) {
              updateWorkflow(id: $id, input: { description: null }) { id description }
            }
            """;

    private static final String UPDATE_NODES_MUTATION = """
            mutation TrocarNos($id: ID!, $nodes: [WorkflowNodeInput!]!) {
              updateWorkflow(id: $id, input: { nodes: $nodes, startNodeId: "start" }) { id }
            }
            """;

    private static final String ACTIVATE_MUTATION = """
            mutation Ativar($id: ID!) { activateWorkflow(id: $id) { id enabled } }
            """;

    private static final String DEACTIVATE_MUTATION = """
            mutation Desativar($id: ID!) { deactivateWorkflow(id: $id) { id enabled } }
            """;

    private static final String DELETE_MUTATION = """
            mutation Remover($id: ID!) { deleteWorkflow(id: $id) }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Value("${spring.graphql.path:/graphql}")
    private String graphQlPath;

    private GraphQlTester graphQlTester;

    @BeforeEach
    void buildTester() {
        graphQlTester = MockMvcGraphQlTransport.tester(mockMvc, objectMapper, graphQlPath);
    }

    @Test
    void createWorkflowPersistsTheDefinitionAndReturnsIt() {
        String id = graphQlTester.document(CREATE_MUTATION)
                .variable("input", validInput("cobranca diaria", "dispara a cobranca", true))
                .execute()
                .path("createWorkflow.name").entity(String.class).isEqualTo("cobranca diaria")
                .path("createWorkflow.enabled").entity(Boolean.class).isEqualTo(true)
                .path("createWorkflow.startNodeId").entity(String.class).isEqualTo("start")
                .path("createWorkflow.nodes").entityList(Object.class).hasSize(2)
                .path("createWorkflow.createdAt").hasValue()
                .path("createWorkflow.updatedAt").hasValue()
                .path("createWorkflow.id").entity(String.class).get();

        assertThat(id).isNotBlank();
        assertThat(rawDefinition(id)).isNotNull();
    }

    /**
     * Trava de regressao do pior defeito que este projeto teve. O Spring Data MongoDB trata qualquer
     * propriedade chamada {@code id} como identidade e a grava como {@code _id}, inclusive em
     * documento embedado: os nos iam para o banco como {@code nodes._id} e toda consulta por
     * {@code nodes.id} casava zero documentos sem reclamar de nada. O componente do dominio foi
     * renomeado para {@code nodeId} exatamente por isso, e a unica coisa que percebe a volta do bug e
     * olhar o nome da chave no documento cru -- a resposta do GraphQL continua chamando o campo de
     * {@code id} nos dois casos.
     */
    @Test
    void storedNodesCarryNodeIdAndNeverUnderscoreId() {
        createWorkflow(validInput("com nos nomeados", null, false));

        assertThat(definitions().countDocuments(new Document("nodes.nodeId", "start"))).isEqualTo(1);
        assertThat(definitions().countDocuments(new Document("nodes._id", "start"))).isZero();
    }

    @Test
    void createWorkflowRejectsACyclicGraphAndPersistsNothing() {
        graphQlTester.document(CREATE_MUTATION)
                .variable("input", cyclicInput())
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.BAD_REQUEST.name());
                    assertThat(errors.getFirst().getMessage()).contains("ciclo");
                });

        assertThat(definitions().countDocuments()).isZero();
    }

    @Test
    void createWorkflowRejectsAnEdgeToAnUnknownNodeAndPersistsNothing() {
        graphQlTester.document(CREATE_MUTATION)
                .variable("input", danglingEdgeInput())
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.BAD_REQUEST.name());
                    assertThat(errors.getFirst().getMessage()).contains("no inexistente");
                });

        assertThat(definitions().countDocuments()).isZero();
    }

    @Test
    void workflowByIdReturnsTheCreatedDefinition() {
        String id = createWorkflow(validInput("consulta por id", "descricao gravada", true));

        graphQlTester.document(READ_QUERY)
                .variable("id", id)
                .execute()
                .path("workflow.id").entity(String.class).isEqualTo(id)
                .path("workflow.name").entity(String.class).isEqualTo("consulta por id")
                .path("workflow.description").entity(String.class).isEqualTo("descricao gravada")
                .path("workflow.enabled").entity(Boolean.class).isEqualTo(true)
                .path("workflow.nodes[*].id").entityList(String.class).containsExactly("start", "end")
                .path("workflow.nodes[0].nextOnSuccess").entity(String.class).isEqualTo("end");
    }

    /**
     * O campo e nulavel no schema, entao "nao achei" poderia sair como {@code null} sem erro algum. O
     * que a aplicacao faz e outra coisa: o {@code GetWorkflowUseCase} lanca
     * {@code WorkflowNotFoundException} e o {@code WorkflowExceptionResolver} a traduz para
     * {@code NOT_FOUND}. E esse comportamento observado que fica fixado aqui.
     */
    @Test
    void workflowByUnknownIdIsNotFound() {
        graphQlTester.document(READ_QUERY)
                .variable("id", "wf-que-nunca-existiu")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.NOT_FOUND.name());
                    assertThat(errors.getFirst().getMessage())
                            .isEqualTo("Workflow nao encontrado: wf-que-nunca-existiu");
                });
    }

    /**
     * O deslocamento pedido nao e multiplo do limite de proposito, e e a razao de o
     * {@code OffsetPageable} existir no lugar do {@code PageRequest} do Spring Data, que e indexado
     * por numero de pagina: com {@code limit 2, offset 3}, converter deslocamento em pagina daria
     * {@code 3 / 2 = 1} e devolveria os registros 2 e 3 em vez de 3 e 4. Um teste com deslocamento
     * 0, 2 ou 4 passaria verde com o bug, porque nesses casos as duas contas coincidem.
     *
     * <p>A fatia era comparada com a ordem que a propria listagem devolvia, porque nada declarava
     * ordenacao e fixar a de criacao seria afirmar uma garantia que o codigo nao dava. Agora a
     * ordem existe -- {@code _id} crescente -- e e ela que fica afirmada: comparar a resposta com
     * ela mesma provaria so que o servidor e coerente consigo. Os identificadores sao UUID gerados
     * na criacao, entao a ordem esperada nao e a de insercao e precisa ser ordenada aqui.</p>
     */
    @Test
    void workflowsPaginatesByAbsoluteOffsetInIdOrder() {
        List<String> created = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            created.add(createWorkflow(validInput("workflow " + i, null, false)));
        }
        List<String> expected = created.stream().sorted().toList();

        assertThat(listIds(PageQuery.MAX_LIMIT, 0, false)).containsExactlyElementsOf(expected);
        assertThat(listIds(2, 3, false)).containsExactlyElementsOf(expected.subList(3, 5));
        assertThat(listIds(2, 0, false)).containsExactlyElementsOf(expected.subList(0, 2));
    }

    @Test
    void workflowsWithEnabledOnlyReturnsOnlyTheEnabledDefinitions() {
        String ligado = createWorkflow(validInput("ligado", null, true));
        createWorkflow(validInput("desligado", null, false));
        String outroLigado = createWorkflow(validInput("outro ligado", null, true));

        assertThat(listIds(PageQuery.MAX_LIMIT, 0, true))
                .containsExactlyInAnyOrder(ligado, outroLigado);
    }

    /**
     * Passar do teto e erro, e nao um resultado cortado em silencio: uma listagem que devolvesse os
     * primeiros {@value PageQuery#MAX_LIMIT} registros faria o cliente acreditar que recebeu o que
     * pediu.
     */
    @Test
    void workflowsRejectsALimitAboveTheServerMaximum() {
        createWorkflow(validInput("nao deve ser listado", null, false));

        graphQlTester.document("{ workflows(limit: " + (PageQuery.MAX_LIMIT + 1) + ") { id } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.BAD_REQUEST.name());
                })
                .path("workflows").pathDoesNotExist();
    }

    /**
     * A distincao que sustenta {@code Patch} e {@code ArgumentValue}, do jeito que nenhum teste de
     * camada mais baixa consegue provar: omitir {@code description} e enviar
     * {@code description: null} sao instrucoes diferentes, e o que separa uma da outra so existe
     * porque a informacao "o campo veio na consulta" sobrevive desde a analise do documento GraphQL
     * ate o comando do caso de uso. Um record de tipos simples entregaria nulo nos dois casos, e o
     * servidor teria que escolher uma leitura para ambos -- ou nunca daria para limpar a descricao,
     * ou toda atualizacao parcial apagaria o que nao foi enviado.
     *
     * <p>As duas afirmacoes sao contra o documento cru porque e ele que responde a pergunta: a
     * resposta da mutation ecoa o que o servidor acabou de montar em memoria, e o que interessa e o
     * que ficou gravado.</p>
     */
    @Test
    void omittingDescriptionKeepsItWhileSendingNullClearsIt() {
        String id = createWorkflow(validInput("com descricao", "descricao original", false));

        graphQlTester.document(RENAME_MUTATION)
                .variable("id", id)
                .variable("name", "nome novo")
                .execute()
                .path("updateWorkflow.name").entity(String.class).isEqualTo("nome novo");

        assertThat(rawDefinition(id).getString("description")).isEqualTo("descricao original");

        graphQlTester.document(CLEAR_DESCRIPTION_MUTATION)
                .variable("id", id)
                .execute()
                .path("updateWorkflow.description").valueIsNull();

        assertThat(rawDefinition(id).getString("description")).isNull();
        assertThat(rawDefinition(id).getString("name")).isEqualTo("nome novo");
    }

    /**
     * A versao e o {@code updatedAt} sao o que protege contra escrita concorrente e o que diz ao
     * cliente que o documento mudou. Uma atualizacao que gravasse um agregado recem-construido em vez
     * do carregado passaria por aqui com versao voltando a zero.
     */
    @Test
    void updateWorkflowBumpsTheStoredVersionAndMovesUpdatedAt() {
        String id = createWorkflow(validInput("antes da atualizacao", null, false));
        Document created = rawDefinition(id);
        Date createdUpdatedAt = created.getDate("updatedAt");

        assertThat(created.getLong("version")).isZero();

        graphQlTester.document(RENAME_MUTATION)
                .variable("id", id)
                .variable("name", "depois da atualizacao")
                .execute()
                .path("updateWorkflow.name").entity(String.class).isEqualTo("depois da atualizacao");

        Document updated = rawDefinition(id);
        assertThat(updated.getLong("version")).isEqualTo(1L);
        assertThat(updated.getDate("updatedAt")).isAfter(createdUpdatedAt);
        assertThat(updated.getDate("createdAt")).isEqualTo(created.getDate("createdAt"));
    }

    @Test
    void activateAndDeactivateFlipEnabledInTheStoredDocument() {
        String id = createWorkflow(validInput("para ligar e desligar", null, false));

        assertThat(rawDefinition(id).getBoolean("enabled")).isFalse();

        graphQlTester.document(ACTIVATE_MUTATION)
                .variable("id", id)
                .execute()
                .path("activateWorkflow.enabled").entity(Boolean.class).isEqualTo(true);

        assertThat(rawDefinition(id).getBoolean("enabled")).isTrue();

        graphQlTester.document(DEACTIVATE_MUTATION)
                .variable("id", id)
                .execute()
                .path("deactivateWorkflow.enabled").entity(Boolean.class).isEqualTo(false);

        assertThat(rawDefinition(id).getBoolean("enabled")).isFalse();
    }

    /**
     * <b>A remocao cascateia para as execucoes.</b> Esta afirmacao ja foi a inversa: o teste fixava
     * que a definicao saia e a execucao ficava, porque era isso que o codigo fazia --
     * {@code WorkflowExecutionPort.deleteByWorkflowId} existia documentado como "evitando execucoes
     * orfas quando a definicao e apagada" e nao tinha chamador nenhum. O historico ficava para tras
     * apontando para um {@code workflowId} que nao existe mais, sem nada no sistema capaz de
     * encontrar ou remover esses documentos, ja que toda consulta de execucao parte do workflow.
     *
     * <p>A execucao de outro workflow entra no cenario para que a asercao nao possa ser satisfeita
     * por uma remocao ampla demais: apagar a colecao inteira tambem zeraria a contagem do alvo.</p>
     */
    @Test
    void deleteWorkflowRemovesTheDefinitionAndCascadesToItsExecutions() {
        String id = createWorkflow(validInput("para remover", null, false));
        String outro = createWorkflow(validInput("nao envolvido", null, false));
        seedExecution("exec-1", id);
        seedExecution("exec-2", id);
        seedExecution("exec-de-outro", outro);

        graphQlTester.document(DELETE_MUTATION)
                .variable("id", id)
                .execute()
                .path("deleteWorkflow").entity(Boolean.class).isEqualTo(true);

        assertThat(rawDefinition(id)).isNull();
        assertThat(executions().countDocuments(new Document("workflowId", id))).isZero();
        assertThat(executions().countDocuments(new Document("workflowId", outro))).isEqualTo(1);
    }

    /**
     * O resolver devolve {@code true} incondicionalmente e deixa a ausencia virar erro: quem pede
     * para remover algo que nao existe recebe {@code NOT_FOUND}, nunca um {@code false} que o cliente
     * teria que lembrar de conferir.
     */
    @Test
    void deleteWorkflowOfUnknownIdIsNotFound() {
        graphQlTester.document(DELETE_MUTATION)
                .variable("id", "wf-que-nunca-existiu")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.NOT_FOUND.name());
                });
    }

    /**
     * A redacao e uma preocupacao da leitura, e so dela: ela roda na construcao do DTO de resposta,
     * depois da persistencia. Se algum dia ela escorregasse para o caminho de escrita, o segredo
     * gravado viraria o marcador e o no HTTP passaria a chamar o servico externo com um cabecalho
     * {@code Authorization} literalmente invalido -- uma falha que o teste da fatia GraphQL nao
     * enxerga, porque la nao existe banco para conferir.
     */
    @Test
    void secretsAreRedactedOnReadButStayIntactInTheStoredDocument() {
        String id = createWorkflow(inputWithSecretHeader());

        graphQlTester.document(READ_QUERY)
                .variable("id", id)
                .execute()
                .path("workflow.nodes[0].config.headers.Authorization")
                .entity(String.class).isEqualTo(SecretRedactor.REDACTED)
                .path("workflow.nodes[0].config.headers['Content-Type']")
                .entity(String.class).isEqualTo("application/json")
                .path("workflow.nodes[0].config.url")
                .entity(String.class).isEqualTo("https://exemplo.test");

        assertThat(storedHeadersOfFirstNode(id))
                .containsEntry("Authorization", "Bearer super-secreto")
                .containsEntry("Content-Type", "application/json");
    }

    /**
     * Chave de operador do MongoDB na config de um no: entrada invalida do usuario, e nao falha do
     * servidor.
     *
     * <p>A verificacao existia so no callback de escrita, que roda dentro do {@code save()} -- fora
     * do {@code try} do caso de uso --, entao esta mutation devolvia {@code INTERNAL_ERROR} e
     * despejava uma pilha inteira no log a cada requisicao, sem autenticacao nenhuma na frente. O
     * mesmo valia para chave comecando com {@code _}, aninhamento acima do teto, entradas demais e
     * texto longo demais.</p>
     */
    @Test
    void createWorkflowRejectsAnOperatorKeyInNodeConfigAsBadRequest() {
        graphQlTester.document(CREATE_MUTATION)
                .variable("input", inputWithNodeConfig(Map.of("$where", "1")))
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.BAD_REQUEST.name());
                    assertThat(errors.getFirst().getMessage()).contains("'$'");
                });

        assertThat(definitions().countDocuments()).isZero();
    }

    /**
     * O outro ponto de entrada da mesma classe de falha, e por outro caminho. Aqui quem recusa e a
     * copia defensiva do dominio, disparada pelo construtor compacto do record dentro do mapper --
     * codigo que roda no resolver, fora de qualquer caso de uso, e por isso escapava de toda
     * traducao de erro. O analisador do graphql-java aceita valor de variavel muito mais fundo que
     * este limite, entao e um documento que qualquer cliente consegue enviar.
     */
    @Test
    void createWorkflowRejectsConfigNestedDeeperThanTheDomainAcceptsAsBadRequest() {
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> cursor = config;
        for (int i = 0; i < 150; i++) {
            Map<String, Object> child = new LinkedHashMap<>();
            cursor.put("filho", child);
            cursor = child;
        }

        graphQlTester.document(CREATE_MUTATION)
                .variable("input", inputWithNodeConfig(config))
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.BAD_REQUEST.name());
                });

        assertThat(definitions().countDocuments()).isZero();
    }

    /**
     * O ciclo completo que o marcador de redacao fecha sozinho: le, edita outro campo, reenvia.
     *
     * <p>A leitura devolve {@code Authorization} como marcador; o cliente que reenvia o array
     * {@code nodes} inteiro -- que e o unico jeito de alterar um no, porque {@code nodes} e trocado
     * em bloco -- devolvia o marcador como se fosse o valor. Nada recusava, e o resultado era a
     * credencial de verdade apagada e o no HTTP passando a chamar o servico externo com um
     * cabecalho literalmente invalido. A ultima asercao e a que importa: o segredo continua gravado
     * como estava.</p>
     */
    @Test
    void writingBackTheRedactionMarkerIsRejectedAndLeavesTheStoredSecretIntact() {
        String id = createWorkflow(inputWithSecretHeader());

        graphQlTester.document(UPDATE_NODES_MUTATION)
                .variable("id", id)
                .variable("nodes", List.of(Map.of(
                        "id", "start",
                        "type", "HTTP_REQUEST",
                        "config", Map.of(
                                "url", "https://exemplo.test",
                                "headers", Map.of(
                                        "Authorization", SecretRedactor.REDACTED,
                                        "Content-Type", "application/json")))))
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType()).hasToString(ErrorType.BAD_REQUEST.name());
                    assertThat(errors.getFirst().getMessage()).contains("marcador");
                });

        assertThat(storedHeadersOfFirstNode(id))
                .containsEntry("Authorization", "Bearer super-secreto");
    }

    private String createWorkflow(Map<String, Object> input) {
        return graphQlTester.document(CREATE_MUTATION)
                .variable("input", input)
                .execute()
                .path("createWorkflow.id")
                .entity(String.class)
                .get();
    }

    private List<String> listIds(int limit, int offset, boolean enabledOnly) {
        return graphQlTester.document(LIST_QUERY)
                .variable("limit", limit)
                .variable("offset", offset)
                .variable("enabledOnly", enabledOnly)
                .execute()
                .path("workflows[*].id")
                .entityList(String.class)
                .get();
    }

    /**
     * Grava a execucao como documento cru: o que este teste precisa e de um registro apontando para
     * o workflow, e nao do agregado hidratado nem do callback de escrita que ele dispararia.
     */
    private void seedExecution(String id, String workflowId) {
        executions().insertOne(new Document("_id", id)
                .append("workflowId", workflowId)
                .append("status", "PENDING")
                .append("version", 0L));
    }

    private Document rawDefinition(String id) {
        return definitions().find(new Document("_id", id)).first();
    }

    private Document storedHeadersOfFirstNode(String id) {
        List<Document> nodes = rawDefinition(id).getList("nodes", Document.class);
        return nodes.getFirst().get("config", Document.class).get("headers", Document.class);
    }

    private MongoCollection<Document> definitions() {
        return mongoTemplate.getCollection(DEFINITIONS_COLLECTION);
    }

    private MongoCollection<Document> executions() {
        return mongoTemplate.getCollection(EXECUTIONS_COLLECTION);
    }

    private static Map<String, Object> validInput(String name, String description, boolean enabled) {
        Map<String, Object> input = baseInput(name);
        input.put("description", description);
        input.put("enabled", enabled);
        input.put("nodes", List.of(
                Map.of(
                        "id", "start",
                        "type", "HTTP_REQUEST",
                        "config", Map.of("url", "https://exemplo.test"),
                        "nextOnSuccess", "end"),
                Map.of(
                        "id", "end",
                        "type", "HTTP_REQUEST",
                        "config", Map.of())));
        return input;
    }

    private static Map<String, Object> cyclicInput() {
        Map<String, Object> input = baseInput("grafo ciclico");
        input.put("nodes", List.of(
                Map.of(
                        "id", "start",
                        "type", "HTTP_REQUEST",
                        "config", Map.of(),
                        "nextOnSuccess", "end"),
                Map.of(
                        "id", "end",
                        "type", "HTTP_REQUEST",
                        "config", Map.of(),
                        "nextOnSuccess", "start")));
        return input;
    }

    private static Map<String, Object> danglingEdgeInput() {
        Map<String, Object> input = baseInput("aresta orfa");
        input.put("nodes", List.of(Map.of(
                "id", "start",
                "type", "HTTP_REQUEST",
                "config", Map.of(),
                "nextOnSuccess", "no-que-nao-existe")));
        return input;
    }

    /** Entrada valida em tudo menos na config do unico no, que e o que o teste quer recusar. */
    private static Map<String, Object> inputWithNodeConfig(Map<String, Object> config) {
        Map<String, Object> input = baseInput("config sob teste");
        input.put("nodes", List.of(Map.of(
                "id", "start",
                "type", "HTTP_REQUEST",
                "config", config)));
        return input;
    }

    private static Map<String, Object> inputWithSecretHeader() {
        Map<String, Object> input = baseInput("chamada autenticada");
        input.put("nodes", List.of(Map.of(
                "id", "start",
                "type", "HTTP_REQUEST",
                "config", Map.of(
                        "url", "https://exemplo.test",
                        "headers", Map.of(
                                "Authorization", "Bearer super-secreto",
                                "Content-Type", "application/json")))));
        return input;
    }

    /**
     * Parte comum de toda entrada de criacao. O mapa e mutavel e ordenado porque {@code Map.of} nao
     * aceita valor nulo, e {@code description: null} e justamente um dos casos sob teste.
     */
    private static Map<String, Object> baseInput(String name) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", name);
        input.put("trigger", Map.of("type", "MOCK_EVENT", "config", Map.of()));
        input.put("startNodeId", "start");
        return input;
    }
}
