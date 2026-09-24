---
name: ankka-design
description: Design or review the architecture of an ankka application before writing components — decompose a domain into event sourced and key value entities, views, consumers, workflows, timers, agents and endpoints; decide where each rule lives, what may be eventually consistent, where service boundaries go, and how events, wire names and manifests evolve. Use when a task asks how to structure, model, architect or plan an ankka service, which component to use for something, or whether a design is sound.
---

# Designing an ankka application

The component kinds are fixed, so design is deciding which things are entities, what each guarantees,
how the rest of the system learns of their changes, and what may lag. Work through the questions below
in order, write the answers down as a table of requirement → component → why (the worked checkout
example in `references/concepts/designing-services.md` is the shape), and only then write code.

## The decision procedure

1. **List the rules the system must never break.** For each, ask: *which one thing with an id can
   enforce it?* A rule about one cart is the cart entity's. A rule spanning an order and a customer
   cannot be enforced by one handler and becomes a workflow with a reserving step and a confirming step.
   The entity is the only place in ankka where a check and the change it guards are atomic.
2. **Size entities by their rules, not their nouns.** Too big (one entity for all stock) serialises
   unrelated changes through one queue and becomes the throughput ceiling. Too small cannot enforce the
   rule that justified it. Never keep an unbounded list in an entity to make listing easy; listing is a
   view's job.
3. **Event sourced or key value?** Event sourced when the history matters: anything reacts to individual
   changes, a view counts or sums, an audit trail has value, or you may want read models later. Key value
   when only the latest value matters and nobody reacts to how it got there. When unsure, event sourced;
   a key value entity's past is gone. Name events in the past tense after what happened in the domain,
   and put everything needed to apply them into them, including anything non-repeatable.
4. **Every other question is a view.** An entity answers about itself, by id. "All orders for a customer",
   "carts containing product X" are views: one row per source id, queried by attributes, eventually
   consistent. Shape one view per screen or API; views are cheap. A view reads exactly one source; it
   cannot join two. A handful of ids the caller already holds is not a view but a fan-out of entity
   queries with `invokeAsync`.
5. **Every reaction is a consumer.** "When an order ships, email the customer / tell the warehouse" is a
   consumer over the source's events, delivered at least once, so the reaction must be safe to repeat. A
   multi-step reaction, or one that must be undone, is a consumer that starts a workflow under an id
   derived from the source.
6. **Every process spanning entities or leaving the service is a workflow.** Each step's transition is
   journalled before the next runs, so a crash resumes rather than restarts. Every step may run twice:
   pass the workflow id as an idempotency key to anything external. Recovery (timeouts, retries, the
   failover step that compensates) is declared in settings, not improvised in steps. Retries default to
   zero because the runtime cannot know a step is safe to repeat.
7. **Every deadline is a timer or a paused step.** A deadline that belongs to one workflow instance is
   `thenPause(after, onTimeout)`. A deadline on something that is not a workflow is a named timer calling a
   timed action, whose handler checks current state and reports *done* when there is nothing left to do;
   an error reschedules it forever.
8. **A decision that needs a model is an agent; a fixed sequence is a workflow.** Give agents read-only
   tools where possible; a writing tool calls an entity command so the entity's rules still apply. Put
   multi-agent coordination in a workflow when it costs money or takes minutes. Choose session ids for
   the memory you want (per chat, or per workflow instance). `references/concepts/designing-agents.md`
   has the full set of agent design questions.
9. **The edge is an endpoint with an ACL.** Endpoints are thin: domain validation lives in the entity,
   whose refusal becomes the HTTP status with no mapping. Cross-entity checks that only catch mistakes
   belong in the endpoint; checks that must hold belong in an entity or workflow. Different audiences
   get different endpoints, because each endpoint has one ACL.
10. **Draw service boundaries where ownership, scaling or consistency differ.** Start with fewer, larger
    services; splitting later means publishing what was internal. Across services there is no component
    client, only HTTP and topics, and everything is eventually consistent. Each service owns its
    database. Publish a stable message type from a consumer, never the entity's own events.

## Consistency, stated precisely

- One entity, one writer, one command at a time. Within an entity, no races.
- A reply is sent after the events are persisted; a view catches up afterwards with no bound. Read your
  own write from the entity. Never make a decision that must be exact on view data.
- Views over an entity's events are exactly once; consumers, key value sources and topics are at least
  once. Ordering holds per source id only.
- A timed-out call may still have happened. A retried command must be safe to receive twice.
- Wire names, component ids, manifests and JSON field names are protocol. Add fields and event cases;
  never rename or remove what a journal already holds. During a rolling update two versions run at once.

## Reviewing a design

Refuse a design that has any of these, and say which rule it breaks:

- An entity handler that calls another component, reads a clock into the fold, or holds a growing list.
- A rule enforced by reading a view, or a "check then act" across two entities outside a workflow.
- A consumer or timed action whose reaction is not safe to repeat, or that errors on "nothing to do".
- A workflow step with retries on a call that is not idempotent, or a failover step that expects an input.
- Two services sharing a database, or one service reading another's tables or events.
- An endpoint with domain validation in it, or an `AllowAll` ACL nobody chose deliberately.
- An agent whose tools write to storage directly, or a shared session id that several unrelated features
  address.
- A stored type renamed, a field removed, or a wire name changed on a service that has been deployed.

Before proposing anything ankka does not do, read `references/reference/limitations.md`; and when an
Akka habit does not fit, `references/reference/akka-divergences.md` says what ankka does instead.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Concepts

- `references/concepts/architecture.md` — The parts of ankka and how they fit together — components and the runtime inside a service, the control plane and operator that deploy it, the sidecar for other languages, and where every piece of state lives.
- `references/concepts/effects.md` — Why every ankka handler returns a description of what should happen instead of doing it, what each component's effects look like, and why a refusal is a returned value rather than an exception.
- `references/concepts/components.md` — The component kinds an ankka service is built from, what each is for, how the runtime hosts it, and how to choose between them.
- `references/concepts/designing-services.md` — The concepts that shape an ankka system — consistency boundaries, events, read models, reactions, processes, time, agents, the edge and service boundaries — with a worked example mapping a checkout onto components.
- `references/concepts/wire-names.md` — How ankka names components, handlers and stored types independently of your code's names, why those names are a versioning boundary, and which renames are safe in a running system.
- `references/concepts/consistency.md` — The guarantees ankka gives — strong consistency per entity, eventually consistent views, exactly-once and at-least-once delivery, ordering, timeouts and timers — and what each means for the code you write.
- `references/concepts/designing-agents.md` — Decide when an agent is the right component, design its tools, sessions, guardrails and model choice, plan for failure and cost, put a person in the loop, and combine agents with workflows and entities.
- `references/concepts/clustering.md` — How a service's instances form one cluster, how entities are spread across it, how nodes find each other locally and in Kubernetes, and what that means for rollouts, failures and instance counts.
- `references/concepts/observability.md` — What ankka records about every request — spans, traces, unattributed time, token usage — where you can read it locally and in a cluster, and what it deliberately does not do.

### Build

- `references/build/component-client.md` — Call entities, workflows and agents through the component client — blocking or asynchronous, with typed refusals and timeouts — and query views through the view client.
- `references/build/serialization.md` — How ankka encodes state, events, arguments and messages as JSON under a named manifest, what the JSON looks like in both languages, and how to change a stored type without breaking a journal.

### Reference

- `references/reference/akka-divergences.md` — Where ankka deliberately differs from Akka's SDK and platform — registration, handler identity, tools, views, routing, ACLs, model settings, descriptors, defaults and lifecycle states — and why.
- `references/reference/limitations.md` — What ankka does not do yet, stated plainly and grouped — the platform, networking and security, observability, components, and SDKs and releases — so you can plan around a gap before you reach it.
