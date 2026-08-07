# ADR 0006 -- Propagacao do ator: a costura, antes da autorizacao

## Status

Aceito -- 2026-08-07. Vale a partir de agora, antes da issue #21 (`WorkflowEngine`).

## Contexto

O servico nao autentica ninguem e nao autoriza nada. Quem alcanca o endpoint GraphQL cria, le,
altera e apaga qualquer definicao, e isso e um fato declarado -- nao um descuido -- desde a
`0001-no-multi-tenancy.md`. A autenticacao e a autorizacao sao Sprint 4.

O que se decide aqui e menor e vem antes: **por onde o identificador de quem pede vai trafegar
quando ele existir**. Duas coisas empurram essa decisao para agora.

A primeira e o custo, que so cresce. Acrescentar um parametro aos cinco casos de uso de hoje toca
cinco assinaturas, cinco testes e um resolver. O Sprint 3 traz o `TriggerWorkflowUseCase`, o
`GetExecutionUseCase` e o `ListExecutionsUseCase`, e a mesma mudanca passa a tocar oito. A costura
nao fica melhor esperando; fica mais cara.

A segunda e o desenho da origem do valor, que e onde o projeto ja errou uma vez. O rascunho inicial
levava `tenantId` nas portas, informado pelo cliente como argumento GraphQL. A ADR 0001 o removeu
por tres motivos, e o terceiro e o que importa aqui: **o valor chegava do proprio cliente**, entao
nao isolava nada -- era uma primitiva de enumeracao, bastando variar o argumento para varrer os
dados alheios. A conclusao daquela ADR e que isolamento aparente e pior que a ausencia declarada
dele.

Um `actorId` mal desenhado repete esse erro inteiro. Se o ator vier de um campo de input, de um
argumento ou de um cabecalho lido no resolver, ele nao e identidade: e um seletor, escolhido por
quem esta sendo identificado, com nome de identidade. No dia em que a autorizacao passasse a
filtrar por ele, filtraria pelo valor que o atacante escolheu.

## Decisao

Fica criada a costura de propagacao do ator, e **so** a costura:

1. `ActorId` -- record da camada de aplicacao, ao lado de `PageQuery`, que embrulha um texto nao
   vazio de ate 128 caracteres e expoe a constante `ActorId.ANONYMOUS`. Nao e conceito de dominio:
   nenhum agregado tem dono.
2. `CurrentActorPort` -- porta de saida, com um unico metodo `currentActor()`. E porta de saida
   porque o criterio e quem chama quem: a aplicacao pergunta, a infraestrutura responde, igual a
   persistencia. Portas de entrada seriam as que o mundo externo aciona para pedir um caso de uso, e
   este projeto nao as tem: os casos de uso sao classes concretas chamadas direto pelo resolver.
3. `AnonymousActorProvider` -- implementacao na infraestrutura que devolve `ActorId.ANONYMOUS`,
   sempre. **E o unico arquivo que muda quando o Spring Security chegar.**
4. Os cinco casos de uso recebem `ActorId` como **primeiro parametro explicito**. Nao um detentor
   injetado (`SecurityContextHolder`, `ThreadLocal`, bean de escopo de requisicao): parametro. Um
   parametro e testavel sem montar contexto de seguranca nenhum e deixa a dependencia visivel na
   assinatura -- quem olha o metodo ve que ele conhece o ator.
5. O `WorkflowResolver` obtem o ator **da porta**, por injecao de construtor, num metodo privado
   sem parametros. Nenhum `@Argument`, campo de input ou cabecalho participa disso, e o schema nao
   tem campo por onde tentar.

**Nada disso autoriza coisa alguma.** Hoje o parametro atravessa os cinco casos de uso sem ser
lido por nenhum deles. Nenhuma consulta o usa, nenhum documento o guarda, nenhuma decisao depende
dele.

Nao ha `ownerId` no agregado e nao ha metodo de porta com escopo de dono. Isso e enforcement: exige
migracao de dado e indice composto novo, e so faz sentido junto com a autenticacao que da valor ao
campo. Ver a lista abaixo.

## Consequencias

- A diferenca para o `tenantId` da ADR 0001 e uma so, e e a decisiva: **a origem do valor**. Aquele
  vinha do cliente e, por isso, sua promessa de isolamento era falsa por construcao -- nenhuma
  implementacao posterior o consertaria sem trocar a origem. Este vem de um bean da infraestrutura,
  e nao existe caminho na camada de API que o faca variar. A forma se parece (um identificador
  atravessando assinaturas); a propriedade que importa e oposta. As demais criticas da ADR 0001
  tambem nao se aplicam: `ActorId` nao entra em nenhum indice hoje, porque nao entra em nenhuma
  consulta.
- **Um parametro que nenhuma implementacao le e um custo real, e nao adianta disfarcar.** Para quem
  passa os olhos, ele parece seguranca onde nao ha nenhuma -- exatamente o "isolamento aparente" que
  a ADR 0001 condena --, e alguem pode ler `execute(actor, id)` e concluir que a operacao e
  restrita. E o motivo de esta ADR existir e de o Javadoc do `AnonymousActorProvider` dizer, na
  primeira linha, que nao ha autenticacao e que todo mundo e o mesmo ator. O que se compra com esse
  custo: a mudanca de assinatura acontece uma vez, com cinco casos de uso e nao com oito ou doze; e
  a verificacao futura tera **um** ponto de estrangulamento por caso de uso, ja recebendo o valor,
  em vez de nascer espalhada entre resolver, caso de uso e adaptador.
- O `ActorId` valida o proprio valor no construtor compacto. Enquanto o valor e constante isso e
  quase decorativo; quando ele passar a vir de um token, e a garantia de que nao circula
  identificador vazio nem de tamanho arbitrario -- que sao as duas formas de estragar linha de log
  hoje e chave de consulta depois.
- O `MockTriggerController` continua fora desta costura: ele nao chama caso de uso nenhum e segue
  restrito ao perfil `dev`.

## O que o Sprint 4 ainda tem que fazer

Esta ADR nao entrega autorizacao. Falta, e falta inteiro:

1. **`ownerId` no `WorkflowDefinition`**, obrigatorio na escrita, preenchido a partir do ator. Junto
   dele, migracao dos documentos existentes (que nao tem o campo) e um indice composto com `ownerId`
   como **primeira chave**, porque toda consulta passara a filtrar por ele antes de qualquer outro
   criterio -- os indices atuais assumem consulta global e terao que ser recriados.
2. **Metodos de porta com escopo de dono, com o filtro dentro da consulta.** Nao adianta manter as
   consultas globais e verificar depois:
   - `findById(id)` seguido de "e do ator?" vaza existencia. O tempo de resposta e o tipo de erro
     distinguem "nao existe" de "existe e nao e seu", e isso e um oraculo de enumeracao de ids.
   - `findAll(page)` seguido de filtro em memoria devolve **pagina curta**: o banco corta 20
     documentos, o filtro descarta 18, e o cliente recebe 2 achando que acabou. Paginacao filtrada
     depois da paginacao esta simplesmente errada, nao apenas ineficiente.
3. **`deleteById` continua sendo uma unica operacao atomica**, com o dono dentro do predicado da
   consulta (`deleteByIdAndOwnerId`). A tentacao sera ler, conferir o dono e apagar -- que e o par
   verificar-e-agir que o `DeleteWorkflowUseCase` ja recusou uma vez, por ser uma corrida, e que aqui
   vira tambem uma janela de autorizacao: entre a leitura e a remocao o documento pode ter mudado de
   estado. O retorno booleano da operacao unica responde "existia e era seu" de uma vez so.
4. **`WorkflowNotFoundException` permanece a resposta para "nao e seu".** Um `FORBIDDEN` distinto de
   um `NOT_FOUND` confirma ao chamador que o recurso existe, que e metade do que ele queria
   descobrir. Os dois casos precisam ficar indistinguiveis -- mesma excecao, mesma mensagem, mesmo
   caminho de codigo.

## O que o Sprint 4 nao deve fazer

**O `SecretRedactor` nao pode virar condicional a perfil ou a papel.** A ideia aparece sozinha
("o dono pode ver o proprio segredo"), e o formato natural dela e uma consulta ao `SecurityContext`
dentro do construtor compacto do DTO de resposta. Isso e um desvio, nao um controle: o contexto de
seguranca e propagado por thread, e a resposta GraphQL nao e montada so na thread do request --
data fetchers assincronos, batch loaders e, no futuro, o agendador constroem o mesmo DTO onde o
contexto esta vazio. Nesse ponto o codigo escolhe um dos dois lados: se o vazio for tratado como
"nao autorizado", a redacao passa a depender de qual executor tocou o campo; se for tratado como
"autorizado", a credencial sai em texto claro por um caminho que ninguem testou. Uma regra de
seguranca que muda de resultado conforme a thread nao e uma regra.

Se houver necessidade legitima de recuperar o segredo original, ela e uma **operacao separada** --
autorizada por si, auditada por si e ausente do caminho de leitura comum --, e nao um ramo dentro da
construcao de um DTO.

## Alternativas descartadas

- **Nao fazer nada agora e resolver tudo no Sprint 4.** E a opcao honesta em relacao ao custo do
  parametro nao lido, e foi a que quase venceu. Perde pelo tamanho da mudanca no momento errado:
  ela chegaria junto com a autenticacao, a autorizacao, o `ownerId`, a migracao e o indice novo, no
  mesmo commit em que se mexe em oito ou mais assinaturas. Separar a costura mecanica -- que nao
  muda comportamento nenhum e cuja suite verde e prova disso -- da mudanca que muda comportamento
  deixa a segunda revisavel.
- **Detentor injetado (`SecurityContextHolder`, `ThreadLocal` ou bean de escopo de requisicao).**
  Nao mexeria em assinatura nenhuma, e e por isso que perde: a dependencia fica invisivel, o teste
  de caso de uso passa a exigir montagem de contexto, e o valor some exatamente onde a propagacao
  falha -- o mesmo defeito descrito acima para o `SecretRedactor`, aplicado a todo caso de uso.
- **`ownerId` no agregado agora.** Colocar o campo sem autenticacao gravaria `anonymous` em todos os
  documentos e criaria um indice composto que nao filtra nada. Pior: seria isolamento aparente, com
  a migracao ja paga e para refazer quando o valor passar a ter significado.
- **Ator como argumento GraphQL ou cabecalho lido no resolver.** E a ADR 0001 de novo, com outro
  nome. Recusado sem ressalvas -- e a linha que separa esta ADR de repetir aquele erro.
- **Interface funcional em vez de porta nomeada (`Supplier<ActorId>`).** Menos codigo, e apaga a
  intencao: um `Supplier` injetado nao diz de onde o valor vem nem que ele nao pode vir do cliente.
  A porta nomeada e o lugar onde essa restricao esta escrita.
