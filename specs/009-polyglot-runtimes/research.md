# Research: Polyglot Runtimes

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each finding says whether it was verified — against this repository, or against upstream sources
fetched on 2026-09-22 — or is an assumption a named task settles at implementation. The list at
the end collects the latter.

## R1 — Transport: gRPC over loopback, with grpc-java and ScalaPB, not pekko-grpc

**Verified upstream.** ScalaPB "0.11.x fully supports Dotty"; the current line is sbt-protoc
1.0.6 with `compilerplugin` 0.11.11, and the gRPC guide adds `grpc-netty` at
`scalapb.compiler.Version.grpcJavaVersion` and `scalapb-runtime-grpc` at
`scalapb.compiler.Version.scalapbVersion`. One documented caveat: the generator errors under
`-language:strictEquality`, which this build does not set. **Verified in this repository**:
`commonSettings` turns on `-Wunused`, so generated sources must sit in a project with that flag
off or the warning-free `compile` promise breaks — the `protocol` project carries its own
`scalacOptions`.

**Decision**: the protocol is gRPC, served and dialled with grpc-java (`grpc-netty-shaded`, so the
sidecar image carries no second Netty to reconcile with Pekko's) and ScalaPB-generated messages,
in a `protocol` sbt project that depends on nothing of ankka's. Bidirectional streams are the
natural shape for the per-instance conversation (R3) and every candidate SDK language has a
maintained gRPC implementation.

**Alternatives considered**: pekko-grpc — it runs on pekko-http, and every pekko-http artifact it
pulls would need adding to the `pekkoHttpFamily` pin (the trap that killed every HTTP suite in
feature 004), plus an sbt plugin; nothing about the protocol needs streams to be Pekko streams.
HTTP/1.1 with JSON bodies — no bidirectional stream, so the per-instance conversation would become
polling or long-polling, which is exactly the shape Cloudstate avoided. Unix domain sockets — the
two containers share a network namespace, not a filesystem, so a socket needs a shared volume for
no gain over loopback.

## R2 — Who listens and who dials: the process is a server, and so is the sidecar

**Verified upstream** against Cloudstate's `event_sourced.proto`: the *user function* implements
the `EventSourced` service with one rpc, `handle`, a bidirectional stream of
`EventSourcedStreamIn` (`init | event | command`) to `EventSourcedStreamOut` (`reply | failure`).
The proxy is the client for every conversation.

**Decision**: the same direction. The developer's process listens on loopback at
`ANKKA_PROCESS_PORT` (default `9010`) and the sidecar dials it for discovery and every
conversation. The sidecar *also* listens on loopback at `ANKKA_SIDECAR_PORT` (default `9011`) for
`client.proto`: the component client, view queries and timer scheduling that the process calls
*back* for. Two servers, both bound to `127.0.0.1`, each refusing any other bind address in
configuration (FR-009). The process starts first in the container ordering sense but nothing
depends on it: the sidecar retries discovery with backoff and is not ready until it succeeds
(FR-013).

**Alternatives considered**: one connection with a multiplexed "session" stream carrying callbacks
in the other direction — avoids a second server but makes every SDK implement a multiplexer, and
a callback then cannot be a plain unary rpc in the SDK's language. Cloudstate's own answer was the
proxy's public gRPC API, which ankka does not have; the callback service is the smallest thing that
gives a process a `ComponentClient`.

## R3 — The per-instance conversation: init, replay, commands, and a snapshot on request

**Verified upstream**: Cloudstate's `EventSourcedInit{service_name, entity_id, snapshot}`,
`EventSourcedEvent{sequence, payload}`, `EventSourcedReply{command_id, client_action,
side_effects, events, snapshot}`. **Verified in this repository**: the in-process host's state is
`Stored(value, deleted, expiryMillis)` and its journal is `Journaled.Domain | Deleted | Expiry`;
the effect the host reduces is `events`, `retention`, `Outcome.{Reply, NoReply, Fail}`.

**Decision**: one bidirectional stream per loaded instance, opened by the sidecar when sharding
starts the instance and closed by the sidecar when it passivates (which is how the process learns
to release the state — a closed stream is the signal, no separate message). The sidecar sends
`Init{component_id, entity_id, snapshot?{sequence, payload}}`, then every `Event{sequence,
payload}` after the snapshot, then `Command{id, name, payload, metadata, snapshot_requested}`
messages, strictly one in flight. The process answers each command with `Reply{command_id, events,
retention, outcome, snapshot?}` or `Failure{command_id, message, code}`. `outcome` is
`reply{payload, metadata} | no_reply | error{message, code}` — the three cases of `Outcome`.
`snapshot_requested` is set by the sidecar when this command's events would cross
`snapshot_every`; the process then includes its post-events state as `snapshot`, and the sidecar
stores it. The sidecar itself never folds: it counts events since the last snapshot and keeps the
snapshot bytes, which is all recovery needs.

Retention (`delete_now | expire_after`) and the deletion and expiry markers stay the sidecar's:
the process is told an instance is *fresh* on `Init` after deletion or expiry (no snapshot, no
events), which is what the in-process host shows a handler too.

Two rules the sidecar enforces rather than trusts: a `Reply` whose `command_id` is not the command
in flight is a protocol violation — the command fails, the stream is closed, the instance
restarts; and a `Reply` carrying events for a handler discovered as `read_only` is refused the
same way, so `query` cannot persist over the protocol either.

**Alternatives considered**: request/response per command with the sidecar folding — the sidecar
cannot fold without the developer's code; asking the process to fold event by event — one round
trip per event on the write path, and a replay of ten thousand events becomes ten thousand
requests; Cloudstate's `snapshot` on every reply — pointless traffic; a snapshot on request is
the same thing at the right time.

## R4 — Payloads are opaque bytes with the serializer's manifest, and journals stay portable

**Verified in this repository**: `Serializer[A]` is `manifest`, `toBytes`, `fromBytes`; the
hosts pre-serialise into `JournalRecord` / `StateRecord` carrying ankka's manifest; the shopping
cart's serializers are `Codecs.serializer[ShoppingCart]("shopping-cart")` — JSON under a named
manifest.

**Decision**: the protocol's `Payload` is `{content_type, manifest, data}`. The sidecar writes
`JournalRecord(manifest, data)` exactly as the in-process host does, and hands recovered records
back as `Payload`s. The sidecar never parses `data`. A process-hosted cart and the Scala cart
therefore share a journal if their JSON agrees, which is the developer's contract (SC-002 is
proven with the shopping cart's documented JSON), and no existing journal changes shape (FR-027).
`content_type` is `application/json` for every SDK this feature ships; it exists so a later SDK
may choose otherwise without a protocol change.

**Alternatives considered**: `google.protobuf.Any` for payloads, as Cloudstate — forces protobuf
on the developer's domain and makes every existing JSON journal unreadable from a process; a
sidecar-side JSON schema — nothing would consume it.

## R5 — Discovery hands over descriptors; the sidecar validates them the way the builder does

**Verified in this repository**: `ComponentRegistry.from` reports *every* problem at once;
`ServiceBuilder.validate` is the in-process gate; `HandlerBinding` carries a wire name and the
`query`/`command` distinction; `EventSourcedEntityDescriptor` carries `snapshotEvery`;
`WorkflowDescriptor` carries step names; `AgentDescriptor` carries role and `maxToolCallSteps`.

**Decision**: `Discovery.Discover(SidecarInfo{protocol_version, runtime_version}) → Spec` where
`Spec` is `{protocol_version, sdk{name, version}, components[]}` and each component entry mirrors
the descriptor of its kind: kind, id, handlers `{name, read_only, streaming}`, and per kind:
`snapshot_every`, the view's or consumer's source (component id or topic) and the view's row
serializer manifest, the workflow's step names, the timed action's handler names, the agent's role,
max steps, tools `{name, description, input_schema_json}` and guardrails `{name}`. The sidecar
turns the entries into remote descriptors, runs them through `ComponentRegistry.from`, and refuses
to start with every problem named — plus one it can only know itself: a kind it cannot host. A
second rpc, `ReportError`, lets the sidecar tell the process *why* it refused, so the failure
appears in the developer's own log and not only the sidecar's.

**Alternatives considered**: the process pushing its spec on connect (the sidecar as server) —
reverses the direction R2 settled, and a sidecar that restarts would then wait for a process that
already pushed once.

## R6 — Remote descriptors live in `runtime`; the conversation client is supplied by `sidecar`

**Verified in this repository**: `Ankka.host` matches on descriptor types to start sharded hosts;
`runtime` must not depend on `http` or `agent`; `ComponentClient` is in `sdk` over `CallTransport`
implemented by `ShardingTransport`; `EventSourcedBehavior.withEnforcedReplies` requires a
synchronous command handler, and Pekko persistence offers `Effect.stash()` / `unstashAll()`.

**Decision**: a `remote` package in `runtime` with one descriptor type per hostable kind, all
plain data from discovery, and a `Conversation` trait — `open(component, entity, init) →
InstanceSession` with `command(...)`: `Future[Reply]` and `close()`, plus stateless `handle(...)`
calls for views, consumers and timed actions — that `runtime` declares and `sidecar` implements
over grpc-java. `Ankka.host` starts a `RemoteEventSourcedHost` for a remote descriptor exactly as
it starts `EventSourcedEntityHost` for a Scala one. That host is an `EventSourcedBehavior` whose
state is `RemoteStored(snapshot: Option[Bytes], sinceSnapshot: Int, deleted, expiryMillis)` and
whose events are `Journaled.Domain(bytes) | Deleted | Expiry`. On `Invoke` it sends the command on
the session, marks the instance busy and returns `Effect.none`; further commands are stashed; the
session's `Future` is `pipeToSelf`'d as `RemoteReplied` (or `RemoteFailed`), on which the host
persists the events, replies and unstashes. A late reply for a command that already timed out is
dropped by id. The reduction from `Reply` to what is persisted and answered is one function,
`RemoteEffect.materialise`, applying the same `Outcome` rules as `EventSourcedEffect.materialise`
(a `Fail` persists nothing; `NoReply` persists and answers nothing; `Reply` persists and answers).

**Alternatives considered**: generalising the existing hosts with a strategy for "how to obtain an
effect" — the in-process host folds synchronously inside Pekko's event handler and the remote one
must not fold at all, so the shared part would be smaller than the code needed to share it, and
the in-process path (FR-026) is safest untouched; putting the remote hosts in `sidecar` — they need
`EntityProtocol`, `Observability`, `Trace`, `ProjectionSupport` and `TimerRuntime` internals that
are `private[ankka]` for good reason.

## R7 — There is no generic component-invoke HTTP surface today; the sidecar gets one

**Verified in this repository**: `HttpServer` serves user-declared `HttpEndpoint` route trees and
`/_ankka/health`; there is no route that invokes a component by name. A process-hosted service with
no HTTP layer of its own would be unreachable, and the local console's invoke panel would have
nothing to show.

**Decision**: `sidecar` registers one `HttpEndpoint`, prefix `/_ankka/components`, with
`POST /{kind}/{componentId}/{entityId}/{method}` (body: the payload; reply: the payload; errors
mapped by `HttpProblem.from`) and `GET .../{method}` for `read_only` handlers, plus the SSE form
for streaming agent handlers, on the sidecar's HTTP port. It is `Acl.AllowAll` inside the pod
network exactly as a Scala service's endpoints are today; exposure through the gateway is the
descriptor's `exposed`, unchanged. A developer's process may serve its own HTTP on its own port;
the descriptor's `http`/`port` then describe *that* container and the Kubernetes Service targets
it (contract `descriptor-and-crd.md`). The endpoint kind is not carried over the protocol.

**Alternatives considered**: routing HTTP through the protocol so the process's endpoints are
served by the sidecar — Cloudstate did this through gRPC transcoding of the user function's own
API, which ankka's HTTP model does not have; it would put path templates, query parameters and SSE
into the protocol for every SDK to implement. Adding the generic route to `http` for every
service — useful, but it changes a published module's surface in a feature that must not, and can
be lifted later once it has earned it.

## R8 — The operator renders two containers, injects the sidecar, and gates readiness on it

**Verified in this repository**: `Rendering.container` renders one container with `spec.image`,
the HTTP port env, the five cluster env vars, `envFrom` the credential secret, the `management`
and `remoting` ports, a readiness probe by port *name*, and the `preStop` sleep; `ServiceSpec.
problems` refuses `ANKKA_HTTP_PORT` and the cluster variables in a descriptor's env; the operator
already knows one image the resource does not name — `SchemaInit`'s.

**Decision**: `AnkkaServiceSpec.hosting: String = "embedded"`; for `"process"` the pod template
has two containers: `runtime` (the sidecar image from `operator/Settings`, `ANKKA_SIDECAR_IMAGE`
on the operator's Deployment, defaulting to `ankka-sidecar:<operator version>`) carrying
everything the single container carries today plus `ANKKA_PROCESS_ADDRESS=127.0.0.1:9010`, and
`app` (`spec.image`) carrying the descriptor's own env plus `ANKKA_PROCESS_PORT=9010` and
`ANKKA_SIDECAR_ADDRESS=127.0.0.1:9011`. The credential `envFrom` is on `runtime` only (FR-018).
The `management` port and its readiness probe stay on `runtime`, and `SidecarExtension.readiness`
is false until discovery has completed and while the process is unreachable, so the pod is
un-ready with the sidecar still a cluster member when the app container is down (FR-013). The `app`
container gets a readiness probe only if it serves HTTP (a TCP probe on `port`). `ANKKA_PROCESS_*`
and `ANKKA_SIDECAR_*` join the refused variables in `ServiceSpec.problems`. The Deployment's
immutable `spec.selector`, the `restarts` counter, the strategy and the `preStop` sleep are
unchanged.

**Alternatives considered**: a Kubernetes native sidecar (`initContainers` with
`restartPolicy: Always`) — attractive for ordering, but the *sidecar* here is the one that must
outlive the app for cluster membership, and native sidecars are torn down last, which is the
right way round; adopt it once the k3s image is known to support the feature gate cleanly and
mark as a follow-up; the sidecar image named in the descriptor — refused by FR-016.

## R9 — The second language is TypeScript on Node 22, and the SDK lives in this repository

**Verified upstream**: `@grpc/grpc-js` 1.14.5 ("gRPC Library for Node - pure JS implementation");
`@bufbuild/protobuf` 2.15.0 ("Fully compliant with the Protobuf conformance tests");
`testcontainers` 12.1.0 requires Node `>= 22.22`. **Assumption, to confirm with the user
(spec)**: TypeScript over Python, for reach among developers writing agents and the maturity of the
gRPC and protobuf toolchain; the conformance suite makes the choice reversible.

**Decision**: `sdks/typescript`, an npm workspace in this repository, `@bufbuild/protobuf` for
messages with `protoc-gen-es`, `@grpc/grpc-js` for the transport, `vitest` for tests and
`testcontainers` for the integration testkit. The API mirrors the Scala SDK's shape without
mirroring its syntax: a companion object declares `command("add-item", handler)` and
`query("get-cart", handler)`, handlers return effect values built from an `effects` object, an
event fold and an empty state are declared alongside, and `Ankka.service().register(...)
.listen()` starts the gRPC server and answers discovery. The unit testkit runs a handler against
an in-memory state with no sidecar; the integration testkit starts Postgres and the
`ankka-sidecar` image with `ANKKA_PROCESS_ADDRESS` pointing at the test's own process.

**Alternatives considered**: Python — the stronger agent audience, weaker typing for the effect
builders, and `grpcio`'s async story is less settled; a separate repository — the proto, the
conformance suite and the SDK would drift, which is the reason `ankka.g8` is in-tree.

## R10 — Agents over the protocol: the plan crosses, the loop stays

**Verified in this repository**: `AgentEffect` is inert data — chosen model, system, user,
context, memory provider, function tools, response shape, guardrails, or a failure; `FunctionTool`
is a name, a spec (with a schema) and an invoker returning `Either[String, String]`; `AgentRuntime`
is a `RuntimeExtension` hosting `AgentDescriptor`s, and session memory is an entity.

**Decision**: an agent entry in discovery declares role, max steps, tools (name, description, JSON
schema) and guardrails (name). A handler invocation on an agent crosses the protocol as
`Plan{session_id, name, payload} → AgentReply{model?, system?, user?, context[], memory
(session|none), tools[names], response_shape (text|json{schema_hint}), guardrails[names]} |
Failure`, which the sidecar turns into an `AgentEffect` with `FunctionTool`s whose invoker calls
`InvokeTool{session_id, tool, arguments_json} → ToolResult{ok|error}` on the process, and
guardrails whose check calls `CheckGuardrail`. The loop, memory, compaction and token accounting
run unchanged in the sidecar's `AgentRuntime`. The model is chosen by name from the sidecar's
configuration (`ANTHROPIC_API_KEY` reaches the sidecar only). Streaming handlers cross as the same
`Plan` with `streaming = true` in discovery; tokens flow to the caller through the existing
`InvokeStream` path and never through the process.

**Alternatives considered**: the process running the loop with the sidecar as a model proxy —
reimplements the loop per language, which is the cost this feature exists to avoid; tools as
closures serialised somehow — not a thing.

## R11 — Local development and the integration testkit run the sidecar as a container

**Verified in this repository**: `docker-compose.yml` runs Postgres and Keycloak; `AnkkaTestKit`
starts Postgres with testcontainers and copies the DDL in.

**Decision**: `docker-compose.yml` gains a `sidecar` service under a `polyglot` profile
(`docker compose --profile polyglot up`), configured with `ANKKA_PROCESS_ADDRESS=host.docker.
internal:9010` and the compose Postgres; the TypeScript integration testkit starts the same image
with testcontainers and the same address. On Linux without Docker Desktop, `host.docker.internal`
needs `--add-host=host.docker.internal:host-gateway`, which both compose and the testkit set.
**Assumption**: the sidecar dialling *out* of a container to the developer's process on the host
works on macOS and Linux with that mapping — verify at implementation on both.

**Alternatives considered**: a native sidecar binary (GraalVM) — out of scope in the spec; the
testkit running the sidecar as a JVM subprocess — requires a JDK on the developer's machine,
which SC-007 forbids.

## R12 — The conformance suite is one munit suite parameterised by how the target is started

**Verified in this repository**: `AnkkaTestKit.start(descriptors*)` yields an `AnkkaService`
with a `componentClient` and `restartService()`; every integration suite drives a service through
that or through HTTP.

**Decision**: `ConformanceSuite` in `sidecar/src/test` lists behaviours by name (contract
`conformance.md`) and drives them through the sidecar's generic HTTP invoke route and the journal
(via `jdbcUrl`), never through Scala types. Its target is a `ConformanceTarget`: `InProcess`
(starts the Scala reference service — the shopping cart's components plus a small conformance
entity — with `AnkkaTestKit`) or `Sidecar(processAddress)` (starts the sidecar's `Main` against a
running process, in-JVM, with `AnkkaTestKit`'s Postgres). The `sbt` invocation for an SDK is
`sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.target=127.0.0.1:9010` with the
SDK's reference service listening there; the TypeScript SDK's `npm run conformance` starts its
reference service and invokes exactly that. A behaviour that fails is reported by name, as munit
already does.

**Alternatives considered**: a TCK in the SDK's language — one per language, which is the thing
a conformance suite exists to avoid; a suite in `testkit` — it would need the sidecar, and
`testkit` is published.

## Verify at implementation

Assumptions above that a named task settles, in the order they are needed:

1. **ScalaPB 0.11.11 generated code under Scala 3.9.0** compiles with this build's flags once
   `-Wunused` is off in `protocol`; and `grpc-netty-shaded` coexists with Pekko's Netty-free
   Artery (Artery TCP does not use Netty; confirm no classpath conflict in the sidecar image).
2. **Loopback gRPC round trip cost** on the k3s node and on a laptop, measured before the remote
   host is optimised, so SC-003 is a measurement and not a hope.
3. **Pekko persistence stash semantics under `withEnforcedReplies`**: `Effect.none` while a
   command is in flight, `Effect.stash()` for the rest, `unstashAll()` on the reply — confirm a
   stashed command survives a passivation that arrives mid-flight, or defer passivation while busy.
4. **A closed stream as the passivation signal**: confirm grpc-js surfaces `end` on a
   server-side bidirectional stream promptly and that the process can distinguish a passivation
   (clean close) from a sidecar crash (error), since both mean "release the state".
5. **`host.docker.internal` from the sidecar container** on macOS Docker Desktop and on Linux with
   `host-gateway`, for compose and the TypeScript integration testkit.
6. **The k3s test image** (v1.35.1) with a two-container pod and a readiness probe on only one of
   them behaves as assumed under a rolling update (surge pod joins, old pod stops), the same
   measurement feature 004 made for one container.
7. **`@bufbuild/protobuf` 2.x with `@grpc/grpc-js`**: grpc-js expects serializers per method;
   confirm the `protoc-gen-es` output plugs in without a `protobufjs` fallback, or choose
   `protobufjs` and record why.
8. **The TypeScript package name** and the npm publish path are decided at publish time, not here;
   the release workflow gains a job that runs the SDK's tests and the conformance suite but does
   not publish to npm in this feature.
