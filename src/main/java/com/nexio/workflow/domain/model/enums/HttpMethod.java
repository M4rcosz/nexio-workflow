package com.nexio.workflow.domain.model.enums;

/**
 * Verbo HTTP aceito por um no {@link NodeType#HTTP_REQUEST}.
 *
 * <p>E enum, e nao texto livre, porque enquanto o verbo era uma chave dentro da {@code config} nao
 * tipada nada recusava {@code "PSOT"}: o erro so aparecia no disparo, longe de quem escreveu o
 * workflow. Como enum, o verbo invalido e recusado pela validacao do proprio GraphQL, antes de
 * qualquer codigo deste projeto rodar.</p>
 *
 * <p>A lista e fechada nos verbos que um passo de workflow precisa. {@code CONNECT}, {@code TRACE}
 * e {@code OPTIONS} ficam de fora de proposito: nao servem a nenhum caso de uso aqui e
 * {@code CONNECT} em particular transforma o cliente de saida em tunel, que e exatamente o que o
 * {@link com.nexio.workflow.infrastructure.http.HttpTargetValidator} existe para impedir.</p>
 */
public enum HttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    HEAD
}
