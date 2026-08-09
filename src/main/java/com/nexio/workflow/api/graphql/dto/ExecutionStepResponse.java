package com.nexio.workflow.api.graphql.dto;

import com.nexio.workflow.api.graphql.SecretRedactor;
import com.nexio.workflow.domain.model.ExecutionStep;
import com.nexio.workflow.domain.model.enums.StepStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Representacao de leitura de um passo de execucao.
 *
 * <p><b>O {@code output} e o dado mais perigoso que este schema devolve.</b> Ele e o corpo de
 * resposta de um servico de terceiro, guardado inteiro: um passo que chamou uma API de pagamento
 * traz de volta o que aquela API respondeu, e uma resposta de autenticacao traz {@code token},
 * {@code access_token} ou {@code refresh_token} com nomes exatamente iguais aos que a redacao ja
 * procura na config de um no. Sem redacao aqui, o caminho de vazamento seria mais curto do que o da
 * config: nem seria preciso escrever a credencial no workflow -- bastaria chamar um endpoint que
 * devolve uma e depois ler o historico.</p>
 *
 * <p>A redacao fica no construtor canonico pelo mesmo motivo do {@link WorkflowNodeResponse}: nao
 * existe caminho de construcao que escape do construtor compacto, entao nenhum resolver futuro
 * consegue esquecer de redigir. Redigir no mapper protegeria so enquanto todo mundo passasse pelo
 * mapper.</p>
 *
 * <p>O {@code error} nao passa pela redacao de mapa porque nao e mapa, mas passa por
 * {@link SecretRedactor#redactUrlsIn(String)}: a mensagem de falha de um no HTTP pode citar o
 * endereco chamado, e um endereco carrega credencial em {@code userinfo} e em parametro de consulta.
 * E {@code redactUrlsIn} e nao {@code redactUrl} porque aqui o endereco vem <i>dentro</i> de uma
 * frase, e o segundo so funciona quando o valor inteiro e um URI.</p>
 *
 * @param nodeId     identificador do no executado
 * @param status     estado final do passo
 * @param output     dados produzidos pelo no, ja com os valores sensiveis mascarados
 * @param error      mensagem de erro quando o passo falha, nula caso contrario
 * @param executedAt momento em que o passo foi executado
 */
public record ExecutionStepResponse(
        String nodeId,
        StepStatus status,
        Map<String, Object> output,
        String error,
        OffsetDateTime executedAt
) {

    public ExecutionStepResponse {
        output = SecretRedactor.redact(output);
        error = SecretRedactor.redactUrlsIn(error);
    }

    /**
     * Converte um passo do dominio.
     *
     * @param step passo a converter
     * @return representacao ja redigida
     */
    public static ExecutionStepResponse from(ExecutionStep step) {
        return new ExecutionStepResponse(
                step.nodeId(), step.status(), step.output(), step.error(),
                step.executedAt() == null ? null : step.executedAt().atOffset(ZoneOffset.UTC));
    }
}
