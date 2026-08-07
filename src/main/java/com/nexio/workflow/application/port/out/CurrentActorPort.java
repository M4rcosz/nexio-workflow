package com.nexio.workflow.application.port.out;

/**
 * Porta que informa quem e o ator da requisicao em curso.
 *
 * <p>E porta de <b>saida</b>, ao lado de {@link WorkflowDefinitionPort}, e nao de entrada. O criterio
 * nao e o sentido em que o dado viaja -- se fosse, toda leitura de banco seria porta de entrada --,
 * e sim quem chama quem: aqui a aplicacao pergunta e a infraestrutura responde, exatamente como na
 * persistencia. Portas de entrada sao as que o mundo externo aciona para pedir um caso de uso, e
 * este projeto nem as tem: os casos de uso sao classes concretas chamadas direto pelo resolver.
 * Criar um pacote {@code port/in} so para esta interface daria a ela a companhia errada.</p>
 *
 * <p>A interface existe para que o ator tenha <b>uma unica origem possivel</b>. Sem ela, o caminho
 * mais curto para o resolver seria ler um cabecalho ou um argumento, e o resultado seria o
 * {@code tenantId} da {@code docs/adr/0001-no-multi-tenancy.md} de novo, so que com outro nome. Com
 * ela, trocar a origem do valor e trocar a implementacao -- um arquivo, na infraestrutura, longe de
 * qualquer coisa que o cliente consiga enviar.</p>
 *
 * <p>Sem tipo de framework na assinatura, como as demais portas: nada de {@code Authentication},
 * {@code Principal} ou {@code SecurityContext} atravessa esta fronteira.</p>
 */
public interface CurrentActorPort {

    /**
     * Devolve o ator da requisicao em curso.
     *
     * @return identificador do ator, nunca nulo; hoje sempre {@link ActorId#ANONYMOUS}
     */
    ActorId currentActor();
}
