# Data Model: Service Topology

Nothing in this feature is persisted. Everything below is held in memory and bounded by
registration, or is computed when it is read.

## Caller *(runtime, in-memory and in metadata)*

| Field | Type | Rule |
|---|---|---|
| component | declared component id, `http`, or a timed action's id | must be registered in this service |
| handler | a declared handler name, an HTTP route description, a workflow step name, `on-change`, `on-message`, `iteration`, or a timer method | must be declared for that component |

- On the wire: the metadata key `ankka-caller`, with value `<component>#<handler>`.
- Absent, malformed or not validated → **unknown caller**. It is never repaired or guessed.
- One fixed caller, `(console)#read`, marks the local console's own reads. Calls carrying it are
  not counted. No component can declare a name starting with `(`.
- Set by every host around user code (research R1). `ShardingTransport` stamps it when the thread
  has a current caller, and *removes* it when the thread has none.

## CallEdgeKey *(runtime)*

`(callerComponent, callerHandler, calleeComponent, calleeHandler)`. Each part is an interned name
id, and the four are packed into one `Long` of 16 bits each. Three ids are fixed: `(unknown)` for a
caller that could not be determined, `(undeclared)` for a callee handler the component does not
declare, and `(other)` for external services beyond the admission limit.

Both ends are validated against the registry before a name is interned (research R1, R2).

For a call to another service, the callee component is `service:<project>/<name>` and the callee
handler is the HTTP method (research R3). These names are bounded by an admission limit
(`max-external-services`, default 32), not by what the code names: a service name can come from
request input. Calls to a service beyond the limit use the callee `service:(other)`.

## CallCounts *(runtime, one per process)*

Edges are `CallEdgeKey` → a ring of `CallBucket`, with `B` buckets each `window / B` long (default
60 × 10 s).

| CallBucket field | Meaning |
|---|---|
| epoch | which interval this bucket currently holds; a stale bucket is reset on write |
| handledOk, handledRefused, handledFailed | counted by the callee's host when its span completes |
| unansweredTimedOut | no reply in time; counted by the caller's transport or service client |
| unansweredUndelivered | the handler never ran; counted by the callee's host when it answers without running one, or by the caller's transport or service client when no host was reached. Counts are attempts. |
| durations[32] | log-scale histogram of handled durations |
| streaming | at least one call on this edge was a stream |

**Invariants.**
- The number of edges is at most the product of the declared names. It does not depend on traffic.
- No entity id, session id or filled-in path is ever a key or a value.
- A read folds only buckets whose epoch is inside the window.

## Topology *(document, read-only)*

Produced by `TopologyJson.render` from the registry, the routes and a `CallCounts` snapshot. The
exact shape is in `contracts/topology.md`.

- **service**: name, runtime version, instance id, `startedAt`.
- **window**: `seconds`, `since` (the later of window start and process start), `calls` (the
  handled total) and `unanswered` (the unanswered total): two numbers, as on every pair.
- **nodes**: `Node[]`.
- **declared**: `DeclaredEdge[]`.
- **calls**: `CallEdge[]`.

### Node

| Field | Values |
|---|---|
| id | unique within the document. A component's id; `endpoint:<prefix>` for an endpoint; `topic:<name>`; `service:<project>/<name>` or `service:(other)`; `external:<componentId>`; `unknown` |
| kind | a `ComponentKind`, or `Topic`, `ExternalService`, `ExternalComponent`, `UnknownCaller` |
| layer | 0–4 (research R8); external and unknown nodes take the layer of what they connect to, minus one for a source and plus one for a target |
| handlers | declared handler names, each with `query` or `command`; the routes, for an endpoint |
| platform | true only for components the agent module registers for itself |

### DeclaredEdge

| Field | Values |
|---|---|
| from, to | node ids |
| kind | `events`, `state`, `topic-subscription` or `topic-publication` |

Complete by construction: each source and destination of each descriptor yields exactly one edge.

### CallEdge

| Field | Values |
|---|---|
| from, to | node ids; `from` may be `unknown` |
| pairs | `CallPair[]`, one per (caller handler, callee handler) |

`CallPair` = caller handler, callee handler, `handled {ok, refused, failed}`,
`unanswered {timedOut, undelivered}`, `durationMillis {p50, p99, max}` (bucketed), `streaming`.

## ServiceTopology *(controlplane-api wire type, phase 2)*

`Topology` merged across instances, plus:

| Field | Meaning |
|---|---|
| instances | `InstanceTopology[]`: pod name, `status` (`ok`, `unreachable`, `unsupported` or `failed`), `problem?`, `runtime?`, `readAt` |
| contributing / running | counts, so the UI can say "2 of 3" |
| partial | true when any instance is not `ok` |
| differences | nodes or declared edges not present on every `ok` instance, each naming the instances that have them (FR-024) |

**Merge rules.**
- Nodes and declared edges are the union, and anything not present on every `ok` instance is listed
  under `differences`.
- Call counts are summed per pair. Percentiles are recomputed from the summed histograms, which is
  why instances send histograms and not percentiles.
- `window.since` is the latest `since` of all instances.
