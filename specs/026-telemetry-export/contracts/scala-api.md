# Contract: what `runtime`, `http` and `grpc` gain

The types and functions other code is written against. Decisions are in
[research.md](../research.md) (R1, R3–R12, R14). Nothing here adds a dependency to a published
module other than the new one.

## The provider (`runtime`)

```scala
/** A platform extension a module offers by being on the classpath. */
trait RuntimeExtensionProvider:
  /** The extension to run for this configuration, or none. Called once, before the service starts. */
  def extension(config: Config): Option[RuntimeExtension]
```

- Declared in `META-INF/services/com.thinkmorestupidless.ankka.runtime.RuntimeExtensionProvider`.
- `ServiceBuilder` loads providers in `start` and `startWith`, and appends their extensions after
  the service's own, so they start last and stop first.
- A provider that throws fails the start, naming the provider. A provider that returns `None`
  costs nothing after that call.
- `AnkkaTestKit` goes through the same builder, so a test of a service has what the service has.

## Trace identity (`runtime`)

```scala
final case class TraceContext(traceIdHigh: Long, traceId: Long, spanId: Long):
  def traceIdHex: String   // 32 lower-case hex digits
  def spanIdHex: String    // 16

object Traceparent:
  val Name = "traceparent"
  def parse(value: String): Option[TraceContext]
  def render(context: TraceContext): String      // 00-<32>-<16>-01

object Trace:
  def mint(): (Long, Long)                                    // high, low; never both zero
  def currentContext: Option[TraceContext]                    // new
  def currentTrace: Option[(Long, Long)]                      // unchanged: low half and span
  def into(metadata: Metadata, context: TraceContext): Metadata
  def inbound(metadata: Metadata): Inbound                    // replaces traceIdOf / parentSpanIdOf
  final case class Inbound(traceIdHigh: Long, traceId: Long, parentSpanId: Long)
```

`inbound` is for a host answering a *call*. It returns what the call carries; for a call that
carries no trace it returns a fresh trace with the parent `Recorder.UnknownCaller`, unless the
call is the console's own, which is a root. Entry points (an endpoint, a projection, a timer) do
not use it: they mint, or continue a `traceparent`.

## The recorder (`runtime`)

```scala
enum SpanKind:
  case Internal, Server, Client, Consumer

final class Recorder(val capacity: Int):
  def begin(traceIdHigh: Long, traceId: Long, parentSpanId: Long,
            componentRef: Int, handlerRef: Int, kind: SpanKind = SpanKind.Internal): Span
  def complete(span: Span, outcome: SpanOutcome): Unit        // also counts into totals
  def snapshot(): Vector[RecordedSpan]                        // unchanged in what it returns
  def epochNanos(startedNanos: Long): Long                    // new
  def totals: InvocationTotals                                // new
  def cursor(): Recorder.Cursor                               // new

object Recorder:
  val UnknownCaller: Long                                     // a parent id no span has
  final class Cursor:
    def read(max: Int): Batch
    def commit(batch: Batch): Unit
    def lost: Long
    def unread: Long                                          // recorded minus position; one read
  final class Batch(val spans: Vector[RecordedSpan], val lost: Long, /* position, pending */)
```

`RecordedSpan` gains `traceIdHigh`, `kind` and `callerUnknown`; its `parentSpanId` is `0` where
the caller is unknown.

Guarantees: `begin` and `complete` allocate nothing they do not allocate today; a cursor never
returns a span it read while the slot was being written; `lost` counts every sequence the cursor
never returned.

## Calls to other services (`runtime`)

```scala
// on Observability
private[ankka] def calling[A](service: String, method: String)
    (outcomeOf: A => SpanOutcome)(body: String => A): A
```

Begins a `Client` span named for `service` and `method` under the thread's span, or as a root
with an unknown caller when the thread has none; gives `body` the `traceparent` to send, which is
the span's own context; completes the span with `outcomeOf` of the result, or `Failed`
if `body` throws and `TimedOut` if it throws a timeout. `service` and `method` are admitted names
(data-model, *Admitted names*).

Used by `HttpServiceClients` and by `GrpcClients`' interceptor, and by nothing else that calls a
service: a client added later records through it.

## What reads and writes a context

| Place | Reads | Writes |
|---|---|---|
| `HttpServer` (`Tracing.request`) | the request's `traceparent` header | — |
| `HttpServiceClients` | — | `traceparent` on every request, replacing the caller's |
| `grpc` `Binding` | the call's `traceparent` metadata key | — |
| `GrpcClients` | — | `traceparent` on every call |
| `ViewTopicHandler`, `ConsumerTopicHandler`, `RemoteViewTopicHandler`, `RemoteConsumerTopicHandler` | the message's `traceparent` metadata entry | — |
| `ProjectionSupport.stamped`, called for `Produce` and each message of `ProduceAll`, in process and remote | — | `traceparent` on every published message, replacing the handler's |
| the web-hosting proxy | — | — : it passes the header on as it was given |

A consumer or view reading an entity's events or state reads no context and starts a trace.

## Logs (`runtime`)

`TraceLogging.install()`: adds one turbo filter to logback's logger context, once; does nothing
when the logging backend is not logback. For a call that will be written it sets the MDC keys
`trace_id` and `span_id` from `Trace.currentContext`, or removes them when there is none.

The platform's own configurations end a line with the ids when it has them:

```text
12:00:01.123 INFO  c.t.a.cart.Cart - item added trace_id=4bf92f3577b34da6a3ce929d0e0e4736 span_id=00f067aa0ba902b7
12:00:01.200 INFO  c.t.a.runtime.Ankka - ankka shopping-cart service started
```

## The module (`ankka-telemetry-otlp`)

A service's build names it; no code does.

```scala
libraryDependencies += "com.thinkmorestupidless" %% "ankka-telemetry-otlp" % ankkaVersion
```

Its one public type is the provider. Its configuration is `ankka.telemetry.*` (data-model). With
no address configured it starts no thread, opens no connection and loads no exporter class.
