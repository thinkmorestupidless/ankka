# Contract: the sidecar

**Feature**: [spec.md](../spec.md) | **Research**: R2, R7, R8, R11

The sidecar is `ankka-runtime` with a `main` that boots from discovery. It is published as the
image `ankka-sidecar:<version>` beside `ankka-operator` and `ankka-controlplane`, built by
`sbt docker:publishLocal`, loaded by `deploy-local.sh`, and pushed on a release tag with the other
two. It is never a library.

## Environment

| variable | default | who sets it |
|---|---|---|
| `ANKKA_PROCESS_ADDRESS` | `127.0.0.1:9010` | the operator; compose; the TypeScript testkit |
| `ANKKA_SIDECAR_PORT` | `9011` | the operator (always the default); bound to `127.0.0.1` only |
| `ANKKA_HTTP_PORT` | `9000` | the operator, as for any service |
| `ANKKA_CLUSTER_MODE` and the four Kubernetes formation variables | | the operator, exactly as today |
| `ANKKA_DB_*` | | the operator via `envFrom` the credential secret — on this container only |
| `ANTHROPIC_API_KEY` and any model configuration | | the descriptor's `env`, which the operator places on the **sidecar** container when `hosting = process` (the process never calls a model) |
| `ANKKA_SIDECAR_DISCOVERY_TIMEOUT` | `60s` per attempt, retried forever with backoff | |

A descriptor's `env` is split by the operator: `ANTHROPIC_*` and `ANKKA_MODEL_*` land on the
sidecar; everything else lands on the app container. The split is a fixed prefix list in
`Rendering`, documented in `descriptor-and-crd.md`.

## Startup

1. Load configuration through `ClusterConfig.load` as any service does.
2. Dial `ANKKA_PROCESS_ADDRESS`, call `Discovery.Discover` with the sidecar's protocol and
   runtime versions; retry with backoff until it answers. Log each failed attempt at `info`.
3. Validate the `Spec` (data-model.md). On refusal: `ReportError`, log every problem, exit 1.
4. Build remote descriptors and agent descriptors; `Ankka.service.registerAll(...)` with the
   extensions `ProjectionRuntime`, `TimerRuntime`, `AgentRuntime` (with the configured providers),
   `HttpServer.at(interface, ANKKA_HTTP_PORT)` carrying `InvokeEndpoint`, and `SidecarExtension`.
5. `start()`. Cluster formation, observability, the management endpoint and readiness are the
   runtime's own.

## Readiness

`/ready` on the management port is, as today, cluster membership AND every extension with an
opinion. `SidecarExtension.readiness` is `discovered && reachable`, where `reachable` is a health
ping to the process at most once per second. A process that stops answering makes the pod
un-ready within two seconds while the sidecar keeps its cluster membership and its shards
(FR-013). When the process is back, open conversations have been lost, and each affected instance
restarts on its next command and replays.

## The generic invoke route

Served by `HttpServer` on the sidecar's HTTP port, prefix `/_ankka/components`, `Acl.AllowAll`.

| method | path | body | reply |
|---|---|---|---|
| `POST` | `/{kind}/{componentId}/{entityId}/{handler}` | the payload (`Content-Type` becomes `Payload.content_type`) | the reply payload with its content type; `HttpProblem.from(error)` on a refusal |
| `GET` | `/{kind}/{componentId}/{entityId}/{handler}` | none (empty payload) | only for handlers discovered `read_only`; 405 otherwise |
| `GET` | `/agent/{componentId}/{sessionId}/{handler}?input=…` (SSE) | | for handlers discovered `streaming`; frames JSON-encoded as today |
| `POST` | `/view/{componentId}/{query}` | the query payload | rows |

`kind` is one of `event-sourced`, `key-value`, `workflow`, `agent`, `timed-action`. An unknown
component or handler is 404 with the same message the sharded host gives. The route is listed by
`SidecarExtension.routes`, so the local console's invoke panel shows it.

## Observability

One span per handler invocation, attributed to the discovered component and handler names (interned
once at discovery). The span opens before the command is sent on the conversation and closes when
the reply is materialised, so the wait on the process is inside it. The outcome is `Ok`, `Refused`
(an `outcome.error`) or `Failed` (a `Failure`, a violation or a timeout), reported by
`RemoteEffect.materialise` and never inferred (feature 007's rule).

## Local development

`docker compose --profile polyglot up` runs Postgres and the sidecar with
`ANKKA_PROCESS_ADDRESS=host.docker.internal:9010` (with `extra_hosts:
host.docker.internal:host-gateway` for Linux). The developer starts their process on 9010 first
or second; the sidecar waits. The sidecar's HTTP is on `localhost:9000`, its console entry in
`~/.ankka/running` is written as for any local service, and `ankka console` shows it.

## What the sidecar refuses

- A protocol connection on any interface but loopback: not bound.
- A `Spec` with an unsupported protocol major, a duplicate component, an unknown kind, a
  `read_only` streaming handler, a view over an undeclared source: refused at startup, all
  problems at once.
- A reply for the wrong command, events from a read-only handler, a snapshot that was not
  requested, a `Failure` on a stream with nothing in flight: the instance is failed and restarted;
  the violation is logged at `warn` with the component, instance and handler.
- A descriptor variable it did not expect: not its concern; the control plane refuses those.
