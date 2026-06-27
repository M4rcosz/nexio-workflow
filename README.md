# nexio-workflow

Nexio Workflow Engine is a backend service for designing and executing automation workflows.
It exposes a GraphQL API backed by MongoDB, built with Java 25 and Spring Boot.

## Stack

| Layer       | Technology                          |
|-------------|-------------------------------------|
| Language    | Java 25 (Temurin LTS)               |
| Framework   | Spring Boot 3.4.4                   |
| API         | spring-graphql (schema-first)       |
| Database    | MongoDB 8 (local via Docker Compose)|
| Integration | Testcontainers (MongoDB)            |
| Build       | Maven 3.9+                          |
| CI/CD       | GitHub Actions + Render             |
| Releases    | release-please (Google)             |

## Pre-requisitos

- Java 25 (Temurin) — `java -version`
- Maven 3.9+ — `mvn -version`
- Docker + Docker Compose — `docker compose version`

## Setup local

```bash
# 1. Clone o repositorio
git clone <repo-url>
cd nexio-workflow

# 2. Suba o MongoDB
docker compose up -d

# 3. Copie o arquivo de variaveis de ambiente
cp .env.example .env

# 4. Rode a aplicacao
mvn spring-boot:run

# 5. Acesse o GraphiQL
open http://localhost:8080/graphiql
```

## Rodar os testes

```bash
# Compila + testes unitarios + integration tests (Testcontainers sobe MongoDB automaticamente)
mvn verify
```

## Git hooks

Instale as dependencias de hooks antes de fazer commits:

```bash
pip install pre-commit commitizen
pre-commit install --hook-type commit-msg
pre-commit install --hook-type pre-commit
```

## Conventional Commits

Todos os commits devem seguir o padrao [Conventional Commits](https://www.conventionalcommits.org/).

| Tipo       | Quando usar                                 |
|------------|---------------------------------------------|
| `feat`     | Nova funcionalidade                         |
| `fix`      | Correcao de bug                             |
| `docs`     | Documentacao                                |
| `style`    | Formatacao, sem mudanca de logica           |
| `refactor` | Refatoracao sem nova feature nem bug fix    |
| `perf`     | Melhoria de performance                     |
| `test`     | Adicionar ou corrigir testes                |
| `build`    | Sistema de build, dependencias              |
| `ci`       | Configuracao de CI/CD                       |
| `chore`    | Tarefas de manutencao                       |
| `revert`   | Reverter commit anterior                    |

## Estrutura do projeto

```
nexio-workflow/
  src/main/java/com/nexio/workflow/
    domain/
      model/              # Entidades e enums de dominio (Sprint 1)
    application/
      usecase/            # Casos de uso (Sprint 2)
      port/out/           # Interfaces de porta de saida
    infrastructure/
      config/             # MongoConfig, GraphQLScalarsConfig
      mongodb/            # Adaptadores MongoDB (Sprint 1)
      execution/node/     # Executores de no (Sprint 3)
      scheduler/          # Trigger por cron (Sprint 4)
    api/
      graphql/            # Resolvers GraphQL (Sprint 2)
      rest/               # MockTriggerController
  src/main/resources/
    graphql/
      schema.graphqls     # Schema GraphQL schema-first
    application.yml       # Configuracoes com perfis dev/test
  docker-compose.yml      # MongoDB 8 local
  checkstyle.xml          # Regras Google Style simplificadas
```

## Links

- `docs/nexio-workflow/setup-guide.md` — guia de setup do Sprint 0
- GraphiQL (local): http://localhost:8080/graphiql
- GraphQL endpoint: http://localhost:8080/graphql
