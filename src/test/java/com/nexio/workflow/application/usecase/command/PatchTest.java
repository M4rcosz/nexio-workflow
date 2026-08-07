package com.nexio.workflow.application.usecase.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cobre o que separa {@link Patch} de um simples campo anulavel: "nao enviado" e "enviado como
 * nulo" precisam ser estados distintos, senao nao ha como limpar um campo em uma atualizacao
 * parcial.
 */
class PatchTest {

    @Test
    void distinguishesAnAbsentFieldFromOneSentAsNull() {
        assertThat(Patch.unchanged().present()).isFalse();
        assertThat(Patch.of(null).present()).isTrue();
        assertThat(Patch.of(null).value()).isNull();
    }

    @Test
    void appliesTheActionOnlyWhenTheFieldWasSent() {
        List<String> applied = new ArrayList<>();

        Patch.<String>unchanged().ifPresent(applied::add);
        assertThat(applied).isEmpty();

        Patch.of("valor").ifPresent(applied::add);
        assertThat(applied).containsExactly("valor");
    }

    /**
     * Um campo ausente carregando valor seria um estado sem significado, e o consumidor teria que
     * escolher em qual dos dois componentes acreditar.
     */
    @Test
    void rejectsAnAbsentFieldCarryingAValue() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Patch<>(false, "valor"))
                .withMessageContaining("nao enviado");
    }
}
