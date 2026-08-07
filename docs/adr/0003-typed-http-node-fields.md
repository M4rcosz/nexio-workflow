# ADR 0003 -- Promover `url`, `method`, `headers` e `body` a campos tipados do no

## Status

Aceito -- 2026-08-07. A implementar como **primeira** tarefa do Sprint 3, antes das issues #21-#23.

## Contexto

Hoje o no HTTP guarda tudo em `config`, um `JSON!` sem forma declarada:

```json
{ "url": "https://api.exemplo.com/v1/cobrancas?api_key=sk_live_...",
  "method": "POST", "headers": {}, "body": {} }
```

Duas revisoes independentes chegaram ao mesmo ponto por caminhos diferentes.

**A revisao de seguranca**, olhando o `SecretRedactor`: a redacao e baseada em nome de chave, e
`url` nao e nome de chave sensivel, entao o valor volta inteiro para qualquer leitor. A correcao
imediata -- varrer tambem o valor da string, retirando userinfo e parametros de consulta sensiveis
-- foi aplicada, mas ela e uma heuristica sobre texto livre. Ela existe porque o campo nao tem
tipo.

**O mesmo argumento ja esta escrito no proprio schema**, sobre as arestas do grafo:

> As arestas do grafo sao campos de primeira classe, e nunca chaves dentro de `config`: a validacao
> do grafo so enxerga o que esta declarado aqui, entao uma rota escondida no blob nao tipado
> passaria por um grafo "validado" carregando um ciclo.

Troque "validacao do grafo" por "validacao de destino" e "ciclo" por "endereco interno" e a frase
descreve `url` sem alterar mais nada. O `HttpTargetValidator` existe, esta correto e recusa
loopback, link-local, site-local e CGNAT -- e nao tem como ser chamado pela validacao de escrita,
porque o campo que ele precisa enxergar nao existe no modelo. Ele so vai ser chamado no Sprint 3,
em tempo de execucao, quando o autor do workflow ja foi embora.

O terceiro custo e de contrato: `config: JSON!` nao diz a nenhum cliente quais chaves existem. A
diferenca entre `method` e `httpMethod` so aparece quando o no falha rodando.

## Decisao

`WorkflowNode` passa a declarar os campos que o dominio precisa enxergar:

```graphql
type WorkflowNode {
  id: String!
  type: NodeType!
  url: String
  method: HttpMethod
  headers: JSON
  body: JSON
  nextOnSuccess: String
  nextOnTrue: String
  nextOnFalse: String
}
```

Consequencias diretas:

1. `HttpTargetValidator` roda na **escrita**. Um workflow apontando para `169.254.169.254` e
   recusado no `createWorkflow`, e nao descoberto no primeiro disparo.
2. A redacao de `url` deixa de ser heuristica sobre texto livre e passa a ser tratamento de um
   campo conhecido. A varredura de valor continua, porque `headers` e `body` seguem sendo mapas
   livres e tambem carregam URL.
3. `method` vira enum: um verbo invalido e erro de validacao do GraphQL, antes de qualquer codigo
   do projeto rodar.

## Consequencias

- **E quebra de contrato do schema.** E por isso que ela e a primeira tarefa do Sprint 3: hoje nao
  existe nenhum cliente, e cada sprint que passa torna a mudanca mais cara. Feita depois do
  `ExecutionResolver`, exigiria migracao de documentos ja gravados.
- `config` **nao** desaparece. Continua existindo para o que e genuinamente especifico do tipo de
  no e nao merece campo -- e continua sujeito ao `MapSanitizer`. O criterio e: se o dominio precisa
  ler o campo para validar ou proteger alguma coisa, ele e tipado; se e so carga opaca repassada
  adiante, fica em `config`.
- Nos `CONDITION` nao usam nenhum dos campos novos, o que torna o tipo mais largo do que qualquer
  no individual. A alternativa -- `HttpRequestNode` e `ConditionNode` como uma uniao GraphQL -- e
  mais correta e bem mais cara, em schema, em mapeamento e na persistencia polimorfica. Fica
  registrada como o proximo passo se surgir um terceiro tipo de no.
- A validacao de escrita passa a fazer resolucao de DNS (o `HttpTargetValidator` resolve o host).
  Isso torna `createWorkflow` dependente de rede e mais lento, e um host inexistente vira erro de
  validacao. Aceito: a alternativa e descobrir o destino invalido em producao.
- O DNS rebinding continua em aberto. Validar na escrita nao fecha o problema -- o endereco pode
  mudar entre a escrita e o disparo -- e por isso a validacao em tempo de execucao **permanece**,
  fixando a conexao no IP ja validado. A validacao na escrita e defesa em profundidade, nao
  substituicao.
