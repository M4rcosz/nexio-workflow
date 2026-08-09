# ADR 0008 -- Autenticacao com os tokens do nexio-core, assinados por chave assimetrica

## Status

Aceito -- 2026-08-09. Ainda nao implementado. **Parte desta decisao e uma mudanca no repositorio
`nexio-core`**, e ela precisa ir primeiro.

## Contexto

O nexio-workflow nao tem autenticacao nenhuma. Quem alcanca `/graphql` cria, le, altera e apaga
qualquer workflow, e dispara qualquer um deles. Isso esta registrado em todo lugar onde importa --
`SecretRedactor`, `GetExecutionUseCase`, o guia -- e e o item aberto mais grave do projeto.

O `nexio-core` ja resolve identidade para o produto: `POST /auth/login` devolve
`{ access_token, refresh_token }`, o acesso vale 15 minutos, o refresh 7 dias, e o token carrega
`sub`, `username`, `role` (`ADMIN`, `CUSTOMER`, `KITCHEN`, `MANAGER`, `ATTENDANT`) e
`businessUnitIds[]`. Nao faz sentido este servico ter um segundo cadastro de usuarios: seria uma
segunda fonte de verdade sobre quem e quem, com duas sessoes para o frontend administrar.

O ponto que exigiu decisao e **como** o nexio-workflow confia nesse token. Hoje o nexio-core assina
com `secret: JWT_SECRET_KEY`, ou seja, HS256 -- chave simetrica. Verificar um token HS256 exige a
mesma chave que o assina, e **quem pode verificar pode emitir**.

Isso e desconfortavel em qualquer arquitetura de varios servicos e e especificamente ruim aqui. O
nexio-workflow existe para fazer requisicoes HTTP de saida para URLs que o usuario escolhe: e o
servico com a maior superficie de ataque do conjunto, e o unico que fala com a internet aberta sob
instrucao de terceiros. Dar a ele a chave de assinatura significa que comprometer o executor de
workflows passa a valer um token `role: ADMIN` do nexio-core -- ou seja, a API de gestao inteira. O
elo mais exposto passaria a guardar a chave do elo mais valioso.

## Decisao

**1. O nexio-core passa a assinar com chave assimetrica (RS256).** Ele guarda a chave privada e
assina; o nexio-workflow recebe apenas a chave publica e so consegue verificar. Comprometer o
nexio-workflow deixa de permitir a emissao de token nenhum.

**2. O nexio-workflow valida o `Authorization: Bearer` no `/graphql`** e traduz as claims em
`ActorId`. Nao ha login, nao ha cadastro de usuario e nao ha refresh aqui: renovar token e assunto do
nexio-core, e o frontend fala com ele diretamente para isso.

**3. A primeira regra de autorizacao e por dono.** `WorkflowDefinition` ganha `ownerId`, preenchido a
partir do `sub` na criacao, e as consultas de listagem e leitura passam a ser filtradas por ele. E a
menor regra que fecha o buraco de "todo mundo autenticado ve tudo".

## Consequencias

- **A ordem importa e nao e negociavel.** A troca para RS256 acontece no nexio-core antes de o
  nexio-workflow validar qualquer coisa. Enquanto os tokens forem HS256, a unica forma de valida-los
  seria com o segredo compartilhado, que e exatamente o que esta decisao recusa.

- **A janela de rotacao e curta e conhecida.** Trocar o algoritmo invalida os tokens em circulacao.
  Como o acesso vale 15 minutos e o refresh 7 dias, aceitar as duas assinaturas durante a transicao
  -- verificar RS256 e, por um periodo, tambem HS256 -- evita deslogar todo mundo. O prazo dessa
  tolerancia e o TTL do refresh, e ela precisa sair depois: um verificador que aceita HS256 e um
  verificador que ainda aceita a chave simetrica.

- **A chave publica precisa chegar ao nexio-workflow.** Duas formas, e a escolha pode ficar para a
  implementacao: o nexio-core publica um endpoint JWKS e o nexio-workflow o consulta com cache
  (padrao de mercado, rotacao sem redeploy), ou a chave publica entra como variavel de ambiente
  (mais simples, exige redeploy para rotacionar). Chave publica nao e segredo -- distribui-la nao
  cria risco novo, e essa e justamente a vantagem sobre o modelo atual.

- **O `CurrentActorPort` era exatamente para isto.** A ADR 0006 introduziu o ator como seam quando
  ainda nao havia o que ligar nele; a implementacao passa a ler o `sub` do token em vez de devolver
  `ANONYMOUS`. Nenhum caso de uso muda de assinatura, nenhum resolver muda: o ator ja atravessa a
  fronteira inteira, e nenhum ponto da API consegue le-lo de outro lugar. E a diferenca entre uma
  troca de um arquivo e uma refatoracao de toda a cadeia.

- **`ownerId` custa campo, migracao e indice.** Os documentos existentes nao tem dono. A decisao de
  o que fazer com eles -- atribuir a um administrador, deixar visiveis so para `ADMIN`, ou apagar em
  ambiente de desenvolvimento -- fica para a implementacao, mas precisa ser tomada explicitamente:
  um filtro por `ownerId` sobre documentos sem `ownerId` os esconde de todo mundo em silencio.

- **O `MockTriggerController` perde a razao de ser `@Profile("dev")`.** A restricao existe porque o
  endpoint nao tem autenticacao. Com autenticacao, a decisao passa a ser sobre o produto e nao sobre
  seguranca, e a revisao ja apontou que o guard e hoje meio decorativo: o `triggerWorkflow` do
  GraphQL alcanca o mesmo caso de uso sem restricao nenhuma.

- **Isto nao entrega escopo por unidade de negocio, de proposito.** Ver alternativas.

## Alternativas descartadas

- **Compartilhar o `JWT_SECRET_KEY` (HS256).** E a opcao mais rapida e nao muda nada no nexio-core.
  Foi recusada pelo motivo do contexto: verificar implica emitir, e o servico que receberia a chave e
  o que faz requisicoes para URLs escolhidas por usuarios. O raio de alcance ainda cresce a cada
  servico novo que precisar validar token -- cada um vira mais um lugar de onde a chave de assinatura
  do produto inteiro pode vazar.

- **Introspeccao: perguntar ao nexio-core a cada requisicao.** Nenhuma chave sai do core e a
  revogacao passa a ser imediata, o que e uma vantagem real. Recusada pelo acoplamento: uma ida a
  rede por requisicao, ou um cache que devolve o mesmo problema de validade que o JWT ja resolve --
  e, pior, o nexio-core fora do ar passaria a significar nexio-workflow recusando tudo. Vale
  reconsiderar se um dia a revogacao imediata virar requisito.

- **Autenticacao propria neste servico.** Segundo cadastro de usuarios, segunda sessao para o
  frontend, duas fontes de verdade sobre quem e quem. Nao ha caso de uso que peca isso.

- **Escopo por `businessUnitIds` como primeira regra.** E provavelmente o destino certo, e vale
  registrar por que nao e o primeiro passo -- e tambem uma correcao de leitura sobre a
  `docs/adr/0001-no-multi-tenancy.md`. Aquela ADR removeu o `tenantId` porque ele chegava como
  argumento do cliente: trocar o argumento trocava o suposto isolamento, ou seja, era uma primitiva
  de enumeracao com nome de identidade. Uma claim `businessUnitIds` **verificada** nao tem esse
  defeito -- o cliente nao a escolhe --, entao o argumento da ADR 0001 deixa de se aplicar em vez de
  ter sido errado. O escopo por unidade volta a ser possivel e provavelmente e o modelo certo, junto
  com uma regra explicita para `ADMIN` e para o usuario com lista vazia. Fica para depois porque
  `ownerId` fecha o buraco imediato com uma fracao da mudanca, e as duas coisas nao se excluem.
