package com.nexio.workflow.application.port.out;

/**
 * Recorte de paginacao usado pelas portas de saida.
 *
 * <p>E um tipo do dominio de proposito: as portas nao podem expor {@code Pageable} nem nenhum outro
 * tipo do Spring Data, sob pena de a camada de aplicacao passar a depender da tecnologia de
 * persistencia. Os adaptadores traduzem este recorte para o vocabulario da infraestrutura.</p>
 *
 * <p>O teto de {@value #MAX_LIMIT} e imposto pelo servidor, nao negociado com o chamador: as
 * colecoes crescem um documento por disparo e cada documento pode chegar a 16MB, entao uma consulta
 * sem teto e uma forma barata de derrubar o processo.</p>
 *
 * @param limit  quantidade maxima de registros a devolver, de 1 a {@value #MAX_LIMIT}
 * @param offset quantidade de registros a pular, nunca negativa
 */
public record PageQuery(int limit, int offset) {

    /** Limite maximo de registros por consulta, imposto pelo servidor. */
    public static final int MAX_LIMIT = 100;

    /** Limite usado quando o chamador nao informa um. */
    public static final int DEFAULT_LIMIT = 20;

    public PageQuery {
        if (limit < 1) {
            throw new IllegalArgumentException("limit precisa ser maior que zero: " + limit);
        }
        if (limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit excede o maximo de " + MAX_LIMIT + " registros por consulta: " + limit);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset nao pode ser negativo: " + offset);
        }
    }

    /**
     * Cria o recorte da primeira pagina com o limite padrao.
     *
     * @return recorte com {@value #DEFAULT_LIMIT} registros a partir do inicio
     */
    public static PageQuery firstPage() {
        return new PageQuery(DEFAULT_LIMIT, 0);
    }
}
