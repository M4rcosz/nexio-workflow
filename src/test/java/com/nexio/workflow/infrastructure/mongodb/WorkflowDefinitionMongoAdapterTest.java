package com.nexio.workflow.infrastructure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.nexio.workflow.AbstractMongoIntegrationTest;
import com.nexio.workflow.application.port.out.PageQuery;
import com.nexio.workflow.application.port.out.WorkflowDefinitionPort;
import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.exception.WorkflowConcurrentlyModifiedException;
import com.nexio.workflow.domain.model.TriggerConfig;
import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.WorkflowNode;
import com.nexio.workflow.domain.model.enums.NodeType;
import com.nexio.workflow.domain.model.enums.TriggerType;
import com.nexio.workflow.infrastructure.config.MongoConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;

/**
 * Teste de integracao do {@link WorkflowDefinitionMongoAdapter} contra um MongoDB real
 * (Testcontainers).
 *
 * <p>O adaptador e injetado tipado como {@link WorkflowDefinitionPort}: o teste exercita o
 * contrato da porta, e nao a implementacao, entao qualquer troca de tecnologia de persistencia
 * continua tendo que passar por aqui.</p>
 *
 * <p>Nomeado {@code ...Test} e nao {@code ...IT} de proposito: o surefire so executa
 * {@code *Test.java} e este teste precisa rodar no {@code ./mvnw test}.</p>
 */
@DataMongoTest
@Import({MongoConfig.class,
        WorkflowDefinitionMongoAdapter.class,
        WorkflowDefinitionWriteValidationCallback.class})
class WorkflowDefinitionMongoAdapterTest extends AbstractMongoIntegrationTest {

    @Autowired
    private WorkflowDefinitionPort port;

    @Autowired
    private WorkflowDefinitionMongoRepository repository;

    @Test
    void saveAndFindByIdRoundTripsTheWholeAggregate() {
        WorkflowDefinition saved = port.save(definition("wf-save", "cobranca diaria", true, TriggerType.SCHEDULE));

        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();

        Optional<WorkflowDefinition> found = port.findById("wf-save");

        assertThat(found).isPresent();
        WorkflowDefinition reread = found.orElseThrow();
        assertThat(reread.getName()).isEqualTo("cobranca diaria");
        assertThat(reread.isEnabled()).isTrue();
        assertThat(reread.getStartNodeId()).isEqualTo("start");
        assertThat(reread.getTriggerConfig().type()).isEqualTo(TriggerType.SCHEDULE);
        assertThat(reread.getNodes()).extracting(WorkflowNode::nodeId).containsExactly("start", "check", "done");
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertThat(port.findById("wf-inexistente")).isEmpty();
    }

    @Test
    void findAllReturnsEveryDefinitionWithinThePage() {
        port.save(definition("wf-a", "a", true, TriggerType.MOCK_EVENT));
        port.save(definition("wf-b", "b", false, TriggerType.SCHEDULE));

        assertThat(port.findAll(PageQuery.firstPage()))
                .extracting(WorkflowDefinition::getId)
                .containsExactlyInAnyOrder("wf-a", "wf-b");
    }

    /**
     * O recorte e obrigatorio na porta justamente para que nenhuma consulta volte a colecao
     * inteira: aqui o limite corta o resultado e o deslocamento anda pela colecao.
     */
    @Test
    void findAllHonoursLimitAndOffset() {
        port.save(definition("wf-1", "um", true, TriggerType.MOCK_EVENT));
        port.save(definition("wf-2", "dois", true, TriggerType.MOCK_EVENT));
        port.save(definition("wf-3", "tres", true, TriggerType.MOCK_EVENT));

        List<WorkflowDefinition> firstPage = port.findAll(new PageQuery(2, 0));
        List<WorkflowDefinition> secondPage = port.findAll(new PageQuery(2, 2));

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
        assertThat(firstPage).extracting(WorkflowDefinition::getId)
                .doesNotContainAnyElementsOf(
                        secondPage.stream().map(WorkflowDefinition::getId).toList());
    }

    /**
     * O teto de {@value PageQuery#MAX_LIMIT} e do servidor: o chamador nao consegue pedir mais.
     */
    @Test
    void pageQueryRejectsLimitsOutsideTheServerBudget() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PageQuery(PageQuery.MAX_LIMIT + 1, 0))
                .withMessageContaining("excede o maximo");
        assertThatIllegalArgumentException().isThrownBy(() -> new PageQuery(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new PageQuery(10, -1));
    }

    /**
     * Consulta declarada desde o inicio e nunca exercitada: o bootstrap do contexto so prova que
     * ela e derivavel, nao que ela casa com o campo certo -- foi exatamente assim que o bug de
     * {@code nodes.id} sobreviveu.
     */
    @Test
    void findByTriggerConfigTypeMatchesTheEmbeddedTriggerType() {
        port.save(definition("wf-cron-1", "agendada 1", true, TriggerType.SCHEDULE));
        port.save(definition("wf-cron-2", "agendada 2", false, TriggerType.SCHEDULE));
        port.save(definition("wf-evento", "por evento", true, TriggerType.MOCK_EVENT));

        assertThat(repository.findByTriggerConfigType(TriggerType.SCHEDULE))
                .extracting(WorkflowDefinition::getId)
                .containsExactlyInAnyOrder("wf-cron-1", "wf-cron-2");
        assertThat(repository.findByTriggerConfigType(TriggerType.MOCK_EVENT))
                .extracting(WorkflowDefinition::getId)
                .containsExactly("wf-evento");
    }

    /**
     * O callback de persistencia recusa grafo invalido em qualquer save, sem depender de o caso de
     * uso ter lembrado de chamar {@code validateGraph()}.
     *
     * <p>A recusa sai como {@link InvalidWorkflowException} e nao como o
     * {@code IllegalArgumentException} cru do dominio: o callback e rede de seguranca, mas o que ele
     * pega e entrada invalida do usuario, e o tipo generico atravessava a porta para virar erro
     * interno na resposta -- com pilha inteira no log, por requisicao.</p>
     */
    @Test
    void saveRejectsInvalidGraphThroughTheWriteCallback() {
        WorkflowDefinition definition = definition("wf-ciclo", "ciclica", true, TriggerType.MOCK_EVENT);
        definition.setNodes(List.of(
                new WorkflowNode("start", NodeType.HTTP_REQUEST, Map.of(), "check", null, null),
                new WorkflowNode("check", NodeType.HTTP_REQUEST, Map.of(), "start", null, null)));

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> port.save(definition))
                .withMessageContaining("ciclo");
        assertThat(port.findById("wf-ciclo")).isEmpty();
    }

    /**
     * A config do no e mapa livre: a politica estrita dela vale na escrita.
     */
    @Test
    void saveRejectsOperatorKeysInNodeConfigThroughTheWriteCallback() {
        WorkflowDefinition definition = definition("wf-config", "config hostil", true, TriggerType.MOCK_EVENT);
        definition.setNodes(List.of(
                new WorkflowNode("start", NodeType.HTTP_REQUEST, Map.of("$where", "1"), null, null, null)));

        assertThatExceptionOfType(InvalidWorkflowException.class)
                .isThrownBy(() -> port.save(definition))
                .withMessageContaining("'$'");
        assertThat(port.findById("wf-config")).isEmpty();
    }

    /**
     * Duas leituras do mesmo documento, duas gravacoes: a segunda perde, e o que ela recebe e uma
     * excecao do dominio.
     *
     * <p>A porta promete nao expor tipo do Spring Data, e a
     * {@code OptimisticLockingFailureException} atravessava inteira -- sem nenhum tratamento na
     * camada de API, o perdedor de duas atualizacoes concorrentes recebia erro interno para uma
     * situacao que se resolve relendo e reenviando. A mensagem tambem nao pode repetir a da causa:
     * aquela carrega o nome da colecao e o filtro BSON da atualizacao.</p>
     */
    @Test
    void secondSaveOfAStaleInstanceFailsWithTheDomainConflict() {
        port.save(definition("wf-concorrente", "disputada", true, TriggerType.MOCK_EVENT));

        WorkflowDefinition first = port.findById("wf-concorrente").orElseThrow();
        WorkflowDefinition second = port.findById("wf-concorrente").orElseThrow();

        first.setName("renomeada pelo primeiro");
        assertThat(port.save(first).getVersion()).isOne();

        second.setName("renomeada pelo segundo");
        assertThatExceptionOfType(WorkflowConcurrentlyModifiedException.class)
                .isThrownBy(() -> port.save(second))
                .satisfies(error -> {
                    assertThat(error.workflowId()).isEqualTo("wf-concorrente");
                    assertThat(error.getMessage()).doesNotContain("workflow_definitions");
                });

        assertThat(port.findById("wf-concorrente").orElseThrow().getName())
                .isEqualTo("renomeada pelo primeiro");
    }

    @Test
    void findEnabledReturnsOnlyEnabledDefinitions() {
        port.save(definition("wf-on-1", "habilitada 1", true, TriggerType.MOCK_EVENT));
        port.save(definition("wf-off", "desabilitada", false, TriggerType.MOCK_EVENT));
        port.save(definition("wf-on-2", "habilitada 2", true, TriggerType.SCHEDULE));

        assertThat(port.findEnabled())
                .extracting(WorkflowDefinition::getId)
                .containsExactlyInAnyOrder("wf-on-1", "wf-on-2");
    }

    /**
     * A consulta de habilitadas que serve requisicao de usuario recorta no banco: o {@code limit}
     * precisa reduzir o trabalho do servidor, e nao so o tamanho da resposta.
     */
    @Test
    void findEnabledHonoursLimitAndOffsetAndStillFiltersByEnabled() {
        for (int i = 0; i < 5; i++) {
            port.save(definition("wf-on-" + i, "habilitada " + i, true, TriggerType.MOCK_EVENT));
        }
        port.save(definition("wf-off", "desabilitada", false, TriggerType.MOCK_EVENT));

        assertThat(port.findEnabled(new PageQuery(2, 0)))
                .extracting(WorkflowDefinition::getId).containsExactly("wf-on-0", "wf-on-1");
        assertThat(port.findEnabled(new PageQuery(2, 3)))
                .extracting(WorkflowDefinition::getId).containsExactly("wf-on-3", "wf-on-4");
        assertThat(port.findEnabled(new PageQuery(10, 0)))
                .extracting(WorkflowDefinition::getId).doesNotContain("wf-off");
    }

    /**
     * Paginar sem ordenar nao pagina: {@code skip} mais {@code limit} sobre a ordem natural do
     * MongoDB assume que essa ordem e estavel, e ela nao e -- um documento que mude de lugar entre
     * duas paginas sai duas vezes enquanto outro nao sai nenhuma.
     *
     * <p>Os documentos sao gravados na ordem inversa da dos identificadores de proposito. Sem
     * ordenacao declarada, a leitura devolve a ordem de insercao e as paginas saem invertidas; a
     * unica asercao que separa "ordenado por {@code _id}" de "por acaso veio na ordem certa" e
     * comparar com a ordem dos identificadores, e nao com a de gravacao.</p>
     */
    @Test
    void findAllPaginatesInIdOrderWithoutRepeatingOrSkippingRecords() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ids.add(String.format("wf-%02d", i));
        }
        for (String id : ids.reversed()) {
            port.save(definition(id, "definicao " + id, true, TriggerType.MOCK_EVENT));
        }

        List<String> firstPage = idsOf(port.findAll(new PageQuery(10, 0)));
        List<String> secondPage = idsOf(port.findAll(new PageQuery(10, 10)));

        assertThat(firstPage).containsExactlyElementsOf(ids.subList(0, 10));
        assertThat(secondPage).containsExactlyElementsOf(ids.subList(10, 20));
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);
    }

    private List<String> idsOf(List<WorkflowDefinition> definitions) {
        return definitions.stream().map(WorkflowDefinition::getId).toList();
    }

    @Test
    void existsByIdDistinguishesPresentFromAbsent() {
        port.save(definition("wf-exists", "existe", true, TriggerType.MOCK_EVENT));

        assertThat(port.existsById("wf-exists")).isTrue();
        assertThat(port.existsById("wf-nao-existe")).isFalse();
    }

    /**
     * Razao de ser da assinatura {@code boolean deleteById}: o chamador precisa distinguir
     * "removi" de "nao havia nada para remover" sem uma consulta extra.
     */
    @Test
    void deleteByIdReturnsTrueAndRemovesTheDocument() {
        port.save(definition("wf-delete", "para apagar", true, TriggerType.MOCK_EVENT));

        assertThat(port.deleteById("wf-delete")).isTrue();
        assertThat(port.findById("wf-delete")).isEmpty();
        assertThat(port.existsById("wf-delete")).isFalse();
    }

    @Test
    void deleteByIdReturnsFalseForUnknownId() {
        port.save(definition("wf-sobrevivente", "nao pode sumir", true, TriggerType.MOCK_EVENT));

        assertThat(port.deleteById("wf-nunca-existiu")).isFalse();
        assertThat(port.findAll(PageQuery.firstPage()))
                .extracting(WorkflowDefinition::getId).containsExactly("wf-sobrevivente");
    }

    @Test
    void deleteByIdRemovesOnlyTheTargetDocument() {
        port.save(definition("wf-alvo", "alvo", true, TriggerType.MOCK_EVENT));
        port.save(definition("wf-vizinho", "vizinho", true, TriggerType.MOCK_EVENT));

        assertThat(port.deleteById("wf-alvo")).isTrue();
        assertThat(port.deleteById("wf-alvo")).isFalse();
        assertThat(port.findAll(PageQuery.firstPage()))
                .extracting(WorkflowDefinition::getId).containsExactly("wf-vizinho");
    }

    private WorkflowDefinition definition(String id, String name, boolean enabled, TriggerType triggerType) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("x_trace", "abc-123");
        Map<String, Object> httpConfig = new LinkedHashMap<>();
        httpConfig.put("url", "https://example.test/hook");
        httpConfig.put("method", "POST");
        httpConfig.put("headers", headers);

        List<WorkflowNode> nodes = new ArrayList<>();
        nodes.add(new WorkflowNode("start", NodeType.HTTP_REQUEST, httpConfig, "check", null, null));
        nodes.add(new WorkflowNode("check", NodeType.CONDITION, Map.of("expr", "status == 200"),
                null, "done", "done"));
        nodes.add(new WorkflowNode("done", NodeType.HTTP_REQUEST, Map.of(), null, null, null));

        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(id);
        definition.setName(name);
        definition.setDescription("definicao usada no teste do adaptador");
        definition.setEnabled(enabled);
        definition.setTriggerConfig(new TriggerConfig(triggerType, Map.of("event", "order_created")));
        definition.setNodes(nodes);
        definition.setStartNodeId("start");
        definition.validateGraph();
        return definition;
    }
}
