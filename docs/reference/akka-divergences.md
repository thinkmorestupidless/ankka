---
title: Divergences from Akka
description: Where ankka deliberately differs from Akka's SDK and platform — registration, handler identity, tools, views, routing, ACLs, model settings, descriptors, defaults and lifecycle states — and why.
kind: reference
related: [concepts/components.md, concepts/wire-names.md, reference/limitations.md]
---

# Divergences from Akka

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
| Principals naming the caller — the internet, a named service, self | a predicate over the request, or a credential the service verifies | Without mutual TLS there is nothing to name a caller with, and a header the caller sets is not evidence. |
| `budget_tokens` and `temperature` | `effort` and adaptive thinking | Current Claude models reject both. |
| `apply -f service.yaml` | `apply -f service.json` | The descriptor has the same shape; JSON avoids a YAML parser in the CLI. |
| `minInstances` defaults to 3 | defaults to 1 | One is what you want while trying the platform out. Set three for production. |
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

## ACLs name what the request carries, not who is calling

Most of Akka's ACL vocabulary names the caller: the internet, a specific deployed service, any service, the
service itself, a backoffice proxy. Those principals are trustworthy on Akka because the platform terminates
mutual TLS and guarantees the identity cannot be forged. ankka has none of them, because it has none of that
machinery: there is no mesh, no workload identity and no service-to-service invocation, and a service's
in-cluster address is reachable from every namespace. Inventing the vocabulary anyway would mean deciding who
a caller is from a header the caller sets, which is not a security control.

So ankka's ACLs are the two honest kinds. `Acl.AllowIf` is a predicate over the request as it arrived, and
`Acl.Authenticate` verifies a credential — a signed token, a client certificate — that a service can check
for itself. What Akka expresses as `@Acl(allow = @Acl.Matcher(service = "shopping-cart"))` has no ankka
spelling, and will not until the platform can establish identity; it is recorded in
[Limitations](limitations.md). In one respect ankka's is the richer model: `AuthDecision` distinguishes "log
in" from "you may not" from "the check could not be made", where Akka's ACL has a single refusal.

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

## What Akka has that ankka does not

Multi-region replication, multi-table views, view rebuild on deploy, and autoscaling are among the
capabilities ankka does not have. See [Limitations](limitations.md).
