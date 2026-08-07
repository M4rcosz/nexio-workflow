package com.nexio.workflow.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/**
 * Testes do value object {@link ActorId}.
 *
 * <p>O que se fixa aqui e que o tipo recusa o que nao e identificador. A validacao mora no
 * construtor compacto justamente para que nao exista {@code ActorId} invalido circulando: quando o
 * valor deixar de ser constante e passar a vir do principal autenticado, quem o construir nao tera
 * como criar um vazio ou um texto de tamanho arbitrario -- que sao os dois formatos com que um
 * identificador estraga log e, no Sprint 4, consulta.</p>
 */
class ActorIdTest {

    @Test
    void rejectsNullValue() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ActorId(null))
                .withMessageContaining("actorId");
    }

    /** Branco e vazio sao o mesmo caso: identificador que nao identifica ninguem. */
    @Test
    void rejectsBlankValue() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ActorId("   "))
                .withMessageContaining("actorId");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ActorId(""))
                .withMessageContaining("actorId");
    }

    @Test
    void rejectsValueAboveTheMaximumLength() {
        String tooLong = "a".repeat(ActorId.MAX_LENGTH + 1);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ActorId(tooLong))
                .withMessageContaining(String.valueOf(ActorId.MAX_LENGTH));
    }

    @Test
    void acceptsAValueExactlyAtTheMaximumLength() {
        String atLimit = "a".repeat(ActorId.MAX_LENGTH);

        assertThat(new ActorId(atLimit).value()).hasSize(ActorId.MAX_LENGTH);
    }

    @Test
    void acceptsAnOrdinaryIdentifier() {
        assertThat(new ActorId("ator-1").value()).isEqualTo("ator-1");
    }

    /**
     * O ator anonimo e um {@link ActorId} como qualquer outro: ele passa pela mesma validacao e nao
     * e um caso especial que os casos de uso precisem distinguir.
     */
    @Test
    void theAnonymousConstantIsAValidActor() {
        assertThat(ActorId.ANONYMOUS.value()).isEqualTo(ActorId.ANONYMOUS_VALUE).isNotBlank();
        assertThat(ActorId.ANONYMOUS).isEqualTo(new ActorId(ActorId.ANONYMOUS_VALUE));
    }
}
