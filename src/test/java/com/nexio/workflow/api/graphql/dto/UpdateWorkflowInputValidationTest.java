package com.nexio.workflow.api.graphql.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexio.workflow.domain.model.WorkflowDefinition;
import com.nexio.workflow.domain.model.enums.NodeType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.data.ArgumentValue;

/**
 * Fixa o comportamento de Bean Validation sobre os componentes {@link ArgumentValue}.
 *
 * <p>Existe porque a semantica aqui nao e obvia e o desenho inteiro da atualizacao parcial depende
 * dela: o extrator que o spring-graphql registra e {@code @UnwrapByDefault}, entao a restricao
 * escrita no componente vale para o valor de dentro, e um campo omitido nao entrega valor algum ao
 * validador -- e o que faz {@code @NotBlank} recusar {@code name: null} sem recusar a atualizacao
 * que nao mexe no nome. A cascata para os inputs aninhados so funciona com {@code @Valid} no
 * argumento de tipo ({@code ArgumentValue<List<@Valid WorkflowNodeInput>>}); no componente, ao lado
 * das outras anotacoes, ela nao roda -- e nao roda em silencio, que e a razao deste teste
 * existir.</p>
 */
class UpdateWorkflowInputValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void omittedFieldsProduceNoViolation() {
        assertThat(validator.validate(allOmitted())).isEmpty();
    }

    @Test
    void explicitNullIsRejectedOnFieldsThatCannotBeCleared() {
        assertThat(messagesOf(withName(ArgumentValue.ofNullable(null))))
                .contains("name nao pode ser vazio");
    }

    @Test
    void explicitNullIsAcceptedOnFieldsThatCanBeCleared() {
        UpdateWorkflowInput input = new UpdateWorkflowInput(
                ArgumentValue.omitted(),
                ArgumentValue.ofNullable(null),
                ArgumentValue.omitted(),
                ArgumentValue.omitted(),
                ArgumentValue.ofNullable(null),
                ArgumentValue.omitted());

        assertThat(validator.validate(input)).isEmpty();
    }

    @Test
    void cascadesIntoTheTriggerInput() {
        UpdateWorkflowInput input = new UpdateWorkflowInput(
                ArgumentValue.omitted(),
                ArgumentValue.omitted(),
                ArgumentValue.ofNullable(new TriggerConfigInput(null, Map.of())),
                ArgumentValue.omitted(),
                ArgumentValue.omitted(),
                ArgumentValue.omitted());

        assertThat(messagesOf(input)).contains("type do gatilho e obrigatorio");
    }

    @Test
    void cascadesIntoTheNodeInputs() {
        UpdateWorkflowInput input = new UpdateWorkflowInput(
                ArgumentValue.omitted(),
                ArgumentValue.omitted(),
                ArgumentValue.omitted(),
                ArgumentValue.ofNullable(List.of(
                        new WorkflowNodeInput(" ", NodeType.HTTP_REQUEST, "https://exemplo.test",
                                null, null, null, null, Map.of(), null, null, null))),
                ArgumentValue.omitted(),
                ArgumentValue.omitted());

        assertThat(messagesOf(input)).contains("id do no e obrigatorio");
    }

    @Test
    void enforcesTheLengthCapOfTheDomain() {
        String tooLong = "n".repeat(WorkflowDefinition.MAX_NAME_LENGTH + 1);

        assertThat(messagesOf(withName(ArgumentValue.ofNullable(tooLong))))
                .contains("name excede " + WorkflowDefinition.MAX_NAME_LENGTH + " caracteres");
    }

    private static UpdateWorkflowInput allOmitted() {
        return new UpdateWorkflowInput(
                ArgumentValue.omitted(),
                ArgumentValue.omitted(),
                ArgumentValue.omitted(),
                ArgumentValue.omitted(),
                ArgumentValue.omitted(),
                ArgumentValue.omitted());
    }

    private static UpdateWorkflowInput withName(ArgumentValue<String> name) {
        return new UpdateWorkflowInput(
                name,
                ArgumentValue.omitted(),
                ArgumentValue.omitted(),
                ArgumentValue.omitted(),
                ArgumentValue.omitted(),
                ArgumentValue.omitted());
    }

    private static Set<String> messagesOf(UpdateWorkflowInput input) {
        return validator.validate(input).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }
}
