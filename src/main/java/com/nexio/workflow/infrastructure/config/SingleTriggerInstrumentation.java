package com.nexio.workflow.infrastructure.config;

import graphql.ExecutionResult;
import graphql.execution.AbortExecutionException;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import java.util.Map;

/**
 * Recusa um documento que dispare mais de um workflow.
 *
 * <p><b>Por que o teto de complexidade nao resolve isto.</b> O calculo pontua o formato da selecao,
 * e {@code triggerWorkflow(id: "x") &#123; id &#125;} tem o formato mais barato que existe: vale 2.
 * Sob o teto de {@value GraphQlQueryCostConfig#MAX_COMPLEXITY} cabiam mil apelidos do mesmo campo
 * num documento de uns 40 KB -- bem abaixo do teto de corpo da requisicao --, e o GraphQL executa
 * campos de mutation na raiz <b>em serie</b>. Uma requisicao anonima comprava mil execucoes
 * sincronas na mesma thread: cada uma ate o teto de tempo da engine, cada uma capaz de
 * {@value com.nexio.workflow.domain.model.WorkflowExecution#MAX_STEPS} chamadas HTTP de saida.</p>
 *
 * <p>E o mesmo defeito de sempre -- o que decide quanto trabalho o servidor faz nao aparece no
 * calculo --, mas aqui a unidade de trabalho e uma execucao inteira de workflow, e nao uma linha.
 * Nao ha numero de complexidade que descreva isso honestamente: dar a {@code triggerWorkflow} um
 * custo alto o bastante para caber so uma vez faria qualquer selecao de campos filhos estourar o
 * teto, e um custo menor deixaria passar dez disparos, que ja sao cinco minutos de thread.</p>
 *
 * <p>Por isso a regra e declarada e nao calculada: <b>no maximo um {@code triggerWorkflow} por
 * documento</b>. E facil de explicar a quem escreve o cliente, e nao depende de o teto de
 * complexidade continuar calibrado.</p>
 *
 * <p>A contagem desce por fragmento e por fragmento inline: sem isso, mover os apelidos para dentro
 * de um fragmento contornaria a regra inteira.</p>
 */
public class SingleTriggerInstrumentation implements Instrumentation {

    /** Campo de mutation cuja repeticao e recusada. */
    public static final String TRIGGER_FIELD = "triggerWorkflow";

    /** Teto de disparos por documento. */
    public static final int MAX_TRIGGERS_PER_DOCUMENT = 1;

    @Override
    public InstrumentationContext<ExecutionResult> beginExecuteOperation(
            InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
        OperationDefinition operation = parameters.getExecutionContext().getOperationDefinition();
        if (operation != null && operation.getOperation() == OperationDefinition.Operation.MUTATION) {
            Map<String, FragmentDefinition> fragments = parameters.getExecutionContext().getFragmentsByName();
            int triggers = count(operation.getSelectionSet(), fragments, 0);
            if (triggers > MAX_TRIGGERS_PER_DOCUMENT) {
                throw new AbortExecutionException("Um documento pode disparar no maximo "
                        + MAX_TRIGGERS_PER_DOCUMENT + " workflow, e este dispara " + triggers
                        + ". Cada disparo executa o workflow inteiro de forma sincrona; envie um por"
                        + " requisicao");
            }
        }
        return Instrumentation.super.beginExecuteOperation(parameters, state);
    }

    /**
     * Conta os {@code triggerWorkflow} da selecao, descendo por fragmentos.
     *
     * <p>A profundidade e limitada para que um fragmento que se referencie -- direta ou
     * indiretamente -- nao vire recursao infinita aqui. A validacao do proprio GraphQL ja recusa
     * ciclo de fragmento, mas esta instrumentacao roda depois dela e nao deve depender disso para
     * terminar.</p>
     */
    private static int count(SelectionSet selectionSet, Map<String, FragmentDefinition> fragments, int depth) {
        if (selectionSet == null || depth > GraphQlQueryCostConfig.MAX_DEPTH) {
            return 0;
        }
        int total = 0;
        for (Selection<?> selection : selectionSet.getSelections()) {
            switch (selection) {
                case Field field -> {
                    if (TRIGGER_FIELD.equals(field.getName())) {
                        total++;
                    }
                }
                case InlineFragment inline -> total += count(inline.getSelectionSet(), fragments, depth + 1);
                case FragmentSpread spread -> {
                    FragmentDefinition fragment = fragments.get(spread.getName());
                    if (fragment != null) {
                        total += count(fragment.getSelectionSet(), fragments, depth + 1);
                    }
                }
                default -> { }
            }
        }
        return total;
    }
}
