package com.nexio.workflow.api.rest;

import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.exception.WorkflowConcurrentlyModifiedException;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import com.nexio.workflow.domain.model.TextSanitizer;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz as excecoes de dominio em codigo de status HTTP para os controladores REST.
 *
 * <p>Existe pela mesma razao do {@code WorkflowExceptionResolver} do lado GraphQL, e resolve o mesmo
 * defeito que ja apareceu duas vezes neste projeto: <b>sem um tradutor, erro de entrada do usuario
 * chega ao cliente como erro de servidor.</b> Um {@code id} inexistente e um workflow desativado sao
 * {@code 500} por omissao -- e um {@code 500} manda o cliente reportar um incidente e o operador
 * procurar um defeito que nao existe, alem de despejar a pilha inteira no log para o que e erro de
 * digitacao.</p>
 *
 * <p>O vocabulario de protocolo entra aqui, e so aqui. {@link WorkflowNotFoundException} e
 * {@link InvalidWorkflowException} vivem no dominio e nao sabem o que e um codigo de status; e esta
 * classe que decide que uma vira {@code 404} e a outra {@code 400}.</p>
 *
 * <p><b>O que nao esta mapeado cai no {@code 500} padrao do Spring, e isso e deliberado.</b> Uma
 * excecao inesperada carrega nome de classe, caminho de arquivo e, com frequencia, trecho de dado
 * interno na mensagem; devolve-la ao cliente e entregar um mapa da aplicacao. O detalhe fica no log
 * do servidor. Em particular <b>nao</b> existe {@code @ExceptionHandler(Exception.class)} aqui: ele
 * pegaria tambem o defeito de programacao e o transformaria numa resposta bem formatada com a
 * mensagem original, ou seja, entregaria ao cliente exatamente o que o {@code 500} padrao esconde.
 * A lista de tipos tratados abaixo e a lista inteira.</p>
 *
 * <p>O {@link ProblemDetail} e o formato do RFC 9457, que ja e o padrao do Spring para erro em REST
 * -- inventar um envelope proprio criaria um segundo formato de erro no mesmo servico.</p>
 */
// Restrito ao controlador do disparo simulado: o advice vale so para quem esta declarado aqui, e
// nao para todo controlador que a aplicacao venha a ter. Um advice global mapearia excecoes de
// dominio em endpoints que ainda nao existem e cujo contrato de erro ninguem decidiu.
@RestControllerAdvice(assignableTypes = MockTriggerController.class)
public class RestExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RestExceptionHandler.class);

    /** Teto da mensagem devolvida, pelo mesmo motivo que ela existe no resolver GraphQL. */
    private static final int MAX_MESSAGE_LENGTH = 500;

    /**
     * Workflow ou execucao inexistente.
     *
     * @param e excecao capturada
     * @return {@code 404} com a mensagem do dominio
     */
    @ExceptionHandler(WorkflowNotFoundException.class)
    public ProblemDetail handleNotFound(WorkflowNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Entrada recusada pelo dominio: payload fora da politica de escrita, workflow desativado.
     *
     * @param e excecao capturada
     * @return {@code 400} com a mensagem do dominio
     */
    @ExceptionHandler(InvalidWorkflowException.class)
    public ProblemDetail handleInvalid(InvalidWorkflowException e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Escrita concorrente sobre o mesmo documento.
     *
     * @param e excecao capturada
     * @return {@code 409}, o unico status que descreve conflito de versao
     */
    @ExceptionHandler(WorkflowConcurrentlyModifiedException.class)
    public ProblemDetail handleConflict(WorkflowConcurrentlyModifiedException e) {
        return problem(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * Violacao das restricoes declaradas nos argumentos do controlador.
     *
     * <p>A mensagem e montada a partir das violacoes em vez de usar o texto padrao: o padrao do
     * {@code ConstraintViolationException} e um despejo do objeto violado, que devolveria ao cliente
     * o proprio valor recusado.</p>
     *
     * @param e excecao capturada
     * @return {@code 400} com o caminho e a mensagem de cada violacao
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleViolations(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("entrada invalida");
        return problem(HttpStatus.BAD_REQUEST, detail);
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        LOG.debug("Requisicao recusada: status={} detalhe={}", status.value(), detail);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status, TextSanitizer.truncateSystemText(detail, MAX_MESSAGE_LENGTH));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
