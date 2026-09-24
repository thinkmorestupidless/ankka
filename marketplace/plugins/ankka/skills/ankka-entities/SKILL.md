---
name: ankka-entities
description: Write, change or test an ankka entity in Scala or Python — an event sourced entity (events, event handler, commands, queries, snapshots, deletion, expiry) or a key value entity (updateState) — including its serializers and manifests, the wire names of its handlers, how it is called through the component client, and how to change a stored event or state type without breaking the journal. Use when the task names an entity, an event, a command handler, currentState, applyEvent, a Codecs.serializer, or a manifest.
---

# ankka entities

An entity is one thing with an id. The runtime hosts each id in exactly one place in the service's
cluster and hands it one command at a time, so a handler is sequential code with no locks and the entity
is the only place in ankka where a check and the change it guards are atomic. An event sourced entity
keeps its history as events and folds them into state; a key value entity keeps only the latest value.

## Rules

1. **A command handler decides; it does not act.** It reads `currentState` (`self.state` in Python) and
   the argument, and returns an effect: `effects.persist(event).thenReply(...)` or
   `effects.error(message, code)`. It never calls another component, reads a clock it needs to store, or
   touches anything outside the entity. Cross-entity work belongs in a workflow, consumer or endpoint.
2. **The event handler is pure and is the only place state changes.** `applyEvent` runs on first
   persistence and again on every replay. Anything non-repeatable (a timestamp, a generated id) goes
   *into* the event when the command handler creates it, never computed in the fold.
3. **Refuse before persisting.** Check every rule first and return `effects.error`; nothing is persisted
   by a refusal. All events of one effect are persisted together before the reply. Use the error code
   the caller needs: `Conflict` for a state that forbids the command, `NotFound` for a missing part,
   the default `BadRequest` for a bad argument. An endpoint turns the code into the HTTP status with no
   mapping of its own.
4. **`query` for read-only handlers, `command` for the rest.** A query must return a `ReadOnlyEffect`
   (`effects.reply`), so it cannot persist; the compiler enforces it in Scala and registration in Python.
5. **Name events in the past tense after what happened** (`ItemAdded`, `PaymentAuthorised`), not after
   the command. They are a closed set: a Scala `enum` or a union of frozen Python dataclasses.
6. **Every stored or transmitted type has a serializer with a manifest you choose.** In Scala,
   `Codecs.serializer[A]("manifest")`; in Python, `json_codec(A, "manifest")`. Handler arguments and
   replies need one too: primitives and `Done` come from `Serializers.given`; any other type needs a
   `given` declared in the companion *before* the handlers that use it (object initialisation runs in
   order). A manifest is a name you keep forever.
7. **Change stored types by adding, never by altering.** Safe: a new optional field, a field with a
   default, a new event case. Breaking: renaming or removing a field or case, changing a type, changing
   a manifest. When a breaking change seems necessary, add a new event case and keep the old one forever.
8. **Field names are the JSON.** A Scala field `productId` and a Python field `productId` read one
   journal; `product_id` does not. Keep names identical across languages.
9. **Wire names are protocol.** `val addItem = command("add-item")(_.addItem)`: rename the method freely,
   never the string of a deployed service.
10. **Register the entity** on the service builder (`.register(ShoppingCartEntity.descriptor)` in Scala,
    `.register(ShoppingCartEntity)` in Python). Unregistered means nonexistent.

## Before writing an entity

- **Which rule does this entity enforce, and is it about exactly one thing with one id?** Size entities by
  their rules, not their nouns. A single "all products" entity serialises the whole shop; a per-product
  stock entity serialises only that product.
- **Does the history matter?** If anything downstream reacts to individual changes, a view counts or
  sums, or an audit trail has value: event sourced. If only the latest value matters (preferences, a
  cache): key value. When unsure, event sourced; past values of a key value entity are gone.
- **What is the id?** It is the addressing key and the unit of ordering. After a deletion the id starts
  again from the empty state, so an id that can be reused by accident (an email address) is a poor
  choice where that matters; a generated order id is better.
- **Is anything in the state unbounded?** Never keep a growing list in an entity to make listing easy.
  Listing is a view's job.
- **What does each command reply with?** `thenReply(state => value)` computes from the state *after* the
  events apply; `thenReplyState` returns the whole state; `thenNoReply` returns nothing.

## Deletion, expiry and snapshots

- `effects.persist(CheckedOut).deleteEntity()` persists the final event *before* deleting, so a view or
  consumer downstream sees what happened rather than a vanished entity. Prefer that to a bare delete.
- `.expireAfter(duration)` is the same deletion, deferred until the entity has been idle that long.
- Snapshots change performance, never behaviour: every 100 events by default in Scala
  (`override def snapshotEvery`), never by default in Python (`snapshot_every`).

## Calling an entity

From an endpoint, workflow step, consumer, timed action or agent tool, through the component client:
`componentClient.forEventSourcedEntity(EntityId("c1")).call(ShoppingCartEntity.addItem).invoke(item)`.
`invoke` blocks and that is free on ankka's virtual threads; `invokeAsync` fans out. A refusal arrives as
a `CommandError` carrying the code, never as a default value. A `Timeout` says the reply did not arrive,
not that the command did not happen, so a retried command must be safe to receive twice. An entity that
has never been written exists with its empty state, so a read is never "not found" at the client.

## Testing

`EventSourcedTestKit.of(ShoppingCartEntity, "cart-1")` and `KeyValueEntityTestKit` (Python:
`EventSourcedTestKit`, `KeyValueTestKit`) run one handler with no runtime: assert on `result.events`,
`result.replyValue` and the new state. Arguments and replies still round-trip through the entity's own
serializers, so a missing codec fails here rather than on first deployment. Prove durability with the
integration testkit and `restartService()`, which drops every entity from memory and forces a replay.

## Mistakes to check for

- A `given Serializer` declared after the `val` that needs it: a null at object initialisation.
- Logic in `applyEvent` that belongs in the command handler, or a clock read in the fold.
- A handler that returns `effects.reply(currentState)` after mutating a `var`: state changes only
  through events (or `updateState` on a key value entity).
- Retrying a timed-out command that is not idempotent.
- A test that asserts a view reflects a write immediately: read your own write from the entity.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Concepts

- `references/concepts/effects.md` — Why every ankka handler returns a description of what should happen instead of doing it, what each component's effects look like, and why a refusal is a returned value rather than an exception.
- `references/concepts/wire-names.md` — How ankka names components, handlers and stored types independently of your code's names, why those names are a versioning boundary, and which renames are safe in a running system.
- `references/concepts/consistency.md` — The guarantees ankka gives — strong consistency per entity, eventually consistent views, exactly-once and at-least-once delivery, ordering, timeouts and timers — and what each means for the code you write.

### Build

- `references/build/event-sourced-entities.md` — Model state as a sequence of persisted events, write commands and queries that return effects, delete or expire an entity, and tune snapshots, in Scala or Python.
- `references/build/key-value-entities.md` — Store only the latest value of a piece of state, replace it with updateState, delete or expire it, and decide when that is a better fit than event sourcing.
- `references/build/component-client.md` — Call entities, workflows and agents through the component client — blocking or asynchronous, with typed refusals and timeouts — and query views through the view client.
- `references/build/serialization.md` — How ankka encodes state, events, arguments and messages as JSON under a named manifest, what the JSON looks like in both languages, and how to change a stored type without breaking a journal.
- `references/build/testing.md` — Test ankka components at two levels in Scala and Python — unit test kits that run a component with nothing else, and integration test kits that run the whole service against a real database — with scripted models for agents.
