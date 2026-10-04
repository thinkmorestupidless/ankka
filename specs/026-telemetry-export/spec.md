# Feature Specification: Telemetry Export — Traces and Metrics Leave the Instance over OTLP

**Feature Branch**: `026-telemetry-export`

**Created**: 2026-10-03

**Status**: Draft

**Input**: User description: "ankka records every component invocation into an in-memory ring of
spans and exposes four hand-written Prometheus counters over that window; nothing is exported,
nothing is persisted, and a call from one service to another starts a new trace. Add a separate
published module, an OTLP exporter as a `RuntimeExtension`, that ships the recorder's spans and
counters, and the service's logs, to a collector the installation names through variables the
operator injects, so a service opts into nothing; propagate W3C `traceparent` on the service client
and the HTTP server so one request is one trace across services; carry the module in the sidecar so
process-hosted and wasm services export without SDK changes; and add an optional `otel-collector`
kustomization component. Out of scope: dashboards, alerting, log search, a metrics backend, sampling
policy beyond what the ring already imposes, and any change to the recorder's dependency-free
design."

## Context

Observability in ankka today is deliberately self-contained. `modules/runtime/.../runtime/Recorder.scala`
is a fixed ring of 4096 spans held in primitive arrays, with trace id, span id, parent id, interned
component and handler references, start, duration and an outcome byte from `SpanOutcome` (`Ok`,
`Failed`, `Refused`, `TimedOut`). Writes are lock-free and a reader that finds a torn slot marks the
trace partial. The comment on the class says why: "There is no metrics library and no tracing
library." That decision is what keeps a span allocation-free and recording within its measured
bound of 22 nanoseconds on a 640 microsecond invocation, and this feature keeps it.

Two exposures read that ring. Locally, `ObservabilityEndpoint.scala` serves JSON on the JDK's own
HTTP server, on loopback and an ephemeral port, for the local console. In Kubernetes,
`ObservabilityRoute.scala` serves `GET /ankka/metrics` on the Pekko management port in Prometheus
text written by hand: `ankka_invocations_total`, `ankka_invocation_duration_seconds_sum`,
`ankka_recorder_spans_recorded_total` and `ankka_recorder_capacity`, each counted over the current
window rather than since the process started. Trace context is the `ankka-trace-id` metadata key in
`runtime/Trace.scala`, followed on the handler's own thread; work handed to another thread becomes
an orphan span at the root, marked unknown, and never re-parented. There is no W3C `traceparent`
anywhere, so a call from one service to another starts a second trace. Logging is logback's
`ConsoleAppender` to stdout in a plain pattern; `ankka services logs` reads back what Kubernetes
holds for the current and previous container, with no search and no retention.
`project/Dependencies.scala` carries `logback-classic` and no OpenTelemetry, Micrometer, Prometheus
client or Cinnamon. The platform installs no collector, no Prometheus and no Grafana.

For a developer with one service on a laptop this is enough, and the local console shows traces,
sessions and entity state. For an operator running a system of fifteen services in production it is
not: a trace that stops at a service boundary cannot explain a slow withdrawal that crossed three;
a window of recent spans cannot answer what happened an hour ago; and a log that lives in the
container dies with the pod. Every installation that runs anything serious already runs a
collector and a store, and expects workloads to send to it. The limitations page says this honestly
("Traces are a window, not a history", "`ankka services logs` is not a log store") and this feature
is the answer to the first of those lines; for the second it gives the installation's own log store
the trace id to join on, and nothing else.

Six decisions shape it.

- **The recorder is untouched in cost and dependencies.** The exporter is a separate published
  module, `ankka-telemetry-otlp`, that depends on the OpenTelemetry SDK and reads the ring through
  a cursor on an interval. A service that does not load it pays nothing and depends on nothing new.
  The recorder gains a read cursor and 128-bit trace ids so a span's identity is W3C-shaped; it
  gains no library.
- **The operator decides, not the service.** `ANKKA_OTLP_ENDPOINT` and `ANKKA_OTLP_HEADERS` are
  installation settings, the address on the platform ConfigMap and the headers in a Secret beside
  it, that the operator injects into every workload that runs the platform's runtime.
  A descriptor may not set them, under the same rule as `ANKKA_HTTP_PORT` and the cluster
  variables. A service author writes no telemetry configuration and an installer points everything
  at one collector in one place.
- **One request is one trace across services.** Every way one service reaches another carries
  `traceparent`: `HttpServiceClients` sends it and `HttpServer` reads it, the gRPC client sends it
  as metadata and the gRPC server reads it, and a message published to a topic carries it as a
  header that the consumer reading the topic continues. A span recorded in the callee has the
  caller's trace id and the caller's span as its parent. Inside a service the existing rule stands: a trace follows the
  handler's thread, and what it cannot follow is shown as unattributed, never re-parented.
- **Process-hosted and wasm services need no SDK change.** The sidecar carries the module, exports
  the spans the sidecar records, and sets the variables from its own environment.
- **Logs stay on stdout, for every hosting.** Nothing exports a log. A Scala service's stdout, like
  a Python or TypeScript process's, is the installation's log agent's to collect, so an
  installation receives each line once and still receives it while the collector is down. What the
  platform adds is the join: a line written inside a handler carries the trace id and span id of
  the handler's span.
- **The platform offers a collector, and a local platform has a telemetry store.** An
  `otel-collector` kustomization component with a zero-trust policy admitting every workload
  namespace is the least an installation can send to. The local overlay lists a second component
  instead, a telemetry store for a developer's machine: Grafana's single-container stack (a
  collector, Tempo, a Prometheus-compatible store, Loki and Grafana) and an agent that gathers
  what pods printed. It keeps nothing across a restart. An installation that is not a local
  platform gets neither by default: storage, dashboards and alerts there are the installation's,
  and the platform speaks OTLP to whatever it names.

Beyond a developer's machine this feature is not a metrics backend, a log store, a log exporter
or a dashboard; it does not change the local
console; it does not add sampling beyond the ring's own overwrite; and it does not persist anything
on the instance. A span the ring overwrote before the exporter's interval reached it is lost, and
the exporter's own metric says how many.

## Clarifications

### Session 2026-10-04

- Q: Do logs go over OTLP from the JVM at all? → A: No. Logs stay on stdout only, for every
  hosting, and the feature exports traces and metrics. A line written inside a handler gains the
  trace id and span id of the handler's span on stdout, so the installation's log agent can join
  logs to traces.
- Q: Which calls between services carry the trace context? → A: All three: HTTP calls, gRPC calls
  and messages published to a topic. A request arriving from outside by HTTP or gRPC continues the
  trace it carries, and a consumer's span continues the trace of the message it handles.
- Q: What does this feature do about the outbound span 025-polyglot-service-client was to supply?
  → A: It records that span itself, for the HTTP and gRPC clients between services wherever they
  exist today, and no longer depends on 025.
- Q: Which of the platform's programs that are not ankka runtimes export? → A: None. Every
  service's platform program and the control plane export; the operator and the web-hosting proxy
  export nothing. The operator reads the telemetry settings only to render them onto workloads;
  the proxy is not given them. The proxy passes `traceparent` through
  in both directions, so a trace crosses a web-hosted service unbroken, with no span for the hop.
- Q: Are the twelve glossary terms the features propose the right words? → A: Yes, as written:
  span, parent, trace id, trace context, telemetry, export, collector, platform's collector,
  telemetry settings, metric, trace window and lost span. "Ring", "buffer", "overwritten span",
  "dropped span" and "traceparent" are refused in features; this spec keeps them where it names
  the code.
- Q: Where does exported telemetry go, and should the platform bring Grafana's open-source stack?
  → A: For a local platform, yes: the local overlay lists a telemetry store (Grafana's
  single-container stack) and an agent that gathers pods' printed lines into it, so traces,
  metrics and logs are read in one place on a developer's machine. It is never part of the cloud
  overlay. A production store belongs to the installation (for the production clusters, to
  `ankka-deployments`), and the platform stays neutral about what receives OTLP.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - One request across two services is one trace in the collector (Priority: P1)

An operator has a collector. A request arrives at a Scala service through the gateway, the endpoint
calls an entity, and a consumer in that service calls a second service, which calls an entity of
its own. In the collector the operator sees one trace: the gateway's request as the root, the
entity span under it, the outbound call under the consumer, and the second service's endpoint and
entity spans under that call, each tagged with the service and project it ran in.

**Why this priority**: This is the capability. Export without propagation gives a pile of
one-service traces, which is what a window of spans already is.

**Independent Test**: In the k3s suite, deploy a collector that writes received spans to a file,
deploy two Scala services with the call chain above and the exporter configured, make one request,
and read the file: one trace id, spans from both services, parent ids forming a tree with the
gateway request at the root.

**Acceptance Scenarios**:

- added `features/observability/trace-across-services.feature`: a request that crosses from one service to another is one trace in the collector
- added `features/observability/exported-spans.feature`: an exported span says where it ran, which handler ran and how it ended
- added `features/observability/exported-spans.feature`: a refusal is exported as refused and never as failed
- added `features/observability/exported-spans.feature`: a span with an unknown caller is exported with no parent and is never given one
- added `features/observability/trace-across-services.feature`: a request that carries no trace context starts a new trace
- added `features/observability/trace-across-services.feature`: a request that carries a trace context from outside the cluster continues that trace
- added `features/observability/trace-across-services.feature`: a message published to a topic continues the trace of what published it
- added `features/observability/trace-across-services.feature`: a message that carries no trace context starts a new trace
- added `features/observability/trace-across-services.feature`: a request that passes through a web-hosted service keeps its trace context
- added `features/observability/trace-across-services.feature`: a call the process of a web-hosted service makes keeps the trace context the process gave it

---

### User Story 2 - Metrics arrive beside the traces, and logs name their trace (Priority: P1)

The same operator reads invocation counts and durations per component and handler in their
metrics backend, since start rather than over a window, and finds a service's log lines in their
log store, gathered from stdout by the agent they already run, with the trace id on each line
written inside a handler, so a trace and its logs are joined.

**Why this priority**: Traces alone answer "what happened to this request"; metrics answer "how
is the service doing" and logs answer "what did it say". An installation expects the first two
through its collector and already gathers the third from stdout, where it needs only the trace id
to join it to the rest.

**Independent Test**: With the file-writing collector, run a known number of invocations and read
the exported metric points; assert the counter equals the number run and keeps rising past the
ring's capacity. Log a line inside a handler and assert the line on stdout carries the span's
trace id and span id, and that the collector received no log record.

**Acceptance Scenarios**:

- added `features/observability/exported-metrics.feature`: an exported metric counts every run of a handler since the instance started
- added `features/observability/exported-metrics.feature`: an exported metric names the same service and project as the spans
- added `features/observability/logs-and-traces.feature`: what a handler prints names the trace id and the span of the handler
- added `features/observability/logs-and-traces.feature`: what a service prints outside any handler names no trace id
- added `features/observability/logs-and-traces.feature`: a service's logs are not exported

---

### User Story 3 - Services opt into nothing; the installation points them at one collector (Priority: P1)

An installer sets the collector's address once on the platform ConfigMap. Every workload the
operator renders from then on that runs the platform's runtime exports to it, including process-hosted and wasm services whose
developers wrote no telemetry code. A developer who sets the variable in a descriptor is refused
at apply with the reason.

**Why this priority**: A feature a service must opt into is one most services do not have, and
an installation with gaps in its telemetry is worse than one with none, because it looks complete.

**Independent Test**: In the k3s suite, set the address on the ConfigMap, deploy an embedded, a
process-hosted and a wasm service, and assert the collector receives spans from all three with
their service names. Apply a descriptor setting `ANKKA_OTLP_ENDPOINT` and assert the refusal.

**Acceptance Scenarios**:

- added `features/observability/telemetry-settings.feature`: a deployed service exports without its descriptor asking
- added `features/observability/telemetry-settings.feature`: a service exports whatever language it is written in, with no change to its code
- added `features/observability/telemetry-settings.feature`: a descriptor may not give a telemetry setting
- added `features/observability/telemetry-settings.feature`: a service of an installation that names no collector exports nothing
- changed `features/secrets/platform-settings.feature`: a module that asks for a platform setting is told that it is not set
- added `features/observability/telemetry-settings.feature`: a web-hosted service exports nothing

---

### User Story 4 - A collector that is down costs the service nothing (Priority: P2)

The collector is unreachable for an hour. The services keep serving; the local ring still holds
recent spans for the local console and `/ankka/metrics`; the exporter logs the failure once, not
once per span, and resumes when the collector returns.

**Why this priority**: An exporter that can take a service down with it, or flood its log, is one
an operator turns off.

**Independent Test**: With the exporter configured against a closed port, run a benchmark of
invocations and assert throughput within the recorder's measured bound; count log lines from the
exporter; open the port and assert spans arrive.

**Acceptance Scenarios**:

- added `features/observability/collector-unreachable.feature`: a service whose collector cannot be reached handles its requests as before
- added `features/observability/collector-unreachable.feature`: a collector that cannot be reached is reported once and not for every span
- added `features/observability/collector-unreachable.feature`: a service exports again when its collector can be reached again
- added `features/observability/collector-unreachable.feature`: an instance that stops exports what it holds and is not kept from stopping

---

### User Story 5 - A local installation has somewhere to send, and somewhere to look (Priority: P3)

A developer running the local platform on kind deploys a service, makes a request, and opens the
local platform's telemetry store in a browser: the trace of the request, the service's metrics,
and the lines its instances printed, each line a click from the trace it belongs to. They
installed nothing else. An installer who wants the least there is adds the `otel-collector`
component instead, which prints what it receives and keeps nothing.

**Why this priority**: The export is complete without it, since any installation that cares has
a collector and a store of its own. But without somewhere to look, a trace across two services
can be shown only by a test, and the k3s suites and the local platform need a collector to prove
the rest.

**Independent Test**: `kubectl apply -k` of the local overlay brings up the telemetry store; the
smoke test asserts a span from the control plane itself is in it. Against the store's own image
in a container, a service's exported trace is read back by its id, its counter by its name, and
a line gathered from a pod's log file by the trace id on it. The operator exports nothing: it is
not an ankka application.

**Acceptance Scenarios**:

- added `features/observability/platform-collector.feature`: a service of any project reaches the platform's collector
- added `features/observability/platform-collector.feature`: a workload that is not of the installation cannot reach the platform's collector
- added `features/observability/platform-collector.feature`: an installation that is not a local platform names a collector of its own
- added `features/observability/platform-collector.feature`: the control plane exports as a service does
- added `features/observability/telemetry-store.feature`: a developer reads the trace of a request in the telemetry store
- added `features/observability/telemetry-store.feature`: a developer reads the metrics of a service in the telemetry store
- added `features/observability/telemetry-store.feature`: a developer reads the logs of a service in the telemetry store, joined to their trace

---

### Edge Cases

- **A trace whose older spans the ring overwrote before export.** The exported trace is partial;
  the exporter does not wait for a trace to complete, since the ring never promised one would.
  The exporter's overwritten counter is the only signal, and the documentation says so.
- **Two instances of one service.** Each exports its own spans with the same service name and a
  distinct instance attribute; a trace that crossed instances through sharding joins in the
  collector by trace id, which is new, since the ring could never show it.
- **A `traceparent` with an invalid format.** Ignored, on a request, a call or a message alike; a
  new trace starts, and the header is not echoed.
- **A message handled long after it was published.** The consumer's span joins the publisher's
  trace whenever it runs, a second or a week later; the collector may long since have closed that
  trace, and the span is exported all the same. A message delivered again is a second span under
  the same parent.
- **A message published by something that is not an ankka service.** It carries no `traceparent`
  unless its publisher wrote one; with none, the consumer's span is the root of a new trace.
- **Several messages published from one change.** Each carries the same context, the span of the
  handler that published them.
- **A `traceparent` on a call between services with an attacker-chosen trace id.** The id is
  taken as given, as every W3C-propagating system does; the caller's identity is the certificate's,
  never the header's, and nothing authorizes on a trace id.
- **A log line written on a thread the trace did not follow.** It carries no trace id, as a line
  written outside any handler does; an id is never guessed.
- **A tool that reads `ankka services logs`.** A line written inside a handler is longer by the
  two ids; a line written outside one is as it was.
- **An installation with an authenticated collector.** `ANKKA_OTLP_HEADERS` carries the header
  the collector wants; it is the installation's secret and sits on the ConfigMap's companion
  Secret, never in a descriptor.
- **The sidecar's own startup before discovery.** Spans recorded before the process answers
  discovery are exported with the service name the sidecar already knows from its environment.
- **A trace that crosses a web-hosted service.** The proxy is not a hop in it: the mounted
  service's endpoint span names as its parent whatever span the request carried, and a process
  that wants its own span in the trace exports it itself, to a collector it is told of by its own
  descriptor's variables.
- **The telemetry store restarts.** Everything it held is gone; services go on exporting and the
  store fills again. It is not a history.
- **A pod that is not an ankka service.** Its printed lines are gathered too, under its own
  namespace and container; they name no trace.
- **The local console.** Unchanged; it reads the ring as before, whether or not an exporter runs.

## Requirements *(mandatory)*

### Functional Requirements

**Exporter**

- **FR-001**: A new published module MUST provide a `RuntimeExtension` that exports the recorder's
  spans and the invocation counters over OTLP to a configured endpoint. It MUST NOT export logs.
- **FR-002**: The recorder MUST gain a read cursor for the exporter and 128-bit trace ids, and
  MUST gain no new library dependency. Planning added what export also needs of it: span ids
  unique across instances, a wall-clock anchor, a span kind, a mark for a span whose caller is
  unknown, and a count of invocations since the instance started (research R4 to R8).
- **FR-003**: Exported spans MUST carry the service name and project as resource attributes, the
  component and handler as attributes, and the span outcome as the OTLP status, with `Refused`
  distinguished from `Failed`.
- **FR-004**: Exported counters MUST count since process start, not over the ring's window.
- **FR-005**: A log line written inside a handler MUST carry the trace id and span id of the
  handler's span in the logging context of the platform's program, in every hosting, and a line
  written outside any handler MUST carry neither. The ids MUST be printed on stdout wherever the
  platform owns the logging configuration (the sidecar, the control plane, the template and the
  sample); an embedded service's own configuration prints them when its pattern names them
  (research R14).
- **FR-006**: An unreachable collector MUST NOT affect the service's throughput beyond the
  recorder benchmark's stated bound, and MUST be logged with backoff rather than per batch.
- **FR-007**: The exporter MUST report, as its own metric, how many spans were overwritten before
  export.

**Propagation**

- **FR-008**: `HttpServiceClients` MUST send a W3C `traceparent` header carrying the current span,
  and `HttpServer` MUST read one and parent the endpoint span under it.
- **FR-015**: The gRPC client between services MUST send `traceparent` as metadata carrying the
  current span, and the gRPC server MUST read one and parent the method's span under it, for every
  kind of method.
- **FR-016**: A message published to a topic MUST carry `traceparent` for the span of the handler
  that published it, in process, behind a sidecar and in a module alike, and a consumer or view
  reading a topic MUST parent the span of handling a message under the context the message carries.
  A message with none, or with one that cannot be read, starts a new trace.
- **FR-017**: A call a handler makes to another service through the HTTP or the gRPC client MUST
  be recorded as a span under the handler's span, with the called service as its name and the
  outcome the caller saw (ok, refused, failed or timed out), and that span MUST be the one the
  `traceparent` of FR-008 and FR-015 carries. The called service's name is declared, never an
  address or a path, so the recorder's name table stays bounded.
- **FR-018**: The web-hosting proxy MUST pass a `traceparent` header through unchanged, on a
  request to the process, on a request under a mount and on a call made at the calling address,
  and MUST record and export nothing. The operator MUST export nothing and gains no dependency.
- **FR-009**: Trace context MUST NOT be re-parented where the runtime cannot follow it; the
  existing orphan rule stands.

**Platform**

- **FR-010**: The operator MUST inject `ANKKA_OTLP_ENDPOINT`, from the platform ConfigMap, and
  `ANKKA_OTLP_HEADERS`, from a Secret the installer makes, into every workload that runs the
  platform's runtime, and into neither container of a web-hosted service. For process hosting
  they go to the sidecar and not the process, and a wasm module's `config` import withholds them.
  The headers MUST reach a workload as a reference to a Secret and never as a literal.
- **FR-011**: `ServiceSpec.problems` MUST refuse a descriptor that sets either variable.
- **FR-012**: The sidecar MUST carry the exporter and configure it from its environment.
- **FR-013**: A kustomization component MUST provide a collector with a zero-trust policy
  admitting every workload namespace, which keeps nothing. The cloud overlay MUST list no
  collector and name none until an installer sets one.
- **FR-019**: A second kustomization component MUST provide a telemetry store for a local
  platform: one that receives OTLP from every workload under the same policy, keeps traces,
  metrics and logs while it runs, and shows them in a browser at a hostname of the local
  platform. The local overlay MUST list it and point the telemetry settings at it. The cloud
  overlay MUST NOT render it, nor any sign-in it has.
- **FR-020**: The telemetry store's component MUST gather what every pod of the local platform
  printed and send it to the store, naming each line's service and project, and MUST carry the
  trace id and span id a line names so that the store joins the line to its trace. No service
  exports a log for this (FR-001).

**Documentation**

- **FR-014**: The observability concept page and the logs page MUST describe export, propagation,
  the overwritten-span limit and the ids a log line carries, and say that logs are gathered from
  stdout and never exported, and the limitations page MUST be updated to say what remains a
  window.

### Key Entities

- **Span**: as recorded today, plus a 128-bit trace id; exported with resource and span attributes.
- **Export cursor**: the recorder's position of the last exported slot, per exporter.
- **Platform telemetry settings**: the two installation values the operator injects.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: One request crossing two services is one trace in the collector, with spans from
  both under one id and a correct parent chain. This is shown on a real cluster for an HTTP call,
  and between two running services, without a cluster, for an HTTP call, a gRPC call and a
  message published to a topic.
- **SC-002**: Embedded, process-hosted and wasm services export without any change to the
  service's code. An embedded service's build names the exporter's module; a process-hosted
  or wasm service changes nothing (research R1).
- **SC-003**: Recording cost with the exporter attached stays within the bound the recorder's
  benchmark suite states. Like that bound, it is measured by benchmark suites run on demand
  (`-Dankka.benchmarks=on`) and not on every build; every build asserts only that no request is
  lost and every span is recorded while the collector cannot be reached.
- **SC-004**: An unreachable collector produces at most one log line per backoff interval and no
  dropped requests.
- **SC-005**: A descriptor cannot set the telemetry variables.
- **SC-006**: On a local platform, with nothing installed beyond it, a developer reads the trace
  of a request, the metrics of the service that handled it and the lines it printed in one
  place. Each line a handler printed carries the id of its trace, and the store holds the trace
  under that id; that the store's own page links the two is checked by hand, on the local
  platform.

## Assumptions

- The OpenTelemetry Java SDK's OTLP gRPC or HTTP exporter is the transport; the choice is the
  plan's, not the spec's.
- The collector is the installation's responsibility beyond the optional component. The only
  storage the platform ships is the local platform's telemetry store, which is for development:
  it holds what it has in one container and loses it on restart.
- `ANKKA_OTLP_HEADERS` is the only credential and is small enough to live in a Secret beside the
  ConfigMap.
- The local console continues to read the ring and is not changed.

## Dependencies

- Gates domain stage 1 going to production: the money path cannot be operated without traces that
  cross services.
- 025-polyglot-service-client no longer gates this feature: the outbound span is FR-017 here.
  When 025 gives further contexts a service client, those clients record the same span through
  the same code.
- 027-managed-broker and 023-secret-store reuse the shared sidecar prefix declaration that
  `ANKKA_OTLP_` joins.
- 024-replayable-topics changes how a topic is read; a message replayed under it carries the
  `traceparent` it was published with, so whichever of the two is built second must keep FR-016
  holding.
- No dependency on any other spec in this set.

## Open Questions

- ~~Whether logs should go through OTLP from the JVM at all.~~ Settled: they do not (see
  Clarifications).
- ~~Whether to sample at export when the ring overturns faster than the interval, or to shorten the
  interval under load.~~ Settled by the scope: no sampling beyond the ring's own overwrite. How
  often the exporter drains under load is the plan's.
- ~~Whether trace propagation across a Kafka topic belongs here or in 024-replayable-topics.~~
  Settled: here (see Clarifications).
