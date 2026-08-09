# ADR 0005 -- Execucao sincrona, com teto de tempo

## Status

Aceito -- 2026-08-07. Vale a partir da issue #21 (`WorkflowEngine`), ainda nao implementada.

## Contexto

Disparar um workflow pode ser feito de duas formas, e a escolha decide o formato da engine, do
resolver de disparo e dos testes de todos os dois.

**Assincrona** e o modelo realista de um motor de workflow: `triggerWorkflow` grava a execucao como
`PENDING`, devolve na hora e um `@Async` toca o grafo. E o que o agendador do Sprint 4 vai precisar
de qualquer jeito, porque ninguem esta esperando resposta de um disparo por cron.

A revisao de backend apontou a armadilha concreta dessa opcao neste codigo: excecao dentro de um
metodo `@Async` sem `AsyncUncaughtExceptionHandler` e engolida. Uma
`OptimisticLockingFailureException` no meio da execucao nao chega a lugar nenhum, e a execucao fica
`RUNNING` para sempre -- estado que nada limpa, que nenhuma consulta distingue de "ainda rodando" e
que so aparece quando alguem for procurar. Ou seja, assincrono aqui nao e "mesma coisa, sem
esperar": exige tambem um handler e um varredor de execucoes travadas, com criterio de idade, antes
de ser seguro.

**Sincrona** faz o disparo executar o grafo e devolver a execucao ja terminada. O erro chega a quem
chamou, o teste e uma chamada e uma asercao, e nao existe estado orfa porque nao existe janela em
que ninguem e dono da execucao.

O custo da sincrona e real: um no HTTP lento segura uma thread de request. O `RestClient` ja tem
timeout de conexao (5s) e de requisicao (10s), mas isso e **por no** -- um grafo de 50 nos pode
somar 500 segundos dentro de um request.

## Decisao

O disparo e **sincrono** no Sprint 3, com um **teto de tempo total da execucao** aplicado pela
engine, separado e menor que a soma dos timeouts por no. Estourado o teto, a execucao e marcada
`FAILED` com mensagem propria e a engine para de avancar no grafo.

O teto total existe porque os timeouts por no nao limitam nada em conjunto: sao a garantia de que
uma chamada termina, nao de que a execucao termina.

## Consequencias

- `triggerWorkflow` devolve a execucao terminada, entao o cliente ve `SUCCESS`/`FAILED` e os passos
  na propria resposta, sem consultar de novo. O `ExecutionResolver` (#26) fica mais simples: nao
  precisa de assinatura nem de polling para ser util.
- Nao existe execucao presa em `RUNNING` por excecao perdida, porque nao ha fronteira assincrona
  onde perde-la. `RUNNING` so aparece enquanto a thread esta de fato trabalhando.
- **Nao ha varredor de execucoes travadas, e no dia em que o disparo virar assincrono ele passa a
  ser obrigatorio.** Isto e o item a nao esquecer: a ausencia dele hoje e consequencia de ser
  sincrono, e nao uma decisao independente.
- Uma requisicao de disparo pode demorar ate o teto total. Aceito enquanto o unico gatilho e
  `MOCK_EVENT`, que existe para exercitar o motor.
- O agendador do Sprint 4 **nao** pode chamar o disparo sincrono direto de dentro do laco de
  agendamento: um workflow lento atrasaria todos os outros. Quando ele chegar, ou ele proprio roda
  cada disparo num executor proprio, ou esta ADR e revisitada -- que e o momento certo, porque e
  quando o varredor tambem passa a ser necessario.

## Alternativas descartadas

- **`@Async` agora.** Mais fiel ao destino final, e paga hoje por um varredor, um handler de excecao
  e testes de concorrencia, para um motor que ainda nao existe. Prematuro: o modelo sincrono e o que
  torna a engine testavel enquanto ela esta sendo escrita.
- **Fila (SQS/Rabbit/outbox).** A resposta certa para volume e para nao perder disparo em queda de
  processo. Fora de escala para o projeto atual, e nada do que esta sendo decidido aqui impede
  adota-la depois: a fronteira e o caso de uso de disparo.

## Correcao feita na implementacao (2026-08-07)

**O teto de tempo nao limita a execucao: ele limita o inicio do proximo no.** A ADR fala em "teto de
tempo total da execucao", o que sugere uma parede. Nao e o que a engine faz, nem o que ela pode
fazer: nao ha como interromper um no que ja esta rodando sem matar a thread, e o unico limite de uma
chamada em curso e o tempo limite por no do cliente HTTP.

O que a engine verifica e o relogio **antes de comecar cada no**. Estourado o teto, ela para de
avancar e marca `FAILED`. O pior caso real e, portanto, `teto + duracao de um no` -- hoje 30s + 10s.

Isso esta escrito no Javadoc da engine e fixado por teste
(`theCapNeverInterruptsANodeAlreadyRunning`), em vez de deixar o numero do `application.yml` passar
a impressao de um limite que ele nao entrega. Quem precisar de parede de verdade tem que executar o
no em outra thread e abandona-la no timeout, o que traz de volta a fronteira assincrona que esta ADR
escolheu nao ter.

## Correcao adicional (2026-08-08)

**O pior caso de uma requisicao nao e `teto + um no`.** O texto acima e o Javadoc do
`HttpClientConfig` tratavam `REQUEST_TIMEOUT` como "tempo limite total da requisicao". Nao e: o
`responseTimeout` do httpclient5 vira o tempo limite de leitura do socket, ou seja, limita a
**inatividade entre leituras**, nao a duracao da resposta. Um servidor que envia um byte a cada 9
segundos nunca o dispara, e o teto de tempo da engine nao ajuda porque e conferido entre nos.

Com isso, o tempo que uma unica requisicao anonima pode segurar uma thread e **ilimitado**. Fica
declarado aqui em vez de silenciosamente errado: anunciar um limite que nao existe e pior do que
declarar a ausencia dele -- e exatamente o erro que o item 4 da ADR 0002 cometeu.

A correcao e um prazo de parede por no, aplicado na contagem de bytes da leitura (que ja roda a cada
leitura). Nao foi feita nesta rodada.
