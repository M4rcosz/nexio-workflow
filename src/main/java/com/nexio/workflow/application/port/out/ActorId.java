package com.nexio.workflow.application.port.out;

/**
 * Identificador de quem esta pedindo a operacao.
 *
 * <p>Mora na camada de aplicacao, e nao no dominio, porque o dominio nao tem dono: nem
 * {@code WorkflowDefinition} nem {@code WorkflowExecution} carregam campo de proprietario, e
 * inventar um agora seria adiantar a decisao de persistencia e de indice que so faz sentido junto
 * com a autorizacao. Ate la o ator e vocabulario de quem orquestra a operacao, nao de quem guarda o
 * estado. Fica ao lado de {@link PageQuery} pelo mesmo motivo que ele: e um tipo simples que
 * atravessa a fronteira das portas nos dois sentidos -- o adaptador de entrada o obtem, os casos de
 * uso o recebem -- e nao carrega nada de framework.</p>
 *
 * <p><b>O valor nunca vem do cliente.</b> Essa e a diferenca inteira entre este record e o
 * {@code tenantId} que a {@code docs/adr/0001-no-multi-tenancy.md} removeu: aquele chegava como
 * argumento GraphQL, entao trocar o argumento trocava a identidade e o suposto isolamento era uma
 * primitiva de enumeracao. Este chega por {@link CurrentActorPort}, implementado pela
 * infraestrutura, e nao existe campo de entrada, argumento ou cabecalho lido no resolver capaz de
 * influencia-lo. Ver {@code docs/adr/0006-actor-propagation.md}.</p>
 *
 * @param value identificador nao vazio do ator, dentro de {@value #MAX_LENGTH} caracteres
 */
public record ActorId(String value) {

    /**
     * Teto do identificador.
     *
     * <p>O que um provedor de identidade entrega e um {@code sub} opaco, um UUID ou um e-mail --
     * todos ordens de grandeza abaixo disto. O limite existe porque este valor tem destino: vai
     * virar linha de log e, no Sprint 4, chave de consulta indexada. Um identificador de tamanho
     * ilimitado nos dois lugares e o mesmo problema que {@code name} e {@code description} ja
     * resolveram no agregado.</p>
     */
    public static final int MAX_LENGTH = 128;

    /** Valor textual do ator usado enquanto nao existe autenticacao. */
    public static final String ANONYMOUS_VALUE = "anonymous";

    /**
     * Ator unico de todas as requisicoes enquanto nao ha autenticacao.
     *
     * <p>Ser uma constante nomeada e proposital: um teste que verifica propagacao compara com este
     * valor, e o dia em que a autenticacao chegar, quem ainda depender dele aparece na busca por
     * referencias em vez de continuar funcionando em silencio.</p>
     */
    public static final ActorId ANONYMOUS = new ActorId(ANONYMOUS_VALUE);

    public ActorId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("actorId nao pode ser vazio");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "actorId excede " + MAX_LENGTH + " caracteres: " + value.length());
        }
    }
}
