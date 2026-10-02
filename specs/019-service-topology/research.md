# Research: Service Topology

Every decision below was checked against the code as of `dd0a603`. File references are relative to
the repository root.

## R1. Who is calling: the runtime does not know today

**Finding.** When a handler calls the component client, the only thing on its thread is
`Trace.currentTrace: Option[(Long, Long)]`, which holds a trace id and a span id
(`modules/runtime/.../Trace.scala:102-107`). The component and handler of that span live only in the
recorder's ring arrays, indexed by slot. Nothing maps a span id back to them, and a span still in
flight cannot be read at all (`Recorder.scala:80, 99-105`). Several hosts set no current span:

| Host | Records a span | Sets the current span on its thread |
|---|---|---|
| HTTP endpoint (`HttpServer.scala:589-602`) | yes (component `"http"`, handler `"POST /carts/{cartId}/items"`) | yes |
| Event-sourced / key-value entity, in process | yes | yes |
| Timed action (`TimerSweeper.scala:225-257`) | yes | yes |
| View, in process (`ProjectionSupport.scala:49-67`) | yes | **no** |
| Consumer, in process (`ProjectionRuntime.scala:541-579`, `TopicHandlers.scala:84-97`) | **no** | **no** |
| Workflow, in process and remote (`RemoteWorkflowHost.scala:115-125`) | **no** | **no** |
| Agent and autonomous agent | **no** | **no** |
| Remote entity, view, consumer, timer | yes | no; the span travels in `Command.metadata` to the process |

The remote workflow host passes its *own caller's* metadata to the process, and `runStep` sends
none. As things stand, a workflow's calls would be attributed to whoever started the workflow,
which is the exact misattribution FR-009 forbids.

**Decision.** A *caller* is a pair `(component, handler)` of declared names, and every host that runs
user code makes one available to calls that code makes:

1. `Trace`'s thread-local widens from `(traceId, spanId)` to `(traceId, spanId, Caller)`.
   `Trace.within` takes the caller, so every site that already sets a span (`HttpServer`, both
   entity hosts, `TimerSweeper`) passes one with no other change.
2. Every host in the "no" rows records a span around its user code and sets it current:
   - in-process view and consumer: handler `on-change` / `on-message`, the names the remote ones
     already use;
   - workflow: handler = the step name, or the command name for a command;
   - agent: handler = the command name;
   - autonomous agent: handler `iteration`, around each iteration and around task-start guardrails.
   This also puts these components into traces and metrics, which they were missing anyway.
3. The caller crosses the sharding boundary and the process boundary in metadata, beside the span
   ids, as one key `ankka-caller` = `<component>#<handler>`. `ShardingTransport.ask` stamps it from
   the thread-local. When there is no current caller it **removes** any `ankka-caller` already in
   the metadata, so identity forwarded by user code never survives. The remote hosts stamp their
   own caller into `Command.metadata` beside the span they already put there. The remote workflow
   host is changed to stamp its own span and caller instead of forwarding its caller's.
4. The sidecar's callback path (`sidecar/.../ClientLogic.scala:93-110`, used by gRPC processes and by
   WebAssembly imports alike) runs on a thread with no current span. It reads `ankka-caller` from
   the metadata the process forwarded and runs the transport call under `Trace.within`. All three
   SDKs already forward the metadata their handler received on every client call
   (`sdks/python/src/ankka/client.py:80-102`, `sdks/typescript/src/client.ts:90`,
   `sdks/rust/ankka/src/client.rs:52`). Attribution for Python, TypeScript and Rust services
   therefore needs **no SDK change**.

**The caller's names are validated, never trusted.** A caller read from metadata is used only if
`<component>` is registered in this service and `<handler>` is one of its declared handlers, or is
one of the service's HTTP routes or a declared timer method. Anything else is the unknown caller.
This keeps the name table bounded (CLAUDE.md: never intern anything unbounded), and it means a
process that sends nonsense is reported as an unknown caller rather than a new node. The check is a
lookup in a set built once at startup, so it costs nothing that grows.

**Alternatives considered.**
- *A span id to component table, filled at `begin` and cleared at `complete`.* It holds only
  process-local ints, so it cannot resolve a caller whose span is on another node. A hop through
  sharding crosses nodes routinely.
- *Interned ints in metadata.* Interned ids are per process. Names are the only identity that is
  valid on both ends.
- *Inferring the caller from the trace tree when it is read.* A span is a child of whatever was
  current, so this would attribute a call to the nearest open span, which is the guessing the spec
  forbids. It would also lose edges whenever the ring overwrote their spans.

### R1, as built

Decided while implementing, and where the code differs from the text above:

- The type is `CallOrigin` (`RT/CallOrigin.scala`), read with `Trace.currentOrigin`, because `http`
  already has a `Caller`: who a request is from, read off a certificate. The metadata key is still
  `ankka-caller`.
- An endpoint's origin is its node id and the route's whole path (`endpoint:/carts`,
  `POST /carts/{cartId}/items`), not the span's `http` and the route within the endpoint. The
  span's names are unchanged (FR-027), and the document needs no mapping from `http` to a node.
- The console's origin is **not** a declared name. It is set only by the runtime's own thread
  (`Trace.asOrigin(CallOrigin.Console)`), so a process that sends it is not believed.
- `Trace.asOrigin` is a handler at work in no trace: an autonomous agent's worker between
  iterations, and the sidecar making a call a process asked for.
- `Trace.capture` and `Trace.resume` carry a scope to another thread, for the one case where the
  code knows the work is the same: an agent's stream, and an autonomous agent's notifications, are
  sent when the source is run, which is on the server's thread, and are the call of the handler
  that asked for them. Without this a streaming route's call came from the unknown caller.
- A workflow step in another language is told its trace and its own name: `WorkflowIn.RunStep`
  gains `metadata`, and so do `ToolRequest` (a tool an agent in another language runs, which the
  reference service's own tool showed up as a call from nobody) and `QueryRequest` (a view query
  from another language). The protocol is `1.3`. A guardrail and a task rule are still told
  nothing, so a call one of them makes is from the unknown caller.
- The limit on declared names is 60,000, short of the key's 65,535, to leave room for the names
  that are not a service's own.

## R2. Where a call is counted, and what its outcome is

**Finding.** A handler that throws in an entity host propagates out of the Pekko command handler.
No reply is sent (`EventSourcedEntityHost.scala:136-148`), so the caller sees a `TimeoutException`
after `askTimeout` (`ShardingTransport.scala:68-75`). On the caller's side, a thrown handler and a
slow one look the same. A refusal arrives as `Rejected` carrying the callee's `ErrorCode`, which the
caller cannot tell apart from a platform error with the same code. Only the callee's host knows
which of `Ok`, `Refused` and `Failed` happened; it already decides this for its span
(`interpret` returns a `SpanOutcome`).

**Decision.** Each call is counted from two viewpoints, kept separate rather than summed:

- **Handled.** The callee's host counts `(caller, callee component, callee handler)` when its span
  completes, with the span's outcome (`Ok`, `Refused`, `Failed`) and the handler's duration. The
  caller comes from the incoming `ankka-caller`, validated as in R1, or is unknown.
- **Unanswered.** `TimedOut`: no reply within `askTimeout`, counted by the caller's transport, the
  only place that can see it. `Undelivered`: the handler never ran. The callee's host counts it when
  it replies without running a handler (`Rejected(NotFound)` for a method it does not declare,
  `Unavailable` from a stopping host); the caller's transport counts it when no host was reached.
  Counts are attempts: a call the sidecar retries three times is three undelivered attempts.

**Both ends are validated.** The callee's names are checked like the caller's (R1) before anything
is counted. A registered component with an undeclared method counts under the fixed handler name
`(undeclared)`. An unregistered component is not counted and creates no node. Without this, the
names a sidecar process sends as a callee would reach the name table unchecked.

A handler that throws therefore shows as one *handled, failed* call and one *unanswered, timed out*
call on the same edge. Both are true, and each is stated from the viewpoint that observed it. A
handler that is merely slow shows as *handled, ok* plus *unanswered, timed out*: the work happened
and the caller gave up. The console labels the two groups so that neither is mistaken for a total.

**Spec corrections that follow.** FR-007 lists four outcomes as if one observer saw all of them. It
becomes: handled calls by `Ok`, `Refused` and `Failed` with durations, plus unanswered calls by
`TimedOut` and `Undelivered`. The scenario
"a call its caller stopped waiting for is counted as timed out, and as handled when the handler finishes"
holds as an *unanswered, timed out* count.
SC-003 is restated per group. These are recorded in the spec's Clarifications section.

**Alternatives considered.**
- *Count only at the transport* (the "one chokepoint" in the input). It would count a thrown handler
  as `TimedOut` and could not separate a refusal from a failure, so the scenario
  "a handler that fails is counted as failed where it ran and as timed out where it was called" would fail.
- *Make hosts reply when a handler throws.* That would fix the caller's view, but it changes command
  semantics (a fast `Internal` error instead of an actor restart and a timeout) for every caller. It
  is a separate decision with its own consequences, and it is not needed here.
- *Dedupe the two viewpoints with a per-call id.* That needs per-call state, which is unbounded.

## R3. Calls that do not go through a host

- **View queries** (`modules/runtime/.../ViewClient.scala`) read Postgres directly; there is no view
  host to count them. The `ViewClient` counts them on the caller's side as *handled*, with handler
  names from the fixed set `get`, `where`, `ordered`, `all` and `count`, outcome `Ok`, or `Failed`
  on an exception. Duration is the query's.
- **Calls to another service** (`HttpServiceClients.scala:37-160`) are untraced today. They are
  counted on the caller's side, keyed by the target service (`service:<project>/<name>`) and the
  HTTP method **only**. The path is a filled-in string and unbounded, so it is never a key. A service
  name can come from request input too (the shopping cart sample's `GET /callers/call/{service}`
  passes its path parameter to `services(…)`), so it is bounded by admission, not by trust: a process
  admits at most `ankka.observability.max-external-services` names (default 32), interns a name only
  when it is admitted, and counts calls to any further service under the fixed callee
  `service:(other)`. A 2xx response is `Ok`, a 4xx is `Refused`, a 5xx is `Failed`, a client timeout
  is `TimedOut` and a connection failure is `Undelivered`. The callee service is shown as an external
  node. Trace headers are **not** propagated: the receiving `HttpServer` mints a new trace for every
  request and reads none (`HttpServer.scala:589-602`), so a trace that crosses services is a feature
  of its own and out of scope here.
- **Timers.** A fired timer is already a span with the timed action as its component
  (`TimerSweeper.scala:244-252`). Its calls are attributed to it like any handler's. The *scheduling*
  call (`DeferredCall`) is not a call and is not counted. The declared target of a timed action is
  not known at registration, so it appears only once a timer fires.
- **Streams.** `tell(... InvokeStream ...)` is counted when the stream ends: handled on the callee
  with the stream's outcome and lifetime, and unanswered on the caller if it never started.

## R4. Endpoints in the topology

**Finding.** Every endpoint span has component `"http"` and the route's description as its handler
(`HttpEndpoint.scala:128,144`). Changing the component would change the `component` label on
`ankka_invocations_total`, a metrics break.

**Decision.** Spans keep `"http"`. Each route the server reports gains an `endpoint` field: the
prefix the endpoint was registered with (`HttpEndpoint(prefix)`), or the component id for a remote
endpoint (`ComponentKind.Endpoint`). The topology groups routes into one node per endpoint by that
field. The caller of a call made inside a route is `http#<route>`, which maps back to its endpoint
node through the same field.

## R5. Declared connections are already in the registry

`ComponentRegistry.components` holds whole descriptors (`modules/core/.../ComponentDescriptor.scala:41`),
and `ObservabilityEndpoint` already matches them onto SDK types (`:260-265`). Sources and
destinations are read from four types:

| Descriptor | Source | Destination |
|---|---|---|
| `ViewDescriptor` (`sdk/View.scala:83`) | `ChangeSource.EventSourced \| KeyValue \| Topic` | none |
| `ConsumerDescriptor` (`sdk/Consumer.scala:74`) | the same | `produceTo` |
| `RemoteViewDescriptor` (`runtime/remote/RemoteDescriptors.scala:63`) | `RemoteSource.Component(kind, id) \| Topic` | none |
| `RemoteConsumerDescriptor` (`:73`) | the same | `producesTo` |

A remote source naming a `KeyValueEntity` is a state subscription; one naming an
`EventSourcedEntity` is an event subscription. `ProjectionRuntime.scala:131-145` already makes the
same distinction, and the topology reuses that match rather than writing a second one. Discovery
already refuses a component source that names no declared component (`sidecar/.../Discovery.scala:214-228`),
so within a polyglot service an external source cannot arise. A Scala view sourced from an entity
registered in another service can, and it becomes an external node (FR-005).

**Platform components.** Names starting `ankka-` are a convention, not a rule: `ids.scala:18`
allows a user to choose one. A component is a platform component when it is one of
`AgentRuntime.descriptors`. Those are marked by a `platform` flag the agent module sets on them,
never inferred from the prefix.

## R6. Bounded counts in a window

**Decision.** `CallCounts` lives beside `Recorder` in `Observability`. Its key is the four interned
names of an edge, packed into one `Long`, 16 bits each. The names table is bounded by registration
(R1). After registration, startup fails if the declared names exceed 65,535, naming the count. At
runtime the interner never throws, so nothing on a request path can fail on the name table: every
runtime caller interns only names that validation accepted, plus the fixed ids `(unknown)`,
`(undeclared)` and `(other)`. Each edge holds
a ring of time buckets: by default 60 buckets of 10 seconds, so 10 minutes. Each bucket holds:
- counts per outcome (handled `Ok`, `Refused`, `Failed`; unanswered `TimedOut`, `Undelivered`);
- a fixed log-scale duration histogram of 32 buckets.

Percentiles are read from the histogram, so they are bucketed rather than exact. The contract
reports them as such. A stale bucket is reset when it is next written. Reading folds the live
buckets and reports `windowSeconds` and the start time actually covered (`since`), which is shorter
than the window after a restart. Memory is edges × buckets × fixed width, a function of registration
only (FR-010, SC-005). Settings: `ankka.observability.call-window = 10m`,
`ankka.observability.call-buckets = 60` and `ankka.observability.max-external-services = 32`.

**Cost.** Writing is one map lookup on a primitive key plus atomic increments, with no allocation.
`RecorderBenchmark` gains a `CallCounts` case. `ServiceRecordingCostSuite` divides a hardcoded
22 ns by a real invocation's cost, so it is re-measured with spans *and* counts, and SC-004's 1%
budget is asserted there.

**Alternatives considered.**
- *Fold counts out of the span ring when read.* Edges would vanish when spans are overwritten, and a
  call's two viewpoints are not both spans.
- *Counters since start.* They grow without a window. The metrics page already avoids this rule.

## R7. Phase 2: how the control plane reaches an instance

**Finding.** Today the control plane cannot reach anything that would serve this:
1. The `/observability/*` endpoint runs only outside Kubernetes (`Ankka.scala:232-239`).
2. Management (7626) requires the service's *own* certificate (`RotatingTls.Peers.SameIdentity`),
   and its network policy admits only the service's own pods (`ZeroTrust.scala:146-173`).
3. The control plane's RBAC has no `pods/proxy`.

What the control plane does have is a service certificate with URI `ankka://platform/controlplane`
(`kustomization/components/controlplane/zero-trust.yaml`), issued by the same `ankka-service`
authority every workload trusts. It also has `pods: get, list` in project namespaces (used by logs).

**Decision.** A fourth workload port, **7628 `observe`**, serves the observability contract under
Kubernetes.
- It is mutual TLS with the service certificate and admits exactly one peer, `ankka://platform/controlplane`
  (a new `RotatingTls.Peers.Exactly(uri)`).
- The network policy rendered for the workload admits only the control plane's pods, by namespace
  and label.
- It serves only reads: `service` (inventory) and `topology`. No trace, session or query route is
  exposed, so this feature widens what can be read about a deployed service by the topology and
  nothing else (spec Assumptions).
- The control plane lists the service's pods (an existing grant) and calls each pod's IP on
  `observe`, presenting its own service certificate.

This follows the trap recorded in CLAUDE.md: client authentication belongs to a listener, not a
route. Management cannot admit a second identity without loosening what guards bootstrap, so a
different peer gets a different port.

**The peer check relies on a reserved project id.** A workload's identity is
`ankka://<projectId>/<serviceName>` (`operator/.../ZeroTrust.scala:85`), and `ProjectId.problems`
(`controlplane-api/.../descriptors.scala:57-75`) accepts `platform` today. A tenant project
`platform` with a service `controlplane` would be issued the control plane's identity; the network
policy would then be the only guard on 7628, and any service whose ACL admits
`platform/controlplane` or `platform/console` is exposed already. This feature reserves the id: the
control plane refuses to create the project and the operator refuses to issue the certificate.

**An instance without the port** (an image whose runtime predates the feature) refuses the
connection. It is reported per instance as `unsupported` (runtime too old), and the topology is
partial (FR-023). It is never treated as an error for the whole request, the same shape as
per-instance log failures (`ServiceEndpoint.scala:185-214`).

**Alternatives considered.**
- *`pods/proxy` through the API server.* The API server cannot present a client certificate the
  workload trusts, and `pods/proxy` reaches every port of every pod in the namespace, including the
  service's own HTTP routes. It is a much broader grant than this needs.
- *A platform route on the service's HTTP port (9000), called as `ankka://platform/controlplane`.*
  It would put a platform route into the application's route tree, under the application's ACL and
  path space.
- *Proxy through the operator.* Rejected for logs for the same reasons (`controlplane-rbac.yaml:70-84`):
  the operator should depend on as little as possible.
- *Open management to the control plane.* That loosens a listener whose guarantee bootstrap relies
  on.

## R8. One contract, two exposures, one layout rule

The topology document has one shape everywhere (contract `topology.md`). It is rendered in
`runtime` by one function, `TopologyJson.render`, which both `ObservabilityEndpoint` (local) and the
new `ObserveServer` (cluster) call. The control plane merges instances' documents into the same
shape plus an `instances` block. The two consoles are different codebases: hand-written JavaScript
in `cli/src/main/resources/console/`, and React in `console/package`. To keep them from disagreeing
about layout, **each node carries its `layer`**, computed in `runtime`:
- 0: endpoints and timed actions
- 1: workflows, agents and autonomous agents
- 2: entities
- 3: views and consumers
- 4: topics
- external nodes: placed beside the nodes they connect to

Both consoles draw columns by layer and rows by id. No graph-layout library is added to either
console. The `cli` module adds no dependency, and the console package's layout is about a page of
code.

## R9. The local console

`cli/src/main/resources/console/{index.html, app.js, style.css}` are hand-written, polled every 3 s
(`app.js:652-657`), and proxied by `ConsoleServer` through the `Source` trait (`cli/.../console/Source.scala`).
That trait was built so that a deployed console could be added later, and it already models
instances as plural and `partial`. A Topology tab follows the existing tab pattern (`index.html:34-104`;
`app.js:12, 74, 633-639`), and `Source` gains `topology(service)`. It is drawn as inline SVG.
Selection and focus are client state. The Topology tab refreshes every 2 s while it is open; counts
are written as a call completes, so a call is on screen within SC-006's 3 s in the worst case. The
other tabs keep their 3 s tick.

The rules that decide what is drawn are pure functions in `topology.js`, tested with `node --test`
against fixtures the installation console's layout tests read too (contract `console-ui.md`).

## R10. Phase 2: the route, the CLI, the console

- **Route.** `GET /services/{projectId}/{name}/topology` on `ServiceEndpoint`, built on the logs
  route. It checks `authz.project(principal, projectId, write = false)` (404 rather than 403), uses
  `ServiceEntity.get` (404 when absent), lists the pods, and reads each pod with a 2 s per-instance
  deadline. A failure is reported per instance inside a 200. Wire types `ServiceTopology` and
  `InstanceTopology` go in `controlplane-api` beside `LogsResponse`. `ControlPlaneRoutesReferenceSuite`,
  `ControlPlaneFixturesSuite` (fixtures for the console's zod schemas) and `console/e2e/parity.ts`
  all require the new route to be documented, given fixtures and exercised.
- **CLI.** `ankka services topology <name> -p <project> [--json]` prints the nodes by layer and the
  edges with counts. `CliReferenceSuite` regenerates `docs/reference/cli.md`.
- **Console.** A page `projects/:projectId/services/:name/topology` (`routes.ts:55-74`), linked from
  the service page's actions beside Logs. It is fed by the existing SSE stream `stream.service.ts`
  with a new `?topology` flag on the existing 2 s tick, following `?logs`. `fake-control-plane.ts`
  gains the route, and Playwright gains a spec.
- **RBAC.** No change. Listing pods is already granted, and `LogsRbacSuite` stays as it is.

## R11. Testing that can fail

Each scenario in `features/` that this spec names becomes a test, and each test is checked once against the behaviour
removed:
- **Unit tests** (`runtime`): `CallCounts` window, bounds and packing; `TopologyJson` over every
  descriptor type; caller validation that rejects unknown names.
- **Service tests** (`testkit`, `AnkkaTestKit`): an endpoint → entity call, a refused command, a
  thrown handler (handled failed plus unanswered timed out), a workflow step, a consumer, an agent
  tool, a view query, work handed to another thread (unknown caller), and a million distinct entity
  ids against a flat edge count (SC-005).
- **Conformance** (`sidecar/.../ConformanceSuite.scala`): new cases `topology.declared-sources` and
  `topology.call-attributed`. They run against the in-process reference, every SDK process and the
  WebAssembly module, which is how FR-004 and the scenario
  "a call is attributed to its caller in every language" are proven without per-language
  plumbing. Each SDK's conformance sample gains nothing new, since the existing workflow and consumer
  cases already make client calls.
- **k3s** (`EndToEndClusterSuite`): two instances of the shopping cart. The control plane's route
  returns summed counts and `2 of 2`. A request presenting a workload's own certificate to `observe`
  is refused, and so is a pod in another namespace (network policy).
- **Console**: `ConsoleServerSuite` with a `FakeSource` topology; for the installation console, unit
  tests of the layout and Playwright against the fake and against compose.

## R12. Decisions from the cross-artifact analysis

- **The console's own reads are not calls.** `ObservabilityEndpoint`'s query and session routes
  call entities through the transport from a thread with no caller, so they would have shown as
  edges from the unknown caller every time a developer looked at an entity. They run under a fixed
  caller, `(console)`, which the counting function skips. A component id cannot start with `(`
  (`modules/core/.../ids.scala:18`), so no component can claim that name, nor `(unknown)`,
  `(undeclared)` or `(other)`.
- **The new spans are a requirement, not a side effect (FR-027).** Spans for workflows, agents,
  autonomous agents and in-process consumers add `component` label values to
  `ankka_invocations_total` and more spans per request to the fixed ring, so its window covers fewer
  requests; the ring's default capacity is left alone and the docs say so. An agent's span lasts for
  its model call, so that time moves from the endpoint's unattributed row to the agent's. Existing
  span names and labels do not change, and a suite asserts it.
- **A local service is linked by the name the runtime resolves it by.** `LocalClient` finds another
  local service through `ankka.local-services` or through its announcement in `~/.ankka/running`
  (`HttpServiceClients.scala:106-135`), the same directory the console lists. A name that matches
  exactly one listed service is therefore the service the call reached. A service reached through an
  explicit `ankka.local-services` address and listed under another name is not linked.
- **SC-005 is measured through the real path.** A count driven straight into `CallCounts` takes
  interned names and could not fail, so the test sends ten thousand real calls to ten thousand
  distinct entity ids (a declared query, so no journal write) and compares the tables with their
  state after the first call.
- **SC-007 is measured where it can be measured.** The control plane's route is timed against a
  scripted reader with three instances, including one that never answers. The k3s case keeps two
  real instances, because a k3s node running several sample JVMs answers in seconds.
- **SC-002 names what is tested.** The conformance service's declared edges are compared with an
  explicit list in every language, and the shopping cart sample's in its own suite.

## R13. Decisions on the living features

**The features are run by `GherkinSuite` where one suite can run a whole file.** `GherkinSuite`
(`modules/testkit/.../GherkinSuite.scala`) makes each scenario a munit test and fails a scenario
whose step has no definition, so a feature file run by it cannot hold an untested scenario. That is
SC-008 enforced by construction, and it is how the shopping cart sample already tests its own
features. It takes a directory today; it gains a path to one file, because the platform's features
are grouped by what they describe and not by the module that can test them.

| Feature file | Run by |
|---|---|
| `features/topology/declared-connections.feature` | `GherkinSuite`, in `testkit` |
| `features/topology/observed-calls.feature` | `GherkinSuite`, in `testkit` |
| `features/topology/other-services.feature` | `GherkinSuite`, in `testkit` |
| `features/observability/traces.feature` | `GherkinSuite`, in `testkit` |
| `features/topology/deployed-services.feature` | `GherkinSuite`, in `controlplane`, over a scripted reader |
| `features/topology/reading.feature` | fixtures named after each scenario, read by both consoles' tests; a suite fails when a scenario has no fixture |
| `features/topology/languages.feature` | the conformance suite: each target is one row of each outline |
| `features/documentation/topology.feature` | two cases named after the scenarios, reading `docs/` |

The scenarios were regrouped once to make this so: the two "every language" outlines have a file
of their own, and the two scenarios about going from one topology to another moved in with the
other rules a console applies.

*Alternative: hand-written cases, one per scenario.* It needs nothing new, but nothing would hold a
case to its scenario's words, and a scenario added later with no case would pass unnoticed.

**CI gates on the checker.** `.github/features-check.sh` runs `speckit-bdd check` with the paths,
the first spec and the checker version the extension's config names, so the job and
`/speckit-bdd-check` cannot check different things. It also fails when no spec or no scenario was
read, since both of those exit 0. The checks are mechanical and give the same answer on every run,
which is what a gate needs. Leaving it to the `before_clarify` hook would check only when someone
runs `/speckit-clarify`, and a feature edited by hand afterwards would never be checked.

**Every term is settled now.** The words become step definitions, JSON field names and
documentation headings during implementation, so a term changed afterwards is changed in all of
them. None was genuinely open: each was decided in the spec's clarification sessions, and the
glossary records what each does not mean.
