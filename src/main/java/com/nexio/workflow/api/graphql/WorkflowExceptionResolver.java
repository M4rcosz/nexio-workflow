package com.nexio.workflow.api.graphql;

import com.nexio.workflow.domain.exception.InvalidWorkflowException;
import com.nexio.workflow.domain.exception.WorkflowConcurrentlyModifiedException;
import com.nexio.workflow.domain.exception.WorkflowNotFoundException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

/**
 * Traduz as excecoes do dominio e as falhas de validacao para erros GraphQL.
 *
 * <p>E aqui, e so aqui, que o vocabulario de protocolo entra: {@link WorkflowNotFoundException},
 * {@link InvalidWorkflowException} e {@link WorkflowConcurrentlyModifiedException} vivem no dominio
 * justamente por nao conhecerem HTTP nem GraphQL. Sem esta traducao, as tres cairiam no tratamento
 * padrao e virariam {@code INTERNAL_ERROR} com a mensagem generica -- o cliente receberia "erro
 * interno" para um id que ele digitou errado.</p>
 *
 * <p><b>O que nao esta mapeado cai no padrao de proposito.</b> O spring-graphql troca a mensagem de
 * qualquer excecao nao resolvida por um texto generico e registra o resto no log do servidor, e e
 * exatamente esse o comportamento desejado: mensagem de excecao inesperada carrega nome de classe,
 * trecho de consulta, as vezes endereco de banco. Um resolver que devolvesse
 * {@code e.getMessage()} para tudo transformaria cada bug em um relatorio para quem estivesse
 * sondando o servico. As mensagens que saem daqui sao as do dominio, escritas para serem lidas pelo
 * cliente e ja truncadas e higienizadas na propria excecao.</p>
 *
 * <p>{@link ConstraintViolationException} vira {@code BAD_REQUEST} com o caminho e a mensagem de
 * cada restricao violada, e nunca com o valor recusado: o valor e a entrada do usuario e ecoa-lo de
 * volta numa mensagem de erro e o caminho classico de reflexao. O texto padrao do
 * {@code ConstraintViolationException} tambem nao serve -- ele e um despejo do objeto violado.</p>
 */
@Component
public class WorkflowExceptionResolver extends DataFetcherExceptionResolverAdapter {

    /** Tamanho maximo do texto agregado das violacoes de validacao. */
    private static final int MAX_VIOLATIONS_MESSAGE_LENGTH = 1000;

    /** Codigo publicado na extensao do erro de escrita concorrente. */
    private static final String CONFLICT_CODE = "CONFLICT";

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        return switch (ex) {
            case WorkflowNotFoundException notFound -> error(env, ErrorType.NOT_FOUND, notFound.getMessage());
            case InvalidWorkflowException invalid -> error(env, ErrorType.BAD_REQUEST, invalid.getMessage());
            case WorkflowConcurrentlyModifiedException conflict -> conflict(env, conflict);
            case ConstraintViolationException violations ->
                    error(env, ErrorType.BAD_REQUEST, describe(violations));
            default -> null;
        };
    }

    private GraphQLError error(DataFetchingEnvironment env, ErrorType errorType, String message) {
        return GraphqlErrorBuilder.newError(env)
                .errorType(errorType)
                .message(message)
                .build();
    }

    /**
     * Traduz o conflito de escrita concorrente.
     *
     * <p>Sai como {@code BAD_REQUEST} porque e a unica classificacao de erro do spring-graphql que
     * significa "o pedido nao foi aceito e refaze-lo pode dar certo" -- {@code INTERNAL_ERROR} diria
     * ao cliente que o problema e do servidor e que reenviar nao adianta, que e o contrario do que
     * vale aqui. A distincao fina fica na extensao {@code code}: e ela que separa "o que voce enviou
     * esta errado", em que reenviar igual nunca funciona, de "alguem chegou antes", em que reler e
     * reenviar e exatamente o procedimento.</p>
     */
    private GraphQLError conflict(DataFetchingEnvironment env, WorkflowConcurrentlyModifiedException ex) {
        return GraphqlErrorBuilder.newError(env)
                .errorType(ErrorType.BAD_REQUEST)
                .message(ex.getMessage())
                .extensions(Map.of("code", CONFLICT_CODE))
                .build();
    }

    /**
     * Monta a mensagem das violacoes em ordem estavel, sem repetir caminho e sem incluir o valor
     * recusado. A ordenacao existe porque {@code getConstraintViolations()} devolve um
     * {@code Set} sem ordem definida, e uma mensagem que muda de ordem a cada execucao e uma
     * mensagem que ninguem consegue testar nem comparar entre dois chamados.
     */
    private String describe(ConstraintViolationException ex) {
        Set<String> details = new TreeSet<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            details.add(lastPathNode(violation) + ": " + violation.getMessage());
        }
        String message = "Entrada invalida: " + String.join("; ", details);
        return message.length() <= MAX_VIOLATIONS_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_VIOLATIONS_MESSAGE_LENGTH) + "...";
    }

    /**
     * Devolve o caminho da propriedade violada sem o nome do metodo do resolver, que e detalhe de
     * implementacao do servidor e nao ajuda quem escreveu a consulta.
     */
    private String lastPathNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int firstSeparator = path.indexOf('.');
        return firstSeparator < 0 ? path : path.substring(firstSeparator + 1);
    }
}
