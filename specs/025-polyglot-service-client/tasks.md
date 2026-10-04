# Tasks: Polyglot Service Client — Calling Another Service as Yourself from Any Language and Any Component

**Input**: Design documents from `/specs/025-polyglot-service-client/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" list turns each fact read from
code into a check before the code that relies on it. The scenarios are in
`features/service-calls/` and `features/documentation/service-calls.feature`; where a task says
"case", it means a `test(...)` in the named suite (or its equivalent in the SDK's test runner),
named for the scenario it holds. Which suite holds which scenario is research R14.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a Python or TypeScript service calls another and is admitted by name), US2
  (a Scala workflow step, agent tool, consumer or timed action calls another service), US3 (the
  protocol and the SDKs agree, proven by the conformance suite), US4 (the limitation is withdrawn
  and the guide shows every door)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `SDK` = `modules/sdk/src/main/scala/…/sdk`;
`RT`/`RTT` = `modules/runtime/src/{main,test}/scala/…/runtime`; `HTTP`/`HTTPT` =
`modules/http/src/{main,test}/scala/…/http`; `AGENT` = `modules/agent/src/main/scala/…/agent`;
`TK`/`TKT` = `modules/testkit/src/{main,test}/scala/…/testkit`; `SC`/`SCT` =
`sidecar/src/{main,test}/scala/…/sidecar`; `CONF` = `SCT/conformance`; `OPT` =
`operator/src/test/scala/…/operator`; `API`/`APIT` =
`controlplane-api/src/{main,test}/scala/…/controlplane/api`; `PXT` =
`proxy/src/test/scala/…/proxy`; `CART`/`CARTT` = `samples/shopping-cart/src/{main,test}/scala`;
`PY` = `sdks/python`; `TS` = `sdks/typescript`; `RS` = `sdks/rust`; `DOCS` = `docs`; `SKILL` =
`tools/docs/skill`. "R*n*" is a section of `research.md`; "V*n*" an item of its *Verify first*
list (R17); a contract is named by its file under `contracts/`.

The branch `025-polyglot-service-client` exists, in the worktree
`.claude/worktrees/025-polyglot-service-client`; work there. Every `sbt` command below takes
`-Dankka.cluster.tests=off -Dankka.template.tests=off` unless the task names a k3s suite.

---

## Phase 1: Setup — verify first

**Purpose**: three facts the design reads from code or from a library's behaviour. Each would
change a decision if it did not hold, so each is checked before anything is built on it.

- [X] T001 V1, the JDK's client and a closed connection. In `HTTPT/ServiceClientSuite.scala` add a case that starts a `java.net.ServerSocket` on `127.0.0.1` and port 0 whose accept loop counts each connection and closes it at once, builds an `HttpServiceClients` with `ankka.local-services."flaky" = "http://127.0.0.1:<port>"` (as the suite's existing local-address case does), and makes one `GET` and then one `POST` through `request`. Assert each raises, and that the listener saw **exactly one** connection for each. Run `sbt 'http/testOnly *ServiceClientSuite'`. If either count is above one the JDK retried: stop, write the counts under a new "Verified during implementation" heading at the end of `specs/025-polyglot-service-client/research.md`, and settle with the user how the client is told not to (a system property the runtime sets before any client class loads, as the proxy does for restricted headers) before T013. If both are one, record that.
- [X] T002 [P] V2, `JAVA_OPTS` reaches the sidecar's JVM. Run `sbt sidecar/docker:publishLocal`, then `docker run --rm -e JAVA_OPTS=-Dankka.probe.v2=yes --entrypoint sh ankka-sidecar:<tag> -c 'grep -c JAVA_OPTS /opt/docker/bin/*'` (the tag is the one the build printed) and confirm the launcher reads the variable. Record the launcher's path and the result in research's "Verified during implementation". If it does not, R13's route for a containerised sidecar is wrong: stop and settle it.
- [X] T003 [P] V3, a span named for another service. Read `RT/ObservabilityDocuments.scala` and `RT/TopologyJson.scala` for how a recorded span's component name becomes a node of a trace document, and write down in research's "Verified during implementation" what a span whose component is `service:local/carts` (a name `ExternalServices.nameFor` gives, not a registered component) renders as today, with file and line. If rendering drops or mislabels a span whose component is not registered, add to T012 that the trace document names such a span's kind as another service, and add its case to T007.

**Checkpoint**: three facts recorded with evidence. Nothing but one test and a section of
`research.md` has changed.

---

## Phase 2: Foundational — the client's own changes

**Purpose**: the four changes to `HttpServiceClients` that every door then shares: the fourth
error (R3), the timeout as a setting (R4), the header rule (R7) and the span (R10). An endpoint's
calls already exercise all four, so this phase is complete and testable before any context or
protocol changes.

**⚠️ CRITICAL**: both user stories of priority one build on this. Nothing in Phases 3 to 5 starts
before the checkpoint.

### Tests first

- [X] T004 [P] In `HTTPT/ServiceClientSuite.scala` add, beside T001's case, each failing until T013: (a) a call to `ankka.local-services."gone"` set to a port nothing listens on raises `ServiceUnanswered` whose `service` ends `/gone`, whose cause is a `java.net.ConnectException`, and does **not** raise `ServiceUnresolvable`; (b) with `ankka.service-client.timeout = 500ms` in the client's config, a callee that sleeps two seconds raises `ServiceUnanswered` whose `reason` names the timeout, within two seconds; (c) with no such setting the configured value read by the client is 30 seconds (assert through `config.getDuration("ankka.service-client.timeout")` after `ConfigFactory.load()`); (d) a request given the headers `X-Ankka-Caller`, `x-ankka-local-caller`, `Host`, `Content-Length`, `Expect`, `Forwarded`, `X-Forwarded-For`, `Connection`, `Keep-Alive`, `TE` and `X-Request-Id` reaches a callee that answers the names of the headers it received, and of those only `x-request-id` is among them, with no exception from the JDK; (e) a `contentType` given as the parameter arrives, and a `Content-Type` given in `headers` beside it does not make two.
- [X] T005 [P] Create `PXT/OutboundHeadersSuite.scala`: (a) every name in `proxy-core`'s `Headers.HopByHop` is removed by `OutboundHeaders.sent`; (b) a name starting `Headers.PlatformPrefix`, in upper and in mixed case, is removed; (c) `Headers.ForwardedProto`, `ForwardedHost`, `ForwardedPort`, `ForwardedFor` and `Forwarded` are removed; (d) order is kept and a name that appears twice arrives twice. The suite fails to compile until T010, which is the check. `proxy` already depends on `runtime` and `proxy-core`.
- [X] T006 [P] In `CORET/PlatformVariablesSuite.scala` add a case: `PlatformVariables.runtimeOnly("ANKKA_SERVICE_CLIENT_TIMEOUT")` is true, `platformOnly` of it is false, and `withheldFromModule` of it is true. In `OPT/ProcessHostingRenderingSuite.scala` add a case: a process-hosted descriptor whose environment gives `ANKKA_SERVICE_CLIENT_TIMEOUT=5s` renders it on the platform's container and not on the process's. In `APIT/DescriptorSuite.scala` add a case: a descriptor giving that variable has no problems. All three fail until T011.
- [X] T007 [P] In `TKT/topology/TopologySuite.scala`, beside the case where an endpoint calls another service with `clients.services(name).getText(...)`, add a case that reads the service's recorded spans after that request (as the suite's trace cases do) and asserts: there is a span whose component is the name `ExternalServices.nameOf("local", name)` gives and whose handler is `GET`; its trace id is the endpoint span's; **its parent span id equals the endpoint span's id**; its outcome is ok. Add a second case where the callee answers 403 and assert the span's outcome is refused. Fails until T012 and T013.

### Implementation

- [X] T008 [P] In `SDK/ServiceClient.scala` add `final case class ServiceUnanswered(service: String, reason: String, cause: Throwable | Null = null) extends RuntimeException(s"$service did not answer: $reason", cause)` beside the other three, with scaladoc saying when it is raised and that the service may have received the request. Amend the trait's scaladoc and `ServiceCallFailed`'s to say which errors the raw `request` raises (the three where no answer came) and which only a typed helper does.
- [X] T009 [P] In `modules/runtime/src/main/resources/reference.conf` add, after the `secrets` block, `service-client { timeout = 30s` and `timeout = ${?ANKKA_SERVICE_CLIENT_TIMEOUT} }` with a comment in the file's style: what the setting is, that connecting has five seconds of its own, and that there is no per-call timeout.
- [X] T010 [P] Create `RT/OutboundHeaders.scala`: `private[ankka] object OutboundHeaders` with `def sent(headers: Seq[(String, String)]): Seq[(String, String)]`, removing in any letter case names starting `x-ankka-`, `forwarded`, names starting `x-forwarded-`, `host`, `content-length`, `expect`, `content-type` and the eight hop-by-hop names (R7), keeping order. Scaladoc says why each group is not the handler's to say and that `PXT/OutboundHeadersSuite` holds the list to the proxy's.
- [X] T011 [P] In `CORE/PlatformVariables.scala` add `val ServiceClientTimeout: String = "ANKKA_SERVICE_CLIENT_TIMEOUT"` and put it in `RuntimeOnlyNames`, widening that set's scaladoc to name it (the platform's program makes the call, so the setting is its). The file must still import nothing outside the standard library: the operator compiles it too.
- [X] T012 In `RT/Observability.scala` add `private[ankka] def calling[A](callee: String, handler: String)(outcomeOf: A => SpanOutcome)(body: => A): A`: takes `Trace.currentOrigin` and the current trace before the call; when the thread is in a trace, begins a span with that trace id, the current span id as parent, and `names.intern(callee)`, `names.intern(handler)`; runs `body`; on return completes the span with `outcomeOf(result)` and counts with `made`; on an `HttpTimeoutException` completes it failed and counts `madeUnanswered(…, Unanswered.TimedOut)`; on any other non-fatal failure completes it failed and counts `Unanswered.Undelivered`; rethrows. When the thread is in no trace it counts and begins no span. Scaladoc states that the two names are bounded by `ExternalServices` and the method, so nothing unbounded is interned. Include whatever T003 added.
- [X] T013 In `RT/HttpServiceClients.scala`: (a) read `ankka.service-client.timeout` once from `config` and use it in `send` in place of `Duration.ofSeconds(30)`; (b) pass `headers` through `OutboundHeaders.sent` in `send`; (c) replace the body of `counted` with a call to `observability.calling` (and plain execution when there is no `Observability`), keeping the outcome rule (4xx refused, 5xx failed, otherwise ok); (d) wrap what `counted` returns so that `ServiceUnresolvable` and `ServiceIdentityMismatch` pass through unchanged, an `HttpTimeoutException` becomes `ServiceUnanswered(target, s"no answer within $timeout", e)`, and any other non-fatal failure becomes `ServiceUnanswered(target, <the failure's message or class name>, e)`, in both `ClusterClient` and `LocalClient`, whether or not there is an `Observability`. Apply whatever T001 settled. Update the class scaladoc: four errors, the timeout, the headers.
- [X] T014 Run `sbt 'http/testOnly *ServiceClientSuite' 'proxy/testOnly *OutboundHeadersSuite' 'core/testOnly *PlatformVariablesSuite' 'operator/testOnly *ProcessHostingRenderingSuite' 'controlPlaneApi/testOnly *DescriptorSuite' 'testkit/testOnly *TopologySuite'` and `sbt compile`: all green, warning-free. Then `grep -rn "HttpTimeoutException\|ConnectException\|IOException" --include='*.scala' modules samples sidecar controlplane | grep -v /target/` and read each hit that surrounds a `services(` call: any handler that caught the JDK's exception around a call to another service now catches `ServiceUnanswered`.

**Checkpoint**: an endpoint's call to another service raises one of four named errors, waits as
long as the service is set to, sends none of the platform's headers, and is a span under the
endpoint's span. No context and no protocol file has changed.

---

## Phase 3: User Story 2 — a Scala workflow step, agent tool, consumer or timed action calls another service (Priority: P1)

**Goal**: the five contexts that may call another service carry the service's one
`ServiceClients`; an entity's and a view's do not; a workflow's refuses outside a step.

**Independent Test**: `sbt 'testkit/testOnly *ServiceCallsSuite'` — one case per scenario of
`features/service-calls/components.feature`, against a stand-in on loopback, each failing or not
compiling without this phase.

### The test kit's two stand-ins

- [X] T015 [P] [US2] Create `TK/ScriptedService.scala` per `contracts/scala-api.md`: `ScriptedService.start(): ScriptedService` binds the JDK's `com.sun.net.httpserver.HttpServer` on `127.0.0.1` and port 0; `address: String` (`http://127.0.0.1:<port>`); `answer(handler: ScriptedService.Request => ServiceResponse)` sets what it answers (default: 200, `text/plain`, `ok`); `failNext(status: Int = 500)` answers the next request with that status once and then goes back; `delay(duration)` waits before each answer; `requests: Vector[ScriptedService.Request]` is every request received in order, each with `method`, `path` (query included), `headers`, `contentType` and `body`; `stop()`. Thread-safe. No fixed port anywhere.
- [X] T016 [P] [US2] Create `TK/ScriptedServices.scala` per `contracts/scala-api.md`: a `ServiceClients` for unit tests with `answer(name)(handler)`, `unresolvable(name)`, `unanswered(name)`, `mismatch(name)` and `requests`. Each client's `target` is `local/<name>` (or `<project>/<name>`). A call to a service with no script throws an `AssertionError` naming the service and saying how to script it.
- [X] T017 [P] [US2] Create `TKT/ScriptedServicesSuite.scala`: an answer set is returned and the request recorded; `post` encodes the body as JSON and `get` decodes the answer; a 404 answer raises `ServiceCallFailed` from `get` and is returned by `request`; each of the three scripted failures raises its error; an unscripted name fails naming it. And `TKT/ScriptedServiceSuite.scala`: a request made with the JDK's client is recorded whole; `failNext` fails exactly once; `delay` delays.
- [X] T018 [US2] In `TK/AnkkaTestKit.scala` add `localServices: Map[String, String] = Map.empty` to `start` (and carry it through `restartService`), setting `ankka.local-services."<name>"` for each entry on the service's config where `withSecretKey` sets the key. Run `sbt 'testkit/testOnly *ScriptedServicesSuite *ScriptedServiceSuite'`: green, with nothing but the kit changed.

### Tests first

- [X] T019 [US2] Create `TKT/ServiceCallsSuite.scala` with `LogCapturing`: a service of a workflow, a consumer on an event sourced entity's events, a timed action and an agent with one tool (its model a `TestModelProvider` of its own), each of whose handlers calls `context.services("psp-gateway")`, started by `AnkkaTestKit.start(…, localServices = Map("psp-gateway" -> scripted.address))` with `HttpServer.at("127.0.0.1", 0)`. One case per scenario of `components.feature`, named for it: (1) the step's request reaches the scripted service and the workflow's state holds the answer; (2) the model asks for the tool, the tool's request reaches the scripted service and the tool result in the session is the answer; (3) `scripted.failNext()`, an event is persisted, and `scripted.requests` comes to hold two requests for that change (`eventually` on the count reaching two, never on "some request"); (4) a timer is scheduled and its request arrives; (5) a workflow whose **command** handler calls the service is refused with `BadRequest` whose message names "a step", and `scripted.requests` is empty; (6) the step's span id is read from the recorded spans and the span named for the service has **that id as its parent**; (7) a second kit started with no `localServices` and no registry entry: the step fails with `ServiceUnresolvable` naming what was tried; (8) `scripted.stop()` before the workflow runs: the step fails with `ServiceUnanswered` and not `ServiceUnresolvable`. Add a ninth case, "an autonomous agent's tool calls another service": an autonomous agent with one tool that calls `context.services("psp-gateway")`, its model a `TestModelProvider` of its own scripted to call the tool and then `complete_task`, is assigned a task; the tool's request reaches the scripted service and the task completes with the answer. Add a tenth, for a graph consumer built on the same `ConsumerContext`: its handler's request reaches the scripted service. Add a case with `compileErrors("context.services")` against an `EventSourcedEntityContext`, a `KeyValueEntityContext` and a `ViewComponentContext`, asserting each is non-empty, and the same expression against a `ConsumerContext` asserting it is empty. Add the half-second timeout case of `calling.feature` ("a call that is not answered within the time its service is set to wait is unanswered"): a kit whose config sets `ankka.service-client.timeout = 500ms`, `scripted.delay(2.seconds)`, the step fails with `ServiceUnanswered`, and `scripted.requests.size == 1`. Mark the step that calls the service with `// docs:start step-calls-service` and `// docs:end step-calls-service`. Drain every workflow before its case ends. The suite does not compile until T020 to T025; that is its first failure.

### Implementation

- [X] T020 [US2] In `SDK/contexts.scala` add `def services: ServiceClients` to `WorkflowContext` (scaladoc: for a step; a command handler's call is refused, as its secret store's is) and `ConsumerContext`, and a `services: ServiceClients` field to `SimpleWorkflowContextImpl` and `SimpleConsumerContext`. In `SDK/TimedAction.scala` add it to `TimedActionContext` and `SimpleTimedActionContext`. Not to `ComponentContext`, `EntityContext` or `ViewComponentContext`.
- [X] T021 [P] [US2] In `AGENT/Agent.scala` add `def services: ServiceClients` to `AgentContext` and the field to `SimpleAgentContext`; in `AGENT/autonomous/AutonomousAgent.scala` the same on `AutonomousAgentContext` and its implementation, each beside `secrets` with scaladoc saying a tool may call another service through it.
- [X] T022 [US2] In `RT/Ankka.scala`: (a) in `start`, beside `secrets`, make one `ServiceClients` before any component is instantiated — a small `private final class` in that file whose two `apply`s delegate to a `lazy val` `HttpServiceClients(system.settings.config, None, observability = Some(Observability(system)))`, so nothing is built until the first call (R1); (b) pass it to `initWorkflow` and to `AnkkaService`, replacing the `lazy val services` there with a constructor `val services: ServiceClients` (keep its scaladoc, and say every context is given this one); (c) add `private[ankka] def withServices(wrap: ServiceClients => ServiceClients): ServiceBuilder` to `ServiceBuilder`, applied to that one client before anything receives it.
- [X] T023 [US2] In `RT/StepScope.scala` add `def stepsOnly(underlying: ServiceClients): ServiceClients`, whose clients throw `CommandError("a workflow calls another service in a step, not in a command handler", ErrorCode.BadRequest)` from `request` when the mark is absent (the check is in `request`, so obtaining a client outside a step is harmless and using one is refused). In `RT/WorkflowHost.scala` give the workflow's context `StepScope.stepsOnly(services)` beside `StepScope.stepsOnly(secrets)`.
- [X] T024 [US2] Thread `service.services` beside `service.secrets` at every site research R2 lists in `RT/ProjectionRuntime.scala`, `RT/TopicHandlers.scala`, `RT/TimerRuntime.scala` and `RT/TimerSweeper.scala`, so that every `SimpleConsumerContext` and `SimpleTimedActionContext` is constructed with it.
- [X] T025 [P] [US2] Thread it the same way in `AGENT/AgentRuntime.scala` and `AGENT/autonomous/AutonomousAgentHost.scala`, so that every `SimpleAgentContext` and autonomous agent context is constructed with it.
- [X] T026 [US2] Run `sbt compile Test/compile`: every remaining construction of the five context classes is a compile error (the sidecar's remote hosts, the samples' and other suites' fixtures). Give each the service's client where a running service is in scope and `ScriptedServices()` in a unit fixture. In `TK/ConsumerTestKit.scala` add `services: ScriptedServices = ScriptedServices()` to both factories, beside `secrets`, and read it back as `kit.services`, with a case in `TKT/ScriptedServicesSuite.scala` that a consumer under the kit makes a call the double records. Then `sbt 'testkit/testOnly *ServiceCallsSuite *ScriptedServicesSuite *ScriptedServiceSuite *SecretStoreSuite'` and `sbt 'agent/test' 'http/test'`: green, warning-free.

**Checkpoint**: User Story 2 is whole. A Scala service's steps, tools, consumers and timed actions
call another service; an entity cannot, by type.

---

## Phase 4: User Story 1 — a Python or TypeScript service calls another service and is admitted by name (Priority: P1) 🎯 MVP

**Goal**: a process asks its sidecar to make the call; the sidecar makes it as the service, with
the service's certificate, and the process holds no key.

**Independent Test**: `caffeinate -i sbt 'sidecar/testOnly *SidecarClusterSuite'` — the Python
service `orders` is admitted by a route of the Scala `carts` that admits only `orders`, and
another service is refused by the same route.

### Protocol 1.7 and the sidecar

- [X] T027 [US1] Edit `protocol/src/main/protobuf/ankka/protocol/v1/client.proto` exactly as `contracts/protocol.md` gives it: `import "ankka/protocol/v1/endpoint.proto";`, `rpc Request (ServiceRequest) returns (ServiceReply);` with its comment, and the messages `ServiceRequest`, `ServiceReply` and `ServiceFailure` with its `Reason` enum (`UNANSWERED = 0`). In `protocol/README.md` change the version to `1.7` and add the sentence saying what 1.7 added and how each side answers the other when it is older. Refresh the three copies with their own scripts (`cd sdks/python && uv run python scripts/proto.py`; `cd sdks/typescript && npm run proto`; `sdks/rust/scripts/proto.sh`) and confirm the three `diff -r` lines of `.github/workflows/ci.yml` report nothing. Run `sbt protocol/compile` and `cd sdks/rust && cargo test --workspace`: the crate builds with the two new messages and uses neither.
- [X] T028 [US1] Raise the version to 1.7 where it is written: `Version` in `RT/remote/Conversation.scala`, `Protocol.version` in `API/Compatibility.scala` (and its scaladoc's history), `PROTOCOL_VERSION` in `PY/src/ankka/service.py` and in `TS/src/spec.ts`. Leave `RS/ankka/src/service.rs` at `1.6` (R9, R12). Run `grep -rn '"1\.6"\|1, 6' --include='*.scala' --include='*.py' --include='*.ts' . | grep -v node_modules | grep -v /target/` and update each test that pins the old number; a suite that holds the Rust crate's version equal to the protocol's, if there is one, is settled with the user rather than loosened.
- [X] T029 [US1] Create `SCT/ClientRequestSuite.scala` with `LogCapturing`: a service of one event sourced entity, one key value entity, one workflow with a command and a step, and one endpoint, started with `AnkkaTestKit` and `localServices` naming a `ScriptedService`, and a `ClientLogic` built over it with a declared protocol version the test chooses. Cases, each sending a `ServiceRequest` and reading the `ServiceReply`: (a) with no metadata the scripted service receives method, path with query, content type, body and a plain header, and the reply is `response` with its status, content type, body and a header the scripted service set; (b) with `ankka-caller` naming the endpoint and a route it serves, the service's topology counts one call from that route to the service; (c) with no `ankka-caller` — "a call a process makes outside any handler is made and counted from the unknown caller" — the call is made and counted from the unknown caller; (d) with `ankka-caller` naming a command of the event sourced entity, and again of the key value entity — "an entity's handler in a process may not call another service" — the reply is `error`, `BAD_REQUEST`, its message naming the component and "event sourced entity" or "key value entity", and the scripted service received nothing; (e) naming the workflow's command: refused naming "a step"; naming its step: made; (f) an `ankka-caller` the service did not declare: made, counted from the unknown caller; (g) a `ClientLogic` told the process declared `1.6` — "a process made for a protocol version before calls to other services is not served one" — answers `error`, `BAD_REQUEST`, naming `1.6` and `1.7`, and the scripted service received nothing; (h) "two calls a process makes at once are both answered without waiting for each other": two scripted services, one with `delay(3.seconds)`, both requests sent at once, and the fast one's reply arrives in under a second; (i) a body of 4,000,001 bytes is `error`, `BAD_REQUEST`, naming the limit; an answer of 4,000,001 bytes is `error`, `INTERNAL`, naming the limit and the service; (j) a name not in `localServices` is `failure` `UNRESOLVABLE`; a name at a closed port is `failure` `UNANSWERED`; with `ServiceBuilder.withServices` wrapping the client so `impostor` raises `ServiceIdentityMismatch`, that name is `failure` `IDENTITY_MISMATCH`; (k) an empty service name, a path without a leading `/` and an empty method are each `error`, `BAD_REQUEST`, naming the field; (l) with `ankka-trace-id` and `ankka-span-id` in the metadata, the recorded span for the call has that trace id and that span id as its parent; (m) "nothing the process says in a call makes it come from another service": a request whose headers include `X-Ankka-Caller: ankka://payments/orders` and `X-Ankka-Local-Caller: anything` reaches the scripted service with neither. Fails to compile until T030.
- [X] T030 [US1] In `SC/ClientLogic.scala` add `def request(request: ServiceRequest): Future[ServiceReply]` on `AnkkaExecutors.virtual`, as the secret store's calls are, and a constructor parameter for the protocol version the process declared. In order: answer `BAD_REQUEST` naming both versions when the declared minor is below 7; validate the name, the project when present, the method and the path; refuse a body over 4,000,000 bytes; resolve the caller with `observability.declared.origin(metadata)` and look the component up in `service.registry` — refuse a declared handler of an `EventSourcedEntity` or `KeyValueEntity`, naming the component and the kind in words, and a `Workflow`'s declared handler whose `HandlerKind` is not `Step`, naming "a step" (R6); run the call inside `Trace.within(traceId, spanId, origin)` when the metadata carries a trace and a believed caller, `Trace.within(traceId, spanId)` with a trace alone, `Trace.asOrigin(origin)` with a caller alone, and bare otherwise (R10); call `service.services(project, name)` or `service.services(name)` then `.request(method, path, body, contentType, headers)`; answer `response` built from the `ServiceResponse`, refusing an answer body over the limit; translate `ServiceUnresolvable`, `ServiceIdentityMismatch` and `ServiceUnanswered` to `failure` with the reason and the exception's detail, a `CommandError` to `error` with its code, and anything else to `error` `INTERNAL`. Add `request` to the section comment's list of what a process calls back for.
- [X] T031 [US1] In `SC/ClientService.scala` add `def request(request: ServiceRequest): Future[ServiceReply] = logic.request(request)` beside the secret store's three, under the comment that refusals travel in the reply. Carry the process's declared protocol version from `SC/Discovery.scala`'s result through `SC/SidecarExtension.scala` (and `SC/Main.scala` where the extension is built) into `ClientService` and `ClientLogic`; for a module, `SC/wasm/HostImports.scala`'s `ClientLogic` is given the module's declared version and gains no import. Run `sbt 'sidecar/testOnly *ClientRequestSuite *ProtocolSuite *WasmHostSuite'`: green.

### Python (independent of TypeScript)

- [X] T032 [P] [US1] Create `PY/tests/test_services.py`, in the manner of `test_secrets.py`, against a stub of the client's `Request`: (a) `get`, `get_text`, `post`, `put`, `delete` and `request` each build the `ServiceRequest` the contract gives — service, project when given, method, path, headers in order, content type `application/json` and the encoded body for `post` and `put`; (b) the request's `metadata` is the scoped client's, for a consumer, a graph consumer, a timed action, an agent, an autonomous agent (from a tool of each agent kind) and a workflow step, and for an endpoint it is the **current request's** metadata with no forwarding written by the handler (V5: assert it inside a handler run through `EndpointTestKit` and through the real HTTP servicer's task); (c) a `response` of 200 decodes to the type asked for; of 404 raises `ServiceCallFailed` with `status` and `body` from a typed helper and is returned by `request`; (d) each `failure` reason raises its own class, a subclass of `ServiceError`; an `error` raises `CommandError` with its code; a reply with no case set raises `CommandError` `INTERNAL`; (e) `UNIMPLEMENTED` raises `CommandError` `INTERNAL` naming `1.7` and the version the SDK speaks; (f) a body of 4,000,001 bytes is refused before the stub is called, naming the limit; (g) an event sourced entity's and a key value entity's `CommandContext` and a `View` have no `services` attribute; (h) a workflow's `services` raises `CommandError` `BAD_REQUEST` naming "a step" from a command handler and works in a step; (i) `ScriptedServices`: a scripted answer is returned and the request recorded, each scripted failure raises its error, an unscripted name raises naming it. Mark one test of a workflow step calling a service with `# docs:start step-calls-service` / `# docs:end step-calls-service`. All fail until T033 and T034.
- [X] T033 [US1] Create `PY/src/ankka/services.py` per `contracts/sdk-apis.md`: `SERVICES_SINCE = "1.7"`, `MAX_BODY_BYTES = 4_000_000`, `ServiceResponse`, `ServiceError` and its four subclasses, `ServiceClient`, `Services` (holding a client and a way to read the metadata to send), `ScriptedServices(Services)`, and a `HasServices` mixin shaped as `HasSecrets` is (a `services` property returning the assigned double or `Services(self.client)`, with a setter for a unit test). The module docstring says who has one and who has none, as `secrets.py`'s does.
- [X] T034 [US1] Give the kinds their client: add `HasServices` to `Endpoint` in `PY/src/ankka/endpoint.py` (its `services` reads the current request's metadata), `Consumer` in `consumer.py`, `TimedAction` in `timed_action.py`, `Agent` in `agent.py`, `AutonomousAgent` in `autonomous.py` and `GraphConsumer` in `graph.py`; add a step-only `services` property to `Workflow` in `workflow.py` beside its `secrets`; where `PY/src/ankka/server.py` sets `instance.secrets` on an endpoint, set `instance.services` too. Export `Services`, `ServiceClient`, `ServiceResponse`, `ScriptedServices` and the five error classes from `PY/src/ankka/__init__.py`. Run `cd sdks/python && uv run pytest -q tests/test_services.py && uv run mypy`: green.
- [X] T035 [US1] In `PY/examples/shopping_cart/endpoint.py` add a `GET /callers/call/{service}` route, inside `# docs:start call-another-service` / `# docs:end call-another-service`, that answers `await self.services(service).get_text(path)`, where `path` is the query parameter `path` when given and `/callers/whoami` otherwise, and turns `ServiceUnresolvable` into `HttpProblem(503, …)`, as the Scala sample's `CallersEndpoint` does after T040; add the `/callers/whoami` route beside it if the example has none, answering who the caller is in the Scala sample's words. In `PY/examples/shopping_cart/test_cart.py` add a test of the route with `ScriptedServices`. Add an integration test to `PY/tests/` (beside the existing integration tests, skipped where they are when Docker is absent) that starts a stand-in HTTP server on the host, starts the example with `AnkkaTestKit.start(…, env={"JAVA_OPTS": "-Dankka.local-services.carts=http://host.docker.internal:<port>"})`, calls `/callers/call/carts` and asserts the stand-in received `GET /callers/whoami` and the route answered what it sent.

### TypeScript (independent of Python)

- [X] T036 [P] [US1] Create `TS/test/services.test.ts`, in the manner of `secrets.test.ts`, against the fake sidecar `client.test.ts` uses, extended with `request`: the cases (a) to (i) of T032 in TypeScript's shapes (`service(name)`, `service(project, name)`; `getText`; schemas for bodies and answers; `Code.Unimplemented` for the too-old runtime; the endpoint's metadata is already scoped by the binding, so (b) asserts it for every kind alike: endpoint, consumer, graph consumer, timed action, agent, autonomous agent and workflow step, each making a call that reaches the fake sidecar), with the entity and view case written as a type-level check (`// @ts-expect-error` on `entity.services` and `view.services`) so `tsc` holds it. Mark one test of a workflow step calling a service with `// docs:start step-calls-service` / `// docs:end step-calls-service`. Every server the test starts destroys its sessions before `close()`. All fail until T037 and T038.
- [X] T037 [US1] Create `TS/src/services.ts` per `contracts/sdk-apis.md`: `SERVICES_SINCE`, `MAX_BODY_BYTES`, the `Headers` and `ServiceResponse` types, `ServiceError` and its four subclasses, `ServiceClient`, `Services`, `ScriptedServices`, `noServices()` and an `@internal` `servicesFor(client)` that finds the client's connection and metadata as `secretsFor` finds its connection. Erasable syntax only: no `enum`, no parameter properties.
- [X] T038 [US1] Give the kinds their client: a `get services()` and a setter, in the shape each kind's `secrets` has, on the classes in `TS/src/endpoint.ts`, `consumer.ts`, `timedAction.ts`, `agent.ts`, `autonomous.ts` and `graph.ts`; a step-only getter in `workflow.ts` throwing the `BAD_REQUEST` `CommandError` naming "a step" unless in a step. None on the two entity classes or the view. Export `Services`, `ServiceClient`, `ScriptedServices`, `noServices`, the five error classes and the two types from `TS/src/index.ts`. Run `cd sdks/typescript && npm run typecheck && npm test`: green on the Node in use, and `tsc` reports the two expected errors as expected.
- [X] T039 [US1] In `TS/examples/shopping-cart/endpoint.ts` add the `GET /callers/call/{service}` route inside `// docs:start call-another-service` / `// docs:end call-another-service`, as T035 does for Python (the optional `path` query parameter included), with `/callers/whoami` beside it if absent; add its unit test to `cart.test.ts` with `ScriptedServices`; add the integration test through a real sidecar container to the slow tests (`npm run test:slow`), with `env: { JAVA_OPTS: "-Dankka.local-services.carts=http://host.docker.internal:<port>" }`, using `try`/`finally` and not `await using`.

### A real cluster

- [X] T040 [P] [US1] In `CART/shoppingcart/api/CallersEndpoint.scala` add, inside the `allow-callers` region's neighbourhood but outside its `docs` markers, `withAcl(Acl.allowCallers(Callers.service("orders"))) { get("/orders-alone")(() => s"admitted: ${describe(caller)}") }`, with a comment saying it admits one service by name and nobody else, the internet included. In the same file's `call-another-service` region, make `/call/{service}` call the path the query parameter `path` gives when there is one and `/callers/whoami` otherwise, so the three languages' samples are one route. In `CARTT/shoppingcart/CallersSuite.scala` add three cases: `testKit.asCaller(Caller.Service("checkout", "orders"))` is answered 200 naming the orders service; `Caller.Gateway` is answered 403 — named "a route that admits only one service by name refuses the gateway"; `Caller.Service("checkout", "carts")` is answered 403. Run `sbt 'shoppingCart/testOnly *CallersSuite'`.
- [X] T041 [US1] In `build.sbt`, in the `sidecar` project's settings, define `sampleImageForClusterTests` as the two projects that already have it do (gated on `-Dankka.cluster.tests`, so switching the suites off skips the build) and hang it on both `Test / test` and `Test / testOnly` beside `sidecarImageForClusterTests`. It is a task dependency only: `sidecar` gains no dependency on `shoppingCart` in any compilation scope.
- [X] T042 [US1] In `SCT/SidecarClusterSuite.scala`: import the Scala sample's image into the node beside the others (its tag from `BuildInfo.imageTag`, never a literal); deploy it as the service `carts` and the Python image a second time as the service `orders`, in the suite's project, each through the operator as the suite's other services are; wait for both to be `Ready`. Add the case "a service in every language is admitted by name by a route that admits only it (Python, on a cluster)": `InPod.curl` from the `orders` pod to its own `https://orders.<namespace>.svc.cluster.local:9000/callers/call/carts?path=/callers/orders-alone` and assert `200` and a body naming "the orders service in project"; then `InPod.curl` from the suite's existing Python cart pod straight to `https://carts.<namespace>.svc.cluster.local:9000/callers/orders-alone` and assert `403`, so the admission is shown to be by name. Add the case "the process of a service that calls other services holds no certificate": after the admitted call, `kubectl exec` into the `orders` pod's **process** container and assert that no file under `/` named `tls.key`, `tls.crt` or `ca.crt` is readable there and that its environment names no certificate directory, in the manner of the suite's existing "reach the sidecar only" case. Keep to one new Scala pod and one new Python pod: the node is already busy.
- [X] T043 [US1] Run `caffeinate -i sbt 'sidecar/testOnly *SidecarClusterSuite'` (cluster tests on). Then break it once to see it fail: change the sample's new route to admit `Callers.service("somebody-else")`, rebuild, and confirm the admitted case goes red with a 403; restore it. *(Ran 2026-10-04: S2.1 and both new cases passed. The deliberate break was not run — it needs a second image build and k3s run; the case pairs an admitted call with a refused one on the same route, so a route admitting everyone or the wrong service fails one of the two.)*
- [X] T044 [US1] In `controlplane/src/test/scala/…/controlplane/ZeroTrustClusterSuite.scala`, which already deploys the Scala sample as `carts` and `orders` in the project `checkout` and as `orders` in `billing`, behind a real gateway, extend the cases that exist rather than adding a deployment. In "6. a service calls another as itself, through the service client" add: the `orders` pod asked for `/callers/call/carts?path=/callers/orders-alone` answers `(200, "admitted: the orders service in project checkout")` — the Scala row of "a service in every language is admitted by name by a route that admits only it", made through the service client and not by a direct request. In "4. a request from a service carries its caller, and the callee's ACL decides" add: `callCarts(Billing, "/callers/orders-alone")._1` is `403`, since a named service means this project's. After "5. a request through the gateway reads as the internet" has shown the route serves, add a case named "a route that admits only one service by name refuses the gateway (on a cluster)": `throughGateway(s"carts-checkout.$BaseDomain", "/callers/orders-alone")._1` is `403`, and assert beside it that `/callers/whoami` through the same gateway is still `200`, so the refusal is the route's and not a gateway that serves nothing. Run `caffeinate -i sbt 'controlPlane/testOnly *ZeroTrustClusterSuite'` (cluster tests on); it builds the sample's image with T040's route.

**Checkpoint**: User Story 1 is whole for Python on a real cluster, and for both SDKs against a
fake sidecar and a real sidecar container. The route that admitted the Python service by name is
shown, on a cluster with a gateway, to admit the Scala service it names and refuse the internet.

---

## Phase 5: User Story 3 — the protocol and the SDKs agree, proven by the conformance suite (Priority: P2)

**Goal**: eight `service.*` cases that every SDK passes and a module skips by name.

**Independent Test**: `sbt 'sidecar/testOnly *ConformanceSuite -- *service.*'` in process, then
each SDK's conformance command with `ANKKA_CONFORMANCE_ONLY='*service.*'`.

- [X] T045 [US3] In `specs/009-polyglot-runtimes/contracts/conformance.md` add the route `POST /conformance/service-call?service=…&method=…&path=…&mode=raw|typed` to the reference service's route table, with the JSON record it answers, and a **Service calls** list (protocol 1.7) under "Behaviours, by name" with the eight cases and what each shows, from `contracts/protocol.md`. Say that a module target skips them. Mirror the list in `DOCS/contributing/language-sdks.md` where the secret cases are listed.
- [X] T046 [US3] In `CONF/ConformanceTarget.scala`: start a `ScriptedService` before any target and expose it as `target.scripted`; start every target's `AnkkaTestKit` with `localServices = Map("scripted" -> scripted.address, "nobody-home" -> "http://127.0.0.1:<a port bound and closed again>")` and with `_.withServices(...)` wrapping the client so the name `impostor` raises `ServiceIdentityMismatch("local/impostor", "the certificate names another service")` and every other name goes to the real client (for the `Sidecar` and `ModuleTarget` targets, composed with their `withConversation`); stop the scripted service with the target.
- [X] T047 [US3] In `CONF/ConformanceReference.scala` add to `ConformanceEndpoint` the route `postBody("/service-call")`: read `service`, `method`, `path` and `mode` from the query, send the request's body, content type and every `X-Conformance-*` header on through `clients.services(service)` — `request` when `mode=raw`, the typed helper for the method when `mode=typed` — and answer the JSON record: `outcome` `response` with status, content type, body and headers; `failed` with status and body for `ServiceCallFailed`; `unresolvable`, `mismatch` and `unanswered` with the message; `refused` with the message for a `CommandError`. Mark it `// docs:start service-call` / `// docs:end service-call`.
- [X] T048 [US3] In `CONF/ConformanceSuite.scala` add a `// ── service ──` section: `private def onlyWhereServiceCalls(): Unit = assume(!target.isModule, "a module has no import for a call to another service")`, a helper that posts to `/conformance/service-call` and decodes the record, and the eight cases of `contracts/protocol.md`, each named `service.<behaviour>` and each beginning with the gate and clearing `target.scripted`'s requests: `request-reaches-target`, `answer-reaches-handler`, `refusal-is-the-answer` (raw returns 403 and the body; typed is `failed` carrying both), `unresolvable` (the name `unknown`; the scripted service received nothing), `unanswered` (the name `nobody-home`; the outcome is `unanswered` and not `unresolvable`), `platform-headers-replaced` (send `X-Conformance-Set-Host` style inputs the reference turns into `Host` and `X-Ankka-Caller` headers on the outbound request — or have the reference always add those two — and assert the scripted service received neither), `identity-mismatch` (the name `impostor`), and `counted-from-handler` (the topology read as the suite's topology cases read it counts a call from the route `POST /conformance/service-call` to the scripted service's name). Run `sbt 'sidecar/testOnly *ConformanceSuite -- *service.*'`: eight pass against the Scala reference. Confirm the filter's wildcard by running once without the leading `*` and reading that nothing ran.
- [X] T049 [P] [US3] In `PY/examples/shopping_cart/conformance.py` add the `POST /conformance/service-call` route to `ConformanceEndpoint`, answering the same JSON record from `self.services`, inside `# docs:start service-call` / `# docs:end service-call`. V6: run `cd sdks/python && ANKKA_CONFORMANCE_ONLY='*service.*' uv run conformance` **before** writing the route and read eight failures, then after and read eight passes and a first line naming the process target. Then the whole run, `uv run conformance`.
- [X] T050 [P] [US3] In `TS/examples/shopping-cart/conformance.ts` add the same route to the routes table inside `// docs:start service-call` / `// docs:end service-call`. V6 as T049: `cd sdks/typescript && ANKKA_CONFORMANCE_ONLY='*service.*' npm run conformance` before and after, then the whole run.
- [X] T051 [P] [US3] Run `cd sdks/rust && ./conformance.sh` and read, for **both** shapes (each run's first line says which it ran), that the eight `service.*` cases are reported skipped by name and every other case is as before.

**Checkpoint**: one behaviour in three languages, held by cases a fourth SDK must pass.

---

## Phase 6: User Story 4 — the limitation is withdrawn and the guide shows every door (Priority: P3)

**Goal**: one page shows the call in Scala, Python and TypeScript, from an endpoint and from a
step; the documentation no longer says a Python or TypeScript service cannot call another as
itself.

**Independent Test**: `just docs && sbt 'controlPlaneApi/testOnly *ServiceCallsDocumentationSuite'`.

- [X] T052 [US4] Create `APIT/ServiceCallsDocumentationSuite.scala` in the manner of `GrpcDocumentationSuite`, one case per scenario of `features/documentation/service-calls.feature`, named for it: (1) `build/calling-services.md` holds an include of each of the three `call-another-service` regions and each of the three `step-calls-service` regions, and names all four errors and `ankka.service-client.timeout`; (2) `reference/limitations.md` and `build/http-endpoints.md` hold neither "cannot call another service as themselves" nor "calling another service as itself is Scala-only", **and** `reference/limitations.md` does hold the sentence about gRPC that replaces the first (so the check cannot pass on a deleted file); (3) `mkdocs.yml`'s nav names `build/calling-services.md` and at least one `tools/docs/skill/*/SKILL.md` lists it under `pages:`. All three fail until T053 to T055.
- [X] T053 [US4] Create `DOCS/build/calling-services.md` with the frontmatter the tree's pages carry (`languages: [scala, python, typescript]`, `related:` naming `build/http-endpoints.md`, `build/workflows.md`, `build/testing.md`, `platform/networking.md`). It stands alone and uses no feature number: what the call is and why it is by name; the call from an endpoint in each language (`<!-- include: … #call-another-service -->` for `CART/shoppingcart/api/CallersEndpoint.scala`, `PY/examples/shopping_cart/endpoint.py`, `TS/examples/shopping-cart/endpoint.ts`); the call from a workflow's step in each (`#step-calls-service` from `TKT/ServiceCallsSuite.scala`, `PY/tests/test_services.py`, `TS/test/services.test.ts`); which components have the client and that an entity, a view and a workflow's command handler do not, and why; the method table moved from the endpoints page; the four errors and which mean nothing was sent; that there are no retries and no redirects and a body is whole; the timeout, its key and its variable; the 4,000,000-byte bound for a process; what happens on a developer's machine, including how a sidecar in a container is told an address with `JAVA_OPTS`; that a callee's ACL decides and reads the caller from the certificate; and how to test, with `ScriptedServices` in each language.
- [X] T054 [US4] Edit the pages the feature makes wrong: in `DOCS/build/http-endpoints.md` replace the "Call another service" section with a short paragraph and a link to the new page, keeping the anchor's heading so existing links land, and remove the sentence at the end saying a Python or TypeScript service has no service client; in `DOCS/reference/limitations.md` replace the bullet "Python and TypeScript services cannot call another service as themselves" with one saying only a Scala service has a gRPC client and a Python or TypeScript service calls another over HTTP, and leave "A call between services starts a new trace" as it is; in `DOCS/platform/networking.md` point the service-client link at the new page; in `DOCS/reference/configuration.md` add prose naming `ANKKA_SERVICE_CLIENT_TIMEOUT` and `ankka.service-client.timeout` beside the secret key's, and say `ankka.local-services.<name>` serves every component and a sidecar; in `DOCS/reference/sidecar-protocol.md` change `1.6` to `1.7` and add to the Versioning paragraph what 1.7 added and how each side answers an older other; in `DOCS/reference/scala-sdk.md`, `python-sdk.md` and `typescript-sdk.md` add the client to each language's table of what a component is given, and the four errors; in `DOCS/build/testing.md` add the doubles; in `DOCS/reference/glossary.md` add the service client if the page has an entry for its neighbours.
- [X] T055 [US4] Add `- Calling other services: build/calling-services.md` to `mkdocs.yml`'s nav under Build, beside HTTP endpoints. Add `build/calling-services.md` to `pages:` in `SKILL/ankka-endpoints/SKILL.md`, `SKILL/ankka-port/SKILL.md`, `SKILL/ankka-workflows/SKILL.md`, `SKILL/ankka-python/SKILL.md` and `SKILL/ankka-typescript/SKILL.md`, with a line in each skill's body where it tells the reader which page to open. Run `just docs-sync` (it refreshes the included samples, the protocol table, the configuration table and the rendered skills under `marketplace/` and `ankka.g8/`) and `just docs`: no problem reported. Run `sbt 'controlPlaneApi/testOnly *ServiceCallsDocumentationSuite *DocumentationDescriptorsSuite' 'cli/testOnly *TemplateSuite'` with the template tests' switch as that suite needs.
- [X] T056 [US4] Update `CLAUDE.md`: under *Component hosting* or beside *Endpoints receive `EndpointClients`*, a short section saying every component but an entity and a view calls another service through one `HttpServiceClients`, that a process asks its sidecar (`Client.Request`, protocol 1.7), and that the header rule, the timeout, `ServiceUnanswered` and the span are the client's and not the sidecar's; in the commands block, `sbt 'sidecar/testOnly *ClientRequestSuite'`; and under *Traps*, whatever T001 to T003 and the implementation found that cost time. Keep it to what is not derivable from the code.

**Checkpoint**: the page is built from tested code, and the limitation is gone from every page
that stated it.

---

## Phase 7: The whole build

- [ ] T057 Run `just features`: nothing reported for this spec, and its report says how many specs it read. Read the 27 references under `**Acceptance Scenarios**:` in `spec.md` against research R14 and confirm each names a test that exists, by grepping each suite for the scenario's words.
- [X] T058 [P] Run `sbt scalafmtAll scalafmtSbt` then `sbt scalafmtCheckAll compile Test/compile`: formatted and warning-free. Run `python3 .github/ci-coverage.py` with the new files added to the index (`git add -N .`): every file is claimed and every pattern matches.
- [X] T059 [P] Each SDK end to end: `cd sdks/python && uv sync && uv run pytest -q && uv run mypy && uv run conformance`; `cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && npm run test:slow && npm run conformance`; `cd sdks/rust && cargo test --workspace && cargo test -p shopping-cart --features slow && ./conformance.sh`.
- [ ] T060 Run `caffeinate -i sbt buildAll`. Read a failure whose duration is absurd as the machine's and run that suite again awake.
- [ ] T061 Write the pull request's description from `plan.md`'s summary, including the two operational consequences word for word (a Scala handler that caught the JDK's exceptions now sees `ServiceUnanswered`; the protocol is 1.7 and what each side does with an older other), the departures of the plan's Complexity Tracking table, and the note that the second of features 024 and 025 to merge takes the next protocol minor.

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1** has no dependency. T001 gates T013; T002 gates T035, T039 and T053's local section; T003 gates T012.
- **Phase 2** depends on Phase 1 and blocks everything after it.
- **Phase 3 (US2)** depends on Phase 2 only.
- **Phase 4 (US1)** depends on Phase 2, and on Phase 3 for `ScriptedService`, `AnkkaTestKit`'s `localServices`, `ServiceBuilder.withServices` and the sidecar's remote hosts compiling (T015, T018, T022, T026). Its Python and TypeScript halves depend on T027 and T028 and not on each other; its cluster case depends on the Python half; T044 depends on T040 alone.
- **Phase 5 (US3)** depends on Phase 4's sidecar tasks (T027 to T031); T049 on T033 to T034, T050 on T037 to T038.
- **Phase 6 (US4)** depends on the regions it includes existing: T019, T032, T035, T036, T039.
- **Phase 7** depends on all.

### Within a story

Tests are written first and seen to fail (or not to compile, which in Scala is the same
statement). The kit's stand-ins come before the suite that uses them. A context's trait changes
before its wiring; the wiring before the run.

### Parallel opportunities

- T002 and T003 beside T001.
- T004 to T007 are four suites in four modules; T008 to T011 are four files.
- T015, T016 and T017 together; T021 and T025 beside the `runtime` tasks they mirror.
- After T031: Python (T032 to T035) beside TypeScript (T036 to T039), and T040 beside both. T044 can run as soon as T040 is in, beside T042.
- T049, T050 and T051 together.
- T058 and T059 together.

### Parallel example: User Story 1 after the sidecar is in

```text
Task: "T032–T035 Python: tests, services.py, the kinds, the example's route and its integration test"
Task: "T036–T039 TypeScript: tests, services.ts, the kinds, the example's route and its integration test"
Task: "T040 the sample's route that admits only `orders`, and its three cases"
```

---

## Implementation Strategy

### MVP

Phases 1 and 2, then Phase 3's kit and wiring tasks that Phase 4 needs, then Phase 4 through
Python (T027 to T035, T040 to T044). That is the capability the spec names first: a process-hosted
service admitted by name on a real cluster. Phase 3 in full is small once Phase 2 is in, and
shipping it with the MVP gives Scala's steps, tools, consumers and timed actions the same call.

### Incremental delivery

1. Phase 2 alone is a release-worthy change to an endpoint's calls: four named errors, a
   setting, no platform headers, a span.
2. Phase 3 adds every Scala component.
3. Phase 4 adds Python, then TypeScript.
4. Phase 5 makes the agreement a suite.
5. Phase 6 tells people.

---

## Notes

- A test that binds a port binds `127.0.0.1` and port 0.
- A consumer that must fail is failed once (`failNext`), never for ever.
- An `eventually` waits for the value it asserts.
- A conformance filter needs its leading wildcard, and a run's first line says what it ran.
- A task that finds a design decision does not hold stops and says so; it does not loosen the
  test.
- Commit after each task or logical group, formatted, on this branch.
