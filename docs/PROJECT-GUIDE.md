# Nexio Workflow — A Complete Guide From Zero

This document assumes you know **nothing**: not the project, not Spring, not Java. It starts at the
bottom and builds up. Every example is real code from this repository, not invented.

Read it in order. Each part depends on the one before it.

---

## Table of contents

1. [What this project actually is](#1-what-this-project-actually-is)
2. [Java, the parts you need](#2-java-the-parts-you-need)
3. [Spring Boot: what it does for you](#3-spring-boot-what-it-does-for-you)
4. [The architecture: why the folders look like that](#4-the-architecture-why-the-folders-look-like-that)
5. [The data layer: MongoDB](#5-the-data-layer-mongodb)
6. [The API layer: GraphQL](#6-the-api-layer-graphql)
7. [One request, end to end](#7-one-request-end-to-end)
8. [The engine](#8-the-engine)
9. [Security: every concept in this codebase](#9-security-every-concept-in-this-codebase)
10. [Testing, and why counting tests lies](#10-testing-and-why-counting-tests-lies)
11. [The decisions (ADRs), in plain language](#11-the-decisions-adrs-in-plain-language)
12. [Bugs we found, and why they mattered](#12-bugs-we-found-and-why-they-mattered)
13. [What is done and what is left](#13-what-is-done-and-what-is-left)
14. [Glossary](#14-glossary)

---

## 1. What this project actually is

A **workflow engine**. A user describes a small flowchart — "call this URL; if the response says the
total is over 100, call that other URL; otherwise call a third one" — and the system stores it and
can run it.

Two concepts, and keeping them apart is the single most important idea in the whole project:

- **Definition** (`WorkflowDefinition`) — the *recipe*. Written once, stored, reused. "Call X, then
  check Y."
- **Execution** (`WorkflowExecution`) — one *run* of that recipe. If you trigger the same workflow
  five times you get one definition and five executions. Each execution records what happened at
  each step.

A definition contains **nodes** (`WorkflowNode`). A node is one box in the flowchart. There are two
kinds today:

- `HTTP_REQUEST` — call a URL.
- `CONDITION` — evaluate an expression like `total > 100` and branch.

Nodes are connected by **edges**, which are just fields naming the next node:

- `nextOnSuccess` — for normal nodes: "when this works, go here."
- `nextOnTrue` / `nextOnFalse` — for CONDITION nodes: "if true go here, if false go there."

So a workflow is a **directed graph**: boxes with arrows. The project spends a lot of effort making
sure that graph is sane — no cycles (arrows that loop forever), no arrows pointing at boxes that
don't exist, no unreachable boxes.

---

## 2. Java, the parts you need

Java is a **compiled, statically typed** language. Two words that matter:

- **Compiled**: you write `.java` files, a compiler turns them into `.class` files, and those run.
  If you write nonsense, the compiler refuses *before* anything runs. This is why a lot of design in
  this project tries to turn mistakes into compile errors.
- **Statically typed**: every value has a declared type. `String name` can only hold text. `int
  count` can only hold a whole number. The compiler checks it.

### 2.1 Classes

A **class** is a blueprint. It has *fields* (data) and *methods* (behaviour).

```java
public class WorkflowDefinition {
    private String id;          // a field: data this object holds
    private String name;

    public String getName() {   // a method: something this object can do
        return name;
    }
}
```

- `public` — anyone can use this.
- `private` — only code inside this class can touch it. Fields are almost always private, so the
  class controls how they change. That control is the whole point, as you'll see in §2.7.

You create one with `new`:

```java
WorkflowDefinition definition = new WorkflowDefinition();
```

Read that as: "make a new `WorkflowDefinition`, and let me refer to it as `definition`."

### 2.2 Methods, parameters, return types

```java
public String getName() {
    return name;
}
```

- `String` — the type this method gives back.
- `getName` — its name.
- `()` — it takes no inputs.
- `return name;` — hands back the value.

With inputs:

```java
public void setName(String name) {
    this.name = name;
}
```

- `void` — gives nothing back.
- `String name` — takes one input, text, called `name`.
- `this.name` — the *field*; plain `name` here is the *parameter*. `this.` disambiguates.

### 2.3 Interfaces

An **interface** is a contract: a list of methods, with no bodies. It says *what* without saying
*how*.

Real example from this project:

```java
public interface NodeExecutor {
    NodeType supportedType();
    NodeExecutionResult execute(WorkflowNode node, NodeExecutionContext context);
}
```

This says: "anything claiming to be a `NodeExecutor` must be able to tell me which node type it
handles, and must be able to execute a node."

Then separate classes *implement* it:

```java
public class HttpRequestNodeExecutor implements NodeExecutor { ... }
public class ConditionNodeExecutor implements NodeExecutor { ... }
```

**Why this matters enormously here:** the engine (`WorkflowEngine`) only knows about the *interface*.
It has never heard of HTTP or of expressions. It just says "give me the executor for this node type,
and run it." That means we built and fully tested the engine before either real executor existed —
using fake executors in tests. Adding a third node type later touches zero lines of the engine.

### 2.4 Records

A **record** is a short way to write a class that just holds values and never changes.

```java
public record PageQuery(int limit, int offset) { }
```

That one line gives you: two fields, a constructor, `getLimit()`-style accessors (called `limit()`
and `offset()`), plus equality and printing. Written by hand it would be ~40 lines.

Records are **immutable**: once created, the values never change. That kills an entire family of
bugs where something modifies your object behind your back.

### 2.5 The compact constructor — important

A record can validate or transform its inputs as it's built:

```java
public record WorkflowNode(String nodeId, NodeType type, String url, /* ... */) {

    public WorkflowNode {                                    // <- compact constructor
        Objects.requireNonNull(nodeId, "nodeId nao pode ser nulo");
        Objects.requireNonNull(type, "type nao pode ser nulo");
        headers = MapSanitizer.copy(headers, "nodes.headers");
        body    = MapSanitizer.copy(body, "nodes.body");
        config  = MapSanitizer.copy(config, "nodes.config");
    }
}
```

Every single time a `WorkflowNode` comes into existence, this code runs. There is **no way around
it**. That's a powerful place to put a rule — and also a dangerous one, because it runs on paths you
might not expect. That exact fact caused a real bug in this project (§12.2).

### 2.6 Enums

An **enum** is a fixed list of allowed values.

```java
public enum NodeType {
    HTTP_REQUEST,
    CONDITION
}
```

A variable of type `NodeType` can *only* be one of those two. Not `"HTTP_REQEUST"` with a typo — the
compiler rejects it.

We used this deliberately in this project. The HTTP verb used to be free text inside a map, so
`"PSOT"` was accepted and only failed at run time. It's now:

```java
public enum HttpMethod { GET, POST, PUT, PATCH, DELETE, HEAD }
```

Now a bad verb is rejected by GraphQL before any of our code runs. **Turning a runtime failure into a
compile-time or validation-time failure is one of the recurring themes of this codebase.**

### 2.7 Encapsulation, and why fields are private

```java
private String name;

public void setName(String name) {
    this.name = TextSanitizer.requireWithin(name, MAX_NAME_LENGTH, "name");
}
```

Because `name` is private, the *only* way to set it is through `setName`, which enforces a 200-char
limit. If the field were public, anyone could write `definition.name = <10MB of text>` and the rule
would be worthless.

This is why you'll see `WorkflowExecution` has **no** `setStatus` method. You can't just assign a
status. You must call `markRunning()`, `markSucceeded()` or `markFailed()`, and those enforce a
**state machine**: PENDING → RUNNING → SUCCESS or FAILED, and once finished it can't change again.
Making illegal states impossible to express beats checking for them.

### 2.8 Generics — the angle brackets

`List<String>` means "a list, and everything in it is a String." The `<...>` part is a **generic
type parameter**.

```java
Map<String, Object> config
```

A map (dictionary/key-value store) where keys are text and values are anything. This is exactly the
"free-form blob" type that a lot of this project's security work is about — see §9.3.

### 2.9 Annotations — the `@` things

An **annotation** is a label attached to code. By itself it does nothing; something else reads it.

```java
@Document(collection = "workflow_definitions")
public class WorkflowDefinition { ... }
```

`@Document` tells Spring Data "store this in the `workflow_definitions` collection." The class
itself doesn't act on it — a framework reads the label and behaves accordingly.

This is the single biggest source of confusion for people new to Spring: **a lot of behaviour comes
from labels, not from visible calls.** When something happens that you can't find in the code, it's
usually an annotation.

### 2.10 Exceptions

When something goes wrong, Java **throws an exception** — execution stops and jumps up the call
chain until someone catches it.

```java
throw new IllegalArgumentException("No HTTP_REQUEST '" + nodeId + "' precisa definir url");
```

Catching:

```java
try {
    definition.validateGraph();
} catch (IllegalArgumentException e) {
    throw new InvalidWorkflowException(e.getMessage(), e);
}
```

"Try this; if it throws that kind of error, do this instead." Here we're **translating** a generic
Java error into our own domain error — which matters enormously, because a later layer maps
`InvalidWorkflowException` to "400 Bad Request, your input was wrong" while an unknown exception
becomes "500 Internal Server Error, the server is broken." Those are very different messages to send
a user. **We shipped that bug once and fixed it — see §12.1.**

---

## 3. Spring Boot: what it does for you

Spring is a framework. Spring Boot is Spring with sensible defaults so you don't configure everything
by hand.

### 3.1 Dependency injection — the core idea

Normally, an object that needs another object creates it:

```java
public class DeleteWorkflowUseCase {
    private WorkflowDefinitionPort port = new WorkflowDefinitionMongoAdapter(); // BAD
}
```

That's rigid. This class is now welded to MongoDB, and you can't test it without a real database.

Instead, it **asks for** what it needs:

```java
public DeleteWorkflowUseCase(WorkflowDefinitionPort definitionPort,
                             WorkflowExecutionPort executionPort) {
    this.definitionPort = definitionPort;
    this.executionPort = executionPort;
}
```

Spring sees the constructor, finds objects matching those types, and passes them in. That's
**dependency injection**. Consequences:

- The class depends on the *interface* (`WorkflowDefinitionPort`), not on MongoDB.
- In tests you pass a fake. No database needed.
- Swapping MongoDB for PostgreSQL means writing one new class and changing nothing here.

### 3.2 The annotations that make objects available

- `@Service` — "this is business logic; make one and manage it."
- `@Repository` — "this talks to the database."
- `@Controller` — "this handles incoming requests."
- `@Component` — generic version of the above.
- `@Configuration` + `@Bean` — "I'll build this object myself, here's how."

Spring scans your code at startup, finds these, builds one of each, and wires them together. An
object Spring manages is called a **bean**.

We used `@Configuration` deliberately for the engine:

```java
@Configuration
public class WorkflowEngineConfig {
    @Bean
    public WorkflowEngine workflowEngine(ObjectProvider<NodeExecutor> executors, ...) { ... }
}
```

Why not just `@Service` on `WorkflowEngine`? Because it needs a `List<NodeExecutor>`, and **no
`NodeExecutor` exists yet** (they're issues #22 and #23). Spring refuses to inject an empty list by
default, so the entire application would fail to start. `ObjectProvider` tolerates zero. A small
detail that would otherwise have been a very confusing startup crash.

### 3.3 Configuration files

`src/main/resources/application.yml` holds settings:

```yaml
nexio:
  engine:
    max-execution-duration: ${NEXIO_ENGINE_MAX_EXECUTION_DURATION:30s}
```

`${NAME:default}` means "use environment variable `NAME`, or `30s` if it isn't set." This is how you
change behaviour between your laptop and production **without changing code**.

**Profiles** let whole blocks apply conditionally — this project has `dev` and `test` profiles. For
example GraphiQL (a web console that lets anyone run queries) is **off** by default and only enabled
under `dev`. That was originally on by default, which would have shipped an unauthenticated query
console to production.

---

## 4. The architecture: why the folders look like that

This project uses **Ports & Adapters**, also called Hexagonal or Clean Architecture.

```
src/main/java/com/nexio/workflow/
├── domain/          the rules. Knows nothing about anything else.
├── application/     the use cases. Knows domain. Defines "ports".
├── infrastructure/  the real world: MongoDB, HTTP. Implements ports.
└── api/             the outside: GraphQL resolvers.
```

### 4.1 The dependency rule

**Dependencies point inward only.**

```
api ──> application ──> domain
             ↑
      infrastructure
```

`domain` imports nothing from the others. `application` imports `domain`. `infrastructure` and `api`
depend on `application`, and `application` never depends on them.

### 4.2 Ports and adapters

A **port** is an interface the application defines, describing what it needs:

```java
public interface WorkflowDefinitionPort {
    WorkflowDefinition save(WorkflowDefinition definition);
    Optional<WorkflowDefinition> findById(String id);
    List<WorkflowDefinition> findAll(PageQuery page);
    boolean deleteById(String id);
}
```

Notice: no MongoDB anywhere. `PageQuery` is our own type, not Spring's. That's the rule — **no
infrastructure type may appear in a port signature.**

An **adapter** implements it:

```java
@Repository
public class WorkflowDefinitionMongoAdapter implements WorkflowDefinitionPort { ... }
```

This is the *only* place that knows MongoDB exists.

### 4.3 Why bother

- **Testability.** Use cases are tested with fake ports, no database. Fast.
- **Replaceability.** Change database → write one adapter.
- **Clarity.** The business rules aren't tangled with database code.

There's a real cost, which this project hit: **testing each layer with the one below it faked means
bugs that live *between* layers are invisible.** That's not theoretical — it hid a bug that broke
every workflow execution (§12.3).

---

## 5. The data layer: MongoDB

MongoDB is a **document database**. Instead of tables and rows, it stores JSON-like documents.

One `WorkflowDefinition` in the database looks roughly like:

```json
{
  "_id": "a3f9-...",
  "name": "daily billing",
  "enabled": true,
  "triggerConfig": { "type": "MOCK_EVENT", "config": {} },
  "nodes": [
    { "nodeId": "start", "type": "HTTP_REQUEST", "url": "https://api.example.com/x",
      "method": "GET", "nextOnSuccess": "check" },
    { "nodeId": "check", "type": "CONDITION", "expression": "total > 100",
      "nextOnTrue": "big", "nextOnFalse": "small" }
  ],
  "version": 3,
  "createdAt": "2026-08-07T12:00:00Z"
}
```

Note the nodes are stored **inside** the definition, not in a separate table. That's the document
model: things read together are stored together.

### 5.1 The `_id` trap — the worst bug of this project

MongoDB names the primary key `_id`. Spring Data has a rule: **any property named `id` becomes the
primary key and is stored as `_id`** — and it applies that rule even to nested objects.

Our node type originally had a component called `id`. So every node was written as:

```json
{ "_id": "start", "type": "HTTP_REQUEST" }
```

not

```json
{ "id": "start", "type": "HTTP_REQUEST" }
```

Which means **every query searching `nodes.id` matched zero documents, silently.** No error. No
warning. Just empty results forever.

We proved it by querying the raw database and getting `0`, then renamed the component to `nodeId`.
There is now a test that asserts both directions — `nodes.nodeId` matches, `nodes._id` doesn't — so
the fix can't silently regress.

**The lesson:** when a framework does something magical based on a *name*, that magic can fire where
you didn't intend. And "returns nothing" is a much worse failure than "throws an error."

### 5.2 Indexes

An index makes queries fast. Without one, MongoDB reads every document.

```java
@CompoundIndex(name = "def_enabled_trigger", def = "{'enabled': 1, 'triggerConfig.type': 1}")
```

Indexes work **left to right**. This index serves a query on `enabled`, or on `enabled` +
`triggerConfig.type` — but **not** on `triggerConfig.type` alone, because that isn't a prefix. That's
why there's a second index for that case.

### 5.3 Optimistic locking — `@Version`

```java
@Version
private Long version;
```

Two people load the same workflow. Both edit. Both save. Without protection, the second silently
erases the first.

With `@Version`, every save checks the version still matches and increments it. The second save fails
loudly instead of losing data.

**This mechanism caused a serious bug — see §12.3.** Understanding it is worth the effort.

### 5.4 Auditing

```java
@CreatedDate  private Instant createdAt;
@LastModifiedDate private Instant updatedAt;
```

Spring fills these automatically. You never set them by hand.

---

## 6. The API layer: GraphQL

REST gives you fixed endpoints returning fixed shapes. GraphQL gives you **one endpoint**, and the
client says exactly what it wants.

The **schema** (`src/main/resources/graphql/schema.graphqls`) is the contract:

```graphql
type Query {
  workflows(limit: Int = 20, offset: Int = 0, enabledOnly: Boolean = false): [WorkflowDefinition!]!
  workflow(id: ID!): WorkflowDefinition
}

type Mutation {
  createWorkflow(input: CreateWorkflowInput!): WorkflowDefinition!
  deleteWorkflow(id: ID!): Boolean!
}
```

Reading the punctuation:
- `String` — text, may be null.
- `String!` — text, never null.
- `[WorkflowNode!]!` — a list that is never null, containing items that are never null.
- `Query` = read. `Mutation` = write.

A client sends:

```graphql
{ workflow(id: "abc") { name nodes { id url } } }
```

and gets back exactly `name` and those two node fields — nothing else.

### 6.1 Resolvers

A **resolver** is the Java method behind a schema field:

```java
@QueryMapping
public WorkflowDefinitionResponse workflow(@Argument String id) {
    return WorkflowGraphQlMapper.toResponse(getWorkflowUseCase.execute(actor(), id));
}
```

`@QueryMapping` links the method name to the schema field. `@Argument` binds the GraphQL argument.

The resolver in this project does exactly three things: translate input, call the use case, translate
output. No business logic. That's deliberate — it means you could add a REST API tomorrow without
touching anything below.

### 6.2 "Omitted" vs "explicitly null" — a genuinely subtle problem

When updating, these two requests mean different things:

```graphql
updateWorkflow(id: "x", input: { name: "new" })                    # don't touch description
updateWorkflow(id: "x", input: { name: "new", description: null }) # erase description
```

Java's usual tool, `Optional`, **cannot** express this: `Optional` can be "absent" or "present", but
a *present null* isn't representable.

So the project has its own type:

```java
public record Patch<T>(boolean present, T value) {
    public static <T> Patch<T> of(T value)   { return new Patch<>(true, value); }  // set it (even to null)
    public static <T> Patch<T> unchanged()   { return new Patch<>(false, null); }  // leave it alone
}
```

And GraphQL's side uses Spring's `ArgumentValue`, checking `!isOmitted()` rather than `isPresent()`.

This distinction is only observable through the **whole stack**, which is why there's a full
end-to-end test for it.

---

## 7. One request, end to end

Follow `createWorkflow` all the way down.

**1. HTTP arrives** at `/graphql`.

**2. `RequestSizeLimitFilter`** counts actual bytes; over 256KB → rejected with 413. It counts real
bytes rather than trusting the `Content-Length` header, because a lying header is trivial.

**3. Query cost check.** `GraphQlQueryCostConfig` rejects queries that are too deep (>14) or too
expensive (>2000). §9.5 explains why those numbers.

**4. Binding + validation.** GraphQL parses the input into `CreateWorkflowInput`. Annotations like
`@NotBlank` and `@Size` run here.

> A subtle Java/Spring trap we hit: `@Valid` on a container doesn't cascade to its contents. You need
> `ArgumentValue<@Valid TriggerConfigInput>` — the annotation on the *type argument*. Without it,
> nested objects go completely unvalidated, silently.

**5. Resolver** gets the actor (§9.6), maps input to a command, calls the use case.

**6. Use case** (`CreateWorkflowUseCase`) builds the domain object, sets fields through setters that
enforce limits, and calls `validateGraph()` and `validateConfigs()` inside a `try`, translating
failures to `InvalidWorkflowException` → **400 Bad Request**.

**7. `validateGraph()`** runs, in this order — and the order is deliberate:
   1. node ids valid and unique
   2. every edge points at an existing node
   3. **no cycles** (depth-first search with three colours: unvisited / in-progress / done — finding
      an in-progress node again means a loop)
   4. exactly one start node resolvable
   5. every node reachable
   6. per-type parameters (HTTP needs `url`, CONDITION needs `expression`)

   Cycle detection runs *before* start-node resolution because a fully cyclic graph has no node
   without an incoming edge, so the start-node check would report "expected 1 node with no incoming
   edge, found 0" — technically true, completely misleading. Parameter checks run *last* so that a
   cyclic graph whose nodes also lack a `url` still reports the cycle, which is the real problem.

   **Error message quality is a design concern here, not an afterthought.**

**8. `port.save(...)`** → the adapter → Spring Data.

**9. `BeforeConvertCallback`** fires automatically before writing. It re-runs validation *and* checks
every URL against SSRF rules (§9.2). This is the seam **no code path can skip** — that's its whole
value.

**10. MongoDB writes.** `@Version` set to 0, `createdAt`/`updatedAt` filled.

**11. Back out:** domain object → `WorkflowDefinitionResponse`. Secrets are redacted **in the
response record's compact constructor**, so no future code path can forget (§9.1).

---

## 8. The engine

`WorkflowEngine` runs a workflow. Roughly:

```
create execution (RUNNING)
resolve start node
loop:
  time cap exceeded?  -> FAILED, stop
  step ceiling hit?   -> FAILED, stop
  find executor for node type
  run it
  record a step (appendStep)
  choose next node from the outcome
reload execution
mark SUCCESS or FAILED
save
```

### 8.1 The result type

```java
enum NodeOutcome { SUCCESS, CONDITION_TRUE, CONDITION_FALSE, FAILURE }

record NodeExecutionResult(NodeOutcome outcome, Map<String,Object> output, String error) { }
```

Why four named outcomes instead of `boolean success` + `Boolean branch`? Because a nullable `Boolean`
can't distinguish "not a condition node" from "condition evaluated false". With named outcomes, the
engine's decision becomes an exhaustive `switch` with no `default` — so adding a fifth outcome later
**fails to compile** instead of silently falling through. Compile errors are better than silent
wrong behaviour.

### 8.2 Three defensive decisions

- **Outcome contradicting node type fails the execution.** A CONDITION returning plain `SUCCESS`
  would try to follow `nextOnSuccess`, which validation forbids CONDITION nodes to have — the walk
  would just end, and the execution would be recorded SUCCESS having skipped half the workflow.
  Silently doing half the work and reporting success is the worst possible failure.
- **An exception escaping an executor becomes a FAILED step**, not a crash. Otherwise a bug in a
  future executor leaves an execution stuck at RUNNING forever.
- **A step ceiling (200)** as a hard stop, even though cycles are rejected at write time — because a
  document stored before a validation rule existed could still contain one.

### 8.3 The time cap, honestly described

`nexio.engine.max-execution-duration` (30s default) does **not** interrupt a running node. Java can't
safely kill a thread. What it does is check the clock **before starting each node**.

Real worst case: `cap + one node's duration` = 30s + 10s. That's written in the code's documentation
and pinned by a test, rather than letting the config value imply a guarantee it doesn't provide.

---

## 9. Security: every concept in this codebase

### 9.1 Secret redaction

A workflow node stores an `Authorization` header. Anyone reading the workflow back would see the
credential in plain text — and there's no authentication yet, so that's *anyone*.

`SecretRedactor` replaces values of sensitive-looking keys with `***REDACTED***`.

Three details that matter:

**It runs in the response record's compact constructor**, not in the mapper. There is no construction
path that avoids a constructor, so no future code can forget to redact. If it lived in the mapper, it
would protect only as long as everyone used the mapper.

**Key names aren't enough.** This is a real hole we closed:

```
url: "https://api.stripe.com/v1/charges?api_key=sk_live_51H..."
```

`url` isn't a sensitive key name, so a name-based redactor returns it verbatim. Now string values are
also parsed as URLs, stripping userinfo (`https://user:pass@host/`) and redacting sensitive *query
parameters* — while keeping the rest readable so it's still useful for debugging.

**Short keywords need word boundaries.** Adding `sig` and `pin` to the sensitive list caused
`design`, `mapping` and `spinner` to be redacted, because matching was substring-based. That went
from cosmetic to harmful the moment we started rejecting the marker on write (below), so those three
short terms now only match when delimiter-bounded.

**Redaction is read-only.** What's stored stays real — the execution needs the actual credential.
Which creates the next problem:

**The round-trip trap.** A UI reads a workflow (`Authorization: ***REDACTED***`), the user edits the
name, the UI sends the whole object back — and now the literal string `***REDACTED***` is your
credential, and the real one is gone forever. Every generated CRUD UI does this. So writing the
marker is now **rejected**.

### 9.2 SSRF (Server-Side Request Forgery)

Our server makes HTTP requests to URLs a *user* chose. So a user can point us at things they can't
reach themselves:

```
http://169.254.169.254/latest/meta-data/iam/security-credentials/
```

On AWS that address returns cloud credentials. It's unreachable from the internet — but our server is
inside, and we'd fetch it and put the response in the execution output where the attacker can read
it.

`HttpTargetValidator` rejects: loopback (`127.x`), link-local (`169.254.x`), private ranges,
carrier-grade NAT, embedded credentials, URL fragments, port 0, and non-HTTPS unless explicitly
allowed. It checks **every** address a hostname resolves to, because a hostname can publish several.

Its error messages deliberately carry **no** detail — an unresolvable host and a blocked address
produce *identical* text, so the error can't be used as a scanner to map the internal network.

**DNS rebinding** remains open, and is worth understanding: an attacker's domain can answer with a
harmless address when we validate, then an internal one moments later when we connect. The only real
fix is to validate, then connect to *the exact IP already validated*. That's required work in #23.

We now also validate at **write** time (when the workflow is created) — but only the parts that don't
need DNS, plus literal IPs. Full resolution at write time would make workflow creation depend on the
network and turn every non-resolving name into an error. Write-time checking is defence in depth; the
runtime check remains mandatory.

### 9.3 NoSQL injection and the `$` problem

MongoDB queries *are* documents. Keys starting with `$` are operators — `$where` executes JavaScript
on the database server. If user-controlled keys reach a query, that's remote code execution.

`MapSanitizer` enforces the write policy: no `$`-prefixed keys, no `_`-prefixed keys, key length
caps, string length caps, entry counts, nesting depth, and a narrow list of allowed value types.

**The lenient/strict split** is subtle and important. There are two methods:

- `copy()` — **lenient**, used in constructors.
- `validate()` — **strict**, used on writes.

Why? Because record compact constructors also run when Spring Data *reads* a document from the
database. If constructors were strict, one bad document written before a rule existed would make
**every read of the entire collection** throw. You'd lose access to all your data because of one bad
row. So: lenient on read, strict on write.

### 9.4 Error mapping: 400 vs 500

`InvalidWorkflowException` → **400 Bad Request** ("your input was wrong").
Anything unrecognised → **500 Internal Server Error** ("the server is broken"), with the message
hidden so database details don't leak.

Getting this wrong is worse than it sounds. We shipped a version where `config: {"$where": "1"}`
returned **500** and logged a full stack trace on every request, with no authentication in front of
it. Two consequences: cheap disk exhaustion, and — worse — an attacker probing the injection guard
produced signals indistinguishable from genuine server bugs. **In both directions.**

### 9.5 Denial of service through query cost

`workflows(limit: 100)` does more work than `workflows(limit: 1)`. But graphql-java's default cost
calculator is `1 + childComplexity` and **ignores list size**, so both scored the same.

Combined with an unpaginated database call, this was exploitable: 99 aliased copies of
`workflows(limit: 1, enabledOnly: true){id}` scored 198 against a limit of 200 — passing — while each
one loaded the *entire* collection into memory. One small request, 99 full-collection loads. And
`limit: 1` kept the response tiny so nothing downstream looked wrong.

Fixed on both sides: pagination pushed into the database, and a cost calculator that multiplies by
`limit`. The ceiling moved 200 → 2000 because the old number only *looked* strict while the
calculator was measuring the wrong thing.

### 9.6 What's still missing: authentication

**There is none.** Anyone who can reach `/graphql` can create, read, modify and delete every
workflow.

What exists is a **seam**: an `ActorId` threaded through every use case, sourced from
`CurrentActorPort` → `AnonymousActorProvider`, which returns the same anonymous actor for everyone.

The critical design constraint: the actor **never** comes from client input. An earlier version of
this project had a `tenantId` supplied as a GraphQL argument, which isolated nothing — you'd just
pass someone else's ID. That was deleted (ADR 0001). The seam avoids repeating that mistake purely
because of where the value originates, and there's a test asserting the schema *rejects* an `actorId`
field before resolution even runs.

Be clear-eyed: **a parameter that every implementation ignores can read as security theatre.** That's
the honest cost. What it buys is that the future check lands in one place instead of eight.

---

## 10. Testing, and why counting tests lies

296 tests pass. Types used here:

- **Unit tests** — one class, dependencies faked. Fast.
- **Slice tests** — one layer with real framework (`@DataMongoTest`, `@GraphQlTest`).
- **Integration tests** — the whole stack against a real MongoDB in Docker (**Testcontainers**).

### 10.1 Test count is a bad metric

The version bug (§12.3) broke **every workflow execution**, and 261 tests passed. Because the
engine's unit tests mocked the port, and a mock doesn't reproduce what the real database does.

**A test that mocks the thing where the bug lives cannot find the bug.** Layered architecture makes
this worse, not better, because it encourages mocking at every boundary.

### 10.2 Mutation testing

Used repeatedly here: break the code deliberately, confirm the test fails, then restore. If it still
passes, the test was proving nothing.

We did this for the pagination sort, the redaction word boundaries, the query cost calculator, and
the write-time SSRF check. In one case a `BAD_REQUEST` assertion would have passed for entirely the
wrong reason.

### 10.3 A test can be worse than no test

An agent was asked to prove the actor reaching the use case came from the port. With the real
provider, the actor is the only value the system can produce — so the assertion would pass even if
the resolver ignored the port entirely and built the constant itself. It caught this and used a
distinct fake value instead.

**A test that cannot fail is worse than no test: it produces confidence you haven't earned.**

---

## 11. The decisions (ADRs), in plain language

An **ADR** (Architecture Decision Record) captures *why* a decision was made, so future readers don't
undo it without understanding it. They're in `docs/adr/`.

| ADR | Decision | Why |
|---|---|---|
| 0001 | No multi-tenancy | `tenantId` came from the client, so it isolated nothing. Fake isolation is worse than declared absence. |
| 0002 | SpEL sandbox | Expressions are user input. The obvious implementation is one-line remote code execution. Read-only context, validated at write time. |
| 0003 | Typed node fields | Validation only sees what's declared. `url` in a free-form map is invisible to the SSRF validator. |
| 0004 | `appendStep` with `$push` | Re-saving per step is quadratic. But `$push` skips the validation seam, so the port method must validate itself. |
| 0005 | Synchronous execution | Async silently swallows exceptions, leaving executions stuck at RUNNING forever, needing a reaper. |
| 0006 | Actor seam | Thread the actor now while there are five use cases, not later when there are eight. |

**ADRs 0003, 0004 and 0005 all have correction sections**, because reality disagreed with them. The
original reasoning is kept next to what it got wrong — that's more useful than a document that
pretends it was right all along.

---

## 12. Bugs we found, and why they mattered

### 12.1 User error reported as server error

`config: {"$where": "1"}` returned 500 instead of 400. The validation ran inside a database callback,
which ran inside `port.save(...)`, which sat **outside** the `try/catch` in the use case. Fixed by
validating at the write boundary and having the callback throw the domain exception too.

### 12.2 Read-path poisoning

Two reviewers disagreed about whether validation ran when *reading* from the database. Both were
wrong. Spring Data writes fields directly, so **setters never run** on read — but **record compact
constructors do**. So the risk was real, just in a different place than either had said.

**Only running the code settled it.** When reviewers disagree, run an experiment.

### 12.3 The version bug — every execution broken, suite green

ADR 0004 assumed `$push` bypasses `@Version`. It doesn't — Spring Data's `updateFirst` adds an
increment for versioned entities.

So: N steps → document at version N; engine's in-memory object still at version 0; final save
rejected as a conflict. **Every execution with at least one node failed.** And 261 tests passed,
because the engine's tests mocked the port.

The visible symptom was a single stale assertion (`expected: 0L but was: 2L`) that looked like a
wrong assertion rather than a wrong premise. Fixed by reloading before the terminal write, and by
adding an integration test against a real database.

### 12.4 The `_id` trap

Covered in §5.1. Queries silently matched nothing.

### 12.5 Redaction round-trip

Covered in §9.1. A normal read-edit-save cycle destroyed credentials.

---

## 13. What is done and what is left

**Done:** domain model with graph validation; MongoDB persistence with indexes, optimistic locking
and auditing; hardened outbound HTTP client with SSRF validation; full CRUD use cases; GraphQL API;
query cost limits; secret redaction; the workflow engine and its extension point; 296 tests.

**Left in Sprint 3:**
- **#22** CONDITION executor — must use `SimpleEvaluationContext.forReadOnlyDataBinding()`. A default
  SpEL context is remote code execution.
- **#23** HTTP executor — must validate at runtime *and* pin the connection to the validated IP
  (DNS rebinding).
- **#24–#26** execution use cases, mock trigger endpoint, and the resolver that re-exposes execution
  queries in the schema.

**Sprint 4:** authentication and authorization; `ownerId` on the aggregate with a migration and
index; owner-scoped port methods; rate limiting; scheduler.

**Known open items:**
- No authentication at all.
- DNS rebinding not closed.
- No rate limiting.
- `ExecutionStep.output` will need its own redaction when exposed (#26) — it will contain third-party
  response bodies, which are the least trustworthy data in the system.

---

## 14. Glossary

| Term | Meaning |
|---|---|
| **Adapter** | Class implementing a port using real technology. |
| **Aggregate** | A cluster of objects treated as one unit, e.g. a definition plus its nodes. |
| **Annotation** | `@Something` — a label read by a framework. |
| **Bean** | An object Spring creates and manages. |
| **Compact constructor** | Record constructor that validates/transforms inputs. Runs on every construction, including database reads. |
| **DI (Dependency Injection)** | Objects receive collaborators instead of creating them. |
| **DTO** | Data Transfer Object — a shape for crossing a boundary. |
| **Enum** | A fixed set of allowed values. |
| **Idempotent** | Running it twice does the same as running it once. |
| **Immutable** | Cannot change after creation. |
| **Index** | Database structure making queries fast. |
| **Interface** | A contract: methods without implementations. |
| **Mock / fake** | Stand-in object used in tests. |
| **Optimistic locking** | Detecting concurrent modification via a version number. |
| **Port** | Interface the application defines describing what it needs. |
| **Record** | Short syntax for an immutable data class. |
| **Resolver** | Java method behind a GraphQL field. |
| **Schema** | The GraphQL contract. |
| **SSRF** | Making a server fetch a URL it shouldn't reach. |
| **Testcontainers** | Library running real databases in Docker for tests. |
| **Use case** | One application operation, e.g. "create a workflow". |

---

## Where to look in the code

Reading order, if you want to explore:

1. `src/main/resources/graphql/schema.graphqls` — the contract, and readable without Java.
2. `domain/model/WorkflowDefinition.java` — the rules, especially `validateGraph()`.
3. `application/usecase/CreateWorkflowUseCase.java` — a short, complete operation.
4. `api/graphql/WorkflowResolver.java` — the entry point.
5. `application/engine/WorkflowEngine.java` — the most interesting logic.
6. `docs/adr/` — why things are the way they are.

The comments in this codebase are in Portuguese and explain **why**, not what. They're often the
fastest way to understand a decision — many of them exist specifically to stop someone "simplifying"
something load-bearing.
