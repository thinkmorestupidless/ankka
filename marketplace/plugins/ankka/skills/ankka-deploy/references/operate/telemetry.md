# Telemetry

> Send every service's traces and metrics to an OpenTelemetry collector the installation names once, read one request across services as one trace, join logs to traces, and open a local platform's telemetry store.

Source: https://docs.ankka.cloud/operate/telemetry/
An installation names one OpenTelemetry collector, and every service deployed to it exports its
**spans** and **metrics** there over OTLP. A service's code says nothing about telemetry, and a
service written in Python, TypeScript or Rust exports exactly as a Scala one does, because the
platform's own program beside it does the exporting.

Logs are not exported. A service's logs stay on standard output, where the installation's own log
agent gathers them, and every line a handler writes ends with the trace it belongs to, so a log store
can join it to the trace.

## Where telemetry goes

| Installation | Where | What keeps it |
|---|---|---|
| A local platform | the **telemetry store**, at `https://grafana.<base domain>` | Grafana, Tempo, a Prometheus-compatible store and Loki, in one container |
| Any other | the collector the installation names | whatever the installation runs behind it |

### On a local platform

The local overlay installs a telemetry store and points every service at it. Open
`https://grafana.127.0.0.1.sslip.io:8443` in a browser that trusts `~/.ankka/local-ca.crt` and sign
in as `admin` / `admin`. Traces are under Tempo, metrics under Prometheus and logs under Loki; a log
line that names a trace links to it.

The store is for a developer's machine. What it holds is gone when it restarts, its sign-in is the
image's own, and an installation that is not a local platform never renders it. An agent on each node
reads every pod's log files and sends them to the store, naming each line's service by its container
and its project by its namespace.

### On any other installation

Set the collector's address on the `ankka-platform` ConfigMap your overlay applies:

```yaml
data:
  otlpEndpoint: https://otel-collector.observability.example.com:4318
```

The address is `http://host:4318` or `https://host[:port]`; empty exports nothing. An `https` address
is verified against the JVM's trust store, and no client certificate is presented.

When the collector wants a credential, create a Secret named `ankka-telemetry` with the key `headers`
in both `ankka-operator` and `ankka-controlplane`, holding the headers to send as `name=value` pairs
separated by commas:

```bash
for ns in ankka-operator ankka-controlplane; do
  kubectl -n "$ns" create secret generic ankka-telemetry \
    --from-literal=headers='authorization=Bearer <token>'
done
```

The operator copies the headers into a Secret of each service's own, `<service>-telemetry`, which the
service reads by reference. They are never written onto a Deployment, into an action the operator logs,
or into any log line.

| Change | What happens to running services |
|---|---|
| The address is set, changed or removed | Every service rolls once, by its ordinary rolling update |
| The headers change | Each service's Secret changes; a service sends the new headers when it next starts |
| The collector cannot be reached | Nothing: see [When the collector cannot be reached](#when-the-collector-cannot-be-reached) |

A descriptor cannot set `ANKKA_OTLP_ENDPOINT` or `ANKKA_OTLP_HEADERS`; an apply that tries is refused,
naming the variable. A process is not given them, and a WebAssembly module that asks for either is told
it is not set. A web-hosted service exports nothing.

The platform also ships `kustomization/components/otel-collector`, the least there is to send to: the
core collector, printing what it receives to its own log and keeping nothing. No overlay lists it; an
installation that wants it lists it in place of a collector of its own.

### On a developer's machine without a cluster

A service run with `sbt run` or `docker compose up` exports when `ANKKA_OTLP_ENDPOINT` is set in its
environment, and not otherwise. A Scala service needs the exporter on its classpath, which a project
made by `ankka init` already has:

```scala
libraryDependencies += "com.thinkmorestupidless" %% "ankka-telemetry-otlp" % ankkaVersion
```

With no address the module starts nothing: no thread, no connection.

## One request is one trace

A call from one service to another carries the trace it belongs to, and the service that is called
continues it, so one request that crosses several services is one trace in the collector:

| How one service reaches another | What carries the trace |
|---|---|
| an HTTP call through the service client | the `traceparent` header |
| a gRPC call through the gRPC client | the `traceparent` metadata key |
| a message published to a topic | the message's `traceparent` metadata, which Kafka carries as a record header |

A request from outside the cluster that carries a `traceparent` header continues that trace, so an
installation's edge proxy can be a trace's root; one that carries none, or one that cannot be read,
starts a new trace. A call to another service is a span of its own, under the calling handler's, and
the called service's span is under it. A web-hosted service's proxy passes `traceparent` on as it was
given and adds no span of its own.

A consumer or view reading an entity's events or state, inside one service, starts a trace of its own:
an event carries no trace. A message read from a topic continues the trace of the consumer that
published it, however long after; a message delivered twice is two spans under the same parent.
`tracestate` is neither read nor written.

## What a span says

| Field | Value |
|---|---|
| name | the component and the handler: `cart add-item`, `http GET /carts/{cartId}`, `service:shop/payments GET` |
| kind | `SERVER` for an endpoint, `CLIENT` for a call to another service, `CONSUMER` for a topic's message, `INTERNAL` otherwise |
| status | `ERROR` when the handler failed or timed out; unset otherwise |
| `ankka.component`, `ankka.handler` | the component and the handler |
| `ankka.outcome` | `ok`, `refused`, `failed` or `timed_out` |
| `ankka.caller` | `unknown`, only on a span whose caller the service could not tell |

A refusal is the service saying no, as it should: it is exported as `refused` with no error status, so
a collector's error rate counts only faults. A call made outside any handler is exported with no
parent and marked `ankka.caller = unknown`; it is never given a parent by guessing.

Every span and metric says where it came from:

| Resource attribute | Value |
|---|---|
| `service.name` | the service |
| `service.namespace`, `ankka.project` | the project |
| `service.instance.id` | the instance: a pod's name, in a cluster |
| `ankka.runtime.version` | the platform's version |

In a cluster the service and project are read from the instance's own certificate. Elsewhere they are
`ankka.telemetry.service-name` and `ankka.telemetry.project`, which default to the actor system's name
and to none.

## Metrics

| Metric | Unit | Attributes |
|---|---|---|
| `ankka.invocations` | invocations | `ankka.component`, `ankka.handler`, `ankka.outcome` |
| `ankka.invocation.duration` | seconds | `ankka.component`, `ankka.handler` |
| `ankka.telemetry.lost_spans` | spans | none |

All three are cumulative sums since the instance started, so a collector that misses an export loses
nothing. A Prometheus-compatible store names the first `ankka_invocations_total`.

## Spans the trace window loses

An instance keeps its most recent spans in a fixed **trace window**, 4096 by default, and the exporter
reads it once a second, sooner when it is half full. A span the window overwrites before it is read is
**lost**: it is never exported, and `ankka.telemetry.lost_spans` counts it. A trace whose spans were
partly lost arrives partial. Raising `ankka.observability.ring-capacity` keeps more; nothing is sampled.

## When the collector cannot be reached

A service handles its requests exactly as before. The exporter keeps trying, waiting longer each time
up to 30 seconds, and says so once in the service's log:

```text
WARN  OtlpTelemetry - telemetry: the collector at https://collector:4318 cannot be reached (...)
INFO  OtlpTelemetry - telemetry: the collector at https://collector:4318 can be reached again after 312 s; 5120 spans were lost meanwhile.
```

When it can be reached again, it is sent what the trace window still holds; what the window
overwrote meanwhile is lost and counted. A stopping instance sends what it holds within three seconds,
whether or not the collector answers.

## Logs and traces

A line a handler writes carries `trace_id` and `span_id` in its logging context, and the platform's own
programs end the line with them:

```text
12:00:01.123 INFO  c.t.a.cart.Cart - an item was added trace_id=4bf92f3577b34da6a3ce929d0e0e4736 span_id=00f067aa0ba902b7
12:00:01.200 INFO  c.t.a.runtime.Ankka - ankka shopping-cart service started
```

A line written outside any handler carries neither and ends as it always did. A Scala service's own
`logback.xml` decides its pattern; a project made by `ankka init` already ends a line with the ids, and
[Logs](logs.md) shows what to add to one that does not. A Python or TypeScript process's own lines are
its own and carry no ids.
