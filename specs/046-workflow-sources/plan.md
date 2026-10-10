# Implementation Plan: Workflow Sources — A View or a Consumer Reads a Workflow

**Branch**: `046-workflow-sources` | **Date**: 2026-10-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/046-workflow-sources/spec.md`

## Summary

A view or a consumer reads a workflow as it reads an entity: `ChangeSource.stateOf(Checkout)` in
Scala, the workflow class where an entity class goes in Python, TypeScript and Rust. Each change is
a state the workflow recorded, and it carries the workflow's **standing** once the whole effect that
recorded the state is applied — running, paused, completed or failed, the step, the retries, the
reason — which Akka's workflow source does not. A transition, pause, end, failure or retry that
records no state is no change. Delivery, rebuild, deletion and the topology are the entity source's.

Technically: the engine stamps the standing onto the `state` record it is about to persist, an
optional field Jackson reads as absent on every record written before (R2); one function reads a
workflow record as a change for every host, in-process, remote and module alike (R3); the five
event handlers gain a record type so a workflow source runs the same exactly-once projection an
entity's does (R4); the standing rides on the change's context (R5); the wire gains a
`WorkflowStanding` on the two requests at protocol 1.15, gated both ways as socket routes are (R6).

Planning found seven things the spec had assumed otherwise; `research.md` opens with them. The
ones that change what a reader should expect:

- **The journal's reader is typed by record, and a workflow's records are not an entity's.** "Can
  read it today" was true of the plugin and false of every handler (R1, R4).
- **A remote workflow's state carries its manifest inside its bytes**, so the one reader of a
  workflow record knows two packings (R3).
- **The journal is Jackson CBOR**, so the stamp is an `Option` field, and a compatibility suite
  for `WorkflowRecord` is written for the first time (R2).
- **A standing can be `NotStarted`**: a command that updates the state and transitions nowhere.
  The glossary's four words gain it (R2).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`sdk`, `runtime`, `testkit`, `sidecar`,
`controlplane-api`, `cli` for the console's legend); Python ≥ 3.12 (`sdks/python`); TypeScript on
Node ≥ 22 (`sdks/typescript`); Rust, edition 2024, target `wasm32-unknown-unknown` (`sdks/rust`);
protobuf (`protocol`)

**Primary Dependencies**: none added, in any language. Pekko Projection's `eventsBySlices` is
already typed by event, and `WorkflowRecord` already crosses the `AnkkaSerializable` binding.

**Storage**: the service's own Postgres. One stored form changes by one optional field,
`WorkflowRecord.standing`, on `state` records written from this release. No table is created or
altered; a view over a workflow uses the view table, the versions table and the offset store a view
over an entity uses. No file under `kustomization/components/postgres/ddl/` changes.

**Testing**: munit — pure suites in `runtime` (`WorkflowChangesSuite`, `ViewProjectionsSuite`,
`KeyedViewRulesSuite`, `TopologyJsonSuite`); `testkit` with Postgres
(`WorkflowRecordCompatibilitySuite`, `WorkflowSourceSuite`, `ConsumerTestKitSuite`,
`KeyedViewTestKitSuite`, `TopologyFeatures`, and `WorkflowSourcesFeatures` over
`features/workflow-sources/`); `sidecar` (`ProtocolSuite`, `TranslateSuite`,
`RemoteProjectionSuite`, `WasmHostSuite`, `ConformanceSuite`); `controlplane-api`
(`CompatibilitySuite`). pytest with mypy strict; Node's test runner with `tsc`; `cargo test` with
clippy; each SDK's conformance run; the docs build; the bdd checker. **No new k3s suite**: nothing
here depends on where a service runs.

**Target Platform**: wherever an ankka service runs — in process, behind a sidecar, or as a
WebAssembly module.

**Project Type**: platform libraries and a protocol (the runtime, the sidecar, four SDKs), and
documentation

**Performance Goals**: SC-002, a consumer acts within the service's usual projection delay — the
same projection, so the same delay, measured by `WorkflowSourcesFeatures` against
`CheckoutWorkflowSuite`'s existing bound. A `state` record grows by the stamp (tens of bytes);
every other record is unchanged. A workflow's command and step paths gain one fold over the batch
they already build, which is the same fold the event handler runs on recovery.

**Constraints**: no existing view, consumer or workflow changes behaviour (SC-004): every
handler's existing path is the `ChangeReader.journal` case, moved not rewritten, and
`ViewProjectionsSuite` pins every projection name; a journal written by this release reads on the
release before and the reverse (FR-007, R15); a 1.14 SDK runs on a 1.15 runtime unless it declares
a workflow source; the three SDK copies of `protocol/` stay identical to the canonical one;
`runtime` names nothing generated; nothing unbounded is interned into the recorder's name table;
`Test / parallelExecution := false` stays; warning-free in every language; no suite binds a fixed
port

**Scale/Scope**: about 4 new Scala files and 18 changed across `sdk`, `runtime`, `testkit`,
`sidecar`, `controlplane-api` and `cli`, with 4 new suites and 8 changed; 3 protocol files and their
three copies; per SDK about 1 new source file, 6 changed, 1 new test file and the conformance
reference; 9 documentation pages changed and none added, 2 skills regenerated; 4 feature files
holding 25 scenarios and outlines and the glossary, already written

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | nothing a handler builds changes; `ChangeSource.Workflow` is a value on a descriptor, and the standing is a value the engine computes from the batch it already built |
| Where two interpreters reduce the same thing, they share one function | pass | `WorkflowChanges.read` for the plain, keyed, consumer, remote and module hosts (R3); the engine's stamp is the fold `applyEvent` already is (R2); `ChangeReader.journal` is today's `JournalRecord` match moved, not copied (R4) |
| Module dependency direction | pass | the source in `sdk`; the reader, the stamp and the handlers in `runtime`; `sidecar` translates one message; `core` and `controlplane-api` see only the version line |
| `runtime` never sees the generated protocol | pass | `ViewRequest`/`ConsumerRequest` in `Conversation.scala` gain a plain `Option[WorkflowLifecycle]`; `Translate` maps it to `WorkflowStanding` |
| No classpath scanning; explicit registration | pass | no new component kind; a workflow source is a case of a declared source, checked in `ComponentRegistry.validate` and `Discovery.validate` as the others are |
| Wire names are a versioning boundary | pass | no handler name changes; `stateOf` is a Scala overload, not a wire name; proto fields are numbered additions; the connection kind `workflow` is a new value beside four kept ones |
| The protocol directory is copied whole, and CI proves it | pass | one edit to `protocol/`, three runs of the SDKs' copy scripts |
| Virtual threads for anything that blocks | pass | the handlers' threading is unchanged; the stamp is computed on the actor's thread from values it holds |
| Schema is additive within a supported range | pass | one optional field on a journal record; nothing dropped, altered or created (R15) |
| Never touch `ActorContext` from a `Future` callback | pass | the stamp is computed in `onInvoke`/`onStepSucceeded` before `persist`, on the actor |
| Never intern anything unbounded into the recorder's name table | pass | a standing's words are six; a step name is a declared handler; nothing from a change is interned |
| No secret value in a journal; write the cluster first | n/a | nothing here is a credential or reaches the cluster |
| Tests are serialised; a test never binds a fixed port | pass | nothing changes in `build.sbt`'s test settings |
| An `eventually` waits for the thing it asserts | pass | the feature suite waits on the row's standing or the consumer's recorded end, never on "a row exists" |
| Could this check pass while the thing it checks is false? | pass | four claims (R2, R3, R4, R13) are marked "verify first" with the test that would show each false; `quickstart.md` names the one line to break for each story; the conformance case asserts a standing no SDK that ignored the field could answer; the "no change" scenarios assert the row *unchanged* and the journal *holding* the record, so a reader that skipped the record by accident and one that delivered it both go red |
| Each acceptance scenario ends as a test that fails without the feature | pass | 25 scenarios in `features/`, each named by the spec: 17 through `GherkinSuite`, 5 outlines as conformance cases per language (the topology one through the topology case, the test kit one as a unit test per SDK), 3 read off the documentation pages (R12, R14) |
| Docs: pages stand alone, samples from tested code, reference facts generated | pass | no page added; samples are marked regions in the conformance references; the protocol table is regenerated |
| Keep the Justfile thin; every tracked file claimed by a CI path filter | pass | no recipe added; every new file is under a directory a filter already claims |

**Violations to justify**: none against these principles. What widens the platform's surface is
under *Complexity Tracking*.

**Re-check after Phase 1**: unchanged. The design added no module, no port, no table and no
dependency.

## Project Structure

### Documentation (this feature)

```text
specs/046-workflow-sources/
├── plan.md              # this file
├── research.md          # R1–R16: decisions with file-level evidence; four things to verify first
├── data-model.md        # the source, the stamped record, the change, the wire, the topology
├── quickstart.md        # the validation runs, by user story, and the line to break for each
├── contracts/
│   ├── protocol.md          # the wire at 1.15, what each side refuses, compatibility
│   ├── scala-api.md         # stateOf, the standing on the context, rules S1–S6, the kits
│   └── sdk-apis.md          # Python, TypeScript, Rust; the conformance components and cases
└── tasks.md             # /speckit-tasks output (not created here)
```

### Source Code (repository root)

```text
features/workflow-sources/{views,consumers,languages}.feature     # written; 22 scenarios and outlines
features/documentation/workflow-sources.feature                   # written; 3 scenarios
GLOSSARY.md                                                       # written; standing, transition, workflow subscription settled

protocol/
├── src/main/protobuf/ankka/protocol/v1/{payload,view,consumer}.proto   # WorkflowStanding; standing on two requests
├── README.md                                                          # version 1.15
└── WASM-ABI.md                                                        # one sentence

modules/sdk/src/main/scala/…/sdk/
├── ChangeSource.scala                   # Workflow case; stateOf(Workflow.Companion); ChangeContext.standing
├── KeyedView.scala                      # KeyedChange.standing; KeyedSource.componentId
└── WorkflowLifecycle.scala              # isUnknown

modules/runtime/src/main/scala/…/runtime/
├── WorkflowChanges.scala                # new: read(record): State | Deleted | Nothing; both packings; Unknown
├── ChangeReader.scala                   # new: journal and workflow readers for the event handlers
├── wire.scala                           # WorkflowRecord.standing: Option[StandingRecord]; StandingRecord
├── WorkflowHost.scala                   # Event.StateUpdated(state, standing); the adapter writes and reads the stamp
├── WorkflowEngine.scala                 # the standing of the batch's Run, stamped before persist, in onInvoke and onStepSucceeded
├── ProjectionRuntime.scala              # workflowSource; the Workflow case in six starters; handlers typed by reader
├── ProjectionSupport.scala              # runView over a ChangeReader
├── KeyedViewHandlers.scala              # KeyedViewEventHandler[A]; handle carries a standing
├── DeclaredConnections.scala            # DeclaredSource.Workflow; of(Workflow) both ways
├── KeyedViewRules.scala                 # a workflow is a component with a change stream
├── TopologyJson.scala                   # kind "workflow"
└── remote/{Conversation,RemoteProjection}.scala   # standing on ViewRequest/ConsumerRequest; remote handlers typed by reader

modules/testkit/src/main/scala/…/testkit/{ConsumerTestKit,KeyedViewTestKit}.scala   # standing = None
sidecar/src/main/scala/…/sidecar/{Discovery,Translate}.scala                    # WorkflowSourcesSince; the message both ways
cli/src/main/resources/console/app.js                                            # the legend's fifth kind
controlplane-api/…/Compatibility.scala                                           # 1.15

sdks/python/      src/ankka/{view,keyed_view,consumer,context,server,service}.py, src/ankka/standing.py (new),
                  src/ankka/testkit/unit.py, proto/ (copied), examples/shopping_cart/conformance.py
sdks/typescript/  src/{view,keyedView,consumer,context,spec,index}.ts, src/standing.ts (new), src/server/{discovery,stateless}.ts,
                  src/testkit/unit.ts, proto/ (copied), examples/shopping-cart/{conformance,checkoutWorkflow}.ts
sdks/rust/        ankka/src/{context,service,prelude}.rs, ankka/src/standing.rs (new), ankka/src/components/{view,keyed_view,consumer}.rs,
                  ankka/src/testkit/unit.rs, ankka/protocol/ (copied), examples/shopping-cart/src/conformance.rs

modules/testkit/src/test/scala/…/testkit/
├── WorkflowRecordCompatibilitySuite.scala, resources/journal/workflow-record.txt   # new
├── views/{WorkflowKit,WorkflowSourcesFeatures,WorkflowSourceSuite}.scala           # new
├── topology/TopologySteps.scala                                                    # the fifth step
└── {ConsumerTestKitSuite,KeyedViewTestKitSuite}.scala                              # a standing handed and read
modules/runtime/src/test/scala/…/runtime/{WorkflowChangesSuite (new),ViewProjectionsSuite,KeyedViewRulesSuite,TopologyJsonSuite}.scala
sidecar/src/test/scala/…/conformance/{ConformanceReference,ConformanceSuite}.scala   # mode abort, two components, three routes, seven cases
sidecar/src/test/scala/…/{ProtocolSuite,TranslateSuite,RemoteProjectionSuite}.scala

docs/build/{views,consumers,workflows,testing}.md,
docs/reference/{akka-divergences,sidecar-protocol,python-sdk,typescript-sdk,rust-sdk}.md   # changed; none added
tools/docs/skill/{ankka-views-consumers,ankka-workflows}/SKILL.md, and their rendered copies
```

**Structure Decision**: the existing modules, with no new one. The two new runtime files hold the
two things that must exist exactly once — how a workflow record is read as a change, and how an
event handler reads an envelope — and everything else is a case added where the compiler's
exhaustiveness warnings point. `crd`, `operator`, `controlplane` (beyond the version line) and the
console package are not touched; the local console's legend is one line in `app.js`.

## Slices, in the order they are built, and what is released

Built in four slices, each ending green; released once.

1. **The stamp and the reader** (the four *verify first* suites). `StandingRecord` and the optional
   field; `WorkflowRecordCompatibilitySuite` with its fixture written from the release before's
   form; the engine's stamp in both paths; `WorkflowChanges.read` and its pure suite;
   `ChangeReader` with today's journal match moved into it and every existing suite still green.
   Nothing yet reads a workflow as a source; a service deployed at this slice journals stamped
   state records and is otherwise unchanged.
2. **The Scala source** (User Stories 1, 2, 4). `ChangeSource.Workflow` and `stateOf`; the
   `Workflow` case in the three in-process starters; the standing on `ChangeContext` and
   `KeyedChange`; the rules; the topology kind, legend and step; the kits; `WorkflowKit`,
   `WorkflowSourcesFeatures` and `WorkflowSourceSuite`.
3. **The wire and the languages** (User Story 3). Protocol 1.15 **with every field of this
   feature**; `Translate`; `Discovery`'s acceptance and gate; the three remote starters; the
   reference's `abort` mode, two components and three routes; the seven conformance cases green
   against the Scala reference; then each SDK: `Standing`, the property, the refusal, the kits, the
   reference components, the conformance run.
4. **The documentation**, written against what 1 to 3 built, with `just docs-sync`.

**No release carries slice 1 or 2 without slice 3.** Protocol 1.15 is written once, in slice 3,
and a runtime at slice 2 would still answer `1.14` while accepting nothing new from a process —
which is consistent — but a release at slice 2 would teach Scala services a source that a process
service could not declare until the next release, and the sources tables in the documentation would
be true of one language. So the feature is one release; the slices are for review and for
bisecting. Slice 1 alone is safe to carry: a stamped journal reads everywhere.

## Complexity Tracking

Nothing here violates a principle. These are the places the platform's surface grows, and why
each is the smaller of the options.

| What grows | Why needed | Simpler alternative rejected because |
|-----------|------------|-------------------------------------|
| A fourth `ChangeSource` case, matched in nine places | a workflow's records are read from the event journal with an entity's guarantees and a stamp an entity has no room for | reusing `KeyValue` would read the wrong store; a synthesised `JournalRecord` has nowhere to carry the standing (R1, R4) |
| An optional field on a journal record, and a compatibility suite that did not exist | the standing after the effect is knowable only by the engine, at the moment it persists (D2) | carrying or folding the state for stateless records was clarified away; a separate standing record is a second record a projection would have to join (R2) |
| A type parameter on five event handlers | one exactly-once path for both record kinds | five twin classes copy the guard, the row load and the transaction, which is where FR-008 lives (R4) |
| A sixth standing word, `Unknown` | a `state` record from before this release carries no stamp, and inventing one was clarified away | an absent value every handler in four languages checks for, rather than a word a `switch` already handles (clarification 5) |
| A protocol minor, gated both ways | a 1.14 SDK declaring a workflow source against a 1.15 runtime would be handed changes with no standing and no error | a field alone is read as absent by the side that does not know it, which is the sidecar rule's named trap (R6) |
| Two more components in every conformance reference, and a failing mode | the only test that catches an SDK reading a change without its standing is one that asserts the standing, in that language | asserting in Scala alone proves the runtime and nothing about the three SDKs (R13) |
