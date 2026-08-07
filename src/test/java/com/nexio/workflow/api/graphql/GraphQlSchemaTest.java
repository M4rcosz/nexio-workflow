package com.nexio.workflow.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexio.workflow.application.usecase.CreateWorkflowUseCase;
import com.nexio.workflow.application.usecase.DeleteWorkflowUseCase;
import com.nexio.workflow.application.usecase.GetWorkflowUseCase;
import com.nexio.workflow.application.usecase.ListWorkflowsUseCase;
import com.nexio.workflow.application.usecase.UpdateWorkflowUseCase;
import com.nexio.workflow.infrastructure.config.GraphQLScalarsConfig;
import com.nexio.workflow.infrastructure.config.GraphQlQueryCostConfig;
import graphql.introspection.IntrospectionQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Afirma que o schema carrega e continua reconciliado com o dominio.
 *
 * <p>Existe porque um schema quebrado e uma falha de subida da aplicacao, e todo o resto da suite e
 * teste de fatia ou de unidade: nenhum deles carrega o schema, entao um campo apontando para um
 * scalar inexistente ou um tipo removido pela metade passaria verde ate o primeiro deploy.</p>
 *
 * <p>A consulta de introspeccao tambem exercita {@link GraphQlQueryCostConfig}: e a consulta mais
 * funda que este servico recebe na pratica -- mais funda que qualquer consulta de negocio do schema
 * atual -- e um teto de profundidade escolhido no olho a quebraria em silencio, derrubando o
 * GraphiQL e qualquer cliente que baixe o esquema.</p>
 */
@GraphQlTest(WorkflowResolver.class)
@Import({GraphQLScalarsConfig.class, GraphQlQueryCostConfig.class})
class GraphQlSchemaTest {

    @MockitoBean
    private CreateWorkflowUseCase createWorkflowUseCase;

    @MockitoBean
    private UpdateWorkflowUseCase updateWorkflowUseCase;

    @MockitoBean
    private DeleteWorkflowUseCase deleteWorkflowUseCase;

    @MockitoBean
    private GetWorkflowUseCase getWorkflowUseCase;

    @MockitoBean
    private ListWorkflowsUseCase listWorkflowsUseCase;

    @Autowired
    private GraphQlTester graphQlTester;

    @Test
    void schemaLoadsAndAnswersIntrospection() {
        graphQlTester.document(IntrospectionQuery.INTROSPECTION_QUERY)
                .execute()
                .path("__schema.queryType.name").entity(String.class).isEqualTo("Query");
    }

    /** O {@code tenantId} saiu do modelo inteiro; um resto dele no schema seria contrato morto. */
    @Test
    void hasNoTenantIdAnywhere() {
        assertThat(fieldNames("WorkflowDefinition")).doesNotContain("tenantId");
        assertThat(inputFieldNames("CreateWorkflowInput")).doesNotContain("tenantId");
        assertThat(argumentNames("workflows")).doesNotContain("tenantId");
    }

    @Test
    void definitionTypeMatchesTheDomain() {
        assertThat(fieldNames("WorkflowDefinition"))
                .contains("enabled", "startNodeId")
                .doesNotContain("active");
    }

    /**
     * As arestas sao campos declarados, e nao chaves escondidas em {@code config}: e o que a
     * validacao do grafo enxerga.
     */
    @Test
    void nodeExposesRoutingAsFirstClassFields() {
        assertThat(fieldNames("WorkflowNode"))
                .contains("nextOnSuccess", "nextOnTrue", "nextOnFalse")
                .doesNotContain("nextOnFailure");
        assertThat(inputFieldNames("WorkflowNodeInput"))
                .contains("nextOnSuccess", "nextOnTrue", "nextOnFalse")
                .doesNotContain("nextOnFailure");
    }

    /** Campo sem resolver devolve nulo em silencio: as operacoes de execucao voltam no Sprint 3. */
    @Test
    void executionOperationsAreNotPublished() {
        assertThat(fieldNames("Query")).containsExactlyInAnyOrder("workflows", "workflow");
        assertThat(fieldNames("Mutation")).doesNotContain("triggerWorkflow");
    }

    @Test
    void everyPublishedMutationHasAResolver() {
        assertThat(fieldNames("Mutation")).containsExactlyInAnyOrder(
                "createWorkflow", "updateWorkflow", "deleteWorkflow", "activateWorkflow", "deactivateWorkflow");
    }

    @Test
    void executionEnumsMatchTheDomain() {
        assertThat(enumValues("ExecutionStatus"))
                .containsExactlyInAnyOrder("PENDING", "RUNNING", "SUCCESS", "FAILED");
        assertThat(enumValues("StepStatus"))
                .containsExactlyInAnyOrder("PENDING", "SUCCESS", "FAILED", "SKIPPED");
    }

    private List<String> fieldNames(String typeName) {
        return graphQlTester.document("query T($name: String!) { __type(name: $name) { fields { name } } }")
                .variable("name", typeName)
                .execute()
                .path("__type.fields[*].name").entityList(String.class).get();
    }

    private List<String> inputFieldNames(String typeName) {
        return graphQlTester.document("query T($name: String!) { __type(name: $name) { inputFields { name } } }")
                .variable("name", typeName)
                .execute()
                .path("__type.inputFields[*].name").entityList(String.class).get();
    }

    private List<String> enumValues(String typeName) {
        return graphQlTester.document("query T($name: String!) { __type(name: $name) { enumValues { name } } }")
                .variable("name", typeName)
                .execute()
                .path("__type.enumValues[*].name").entityList(String.class).get();
    }

    private List<String> argumentNames(String queryField) {
        return graphQlTester.document("{ __schema { queryType { fields { name args { name } } } } }")
                .execute()
                .path("__schema.queryType.fields[?(@.name == '" + queryField + "')].args[*].name")
                .entityList(String.class).get();
    }
}
