# Feature Specification: Telemetry Export — Traces, Metrics and Logs Leave the Instance over OTLP

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
is the answer to those two lines.

Five decisions shape it.

- **The recorder is untouched in cost and dependencies.** The exporter is a separate published
  module, `ankka-telemetry-otlp`, that depends on the OpenTelemetry SDK and reads the ring through
  a cursor on an interval. A service that does not load it pays nothing and depends on nothing new.
  The recorder gains a read cursor and 128-bit trace ids so a span's identity is W3C-shaped; it
  gains no library.
- **The operator decides, not the service.** `ANKKA_OTLP_ENDPOINT` and `ANKKA_OTLP_HEADERS` are
  installation settings on the platform ConfigMap that the operator injects into every workload.
  A descriptor may not set them, under the same rule as `ANKKA_HTTP_PORT` and the cluster
  variables. A service author writes no telemetry configuration and an installer points everything
  at one collector in one place.
- **One request is one trace across services.** `HttpServiceClients` sends `traceparent` and
  `HttpServer` reads it, so a span recorded in the callee has the caller's trace id and the
  caller's span as its parent. Inside a service the existing rule stands: a trace follows the
  handler's thread, and what it cannot follow is shown as unattributed, never re-parented.
- **Process-hosted and wasm services need no SDK change.** The sidecar carries the module, exports
  the spans the sidecar records, and sets the variables from its own environment. A Python or
  TypeScript process's stdout is still the installation's log agent's to collect, as it is today.
- **The platform installs a collector, optionally, and nothing more.** An `otel-collector`
  kustomization component with a zero-trust policy admitting every workload namespace gives a
  local installation somewhere to send. Storage, dashboards and alerts are the installation's.

This feature is not a metrics backend, a log store or a dashboard; it does not change the local
console; it does not add sampling beyond the ring's own overwrite; and it does not persist anything
on the instance. A span the ring overwrote before the exporter's interval reached it is lost, and
the exporter's own metric says how many.

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

1. **Given** two services with the exporter configured and a request that crosses from one to the
   other, **When** the request completes, **Then** the collector holds spans from both services
   under one trace id, and the callee's endpoint span names the caller's outbound span as its
   parent.
2. **Given** the same request, **When** the collector's spans are read, **Then** each span carries
   the service name and project as resource attributes, the component and handler as span
   attributes, and the outcome as the span's status.
3. **Given** a command that an entity refused through `effects.error`, **When** its span is
   exported, **Then** the status is the refused outcome, not an error.
4. **Given** a handler that handed work to another thread, **When** its spans are exported,
   **Then** the orphan span is exported at the root with the unknown-parent marker, and is not
   re-parented.
5. **Given** a request with no `traceparent` header, **When** it arrives at the gateway-facing
   endpoint, **Then** the endpoint span is the root of a new trace with a 128-bit id.
6. **Given** a request carrying a `traceparent` header from outside the cluster, **When** it
   arrives, **Then** the endpoint span continues that trace, so an installation's own edge proxy
   can be the root.

---

### User Story 2 - Metrics and logs arrive beside the traces (Priority: P1)

The same operator reads invocation counts and durations per component and handler in their
metrics backend, since start rather than over a window, and finds a service's log lines in their
log store with the trace id attached, so a trace and its logs are joined.

**Why this priority**: Traces alone answer "what happened to this request"; metrics answer "how
is the service doing" and logs answer "what did it say", and an installation that runs a collector
expects all three through it.

**Independent Test**: With the file-writing collector, run a known number of invocations and read
the exported metric points; assert the counter equals the number run and keeps rising past the
ring's capacity. Log a line inside a handler and assert the exported log record carries the span's
trace id.

**Acceptance Scenarios**:

1. **Given** the exporter configured, **When** more invocations than the ring holds have run,
   **Then** the exported invocation counter reports every one of them, not the window's count.
2. **Given** the exporter, **When** metric points are read, **Then** they carry the same service
   and project resource attributes as the spans and are keyed by component and handler.
3. **Given** a log line written inside a handler with the exporter configured, **When** it is
   exported, **Then** the log record carries the trace id and span id of the handler's span.
4. **Given** a log line written outside any handler, **When** it is exported, **Then** it carries
   no trace id and is still exported.
5. **Given** the exporter, **When** the service's own stdout is read, **Then** the log lines are
   still there in the existing pattern, so `ankka services logs` and a stdout-collecting agent
   are unchanged.

---

### User Story 3 - Services opt into nothing; the installation points them at one collector (Priority: P1)

An installer sets the collector's address once on the platform ConfigMap. Every workload the
operator renders from then on exports to it, including process-hosted and wasm services whose
developers wrote no telemetry code. A developer who sets the variable in a descriptor is refused
at apply with the reason.

**Why this priority**: A feature a service must opt into is one most services do not have, and
an installation with gaps in its telemetry is worse than one with none, because it looks complete.

**Independent Test**: In the k3s suite, set the address on the ConfigMap, deploy an embedded, a
process-hosted and a wasm service, and assert the collector receives spans from all three with
their service names. Apply a descriptor setting `ANKKA_OTLP_ENDPOINT` and assert the refusal.

**Acceptance Scenarios**:

1. **Given** a collector address on the platform ConfigMap, **When** a service is deployed,
   **Then** its workload carries `ANKKA_OTLP_ENDPOINT` and `ANKKA_OTLP_HEADERS`, routed to the
   sidecar container for process hosting, and the collector receives its spans.
2. **Given** a process-hosted Python service, **When** it handles a request, **Then** the sidecar
   exports the request's spans with the service's name, with no change to the Python code.
3. **Given** a wasm-hosted Rust service, **When** it handles a request, **Then** the same holds.
4. **Given** a descriptor that sets `ANKKA_OTLP_ENDPOINT` or `ANKKA_OTLP_HEADERS` in its `env`,
   **When** it is applied, **Then** the apply is refused with an error naming the variable, as a
   descriptor setting `ANKKA_HTTP_PORT` is.
5. **Given** no address on the platform ConfigMap, **When** a service is deployed, **Then** no
   exporter thread starts and no connection is attempted, and the service runs as before this
   feature.
6. **Given** a wasm module that asks its `config` import for `ANKKA_OTLP_ENDPOINT`, **When** it
   does, **Then** the answer is absent, since the prefix is the platform's.

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

1. **Given** the exporter pointed at an address nothing listens on, **When** a thousand invocations
   run, **Then** every one is recorded in the ring and the service's throughput is within the bound
   the recorder's benchmark suite states.
2. **Given** the same, **When** the service's log is read, **Then** the exporter's failure appears
   once per outage, with a backoff, not once per batch.
3. **Given** the collector comes back, **When** the next interval passes, **Then** export resumes
   with the spans still in the ring, and the exporter's own metric reports how many were
   overwritten before they could be sent.
4. **Given** a service shutting down, **When** coordinated shutdown runs, **Then** the exporter
   flushes what it has within a bounded time and does not hold the shutdown past it.

---

### User Story 5 - A local installation has somewhere to send (Priority: P3)

A developer running the local platform on kind adds the `otel-collector` component and sees their
services' traces in a collector's debug output without installing anything else.

**Why this priority**: The feature is complete without it, since any installation that cares has
a collector, but the k3s suite and the local platform need one to prove the rest.

**Independent Test**: `kubectl apply -k` of the local overlay with the component enabled brings up
a collector; the smoke test asserts a span from the control plane itself arrives.

**Acceptance Scenarios**:

1. **Given** the local overlay with the component enabled, **When** it is applied, **Then** a
   collector runs in its own namespace with a zero-trust policy that admits every workload
   namespace and nothing else.
2. **Given** the component, **When** `RemoteOverlaySuite` renders both overlays, **Then** the
   cloud overlay's placeholder for the collector address is distinct from the local one and both
   render.
3. **Given** the control plane and the operator, **When** the address is set, **Then** they export
   too, since they are ankka services.

---

### Edge Cases

- **A trace whose older spans the ring overwrote before export.** The exported trace is partial;
  the exporter does not wait for a trace to complete, since the ring never promised one would.
  The exporter's overwritten counter is the only signal, and the documentation says so.
- **Two instances of one service.** Each exports its own spans with the same service name and a
  distinct instance attribute; a trace that crossed instances through sharding joins in the
  collector by trace id, which is new, since the ring could never show it.
- **A `traceparent` with an invalid format.** Ignored; a new trace starts, and the header is not
  echoed.
- **A `traceparent` on a call between services with an attacker-chosen trace id.** The id is
  taken as given, as every W3C-propagating system does; the caller's identity is the certificate's,
  never the header's, and nothing authorizes on a trace id.
- **A log line larger than the exporter's batch limit.** Truncated with a marker, never dropped
  silently.
- **An installation with an authenticated collector.** `ANKKA_OTLP_HEADERS` carries the header
  the collector wants; it is the installation's secret and sits on the ConfigMap's companion
  Secret, never in a descriptor.
- **The sidecar's own startup before discovery.** Spans recorded before the process answers
  discovery are exported with the service name the sidecar already knows from its environment.
- **The local console.** Unchanged; it reads the ring as before, whether or not an exporter runs.

## Requirements *(mandatory)*

### Functional Requirements

**Exporter**

- **FR-001**: A new published module MUST provide a `RuntimeExtension` that exports the recorder's
  spans, the invocation counters and the service's logs over OTLP to a configured endpoint.
- **FR-002**: The recorder MUST gain a read cursor for the exporter and 128-bit trace ids, and
  MUST gain no new library dependency.
- **FR-003**: Exported spans MUST carry the service name and project as resource attributes, the
  component and handler as attributes, and the span outcome as the OTLP status, with `Refused`
  distinguished from `Failed`.
- **FR-004**: Exported counters MUST count since process start, not over the ring's window.
- **FR-005**: Exported log records MUST carry the trace and span ids of the handler's span when
  written inside a handler.
- **FR-006**: An unreachable collector MUST NOT affect the service's throughput beyond the
  recorder benchmark's stated bound, and MUST be logged with backoff rather than per batch.
- **FR-007**: The exporter MUST report, as its own metric, how many spans were overwritten before
  export.

**Propagation**

- **FR-008**: `HttpServiceClients` MUST send a W3C `traceparent` header carrying the current span,
  and `HttpServer` MUST read one and parent the endpoint span under it.
- **FR-009**: Trace context MUST NOT be re-parented where the runtime cannot follow it; the
  existing orphan rule stands.

**Platform**

- **FR-010**: The operator MUST inject `ANKKA_OTLP_ENDPOINT` and `ANKKA_OTLP_HEADERS` from the
  platform ConfigMap into every workload, routed to the sidecar for process hosting and withheld
  from the wasm `config` import.
- **FR-011**: `ServiceSpec.problems` MUST refuse a descriptor that sets either variable.
- **FR-012**: The sidecar MUST carry the exporter and configure it from its environment.
- **FR-013**: A kustomization component MUST provide a collector with a zero-trust policy
  admitting every workload namespace, enabled in the local overlay and placeholdered in the cloud
  overlay.

**Documentation**

- **FR-014**: The observability concept page and the logs page MUST describe export, propagation
  and the overwritten-span limit, and the limitations page MUST be updated to say what remains a
  window.

### Key Entities

- **Span**: as recorded today, plus a 128-bit trace id; exported with resource and span attributes.
- **Export cursor**: the recorder's position of the last exported slot, per exporter.
- **Platform telemetry settings**: the two installation values the operator injects.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: One request crossing two services on a real cluster is one trace in the collector,
  with spans from both under one id and a correct parent chain.
- **SC-002**: Embedded, process-hosted and wasm services export without any change to the
  service's code.
- **SC-003**: Recording cost with the exporter attached stays within the bound the recorder's
  benchmark suite states.
- **SC-004**: An unreachable collector produces at most one log line per backoff interval and no
  dropped requests.
- **SC-005**: A descriptor cannot set the telemetry variables.

## Assumptions

- The OpenTelemetry Java SDK's OTLP gRPC or HTTP exporter is the transport; the choice is the
  plan's, not the spec's.
- The collector is the installation's responsibility beyond the optional component; the platform
  ships no storage.
- `ANKKA_OTLP_HEADERS` is the only credential and is small enough to live in a Secret beside the
  ConfigMap.
- The local console continues to read the ring and is not changed.

## Dependencies

- Gates domain stage 1 going to production: the money path cannot be operated without traces that
  cross services.
- 025-polyglot-service-client: `traceparent` propagation rides on the outbound span that spec adds
  to every context's service client.
- 027-managed-broker and 023-secret-store reuse the shared sidecar prefix declaration that
  `ANKKA_OTLP_` joins.
- No dependency on any other spec in this set.

## Open Questions

- Whether logs should go through OTLP from the JVM at all, or stay on stdout for the
  installation's agent to collect, which most installations already run and which costs the
  service nothing. [NEEDS CLARIFICATION: the domain plan assumes Loki through an agent; exporting
  logs twice is wasteful and exporting them once from the JVM loses them when the exporter is down.]
- Whether to sample at export when the ring overturns faster than the interval, or to shorten the
  interval under load.
- Whether trace propagation across a Kafka topic (a `traceparent` header on a published message)
  belongs here or in 024-replayable-topics.
