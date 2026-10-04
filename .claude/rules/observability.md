---
paths:
  - "modules/runtime/**"
  - "modules/http/**"
  - "modules/telemetry-otlp/**"
  - "kustomization/components/telemetry-store/**"
  - "kustomization/components/otel-collector/**"
  - "cli/src/main/resources/console/**"
  - "cli/src/main/scala/**/cli/console/**"
  - "controlplane/**"
---

# Observability, topology and the local console

## Calls are attributed by the runtime and counted from two ends

A service's topology (feature 019) has declared connections, read from the registry, and observed calls,
counted over a window (`CallCounts`, `ankka.observability.call-window`). The caller is a `CallOrigin`
(component and handler) held on the thread beside the trace (`Trace.within`, `Trace.currentOrigin`) and
written into a call's metadata as `ankka-caller` by `Trace.outbound`, which *strips* any caller already
there when nothing is current — a handler forwarding its own metadata must not put its call on whoever
called it. A name in metadata is believed only when `DeclaredNames` holds it, so nothing a call carries can
grow the names table. *Handled* (ok, refused, failed) is counted by the callee's host, *unanswered* (timed
out, undelivered) by the caller's transport, and the two are never added together; a view query and a
call to another service are counted where they are made, since nothing hosts the callee. Every host counts
through one function on `Observability`. A remote host stamps its own caller on what it sends the process,
and protocol 1.3 carries metadata on a step, a tool call, a guardrail check, a result check and a view query
so a call made from any of them is attributed. A deployed service's topology is read by the control plane
over port 7628 `observe` (`ObserveServer`, `InstanceTopologies`) and merged by `TopologyMerge`, which sums
pairs and recomputes percentiles from the instances' histograms.

## Telemetry leaves the instance through a module the runtime finds, not one a service names

`runtime` records every invocation with no library (`Recorder`), and `ankka-telemetry-otlp` is the only
place OpenTelemetry is (feature 026). It declares a `RuntimeExtensionProvider` in
`META-INF/services`, and `ServiceBuilder` appends what declared providers return after the service's own
extensions: the one extension a service does not hand over, so that "services opt into nothing" holds
for an embedded service whose `Main` is the developer's. Components are still only ever handed over.
With `ankka.telemetry.endpoint` empty the provider returns nothing and no exporter class loads.

What export needed of the recorder, and it has: a trace id in two halves (`traceIdsHigh`; 32 hex digits
in `ankka-trace-id`, 16 still read), span ids that start at a random number per recorder (two instances'
span 5 would collide in a collector), a kind, a wall-clock anchor (`epochNanos`), a parent no span has
for a call that carried no trace (`Recorder.UnknownCaller`, read back as a root with `callerUnknown`;
every host of a call begins through `Trace.inbound`), a read cursor (`Recorder.cursor`, which waits on
spans still in flight and counts what the ring overwrote first), and `InvocationTotals`, counted in
`complete` because a count since start cannot come from a ring that forgets. The cursor reads a slot
between `VarHandle` fences.

A trace context crosses services as W3C `traceparent` (`Traceparent`, the one parser and writer): read
by `HttpServer`'s `Tracing.request`, `grpc`'s `Binding`, and the four topic handlers; written by
`Observability.calling` for both service clients (a `Client` span under the calling handler's) and by
`ProjectionSupport.stamped` on every published message, single or several, in process and remote. Logs
are never exported: `TraceLogging`, a logback turbo filter, puts `trace_id` and `span_id` in the logging
context of a line about to be written, or clears them. The operator renders `ANKKA_OTLP_ENDPOINT` as a
literal on the runtime's container of every hosting but web, and `ANKKA_OTLP_HEADERS` by reference to
`<service>-telemetry`, a Secret it applies from a credential no action carries. The local overlay lists
`components/telemetry-store` (Grafana's single container plus a log agent); `components/otel-collector`,
which keeps nothing, is listed by no overlay and rendered by `kustomization/tests/otel-collector`.

## Traps

- **Local mode runs no management server, so observability needs its own local exposure.**
  `ClusterFormation` starts Pekko Management only in the `bootstrap` path, and says why: it binds a
  fixed port, which two services on one laptop would fight over. `VersionRoute` is therefore the
  obvious model for an observability endpoint and the wrong one — it does not exist where the local
  console needs it. Observability is exposed twice, chosen by where the process runs, exactly as
  formation is: a loopback endpoint on an ephemeral port locally (`ObservabilityEndpoint`, on the
  JDK's own HTTP server so `runtime` gains no dependency), and a `ManagementRouteProvider`
  (`ObservabilityRoute`) under Kubernetes. One recorder, two exposures.
- **A trace set on the caller's thread is invisible to the thread doing the work.** `dispatch`
  returns a `Future`; the handler runs later on its own virtual thread. Wrapping the *call to*
  `dispatch` in `Trace.within` compiles, runs, and produces an endpoint span and an entity span in
  two unrelated traces — a list, not a tree. The trace belongs where `RequestScope` already puts
  the request context: inside the `Future`, on the handler's thread. The general rule is the one
  `RequestContext` already states — work handed to another thread cannot see a thread-local — and
  tracing inherits it exactly. Where it genuinely cannot follow, the time shows as *unattributed*
  and an orphan span stays at the root marked unknown. **Never re-parent an orphan to the nearest
  plausible candidate**: a tree that reads correctly and describes something that did not happen is
  worse than a visible hole.
- **A refusal is not a failure, and the recorder has to be told which it was.** `effects.error(...)`
  returns a *value*, so a refused command reaches the caller looking exactly like a success — a
  `try`/`finally` around the handler records `Ok` for a working ACL. `interpret` returns the span
  outcome rather than leaving the caller to infer it from an exception that never comes. A console
  that paints a refusal red, or a fault green, teaches its reader to ignore the column.
- **Never intern anything unbounded into the recorder's name table.** Component and handler names
  are interned once and become `Int`s, which is what keeps a span allocation-free. That table is
  bounded *only* because registration is explicit and handler names are declared on companions.
  Entity ids, session ids and request paths with parameters filled in are not bounded, and interning
  one would grow the table for the life of the process.
- **A benchmark needs a denominator that is the thing the criterion names.** SC-003 asked for
  instrumentation within 5% of "a service's throughput". Measured against an empty loop recording
  cost 58%; against a jsoniter round-trip, 28%; against a testkit entity call, 50% *while measuring
  faster than the serializer alone*, which is the JIT folding a monomorphic loop. All three numbers
  were arithmetically true and answered a question nobody asked. Against a real service — HTTP in,
  entity, journal, reply — one invocation is 640µs and recording is 22ns, or 0.003%. A ratio that
  moves with the shape of the harness is measuring the harness.
- **An SVG with `role="img"` may not contain anything focusable.** axe reports `nested-interactive` for a
  topology picture whose nodes are buttons; the picture is a `group` with the same label.
- **A `var` that is assigned and never read is a lifecycle that never runs.** The local console
  endpoint was held in a `@volatile var` on the `Ankka` builder object; nothing read it, so
  `stop()` — and with it `ServiceRegistration.withdraw` — was unreachable, and every locally-run
  service left its entry in `~/.ankka/running` forever. Unit tests of `announce` and `withdraw`
  passed throughout: both were correct, and the defect was that one was never called. The console
  sweeps entries nothing answers for, so the only symptom was a directory quietly filling up. It
  belongs to `AnkkaService`, which is the thing that gets terminated — a singleton builder would
  keep only the most recently started endpoint anyway. `ServiceRegistrationSuite` drives the whole
  lifecycle for this reason; nothing narrower can catch a call that is never made.
- **Span ids must not start at the same number in every instance.** They were a counter from one, unique
  in one ring and colliding everywhere else: two services' spans in one trace both had a span 5, and a
  parent id named the wrong one. The counter starts at a random number now, and two tests that had read
  "after" as `spanId > recorder.recorded` were asserting the old numbering — one passed by luck, since a
  random start is negative half the time.
- **A message is published after its handler's span has closed and its thread has moved on.**
  `ProjectionSupport.handling` restores the thread-local before the effect is applied, so the publish can
  read no trace; the span's context is handed out (`traced`) and stamped. And a single `Produce` never
  passes through `publishAll`: a rule about every published message that lives only there misses the
  commonest case (the Python SDK sends a one-message batch as `produce`).
- **The OpenTelemetry SDK logs through `java.util.logging`, once a batch, and nothing here bridges it.**
  An outage would print `SEVERE: Failed to export` to stderr every second. `OtlpTelemetry` holds the two
  loggers at `OFF` — held, because JUL keeps loggers weakly and forgets a level set on a collected one —
  and `Outage` says it once. The exporters also retry on their own by default; the module builds them
  with `setRetryPolicy(null)` so a failed batch is one try and back-off is the loop's.
- **`opentelemetry-proto` needs protobuf-java 4**, and a test classpath holding ScalaPB code generated
  against 3 cannot take it. The fake collector reads OTLP's wire format by field number (`Protobuf` in
  `FakeCollector.scala`).
- **A logging event reads its MDC lazily, the first time it is asked.** A `ListAppender` in a test that
  inspects an event after the next line was written sees the next line's context; a console appender asks
  at once and an async one snapshots first. A test appender calls `prepareForDeferredProcessing` on append.
- **`$` in the template's `logback.xml` is `\$`.** The log pattern's `%replace` ends in a regex anchor, and
  Giter8 deletes an unescaped `$`.
- **A log line stamped outside the window a Loki query asks for is not returned**, and reads exactly like a
  line never sent. `TelemetryStoreSuite` writes its pod log lines with the current time.
- **`otel/opentelemetry-collector-contrib` lags the core image's tags on Docker Hub.** 0.162.0 existed for
  the core collector and not for contrib; both are pinned at 0.161.0 so the two collectors match.
