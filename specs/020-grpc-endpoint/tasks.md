# Tasks: gRPC Endpoints — A Second Way Into a Service

**Input**: Design documents from `/specs/020-grpc-endpoint/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included. This repository's rule is that every acceptance scenario ends up as a test
that fails without the feature, and the scenarios are living features: those under `features/grpc/`
are run as written by `GherkinSuite`; those under `features/grpc-deployed/`,
`features/local-console/` and `features/documentation/` are tests named after them. Where a task
says "case", it means a `test(...)` in the named suite.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (serve a gRPC API), US2 (decide who may call), US3 (deploy), US4 (reach it from
  outside the cluster), US5 (call another service), US6 (stream in either direction), US7 (replace
  and add instances), US8 (traces and the local console), US9 (reflection), US10 (documentation)

Paths are repository-relative. Abbreviations: `GR` =
`modules/grpc/src/main/scala/com/thinkmorestupidless/ankka/grpc`, `GRT` =
`modules/grpc/src/test/scala/com/thinkmorestupidless/ankka/grpc`, `FX` = `modules/grpc-fixtures`;
`RT` = `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime`, `RTT` its
`src/test` twin; `HT` = `modules/http/src/main/scala/com/thinkmorestupidless/ankka/http`, `HTT`
its `src/test` twin; `API` =
`controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api`, `APIT` its
`src/test` twin; `CP` = `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane`,
`CPT` its `src/test` twin; `OP` = `operator/src/main/scala/com/thinkmorestupidless/ankka/operator`,
`OPT` its `src/test` twin; `SC` = `samples/shopping-cart`; `DOCS` = `docs`. "R*n*" is a section of
`research.md`; a contract is named by its file.

Three rules hold for every task:

- **No suite binds a fixed port**: `GrpcServer.at("127.0.0.1", 0)` and `HttpServer.at("127.0.0.1", 0)`.
- **Stage this feature's paths by name** on every commit; never `git add -A`.
- **A scenario not yet implemented is tagged, never deleted**: T009 tags every feature under
  `features/grpc/` `@ignore`, and the task that implements a file removes its tag. T072 fails if
  one is left.

---

## Phase 1: Setup — commit the design, pin what is rendered today, bump the dependency

**Purpose**: three things that must be true before a line of the feature exists: CI accepts the
living features, what the operator renders today is on record, and the build runs on the grpc-java
the feature needs.

- [X] T001 Make the first commit on `020-grpc-endpoint` (the branch exists, in the worktree `.claude/worktrees/020-grpc-endpoint`, and nothing is committed). Add `'features/**'` and `'GLOSSARY.md'` to the `scala` filter in `.github/workflows/ci.yml` (R19), run `python3 .github/ci-coverage.py` and see it pass with the new files staged. Commit `specs/020-grpc-endpoint/`, `features/`, `GLOSSARY.md`, `.specify/feature.json` and `.github/workflows/ci.yml`, by name.
- [X] T002 Pin what the operator renders **before** any operator change (SC-006, R18). Write `OPT/RenderingGoldenSuite.scala` and fixtures under `operator/src/test/resources/golden/`: for six resources — embedded with the default port; `port = None`; exposed with a base domain; `hosting = "process"`; `hosting = "wasm"`; a supplied database (`provisionDatabase = false`) — call `Rendering.render` and write every `Action`'s object as YAML, one file per resource, through the serializer `AnkkaServiceCodecSuite` uses. The suite asserts today's rendering equals the committed files byte for byte, and rewrites them only under `-Dankka.golden.update=true` (forward that property in `build.sbt`'s `Test / javaOptions`, beside `ankka.docs.update`). Run it green on unmodified code and commit the fixtures. To see it can fail: add a label in `Rendering.service`, watch it go red, revert.
- [X] T003 Bump grpc-java for the whole build (R2). In `project/plugins.sbt` set the ScalaPB `compilerplugin` to `0.11.20`. In `project/Dependencies.scala` add `V.grpc = "1.84.0"`, define `grpcNettyShaded` and `grpcStub` from it instead of `scalapb.compiler.Version.grpcJavaVersion`, add `grpcProtobuf` and `grpcServices` at the same version, and rewrite the comment above them to say why the version is named here. Prove it: `sbt protocol/compile sidecar/test`; then build the image the SDK suites start, explicitly — `sbt -Dankka.cluster.tests=off sidecar/docker:publishLocal` — and confirm it carries the new version before trusting anything that runs against it: `docker run --rm --entrypoint ls ankka-sidecar:latest /opt/docker/lib` lists `io.grpc.grpc-netty-shaded-1.84.0.jar` and no other grpc-netty-shaded. An unreleased SDK starts `ankka-sidecar:latest`, so a suite run against an image built before the bump proves nothing. Then `cd sdks/python && uv sync && uv run pytest -q && uv run conformance`, `cd sdks/typescript && npm ci && npm run proto && npm test && npm run conformance`, `cd sdks/rust && cargo test --workspace && ./conformance.sh`. All must pass unchanged. If one fails on the grpc-java version, step `V.grpc` down to the newest release it passes on, no lower than `1.66.0`, and record the version and the failure in `research.md` R2.
- [X] T004 Create the two projects in `build.sbt` and add both to the root `aggregate`. `grpc` at `modules/grpc`: `.dependsOn(core, sdk, runtime, http, grpcFixtures % Test, testkit % Test, testPki % Test)`, `commonSettings`, `name := "ankka-grpc"`, `libraryDependencies ++= Seq(grpcNettyShaded, grpcStub, grpcProtobuf, grpcServices)`, published (no `publish / skip`). `grpcFixtures` at `modules/grpc-fixtures`: `publish / skip := true`, the `protocol` project's `scalacOptions` and `PB.targets` (`build.sbt:434-455`), `libraryDependencies` `scalapbRuntime % "protobuf"`, `scalapbRuntime`, `scalapbRuntimeGrpc`. Write `FX/src/main/protobuf/ankka/fixtures/v1/cart.proto`, package `ankka.fixtures.v1`: `CartService { GetCart, AddItem }` (unary), `CartStreams { WatchCart (server stream), ImportItems (client stream), Converse (both) }`, `OrderService { GetOrder }`, with small request and reply messages. Write `modules/grpc/src/main/resources/reference.conf` with every `ankka.grpc.*` key of contract `descriptor-and-configuration.md`. `sbt grpc/compile grpcFixtures/compile` is warning-free.

**Checkpoint**: the first commit is on the branch; `RenderingGoldenSuite` is green on unmodified
rendering; the sidecar and three SDK suites pass on the bumped versions; both projects compile.

---

## Phase 2: Foundational — what every story imports

**Purpose**: the pieces below the endpoint, and the TLS spike that gates how the server is built.

- [X] T005 [P] Add `CommandError.from(t: Throwable): Option[CommandError]` to `modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/CommandError.scala`: `t` when it is one, else `t.getCause` when that is one, else `None` — one level, never a loop. Add cases to the core suite that covers `CommandError` (or `modules/core/src/test/scala/com/thinkmorestupidless/ankka/core/CommandErrorSuite.scala` if none does): itself, a wrapped one, an unrelated exception, a `null` cause.
- [X] T006 [P] In `HT/RequestContext.scala` widen `RequestScope` from `private[http]` to `private[ankka]`; in `HT/Caller.scala` widen `LocalCallers.callerFrom` likewise; in `HT/HttpServer.scala` widen `Tracing` likewise, and in both `recover` blocks of `Router` add a case, after the existing `CommandError` one, for a failure with `CommandError.from(failure)` defined, answered as that error (R13, R14). No behaviour changes for any existing input. Add a case to `HTT/ResponseSuite.scala`: a handler throwing `RuntimeException("x", CommandError("gone", ErrorCode.NotFound))` is answered 404 with `gone`. `sbt http/test` green.
- [X] T007 [P] Add the rotating managers to `RT/RotatingTls.scala` (R4): `def keyManager: X509ExtendedKeyManager`, `def trustManager: X509ExtendedTrustManager`, `def trustManagerRequiring(uri: String): X509ExtendedTrustManager`. Each is one object for the life of the `RotatingTls`, delegating every method to the managers of `refreshed()` at the time it is called — `trustManager` to the authority's (`Loaded.trustManagers`), `trustManagerRequiring` to those wrapped in the existing `RequiredIdentityTrustManager`. Add cases to `RTT/RotatingTlsSuite.scala` with `testPki`: an `SSLContext` built once from the delegating managers completes a handshake; after the files are replaced and the reload interval passes, a new handshake on the same context presents the new certificate; `trustManagerRequiring` refuses a peer naming another service.
- [X] T008 Write `GRT/GrpcTlsSpike.scala`, ignored unless `-Dankka.spikes=on` (the pattern of `CPT/IndexProjectionSpike.scala`), proving R4 on the default provider grpc-netty-shaded picks: a server from `Grpc.newServerBuilderForPort(0, TlsServerCredentials.newBuilder().keyManager(tls.keyManager).trustManager(tls.trustManager).clientAuth(ClientAuth.REQUIRE).build())` serving `FX`'s `CartService`; a client from `TlsChannelCredentials` with a `testPki` certificate. Cases: a call succeeds; a client with no certificate fails the handshake; a `ServerInterceptor` reads the peer certificate from `Grpc.TRANSPORT_ATTR_SSL_SESSION` and `Caller.fromCertificate` names the caller; after rotating the server's files a new connection sees the new certificate. Run it: `sbt -Dankka.spikes=on 'grpc/testOnly *GrpcTlsSpike'`. If the custom key manager fails under the default provider, switch the spike to `NettyServerBuilder` with `GrpcSslContexts.configure(builder, SslProvider.JDK)`, and record in `research.md` R4 which form the server uses.
- [X] T009 Write `GR/GrpcStatus.scala` (R7): `toStatus(error: CommandError): io.grpc.Status` and `fromStatus(status: io.grpc.Status): Option[CommandError]` over the one table of R7, with the message as the description; `internal: Status` (`INTERNAL`, `internal error`). Write `GRT/GrpcStatusSuite.scala`: each of the eight `ErrorCode`s maps to its status and back to itself; a status outside the table maps back to `None`; the table covers `ErrorCode.values` exactly, so a ninth code fails the suite. Then write the empty scaffold `GRT/GrpcFeatures.scala` — `class GrpcFeatures extends GherkinSuite("../../features/grpc") with LogCapturing` — and add the tag line `@ignore` above `Feature:` in every file under `features/grpc/`. `sbt 'grpc/testOnly *GrpcFeatures'` reports every scenario ignored and none failed; the BDD checker still reports 0 findings.

**Checkpoint**: `sbt core/test http/test runtime/test 'grpc/testOnly *GrpcStatusSuite'` green; the TLS
spike has been run and its outcome is recorded; `sbt compile` warning-free.

---

## Phase 3: User Story 1 — Serve a gRPC API from a service (Priority: P1) 🎯 MVP

**Goal**: a `.proto` service definition, implemented as an endpoint, registered, called and tested
on a developer's machine.

**Independent Test**: start the sample, call `GetCart` and `AddItem` with a generated client, add
an item the cart refuses and read `FAILED_PRECONDITION` with the cart's message; the sample's suite
calls the endpoint through the test helper on a port the system picks.

- [X] T010 [US1] Write `GR/GrpcEndpoint.scala` (contract `scala-api.md`, R5): `abstract class GrpcEndpoint(val service: ServiceDescriptor)` with abstract `def acl: Acl`, `protected def unary[Req, Res](method: MethodDescriptor[Req, Res])(handler: Req => Res): Unit`, and a `private[ankka]` collected `Vector[DeclaredMethod]` (`descriptor`, `kind`, `acl: Option[Acl]`, the handler erased) as `data-model.md` describes. Declarations are collected in the constructor body, as `HttpEndpoint` collects routes. `clientStream`, `serverStream`, `bidiStream`, `withAcl` and the context accessors arrive with US6 and US2; do not stub them.
- [X] T011 [US1] Write `GR/Binding.scala` (R6, R7): for an endpoint, build a new `ServiceDescriptor` with the original name and schema descriptor, each method rebuilt with a pass-through `Array[Byte]` request marshaller and the original response marshaller, and a `ServerServiceDefinition` over it. The unary `ServerCallHandler`: request one message; on half-close run, on `AnkkaExecutors.virtual`, parse (a parse failure closes `INVALID_ARGUMENT` and runs no handler) then the handler inside `RequestScope.withContext`; send the reply and close `OK`. A thrown failure closes with: `GrpcStatus.toStatus` of `CommandError.from(failure)` when defined; the status of a `StatusRuntimeException` as thrown; otherwise `GrpcStatus.internal`, with the failure logged naming the method. Until T019 lands, the only admission is: `Acl.AllowAll` runs the handler, any other ACL closes `PERMISSION_DENIED` — closed by default, never open.
- [X] T012 [US1] Write `GR/GrpcServer.scala` and `GR/GrpcChannels.scala` (contract `scala-api.md`, *Serving* and *Testing*). `GrpcServer.of(factories*)` and `.at(interface, port)(factories*)` over `EndpointClients => GrpcEndpoint`; `name = "grpc-server"`; `start` builds `EndpointClients` as `HttpServer.scala:57` does, validates, binds plaintext with `executor(AnkkaExecutors.virtual)` and `maxInboundMessageSize` from `ankka.grpc.max-message-size`, and logs each method and the bound address; `readiness` false until bound; `boundPort`; `stop()` is `shutdown()`, `awaitTermination(ankka.grpc.shutdown-grace)`, `shutdownNow()`. Validation throws one `IllegalArgumentException("invalid ankka grpc configuration:\n  - …")` listing every problem: a method of the service definition with no handler; a handler whose kind is not its method's; a method declared twice; a method not of the service definition; two endpoints with one service definition. A port in use fails startup naming the port. A call to a method nothing serves is `UNIMPLEMENTED` (grpc-java's default; T019 puts the ACL in front of it). `GrpcChannels.plaintext(port)`.
- [X] T013 [P] [US1] Write `GRT/GrpcServerValidationSuite.scala`: one case per validation problem asserting the message names the service definition or the method; all problems of two bad endpoints reported in one exception; a second server on a bound port fails naming it. And `GRT/GrpcLimitsSuite.scala`: a call with a 200 ms deadline to a handler that sleeps a second ends `DEADLINE_EXCEEDED` at the caller, and the handler still runs to its end (FR-013); with `ankka.grpc.max-message-size` set to 1 KiB, a 2 KiB request ends `RESOURCE_EXHAUSTED` and the handler's counter stays at zero, while a 2 KiB answer to a client that accepts it is delivered (FR-014).
- [X] T014 [US1] Implement the steps of `features/grpc/serving.feature`, `statuses.feature` and `testing.feature` in `GRT/GrpcFeatures.scala` and remove `@ignore` from those three files. The fixture is one helper that starts an `AnkkaTestKit` service with `GrpcServer.at("127.0.0.1", 0)` over endpoints built from `FX`'s descriptors, and a `GrpcChannels.plaintext` channel; "a developer calls the method" is a blocking stub call returning the status and message. For the statuses outline the endpoint's handler calls a test entity that answers `effects.error(message, code)`. "is not registered with the service" is a server with no endpoint for that definition. Every scenario of the three files passes and none is ignored. To see a check fail: map `Conflict` to `ABORTED` in `GrpcStatus` and watch one outline row go red.
- [X] T015 [P] [US1] Add the project `shoppingCartApi` at `samples/shopping-cart-api` in `build.sbt` (the shape of `grpcFixtures`, `publish / skip := true`, in the root `aggregate`) with `samples/shopping-cart-api/src/main/protobuf/shoppingcart/v1/cart.proto`: package `shoppingcart.v1`, `service CartService { rpc GetCart; rpc AddItem; rpc WhoCalled }` and their messages (`WhoCalled` answers the calling workload and the instance's name, for the cluster cases). `shoppingCart` gains `.dependsOn(grpc, shoppingCartApi)` and `dockerExposedPorts := Seq(9000, 9090)`.
- [X] T016 [US1] Write `SC/src/main/scala/shoppingcart/api/CartGrpcEndpoint.scala`: `GetCart` and `AddItem` over the cart entity, as `ShoppingCartEndpoint` calls it (`cart(id).call(ShoppingCartEntity.getCart).invoke()`), translating between the proto messages and the sample's own types; `WhoCalled` answers `Local` for now; `val acl = Acl.AllowAll`. Mark the regions the documentation will include with `// docs:start grpc-endpoint` / `// docs:end grpc-endpoint` and the like. Register `GrpcServer.of(clients => CartGrpcEndpoint(clients))` in `SC/src/main/scala/Main.scala`, unless the environment says `CART_GRPC=off` — the switch the cluster suite uses to deploy a service that declares gRPC and serves none (T028); say so in a comment. Write `SC/src/test/scala/shoppingcart/CartGrpcSuite.scala` on `AnkkaTestKit` with `GrpcServer.at("127.0.0.1", 0)`: get an empty cart; add an item and read it back; add to a checked-out cart and read `FAILED_PRECONDITION` with the cart's message; the HTTP endpoint of the same service still answers.

**Checkpoint**: `sbt 'grpc/testOnly *GrpcFeatures *GrpcServerValidationSuite' shoppingCart/test`
green; `sbt shoppingCart/run` answers `grpcurl -plaintext -proto … localhost:9090 shoppingcart.v1.CartService/GetCart`.

---

## Phase 4: User Story 2 — Decide who may call a gRPC endpoint (Priority: P1)

**Goal**: the ACL vocabulary of an HTTP endpoint, per endpoint and per method, with the caller read
from the certificate under TLS.

**Independent Test**: one method under an authenticator and one under deny-all; call each with and
without a credential and read the three kinds of refusal; no handler ran for a refused call.

- [X] T017 [US2] Write `GR/CallMetadata.scala` (contract `scala-api.md`, *Metadata*): a read-only view over `io.grpc.Metadata` with `get`, `all`, `toSeq`; text keys only; the local-caller key (`LocalCallers.Header`, lower-cased) withheld. Add to `GR/GrpcEndpoint.scala`: `protected def withAcl(acl: Acl)(declare: => Unit)` scoping declarations as `HttpEndpoint.withAcl` does, and the accessors `caller`, `principal`, `metadata`, `call`, each reading `RequestScope.currentContext` and throwing `IllegalStateException` off the handler's thread, with `principal`'s message naming the method as `HttpEndpoint.principal` names the route.
- [X] T018 [US2] Give `GR/GrpcServer.scala` TLS (R4, in the form T008 proved): when `ankka.http.tls.enabled` is on, build `RotatingTls` from `ankka.tls.service-directory` (an empty directory fails startup, as `HttpServer.serviceTls` does) and serve with `TlsServerCredentials` requiring a client certificate; add `private[ankka] def withTls(directory: Path): GrpcServer` for suites, as `HttpServer.withTls`. Add `GrpcChannels.plaintext(port, caller)`, which sends `LocalCallers.header(caller)` as metadata, and a `private[ankka]` `GrpcChannels.tls(port, authority, tls: RotatingTls, expecting: String)` for suites.
- [X] T019 [US2] Write `GR/Admission.scala` (R14, contract `scala-api.md` *How a call ends*) and replace T011's placeholder with it. The caller: under TLS, the session's first peer certificate through `Caller.fromCertificate`, a certificate naming nobody refused `PERMISSION_DENIED`; without TLS, `LocalCallers.callerFrom` of the metadata key when present, else `Caller.Local`. The context: `SimpleRequestContext(method = "POST", path = "/<service definition>/<method>", query = QueryParams.empty, headers = metadata.toSeq, remoteAddress = None, caller = caller)`. The decision: the method's own ACL else the endpoint's; each `Acl` case mapped to a status exactly as the table says, `Unauthenticated(challenge)` adding the trailer `www-authenticate: Bearer <challenge>`, and `Allow(principal)` putting the principal on the context the handler sees. A refused call runs no handler. Register a fallback `HandlerRegistry`: for a method name whose service definition an endpoint serves, apply that endpoint's ACL and then answer `UNIMPLEMENTED`. Startup validation gains "endpoint for '<definition>' states no acl" when `acl` is `null`. Log once at startup, as `HttpServer.scala:141-144` does, when an ACL names callers and TLS is off.
- [X] T020 [P] [US2] Write `GRT/AdmissionSuite.scala`: one case per row of the table for `DenyAll`, `AllowAll`, `AllowIf` (the predicate sees `path == "/ankka.fixtures.v1.CartService/GetCart"` and a metadata value as a header), and `Authenticate`'s four answers with the trailer asserted; an authenticator written for an HTTP endpoint (reads `Authorization`) admits a gRPC call carrying `authorization` metadata, unchanged (FR-019); a `-bin` key and the local-caller key are not among the headers; with `withTls` and `testPki` certificates, a client with no certificate never reaches the ACL, and `caller` is the certificate's service whatever `x-ankka-local-caller` says.
- [X] T021 [US2] Implement the steps of `features/grpc/access.feature` in `GRT/GrpcFeatures.scala` and remove its `@ignore`. "a deployed service" starts the server with `withTls` on `testPki` certificates and calls with a certificate naming the calling service; "running on a developer's machine" is plaintext. "no handler runs" asserts a counter the fixture endpoint increments. Set the sample's `CartGrpcEndpoint.acl` to `Acl.allowCallers(Callers.anyInProject, Callers.internet)` by default and, when `CART_GRPC_CALLER` names a service, to `Acl.allowCallers(Callers.service(<it>))` — the switch T033 and T041 deploy a second copy with; make `WhoCalled` answer `caller` and the `HOSTNAME`, and add a case to `SC`'s `CartGrpcSuite`: `GrpcChannels.plaintext(port, Caller.Service("local", "checkout"))` is answered `service:local/checkout` (locally the service is `local/local`, so a caller in another project is refused, as over HTTP).

**Checkpoint**: `sbt grpc/test shoppingCart/test` green with `serving`, `statuses`, `testing` and
`access` un-ignored. **This is the MVP**: a service serves and protects a unary gRPC API locally.

---

## Phase 5: User Story 3 — Deploy a service that serves gRPC (Priority: P2)

**Goal**: a descriptor declares gRPC; the platform gives the service an address, admits only
platform workloads, and holds an instance un-ready until it answers. Nothing changes for a service
that declares none.

**Independent Test**: deploy the sample with `"grpc": true` into k3s: the Service has a `grpc` port
and an SRV record, a pod with no platform identity cannot connect, and a service that declares gRPC
and registers none is `Failed` with the sentence of contract `descriptor-and-configuration.md`.

- [X] T022 [P] [US3] In `API/descriptors.scala` add `grpc: Boolean = false` and `grpcPort: Int = ServiceSpec.DefaultGrpcPort` to `ServiceSpec`, `resolvedGrpcPort`, the constants `DefaultGrpcPort = 9090` and `GrpcPortEnvVar = "ANKKA_GRPC_PORT"`, and the first four problems of contract `descriptor-and-configuration.md` with exactly its messages (R10); the fifth, the runtime version, is T067's. Add cases to `APIT/DescriptorSuite.scala`: each problem; `grpcPort` out of range refused with `grpc` false too; `"http": false, "grpc": true` valid; a descriptor with neither field encodes with no `grpc` and no `grpcPort` key, and one encoded before this feature decodes to `grpc = false`. Add `grpc` to the full `ServiceSpec` fixture in `console/package/fixtures/control-plane/` and keep `APIT/ControlPlaneFixturesSuite.scala` green; `cd console && npm test -w package` still passes (the client treats `ServiceSpec` as opaque).
- [X] T023 [P] [US3] Add `grpcPort: Option[Int] = None` to `AnkkaServiceSpec` in `crd/src/main/scala/com/thinkmorestupidless/ankka/crd/AnkkaService.scala`, with `@JsonDeserialize(contentAs = classOf[java.lang.Integer])` as `port` has, and declare it in `kustomization/components/crd/ankkaservice.yaml` beside `port` (integer, 1–65535). `OPT/CrdSchemaSuite.scala` passes; add a round-trip case to the crd codec suite: absent stays absent in the written resource, `Some(9090)` round-trips.
- [X] T024 [US3] In `CP/deploy/ServiceProjection.scala` set `grpcPort = descriptor.service.resolvedGrpcPort` beside `port`. Add cases to its suite: declared, undeclared, and `grpc` with `"http": false` projects `port = None, grpcPort = Some(9090)`. Add a case to `CPT/EventCompatibilitySuite.scala`: a `ServiceApplied` event stored before this feature replays to a descriptor with `grpc = false`.
- [X] T025 [US3] Render the workload's half of contract `rendering.md` in `OP/Rendering.scala`, all of it conditional on `spec.grpcPort`: the container port named `grpc` and `ANKKA_GRPC_PORT` on the node container (`container`); the Service's second port with `appProtocol: kubernetes.io/h2c`, and `addressAction` ensuring the Service when `port` **or** `grpcPort` is set, ports in the contract's order. In `OP/ZeroTrust.scala` add `grpcPolicyName` and `grpcPolicy` (the peers of `httpPolicy`, the port `grpcPort`); `zeroTrustActions` ensures it when set and removes it when not. Add `grpc` names to `OP/Names.scala` if a name is derived there.
- [X] T026 [US3] Tests for T025. `OPT/RenderingSuite.scala`: with `grpcPort`, the node container has the port and the variable, and has neither without. `OPT/ServiceRenderingSuite.scala`: two ports in order with `targetPort` equal to `port`, the `appProtocol` on `grpc` only; `port = None, grpcPort = Some` renders a Service with the one port; neither removes it. `OPT/ZeroTrustRenderingSuite.scala`: the policy admits the two peers and nothing else, on the gRPC port only; the HTTP policy is unchanged by `grpcPort`. For `process` hosting, the port and variable are on the sidecar container (it is the node), though the control plane refuses that descriptor. `RenderingGoldenSuite` is still green with no fixture rewritten.
- [X] T027 [US3] The declared-but-unserved check (R16). In `RT/Ankka.scala`, at the top of `ServiceBuilder.host` before any actor system: a `private[ankka]` pure function taking the environment lookup and the registered extension names, returning the message of contract `descriptor-and-configuration.md` when `ANKKA_GRPC_PORT` is set and no extension is named `grpc-server`. When it returns one, write it to the termination log (`/dev/termination-log`, overridable by `-Dankka.termination.log` for tests; a write failure is ignored), log it, and throw. Add `RTT/DeclaredGrpcSuite.scala`: set and unserved → the message, naming the port; set and served → none; unset → none; the message is written to a temp file given by the property.
- [ ] T028 [US3] Write `CPT/GrpcClusterSuite.scala` on the k3s harness the other cluster suites share (`PkiStack.install`, the operator, the sample image; skipped under `-Dankka.cluster.tests=off`), with tests named for these scenarios of `features/grpc-deployed/deployed.feature`: *a service that does not declare gRPC is deployed as it was before* (apply the sample without `grpc`, record its pods' UIDs and its Service; they are unchanged and the Service has one port); *a service may serve gRPC and no HTTP* (ready, one `grpc` port); *a workload that is not of the installation cannot connect to a gRPC address* (a `busybox` pod in an unlabelled namespace fails `nc -w 3 <clusterIP> 9090`, while the same pod reaches an unprotected address, so the refusal is the policy's); *an instance that cannot yet answer a gRPC call is not ready* (the pod's `/ready` is false until the log line that the gRPC server is listening); *a service that declares gRPC and serves none is reported as failed, with the reason* (the sample deployed with `"grpc": true` and the env var `CART_GRPC=off` (T016). Run the control plane under test with a progress deadline of 60 seconds (`DeployConfig.progressDeadline`; the default is 600): the sentence is in `services get`'s detail while the rollout is still in progress, and the lifecycle is `Failed` once the deadline has passed. Assert both, in that order). The three descriptor refusals are T022's cases. Scenarios that need a calling service are T041's.

**Checkpoint**: `sbt -Dankka.cluster.tests=off controlPlaneApi/test operator/test controlPlane/test`
green; `caffeinate -i sbt 'controlPlane/testOnly *GrpcClusterSuite'` green for the cases above.

---

## Phase 6: User Story 4 — Reach a gRPC endpoint from outside the cluster (Priority: P2)

**Goal**: an exposed service's one hostname answers gRPC calls and HTTP requests alike.

**Independent Test**: from the host, with the hostname and the installation's CA, a generated
client is answered and so is `curl`; unexposed, neither is.

**Depends on**: US3. The stream scenarios also need US6.

- [ ] T029 [US4] Write `CPT/GatewayGrpcSpike.scala`, ignored unless `-Dankka.spikes=on`, proving R8 before any rendering is written. In k3s with the gateway stack the exposure suite installs, deploy the sample (HTTP only, exposed) and apply **by hand**, with the node's `kubectl`: a Service `cart-spike` with a `grpc` port and `appProtocol: kubernetes.io/h2c`, a network policy admitting the gateway's proxies to 9090, a `BackendTLSPolicy` targeting it, and a second `HTTPRoute` on the same hostname with the header rule and `timeouts.request: "0s"` of contract `rendering.md`. From the host, with a grpc-java channel to the mapped NodePort, `overrideAuthority` the hostname and the local CA trusted: a unary call is answered and `WhoCalled` says `gateway`; a `curl --cacert --resolve` to the same hostname still answers HTTP; and — once US6 has landed, else leave these two cases `assume`d off with the reason — a server stream runs for 25 seconds and a bidirectional call exchanges parts. Run it: `caffeinate -i sbt -Dankka.spikes=on 'controlPlane/testOnly *GatewayGrpcSpike'`. If the header rule does not carry gRPC, stop: amend `research.md` R8 and `contracts/rendering.md` to the `GRPCRoute` fallback, and raise FR-046 with the user before T031.
- [X] T030 [P] [US4] In `CP/api/ExposureRules.scala` refuse only a service that serves neither, with the message of contract `descriptor-and-configuration.md`. Add cases to its suite: gRPC and no HTTP is exposable; neither is refused with that message; HTTP only is as before.
- [ ] T031 [US4] Render the exposed half of contract `rendering.md` in `OP/Rendering.scala` and `OP/ZeroTrust.scala`: `routeAction` and `backendTlsAction` take "exposed, a base domain, and `port` or `grpcPort`"; `httpRoute` puts the gRPC rule first when `grpcPort` is set and the existing rule, built exactly as today, when `port` is; `backendTlsPolicy` has one `targetRef` per port the service has. If fabric8's model has no builder for a rule's `timeouts` or a header match type, set them through the builders' generic fields and say so in a comment.
- [ ] T032 [US4] Tests for T031 in `OPT/RenderingSuite.scala` and `OPT/ZeroTrustRenderingSuite.scala`, asserting the shape field for field and never a substring: both ports → two rules in order, the first with the one header match, the regular expression's exact value, `timeouts.request` `"0s"` and the gRPC port, the second identical to what a service without gRPC renders; gRPC only → the one rule; HTTP only → identical to before; the policy's `targetRefs` per case. Assert the regular expression itself in a unit case: it matches `application/grpc` and `application/grpc+proto` in full and does not match `application/grpc-web` or `application/grpc-web+proto`. `RenderingGoldenSuite` still green.
- [ ] T033 [US4] Add to `CPT/GrpcClusterSuite.scala` tests named for the scenarios of `features/grpc-deployed/exposed.feature`, calling from the host as T029 does (give `controlPlane` `grpc % Test` and `shoppingCartApi % Test` in `build.sbt`): a call at the hostname is answered and `WhoCalled` says `gateway`; an HTTP request at the same hostname is answered by the HTTP endpoint; an endpoint that admits only a named service refuses the call (a second deployment of the sample with `CART_GRPC_CALLER=checkout`, T021); one that admits the gateway admits it; not exposed → the call reaches no handler; an exposed service that declares no gRPC renders the route it rendered before (compare the object) and its pods are not restarted; gRPC and no HTTP → answered; a member is shown why (label the namespace so the route is not allowed, and read `route rejected: …` from `services get`). The stream, both-directions and reflection scenarios are added by T049 and T058.

**Checkpoint**: the spike's outcome is recorded; rendering suites green; the cluster cases above
green.

---

## Phase 7: User Story 5 — Call another service's gRPC endpoint (Priority: P2)

**Goal**: a Scala service calls another's gRPC endpoint by name, as itself.

**Independent Test**: two deployed services, one admitting only the other by name: the admitted one
is answered, a third is refused, and a name that does not exist is an error that names it.

**Depends on**: US2 offline; US3 for the cluster cases.

- [X] T034 [P] [US5] Add `final case class ServiceServesNoGrpc(service: String) extends RuntimeException(s"$service serves no gRPC")` to `modules/sdk/src/main/scala/com/thinkmorestupidless/ankka/sdk/ServiceClient.scala`, beside `ServiceUnresolvable`.
- [ ] T035 [P] [US5] What a running service says about its gRPC (R16). In `RT/Ankka.scala` add `def grpcAddress: Option[String] = None` to `RuntimeExtension` and `grpcAddresses` to `AnkkaService`; in `RT/ObservabilityEndpoint.scala` add `"grpc":{"address":…}` to an instance when there is one; in `RT/ServiceRegistration.scala` add `grpcAddressOf(name): Option[String]`, sharing `httpAddressOf`'s lookup and returning `None` both for no such service and for one with no gRPC — and a sibling that tells the two apart (`isAnnounced(name)`). `GR/GrpcServer.scala` overrides `grpcAddress` (`127.0.0.1:<bound port>`) and leaves `boundAddress` alone. Extend `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/ServiceRegistrationSuite.scala` to drive announce → `grpcAddressOf` → withdraw for a service with gRPC and for one without.
- [ ] T036 [P] [US5] Render the headless Service of contract `rendering.md` (R12): `Rendering.grpcPeers(resource, spec, namespace, port)` named `<service>-grpc`, `clusterIP: None`, the selector from the same `selectorLabels` function, owned by the resource; ensured when `grpcPort` is set and removed when not, with the `Action`s and `Executor` handling that the existing Service's have. Cases in `OPT/ServiceRenderingSuite.scala`: its selector equals the Deployment's; absent without `grpcPort`; `RenderingGoldenSuite` still green.
- [ ] T037 [US5] Write `GR/GrpcClients.scala` (contract `scala-api.md` *Calling another service*, R12, R13). An extension named `grpc-clients`; `apply(name)` and `apply(project, name)` return a `Channel` whose `ManagedChannel` is built on first use and cached, all shut down in `stop()`; use before `start` is an `IllegalStateException`. In a cluster (`ankka.tls.service-directory` set): the port from the `_grpc._tcp` SRV record of `HttpServiceClients.hostOf(project, name)` — generalise `HttpServiceClients.srvPort` to take the port's name, in `RT/HttpServiceClients.scala`; target `dns:///<name>-grpc.<namespace>.svc.cluster.local:<port>`; `defaultLoadBalancingPolicy("round_robin")`; `overrideAuthority` the ordinary host; `TlsChannelCredentials` over `keyManager` and `trustManagerRequiring("ankka://<project>/<name>")`. Locally: `ankka.local-grpc-services."<name>"` else `ServiceRegistration.grpcAddressOf`, plaintext. A `locate` parameter overrides resolution for suites, as `HttpServiceClients` has. Failures on first use: no host or not announced → `ServiceUnresolvable`; a host with no gRPC port, or announced with no gRPC address → `ServiceServesNoGrpc`. A `ClientInterceptor` on every channel: a closing status in R7's table gets its `CommandError` as the cause of the exception the stub throws; a handshake failure whose message carries "peer identity" becomes `ServiceIdentityMismatch`, as `HttpServiceClients.scala:85-86` does. If `round_robin` is not found at run time, add `grpc-util` to `Dependencies` and the module.
- [ ] T038 [P] [US5] Write `GRT/GrpcClientsSuite.scala` with a `locate` override and `testPki`: each of the three errors, each message naming the service; a refusal's cause is the matching `CommandError` for all eight codes; with two servers behind one name, ten calls reach both (round robin); a server whose certificate names another service is never sent a request (a counter on the server stays at zero).
- [ ] T039 [US5] Implement the steps of `features/grpc/calling.feature` in `GRT/GrpcFeatures.scala` and remove its `@ignore`. "a deployed service" pairs are two `AnkkaTestKit` services in the one JVM with `withTls` and `testPki` certificates naming each, the caller's `GrpcClients` resolving through a `locate` override; "running on a developer's machine" uses the running-services directory under `-Dankka.running.dir`. "a handler of the service checkout … is given the refusal" is an HTTP endpoint of the calling service that lets the stub's exception pass and is answered 404 (T006). The stream scenario of this file is T047's and keeps a scenario-level `@ignore` until then.
- [ ] T040 [US5] In the sample, add to `SC/src/main/scala/shoppingcart/api/CallersEndpoint.scala` a route `GET /callers/grpc/{service}` that calls `WhoCalled` on the named service through a `GrpcClients` passed to its constructor and answers what it said, `GET /callers/grpc/{service}/loop/{n}` that makes `n` calls and answers the count per instance name and the count of failures, and `GET /callers/grpc/{service}/claiming/{other}` that makes the same call with `x-ankka-local-caller` metadata naming `{other}` (what T041 uses to show the claim is not believed); create and register the `GrpcClients` in `SC/src/main/scala/Main.scala`. A case in `SC`'s suite calls the service's own gRPC through it locally.
- [ ] T041 [US5] Add to `CPT/GrpcClusterSuite.scala`, with two deployments of the sample in one project and one in another, tests named for the remaining scenarios of `deployed.feature` — *a service that declares gRPC has a gRPC address other services reach*, *a deployed gRPC endpoint reads its calling workload from the certificate*, *a call that says it is from another service is not believed* (the caller's `/callers/grpc/{service}/claiming/billing` route, T040) — and for every scenario of `features/grpc/calling.feature` against real pods, driving the caller's `/callers/grpc/…` route with `InPod.curl`. *A call to a service that does not serve gRPC* targets a deployment without `grpc`; *cannot be found* a name nothing has.

**Checkpoint**: `sbt grpc/test shoppingCart/test operator/test` green; the cluster cases green.

---

## Phase 8: User Story 6 — Stream in either direction (Priority: P2)

**Goal**: all four kinds of method, with neither side made to hold what the other has not read.

**Independent Test**: the sample streams a cart's changes, takes a stream of items and answers a
count, and answers each part it is sent; disconnecting mid-stream stops production and tells the
handler.

**Depends on**: US1, US2. Can be built before US3–US5.

- [X] T042 [US6] Write `GR/Streams.scala` (R6, contract `scala-api.md` *Streams*): `trait Requests[Req] extends Iterator[Req]` with `asSource`, and `final class CallCancelled`. The inbound bridge: `call.request(1)` only when `hasNext`/`next` is called (or on downstream demand of `asSource`), a queue of at most one undelivered part, `onHalfClose` ending the iterator, `onCancel` and an undecodable part failing it with `CallCancelled` (the latter after closing the call `INVALID_ARGUMENT`). The outbound bridge: run the handler's `Source` with a sink that takes a part only while `call.isReady`, resumed from `onReady`; completion closes `OK`, failure closes with the status rules of T011, `onCancel` cancels the stream.
- [X] T043 [US6] Add `serverStream`, `clientStream` and `bidiStream` to `GR/GrpcEndpoint.scala` and their `ServerCallHandler`s to `GR/Binding.scala`, each running the handler on `AnkkaExecutors.virtual` inside `RequestScope.withContext`; the scope covers building a `Source`, not draining it, as `HttpServer.dispatchStream` says of its own. A handler that returns before `Requests` is exhausted ends the call and stops asking for parts. Admission (T019) runs before the first part is read or sent, for every kind. Validation's kind check now covers all four.
- [X] T044 [P] [US6] Write `GRT/GrpcFlowControlSuite.scala`, on raw grpc-java client calls with manual flow control: a server stream of 100,000 parts whose client has asked for 10 has produced fewer than 1,000 (assert on a counter in the `Source`); a client that has sent 100,000 parts to a handler that read 10 and then waits has had fewer than 1,000 accepted (the client's `isReady` goes false); a client that cancels after 2 parts stops production within a second; each stream then completes whole when the reader resumes, 100,000 parts in order.
- [ ] T045 [US6] Implement the steps of `features/grpc/streaming.feature` and `features/grpc/request-streams.feature` in `GRT/GrpcFeatures.scala` over `FX`'s `CartStreams`, and remove both `@ignore` tags. "goes away" is a cancelled client call; "is told that the stream ended unfinished" asserts the handler caught `CallCancelled`; "the service holds fewer than … parts" reads the same counter as T044. If T053 left a scenario-level `@ignore` in `features/grpc/traces.feature`, remove it here and see that scenario pass.
- [ ] T046 [US6] In the sample add `WatchCart` (server stream: the cart after each change, by polling the entity — say so in a comment, since entities do not stream), `ImportItems` (client stream → a count) and `Converse` (bidirectional: each part answered with one) to `samples/shopping-cart-api/…/cart.proto` and `SC/…/CartGrpcEndpoint.scala`, with `docs:start`/`docs:end` regions for one of each kind, and cases in `SC`'s `CartGrpcSuite`.
- [ ] T047 [US6] Remove the scenario-level `@ignore` T039 left in `features/grpc/calling.feature` and implement *a service calls a method of another service that takes a stream and answers with a stream* through a `GrpcClients` channel and an async stub.
- [ ] T048 [US6] Turn on the two `assume`d cases of `CPT/GatewayGrpcSpike.scala` (T029) and run the spike again: a 25-second server stream and a bidirectional exchange through the gateway. Record the outcome in `research.md` R8.
- [ ] T049 [US6] Add to `CPT/GrpcClusterSuite.scala` the tests named for *a stream reaches a developer outside the cluster a part at a time* and *a method that takes a stream and answers with a stream is called from outside the cluster* (`features/grpc-deployed/exposed.feature`), the first asserting parts arrive spread over the stream's 20 seconds and not at its end.

**Checkpoint**: `sbt grpc/test shoppingCart/test` green with seven feature files un-ignored.

---

## Phase 9: User Story 7 — Replace and add instances without stranding callers (Priority: P3)

**Goal**: a rolling replacement refuses no call, and new instances come to answer calls.

**Independent Test**: one service calling another steadily over gRPC: roll the called one and count
refused calls (none); scale it 1 → 3 and see every instance answer within five minutes.

**Depends on**: US3, US5; the outside case on US4.

- [ ] T050 [US7] In `GR/GrpcServer.scala` set `maxConnectionAge` from `ankka.grpc.max-connection-age` and `maxConnectionAgeGrace` from `ankka.grpc.shutdown-grace` on the server builder, and `keepAliveTime`/`keepAliveTimeout` from `ankka.grpc.keepalive-time` and `ankka.grpc.keepalive-timeout` (R12, FR-051). Write `GRT/GrpcShutdownSuite.scala`: with an age of two seconds, a channel making a call each 100 ms for ten seconds sees no failure and the server sees more than one connection; on `stop()`, a unary call in progress completes, a stream that does not end is ended `UNAVAILABLE` after the grace and not before, and a call begun after `stop()` fails without reaching a handler. One more, for a caller that vanishes: put a small TCP relay the test controls between a client and the server, open a stream that does not end, make the relay stop forwarding in both directions without closing either socket, and with the keepalive set to one second and its timeout to one, the handler's stream is cancelled within five seconds — and is still being produced at ten when keepalive is off, so the case can fail.
- [ ] T051 [US7] Add to `CPT/GrpcClusterSuite.scala` tests named for the scenarios of `features/grpc-deployed/replacement.feature`. *No call is refused while a service's instances are replaced one at a time*: three instances; drive the caller's loop route (T040) at 20 calls a second through a `services restart`, at least 1,000 calls, zero failures (SC-004). *An instance added to a service comes to answer calls…*: one instance, start the loop, scale to three, and within five minutes the per-instance counts name three instances while the caller's pod UID is unchanged (SC-005). *No call from outside the cluster is refused…*: the same replacement under calls from the host at the hostname. *A stream on an instance that is stopping…*: open `WatchCart` through the caller, delete the pod serving it, and the stream ends `UNAVAILABLE` no sooner than the `preStop` sleep. Assert on counts and identities, never on a readiness flap.

**Checkpoint**: `GrpcShutdownSuite` green; the replacement cases green on a machine that stayed awake.

---

## Phase 10: User Story 8 — See gRPC calls in traces and the local console (Priority: P3)

**Goal**: a call is the root of the trace of what it caused; a refusal is not a failure; the local
console lists methods and offers no way to call them.

**Independent Test**: call a method that calls an entity and read one root named for the method
with the entity's span beneath it; a refused call's root says refused.

**Depends on**: US1, US2; the stream scenario on US6.

- [ ] T052 [US8] Record a span per call to a declared method (R17), in `GR/Binding.scala` and `GR/Admission.scala`: `Observability(system).recorder.begin` with component `grpc` and handler `<service definition>/<method>`, both interned once at startup, opened on the handler's virtual thread inside `Trace.within` so component calls nest under it; completed `Ok`, `Refused` (the ACL said no; or the call ended in a `CommandError` or a status of R7's table) or `Failed`. A streaming call's span completes when the stream ends. A call the fallback registry answers records nothing. `GrpcServer.routes` reports each method as `ServedRoute("GRPC", "<service definition>/<method>", streaming)`.
- [ ] T053 [US8] Implement the steps of `features/grpc/traces.feature` in `GRT/GrpcFeatures.scala`, reading spans through the recorder and `Trace.assemble`, and remove its `@ignore`. If US6 has not landed, put the tag on the one scenario *a call answered with a stream is one call in the trace* instead, and have T045 remove it. Add to `GRT/AdmissionSuite.scala`: an ACL's refusal of a declared method records one `Refused` span; 1,000 calls to distinct undeclared method names leave the name table's size unchanged.
- [ ] T054 [US8] The local console (FR-037). In `cli/src/main/resources/console/app.js` render a route whose method is `GRPC` as a listed row with its path and a hint that it is called with a gRPC client, with no form and no send. In `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/console/ConsoleServer.scala` refuse `/api/invoke/…` and `/api/invoke-stream/…` for a `GRPC` route with a 400 that says so. Write `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/ConsoleGrpcSuite.scala` with a test named for the scenario of `features/local-console/grpc.feature`: against a stub observability endpoint listing one HTTP route and one `GRPC` route, the inventory the console serves lists both, and invoking the `GRPC` one is refused. `cli/native-smoke.sh` still passes if run.

**Checkpoint**: `sbt grpc/test 'cli/testOnly *ConsoleGrpcSuite'` green.

---

## Phase 11: User Story 9 — Let a tool ask a service what it serves (Priority: P3)

**Goal**: opt-in reflection, under an ACL of its own, wherever the service is reached.

**Independent Test**: without opting in, `grpcurl list` is told the service does not answer; with
it, the methods are listed and one is called with no `.proto` file.

**Depends on**: US1, US2; the exposed case on US4.

- [ ] T055 [US9] Write `GR/Reflection.scala` and `GrpcServer.withReflection(acl: Acl)` (R15): bind `ProtoReflectionServiceV1.newInstance()` and the v1alpha `ProtoReflectionService.newInstance()` from `grpc-services`, and have `Admission` judge calls to either service name by `acl`. Without it neither is bound. Startup validation refuses `withReflection(null)` as "reflection states no acl". Reflection calls record no span (R17: not a declared method).
- [ ] T056 [US9] Implement the steps of `features/grpc/reflection.feature` in `GRT/GrpcFeatures.scala` with grpc-java's own reflection client stub (`ServerReflectionGrpc`), and remove its `@ignore`: listing services returns `ankka.fixtures.v1.CartService`; a file-containing-symbol request describes its methods; both v1 and v1alpha answer. Once US6, US8 and US9 have all landed, no file under `features/grpc/` carries `@ignore`.
- [ ] T057 [P] [US9] In the sample, `GrpcServer.of(…).withReflection(Acl.allowCallers(Callers.internet))` in `SC/src/main/scala/Main.scala`, with a `docs:start reflection` region; `CART_REFLECTION=off` leaves reflection out, for T058's second deployment and for showing a service that has not opted in; and a case in `SC`'s `CartGrpcSuite` that lists the service through reflection.
- [ ] T058 [US9] Add to `CPT/GrpcClusterSuite.scala` the test named for *an exposed service that opts into reflection answers a tool outside the cluster* (`features/grpc-deployed/exposed.feature`), with the reflection stub from the host; and one more: from inside the cluster, a service in another project asking the sample for reflection is refused `PERMISSION_DENIED`, since the sample's reflection ACL admits only the gateway; and a deployment with `CART_REFLECTION=off` answers `UNIMPLEMENTED` (T057).

**Checkpoint**: `sbt grpc/test shoppingCart/test` green, no scenario ignored;
`grpcurl -plaintext localhost:9090 list` names the sample's service.

---

## Phase 12: User Story 10 — Read how to build one, and what it does not do (Priority: P3)

**Goal**: one page from a `.proto` file to a tested endpoint; the reference pages current; the
limitations page saying exactly what is left out.

**Independent Test**: `just docs` passes; the limitations page states each thing the spec puts out
of scope and no page says the platform serves no gRPC.

- [ ] T059 [US10] Write `DOCS/build/grpc-endpoints.md` (R20), frontmatter `kind` and `related` as `DOCS/build/http-endpoints.md` has: the build setup (a subproject for the `.proto` files, the ScalaPB plugin, the `ankka-grpc` dependency, why generated code is kept apart); declaring an endpoint and its four kinds of method; ACLs, the caller, the principal, metadata; how a call ends, with the status table; registering `GrpcServer`; reflection and what opting in discloses; calling another service with `GrpcClients`; testing with `GrpcServer.at("127.0.0.1", 0)` and `GrpcChannels`. Every code block is an `<!-- include: path#name -->` of a region marked in the sample (T016, T046, T057, T040) — run `just docs-sync`. Add the page to `mkdocs.yml`'s `nav` and to a skill's `pages:` list under `tools/docs/skill/`. The page stands alone: no feature numbers, no "see above".
- [ ] T060 [P] [US10] Reference pages. `DOCS/reference/service-descriptor.md`: `grpc`, `grpcPort`, the four refusals, an example block `DocumentationDescriptorsSuite` validates. `DOCS/reference/configuration.md`: add the module's `reference.conf` to the HOCON sources in `mkdocs.yml`'s `extra.docs`, run `just docs-sync`, and describe each new key and `ANKKA_GRPC_PORT`, `ANKKA_GRPC_INTERFACE` and `ankka.local-grpc-services` in the prose the coverage check reads. `DOCS/reference/error-codes.md`: the gRPC status beside each code's HTTP status. `DOCS/reference/glossary.md`: *Endpoint* is HTTP or gRPC; add *gRPC endpoint*, *Service definition*, *Reflection*. `DOCS/reference/akka-divergences.md`: how a gRPC endpoint here differs from Akka's.
- [ ] T061 [P] [US10] Platform and operations pages. `DOCS/platform/networking.md`: the gRPC address, the headless name, who may connect, that the gateway now forwards gRPC as HTTP/2 to a service's gRPC port and puts no limit of its own on how long a gRPC call lasts or stays idle (FR-043), that a caller's connection to a service is renewed every two minutes and a new instance is in every caller's rotation within about that (FR-034), that a caller which vanishes is noticed within a minute when connected directly and by the gateway's own rules otherwise (FR-051), and replace "The gateway routes HTTP/1.1. It forwards no gRPC or HTTP/2 to services". `DOCS/deploy/expose.md`: one hostname for both, calling it with a gRPC client and the CA, a service with gRPC and no HTTP is exposable. Its table of refusals (line 60 today) still says a service with `"http": false` has nothing to route to; the refusal is now `serves no HTTP and no gRPC`. `DOCS/operate/local-console.md`: gRPC methods are listed and not callable. `DOCS/build/http-endpoints.md` and `DOCS/build/testing.md`: a pointer each. `DOCS/concepts/architecture.md` and `README.md`: the endpoint is one of two kinds.
- [ ] T062 [US10] `DOCS/reference/limitations.md`: remove "no gRPC or HTTP/2 to services" and rewrite "One port, HTTP only"; add, each standing alone — gRPC endpoints are for Scala services only; a web page cannot call a gRPC endpoint (no gRPC-Web); the local console does not call gRPC methods; a gRPC call between services starts a new trace in the called service; a Python, TypeScript or Rust service cannot call a gRPC endpoint as itself. Write `APIT/GrpcDocumentationSuite.scala` with tests named for the two scenarios of `features/documentation/grpc.feature`, reading the pages from `docs/`: each "says" and "describes" step is an assertion on a page's text, and "does not say that a service serves HTTP only" asserts the two removed sentences are in no page under `docs/`.
- [ ] T063 [US10] `CLAUDE.md`: add `grpc` to the module dependency direction and say why it sits above `http`; an HTTP Endpoint row's sibling in the hosting table; `sbt grpc/test` and the spikes among the commands; "Seven modules" becomes eight in *Publishing*, with `ankka-grpc` among the libraries a service depends on; and a trap for each thing this feature learned the hard way — at the least: the 15-second route timeout and `timeouts.request: "0s"`; a `ClusterIP` balances connections, not calls; grpc-java answers a marshaller that throws with `UNKNOWN`; a bound address is listed as an HTTP one, so gRPC has `grpcAddress`. Add anything a spike or a cluster run taught that is not in `research.md`.
- [ ] T064 [US10] Run `just docs-sync`, `just docs-reference` and `just docs`, and commit what the sync rendered into `marketplace/plugins/ankka/skills/` and `ankka.g8/src/main/g8/.claude/skills/`. `sbt 'cli/testOnly *TemplateSuite' -Dankka.template.tests=off` is unaffected; if the Scala template suite is run, the expanded project still builds with no gRPC in it (FR-040).

**Checkpoint**: `just docs` green; `sbt 'controlPlaneApi/testOnly *GrpcDocumentationSuite *DocumentationDescriptorsSuite'` green.

---

## Phase 13: Polish and cross-cutting

- [ ] T065 [P] Write `GRT/GrpcBenchmark.scala`, ignored unless `-Dankka.benchmarks=on`: on one `AnkkaTestKit` service with both servers, the median of 2,000 `GetCart` calls over gRPC is no greater than the median of the same call over HTTP (SC-007) — both through real clients to real ports, reaching the real entity, which is the denominator the criterion names; and the two 100,000-part streams of T044 with the heap after a full collection no larger at the end than after the first 1,000 parts plus a fixed margin (SC-008). Record the figures in `research.md`.
- [ ] T066 [P] Publishing. `sbt publishLocal` produces eight artifacts, `ankka-grpc_3` among them, and its POM's compile scope names the four grpc-java artifacts directly and no ScalaPB one. `templateArtifacts` is unchanged (the template uses no gRPC). Check `.github/workflows/release.yml` publishes by `publish / skip` and needs no list changed; if it names modules, add this one.
- [ ] T067 [P] Compatibility (FR-052, R10). Add `Compatibility.GrpcSince` to `API/Compatibility.scala` and a fifth problem to `ServiceSpec.problems` with the message of contract `descriptor-and-configuration.md`: `grpc` with a declared `runtime` below it is refused, naming both versions; an undeclared runtime is unchecked, as today. Cases in `APIT/DescriptorSuite.scala` are written against the constant, never a literal: one below it refused, equal and above accepted, undeclared accepted. Set the constant to the version this feature is first released in, in the change that cuts that release, and say so in a comment beside it; a case asserts it is not above `BuildInfo.version`'s major and minor plus one, so a constant nobody set cannot outlive a release unnoticed.
- [ ] T068 Complete the *Requirement coverage* table at the end of this file: beside each of FR-001 to FR-052, the suite and case, or the feature scenario, that fails without it. The table names the tasks today; this task names the tests. An FR whose row cannot be filled is a missing test, and is written before this task is ticked.
- [ ] T069 `sbt scalafmtAll scalafmtSbt`, then `sbt compile` warning-free outside `grpcFixtures`, `shoppingCartApi` and `protocol`.
- [ ] T070 `sbt -Dankka.cluster.tests=off test` green; then `caffeinate -i sbt test` green, reading any failure whose duration is absurd as the machine's and rerunning it awake.
- [ ] T071 Quickstart step 6 by hand on kind: `just up`, apply the sample with `"grpc": true`, expose it, and from the laptop `grpcurl -cacert ~/.ankka/local-ca.crt <hostname>:8443 list` and `curl` the same hostname. Then `kubectl get deploy,svc,networkpolicy,httproute -n <a project without gRPC> -o yaml` before and after upgrading the operator image shows no object changed and no pod restarted (SC-006 on a real leftover cluster, which an empty one cannot show).
- [ ] T072 Final gates: `grep -rn "@ignore" features/` prints nothing; the BDD checker reports 0 findings and at least one spec read; `python3 .github/ci-coverage.py` passes; `RenderingGoldenSuite` passes with fixtures unchanged since T002.

---

## Dependencies

```text
Setup (T001–T004) → Foundational (T005–T009) → US1 (T010–T016) → US2 (T017–T021)   ← MVP
US2 → US6 (T042–T047)            US2 → US8 (T052–T054)      US2 → US9 (T055–T057)
US2 → US3 (T022–T028) → US4 (T029–T033)
US2 + US3 → US5 (T034–T041) → US7 (T050–T051)
US6 → T048, T049 (need US4)      US4 → T058 (needs US9)     US4 → the outside case of T051
everything → US10 (T059–T064) → Polish (T065–T072)
```

- **US1 and US2** are the P1 pair; nothing ships before both, because T011's admission is a
  placeholder that only `Acl.AllowAll` passes.
- **US6, US8 and US9** need only US2 and touch only `modules/grpc` and the sample, so they can be
  built before or beside the platform stories.
- **US3** is the first story to touch the control plane and the operator. T002 must be green
  before it and stay green through it.
- **US4** starts with a spike (T029) whose failure changes its design.
- **US5**'s offline tasks (T034–T040) need no cluster; T041 needs US3.
- **US7** needs a deployed caller and callee: US3 and US5.

## Parallel execution examples

- **Setup**: T002 beside T003; T004 after T003.
- **Foundational**: T005, T006 and T007 in parallel; T008 after T007; T009 after T004.
- **US1**: T010 → T011 → T012 serially (one module, shared types); T013 and T015 in parallel once
  T012 has landed; T014 and T016 after.
- **US2**: T017 and T018 in parallel; T019 after both; T020 beside T021.
- **US3**: T022, T023 in parallel; T024 after both; T025 → T026; T027 in parallel with all of
  them; T028 last.
- **US4**: T030 beside T029; T031 → T032 → T033 after the spike.
- **US5**: T034, T035, T036 in parallel; T037 after T034 and T035; T038 beside T039; T040 → T041.
- **US6**: T042 → T043; T044 beside T045; T046 beside T047; T048 and T049 when US4 has landed.
- **US8, US9**: T052 → T053, with T054 in parallel; T055 → T056, with T057 in parallel.
- **US10**: T060 and T061 in parallel with T059; T062 after; T063 and T064 last.
- **Polish**: T065, T066, T067 in parallel; T068–T072 in order.

## Implementation strategy

**MVP is Setup + Foundational + US1 + US2** (T001–T021): a Scala service serves a unary gRPC API
under an ACL on a developer's machine, tested offline. It needs no cluster and changes nothing a
deployed service renders.

**Then the library, before the platform**: US6, US9 and US8 (T042–T047, T052–T057) finish
`ankka-grpc` as a library — every kind of method, reflection, traces. All offline.

**Then the platform, in the order risk falls**: US3 (T022–T028) is the only work that can break an
existing deployment, which is why the golden suite exists before it. US5 and US7 (T034–T041,
T050–T051) make gRPC usable between services. US4 (T029–T033, T048–T049, T058) is last of the
platform stories because it alone rests on behaviour of software outside this repository; if its
spike fails, everything before it still ships and the spec's FR-046 goes back to the user.

**US10** is written against behaviour that exists, so it is last, though each story's regions are
marked in the sample as the story lands.

Land each phase as its own pull request against `main`: each leaves `main` releasable, and only a
service that declares gRPC is affected by any of them. The `019-service-topology` branch creates
`GLOSSARY.md`, `features/` and a CI job of its own; whichever of the two merges second reconciles
them, and this branch's widened definitions of `handler`, `request` and `call` are the ones to
check.

Three tasks decide design and stop the work if they fail: **T003** (the dependency bump), **T008**
(TLS under grpc-java's shaded Netty) and **T029** (gRPC through the gateway on a header rule). Each
names its fallback.

## Requirement coverage

Which tasks deliver each requirement. T068 adds the test that fails without it.

| Requirement | Tasks |
|---|---|
| FR-001, FR-002, FR-003 | T010–T012, T014, T016 |
| FR-004 | T012, T013, T019 |
| FR-005, FR-006 | T011, T012, T013 |
| FR-007, FR-008, FR-010 | T009, T011, T014 |
| FR-009, FR-018 | T012, T019, T021 |
| FR-011, FR-012 | T042–T045 |
| FR-013, FR-014 | T012, T013 |
| FR-015, FR-016, FR-017, FR-019 | T017, T019–T021 |
| FR-020 | T018–T020 |
| FR-021, FR-024, FR-025 | T022–T024 |
| FR-022 | T012, T018, T025, T026, T028 |
| FR-023, FR-042 | T002, T026, T032, T033, T071 |
| FR-026 | T027, T028 |
| FR-027, FR-041, FR-044, FR-046 | T029–T033 |
| FR-028–FR-032 | T034, T035, T037–T041 |
| FR-033, FR-034 | T036, T037, T050, T051 |
| FR-035 | T012, T050, T051 |
| FR-036, FR-037 | T052–T054 |
| FR-038 | T012, T016 |
| FR-039, FR-040 | T059–T064 |
| FR-043 | T031, T048, T049, T061 |
| FR-045 | T051 |
| FR-047–FR-050 | T055–T058 |
| FR-051 | T050, T061 |
| FR-052 | T067 |
| SC-002 | T014, T020 |
| SC-003 | T020, T028 |
| SC-004, SC-005 | T051 |
| SC-006 | T002, T026, T032, T071 |
| SC-007, SC-008 | T044, T065 |
| SC-009 | T062 |
| SC-010 | T033 |
| SC-011 | T056, T057 |

SC-001 is a measure of a developer's first half hour, and no task builds it; T059's page is what
it is measured on.

## Format validation

Every task line above is `- [ ] T### [P]? [US#]? description with a repository path`; setup,
foundational and polish tasks carry no story label; story tasks carry exactly one.
