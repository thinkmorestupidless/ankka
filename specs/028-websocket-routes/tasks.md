# Tasks: Socket Routes — A Long-Lived Connection on an Endpoint

**Input**: Design documents from `/specs/028-websocket-routes/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" list turns each fact read from
code or a dependency into a spike or a test before the code that relies on it. The scenarios are
in `features/sockets/`, `features/local-console/sockets.feature` and
`features/documentation/sockets.feature`; where a task says "case", it means a `test(...)` in the
named suite (or its equivalent in the SDK's test runner), named for the scenario it holds.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a Scala endpoint holds a socket), US2 (a Python or TypeScript endpoint holds
  a socket), US3 (a socket crosses the gateway and survives operations), US4 (the route is
  documented and its limits stated)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `RT`/`RTT` =
`modules/runtime/src/{main,test}/scala/…/runtime`; `HTTP`/`HTTPT` =
`modules/http/src/{main,test}/scala/…/http`; `SHIM` =
`modules/http/src/main/scala/org/apache/pekko/http/impl/engine/ws/AnkkaSocketUpgrade.scala`;
`TK`/`TKT` = `modules/testkit/src/{main,test}/scala/…/testkit`; `SC`/`SCT` =
`sidecar/src/{main,test}/scala/…/sidecar`; `CONF` = `SCT/conformance`; `OPT` =
`operator/src/test/scala/…/operator`; `API`/`APIT` =
`controlplane-api/src/{main,test}/scala/…/controlplane/api`; `CPT` =
`controlplane/src/test/scala/…/controlplane`; `CLI`/`CLIT` = `cli/src/{main,test}/scala/…/cli`;
`CART`/`CARTT` = `samples/shopping-cart/src/{main,test}/scala/shoppingcart`; `PROTO` =
`protocol/src/main/protobuf/ankka/protocol/v1`; `PY` = `sdks/python`; `TS` = `sdks/typescript`;
`RS` = `sdks/rust`; `DOCS` = `docs`. "R*n*" is a section of `research.md`; "V*n*" an item of its
*Verify first* list; a contract is named by its file under `contracts/`.

The branch `028-websocket-routes` exists, in the worktree `.claude/worktrees/028-websocket-routes`;
work there. Every `sbt` command below takes `-Dankka.cluster.tests=off` unless the task names a
k3s suite. A test client is the JDK's `java.net.http.WebSocket`; no dependency is added anywhere.
Every new suite that can see `testkit` (`SocketSuite`, `SocketFeatures`, `SocketAccessFeatures`,
`GatewaySocketSpike`) mixes in `LogCapturing`; the two in `http`'s tests cannot and rely on that
module's `logback-test.xml`. A case that asserts something is *absent* from the log attaches an
appender of its own to the root logger for the case and searches what it collected, since
captured output is not the test's to read.

---

## Phase 1: Setup — the ground is as the plan read it

**Purpose**: confirm the three facts every later phase leans on before writing against them.

- [X] T001 Confirm the baseline: `just features` reports no finding under `features/sockets/`, `features/local-console/sockets.feature`, `features/documentation/sockets.feature` or `GLOSSARY.md`; `git add -N features/sockets features/local-console/sockets.feature features/documentation/sockets.feature specs/028-websocket-routes && python3 .github/ci-coverage.py` passes (every new file is claimed by a filter that exists).
- [X] T002 Confirm the protocol's number: `grep -n 'val Version' RT/remote/Conversation.scala` reads `1.6` and `git fetch origin main && git diff HEAD origin/main -- protocol/ RT/remote/Conversation.scala` is empty. If `main` has moved to 1.9 for another feature, this feature is 1.8: change the number in `contracts/protocol.md`, `contracts/sdk-apis.md`, `data-model.md` and R11/R12 before any code, and read "1.9" below as that number.
- [X] T003 Confirm `-Dankka.spikes=on` reaches `http`'s forked tests: a one-line `assume(sys.props.get("ankka.spikes").contains("on"))` in a scratch test in `HTTPT`, run with and without the flag, then deleted. `build.sbt:139-141` forwards the property; the check is that `http` inherits the setting.

**Checkpoint**: nothing has changed; the numbers and the flag are known, not assumed.

---

## Phase 2: Foundational — the two spikes, and what every story reads

**Purpose**: V1 and V2 decide whether the design stands. The recorder, the variables and the settings are read by US1 and US2 alike.

**⚠️ CRITICAL**: if T004 cannot make a client read each close code, stop and take it to the user. R4 names the fallback (two codes) and it changes the spec, the glossary and several scenarios.

- [X] T004 V1: write `SHIM` in its first form and `HTTPT/SocketCloseCodeSpike.scala` (runs only with `-Dankka.spikes=on`). The spike binds a bare pekko-http server on `127.0.0.1:0` whose handler takes `request.attribute(AttributeKeys.webSocketUpgrade)`, asserts it is an `UpgradeToWebSocketLowLevel`, and upgrades through `AnkkaSocketUpgrade` with a message flow that completes after setting a chosen `(code, reason)`. A JDK `WebSocket` client connects once per code and asserts `onClose` reports exactly 1000, 1001, 1003, 1008, 1009 and 1011 with the reason text. Also assert the control: with no chosen code, a completed flow reads 1000. Confirms that ankka source in package `org.apache.pekko.http.impl.engine.ws` compiles under Scala 3 with `-Wunused` clean and reaches `private[http]` members.
- [X] T005 V2, in the same spike file: with `periodic-keep-alive-max-idle` set to 20s through `ServerSettings`, a socket with no frames is still open after 90 seconds and then carries a frame; with it left at `infinite`, the same socket is ended by pekko-http's 60-second `idle-timeout` and the client sees an error, not a close. Also record what the client sees when it offers only `ankka.bearer.x` and the server selects no subprotocol, and when it offers `ankka.socket` too (V4, for the JDK client; Node's is T041's).
- [X] T006 Record both results at the end of `research.md` under *Verify first* (confirmed, with what was observed) and correct R4 or R6 where the observation differs from what was read. If V1 failed, stop here.
- [X] T007 [P] Tests first, in `RTT/RecorderSuite.scala`: `reserve()` returns ids no `begin` returns; `record(...)` with a start in the past publishes a span a `snapshot()` shows at once, with that duration and outcome; a child begun with the reserved id as its parent is nested under it once it is recorded and reads as having no recorded parent before; `record` after the ring has wrapped still publishes. Then implement `reserve` and `record` in `RT/Recorder.scala` per `contracts/scala-api.md`, allocating nothing a `begin` does not.
- [X] T008 [P] `ANKKA_SOCKET_` in `CORE/PlatformVariables.scala`'s `RuntimeOnlyPrefixes`, tests first: a case each in `CORET/PlatformVariablesSuite.scala` (runtime-only, withheld from a module), `OPT/ProcessHostingRenderingSuite.scala` (a descriptor's `ANKKA_SOCKET_KEEP_ALIVE` is on the platform's container and not the process's), `CPT/PlatformDeclarationSuite.scala` and `APIT/DescriptorSuite.scala` (a descriptor that gives one is accepted), and `SCT/WasmHostSuite.scala` (a module asking for one is told it is not set). The operator compiles this same file; run `sbt operator/test` to see it still builds.
- [X] T009 [P] Add `ankka.http.socket { max-frame-size = 64KiB, unread-frames = 64, keep-alive = 20s }` to `modules/http/src/main/resources/reference.conf`, each with its `${?ANKKA_SOCKET_…}` line and a comment saying what exceeding it does, per `data-model.md` "Settings".

**Checkpoint**: the close codes are proved on the wire; the recorder can record a span whole; the three variables are the platform's. No route exists yet.

---

## Phase 3: User Story 1 — a Scala endpoint holds a socket (Priority: P1) 🎯 MVP

**Goal**: `socket("/stream") { socket => … }` on an `HttpEndpoint`, with the ACL decided at the opening request, blocking `receive()` and `send`, close reasons, limits, keep-alive, one span, a clean stop.

**Independent test**: `sbt http/test 'testkit/testOnly *SocketSuite *SocketFeatures *ShutdownOrderSuite' 'sidecar/testOnly *SocketAccessFeatures'` — every scenario of `features/sockets/frames.feature`, `access.feature` and `traces.feature` run by name.

### The test client

- [X] T010 [US1] `TK/TestSocket.scala` per `contracts/scala-api.md`: `open` returns `Left(Refused(status, headers))` for an opening request answered with anything but 101 (the JDK reports it as `WebSocketHandshakeException`; read the response from it) and `Right(socket)` otherwise; `receive` returns `None` once closed; `closed` returns the code and reason and **fails** when the connection ended without a close frame, so a cut-off socket can never read as closed. Add `socket(path, headers, subprotocols)` to `TK/AnkkaTestKit.scala` against the service's bound address.

### Tests first (each red before T018–T024)

- [X] T011 [P] [US1] `HTTPT/SocketRouteSuite.scala`, no server: a handler whose arity does not match its template throws at construction naming both; `validate` refuses a socket route on the template of a `get` and of an `sse` (`declares GET /x 2 times`) and accepts one beside a `post`; a socket route under `withAcl` carries that ACL; `describe` is `SOCKET <template>`; the `ServedRoute` is `("SOCKET", "<prefix><template>", true, <endpoint id>)`.
- [X] T012 [P] [US1] `TKT/SocketSuite.scala`, a service on `HttpServer.at("127.0.0.1", 0)`, one case per row of `contracts/scala-api.md` "The opening request": 401 with the same `WWW-Authenticate` a `get` on the same endpoint gives, 403, 503 with `Retry-After`, each with a counter proving no handler ran; 426 with `Upgrade: websocket` for a plain `GET`, and 403 not 426 when the ACL refuses a plain `GET`; 101 selecting `ankka.socket` only when offered. With a stub `Acl.Authenticate` that reads `Authorization`: the token is accepted from the header, from `ankka.bearer.<token>` when there is no header, and the header wins when both are present; the handler's `request.headers` has `Authorization` and never `Sec-WebSocket-Protocol`; the 101's headers never contain the token.
- [X] T013 [P] [US1] In `TKT/SocketSuite.scala`, the close reasons, each asserting the **number**: handler returns → 1000; handler throws → 1011 and the failure is logged once; a frame of `max-frame-size + 1` bytes → 1009 and the handler never receives it; a 2-byte character repeated to just over the bound → 1009 (bytes, not characters); a fragmented message under the bound arrives as one frame; `unread-frames + 1` frames to a handler that never calls `receive()` → 1008; a binary frame → 1003; `send` after the client closed throws `SocketClosed`; a handler that lets `SocketClosed` escape is not logged as a failure; a client that aborts its connection with no close frame has its handler's `receive()` return `None` and its span recorded. With pekko-http's `idle-timeout` set to 3s for the suite: keep-alive 1s keeps a silent socket open for 8s and a frame then crosses; keep-alive 10s fails startup naming both values.
- [X] T014 [US1] `TKT/sockets/SocketFeatures.scala`, a `GherkinSuite` over `features/sockets/frames.feature` and `features/sockets/traces.feature`, with step definitions in `TKT/sockets/SocketSteps.scala`. The thread scenario reads the platform thread count (the thread MX bean) before and after a thousand `TestSocket`s whose handlers wait, all opened through one shared JDK `HttpClient` so the client adds none of its own, and asserts a difference of at most eight. It first proves the measure can move: a thousand platform threads parked by the test itself must raise the same reading by about a thousand. The trace scenarios read the recorder after the socket closes and assert one root named `SOCKET /stream`, its children under it, and `ok` or `failed`.
- [X] T015 [P] [US1] A case in `TKT/ShutdownOrderSuite.scala`: with a socket open and its handler waiting, running coordinated shutdown alone closes the client with 1001 "going away", the handler's `receive()` returns `None`, and the client is not cut off (V3).
- [X] T016 [P] [US1] `HTTPT/SocketCallerSuite.scala` over `TlsServing`, for the two named-service scenarios of `features/sockets/access.feature`: a client with the certificate of `checkout` opens a socket on a route admitting only `checkout` and the handler reads that caller; one with `billing`'s is answered 403 and no handler runs. And for `features/sockets/deployed.feature`'s "a browser's token opens an authenticated socket route at an exposed service's hostname": a client holding the **gateway's** certificate (the one `TlsServerSuite`'s "the gateway's certificate reads as the internet" uses) offers `ankka.socket` and `ankka.bearer.<token>` to a route under an `Acl.Authenticate`, and the handler reads the principal the token names and `Caller.Gateway`. The client is a JDK `HttpClient` with `RotatingTls(dir, 1.minute).sslContext`, as `TlsServerSuite` builds one.
- [X] T017 [P] [US1] `SCT/sockets/SocketAccessFeatures.scala`, a `GherkinSuite` over `features/sockets/access.feature` against an embedded service whose ACL is the real `Oidc` verifier over `TestIssuer` (`sidecar` is the project that sees both `testkit` and `auth-oidc`'s tests): the principal told, the same principal after each frame, the browser's token as a subprotocol with the recorder's and the log's content searched for the token, the socket still open after the token's expiry has passed, the challenge with no token, the forbidden authenticator. The two named-service scenarios are marked as run in `SocketCallerSuite`. It needs only T010 to be written, and `Socket` and `socket(...)` (T018, T019) to compile; write it with the others and see it red once those two are in.

### Implementation

- [X] T018 [US1] `HTTP/Socket.scala`: `Socket`, `SocketClosed`, and `CloseReason` with its name and code per `data-model.md`.
- [X] T019 [US1] `HTTP/HttpEndpoint.scala`: `SocketRoute(template, run, acl)` with `describe`, the three `socket` overloads through `FromPath` as `sse` takes its argument, `socketRoutes`, and the arity check `addStream` has.
- [X] T020 [US1] `SHIM` in its final form from T004: `respond(upgrade, messages, subprotocol, chosen: () => Option[CloseReason], settings, log): HttpResponse`, falling back to `upgrade.handleMessages` when the attribute is not the low-level class. A header comment says why the file is in this package, names the five internal symbols, and points at the suite that holds them.
- [X] T021 [US1] `HTTP/Sockets.scala`: the bridge of R3 and the limits of R5 — strict and streamed text reduced to whole frames under `limitWeighted` by UTF-8 bytes; the bounded inbound queue that `receive()` takes from and that sets "unread" when full; the outbound `Source.queue` that `send` offers to and waits on; the close reason set once, by whoever ends the socket first; a registry of open sockets with `closeAll(reason, within)`. No `ActorContext` is touched; stream callbacks only offer to queues.
- [X] T022 [US1] `HTTP/HttpServer.scala`: `validate` counts a socket route as `("GET", template)`; `Matched.Socket`, consulted for a `GET`; the context for a socket route moves an offered `ankka.bearer.<token>` to `Authorization` when there is no header and drops `Sec-WebSocket-Protocol`; `admit` unchanged and first; then 426 or the upgrade through `SHIM`, selecting `ankka.socket` when offered; the handler run once on `AnkkaExecutors.virtual` inside `RequestScope.withContext`; the upgrade built with `ServerSettings(system).websocketSettings` carrying the keep-alive (see *Results* in `research.md`); startup refused when keep-alive is not under pekko-http's idle timeout; socket routes in the startup log, in `served`, in the "nothing is reachable" warning and in `namesCallers`; `unmatched` knows a socket route's path exists.
- [X] T023 [US1] The span, in `HTTP/HttpServer.scala`'s `Tracing`: `socket(describe, origin)(body)` reserves an id, runs the body inside `Trace.within`, and records at the end with the start it noted — `Ok` on return or an escaping `SocketClosed`, `Failed` otherwise. The handler name interned is the template's, never a path with its parameters filled in.
- [X] T024 [US1] `HttpServer.stop()`: unbind, `closeAll(GoingAway, 2.seconds)`, then `terminate(5.seconds)` as today.
- [X] T025 [US1] The sample: `socket("/{cartId}/watch")` on `CART/api/ShoppingCartEndpoint.scala` — each "refresh" frame is answered with the cart as JSON, anything else with an error line — between `// docs:start socket` and `// docs:end socket`, with a case in `CARTT/ShoppingCartHttpSuite.scala` using `TestSocket`. And `socket("/socket")` on `CART/api/CallersEndpoint.scala`, which answers each frame with who is calling in the words `/callers/whoami` uses — that endpoint exists so the platform's suites have a real service to point at — with a case in `CARTT/CallersSuite.scala`.
- [X] T026 [US1] Run the story's independent test, `sbt compile` (warning-free) and `sbt scalafmtAll`. Break one thing and watch a case go red, then restore it: remove the `admit` call before the upgrade and see T012's 401 case fail.

**Checkpoint**: a Scala service holds sockets, on a laptop, tested against a real server. Nothing on the wire to a process has changed.

---

## Phase 4: User Story 2 — a Python or TypeScript endpoint holds a socket (Priority: P1)

**Goal**: the same route declared in Python and TypeScript, relayed by the sidecar over `Http.HandleSocket`; refused for a module; refused across a version gap from either end.

**Independent test**: `sbt 'sidecar/testOnly *ProtocolSuite *RemoteEndpointSuite *WasmHostSuite'`, then `ANKKA_CONFORMANCE_ONLY='*socket.*'` with `uv run conformance` and `npm run conformance`.

### Protocol 1.9 and the sidecar

- [X] T027 [US2] Edit `PROTO/endpoint.proto` and `PROTO/discovery.proto` per `contracts/protocol.md`; say 1.9 and what it added in `protocol/README.md`; set `WireProtocol.Version` in `RT/remote/Conversation.scala` and `Protocol.version` (with its history comment) in `API/Compatibility.scala`; update `APIT/CompatibilitySuite.scala`, `APIT/HostingSuite.scala:120` and the version assertion at `SCT/ProtocolSuite.scala:147`. Then `grep -rn '"1\.6"' --include='*.scala' --include='*.py' --include='*.ts' --include='*.rs' .` and account for every remaining hit: the secret store's own "since 1.6" constants and tests stay, anything stating the *current* version moves. Copy the protocol into the three SDKs with their own scripts (`cd sdks/python && uv run python scripts/proto.py`; `cd sdks/typescript && npm run proto`; `sdks/rust/scripts/proto.sh`) and confirm the `diff -r` lines of `.github/workflows/ci.yml` report nothing.
- [X] T028 [US2] Tests first. Teach `SCT/ProcessDouble.scala` `HandleSocket`: a route may script what it does with each inbound message (echo, send *n* then complete, fail, never read, send a message with no case set), and every inbound message is recorded. Cases in `SCT/ProtocolSuite.scala`: `open` is first and carries path arguments, query, headers, principal, caller, and metadata with the trace, `ankka-caller` and `ankka.protocol`; frames cross in order both ways; the client closing sends `closed` then half-closes; `completed` closes the client 1000 and `failed` 1011; a message with no case closes it 1011; discovery refuses a socket route that is not `GET`, has a body, or also streams, and refuses one under `protocol_version` 1.6 naming the route and both versions, while a 1.6 spec with no socket route is admitted.
- [X] T029 [US2] Cases in `SCT/RemoteEndpointSuite.scala`, over real HTTP: a process stopped mid-socket closes the client 1011, and a socket opened after `restart()` is served; a process that never reads, sent 16 KiB frames, closes the client 1008 with fewer than a bounded number buffered in the sidecar (V5); a `max-frame-size` over 3 MiB fails the sidecar's startup naming it and the gRPC bound; the sidecar's HTTP server stopped with a socket open closes the client 1001 and the double has received `closed` with the reason `going away`, then the half-close.
- [X] T030 [US2] `RT/remote/Conversation.scala`: `SocketLink` and `openSocket(request: HttpForward): SocketLink` in plain values, per R13. `SC/GrpcConversation.scala`: the link over `http.handleSocket`, sending `open` first, forwarding a frame only when the call is ready, mapping `completed`, `failed`, `onCompleted`, `onError` and an unset case. `SC/Translate.scala`: the messages.
- [X] T031 [US2] `SC/RemoteEndpoint.scala`: a discovered route with `socket` becomes a `SocketRoute` whose handler relays on two virtual threads and returns or throws as the process completes or fails; the open's metadata is `forwardOf`'s plus `ankka.protocol`. `SC/Discovery.scala`: the three shape rules and the minor gate. The sidecar's startup check on `max-frame-size`.
- [X] T032 [P] [US2] The module: a case in `SCT/WasmHostSuite.scala` (a discovery message built by hand with `socket` set is refused with the message of R14, naming the route), then the refusal in `SC/wasm/WasmDiscovery.scala` and the backstop in `SC/wasm/WasmConversation.scala`.
- [X] T033 [US2] `CONF/ConformanceReference.scala`: the four routes of `contracts/protocol.md`. `CONF/ConformanceSuite.scala`: the nine `socket.*` cases, guarded by `onlyWhereStreaming()` except `socket.refused-for-module`, using `TestSocket` against `target.baseUrl` and `ConformanceTarget.issuer` for tokens. Green with `sbt 'sidecar/testOnly *ConformanceSuite -- *socket.*'`; confirm the run reports nine tests, not zero.

### Python (independent of TypeScript)

- [X] T034 [US2] Tests first, in `PY/tests/test_endpoint.py` and a new `PY/tests/test_server_socket.py` (the real `Server` and an in-process `grpc.aio` client, as `test_server_stream.py`): `@socket` marks the route in discovery; the handler's `Socket` iterates frames and `send` reaches the client; `self.request` answers for the opening request after several frames; returning sends `completed`, raising sends `failed`, `closed` from the runtime ends the iteration; the request stream is read only as the handler asks; discovery fails naming 1.9 when `SidecarInfo.protocol_version` is 1.6 and the service has a socket route, and succeeds for a service without one; `Handle` on a socket route answers a failure naming 1.9; a socket route with a body parameter is refused at declaration.
- [X] T035 [US2] `PY/src/ankka/endpoint.py` (`socket`, `Socket`, `SocketClosed`, `RouteSpec.socket`, `to_pb`), `PY/src/ankka/server.py` (`HandleSocket`, the discovery check, the `Handle` backstop), `PY/src/ankka/service.py` (`PROTOCOL_VERSION = "1.9"`), `PY/src/ankka/testkit/unit.py` (the socket double), per `contracts/sdk-apis.md`.
- [X] T036 [US2] `PY/examples/shopping_cart/conformance.py`: the four conformance routes. `PY/examples/shopping_cart/endpoint.py`: `/carts/{cartId}/watch` between `# docs:start socket` and `# docs:end socket`, with a unit test through the double. Run `uv run pytest -q && uv run mypy && ANKKA_CONFORMANCE_ONLY='*socket.*' uv run conformance`; read the run's first lines for the target it used.

### TypeScript (independent of Python)

- [X] T037 [US2] Tests first, in `TS/test/endpoint.test.ts` and a new `TS/test/server-socket.test.ts` (through `startServer` and the `Conversation` harness of `test/helpers.ts`): the same list as T034, plus the request iterable implementing `return` and `throw`, and every server the test starts destroying its sessions before `close()`.
- [X] T038 [US2] `TS/src/routes.ts` (`socket`, `RouteRef.socket`), a new `TS/src/socket.ts` (`Socket`, `SocketClosed`), `TS/src/server/http.ts` (`handleSocket`, registered beside `handle` and `handleStream`), `TS/src/spec.ts` (`PROTOCOL_VERSION = "1.9"`, `socket` in `renderSpec`), `TS/src/server/discovery.ts` (the check), `TS/src/testkit/unit.ts` (the double), exported from the package's entry points. Erasable syntax only.
- [X] T039 [US2] `TS/examples/shopping-cart/conformance.ts`: the four routes. `TS/examples/shopping-cart/endpoint.ts`: the watch route between `// docs:start socket` and `// docs:end socket`, with a unit test. Run `npm run typecheck && npm test && ANKKA_CONFORMANCE_ONLY='*socket.*' npm run conformance` on Node 22 and 24.

### Rust (nothing to build)

- [X] T040 [P] [US2] `RS/ankka/src/service.rs`: `PROTOCOL_VERSION = "1.9"`, since the crate's protocol copy now is. Run `cargo test --workspace && ./conformance.sh` and read both runs' first lines (stateless, then stateful): nothing new passes, nothing breaks, and the `socket.*` cases report as skipped for a module.

**Checkpoint**: the same socket route works in three languages through one implementation of every rule; a module and a version gap are refused by name.

---

## Phase 5: User Story 3 — a socket crosses the gateway and survives operations (Priority: P2)

**Goal**: a browser opens a socket at an exposed service's hostname with its token; it stays open idle; a restart closes it "going away"; the route is listed where routes are listed.

**Independent test**: `sbt 'runtime/testOnly *TopologyJsonSuite' 'cli/testOnly *ConsoleServerSuite *OutputSuite'`, then `caffeinate -i sbt 'controlPlane/testOnly *ExposureClusterSuite'`.

### Listings (offline)

- [X] T041 [P] [US3] The local console, tests first: a case in `RTT/TopologyJsonSuite.scala` (a socket route is the handler `SOCKET /notices/stream`, type `route`) and one for the service document's `{"method":"SOCKET",…}`; a case in `CLIT/ConsoleServerSuite.scala` (the console's invoke routes refuse a `SOCKET` route as they refuse `GRPC`); a case in `CLIT/ConsoleTopologyJsSuite.scala` or beside it for the page listing it with no form. Then `cli/src/main/resources/console/app.js` and `CLI/console/ConsoleServer.scala`. Record with Node's `WebSocket` what a client that offers only the bearer subprotocol sees (V4), in `research.md`.
- [X] T042 [P] [US3] The topology scenario of `features/sockets/deployed.feature`: a case in `CLIT/OutputSuite.scala` (`services topology` prints `SOCKET /notices/stream` beside `GET /notices`) and a step in the fast deployed-topology features (`CPT/DeployedTopologyFeatures.scala`) or a case beside them, asserting the merged topology of two instances lists the socket route once. And a case in `console/package/test/` (beside `topology-layout.test.ts`, or in the fixtures test) that a node whose handlers include `SOCKET /notices/stream` renders that name on the topology page's table, run with `just test-console`. No wire type changes; if one seems to need to, stop and re-read R10.

### The gateway (k3s)

- [ ] T043 [US3] `CPT/SocketProbe.scala`: a `main` that opens a socket with the JDK client (URL, CA file, subprotocols, a script of sends and waits) and prints one line per event — each frame, and `closed <code>` or `cut off`. It is run as a subprocess with `-Djdk.net.hosts.file`, as `ControlPlaneClusterSuite` runs the CLI, never in the test JVM.
- [ ] T044 [US3] V6 (folded into T046 in implementation: `ExposureClusterSuite` already deploys the real cart exposed behind the gateway, so its first socket case exchanges a frame every five seconds for twenty seconds — past the fifteen-second route timeout — and its quiet socket waits ten minutes, past Envoy's five-minute stream idle timeout; a separate spike would start a second cluster to learn the same thing): `CPT/GatewaySocketSpike.scala` (runs only with `-Dankka.spikes=on`): deploy the real cart exposed, open `/callers/socket` through the gateway, exchange a frame every five seconds for a minute, then nothing for six minutes, then a frame. Run `caffeinate -i sbt -Dankka.spikes=on 'controlPlane/testOnly *GatewaySocketSpike'` and record the result in `research.md`.
- [ ] T045 [US3] Only if T044 showed the route's own timeout ending an upgraded connection: `timeouts.request: "0s"` on the HTTP rule in `operator/src/main/scala/…/operator/Rendering.scala`, `OPT/RenderingGoldenSuite.scala` updated, and `contracts/platform.md` and R17 corrected. Otherwise mark this task done with "not needed" and the observation.
- [ ] T046 [US3] Cases in `CPT/ExposureClusterSuite.scala`, named for the scenarios of `features/sockets/deployed.feature`, against the real cart's `/callers/socket` (open to everyone, so no issuer is needed): a socket opened at the hostname offering `ankka.socket` and `ankka.bearer.x` is answered 101 **selecting `ankka.socket`** — which the gateway can only have carried both ways — the handler answers that its caller is the internet through the gateway, and three frames cross each way in order; a socket opened in the suite's setup, asserted in its last case once ten minutes have passed (waiting only the remainder) by sending a frame and reading the answer; `services restart` closing an open socket with **1001** and a socket opened at once answered; an unexposed service giving the probe nothing to connect to. The scenario "a browser's token opens an authenticated socket route at an exposed service's hostname" is marked as run in `SocketCallerSuite` (T016): no suite has an issuer, the gateway and an authenticated socket route at once, and the cart is open by design. Deploy the real image for the cart only.
- [ ] T047 [US3] Run `caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *ExposureClusterSuite'` awake. Read a failure whose duration is absurd as the machine's, and rerun it.

**Checkpoint**: the socket works where a deployed service is reached, and is listed where its other routes are.

---

## Phase 6: User Story 4 — the route is documented and its limits stated (Priority: P3)

**Goal**: a developer finds the route beside SSE, with a tested sample per language, and the limits.

**Independent test**: `just docs` passes; each scenario of `features/documentation/sockets.feature` can be answered from the built pages.

- [X] T048 [US4] `DOCS/build/http-endpoints.md`: a section on sockets beside the routes table — the declaration in Scala, Python and TypeScript, each `<!-- include: …#socket -->` from T025, T036 and T039; that the ACL is decided when the socket is opened; how a browser sends its token (`new WebSocket(url, ["ankka.socket", "ankka.bearer." + token])`, and to offer both); the close reasons with their codes; that the handler reads the opening request for the socket's whole life. A pointer from `DOCS/build/streaming.md`. Run `just docs-sync`.
- [X] T049 [P] [US4] `DOCS/reference/limitations.md`: frames are text only; a module cannot declare a socket route; the platform keeps no record of who holds a socket open and keeps no frame; a client that vanishes is noticed only when its connection fails; a socket route is not reached under a mount, replacing the sentence that says WebSockets do not work with where a browser opens the socket instead. The same in `DOCS/reference/web-hosting.md`.
- [X] T050 [P] [US4] `DOCS/reference/configuration.md`: prose for `ANKKA_SOCKET_MAX_FRAME_SIZE`, `ANKKA_SOCKET_UNREAD_FRAMES` and `ANKKA_SOCKET_KEEP_ALIVE` (the coverage check fails without each), then `just docs-sync` for the generated table. `DOCS/reference/sidecar-protocol.md`: `HandleSocket` in prose beside `HandleStream`, 1.9 in the versioning section, the generated table synced. `DOCS/reference/wasm-abi.md`: a socket route beside a streaming route in what a module cannot declare.
- [X] T051 [P] [US4] `DOCS/reference/scala-sdk.md`, `python-sdk.md`, `typescript-sdk.md` (the declaration, `Socket`, the test kits' doubles and `TestSocket`) and `DOCS/reference/glossary.md` (socket, socket route, frame, close reason, as `GLOSSARY.md` has them).
- [X] T052 [US4] `just docs-sync && just docs`; the skills rendered again into `marketplace/` and `ankka.g8/` (with `$` escaped in the template's copy); `sbt -Dankka.template.tests=off 'cli/testOnly *McpServerSuite'` for the pages the CLI serves. Read each scenario of `features/documentation/sockets.feature` against the built site and mark it.
- [X] T053 [US4] `CLAUDE.md`: the protocol's number where it says 1.3; the component hosting table's HTTP endpoint row naming the three route kinds; under *Traps*, in the file's own style: pekko-http's public WebSocket API sends two close codes and where the others come from; pekko-http cuts an idle connection at sixty seconds; a span begun and held is skipped and then overwritten; `http` tests cannot see `testkit`, so the server's suites are in `testkit` and the issuer's in `sidecar`.

**Checkpoint**: the feature is usable by someone who has not read the source.

---

## Phase 7: The whole build

- [ ] T054 `just features` with no finding in the socket features, and the count of scenarios it read risen by 45; `python3 .github/ci-coverage.py`.
- [ ] T055 `sbt scalafmtCheckAll scalafmtSbtCheck && sbt compile` warning-free, then `sbt -Dankka.cluster.tests=off test`; the SDK lines of `quickstart.md` run 4 in full, not filtered.
- [ ] T056 `quickstart.md` run 7 by hand: the cart on a laptop, Node's `WebSocket`, `closed 1001` on Ctrl-C, the route in the local console with no form.
- [ ] T057 `caffeinate -i sbt buildAll`, awake. Then the release note of `plan.md`'s last paragraph in the pull request's description: the protocol's minor version rises.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (1)** → nothing.
- **Foundational (2)** → Setup. T004 blocks everything; T007, T008 and T009 are independent of each other and of the spikes.
- **US1 (3)** → Foundational.
- **US2 (4)** → US1 (the sidecar builds US1's route kind) and T008. Within it, T027–T033 block the two SDK groups; T032 needs only T027.
- **US3 (5)** → US1 for everything; T041 and T042 need nothing else. The cluster cases need T025's sample route. Independent of US2.
- **US4 (6)** → T025, T036 and T039 for the includes; T045's outcome for what the platform page says.
- **Whole build (7)** → everything.

### Within a story

Tests before the code they hold; a spike before the design it could overturn (T004, T005, T044).

### Parallel opportunities

- T007, T008 and T009 together, beside the spikes.
- T011, T012, T013, T015, T016 and T017 together once T010 is in.
- After T033: the Python line (T034–T036) and the TypeScript line (T037–T039); T040 beside both.
- US3's listings (T041, T042) beside US2, by a second person, once US1 is merged.
- T049, T050 and T051 together.

### Parallel example: User Story 2 after the sidecar is in

```text
Line A: T034 → T035 → T036      (Python)
Line B: T037 → T038 → T039      (TypeScript)
Line C: T040                    (Rust: the number and a run)
Line D: T041, T042              (US3's listings)
```

---

## Implementation Strategy

**MVP**: Phases 1, 2 and 3 — User Story 1. A Scala service holds sockets with every rule in place, tested against a real server on loopback. It touches no wire to a process, no operator and no control plane.

**Then, each a mergeable step**:

1. US2's protocol and sidecar (T027–T033), then each SDK as it is ready.
2. US3's listings (T041–T042), then the gateway spike and the cluster cases (T043–T047).
3. Documentation (Phase 6) and the whole build (Phase 7).

## Notes

- A task that says "tests first" is not done until the case has been seen red for the right reason and green after.
- Assert a close **code**, never only that a socket closed; `TestSocket.closed` failing on a cut-off socket is what keeps "closed" from meaning "gone".
- Read what a run says it ran. A munit filter needs its leading `*`; the conformance runs print their target; a k3s suite that takes hours has been run on a sleeping laptop.
- Never log, record or echo a token. T012 and T017 assert it for the response, the handler's headers, the recorder and the log.
- The five pekko-http internals stay in `SHIM`. If a second file needs one, the design has drifted from R4.
