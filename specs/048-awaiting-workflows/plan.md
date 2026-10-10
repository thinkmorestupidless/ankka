# Implementation Plan: Awaiting Workflows — A Caller Is Answered When the Workflow Ends

**Branch**: `048-awaiting-workflows` | **Date**: 2026-10-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/048-awaiting-workflows/spec.md`, clarified 2026-10-10
(six decisions in its Clarifications section; the last two were taken during this plan).

## Summary

A caller waits for a workflow's end with a timeout of its own and is answered with the end: the
final state, or `ErrorCode.WorkflowFailed` carrying the step and the reason as fields. The engine
holds the waiters of one workflow in memory beside its `Run` and answers them in the same
`thenRun` that follows the journalling of `Ended`, `Failed` or `Deleted`; a waiter it has held for
half an ask timeout is told `NotYet`, and the client asks again until its own deadline, which is
what carries a wait across a shard move or a stopped instance with nothing journalled. The await is
a reserved method, `ankka:await-end`, on the ordinary `Invoke`, so an instance from before this
release answers it "no handler" and the client says so. The Scala client gains
`forWorkflow(id).awaitEnd(Companion, timeout)` and `call(start).thenAwaitEnd(timeout).invoke(in)`;
`ankka-http` gains the stream form, a `Source[SseEvent]` of heartbeats and the end that an SSE route
returns as it is; the sidecar's `Client` gains `AwaitEnd` and `AwaitEndStream` at protocol **1.15**,
with `Error.details` for the step and the reason, and the Python and TypeScript clients and the Rust
crate's `await_end` import follow. `features/awaiting-workflows/` runs whole against the Scala test
kit, the Python and TypeScript rows through their kits, two conformance cases pin every reference,
and the shopping cart's checkout suite is rewritten over the await.

## Technical Context

**Language/Version**: Scala 3 on Apache Pekko (`core`, `sdk`, `runtime`, `http`, `grpc`,
`testkit`, `sidecar`); Python 3 (`sdks/python`, uv); TypeScript under Node type stripping
(`sdks/typescript`); Rust for `wasm32-unknown-unknown` (`sdks/rust`). No new library anywhere.

**Primary Dependencies**: Pekko cluster sharding and persistence (the engine and the transport),
pekko-http (the SSE route and its idle timeout), grpc-java and ScalaPB (the sidecar's `Client`),
Chicory (the module's import). `sdk` has no Pekko dependency, which is why the stream form lives in
`http` (research R6).

**Storage**: the journal gains nothing new in kind. `Event.Failed` carries the failing step in the
`step` field `WorkflowRecord` already has, and `WorkflowSnapshot` gains `failedStep` and `deleted`
(R3, R4). No DDL, no table. Nothing about a wait is journalled (FR-006).

**Testing**: munit everywhere; `GherkinSuite` over `features/awaiting-workflows/` from `testkit`'s
tests (one `AwaitSteps` abstract suite, one final class per feature file); `startPeer` for the
instances feature; pytest and the Node runner for the Python and TypeScript rows through their
integration kits against `ANKKA_SIDECAR_IMAGE`; `ConformanceSuite` gains `wf.start-and-await` and
`wf.await-failed`; `CheckoutWorkflowSuite` rewritten; an `AwaitingWorkflowsDocumentationSuite` for
`features/documentation/awaiting-workflows.feature`.

**Target Platform**: the JVM runtime and the sidecar image, locally and on Kubernetes; the
gateway's fifteen-second HTTP route bound is documented, not changed.

**Project Type**: platform: runtime, SDKs in four languages, protocol, docs and skills.

**Performance Goals**: SC-002, a waiter answered within one second of the end being recorded on an
unloaded service; FR-005, answered where the end is journalled, no polling interval anywhere.

**Constraints**: a command is sent once and a query is resent within one ask; the await is neither
and has its own loop in `ShardingTransport` (R2). The hold per ask is half `ankka.ask-timeout`,
so no attempt times out at the transport while the wait is healthy. The heartbeat is a third of
`pekko.http.server.idle-timeout`, never set by the caller. No new configuration key (the spec:
"no interval is configured"). A behaviour an older runtime must refuse is a new rpc, not a new
field (`sidecar.md`); the protocol's new `Error.details` only enriches what the new rpcs return.
Every exhaustive `match` over `ErrorCode` gains a case (R3 lists them). No test binds a fixed port.

**Scale/Scope**: one reserved method, two `Reply` cases, one error code, two rpcs, one import, one
`EndpointClients` method, two client methods per language, five feature files already written,
about ten documentation pages and six skills.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template: no principles are declared, so no gate
derives from it. The repository's standing rules (`CLAUDE.md`, `.claude/rules/runtime.md`,
`sidecar.md`, `wasm.md`, `observability.md`, `docs.md`, `testing.md`) are the gates:

| Gate | Pre-research | Post-design |
|---|---|---|
| Effects are inert; the runtime interprets. No handler changes when it replies | pass: the await is the caller's, not an effect; `thenReply` is untouched | pass |
| Wire names are declared separately and never derived | pass: `ankka:await-end` is a reserved name beside `ankka:lifecycle`, refused to user handlers by `Companion.descriptor` | pass |
| `runtime` does not see `http` or `agent`; `sdk` carries no Pekko | n/a | pass: the loop is in `ShardingTransport` behind `CallTransport.awaitEnd`; the `Source` form is `EndpointClients.awaitEnd` in `http` (R6) |
| A behaviour an older runtime must refuse is a new call, not a new field | n/a | pass: `AwaitEnd` and `AwaitEndStream` are rpcs; an older runtime answers `UNIMPLEMENTED`, each SDK names 1.15 (R8) |
| A new Rust import is not in the shared dispatch `match` | n/a | pass: `await_end` has its own link block and `call_await` (R9) |
| Work handed to another thread cannot see a thread-local | n/a | pass: the caller's origin and trace are captured in `Call` on the asking thread, as every transport call does; the engine answers waiters from the actor |
| A span begun and held open is lost | n/a | pass: the caller's `Client` span is `reserve`d and `record`ed when the wait ends (R5) |
| A refusal is not a failure, and the recorder is told which | n/a | pass: a `WorkflowFailed` answer is `Refused` in the recorder, never `Failed`, since the call did what it should; the engine's handler span is untouched (R3, R5) |
| Every acceptance scenario ends as a test that fails without the feature | n/a | pass: every scenario of the five feature files is bound (quickstart lists the suite per file); the two Python and TypeScript rows run in the SDK jobs and are `ranElsewhere` in the Scala suite |
| A test switch is forwarded to the forked JVM | n/a | pass: no new switch; the idle timeout a serving test needs is passed through `AnkkaTestKit.start(settings = …)` |
| Could this check pass while the thing it checks is false? | | the "not polled" scenario asserts the engine's lifecycle-query count stayed at zero while a waiter was held; the "one call" scenario reads the observed call from the topology, not from the client; the instances scenario asserts the answer *and* that the step ran on the other instance |

## Project Structure

### Documentation (this feature)

```text
specs/048-awaiting-workflows/
├── plan.md              # this file
├── research.md          # R1–R13: the decisions and what was rejected
├── data-model.md        # Waiter, End, the Run and snapshot changes, the error's fields
├── quickstart.md        # how to prove each feature file green, and what red looks like
├── contracts/
│   ├── runtime-wire.md  # the reserved method, NotYet, WorkflowFailed, the client loop
│   ├── scala-sdk.md     # awaitEnd, thenAwaitEnd, EndpointClients.awaitEnd, the SSE events
│   ├── protocol.md      # client.proto: AwaitEnd, AwaitEndStream, Error.details, 1.15
│   └── polyglot-sdks.md # Python, TypeScript and Rust surfaces and errors
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/
└── CommandError.scala                 # ErrorCode.WorkflowFailed; CommandError.details; WorkflowEnd

modules/sdk/src/main/scala/com/thinkmorestupidless/ankka/sdk/
├── ComponentClient.scala              # CallTransport.awaitEnd; WorkflowCalls.awaitEnd/awaitEndAsync;
│                                      # WorkflowInvocation with thenAwaitEnd
├── CommandHandle.scala                # a workflow's handle carries its state serializer
├── Workflow.scala                     # Companion.command passes the state serializer
└── WorkflowLifecycle.scala            # the reserved method name AwaitEnd beside Method

modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/
├── wire.scala                         # Reply: NotYet, WorkflowFailed; Rejected unchanged
├── WorkflowHost.scala                 # Run.failedStep, Run.deleted; Event.Failed(message, step);
│                                      # WorkflowSnapshot two fields; PostStop signal
├── WorkflowEngine.scala               # waiters, onAwaitEnd, answerWaiters at every end
├── ShardingTransport.scala            # awaitEnd loop; NotFound on the reserved method → "before awaiting"
└── Observability.scala                # outcomeOf(WorkflowFailed)

modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/
├── HttpEndpoint.scala                 # HttpProblem.from: WorkflowFailed → 424, details in the body
├── EndpointClients.scala              # awaitEnd(workflowId, Companion, timeout): Source[SseEvent, NotUsed]
├── HttpServer.scala                   # the heartbeat interval from the server's idle timeout
└── SseEvent.scala                     # heartbeat, ended, failed, timedOut

modules/grpc/src/main/scala/com/thinkmorestupidless/ankka/grpc/GrpcStatus.scala   # WorkflowFailed → ABORTED

modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit/TestTransport.scala  # awaitEnd stub
modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/
├── awaiting/QuoteWorkflow.scala       # rates, margin, offer; scripted durations and failures per id
├── awaiting/AwaitSteps.scala          # abstract GherkinSuite with LogCapturing
├── awaiting/{Awaiting,Composition,Serving,Instances,Languages}Features.scala
├── awaiting/QuoteEndpoint.scala       # the SSE route and the whole-answer route (docs:start markers)
└── AwaitingWorkflowsDocumentationSuite.scala

protocol/src/main/protobuf/ankka/protocol/v1/
├── client.proto                       # AwaitEnd, AwaitEndStream, AwaitEndRequest
├── payload.proto                      # ErrorCode.WORKFLOW_FAILED = 8; Error.details
└── (copies) sdks/python/proto, sdks/typescript/proto, sdks/rust/ankka/protocol
protocol/README.md, protocol/WASM-ABI.md                        # 1.15; the await_end import

sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/
├── ClientService.scala                # the two rpcs
├── ClientLogic.scala                  # awaitEnd, awaitEndStream (heartbeats from the same interval)
├── Translate.scala                    # WORKFLOW_FAILED both ways; details both ways
├── wasm/HostImports.scala             # await_end
└── wasm/ModuleLoader.scala            # Imports += await_end
sidecar/src/test/.../conformance/{ConformanceSuite,ConformanceReference}.scala
specs/009-polyglot-runtimes/contracts/conformance.md            # the two behaviours, the route

sdks/python/src/ankka/client.py        # Calls.await_end, await_end_stream; Invocation.then_await_end
sdks/python/src/ankka/effects/common.py # ErrorCode.WORKFLOW_FAILED; Error.details
sdks/python/src/ankka/service.py       # PROTOCOL_VERSION 1.15
sdks/typescript/src/{client.ts,effects/common.ts,kinds.ts,spec.ts}
sdks/rust/ankka/src/{client.rs,effects/common.rs,abi/imports.rs,service.rs}
sdks/*/examples/shopping-cart|shopping_cart/conformance.*       # the await routes

controlplane-api/src/main/scala/.../Compatibility.scala         # ProtocolVersion(1, 15)

samples/shopping-cart/src/test/scala/shoppingcart/CheckoutWorkflowSuite.scala  # over awaitEnd

docs/build/{workflows,component-client,streaming,http-endpoints}.md
docs/reference/{error-codes,akka-divergences,limitations,sidecar-protocol,wasm-abi,
                scala-sdk,python-sdk,typescript-sdk,rust-sdk,glossary}.md
tools/docs/skill/{ankka-workflows,ankka-endpoints,ankka-python,ankka-typescript,ankka-rust,ankka}/SKILL.md
```

**Structure Decision**: no new module and no new directory. The engine, the transport and the
client each gain one concern in the file that owns the neighbouring one (`lifecycle` is the model
for a runtime-answered reserved method; `askQuery` for a transport loop; `sse` for a long stream).
The tests for the five feature files live in `testkit`'s tests under one package, as sockets',
timers' and topology's do.

## Complexity Tracking

No gate is violated. Two choices look like extra machinery and are not:

| Choice | Why needed | Simpler alternative rejected because |
|---|---|---|
| A second rpc for the stream form (`AwaitEndStream`) rather than tokens on `AwaitEnd` | a unary rpc cannot carry heartbeats, and a process's SSE route must outlast the sidecar's idle timeout as a Scala one does (clarification 6) | one rpc with the SDK polling `AwaitEnd` in a loop would reintroduce the interval the spec forbids and count a call per tick |
| Two new `Reply` cases instead of fields on `Rejected` | an instance from before this release may receive a reply during a rolling update; a new case is never sent to it, a changed `Rejected` would be | relying on Jackson ignoring unknown fields is a bet on a serializer setting nothing here pins |
