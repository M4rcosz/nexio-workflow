package com.nexio.workflow.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexio.workflow.application.port.out.ActorId;
import org.junit.jupiter.api.Test;

/**
 * Testes do {@link AnonymousActorProvider}.
 *
 * <p>Afirmam pouca coisa de proposito, porque a classe faz pouca coisa de proposito: enquanto nao ha
 * autenticacao, o ator e constante. O valor esta em fixar que ele e <b>sempre o mesmo</b> e que nao
 * depende de nada -- nao ha estado, nao ha requisicao, nao ha parametro. No dia em que este teste
 * comecar a falhar, ou a autenticacao chegou (e ele deve ser reescrito junto com a classe) ou
 * alguem fez o ator variar sem que ninguem o tivesse verificado.</p>
 */
class AnonymousActorProviderTest {

    private final AnonymousActorProvider provider = new AnonymousActorProvider();

    @Test
    void returnsTheAnonymousActorConstant() {
        assertThat(provider.currentActor()).isEqualTo(ActorId.ANONYMOUS);
        assertThat(provider.currentActor().value()).isEqualTo(ActorId.ANONYMOUS_VALUE);
    }

    /** Sem autenticacao nao ha o que distinguir: duas chamadas seguidas dao o mesmo ator. */
    @Test
    void everyCallYieldsTheSameActor() {
        assertThat(provider.currentActor()).isEqualTo(provider.currentActor());
    }
}
