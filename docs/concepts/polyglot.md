---
title: Services in other languages
description: How ankka hosts a service written in Python — the runtime runs beside the process as a sidecar, owning everything durable and distributed, while the process decides what each command does.
kind: concept
languages: [python]
related: [get-started/first-service-python.md, reference/python-sdk.md, reference/sidecar-protocol.md, contributing/language-sdks.md]
---

# Services in other languages

A service written in Python is hosted by the same runtime as one written in Scala, with the same
components, journal, cluster, deployment and console. What differs is where the code runs. A Scala
service compiles into one JVM with the runtime. A Python service runs as its own process, and the ankka
runtime runs beside it as a **sidecar**, booted from a conversation with that process instead of from a
Scala builder.

## Who owns what

The split follows the idea every ankka component is built on: a handler decides what should happen, and
the runtime makes it happen.

| The sidecar owns | The process owns |
|---|---|
| sharding, the journal, snapshots and durable state | given this command and this state, which events or new state and which reply |
| projections for views and consumers, and the rows they write | what an event does to a view's row |
| timers and when they fire | what a timed action does when it fires |
| the workflow engine: transitions, timeouts, retries, failover | what each workflow step does and where it goes next |
| the agent loop: model calls, session memory, compaction, token counts, the model's key | an agent's plan, its tools and its guardrails |
| HTTP: binding the port, ACLs, routing | what each route does |
| cluster formation, readiness and observability | nothing |

Because the process holds no durable state, it can be restarted, redeployed or crash without losing
anything. An entity whose process is briefly unavailable is re-opened when the process returns; callers
waiting at that moment are told the service is unavailable and the component client retries such a
refusal briefly, so a rolling replacement refuses nothing.

## The protocol

The two speak a protobuf protocol over gRPC, on loopback inside one pod. Your code never sees it; the SDK
does. The process listens on port 9010 and answers the sidecar's questions: handle this command, apply
this event, run this step, plan this agent request. The sidecar listens on port 9011 for the process's
own calls to other components, views and timers, which is what the SDK's component client uses. Both
ports are bound to loopback, so nothing outside the pod can reach either.

When the sidecar starts, it asks the process to **discover** itself: which components it hosts, their
handlers and wire names, which are read-only, which stream, the settings the engine must enforce for a
workflow, the tools an agent offers, and the HTTP routes and ACLs of its endpoints. The sidecar validates
all of it and reports every problem at once, both in its own log and back to the process, so a service
with four mistakes takes one restart to fix, not four. It then hosts what was described. The sidecar
waits up to 60 seconds for the process to answer discovery before giving up.

The protocol is versioned `MAJOR.MINOR`, and a process-hosted service declares the version its SDK
speaks. The platform accepts a declaration with the same major version and a minor version no higher
than its own. See [Sidecar protocol](../reference/sidecar-protocol.md).

## One journal, two languages

Stored data does not record which language wrote it. The Python SDK's default encoding produces the same
JSON the Scala SDK does: records with every field present, sum types with a `"type"` discriminator
naming the case, `null` for an absent optional value, and primitives such as a string or an integer as
plain text. The mapping is written down in the protocol's
[ENCODING.md](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/ENCODING.md) and checked by
shared fixtures that every SDK must pass.

So a cart written by a Scala service can be read by a Python service on the same database, and the
reverse. Field names are the contract. A Python dataclass that stores a field as `product_id` does not
read a journal that says `productId`, which is why the Python samples use the stored spelling.

## Deployed, a service is two containers

A descriptor says a service is process-hosted in two fields:

```json title="service.json"
{ "name": "cart", "service": { "image": "my-cart:1.0.0", "hosting": "process", "protocol": "1.0" } }
```

The image holds only your process. The platform adds the sidecar, at the version that matches the
platform, and the descriptor cannot name its image or set its variables.

- **The sidecar carries the service's outward face**: its HTTP and management ports, the readiness
  probe, the database credential and the cluster membership. Scaling, restarting, exposing and pausing
  behave exactly as for a Scala service.
- **The app container has no ports and no probe**, and small fixed resources.
- **The descriptor's environment is split by name.** Variables starting `ANTHROPIC_`, `ANKKA_MODEL_` or
  `ANKKA_DB_` go to the sidecar; everything else goes to your process. A model key is therefore supplied
  in the descriptor as usual, and your process never sees it or the database.
- **A producing consumer or a topic-sourced view needs a broker.** Set `ANKKA_KAFKA_BOOTSTRAP_SERVERS`;
  the sidecar refuses to start without it and names the component that needs it.

## What makes an SDK compatible

A second language is compatible when it behaves the same, not when it looks the same. ankka defines that
with a conformance suite: one case per behaviour, from "a deleted entity written to again starts empty"
to "a query answered while a workflow step runs", run against the Scala reference in-process and against
any process through the real sidecar. The Python SDK passes it, and a third language arrives by passing
it with its own reference service, plus the encoding fixtures. The platform needs no change to host it.
See [Adding a language SDK](../contributing/language-sdks.md).
