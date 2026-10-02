# Implementation Plan: Graph Delta Publisher — a Service Publishes Its Entities as a Graph

**Branch**: `019-graph-delta-publisher` | **Date**: 2026-10-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/019-graph-delta-publisher/spec.md`

## Summary

A service publishes its entities as a graph with one consumer. Two things make that possible: a
consumer's handler can return **several messages for one change, each under its own record key**,
and a **graph delta builder** turns "this node, this edge, this one is gone" into records the
ankka-flow merge sink accepts — keyed by element, versioned by the change's sequence number. The
pipeline that fills the graph database is then the built-in sink alone. All four SDKs with
consumers get both: Scala, Python, TypeScript and Rust.

Technically: several messages are a new effect, `ProduceAll`, beside an untouched `Produce` (R1);
the record key becomes a parameter of publishing, separate from the subject (R2); one function
sends a change's messages in order and confirms them together, for the in-process, sidecar and
WebAssembly paths alike (R3). The wire gains one oneof case and two messages, as protocol 1.3
(R4), and the runtime states its protocol on every consumer request so a newer SDK on an older
runtime fails a change instead of losing its messages (R5). A delta is a value in `core`; the
runtime publishes bytes under keys and never learns the word "graph" (R10). One contract of
builder rules is implemented four times and held together by shared fixtures, two of them
ankka-flow's own (R11, R12), and by conformance cases that compare the records four languages
publish for one history (R15).

Planning found one thing the spec had assumed otherwise: **deleting a key value entity removes its
row**, so no view or consumer is told and its revisions restart. A tombstone could never be
published for one, and a re-created one would never reappear in a graph. The plan makes a key
value deletion a recorded state, as an event sourced deletion is a recorded event, using a field
the stored form already has (R7). It is the first slice of work, it stands on its own, and it
repairs views over key value entities, which have never had a row removed.

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`core`, `sdk`, `runtime`, `testkit`, `sidecar`,
`controlplane-api`, the shopping cart sample); Python ≥ 3.12 (`sdks/python`); TypeScript 7 on
Node ≥ 22 (`sdks/typescript`); Rust 1.88, edition 2024, target `wasm32-unknown-unknown`
(`sdks/rust`); protobuf (`protocol`)

**Primary Dependencies**: none added, in any language. Scala: jsoniter through `Codecs`, Pekko
Projection and the Kafka producer already in `runtime`. Python: the standard library's `json`.
TypeScript: the SDK's own JSON writer and reviver. Rust: `serde_json`, already a dependency.

**Storage**: Postgres through the existing durable state store. **No DDL change and no change to
any stored form**: a key value deletion writes `StateRecord.deleted = true`, a field that exists
and has only been written `false` (R7). Kafka for published messages, as today.

**Testing**: munit in `core`, `sdk`, `testkit` (Postgres, `InMemoryBroker`), `sidecar`
(`RemoteProjectionSuite`, `WasmHostSuite`, `ConformanceSuite`) and the sample; `KafkaSuite` for
record keys on a real broker; pytest with mypy strict; Node's test runner with `tsc`; `cargo test`
with clippy and rustdoc at `-D warnings`; each SDK's conformance run; the docs build. One run on
the local cluster with ankka-flow. No new k3s suite.

**Target Platform**: wherever an ankka service runs — in process, behind a sidecar, or as a
WebAssembly module. The reader of what it publishes is ankka-flow 0.3.0 or later.

**Project Type**: platform libraries and a protocol (four SDKs, the runtime, the sidecar), a
sample, documentation

**Performance Goals**: publishing n messages for a change costs one batch to the producer, not n
round trips (R3); a single `produce` takes the path it takes today, at today's cost; the pure and
unit suites of every SDK stay under a minute; the sample's graph tests run in under a second with
nothing started (SC-004)

**Constraints**: `Produce` unchanged in Scala and on the wire; `runtime` and `sidecar` contain no
graph code; ankka depends on no ankka-flow artifact; the three SDK copies of `protocol/` identical
to the canonical one; `protocol/fixtures/` at its top level untouched; a service built against
protocol 1.2 runs unchanged; a rolling upgrade safe in both directions; `Test / parallelExecution
:= false` stays; warning-free in every language; no suite binds a fixed port

**Scale/Scope**: about 12 new Scala files and 16 changed across six modules, with 9 new suites
and 6 changed; per SDK about 3 new source files, 4 changed, 3 new test files, one example and one
conformance reference changed; 1 protocol file; 4 fixture files; 1 new docs page and about 16
changed; 4 skills; and one test-data change in ankka-flow, first

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | `ProduceAll` is a list of values; `effects.publish(…)` builds a `GraphEffect` and does nothing. Versions are resolved by an adapter after the handler returns, not by the builder reaching for a context (R13) |
| Where two interpreters reduce the same thing, they share one function | pass | `ProjectionSupport.publishAll` is the one place a change's messages are keyed, sent and confirmed, called by the in-process path and by `RemoteConsumer` for sidecar and WebAssembly (R3). The test kit reads a result through the same normalising function the runtime uses |
| Module dependency direction | pass | `GraphDelta` in `core`, `GraphConsumer` in `sdk`, `ConsumerTestKit` in `testkit`; `runtime` gains a keyed publish and a list, and no graph (R10) |
| `runtime` never sees the generated protocol | pass | `ConsumerOutcome` gains a case in plain Scala values (`remote/Conversation.scala`); `sidecar`'s `Translate` maps the new message |
| No classpath scanning; explicit registration | pass | a graph consumer's companion yields a `ConsumerDescriptor` registered like any other; no new component kind, in discovery or anywhere |
| Wire names are a versioning boundary | pass | the delta's field names, `ankka.graph-delta.v1`, `ankka.protocol` and the new proto fields are declared strings and numbers; the protocol change is a minor by its own rule (R4) |
| The protocol directory is copied whole, and CI proves it | pass | one edit to `protocol/`, three runs of the SDKs' copy scripts; fixtures travel the same way (R12) |
| `protocol/fixtures/` belongs to `EncodingFixturesSuite` | pass | the new files live in a subdirectory, as `autonomous/` does; the top level is untouched (R12) |
| Stored forms stay readable both ways | pass | no stored form changes; a suite pinning `StateRecord`'s bytes is written before the hosts change (R7) |
| Never touch `ActorContext` from a `Future` callback | pass | publishing happens in the projection handler's future, as it does today; the entity hosts change one effect each |
| Tests are serialised; a test never binds a fixed port | pass | nothing changes in `build.sbt`'s test settings; the broker in new suites is in memory or the suite's own container |
| An `eventually` waits for the thing it asserts | pass | suites wait on the published records themselves, not on a proxy |
| Could this check pass while the thing it checks is false? | pass | each conformance case is shown failing without the feature; the `ANKKA_CONFORMANCE_ONLY` trap is named in the quickstart; the "verify first" list makes each inferred fact a test (research) |
| Each acceptance scenario ends as a test that fails without the feature | pass | the tasks are cut by user story, tests before the code they hold |
| Docs: pages stand alone, samples from tested code, new pages in nav and a skill | pass | R17 |
| Testkit-first: unit level with no runtime | pass | Scala gains the `ConsumerTestKit` it lacks; the other three learn sequence numbers, keys and elements (R13, R14) |
| Keep the Justfile thin; every tracked file claimed by a CI path filter | pass | no recipe added; `samples/shopping-cart/graph/` and `protocol/fixtures/graph-deltas/` fall under filters that exist, checked by `.github/ci-coverage.py` |

**Violations to justify**: none against these principles. What widens the platform's surface is
under *Complexity Tracking*.

## Project Structure

### Documentation (this feature)

```text
specs/019-graph-delta-publisher/
├── plan.md              # this file
├── research.md          # R1–R17: decisions with file-level evidence; seven things to verify first
├── data-model.md        # change, outgoing message, result, element, delta, an element's history, stored state
├── quickstart.md        # the validation runs: fixtures → pure → runtime → broker → sample → SDKs → docs → cluster
├── contracts/
│   ├── protocol.md            # the wire at 1.3, what the runtime does with a reply, the guard, conformance cases
│   ├── graph-builder.md       # one statement of the builder's rules, the record, the fixtures, the cart graph
│   ├── key-value-deletion.md  # what a deletion is, before and after, and the tests that hold it
│   ├── scala-api.md
│   ├── python-api.md
│   ├── typescript-api.md
│   └── rust-api.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks output (not created here)
```

### Source Code (repository root)

```text
protocol/
├── src/main/protobuf/ankka/protocol/v1/consumer.proto     # ProduceAll, Message
├── fixtures/graph-deltas/                                  # new: keys.json, deltas.json, refused.json, SOURCE.md
├── README.md                                               # version 1.3; ankka.protocol
└── WASM-ABI.md                                             # the same two facts

modules/core/src/main/scala/…/core/
├── effect/ConsumerEffect.scala          # Outgoing, ProduceAll, produceAll, message; one normalising function
└── graph/                               # new: GraphDelta, its codec, reader, keys

modules/sdk/src/main/scala/…/sdk/
└── graph/                               # new: GraphElement, GraphElements, GraphEffect(s), GraphConsumer, its adapter

modules/runtime/src/main/scala/…/runtime/
├── MessagePublisher.scala               # keyed publish; Published.key
├── MessageSubscriber.scala              # InMemoryBroker: keys, failNext
├── Kafka.scala                          # KafkaPublisher: key, else subject
├── ProjectionSupport.scala              # publishAll; the bound
├── ProjectionRuntime.scala              # key value: revision to consumers; a deleted state is the deletion
├── KeyValueEntityHost.scala             # deletion persists a deleted state
└── remote/{Conversation,RemoteProjection,RemoteKeyValueHost}.scala
                                         # ConsumerOutcome.ProduceAll; ankka.protocol; the same deletion

modules/testkit/src/main/scala/…/testkit/ConsumerTestKit.scala     # new
sidecar/src/main/scala/…/sidecar/Translate.scala                    # the new message, both directions
controlplane-api/…/Protocol.scala                                   # 1.3

samples/shopping-cart/
├── src/main/scala/shoppingcart/application/CartGraph.scala         # new
├── src/main/scala/Main.scala                                       # registered with the broker
└── graph/                                                          # new: the sink-only blueprint and its steps

sdks/python/      src/ankka/{effects/consumer,consumer,server,context}.py, src/ankka/graph.py (new),
                  src/ankka/testkit/unit.py, examples/shopping_cart/{cart_graph.py (new), conformance.py}
sdks/typescript/  src/{effects/stateless,consumer,service,index}.ts, src/server/stateless.ts,
                  src/graph.ts (new), src/testkit/kinds.ts,
                  examples/shopping-cart/{cartGraph.ts (new), conformance.ts}
sdks/rust/        ankka/src/{effects/consumer,components/consumer,components/mod,context,prelude}.rs,
                  ankka/src/graph.rs (new), ankka/src/testkit/kinds.rs,
                  examples/shopping-cart/src/{cart_graph.rs (new), conformance.rs}

sidecar/src/test/scala/…/conformance/     # the broker for targets; the new cases; the Scala reference's components

docs/build/graph.md (new), docs/build/{consumers,topics,key-value-entities,views,testing}.md,
docs/concepts/consistency.md, docs/reference/{scala,python,typescript,rust}-sdk.md,
docs/reference/{sidecar-protocol,wasm-abi,limitations,glossary}.md, mkdocs.yml, tools/docs/skill/*
```

**Structure Decision**: no new module, crate or package to publish. The work follows the layers
that exist: the protocol, then the runtime and Scala surface, then each SDK against the same
contract and fixtures, then the documentation. In ankka-flow, one fixture file and two test
readers, ahead of everything here.

## Order of work

Each slice ends green and could be merged on its own.

0. **ankka-flow**: `deltas.json` and its two readers. Its own pull request there.
1. **A key value deletion is a recorded state** (R7, R8). The pinning suite and the failing suites
   first; then the two hosts and the four projection handlers. Nothing about graphs or several
   messages. Ships the repair to views.
2. **Several messages** (R1–R6): protocol 1.3, the effect, keyed publishing, `publishAll`, the
   remote and WebAssembly paths, the bound, `ankka.protocol`; Scala's `produceAll`; the Scala
   `ConsumerTestKit`; the conformance broker and the plain-message cases against the Scala
   reference. This is user story 1.
3. **The graph in Scala** (R10–R13): fixtures in, `GraphDelta`, `GraphConsumer`, the sample's
   `CartGraph`, the graph conformance cases against the Scala reference. User stories 2, 3 and 5
   for Scala.
4. **Python, TypeScript, Rust** (R14), in parallel: each implements slice 2's reply and guard and
   slice 3's builder, passes the fixtures, extends its test kit and example, and passes the
   conformance cases unchanged.
5. **Documentation and skills** (R17).
6. **The cluster run** (quickstart tier 7), then the fixture comparison against ankka-flow's tag
   and the release notes.

## Complexity Tracking

Nothing violates a principle. These widen the platform's surface and are recorded so the
widening is deliberate:

| Addition | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| A fourth consumer effect and a message type on the wire | one change touches several elements, each with its own key (FR-001, FR-003) | a key on the existing `Produce` changes the one message every SDK already sends (R1) |
| A second method on `MessagePublisher` | the key must reach the broker separately from the subject (FR-004) | a reserved metadata entry would be keyed wrongly, silently, by any publisher that did not know it (R1, R2) |
| `ankka.protocol` on every consumer request | a newer SDK on an older runtime would otherwise lose messages without a sound (FR-029b) | discovery-time knowledge does not survive a fresh WebAssembly instance per call (R5) |
| A key value deletion keeps its row | FR-019, FR-020; views over key value entities never see a deletion today | leaving it out gives a graph that cannot show deletion for one of two entity kinds, and leaves the view defect (R7) |
| `GraphConsumer` as its own class in four SDKs | a graph-publishing consumer must not be able to publish anything else or set a key (FR-012, FR-013) | helper functions on an ordinary consumer leave `produce` and the key within reach |
| A Scala `ConsumerTestKit` | FR-030 in every SDK; Scala alone has none | testing through `AnkkaTestKit` starts a runtime and Postgres for a pure mapping |
| Fixtures owned by another repository | the reader of these records lives there (FR-025) | fixtures written here would prove only that ankka agrees with itself |

## Constitution Check (post-design)

Re-evaluated after Phase 1: unchanged, all pass. The design adds no dependency in any language, no
DDL, no stored form, no published module, no component kind and no configuration key. It adds one
oneof case and two messages to the protocol as a minor version, one metadata entry to consumer
requests, one method with a failing default to `MessagePublisher`, and public types in `core`,
`sdk` and `testkit` and their counterparts in three SDKs.

Corrections made to the spec during planning, recorded in it under *Found in planning*:

- a key value entity's deletion reaches nobody today and its revisions restart; FR-020a and
  FR-020b state what the plan does about it and what expiry does not do;
- only an in-process Scala consumer is handed zero for a key value source;
- a newer SDK on an older runtime needs a guard: FR-029b;
- the delta contract reserves property names, not labels;
- the contract allows a version of zero; the builder refuses it because zero is how ankka presents
  a change with no sequence number;
- a fixture of deltas is added to ankka-flow, as test data, so both repositories read the same
  rows.

## Reported with this plan, not part of it

- **A write after a key value deletion may fail today** within one incarnation of the entity's
  actor: the store is asked to update a row that was removed. Read from the dependency, not run.
  Slice 1 starts with the test, and its fix is the slice's own.
- **Views over key value entities have never had a row removed on deletion.** Slice 1 repairs it;
  services that relied on the row staying will see it go.
- **No suite uses a key value entity as the source of a view or a consumer.** Slice 1 adds them.
- **A failed publication redelivers up to a hundred changes**, the projection's offset batch, not
  one. True today for a single message; documented with this feature.
- **The Rust example's `consumer` region is marked for inclusion and no page includes it**, and
  `reference/wasm-abi.md`'s table of imports omits `send`. The first is used by this feature's
  pages; the second is noted for whoever next edits that page.

## Not in this feature (from the spec's Out of Scope, restated for the tasks)

- The entity's state as a source for an event sourced entity.
- Creating, altering or inspecting topics from ankka, and any startup check of a delta topic.
- Delete markers. Atomic or exactly-once publication. Detecting two writers of one element.
- Tombstoning the elements of an expired entity.
- A version for topic sources taken from the record's offset.
- Any change to `ankka.graph-delta.v1`, to the merge sink, or to how ankka-flow behaves.
- A guide in ankka-flow to filling a graph from an ankka service; other graph databases; the
  console.
