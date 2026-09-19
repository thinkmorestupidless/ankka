# Implementation Plan: Seeing What a Service Is Doing

**Branch**: `007-observability-console` | **Date**: 2026-09-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-observability-console/spec.md`

## Summary

Record every component invocation in the runtime — which is the one place that already sees them
all, because effects are inert data it interprets — into a bounded in-memory ring, correlated by a
trace identifier that rides on `Metadata` and therefore already crosses the sharding boundary
unchanged. Expose that recording twice, chosen by where the process runs, exactly as cluster
formation already is: on an ephemeral local endpoint for development, and on the existing
management port under Kubernetes. Over the local endpoint, `ankka local console` serves a web UI
from the CLI using the JDK's own HTTP server — services, components, an invoke panel, entity and
session-memory inspection, traces with per-component timings, and agent tokens and cost. For
deployed services, `ankka services logs` streams from the control plane, which gains read-only
`pods/log` rights, and a Prometheus endpoint carries counts, durations and agent cost for anyone
with a monitoring stack.

The console reads through one named source, and local discovery is the only implementation this
feature ships. That seam, and modelling a service as having one *or more* instances, is what keeps a
later console over a deployed installation additive rather than a rewrite — without taking on the
part that makes it expensive, which is reassembling one trace from several pods' separate windows.

The user's answers shaped it: everything Akka's console shows; ephemeral trace data; instrumentation
always on; `services logs` in this feature and independent of the console; agent cost in the console;
and the seam for a deployed console added now with its scope left out.

## Technical Context

**Language/Version**: Scala 3.9.0, JDK 21, sbt 1.12.15 (unchanged)

**Primary Dependencies**: **none new.** The recorder is JDK primitives in `runtime`; the console's
server is `jdk.httpserver`; the UI is hand-written HTML and JavaScript with no build step. This is
a hard constraint, not a preference — `ankka-runtime` is published, so anything added there is
imposed on every application that uses the platform (R2), and `cli` is defined by carrying no actor
system (R7).

**Storage**: none. Trace data is in memory, bounded, and lost on restart by decision (FR-004).
Logs are read from Kubernetes at request time and stored nowhere.

**Testing**: munit. New unit suites for the recorder, the ring's bounds, trace assembly and the
registry's staleness handling; a k3s suite for `services logs` against the shipped RBAC; a
benchmark for SC-003 that is a gate on the design, not a report.

**Target Platform**: unchanged. The console is local-only and binds loopback; metrics and logs are
for deployed services.

**Project Type**: a recorder in `runtime`, two exposures, a UI and one command in `cli`, one command
and one RBAC rule across `controlplane`, plus documentation.

**Performance Goals**: SC-003 — throughput and median latency within 5% of uninstrumented.
SC-004 — trace memory flat between 10,000 and 100,000 requests.

**Constraints**: no new dependency in `runtime` or `cli`; no change to `EntityProtocol.Command`; the
console may not reach a handler an external caller could not; the control plane gains no verb able
to create or alter a workload.

**Scale/Scope**: ~20 files. The UI is the largest single piece and the least risky; the recorder is
the smallest and the most.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; `CLAUDE.md` governs:

| Principle | How this plan keeps it |
|---|---|
| Effects are inert data; the runtime interprets them | this is *why* the runtime is the instrumentation point — no component author writes instrumentation and no existing component changes |
| Module dependency direction | recorder in `runtime`; `agent` writes to it (allowed, `agent → runtime`); `cli` still depends on `controlPlaneApi` alone; nothing new points inward |
| Explicit registration | the console shows the registry the runtime already holds — it discovers nothing by scanning, which would contradict "there is no classpath scanning" |
| Single copy | one recorder, two exposures; the console and the metrics endpoint read the same records rather than each computing their own |
| Withhold verbs rather than promise restraint | the control plane gains `get` on `pods/log` and nothing else — no `create`, no `delete`, no `exec` |
| Verify on a real cluster | `services logs` is proven with a token scoped to the shipped RBAC, not kind's admin credentials (R6, and the trap that made this rule) |
| Never touch `ActorContext` from a `Future` callback | the recorder is called from handler threads and never from an actor's async callbacks; it holds no actor reference |

**One tension worth naming rather than hiding**: `CLAUDE.md` says a `ThreadLocal` request context is
sound "precisely because ankka runs each handler on its own virtual thread", and that work handed to
another thread cannot see it. Tracing inherits that limit exactly. The plan does not try to beat it;
it requires the gap to be *visible* (R4). A trace that quietly reattached orphan spans would be the
failure mode this project's own traps are written to prevent.

No violations.

## Project Structure

### Documentation (this feature)

```text
specs/007-observability-console/
├── plan.md
├── research.md          # R1–R9, and five "verify first" items
├── data-model.md        # records, trace assembly, registry entry, price table
├── quickstart.md        # Tiers 1–5 and the reviewer's checklist
├── contracts/
│   ├── observability-endpoint.md   # what a running service exposes, in both modes
│   ├── console-ui.md               # the five panels, and what each must show
│   └── cli-commands.md             # `local console`, `services logs`
└── checklists/requirements.md
```

### Source Code (repository root)

```text
modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/
├── Recorder.scala              # NEW: the ring, the spans, the bounds
├── Trace.scala                 # NEW: identifiers, propagation via Metadata, assembly for readers
├── ObservabilityEndpoint.scala # NEW: the local exposure (jdk.httpserver, ephemeral port)
├── ObservabilityRoute.scala    # NEW: the Kubernetes exposure (ManagementRouteProvider, as VersionRoute)
├── ServiceRegistration.scala   # NEW: writes/removes ~/.ankka/running/<pid>.json in local mode
├── {EventSourcedEntityHost,KeyValueEntityHost,WorkflowHost}.scala   # record a span
├── {ProjectionRuntime,TimerRuntime,ShardingTransport}.scala          # record a span
└── resources/ankka-cluster-kubernetes.conf                           # + the management route

modules/http/src/main/scala/.../http/HttpServer.scala     # mint a trace at the entry point
modules/agent/src/main/scala/.../agent/AgentLoop.scala    # record model usage, incl. failed calls

cli/src/main/scala/com/thinkmorestupidless/ankka/cli/
├── Main.scala                  # + `local console`, + `services logs`
├── console/ConsoleServer.scala # NEW: jdk.httpserver, static assets, aggregation API
├── console/Source.scala        # NEW: the seam — where console data comes from
├── console/LocalSource.scala   # NEW: the only implementation this feature ships
├── console/Discovery.scala     # NEW: reads the registry, drops stale entries
└── resources/console/          # NEW: index.html, app.js, style.css — no build step

controlplane/src/main/scala/.../controlplane/
├── api/LogsEndpoint.scala      # NEW: authenticated, project-scoped
└── deploy/PodLogs.scala        # NEW: reads pods/log through the existing client

kustomization/components/controlplane/controlplane-rbac.yaml   # + get on pods, pods/log
README.md, CLAUDE.md
```

**Structure Decision**: the recorder sits in `runtime` because that is where invocations are
interpreted, and nowhere else can see them without the application's cooperation. The console sits
in `cli` because `ankka local console` is the UX being imitated and the JDK supplies everything it
needs; a twelfth module would add a second thing to install for one command. The exposure splits by
cluster mode because the code already establishes that pattern, and because local mode has no
management port on purpose (R1).

## Design notes that tasks must respect

1. **The 5% budget is a gate, not a report.** Benchmark before fixing the recorder's shape. If it
   cannot be met, take always-on back to the user rather than adding sampling — the spec's
   assumption and its checklist both say so, and quietly sampling would leave them lying.
2. **An unattributable span stays unattributed.** Never attach an orphan to the most recent trace.
   A wrong trace that reads correctly is worse than a visible hole.
3. **The console invokes over the service's real HTTP port**, as an ordinary client. This makes
   "cannot bypass an ACL" structural rather than a rule to remember (R5).
4. **The registry directory must be overridable by a system property**, for the same reason
   `Settings.path` checks `-Dankka.config` first — otherwise no suite can exercise discovery
   without writing into the developer's home.
5. **A stale registry entry is the normal case.** `kill -9` during development leaves one behind;
   probe before listing, and remove what does not answer.
6. **Nothing is added to `EntityProtocol.Command`.** Trace identity travels as `Metadata`, which
   already crosses sharding on every `ComponentClient` call (R4).
7. **`runtime` and `cli` gain no dependency.** If a task finds it needs one, that is a design
   failure to raise, not a line to add to `Dependencies.scala`.
8. **Unknown cost shows as unknown.** A model with no price yields tokens and no money, never zero
   — zero reads as free.
9. **Metrics and the console read the same records.** No second accounting path, or the two will
   disagree and both will be believed.
10. **`services logs` works with the console never started**, and the console works against a
    service the control plane has never heard of. Neither may become the other's prerequisite.
11. **The console reads through a `Source`, never from the registry directly.** `LocalSource` is
    the only implementation here. The UI and the aggregation API must not know that discovery is a
    directory of files, or a deployed source becomes a rewrite of both.
12. **A service has instances, plural, even locally.** Locally the list always has one entry. Every
    shape that carries a service — listing, trace, usage — admits several, because a deployed
    service has as many as it has pods, and a shape that assumes one is the expensive thing to
    change later. Handling *disagreement* between instances is explicitly not in this feature.
13. **`partial` means "this window does not hold it all", not "spans aged out".** The two causes
    are eviction (this feature) and spans living on another instance (a later one). One flag, one
    meaning, so a deployed source does not need a second.

## Phase 0 — research

Done: [research.md](./research.md). Findings R1 (local mode has no management endpoint — the
decision that shaped the rest), R4 (`Metadata` already crosses sharding) and R6 (the control plane's
current pod rights) were verified against the code; five items are carried to tasks as "verify
first".

## Phase 1 — design

Done: [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md).

## Post-design Constitution Check

Unchanged. Two nuances are recorded rather than resolved:

- **Disclosure, not capability** (R6): the control plane becomes able to read every service's
  output. No new verb lets it change anything, but a compromised control plane now discloses more.
  The spec accepted this as the price of SC-006; `README.md`'s "Not implemented" should say so
  plainly rather than leaving it to be discovered.
- **The console shows whatever the application put in its state**, including prompts and personal
  data. Local-only and loopback-bound is the whole mitigation, and it is the reason the console is
  not reachable from anywhere else.

## Complexity Tracking

No constitution violations to justify. One piece of scope is larger than it first appears and is
called out so it is not discovered late:

| Area | Why it is bigger than it looks |
|---|---|
| The UI | five panels, one of which is an interactive HTTP client and another a streaming view. Hand-written with no build step is the right call, but it is the bulk of the line count. |
| Trace assembly | records are flat and cheap to write; turning them into a correctly-nested tree with honest gaps, across a sharding hop, is where the real logic is. |

### Deferred, with the seam left for it

A console over a deployed installation is **not** in this feature. What it would add, recorded now
so the decision is not re-litigated from scratch:

| Piece | Cost | Already covered by the seam |
|---|---|---|
| Authentication and project scoping | small — the control plane already has tokens, projects and an ACL | n/a |
| Serving the UI | none — the CLI keeps serving it on loopback and points a different `Source` at the control plane | yes: `Source` |
| The JSON the UI consumes | none | yes: one contract, defined independently of its source |
| Reaching pods | small — the control plane already lists pods, and projects are not a network boundary today | n/a |
| **One trace split across pods** | **large** — fan out to every instance, merge by trace id, and accept that one instance's window may have evicted its half while another's has not | partly: `partial` and plural instances are modelled, the merging is not |

The last row is the feature. It is why a deployed console is 008 and not a flag on 007.
