# Divergences from Akka

> Where ankka deliberately differs from Akka's SDK and platform — registration, handler identity, tools, views, routing, ACLs, model settings, descriptors, defaults and lifecycle states — and why.

Source: https://docs.ankka.cloud/reference/akka-divergences/
ankka reimplements Akka's component model — entities, views, consumers, workflows, timers, agents and HTTP
endpoints — in Scala 3 on Apache Pekko, the Apache-licensed fork of Akka 2.6. Someone who knows Akka's SDK
will recognise every component. The differences below are deliberate, and each has a reason.

## Summary

| Akka | ankka | Why |
|---|---|---|
| `@Component` and classpath scanning | explicit `register(...)` | A missing component fails at startup, not at its first request. |
| `Entity::method` lambda inspection | typed handles declared on a companion | No reflection; the compiler checks every call site. |
| `@FunctionTool` and reflection | the `FunctionTool` builder | A tool's schema and its argument decoder come from one instance and cannot disagree. |
| A bespoke SQL-like view query language | real SQL over a JSON row column | Nothing to learn or parse, strictly more expressive, and indexes are explicit. |
| Route order decides dispatch | literal segments outrank parameters | `/users/me` works whether it is declared before or after `/users/{id}`. |
| An ACL by absent annotation | an abstract `acl` every endpoint must define | An unstated ACL is a decision nobody made. |
| `BACKOFFICE` among the callers an ACL can name | no equivalent | There is no backoffice proxy to be the caller. |
| `budget_tokens` and `temperature` | `effort` and adaptive thinking | Current Claude models reject both. |
| `apply -f service.yaml` | `apply -f service.json` | The descriptor has the same shape; JSON avoids a YAML parser in the CLI. |
| `minInstances` defaults to 3 | defaults to 1 | One is what you want while trying the platform out. Set three for production. |
| `AutonomousAgent` declared by a `definition()` on the instance | a `definition` on the companion, tools on the instance | The definition is checked at registration; the tools need the instance's component client. |
| A task type's name is the Java field's | `Task.named("wire-name")` | A task type's name is written into every task of it: renaming code must not orphan stored tasks. |
| Four service lifecycle states | eight | `NotDeployed`, `Paused`, `Failed` and `Suspended` are distinctions four states cannot express. |

## Registration is explicit

Akka finds components by scanning the classpath for annotations. In ankka a service is the list of
components handed to its builder:

```scala
Ankka.service
  .register(ShoppingCartEntity.descriptor)
  .register(CartRows.descriptor)
  .withExtension(HttpServer.of(clients => ShoppingCartEndpoint(clients.componentClient)))
  .start()
```

The service's contents are a value that can be read, diffed and tested, and a component that was never
registered does not exist, rather than failing on the first request that reaches for it.

## Handlers are declared with a wire name

Akka identifies a handler by inspecting the bytecode of a method reference. ankka declares each handler on
the component's companion, with its wire name:

```scala
val addItem = command("add-item")(_.addItem)
val getCart = query("get-cart")(_.getCart)
```

The wire name is what callers, persisted timers and in-flight requests address, so renaming the Scala method
changes nothing on the wire. `query` accepts only a read-only effect, which makes "this handler cannot
persist" a compiler guarantee. See [Handlers and wire names](../concepts/wire-names.md).

## Tools are built, not annotated

A `FunctionTool` is declared with a builder whose parameter types produce both the JSON Schema the model sees
and the decoder that reads the model's arguments. A parameter cannot be described as an integer and read as a
string, and a mismatch between the parameters and the handler is a compile error.

## Views are queried with SQL

Akka views have their own query language. An ankka view stores each row as JSON in a Postgres table and is
queried with SQL fragments over that JSON, through the view client. There is no parser to learn, any
condition Postgres can express is available, and an index is something you create rather than something the
platform infers.

## Literal path segments win

In Akka's HTTP endpoints, which of two matching routes handles a request can depend on declaration order.
In ankka a literal segment always outranks a parameter, so `/carts/summary` reaches its own route even when
`/carts/{cartId}` was declared first.

## Every endpoint states its ACL

Akka denies access when an endpoint has no ACL annotation, which is safe but silent. An ankka endpoint must
define `acl`; `Acl.DenyAll`, `Acl.AllowAll`, a predicate or an authenticator. Nobody ships an endpoint without
having decided who can reach it. The Python SDK requires the same attribute, and an endpoint that omits it
fails when its class is defined.

A route can state an ACL of its own — `withAcl` in Scala, an `acl` argument to the route decorator in
Python — which replaces the endpoint's for that route exactly as Akka's method-level annotation replaces
its class's.

## Callers are named by certificate

Akka's ACL principals name who is calling — the internet, a named service, any service, the service
itself — and they are trustworthy because the platform terminates mutual TLS and the identity cannot be
forged. ankka does the same: every connection inside a cluster is mutual TLS with a certificate the
installation issued for exactly one workload, and the caller is read from it.

| Akka | ankka |
|---|---|
| `@Acl(allow = @Acl.Matcher(principal = INTERNET))` | `Acl.allowCallers(Callers.internet)` |
| `@Acl(allow = @Acl.Matcher(service = "orders"))` | `Acl.allowCallers(Callers.service("orders"))` |
| `@Acl(allow = @Acl.Matcher(service = "*"))` | `Acl.allowCallers(Callers.anyInProject)`, for this project's services |
| `@Acl(allow = @Acl.Matcher(principal = SELF))` | `Acl.allowCallers(Callers.self)` |
| `@Acl(allow = @Acl.Matcher(principal = BACKOFFICE))` | none; there is no backoffice proxy |

Two differences. ankka names a service in another project explicitly, `Callers.service("billing",
"invoices")`, because projects are ankka's unit of tenancy. And outside a cluster every caller is the
local machine, which every caller-naming ACL admits; Akka instead offers switches that disable ACLs
locally. A test names a caller through the test kit rather than a header anyone could send. See
[HTTP endpoints](../build/http-endpoints.md#name-who-may-call).

In one respect ankka's model is the richer: `AuthDecision` distinguishes "log in" from "you may not" from
"the check could not be made", where Akka's ACL has a single refusal.

## Model settings follow current models

Akka's agent configuration exposes a thinking token budget and a temperature. Current Claude models refuse
both, so ankka's Anthropic provider uses effort and adaptive thinking instead.

## Descriptors are JSON

The service descriptor has the same shape as Akka's, and is JSON rather than YAML. See
[Service descriptor](service-descriptor.md).

## One instance by default

Akka's platform defaults a service to three instances, which suits a managed production cluster. ankka
defaults to one, because the platform is as often a laptop or a development cluster. Three is still the
right number for production: the instances form one cluster, and an odd count is what lets a majority survive
a network partition.

## More lifecycle states

Akka reports `Ready`, `UpdateInProgress`, `PartiallyReady` and `Unavailable`. ankka adds `NotDeployed`,
`Paused` for a service its members stopped, `Failed` for a rollout or provisioning that gave up, and
`Suspended` for a service stopped because its organization was disabled. See
[Service lifecycle states](lifecycle-states.md).

## Autonomous agents

An autonomous agent's definition — its description, instructions, guardrails, model, the task types it
accepts and their budgets — is declared on its companion and checked when it is registered, so a missing
description or a type accepted twice fails the service at startup. Its tools are declared on the instance,
which is what holds the component client a tool calls other components through.

Three behaviours are stated rather than left to be discovered:

- **Tools run at least once.** A recorded model response is never asked for again, but a tool whose result
  had not been recorded when the process stopped runs again when the task resumes.
- **A task can be cancelled from outside**, and one being worked stops at the end of the iteration in
  progress.
- **Terminating an instance hands its tasks back.** They return to pending, unassigned, for another
  instance to take, rather than failing.

## What Akka has that ankka does not

Multi-region replication, multi-table views, view rebuild on deploy, and autoscaling are among the
capabilities ankka does not have. Autonomous agents do not yet delegate subtasks, hand tasks on, lead teams
or moderate conversations, and have no MCP tools. See [Limitations](limitations.md).
