# Runtime endpoints

> The ports and HTTP endpoints every running ankka service exposes besides its own routes — readiness, version and metrics on a deployed instance, and the loopback observability endpoint a local one serves the console.

Source: https://docs.ankka.cloud/reference/runtime-endpoints/
Besides the routes a service declares, the runtime serves a few endpoints of its own, for the platform and
for the people operating it. Which ones exist depends on where the service runs. A deployed instance runs a
management server beside its HTTP server. A service run on a laptop runs no management server, because it
binds a fixed port that two services on one machine would fight over, and serves a loopback endpoint for
the local console instead.

## Ports on a deployed instance

| Port | Name | Transport | What it serves |
|---|---|---|---|
| The descriptor's `port`, `9000` by default | `http` | mutual TLS | The service's own routes. Absent with `"http": false`. |
| `7626` | `management` | mutual TLS | Version, metrics, cluster membership and cluster bootstrap. |
| `7627` | `probe` | plain HTTP | Readiness, and nothing else. |
| `7628` | `observe` | mutual TLS | The service's description and its topology, for the control plane alone. |
| `17355` | `remoting` | mutual TLS | Cluster remoting between the service's instances. |

Management and remoting accept a connection only from another instance of the same service, presenting
the service's cluster certificate; a network policy refuses every other source before the handshake. The
HTTP port requires a client certificate from the installation's service authority. The probe port is plain
because the kubelet holds no certificate, and it answers only `GET /ready`. See
[Networking and TLS](../platform/networking.md).

The observe port admits exactly one peer, the installation's control plane, which presents the identity
`ankka://platform/controlplane`; any other certificate, the service's own included, fails the handshake. It
serves `GET /observability/service` and `GET /observability/topology`, the same documents the local
endpoint serves, and nothing else. It is not part of readiness: an instance whose observe listener could not
start is still ready, and the control plane reports it as one whose topology could not be read.

In a process-hosted service these ports belong to the sidecar container; the process container has none.

## Management endpoints

### `GET /ready`

The readiness probe. It answers `200` only when the instance is a member of its service's cluster and every
part of the runtime with an opinion agrees, which includes the HTTP server having bound its port. Until then
it answers with an error status. The platform's readiness probe calls it on the port named `probe`, and
a service is `Ready` only when all its instances pass. Management answers the same path with the same checks
to a caller holding the service's certificate.

Readiness does not call your routes, so it says the instance can be routed to, not that your application is
healthy. There is deliberately no liveness probe: entities rebuild from their journal, so restarting an
instance for a slow garbage collection would cost more than the pause.

### `GET /alive`

Pekko Management's liveness check, answering `200` while the node is running. The platform does not probe
it.

### `GET /ankka/version`

The ankka runtime version the image carries:

```json
{"version":"0.2.0"}
```

A descriptor's `runtime` field is a declaration the platform checks before starting anything; this endpoint
is the measurement to compare it with.

### `GET /ankka/metrics`

Metrics in the Prometheus text exposition format, from the same in-memory span window the local console
reads:

```text
# HELP ankka_invocations_total Component invocations recorded.
# TYPE ankka_invocations_total counter
ankka_invocations_total{component="shopping-cart",handler="add-item",outcome="Ok"} 42
# HELP ankka_invocation_duration_seconds_sum Time spent in component handlers.
# TYPE ankka_invocation_duration_seconds_sum counter
ankka_invocation_duration_seconds_sum{component="shopping-cart",handler="add-item"} 0.0756
# HELP ankka_recorder_spans_recorded_total Spans begun since this process started.
# TYPE ankka_recorder_spans_recorded_total counter
ankka_recorder_spans_recorded_total 1893
# HELP ankka_recorder_capacity Size of the in-memory span window.
# TYPE ankka_recorder_capacity gauge
ankka_recorder_capacity 4096
```

| Series | Meaning |
|---|---|
| `ankka_invocations_total` | Invocations in the current window, by component, handler and outcome. |
| `ankka_invocation_duration_seconds_sum` | Total handler time in the current window, by component and handler. |
| `ankka_recorder_spans_recorded_total` | Spans recorded since the process started. |
| `ankka_recorder_capacity` | The size of the window, `ankka.observability.ring-capacity`. |

`outcome` is `Ok`, `Refused` for a request a handler refused on purpose, `Failed` for a fault, or `TimedOut`.

The first two series describe a bounded recent window, not totals since the process started: as the window
overwrites its oldest spans, their counts drop. Do not compute rates from them across scrapes. A service
that has served nothing answers with zeroed series rather than an empty body. The platform ships no
dashboards or alerts; an installation brings its own monitoring.

### Cluster membership

Pekko Management's cluster routes are also served, including `GET /cluster/members`, which lists the nodes
of the service's cluster and their status.

## The local observability endpoint

A service run outside Kubernetes starts a small HTTP server on the loopback address and an ephemeral port,
and announces it by writing a file to `~/.ankka/running/<pid>.json`:

```json
{"name":"shopping-cart","instanceId":"48213","pid":48213,"observabilityAddress":"http://127.0.0.1:53117","startedAt":"2026-09-24T09:12:03Z"}
```

`ankka local console` lists that directory to find every service running on the machine, in whatever order
they were started. The file is removed when the service stops; a file left by a killed process is ignored by
the console once nothing answers at its address. The system property `-Dankka.running.dir` moves the
directory.

The endpoint is for the console. Loopback is its only access control, and it shows entity state and agent
memory, so it is never started on a deployed instance.

| Path | Answers |
|---|---|
| `GET /observability/service` | The service's name, runtime version, instances with their HTTP address, registered components with their query handlers, and HTTP routes. |
| `GET /observability/topology` | The service's topology: its nodes, declared connections and the calls observed in the current window, with each pair of handlers' handled and unanswered counts and a duration histogram. |
| `GET /observability/traces` | Summaries of the traces in the current window, newest first. |
| `GET /observability/traces/{traceId}` | One trace as a tree of spans. |
| `GET /observability/sessions/{sessionId}` | An agent session's stored conversation and the tokens it has used. |
| `GET /observability/query/{component}/{id}/{method}` | Runs one of a component's query handlers against an entity id, and answers with its reply. |

`/observability/query` runs only handlers declared with `query`, which cannot persist. Naming a command
answers `405` with `'<method>' is a command, not a query; the console only runs queries`, and an unknown
handler answers `404`.

If the endpoint cannot start, the service starts anyway and simply cannot be browsed.
