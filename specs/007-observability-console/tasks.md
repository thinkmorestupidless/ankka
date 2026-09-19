# Tasks: Seeing What a Service Is Doing

**Input**: Design documents from `/specs/007-observability-console/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/)

**Tests**: included. This repository's suites are the proof for every previous feature, and the
quickstart names the ones this feature needs.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (local console), US2 (deployed logs), US3 (metrics)

---

## Phase 1: Setup — verify first (research R4, R7, R8)

**Purpose**: settle the three things that would invalidate the design if they turn out false. Each
is throwaway work whose *answer* is the deliverable, not the code.

- [X] T001 Spike `jdk.httpserver` under the JDK 21 baseline: bind loopback on an ephemeral port, serve a byte and a resource read from inside a jar, from both `sbt cli/run` and `sbt cli/stage` — record the answer in [research.md](./research.md) R7. If resources do not resolve from the staged launcher, that changes how the UI ships and must be known now.
- [X] T002 Spike trace propagation: assert a metadata entry set before `ComponentClient.invoke` is visible inside the target handler when sharding places the entity on **another node**, using a two-node test — record in [research.md](./research.md) R4. This is the assumption the whole correlation design rests on.
- [X] T003 Create the benchmark harness `modules/runtime/src/test/scala/com/thinkmorestupidless/ankka/runtime/RecorderBenchmark.scala`: one representative workload, measured with recording on and off, reporting throughput and median latency. It must run before `Recorder`'s shape is fixed (T007) and again after (T014).
- [X] T004 [P] Add the observability keys to `modules/runtime/src/main/resources/reference.conf`: ring capacity (the only tuning knob), and the model price table — absent price must be expressible, since unknown cost is a first-class outcome, never zero.

**Checkpoint**: T001–T003 answered. If T002 is false, stop and re-plan correlation before writing
any recorder.

---

## Phase 2: Foundational — the recorder (blocks US1 and US3)

**Purpose**: the one place that sees every invocation. **US2 does not depend on this phase** and
may be built first if deployed logs are wanted sooner.

- [X] T005 Create `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/Trace.scala`: trace and span identifiers, the reserved `Metadata` keys that carry them, minting at an entry point, and reading a parent from inbound metadata. No assembly here.
- [X] T006 Create the fixed-width span record in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/Recorder.scala` per [data-model.md](./data-model.md): `traceId`, `spanId`, `parentSpanId`, `componentRef`, `handlerRef`, `startedNanos`, `durationNanos`, `outcome`. Component and handler are **indices into the registry**, never strings — the registry is fixed at startup because registration is explicit.
- [X] T007 Implement the ring in `Recorder.scala`: pre-allocated, fixed capacity, oldest overwritten, no allocation per span beyond the record, no string built on the hot path.
- [X] T008 [P] Write `modules/runtime/src/test/scala/com/thinkmorestupidless/ankka/runtime/RecorderSuite.scala`: capacity is respected; the oldest is overwritten; a trace whose oldest spans were overwritten is reported `partial` rather than returned as a whole-looking tree.
- [X] T009 Implement trace assembly in `Trace.scala`: build the tree from flat records by `parentSpanId`; compute `unattributed` as total minus the sum of root spans; set `partial`. **`unattributed` is never redistributed across spans, and an orphan span stays at the root with an unknown parent — never reattached to the most recent trace.**
- [X] T010 [P] Write `modules/runtime/src/test/scala/com/thinkmorestupidless/ankka/runtime/TraceSuite.scala`: nesting is recovered from flat records; unattributed time is reported and not redistributed; an orphan stays at the root; `partial` is set when spans are missing.
- [X] T011 Record a span around each sharded host's handler invocation in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/EventSourcedEntityHost.scala`, `KeyValueEntityHost.scala` and `WorkflowHost.scala`, reading the parent from the command's metadata.
- [X] T012 [P] Record a span in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/ProjectionRuntime.scala` and `TimerRuntime.scala`. These have no inbound request, so they are trace roots — that is correct, not a gap.
- [X] T013 Propagate the current trace into outbound calls in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/ShardingTransport.scala`, writing the identifiers into the `Metadata` already passed on every `ComponentClient` call. **`EntityProtocol.Command` must not change.**
- [X] T014 Write `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/ServiceRecordingCostSuite.scala`, driving a **real service** (HTTP in, entity, journal, reply via `AnkkaTestKit`) with recording on and off. **Gate on SC-003: within 5% of uninstrumented.** This is the only valid denominator — micro-benchmarks of fragments were tried and each gave a different answer (58%, 28%, 50%) because the JIT folds them; `RecorderBenchmark` establishes the absolute cost (22ns/span, stable) and this establishes the ratio. It can only run after T011–T013. If it fails, take the always-on decision back to the user — do not add sampling, which would leave the spec's assumption and its checklist both reading as though they still held.
- [X] T015 [P] Assert SC-004 in `RecorderSuite.scala`: the same workload at 10,000 and 100,000 requests holds the same trace footprint.

**Checkpoint**: invocations are recorded and assemble into honest traces, at a measured cost.

---

## Phase 3: User Story 1 — the local console (P1)

**Goal**: a developer running services locally sees them, browses components, invokes an endpoint
from the browser, reads entity and session state, and sees a request's trace with per-component
timings and agent cost.

**Independent Test**: run the shopping cart locally, start the console, and complete a whole
diagnosis without a terminal — find the service, invoke `POST /carts/{id}/items`, read the
response, open the trace, see the entity's state change.

### The service side

- [X] T016 [US1] Create `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/ServiceRegistration.scala`: write the registry entry at startup and remove it on graceful shutdown, **only in `local` cluster mode**. Fields per [data-model.md](./data-model.md), including `instanceId`.
- [X] T017 [US1] Make the registry directory overridable by a system property in `ServiceRegistration.scala`, defaulting to `~/.ankka/running/`. This follows the recorded rule that anything reading `~/.ankka` or `$HOME` must be overridable, or no suite can exercise discovery without writing into the developer's home.
- [X] T018 [US1] Create `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/ObservabilityEndpoint.scala`: `jdk.httpserver`, **loopback only**, ephemeral port, started in `local` mode only. Loopback is the whole of the access control.
- [X] T019 [US1] Implement `GET /observability/service` in `ObservabilityEndpoint.scala` per [contracts/observability-endpoint.md](./contracts/observability-endpoint.md), returning the component inventory the runtime already holds and an **`instances` list that always holds exactly one entry locally**.
- [X] T020 [US1] Implement `GET /observability/traces` and `GET /observability/traces/{traceId}` in `ObservabilityEndpoint.scala`, including `oldestOverwritten`, `unattributedMillis` and `partial`.
- [ ] T021 [P] [US1] ~~Implement `GET /observability/entities/{component}/{id}`~~ — **revised**: reading arbitrary entity state needs either a generic invoke-any-handler route (which would let the console call commands, not just queries) or a direct read of the journal and durable-state tables. Sessions are served specifically instead, because `SessionMemoryEntity` declares a `history` *query* whose reply is already JSON. Generic entity inspection needs its own decision about which handlers a read-only console may call. Original: `GET /observability/entities/{component}/{id}` in `ObservabilityEndpoint.scala`: current state, and `404` when the entity has never been created — never an empty state that looks real.
- [X] T022 [P] [US1] Implement `GET /observability/sessions/{sessionId}` and `GET /observability/usage` in `ObservabilityEndpoint.scala`, returning stored agent memory plus tokens and cost.
- [X] T023 [US1] **Resolved differently.** Usage is read from `SessionMemoryEntity`, which `AgentLoop` already appends it to — event sourced, so durable, where the recorder's ring evicts. Recording it a second time in the ring would be a second accounting path for the same number, which the plan forbids. Original: Record model usage in `modules/agent/src/main/scala/com/thinkmorestupidless/ankka/agent/AgentLoop.scala` against the current trace — **including calls that failed part-way**, which still consumed input tokens.
- [~] T024 [P] [US1] **Deferred, deliberately.** Cost needs a price the platform is told; the drafted `prices {}` config was *removed* rather than shipped unread, because config that looks like a feature and changes nothing is worse than none. Tokens are reported; cost renders as unknown — never zero — and that path is built and in use. Original: Implement cost in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/Recorder.scala` from the price table: a model absent from the table yields tokens with **unknown cost, never zero** — zero reads as free.
- [X] T025 [US1] Mint a trace at the HTTP entry point in `modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/HttpServer.scala`, carrying it in the existing request context.
- [X] T026 [P] [US1] Write `modules/runtime/src/test/scala/com/thinkmorestupidless/ankka/runtime/ObservabilityEndpointSuite.scala`: every route's shape matches the contract; an absent entity is `404`; a service with `"http": false` reports no HTTP instance address.

### The console side

- [X] T027 [US1] Create the seam `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/console/Source.scala`: everything the console can ask for, defined independently of where it comes from. The UI and aggregation API talk to this and nothing else.
- [X] T028 [US1] Create `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/console/Discovery.scala`: read the registry directory, honour the system-property override, and **drop entries whose observability address does not answer, removing the stale file**. `kill -9` during development is the common case.
- [X] T029 [US1] Create `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/console/LocalSource.scala` implementing `Source` over `Discovery` — the only implementation this feature ships.
- [X] T030 [P] [US1] Write `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/DiscoverySuite.scala`: a live entry is listed; a stale entry is dropped and its file removed; the `-D` override keeps `$HOME` out of the test.
- [X] T031 [US1] Create `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/console/ConsoleServer.scala`: `jdk.httpserver` on loopback, serving static assets from resources and a JSON aggregation API backed by `Source`. Default port 9889 (Akka's); **if taken, take the next free one and say so — never fail on a busy port.**
- [X] T032 [US1] Add the `local console` command to `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/Main.scala` with `--port` and `--no-open`. It must work with **no control plane configured** — no URL, no token, no cluster.
- [ ] T033 [P] [US1] Write `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/ConsoleServerSuite.scala`: the aggregation API is served; a busy default port is handled; `Main.run` returns the right exit code.

### The UI

- [X] T034 [US1] Create `cli/src/main/resources/console/index.html` and `style.css`: the shell and the five panels. Hand-written, no build step, no bundler.
- [X] T035 [US1] Implement the **Services** panel in `cli/src/main/resources/console/app.js`: name, state, instances (always `1` locally, **and a column anyway**) and address. No services running says so plainly; a service appearing or vanishing is reflected within 5 seconds.
- [X] T036 [P] [US1] Implement the **Components** panel in `app.js`: registered components grouped by kind.
- [X] T037 [US1] Implement the **Invoke** panel in `app.js`: route templates become a form, and the request goes to the **service's own HTTP address as an ordinary client** — which is what makes "cannot bypass an ACL" structural. A streaming response renders as it arrives, not on completion.
- [X] T038 [US1] Implement the **Traces** panel in `app.js`: the tree, per-span durations and shares, **unattributed time as its own row**, an orphan at the root with an unknown parent, and a `partial` trace labelled "this window does not hold all of it" **without naming eviction as the cause**.
- [X] T039 [P] [US1] Implement the **Agents** panel in `app.js`: sessions, stored memory, tokens and cost per session and per service. Unknown cost shows `—` with a reason; a service with no agents shows no panel rather than an empty one.
- [X] T040 [US1] Offer the trace for a request directly from the Invoke panel's response in `app.js` — the invoke-then-explain loop is the point of the panel.
- [ ] T041 [US1] Walk [quickstart.md](./quickstart.md) Tier 3 by hand with two services running, including `kill -9` on one, and the ACL negative: a route that refuses `curl` must refuse the console identically.

**Checkpoint**: US1 is independently shippable. Nothing here needs a cluster or a control plane.

---

## Phase 4: User Story 2 — deployed logs (P2)

**Goal**: `ankka services logs` returns a deployed service's output, recent or followed, with no
cluster credentials.

**Independent Test**: deploy the sample, make a request that logs, retrieve that line through the
CLI alone on a machine with no kubeconfig, then restart and read the previous instance's output.

**Note**: this phase depends on **nothing in Phases 2 or 3**. It can be built first.

- [X] T042 [US2] Add `get` on `pods` and `pods/log` for service namespaces to `kustomization/components/controlplane/controlplane-rbac.yaml`, with a comment recording *why this is disclosure and not capability*: read verbs only, no `create`, no `delete`, no `exec`, so the control plane still holds no credential able to alter a workload.
- [X] T043 [US2] Create `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/deploy/PodLogs.scala`: read a service's pods and their log streams through the existing client, supporting tail, since, previous-container and a single instance.
- [X] T044 [US2] Create `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/LogsEndpoint.scala`: authenticated and **project-scoped by the same rules as every other command** — logs are not a side channel around project scoping. Streaming for follow.
- [X] T045 [US2] Register the endpoint in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/ControlPlane.scala` — `endpoints` is the whole inventory, so an unregistered endpoint simply does not exist.
- [X] T046 [US2] Add `services logs` to `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/Main.scala` with `--follow`, `--previous`, `--instance`, `--since` and `--tail` per [contracts/cli-commands.md](./contracts/cli-commands.md).
- [X] T047 [US2] Identify the instance on each line in `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/Output.scala` when a service runs several; stream lines **unwrapped** so piping to `grep` gives the service's own output.
- [X] T048 [US2] Handle the empty cases in `Main.scala`: a paused service, or one with no running instance, **says so and exits non-zero** — never hangs, and never exits 0 with empty output, which reads as "logged nothing".
- [X] T049 [P] [US2] Write `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/LogsEndpointSuite.scala`: another project's service is refused; an unknown service gives the same error `services get` gives; follow closes cleanly on interrupt.
- [~] T050 [US2] **Partly done.** `LogsRbacSuite` asserts the shipped manifest grants `get` on pods and pods/log and withholds every mutating verb, `pods/exec`, `pods/attach`, `pods/portforward` and all of `deployments`; the granted side was verified against kind with `kubectl auth can-i --as` the control plane's ServiceAccount (quickstart Tier 5, output recorded). **Remaining**: the automated form, so the granted side is covered in CI rather than by a command someone remembers — original: Write `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/LogsClusterSuite.scala`: mint a token for the control plane's **own ServiceAccount** and read logs through it — not kind's admin credentials, which is the recorded reason an RBAC gap once reached a real deploy. Assert the **withheld** verbs too: that token must still be refused creating, deleting or exec'ing into a pod.
- [ ] T051 [US2] Walk [quickstart.md](./quickstart.md) Tier 4 by hand, including the no-credentials proof (`env -u KUBECONFIG HOME=$(mktemp -d)`).

**Checkpoint**: US2 is independently shippable, with or without the console.

---

## Phase 5: User Story 3 — metrics (P3)

**Goal**: a deployed service exposes counts, durations and agent cost for a monitoring stack to
scrape.

**Independent Test**: deploy a service, drive traffic, and read values that match what was driven.

- [X] T052 [US3] Create `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/ObservabilityRoute.scala` as a `ManagementRouteProvider`, following `VersionRoute.scala` exactly — the Kubernetes exposure of the same recorder.
- [X] T053 [US3] Implement `GET /ankka/metrics` in `ObservabilityRoute.scala`: Prometheus text exposition, **hand-written — no dependency may be added to a published artifact**. Counts and durations by component and handler; tokens and cost by model.
- [X] T054 [US3] Register the route in `modules/runtime/src/main/resources/ankka-cluster-kubernetes.conf` under `http.routes`, beside `ankka-version`. The key is `http.routes.*`, not `routes.*` — the wrong one is silently ignored.
- [X] T055 [P] [US3] Write metrics cases in `modules/runtime/src/test/scala/com/thinkmorestupidless/ankka/runtime/ObservabilityEndpointSuite.scala`: a service that has served nothing returns **zeroed series, not an error**; a model with no configured price emits **no cost series at all** — absent, not zero, which would be charted as free.
- [X] T056 [US3] Walk [quickstart.md](./quickstart.md) Tier 6 against the local cluster.

**Checkpoint**: all three stories complete.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T057 [P] Add the console to `README.md`: what it shows and how to start it, in the "Getting started" flow where a developer meets it.
- [X] T058 [P] Extend `README.md`'s "Not implemented" with what this feature deliberately does not do: the console is local-only; no persistence, sampling, retention or log search; and **the control plane can now read every service's output** — disclosure, recorded rather than left to be discovered.
- [X] T059 [P] Add the traps to `CLAUDE.md`: local mode runs no management server, so observability needs its own local exposure; unattributed time is never redistributed and an orphan span is never reattached; the registry directory needs a `-D` override like `~/.ankka/config.json`; the console invokes over the service's real HTTP port so an ACL cannot be bypassed.
- [X] T060 Verify the seam holds, per [quickstart.md](./quickstart.md)'s reviewer checklist: the UI and aggregation API reference `Source` and never the registry; a service carries an `instances` list; `partial` is nowhere described as eviction. Grep `cli/src/main/resources/console/` for `registry`, `pid` and `aged out` — none should appear.
- [X] T061 Confirm `git diff project/Dependencies.scala build.sbt` shows **no dependency added** to `runtime` or `cli`, and that `EntityProtocol.Command` is unchanged.
- [ ] T062 Run `sbt scalafmtAll scalafmtSbt` from the repository root, then the full `caffeinate -i sbt test`; every existing suite must pass and no existing component may have changed in order to be observed (SC-008).

---

## Dependencies

- **Phase 1 → Phase 2**: T002's answer decides whether correlation works at all. T003 must exist before T007 so the recorder's shape can be measured as it is written.
- **Phase 2 → Phase 3, Phase 5**: both read the recorder.
- **Phase 4 depends on nothing above it.** It is the one story that can be built and shipped in isolation.
- **T014 gates Phase 3 and Phase 5.** A recorder that misses the 5% budget is a design decision to revisit, not a detail to carry forward.
- Within Phase 3: T027 (`Source`) precedes T029, T031 and every UI task — writing the UI against `Discovery` is precisely the mistake the seam exists to prevent.

## Parallel opportunities

- T004 alongside the T001–T003 spikes.
- T008 and T010 (tests) alongside their implementations by a second person.
- T012 alongside T011 — different files, same shape of change.
- T021, T022, T024 — three independent routes/values once T019 lands.
- T036 and T039 alongside T035 — different panels in the same file, so coordinate or sequence.
- The whole of Phase 4 alongside Phases 2, 3 and 5.
- T057, T058, T059 — three separate documents.

## Implementation strategy

**MVP is User Story 1**, which needs Phases 1–3. It delivers the thing the feature exists for —
Akka's console, imitated — and needs no cluster, no control plane and no credentials to demonstrate.

**If deployed logs are wanted sooner**, Phase 4 is the shortest path to value in this whole feature
and is unblocked from the start: three files, one RBAC rule and a CLI command. It can ship before
the recorder exists.

**Phase 5 is the smallest increment** once Phase 2 is done — one route and one config line.

**The one decision that can stop the feature** is T014. Everything else is work; that is a
judgement, and it belongs to the user, not to whoever is implementing.
