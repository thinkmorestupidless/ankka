# Implementation Plan: Telemetry Export — Traces and Metrics Leave the Instance over OTLP

**Branch**: `026-telemetry-export` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/026-telemetry-export/spec.md`

## Summary

An installation names a collector once, and every service in it exports its spans and metrics
there with no change to its code. A local platform comes with somewhere to look: Grafana's
single-container stack, with traces, metrics and logs in one place. One request is one trace across services, whether it crosses
by HTTP, by gRPC or by a message on a topic. Logs are not exported: they stay on stdout, and a
line written inside a handler says which trace and span it belongs to.

Technically: a new published module, `ankka-telemetry-otlp`, holds the only OpenTelemetry
dependency and is found by the runtime through a declared provider, so a service names it in its
build and nowhere else (R1). It hands recorded spans straight to the SDK's OTLP exporter and
exports metrics from a tally the runtime keeps (R2, R7). The recorder gains what export needs
and no library: a 128-bit trace id, span ids that are unique across instances, a clock anchor,
a read cursor, a span kind and a mark for an unknown caller (R3–R8). `traceparent` is read by the
HTTP server, the gRPC server and the four topic handlers, and written by the two service clients
and by one stamping function every published message passes through (R9–R12). The operator
renders the collector's address on the platform's container of every hosting but web, and its
credential from a Secret it writes per service (R18). The platform's own collector is a
kustomize component that keeps nothing (R19); the local overlay lists a second one instead, a
telemetry store a developer opens in a browser, with an agent that gathers what pods printed
(R23).

Planning found things the spec did not have:

- **An embedded Scala service has to have the module on its classpath.** Nothing loads an
  extension a service did not hand over, so the runtime gains a provider it finds by
  `ServiceLoader`, and the template's build names the module. A service built before this
  feature exports once its build gains one line; its code does not change (R1).
- **Span ids are a counter that starts at 1 in every instance**, so two services in one trace
  would both have a span 5. The counter now starts at a random number. This also repairs a
  parent id that already crosses nodes of one service (R4).
- **A count since the instance started cannot be read from the ring.** What is lost is lost when
  a service is busiest. The recorder keeps a tally where a span ends, beside the counting the
  topology already does there (R7).
- **A published message has no trace to read.** The handler's span is closed and its thread
  context restored before the effect is applied, and a single message bypasses the function that
  publishes several. The span's context is handed to one stamping function both paths call (R12).
- **The credential cannot be a literal on a Deployment**, and a pod can only reference a Secret
  in its own namespace. The operator writes one per service, owned by the service (R18).
- **Setting the collector's address rolls every service once**, since it changes every pod
  template (R18).
- **A trace that began in another service reads as partial in the callee's console**, because
  its parent is on another instance. That is what partial was defined to mean; no console code
  changes (R12).

Found on the way, and **not changed** by this plan; each is a decision for its own change:

- An HTTP handler that answers 4xx is recorded as failed, not refused, and an HTTP ACL refusal,
  a 404 and a 405 record no span, where gRPC records one. Exported, the first is an error status
  on something the service did on purpose (R10).
- The gRPC client makes no topology count, and a gRPC handler's thread has a trace and no
  origin, so a call made from one is counted from the unknown caller (R11).
- `/ankka/metrics` goes on counting the window, though the tally would now make its counters
  true (R7).

Found and **changed**, beyond the spec's wording:

- The control plane's image has no logging configuration and logs at `DEBUG` in logback's default
  pattern. It gains one, at `INFO`, with the ids (R14).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`core`, `runtime`, `http`, `grpc`, `testkit`, the new
`telemetry-otlp`, `sidecar`, `controlplane-api`, `controlplane`, `operator`, `proxy-core` tests
only); YAML (kustomize); bash (`deploy-local.sh`). No Python, TypeScript or Rust source changes:
every SDK carries a call's metadata through untouched, and no protocol message changes.

**Primary Dependencies**: `io.opentelemetry:opentelemetry-exporter-otlp` 1.66.0 with its OkHttp
sender excluded and `opentelemetry-exporter-sender-jdk` in its place, in the new module only;
no others: the fake collector reads OTLP's protobuf by field number, since `opentelemetry-proto`
needs protobuf-java 4 beside the ScalaPB 3 code on the module's test classpath. `runtime` gains none: the
log ids use logback, which it already has.

**Storage**: none. No table, no event, no stored format and no field on the `AnkkaService`
resource. One Kubernetes Secret per service where the installation has a credential, one
ConfigMap key and one optional Secret for the installation.

**Testing**: munit in `core`, `runtime`, `http`, `grpc`, `controlplane-api`, `operator`,
`proxy-core`; the new module's suites against a fake collector on the JDK's HTTP server;
`testkit` with Postgres and `InMemoryBroker`, and `KafkaSuite` with a real broker; the operator's
rendering suites and its pinned and golden ones; `RemoteOverlaySuite`; the telemetry store's image and its agent run as containers by the module's
`TelemetryStoreSuite`; three k3s suites extended
(`ZeroTrustClusterSuite`, `ControlPlaneClusterSuite`, `SidecarClusterSuite`); one benchmark suite
extended and one added, under `-Dankka.benchmarks=on`; `TemplateSuite`; the docs build.

**Target Platform**: wherever an ankka service runs. Export is wherever the configuration names
a collector: in a cluster through the operator, on a developer's machine by a variable.

**Project Type**: a platform library and a new one beside it, an operator, kustomize manifests,
documentation

**Performance Goals**: recording a span stays under the bound `RecorderBenchmark` asserts
(200 ns) and under 1% of a real invocation with an exporter attached (`ExporterCostSuite`, the
module's twin of `ServiceRecordingCostSuite`).
The hot path gains one array store for the high half of the id, one for the kind, and the tally:
a hash, a probe and two atomic adds. Nothing is formatted, allocated or sent on it. A log line
pays for its ids when it is written.

**Constraints**: `runtime` gains no dependency; a service without the module loads no exporter
class; with no address configured no thread starts and no connection is tried; an unreachable
collector costs a service no request and one log line an outage; no header's value in a log, an
action's description or a Deployment; the operator's and the control plane's grants unchanged;
the operator's `dependsOn` unchanged; with no address set the operator renders exactly what it
renders today; the local console and the observe port answer what they answer today; a parent is
never guessed; nothing interned is unbounded; `Test / parallelExecution := false` stays;
warning-free; no suite binds a fixed port; no test names an image by a literal tag

**Scale/Scope**: 1 new module of 8 source files and 12 suites; about 22 Scala source files
changed across `runtime`, `http`, `grpc`, `sidecar`, `core`, `controlplane-api` and `operator`,
with about 7 new suites and 14 changed; 2 new kustomize components of 6 and 8 files and about 8
manifest files changed; 4 logging configurations (1 new); `build.sbt`, `project/Dependencies.scala`, the
template's build; 1 new docs page and about 8 changed; `CLAUDE.md`; the skills rendered again

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | no effect is added. `EnsureTelemetrySecret` is an inert description with no value in it (R18) |
| Where two interpreters reduce the same thing, they share one function | pass | one `Traceparent` parser and writer for three transports (R9); one `calling` for both service clients (R10, R11); one `stamped` for a single message and for several, in process and remote (R12); one `inbound` for the six hosts (R8) |
| Module dependency direction | pass | `core → sdk → runtime → telemetry-otlp`; `sidecar`, `controlplane` and the sample depend on the module; `runtime`, `http` and `grpc` never name it |
| `runtime` must not depend on what plugs into it; the `RuntimeExtension` seam | pass | the exporter is an extension; `runtime` knows a provider trait and nothing of OpenTelemetry (R1) |
| No classpath scanning; explicit registration | **justified** | components stay explicit. The exporter is the first extension a service does not hand over; it is found through a provider file its jar declares, not by scanning. See *Complexity Tracking* |
| The recorder: no dependency, nothing built on the hot path, bounded absolutely | pass | arrays and atomics only; the tally and the admitted names are bounded with an overflow entry; the cursor's pending set is bounded by the ring (R6, R7) |
| Never intern anything unbounded | pass | a call's span is named by an admitted service and an HTTP method or an admitted gRPC method, never a path or an address (R10, R11) |
| A trace set on the caller's thread is invisible to the thread doing the work; never re-parent an orphan | pass | the context is handed over as a value where the thread cannot carry it (R12); an unknown caller is exported as exactly that (R8) |
| A refusal is not a failure | pass | exported with the status unset and its own outcome (R16) |
| Wire names are a versioning boundary | pass | no handler, route or protocol name changes; the in-service trace id is read in both lengths (R3) |
| Stored forms stay readable both ways; the schema is additive | pass | nothing stored changes |
| A field on the resource needs the schema | pass | no field is added; the operator reads its own settings (R18) |
| The operator cannot reach into the control plane | pass | `dependsOn(crd)` is unchanged; the two names are in the shared `PlatformVariables` file |
| No secret value in a journal, an action or a log | pass | the action holds none; a log names the address and never a header (R17, R18) |
| An overlay that only works from the deploy script is not an overlay | pass | the telemetry store, its address and the two Deployments' variables are in the overlay; the script only asks the store a question at the end (R19, R23) |
| A strategic merge patch must name the container the target has; assert the shape, not a string | pass | values arrive by `replacements`, and `RemoteOverlaySuite` asserts each variable is set exactly once on the named container (R19) |
| Never touch `ActorContext` from a `Future`; anything read from the environment is overridable | pass | the exporter is a thread of its own and uses no actor; every setting is configuration, set by a test (data-model) |
| On SIGTERM, do not make the first shutdown phase wait | pass | the exporter stops with the other extensions and takes at most 3 s of the last phase's 20 (R17) |
| Tests are serialised; no fixed port; no literal image tag | pass | the fake collector binds port 0; the k3s cases join suites that exist |
| An `eventually` waits for the thing it asserts | pass | a k3s case waits for the span it names in the collector's log, and times out naming it |
| Could this check pass while the thing it checks is false? | pass | the fake collector records every path asked for, so "logs are not exported" is an assertion and not an absence; the outage case counts log lines; "exports nothing" asserts no thread and no connection, not no error; the limits are stated (R21) |
| Each acceptance scenario ends as a test that fails without the feature | pass | 32 scenario references in the spec, each mapped to a level in R21 |
| Docs: pages stand alone, samples from tested code, new pages in nav and a skill | pass | R22 |
| Every tracked file claimed by a CI path filter | pass | `modules/**` and `kustomization/**` are already claimed (R22) |

**Violations to justify**: one, under *Complexity Tracking*.

**Post-design re-check**: unchanged. The contracts add one dependency, in the new module; no
field on the resource; no grant.

## Project Structure

### Documentation (this feature)

```text
specs/026-telemetry-export/
├── plan.md              # this file
├── research.md          # R1–R23: decisions with file-level evidence; thirteen things to verify first
├── data-model.md        # the span, the context, the cursor, the tally, the settings, the Secret
├── quickstart.md        # the validation runs: pure → one service → export → offline → cost → k3s → docs → by hand
├── contracts/
│   ├── scala-api.md         # the provider, trace identity, the recorder, calls, logs, the module
│   ├── export.md            # what a collector receives, and how
│   └── operator.md          # what is rendered, the Secret, the variables, the installation, the component
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/observability/` (eight files beside `traces.feature`)
and one changed outline in `features/secrets/platform-settings.feature`, in the words of
`GLOSSARY.md`.

### Source Code (repository root)

```text
modules/telemetry-otlp/                                        # new module: ankka-telemetry-otlp
  src/main/resources/reference.conf                            #   ankka.telemetry.*
  src/main/resources/META-INF/services/…RuntimeExtensionProvider
  src/main/scala/…/telemetry/OtlpProvider.scala                #   the provider: none when no address
  src/main/scala/…/telemetry/OtlpTelemetry.scala               #   the extension: start, stop
  src/main/scala/…/telemetry/TelemetrySettings.scala           #   address, headers, intervals
  src/main/scala/…/telemetry/ExportLoop.scala                  #   read, send, commit, back off
  src/main/scala/…/telemetry/Outage.scala                      #   one line an outage
  src/main/scala/…/telemetry/RecordedSpanData.scala            #   a recorded span as the SDK's SpanData
  src/main/scala/…/telemetry/Metrics.scala                     #   three instruments over the totals
  src/main/scala/…/telemetry/Identity.scala                    #   the resource
  src/test/scala/…/telemetry/FakeCollector.scala, twelve suites

modules/runtime/…/runtime/Recorder.scala                       # high half, kind, anchor, random start, sentinel, cursor
modules/runtime/…/runtime/InvocationTotals.scala               # new
modules/runtime/…/runtime/Trace.scala                          # TraceContext, mint, inbound, into, currentContext
modules/runtime/…/runtime/Traceparent.scala                    # new
modules/runtime/…/runtime/TraceLogging.scala                   # new: the turbo filter
modules/runtime/…/runtime/RuntimeExtensionProvider.scala       # new
modules/runtime/…/runtime/Ankka.scala                          # providers loaded; TraceLogging installed
modules/runtime/…/runtime/Observability.scala                  # calling; invocation uses inbound
modules/runtime/…/runtime/ExternalServices.scala               # admitted method names
modules/runtime/…/runtime/HttpServiceClients.scala             # the call span and the header
modules/runtime/…/runtime/ProjectionSupport.scala              # handling returns the context; stamped
modules/runtime/…/runtime/ProjectionRuntime.scala, TopicHandlers.scala
modules/runtime/…/runtime/EventSourcedEntityHost.scala, KeyValueEntityHost.scala, WorkflowEngine.scala, TimerSweeper.scala
modules/runtime/…/runtime/remote/RemoteEventSourcedHost.scala, RemoteKeyValueHost.scala, RemoteProjection.scala
modules/runtime/…/runtime/ObservabilityEndpoint.scala, ObservabilityRoute.scala   # compile against the new span; answers unchanged
modules/runtime/src/main/resources/reference.conf              # two limits
modules/http/…/http/HttpServer.scala                           # Tracing.request reads traceparent
modules/grpc/…/grpc/Binding.scala, GrpcClients.scala           # read; the client interceptor
modules/agent/…/agent/AgentRuntime.scala, autonomous/AutonomousAgentHost.scala    # recorder.begin's new shape
modules/core/…/core/PlatformVariables.scala                    # two names

sidecar/…/sidecar/RemoteEndpoint.scala                         # Trace.into's new shape
sidecar/src/main/resources/logback.xml                         # the ids
controlplane/src/main/resources/logback.xml                    # new
samples/shopping-cart/src/main/resources/logback.xml
ankka.g8/src/main/g8/src/main/resources/logback.xml, build.sbt # the ids; the module

controlplane-api/…/api/descriptors.scala                       # the -telemetry suffix
operator/…/operator/Settings.scala, Rendering.scala, Action.scala, Executor.scala, Names.scala

kustomization/components/otel-collector/                       # new: six files
kustomization/tests/otel-collector/kustomization.yaml          # new: renders the component no overlay lists
kustomization/components/telemetry-store/                      # new: the store, its route and policy, the log agent
kustomization/components/operator/operator.yaml                # two variables
kustomization/components/controlplane/deployment.yaml          # two variables
kustomization/overlays/local/kustomization.yaml, platform-configmap.yaml
kustomization/overlays/cloud/kustomization.yaml, platform-configmap.yaml
kustomization/deploy-local.sh                                  # asks the telemetry store for the control plane's trace

build.sbt, project/Dependencies.scala                          # the module, its dependencies, templateArtifacts
docs/operate/telemetry.md (new), docs/concepts/, docs/operate/logs.md, docs/reference/, mkdocs.yml, tools/docs/skill/
CLAUDE.md                                                      # the graph, the counts, the traps
```

**Structure Decision**: one module and two kustomize components are added, because each is a
thing the spec names. Everything else goes where its kind already lives: trace identity in `Trace`, the
ring's fields in `Recorder`, the read of a context beside each transport's own entry span, the
write of one beside each client's own send, the platform's variables in their one declaration,
the Secret beside the secret key's in the operator's rendering.

## Order of work

Cut by user story, tests before the code they hold. Each slice stands on its own.

1. **Verify first** (research, *Verify first, gathered*): thirteen facts, each turned into a
   test. Four about the SDK come first; the rest sit with the slice that relies on them. Three
   could change the design: the JDK sender beside OkHttp, the SDK kept quiet, and the exporter
   accepting a span it did not make. If the SDK cannot be kept quiet, the fallback in R1 is taken
   up with you before anything is built on it.
2. **The recorder** (FR-002, FR-004). The high half, the random start, the anchor, the kind, the
   sentinel and `inbound`, the cursor, the tally; `RecorderSuite`, `RecorderCursorSuite`,
   `InvocationTotalsSuite`, `TraceSuite`, and the benchmark. Every later slice stands on it, and
   after it every existing suite still passes: the consoles' answers are unchanged.
3. **One trace across services** (User Story 1). `Traceparent`; HTTP in, HTTP out with `calling`;
   gRPC in and out; topics, stamped and read; the proxy's three cases. Proven on the ring, with
   no exporter.
4. **Export** (User Stories 1, 2 and 4). The provider and the builder; the module: settings,
   identity, the span as `SpanData`, the loop and its outage, the metrics, stopping; the fake
   collector and the module's suites.
5. **Logs** (User Story 2). The turbo filter, the four configurations, `TraceLoggingSuite`.
   Independent of slices 3 and 4.
6. **The operator and the declaration** (User Story 3). The two names, the suffix, the settings,
   the rendering by hosting, the action and the executor; the offline suites.
7. **The installation** (User Story 5). The collector's component, the two Deployments, the
   telemetry store's component and its agent with `TelemetryStoreSuite`, both overlays,
   `RemoteOverlaySuite`, the deploy script's last check.
8. **On a cluster**. The sample, the sidecar and the control plane carry the module; the k3s
   cases in the three suites that exist.
9. **Documentation**, the template and `CLAUDE.md`, then the whole build.

Slices 5, 6 and 7 depend on nothing after slice 2. Slice 8 needs 3, 4, 6 and 7.

## Complexity Tracking

| Violation or departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| **The runtime loads an extension the service did not hand over** (a `ServiceLoader` provider), against "registration is explicit" | "services opt into nothing" is the feature's third story; an embedded service's `Main` is the developer's, and nothing else can add an extension to it | naming the extension in every `Main` is the opt-in the spec refuses; putting the exporter in `ankka-runtime` puts OpenTelemetry in every application's build, which the spec's first decision refuses. Components are still only ever handed over (R1) |
| An embedded service's build names the module (SC-002 says no change to its code) | the dependency has to be on a classpath the developer owns | no module at all means writing OTLP's wire format by hand in `runtime`; the spec chose the SDK's exporter (R1) |
| The recorder gains more than FR-002 named: a random start for span ids, a clock anchor, a kind, an unknown-caller mark and a tally | each is something a collector needs that a console in the same process did not: ids unique across instances, a time since 1970, an edge between services, a root that is honestly an orphan, a count that never falls | exporting the ring as it is gives colliding span ids and counters that fall (R4, R5, R7, R8). FR-002 was amended to say so |
| The tally is written on the hot path | a count since start has to be made where a span ends | counting when spans are read is wrong exactly when spans are lost (R7) |
| The log ids are in a service's logging context, and on its lines only where the platform owns the pattern | an embedded service's `logback.xml` is the developer's | rewriting a developer's pattern at run time; FR-005 was amended to say where the platform prints them (R14) |
| The operator writes a Secret per service for the credential | a pod can only reference a Secret in its own namespace, and a Deployment is no place for a credential | a literal on every workload is readable by anyone who can read a Deployment (R18) |
| The platform's collector admits the platform's own namespaces as well as every project's (the spec says "every workload namespace and nothing else") | the control plane exports, and it carries the same label | a second label for workload namespaces alone, set by the operator and the control plane on every namespace they make (R19) |
| The platform ships a store, for a local platform only (the spec's input put a metrics backend, log search and dashboards out of scope) | decided in clarification: a trace nobody can open is shown only by a test | leaving a developer to install a backend before the first story can be seen. It is one development-grade container the cloud overlay never renders; a production store stays the installation's (R23) |
| The local store's agent reads every pod's log files from the node | logs are not exported, so something has to carry a line to the store | exporting logs from the JVM was refused in clarification; an agent on the node is what an installation runs anyway |
| The control plane gains a logging configuration | it has none, and FR-005 needs its pattern | leaving it at logback's default, at `DEBUG`, with no ids (R14) |

**Two operational consequences** to announce in the release: setting the collector's address on
a running installation rolls every service once; and for the minutes a service runs two versions
during its update to this release, a trace that crosses them is split in two.
