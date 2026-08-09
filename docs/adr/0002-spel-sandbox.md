# ADR 0002 -- Avaliacao de condicoes com SpEL restrito

## Status

Aceito -- 2026-08-07. Implementado na issue #22 (`ConditionNodeExecutor`), com as correcoes
registradas no fim deste documento.

## Contexto

O no `CONDITION` guarda a condicao em `config.expression`, uma string arbitraria enviada pelo
cliente na criacao do workflow. O Sprint 3 vai avalia-la com SpEL.

A forma obvia de escrever isso e a forma perigosa:

```java
new SpelExpressionParser().parseExpression(expression).getValue(new StandardEvaluationContext(ctx));
```

O `StandardEvaluationContext` habilita referencia de tipo, construtor e invocacao de metodo
arbitrario. Com ele, `T(java.lang.Runtime).getRuntime().exec("...")` e execucao remota de codigo em
uma linha, escrita por qualquer um que consiga chamar `createWorkflow`. Nao e uma hipotese: e a
classe de falha mais explorada em aplicacoes Spring.

O projeto ja raciocinou com cuidado sobre SSRF (`HttpTargetValidator`) e sobre injecao de operador
do MongoDB (`MapSanitizer`), mas nao ha nada sobre injecao de expressao. Hoje o unico limite em
vigor e acidental: `MapSanitizer.MAX_STRING_LENGTH` (4096) limita o tamanho da expressao porque ela
e um valor de string dentro de `config`, e nao porque alguem decidiu limita-la.

A decisao vem antes do codigo de proposito. Escolher o contexto de avaliacao no meio da
implementacao do executor e escolher sob pressao de fazer o teste passar.

## Decisao

1. **Nunca `StandardEvaluationContext`.** A avaliacao usa
   `SimpleEvaluationContext.forReadOnlyDataBinding().build()`, que nao resolve tipos, nao chama
   construtor e nao invoca metodo -- so le propriedades do objeto raiz.
2. **A expressao e validada na escrita.** O dominio faz o parse na criacao e na atualizacao. Uma
   expressao malformada vira `BAD_REQUEST` no `createWorkflow`, e nao uma falha de execucao no
   disparo numero 4000. Parse nao e avaliacao: fazer o parse na escrita nao executa nada.
3. **Teto explicito de tamanho da expressao**, declarado no dominio, e nao herdado por acidente do
   limite generico de string do `MapSanitizer`.
4. **A avaliacao e limitada no tempo pela engine.** SpEL nao tem timeout proprio, e
   `forReadOnlyDataBinding` nao impede laco custoso em expressao patologica.

## Consequencias

- Expressoes ficam restritas a leitura de propriedade e operadores. Nao ha chamada de metodo, nem
  mesmo de metodo aparentemente inofensivo como `#root.nome.length()`. E uma limitacao real e
  aceita: a alternativa e manter uma lista de metodos permitidos, que e uma superficie que so
  cresce e que e preciso reavaliar a cada versao do Spring.
- Se um dia for preciso mais poder de expressao, a resposta **nao** e afrouxar o contexto: e uma
  linguagem de expressao propria, com gramatica declarada, ou um conjunto fechado de operadores
  modelados no dominio.
- O parse na escrita acopla o dominio ao SpEL. E o preco de recusar a expressao quebrada no momento
  em que o autor pode corrigi-la.

## Alternativas descartadas

- **`StandardEvaluationContext` com uma lista de bloqueio de tipos.** Lista de bloqueio contra uma
  linguagem completa e uma corrida que se perde; basta um caminho de reflexao nao previsto.
- **Avaliar em sandbox de `SecurityManager`.** Depreciado e a caminho da remocao no JDK.
- **Recusar SpEL e escrever um interpretador proprio.** Defensavel, e mais trabalho do que o
  problema pede enquanto `SimpleEvaluationContext` resolve.

## Correcoes feitas na implementacao (2026-08-08)

A decisao central -- nunca `StandardEvaluationContext` -- se sustentou: `T(java.lang.Runtime)`,
`''.getClass()` e `new ...` foram todos verificados contra `SimpleEvaluationContext` e morrem la.
O que nao sobreviveu foi o raciocinio sobre custo.

**1. O item 4 estava errado, e era a unica coisa que segurava o custo da avaliacao.** "A avaliacao e
limitada no tempo pela engine" nao e verdade: o teto de tempo da engine e conferido *entre* nos e
nunca interrompe um no em andamento -- o proprio Javadoc da engine ja dizia isso. Com o item 4 fora,
nao restava limite nenhum, e o item 3 (teto de tamanho) nao serve para esse fim. Medido:

```
#t['i'].?[#t['i'].?[#t['i'].?[#t['i'].?[true].size>0].size>0].size>0].size>0
```

Sao 84 caracteres, contra os 512 permitidos, e a avaliacao levou **49 segundos** numa lista de 200
itens. Selecao aninhada e exponencial: o nivel seguinte custaria cerca de duas horas e meia. Pior do
que o numero e a origem dos dados -- `{1,2,3}.?[...]` reproduz a mesma iteracao com lista literal,
sem payload nenhum, entao os limites do `MapSanitizer` sobre o que entra nao ajudam. Como
`createWorkflow` e o disparo sao anonimos hoje, era negacao de servico sem autenticacao.

**2. O `SimpleEvaluationContext` limita o que a expressao alcanca, nao quanto ela custa.** Vale
dizer explicitamente porque a ADR original tratava a escolha do contexto como se resolvesse a
questao inteira. Sao dois problemas distintos, e o contexto resolve so o primeiro.

**3. A resposta e uma lista de permissao de construtos, conferida na escrita.** O
`ConditionExpressionValidator` percorre a arvore que o parser produziu e recusa todo tipo de no que
nao esteja na lista: sobram literais, operadores logicos e aritmeticos, comparacao, ternario, elvis,
leitura de propriedade, indexacao e referencia a variavel. Saem selecao `?[...]`, projecao `![...]`,
lista e mapa literais, chamada de metodo, construtor, referencia de tipo, referencia a bean, funcao
e atribuicao. Com isso a avaliacao passa a ser linear no tamanho da arvore, que ja e limitada pelo
item 3 -- ou seja, o item 4 deixa de ser necessario em vez de ser consertado.

A polaridade e deliberada: **o construto desconhecido e recusado**. Um tipo de no novo numa versao
futura do SpEL chega aqui bloqueado por padrao. E o mesmo argumento que a secao de alternativas
descartadas usa contra lista de bloqueio de tipos, aplicado um nivel acima.

**4. `matches` sai junto, por motivo proprio.** Nao itera sobre colecao, mas o padrao e o texto
comparado vem os dois de quem escreve, e `'aaaaaaaaaaaaaaaaaaaaaa' matches '(a+)+$'` e retrocesso
catastrofico em vinte caracteres. E a unica recusa da lista que custa algo util; se voltar, volta
como operador de comparacao de texto modelado no dominio, e nao como expressao regular livre.

**5. Avaliar com timeout foi considerado e recusado.** `Thread.interrupt()` nao interrompe um laco
de selecao do SpEL: a thread abandonada continuaria queimando um nucleo ate terminar sozinha, e
disparos repetidos esgotariam o pool do mesmo jeito. Seria a aparencia de um limite sem o limite.

**6. O item 2 esta implementado, e com escopo maior do que o descrito.** O parse na escrita nao
serve so para recusar expressao malformada: e ele que da a arvore em que a lista de permissao e
aplicada. Roda no `validateGraph()` da definicao, ou seja, no `createWorkflow` e no
`updateWorkflow`.

**7. Uma coisa que a ADR nao previu: o SpEL nao embrulha tudo o que a avaliacao lanca.**
`total / 0` sai como `ArithmeticException` crua, sem passar por `SpelEvaluationException` --
descoberto por teste, e alcancavel com `total / quantidade` e um payload com quantidade zero. O
executor captura `RuntimeException` por isso, para que divisao por zero vire falha de no com o no
nomeado, e nao a mensagem generica da rede de seguranca da engine.

**8. O contexto de avaliacao final nao e o que o item 1 especifica.** O item 1 diz
`SimpleEvaluationContext.forReadOnlyDataBinding().build()`; o executor usa
`SimpleEvaluationContext.forPropertyAccessors(new MapAccessor(false)).build()`. A troca e deliberada
-- `forReadOnlyDataBinding` nao registra `MapAccessor`, entao `total > 100` nao resolveria contra um
`Map` e a unica forma seria `#trigger['total']` -- mas ela e relevante para seguranca e nao pode
ficar so no Javadoc do executor: a leitura-apenas passou a depender do argumento `false` do
`MapAccessor`, e nao de o acessor ser somente-leitura por construcao. Verificado que o construtor
com `false` recusa escrita, e ha teste para isso. O resto do item 1 continua valendo: nada de tipo,
construtor ou metodo.
