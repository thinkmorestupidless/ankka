# Tasks: Polyglot Runtimes

**Input**: Design documents from `/specs/009-polyglot-runtimes/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included. This repository's suites are the proof for every previous feature, the spec's
success criteria name what must be proven against a real cluster and against a real second-language
process, and research R12 and R13 name the suites. The `ProcessDouble` (a Scala process speaking
the protocol) is how every runtime-side behaviour is proven *before* the Python SDK exists.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (an entity in another language), US2 (deployed like any other service),
  US3 (every component kind), US4 (agents), US5 (a second SDK can prove itself)

Paths are repository-relative. Abbreviations: `PROTO` = `protocol/src/main/protobuf/ankka/protocol/v1`;
`RT` = `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime`; `SC` =
`sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar`, `SCT` its test twin
`sidecar/src/test/scala/com/thinkmorestupidless/ankka/sidecar`; `PY` = `sdks/python/src/ankka`,
`PYT` = `sdks/python/tests`, `PYX` = `sdks/python/examples/shopping_cart`; `OP` =
`operator/src/main/scala/com/thinkmorestupidless/ankka/operator`, `OPT` its test twin; `API` =
`controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api`; `CP` =
`controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane`; `CRD` =
`crd/src/main/scala/com/thinkmorestupidless/ankka/crd`.

---

## Phase 1: Setup — verify first (research "verify at implementation" 1–7)

**Purpose**: settle the assumptions the design rests on before building on them, and put the two
new sbt projects and the Python project in place. Each spike's *answer* is the deliverable; its
code is throwaway unless it becomes the suite named.

- [X] T001 Add `sbt-protoc` 1.0.6 and the ScalaPB `compilerplugin` 0.11.11 to `project/plugins.sbt`; add `scalapbRuntimeGrpc`, `grpcNettyShaded` (at `scalapb.compiler.Version.grpcJavaVersion`) and `grpcStub` to `project/Dependencies.scala`; in `build.sbt` add `lazy val protocol` (`publish / skip := true`, `Compile / PB.targets` → `scalapb.gen(grpc = true)`, its own `scalacOptions` **without** `-Wunused`, no ankka dependency) and `lazy val sidecar` (`dependsOn(runtime, http, agent, protocol)`, `JavaAppPackaging`, `dockerSettings`, `publish / skip := true`, `Docker / packageName := "ankka-sidecar"`), add both to `root.aggregate`, and add `sidecar` to the `docker:publishLocal` aggregation and to `buildAll`.
- [X] T002 Spike research item 1: write a throwaway `PROTO/spike.proto` with one bidirectional rpc, run `sbt protocol/compile sidecar/compile` and confirm the generated code is warning-free under this build and that `grpc-netty-shaded` starts a server in a `sidecar` test beside a Pekko `ActorSystem`; record the answer in [research.md](./research.md) verify item 1, then delete the spike.
- [X] T003 Spike research item 2 in `SCT/LoopbackLatencySpike.scala` (gated on `-Dankka.benchmarks` like feature 007's harness): one unary and one bidirectional-stream round trip on loopback, p50 and p99 over 10k calls; record the numbers in research item 2. Keep the file: it becomes part of T110's measurement.
- [X] T004 Spike research item 3 in `SCT/StashSpike.scala`: an `EventSourcedBehavior.withEnforcedReplies` that answers `Effect.none` while busy, `Effect.stash()` for the rest and `unstashAll()` on a self-sent reply; prove a stashed command survives a passivation message arriving mid-flight, or record that passivation must be deferred while busy. Record the answer; the pattern becomes T031.
- [X] T005 [P] Create the Python project: `sdks/python/pyproject.toml` (package `ankka`, Python `>=3.12`, deps `grpcio` 1.84, `protobuf`, dev deps `grpcio-tools` 1.84, `pytest`, `pytest-asyncio`, `mypy`, `testcontainers` 4.15; scripts `proto`, `test`, `typecheck`, `conformance`, `example`), `sdks/python/README.md`, `sdks/python/src/ankka/__init__.py`, and `sdks/python/scripts/proto.py` that copies `protocol/` into `sdks/python/proto/` and runs `grpc_tools.protoc` into `PY/_proto/`. `uv sync && uv run typecheck` passes on the empty package.
- [X] T006 [P] Spike research item 4 in `PYT/test_stream_close_spike.py`: a `grpc.aio` server with a bidirectional handler; a client that closes cleanly and one that aborts; assert the handler observes each promptly and can tell them apart. Record the answer in research item 4; the handling becomes T042.
- [X] T007 [P] Spike research item 5 by hand on this machine: `docker run --add-host=host.docker.internal:host-gateway` of any image dialling a listener on the host at 9010, on macOS Docker Desktop and, if available, Linux; record in research item 5 what compose and the Python testkit must set.
- [X] T008 [P] Forward `-Dankka.conformance.target` and `-Dankka.benchmarks` to the forked test JVM in `build.sbt`'s `Test / javaOptions` for `sidecar` (the trap in `CLAUDE.md`: forked tests do not inherit `-D`), and add `ANKKA_SIDECAR_IMAGE`'s default derivation (`ankka-sidecar:` + `version.value.replace('+','-')`) to `BuildInfo` so no suite names the image by a literal tag.

**Checkpoint**: T002–T004, T006, T007 answered and recorded in research.md. If T004 says
passivation must be deferred while busy, T031 implements that. If T007 needs a different mapping
on Linux, T049 and T047 carry it.

---

## Phase 2: Foundational — the protocol, the encoding, the remote seam, the sidecar skeleton, the SDK skeleton

**Purpose**: what every story needs. Blocks all stories.

### The protocol (contracts/protocol.md)

- [X] T009 Write `PROTO/payload.proto`: `Payload`, `Metadata`, `ErrorCode`, `Error`, `Failure`, `Outcome`, `Retention`, `Empty` exactly as in [contracts/protocol.md](./contracts/protocol.md).
- [X] T010 [P] Write `PROTO/discovery.proto`: `Discovery` service (`Discover`, `ReportError`), `SidecarInfo`, `Spec`, `SdkInfo`, `Kind`, `Component` with every `*Detail`, `Handler`, `Source`, `Tool`, `Endpoint`, `Route`, `Problem`.
- [X] T011 [P] Write `PROTO/event_sourced.proto`, `PROTO/key_value.proto`, `PROTO/workflow.proto` (with `StepRef`, `StepOutcome`) per the contract.
- [X] T012 [P] Write `PROTO/view.proto`, `PROTO/consumer.proto`, `PROTO/timed_action.proto`, `PROTO/endpoint.proto` (`HttpRequest`, `Principal`, `HttpReply`, `HttpResponse`, `StreamFrame`), `PROTO/agent.proto`, `PROTO/client.proto` per the contract.
- [X] T013 Write `protocol/README.md`: the versioning rule (`MAJOR.MINOR`, what is a minor, what is a major), the directory as the artifact, how an SDK copies it, and `Protocol.version = "1.0"`; `sbt protocol/compile` is warning-free.

### The encoding (research R13, data-model.md "Encoding")

- [X] T014 Write `protocol/ENCODING.md`: the table from [data-model.md](./data-model.md#encoding-protocolencodingmd-r13) with one worked example per row taken from the shopping cart (`ItemAdded`, `CheckedOut`, `ShoppingCart` with an empty `items`), the control plane (`Instant` fields, an `Option` that is `None`) and the primitives (`int`, `done`, `option[int]`, `duration-millis`).
- [X] T015 Create `modules/core/src/test/scala/com/thinkmorestupidless/ankka/core/EncodingFixturesSuite.scala`: for each shape in `ENCODING.md`, encode a value with the real codec (`Codecs.make` / `Serializers`) and write `protocol/fixtures/<name>.json` = `{manifest, content_type, bytes_base64, value}`; the suite **fails** when a regenerated file differs from the committed one. Domain types come from the samples' and the control plane's own case classes compiled into the test (a `core` test may depend on nothing else, so copy the minimal shapes into `EncodingShapes.scala` beside it and say why). Commit the generated fixtures.

### The remote seam in `runtime` (research R6, data-model.md "Runtime")

- [X] T016 Create `RT/remote/RemoteDescriptors.scala`: `RemoteHandler`, `RemoteSource`, and `RemoteEventSourcedDescriptor`, `RemoteKeyValueDescriptor`, `RemoteWorkflowDescriptor`, `RemoteViewDescriptor`, `RemoteConsumerDescriptor`, `RemoteTimedActionDescriptor`, each extending `ComponentDescriptor` and carrying no functions.
- [X] T017 [P] Create `RT/remote/Conversation.scala`: the trait per data-model.md (`open`, `InstanceSession.{command, runStep, close}`, `handleView`, `handleConsumer`, `invokeTimedAction`, `handleHttp`, `handleHttpStream`, `reachable`), with a `ProtocolViolation(message)` exception and the `Init`, `Command`, `Reply`, `StepReply`, `HttpForward`, `HttpResult` plain case classes it speaks in (Scala values, not generated messages: `runtime` does not see `protocol`).
- [X] T018 [P] Create `RT/remote/RemoteEffect.scala`: `materialise(reply: Reply, handler: RemoteHandler, snapshotRequested: Boolean): Either[ProtocolViolation, Materialised]` where `Materialised` is `(events: Vector[Payload], retention, outcome: Either[CommandError, Option[(Payload, Metadata)]], snapshot: Option[Payload])`, refusing events on a read-only handler, events beside an `error` outcome, a snapshot not requested, and a `command_id` mismatch; the same `Outcome` rules as `EventSourcedEffect.materialise`, stated in a comment beside it.
- [X] T019 [P] Write `modules/runtime/src/test/scala/com/thinkmorestupidless/ankka/runtime/remote/RemoteEffectSuite.scala`: every rule in T018, both directions.

### The sidecar skeleton (contracts/sidecar.md)

- [X] T020 Create `SC/Settings.scala` (`ANKKA_PROCESS_ADDRESS`, `ANKKA_SIDECAR_PORT`, `ANKKA_SIDECAR_DISCOVERY_TIMEOUT`, command and request timeouts from `ankka.ask-timeout` and the HTTP body timeout) and `sidecar/src/main/resources/{application.conf,logback.xml}`; the callback bind address is a constant `127.0.0.1`, not a setting.
- [X] T021 Create `SC/Discovery.scala`: dial the process, call `Discover` with `Protocol.version` and `BuildInfo.version`, retry with backoff, then validate the `Spec` — protocol major, duplicate `(kind, id)`, duplicate handler, `read_only && streaming`, unknown source, undeclared view query, unsupported kind, endpoint templates parsed and checked with `HttpServer.validate`'s rules — collecting every problem; on refusal call `ReportError` and return `Left(problems)`. On success build the remote descriptors from T016 (agents and endpoints are added by T060 and T036).
- [X] T022 Create `SC/GrpcConversation.scala`: `Conversation` over grpc-java stubs — one bidirectional stream per `open`, a per-session queue that admits exactly one command in flight, `Future`s completed from the stream observer, `close()` half-closing the stream, stateless calls as unary rpcs, `reachable` as a one-per-second cached health ping (`grpc.health.v1` or `Discover` re-called cheaply — decide and say why); and `SC/CallbackServer.scala`: the loopback-only grpc-java server for `client.proto`.
- [X] T023 Create `SC/ClientService.scala`: `Client.Invoke` → `ComponentClient` by kind, `InvokeStream` → the runtime's `InvokeStream` tokens, `Query` → `ViewClient`, `Schedule`/`Cancel` → the timer surface; metadata copied so the call is a child span.
- [X] T024 Create `SC/SidecarExtension.scala` (`RuntimeExtension`: starts the callback server, `readiness = discovered && reachable`, `boundAddress` from the HTTP extension, `routes` from the remote endpoints) and `SC/Main.scala`: `Discovery` → `Ankka.service.registerAll(descriptors).withExtension(ProjectionRuntime(...)).withExtension(TimerRuntime(...)).withExtension(AgentRuntime(...)).withExtension(HttpServer.at(...)).withExtension(SidecarExtension).start()`; exit 1 with every problem logged on a refused `Spec`.

### The test double and the protocol suite

- [X] T025 Create `SCT/ProcessDouble.scala`: a grpc-java server implementing every service in `protocol.md` from a scriptable `DoubleSpec` (components with in-memory folds written in Scala, endpoints with Scala handlers), recording every message it receives, with knobs to reply late, reply with the wrong id, include an unrequested snapshot, throw, never reply, and to restart (drop all state and re-listen).
- [X] T026 Write `SCT/ProtocolSuite.scala`: both ends in one JVM — every conversation round-trips; each knob in T025 produces the `ProtocolViolation` or timeout T018 and T022 promise; a bind on `0.0.0.0` is refused; `Discover` with `99.0` and with two conflicting routes is refused with every problem in one message and a `ReportError` at the double.

### The Python SDK skeleton (contracts/python-sdk.md)

- [X] T027 Create `PY/codec.py`: `Codec[A]` protocol, `json_codec(cls, manifest)` for dataclasses, unions of dataclasses (discriminator `type` = class name), `Optional`, `list`/`tuple`, `dict[str, …]`, scalars, `datetime`, `timedelta` per `ENCODING.md`; the primitive codecs (`int`, `str`, `bool`, `float`, `Done`, `Optional[A]`, `bytes`, `timedelta` as `duration-millis`) with their manifests and content types.
- [X] T028 [P] Write `PYT/test_encoding_fixtures.py`: load every file in `sdks/python/proto/fixtures/`, decode `bytes_base64` with the codec the manifest and content type select, compare to `value`, re-encode and compare bytes; a fixture with no matching codec is a failure, never a skip.
- [X] T029 Create `PY/server.py` (the `grpc.aio` server bound to `127.0.0.1:${ANKKA_PROCESS_PORT}`, `Discovery` servicer answering from registered classes, `ReportError` logging at error), `PY/service.py` (`Ankka.service().register(...).listen()` / `.spec()`, duplicate-registration and validation errors before listen), `PY/context.py` (`CommandContext`, `RequestContext`), and `PY/client.py` (`ComponentClient` over `client.proto` with `for_event_sourced_entity(...).call(name).invoke(input, reply=...)`, `stream`, `views`, `timers`, trace metadata copied from the context).

**Checkpoint**: `sbt protocol/compile core/test 'sidecar/testOnly *ProtocolSuite *RemoteEffectSuite'`
green; `uv run test` green on the fixtures; `sbt runtime/dependencyTree` shows no `protocol`,
gRPC, `http` or `agent` under `runtime`.

---

## Phase 3: User Story 1 — An entity in another language (P1) 🎯 MVP

**Goal**: a Python event sourced entity, commanded over HTTP through a local sidecar, persisted,
recovered after restart, snapshotted, with the journal portable to and from the Scala cart.

**Independent test**: [quickstart.md](./quickstart.md) tiers 1–2 for the double, then the Python
shopping cart against the compose sidecar: add items, read, restart both, read again; point the
Scala cart at the same database and read the same cart.

### The remote event sourced host (research R6)

- [X] T030 [US1] Create `RT/remote/RemoteEventSourcedHost.scala`: an `EventSourcedBehavior.withEnforcedReplies` over `RemoteStored(snapshot, sinceSnapshot, deleted, expiryMillis)` and `Journaled.Domain(Payload) | Deleted | Expiry`, using the existing `JournalRecord`/`StateRecord` adapters with the payload's manifest; on first command (or recovery) `open` the session with the snapshot and a streamed replay of the events after it, read with the same query the in-process recovery uses.
- [X] T031 [US1] In the same host: on `Invoke`, send `Command{id, name, payload, metadata, snapshot_requested = sinceSnapshot + 1 >= snapshotEvery}`, mark busy, `Effect.none`; stash further `Invoke`s; `pipeToSelf` the reply as `RemoteReplied`/`RemoteFailed`; on reply run `RemoteEffect.materialise`, persist the events (and the retention markers), store the snapshot when present, reply, `unstashAll()`; drop a reply whose id is not in flight; on timeout reply `Timeout`, close the session and mark the instance for re-`open`. Passivation per T004's answer. `InvokeStream` → `StreamFailed`, exactly as the in-process host.
- [X] T032 [US1] Record the span in the host as the in-process host does (`observability.recorder.begin` before the send, `complete(span, outcome)` after materialise, outcome from `RemoteEffect`), with the component and handler names interned once at construction.
- [X] T033 [US1] Wire it: in `RT/Ankka.scala`'s `host`, a `RemoteEventSourcedDescriptor` starts `RemoteEventSourcedHost.behavior(descriptor, entityId, componentClient, conversation)`; the `Conversation` reaches `Ankka` through a new `withConversation(conversation)` on `ServiceBuilder` (a remote descriptor with no conversation is a validation error naming it).
- [X] T034 [US1] Write `SCT/RemoteEntitySuite.scala` on `AnkkaTestKit` with the double: S1.1–S1.4 (persist and reply; refusal persists nothing; recover after `restartService()`; snapshot at the interval and replay from it), then every edge case — double restarted mid-stream, sidecar restarted (`restartService()`), a handler that never replies, a late reply, ten concurrent commands on one id in order, passivation closes the stream and a later command re-inits, `misbehave` answers 500 and the next command works.

### The remote endpoint (research R7, contracts/sidecar.md "Serving declared endpoints")

- [X] T035 [US1] Create `SC/RemoteEndpoint.scala`: an `HttpEndpoint(prefix)` with `acl` from discovery, registering one route per declared `Route` through the endpoint's own `get`/`post`/…/`sse` builders with the declared template and arity, whose handler builds `HttpForward{endpoint_id, route_id, path_args, query (from request.query), headers, content_type, body, principal (from request.principal when present), metadata (current trace)}`, blocks on `conversation.handleHttp`, and returns an `EncodedResponse` as-is, a `Failure` as 500, a timeout as 503; a streaming route maps `handleHttpStream` frames to the SSE `Source`.
- [X] T036 [US1] Extend `SC/Discovery.scala` to build the `RemoteEndpoint`s and `SC/Main.scala` to register them with `HttpServer.at(interface, port)`; when the descriptor says `http: false` (the sidecar reads `ANKKA_HTTP_PORT` absent) a `Spec` with endpoints is refused with that reason.
- [X] T037 [US1] Write `SCT/RemoteEndpointSuite.scala`: path parameters bind in template order; `/carts/awkward` beats `/carts/{id}`; repeated query parameters and headers cross in order; a JSON body and reply round-trip; status passthrough (418); a throwing handler is 500 and the span `Failed`; `DENY_ALL` and `AUTHENTICATED`-without-verifier never reach the double; SSE frames with a leading space and an embedded newline arrive intact; the entity span from a forwarded request is the request span's child; with the double stopped a request is 503 within two seconds and 200 after restart.

### The Python entity, endpoint and testkits

- [X] T038 [US1] Create `PY/effects/event_sourced.py` (frozen dataclasses `EventSourcedEffect`, `ReadOnlyEffect`, `PersistBuilder` with `then_reply`, `then_reply_state`, `then_no_reply`, `delete_entity`, `expire_after`; `Effects` with `persist`, `reply`, `error`, `no_reply`, `delete_entity`) and `PY/effects/common.py` (`Outcome`, `Retention`, `ErrorCode`, `Done`).
- [X] T039 [US1] Create `PY/event_sourced_entity.py`: `EventSourcedEntity[S, E]` base class with `component_id`, `state_codec`, `event_codec`, `snapshot_every`, `empty_state`, `apply_event`, `self.state`, `self.context`, `self.effects`; `@command(name)` and `@query(name)` decorators recording wire name and `read_only`; registration refuses a `@query` whose return annotation is not `ReadOnlyEffect` and a duplicate wire name; `to_component()` produces the discovery entry.
- [X] T040 [US1] Create `PY/endpoint.py`: `Endpoint` base with `prefix`, `acl`, `@get`/`@post`/`@put`/`@delete`/`@patch`/`@sse(template)` decorators binding path parameters by name, one optional typed body parameter decoded by its default codec, return values encoded by theirs, `HttpProblem(status, message)`, `self.request` (query, headers, principal); `to_endpoint()` produces the discovery entry with route ids.
- [X] T041 [US1] Implement the `EventSourced` servicer in `PY/server.py`: per stream an `asyncio` task holding `S` and the sequence; `Init` decodes the snapshot or takes `empty_state`; `Event` folds; `Command` runs the handler (sync or `async`), folds its events locally, encodes `Reply` (`snapshot` when requested), a raised exception becomes `Failure`; strictly one command at a time; release state when the stream ends (per T006's answer).
- [X] T042 [US1] Implement the `Endpoint` servicer in `PY/server.py`: `Handle` binds path args, query, headers, body and principal into the decorated method, encodes the result or `HttpProblem` into `HttpResponse`; `HandleStream` iterates the async generator into `StreamFrame`s; an exception is a `Failure`.
- [X] T043 [US1] Create `PY/testkit/unit.py`: `EventSourcedTestKit.of(cls, entity_id)` with `call(name, input)` → `Materialised{events, new_state, retention, reply | error}` round-tripping through the codecs, `state`, and `EndpointTestKit.of(cls, client)` with `get`/`post`/…(path, query, headers, body) → `(status, decoded body)`.
- [X] T044 [US1] Create `PY/testkit/integration.py`: `AnkkaTestKit.start(service)` — Postgres via `testcontainers` with the DDL copied from the sidecar image (`docker create` + `cp` of `/opt/docker/ddl`, or the image exposes it at a documented path — decide and document), the `ankka-sidecar:<ankka_version>` image with `ANKKA_PROCESS_ADDRESS` per T007 and `ANKKA_DB_*`, waits for `/ready`; `http` client against the sidecar's port; `restart()`; async context manager.
- [X] T045 [US1] Write `PYT/test_event_sourced_entity.py` (the unit testkit over a small entity: persist, refuse, no-reply, delete, expire; a `@query` annotated with a persisting effect refused at registration), `PYT/test_endpoint.py` (the endpoint testkit), and `PYT/test_server_stream.py` (the servicer against an in-process `grpc.aio` client: init, replay, command, snapshot on request, release on close).
- [X] T046 [US1] Port the cart: `PYX/domain.py` (`LineItem`, `ShoppingCart`, `ItemAdded | ItemRemoved | CheckedOut` — the same field names as the Scala sample), `PYX/entity.py` (`shopping-cart`, `add-item`, `remove-item`, `checkout`, `get-cart`), `PYX/endpoint.py` (`/carts` with the Scala endpoint's routes), `PYX/main.py`, `PYX/service.json` (`hosting: process`, `protocol: 1.0`), `PYX/Dockerfile` (`python:3.12-slim`, `uv`, `CMD python -m examples.shopping_cart.main`), and `PYX/test_cart.py` on both testkits including a restart.
- [X] T047 [US1] Add the `sidecar` service under a `polyglot` profile to `docker-compose.yml` (`ANKKA_PROCESS_ADDRESS=host.docker.internal:9010`, `extra_hosts` per T007, the compose Postgres's `ANKKA_DB_*`, port `9000`), and `deploy-local.sh`/`buildAll` build `ankka-sidecar` with the other images.
- [X] T048 [US1] Write `docs/polyglot.md`, first version: the model in one paragraph, `uv add ankka` (path dependency for now), the entity, the endpoint, `docker compose --profile polyglot up`, `uv run example`, `curl`, `ankka console`, and the journal-portability walkthrough (start the Scala cart on the same database and `GET` the same cart).
- [X] T049 [US1] Prove S1.5 by hand and in a test: `PYT/test_journal_portable.py` (integration testkit, marked `slow`) writes a cart, then starts the Scala cart's image against the same Postgres and reads it through its HTTP; and the reverse. The Scala image tag comes from `ankka_version`, never a literal.

**Checkpoint**: the MVP. `sbt 'sidecar/testOnly *RemoteEntitySuite *RemoteEndpointSuite'` green;
`uv run test` green including `test_cart.py`; the quickstart's tier 1–2 and the compose
walkthrough in `docs/polyglot.md` done by hand once; SC-002's cart half proven.

---

## Phase 4: User Story 2 — Deployed like any other service (P2)

**Goal**: a descriptor with `hosting: process` deploys the Python cart with the sidecar injected;
`Ready`, exposed, scaled 1→3 without replacing the first pod, restarted with no refused request.

**Independent test**: quickstart tier 6: apply `PYX/service.json` to the local installation and
walk the commands; `SidecarClusterSuite` on k3s from an empty cluster.

### The descriptor and the control plane (contracts/descriptor-and-crd.md)

- [X] T050 [US2] In `API/descriptors.scala` add `hosting: String = "embedded"` and `protocol: Option[String] = None` to `ServiceSpec` with the refusals from the contract (`hosting must be…`, `protocol must be declared for process hosting`, `protocol is meaningful only for process hosting`, and `ANKKA_PROCESS_PORT`/`ANKKA_PROCESS_ADDRESS`/`ANKKA_SIDECAR_PORT`/`ANKKA_SIDECAR_ADDRESS` `is set by the platform`); add `Protocol.version` and `Compatibility.supportsProtocol` in `API/Compatibility.scala`.
- [X] T051 [P] [US2] Extend `controlplane-api/src/test/scala/.../api/DescriptorSuite.scala` and `CompatibilitySuite.scala`: every refusal in T050; `2.0` vs `1.x` unsupported; `1.0` vs `1.1` supported; `1.1` vs `1.0` not.
- [X] T052 [US2] In `CP/deploy/ServiceProjector.scala` apply `supportsProtocol` where `runtime` is checked, refusing as `ClusterView.Refused` → `Unavailable` with both versions; carry `hosting` into the `AnkkaServiceSpec` it writes; in `CP/application/ServiceRows.scala` and `API/descriptors.scala`'s service detail add `hosting` and `protocol` for display.
- [X] T053 [P] [US2] In `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/Output.scala` print `hosting` and `protocol` after `image` in `services get`, and warn on `apply` when `runtime` is declared with `hosting: process`; extend `cli`'s `OutputSuite`.
- [X] T054 [US2] Extend `controlplane/src/test/scala/.../ProjectorSuite.scala` (or the suite that covers the runtime check today): an unsupported protocol is `Unavailable` with both versions before any resource is written; a supported one projects `hosting: process`.

### The resource and the operator (research R8)

- [X] T055 [US2] Add `hosting: String = "embedded"` to `CRD/AnkkaService.scala`'s spec and to the CRD schema in `kustomization/components/crd/`; extend `crd`'s `AnkkaServiceCodecSuite` for the round trip.
- [X] T056 [US2] In `OP/Settings.scala` read `ANKKA_SIDECAR_IMAGE` (default `ankka-sidecar:<BuildInfo.version with + → ->`); in `kustomization/components/operator/deployment.yaml` set it explicitly to the same tag `deploy-local.sh` builds.
- [X] T057 [US2] In `OP/Rendering.scala`, for `hosting == "process"` render two containers per the contract's table: `runtime` (sidecar image, the existing env plus `ANKKA_PROCESS_ADDRESS` and `ANKKA_SIDECAR_PORT`, the descriptor's `ANTHROPIC_*`/`ANKKA_MODEL_*` env, `envFrom` the credential, the `http`/`management`/`remoting` ports, the readiness probe by name, `preStop`) and `app` (`spec.image`, the descriptor's other env plus `ANKKA_PROCESS_PORT` and `ANKKA_SIDECAR_ADDRESS`, no ports, no probe, `preStop`, fixed small resources); refuse with `Failed: operator has no sidecar image` when unset. Everything else (selector, strategy, Service, HTTPRoute, labels, init container) unchanged.
- [X] T058 [US2] In `OP/LifecycleRules.scala` fold readiness from the pod's `Ready` condition (already both containers) and report a not-ready `app` container distinctly in the status message (`app container not running`), so `services get` says which half is down.
- [X] T059 [P] [US2] Extend `OPT/RenderingSuite.scala`: two containers; the env split; `envFrom` on `runtime` only; the probe and ports on `runtime` only; `app` has neither; `embedded` renders exactly as before (a snapshot assertion on the existing single-container case); `hosting` change rolls the template but not the selector.

### The cluster proof

- [X] T060 [US2] Add the sidecar and the double's image to `operator/src/test/scala/.../ClusterImages.scala` (tags from `BuildInfo`, built by `testOnly` too), give `ProcessDouble` a `Main` and a `sidecar/src/test` Docker image target `ankka-process-double`, and add `sampleImageForClusterTests`-style task dependencies in `build.sbt` for both.
- [X] T061 [US2] Write `SCT/SidecarClusterSuite.scala` on k3s (gated on `-Dankka.cluster.tests`): deploy the double with `hosting: process` and assert two containers, `Ready`, a forwarded request 200; `crictl` kill the app container's process and assert the pod un-ready within a few seconds with the sidecar still a member (`/cluster/members` on management), 503 on a forwarded request, then `Ready` and 200 after the container restarts; scale 1→3 and assert the first pod's name survives; `services restart` under a request loop with 0 refusals; a `grpc` connection to `9010` and `9011` from another pod in the namespace refused; `kubectl exec app -- env` contains no `ANKKA_DB_*`; then deploy the Python cart's image (`PYX/Dockerfile`, built by `ClusterImages`) and repeat the `Ready` and request assertions.
- [X] T062 [US2] Extend `kustomization/deploy-local.sh` to build and `kind load` `ankka-sidecar`, and `README.md`'s deploy section to say so; `just up` unchanged in shape.

**Checkpoint**: S2.1–S2.6 proven on k3s and by hand on kind. The platform runs a polyglot
service; only entities and endpoints exist in Python.

---

## Phase 5: User Story 3 — Every component kind, not just entities (P3)

**Goal**: key value entities, views, consumers, timed actions and workflows over the protocol,
and the component client from inside the process, so the full shopping cart sample ports.

**Independent test**: port `CartRows` and `CheckoutNotifier` and add a checkout workflow to the
Python cart; run `PYX/test_cart.py` extended for them; `RemoteWorkflowSuite` and
`RemoteProjectionSuite` on the double.

### Runtime hosts

- [X] T063 [US3] Create `RT/remote/RemoteKeyValueHost.scala`: a `DurableStateBehavior` over `RemoteState(value: Option[Payload], deleted, expiryMillis)` with the same busy/stash/`pipeToSelf` pattern as T031, `Init{state}` on open, `new_state` stored when present; wire in `Ankka.host`.
- [X] T064 [P] [US3] Create `RT/remote/RemoteWorkflowHost.scala`: `WorkflowEngine[Payload]` unchanged; command handlers forwarded as `Command` → `Reply{new_state, transition, outcome}`; steps run by `InstanceSession.runStep` on the engine's schedule with `StepReply{new_state, next}` mapped to `StepSucceeded`/`StepFailed`, timeouts to `StepTimedOut`; wire in `Ankka.host`.
- [X] T065 [P] [US3] Create `RT/remote/RemoteProjection.scala`: view and consumer handler functions over `conversation.handleView`/`handleConsumer` (the current row passed to the view; `produce` published through the existing publisher), and extend `RT/ProjectionRuntime.scala` to host `RemoteViewDescriptor` and `RemoteConsumerDescriptor` with the same source resolution and offset store as Scala ones; view queries answered by `ViewClient` unchanged (rows are the process's JSON under `row_manifest`).
- [X] T066 [P] [US3] Extend `RT/TimerRuntime.scala`/`TimerSweeper` to invoke a `RemoteTimedActionDescriptor` through `conversation.invokeTimedAction` with the sweeper's existing retry and attempt counting; a gRPC error counts as a thrown handler.
- [X] T067 [US3] Write `SCT/RemoteWorkflowSuite.scala` (steps in order, step failure → compensate, a step that calls `Client.Invoke` with a child span, `restartService()` mid-pause resumes the pending step, a step that never replies times out per settings) and `SCT/RemoteProjectionSuite.scala` (view row updated/deleted/queried; consumer at-least-once in order per entity with offsets surviving restart; a timed action fires, and fires after the double restarts; key value set/get/delete/recover).

### The Python SDK

- [X] T068 [US3] Create `PY/effects/{key_value,workflow,view,consumer,timed_action}.py` with the builders in [contracts/python-sdk.md](./contracts/python-sdk.md) and `PY/key_value_entity.py`, `PY/workflow.py` (`@command`, `@query`, `@step(name)`, `StepEffect`), `PY/view.py` (`@on(source_event_type)` handlers, `@query(name)`), `PY/consumer.py`, `PY/timed_action.py`, each producing its discovery entry.
- [X] T069 [US3] Implement the `KeyValue`, `Workflow`, `View`, `Consumer`, `TimedAction` servicers in `PY/server.py` (per-stream state for the stateful ones as in T041; unary for the rest), and `views.query`/`timers.schedule`/`timers.cancel` in `PY/client.py`.
- [X] T070 [P] [US3] Extend `PY/testkit/unit.py` with `KeyValueTestKit`, `WorkflowTestKit` (`call`, `run_step`), `ViewTestKit`, `ConsumerTestKit`, `TimedActionTestKit`, and write `PYT/test_key_value.py`, `PYT/test_workflow.py`, `PYT/test_view_consumer.py`, `PYT/test_timed_action.py`.
- [X] T071 [US3] Port the rest of the sample: `PYX/cart_rows.py` (view `cart-rows`, query `by-id`), `PYX/checkout_notifier.py` (consumer), `PYX/checkout_workflow.py` (`checkout`: `reserve` → `charge` → `end`, `compensate` on failure, calling the entity through the client), the endpoint's `/carts/{id}/rows` and `/carts/{id}/checkout` routes, and extend `PYX/test_cart.py` on the integration testkit: the view row appears, the notifier records, the workflow completes and survives `restart()`.
- [X] T072 [US3] Extend `docs/polyglot.md` with one section per kind, each a dozen lines and a link to the sample file.

**Checkpoint**: the whole component model except agents is hosted in Python; the sample is
fully ported; S3.1–S3.7 proven on the double and the sample.

---

## Phase 6: User Story 4 — Agents without an agent loop in every language (P4)

**Goal**: an agent declared in Python with tools and guardrails; the loop, memory, compaction and
streaming in the sidecar; the process only runs tools.

**Independent test**: `RemoteAgentSuite` on the double with a scripted `TestModelProvider` in the
sidecar; the Python `assistant` example with the same scripted provider through the integration
testkit (`ANKKA_MODEL_SCRIPT` on the sidecar container, or a scripted-provider configuration —
decide in T073).

- [X] T073 [US4] Create `SC/RemoteAgent.scala`: an `Agent` subclass and `AgentDescriptor` built from an `AgentDetail` — one `HandlerBinding` per discovered handler whose invocation calls `Agent.Plan` and maps `AgentPlan` to an `AgentEffect` (model by name from the sidecar's configured providers, `FunctionTool`s whose invoker calls `InvokeTool`, `Guardrail`s whose check calls `CheckGuardrail`, memory `SESSION`/`NONE`, response shape), streaming handlers as `StreamHandle`s; a `Failure` is a `CommandError`. Configure providers in `sidecar/src/main/resources/application.conf` (`ankka.sidecar.models.<name>`: anthropic with `ANTHROPIC_API_KEY`, and a `scripted` provider reading `ANKKA_MODEL_SCRIPT` for tests).
- [X] T074 [US4] Extend `SC/Discovery.scala` to build `RemoteAgent` descriptors and `SC/Main.scala` to register `AgentRuntime` with the configured providers; `SC/ClientService.scala`'s `InvokeStream` already carries agent tokens — assert it.
- [X] T075 [US4] Write `SCT/RemoteAgentSuite.scala`: S4.1–S4.4 on the double — plan, tool invoked at the double with the model's arguments, tool error fed back and the loop continues, streaming tokens in order through the sidecar's SSE, a guardrail blocking output, a session that survives the double restarting (memory is the sidecar's entity), and `max_tool_call_steps` honoured.
- [X] T076 [US4] Create `PY/effects/agent.py` (`AgentEffect` builders: `system_message`, `user_message`, `context`, `tools`, `memory`, `guardrails`, `then_reply`, `then_reply_json`, `error`) and `PY/agent.py` (`Agent` base with `component_id`, `role`, `max_tool_call_steps`, `tools = {name: Tool(description, input_schema, run)}`, `guardrails = {name: Guardrail(stage, check)}`, `@command`, `@stream`), producing the discovery entry with tool schemas as JSON.
- [X] T077 [US4] Implement the `Agent` servicer in `PY/server.py` (`Plan` runs the handler and encodes the plan; `InvokeTool` decodes `arguments_json` into the tool's input dataclass and runs it, an exception → `error`; `CheckGuardrail` runs the check), and `PY/testkit/unit.py`'s `AgentTestKit` with a scripted model that fails loudly when the script runs out.
- [X] T078 [US4] Add `PYX/assistant.py` (agent `assistant`, tool `lookup` calling `get-cart` through the client, guardrail `no-secrets`), the `/carts/ask/{session}` and `/carts/stream/{session}` routes, `PYT/test_agent.py` on the unit testkit, and an integration case in `PYX/test_cart.py` with the scripted provider configured on the sidecar container.
- [X] T079 [US4] Extend `docs/polyglot.md` with the agent section, saying plainly that the model key lives on the sidecar and the process never calls a model.

**Checkpoint**: S4.1–S4.4 proven on the double and in Python; the strongest product argument
for the feature is demonstrable.

---

## Phase 7: User Story 5 — A second SDK can prove itself (P5)

**Goal**: one conformance suite, run from the sidecar's side, that defines a compatible SDK; it
passes against the Scala SDK in-process and against the Python SDK; a deliberately broken
behaviour is named.

**Independent test**: `sbt 'sidecar/testOnly *ConformanceSuite'` green; `uv run conformance`
green; break `es.refusal-persists-nothing` in the Python reference and see that name fail.

- [ ] T080 [US5] Create `SCT/ConformanceReference.scala`: the Scala reference service from [contracts/conformance.md](./contracts/conformance.md) — every component and every endpoint, registered on `AnkkaTestKit` — with a `conformance` entity and endpoint written as plainly as possible (it is documentation of the behaviours).
- [ ] T081 [US5] Create `SCT/ConformanceTarget.scala`: `InProcess` (starts T080 with `AnkkaTestKit`) and `Sidecar(address)` (starts `Main`'s wiring in-JVM against `AnkkaTestKit`'s Postgres and a running process), each exposing the HTTP base URL, `jdbcUrl`, `restart()`, the recorder for span assertions, and the scripted model provider; chosen by `-Dankka.conformance.target`.
- [ ] T082 [US5] Write `SCT/ConformanceSuite.scala`: one munit case per behaviour name in the contract, driving everything through HTTP and the journal; the `(process targets only)` cases skip in-process with a reason; `discovery.refuses-wrong-major` starts a second sidecar with `Protocol.version` overridden to `99.0` and asserts the refusal and the `ReportError` (read from the double's log or, for the Python target, from a `problems` endpoint the reference service exposes — add it to the contract's `conformance` endpoint as `GET /conformance/problems`).
- [ ] T083 [US5] Run it in-process and fix what fails in the Scala hosts or the suite until green; then run it against the double (`ProcessDouble.Main` with the reference `DoubleSpec`) and fix the sidecar until green.
- [ ] T084 [US5] Build the Python reference: extend `PYX` with the `conformance` entity and endpoint, `profile`, `checkout` (already), `reminder`, `assistant` (already), `private`; `sdks/python/scripts/conformance.py` starts it on 9010 and runs `sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.target=127.0.0.1:9010` from the repository root, exiting with sbt's status.
- [ ] T085 [US5] Run `uv run conformance` and fix the Python SDK until green; then break `refuse` to persist an event and confirm `es.refusal-persists-nothing` fails by name (and that the sidecar logged the violation); revert.
- [ ] T086 [US5] Add a `sdk-python` job to `.github/workflows/ci.yml`: `uv sync`, `uv run typecheck`, `uv run test`, `uv run conformance` (needs Docker and the sidecar image from `sbt sidecar/docker:publishLocal` in the same job), plus a step that `diff -r`s `protocol/` against `sdks/python/proto/`.

**Checkpoint**: FR-024/FR-025/FR-028 hold: one suite, two SDKs, both green; the fixtures and
the conformance suite are what "compatible" means.

---

## Phase 8: Polish and cross-cutting

- [ ] T087 Measure SC-003 with `SCT/LoopbackLatencySpike.scala` extended into the feature 007 harness shape: one request through the Python cart's endpoint (three hops) versus the Scala cart in-process, same machine; record both numbers and the console's unattributed time in `research.md`; if over 2×, the plan's follow-up is a batch of `Event`s per message on replay and a pooled channel per process — do not ship a workaround silently.
- [ ] T088 Measure SC-007: a person follows `docs/polyglot.md` from an empty directory with no JVM installed and times it; record the result and fix the doc until it is under fifteen minutes.
- [ ] T089 [P] Push `ankka-sidecar` on a release tag in `.github/workflows/release.yml` beside the operator and control plane images; `dockerSettings` already handles the tag.
- [ ] T090 [P] Update `README.md`: the component model table gains "Hosted in Python via the sidecar" per row; a "Polyglot services" section linking `docs/polyglot.md`; "Not implemented" gains "one non-Scala SDK; a third arrives through the conformance suite and the encoding fixtures"; the layout section gains `protocol/`, `sidecar/`, `sdks/python/`.
- [ ] T091 [P] Update `CLAUDE.md`: the module graph (`protocol` → nothing; `sidecar` ← runtime, http, agent, protocol), the commands (`sbt sidecar/test`, `uv run conformance`), and every trap found during Phases 1–7 (at minimum: forked `-D` forwarding for the new switches, `-Wunused` off for generated code, the primitive encodings that are not JSON, `host.docker.internal` on Linux, and whatever T004 and T006 taught).
- [ ] T092 [P] Update `kustomization/overlays/arrakis/kustomization.yaml`'s commented `images:` block and `RemoteOverlaySuite` for the sidecar image name.
- [ ] T093 Run `sbt scalafmtCheckAll scalafmtSbt`, `uv run typecheck`, then `caffeinate -i sbt buildAll` and `uv run conformance`; fix anything red; confirm `git diff --stat main -- '*Suite.scala'` lists only new files (FR-026).

---

## Dependencies

```
Phase 1 (verify, projects) ─→ Phase 2 (protocol, encoding, remote seam, sidecar and SDK skeletons)
                                   │
                                   └─→ US1 (entity + endpoint, Scala side then Python side) 🎯 MVP
                                         ├─→ US2 (descriptor, resource, operator, k3s)
                                         ├─→ US3 (remaining kinds)  ─┐
                                         └─→ US4 (agents)           ─┴─→ US5 (conformance, both SDKs)
Phase 8 after everything.
```

- **US1** depends only on Phase 2. It is the MVP: an entity and an endpoint in Python against a
  local sidecar, journal-portable with the Scala cart.
- **US2** depends on US1's sidecar and Python image; nothing in it needs US3 or US4.
- **US3** and **US4** depend on US1's host pattern and SDK base and are independent of each
  other and of US2 (they can be developed against compose while US2 lands on k3s).
- **US5** depends on US3 and US4: the reference service declares every kind. Its Scala half
  (T080–T083) can start once US3 and US4's Scala hosts exist, before their Python halves.

## Parallel execution examples

- Phase 1: T001 → T002 → T003/T004 in sequence on the JVM, while T005 → T006 (Python) and T007
  (Docker) run beside them; T008 any time after T001.
- Phase 2: T009 first, then T010–T012 in parallel; T014–T015 (encoding) beside T016–T019
  (`runtime`); T020–T024 (sidecar) after T016–T018; T025–T026 after T022; T027–T029 (Python)
  beside all of it, T028 after T015's fixtures exist.
- US1: the Scala side (T030–T037) and the Python side (T038–T045) are disjoint trees; T046–T049
  wait for both.
- US2: T050–T054 (control plane and CLI) beside T055–T059 (resource and operator); T060–T062
  after both.
- US3: T063, T064, T065, T066 in parallel; T068–T070 beside them; T071 after all.
- US3 and US4 in parallel with each other; US5's T080–T083 as soon as their Scala halves land.
- Phase 8: T089–T092 in parallel; T087, T088, T093 last.

## Implementation strategy

1. **Phase 1 first, honestly.** T002, T004 and T006 can each change the design of the host or
   the servicer; T003 tells you now whether SC-003 is realistic. Do not write the remote host on
   an assumed stash semantics.
2. **MVP = Phase 2 + US1.** A Python entity behind a Python endpoint, on a local sidecar,
   sharing a journal with the Scala cart. It is demonstrable, it exercises every risky part of
   the protocol, and everything after it is more of the same on proven seams.
3. **Prove the Scala side on the double before writing the Python side.** Every host and the
   endpoint are green on `ProcessDouble` first; the Python SDK then has a known-good far end to
   develop against, and a Python failure is a Python failure.
4. **US2 as soon as US1's image exists**, so the deployment path is proven while US3 and US4
   fill in the component model.
5. **US5 defines "done".** The conformance suite is written against the Scala reference first,
   which finds host bugs; then against the Python reference, which finds SDK bugs. The feature is
   complete when both are green and a deliberate break is named.
6. **Every phase ends green in `sbt -Dankka.cluster.tests=off test` and `uv run test`**, with the
   cluster suites on in US2 and Phase 8.
