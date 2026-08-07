# ADR 0001 -- Sem multi-tenancy: remocao do `tenantId`

## Status

Aceito -- 2026-08-06.

## Contexto

O rascunho inicial das portas de saida carregava um parametro `tenantId` em varias operacoes, e o
mesmo argumento aparecia no schema GraphQL, informado pelo cliente na consulta.

Tres fatos, levantados na revisao do modelo de dominio:

1. Nenhuma entidade tinha o campo. Nem `WorkflowDefinition` nem `WorkflowExecution` guardavam
   `tenantId`, entao nao havia por onde filtrar: o parametro atravessava as assinaturas sem chegar
   a nenhuma consulta.
2. A issue #13, que especifica as portas, define assinaturas sem tenant.
3. O valor chegava como argumento GraphQL fornecido pelo cliente. Sem autenticacao que o vincule a
   um usuario, um identificador de tenant enviado na requisicao nao isola nada -- e uma primitiva
   de enumeracao de tenants: basta variar o argumento para varrer os dados dos outros.

Um isolamento aparente e pior que a ausencia declarada dele: passa a impressao de que existe uma
fronteira onde nao ha nenhuma.

## Decisao

O sistema **nao** e multi-tenant. O `tenantId` foi removido das portas, do schema e de toda
assinatura. Nao ha nenhuma nocao de tenant no dominio, na persistencia ou na API.

## Consequencias

- As operacoes de massa das portas -- `findAll`, `findEnabled` e `deleteByWorkflowId` -- sao, por
  construcao, de escopo global: elas enxergam a colecao inteira. Isso e a decisao funcionando como
  esperado, nao um descuido.
- Nao existe rede de seguranca na camada de dados. Quando a autorizacao chegar, ela tera que ser
  imposta **inteiramente na camada de casos de uso**, porque nenhuma consulta abaixo dela restringe
  o conjunto de documentos alcancado. Um caso de uso que esqueca a verificacao devolve tudo, e nada
  no adaptador ou no MongoDB o impede.
- Os indices declarados hoje (`def_enabled_trigger`, `def_trigger_type`, `exec_workflow_created`,
  `exec_status_created`) assumem consulta global.

## O que seria preciso para reintroduzir

1. `tenantId` como campo de ambos os agregados, obrigatorio na escrita.
2. `tenantId` como **primeira chave de todo indice composto**, porque toda consulta passaria a
   filtrar por ele antes de qualquer outro criterio; os indices atuais teriam que ser recriados.
3. Metodos de porta com escopo de tenant, substituindo as operacoes globais, para que o filtro nao
   dependa de o caso de uso lembrar de aplica-lo.
4. Origem confiavel do `tenantId`: derivado do principal autenticado, nunca de argumento enviado
   pelo cliente.
