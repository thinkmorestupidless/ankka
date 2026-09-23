# Contract: the sidecar

**Feature**: [spec.md](../spec.md) | **Research**: R2, R7, R8, R11

The sidecar is `ankka-runtime` with a `main` that boots from discovery. It is published as the
image `ankka-sidecar:<version>` beside `ankka-operator` and `ankka-controlplane`, built by
`sbt docker:publishLocal`, loaded by `deploy-local.sh`, and pushed on a release tag with the other
two. It is never a library.

## Environment

| variable | default | who sets it |
|---|---|---|
| `ANKKA_PROCESS_ADDRESS` | `127.0.0.1:9010` | the operator; compose; the Python testkit |
| `ANKKA_SIDECAR_PORT` | `9011` | the operator (always the default); bound to `127.0.0.1` only |
| `ANKKA_HTTP_PORT` | `9000` | the operator, as for any service; the sidecar is the service's HTTP |
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
3. Validate the `Spec` (data-model.md), including every endpoint's routes through
   `HttpServer.validate`. On refusal: `ReportError`, log every problem, exit 1.
4. Build remote descriptors, agent descriptors and one `RemoteEndpoint` per declared endpoint;
   `Ankka.service.registerAll(...)` with the extensions `ProjectionRuntime`, `TimerRuntime`,
   `AgentRuntime` (with the configured providers), `HttpServer.at(interface, ANKKA_HTTP_PORT)`
   carrying the remote endpoints, and `SidecarExtension`.
5. `start()`. Cluster formation, observability, the management endpoint and readiness are the
   runtime's own.

## Readiness

`/ready` on the management port is, as today, cluster membership AND every extension with an
opinion. `HttpServer` says no until bound. `SidecarExtension.readiness` is `discovered &&
reachable`, where `reachable` is a health ping to the process at most once per second. A process
that stops answering makes the pod un-ready within two seconds while the sidecar keeps its cluster
membership and its shards (FR-013); forwarded HTTP requests answer 503 in that window. When the
process is back, open conversations have been lost, and each affected instance restarts on its
next command and replays.

## Serving declared endpoints

Each `Endpoint` in the `Spec` becomes a `RemoteEndpoint(prefix, acl)` — an `HttpEndpoint` — with
one `Route` per declared route (or a `StreamRoute` when `streaming`). The sidecar's `Router` is
unchanged, so for every request it:

1. Resolves the route by the existing specificity rule (literal segments before parameters).
2. Applies the endpoint's ACL: `ALLOW_ALL`, `DENY_ALL`, or `AUTHENTICATED` through whatever
   authenticator the sidecar is configured with (none by default: `AUTHENTICATED` then answers
   503, the same as a Scala endpoint with no verifier).
3. Opens the request span and, on a virtual thread, forwards `HttpRequest{endpoint_id, route_id,
   path_args, query, headers, content_type, body, principal?, metadata}` over `Endpoint.Handle`,
   blocking on the reply.
4. Answers the `HttpResponse` as-is; a `Failure` as 500; a gRPC error or timeout as 503.

A streaming route forwards over `Endpoint.HandleStream` and feeds each `text` frame to the
existing SSE encoder, so frames are JSON-encoded on the wire (the trap in `CLAUDE.md`) without
the process knowing the SSE rules. `/_ankka/health` is served by the sidecar itself. The declared
routes are listed by `SidecarExtension.routes`, so the local console's invoke panel shows them
with the same `streaming` flag it shows for Scala routes.

## Observability

One span per handler invocation, attributed to the discovered component and handler names (interned
once at discovery), and one request span per forwarded HTTP request, attributed to the endpoint
and route. Each opens before the message is sent on the conversation and closes when the reply is
materialised, so the wait on the process is inside it. The outcome is `Ok`, `Refused` (an
`outcome.error`, or an `HttpResponse` with 4xx from an ACL) or `Failed` (a `Failure`, a violation,
a timeout or a 5xx), reported by `RemoteEffect.materialise` and the remote endpoint and never
inferred (feature 007's rule).

## Local development

`docker compose --profile polyglot up` runs Postgres and the sidecar with
`ANKKA_PROCESS_ADDRESS=host.docker.internal:9010` (with `extra_hosts:
host.docker.internal:host-gateway` for Linux). The developer starts their process on 9010 first
or second; the sidecar waits. The sidecar's HTTP is on `localhost:9000` and serves the process's
declared routes; its console entry in `~/.ankka/running` is written as for any local service, and
`ankka console` shows it.

## What the sidecar refuses

- A protocol connection on any interface but loopback: not bound.
- A `Spec` with an unsupported protocol major, a duplicate component, an unknown kind, a
  `read_only` streaming handler, a view over an undeclared source, an endpoint whose routes
  conflict or whose template does not parse: refused at startup, all problems at once.
- A reply for the wrong command, events from a read-only handler, a snapshot that was not
  requested, a `Failure` on a stream with nothing in flight: the instance is failed and restarted;
  the violation is logged at `warn` with the component, instance and handler.
- A descriptor variable it did not expect: not its concern; the control plane refuses those.
