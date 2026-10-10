# Research: Workflow Sources

Decisions R1–R16, each with what was found in the code, what was chosen, and what was not. Paths
are from the repository root. *Verify first* marks a claim the design rests on that has not been
run; each names the test that would show it false, and that test is written before the code that
depends on it.

## What planning found that the spec had assumed otherwise

1. **A workflow's journal is not an entity's journal, to a projection.** `ProjectionRuntime`'s
   event source is `EventSourcedProvider.eventsBySlices[JournalRecord]`
   (`modules/runtime/…/ProjectionRuntime.scala:1055`), and every event handler is typed
   `EventEnvelope[JournalRecord]`. A workflow's persistence id (`PersistenceId(componentId,
   workflowId)`, `WorkflowHost.scala:91`) sits in the same table and the same slices, but its events
   are `WorkflowRecord`s. "Can read it today" was true of the plugin and false of every handler:
   each needs the record read as a change, which is one new function and a type parameter (R3, R4).
2. **A remote workflow's state carries its own manifest inside the bytes.** `RemoteWorkflowHost`
   packs the manifest, the content type and the data into the `state` field of its records
   (`RemoteWorkflowHost.scala:49-70`, manifest `ankka.remote.workflow`), because `WorkflowDetail`
   declares no state manifest. A view over a remote workflow decodes the state through that packing;
   a view over a Scala workflow decodes it with the workflow's `stateSerializer`. The one reader of
   a workflow record knows both (R3).
3. **The journal is serialized by Jackson CBOR, not jsoniter.** `AnkkaSerializable` is bound to
   `jackson-cbor` (`modules/runtime/src/main/resources/reference.conf:164`), so the stamp on a
   `state` record is an `Option` field Jackson reads as `None` when absent, and there is no
   compatibility suite for `WorkflowRecord` as there is for `StateRecord`
   (`StateRecordCompatibilitySuite`). One is added (R2).
4. **A standing after a state record can be "NotStarted".** `applyEvent` for `StateUpdated` keeps
   the status (`WorkflowHost.scala:107-108`), so a command that updates the state and transitions
   nowhere leaves the workflow `NotStarted`. The lifecycle query already answers the word; the
   standing carries it too, and the glossary's four words gain it as what a workflow is before its
   first transition (R2).
5. **The lifecycle query has no SDK outside Scala.** No `lifecycle` call exists in the Python,
   TypeScript or Rust clients, so the standing is new to the three SDKs and defined once in the
   protocol (R6, R9).
6. **The Scala view has no unit test kit** (031 found the same): a workflow change reaches a Scala
   test through `KeyedViewTestKit` and `ConsumerTestKit`, which gain a standing, and through the
   integration kit (R12).
7. **The conformance reference's checkout never fails.** Its `compensate` step records
   `compensated` and ends (`ConformanceReference.scala:190-191`), so no reference workflow reaches
   the standing `failed`; a mode is added that records the failure and fails (R13).

---

## R1. A workflow source is a fourth case of `ChangeSource`, declared with `stateOf`

**Found**: `ChangeSource` is a sealed trait with `EventSourced`, `KeyValue` and `Topic`
(`modules/sdk/…/ChangeSource.scala`), each built from a companion so the decoder is the source's
own. `DeclaredConnections.of` maps the three to `DeclaredSource.Events | State | Topic`, and every
`match` over a source in `ProjectionRuntime`, `KeyedViewRules`, `KeyedSource.componentId` and
`TopologyJson` is exhaustive over them, so the compiler lists every place a fourth case must go.

**Decision**: `ChangeSource.Workflow[S](componentId, decoder)` with `describe = "workflow(id)"`, and
`ChangeSource.stateOf(companion: Workflow.Companion[W, S]): ChangeSource[S]` overloaded beside the
key value one, building it from `companion.componentId` and `companion.stateSerializer`.
`DeclaredSource.Workflow(component)` beside the three. The compiler's non-exhaustive match warnings
(`-Werror`) are the checklist of sites.

**Rationale**: the clarification chose `stateOf`; a case of its own rather than reusing `KeyValue`
because the runtime reads the two from different stores with different guarantees (an event journal,
exactly once; durable state, at least once), and `KeyValue`'s handlers would read a workflow's
persistence id from the wrong table.

**Alternatives**: a flag on `KeyValue` — misdeclarable, and the match sites would not be found by the
compiler. A new verb — declined in clarification.

## R2. The stamp: an `Option[StandingRecord]` on the `state` record, written by the engine

**Found**: the engine builds the whole list an effect persists before persisting any:
`onInvoke` (`WorkflowEngine.scala:200-208`: `Deleted`, `StateUpdated`, `TransitionedTo`),
`onStepSucceeded` (`:343-365`: `stateEvents :+ TransitionedTo | Paused | Ended | Failed`). The
`Run` after the batch is `events.foldLeft(state)(applyEvent)`, which is what the host's event handler
will compute anyway. `WorkflowLifecycle(status, pendingStep, retries, failure)` is already how the
engine answers where a workflow stands (`onLifecycleQuery`).

**Decision**: `Event.StateUpdated(state, standing: Option[Standing])` in memory and
`WorkflowRecord.standing: Option[StandingRecord] = None` on disk, where `StandingRecord(status:
String, step: String, retries: Map[String, Int], failure: String)` is flat like the record it sits
on. The engine computes the standing of the batch's final `Run` and stamps it onto the batch's
`StateUpdated` event before `persist`; `applyEvent` ignores the stamp. The paused standing's step is
the `onTimeout` step the pause names, when it names one, else the step paused after, which the
engine has in hand (`succeeded.step` in `onStepSucceeded`). This is the one place a standing says
more than the lifecycle query: `applyEvent` sets `pending = None` on a pause, so the query answers no
step for a paused workflow and cannot after a recovery, and FR-005 says so. Every other record kind
is written exactly as today.

The standing's `status` words are the lifecycle query's: `NotStarted`, `Running`, `Paused`,
`Completed`, `Failed`. A record without the field reads back as `None`, which the change reports as
the standing `Unknown` — a sixth word that only a reader of a change ever sees.

**Rationale**: the clarification (D2). Jackson reads a missing `Option` as `None` (Pekko's own
schema-evolution guidance for an added field), so the journal stays readable both ways; a release
before this one ignores a field it does not know because Pekko's Jackson serializer is built with
`FAIL_ON_UNKNOWN_PROPERTIES` off (`pekko.serialization.jackson.deserialization-features`). Both
directions are pinned, the second by reading the new form into a copy of the pre-feature case class.
The stamp is tens of bytes on a record that already holds the state.

*Verify first*: `WorkflowRecordCompatibilitySuite` (testkit), modelled on
`StateRecordCompatibilitySuite`: a pinned hex of a `state` record without the field reads back with
`standing = None`, and the new form round-trips; the file under
`modules/testkit/src/test/resources/journal/` is the proof the old form still reads.

**Alternatives**: carry the state on every record (rejected in clarification); fold in the
projection (rejected); a separate standing record (a second record per effect, and the reader would
have to join two records that a projection delivers one at a time).

## R3. One function reads a workflow record as a change, for every host

**Found**: `ProjectionSupport.runView` matches `JournalRecord.kind` (`:111-118`); the keyed and
remote handlers match it again (`KeyedViewHandlers.scala:178-183`, `RemoteProjection.scala:242-249`,
`:437-443`); the consumer handler a fourth time. Four readers of one record kind, which could not
disagree because the record is two cases and a payload. A workflow record has seven kinds, two state
packings (Scala's bytes; the remote host's manifest-prefixed bytes), and a stamp.

**Decision**: `WorkflowChanges.read(record: WorkflowRecord): WorkflowChange` in `runtime`, a sealed
result: `State(payload: Payload, standing: WorkflowLifecycle)`, `Deleted`, or `Nothing` (transition,
pause, end, fail, retry). It unpacks the remote host's prefix when the bytes carry it and otherwise
takes the bytes as the workflow's own serializer wrote them with the source's declared manifest; it
turns an absent stamp into the standing `Unknown`. Every host — the plain view's, the keyed view's,
the consumer's, and the remote and module variants of each — reads a workflow envelope through it
and nothing else.

**Rationale**: "where both the runtime and the testkit reduce an effect they share one function".
The remote packing is exactly the kind of fact that a second reader gets wrong.

*Verify first*: `WorkflowChangesSuite` (runtime, pure): each record kind to its change; a remote
packed state and a plain state both to their payload; an absent stamp to `Unknown`.

**Alternatives**: a match in each handler, as today for `JournalRecord` — four copies of the
packing rule.

## R4. The event handlers take a record type, and the workflow source reuses them

**Found**: `ViewEventHandler`, `ConsumerEventHandler`, `KeyedViewEventHandler`,
`RemoteViewEventHandler` and `RemoteConsumerEventHandler` are each typed over
`EventEnvelope[JournalRecord]` and differ from their would-be workflow twins only in how the record
becomes a change: a payload to decode, a deletion, or nothing, and now a standing.

**Decision**: each event handler takes a type parameter `A` and a `ChangeReader[A]`, a two-case
value: `ChangeReader.journal` (today's `JournalRecord` match, moved into it unchanged) and
`ChangeReader.workflow` (R3). `ProjectionRuntime` gains `workflowSource(sourceId, range)` returning
`EventSourcedProvider.eventsBySlices[WorkflowRecord]`, and `exactlyOnceWorkflowProjection` /
`atLeastOnceWorkflowProjection` beside the event ones. `startView`, `startKeyedView`,
`startConsumer` and the three remote starters gain a `ChangeSource.Workflow` /
`RemoteSource.Component(ComponentKind.Workflow, _)` case that builds the same projection with the
workflow reader. Slices and `parallelism` are the entity source's; the projection and daemon names
come from `ViewProjections` unchanged, since they key on the view and the source's component id.

**Rationale**: five handlers gain a parameter rather than five new classes that would copy the
guard, the row load, the trace and the transaction; the sharing is where the exactly-once guarantee
(FR-008) lives, so a workflow view inherits it by construction.

*Verify first*: `ViewProjectionsSuite` still pins today's names (a workflow source must not move an
existing view's offsets); `WorkflowSourceSuite` (testkit): a plain view over `Checkout` writes a row
per state record and, restarted, reads none again (FR-008, the scenario "a restarted view reads no
state of a workflow again").

**Alternatives**: new handler classes — more code and a second place for every guard. A
`JournalRecord` synthesised from a `WorkflowRecord` so today's handlers run unchanged — loses the
standing, which has nowhere to go on a `JournalRecord`.

## R5. The standing rides on the change's context: `ChangeContext.standing`

**Found**: a Scala view or consumer reads `updateContext.subject` / `messageContext.sequenceNumber`
from `ChangeContext` (`modules/sdk/…/ChangeSource.scala:138-147`); a keyed view's handler takes a
`KeyedChange` with `subject`, `sequenceNumber` and `rows`. The change value itself is the decoded
state, typed `S` by the companion.

**Decision**: `ChangeContext.standing: Option[WorkflowLifecycle]` and
`KeyedChange.standing: Option[WorkflowLifecycle]`, `None` for an entity or topic source,
`Some(lifecycle)` for a workflow source, with `WorkflowLifecycle.isUnknown` for the `Unknown`
status. `SimpleChangeContext` gains the field with a default of `None`, so every existing
construction compiles. `WorkflowLifecycle` keeps its name (clarification 1); the documentation calls
what it holds the standing.

**Rationale**: the clarification (`stateOf`: the change type is the state, the standing beside the
subject). Reusing `WorkflowLifecycle` means the lifecycle query and a change agree by type on what a
standing is.

**Alternatives**: a `WorkflowChange[S](state, standing)` as the change type — makes every handler
over a workflow unpack a wrapper and makes `stateOf` lie about its type.

## R6. The wire: protocol 1.15, a `WorkflowStanding` message on the two requests

**Found**: `ViewRequest` has fields 1–6 and `ConsumerRequest` 1–4 (`protocol/…/view.proto`,
`consumer.proto`); `Source.ComponentRef` already carries any `Kind` and `Discovery.source` accepts
`WORKFLOW` as a `RemoteSource.Component` which `ProjectionRuntime.rejectUnsupportedRemote` then
refuses as "no change stream" (`:350-352`). The subject and sequence travel as metadata
(`RemoteProjection.changeMetadata`). The sidecar gates a feature on the SDK's minor exactly once
today: socket routes, `SocketsSince` (`Discovery.scala:365`), and each SDK refuses a runtime that is
too old for what the SDK declares (`sdks/python/src/ankka/server.py:87-110`,
`sdks/typescript/src/server/discovery.ts`, `sdks/rust/ankka/src/service.rs:372-415`).

**Decision**: protocol `1.15`. `payload.proto` gains
`message WorkflowStanding { string status = 1; optional string step = 2; map<string, int32> retries
= 3; optional string failure = 4; }`; `ViewRequest.standing = 7` and `ConsumerRequest.standing = 5`,
both `optional`, present only for a workflow source. A `Source.ComponentRef` of kind `WORKFLOW` is
accepted by `DeclaredConnections.of(RemoteSource)` as `DeclaredSource.Workflow`. Gated both ways:
the sidecar refuses a `Spec` whose minor is below 15 and declares a workflow source, naming the
component and both versions (`WorkflowSourcesSince`); each SDK refuses discovery from a runtime
below 1.15 when a view or consumer declares a workflow source, in the words the 1.13 and 1.14
refusals use. `Discovery`'s "Sources must name a declared component" check already covers a workflow
by kind.

**Rationale**: a typed message rather than metadata entries, because the standing has four parts
and a map, and metadata is string pairs. Gated as sockets are, because the one trap the sidecar rule
file names is a field an older side reads as absent: a 1.14 SDK that declared a workflow source
against a 1.15 runtime would be handed changes with no standing and no error.

**Alternatives**: metadata entries `ankka.standing.*` — five keys a reader reassembles, and a map
flattened into strings. A new rpc — nothing here is a call an older runtime could misread, so a
field with a gate is enough (the sidecar rule's "new call" case is a *behaviour* the old runtime would
perform wrongly, which a refused discovery prevents).

## R7. The topology: `DeclaredSource.Workflow`, connection kind `workflow`

**Found**: `TopologyJson` names connections `events`, `state`, `topic-subscription`,
`topic-publication` (`:351-370`), the console's legend maps those four to words
(`cli/src/main/resources/console/app.js:997-1002`), and `TopologySteps` asserts them
(`:760-770`). `entity(component, kind, connection)` finds the source among the registered
descriptors by kind, or draws it outside the service.

**Decision**: `DeclaredSource.Workflow(component)` renders
`entity(component, ComponentKind.Workflow, "workflow")`, the legend gains
`'workflow': 'a workflow subscription'`, `TopologySteps` gains the step for "as a workflow
subscription" mapping to `"workflow"`. The control plane's `TopicChecks` reads `topic-subscription`
only and is untouched.

**Rationale**: the clarification chose a kind of its own; the short form matches `events` and
`state`.

## R8. Rules: a workflow source is an entity source to every rule

**Found**: `KeyedViewRules` refuses a keyed view that reads a topic beside a component, a topic
alone, a component twice, or a component kind that is not an entity (`:56-75`);
`TopicSourceRules` refuses a version on a consumer over an entity; `rejectUnsupportedRemote`
refuses a remote component source of any other kind.

**Decision**: `KeyedViewRules` admits `ComponentKind.Workflow` beside the two entity kinds and its
"topic and an entity" message gains "or a workflow" where the spec's scenario reads it; the plain
view's refusal of a topic beside anything is unchanged (a plain view has one source). The
`rejectUnsupportedRemote` message lists the workflow as a fourth source. `Discovery` refuses a start
position, a contract, a broker or `parallel` on a workflow source as it does on an entity's, through
the same branch.

## R9. The SDKs: a workflow class where an entity class goes, and a `standing` on the context

**Found**: Python's `_source_pb` already turns `source = SomeWorkflow` into a `ComponentRef` of the
workflow's kind through `to_component()` (`sdks/python/src/ankka/view.py:52-59`); TypeScript's
`source?: ComponentRef` and Rust's `Source::of(C: ComponentOf<M>)` likewise take any component.
The three SDKs expose a change's subject and sequence from metadata (`self.metadata.subject`,
`this.subject`, `ctx.sequence()`).

**Decision**: no change to how a source is named. Each SDK gains a `Standing` type (`status`,
`step`, `retries`, `failure`, with `is_unknown`/`isUnknown`) decoded from the request's field, and
exposes it as `self.standing` (Python, `None` for an entity source), `this.standing` (TypeScript,
`undefined`), `ctx.standing()` (Rust, `Option<&Standing>`), on views, keyed views and consumers. The
decoder of a workflow source's change is the workflow class's state codec, which each SDK already
knows from the class. Each SDK's discovery refusal (R6) names the classes that declare a workflow
source.

**Rationale**: the shape is drawn; only the standing is new. A property beside `subject` is where a
reader already looks.

## R10. The module: the same request, read by the crate

**Found**: `WasmConversation.handleView` and `handleConsumer` pass the protobuf request through
`toViewRequest` / `toConsumerRequest` (`sidecar/…/wasm/WasmConversation.scala:303-322`); the crate
decodes `ViewRequest` in `ankka1_view`. A view runs on the blocking pool and may not call `request`
(`CallSite`), which a workflow source does not change.

**Decision**: nothing in the host; the crate reads `standing` into `Context` (R9). `WASM-ABI.md`
gains one sentence on the field. `WasmImportsSuite` is untouched: no import changes.

## R11. Test kits: a standing is handed where a change is

**Decision**: Scala `ConsumerTestKit.onMessage(message, subject, sequenceNumber, standing = None)`
and `KeyedViewTestKit.change(source, subject, value, standing = None)`; Python
`ViewTestKit.on_change(key, event, standing=None)`, `KeyedViewTestKit.change(source, key, event,
standing=None)`, `ConsumerTestKit.on_message(message, subject, sequence=, standing=None)`;
TypeScript and Rust likewise on their `onChange`/`change`/`onMessage` and
`on_event`/`change`/`on_message`. A kit handed a standing sets it on the context the handler reads.

**Rationale**: FR-012; a default of none keeps every existing test as it is.

## R12. Living features: one Gherkin suite in `testkit`, the languages as conformance cases

**Found**: `SeveralSourcesFeatures` runs `features/views/several-sources.feature` whole through
`GherkinSuite` against a real service with scripted components (`ViewsKit`, `KeyedKit`);
`features/views/languages.feature`'s outlines run as conformance cases per SDK, and the
documentation scenarios are read off the pages.

**Decision**: `WorkflowSourcesFeatures` (testkit, `features/workflow-sources/views.feature` and
`consumers.feature`) over a scripted workflow kit (`WorkflowKit`: a workflow whose steps record what
the scenario says — a state, a pause, a failure recorded or not — and a view and a consumer over it,
with the in-memory broker for the published message). `languages.feature`'s outlines become
conformance cases (R13), the topology scenario a case per language too, and the test kit scenario a
unit test in each SDK. `documentation/workflow-sources.feature` is held by the documentation checks
as the other documentation features are.

## R13. Conformance: the reference gains a view and a consumer over `checkout`, and a failing mode

**Found**: the reference's `Checkout` workflow has modes `ok`, `fail` and `pause`
(`ConformanceReference.scala:158-205`); `fail` compensates and *completes*. Every reference must
declare the same components (`discovery.lists-every-component`), so a component added to one is
added to all four.

**Decision**: the reference gains mode `abort`, whose compensation records `status = "aborted"` and
ends in `thenFail("payment declined")`, so a `state` record is stamped `Failed` with the reason; a
view `checkout-rows` (`ChangeSource.stateOf(Checkout)`, one row per checkout holding the state and
the standing's status, step and failure, with a declared query `by-standing`) and a consumer
`checkout-ends` over the same source that records each change whose standing is completed or failed
into the existing key value `profile` entity under the checkout's id, so the suite can read it.
Routes: `GET /conformance/checkout/{id}/row`, `GET /conformance/checkout-rows?standing=`,
`GET /conformance/checkout/{id}/end`. Cases: `view.workflow-completed`,
`view.workflow-failure-recorded`, `view.workflow-no-change-without-state`,
`view.workflow-by-standing`, `consumer.workflow-end-once`, `topology.workflow-subscription`, and
`discovery.workflow-source-needs-1.15` against a sidecar told to state 1.14. Each SDK's reference
adds the same components in its own language.

*Verify first*: the Scala reference passes the new cases before any SDK is touched, so a case that
passes against an SDK that ignored the standing is impossible: `view.workflow-completed` asserts the
row's standing is `Completed`, which no SDK reading nothing can answer.

## R14. Documentation

**Decision**: `docs/build/views.md` and `consumers.md` gain a row in their sources tables and a
section "Reading a workflow" each: what a change is, that what records no state is no change, the
standing's words including `NotStarted` and `Unknown`, in four languages from the conformance
references' marked regions; `docs/build/workflows.md`'s "Domain state and lifecycle" says the
standing can be read as data and that a step whose outcome must be seen records it in the state, and
links to the views guide; `docs/reference/akka-divergences.md` gains a row; `docs/build/testing.md`
gains the standing arguments; `docs/reference/sidecar-protocol.md` and `protocol/README.md` gain
`1.15`; `docs/reference/{python,typescript,rust}-sdk.md` gain the property; the
`ankka-views-consumers` and `ankka-workflows` skills are regenerated by `just docs-sync`.
`limitations.md` is unchanged: nothing here is a limit it did not list.

## R15. Compatibility

**Decision**: a journal written by this release reads on the release before (an unknown `Option`
field is ignored by Jackson); a journal written before reads on this release (R2). A 1.14 SDK runs
on a 1.15 runtime unchanged unless it declares a workflow source, which it is refused (R6). A 1.15
SDK runs on a 1.14 runtime unless it declares one, which it refuses (R6). `Compatibility.scala` in
`controlplane-api` gains the 1.15 line. No DDL changes; no table is created or altered: a view over
a workflow uses the view table and offset store a view over an entity uses.

## R16. What is deliberately not built

- An ends-only option (clarified away).
- A standing derived for old records (clarified away).
- A source over an agent session, an autonomous agent instance or a blueprint run: each is already
  an entity.
- Any change to what a workflow journals beyond the one optional field; the engine's recovery
  reads nothing new.
- A watch over a view of workflows: that is 047's.
