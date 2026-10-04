# Research: Telemetry Export

Decisions for [plan.md](plan.md), each with what in the repository it rests on. File references
are to this branch's base (`64102d79`); `R/` is
`modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/`. "Verify first" marks a
claim read from code or a dependency and not yet run; the task that touches it starts with a test
that would show it false. The words are the glossary's: a service **exports** its **telemetry**
(its **spans** and **metrics**) to a **collector** named by the installation's **telemetry
settings**; a request, a call or a message carries a **trace context**; the **trace window** is
the ring, and a **lost span** left it before it was exported.

## R1. A published module the runtime finds by a declared provider

**Decision**: a new module `modules/telemetry-otlp`, published as `ankka-telemetry-otlp`,
depending on `runtime` and the OpenTelemetry Java SDK. `runtime` gains one small trait,
`RuntimeExtensionProvider { def extension(config: Config): Option[RuntimeExtension] }`, and
`ServiceBuilder` asks `java.util.ServiceLoader` for providers when it starts, appending what they
return after the extensions the service registered. The module declares its provider in
`META-INF/services/…RuntimeExtensionProvider`; it returns `None` when `ankka.telemetry.endpoint`
is empty. `sidecar`, `controlPlane` and the shopping cart sample depend on the module, and the
Scala template's build names it.

**Rationale**: the spec's Story 3 is "services opt into nothing", and there is no way to honour
that today. `RuntimeExtension`s reach the builder only through `withExtension`
(`R/Ankka.scala:108-109`); the repository has no `ServiceLoader`, no `META-INF/services` and no
config key that loads one. The sidecar and the control plane could name the extension in their
own `Main` (`sidecar/…/Main.scala:186-194`, `controlplane/…/ControlPlane.scala:181-186`), but an
embedded Scala service's `Main` is the developer's, so without a provider every such service
would have to add a line of code, which is the opt-in the spec refuses. With a provider, an
embedded service needs the module on its classpath and nothing else: one line of its build, which
the template carries. `runtime` stays free of OpenTelemetry (the recorder's own comment, at
`R/Recorder.scala:28-30`, is "No dependency"), and a service without the module starts no thread
and loads no class.

The builder's comment "there is no classpath scanning" (`R/Ankka.scala:91`) is about components,
and stays true: `ServiceLoader` reads a named file a jar declares, it does not scan. This is the
first extension a service does not hand over itself, which is why it is under *Complexity
Tracking* in the plan.

**Alternatives considered**: `ankka-runtime` depending on the module (every service exports with
no build change) — rejected by the spec's first decision: a published library would put the
OpenTelemetry SDK in every application's build. Writing OTLP's protobuf by hand in `runtime`, as
the Prometheus text is written by hand, over the JDK's client: no dependency, no provider, no
build line, and the purest reading of "opts into nothing" — rejected because the spec chose the
SDK's exporter as the transport, and a hand-kept wire encoder is a second thing to keep right; it
is the fallback if the SDK cannot be made quiet (R2). A config key naming extension classes, as
Pekko's `library-extensions` does — a HOCON list is replaced, not merged, across `reference.conf`
files, and reflection on a name is `ServiceLoader` with less checking. Explicit `withExtension` in
every `Main` — the opt-in.

**Consequence the spec did not state**: an embedded Scala service built before this feature
exports once its build names the module; SC-002's "without any change to the service's code"
holds, and its build changes by one line. Process-hosted and module services change nothing: the
sidecar image carries the module.

## R2. The SDK is used for its exporters, not its tracer

**Decision**: the module depends on `io.opentelemetry:opentelemetry-exporter-otlp` (1.66.0, the
current release) with `opentelemetry-exporter-sender-okhttp` excluded and
`opentelemetry-exporter-sender-jdk` added, so the transport is OTLP over HTTP with protobuf on
the JDK's own client. Spans are handed to `OtlpHttpSpanExporter.export` as the module's own
implementation of the SDK's `SpanData` interface, built from recorded spans; no `Tracer`, no
`SdkTracerProvider` and no `BatchSpanProcessor` is used. Metrics go through an `SdkMeterProvider`
with asynchronous instruments whose callbacks read the runtime's totals (R7), a
`PeriodicMetricReader`, and `OtlpHttpMetricExporter` behind a delegating exporter that reports
each result to the module's outage tracker (R17). The SDK's own loggers
(`io.opentelemetry.exporter`, `io.opentelemetry.sdk.metrics.export`) are set to `OFF` through
`java.util.logging` when the extension starts.

**Rationale**: a recorded span is finished before the exporter sees it and has ids the recorder
chose, and the SDK's tracer makes spans live with ids of its own; `SpanExporter.export` taking
`SpanData` is the SDK's supported seam for exactly this. Calling it directly also puts batching,
back-off and what is logged in the module's hands, which FR-006 needs: the SDK's exporters log
each failed export through `java.util.logging`, which no logback configuration in this
repository bridges, so they would print to stderr once a batch. HTTP with the JDK sender is the
smallest classpath: `runtime` and the control plane have no grpc-java (`build.sbt:213-246, 457`),
and OkHttp arrives today only with the Anthropic client in `agent`.

**Verified** (T004–T006, `SenderSuite`, `SpanDataSuite`, `SdkBehaviourSuite`): all four hold. The
JDK sender is the only `io.opentelemetry.sdk.common.export.HttpSenderProvider` found, beside
OkHttp; a hand-built `SpanData` is exported with its ids as given and an empty parent for a root;
with the two JUL loggers `OFF` a failed export prints nothing (left alone it prints `SEVERE: Failed
to export 1 spans` per batch, which is the line a batch FR-006 forbids); an asynchronous counter is
cumulative and monotonic from one start time. Two things learnt: the exporters **retry by default**,
so the module builds them with `setRetryPolicy(null)` and keeps back-off in its own loop; and
`opentelemetry-proto`, planned as the fake collector's decoder, needs protobuf-java 4 beside the
ScalaPB 3 code of the module's test classpath, so the fake collector reads the wire format by field
number instead.

**Was to verify**: (1) excluding the OkHttp sender and adding the JDK one is enough for the
exporter to pick it, in sbt, with no `ServiceLoader` conflict when `agent` puts OkHttp on the
classpath beside it; (2) implementing `SpanData` outside the SDK is accepted by the exporter's
marshaller (ids as 32 and 16 hex, parent "invalid" for a root); (3) the SDK's loggers are
silenced by level and nothing prints during an outage; (4) asynchronous counters export
cumulative sums with a start time that does not move.

**Alternatives considered**: OTLP over gRPC — needs a gRPC sender (OkHttp or grpc-java) on every
exporting service's classpath. The SDK's tracer with a wrapping `SpanProcessor` — would mean
recording twice. `ImmutableMetricData` handed straight to the exporter — it is in an `internal`
package the SDK does not keep stable.

## R3. A trace id is two longs, in the ring and on the wire

**Decision**: `Recorder` gains a `traceIdsHigh` array beside `traceIds`; `RecordedSpan` and the
`Span` handle gain `traceIdHigh`. `Trace.mint()` returns both halves, each random and the pair
never zero. Inside a service the context still travels as metadata under `ankka-trace-id` and
`ankka-span-id`; the trace id is written as 32 hex digits and read as 1 to 32, so a 16-digit id
from an older node reads as a trace whose high half is zero. The thread's `Working` holds the
high half too, and a new `Trace.currentContext: Option[TraceContext]` returns all three numbers;
`Trace.currentTrace` keeps its shape for the callers that only compare. The local console and the
observe port go on keying a trace by the low half, in hex, as now.

**Rationale**: a trace context from outside carries a 128-bit id that must be continued exactly
(FR-008, scenario "…from outside the cluster continues that trace"), so both halves have to be
stored; minting 128 random bits rather than padding 64 with zeros is what a collector's ids are
assumed to be, and 64 random bits collide after about four billion traces. The cost on the hot
path is one more array store. Every SDK passes metadata through untouched (no source under
`sdks/` names `ankka-trace-id`), so the longer id crosses a process or a module with no SDK
change and no protocol change. The console reads ids as hex strings
(`R/ObservabilityEndpoint.scala:119, 134, 148`), so keeping the low half as "the" id there leaves
it unchanged, which the spec asks.

**Compatibility**: during a rolling update a node from before this feature cannot parse a
32-digit id (`R/Trace.scala:188-189`, `parseUnsignedLong`), reads it as no trace, and starts one.
For the minutes two versions run together, a trace that crosses them is split. Nothing fails, and
no stored form changes.

**Call sites**: `Trace.into(metadata, traceId, parentSpanId)` is called at `R/TimerSweeper.scala:190`,
`R/remote/RemoteProjection.scala:111, 228`, `R/remote/RemoteKeyValueHost.scala:135`,
`R/remote/RemoteEventSourcedHost.scala:224` and `sidecar/…/RemoteEndpoint.scala:106`; it becomes
`Trace.into(metadata, context)`. `Trace.traceIdOf`/`parentSpanIdOf` are read at
`R/Observability.scala:178-179`, `R/KeyValueEntityHost.scala:123-124`,
`R/EventSourcedEntityHost.scala:140-141`, `R/WorkflowEngine.scala:139-140`,
`R/remote/RemoteKeyValueHost.scala:122-123` and `R/remote/RemoteEventSourcedHost.scala:202-203`;
they become one `Trace.inbound(metadata)` (R8).

**Alternatives considered**: a second metadata key for the high half — an older node would carry
on with the low half alone and re-send only that, so a trace crossing old and new nodes would
have two ids in the collector, which is the same split with more code. Padding a 64-bit id with
zeros, as W3C allows a 64-bit system to — legal, and too few bits for an installation's history.

## R4. Span ids must be unique across instances

**Decision**: the recorder's span id counter starts at a random 64-bit number chosen when the
recorder is made, and skips zero and the sentinel of R8.

**Rationale**: it is `AtomicLong(0L).incrementAndGet()` today (`R/Recorder.scala:65, 79`), so every
instance's first span is span 1. Inside one ring that is unique; in a collector, two services in
one trace would both have a span 5, and a parent id would name the wrong span. A random start
keeps `begin` exactly as cheap, and two instances' ranges overlap only if their starts fall
within a few billion of each other in 2^64. It also repairs something that is already wrong: a
call that crosses nodes of one service carries the caller's span id as its parent
(`R/Trace.scala:81-84`), and on the callee's ring that number can already name an unrelated
local span.

**Alternatives considered**: a random id per span — a call to `ThreadLocalRandom` on the hot path
for nothing the counter does not give. Mixing the id with a salt at export — the id that travels
in metadata between nodes would still be the raw one.

## R5. A span's start is a wall-clock time at export, from one anchor

**Decision**: `Recorder` records `System.currentTimeMillis()` and `System.nanoTime()` once, when
it is made, and offers `epochNanos(startedNanos)`. The exporter uses it; nothing on the hot path
changes.

**Rationale**: `startedNanos` is `System.nanoTime()` (`R/Recorder.scala:86`), which is monotonic
and has no epoch, and OTLP wants nanoseconds since 1970. One anchor makes every span of an
instance consistent with every other; reading the wall clock per span would cost a second clock
read and could run backwards.

## R6. A read cursor that never trusts a slot and never waits for one

**Decision**: `Recorder.cursor(): Recorder.Cursor`, in `runtime`, with no dependency. A cursor
holds a position (a sequence number) and a bounded set of sequences it passed while their spans
were still in flight. `read(max)` returns a `Batch`: the complete spans after the position, up to
`max`, oldest first; the sequences still in flight; and how many spans were **lost** — sequences
that fell out of the ring before the cursor reached them, and in-flight ones whose slot was
reused. `commit(batch)` moves the cursor; a batch that is not committed is read again. A slot is
read only if its sequence matches before and after its fields are read.

**Rationale**: `snapshot()` (`R/Recorder.scala:106-125`) reads the whole ring newest first, which
is right for a console and gives an exporter no way to know what it has already sent. Sequences
are claimed at `begin` and published at `complete` (`:77, 97`), so a request's root span has a
lower sequence than its children and completes after them: a cursor that stopped at the first
in-flight span would stall behind every long request and every open stream until the ring
overturned, and one that skipped them would never export a root. Holding the skipped sequences
and looking again is the only shape that does neither, and it is bounded by the ring's capacity.
Read-then-commit is what "export resumes with the spans still in the ring" (Story 4) needs: a
failed export leaves the cursor where it was, and what the ring overwrote meanwhile is counted
lost when the cursor next reads. The lost count is exact, because sequences are dense.

`snapshot()` validates a slot once, before reading it; the cursor validates after as well, since
an exported span that was half one request and half another could not be taken back.

## R7. Counting since the instance started needs a tally on the hot path

**Decision**: `Recorder.complete` adds the span to `InvocationTotals`, a new dependency-free
class in `runtime`: for each pair of component and handler, four outcome counts and a sum of
durations, in `AtomicLongArray`s behind an open-addressed table of packed keys, sized by
`ankka.observability.max-counted-handlers` (default 1024). A pair that arrives when the table is
full is counted under one overflow entry, exported as `(other)`. The `Span` handle carries the
component, the handler and the start time, so a span whose slot was reused before it completed is
still counted.

**Rationale**: FR-004 and the scenario "an exported metric counts every run of a handler since
the instance started" cannot be met from the ring. The Prometheus route counts by grouping
`recorder.snapshot()` (`R/ObservabilityRoute.scala:43-89`), and its own comment says a scraper
differencing those numbers "would be differencing a ring". Counting at drain time is exact only
while nothing is lost, and what is lost is lost exactly when a service is busiest. So the count
is made where the span ends. It is not the first thing counted there: every host already calls
`Observability.handled`, which interns up to four names and writes a bucket of `CallCounts`
(`R/Observability.scala:62-74, 162-166`). The table costs a hash, a probe and two atomic adds, with
no allocation and no boxed key, which is why it is not a `ConcurrentHashMap[Long, …]`.

The bound is the one that exists: `RecorderBenchmark` asserts under 200 ns for recording a span
and under 2000 ns for stamping and counting (`modules/runtime/src/test/…/RecorderBenchmark.scala:98-102,
151-154`), and `ServiceRecordingCostSuite` asserts recording is at most 1% of a real invocation
(`modules/testkit/src/test/…/ServiceRecordingCostSuite.scala:89-94`). Both run under
`-Dankka.benchmarks=on`. The first is extended for the tally; the second's measurement is
repeated with an exporter attached in the new module's own `ExporterCostSuite`, since `testkit`
cannot depend on a module whose tests depend on it (R21).

The pairs are bounded for the reason the names are: components are registered and handlers are
declared (`R/Observability.scala:255-257`). The overflow entry is so that the table cannot fail,
as `ExternalServices` has `(other services)`.

**Measured** (T016, `RecorderBenchmark` under `-Dankka.benchmarks=on`, on the development Mac):
recording one span with both halves, a kind and the tally costs 48 ns, against the 200 ns the suite
asserts; 107 ns while a second thread reads the ring with a cursor as fast as it can, which is far
harsher than an exporter reading once a second. Side by side in one JVM, the recorder before and
after this feature measured 90–115 ns each across three rounds of a harness that allocates a
handle per span, with no consistent difference: within the noise. The default for
`max-counted-handlers` is 1024, not the 4096 first written here: 2048 slots of six longs is 96 KiB
beside the ring's 288 KiB, and a service with more than a thousand pairs of component and handler
is counted under `(other)` beyond them rather than failing.

**Found while doing it**: `TraceCorrelationSuite` selected the spans of its own call with
`spanId > recorder.recorded`, which was true only while span ids counted up from one alongside the
ring's sequence; and `RecorderSuite` asserted every span id positive, which a random start makes
false half the time. Both now say what they mean. The cursor reads a slot's sequence before and
after its fields behind `VarHandle` fences, and the writers fence the two stores that bracket the
fields: on an ARM machine a plain array gives no ordering at all, and a span exported torn cannot
be taken back.

**Not done**: `/ankka/metrics` goes on counting the window. Pointing it at the totals would make
its two `counter`s true counters and retire the limitation "Metrics are a window too"; it is a
small change this plan leaves to its own decision, since the spec says the route is as it was.

## R8. Two facts a span must carry that the ring does not hold

**Decision**: the ring gains one byte array, `kinds`, and `begin` takes a `SpanKind` (`Internal`
by default; `Server` for an HTTP or gRPC endpoint's span, `Client` for a call to another service,
`Consumer` for a topic message's span). And a span recorded for a *call* that carried no trace is
recorded with the parent `Recorder.UnknownCaller`, a reserved id: `Trace.inbound(metadata)`
returns the trace and parent a call carries, or a fresh trace with that parent when it carries
none, unless the call is the console's own (`CallOrigin.Console`). The six hosts of R3 use it.
`RecordedSpan` reads the sentinel back as `parentSpanId = 0` with `callerUnknown = true`, so
`Trace.assemble` and both consoles see what they see today.

**Rationale**: the scenario "a span with an unknown caller is exported with no parent and is
never given one" asks the export to say so, and today nothing can: a component called from a
thread the trace did not follow is recorded as an ordinary root (`getOrElse(Trace.mint())`,
`getOrElse(0L)` at each of the six sites), indistinguishable from an endpoint's. The host is the
one place that knows the difference: what reaches it came through the transport, and
`ShardingTransport` writes the caller's trace into every call that has one
(`R/ShardingTransport.scala:42, 59`), so a call with none was made outside any handler. That is
the glossary's *unknown caller*, and the same rule the topology uses for its counts. Entry points
(endpoints, projections, timers) mint a trace and are roots with no caller to be unknown.

The kind is what lets a collector draw a service-to-service edge: it pairs a `CLIENT` span with
the `SERVER` span that names it as parent. The exporter cannot infer a kind from a name.

**Alternatives considered**: marking any root whose component is not an endpoint — a guess from
a name, and wrong for a workflow resumed after a restart. Showing the sentinel in the local
console as "parent unknown" — arguably what the documentation already says, and a change to the
console the spec rules out; `callerUnknown` is there for the console to use later.

## R9. `traceparent`: one parser, one writer, and what is not carried

**Decision**: `runtime` gains `Traceparent`, pure: `parse(value): Option[TraceContext]` and
`render(context): String`. It accepts version `00` exactly as W3C writes it (55 characters, lower
case hex, neither id all zeros) and a later version by its first four fields; `ff`, a wrong
length, upper case or a zero id is no context. It writes version `00` with the flags `01`
(sampled): everything recorded is exported. `tracestate` is neither read nor written.

**Rationale**: three transports read and write it (R10–R12), and the proxy must be shown to pass
it (R13); one function each way is how they cannot disagree. An unreadable header is ignored and
a new trace starts (Edge Cases). Dropping `tracestate` is what a participant with nothing to add
may do; the documentation says so, since a vendor that keeps state there will see it stop at the
first ankka service.

## R10. HTTP: the server continues a context, the client records a call

**Decision**:

- `HttpServer`'s `Tracing.request` reads `traceparent` from the request context already on the
  thread and begins its span with that trace and parent, kind `Server`; with none it mints, as
  now.
- `Observability` gains `calling(service, method)(outcomeOf)(body: String => A)`: it
  begins a `Client` span under the thread's current span (or as a root with an unknown caller
  when the thread has no trace), hands `body` the `traceparent` to send, and completes the span
  with the outcome the caller saw. `HttpServiceClients.counted` runs its call inside it and sets
  the header, replacing one the handler supplied.

**Rationale**: `Tracing.request` begins every HTTP span with `Trace.mint()` and parent `0L`
(`modules/http/…/HttpServer.scala:625-642`) and is run inside `RequestScope.withContext(context)`
(`:469, 530`), whose headers include everything but the local-caller header (`:389-410`), so the
context is one lookup away with no signature change. The sidecar serves a process's and a
module's endpoints through this same server (`sidecar/…/Main.scala:177-184`), so one change
covers every hosting, and `RemoteEndpoint` already forwards the request's headers to the process
(`sidecar/…/RemoteEndpoint.scala:87-107`).

The client side has nothing to build on: `HttpServiceClients.send` sets only `Content-Type` and
the caller's own headers (`R/HttpServiceClients.scala:177-200`), and `counted` (`:136-160`) updates
the topology's counts through `Observability.made`, which never touches the recorder
(`R/Observability.scala:124-144`). The span's names are the ones that count already uses, and are
bounded for the same reason: the service by `externalServices.nameFor(project, name)`, admitted up
to a limit, and the handler by `HttpServiceClients.methodName` (`:210-212`), never a path. The
outcome is the one `counted` computes: 4xx refused, 5xx failed, a timeout timed out, anything
that never arrived failed.

`calling` is in `Observability` so that 025's clients on other contexts, when they exist, record
through the same function (the clarification, and 025's amended dependency).

**Found, not changed**: an HTTP handler that throws a 4xx `HttpProblem` is recorded `Failed`, not
`Refused` (`HttpServer.scala:625-642`: the outcome is `Ok` only if the body returns), and an ACL
refusal, a 404 and a 405 record no span at all (`:305-319, 368-381`), where a gRPC refusal does.
Exported, the first is an error status on a request the service refused on purpose. It predates
this feature and the spec's refusal scenario is about an entity; it is listed in the plan for a
decision.

## R11. gRPC: the same two halves

**Decision**: `Binding`'s `Spans.Call` reads the `traceparent` key from the call's metadata and
begins its span with that trace and parent, kind `Server`, for every kind of method.
`GrpcClients` adds a `ClientInterceptor` beside `Outcomes` that, in `start`, runs
`Observability.calling` for the channel's service and the method's full name, puts `traceparent`
in the headers, and completes the span when the call closes, with the status mapped as the
server maps it. `GrpcClients` is given the service's `Observability` when it starts.

**Rationale**: the server begins with `Trace.mint()` and `0L` (`modules/grpc/…/Binding.scala:413-415`)
and has the headers in `startCall` (`:290-312`); `CallMetadata.of` already keeps every ASCII key
(`CallMetadata.scala:32-45`). The client has an interceptor whose `start(listener, headers)` sees
the outgoing headers (`GrpcClients.scala:221-261`), on the calling handler's thread, where the
thread's trace is. A method's full name comes from a compiled service definition, so it is
bounded by code; it is still admitted through a limit
(`ankka.observability.max-external-methods`, default 256, then `(other methods)`), because it is
interned and nothing interned may be unbounded.

**Found, not changed**: the gRPC client makes no topology count (`.made(` has callers only in
`HttpServiceClients` and `ViewClient`), and a gRPC handler's thread has a trace and no
`CallOrigin` (`Binding.scala:415` uses the two-argument `Trace.within`), so a call made from one
is counted from the unknown caller. The outbound span is correct regardless: it is parented by
the thread's span, not its origin. Both are listed in the plan.

## R12. Topics: the context is handed from the span to one stamping function

**Decision**:

- `ProjectionSupport.handling` returns the context of the span it recorded beside the handler's
  effect, and `applyConsumer` takes it. A new `ProjectionSupport.stamped(metadata, context)` sets
  `traceparent` on a message's metadata, replacing one the handler set, and is called for
  `Produce` and for each message of `ProduceAll`, in process and in `RemoteConsumer`, which has
  its span's ids as local values.
- The four topic handlers (`ViewTopicHandler`, `ConsumerTopicHandler`, `RemoteViewTopicHandler`,
  `RemoteConsumerTopicHandler`) parse `traceparent` from the message's metadata and begin their
  span with that trace and parent, kind `Consumer`; with none, or one that cannot be read, they
  mint.
- A consumer or view reading an entity's events or state inside a service is unchanged: its span
  is the root of a new trace.

**Rationale**: the publish cannot read the thread. `handling` closes the span and restores the
thread-local before the effect is applied (`R/ProjectionSupport.scala:43-57`; `applyConsumer` is
called after it at `R/ProjectionRuntime.scala:575-584, 610-621` and `R/TopicHandlers.scala:100-109`),
and a remote consumer's span is never put on a thread at all (`R/remote/RemoteProjection.scala:215-296`).
The span's ids are in hand at both places, so they are passed. Stamping cannot live in
`publishAll` alone: a single `Produce` goes straight to `target.publish`
(`R/ProjectionSupport.scala:214-219`, `R/remote/RemoteProjection.scala:276-286`), and the Python SDK
sends a one-message batch as a plain `produce`. It cannot live in the Kafka publisher either:
`InMemoryBroker` hands the publisher's metadata to subscribers with no headers added
(`R/MessageSubscriber.scala:64-83`), and every suite but one uses it. Metadata is the one thing
both carry: `CloudEvents.headers` writes every metadata entry that is not `ce-*` as a record
header (`R/Kafka.scala:49-50`), and the subscriber reads every header back into the message's
metadata (`:141-152`).

A message delivered again is handled again and is a second span under the same parent; a message
handled a week later joins a trace a week old. Both are in the spec's edge cases and need no
code.

**What the clarification overrides, and where it stops**: `handling`'s comment says a projection
is "a trace root, as every projection's work is", because "threading the writer's trace into it
would make one request appear to last for hours" (`R/ProjectionSupport.scala:37-42, 71-73`). The
clarification chose to continue a trace across a topic, where the message says which trace it is.
It did not ask for the same across a journal: a journal record has no field for a trace
(`R/wire.scala:142-147, 163-168`), and adding one is a stored-format change no requirement asks
for. So the rule becomes: a message that carries a context is continued; a change read from a
journal starts a trace.

**In the callee's own console** the consumer's span, like an endpoint's span that continued a
context, names a parent that is on another instance. `Trace.assemble` reports that as
`parentUnknown` and the trace as `partial` (`R/Trace.scala:217, 244-246`), which is what
`partial` was defined to mean ("this window does not hold all of it … a console over a deployed
installation would have a second" cause, `:192-197`). No console code changes; a trace that began
elsewhere reads as one that did.

## R13. The proxy already passes the context; a test holds it

**Decision**: no change to `proxy-core`. Three cases are added to its tests: a `traceparent` on a
request to the process, on a request under a mount, and on a call at the calling address arrives
unchanged.

**Rationale**: `Headers.inbound` drops `x-ankka-*`, `forwarded`, the hop-by-hop headers and the
`X-Forwarded-*` it sets itself (`proxy-core/…/Headers.scala:65-70`); a mount adds a second
`x-ankka-` filter (`ProxyEngine.scala:177-179`); `Headers.outbound` drops `x-ankka-*`, hop-by-hop
and `host` (`Headers.scala:92-96`). `traceparent` is none of those. FR-018 is therefore a property
the proxy has by accident, and a filter tightened later would lose it silently: the cases are the
requirement.

## R14. A log line gets its ids when it is written, not when a handler starts

**Decision**: `runtime` gains `TraceLogging`, a logback `TurboFilter` installed on the logger
context when a service starts (and only when the logging backend is logback). For a log call
that will be written, it sets the MDC keys `trace_id` (32 hex) and `span_id` (16 hex) from
`Trace.currentContext`, or removes them when the thread has no trace; for a call below its
logger's level it does nothing. The platform's own configurations print them: the sidecar's
`logback.xml`, a new one for the control plane, the sample's and the template's append
` trace_id=… span_id=…` to a line that has them and nothing to a line that does not.

**Rationale**: there are three ways to get an id onto a line. Setting the MDC in `Trace.scoped`
(`R/Trace.scala:182-186`) pays for two hex strings and a map copy on every invocation, logged or
not, on the path the recorder keeps free of allocation. A pattern converter that reads the
thread's trace when the line is formatted costs nothing until then, and is wrong whenever the
line is formatted on another thread: an `AsyncAppender`, and `LogCapturing`'s buffer, which
snapshots an event with `prepareForDeferredProcessing` and prints it later
(`modules/testkit/src/main/…/LogCapturing.scala:97-107`). A turbo filter runs on the logging
thread before the event exists, so the MDC it sets is in the event's own snapshot: nothing is
paid until a line is written, and the ids survive any appender. Because it sets *or clears* on
every written line, a thread reused after a handler (a dispatcher's, the Kafka stream's) cannot
carry the last handler's ids onto a line written outside one.

`runtime` already depends on logback (`build.sbt:246`); no dependency is added. The keys are the
names log stores join on.

**What the platform cannot do**: an embedded Scala service's `logback.xml` is the developer's
(`ankka.g8/src/main/g8/src/main/resources/logback.xml`; `ankka-runtime` ships none). The ids are
in the MDC of every such service; they are on its lines when its pattern prints them. The
template and the sample are changed, and the logs page says what to add. A process's own lines
(Python, TypeScript) are the process's: FR-005 is about the platform's program.

**Found**: the control plane's image has no `logback.xml` (`controlplane/src/main/resources`
holds only `reference.conf`), so it logs with logback's default configuration, at `DEBUG`, in
another pattern. It gains one, the sidecar's with the ids, at `INFO`. That is a change to what
the control plane prints, and is listed in the plan.

**Verify first**: logback's `%replace(…){regex, replacement}` wraps the two `%X` keys so that a
line without ids ends exactly as it does today; a turbo filter added in code survives a
`logback.xml` loaded before it and one reloaded after.

## R15. Who a span says it is from

**Decision**: the resource of everything an instance exports is `service.name` (the service),
`service.namespace` and `ankka.project` (the project), `service.instance.id` (the host name,
which in a pod is the pod's name) and `ankka.runtime.version`. The service and project are read
from the instance's own certificate, `ankka://<project>/<service>`, when it has one, and
otherwise from `ankka.telemetry.service-name` and `ankka.telemetry.project`, which default to the
actor system's name and to nothing.

**Rationale**: a workload is told neither its service's name nor its project today. The operator
sets `ANKKA_CLUSTER_SERVICE` and no project (`operator/…/Rendering.scala:1229-1247`); the
observability documents call a service by its actor system's name, which is `"ankka"` for every
sidecar (`sidecar/…/Main.scala:58`), and its instance by the JVM's pid, which in a container is 1
(`R/ObservabilityDocuments.scala:20`). The certificate already says both, it is what every other
workload identifies this one by, and `RotatingTls.identity` reads it (`R/RotatingTls.scala:59-62,
383-385`). Using it adds no variable to render, to refuse in a descriptor and to withhold from a
module, and the control plane, whose manifest the operator does not render, gets
`platform`/`controlplane` for nothing. Locally there is no certificate, and a test names its
service by configuration.

**Alternatives considered**: two more variables the operator injects — four names to declare
instead of two, and a second statement of an identity that could disagree with the first.

## R16. What is exported

**Decision**: see [contracts/export.md](contracts/export.md). In short: a span is named
`<component> <handler>`, carries `ankka.component`, `ankka.handler` and `ankka.outcome`
(`ok`, `refused`, `failed`, `timed_out`), and has the status `ERROR` only when it failed or timed
out; a refusal is exported with the status unset and the outcome `refused`. A span with an
unknown caller has no parent and `ankka.caller = unknown`. Three metrics: `ankka.invocations`
(by component, handler and outcome), `ankka.invocation.duration` in seconds (by component and
handler), both cumulative since the instance started, and `ankka.telemetry.lost_spans`.

**Rationale**: OTLP's status has three values and no word for a refusal, and FR-003 wants
`Refused` told from `Failed`; an attribute on every span says which of the four it was, and the
status is left to mean what a collector's error rate should count. Histograms are not exported:
a bucket count per span is more on the hot path than a sum, and no requirement asks for one.

## R17. The export loop, an outage, and stopping

**Decision**: one daemon thread per exporting instance, `ankka-telemetry`. It reads a batch
(`ankka.telemetry.batch-size`, 512) every `ankka.telemetry.interval` (1 s), and sooner when the
unread spans pass half the ring, which it checks every 100 ms by comparing two longs. A batch
that exports is committed; one that fails is not, and the loop waits, doubling from the interval
to `ankka.telemetry.max-backoff` (30 s). The first failure of an outage logs one `WARN` naming
the collector's address (never its headers) and the reason; the first success after it logs one
`INFO` with how many spans were lost. Metrics export every `ankka.telemetry.metric-interval`
(10 s) through the same outage tracker. `stop()` reads and exports once more and shuts the
exporters down, all within `ankka.telemetry.shutdown-timeout` (3 s).

**Rationale**: FR-006 and SC-004 want one line an outage, not one a batch; the tracker is the
single place either exporter's result arrives, so there is one state to log from. Not committing
a failed batch is what makes recovery export "the spans still in the trace window" with nothing
buffered: the ring is the buffer, and it is already bounded. Waking early under load is the
answer the scope leaves to the plan ("no sampling beyond the ring's own overwrite; how often the
exporter drains is the plan's"): the check reads `recorder.recorded`, which is one volatile read.

Stopping fits the phases that exist. Extensions are stopped from coordinated shutdown's first
phase without being waited for, and waited for in the last, which has 20 s
(`R/Ankka.scala:460-503`, `reference.conf:107-113`); providers' extensions are appended last and
stopped first, so the exporter flushes while the instance still serves and takes three seconds of
the twenty at most, collector or no collector.

## R18. The operator gives the settings to the platform's program, and writes one Secret

**Decision**:

- `Settings` gains `otlpEndpoint` and `otlpHeaders`, read from `ANKKA_OTLP_ENDPOINT` and
  `ANKKA_OTLP_HEADERS` in the operator's own environment.
- `Rendering.container(...)` adds `ANKKA_OTLP_ENDPOINT` as a literal when the endpoint is set,
  and, when headers are set too, `ANKKA_OTLP_HEADERS` as a `secretKeyRef` to
  `<service>-telemetry`, key `headers`, in the service's own namespace.
- A new action, `EnsureTelemetrySecret(namespace, name, labels, owner)`, describes that Secret
  and holds no value; `Fabric8Executor` is given the headers when it is built and applies the
  Secret, owned by the service's resource, so it goes when the service does.
- Both names join `PlatformVariables.PlatformOnly`, and `-telemetry` joins
  `ServiceSpec.PlatformSecretSuffixes`.

**Rationale**: every installation setting the operator has is read from its own environment and
rendered as a literal (`operator/…/Settings.scala:88-134`, `Rendering.scala:233-244, 1353-1354`);
the address is one more. `container()` is the path embedded, process and module hosting share
and web hosting does not (`Rendering.scala:951-1039` against `webContainers`, `:1048-1142`), and
the process container is built apart from it (`:1009-1018`), so placing the variables there is the
whole of "the sidecar and not the process, and neither container of a web-hosted service"
(FR-010). With nothing set, nothing is rendered, so `RenderingUnchangedSuite` is unchanged.

The headers are a credential and cannot be a literal: a Deployment is readable by far more than
a Secret is. A pod can only reference a Secret in its own namespace, and nothing copies Secrets
between namespaces, so the operator writes one, as it writes the secret key's and the database's.
Its grant is already `secrets: get, create, patch` (`kustomization/components/operator/operator.yaml:91-97`),
so no grant changes. The action holds no value for the reason `EnsureSecretKey` holds no key: an
action is described in logs.

`PlatformOnly` is what makes a descriptor that sets either refused, with the message
`is set by the platform and cannot be declared`
(`controlplane-api/…/descriptors.scala:260-266`), and what makes a module told it is not set
(`PlatformVariables.withheldFromModule`, `modules/core/…/PlatformVariables.scala:96-98`, read by
`sidecar/…/wasm/HostImports.scala:147-149`); both suites iterate the declaration
(`DescriptorSuite.scala:274-305`, `WasmHostSuite.scala:324-332`), so FR-011 and the module half of
FR-010 are met by two names in one set. The suffix is what stops a descriptor's own
`secretKeyRef` naming the collector's credential and handing it to a process
(`descriptors.scala:425-433`), and a project secret taking the name.

**Consequence the spec did not state**: setting or changing the address on a running installation
changes every workload's pod template, so the operator rolls every service once, by the ordinary
rolling update. Changing the headers changes the Secret and no pod: a service sends the new
headers when it next starts. Both are documented where an installer sets them.

**Verify first**: the Secret applied by server-side apply with an owner reference is deleted with
the service's resource, and an apply that changes nothing rolls nothing.

## R19. The installation says it once; the platform's collector is a component

**Decision**:

- The `ankka-platform` ConfigMap gains `otlpEndpoint`. Kustomize `replacements` copy it into
  `ANKKA_OTLP_ENDPOINT` on the operator's and the control plane's Deployments, which gain that
  variable with an empty value, and `ANKKA_OTLP_HEADERS` from a Secret `ankka-telemetry`, key
  `headers`, `optional: true`, in each one's own namespace.
- A new component, `kustomization/components/otel-collector/`: a namespace `ankka-telemetry`,
  the collector (`otel/opentelemetry-collector:0.162.0`) receiving OTLP on 4318 and
  4317 and writing to its `debug` exporter in detail, a Service, and a NetworkPolicy admitting
  pods labelled `app.kubernetes.io/managed-by: ankka` in namespaces labelled the same.
- The local overlay lists the telemetry store's component (R23) instead and sets `otlpEndpoint`
  to that. The cloud overlay lists neither, and sets `otlpEndpoint: ""`, marked `SET`.
- `deploy-local.sh` ends by asking the telemetry store for a trace from the control plane.

**Rationale**: every value the platform's own Deployments take from the installation arrives
this way (`overlays/local/kustomization.yaml:35-145`), and the control plane is not rendered by
the operator, so it needs the variable in its manifest (`components/controlplane/deployment.yaml:81-136`).
An empty address is no address: the operator's `raw` drops an empty value
(`Settings.scala:130-134`) and the module's provider returns nothing for one. That is what makes
"an installation that is not a local platform names no collector until the installation sets
one" a property of the cloud overlay as written.

The label is the one every HTTP policy already admits by (`operator/…/ZeroTrust.scala:303-347`),
and the platform's own namespaces carry it too (`components/controlplane/namespace.yaml`), which
is needed: the control plane exports. So the policy admits the installation's projects and its
own services, and a pod of anything else is refused. No workload namespace has an egress policy
(every rendered policy is `Ingress` only), so nothing has to be opened on the sending side.

`debug` in detail is the core image's own exporter and prints each span's ids, name and
attributes to the collector's log, which is what the k3s suites read and is the least an
installer can send to; the image that can write a file is
the contrib one, which has no shell to read it back with.

**Verify first**: a kustomize `replacement` whose source is an empty string writes an empty
string (if it refuses, the cloud overlay carries the variable in a patch file, as it does the
sidecar image); the detail output's form, which the suites parse.

## R20. The connection to the collector

**Decision**: the address is a URL. `http://` is sent in the clear, which is what the platform's
own collector is reached by, inside the cluster and behind its policy; `https://` is verified
against the JDK's trust store. The exporter presents no client certificate and takes no
authority of the installation's own. The credential, where a collector wants one, is
`ANKKA_OTLP_HEADERS`, in the form `name=value,name=value`.

**Rationale**: the collector is the installation's program, not a workload of the platform, so it
has no `ankka://` identity for a client to require, and the platform's authorities issue nothing
to it. An installation whose collector is outside the cluster uses `https` and a header; one
whose collector has a private certificate fronts it with something publicly trusted or runs it
in the cluster. This was the item clarification deferred; a private authority for the collector
is recorded under limitations.

## R23. A local platform's telemetry store

**Decision**: a second component, `kustomization/components/telemetry-store/`, in the namespace
`ankka-telemetry` (labelled `app.kubernetes.io/managed-by: ankka`, so the gateway's listener
admits its route):

- **The store**: one Deployment of `grafana/otel-lgtm` (0.35.0, pinned), a Service `lgtm` on 4318
  and 4317 for OTLP and 3000 for Grafana, an `HTTPRoute` for `grafana.<base domain>` on the
  gateway's HTTPS listener, and a NetworkPolicy admitting OTLP from pods labelled
  `managed-by: ankka` in namespaces labelled the same and from the log agent, and Grafana's port
  from the gateway's proxy pods and nothing else.
- **The log agent**: a DaemonSet of `otel/opentelemetry-collector-contrib` (0.162.0, the collector's release) that reads
  `/var/log/pods` from the node, read-only, with the `filelog` receiver and its `container`
  parser, which takes the namespace, pod and container from each file's path; lifts
  `trace_id=… span_id=…` from the end of a line into the record's own trace and span ids; names
  each record's service by its container and its project by its namespace; and sends to the
  store over OTLP. It needs no grant: it asks the API server nothing.
- The local overlay lists the component, sets `otlpEndpoint` to
  `http://lgtm.ankka-telemetry.svc.cluster.local:4318`, and copies the base domain into the
  route. The cloud overlay does not list it.

The component and the `otel-collector` component are alternatives: both make the namespace, so an
overlay that lists both fails to render, loudly.

**Rationale**: the user's decision in clarification (Session 2026-10-04, the last answer): a
trace nobody can open is shown only by a test. `otel-lgtm` is Grafana's own single container of
a collector, Tempo, a Prometheus-compatible store, Loki and Grafana, published "for development,
demo, and testing", which is exactly a local platform; a real Tempo, Loki and Mimir want object
storage, retention and sign-in, are an installation's own, and for the production clusters
belong in `ankka-deployments` beside the overlays that moved there. Listing the store in the
local overlay and not the cloud one is how "the platform stays neutral" is a build fact:
`RemoteOverlaySuite` asserts the cloud overlay renders no object of it.

The agent exists because logs are not exported (the first clarification): nothing else would
carry a line to the store. It is the same thing an installation's own agent does, which is why
it is the honest demonstration that stdout with ids is enough. Reading the namespace and
container from the path rather than asking the API server keeps it without a ServiceAccount's
grant; a deployed service's container is named for the service and its namespace for the project
(`operator/…/Rendering.scala`, `Names.scala:32`), so both names are in the path.

Grafana's built-in sign-in in that image is public knowledge, as Keycloak's development admin
is. It is reachable only where the local platform is, on the developer's own machine, and the
cloud overlay never renders it; the same rule that deletes Keycloak's development secret there.

**How it is tested without a cluster**: the store is one image, so the new module's
`TelemetryStoreSuite` runs it as a container, with the tag read from the component's own
Deployment so the two cannot differ, points a test-kit service's exporter at it, and reads the
trace back from Tempo by its id and the counter from the metrics store by its name, on the ports
the image publishes for each. The agent's case runs the contrib image with **the component's own
configuration file**, over a directory holding one pod's log file in the node's format, and
reads the line back from Loki with its service, its project and its trace id. A k3s node already
starved by sample JVMs (the trap in `CLAUDE.md`) is not asked to hold a gigabyte image; the
overlay's shape is `RemoteOverlaySuite`'s, and the deploy script's last check is the proof on a
real cluster.

**Verify first**: the image's size and start time in CI (about 915 MB compressed); that Tempo,
the metrics store and Loki answer on the ports the image documents (3200, 9090, 3100) without
sign-in; that OTLP metrics named `ankka.invocations` are found as the store names them; that a
log record carrying a trace id is joined to its trace by the Grafana the image provisions, and
if not, what one mounted data source file changes; that the `container` parser reads a kind
node's log lines; that Grafana serves behind the gateway at a hostname without a setting of its
own for the address it is reached at.

## R21. Where each scenario is held

`features/observability/` is not run by one `GherkinSuite`: its scenarios span every level from
a pure suite to a k3s cluster, so each is a test named after it (the rule for a scenario no one
suite can reach). A **fake collector**, in the new module's tests, is a JDK `HttpServer` on an
ephemeral port that parses `ExportTraceServiceRequest` and `ExportMetricsServiceRequest` with
its own reader of protobuf's wire format, records the paths asked for, and can be closed and
reopened on the same port.

| Scenario | Held by |
|---|---|
| a request that crosses from one service to another is one trace in the collector (HTTP, gRPC) | `CrossServiceTraceSuite` (module): two services in one JVM, one fake collector; and on k3s, `ZeroTrustClusterSuite`, two samples and the real collector (HTTP) |
| a request that carries no trace context starts a new trace | `HttpTraceContextSuite` (`http`), `GrpcTraceContextSuite` (`grpc`) |
| a request that carries a trace context from outside the cluster continues that trace | the same two |
| a message published to a topic continues the trace of what published it | `TopicTraceSuite` (`testkit`, `InMemoryBroker` shared by two services); `KafkaSuite` gains the header on a real record |
| a message that carries no trace context starts a new trace | `TopicTraceSuite` |
| a request that passes through a web-hosted service keeps its trace context | `proxy-core`'s engine suite, under a mount |
| a call the process of a web-hosted service makes keeps the trace context the process gave it | the same, at the calling address |
| an exported span says where it ran, which handler ran and how it ended | `ExportedSpansSuite` (module) |
| a refusal is exported as refused and never as failed | `ExportedSpansSuite` |
| a span with an unknown caller is exported with no parent and is never given one | `ExportedSpansSuite`; `TraceSuite` (`runtime`) for the sentinel |
| an exported metric counts every run of a handler since the instance started | `ExportedMetricsSuite` (module), ring of 64, 200 commands |
| an exported metric names the same service and project as the spans | `ExportedMetricsSuite` |
| what a handler prints names the trace id and the span of the handler | `TraceLoggingSuite` (`runtime`) |
| what a service prints outside any handler names no trace id | `TraceLoggingSuite` |
| a service's logs are not exported | `ExportedSpansSuite`: the fake collector was asked for nothing but traces and metrics |
| a deployed service exports without its descriptor asking | `TelemetryRenderingSuite` (`operator`); `ZeroTrustClusterSuite` |
| a service exports whatever language it is written in (Scala, Python, Rust) | `ZeroTrustClusterSuite` (Scala); `SidecarClusterSuite` (Python, Rust) |
| a descriptor may not give a telemetry setting | `DescriptorSuite`, by iteration |
| a module that asks for a platform setting is told that it is not set (changed) | `WasmHostSuite`, by iteration |
| a service of an installation that names no collector exports nothing | `TelemetryProviderSuite` (module): no extension, no thread, no connection |
| a web-hosted service exports nothing | `TelemetryRenderingSuite`: neither container has either variable |
| a service whose collector cannot be reached handles its requests as before | `CollectorOutageSuite` (module); the bound in `ExporterCostSuite` (module) under `-Dankka.benchmarks=on` |
| a collector that cannot be reached is reported once and not for every span | `CollectorOutageSuite` |
| a service exports again when its collector can be reached again | `CollectorOutageSuite` |
| an instance that stops exports what it holds and is not kept from stopping | `CollectorOutageSuite`, with the collector up and with it down |
| a service of any project reaches the platform's collector | `ZeroTrustClusterSuite` |
| a workload that is not of the installation cannot reach the platform's collector | `ZeroTrustClusterSuite`: a pod in an unlabelled namespace |
| an installation that is not a local platform names a collector of its own | `RemoteOverlaySuite`: no collector, no telemetry store, no address |
| the control plane exports as a service does | `ControlPlaneClusterSuite`, which deploys the control plane's manifest |
| a developer reads the trace of a request in the telemetry store | `TelemetryStoreSuite` (module), the store's image in a container |
| a developer reads the metrics of a service in the telemetry store | `TelemetryStoreSuite` |
| a developer reads the logs of a service in the telemetry store, joined to their trace | `TelemetryStoreSuite`, the agent's own configuration over a pod's log file |

Under them: `RecorderSuite` and `RecorderCursorSuite` (the cursor against concurrent writers, a
ring that overturns, a span that completes late), `InvocationTotalsSuite`, `TraceparentSuite`,
`PlatformVariablesSuite` (the pinned counts), `ProxyEnvironmentSuite` (the new Secret name),
`RenderingUnchangedSuite` (nothing set, nothing rendered), `TemplateSuite` (the template's build
resolves the module).

**The honest limits**: the throughput bound is asserted only under `-Dankka.benchmarks=on`, as
the recorder's own bound is; CI does not run it. A k3s suite reads the collector's log, so a
collector that received nothing reads as a timeout naming the span waited for, not as a pass.
Each k3s case is added to a suite that already starts a cluster and already deploys the samples
it needs; no new cluster suite is added.

## R22. Documentation, publishing and the files that count things

**Decision**: a new page, `docs/operate/telemetry.md` (pointing an installation at a collector,
the credential, the platform's collector, a local platform's telemetry store and how to open it,
what is exported, lost spans, what is not carried), in
`mkdocs.yml`'s `nav` and in the `ankka-deploy` skill's `pages:`. Changed: `concepts/observability.md`,
`operate/logs.md`, `reference/limitations.md` (traces are also exported; the window is what an
instance holds; logs are gathered from stdout; a collector with a private authority),
`reference/configuration.md` (the module's `reference.conf` joins the generated table's files in
`mkdocs.yml`, and the prose names each new key), `reference/web-hosting.md` (the context passes
through), the descriptor reference (the two names a descriptor may not set), and the Scala build
page (the one line). The skills are rendered again.

The module is the tenth published artifact and the ninth a service depends on;
`templateArtifacts` publishes it locally, since the template's build names it. `CLAUDE.md`'s
module graph, its *Publishing* counts and its traps are updated, and two stale counts found on
the way are corrected with them (`release.yml:1`, "six application-facing modules";
`TemplateSuite.scala:14`, "six artifacts").

**Rationale**: a new page fails `docs check` unless it is in `nav` and a skill; the configuration
table is generated from the files `mkdocs.yml` lists (`mkdocs.yml:105-115`), and its coverage
check fails until the prose beside it names every key. `modules/**` and `kustomization/**` are
already claimed by CI's path filters (`.github/workflows/ci.yml:56-76, 97`), so no filter changes.

## Verify first, gathered

1. The JDK sender is chosen with the OkHttp one excluded, beside `agent`'s OkHttp (R2).
2. The exporter accepts a `SpanData` it did not make (R2).
3. The SDK logs nothing once its loggers are off (R2).
4. Asynchronous counters export cumulative sums from a fixed start (R2).
5. `%replace` leaves a line without ids exactly as it is today, and the turbo filter survives a
   configuration reload (R14).
6. An owned Secret goes with its service; an unchanged apply rolls nothing (R18).
7. A kustomize replacement copies an empty string (R19).
8. The collector's detail output can be parsed for ids, parents and attributes (R19).
9. `RotatingTls.identity` is readable when a provider's extension starts, in the control plane as
   in a service (R15).
10. The telemetry store's image starts in a container in a time CI can afford, and its three
    stores answer without sign-in on the ports it documents (R23).
11. An exported counter is found in the store under the name the store gives it (R23).
12. A gathered line that names a trace is joined to that trace by the Grafana the image
    provisions (R23).
13. The `container` parser reads a kind node's log files, and the path gives the namespace and
    the container (R23).
