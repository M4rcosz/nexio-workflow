# Frontend integration guide — nexio-workflow

Written for whoever builds the UI. It covers the API surface, the rules the backend enforces, and —
most importantly — **the behaviours that will bite you if you assume this is an ordinary CRUD API.**
Read [§2](#2-seven-things-that-will-bite-you) before writing any code.

- Endpoint: `POST /graphql` (single endpoint, schema-first)
- Interactive console: `/graphiql`, **dev profile only**
- Health: `GET /actuator/health`
- Auth today: **none**. See [§9](#9-authentication).

---

## 1. The domain in one paragraph

A **workflow definition** is a recipe: a small directed graph of nodes. An **execution** is one run
of that recipe, recording a **step** per node visited. Two node types exist: `HTTP_REQUEST` calls a
URL, `CONDITION` evaluates an expression and branches. Definitions and executions are separate
resources with separate queries — trigger a workflow five times and you get one definition and five
executions.

---

## 2. Seven things that will bite you

### 2.1 Never send `***REDACTED***` back

This is the one that will actually break a user's workflow, and the read/edit/save loop is the most
natural thing to build.

Reads mask credential-shaped values: `headers.Authorization`, anything named `token`, `apiKey`,
`password`, `secret`, and the query parameters and userinfo inside `url`. The value you receive is
the literal string `***REDACTED***`. **Writes reject that marker** — deliberately, because accepting
it would overwrite the real credential with the mask and silently break the workflow.

So a form that loads a workflow, changes the name, and posts the whole object back **fails**.

Do one of these instead:

- send only the fields the user actually edited (the update mutation is a partial patch — see §2.2);
- or, if you must send a whole object, strip every field whose value is exactly `***REDACTED***`
  before sending, and show a "leave blank to keep current" affordance for secret inputs.

```ts
const REDACTED = '***REDACTED***';
const stripRedacted = (v: unknown): unknown =>
  Array.isArray(v) ? v.map(stripRedacted)
  : v && typeof v === 'object'
    ? Object.fromEntries(
        Object.entries(v).filter(([, x]) => x !== REDACTED).map(([k, x]) => [k, stripRedacted(x)]))
  : v;
```

### 2.2 `updateWorkflow` distinguishes "omitted" from "explicit null"

- field **omitted** → left as it is
- field sent as **`null`** → cleared

That is a real distinction, not an accident, so build the form state accordingly: don't send keys the
user never touched. Sending `description: null` deletes the description; leaving it out keeps it.

### 2.3 Triggering is synchronous and can take ~40 seconds

`triggerWorkflow` runs the whole workflow and returns the finished execution. It is not a job
submission. Consequences for the UI:

- **do not set a short client timeout.** The engine's cap is 30s, checked before each node, and a
  node already running is not interrupted — so the realistic worst case is a bit over 40s.
- show a determinate-ish progress state; don't leave a spinner with no explanation.
- the response *is* the result: no polling needed, and `steps` tells you what happened.

### 2.4 One `triggerWorkflow` per document

A document with two or more `triggerWorkflow` fields is rejected outright. Root mutation fields run
serially, so batching N triggers means N full workflow runs on one request. Fire them as separate
requests.

### 2.5 Don't ask for `steps` in a list of executions

`executions(...) { steps { … } }` is refused by the query-cost limit. Each execution can hold up to
200 steps, and each step's `output` is a third party's whole response body.

Correct shape: list without steps, then fetch one execution with its steps when the user opens it.

### 2.6 Server-imposed paging limits

`limit` 1–100 (default 20), `offset` 0–10000. Values outside those come back as `BAD_REQUEST`, so
clamp in the UI rather than letting the server refuse.

Also: **query depth ≤ 14 and complexity ≤ 2000.** You will not hit these with normal queries; you
can hit them by aliasing the same query many times in one document. Prefer separate requests.

### 2.7 Concurrent edits return `CONFLICT`

Two saves of the same workflow race, and the loser gets `CONFLICT`. Handle it: re-fetch, show the
user what changed, and let them decide. Don't retry blindly — that silently discards someone's edit.

Request bodies are capped at **256 KB** (`413` if exceeded).

---

## 3. Error handling

Every error carries `extensions.classification`:

| Classification | Meaning | UI treatment |
| --- | --- | --- |
| `BAD_REQUEST` | invalid input — bad graph, bad cron, bad expression, redaction marker, limit out of range | show `message` next to the field; it is written for a human |
| `NOT_FOUND` | no such workflow or execution | 404 state |
| `CONFLICT` | concurrent modification | re-fetch and reconcile (§2.7) |
| `INTERNAL_ERROR` | a server defect | generic apology, log it, **never** show the raw message |

`BAD_REQUEST` messages are intentionally specific and safe to display — e.g. *"No CONDITION 'checa'
precisa definir expression"*, *"url do no 'x' usa marcador no esquema, no host ou na porta"*. They
are currently in Portuguese; if the UI is localised, key off the message text at your own risk and
prefer client-side validation (§6) for anything you can check yourself.

---

## 4. Operations

### Queries

```graphql
workflows(limit: Int = 20, offset: Int = 0, enabledOnly: Boolean = false): [WorkflowDefinition!]!
workflow(id: ID!): WorkflowDefinition
executions(workflowId: ID!, limit: Int = 20, offset: Int = 0): [WorkflowExecution!]!
execution(id: ID!): WorkflowExecution
```

`executions` on an unknown `workflowId` is `NOT_FOUND`, not an empty list — an empty list would be
indistinguishable from a real workflow that was never triggered.

### Mutations

```graphql
createWorkflow(input: CreateWorkflowInput!): WorkflowDefinition!
updateWorkflow(id: ID!, input: UpdateWorkflowInput!): WorkflowDefinition!
deleteWorkflow(id: ID!): Boolean!
activateWorkflow(id: ID!): WorkflowDefinition!
deactivateWorkflow(id: ID!): WorkflowDefinition!
triggerWorkflow(id: ID!, payload: JSON): WorkflowExecution!
```

`activateWorkflow` / `deactivateWorkflow` are shortcuts for the enabled flag — use them for a toggle
rather than building a partial update.

`deleteWorkflow` also removes that workflow's executions.

### Types

```graphql
type WorkflowDefinition {
  id: ID!  name: String!  description: String
  trigger: TriggerConfig!  nodes: [WorkflowNode!]!  startNodeId: String
  enabled: Boolean!  createdAt: DateTime!  updatedAt: DateTime!
}

type WorkflowNode {
  id: String!  type: NodeType!
  url: String  method: HttpMethod  headers: JSON  body: JSON   # HTTP_REQUEST only
  expression: String                                            # CONDITION only
  config: JSON!
  nextOnSuccess: String  nextOnTrue: String  nextOnFalse: String
}

type WorkflowExecution {
  id: ID!  workflowId: ID!  status: ExecutionStatus!
  triggerPayload: JSON!  steps: [ExecutionStep!]!
  createdAt: DateTime!  startedAt: DateTime  finishedAt: DateTime  errorMessage: String
}

type ExecutionStep {
  nodeId: String!  status: StepStatus!  output: JSON!  error: String  executedAt: DateTime!
}

enum NodeType        { HTTP_REQUEST, CONDITION }
enum HttpMethod      { GET, POST, PUT, PATCH, DELETE, HEAD }
enum TriggerType     { MOCK_EVENT, SCHEDULE }
enum ExecutionStatus { PENDING, RUNNING, SUCCESS, FAILED }
enum StepStatus      { PENDING, SUCCESS, FAILED, SKIPPED }
```

`DateTime` is ISO-8601 with offset (`2026-01-01T00:00:00Z`). `JSON` is an arbitrary object.

---

## 5. Reading an execution

`status` is `SUCCESS` or `FAILED` when you get it back from a trigger. `steps` is in execution order
— it is also the branch that was actually taken, which is what a run view should render.

Step `output` shape by node type:

```jsonc
// HTTP_REQUEST — always this shape, including on failure
{ "statusCode": 200,
  "headers": { "content-type": "application/json" },  // short allow-list only
  "body": { /* the response, or a string when not JSON */ },
  "truncated": false,      // true => something was cut; tell the user
  "droppedKeys": 0 }       // keys the response had that could not be stored

// CONDITION
{ "result": true }         // which branch was taken, and why the next step is what it is
```

Two flags worth surfacing: **`truncated: true`** means the stored body is not the whole response,
and **`droppedKeys > 0`** means keys were removed because they could not be stored (keys starting
with `_` or `$`, e.g. HAL's `_links`). Showing "response partially stored" beats showing an object
that looks complete.

A non-2xx **fails the node but still records the body** — a `500`'s body is usually the most useful
thing on the page. A `3xx` also fails, with `headers.location` recorded, because redirects are not
followed.

---

## 6. Client-side validation worth doing

All of this is enforced server-side; doing it in the UI turns a round trip into instant feedback.

**Graph** — no cycles; every `nextOn*` must name an existing node; every node must be reachable from
the start; ≤ 50 nodes; node ids match `^[A-Za-z0-9_-]{1,64}$`.

**Per node type** — the rule is symmetric, and the server refuses the field a type does not use:

| | `HTTP_REQUEST` | `CONDITION` |
| --- | --- | --- |
| `url` (≤ 2048), `method`, `headers`, `body` | required / optional | must be absent |
| `expression` (≤ 512) | must be absent | required |
| `nextOnSuccess` | the next node | must be absent |
| `nextOnTrue` / `nextOnFalse` | must be absent | the two branches |

**Free-form maps** (`headers`, `body`, `config`, `payload`) — keys may not start with `$` or `_`,
keys ≤ 128 chars, strings ≤ 4096, ≤ 200 entries across all levels, ≤ 10 levels deep.

**Text** — `name` ≤ 200, `description` ≤ 2000.

---

## 7. Templates (for the node editor)

`url`, `headers` and `body` accept `{{trigger.field}}` and `{{steps.<nodeId>.field}}`, dotted paths
allowed.

```
https://api.example.com/orders/{{trigger.orderId}}
https://api.example.com/x?ref={{trigger.customer.ref}}
{{steps.lookup.body.total}}
```

Rules to enforce or explain in the editor:

- **not in the scheme, host or port** — `https://{{trigger.host}}/x` is rejected. Only path and
  query. (The host must stay fixed so the destination can be validated when the workflow is saved.)
- a missing field **fails the node** at run time; it does not become empty text
- ≤ 20 placeholders per field
- in a JSON `body`, a value that is exactly one placeholder keeps its type: `"{{trigger.total}}"`
  sends the number `150`, not `"150"`

A field-picker built from a sample payload is far kinder than a free-text box here.

---

## 8. Condition expressions (for the expression editor)

Root object is the trigger payload; previous node output comes from `#outputs`:

```
total > 100 and status == 'pago'
#outputs['lookup']['body']['total'] > 100
#trigger['customer']['plan'] == 'gold'
total == null ? false : total > 100
```

**The grammar is a closed allow-list, checked when the workflow is saved.**

| Allowed | Rejected |
| --- | --- |
| literals; `and` `or` `!` | selection `?[…]`, projection `![…]` |
| `>` `<` `>=` `<=` `==` `!=` | list `{1,2}` / map `{a:1}` literals |
| `+` `-` `*` `/` `%` | method calls, `new`, `T(…)`, `@bean`, functions |
| ternary, elvis | assignment, `matches`, `^` |
| field reads, indexing, `#variables` | anything not listed |

If you offer autocomplete, offer only the left column. Note `matches` is unavailable, so there is no
regex comparison — if a user asks for one, that is a backend feature request, not a syntax problem.

At run time, a condition that cannot be evaluated (missing field, divide by zero, non-boolean result)
**fails the node**; it never silently takes the false branch.

---

## 9. Authentication

**Today there is none.** Every operation is anonymous, so build the UI with an auth layer in mind but
do not expect a 401 yet.

**The plan** is to accept the JWT that `nexio-core` already issues, so the frontend does not manage a
second identity. Concretely, once it lands:

1. Log in against **nexio-core**, not this service: `POST /auth/login` → `{ access_token, refresh_token }`.
   Sending `x-auth-transport: cookie` puts the refresh token in an httpOnly cookie and omits it from
   the body — prefer that in a browser.
2. Send the access token to nexio-workflow as `Authorization: Bearer <access_token>` on every
   `/graphql` request.
3. Access tokens are short-lived (**15 minutes** by default). On `401`, call nexio-core's
   `POST /auth/refresh` and retry once. Refresh tokens last 7 days.
4. Both services read the same claims, so a single session covers both:

```ts
type NexioJwt = {
  sub: string;              // user id
  username: string;
  role: 'ADMIN' | 'CUSTOMER' | 'KITCHEN' | 'MANAGER' | 'ATTENDANT';
  businessUnitIds: string[];// units this user is bound to; [] means none
  iat: number; exp: number;
};
```

**Do not decode the token to make security decisions** — decode it only to render the UI (hide a
button, show a name). Every real check happens server-side.

Until this lands, treat "who owns a workflow" as unanswerable: there is no `ownerId` on a workflow
yet, so any authenticated user will initially see all workflows. Design list and detail screens so
adding an owner column later is not a rewrite.

---

## 10. A complete flow

```graphql
# 1. create
mutation Create($input: CreateWorkflowInput!) {
  createWorkflow(input: $input) { id name enabled }
}
```
```json
{ "input": {
  "name": "charge by value", "enabled": true,
  "trigger": { "type": "MOCK_EVENT", "config": {} },
  "startNodeId": "lookup",
  "nodes": [
    { "id": "lookup", "type": "HTTP_REQUEST", "method": "GET",
      "url": "https://api.example.com/order/{{trigger.orderId}}", "nextOnSuccess": "check" },
    { "id": "check", "type": "CONDITION",
      "expression": "#outputs['lookup']['body']['total'] > 100",
      "nextOnTrue": "charge-high", "nextOnFalse": "charge-low" },
    { "id": "charge-high", "type": "HTTP_REQUEST", "method": "POST",
      "url": "https://api.example.com/charge-high" },
    { "id": "charge-low", "type": "HTTP_REQUEST", "method": "POST",
      "url": "https://api.example.com/charge-low" }
  ]
} }
```

```graphql
# 2. trigger — long-running, see §2.3
mutation Trigger($id: ID!, $payload: JSON) {
  triggerWorkflow(id: $id, payload: $payload) {
    id status errorMessage
    steps { nodeId status output error executedAt }
  }
}

# 3. list runs — no steps here, see §2.5
query Runs($workflowId: ID!, $limit: Int, $offset: Int) {
  executions(workflowId: $workflowId, limit: $limit, offset: $offset) {
    id status createdAt finishedAt errorMessage
  }
}

# 4. one run, with steps
query Run($id: ID!) {
  execution(id: $id) {
    id workflowId status triggerPayload createdAt finishedAt errorMessage
    steps { nodeId status output error executedAt }
  }
}
```

---

## 11. Scheduled workflows

```json
"trigger": { "type": "SCHEDULE", "config": { "cron": "0 0 8 * * MON-FRI" } }
```

**Six fields, starting with seconds** — Spring's format, not the five-field Unix one. A five-field
expression is rejected, so a cron builder must emit six.

Validated on save: it must parse, it must be able to fire (`0 0 0 30 2 *` never happens), and it must
not fire more often than every **30 seconds**.

Scheduled runs execute in the background; nothing is returned to a caller. The user sees them in the
execution list like any other run.

---

## 12. Further reading

- [`docs/PROJECT-GUIDE.md`](PROJECT-GUIDE.md) — how the backend works and why, from zero
- [`docs/adr/`](adr/) — the decisions, including the ones later corrected
- [`src/main/resources/graphql/schema.graphqls`](../src/main/resources/graphql/schema.graphqls) —
  the schema, heavily commented; it is the contract
