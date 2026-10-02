# Research: Graph Delta Publisher

Decisions for [plan.md](plan.md), each with what in the repository it rests on. File references
are to this branch's base (`dd0a603`). "Verify first" marks a claim read from code or a dependency
and not yet run; the task that touches it starts with a test that would show it false.

## R1. Several messages are a new effect, and the single message is untouched

**Decision**: `ConsumerEffect` gains one case, `ProduceAll(messages)`, whose elements are a new
value `Outgoing(payload, metadata, key)`. `Produce(payload, metadata)` keeps its two fields in
Scala and its two fields on the wire. A handler that returns one message with no key returns
`Produce`, as today, and every byte and header of what it publishes is produced by the code that
produces it today.

**Rationale**: FR-009 asks for *exactly as before*, and the cheapest way to have that is not to
touch the path. `Produce` is a public case class (`core/.../effect/ConsumerEffect.scala:15`)
pattern-matched in the runtime (`ProjectionSupport.scala:105`) and in tests; a third field would
break every extractor. On the wire `ConsumerEffect.Produce` (`consumer.proto:18`) is read by four
SDKs; leaving it alone means a service built against protocol 1.2 sends and receives exactly what
it did.

**Alternatives considered**: a `key` on `Produce` and `ProduceAll { repeated Produce }` — one
message type fewer, at the cost of changing the one that is in use. The key as a reserved metadata
entry (`ankka.key`), needing no protocol field at all — rejected: metadata entries become Kafka
headers (`Kafka.scala:40-50`), so it would need a filter in every publisher, and a third-party
`MessagePublisher` that had never heard of it would key by subject without complaint.

## R2. The record key is a parameter of publishing, separate from the subject

**Decision**: `MessagePublisher` gains
`publish(topic, key: Option[String], payload, metadata)`. Its default implementation delegates to
the existing three-argument method when `key` is `None` and **fails** when it is `Some`, so a
publisher that has not learned keys cannot key a message by its subject and say nothing.
`KafkaPublisher`, `InMemoryPublisher` and `InMemoryBroker` implement it: the record key is
`key.orElse(metadata.subject)`. `InMemoryPublisher.Published` gains `key: Option[String]` and a
`recordKey` that applies the same rule, so a test asserts on what Kafka would have been given.
`ce-subject` is defaulted to the source's id exactly where it is today
(`ProjectionSupport.scala:108`, `RemoteProjection.scala:227`), whatever the key.

**Rationale**: the key and the subject are tied together in six places (the publisher's signature,
`Kafka.scala:65`, `MessageSubscriber.scala:59`, `Published`, and the two defaulting sites). The
key type stays `String`: the producer's key serializer is `StringSerializer` (`Kafka.scala:85`)
and an element key is UTF-8 text by contract.

**Consequence to state in the docs**: `IncomingMessage.subject` falls back to the record key when
there is no `ce-subject` header (`MessageSubscriber.scala:11-17`). Messages ankka publishes always
carry the header, so a keyed message read back by an ankka service still has its entity's id as
subject.

## R3. All of a change's messages are sent in order and confirmed together

**Decision**: one function, `ProjectionSupport.publishAll`, used by the in-process path and by
`RemoteConsumer` (sidecar and WebAssembly). It calls `publish` for each message in order —
`KafkaPublisher` hands each record to the producer synchronously, so they are enqueued in order —
and completes when every returned future has, failing if any failed. `ProduceAll(Nil)` completes
immediately.

**Rationale**: FR-002 and FR-005. Order matters only among records with one key, which share a
partition, and the producer preserves enqueue order within a partition. Awaiting each send before
the next would cost a broker round trip per message for nothing. The handler's `Future` failing is
already what makes the change come again: entity sources run `R2dbcProjection.atLeastOnceAsync`
(`ProjectionRuntime.scala:394,402`) with pekko-projection's defaults — the projection restarts
with backoff from the last stored offset, saved after 100 envelopes or 500 ms — and the topic
source commits only after the handler succeeds (`Kafka.scala:139-147`).

**Consequence**: a failure redelivers up to a hundred changes, not one. Their messages are
published again. The docs already tell readers of plain messages to tolerate repeats; for deltas
the repeats are stale at the sink.

**Shared interpreter**: the two paths each default the subject and publish
(`ProjectionSupport.applyConsumer`, `RemoteConsumer.handle`). The single-message branches stay;
the several-message branch is one function both call, so the rule "key, else subject; all or
redeliver" is written once.

## R4. The wire: protocol 1.3

**Decision**: in `consumer.proto`,

```protobuf
message ConsumerEffect {
  oneof effect { Produce produce = 1; Empty done = 2; Empty ignore = 3; ProduceAll produce_all = 4; }
  message Produce { Payload payload = 1; Metadata metadata = 2; }
  message ProduceAll { repeated Message messages = 1; }
  message Message { Payload payload = 1; Metadata metadata = 2; optional string key = 3; }
}
```

and the protocol version goes from `1.2` to `1.3` (`Protocol.version` in `controlplane-api`,
`protocol/README.md`, each SDK's `PROTOCOL_VERSION`). Nothing else in the protocol changes: no
discovery field, no new RPC, no change to `ConsumerRequest`. The WebAssembly export
`ankka1_consumer` carries the same two messages and keeps its signature.

**Rationale**: an added message and an added oneof case are a minor by the protocol's own rule
(`protocol/README.md:14-25`). The control plane already refuses a service that declares a protocol
later than the platform's (`Compatibility.supportsProtocol`), so a descriptor declaring `1.3` is
refused by a `1.2` platform at deploy.

## R5. The runtime says what it accepts, on the request

**Problem**: a runtime at protocol 1.2 reads a reply whose oneof case it does not know as *no
effect set*, and `Translate.fromConsumerEffect` maps that to `Ignore` (`sidecar/.../Translate.scala:
176-181`). A service built with a newer SDK and run on an older platform would have its messages
dropped and its changes recorded as handled. The sidecar checks only the major at discovery
(`Discovery.scala:119-124`), and the declaration in `service.json` is written by hand.

**Decision**: the runtime adds the metadata entry `ankka.protocol`, its protocol version, to every
consumer request. It is set in one place, `RemoteProjection.changeMetadata` (`:51`), which serves
the sidecar and the WebAssembly host alike. An SDK that is about to reply with `produce_all` to a
request that carries no `ankka.protocol`, or one below `1.3`, fails the request instead, with
`this runtime speaks protocol <x>; several messages or a record key need 1.3`. The change is
redelivered and the consumer stalls, visibly.

**Rationale**: FR-029b. Remembering the version from discovery would do for a process behind a
sidecar, but a consumer in a WebAssembly module runs on a fresh instance per call
(`WasmConversation.scala:254-257`) and remembers nothing. The request is the one thing every
handler in every mode is given. `ankka.sequence` and `ankka.now` are precedents for
runtime-stamped entries.

**Alternatives considered**: making an unset effect an error in the new runtime — good hygiene,
no help with the old ones. A discovery field declaring "this consumer may produce several" — an
old sidecar ignores unknown fields too.

## R6. A bound on one change's result

**Decision**: a change's result may be at most 4 MiB as encoded for the wire. The sidecar's
channel to the process already imposes that on a reply (grpc-java's default inbound limit; nothing
in the repository raises it), and `RemoteConsumer` turns the transport's refusal into an error
naming the consumer, the subject and the limit. For a WebAssembly module, whose only bound is its
256 MiB of memory (`Settings.scala:51`), and for an in-process consumer, `publishAll` applies the
same limit before publishing anything, so the behaviour does not depend on how a service is
hosted. Nothing is published in part (FR-010).

**Not bounded here**: the broker's own limit on one record (1 MiB by default). A record over it
fails its publication, and the change is redelivered until the consumer or the broker is changed,
as an oversized single message does today.

## R7. A key value deletion becomes a recorded state

**What is there** (verify first): `KeyValueEntityHost.storageEffect` answers `DeleteNow` with
`PekkoEffect.delete()` (`:182`), as does `RemoteKeyValueHost` (`:181`). In pekko-persistence-r2dbc
1.2.0 that is a SQL `DELETE` of the row; the plugin's change stream emits only
`UpdatedDurableState`. So the `DeletedDurableState` branches in `ProjectionRuntime.scala`
(`:514,529,578`) and `RemoteProjection.scala` (`:159,265`) are never reached: no view row is
removed and no consumer's deletion handler runs for a key value entity. After a restart the
entity's revision begins again at 1. Within one incarnation of the entity's actor the next write
after a delete appears to update a row that is no longer there; `kv.delete-then-fresh` reads after
a delete and never writes. No suite anywhere uses a key value entity as the source of a view or a
consumer.

**Decision**: deletion persists a state instead of deleting the row:
`Stored(emptyState, deleted = true, expiryMillis = 0)` in process and
`RemoteState(None, deleted = true, 0)` for a remote entity. The stored form already has the field
(`StateRecord.deleted`, `wire.scala:163`; a key value row has only ever had it `false`, while an
event sourced entity's snapshot, which is the same record, carries its deleted flag there), so no
stored form changes and there is no DDL. A command handler is shown the empty state when the stored one
is deleted, as it is when it has expired. The four projection handlers treat an
`UpdatedDurableState` whose record is marked deleted as the deletion: the view's row is removed,
the consumer's deletion handler runs, both at that update's revision. The `DeletedDurableState`
branches stay for a store that emits them.

**Rationale**: FR-019, FR-020, FR-020a. It is what the event sourced host already does: a
deletion there is a journalled record with its own sequence number, the id stays usable and the
numbering continues (`EventSourcedEntityHost.scala:110-115,216-221`). The revision is the only
per-entity counter a key value entity has, and it can only survive deletion if the row does.

**Costs, stated**: a deleted key value entity keeps a row holding its id, its revision and an
empty state. Its data is gone; its id is not. That is what an event sourced entity has always
kept, and more. A runtime of an earlier version reading such a row sees the empty state: in
process it decodes the payload and ignores the flag; the remote host already treats `deleted` as
fresh (`RemoteKeyValueHost.scala:36`). A rolling upgrade is therefore safe in both directions.

**A compatibility suite comes first**: a suite that pins the bytes of `StateRecord` for a live
and a deleted state, written before the hosts change, so the claim "no stored form changes" is a
test.

**Alternatives considered**: leaving key value deletion out of the feature and documenting that a
graph cannot follow it — rejected: the defect is the platform's, it already costs views their
correctness, and a graph that cannot show deletion for one of the two entity kinds is half a
feature. A separate tombstone table — a second source of truth for what the row can say itself.

## R8. A key value consumer is handed the revision

**Decision**: `ConsumerStateHandler` passes `revisionOf(change)` where it passes `0L`
(`ProjectionRuntime.scala:572`), as `ViewStateHandler` does (`:507`) and as the remote path does
for every SDK (`RemoteProjection.scala:263-266`).

**Rationale**: FR-018. `ChangeContext.sequenceNumber` is documented as "sequence number for an
event-sourced source, or revision for a key value one" (`ChangeSource.scala:57`); the in-process
consumer is the one place that does not honour it.

**A property to document, not change**: a key value source delivers the latest state and may skip
intermediate ones (`ChangeSource.scala:23-29`). Deltas are whole states, so nothing is lost.

## R9. Topic sources have no sequence, and expiry tells nobody

**Decision**: a consumer over a topic is handed sequence 0 (`TopicHandlers.scala:86`), and the
builder refuses to default a version to 0 (R11): the author states one. An entity whose state has
expired is not deleted — expiry is evaluated when a command arrives (`EventSourcedEntityHost.scala:
113-115`, `Stored.expired`) and reaches no consumer — so its elements are not tombstoned. Both are
documented (FR-020b, FR-021).

**Alternative considered**: the record's offset as a version for a topic source. It rises per
partition and so per key, but `IncomingMessage` does not carry it, and a topic re-created or
re-partitioned restarts it. Left for whoever needs it.

## R10. The delta is a value in `core`; the runtime never learns the word

**Decision**: `core` gains a package `graph` with `GraphDelta` (node, edge, node tombstone, edge
tombstone), its element key, its JSON codec as a `Serializer` with manifest
`ankka.graph-delta.v1`, and a reader. `sdk` gains `GraphConsumer` and its builder. `runtime`
publishes bytes under keys and knows nothing about graphs; the sidecar likewise.

**Rationale**: module direction — `core` depends on nothing of ours and already holds the JSON
codecs (`Codecs`, jsoniter); a delta is data. The other three SDKs have the same split by
construction: the SDK encodes and keys, the runtime publishes.

**What a delta's record carries**: value, the delta as JSON; key, the element key; headers, the
CloudEvents attributes of any published message with `ce-type` set to `ankka.graph-delta.v1` and
`ce-subject` the source entity's id. The merge sink ignores headers; they make the record
self-describing to anything else that reads the topic.

## R11. One set of builder rules, held by four builders

**Decision**: [contracts/graph-builder.md](contracts/graph-builder.md) is the single statement of
what an element is, what is refused, how a version is chosen and how a delta is written. Each
SDK's builder implements it in its own idiom. The points that are decisions rather than the
contract's:

- **A version defaults to the change's sequence number and must be at least 1.** The contract
  allows 0; ankka presents 0 for "no sequence", so a default of 0 is refused with a message saying
  the source has none. A stated version of 0 or less is refused too, in all four.
- **Validation happens where the element is built**; the version is resolved when the result is
  returned, because only then is the change known in every language's shape of handler.
- **The same element twice in one result is refused** (FR-015), by kind and id.
- **Numbers.** An integer property must fit 64 bits. A float must be finite. A float with a whole
  value is the same kind as an integer for the one-kind-per-list rule, because the sink reads it
  as an integer. In TypeScript a `number` used as an integer must be a safe integer, and a
  `bigint` carries the rest; a version is a safe-integer `number` or a `bigint`.
- **Equality is the reader's.** Two deltas are equal when they read back equal — `2.0` and `2`
  are the same property value — not when their bytes match. Languages format floats differently
  and need not agree on key order.

**Rationale**: FR-014, FR-025, the edge case "the four builders disagreeing". ankka-flow's Python
helper already made the same choices for numbers (its `research.md`, "the SDK helper is stricter
than the contract listed"); the builders here match it so a mapper and a publisher cannot differ.

## R12. Fixtures: two from ankka-flow, one of ankka's own

**Decision**: `protocol/fixtures/graph-deltas/` holds

- `keys.json` — ankka-flow's, byte for byte (`protocol/fixtures/graph-deltas/keys.json` there);
- `deltas.json` — ankka-flow's, a new file there: rows of `{delta, key}` covering every property
  kind (string, integer, negative, the 64-bit extremes, float, boolean, a list of each), absent
  and empty `labels` and `properties`, and the two tombstones. It is added to ankka-flow first, in
  a change that makes the sink's `DeltasSuite` and the Python SDK's `test_graph.py` read it, so
  the rows are ones the reader is proven to accept;
- `refused.json` — ankka's: elements every builder must refuse, each with the reason's name;
- `SOURCE.md` — which files are copies, of what, at which ankka-flow tag.

Every SDK builds each row of the first two through its builder and compares key and read-back
delta, and builds each row of the third and expects the refusal. Refusals JSON cannot express
(a non-finite float) are tested natively in each SDK.

**Rationale**: FR-025, SC-003. `protocol/fixtures/` at its top level belongs to
`EncodingFixturesSuite`, which rewrites and refuses what it did not generate (`CLAUDE.md:
1073-1075`); a subdirectory is outside it, as `autonomous/` is. The SDKs copy `protocol/fixtures`
whole (`scripts/proto.py:28-33`, `scripts/proto.ts:23-28`, `scripts/proto.sh`) and CI diffs the
copies, so the fixtures reach all four with no new mechanism.

**Keeping the copies equal**: no build reaches into the other repository (FR-024). `SOURCE.md`
names the tag; a task at the end compares against it by hand, and the release notes of either
project say when the files change.

**This needs a change in ankka-flow** — test data and two test readers, no behaviour. It is the
first task and is its own pull request there.

## R13. The Scala surface

**Decision** ([contracts/scala-api.md](contracts/scala-api.md)):

- `effects.produceAll(messages)` and `effects.message(payload)` on the existing `ConsumerEffects`;
  `Outgoing` has `withKey` and `withMetadata`.
- `GraphConsumer[Src]` is a class of its own, not a `Consumer`: its `effects` are `publish`,
  `done` and `ignore`, and `publish` takes elements. There is no `produce` to call and no key to
  set, so FR-012 and FR-013 hold by type. Its companion takes a component id, a source and a
  topic, and builds an ordinary `ConsumerDescriptor` around an adapter, so registration, discovery,
  sharding and startup checks are the consumer's.
- `ConsumerTestKit` in `testkit`, which Scala does not have today: hand a consumer or a graph
  consumer a change with a subject and a sequence number and read back messages or elements.

**Rationale**: the other three SDKs have a `ConsumerTestKit` and Scala has none
(`modules/testkit/src/main` holds `AnkkaTestKit`, `EventSourcedTestKit`, `KeyValueEntityTestKit`);
FR-030 needs one in every SDK. No classpath scanning: a graph consumer's descriptor is a value
registered like any other.

## R14. Python, TypeScript and Rust

**Decision** (contracts [python-api.md](contracts/python-api.md),
[typescript-api.md](contracts/typescript-api.md), [rust-api.md](contracts/rust-api.md)):

- **Python**: `effects.produce_all([...])` with `effects.message(payload, key=…, metadata=…)`;
  `ankka.graph.GraphConsumer` with `self.graph.node(…)` and `self.effects.publish([...])`. The
  module's reader and key functions keep the names ankka-flow's Python SDK uses (`read`,
  `node_key`, `edge_key`, `SCHEMA_NAME`). `ConsumerTestKit` gains a `sequence` argument — it
  cannot pass one today (`testkit/unit.py:427`) — and records messages with their keys.
- **TypeScript**: `effects.produceAll([...])`; `GraphConsumer`; a `sequenceNumber` getter on
  `Consumer`, which has none (`consumer.ts`; the value is a string in `metadata`). The delta codec
  is a hand-written `Codec` — the schema library has no "any JSON" shape (`schema.ts:13-37`) and a
  delta's properties are open.
- **Rust**: `consumer::produce_all(vec![…])` with `consumer::message(x).key("…")`, and a
  `consumer::produce_with(x, metadata)` that the crate lacks today. `GraphConsumer` is a trait of
  its own, registered through a kind marker of its own, `kinds::GraphConsumer`, so its blanket
  `ComponentOf` implementation does not overlap the consumer's; what is discovered is
  `Kind::Consumer`. The testkit's `feed` sets `ankka.sequence`, which it does not today
  (`testkit/kinds.rs:425-486`).

Each SDK implements R5's guard, reads `ankka.protocol` from the request, and passes the fixture
suite of R12.

**Rust enums**: `ConsumerEffect` gains a variant, which breaks an exhaustive `match` in user
code. The crate is `0.x` and the release notes say so.

## R15. Conformance

**What is there**: one consumer case, `consumer.at-least-once-in-order`, whose consumer produces
nothing; every target builds `ProjectionRuntime()` with no broker (`ConformanceTarget.scala:
92,172,278`), so a producing consumer would be refused at startup.

**Decision**: the targets are given an `InMemoryBroker`, and one that can be told to fail the
next publication to a topic. Each reference service gains three components: `checkout-fanout`
(several plain messages, one keyed, one not), `cart-graph` (a graph consumer over the cart) and
`profile-graph` (a graph consumer over the key value entity). New cases, listed in
[contracts/protocol.md](contracts/protocol.md): order; named and default keys with the subject
unchanged; the empty list; redelivery when a publication fails; the graph's records for a
scripted history, compared as read deltas; the revision as a key value consumer's sequence; a key
value deletion reaching a view and a consumer; a write after a key value deletion.

**Rationale**: FR-029, SC-003a. One case with one expected list, run against the Scala reference,
the Python and TypeScript processes and the Rust module in both shapes, *is* the proof that four
languages publish the same records.

**The trap to respect** (`CLAUDE.md:1277`): munit's filter matches full test names and
`ANKKA_CONFORMANCE_ONLY` that matches nothing reports green. Each new case is shown failing
against a reference without the feature before it is trusted.

## R16. The sample, the examples and the graph they publish

**Decision**: the consumer `CartGraph` over `ShoppingCartEntity`, a pure function of the event:

| Change | Published |
|---|---|
| `ItemAdded`, `ItemRemoved` | node `cart:<id>`, label `Cart`, `{cartId, checkedOut: false}` |
| `CheckedOut` | node `cart:<id>` with `checkedOut: true`; node `checkout:<id>`, label `Checkout`, `{cartId}`; edge `checked-out:<id>`, type `CHECKED_OUT`, cart → checkout |
| `Discarded` | nothing |
| the deletion | tombstone for node `cart:<id>` |

Versions are the sequence numbers. A checked-out cart cannot be discarded
(`ShoppingCartEntity.scala:64-66`), so a checkout and its edge are never tombstoned; a discarded
cart that takes items again publishes its node above the tombstone. It lives in
`samples/shopping-cart` for Scala and in each SDK's shopping cart example
(`sdks/python/examples/shopping_cart`, `sdks/typescript/examples/shopping-cart`,
`sdks/rust/examples/shopping-cart`), which is where the documentation's included code comes from
and where each conformance reference lives.

**Reading state** (FR-023a): the guide's section on thin events has its own tested region in each
example — a consumer that reads the cart through the component client and publishes the number of
lines as a property. It is kept out of `CartGraph` and out of conformance because what it
publishes depends on how far the entity has got, and the conformance case compares exact records.

**The pipeline**: `samples/shopping-cart/graph/` holds an ankka-flow blueprint whose one streamlet
is `builtin/neo4j-merge` and whose one topic is managed, named `cart-graph` — the name the
consumer publishes to — with the steps to run it. ankka's local overlay has no Kafka
(`kustomization/` names none); the run uses the broker and the graph database ankka-flow's local
deployment installs, as its checkout samples do.

## R17. Documentation

**Decision**: a new guide, `docs/build/graph.md` (*Publish a graph*), in the navigation after
*Broker topics* and in the skills `ankka-views-consumers`, `ankka-python`, `ankka-typescript` and
`ankka-rust`. Changed pages: `build/consumers.md` (several messages, keys, when a change is
handled; Rust joins its languages — the Rust example already carries an unused `consumer`
region), `build/topics.md` (key and subject; ordering), `build/key-value-entities.md` and
`build/views.md` (a deletion is a recorded change; the row goes), `build/testing.md`,
`concepts/consistency.md`, the four SDK references, `reference/sidecar-protocol.md` (the generated
table, version 1.3, `ankka.protocol`), `reference/wasm-abi.md`, `reference/limitations.md`,
`reference/glossary.md`, and `protocol/README.md` with `WASM-ABI.md`.

**Rules that bind it** (`docs/contributing/documentation.md`): every sample included from tested
code; tab sets in the order Scala, Python, TypeScript, then Rust, showing the same example; no
history; the new page in `nav` and a skill or `docs check` fails.

## Verify first

1. **R7**: that a key value delete removes the row and emits nothing, and what a write after a
   delete does within one actor incarnation. A testkit suite over Postgres, written before the
   host changes, expected to fail in the ways described.
2. **R7**: that no reader of the durable state table (the console's entity pages, the conformance
   suite's direct reads) assumes a deleted entity has no row.
3. **R3**: that a failed publication restarts the projection and redelivers, for all three kinds of
   source, with a publisher that fails once.
4. **R5**: that a 1.2 runtime really maps an unknown case to `Ignore` — a test of
   `Translate.fromConsumerEffect` with the new case absent — so the guard is shown to be needed.
5. **R6**: the message a 4 MiB overrun produces on the sidecar's channel, so the error
   `RemoteConsumer` raises can name the limit.
6. **R12**: that `diff -r protocol/fixtures` in the three SDK jobs covers the new subdirectory and
   that TypeScript's top-level-only fixture reader (`encoding-fixtures.test.ts:16-18`) ignores it.
7. **R16**: how a Python process and a Rust module are deployed to the local cluster for the
   repeated run; `docker-compose.yml` builds both examples, and whether the cluster path takes the
   same images is unconfirmed.

## Found during implementation

- **V6, the fixtures' directory** — answered. `EncodingFixturesSuite` passes with
  `protocol/fixtures/graph-deltas/` present; TypeScript's fixture reader, Python's and Rust's pass;
  the three copies made by the SDKs' scripts are identical to the original.
- **`deltas.json` carries a third field, `reads`**: the kind the sink reads each property as. It
  makes the number rule a row rather than a sentence — `2.0` reads as an integer — and the sink's
  suite asserts it, shown failing with one row's kind changed. The file was read correctly by the
  sink and by ankka-flow's Python helper as first written.
- **A whole-valued float beyond 64 bits is refused**, as an integer out of range: the sink reads
  `1.0E30` as a whole number it cannot store. `refused.json` has the row.
- **`refused.json` has 42 rows over nine reasons.** A property name that is not a string cannot be
  a row, and a row that a typed language cannot express (a version of `1.5` where a version is a
  64-bit integer) counts as refused there.
- **The ankka-flow change is commit `9905de1` on its branch `graph-delta-fixtures`**, not yet
  pushed; `SOURCE.md` names it.
- **V4, a reply the runtime does not know** — confirmed, as a case of `TranslateSuite`: an effect
  with no case set translates to `Ignore`. The suite is a new pure one beside `ProtocolSuite`,
  which starts an actor system and a process double and is the wrong place for five cases that
  need neither.
- **The protocol version is written twice in Scala, not three times.** The sidecar's
  `Discovery.ProtocolVersion` now reads `WireProtocol.Version` in `runtime/remote`, where the
  stamp also lives; `controlplane-api`'s `Protocol.version` stays the second place, since that
  module cannot see `runtime`.
- **`ankka.protocol` is set by `RemoteConsumer`, on consumer requests only**, not on a view's.
  There is no case for it in `WasmHostSuite`: that suite calls the conversation directly, below
  where the entry is added. The module path is covered when the Rust module's guard meets the
  conformance cases — a missing entry would fail every several-message reply.
- **`protocol/WASM-ABI.md` states no version**; it gained the sentence about the two entries a
  consumer's request carries.
- **The build was not warning-free before this branch**: `operator` (`ZeroTrust.scala:297`, an
  unused parameter), `operator`'s and `controlplane-api`'s tests (discarded values) warn on `main`.
  Not this feature's; nothing this feature touches warns.
- **V1, what a key value deletion did** — measured by `KeyValueDeletionSuite` on the code as it
  was, 5 of 8 cases failing: the view's row was never removed (30 s wait); the consumer's
  deletion handler never ran; the consumer was handed sequence number 0 for a state at revision
  1; after a restart the deleted entity's revision read 0 where it had been 3. **Three cases
  passed, one against the plan's expectation**: a write straight after a deletion succeeds, and
  within one incarnation the revision does go on counting through it. The defect was what
  happened to everyone else, and to the count across a restart. All eight pass now.
- **V2, other readers of key value state** — there are none. Nothing in `controlplane`, `cli`,
  the console or the conformance suite reads the durable state table or a `StateRecord`; only the
  two hosts and the projection handlers do. `StateRecord` is also the record of an event sourced
  entity's snapshot, which is untouched.
- **A deleted record is never decoded.** The in-process host reads a record marked deleted as the
  empty state without calling the state's serializer, because a remote host writes a deleted
  record with no payload at all and the two read each other's rows. A runtime from before this
  change would fail to decode a row a *remote* host deleted, if a service were ported from a
  sidecar to in-process and then rolled back — a combination nobody is in; noted, not handled.
- **Deleting an entity that was never written now writes a row**: the deleted marker, at
  revision 1. Before, there was nothing to remove. Its views and consumers are told of a
  deletion of something they never saw, which a view answers by deleting a row that is not there.
- **The stored form is pinned as bytes**, in `journal/state-record.txt`: a live, an expiring, a
  deleted and a remote-deleted record, written and read by the actor system's own serialization
  (jackson-cbor), not by a codec of the suite's.
- **The remote path** is held by `RemoteProjectionSuite` P3c: a remote view's row goes, the remote
  consumer is sent `deleted = true` at the revision after the state's, and a state set again
  arrives one above that. `RemoteEntitySuite` needed nothing: the entity's own behaviour after a
  delete was already covered and did not change.
- **V3, redelivery** — confirmed for an event sourced source, a key value source and a remote
  consumer (`ProduceAllEntitySourceSuite`, `RemoteProjectionSuite` P3f, conformance
  `consumer.produce-all-redelivers`): with one publication refused, the projection restarts and
  the change's messages are all published again, within a few seconds. For a topic source the
  in-memory broker has no redelivery of its own; the case there asserts what makes a real one
  redeliver — the handler's result fails — and then delivers again by hand.
- **V5, a reply over the transport's limit** — the sidecar's channel fails the call with
  grpc-java's `RESOURCE_EXHAUSTED`, whose message carries the limit, `4194304`. `RemoteConsumer`
  now wraps whatever fails between it and the process as
  `consumer '<id>' could not handle the change of '<subject>' at sequence <n>: <cause>`, so the
  projection's log says whose change it was (P3g). In process and for a module the bound is
  `ProjectionSupport.MaxResultBytes`, held by the pure `PublishAllSuite`, not by a consumer made
  to exceed it in a running service: such a consumer is redelivered for ever and would stall its
  slice of the projection for every test after it.
- **`PublishAllSuite` is new and pure**, in `runtime`'s tests: nine cases on the one function all
  three hosting paths share, with no actor system. It is where "a publisher that was never taught
  keys refuses a keyed message" is shown.
- **The conformance suite's `ce-id` assertion moved to `KafkaSuite`.** The in-memory broker the
  conformance targets are given adds no CloudEvents headers; the Kafka publisher does.
- **`consumer.single-produce-unchanged` needed a single-message producer**, which no reference
  service had. `checkout-fanout` answers an item removed with one `produce`.
- **The four several-message cases were seen to fail** with `checkout-fanout`'s checkout reduced
  to a single `produce`, and `-- *consumer.produce-all-keys` runs exactly one case. The filter is
  a glob over the full test name, so it needs its leading `*`.
- **Adding a component to the Scala reference breaks `discovery.lists-every-component` for every
  other reference** until it has the same component. Between this point and the SDKs' tasks the
  three SDK conformance runs fail that one case.
- **`GraphDelta` is one flat case class**, not a case per kind as `contracts/scala-api.md` first
  had it. A test reads `delta.key`, `delta.version`, `delta.properties` without a match; it is the
  shape ankka-flow's Python reader returns and the shape the other SDKs' readers took. The
  contract is corrected.
- **The rules live in `core`, in `GraphRules`**, beside the delta and its reader, and the builder
  in `sdk` only calls them. So the fixture suites are in `core`'s tests — `GraphDeltaSuite` (the
  20 rows of ankka-flow's two files, each built, written and read back) and `GraphRulesSuite` (the
  42 rows of `refused.json`, each for its reason) — and `sdk`'s `GraphElementsSuite` holds the
  author's surface. `core` gained a small JSON tree (`GraphJson`) to read a delta's open-ended
  properties with exact numbers; it is `private[ankka]`.
- **A refusal carries its reason**: `GraphElementRefused.why` is the fixture's name for the rule,
  so a test asserts the rule and not a message.
- **The Scala test kit applies an effect with the runtime's own function.**
  `ConsumerTestKit` calls `ProjectionSupport.applyConsumer` against an `InMemoryPublisher`, so
  the subject default, the key, the bound and the "no topic" failure cannot differ between a
  unit test and a running service.
- **`effects.publish` takes varargs only.** A list is passed as `publish(elements*)`.
- **A key value source may skip a state** (it delivers the latest), so the suites and the
  conformance cases wait for the *last* state's delta and assert its revision, not a count.
- **The conformance graph cases** were seen to fail in the two ways tried: `checkout-fanout`
  reduced to one message (four cases), and the two references' deletion handlers removed
  (`consumer.graph-delete-and-recreate`, `kv.delete-is-a-change`). 82 cases pass against the
  Scala reference.
- **The three other SDKs were built side by side against the contracts**, each in its own
  directory, and then run through the conformance suite: all 82 cases pass against the Python
  process, the TypeScript process and the Rust module. Their own suites went from 98 to 240
  (Python), 139 to 247 (TypeScript) and 84 to 104 with 14 to 17 in the example (Rust). Each tests
  every row of the three fixture files.
- **`ankka.conformance.shape` was never forwarded to the test JVM**, on `main` either, so the
  second run of `sdks/rust/conformance.sh` — the stateful guest shape — had always been a second
  stateless one, and said so in its own output. It is forwarded now (`build.sbt`), and the module
  passes all 82 in the stateful shape for real. Found because the feature's claim was "both
  shapes" and the log named one.
- **There is no several-message case in `WasmHostSuite`.** Its guests are prebuilt spike modules
  that answer `done`; a module that answers `produce_all` is the conformance reference, and the
  eleven new cases run through the same host in both shapes.
- **What the SDKs settled that the contracts had not**: an empty list is sent as `done` and a
  single un-keyed message as `produce`, so the guard fires only where the runtime really must
  understand something new (now in `contracts/protocol.md`); each refusal carries the fixture's
  reason name (`RefusedElement.why`, `GraphError.why`, `Refused`/`Why`), as Scala's does; each
  SDK's delta codec is public, for a consumer that *reads* a delta topic; none sets `ce-subject`
  on a delta — the runtime's default, the source's id, is the rule.
- **Rust has one more reason, `misplaced`**: its one chainable `Element` can be given a label on
  an edge or a property on a tombstone, which the other three cannot express. Refused at dispatch.
- **A foreign delta carrying exactly -2⁶³-1 as a property reads as `i64::MIN` in Rust**: without
  arbitrary precision the JSON parser rounds it. The builder cannot write such a value and the
  sink would refuse it; noted, not handled.
- **Each example registers `CartGraph` only where a broker is named**, as the Scala sample does,
  because a publishing consumer is refused at startup without one. The Python and TypeScript
  examples read `ANKKA_KAFKA_BOOTSTRAP_SERVERS` from the process's environment and the Rust one
  through `ankka::config`. **Whether the platform gives that variable to a process container or a
  module, and not only to the sidecar, is unconfirmed** and is the first thing the cluster run
  with those two must settle.
- **The Rust tutorial page shows the example's registration**, which now ends in that condition;
  `docs sync` carried it into `get-started/first-service-rust.md`.

## Quickstart tier 7, on the local cluster (2026-10-02)

The record is in `samples/shopping-cart/graph/README.md`. In short: the sink-only pipeline's topic
was created compacted under the name the service publishes to; the scripted carts gave exactly the
expected twelve nodes and two edges; all 60 records then on the topic were under their element
key with `ce-type` `ankka.graph-delta.v1`; a restart straight after a second set gave an identical
graph; a replay of both consumers from the start took the topic from 88 records to 176 while the
sink wrote none (88 more stale, 0 failed) and the graph did not move; a rebuild with only the sink
reset restored every live element and every deletion mark; and the Python example behind a
sidecar and the Rust example as a module each produced the same graph for the same script.

What it found:

- **V7 — a sidecar-hosted service could not publish to a topic on a cluster at all.** The operator
  splits a descriptor's variables between the two containers and gave
  `ANKKA_KAFKA_BOOTSTRAP_SERVERS` to the process only, while it is the sidecar that connects to
  the broker. The Python example registered its graph consumers, and its sidecar refused to
  start: `consumer 'cart-graph' publishes to 'cart-graph' but no MessagePublisher was
  configured`. This was so on `main` for any producing consumer behind a sidecar; the docs said
  the platform routed the variable to the sidecar, and no suite deployed such a service to a
  cluster with a broker. The operator now gives `ANKKA_KAFKA_*` to **both** containers
  (`Rendering.SharedEnvPrefixes`, mirrored in `ServiceSpec`, held by
  `ProcessHostingRenderingSuite`, which failed first): the sidecar to connect, the process so it
  can register what publishes only where there is a broker.
- **A module reads the variable through `ankka::config`**: it is not among the names the host
  reserves, so the Rust example's condition works as written.
- **A rolling update over a pod that never formed a cluster does not complete.** The new pod's
  bootstrap kept probing the crash-looping old one. Deleting and re-applying the service cleared
  it. Not this feature's, and not pursued.
- **The released `ankka` CLI on this machine refuses `"hosting": "wasm"`** — it predates it. The
  run used the CLI staged from this branch.
- **A deleted element's leftover labels and properties depend on how the sink batched its
  records**, in ankka-flow: a node and its tombstone read in one batch leave a bare marker, read in
  two they leave the node's last properties under the marker. It showed as the only difference
  between a graph and its rebuild (one long-deleted cart), and between the three languages' runs
  (the one discarded cart). Ids, versions and deletion marks were identical throughout, and every
  live element was. ankka-flow's guarantee is for the live graph, so this is within it; it is
  worth that project's making a tombstone clear what it marks, so that two graphs compare equal
  without a filter.
- **The Scala test kit's `keys` now means what the other three kits' means**: the key a message
  named, with `recordKeys` for what a broker is given. It first reported the latter, and the docs
  had to explain the difference.
- **`drive.sh` broke on bash 3.2** with an apostrophe inside `${1:?…}`; reworded.
