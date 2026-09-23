# Research: Polyglot Runtimes

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each finding says whether it was verified — against this repository, or against upstream sources
fetched on 2026-09-22 and 2026-09-23 — or is an assumption a named task settles at
implementation. The list at the end collects the latter. R7, R9 and R13 were rewritten after the
2026-09-23 clarification session (endpoints over the protocol, Python, one JSON mapping).

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
`ANKKA_PROCESS_PORT` (default `9010`) and the sidecar dials it for discovery, every conversation,
and every HTTP request it forwards (R7). The sidecar *also* listens on loopback at
`ANKKA_SIDECAR_PORT` (default `9011`) for `client.proto`: the component client, view queries and
timer scheduling that the process calls *back* for. Two servers, both bound to `127.0.0.1`, each
refusing any other bind address in configuration (FR-009). Nothing depends on start order: the
sidecar retries discovery with backoff and is not ready until it succeeds (FR-013).

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
same way, so `query` cannot persist over the protocol either — which matters more now that the
SDK language cannot make it a static guarantee (R9).

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
back as `Payload`s. The sidecar never parses `data`. Portability across languages is therefore a
property of the *codecs*, not the protocol — and the clarification session made it a rule of the
platform's default codecs (R13) rather than a contract between two developers. `content_type` is
`application/json` for JSON payloads and `application/octet-stream` for the primitive encodings
(R13); it exists so a later SDK may choose otherwise without a protocol change.

**Alternatives considered**: `google.protobuf.Any` for payloads, as Cloudstate — forces protobuf
on the developer's domain and makes every existing JSON journal unreadable from a process.

## R5 — Discovery hands over descriptors; the sidecar validates them the way the builder does

**Verified in this repository**: `ComponentRegistry.from` reports *every* problem at once;
`ServiceBuilder.validate` is the in-process gate; `HandlerBinding` carries a wire name and the
`query`/`command` distinction; `EventSourcedEntityDescriptor` carries `snapshotEvery`;
`WorkflowDescriptor` carries step names; `AgentDescriptor` carries role and `maxToolCallSteps`;
`HttpEndpoint` collects `Route(method, template, needsBody)` and `StreamRoute` and `HttpServer.
validate` refuses conflicting templates at startup.

**Decision**: `Discovery.Discover(SidecarInfo{protocol_version, runtime_version}) → Spec` where
`Spec` is `{protocol_version, sdk{name, version}, components[], endpoints[]}`. Each component
entry mirrors the descriptor of its kind (contract `protocol.md`); each endpoint entry mirrors an
`HttpEndpoint`: prefix, ACL, and routes `{method, template, has_body, streaming}`. The sidecar
turns the entries into remote descriptors and remote endpoints, runs them through
`ComponentRegistry.from` and `HttpServer.validate`, and refuses to start with every problem named
— plus one it can only know itself: a kind it cannot host. A second rpc, `ReportError`, lets the
sidecar tell the process *why* it refused, so the failure appears in the developer's own log and
not only the sidecar's.

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
calls for views, consumers, timed actions and HTTP requests — that `runtime` declares and
`sidecar` implements over grpc-java. `Ankka.host` starts a `RemoteEventSourcedHost` for a remote
descriptor exactly as it starts `EventSourcedEntityHost` for a Scala one. That host is an
`EventSourcedBehavior` whose state is `RemoteStored(snapshot: Option[Bytes], sinceSnapshot: Int,
deleted, expiryMillis)` and whose events are `Journaled.Domain(bytes) | Deleted | Expiry`. On
`Invoke` it sends the command on the session, marks the instance busy and returns `Effect.none`;
further commands are stashed; the session's `Future` is `pipeToSelf`'d as `RemoteReplied` (or
`RemoteFailed`), on which the host persists the events, replies and unstashes. A late reply for a
command that already timed out is dropped by id. The reduction from `Reply` to what is persisted
and answered is one function, `RemoteEffect.materialise`, applying the same `Outcome` rules as
`EventSourcedEffect.materialise` (a `Fail` persists nothing; `NoReply` persists and answers
nothing; `Reply` persists and answers).

**Alternatives considered**: generalising the existing hosts with a strategy for "how to obtain an
effect" — the in-process host folds synchronously inside Pekko's event handler and the remote one
must not fold at all, so the shared part would be smaller than the code needed to share it, and
the in-process path (FR-026) is safest untouched; putting the remote hosts in `sidecar` — they need
`EntityProtocol`, `Observability`, `Trace`, `ProjectionSupport` and `TimerRuntime` internals that
are `private[ankka]` for good reason.

## R7 — HTTP endpoints are declared in discovery and served by the sidecar (clarified)

**Verified in this repository**: `HttpServer` serves user-declared `HttpEndpoint` route trees and
`/_ankka/health`; a `Route` is `(method, PathTemplate, needsBody, run: (pathArgs, body) =>
EncodedResponse)` and a `StreamRoute` runs to a `Source[String, ?]`; `Router` resolves literal
segments before parameters, applies the ACL, sets the `RequestContext` (query parameters, headers,
principal) on the handler's virtual thread, and maps `HttpProblem`s. There is no generic
component-invoke route.

**Decision** (the clarification session's): the process declares its endpoints in discovery and
the sidecar serves them. Each declared route becomes a `Route` (or `StreamRoute`) in a
`RemoteEndpoint` registered with the sidecar's `HttpServer`, whose `run` forwards
`HttpRequest{route_id, path_args, query, headers, body, metadata}` over `Endpoint.Handle` and
turns the `HttpResponse{status, content_type, body, headers}` into an `EncodedResponse`; a
streaming route opens `Endpoint.HandleStream` and maps its frames to the SSE source. The ACL is
declared per endpoint in discovery as `allow_all | deny_all | authenticated`, applied by the
sidecar's `Router` exactly as for a Scala endpoint; a principal, when there is one, crosses as
metadata. Routing, specificity ordering, the health route, exposure and tracing are therefore the
platform's, unchanged, and a polyglot service has one HTTP port: the sidecar's. The process
serves no HTTP the platform routes to.

The route's `run` executes on a virtual thread like any Scala handler, so the forwarded call is a
blocking `await` on the conversation, and the `RequestContext` is sound for the duration.

**Alternatives considered** (both rejected in the session): a generic component-invoke route on
the sidecar with the process free to serve its own HTTP on its own port — two ports with two
sets of readiness and tracing rules; the process always serving HTTP with no sidecar HTTP at
all — nothing to invoke without code, and the local console's invoke panel empty.

## R8 — The operator renders two containers, injects the sidecar, and gates readiness on it

**Verified in this repository**: `Rendering.container` renders one container with `spec.image`,
the HTTP port env, the five cluster env vars, `envFrom` the credential secret, the `management`
and `remoting` ports, a readiness probe by port *name*, and the `preStop` sleep; `ServiceSpec.
problems` refuses `ANKKA_HTTP_PORT` and the cluster variables in a descriptor's env; the operator
already knows one image the resource does not name — `SchemaInit`'s.

**Decision**: `AnkkaServiceSpec.hosting: String = "embedded"`; for `"process"` the pod template
has two containers: `runtime` (the sidecar image from `operator/Settings`, `ANKKA_SIDECAR_IMAGE`
on the operator's Deployment, defaulting to `ankka-sidecar:<operator version>`) carrying
everything the single container carries today — including the HTTP port, since the sidecar is
the service's HTTP (R7) — plus `ANKKA_PROCESS_ADDRESS=127.0.0.1:9010`, and `app` (`spec.image`)
carrying the descriptor's own env plus `ANKKA_PROCESS_PORT=9010` and
`ANKKA_SIDECAR_ADDRESS=127.0.0.1:9011`. The credential `envFrom` is on `runtime` only (FR-018).
The `management` port and its readiness probe stay on `runtime`, and `SidecarExtension.readiness`
is false until discovery has completed and while the process is unreachable, so the pod is
un-ready with the sidecar still a cluster member when the app container is down (FR-013). The `app`
container has no ports and no probe: its liveness is the sidecar's opinion. `ANKKA_PROCESS_*` and
`ANKKA_SIDECAR_*` join the refused variables in `ServiceSpec.problems`. The Deployment's immutable
`spec.selector`, the `restarts` counter, the strategy and the `preStop` sleep are unchanged.

**Alternatives considered**: a Kubernetes native sidecar (`initContainers` with
`restartPolicy: Always`) — the container that must outlive the other here is the *sidecar*, for
cluster membership, and native sidecars are torn down last, which is the right way round; adopt
it once the k3s image is known to support the feature gate cleanly, as a follow-up; the sidecar
image named in the descriptor — refused by FR-016.

## R9 — The second language is Python 3.12, and the SDK lives in this repository (clarified)

**Verified upstream** (2026-09-23): `grpcio` 1.84.0 and `grpcio-tools` 1.84.0 ("Protobuf code
generator for gRPC"), both `>=3.10`; `testcontainers` 4.15.0 for Python, `>=3.10`.
**Decided in the session**: Python over TypeScript, for the audience writing agents; in this
repository under `sdks/python`, so the protocol, the conformance suite and the SDK move together
and a tag proves them consistent.

**Decision**: `sdks/python`, a `uv`-managed project (`pyproject.toml`, package `ankka`),
`grpcio` with the `grpc.aio` server so a handler may be `async def` and the component client may
be awaited, `grpcio-tools` to generate from the copied `.proto` files, `pytest` with
`pytest-asyncio`, `testcontainers` for the integration testkit. The API mirrors the Scala SDK's
shape without mirroring its syntax: a class per component with decorated handlers carrying the
wire name — `@command("add-item")`, `@query("get-cart")` — handlers returning effect values built
from `effects`, `empty_state` and `apply_event` declared on the class, endpoints as a class with
`@get("/carts/{cart_id}")`-style decorators, and `Ankka.service().register(...).listen()` to
start the gRPC server and answer discovery. Type hints throughout, checked with `mypy --strict`
in CI; `query` returning a persisting effect is refused at registration (the effect type is
inspected) and by the sidecar at runtime (R3), since the language cannot refuse it at compile
time.

The unit testkit runs a handler against an in-memory state with no sidecar; the integration
testkit starts Postgres and the `ankka-sidecar` image with `ANKKA_PROCESS_ADDRESS` pointing at
the test's own listener (R11).

**Alternatives considered**: TypeScript — the stronger typing for the effect builders, the weaker
agent audience; the session chose the audience. A separate repository — drift between the proto,
the conformance suite and the SDK between tags.

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
reimplements the loop per language, which is the cost this feature exists to avoid.

## R11 — Local development and the integration testkit run the sidecar as a container

**Verified in this repository**: `docker-compose.yml` runs Postgres and Keycloak; `AnkkaTestKit`
starts Postgres with testcontainers and copies the DDL in.

**Decision**: `docker-compose.yml` gains a `sidecar` service under a `polyglot` profile
(`docker compose --profile polyglot up`), configured with `ANKKA_PROCESS_ADDRESS=host.docker.
internal:9010` and the compose Postgres; the Python integration testkit starts the same image
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
`conformance.md`) and drives them through the reference service's *declared HTTP endpoints* (R7)
and the journal (via `jdbcUrl`), never through Scala types. Its target is a `ConformanceTarget`:
`InProcess` (starts the Scala reference service with `AnkkaTestKit`) or `Sidecar(processAddress)`
(starts the sidecar's `Main` against a running process, in-JVM, with `AnkkaTestKit`'s Postgres).
The `sbt` invocation for an SDK is `sbt 'sidecar/testOnly *ConformanceSuite'
-Dankka.conformance.target=127.0.0.1:9010` with the SDK's reference service listening there; the
Python SDK's `uv run conformance` starts its reference service and invokes exactly that. The JSON
fixture suite (R13) is a second, cheaper suite the SDK runs on its own.

**Alternatives considered**: a TCK in the SDK's language — one per language, which is the thing
a conformance suite exists to avoid; a suite in `testkit` — it would need the sidecar, and
`testkit` is published.

## R13 — One JSON mapping across SDKs, defined by what the Scala codecs produce today (clarified)

**Verified in this repository**: `Codecs.make` is jsoniter with `discriminatorFieldName =
"type"`, `requireDiscriminatorFirst = false`, `transientEmpty = false` (empty collections are
written), `transientNone = false` (`None` is written as `null`), recursive types allowed. So the
cart's `ShoppingCartEvent.ItemAdded(item)` is `{"type":"ItemAdded","item":{...}}` and the
fieldless `CheckedOut` is `{"type":"CheckedOut"}`. **And not everything is JSON**: `Serializers`
encodes `Int`, `Long`, `Boolean`, `Double`, `String` as their decimal or UTF-8 text, `Done` and
`Unit` as zero bytes, `FiniteDuration` as decimal milliseconds, and `Option[A]` as a one-byte
`1` prefix before `A`'s bytes or zero bytes for `None` — each under its own manifest (`int`,
`done`, `option[int]`, …). These are what cross the wire for `invoke(item)` replies of `Done`
and for primitive command inputs, and they land in the journal only when a domain type is one
of them.

**Decision**: the platform writes the mapping down as `protocol/ENCODING.md` — the jsoniter
rules above for records, sum types (discriminator `type`, the case's simple name), `Option`
(`null`), collections (JSON arrays, empty written), maps (JSON objects with string keys),
`Instant` and the other `java.time` types (ISO-8601 strings, as jsoniter's defaults), numbers
(JSON numbers; `Long` beyond 2⁵³ still a JSON number, which every SDK must parse without loss),
and the primitive and `Option` byte encodings with their manifests — and ships
`protocol/fixtures/*.json` pairs of *bytes* and *expected decoded value* (plus the manifest and
content type) generated from the Scala codecs by a test in `core` that fails when the codecs
drift from the fixtures. Every SDK's default codec is built to the document and proven by the
fixtures. The Python SDK's default codec is a `dataclass`-driven encoder honouring the rules,
with sum types as a union of dataclasses discriminated by class name.

**Alternatives considered** (rejected in the session): portability for the shopping cart only,
by a hand-written codec — proves nothing general; no cross-language portability — makes the
platform's "one journal" promise a per-language one.

## Verify at implementation

Assumptions above that a named task settles, in the order they are needed:

1. **ScalaPB 0.11.11 generated code under Scala 3.9.0** compiles with this build's flags once
   `-Wunused` is off in `protocol`; and `grpc-netty-shaded` coexists with Pekko's Artery
   (Artery TCP does not use Netty; confirm no classpath conflict in the sidecar image).
2. **Loopback gRPC round trip cost** on the k3s node and on a laptop, measured before the remote
   host is optimised, so SC-003 is a measurement and not a hope. The HTTP path now adds a second
   hop (sidecar → process for the endpoint, process → sidecar for the client call, sidecar → the
   entity), so the measurement is of the full path.
3. **Pekko persistence stash semantics under `withEnforcedReplies`**: `Effect.none` while a
   command is in flight, `Effect.stash()` for the rest, `unstashAll()` on the reply — confirm a
   stashed command survives a passivation that arrives mid-flight, or defer passivation while busy.
4. **A closed stream as the passivation signal**: confirm `grpc.aio` surfaces the end of a
   server-side bidirectional stream promptly, and that the process can distinguish a passivation
   (clean close) from a sidecar crash (error), since both mean "release the state".
5. **`host.docker.internal` from the sidecar container** on macOS Docker Desktop and on Linux with
   `host-gateway`, for compose and the Python integration testkit.
6. **The k3s test image** (v1.35.1) with a two-container pod and a readiness probe on only one of
   them behaves as assumed under a rolling update (surge pod joins, old pod stops), the same
   measurement feature 004 made for one container.
7. **`grpc.aio` with one handler at a time per stream**: confirm that a per-stream `asyncio`
   task with an inbound queue gives strict ordering with no reply reordering under load.
8. **The JSON fixtures cover what the samples actually persist**: generate from the shopping
   cart's, the planner's and the control plane's domain types, not from invented ones, so a
   mapping rule nobody uses is not specified and one somebody uses is not missed.
9. **The Python package name** and the PyPI publish path are decided at publish time, not here;
   the release workflow gains a job that runs the SDK's tests, the fixtures and the conformance
   suite but does not publish to PyPI in this feature.
