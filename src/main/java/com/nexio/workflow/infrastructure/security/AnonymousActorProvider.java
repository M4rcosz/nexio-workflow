package com.nexio.workflow.infrastructure.security;

import com.nexio.workflow.application.port.out.ActorId;
import com.nexio.workflow.application.port.out.CurrentActorPort;
import org.springframework.stereotype.Component;

/**
 * Implementacao de {@link CurrentActorPort} para um servico que ainda nao autentica ninguem.
 *
 * <p><b>Nao existe autenticacao neste servico.</b> Toda requisicao, de qualquer origem, e o mesmo
 * ator: {@link ActorId#ANONYMOUS}. Nada e autorizado a partir desse valor, e nenhuma consulta o
 * usa como filtro -- ele apenas atravessa os casos de uso.</p>
 *
 * <p><b>Esta classe e o unico lugar que muda quando o Spring Security chegar</b> (Sprint 4). O
 * corpo do metodo passa a derivar o identificador do principal autenticado, e nenhuma assinatura
 * acima daqui e tocada: e para isso que a porta e o parametro explicito nos casos de uso foram
 * escritos antes da autenticacao, e nao depois.</p>
 *
 * <p>Ela existe, em vez de o resolver simplesmente montar um {@code ActorId} constante, para fechar
 * a origem do valor. Com a origem em um bean da infraestrutura, nao ha ponto na camada de API onde
 * um argumento, um campo de input ou um cabecalho consiga se tornar o ator -- que e precisamente o
 * defeito pelo qual a {@code docs/adr/0001-no-multi-tenancy.md} eliminou o {@code tenantId}. Um
 * ator escolhido pelo cliente nao e identidade, e um seletor de dados alheios.</p>
 */
@Component
public class AnonymousActorProvider implements CurrentActorPort {

    /**
     * {@inheritDoc}
     *
     * <p>Constante, sempre: sem autenticacao nao ha o que distinguir, e devolver algo derivado da
     * requisicao (IP, cabecalho, sessao) seria fabricar uma identidade que ninguem verificou.</p>
     */
    @Override
    public ActorId currentActor() {
        return ActorId.ANONYMOUS;
    }
}
