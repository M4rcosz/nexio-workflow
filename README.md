# nexio-workflow

A backend service for designing and running automation workflows. You describe a small flowchart —
*call this URL; if the response says the total is over 100, call that one; otherwise call a third* —
and the service stores it, validates it, and executes it.

GraphQL API, MongoDB storage, Java 25 and Spring Boot.

---

## Contents

- [Run it in five minutes](#run-it-in-five-minutes)
- [Build a workflow end to end](#build-a-workflow-end-to-end)
- [Node types and their fields](#node-types-and-their-fields)
- [Templates](#templates)
- [Condition expressions](#condition-expressions)
- [Scheduled workflows](#scheduled-workflows)
- [Endpoints](#endpoints)
- [Architecture](#architecture)
- [Tests](#tests)
- [Configuration](#configuration)
- [Contributing](#contributing)

---

## Run it in five minutes

You need **Java 25** and **Docker**. You do *not* need Maven installed — the repository ships the
Maven Wrapper (`./mvnw`).

```bash
git clone <repo-url>
cd nexio-workflow

docker compose up -d          # MongoDB 8 on localhost:27017
cp .env.example .env

./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Then open **<http://localhost:8080/graphiql>** — an interactive console for the queries below.

> The `dev` profile is what enables GraphiQL and the mock trigger endpoint. Both are off by default
> in every other profile: there is no authentication yet, and neither should be reachable on a
> deployed instance. See [Security status](#security-status).

Check it is alive:

```bash
curl -s localhost:8080/actuator/health
# {"status":"UP", ...}
```

---

## Build a workflow end to end

The example below is the one exercised by `EndToEndWorkflowTest`, so it is a flow the test suite
actually runs rather than one that merely looks right.

**The workflow:** fetch an order, and if its total is over 100, call the expensive-charge endpoint;
otherwise the cheap one.

### 1. Create it

```graphql
mutation {
  createWorkflow(input: {
    name: "cobranca por valor"
    enabled: true
    trigger: { type: MOCK_EVENT, config: {} }
    startNodeId: "consulta"
    nodes: [
      {
        id: "consulta"
        type: HTTP_REQUEST
        url: "https://api.exemplo.test/pedido/{{trigger.pedidoId}}"
        method: GET
        nextOnSuccess: "checa"
      },
      {
        id: "checa"
        type: CONDITION
        expression: "#outputs['consulta']['body']['total'] > 100"
        nextOnTrue: "cobranca-alta"
        nextOnFalse: "cobranca-baixa"
      },
      { id: "cobranca-alta",  type: HTTP_REQUEST, url: "https://api.exemplo.test/cobranca-alta",  method: POST },
      { id: "cobranca-baixa", type: HTTP_REQUEST, url: "https://api.exemplo.test/cobranca-baixa", method: POST }
    ]
  }) { id name enabled }
}
```

The graph is validated **on write**, not on the first run. Cycles, edges pointing at nodes that
don't exist, unreachable nodes, an internal or malformed URL, a condition that uses a forbidden
construct — all of these come back as `BAD_REQUEST` while you still have the workflow on screen.

### 2. Trigger it

Over GraphQL:

```graphql
mutation {
  triggerWorkflow(id: "<workflow-id>", payload: { pedidoId: "PED-1" }) {
    id
    status
    errorMessage
    steps { nodeId status output error }
  }
}
```

Or over the mock REST endpoint (`dev` profile only):

```bash
curl -sX POST localhost:8080/api/v1/mock/trigger/<workflow-id> \
  -H 'Content-Type: application/json' \
  -d '{"pedidoId":"PED-1"}'
```

**Triggering is synchronous**: the response *is* the finished execution, with every step. That is a
deliberate choice — see [ADR 0005](docs/adr/0005-synchronous-execution.md) — and it is why the
answer is `200` rather than `202`, and why one GraphQL document may contain at most one
`triggerWorkflow`.

A response looks like this:

```json
{
  "id": "0f1c…", "status": "SUCCESS", "errorMessage": null,
  "steps": [
    { "nodeId": "consulta", "status": "SUCCESS",
      "output": { "statusCode": 200, "headers": { "content-type": "application/json" },
                  "body": { "total": 150 }, "truncated": false, "droppedKeys": 0 } },
    { "nodeId": "checa", "status": "SUCCESS", "output": { "result": true } },
    { "nodeId": "cobranca-alta", "status": "SUCCESS", "output": { "statusCode": 200, "…": "…" } }
  ]
}
```

### 3. Read the history

```graphql
query {
  executions(workflowId: "<workflow-id>", limit: 20) {
    id status createdAt finishedAt errorMessage
  }
}

query {
  execution(id: "<execution-id>") {
    status
    steps { nodeId status output error executedAt }
  }
}
```

> `executions` deliberately refuses to return `steps` for a whole page — a list view shows status
> and timestamps, and you open a single execution to see its steps. A page of 100 executions with
> up to 200 steps each, every step holding a third party's response body, is a request the server
> should not accept.

**Secrets are masked on read.** `triggerPayload`, every step's `output`, and any URL in an error
message come back with credential-shaped values replaced by `***REDACTED***`. What is *stored* stays
intact, because that is the value the execution uses. This matters most for step output: a node that
calls an auth endpoint brings back an `access_token` without anyone having written a credential
anywhere.

---

## Node types and their fields

| Field | `HTTP_REQUEST` | `CONDITION` |
| --- | --- | --- |
| `url`, `method`, `headers`, `body` | required / optional | **refused** |
| `expression` | **refused** | required |
| `nextOnSuccess` | the next node | **refused** |
| `nextOnTrue` / `nextOnFalse` | **refused** | the two branches |

The rule is symmetric on purpose. Silently accepting a field the node type never reads leaves you
convinced you configured something that will never be looked at.

A non-2xx response **fails the node** but still records the body — a `500` is the most useful step
in the whole execution. Redirects are **not followed**: following one would bypass the destination
validation entirely, so a `3xx` fails the node with its `location` recorded so the step is
explainable.

---

## Templates

`url`, `headers` and `body` accept `{{trigger.field}}` and `{{steps.<nodeId>.field}}`, with dotted
paths:

```
https://api.exemplo.test/pedidos/{{trigger.pedidoId}}
https://api.exemplo.test/x?ref={{trigger.cliente.ref}}
{{steps.consulta.body.total}}
```

Four behaviours worth knowing before you rely on them:

- **A missing field fails the node.** It does not become empty text — empty would produce
  `…/pedidos/`, a different endpoint, quite possibly a collection instead of an item.
- **Substitution is single-pass.** A value that happens to contain `{{…}}` is not resolved again.
- **Values are URL-encoded by position** — path segment before the `?`, query parameter after.
- **The host cannot be templated.** Placeholders are allowed only after the host, so the destination
  is validated when you save the workflow and a stored definition still tells you which hosts it
  talks to.

In a JSON `body`, a value that is *exactly* one placeholder keeps its type: `"{{trigger.total}}"`
sends the number `150`, not the string `"150"`.

---

## Condition expressions

The trigger payload is the root object, so the common case reads directly. A previous node's output
comes from `#outputs`, keyed by node id:

```
total > 100 and status == 'pago'
#outputs['consulta']['body']['total'] > 100
#trigger['cliente']['plano'] == 'ouro'
total == null ? false : total > 100
```

**The grammar is closed, and checked when you save.** Allowed: literals, `and` / `or` / `!`,
comparison, arithmetic, ternary, elvis, field reads and indexing. Refused: selection `?[…]`,
projection `![…]`, list and map literals, `matches`, `^`, method calls, `new`, `T(…)`, `@bean`,
functions and assignment — *and* anything a future Spring release adds, because the rule is an
allow-list rather than a block-list.

Two different reasons sit behind those refusals. Method and type references are a **security**
matter: `T(java.lang.Runtime)` in a default evaluation context is remote code execution in one line.
Selection, projection and `matches` are refused for **cost**: 84 characters of nested selection
evaluated for 49 seconds in testing. [ADR 0002](docs/adr/0002-spel-sandbox.md) has the measurements.

A condition that cannot be evaluated — missing field, division by zero, a non-boolean result —
**fails the node**. It never quietly becomes the false branch, because that would make a broken
workflow look like it decided.

---

## Scheduled workflows

Set the trigger to `SCHEDULE` with a cron expression:

```graphql
trigger: { type: SCHEDULE, config: { cron: "0 0 8 * * MON-FRI" } }
```

**Six fields, starting with seconds** — Spring's format, not the five-field Unix one. The expression
is validated on write: it must parse, it must actually be able to fire (`0 0 0 30 2 *` — the 30th of
February — parses fine and never happens), and it must not fire more often than every **30 seconds**.

Scheduled runs execute on a worker pool rather than synchronously, because nobody is waiting for
them. A run is skipped if the previous run of the same workflow is still going.

Two current limitations, both deliberate and both worth knowing before deploying:

- **No coordination between instances.** Every instance schedules the same crons, so with N
  instances a workflow fires N times per slot. Single-instance only for now.
- **No replay of missed times.** If the service was down at 08:00, the 08:00 run does not happen
  later.

---

## Endpoints

| Endpoint | What | Availability |
| --- | --- | --- |
| `POST /graphql` | the API | always |
| `/graphiql` | interactive console | `dev` profile only |
| `POST /api/v1/mock/trigger/{id}` | fire a workflow over REST | `dev` profile only |
| `GET /actuator/health` | liveness and readiness probes | always |
| `GET /actuator/info` | build info | always |

### Security status

**There is no authentication yet.** Anyone who can reach `/graphql` can create, read, modify and
delete every workflow, and trigger any of them. Do not expose an instance publicly.

What *is* in place, and tested: SSRF validation of every outbound destination with the connection
pinned to the validated address (so DNS rebinding cannot redirect it), a sandboxed and cost-bounded
expression language, NoSQL-operator rejection on every free-form map, secret redaction on read,
query cost and depth limits, and request and response size caps.

---

## Architecture

Clean architecture, dependencies pointing inward:

```
src/main/java/com/nexio/workflow/
  domain/model/            entities, invariants, graph validation — no framework in the rules
  application/
    engine/                WorkflowEngine, NodeExecutor SPI, ConditionNodeExecutor, TemplateResolver
    usecase/               one class per operation
    port/out/              interfaces the outside world implements
  infrastructure/
    mongodb/               Spring Data adapters
    http/                  hardened outbound client, SSRF validator, HttpRequestNodeExecutor
    scheduler/             CronTriggerService
    config/                wiring, query cost limits, pools
  api/
    graphql/               resolvers and response DTOs (redaction lives in their constructors)
    rest/                  mock trigger endpoint
```

No Spring Data type appears in any port signature, so the storage engine can change without the
application layer noticing.

**Two documents worth reading:**

- **[docs/PROJECT-GUIDE.md](docs/PROJECT-GUIDE.md)** — a complete walkthrough that assumes no prior
  knowledge of the project, Spring, or Java. It also records the bugs found along the way and why
  each one mattered.
- **[docs/adr/](docs/adr/)** — the architecture decisions, each with its context and consequences.
  Several carry *correction sections*, because a claim that stopped being true is more dangerous
  than one that was never made.

---

## Tests

```bash
./mvnw verify        # checkstyle + the whole suite; Testcontainers starts MongoDB itself
./mvnw test          # same tests, no packaging
```

532 tests. The mix matters more than the count: unit tests for rules, slice tests for the API, and
integration tests against a real MongoDB and a real HTTP server — because this project has repeatedly
been bitten by defects that live *between* layers while every layer, tested alone, looked correct.

---

## Configuration

Everything has a working default; see `.env.example`.

| Variable | Default | What |
| --- | --- | --- |
| `MONGODB_URI` | local Docker instance | database connection |
| `SPRING_PROFILES_ACTIVE` | — | `dev` enables GraphiQL and the mock trigger |
| `GRAPHIQL_ENABLED` | `false` | explicit opt-in outside `dev` |
| `NEXIO_ENGINE_MAX_EXECUTION_DURATION` | `30s` | checked before each node, does not interrupt one |
| `NEXIO_HTTP_ALLOW_INSECURE` | `false` | allow plain `http` destinations (dev only) |
| `NEXIO_MONGODB_ENSURE_INDEXES` | `true` | create the declared indexes on startup |

---

## Contributing

Commits follow [Conventional Commits](https://www.conventionalcommits.org/); releases are cut by
release-please, so **never edit the version by hand**.

```bash
pip install pre-commit commitizen
pre-commit install --hook-type commit-msg
pre-commit install --hook-type pre-commit
```

Checkstyle runs in the `validate` phase, so a style violation fails the build before any test runs.
