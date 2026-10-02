# Tasks: Graph Delta Publisher — a Service Publishes Its Entities as a Graph

**Input**: Design documents from `/specs/019-graph-delta-publisher/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" list turns each fact read from
code into a test before the code that relies on it. Where a task says "case", it means a
`test(...)` in the named suite (or its equivalent in the SDK's test runner).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a consumer publishes several messages for one change), US2 (a service
  publishes its entities as graph deltas), US3 (publishing is safe to repeat, and versions never
  go backwards), US4 (a graph in the database with no mapper), US5 (it is tested without a
  broker), US6 (publishing a graph is documented)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `SDK`/`SDKT` = `modules/sdk/src/{main,test}/scala/…/sdk`;
`RT` = `modules/runtime/src/main/scala/…/runtime`; `TK`/`TKT` =
`modules/testkit/src/{main,test}/scala/…/testkit`; `SC`/`SCT` =
`sidecar/src/{main,test}/scala/…/sidecar`; `CONF` = `SCT/conformance`; `FX` =
`protocol/fixtures/graph-deltas`; `CART` = `samples/shopping-cart`; `PY` = `sdks/python`; `TS` =
`sdks/typescript`; `RS` = `sdks/rust`; `DOCS` = `docs`; `SKILL` = `tools/docs/skill`. "R*n*" is a
section of `research.md`; "V*n*" an item of its *Verify first* list; a contract is named by its
file under `contracts/`.

The branch `019-graph-delta-publisher` exists, in the worktree
`.claude/worktrees/019-graph-delta-publisher`. `main`'s own working tree carries changes that are
not this feature's; work in the worktree.

---

## Phase 1: Setup — the fixtures both repositories read

**Purpose**: the rows that prove ankka writes what ankka-flow reads have to exist in ankka-flow
first, read by its own sink, before they are copied here (R12).

- [X] T001 In the **ankka-flow** repository, on a branch of its own: add `protocol/fixtures/graph-deltas/deltas.json` — rows of `{"delta", "key"}` covering a string, an integer, a negative integer, both 64-bit extremes, a float, a boolean and a list of each as property values; absent and empty `labels` and `properties`; a node tombstone and an edge tombstone. Make `sidecar/src/test/scala/com/thinkmorestupidless/ankka/flow/sidecar/DeltasSuite.scala` and `sdks/python/tests/test_graph.py` read every row (key and parse), copy the file into `sdks/python/proto/fixtures/graph-deltas/` with `uv run python scripts/proto.py`, run both suites, and open the pull request there. No behaviour changes.
- [X] T002 Create `FX/keys.json` and `FX/deltas.json` as byte-for-byte copies of ankka-flow's; write `FX/refused.json` (one row per `why` of `contracts/graph-builder.md`, shaped per `data-model.md` "Fixture rows", including the 2⁶³ integer, a version of 0, a mixed list, an empty list, a nested object, a `null`, each reserved property name, and a `duplicate` row with two elements); write `FX/SOURCE.md` naming which files are copies and the ankka-flow commit they came from. Run `cd sdks/python && uv run python scripts/proto.py`, `cd sdks/typescript && npm run proto`, `cd sdks/rust && ./scripts/proto.sh` so the three copies carry the directory.
- [X] T003 Confirm V6: `sbt 'core/testOnly *EncodingFixturesSuite'` passes with the subdirectory present; `cd sdks/typescript && npm test` passes (its top-level fixture reader in `test/encoding-fixtures.test.ts` ignores the subdirectory); the three `diff -r protocol/fixtures …` lines of `.github/workflows/ci.yml` report nothing; `python3 .github/ci-coverage.py` claims the new files.

**Checkpoint**: the fixtures are in `protocol/fixtures/graph-deltas/` and in all three SDK copies; nothing else has changed and every existing suite is green.

---

## Phase 2: Foundational — protocol 1.3, and a key value deletion that is recorded

**Purpose**: the wire every story speaks, and the one platform behaviour the spec assumed and
planning found absent.

**⚠️ CRITICAL**: the protocol group (T004–T006) blocks every user story. The key value group
(T007–T012) blocks only US3's key value scenarios and the conformance cases that use `log-graph`;
it has nothing to do with graphs, ends green on its own, and can be merged first as its own
change.

### The protocol

- [X] T004 Add `ProduceAll` (oneof case 4) and the messages `ProduceAll` and `Message` to `protocol/src/main/protobuf/ankka/protocol/v1/consumer.proto` exactly as `contracts/protocol.md` writes them, leaving `Produce` untouched. Set the version to `1.3` in `controlplane-api`'s `Protocol.version`, in `protocol/README.md` (and add `ankka.protocol` to its consumers rule) and `protocol/WASM-ABI.md`, in the literals of `controlplane-api/src/test/scala/…/api/CompatibilitySuite.scala` and `HostingSuite.scala`, and in each SDK's `PROTOCOL_VERSION` (declared for `PY/src/ankka/service.py`, `TS/src/spec.ts`, `RS/ankka/src/service.rs`). Re-run the three copy scripts of T002. `sbt protocol/compile controlPlaneApi/test` green.
- [X] T005 Carry the new reply to the runtime in plain values: add `ConsumerOutcome.ProduceAll(messages)` with a message value `(payload, metadata, key: Option[String])` to `RT/remote/Conversation.scala`, and map it in `SC/Translate.scala` (`fromConsumerEffect`). In `SCT/ProtocolSuite.scala` add cases: a `produce_all` reply translates with order, keys and metadata intact; an empty one translates to an empty list; and (V4) a reply with **no** case set still translates to `Ignore` — the recorded reason the guard of R5 exists.
- [X] T006 Stamp `ankka.protocol` with `Protocol.version` in `RemoteProjection.changeMetadata` (`RT/remote/RemoteProjection.scala`); if `runtime` cannot see `controlplane-api`'s constant, pass the version in where `ProjectionRuntime` is built rather than adding a dependency. Cases in `SCT/RemoteProjectionSuite.scala` (through `SCT/ProcessDouble.scala`) and `SCT/WasmHostSuite.scala`: every consumer request, for an event, a state and a deletion, carries `ankka.protocol = 1.3`, `ce-subject` and `ankka.sequence`.

### A key value deletion is a recorded state (R7, R8; `contracts/key-value-deletion.md`)

- [X] T007 Before any host changes, write `TKT/StateRecordCompatibilitySuite.scala`, in the manner of `TKT/SessionMemoryCompatibilitySuite.scala`: pin the serialised bytes of `StateRecord` for a live state and for `deleted = true` with an empty state's payload, as fixtures under `modules/testkit/src/test/resources/`, and assert both read back. Green on today's code; commit it on its own.
- [X] T008 Write `TKT/KeyValueSourceComponents.scala` (a key value entity with `set`, `get` and `delete`; a view and a consumer over it via `ChangeSource.stateOf`, the consumer recording subject and sequence number through the component client) and `TKT/KeyValueDeletionSuite.scala` over Postgres, with these cases, **all but the first expected to fail today** (V1): read after delete returns the empty state; a write after a delete succeeds within one incarnation of the entity; a write after a delete succeeds across a restart of the test kit; revisions across update, delete, update are strictly increasing and `commandContext.sequenceNumber` continues; the view's row is removed when the entity is deleted; the consumer's `onDelete` runs with the deletion's revision; the consumer's `messageContext.sequenceNumber` for a state change is the revision and never 0. Record in `research.md` ("Found during implementation") what each failing case actually did.
- [X] T009 Confirm V2: search for every reader of the durable state table and of `StateRecord` outside the hosts (`git grep -n 'durable_state\|StateRecord'` across `modules`, `sidecar`, `controlplane`, `cli`, `console`) and list in `research.md` each one and whether a row marked deleted changes what it shows; fix any that would present a deleted entity as live, in the file found.
- [X] T010 In `RT/KeyValueEntityHost.scala`: `storageEffect` answers `Retention.DeleteNow` with `PekkoEffect.persist(Stored(<the entity's empty state>, deleted = true, expiryMillis = 0L))` in place of `PekkoEffect.delete`; the command handler shows `empty.value` when the stored state is deleted, as it does when it has expired. In `RT/remote/RemoteKeyValueHost.scala`: the same, persisting `RemoteState(None, deleted = true, 0L)`.
- [X] T011 In `RT/ProjectionRuntime.scala`: `ViewStateHandler` and `ConsumerStateHandler` treat an `UpdatedDurableState` whose record has `deleted = true` as the deletion (`view.onDelete` / `consumer.onDelete`) at that update's revision, and `ConsumerStateHandler` passes `revisionOf(change)` where it passes `0L`. In `RT/remote/RemoteProjection.scala`: the remote view and consumer handlers do the same (`handle(subject, revision, None)`). Keep the `DeletedDurableState` branches. T008's suite goes green; T007's stays green.
- [X] T012 Remote-path cases in `SCT/RemoteProjectionSuite.scala` and `SCT/RemoteEntitySuite.scala` (through `SCT/ProcessDouble.scala`): a remote key value entity deleted and written again continues its revision; a remote view's row is removed and a remote consumer receives `deleted = true` at the deletion's revision. `sbt 'sidecar/testOnly *ConformanceSuite'` still green, `kv.delete-then-fresh` included.

**Checkpoint**: protocol 1.3 compiles everywhere and the copies are identical; a key value entity's deletion reaches its views and consumers, in process and remote; no stored form changed. Nothing yet publishes several messages.

---

## Phase 3: User Story 1 — A consumer publishes several messages for one change (Priority: P1) 🎯 MVP

**Goal**: a handler returns several messages; each goes to the consumer's topic in order, each may
name its record key; the change is handled when all are accepted. A consumer that produces one
message is untouched. In all four SDKs.

**Independent Test**: a consumer returning three messages with three keys per event, against the
in-memory broker and against Kafka: three records per event, in order, under the keys given; with
the second publication made to fail, the change is redelivered and the topic ends holding all
three.

### Tests for User Story 1 (write first; they fail)

- [X] T013 [P] [US1] `CORET/effect/ConsumerEffectSuite.scala`: `Outgoing` defaults, `withKey` and `withMetadata`; `withKey("")` raises; `effects.message` and `effects.produceAll` build inert values; the normalising function gives one entry for `Produce`, n for `ProduceAll`, none for `Done`, `Ignore` and an empty `ProduceAll`.
- [X] T014 [US1] Add a fan-out consumer over a topic to `TKT/TopicComponents.scala` and cases to `TKT/TopicSourceSuite.scala` (US1 scenarios 1–7 for a topic source): three records in the order returned; a named key is the record key and `ce-subject` is still the subject; no key means the subject; three different `ce-id`s; an empty list publishes nothing and the next message is still handled; with `broker.failNext` on the second publication the message is redelivered and all three payloads end in the topic, the first at least twice; a consumer with a handler but no `produceTo` that returns `produceAll` fails the change; the existing single-message cases pass unmodified.
- [X] T015 [US1] `TKT/ProduceAllEntitySourceSuite.scala` with `TKT/ProduceAllComponents.scala`, over Postgres with `InMemoryBroker`, for an event sourced source and a key value source: order and keys; `onDelete` returning several messages; (V3) redelivery after `failNext` for each source, asserting on the published records themselves; a result over 4 MiB fails the change with an error naming the consumer, the subject and the limit, and publishes nothing.
- [X] T016 [P] [US1] Cases in `TKT/KafkaSuite.scala`: against a real broker the record key of a keyed message is the named key and the `ce-subject` header is the entity's id; an un-keyed message of a `produceAll` is keyed by subject.
- [X] T017 [US1] Cases in `SCT/RemoteProjectionSuite.scala`, with `SCT/ProcessDouble.scala` able to script a `produce_all` answer: order, named and default keys, the subject default, `ankka.manifest`; an empty list; redelivery on `failNext`; (V5) a reply over the transport's 4 MiB limit fails with an error naming the consumer and the limit — record the transport's own message in `research.md`.

### Implementation for User Story 1

- [X] T018 [US1] `CORE/effect/ConsumerEffect.scala`: `Outgoing`, `ConsumerEffect.ProduceAll`, `ConsumerEffects.message` and `produceAll`, and one normalising function from an effect to its outgoing messages, per `contracts/scala-api.md`. `Produce` keeps its two fields. T013 green.
- [X] T019 [US1] Keyed publishing (R2): the keyed `publish` with its failing default on `MessagePublisher` and `Published.key`/`recordKey` in `RT/MessagePublisher.scala`; `InMemoryBroker` keys and `failNext(topic, after)` in `RT/MessageSubscriber.scala`; `KafkaPublisher` keying by `key.orElse(subject)` in `RT/Kafka.scala`.
- [X] T020 [US1] `ProjectionSupport.publishAll` in `RT/ProjectionSupport.scala` (R3, R6): default each message's subject, refuse an empty key, apply the 4 MiB bound before publishing anything, hand every message to the publisher in order, complete when all have, fail if any did; `applyConsumer` routes `ProduceAll` to it and leaves the `Produce` branch as it is. T014–T016 green.
- [X] T021 [US1] `RemoteConsumer.handle` in `RT/remote/RemoteProjection.scala` routes `ConsumerOutcome.ProduceAll` through `publishAll` with the payloads' `ankka.manifest` and `ankka.content-type`, and wraps a transport overrun in an error naming the consumer, the subject and the limit. T017 green.
- [X] T022 [US1] Conformance for plain messages: give every target in `CONF/ConformanceTarget.scala` (in-process, process, module) an `InMemoryBroker` through `ProjectionRuntime.withBroker`; add `checkout-fanout` to `CONF/ConformanceReference.scala` as `contracts/protocol.md` specifies; add the cases `consumer.produce-all-in-order`, `consumer.produce-all-keys`, `consumer.produce-all-empty`, `consumer.produce-all-redelivers` and `consumer.single-produce-unchanged` to `CONF/ConformanceSuite.scala` and their rows to `specs/009-polyglot-runtimes/contracts/conformance.md`. Show each new case failing against the reference with `checkout-fanout`'s `produceAll` replaced by a single `produce`, then green; confirm `ANKKA_CONFORMANCE_ONLY='consumer.produce-all-keys'` runs exactly one case.
- [ ] T023 [P] [US1] Python: `Message`, `ProduceAll`, `effects.message`, `effects.produce_all` in `PY/src/ankka/effects/consumer.py`; `Consumer._handle` accepts `ProduceAll` in `PY/src/ankka/consumer.py`; `Metadata.protocol` in `PY/src/ankka/context.py`; `ConsumerServicer.Handle` in `PY/src/ankka/server.py` replies `produce_all` after the guard of `contracts/protocol.md`. Tests first, in `PY/tests/test_other_kinds.py` with a fan-out consumer in `PY/tests/kinds.py`: order, keys, metadata, the empty list, `key=""` refused, and the guard — a request with no `ankka.protocol`, and one at `1.2`, fail with the contract's message while a single un-keyed `produce` still replies. Add `checkout-fanout` to `PY/examples/shopping_cart/conformance.py`. `uv run mypy && uv run pytest -q && uv run conformance` green.
- [ ] T024 [P] [US1] TypeScript: the `produceAll` effect and `OutgoingMessage` in `TS/src/effects/stateless.ts`; a `sequenceNumber` getter on `Consumer` in `TS/src/consumer.ts`; `handleConsumer` in `TS/src/server/stateless.ts` replies `produceAll` after the guard; exports in `TS/src/index.ts`. Tests first, in `TS/test/other-kinds.test.ts` with a fixture in `TS/test/fixtures/kinds.ts`: the same list as T023. Add `checkout-fanout` to `TS/examples/shopping-cart/conformance.ts`. `npm run typecheck && npm test && npm run conformance` green.
- [ ] T025 [P] [US1] Rust: `Outgoing`, `ConsumerEffect::ProduceAll`, `message`, `produce_all` and `produce_with` in `RS/ankka/src/effects/consumer.rs`; dispatch and the guard in `RS/ankka/src/components/consumer.rs`; `Metadata::protocol` in `RS/ankka/src/context.rs`; re-exports in `RS/ankka/src/prelude.rs`. Tests first, in `RS/ankka/tests/kinds.rs`: the same list as T023, the guard as a panic with the contract's message. Add `checkout-fanout` to `RS/examples/shopping-cart/src/conformance.rs`, and a `produce_all` case to `SCT/WasmHostSuite.scala`. `cargo fmt --all --check`, `cargo clippy --workspace --all-targets --features ankka/testkit -- -D warnings`, `cargo test -p ankka`, `./conformance.sh` (both shapes) green.

**Checkpoint**: a consumer in any of the four languages fans one change out into keyed messages; the five conformance cases pass against all four targets; every earlier consumer suite passes unmodified.

---

## Phase 4: User Story 2 — A service publishes its entities as graph deltas (Priority: P1)

**Goal**: a graph consumer's handler says which elements a change leaves in which state and
returns them; each is published as a delta the ankka-flow merge sink accepts, keyed by element,
versioned by the change's sequence number, with no JSON, key or version written by the author.

**Independent Test**: a graph consumer over the sample cart publishes the cart graph; every
record's key and value agree with the shared fixtures' rules and read back as the elements built.

### Tests for User Story 2 (write first; they fail)

- [ ] T026 [P] [US2] `CORET/graph/GraphDeltaSuite.scala`: for every row of `FX/keys.json` and `FX/deltas.json`, the delta built from the row's fields has the row's key and serialises to a value that reads back equal to the row's delta; `read(key, value)` refuses a wrong and a missing key with the element key in the message; the reader refuses what the contract's validation table lists and accepts a version of 0; `2.0` and `2` read as the same property value.
- [ ] T027 [P] [US2] `SDKT/graph/GraphElementsSuite.scala`: every row of `FX/refused.json` is refused for the reason its `why` names; a non-finite float is refused; `at(0)` and `at(-1)` are refused; an element with no stated version takes the change's sequence number, one with a stated version keeps it; a change at sequence 0 with no stated version is refused with the "no sequence number" message; the same kind and id twice in one result is refused, and a node and an edge sharing an id are not.
- [ ] T028 [US2] `CART/src/test/scala/shoppingcart/CartGraphSuite.scala`, in the manner of `CartViewSuite.scala` with `InMemoryPublisher`: for the scripted history of `contracts/graph-builder.md` the records published to `cart-graph` are exactly the expected `(key, delta)` list with versions 1–4, `ce-type` `ankka.graph-delta.v1` and `ce-subject` the cart's id; a node and edge of the same id would have different keys (US2 scenario 4, with a fixture consumer in the suite).

### Implementation for User Story 2

- [ ] T029 [US2] `CORE/graph/GraphDelta.scala` (and its codec beside it): the four cases, `key`, `SchemaName`, `nodeKey`/`edgeKey`, `serializer` with manifest `ankka.graph-delta.v1`, and the two `read`s, per `contracts/scala-api.md` and the rules of `contracts/graph-builder.md`. No Pekko, no dependency added. T026 green.
- [ ] T030 [US2] `SDK/graph/`: `GraphElement`, `GraphElements`, `GraphEffect`/`GraphEffects`, `GraphConsumer` and its `Companion`, and the adapter that makes a `ConsumerDescriptor` of it — resolving versions, refusing duplicates and no-sequence, setting `ce-type`, and returning `ProduceAll` with each element's key. T027 green.
- [ ] T031 [US2] `CART/src/main/scala/shoppingcart/application/CartGraph.scala` as in `contracts/scala-api.md`, with `// docs:start graph-consumer` / `// docs:end graph-consumer` around the class and its companion; register it in `CART/src/main/scala/Main.scala` beside `CheckoutNotifier`, under the same broker condition. T028 green; the file contains no literal `node:`/`edge:` and no version.
- [ ] T032 [US2] Conformance: add `cart-graph` to `CONF/ConformanceReference.scala`; add `consumer.graph-deltas` to `CONF/ConformanceSuite.scala`, comparing records as read deltas against one expected list, and its row to `specs/009-polyglot-runtimes/contracts/conformance.md`. Show it failing with `cart-graph` removed from the reference.
- [ ] T033 [P] [US2] Python: `PY/src/ankka/graph.py` per `contracts/python-api.md` (`Element`, `Graph`, `GraphEffects`, `GraphConsumer`, `read`, `node_key`, `edge_key`, `SCHEMA_NAME`), exported from `PY/src/ankka/__init__.py`; discovery and dispatch of a `GraphConsumer` as a consumer in `PY/src/ankka/service.py` and `PY/src/ankka/server.py`. Tests first, `PY/tests/test_graph.py`: the three fixture files as in T026/T027, non-finite floats, `bool` not an integer, duplicates, no-sequence. Add `PY/examples/shopping_cart/cart_graph.py` (with `# docs:start graph-consumer` markers), register it in `examples/shopping_cart/main.py`, add `cart-graph` to `examples/shopping_cart/conformance.py`. `uv run mypy && uv run pytest -q && uv run conformance` green.
- [ ] T034 [P] [US2] TypeScript: `TS/src/graph.ts` per `contracts/typescript-api.md` (the element types, `Graph`, `GraphEffects`, `GraphConsumer`, `readDelta`, `nodeKey`, `edgeKey`, a hand-written `Codec`), exported from `TS/src/index.ts`; registration in `TS/src/service.ts` and dispatch in `TS/src/server/stateless.ts`. Tests first, `TS/test/graph.test.ts`: the three fixture files, safe-integer and `bigint` rules, integers beyond 2⁵³ read back as `bigint`, non-finite, duplicates, no-sequence. Add `TS/examples/shopping-cart/cartGraph.ts` (docs markers), register it in `examples/shopping-cart/main.ts`, add `cart-graph` to `examples/shopping-cart/conformance.ts`. `npm run typecheck && npm test && npm run conformance` green.
- [ ] T035 [P] [US2] Rust: `RS/ankka/src/graph.rs` per `contracts/rust-api.md` (`Value`, `Element`, the four builders, `GraphEffect`, `GraphConsumer`, `read`, the key functions), `kinds::GraphConsumer` and its `ComponentOf` implementation in `RS/ankka/src/components/mod.rs`, module and prelude exports. Tests first, `RS/ankka/tests/graph.rs`: the three fixture files, non-finite, duplicates, no-sequence, and a compile-fail case in `RS/ankka/tests/compile_fail/` showing a graph consumer has no way to call `consumer::produce`. Add `RS/examples/shopping-cart/src/cart_graph.rs` (docs markers), register it in `src/lib.rs`, add `cart-graph` to `src/conformance.rs`. fmt, clippy, `cargo test -p ankka`, `cargo doc -p ankka --no-deps --all-features` with `RUSTDOCFLAGS=-D warnings`, `./conformance.sh` green.

**Checkpoint**: `consumer.graph-deltas` passes against the Scala reference, the Python and TypeScript processes and the Rust module in both shapes — four languages publish the same records for one history (SC-003a); every SDK agrees with every fixture row (SC-003).

---

## Phase 5: User Story 3 — Publishing is safe to repeat, and versions never go backwards (Priority: P2)

**Goal**: a redelivered change publishes equal deltas; versions come from the event's sequence
number or the state's revision; a deletion's tombstone outranks everything before it; an entity
created again outranks its tombstone; a source with no sequence number needs a stated version.

**Independent Test**: publish one entity's history, redeliver it, and compare; delete the entity
and create it again and read the versions published: they rise throughout.

- [ ] T036 [US3] `TKT/GraphComponents.scala` (graph consumers over an event sourced entity, a key value entity and a topic) and `TKT/GraphVersionsSuite.scala` over Postgres with `InMemoryBroker`, one case per US3 scenario: a change handled twice (forced with `failNext`) publishes equal `(key, version, delta)`; event sourced versions are the events' sequence numbers; key value versions are revisions, never 0; a tombstone from `onDelete` is above every earlier version, for both kinds of entity; delete then create again publishes above the tombstone, for both kinds; a topic source with no stated version fails the message with the "no sequence number" error and with one publishes; a stated version replaces the sequence number.
- [ ] T037 [US3] Fix what T036 shows, in the file it points at (`SDK/graph/` for version resolution, `RT/ProjectionRuntime.scala` or `RT/KeyValueEntityHost.scala` for sequence numbers), until it is green; record anything that was not as the plan assumed in `research.md`.
- [ ] T038 [US3] Conformance: add `log-graph` to `CONF/ConformanceReference.scala`; add `consumer.graph-delete-and-recreate`, `consumer.graph-replay-is-equal`, `consumer.kv-sequence`, `kv.delete-is-a-change` and `kv.delete-then-write` to `CONF/ConformanceSuite.scala` and `specs/009-polyglot-runtimes/contracts/conformance.md`, as `contracts/protocol.md` specifies. Show each failing without the piece it tests.
- [ ] T039 [P] [US3] Python: add `log-graph` to `PY/examples/shopping_cart/conformance.py`; cases in `PY/tests/test_graph.py` for a stated version, the no-sequence refusal from a topic source, and equal output for the same change twice. `uv run conformance` green on the five new cases.
- [ ] T040 [P] [US3] TypeScript: add `log-graph` to `TS/examples/shopping-cart/conformance.ts`; the same cases in `TS/test/graph.test.ts`. `npm run conformance` green on the five new cases.
- [ ] T041 [P] [US3] Rust: add `log-graph` to `RS/examples/shopping-cart/src/conformance.rs`; the same cases in `RS/ankka/tests/graph.rs`. `./conformance.sh` green on the five new cases in both shapes.

**Checkpoint**: all eleven conformance cases of `contracts/protocol.md` pass against all four targets.

---

## Phase 6: User Story 4 — A graph in the database with no mapper (Priority: P2)

**Goal**: the sample service beside an ankka-flow pipeline whose only streamlet is the built-in
merge sink fills a graph database, survives a restart, and is rebuilt from the topic.

**Independent Test**: quickstart tier 7, on the local cluster.

- [ ] T042 [US4] `CART/graph/blueprint.conf` (one streamlet, `builtin/neo4j-merge`; one managed topic named `cart-graph`, the name `CartGraph` publishes to), `CART/graph/k8s/in-cluster.conf` (the broker, the connection Secret), and `CART/graph/README.md` with the steps of quickstart tier 7 and the script of carts to run. `flow verify CART/graph/blueprint.conf` (ankka-flow 0.3.0 or later) prints the note that the topic is compacted. `python3 .github/ci-coverage.py` claims the directory.
- [ ] T043 [US4] Run quickstart tier 7 steps 1–8 with the Scala sample on the local cluster: the pipeline first, the topic compacted; the service with `ANKKA_KAFKA_BOOTSTRAP_SERVERS`; the scripted run and the expected graph; every record under its element key; a restart mid-run; a replay counted stale by the sink; a rebuild with only the sink reset; discard and add again. Record each step's result in `CART/graph/README.md` and in `research.md`. Fix what it finds in the file it points at.
- [ ] T044 [US4] Settle V7 (how the Python example behind a sidecar and the Rust module are deployed to the local cluster — `docker-compose.yml` builds both) and repeat steps 2–4 with each in place of the Scala service, into an emptied database; the graph is the same. Record it beside T043's.

**Checkpoint**: SC-005 to SC-008 observed and recorded, in three hosting modes.

---

## Phase 7: User Story 5 — It is tested without a broker (Priority: P3)

**Goal**: a consumer's unit test hands it a change at a stated sequence number and reads back
messages with their keys, or elements, with nothing started.

**Independent Test**: a unit test of each example's graph consumer asserting on elements, with no
broker and no runtime.

- [ ] T045 [US5] Tests first, `TKT/ConsumerTestKitSuite.scala` (US5 scenarios 1 and 3, and that no actor system is started); then `TK/ConsumerTestKit.scala` per `contracts/scala-api.md`: `ConsumerTestKit` and `GraphConsumerTestKit`, reading a result through the normalising function of T018 and the adapter of T030, payloads round-tripped through the output serializer, a refusing `ComponentClient` by default.
- [ ] T046 [US5] `CART/src/test/scala/shoppingcart/CartGraphUnitSuite.scala` with `// docs:start graph-test` markers: each row of the cart graph table asserted on elements through `ConsumerTestKit.graph(CartGraph)`. A case in `TKT/TopicSourceSuite.scala` reads `broker.publishedTo` records back with `GraphDelta.read(key, value)` (US5 scenario 2).
- [ ] T047 [P] [US5] Python: `sequence` on `on_message`/`on_delete`, `messages` with keys and metadata, and `GraphConsumerTestKit` in `PY/src/ankka/testkit/unit.py`, exported from `ankka.testkit`; the kits set `ankka.protocol`. Tests in `PY/tests/test_other_kinds.py` and `PY/tests/test_graph.py`; the cart graph's unit tests with docs markers in `PY/examples/shopping_cart/test_cart.py`. `produced` keeps its meaning.
- [ ] T048 [P] [US5] TypeScript: `key` on `produced` entries and `GraphConsumerTestKit` in `TS/src/testkit/kinds.ts`, exported from `TS/src/testkit/index.ts`. Tests in `TS/test/other-kinds.test.ts` and `TS/test/graph.test.ts`; the cart graph's unit tests with docs markers in `TS/examples/shopping-cart/cart.test.ts`.
- [ ] T049 [P] [US5] Rust: `ConsumerTestKit::at`, `messages` and `GraphConsumerTestKit` in `RS/ankka/src/testkit/kinds.rs`, with `feed` setting `ankka.sequence` and `ankka.protocol`. Tests in `RS/ankka/tests/unit_testkit.rs`; the cart graph's unit tests with docs markers in `RS/examples/shopping-cart/tests/cart.rs`.

**Checkpoint**: SC-004 — the four examples' graph tests run with nothing started; the Scala ones in under a second.

---

## Phase 8: User Story 6 — Publishing a graph is documented (Priority: P3)

**Goal**: one guide from an entity to elements in a graph database, the consumers and topics
guides covering several messages and keys, and the references describing both, in four languages
from tested code.

**Independent Test**: `just docs-sync && just docs` reports no problems; a reader with the sample
and the guide reaches a graph.

- [ ] T050 [P] [US6] The tested regions for "when events do not carry the whole state" (FR-023a), one per language, each a graph consumer that reads the cart through the component client and publishes the number of lines as a property, with a unit test using a client double and `docs:start graph-from-state` markers: `CART/src/main/scala/shoppingcart/application/CartContentsGraph.scala` with `CART/src/test/scala/shoppingcart/CartContentsGraphSuite.scala`; `PY/examples/shopping_cart/cart_contents_graph.py` with cases in `test_cart.py`; `TS/examples/shopping-cart/cartContentsGraph.ts` with cases in `cart.test.ts`; `RS/examples/shopping-cart/src/cart_contents_graph.rs` with cases in `tests/cart.rs`. Not registered in any conformance reference.
- [ ] T051 [US6] Write `DOCS/build/graph.md` (*Publish a graph*; kind guide; languages scala, python, typescript, rust; components consumer): a graph consumer in four tabs included from `graph-consumer`; what is published (key, version, value) with one record shown; the writer's rules in ankka's terms (FR-036); thin events and reading state, included from `graph-from-state`, saying the state may be ahead and converges; deletion and tombstones, that expiry tombstones nothing (FR-020b), and that a topic source needs stated versions; testing, included from `graph-test`; the topic — compacted, created by the pipeline that declares it, deploy the pipeline first, what happens otherwise (FR-037); where ankka's part ends, linking to ankka-flow's sink and rebuild guides. Add it to `mkdocs.yml`'s `nav` after *Broker topics*.
- [ ] T052 [P] [US6] `DOCS/build/consumers.md`: a section on returning several messages and naming a key, in four tabs from tested code (mark regions in `CART`'s and the three examples' fan-out or add one small tested consumer per language where none fits); when a change counts as handled and that a failure redelivers a batch of changes; add `rust` to its `languages` and a Rust tab to every tab set, using the `consumer` region `RS/examples/shopping-cart/src/checkout_notifier.rs` already carries; the repeat-safety section says deltas are safe by construction. `DOCS/build/topics.md`: the record key and the subject are separate; the headers table; ordering by key.
- [ ] T053 [P] [US6] `DOCS/build/key-value-entities.md` and `DOCS/build/views.md`: a deletion is a recorded change, the id stays usable, a view's row is removed, the row that remains holds no data. `DOCS/build/testing.md`: the consumer test kits in four tabs. `DOCS/concepts/consistency.md`: what a consumer's publication guarantees with several messages.
- [ ] T054 [P] [US6] References: `DOCS/reference/scala-sdk.md`, `python-sdk.md`, `typescript-sdk.md`, `rust-sdk.md` (the several-message effect, the graph consumer, the reader, the test kits); `DOCS/reference/sidecar-protocol.md` (the generated table via `just docs-sync`, version 1.3, `ankka.protocol`, the SDK's obligation, the 4 MiB bound); `DOCS/reference/wasm-abi.md` (the same metadata entry and reply); `DOCS/reference/limitations.md` (no topics created or checked; expiry tombstones nothing; one writer per element is not enforced; no delete markers); `DOCS/reference/glossary.md` (record key, element, element key, delta, graph consumer).
- [ ] T055 [US6] Skills: add `build/graph.md` to `pages:` and the rules and mistakes to check for in `SKILL/ankka-views-consumers/SKILL.md`, `SKILL/ankka-python/SKILL.md`, `SKILL/ankka-typescript/SKILL.md` and `SKILL/ankka-rust/SKILL.md` (a delta built by hand; a key set on a delta; an element another entity owns; a thin event published as whole state). `just docs-sync && just docs` until no problems; commit the rendered skills under `marketplace/plugins/ankka/skills` and `ankka.g8/src/main/g8/.claude/skills`.

**Checkpoint**: the docs build is clean; every sample on the new and changed pages is included from code a test runs.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T056 Add to `CLAUDE.md`, where each belongs (Architecture, Traps): a record's key and its subject are separate, and `MessagePublisher`'s keyed method fails by default; a key value deletion is a persisted state, never a row delete, and why; `protocol/fixtures/graph-deltas/` is copied from ankka-flow and is not `EncodingFixturesSuite`'s; `ankka.protocol` and the guard; `runtime` and `sidecar` hold no graph code.
- [ ] T057 The whole build, from the worktree: `sbt scalafmtCheckAll scalafmtSbtCheck`, `sbt compile` warning-free, `caffeinate -i sbt test`, the three SDK command lines of quickstart tier 5 including each conformance run, `sbt shoppingCart/test`, `just docs`, `python3 .github/ci-coverage.py`. Tick the reviewer's checklist in `quickstart.md`, checking each item rather than assuming it.
- [ ] T058 Compare `FX/keys.json` and `FX/deltas.json` with ankka-flow's at the tag that contains T001, update `FX/SOURCE.md` to name that tag, and re-run the copy scripts if anything moved.
- [ ] T059 Write "Found during implementation" in `research.md` (each V-item's answer, anything the plan had wrong) and the pull request description: protocol 1.3 and what a 1.2 runtime does with a newer SDK; a key value deletion is now a recorded change and a view's row goes with it; a Scala consumer over a key value entity now reads the revision; Rust's `ConsumerEffect` has a new variant; the ankka-flow pull request this depends on.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 is in ankka-flow and comes first; T002 copies from it; T003 checks.
- **Foundational (Phase 2)**: T004 → T005 → T006 in order (one wire). T007 → T008 → T009 →
  T010 → T011 → T012 in order (one behaviour), independent of T004–T006: the two groups can
  proceed side by side.
- **US1 (Phase 3)**: needs T004–T006. Not the key value group, except that T015's key value case
  reads the revision (T011).
- **US2 (Phase 4)**: needs US1's T018–T021 (the effect and keyed publishing) and T002. Each SDK's
  US2 task needs that SDK's US1 task.
- **US3 (Phase 5)**: needs US2, and the key value group.
- **US4 (Phase 6)**: needs US2 for the Scala run (the cart is event sourced); T044 needs T033 and
  T035. Needs the ankka-flow release that the local cluster runs to be 0.3.0 or later.
- **US5 (Phase 7)**: needs US1 (messages) and US2 (elements). It may be pulled ahead of US3 and
  US4; nothing in them depends on it.
- **US6 (Phase 8)**: T050 needs US5's kits; T051 needs T050, T031–T035 and T046–T049 for its
  includes; T055 last.
- **Polish (Phase 9)**: after everything it describes.

### Within Each User Story

- Tests before the code they hold; a new test is seen to fail first.
- Scala and the runtime before the three SDKs: the conformance cases are written against the Scala
  reference and the SDKs then pass them unchanged.
- The same file is never edited by two tasks marked [P].

### Parallel Opportunities

- T004–T006 beside T007–T012.
- T013 and T016 with each other; T014, T015 and T017 share no file with them.
- T023, T024, T025 — three SDKs, three directories.
- T026 and T027; then T033, T034, T035.
- T039, T040, T041. T047, T048, T049. T052, T053, T054.

## Parallel Example: User Story 1

```text
# once T018–T022 are green against the Scala reference:
T023  Python      sdks/python      effects, servicer, guard, conformance reference
T024  TypeScript  sdks/typescript  effects, handler, guard, conformance reference
T025  Rust        sdks/rust        effects, dispatch, guard, conformance reference, WasmHostSuite
```

## Implementation Strategy

### What can ship on its own

1. **Phase 2's key value group** (T007–T012): a repair to views and to key value id reuse, with
   no new surface. A pull request by itself, ahead of the rest.
2. **MVP — US1** (Phases 1–3): several keyed messages from a consumer, in four languages, at
   protocol 1.3. Useful with no graph in sight.
3. **US2 + US3**: the graph consumer and the guarantees that make its output trustworthy.
4. **US5, US6**: the test kits and the documentation; **US4** is the proof on a cluster.

### Order for one person

Phase 1 → the key value group → the protocol group → US1 (Scala, then the three SDKs) → US2
(Scala, then the three SDKs) → US3 → US5 → US4 → US6 → Polish. US5 is taken before US4 and US6
because the guide includes its tests.

### Notes

- A task that says "fix what it shows" has no fixed file because the test decides; record the
  finding in `research.md` when the plan had it wrong.
- Commit at each checkpoint, and T007 on its own before T010.
- Stop at a checkpoint that is not green. Do not carry a failing conformance case forward by
  narrowing `ANKKA_CONFORMANCE_ONLY`.
