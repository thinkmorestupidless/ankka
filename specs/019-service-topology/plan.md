# Implementation Plan: Service Topology

**Branch**: `019-service-topology` | **Date**: 2026-10-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/019-service-topology/spec.md`

## Summary

Each running service describes its own topology in one document, rendered by one function in
`runtime`:
- **nodes**: its components, endpoints, topics, external services and the unknown caller;
- **declared edges**: read from the descriptors it registered, so complete by construction;
- **observed call edges**: counted in a bounded, windowed table.

The local console draws the document in a new Topology tab (phase 1). The control plane reads it
from every instance of a deployed service over a new mutual-TLS port that admits only the control
plane, merges the instances, and serves the result to the installation's console and the CLI
(phase 2).

The work is mostly not in the consoles. Research found that the runtime cannot say who is calling:
- the current thread knows a span id, not a component;
- workflows, agents and in-process consumers record no spans at all;
- the remote workflow host forwards its own caller's identity.

So the core of phase 1 is giving every host that runs user code a **caller**: a validated
`(component, handler)` pair, carried on the thread and in metadata, so it crosses sharding and the
sidecar. This lets Python, TypeScript and Rust services attribute calls with no SDK change. Each call
is then counted from the two viewpoints that can observe it: *handled* by the callee's host (`Ok`,
`Refused`, `Failed`), and *unanswered* (`TimedOut`, or `Undelivered` when the handler never ran). They are not summed,
because a thrown handler sends no reply, and only the callee knows it failed.

## Technical Context

**Language/Version**: Scala 3.9.0 and JDK 21 (runtime, http, agent, sidecar, controlplane, cli);
TypeScript on Node 22/24 (`console/package`); plain JavaScript (local console). All unchanged.

**Primary Dependencies**: **none new.**
- The counters are JDK primitives in `runtime`, which is a published module, so anything added there
  is imposed on every application.
- The `observe` listener is the JDK `HttpsServer` the local endpoint already uses.
- Graph drawing is hand-written SVG in both consoles.

**Storage**: none. Counts are in memory, windowed and lost on restart, the same as traces.

**Testing**:
- the living features under `features/`, run as written by `GherkinSuite` where one suite can run
  a whole file (the test kit's and the control plane's), and as tests named after each scenario
  where none can (the consoles' rules, the per-language rows, the documentation);
- munit for `runtime` units, for what no scenario states, and sidecar conformance cases (every SDK
  and the WebAssembly module);
- a k3s case in `EndToEndClusterSuite`;
- `ConsoleServerSuite` for the local console;
- node tests and Playwright, against the fake and compose, for the installation console;
- the existing benchmark gates, re-measured.

**Target Platform**: unchanged. Phase 1 runs wherever a service runs outside Kubernetes. Phase 2
needs the Kubernetes overlay with zero trust (feature 014).

**Project Type**:
- the runtime: caller propagation, counting and the topology document;
- one new listener in the overlay;
- one operator rendering change;
- one control plane route and one CLI command;
- two console views;
- documentation.

**Performance Goals**:
- SC-004: recording and counting add < 1% to a real call, end to end.
- SC-005: the counts do not depend on the number of distinct entity ids (ten thousand, through the real call path).
- SC-006: local refresh within 3 s.
- SC-007: deployed read within 5 s at three instances.

**Constraints**:
- no new dependency in `runtime`, `cli` or `controlplane`;
- no protocol (`.proto`) change and no SDK change;
- no change to `EntityProtocol.Command`;
- no change to span component names, because metric labels depend on them;
- the control plane gains no RBAC verb, and no workload gains a new path to another;
- nothing unbounded is interned.

**Scale/Scope**: roughly 35 files across 9 modules and the console package. The caller work in the
hosts is the riskiest part. The consoles are the largest and least risky.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the set of principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets them | pass | No effect changes. Counting happens where the runtime already interprets: the host's span completion and the transport. |
| Two interpreters of one thing share one function | pass | `TopologyJson.render` is the only renderer, for local and observe alike. Sources are read with the same match `ProjectionRuntime` uses. `layer` is computed once, so the two consoles cannot disagree (R5, R8). |
| Module dependency direction | pass | Counting and rendering are in `runtime`. `http` reports its routes' endpoints through the existing route report. `agent` marks its platform descriptors. `sidecar` reads `ankka-caller`. `controlplane` gains nothing from `runtime`: the wire types are in `controlplane-api`. |
| No classpath scanning; explicit registration | pass | The topology is the registry. Caller validation is against the registry. |
| Wire names are a versioning boundary | pass | Callers and callees are identified by declared wire names only, never by Scala identifiers. |
| `RuntimeExtension` seam | pass | The `observe` listener is started where `ClusterFormation` starts management, under the Kubernetes overlay. Nothing in `runtime` learns about HTTP routes beyond what is already reported. |
| `ModuleCommand`: hosts reply to unexpected commands | pass | No new command. Stream counting happens at stream end and never withholds a reply. |
| Never touch `ActorContext` from a `Future` | pass | Transport-side counts are written in `transform`, from captured values only. |
| Never intern anything unbounded | pass | Caller and callee names are interned only after they match the registry. External service names, which can come from request input, are admitted up to a fixed limit and the rest share one id. Paths are never a key. Too many declared names fails startup; nothing on a call path can fail on the table (R1, R2, R3, R6). |
| Orphans are shown, not guessed | pass | Unknown caller is a node (FR-009). The transport strips a forwarded `ankka-caller` when there is no current caller. |
| A refusal is not a failure | pass | `handled.refused` is separate and never marks an edge as failing (FR-008). |
| TLS client auth belongs to a listener | pass | It is a new port, not a route on management (R7). |
| A port's name is load-bearing | pass | `observe` is named. It is not in any immutable selector, and it is not in readiness. |
| Old images exist in real clusters | pass | Refused connection → `unsupported`, per instance, partial. It is never an error for the whole request. |
| No secret value in the journal | pass | Nothing is journaled. |
| A test never binds a fixed port | pass | Local suites use the ephemeral loopback endpoint. 7628 is bound only in pods. |
| An `eventually` waits for the thing it asserts | pass | Suites retry on the counted value, not on an edge existing (quickstart §2). |
| Could this check pass while the thing is false? | pass | Each scenario is broken once and watched go red. The conformance filter count is asserted (R11). |
| Docs: pages stand alone, samples from tested code, generated references | pass | Phase 6 below. |

**Violations to justify**: none. Additions that widen the platform's surface are listed under
*Complexity Tracking*.

## Project Structure

### Documentation (this feature)

```text
specs/019-service-topology/
├── plan.md
├── research.md          # R1–R11
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── topology.md            # the document, both exposures
│   ├── observe-port.md        # port 7628, TLS peer, network policy
│   ├── control-plane-route.md # GET /services/{p}/{n}/topology and the CLI
│   └── console-ui.md          # both consoles
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/
├── Trace.scala                    # thread-local gains Caller; within(traceId, spanId, caller)
├── Caller.scala                   # NEW: Caller, ankka-caller codec, validation against the registry
├── CallCounts.scala               # NEW: packed-key windowed buckets, histogram, snapshot
├── Observability.scala            # holds CallCounts beside Recorder; window settings
├── ShardingTransport.scala        # stamp/strip ankka-caller; validate callee; count unanswered
├── ViewClient.scala               # count view queries (caller side, handled)
├── HttpServiceClients.scala       # count service-to-service calls; propagate trace
├── EventSourcedEntityHost.scala   # count handled on span completion
├── KeyValueEntityHost.scala       # count handled on span completion
├── WorkflowHost*.scala            # NEW spans + caller around commands and steps
├── ProjectionSupport.scala        # views: set current span + caller
├── ProjectionRuntime.scala / TopicHandlers.scala   # consumers: span + caller
├── TimerSweeper.scala             # pass caller to Trace.within
├── TopologyJson.scala             # NEW: the one renderer; layer; declared edges
├── ObservabilityEndpoint.scala    # GET /observability/topology; routes gain endpoint
├── ObserveServer.scala            # NEW: 7628 HttpsServer, Peers.Exactly, two routes
├── RotatingTls.scala              # Peers.Exactly(uri)
├── ClusterFormation.scala         # start ObserveServer in the kubernetes overlay
└── remote/                        # Remote{EventSourced,KeyValue,Workflow}Host, RemoteProjection,
                                   #   RemoteAgent: stamp own caller into Command.metadata;
                                   #   workflow stops forwarding its caller's; count handled
modules/runtime/src/main/resources/ankka-cluster-kubernetes.conf   # observe listener on
modules/runtime/src/main/resources/reference.conf                  # call-window, call-buckets
modules/http/src/main/scala/.../http/HttpServer.scala              # Trace.within with caller; route→endpoint
modules/agent/src/main/scala/.../agent/                            # AgentHost, autonomous IterationLoop:
                                                                   #   spans + caller; platform flag
modules/core/src/main/scala/.../core/ComponentDescriptor.scala      # `def platform: Boolean = false`
sidecar/src/main/scala/.../sidecar/ClientLogic.scala               # read ankka-caller → Trace.within
operator/src/main/scala/.../operator/{Rendering,ZeroTrust}.scala   # port observe; ingress from control plane
controlplane-api/src/main/scala/.../api/descriptors.scala          # ServiceTopology, InstanceTopology, codecs
controlplane/src/main/scala/.../controlplane/
├── api/ServiceEndpoint.scala      # GET /{projectId}/{name}/topology
└── deploy/InstanceTopology.scala  # NEW: list pods, read observe with own cert, merge
cli/src/main/scala/.../cli/Main.scala                 # services topology
cli/src/main/scala/.../cli/console/{Source,LocalSource,ConsoleServer}.scala   # /api/topology/
cli/src/main/resources/console/{index.html,app.js,style.css}                  # Topology tab
console/package/src/
├── routes.ts                      # page topology
├── routes/service-topology.tsx    # NEW
├── routes/stream.service.ts       # ?topology
├── ui/topology/{layout.ts,Graph.tsx,Table.tsx}   # NEW
├── client/{schemas.ts,control-plane.ts}
└── testing/fake-control-plane.ts
docs/operate/local-console.md, docs/operate/console.md, docs/concepts/observability.md,
docs/reference/{limitations,runtime-endpoints,control-plane-api,cli}.md, docs/platform/networking.md
```

Tests:
- `modules/runtime/src/test`: `CallCountsSuite`, `TopologyJsonSuite`, `CallerSuite`, and
  `RecorderBenchmark` extended.
- `modules/testkit/src/test`: `TopologySuite`, and `ServiceRecordingCostSuite` re-measured.
- `sidecar/src/test/.../conformance/ConformanceSuite.scala`: two cases.
- `operator/src/test`: rendering and network policy.
- `controlplane/src/test`: `EndToEndClusterSuite` case, the route reference, and the
  `services topology` suite.
- `controlplane-api/src/test`: fixtures.
- `cli/src/test`: `ConsoleServerSuite` and `CliReferenceSuite`.
- `console/package/test` and `console/e2e/tests`: `topology.spec.ts`.

**Structure Decision**: existing modules only. No module is added, because each change belongs to
the module that already owns that concern. The single new runtime concept, the caller, sits beside
`Trace`, whose rule ("work handed to another thread cannot see it") it inherits.

## Order of work

1. **Caller (P1 foundation).** `Caller`, the widened `Trace`, and validation. Spans and caller in the
   hosts that lack them (workflow, agent, autonomous, in-process view and consumer). Remote hosts
   stamp their own caller, and the remote workflow host stops forwarding. `ShardingTransport`
   stamps or strips. `ClientLogic` reads. Tests: every host's calls attributed, the hand-off case
   unknown, and a forwarded `ankka-caller` stripped.
2. **Counting (P1).** `CallCounts`, handled at span completion, unanswered at the transport, view
   queries, service clients. Benchmarks re-measured; the gate is SC-004.
3. **Document (US1 + US2).** `TopologyJson`, `/observability/topology`, routes gain `endpoint`, and
   `platform`. Conformance cases across every SDK and the module.
4. **Local console (US1, US2, US3).** `Source.topology`, the tab, layout by layer, panels, focus and
   filter, links to local services.
5. **Phase 2 (US4).** `Peers.Exactly`, `ObserveServer`, the overlay, operator rendering and policy,
   `InstanceTopology` merge, the route, wire types and fixtures, CLI, the console page and stream,
   the fake, Playwright, and the k3s case.
6. **Docs (US5).** Topology sections in the local console and console pages, the observability concept
   (two edge kinds, observed-not-complete, unknown caller, window), the runtime endpoints reference
   (port 7628), networking, limitations rewritten, and the generated references refreshed. New pages
   are not needed; sections are added to existing ones, which are already in `nav` and the skills.

Steps 1 to 4 ship as phase 1 and are useful alone. Step 5 depends on 1 to 3, not on 4.

## Complexity Tracking

| Addition | Why needed | Simpler alternative rejected because |
|---|---|---|
| A fourth workload port (7628 `observe`) | The control plane must read instances, and management admits only the service's own identity | Widening management loosens what bootstrap relies on. `pods/proxy` grants every port of every pod. A route on 9000 enters the app's ACL and path space (R7). |
| `ankka-caller` metadata key | The caller must cross sharding and the process boundary | A span id table is per node. Interned ints are per process. Inferring from traces is guessing (R1). |
| Two counting viewpoints (handled / unanswered) | A thrown handler sends no reply, so only the callee can say *failed* and only the caller can say *timed out* | Transport-only counting misreports throws and refusals. Making hosts reply on throw changes command semantics (R2). |
| Spans added to workflows, agents and in-process consumers | They are callers, and without spans they cannot be attributed | Leaving them out would make the most common callers unknown, which is useless as a topology. |

## Constitution Check (post-design)

Re-evaluated after Phase 1: all rows still pass. The design adds:
- no dependency, no DDL, no published module, no `.proto` change and no SDK change;
- one metadata key, one port, three configuration keys (`ankka.observability.call-window`,
  `call-buckets`, `max-external-services`), one control plane route and one reserved project id
  (`platform`).

The configuration keys and the route must appear in the generated references, with prose
describing them, or `docs check` and the route suite fail. That is the intended guard.

The spec was corrected in three places during planning. They are recorded in its Clarifications
section:
- **FR-007**: outcomes are split into handled (`Ok`/`Refused`/`Failed`) and unanswered
  (`TimedOut`/`Undelivered`).
- **SC-003 and the scenario "a call its caller stopped waiting for is counted as timed out, and as handled when the handler finishes"**: restated per viewpoint.
- **FR-022's assumption about the network path** is resolved as port 7628.

The cross-artifact analysis then changed the design in five places, recorded in the spec's second
Clarifications session and in `research.md`:
- **External service names are admitted up to a limit** (R3). A name can come from request input,
  so "bounded by what the code names" was false, and the gate row *never intern anything unbounded*
  did not pass as first written. It passes with the admission limit.
- **Callee names are validated like caller names, and the interner never throws at runtime** (R2,
  R6).
- **`Undelivered` replaces `Unreachable`** and also covers calls the platform answered without
  running a handler, so no call goes uncounted (R2).
- **The local console's display rules live in one tested file** (`cli/src/main/resources/console/topology.js`),
  and both consoles are held to the same fixtures (tasks).
- **`platform` becomes a reserved project id** (R7), which the `observe` port's peer check relies on.

After the acceptance scenarios moved into `features/`, three decisions followed (research R13):
the features are run by `GherkinSuite` wherever one suite can run a whole file, CI gates on
`speckit-bdd check`, and every glossary term is settled.

The medium findings were settled in a second pass, recorded in research R12: the console's own
reads are not counted; the new spans are a requirement (FR-027); trace headers are not propagated
between services; the Topology tab refreshes every 2 s; and SC-002, SC-005 and SC-007 are each
measured by a named test.

## Not in this feature (from the spec's Out of Scope, restated for the tasks)

- static analysis;
- a build-time manifest;
- declared or enforced `calls`;
- history;
- a project-wide graph;
- deployed traces, sessions or entity state;
- export;
- making entity hosts reply when a handler throws (R2: a separate decision);
- traces that cross from one service to another (R3);
- routes on `observe` beyond `service` and `topology`. Traces there would be a natural next feature,
  and the port is built so that adding them is one route, but the spec does not ask for them.
