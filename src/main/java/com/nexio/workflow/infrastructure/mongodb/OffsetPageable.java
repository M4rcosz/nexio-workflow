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
 */
final class OffsetPageable implements Pageable {

    private final long offset;
    private final int limit;

    private OffsetPageable(long offset, int limit) {
        this.offset = offset;
        this.limit = limit;
    }

    /**
     * Converte um recorte do dominio.
     *
     * @param page recorte de paginacao, nunca nulo
     * @return {@code Pageable} equivalente
     */
    static Pageable of(PageQuery page) {
        return new OffsetPageable(page.offset(), page.limit());
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
        return Sort.unsorted();
    }

    @Override
    public Pageable next() {
        return new OffsetPageable(offset + limit, limit);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetPageable(Math.max(0, offset - limit), limit) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetPageable(0, limit);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageable((long) pageNumber * limit, limit);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
