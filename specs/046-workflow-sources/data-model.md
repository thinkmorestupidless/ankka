# Data Model: Workflow Sources

What is declared, what is stored, and what crosses between them. One stored form changes, by an
optional field; no table is added or altered.

## Declared (values on descriptors; building one reads and writes nothing)

### ChangeSource.Workflow — `sdk`

| Field | Type | |
|---|---|---|
| `componentId` | `ComponentId` | the workflow's |
| `decoder` | `Serializer[S]` | the workflow companion's `stateSerializer` |

Built by `ChangeSource.stateOf(companion: Workflow.Companion[W, S])`. `describe` is
`workflow(<id>)`. Accepted by `View.Companion.source`, `KeyedView.Companion.source(...)` and
`Consumer.Companion.source`; `KeyedSource.componentId` reads it as it reads the entity cases.

### DeclaredSource.Workflow — `runtime`

| Field | Type |
|---|---|
| `component` | `ComponentId` |

What `DeclaredConnections.of` maps a `ChangeSource.Workflow` and a
`RemoteSource.Component(ComponentKind.Workflow, id)` to. Drawn by `TopologyJson` as a connection of
kind `workflow` from the workflow to the view or consumer.

### RemoteSource — `runtime/remote`, unchanged

`RemoteSource.Component(kind, id)` already carries `ComponentKind.Workflow`; discovery no longer
refuses it (protocol 1.15).

## Stored

### WorkflowRecord — `runtime`, one optional field

| Field | Type | Kinds that set it |
|---|---|---|
| `kind` | `Int` | all; unchanged |
| `state` | `Array[Byte]` | `state` (0); unchanged |
| `step` | `String` | `transition`, `pause`, `retry`; unchanged |
| `stepInput` | `Array[Byte]` | `transition`; unchanged |
| `message` | `String` | `fail`; unchanged |
| `deadlineMillis` | `Long` | `pause`; unchanged |
| `standing` | `Option[StandingRecord]` | **new**: `state` only, from this release; `None` on every other kind and on any record written before |

### StandingRecord — `runtime`, new, nested in `WorkflowRecord`

| Field | Type | Rule |
|---|---|---|
| `status` | `String` | `NotStarted`, `Running`, `Paused`, `Completed`, `Failed`: the `Run.status` after the whole effect, by name |
| `step` | `String` | the step the workflow is on or waits after; `""` for none |
| `retries` | `Map[String, Int]` | retries per step so far |
| `failure` | `String` | the reason when `Failed`; `""` otherwise |

Flat and string-keyed like the record it sits on, so the journal stays readable without ankka.
Serialized by Jackson CBOR under the `AnkkaSerializable` binding; a missing `standing` reads as
`None`. Pinned by `WorkflowRecordCompatibilitySuite` (old form without the field, new form with it).

### What is not stored

Nothing about a view over a workflow differs from a view over an entity on disk: rows in
`ankka_view_<id>`, the version in `ankka_view_versions`, offsets under
`ViewProjections.name(view, source, version)` per slice. A consumer's offsets are under
`ankka-consumer-<id>` as today.

## In memory

### WorkflowHost.Event.StateUpdated — `runtime`

| Field | Type |
|---|---|
| `state` | `S` |
| `standing` | `Option[Standing]` — the standing after the batch, set by the engine before `persist`; `applyEvent` ignores it |

### WorkflowChange — `runtime`, what `WorkflowChanges.read` answers for one record

| Case | Carries | From kinds |
|---|---|---|
| `State(payload, standing)` | the state as a `Payload` (content type, manifest, data), the standing (`Unknown` when the record has none) | `state` |
| `Deleted` | nothing | `delete` |
| `Nothing` | nothing: not a change | `transition`, `pause`, `end`, `fail`, `retry` |

The `Payload`'s manifest is the remote host's packed one when the bytes carry the
`ankka.remote.workflow` prefix, else the source's declared manifest with the bytes as they are.

### ChangeReader[A] — `runtime`, how an event handler reads an envelope

| Reader | Over | Yields |
|---|---|---|
| `ChangeReader.journal` | `JournalRecord` | domain payload / deleted / nothing (expiry), no standing — today's rule, moved |
| `ChangeReader.workflow` | `WorkflowRecord` | `WorkflowChanges.read` |

Every event handler (plain, keyed, consumer; in-process and remote) is typed over `A` and given one.

## The change, as a handler sees it

### WorkflowLifecycle — `sdk`, unchanged shape, one more status word

| Field | Type | |
|---|---|---|
| `status` | `String` | `NotStarted`, `Running`, `Paused`, `Completed`, `Failed`, or `Unknown` on a change from a record written before this release |
| `pendingStep` | `Option[String]` | |
| `retries` | `Map[String, Int]` | |
| `failure` | `Option[String]` | |

`isUnknown` added. The lifecycle query never answers `Unknown`.

### ChangeContext — `sdk`

| Field | Type | |
|---|---|---|
| `subject` | `String` | unchanged |
| `sequenceNumber` | `Long` | unchanged |
| `localOrigin` | `Boolean` | unchanged |
| `standing` | `Option[WorkflowLifecycle]` | **new**: `Some` for a workflow source, else `None` |

`KeyedChange` gains the same `standing`. `SimpleChangeContext` takes it with a default of `None`.

## On the wire (protocol 1.15; see contracts/protocol.md)

### WorkflowStanding — `payload.proto`, new

| Field | Type | |
|---|---|---|
| `status` | `string` | the words above |
| `step` | `optional string` | |
| `retries` | `map<string, int32>` | |
| `failure` | `optional string` | |

Carried as `ViewRequest.standing` (7) and `ConsumerRequest.standing` (5), present only for a
workflow source. A module's `ankka1_view` and `ankka1_consumer` receive the same messages.

### SDK types

| SDK | Type | Read as |
|---|---|---|
| Python | `Standing` dataclass, `is_unknown` | `self.standing` on a view, keyed view, consumer; `None` for an entity source |
| TypeScript | `Standing` interface, `isUnknown()` | `this.standing`; `undefined` |
| Rust | `Standing` struct, `is_unknown()` | `ctx.standing()`: `Option<&Standing>` |

## Topology document

A connection `{ from: <workflow>, to: <view or consumer>, kind: "workflow" }` beside `events`,
`state`, `topic-subscription` and `topic-publication`. The console's legend reads it as "a workflow
subscription". Nothing else in the document changes.

## States of a workflow, as a reader of changes sees them

```
(no record)              -> no row, no change
state, no transition     -> change: standing NotStarted
state + transition       -> change: standing Running, step = the step moved to
state + pause            -> change: standing Paused, step = the pause's timeout step or the step paused after
state + end              -> change: standing Completed
state + fail             -> change: standing Failed, failure = reason
transition / pause / end / fail / retry alone  -> no change
delete                   -> deletion handler
state written before 1.15 -> change: standing Unknown
```
