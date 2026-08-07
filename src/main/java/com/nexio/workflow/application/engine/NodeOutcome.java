package com.nexio.workflow.application.engine;

/**
 * Desfecho da execucao de um no. E a unica coisa que a engine consulta para decidir se continua e
 * por qual aresta.
 *
 * <p><b>Por que quatro valores e nao um {@code boolean} mais um {@code Boolean} de ramo.</b> A
 * forma obvia seria {@code (boolean sucesso, Boolean ramo)}, e ela mistura tres perguntas
 * diferentes num tipo que nao consegue distinguir "no que nao e condicional" de "condicao que deu
 * falso": os dois viram {@code null} e {@code false} conforme quem escreveu o executor tenha
 * lembrado. Aqui cada estado tem nome, o {@code switch} da engine fica exaustivo sem ramo
 * {@code default} e o compilador passa a cobrar o caso novo se um tipo de no com terceira saida
 * aparecer.</p>
 *
 * <p>{@link #CONDITION_TRUE} e {@link #CONDITION_FALSE} sao os dois desfechos de um no CONDITION
 * <b>avaliado com sucesso</b>: uma condicao falsa nao e uma falha, e a diferenca importa porque
 * falha para a execucao e falso apenas escolhe {@code nextOnFalse}. Uma expressao que estourou na
 * avaliacao e {@link #FAILURE}, nao {@code CONDITION_FALSE} -- tratar erro como "falso" faria a
 * execucao seguir por um ramo que ninguem decidiu seguir.</p>
 */
public enum NodeOutcome {

    /** No nao condicional executado com exito: a engine segue por {@code nextOnSuccess}. */
    SUCCESS,

    /** No CONDITION avaliado como verdadeiro: a engine segue por {@code nextOnTrue}. */
    CONDITION_TRUE,

    /** No CONDITION avaliado como falso: a engine segue por {@code nextOnFalse}. */
    CONDITION_FALSE,

    /** O no nao cumpriu o que devia: a engine para de avancar e a execucao termina FAILED. */
    FAILURE
}
