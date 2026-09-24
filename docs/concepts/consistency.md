---
title: Consistency and delivery
description: The guarantees ankka gives — strong consistency per entity, eventually consistent views, exactly-once and at-least-once delivery, ordering, timeouts and timers — and what each means for the code you write.
kind: concept
languages: [scala, python]
components: [event-sourced-entity, key-value-entity, view, consumer, workflow, timed-action]
related: [concepts/designing-services.md, build/views.md, build/consumers.md, build/topics.md, concepts/clustering.md]
---

# Consistency and delivery

ankka is strongly consistent within one entity and eventually consistent everywhere else. An entity sees
every command in order, one at a time, against its current state. Views and consumers see an entity's
changes shortly after they happen, in order per entity, exactly once or at least once depending on the
source. This page states each guarantee and what it asks of your code.

## One entity, one writer, one command at a time

Every entity id — of an event sourced entity, a key value entity, a workflow, or an agent session — is
hosted by exactly one instance of the service at a time. Commands to that id are processed one after
another: a handler sees the state left by the previous command, and its effect is applied before the next
command is read.

So a check and the change it guards are atomic within one entity, with no locks and no retries on
conflict. A command that reads the entity after another command succeeded always sees that command's
effect. Reading an entity by id is therefore strongly consistent, and the right way to read your own write.

The price is that one entity's throughput is one command at a time. Many entities run in parallel across
the instances of the service; one very busy entity does not. [Designing a service](designing-services.md)
covers sizing entities.

## The journal is the source of truth

An event sourced entity's events are written to the journal in Postgres before the command's reply is
sent. The entity's state in memory is a cache of the fold of those events. An idle entity is dropped from
memory after two minutes, and an instance that stops or fails loses its entities; either way the next
command to that id rebuilds the state by replaying the journal, starting from the latest snapshot. By
default an event sourced entity is snapshotted every 100 events, so a rebuild replays at most that many.

The same holds for the other stateful kinds. A key value entity's value is in the durable state table, a
workflow's progress is in its journal, and an agent session's conversation is an entity's events. Nothing
that matters lives only in an instance's memory.

## A timed-out call may still have happened

A call through the component client waits for a reply for ten seconds by default (`ankka.ask-timeout`) and
then fails with the `Timeout` error code. A timeout means the caller stopped waiting, not that the command
was not applied: the entity may have persisted its events a moment later. Timeouts and `Unavailable` are
the two error codes a caller can reasonably retry, and a retry of a command that did happen must be safe.
Make commands idempotent where a retry is likely — for example by carrying an id the entity can recognise
as already handled.

## Views are eventually consistent

A view is maintained from its source's changes by a projection that runs in the background. A change is
reflected in the view shortly after it is persisted, usually well within a second, but not before the
command's reply is sent. So:

- After a successful command, the entity reflects the change immediately; the view may not yet.
- A view query can return a row that is slightly behind its entity.
- A view is the right place to list and search, and the wrong place to check a rule that must hold.

## Delivery to views and consumers

How often a view or consumer sees each change depends on its source:

| Source | Views | Consumers |
|---|---|---|
| An event sourced entity's events | exactly once | at least once |
| A key value entity's state changes | at least once | at least once |
| A broker topic | at least once | at least once |

A view over an event sourced entity gets **exactly-once** delivery because the row write and the record of
how far the projection has read happen in one Postgres transaction: either both happen or neither does.
Everywhere else the offset is recorded after the handler has run, so a crash between the two delivers the
change again. A handler that is delivered a change at least once must produce the same result the second
time:

- A view handler that sets a row from a key value entity's new value is naturally repeatable.
- A consumer that calls a command should call one the target can recognise as already done.
- A consumer that produces to a topic may produce a duplicate, and the receiving side must tolerate it.

A view or consumer over a key value entity sees the entity's state as changes arrive. It is not a history,
so it may not see every intermediate value when several changes happen close together. Use an event sourced
entity when each individual change matters to what reacts to it.

## Ordering

Changes from one entity reach a view or consumer in the order they happened. Changes from different
entities have no defined order relative to each other.

Messages from a broker topic keep their order per key. ankka keys each published message by its subject,
the id of the entity it is about, so every message about one entity lands on the same partition and is
delivered in order. A topic source cannot be rebuilt from history: a component reading one sees only what
was published after it started consuming, because a broker's retention is not an event journal.
[Broker topics](../build/topics.md) covers the details.

## Workflows

A workflow records each transition — its new state and the next step — before that step runs. After a
crash, the workflow resumes at the step it was on. A step that had already acted but whose outcome was not
yet recorded runs again, so each step's calls must be safe to repeat, for example by passing the workflow id
as an idempotency key to an external system.

A step that throws is retried as the workflow's settings declare, then failed over to the declared recovery
step. A step that returns a failure on purpose ends the workflow without retry.
[Workflows](../build/workflows.md) shows how to declare both.

## Timers

A timer is stored in the database when it is scheduled and survives any restart. When it is due, one
instance of the service calls its timed action. The call is made **at least once**: a handler that returns
an error, throws, or cannot be reached is retried with backoff, and the timer is deleted only when the
handler reports success. So a timed action must check the current state before acting, and report success
when there is nothing left to do — returning an error for work that no longer applies retries it forever.

## Agents

An agent session is handled one request at a time, like an entity. Two requests to the same session never
interleave, so each turn sees the whole conversation so far and appends to it. Different sessions run in
parallel. A streaming response holds its session until the stream ends.
