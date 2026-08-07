package com.nexio.workflow.application.usecase.command;

import java.util.function.Consumer;

/**
 * Um campo de atualizacao parcial: ou o chamador enviou um valor, ou nao tocou no campo.
 *
 * <p>Existe porque "nulo significa nao mexer" nao consegue expressar as duas intencoes diferentes
 * que um update parcial recebe. Quem manda {@code description: null} querendo apagar a descricao e
 * quem simplesmente nao mandou {@code description} produzem exatamente o mesmo valor no comando, e
 * o caso de uso teria que escolher uma das duas leituras para todo mundo -- ou nunca da para
 * limpar um campo, ou toda atualizacao parcial apaga o que nao foi enviado.</p>
 *
 * <p>Nao usa {@link java.util.Optional} pelo mesmo motivo: {@code Optional} nao carrega
 * {@code null}, entao {@code Optional.empty()} teria que servir de uma vez para "nao enviado" e
 * para "enviado como nulo". Aqui os dois estados sao distintos: {@link #unchanged()} e
 * {@code of(null)}.</p>
 *
 * @param present {@code true} quando o chamador enviou o campo, mesmo que com valor nulo
 * @param value   valor enviado; sempre {@code null} quando {@code present} e falso
 * @param <T>     tipo do campo
 */
public record Patch<T>(boolean present, T value) {

    private static final Patch<?> UNCHANGED = new Patch<>(false, null);

    public Patch {
        if (!present && value != null) {
            throw new IllegalArgumentException("Um campo nao enviado nao pode carregar valor: " + value);
        }
    }

    /**
     * Campo enviado pelo chamador.
     *
     * @param value valor enviado; {@code null} significa limpar o campo, e so faz sentido para
     *              campos que aceitam nulo
     * @param <T>   tipo do campo
     * @return recorte presente
     */
    public static <T> Patch<T> of(T value) {
        return new Patch<>(true, value);
    }

    /**
     * Campo que o chamador nao enviou e que deve permanecer como esta.
     *
     * @param <T> tipo do campo
     * @return recorte ausente
     */
    @SuppressWarnings("unchecked")
    public static <T> Patch<T> unchanged() {
        return (Patch<T>) UNCHANGED;
    }

    /**
     * Aplica a acao somente quando o campo foi enviado.
     *
     * @param action acao a executar com o valor enviado
     */
    public void ifPresent(Consumer<? super T> action) {
        if (present) {
            action.accept(value);
        }
    }
}
