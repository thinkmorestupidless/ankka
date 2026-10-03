---
name: ankka-views-consumers
description: Build the read side and the reactions of an ankka service in Scala, Python, TypeScript or Rust — a view that projects an entity's or a topic's changes into a queryable table (one row per source id, SQL queries with jsonText/jsonNumber, tombstones), a consumer that reacts to each change by calling components or publishing one or several messages to a Kafka topic, a graph consumer that publishes entities as graph deltas, and the CloudEvents framing, record keys, ordering and at-least-once rules of broker topics. Use when the task names a view, a row, a projection, a consumer, ChangeSource, a topic, Kafka, ProjectionRuntime, a graph, nodes and edges, graph deltas, or "find all X where".
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
   the source id and the change's sequence number (`messageContext.sequenceNumber`,
   `self.metadata.sequence_number`, `this.sequenceNumber`, `ctx.sequence()`); or let the receiver
   deduplicate on what the message carries. Never deduplicate on the CloudEvents `ce-id`: it is
   regenerated on every publish.
7. **Publish a stable message type, not the entity's events.** A consumer that produces to a topic
   declares its own output type and serializer (`produceTo` + `outputSerializer` in Scala,
   `produces_to` + `out_codec` in Python, `producesTo` + `out` in TypeScript; one without the other is refused) so the domain's events can
   change without breaking listeners.
8. **Register `ProjectionRuntime()`** (Scala) or every command succeeds and every view stays empty
   forever. A topic source or output also needs a broker: `ProjectionRuntime.withKafka(servers)`, or
   `ANKKA_KAFKA_BOOTSTRAP_SERVERS` in a Python, TypeScript or Rust service's descriptor `env`. A component
   that needs a broker in a service with none is refused at startup.
9. **Several messages for one change are one effect.** `effects.produceAll(messages)` (`produce_all` in
   Python and Rust) publishes them in order to the consumer's one topic; each may name a **record key**,
   and one that names none is keyed by its subject. The key and the subject are separate: naming a key
   never changes `ce-subject`. The change is handled when the broker has accepted all of them; if one is
   refused the change comes again and all are published again, so a reader may see a repeat and never a
   gap. An empty list is `done`. One change's messages are at most 4 MiB together.
10. **A graph is published by a graph consumer, never by hand.** `GraphConsumer` handlers return elements
    — `graph.node(…)`, `graph.edge(…)`, `graph.tombstoneNode(…)` — and each is published as a delta of
    `ankka.graph-delta.v1` under its element key (`node:<id>`, `edge:<id>`), at the change's sequence
    number or revision. The author keeps four rules nothing checks: an element is its **whole** state; an
    element has **one** writing entity; ids are global and prefixed by kind; a tombstone marks and does
    not remove. Publishing is safe to repeat as it stands. A deletion (event sourced or key value) reaches
    the deletion handler above every earlier version; an **expired** entity is not deleted and nothing is
    tombstoned.

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

## Before writing a graph consumer

- **Which entity owns each element?** One entity writes each node and edge. An element another entity
  owns is not published here: publish the edge to it and let its own entity publish the node.
- **Does the event carry the element's whole state?** If not, read the entity through the component
  client in the handler and build the element from that. The state read may be later than the event; the
  later events publish it again at their own versions and the graph converges.
- **What marks it deleted?** Override the deletion handler and return the tombstones. An element that
  leaves the state without the entity being deleted (an item removed) is tombstoned by the handler for
  that change.
- **Is the source a topic?** Then there is no sequence number and every element states its version.
- **Who creates the topic?** Not ankka. It must be compacted; declare it as a managed topic in the
  ankka-flow pipeline that reads it and deploy the pipeline before the service publishes.

## Topics

Messages are CloudEvents in binary mode: a plain JSON body with `ce-*` Kafka headers. `ce-subject` is the
source entity's id, and the Kafka record key unless a message names its own, so every message about one
entity lands on one partition in order; messages under different keys have no order relative to each
other. A view over a topic keys
rows by `ce-subject`, falling back to the record key, and skips a message with neither. Set other
attributes with `effects.produce(message, metadata)`. One consumer group per component; instances share
partitions with no ankka configuration.

## Rust differences

A view implements `View` (`Row`, `Event`, `COMPONENT_ID`, `ROW_MANIFEST`; `source()` is
`Source::of(ShoppingCart)` or `Source::topic("name")`; `on_event(row, event, ctx) -> ViewEffect<Row>` with
`view::update_row`/`delete_row`/`ignore`; `on_deleted`; `queries()`, `get` and `all` by default). A consumer
implements `Consumer` (`Message`, `source()`, `on_message(message, ctx) -> ConsumerEffect` with
`consumer::produce`/`produce_all`/`done`/`ignore`, `produces_to()`); several messages are
`consumer::produce_all([consumer::message(x).key("k"), …])`; a panic redelivers the message. A graph
consumer implements `GraphConsumer` (`Message`, `COMPONENT_ID`, `TOPIC`, `source()`,
`on_message(message, ctx) -> GraphEffect`) and returns `graph::publish([graph::node(id).label(…).property(…, …), …])`;
its elements are checked when the result is dispatched. The source's id is `ctx.entity_id()` and the
change's sequence number `ctx.sequence()`. Rows are queried with `ctx.client().query(CartRows, "all", ())`.
`ViewTestKit`, `ConsumerTestKit` and `GraphConsumerTestKit` run them natively.

## Testing

A consumer is unit tested with nothing started in every language: `ConsumerTestKit.of(Companion)` in
Scala returns the messages a change publishes with the record key a broker is given, and
`ConsumerTestKit.graph(Companion)` returns a graph consumer's deltas; Python and TypeScript have
`ConsumerTestKit` and `GraphConsumerTestKit`. Scala views run under the integration testkit against a real
projection and database; `InMemoryBroker` exercises the whole topic path with no Kafka (`failNext(topic)`
makes a publication fail), and `InMemoryPublisher` captures what a consumer published. Python and
TypeScript have a `ViewTestKit` that needs no sidecar. Poll for rows; never read once. Poll on the value that changes,
not on a row existing, since a row an earlier write left satisfies "exists"; ask of every test whether
it could pass while the behaviour is broken (`references/build/testing.md`, "A test must be able to
fail").

## Mistakes to check for

- Asserting a view row right after a command, with no polling.
- A consumer that returns `error` or throws for "nothing to do", and so stalls forever.
- A view keyed by anything other than the source id, or "joining" a second source in a handler.
- Publishing the entity's own event type to a topic other services read.
- A view or consumer registered with no `ProjectionRuntime`, or a topic with no broker.
- Several `produce` calls where one `produceAll` is meant: a handler returns one effect.
- A record key used to say which entity a message is about. That is the subject; the key decides ordering
  and what a compacted topic keeps.
- A graph delta built by hand: JSON, a `node:`/`edge:` key or a version written in the handler. Return
  elements from a graph consumer's builder; it writes all three.
- A record key set on a delta, or a delta published through an ordinary consumer's `produce`.
- A graph consumer that publishes an element another entity owns (a cart publishing the `product` node).
  Publish the edge; the owning entity publishes the node.
- An element built from a thin event, carrying only what changed. An element is its whole state: build it
  from an event that carries it, or read the entity through the client.
- A graph consumer over a topic with no version stated on its elements: a topic has no sequence number.
- Expecting ankka to create the delta topic or make it compacted. Declare it in the ankka-flow pipeline
  that reads it, and deploy that pipeline first.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Concepts

- `references/concepts/consistency.md` — The guarantees ankka gives — strong consistency per entity, eventually consistent views, exactly-once and at-least-once delivery, ordering, timeouts and timers — and what each means for the code you write.

### Build

- `references/build/views.md` — Build a queryable projection of an entity's or a topic's changes, keep one row per source id, and query the rows with SQL in Scala or by key in Python and TypeScript.
- `references/build/consumers.md` — React to every change from an entity or a topic, call other components or publish one or several messages onward to a topic, and make the reaction safe to repeat under at-least-once delivery.
- `references/build/topics.md` — Read views and consumers from a Kafka topic and publish to one, with CloudEvents attributes as headers, ordering by record key, which is the subject unless a message names one, and a broker-free in-memory pair for tests.
- `references/build/graph.md` — Publish a service's entities as nodes and edges with a graph consumer, which writes versioned graph deltas to a topic for a graph database to follow, with no key, version or JSON written by hand.
- `references/build/serialization.md` — How ankka encodes state, events, arguments and messages as JSON under a named manifest, what the JSON looks like in every language, and how to change a stored type without breaking a journal.
- `references/build/testing.md` — Test ankka components at two levels in Scala, Python, TypeScript and Rust, with unit test kits that run one component and nothing else, integration test kits that run the whole service against a real database, and scripted models.
