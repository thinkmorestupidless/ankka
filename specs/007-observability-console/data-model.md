# Data Model: Seeing What a Service Is Doing

**Feature**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

Nothing here is persisted. Every structure below lives in the memory of one service process and
dies with it (FR-004), which is what lets the shapes be this plain.

## The hot path: what is written per invocation

The single rule: **fixed-width values only, no allocation beyond the record, no strings built**.
Everything readable is derived later, by a reader, from identifiers.

| Field | Type | Notes |
|---|---|---|
| `traceId` | 128-bit, two longs | minted at the entry point; travels in `Metadata` |
| `spanId` | 64-bit | this invocation |
| `parentSpanId` | 64-bit, 0 when none | 0 at the root; a sharding hop carries the caller's |
| `componentRef` | int | index into the component registry, not a name |
| `handlerRef` | int | index into that component's handlers |
| `startedNanos` | long | monotonic |
| `durationNanos` | long | written on completion |
| `outcome` | byte | ok / error / refused / timed-out |

`componentRef` and `handlerRef` are indices because the registry is fixed at startup — components
reach the runtime only by explicit registration, so the set cannot change while running, and an
index avoids a string per invocation. A reader resolves them to names.

### The ring

A pre-allocated array of the records above, fixed capacity, oldest overwritten. The capacity is the
only tuning knob and it bounds memory absolutely (SC-004). A reader may see a trace whose oldest
spans have already been overwritten; that trace is reported as **partial**, never silently
truncated into a tree that looks whole.

## Trace assembly (read time, not write time)

`Trace` is built on demand from the ring:

```
Trace
├── traceId, startedAt, totalDuration, outcome
├── spans: tree, by parentSpanId
│   └── Span: component, handler, duration, outcome, share of total
├── unattributed: total - Σ(root-level spans)     # honest, never redistributed
└── partial: Boolean                              # this window does not hold it all
```

`partial` means **this window does not hold the whole trace**. In this feature the only cause is
eviction from the ring. It is defined by what it means rather than by its cause so that a deployed
console — where the other half of a trace may sit on another pod — needs no second flag and the UI
needs no new case.

**`unattributed` is a first-class field, not an error.** It is where the truth lives: in Akka's own
example the interesting answer was that 99.9% of the request was spent waiting on the model, which
is time the framework did not spend. It also absorbs work handed to another thread, which the
thread-local request context cannot follow (R4). It is never redistributed across spans to make the
percentages tidy.

## Model usage

Recorded per model call, including calls that failed part-way (FR-006):

| Field | Notes |
|---|---|
| `traceId`, `spanId` | ties usage to the invocation that caused it |
| `sessionId` | the agent session, so the console can total per conversation |
| `model` | as the provider names it |
| `inputTokens`, `outputTokens` | from the provider's own reporting |
| `outcome` | a failed call still consumed input tokens |

### Cost

`cost = tokens × price(model)`, where the price table is configuration the platform is *told*. A
model absent from the table yields **tokens with unknown cost** — never zero, which would read as
free (R9). The console shows `—` and says why.

## The registry entry (local mode only)

One file per running service, written at startup and removed on graceful shutdown:

| Field | Purpose |
|---|---|
| `name` | what the console lists |
| `instanceId` | the pid locally; a pod name in a later deployed source |
| `pid` | liveness check without a request |
| `httpAddress` | where the console's invoke panel sends requests — the *real* port (R5) |
| `observabilityAddress` | ephemeral; where the console reads records and inventory |
| `startedAt` | shown, and breaks ties if a name repeats |

One file is one **instance**. Locally a service has exactly one, and the console still models the
relationship as one-service-to-many-instances, because a deployed service has one per pod and a
shape that assumes one is the expensive thing to change later. Reconciling several instances is not
in this feature.

**Written only in `local` mode.** In Kubernetes the pod is the registry and the management port is
the exposure.

**Staleness is expected, not exceptional.** `kill -9` leaves the file. An entry whose
`observabilityAddress` does not answer is dropped from the listing and its file removed — a service
that vanished must disappear from the console (FR-009), and development kills processes constantly.

The directory is `~/.ankka/running/`, overridable by a system property, for the reason
`Settings.path` checks `-Dankka.config` first: a suite cannot otherwise exercise discovery without
writing into the developer's home.

## Component inventory

Already held by the runtime — components reach it only by explicit registration, so this is a read
of something that exists rather than anything new:

```
Service
└── components, grouped by kind
    ├── Entity / KeyValue / Workflow / View / Consumer / Timer / Agent
    └── Endpoint: routes (method, path template), which is what the invoke panel needs
```

An endpoint's route templates are what the console turns into a form. A service with `"http":
false` has no endpoints and the console says so rather than offering a panel that cannot work.

## Log line (deployed, P2)

Not stored — streamed through and forgotten:

| Field | Notes |
|---|---|
| `instance` | which pod, required when a service runs several (FR-021) |
| `container` | current or previous, for reading what a crashed instance said (FR-022) |
| `timestamp`, `message` | as the platform received them |

## What is deliberately absent

- **No persistence, no retention, no query language.** Ephemeral was chosen; a store is a feature
  of its own and would bring schema, migration and growth with it.
- **No sampling and no trace-level configuration.** Always-on was chosen, and SC-003 is the price.
- **No second accounting path.** The metrics endpoint aggregates the same records the console
  reads; two paths would eventually disagree and both would be believed.
