# ADR 0007 -- Cliente HTTP de saida e fechamento do DNS rebinding

## Status

Aceito -- 2026-08-08. Implementado na issue #23 (`HttpRequestNodeExecutor`).

## Contexto

O `HttpTargetValidator` resolve o host do no e recusa todo endereco interno. Isso e uma checagem de
**pre-voo**, e sozinha ela nao fecha o problema, porque o cliente HTTP resolve o nome **de novo** ao
abrir a conexao. Sao duas consultas de DNS, e quem controla o servidor autoritativo do dominio
controla as duas: publica o registro com TTL zero, devolve um endereco publico na primeira e
`169.254.169.254` na segunda. A validacao passa e a conexao vai para os metadados da nuvem.

Isso e DNS rebinding. Nao e cenario teorico -- e a forma padrao de derrotar validacao de URL feita
antes da conexao, e a propria ADR 0003 e o Javadoc do validador ja registravam a janela como
limitacao conhecida, adiada para a issue do executor. Esta e a issue.

Fechar a janela exige **resolver uma vez so**: validar os enderecos e conectar exatamente neles. O
ponto onde isso pode ser feito e o ponto onde o socket e aberto, ou seja, dentro do cliente HTTP.

E ai esta o problema: **o `java.net.http.HttpClient` do JDK nao expoe ponto de extensao para
resolucao de nome.** Nao ha `DnsResolver`, nao ha resolvedor por cliente, e `HttpClient.Builder`
so tem `localAddress` (endereco de origem, nao de destino). Confirmado no JDK 25 lendo a API.

## Decisao

**Trocar o motor do cliente de saida para o Apache HttpClient 5**, que tem um `DnsResolver` por
cliente -- o ponto de extensao feito exatamente para isto --, e instalar nele o `PinnedDnsResolver`.

O fluxo do executor passa a ser **validar, fixar, chamar**, com a limpeza da fixacao em `finally`.
A `HttpTargetValidator.validateAndResolve` devolve os enderecos aprovados em vez de descarta-los;
`validate(String)` continua existindo para quem so precisa da URI, e o executor nao a usa.

A API que o resto do codigo enxerga continua sendo o `RestClient` do Spring. O que mudou foi a
fabrica de requisicao por baixo.

## Consequencias

- **O TLS continua valendo.** O que se fixa e o endereco, nao o nome: a requisicao continua sendo
  feita para o host original, entao SNI e verificacao de certificado usam o nome que o autor
  escreveu. Um atacante que redirecione o endereco nao ganha um certificado valido para aquele nome.
  E a diferenca entre esta solucao e a alternativa ingenua de reescrever a URL para o IP.
- **A fixacao e por thread e obrigatoriamente limpa.** A execucao e sincrona (ADR 0005), entao
  validacao, fixacao e requisicao acontecem na mesma thread. Thread de servidor e reaproveitada: uma
  fixacao esquecida faria a proxima execucao daquela thread conectar no endereco validado para
  *outro* workflow. O dia em que o disparo virar assincrono, isto tem que ser revisto junto.
- **Uma dependencia nova**, e o endurecimento do cliente teve de ser transplantado: sem
  redirecionamento, sem cookies, sem cache de autenticacao, sem proxy de ambiente, os dois tempos
  limite e o interceptor de teto de corpo. Cada um existe por um achado de revisao, e cada um foi
  reconferido depois da troca.
- **Perdeu-se a inspecao das propriedades do cliente.** O `CloseableHttpClient` nao expoe a propria
  configuracao, e os testes que liam `httpClient.followRedirects()` deixaram de ser possiveis. Isso
  acabou sendo bom: as afirmacoes viraram testes de comportamento contra um servidor de verdade, e
  `followRedirects(NEVER)` nunca provou que um `302` nao e seguido -- provava que a opcao estava
  marcada.

## Alternativas descartadas

- **`InetAddressResolverProvider` do JDK com fixacao por thread.** Funciona e nao traz dependencia,
  mas o SPI substitui a resolucao de nome do **JVM inteiro**, e e carregado uma vez na inicializacao
  sem poder ser trocado depois. Um defeito ali nao quebra o cliente de saida: quebra a conexao com o
  MongoDB e tudo mais que resolva nome. Superficie desproporcional ao problema.
- **Conectar no IP literal e mandar o host no cabecalho `Host`.** Quebra a verificacao de
  certificado, que passaria a ser feita contra o IP. Consertar isso significa desligar a verificacao
  de nome -- trocar um furo por um pior.
- **Aceitar a janela e documenta-la.** Era o estado ate aqui, e o custo ficou visivel: o projeto ja
  investiu duas vezes em SSRF (a validacao original e a correcao do bypass por IP decimal), e deixar
  a rota mais conhecida de contorno aberta tornaria os dois investimentos parcialmente inuteis.
- **Seguir redirecionamento revalidando o destino a cada salto.** Nao foi feito, e continua nao
  sendo: o `3xx` volta como resposta normal, com o `location` gravado no passo. Fazer o executor
  seguir saltos exige revalidar e refixar a cada um, e nenhum caso de uso pediu isso ainda.
