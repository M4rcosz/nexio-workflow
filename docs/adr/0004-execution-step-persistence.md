# ADR 0004 -- Como a execucao grava os passos

## Status

Aceito -- 2026-08-07. Vale a partir da issue #21 (`WorkflowEngine`), ainda nao implementada.

## Contexto

A engine executa os nos em sequencia e precisa registrar cada passo. A forma obvia e:

```java
execution.addStep(step);
executionPort.save(execution);   // uma vez por no
```

O problema e o que `save` faz no MongoDB: nao existe atualizacao parcial de documento por esse
caminho. Cada `save` reescreve o **documento inteiro**, inclusive o array `steps` completo. Com
`MAX_STEPS = 200` e cada passo carregando um `output` que pode ter ate 200 entradas, a N-esima
gravacao reescreve os N passos anteriores: o custo total e quadratico no numero de nos, e o pico de
tamanho do documento e pago repetidamente ate o limite de 16MB do MongoDB.

A alternativa e `$push`, via `MongoOperations.updateFirst`, que acrescenta um elemento ao array sem
tocar no resto. Ela e barata e atomica, e traz dois problemas que nao dao para ignorar:

1. **Passa por fora do `@Version`.** Um `$push` nao incrementa a versao nem detecta escrita
   concorrente. Para o array de passos isso e aceitavel -- `$push` e atomico no servidor e dois
   passos concorrentes nao se sobrescrevem --, mas deixa de valer como controle de concorrencia
   para o resto do documento.
2. **Passa por fora do `BeforeConvertCallback`.** Este e o problema real. O callback e a costura que
   garante que nenhuma escrita escapa da validacao estrita do `MapSanitizer`: e ele que impede uma
   chave `$where` de ser gravada dentro de `steps[].output`. Um `$push` cru grava o que quiserem,
   sem validacao nenhuma, e o argumento inteiro de "existe um ponto por onde toda escrita passa"
   deixa de ser verdade em silencio.

Vale registrar que o `output` de um passo e conteudo **de terceiro**: e a resposta do servico que o
no HTTP chamou. Nao e entrada do usuario deste sistema, e nao e menos hostil por isso -- e a fonte
menos confiavel de todas as que chegam a gravacao.

## Decisao

A engine **nao** chama `save` por passo, e tambem **nao** faz `$push` cru. O acrescimo de passo
ganha um metodo proprio na porta:

```java
void appendStep(String executionId, ExecutionStep step);
```

O adaptador o implementa com `$push`, e **valida o passo explicitamente antes de empurrar**,
aplicando `MapSanitizer.validate(step.output(), "steps[].output")` e o teto de `MAX_STEPS`. A
validacao que o callback fazia continua acontecendo; o que muda e que ela passa a ser
responsabilidade declarada do metodo, em vez de efeito colateral do caminho de `save`.

O `status` da execucao continua indo por `save`, com `@Version`: sao poucas transicoes por
execucao, elas competem de verdade (a engine e um eventual cancelamento), e ali o controle de
concorrencia importa.

## Consequencias

- O custo de gravacao passa de quadratico para linear no numero de nos, e o documento deixa de ser
  reescrito inteiro a cada passo.
- **A costura deixa de ser unica, e isso e uma perda real.** Hoje a resposta para "onde toda escrita
  e validada?" e um lugar so. Depois desta decisao sao dois, e o segundo depende de o autor do
  `appendStep` ter lembrado. A mitigacao e um teste que grava um `output` com chave de operador
  atraves de `appendStep` e exige a recusa -- sem ele, esta ADR e so uma intencao.
- `appendStep` nao devolve a execucao atualizada. Quem precisar do estado depois de acrescentar
  tem que reler, e isso e proposital: devolver o documento reescrito traria de volta o custo que a
  decisao existe para evitar.
- O teto de `MAX_STEPS` deixa de ser garantido pelo dominio no momento da montagem e passa a
  depender de uma verificacao no adaptador. E um lugar pior para uma invariante de dominio, e e o
  preco de nao carregar o agregado inteiro so para contar elementos.

## Alternativas descartadas

- **`save` por passo, aceitando o custo.** Defensavel enquanto os workflows sao pequenos, e a
  decisao mais simples. Descartada porque o custo e quadratico e o retrofit e exatamente a
  reescrita do laco de persistencia da engine -- o trabalho que esta ADR existe para nao pagar
  duas vezes.
- **Gravar os passos numa colecao separada.** Resolve o crescimento do documento de vez e e o que
  se faria com volume alto. Descartada por ora: exige uma segunda colecao, um segundo indice e uma
  juncao na leitura, para um limite de 200 passos que ja e baixo. Fica registrado como o caminho se
  `MAX_STEPS` subir.
- **`$push` cru, sem metodo de porta.** E a versao que traz o ganho sem pagar por ele: rapida de
  escrever e silenciosa quando alguem grava `output` nao validado.

## Correcoes feitas na implementacao (2026-08-07)

Quatro afirmacoes desta ADR nao sobreviveram ao contato com o codigo. Ficam corrigidas aqui, e nao
reescritas acima, para que o raciocinio original continue legivel junto do que ele errou.

**1. `$push` nao passa por fora do `@Version` -- ele incrementa a versao.** Esta era a premissa que
sustentava o item 1 do contexto, e esta errada. O `updateFirst` do Spring Data adiciona um `$inc` na
propriedade de versao de toda entidade versionada. A consequencia so aparece atravessando camadas, e
e grave: depois de N passos o documento esta na versao N enquanto o agregado que a engine tem em
memoria continua na versao com que nasceu, e a gravacao final do estado terminal -- que passa pelo
bloqueio otimista -- e recusada. **Nenhuma execucao com um no sequer terminava.** A engine passou a
reler a execucao antes da gravacao terminal.

Vale registrar como o defeito escapou: os testes de unidade da engine usam porta falsa, e porta
falsa nao incrementa versao. A suite inteira passava. E o mesmo buraco que a revisao de backend ja
tinha apontado -- cada camada testada com a de baixo mockada nao pega o defeito que mora entre elas
-- e a correcao veio junto com um `WorkflowEngineIntegrationTest` contra o Mongo real.

**2. O ganho e de N gravacoes de documento inteiro, e nao de todas.** O texto acima sugere que o
`appendStep` tira a reescrita do documento do caminho da execucao. Tira N delas; sobra uma, no fim,
porque o estado terminal vai por `save` com `@Version` -- que e o que esta ADR queria. O custo deixa
de ser quadratico e passa a ser linear mais uma reescrita, nao linear puro.

**3. "Quem precisar do estado depois de acrescentar tem que reler" e verdade, mas insuficiente.**
A engine tambem espelha cada passo aceito em memoria durante a caminhada, para contar passos sem ir
ao banco a cada no. So entra no espelho o passo que a gravacao aceitou: se um passo recusado
entrasse, a gravacao final do agregado seria recusada pelo mesmo motivo e a execucao ficaria presa
em RUNNING.

**4. O teto de passos nao virou "uma verificacao no adaptador".** Ele esta no *filtro* da
atualizacao -- `steps.<MAX_STEPS - 1>` nao pode existir --, avaliado pelo servidor na mesma operacao
atomica. Uma contagem previa reabriria exatamente a janela de concorrencia que o `$push` fecha. O
efeito colateral e que um filtro que nao casa e ambiguo, e o caminho de erro paga uma consulta extra
para distinguir "atingiu o teto" de "execucao nao existe".
