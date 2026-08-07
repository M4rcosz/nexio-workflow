package com.nexio.workflow.infrastructure.mongodb;

import com.nexio.workflow.application.port.out.PageQuery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Traduz o {@link PageQuery} do dominio para o {@link Pageable} do Spring Data.
 *
 * <p>O {@code PageRequest} do Spring Data e indexado por numero de pagina, e a porta fala em
 * deslocamento absoluto; converter um no outro so daria certo quando o deslocamento fosse multiplo
 * do limite. Este {@code Pageable} carrega o deslocamento como ele e.</p>
 *
 * <p>Fica em {@code infrastructure} de proposito: e exatamente a fronteira onde o vocabulario do
 * Spring Data pode aparecer.</p>
 *
 * <p><b>Paginar sem ordenar nao pagina.</b> {@code skip} mais {@code limit} sobre a ordem natural do
 * MongoDB assume que a ordem natural e estavel, e ela nao e: um documento atualizado entre duas
 * paginas pode mudar de lugar, e ai o mesmo registro sai nas duas paginas enquanto outro nao sai em
 * nenhuma -- perda silenciosa, que o cliente nao tem como perceber. A ordem passa a ser {@code _id}
 * crescente por ser a unica que ja vem de graca: o indice {@code _id_} existe em toda colecao, entao
 * a garantia nao custa indice novo nem ordenacao em memoria.</p>
 */
final class OffsetPageable implements Pageable {

    /** Ordem padrao dos recortes: unica servida pelo indice que toda colecao ja tem. */
    private static final Sort BY_ID = Sort.by(Sort.Direction.ASC, "_id");

    private final long offset;
    private final int limit;
    private final Sort sort;

    private OffsetPageable(long offset, int limit, Sort sort) {
        this.offset = offset;
        this.limit = limit;
        this.sort = sort;
    }

    /**
     * Converte um recorte do dominio, ordenando por {@code _id} crescente.
     *
     * @param page recorte de paginacao, nunca nulo
     * @return {@code Pageable} equivalente, ordenado
     */
    static Pageable of(PageQuery page) {
        return new OffsetPageable(page.offset(), page.limit(), BY_ID);
    }

    /**
     * Converte um recorte do dominio sem declarar ordem.
     *
     * <p>Para as consultas derivadas que ja trazem {@code OrderBy} no nome. Ali a ordem estavel ja
     * existe, e o {@code Sort} do {@code Pageable} seria somado a ela como criterio de desempate:
     * ordenar por {@code createdAt} decrescente e depois por {@code _id} deixa de casar com o indice
     * composto que cobre a consulta e troca uma leitura ja ordenada por uma ordenacao em memoria.</p>
     *
     * @param page recorte de paginacao, nunca nulo
     * @return {@code Pageable} equivalente, sem ordenacao propria
     */
    static Pageable unsorted(PageQuery page) {
        return new OffsetPageable(page.offset(), page.limit(), Sort.unsorted());
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetPageable(offset + limit, limit, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetPageable(Math.max(0, offset - limit), limit, sort) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetPageable(0, limit, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageable((long) pageNumber * limit, limit, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
