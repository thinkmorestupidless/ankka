# Contract: what a collector receives

OTLP over HTTP, protobuf, to `<address>/v1/traces` and `<address>/v1/metrics`. Nothing is sent
to `/v1/logs`. Decisions are in [research.md](../research.md) (R2, R5, R15–R17, R20).

## Resource

On every span and every metric of an instance:

| Attribute | Example |
|---|---|
| `service.name` | `orders` |
| `service.namespace` | `shop` |
| `ankka.project` | `shop` |
| `service.instance.id` | `orders-6c9d7b8f5-x2k4q` |
| `ankka.runtime.version` | `0.10.0` |

Scope: name `ankka`, version the runtime's.

## Span

| OTLP field | Value |
|---|---|
| `trace_id` | 16 bytes: the high half, then the low |
| `span_id` | 8 bytes |
| `parent_span_id` | the recorded parent; empty for a root and for a span with an unknown caller |
| `name` | `<component> <handler>`: `cart add-item`, `http POST /carts/{cartId}/items`, `payments POST` |
| `kind` | `SERVER` for an endpoint's span, `CLIENT` for a call to another service, `CONSUMER` for a topic message's span, `INTERNAL` otherwise |
| `start_time_unix_nano`, `end_time_unix_nano` | from the instance's clock anchor and the span's duration |
| `status` | `ERROR` when the outcome is `failed` or `timed_out`; unset otherwise |
| `flags` | sampled |

Attributes:

| Attribute | Value |
|---|---|
| `ankka.component` | the component, or `http`, `grpc`, or the called service |
| `ankka.handler` | the handler, the route, the gRPC method, or the HTTP method of a call |
| `ankka.outcome` | `ok`, `refused`, `failed`, `timed_out` |
| `ankka.caller` | `unknown`, present only on a span whose caller the service could not tell |

A refusal is `ankka.outcome = refused` with the status unset: it is never an error.

A span is exported once, when it is complete. A trace is not waited for: its spans arrive as
each ends, from each instance that recorded one.

## Metrics

Cumulative since the instance started. Sent every `ankka.telemetry.metric-interval`.

| Name | Type | Unit | Attributes |
|---|---|---|---|
| `ankka.invocations` | sum, monotonic, integer | `{invocation}` | `ankka.component`, `ankka.handler`, `ankka.outcome` |
| `ankka.invocation.duration` | sum, monotonic, floating point | `s` | `ankka.component`, `ankka.handler` |
| `ankka.telemetry.lost_spans` | sum, monotonic, integer | `{span}` | none |

A pair of component and handler beyond the instance's limit is counted under
`ankka.component = (other)`, `ankka.handler = (other)`.

## Sending

| | |
|---|---|
| Address | `ANKKA_OTLP_ENDPOINT`: `http://host:4318` or `https://host[:port]`, with or without a trailing slash |
| Headers | `ANKKA_OTLP_HEADERS`, `name=value,name=value`, on every request |
| Trust | `https` against the JVM's trust store; no client certificate |
| Batch | up to `batch-size` spans a request |
| Rhythm | every `interval`, and sooner when unread spans pass half the trace window |
| On failure | the batch is not given up: it is read again from the trace window after a wait that doubles to `max-backoff`. What the window overwrote meanwhile is lost and counted |
| On stopping | one more read and send, within `shutdown-timeout` |

## What the service says about exporting

| When | Line |
|---|---|
| the first failure of an outage | one `WARN`: the collector's address and the reason |
| every later failure of that outage | nothing |
| the first success after an outage | one `INFO`: how long it lasted and how many spans were lost |
| a malformed `ANKKA_OTLP_HEADERS` or address at start | one `ERROR` naming which, and the exporter does not start; the service does |

No line ever contains a header's value.

## What is not carried

- `tracestate` is neither read nor sent.
- Baggage is not propagated.
- A journal carries no context: a consumer or view reading an entity's events or state starts a
  trace of its own.
- A process's or a module's own telemetry: the platform exports what its own program recorded.
