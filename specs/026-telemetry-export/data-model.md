# Data Model: Telemetry Export

What is held, where, and for how long. Nothing here is stored: every value is in an instance's
memory or in a Kubernetes object the installation or the operator writes. No table, no event and
no field of the `AnkkaService` resource is added. Decisions are in [research.md](research.md).

## Span (a slot of an instance's trace window)

`Recorder`'s ring, one primitive array per field. New fields are marked.

| Field | Type | Notes |
|---|---|---|
| `traceIdsHigh` | `Long` | **new**: the high half of the trace id |
| `traceIds` | `Long` | the low half; what the consoles key a trace by |
| `spanIds` | `Long` | from a counter that now starts at a random number, and never yields `0` or the sentinel |
| `parentIds` | `Long` | `0` for a root; `Recorder.UnknownCaller` for a call that carried no trace (**new** value) |
| `componentRef`, `handlerRef` | `Int` | interned names, as now |
| `kinds` | `Byte` | **new**: `Internal`, `Server`, `Client`, `Consumer` |
| `startedNanos` | `Long` | `System.nanoTime()`, as now |
| `durations` | `Long` | `-1` while in flight |
| `outcomes` | `Byte` | `Ok`, `Failed`, `Refused`, `TimedOut`, as now |
| `sequences` | `Long` | `0` while in flight; published last |

Beside the ring, set once when the recorder is made (**new**): the wall-clock time and the
`nanoTime` it corresponds to, and the span id counter's random start.

**Read back** (`RecordedSpan`): the fields above, with `parentSpanId = 0` and
`callerUnknown = true` where the ring holds the sentinel, so every existing reader sees a root.

**The handle** (`Span`, between `begin` and `complete`) gains `traceIdHigh`, `componentRef`,
`handlerRef` and `startedNanos`, so that a span whose slot was reused before it completed is still
counted in the totals.

**Lifecycle**: begun (in flight) → complete (readable) → overwritten. A span overwritten before a
cursor read it is a *lost span*.

## Trace context (a value, never stored)

`TraceContext(traceIdHigh: Long, traceId: Long, spanId: Long)`.

| Where it travels | Form |
|---|---|
| between components of one service, in a call's metadata | `ankka-trace-id` (32 hex digits; 1 to 32 are read) and `ankka-span-id` (hex), as now but longer |
| on an HTTP request between services, or from outside | the header `traceparent`: `00-<32 hex>-<16 hex>-01` |
| on a gRPC call between services, or from outside | the metadata key `traceparent`, same value |
| on a message published to a topic | the message's metadata entry `traceparent`, which Kafka carries as a record header |
| on a thread | `Trace`'s thread-local, as now, with the high half |
| on a log line | the MDC keys `trace_id` (32 hex) and `span_id` (16 hex), set when the line is written |

Rules: a context is valid when its version is not `ff`, both ids are lower-case hex of the right
length, and neither is all zeros. An invalid one is no context. The platform's value replaces one
a handler put on a call or a message.

## Export cursor (per exporter, in memory)

| Field | Notes |
|---|---|
| position | the highest sequence the cursor has passed |
| pending | sequences passed while still in flight; at most the ring's capacity |
| lost | how many spans left the ring unread, since the cursor was made |

`read(max)` returns a batch (complete spans, oldest first; the new position; the new pending set;
the lost count) and changes nothing. `commit(batch)` adopts it. A batch never committed is read
again, less whatever the ring has overwritten since, which is added to lost.

## Invocation totals (per instance, in memory, since it started)

A table of at most `ankka.observability.max-counted-handlers` (1024) entries, keyed by
(`componentRef`, `handlerRef`), plus one overflow entry.

| Per entry | Type |
|---|---|
| count of `Ok`, `Failed`, `Refused`, `TimedOut` | four `Long`s |
| sum of durations, in nanoseconds | `Long` |

Written by `Recorder.complete`, for every span. Read by the metric callbacks. Never reset.

## Admitted names (per instance, in memory)

The names of other services and of their gRPC methods that a `Client` span is recorded under.
Services are admitted by `ExternalServices`, which exists (`ankka.observability.max-external-services`,
32, then `(other services)`). gRPC methods are admitted by a second limit of the same shape
(`ankka.observability.max-external-methods`, 256, then `(other methods)`). An HTTP call's handler
is its method (`GET`, `POST`, …), never its path.

## Telemetry settings

| Setting | Installation (kustomize) | Operator (`Settings`) | A workload's platform program | Module (`ankka.telemetry.*`) |
|---|---|---|---|---|
| the collector's address | `ankka-platform` ConfigMap, `otlpEndpoint`; empty means none | `otlpEndpoint: Option[String]`, from `ANKKA_OTLP_ENDPOINT` | `ANKKA_OTLP_ENDPOINT`, a literal, rendered only when set | `endpoint` |
| what to send with the telemetry | a Secret `ankka-telemetry`, key `headers`, in `ankka-operator` and in `ankka-controlplane`; optional | `otlpHeaders: Option[String]`, from `ANKKA_OTLP_HEADERS` | `ANKKA_OTLP_HEADERS`, from the Secret below, rendered only when set | `headers` |

Both names are in `PlatformVariables.PlatformOnly`: a descriptor that gives either is refused, a
process is given neither, and a module that asks for either is told it is not set.

**The headers' form**: `name=value,name=value`. Names and values are trimmed; an entry without
`=` is a startup failure of the exporter naming the entry's position, never its content.

## Telemetry Secret (one per service, when the installation has headers)

| | |
|---|---|
| Name | `<service>-telemetry`, in the project's namespace |
| Type | `Opaque` |
| Data | one entry, `headers` |
| Labels | the service's identity labels |
| Owner reference | the service's `AnkkaService`: deleted with it |
| Written | by the operator, by server-side apply, on every reconcile; never read |

`-telemetry` joins `ServiceSpec.PlatformSecretSuffixes`, so a descriptor's `secretKeyRef` may not
name it and a project secret may not take the name.

## Telemetry store (a local platform only)

Nothing of the platform's own is stored in it: it is Grafana's development container, holding
what it was sent in its own files, with no volume. It is lost on restart.

| It holds | Sent by | Found by |
|---|---|---|
| spans | each instance's exporter, over OTLP | trace id; `service.name`, `ankka.project` |
| metrics | each instance's exporter, over OTLP | the metric's name; the same resource |
| log records | the log agent, from each node's pod log files | `service.name` (the container), `ankka.project` (the namespace less its prefix); a record's own trace id and span id, lifted from the end of the line |

The mapping of a log line to a record is in [contracts/operator.md](contracts/operator.md).

## Resource (what every span and metric of an instance says it is from)

| Attribute | Value | From |
|---|---|---|
| `service.name` | the service | the certificate's `ankka://<project>/<service>`, else `ankka.telemetry.service-name`, else the actor system's name |
| `service.namespace`, `ankka.project` | the project | the certificate, else `ankka.telemetry.project`, else absent |
| `service.instance.id` | the host name (a pod's name) | the JVM |
| `ankka.runtime.version` | the platform's version | `BuildInfo.version` |

## Module configuration (`modules/telemetry-otlp/src/main/resources/reference.conf`)

| Key | Default | Meaning |
|---|---|---|
| `ankka.telemetry.endpoint` | `""`, then `${?ANKKA_OTLP_ENDPOINT}` | the collector's address; empty exports nothing and starts nothing |
| `ankka.telemetry.headers` | `""`, then `${?ANKKA_OTLP_HEADERS}` | sent with every export |
| `ankka.telemetry.interval` | `1s` | how often spans are read and sent |
| `ankka.telemetry.batch-size` | `512` | spans in one export |
| `ankka.telemetry.export-timeout` | `5s` | how long one export may take |
| `ankka.telemetry.max-backoff` | `30s` | the longest wait between tries while the collector cannot be reached |
| `ankka.telemetry.metric-interval` | `10s` | how often metrics are sent |
| `ankka.telemetry.shutdown-timeout` | `3s` | how long a stopping instance spends exporting |
| `ankka.telemetry.service-name`, `ankka.telemetry.project` | `""` | who the instance says it is, where it has no certificate |

And in `runtime`'s `reference.conf`: `ankka.observability.max-counted-handlers = 1024`,
`ankka.observability.max-external-methods = 256`.
