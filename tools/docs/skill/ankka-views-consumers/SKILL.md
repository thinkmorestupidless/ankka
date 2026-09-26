---
name: ankka-views-consumers
description: Build the read side and the reactions of an ankka service in Scala, Python or TypeScript — a view that projects an entity's or a topic's changes into a queryable table (one row per source id, SQL queries with jsonText/jsonNumber, tombstones), a consumer that reacts to each change by calling components or publishing to a Kafka topic, and the CloudEvents framing, ordering and at-least-once rules of broker topics. Use when the task names a view, a row, a projection, a consumer, ChangeSource, a topic, Kafka, ProjectionRuntime, or "find all X where".
pages:
  - build/views.md
  - build/consumers.md
  - build/topics.md
  - concepts/consistency.md
  - build/serialization.md
  - build/testing.md
---

# ankka views and consumers

An entity answers questions about itself, by id. Every other question ("which carts contain this
product", "the ten largest accounts") is a **view**: a table with one row per source id, maintained from
the source's changes and queried by attributes. Anything that should *happen* because something changed
is a **consumer**: it calls other components or publishes to a topic. Both read one source in order and
run only in a service with the projection runtime registered.

## Rules

1. **One source, one table, one row per source id.** The row key is always the source entity's id.
   Re-keying by an attribute silently orphans the old row when the attribute changes; every other way
   of finding rows is a query. Joining two sources in one view is not supported: project each and combine
   where they are read.
2. **Build the new row from the current one.** The handler sees one change and `rowState`
   (`self.row`); the row is its only memory. Return `updateRow(row)`, `deleteRow()` or `ignore()`.
3. **A view is eventually consistent; an entity is not.** A reply is sent when the events are persisted
   and the view catches up shortly after, with no bound. Read your own write from the entity; use a view
   to find things; never make a decision that must be exact on view data. In a test, poll for the
   expected row until a deadline: that is the consistency model, not a workaround.
4. **Delivery depends on the source.** Events of an event sourced entity: every event, in order, exactly
   once (the row and the offset commit in one transaction). State of a key value entity: the latest value,
   with intermediate values skippable. A topic: at least once. A consumer is at least once from every
   source, because its offset is recorded only after the handler returns.
5. **Failure is not an effect for a consumer.** A handler that throws does not advance, and the change is
   redelivered. That is the retry mechanism, and it is also why a handler that fails the same way forever
   stops the consumer at that change. Return `done()` or `ignore()` for a change that needs nothing.
6. **Make every reaction safe to repeat.** Make the target idempotent (set, do not add); key the effect by
   the source id and, in Scala, `messageContext.sequenceNumber`; or let the receiver deduplicate on what
   the message carries. Never deduplicate on the CloudEvents `ce-id`: it is regenerated on every publish.
7. **Publish a stable message type, not the entity's events.** A consumer that produces to a topic
   declares its own output type and serializer (`produceTo` + `outputSerializer` in Scala,
   `produces_to` + `out_codec` in Python, `producesTo` + `out` in TypeScript; one without the other is refused) so the domain's events can
   change without breaking listeners.
8. **Register `ProjectionRuntime()`** (Scala) or every command succeeds and every view stays empty
   forever. A topic source or output also needs a broker: `ProjectionRuntime.withKafka(servers)`, or
   `ANKKA_KAFKA_BOOTSTRAP_SERVERS` in a Python or TypeScript service's descriptor `env`. A component that needs a broker
   in a service with none is refused at startup.

## Before writing a view

- **Which single question does this view answer?** Shape one view per screen or API query; several small
  views over the same events beat one general view queried in complicated ways. Views are cheap.
- **What happens when the source is deleted?** The default removes the row. Override `onDelete`
  (`on_delete`) to keep a tombstone when the row outlives the entity, as an order history keeps a
  checked-out cart.
- **Is the source rebuildable?** A view over an entity's events can be replayed from the journal; a view
  over a topic sees only what is published after it starts and cannot be rebuilt. A view does not rebuild
  rows when its code changes: a changed handler applies from then on.
- **Which fields will queries filter or order on?** In Scala, queries are SQL over the row's JSON with
  `sql"…"` and `jsonText("field")`, `jsonNumber("total")`, `jsonContains("members", "x")`; values are
  bound parameters, never spliced. `jsonText` compares as text, so a boolean compares against `"true"`
  and a number needs `jsonNumber`. A hot query needs a Postgres expression index on the same term. In
  Python and TypeScript a view answers only `get(key)` and `all()`.

## Before writing a consumer

- **What does it do, and is that harmless twice?** If not, restructure until it is (see rule 6).
- **From which source kind?** Anything that must react to *every* change needs an event sourced source;
  a key value source may skip intermediate values.
- **Does the target have its own rules?** A consumer that changes something should call an entity
  command, so the entity decides whether the change is allowed.
- **Where does the client come from?** In Scala the context passed to the companion's `create`; in
  Python `self.client`, in TypeScript `this.client`. Consumers run on virtual threads, so a blocking `invoke` in `onMessage` is fine.

## Topics

Messages are CloudEvents in binary mode: a plain JSON body with `ce-*` Kafka headers. `ce-subject` is the
source entity's id and the Kafka record key, so every message about one entity lands on one partition in
order; messages about different entities have no order relative to each other. A view over a topic keys
rows by `ce-subject`, falling back to the record key, and skips a message with neither. Set other
attributes with `effects.produce(message, metadata)`. One consumer group per component; instances share
partitions with no ankka configuration.

## Testing

Scala views and consumers that call components run under the integration testkit against a real
projection and database; `InMemoryBroker` exercises the whole topic path with no Kafka, and
`InMemoryPublisher` captures what a consumer published. Python and TypeScript have `ViewTestKit` and
`ConsumerTestKit` that need no sidecar. Poll for rows; never read once.

## Mistakes to check for

- Asserting a view row right after a command, with no polling.
- A consumer that returns `error` or throws for "nothing to do", and so stalls forever.
- A view keyed by anything other than the source id, or "joining" a second source in a handler.
- Publishing the entity's own event type to a topic other services read.
- A view or consumer registered with no `ProjectionRuntime`, or a topic with no broker.
