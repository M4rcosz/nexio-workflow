package com.nexio.workflow.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.ast.Assign;
import org.springframework.expression.spel.ast.BeanReference;
import org.springframework.expression.spel.ast.BooleanLiteral;
import org.springframework.expression.spel.ast.CompoundExpression;
import org.springframework.expression.spel.ast.ConstructorReference;
import org.springframework.expression.spel.ast.Elvis;
import org.springframework.expression.spel.ast.FloatLiteral;
import org.springframework.expression.spel.ast.FunctionReference;
import org.springframework.expression.spel.ast.Indexer;
import org.springframework.expression.spel.ast.InlineList;
import org.springframework.expression.spel.ast.InlineMap;
import org.springframework.expression.spel.ast.IntLiteral;
import org.springframework.expression.spel.ast.LongLiteral;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.ast.NullLiteral;
import org.springframework.expression.spel.ast.OpAnd;
import org.springframework.expression.spel.ast.OpDivide;
import org.springframework.expression.spel.ast.OpEQ;
import org.springframework.expression.spel.ast.OpGE;
import org.springframework.expression.spel.ast.OpGT;
import org.springframework.expression.spel.ast.OpLE;
import org.springframework.expression.spel.ast.OpLT;
import org.springframework.expression.spel.ast.OpMinus;
import org.springframework.expression.spel.ast.OpModulus;
import org.springframework.expression.spel.ast.OpMultiply;
import org.springframework.expression.spel.ast.OpNE;
import org.springframework.expression.spel.ast.OpOr;
import org.springframework.expression.spel.ast.OpPlus;
import org.springframework.expression.spel.ast.OperatorMatches;
import org.springframework.expression.spel.ast.OperatorNot;
import org.springframework.expression.spel.ast.OperatorPower;
import org.springframework.expression.spel.ast.Projection;
import org.springframework.expression.spel.ast.PropertyOrFieldReference;
import org.springframework.expression.spel.ast.RealLiteral;
import org.springframework.expression.spel.ast.Selection;
import org.springframework.expression.spel.ast.StringLiteral;
import org.springframework.expression.spel.ast.Ternary;
import org.springframework.expression.spel.ast.TypeReference;
import org.springframework.expression.spel.ast.VariableReference;

/**
 * Recusa, na escrita, toda expressao de no CONDITION que saia da gramatica permitida.
 *
 * <p>Roda no {@code validateGraph()} da definicao, ou seja, no {@code createWorkflow} e no
 * {@code updateWorkflow}: uma expressao malformada ou proibida vira BAD_REQUEST enquanto o autor
 * esta com ela na tela, e nao falha de execucao no disparo numero 4000. Fazer o <i>parse</i> na
 * escrita nao avalia nada -- o parser constroi a arvore e para ai.</p>
 *
 * <p><b>O contexto de avaliacao nao e suficiente.</b> A ADR 0002 decidiu avaliar com
 * {@code SimpleEvaluationContext}, que de fato nao resolve tipo, nao chama construtor e nao invoca
 * metodo: {@code T(java.lang.Runtime).getRuntime()} e {@code ''.getClass()} morrem la. O que ele
 * <b>nao</b> limita e o custo, e e por isso que esta classe existe.</p>
 *
 * <p>A ADR afirmava que "a avaliacao e limitada no tempo pela engine". Nao e, e a medicao esta na
 * secao de correcao da propria ADR: o teto de tempo da engine e conferido <i>entre</i> nos e nunca
 * interrompe um no em andamento. Selecao aninhada e exponencial, e o teto de
 * {@value WorkflowDefinition#MAX_EXPRESSION_LENGTH} caracteres nao segura nada perto disso --
 * {@code #t['i'].?[#t['i'].?[#t['i'].?[#t['i'].?[true].size>0].size>0].size>0].size>0} tem 84
 * caracteres e avaliou por 49 segundos numa lista de 200 itens. Pior: com lista literal
 * ({@code {1,2,3}.?[...]}) nem payload e preciso, e 512 caracteres compram cerca de dez elevado a
 * quinze iteracoes. Como {@code createWorkflow} e o disparo sao anonimos hoje, isso e negacao de
 * servico sem autenticacao.</p>
 *
 * <p><b>A regra e lista de permissao, e nao lista de bloqueio.</b> Todo tipo de no da arvore que
 * nao esteja em {@link #ALLOWED_NODES} e recusado, inclusive um que ainda nao exista: se uma versao
 * futura do Spring introduzir um construto novo na gramatica do SpEL, ele chega aqui recusado por
 * padrao, e nao permitido por omissao. E a mesma polaridade que a ADR 0002 defende contra lista de
 * bloqueio de tipos, aplicada um nivel acima.</p>
 *
 * <p>Com a lista de permissao em vigor, o custo da avaliacao passa a ser linear no tamanho da
 * arvore: sobram literais, operadores, leitura de propriedade e indexacao, e nenhum deles itera.
 * Como a arvore e limitada pelo teto de caracteres da expressao, a avaliacao fica limitada sem
 * precisar de relogio, de thread separada ou de deadline -- que e o que faz a alternativa do
 * "avalia com timeout" ser pior do que parece: {@code Thread.interrupt()} nao interrompe um laco de
 * selecao do SpEL, entao a thread abandonada continuaria queimando um nucleo ate terminar
 * sozinha.</p>
 *
 * <p>O que se perde de util e {@code matches}. Ele sai por motivo proprio, e nao por custo de
 * iteracao: a expressao regular e o texto comparado sao os dois controlados por quem ataca, e
 * {@code 'aaaaaaaaaaaaaaaaaaaaaa' matches '(a+)+$'} e retrocesso catastrofico em vinte caracteres.
 * Um comparador de texto que nao seja uma linguagem completa pode voltar depois como operador
 * modelado no dominio.</p>
 */
public final class ConditionExpressionValidator {

    /**
     * Construtos aceitos dentro de uma expressao de condicao.
     *
     * <p>Sao os que leem dado e combinam valor. Nenhum deles itera sobre colecao, constroi objeto,
     * resolve tipo ou chama metodo, e e essa ausencia -- e nao o tamanho da lista -- que torna a
     * avaliacao linear.</p>
     */
    private static final Set<Class<?>> ALLOWED_NODES = Set.of(
            // Literais.
            StringLiteral.class, IntLiteral.class, LongLiteral.class,
            RealLiteral.class, FloatLiteral.class, BooleanLiteral.class, NullLiteral.class,
            // Logica e comparacao.
            OpAnd.class, OpOr.class, OperatorNot.class,
            OpEQ.class, OpNE.class, OpGT.class, OpGE.class, OpLT.class, OpLE.class,
            // Aritmetica. Sem potencia: ver REJECTION_REASONS.
            OpPlus.class, OpMinus.class, OpMultiply.class, OpDivide.class, OpModulus.class,
            // Desvio dentro da propria expressao.
            Ternary.class, Elvis.class,
            // Acesso ao dado.
            VariableReference.class, Indexer.class,
            PropertyOrFieldReference.class, CompoundExpression.class);

    /**
     * Explicacao para os construtos recusados que alguem tentaria de boa-fe.
     *
     * <p>A mensagem diz o nome que a pessoa escreveu ({@code ?[...]}, {@code new}) e nao o nome da
     * classe interna do Spring: quem recebeu o erro escreveu uma expressao, nao uma arvore. O que
     * nao esta no mapa cai no texto generico, que e o caso do construto que ninguem digita por
     * engano.</p>
     */
    private static final Map<Class<?>, String> REJECTION_REASONS = rejectionReasons();

    private static final String CONSTRUCT_NOT_ALLOWED = "usa um construto nao permitido";

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private ConditionExpressionValidator() {
        throw new AssertionError("Classe utilitaria nao deve ser instanciada");
    }

    /**
     * Recusa a expressao que o parser nao entende ou que sai da gramatica permitida.
     *
     * @param expression expressao do no CONDITION, ja conferida quanto a tamanho
     * @param nodeId     id do no, usado so para localizar o erro na mensagem
     * @throws IllegalArgumentException quando a expressao nao compila ou usa construto proibido
     */
    public static void validate(String expression, String nodeId) {
        SpelExpression parsed;
        try {
            parsed = (SpelExpression) PARSER.parseExpression(expression);
        } catch (ParseException e) {
            throw new IllegalArgumentException(
                    "expression do no '" + nodeId + "' nao e uma expressao valida: "
                            + TextSanitizer.truncateSystemText(e.getSimpleMessage(), 160), e);
        }
        reject(parsed.getAST(), nodeId);
    }

    /**
     * Percorre a arvore inteira, e nao so a raiz: o construto proibido quase sempre esta no meio.
     */
    private static void reject(SpelNode node, String nodeId) {
        if (!ALLOWED_NODES.contains(node.getClass())) {
            throw new IllegalArgumentException("expression do no '" + nodeId + "' "
                    + REJECTION_REASONS.getOrDefault(node.getClass(), CONSTRUCT_NOT_ALLOWED)
                    + ": " + TextSanitizer.truncateSystemText(node.toStringAST(), 80));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            reject(node.getChild(i), nodeId);
        }
    }

    private static Map<Class<?>, String> rejectionReasons() {
        Map<Class<?>, String> reasons = new LinkedHashMap<>();
        reasons.put(Selection.class,
                "usa selecao '?[...]', que nao e permitida porque aninhada custa tempo exponencial");
        reasons.put(Projection.class,
                "usa projecao '![...]', que nao e permitida porque itera sobre a colecao inteira");
        reasons.put(InlineList.class,
                "usa lista literal '{...}', que so serve para alimentar selecao ou projecao");
        reasons.put(InlineMap.class,
                "usa mapa literal '{chave:valor}', que so serve para alimentar selecao ou projecao");
        reasons.put(MethodReference.class,
                "chama metodo, e uma condicao so pode ler campo e compara-lo");
        reasons.put(ConstructorReference.class, "usa 'new', que nao constroi objeto numa condicao");
        reasons.put(TypeReference.class, "usa 'T(...)' para referenciar tipo, que nao e permitido");
        reasons.put(BeanReference.class, "usa '@' para referenciar bean da aplicacao");
        reasons.put(FunctionReference.class, "chama funcao registrada, e nenhuma existe aqui");
        reasons.put(Assign.class, "atribui valor, e uma condicao so le");
        reasons.put(OperatorMatches.class,
                "usa 'matches', que nao e permitido porque o padrao e o texto vem os dois de fora "
                        + "e retrocesso catastrofico trava a avaliacao");
        reasons.put(OperatorPower.class,
                "usa potencia '^', que nao e permitida porque o expoente vem de fora");
        return Map.copyOf(reasons);
    }
}
