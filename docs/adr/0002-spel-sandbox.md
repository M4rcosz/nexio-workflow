# ADR 0002 -- Avaliacao de condicoes com SpEL restrito

## Status

Aceito -- 2026-08-07. Vale a partir da issue #22 (`ConditionNodeExecutor`), ainda nao implementada.

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
