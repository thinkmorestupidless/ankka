# Research: Awaiting Workflows

Every unknown the technical context raised, resolved against the code as it is on
`048-awaiting-workflows` (main at c1cc0f78). File references are to that tree.

## R1. How the await reaches the engine: a reserved method on `Invoke`

**Decision**: the await is `Invoke(method = "ankka:await-end", payload = <hold millis as text>,
metadata, replyTo)`, handled by `WorkflowEngine.onInvoke` beside `WorkflowLifecycle.Method`
(`WorkflowEngine.scala:106`). The name is `WorkflowLifecycle.AwaitEnd`, `private[ankka]`, under
the `ankka:` prefix `Companion.descriptor` already refuses to user handlers (`Workflow.scala:263`).

**Rationale**: the sharding transport needs one key type, `EntityProtocol.Command`, and an
`Invoke` already carries metadata (the caller, the trace) and a typed `replyTo`. An instance from
before this release answers an unknown method `Rejected(NotFound, "… has no handler …")`
(`WorkflowEngine.scala:108-119`), which is exactly the spec's rolling-update edge case; the
transport maps that reply, on this method only, to a `CommandError` saying the instance is from
before awaiting (R2). The engine's unknown-command path for a `ModuleCommand` (`:78`) is not
touched.

**Alternatives considered**: a new `EntityProtocol.Command` case (`AwaitEnd(replyTo)`): no
metadata, no caller, a non-exhaustive match in every other host's `onCommand`, and an old instance
would log and drop it rather than answer. A new sharded message kind: the same, with a serializer
binding.

## R2. The "not yet" reply and the client's loop

**Decision**: two new `Reply` cases in `wire.scala`, both `AnkkaSerializable` under the existing
`jackson-cbor` binding: `NotYet(heldMillis: Long)` and `WorkflowFailed(step: Option[String],
reason: String, deleted: Boolean)`. `Rejected` and `Succeeded` are unchanged. The engine holds a
waiter for `min(hold, askTimeout / 2)` where `hold` is what the payload asks (the client sends its
remaining time), then answers `NotYet`. `CallTransport` gains

```scala
def awaitEnd(componentId: ComponentId, entityId: EntityId, timeout: FiniteDuration, metadata: Metadata): Future[Array[Byte]]
```

and `ShardingTransport.awaitEnd` loops: each attempt is `send(Invoke(AwaitEnd, remaining, …))`
under `Timeout(askTimeout)`; `Succeeded` ends the loop with the bytes; `WorkflowFailed` ends it with
`CommandError(message, ErrorCode.WorkflowFailed, details)`; `NotYet` and an attempt's
`TimeoutException` (the actor stopped, the shard moved, a buffered message dropped at hand-off)
loop again while the caller's deadline has not passed; at the deadline the loop fails with
`ErrorCode.Timeout` and counts `unanswered(TimedOut)` once. A `Rejected(NotFound)` whose message is
the engine's "no handler" on this method becomes `CommandError("… is from before awaiting; both
halves must be at this release", Unavailable)`; any other `Rejected` passes through.

**Rationale**: `askQuery` (`ShardingTransport.scala:82-98`) is the model: a loop inside the
transport, the call counted once by how it ends (`:79-80`). The hold is half the ask timeout so
the engine always answers before the transport gives up on a healthy wait (the spec's "a timed-out
wait and the ask" edge case). Nothing about a wait is journalled, and a waiter lost with its actor
costs the caller at most one ask: the engine answers every held waiter `NotYet` from `PostStop`
(R4), so the usual cost is nothing.

**Alternatives considered**: encoding "not yet" as `Rejected(Unavailable)`: `answered` would turn
it into a `CommandError`, and the sidecar's `askWithRetry` retries `Unavailable` three times then
gives up. Holding the ask open for the whole wait with a long `Timeout`: a shard move loses the
waiter for the whole caller's timeout, and a ten-minute ask holds a Pekko ask actor for ten
minutes. Pekko's jackson `FAIL_ON_UNKNOWN_PROPERTIES` default was not relied on: a new case class
is never sent to an instance that does not know it, since only a new instance sends an await.

## R3. The failure's shape: `WorkflowFailed`, `details`, the step in the journal

**Decision**:

- `ErrorCode.WorkflowFailed`, the ninth code. `retryable` stays `Unavailable | Timeout`.
- `CommandError(message: String, code: ErrorCode = BadRequest, details: Map[String, String] =
  Map.empty)`. No code destructures `CommandError` with two fields (zero `case CommandError(`
  matches outside `core`'s constructors), so every construction compiles unchanged. A failed
  workflow's error has `details("reason")` and, when a step failed, `details("step")`; a deleted
  one has `details("deleted") = "true"` and the reason `"the workflow was deleted"`. `core` gains
  `WorkflowEnd.Failure(step: Option[String], reason: String, deleted: Boolean)` with
  `WorkflowEnd.failure(error): Option[Failure]`, so a Scala caller reads fields, not a map.
- `Event.Failed(message, step: Option[String])` journalled in `WorkflowRecord.step`, which the
  record already has (`wire.scala:209-216`); an older runtime reading the record ignores the step
  (`WorkflowHost.scala:190`, `Event.Failed(record.message)`), a newer one reading an older record
  reads an empty step as `None`. `Run` gains `failedStep: Option[String]` and `deleted: Boolean`;
  `WorkflowSnapshot` gains `failedStep: String` and `deleted: Boolean` (a missing primitive
  `Boolean` reads as `false`, a missing `String` as `null`, which the adapter already filters).
  `applyEvent(Deleted)` sets `deleted = true` on the empty run; `StateUpdated` and `TransitionedTo`
  clear it.
- The lifecycle query is unchanged (`failure` is still the one string), as the spec requires.
- Where each end is journalled, the step and the reason are: retries spent (`:404`) step and the
  thrown message; `StepOutcome.Fail` (`:355`) the pending step and `error.message`; workflow
  timeout (`:416`) the pending step and the timeout text; pause timeout (`:430`) and the missing
  step (`:256`) likewise.
- HTTP: `HttpProblem.from` maps `WorkflowFailed` to **424 Failed Dependency** and the problem body
  carries `details`. gRPC: `GrpcStatus` maps it to `ABORTED`, which no other code uses, so
  `fromCode` stays a bijection (`GrpcStatusSuite` asserts `codes == ErrorCode.values`).
  `Observability.outcomeOf` leaves it in the `Refused` bucket: the call was answered, and the
  recorder's `Failed` means the handler broke.
- Protocol: `ErrorCode.WORKFLOW_FAILED = 8` and `map<string, string> details = 3` on `Error`
  (`payload.proto`); `Translate.toCode`/`fromCode` and each SDK's enum and error type gain both
  (`effects/common.py:26,53`, `kinds.ts:36`, `effects/common.ts:41`, `effects/common.rs:27,79`).

**Rationale**: clarification 2 asked for fields a caller in any language reads without parsing
text. A map on the error is one protocol field and one Scala field, and it is what the HTTP
problem body and the SDK error types can all carry; the typed `WorkflowEnd.Failure` is the Scala
convenience over it. 424 is told apart from every refusal the table maps (400–409) and from 500,
503 and 504, which is what FR-004 asks of the status.

**Alternatives considered**: typed fields on `CommandError` (`failedStep`, `failureReason`): two
fields for one code, nothing for the next. A `WorkflowFailure extends CommandError`: `CommandError`
is a final case class, and a subclass does not cross the wire. 409 for HTTP: it is `Conflict`'s,
and a caller could not tell. Changing the lifecycle query to report `Deleted`: the spec excludes it.

## R4. Answering where the end is journalled

**Decision**: `WorkflowEngine` holds `private var waiters: Vector[Waiter]`, with
`Waiter(replyTo: ActorRef[Reply], key: Long, askedAtNanos: Long, metadata: Metadata)`. `onAwaitEnd`:
if `state.isTerminal` or `state.deleted`, answer now; else append a waiter and start
`timers.startSingleTimer(WaiterKey(key), WaiterHeld(key), hold)`. `WaiterHeld` answers that waiter
`NotYet` and removes it. `answerWaiters(run)` is called in the `thenRun` after each persist of
`Ended` or `Failed` (`onStepSucceeded :351,:356`; `onStepFailure :405`; `onWorkflowTimedOut :419`;
the pause-timeout and missing-step fails at `:430` and `:255`, which today also skip
`cancelLifecycleTimers` and will call it) and after `Deleted` in `onInvoke`'s persist (`:195`).
Each answered waiter is counted `observability.handled(metadata, component, AwaitEnd, Ok, held)`
once. A `PostStop` signal on the behavior answers every held waiter `NotYet`, so a passivated or
moved workflow's callers re-ask at once rather than after an ask timeout; the engine logs nothing
for it, and `instances.feature`'s "no line saying a waiting caller was lost" is asserted on the
captured log. The recovery path (`onRecovered`) has no waiters to restore and does nothing new.

**Rationale**: the spec's "answered when it ends, not at the next tick" is the `thenRun` after the
persist: the same place `cancelLifecycleTimers` runs today. The remote hosts answer their queue
from `PostStop` for the same reason (`runtime.md`, "A Pekko stash is dropped when the actor
stops"). A waiter is in-memory state beside `stepTrace`, the one such field the engine has.

**Alternatives considered**: a stash: dropped on stop with no answer. Persisting waiters: the spec
forbids it and a `replyTo` does not survive the instance anyway. A cluster-wide registry of
waiters: a second place to be wrong about where a workflow is.

## R5. One call in the trace and the topology

**Decision**: the caller's side opens one `Client` span through `Recorder.reserve` when
`awaitEnd` starts and `record`s it when the loop ends, so a span held for minutes is written whole,
as a socket's is (`runtime.md`, "A span begun and held open is lost"). The outcome is `Ok` for a
state or a `WorkflowFailed`, `TimedOut` at the deadline; `unanswered(origin, callee, AwaitEnd,
TimedOut)` is counted once at the deadline and never per attempt. The callee's side counts
`handled(…, Ok)` once per waiter it answers with the end (R4), never for `NotYet`. The topology
therefore shows one observed call from the caller to the workflow, handled as ok (the scenario
"a wait is one call in the topology"), and a timed-out wait as one unanswered. Each attempt carries
the same `ankka-caller` and `traceparent`, captured once in `Call` on the asking thread.

**Rationale**: `.claude/rules/observability.md`: handled is the callee's, unanswered the caller's,
counted once by how the call ends; the attempt loop is the transport's business, as `askQuery`'s
resends are.

## R6. The Scala API, and why the stream form is in `http`

**Decision**:

- `Workflow.Companion.command(...)` passes `stateSerializer` into the handle it builds:
  `CommandHandle` and `NoArgHandle` gain `private[ankka] val stateSerializer: Option[Serializer[?]]`
  (`None` for every other component). `WorkflowCalls.call` returns `WorkflowInvocation[W, I, O]`
  (and `WorkflowNoArgInvocation`), with `invoke`, `invokeAsync`, `withMetadata` as today and
  `thenAwaitEnd[S](timeout: FiniteDuration)(using W <:< Workflow[S]): AwaitingInvocation[I, S]`,
  whose `invoke(input): S` sends the command, discards its reply unless it is a refusal, then
  awaits. The state serializer is the handle's, cast to `Serializer[S]`: a workflow has one state
  type. `WorkflowCalls.awaitEnd[W <: Workflow[S], S](companion: Workflow.Companion[W, S], timeout):
  S` and `awaitEndAsync` decode with the companion's `stateSerializer`, as `lifecycle(companion)`
  takes the companion today (`ComponentClient.scala:150`).
- `sdk` depends on `core` only (`build.sbt:222-227`) and has no Pekko Streams, so the `Source`
  form is `EndpointClients.awaitEnd[W <: Workflow[S], S](workflowId: EntityId, companion,
  timeout): Source[SseEvent, NotUsed]` in `http`, built from `Source.future(awaitEndAsync)` merged
  with `Source.tick(heartbeat, heartbeat, SseEvent.heartbeat)`, completing after the terminal
  event. `SseEvent` gains `heartbeat` (event `heartbeat`, data `{}`), `ended(json)` (event
  `ended`, the state's JSON), `failed(error)` (event `failed`, `{"code","message","step","reason","deleted"}`)
  and `timedOut(timeout)` (event `timed-out`). An `sseEvents` route returns the `Source` as it is.

**Rationale**: the spec's shapes are kept to the letter (`thenAwaitEnd(timeout)` with no second
argument) by letting the handle carry what the companion knows. `EndpointClients` is what an
endpoint already holds, and bundling is the module's rule ("Endpoints receive `EndpointClients`").

**Alternatives considered**: `thenAwaitEnd(companion, timeout)`: redundant at every call site.
Making `Invocation` non-final and subclassing: `Invocation` is shared by entities, where an await
means nothing. A `Source` on `WorkflowCalls`: would put Pekko into `sdk`.

## R7. The heartbeat interval

**Decision**: `HttpServer` reads `pekko.http.server.idle-timeout` from the system's config when it
builds `EndpointClients` and sets the heartbeat to a third of it, 20 seconds when the timeout is
`infinite`. The sidecar's `ClientLogic.awaitEndStream` uses the same rule over the same setting.
The caller does not set it (clarification 3); there is no configuration key.

**Rationale**: the socket keep-alive defaults to 20s against the 60s idle timeout
(`ankka.http.socket.keep-alive`), and `Sockets.scala:44` refuses one not shorter than the idle
timeout; a third is the same ratio with no setting to get wrong. `serving.feature` runs with
`pekko.http.server.idle-timeout = 3s` through `AnkkaTestKit.start(settings = …)` as `SocketSteps`
does, so a one-second heartbeat keeps the connection and the whole-answer route is cut.

**Alternatives considered**: a `ankka.http.await-heartbeat` setting: the spec says no interval is
configured, and a setting at or above the idle timeout would need the socket's startup refusal.

**Overlap with 047**: streaming view queries plan a server-wide SSE heartbeat setting
(`ankka.http.sse.heartbeat`, 15s) on every event stream. If it merges first, the await's `Source` and
the sidecar's stream rpc take their interval from that setting instead of a third of the idle
timeout, and the named `heartbeat` event stays, since `serving.feature` asserts the browser receives
one. Either way the caller never sets it.

## R8. The protocol: two rpcs, one field, version 1.15

**Decision**: `client.proto` gains

```proto
rpc AwaitEnd       (AwaitEndRequest) returns (InvokeReply);         // 1.15
rpc AwaitEndStream (AwaitEndRequest) returns (stream StreamToken);  // 1.15
message AwaitEndRequest { string component_id = 1; string entity_id = 2; int64 timeout_millis = 3; Metadata metadata = 4; }
```

`StreamToken` gains `Empty heartbeat = 5;` and `Payload ended = 6;`. `InvokeReply.reply` carries
the final state as the journal holds it (its content type and manifest are the state's own, which
for a process is what the process wrote); a failure is `InvokeReply.error` with `WORKFLOW_FAILED`
and `details`; a timeout is `error` with `TIMEOUT`. `WireProtocol.Version`, `Compatibility.version`,
`PROTOCOL_VERSION` in the three SDKs and `protocol/README.md` go to 1.15; `ProtocolSuite`,
`CompatibilitySuite` and the SDK contract tests that pin 1.14 move with them. An older runtime
answers both rpcs `UNIMPLEMENTED`; each SDK reports it as `tooOld("waiting for a workflow's end",
"1.15")` in its own words (`client.py:376-385`, `client.ts:386-397`). Nothing is declared at
discovery (clarification 5).

**The number is provisional.** Features 046 and 047 are planned on sibling branches and each takes
the next minor too. Whichever merges first is 1.15; the others renumber on rebase (the constant,
the suites that pin it, the `since 1.x` rows, the Versioning paragraph). Nothing in this design
depends on the number itself.

**Rationale**: `ScheduleRecurring` (1.12) is the model: a behaviour an older runtime must refuse is
a new rpc. The stream rpc exists because a unary one cannot carry heartbeats and a process's SSE
route needs them as a Scala one does (clarification 6). `Error.details` is a new field on an
existing message, which is safe here because only the new rpcs set it; every reader of `Error`
today ignores an unknown field.

**Alternatives considered**: a `Kind`-keyed `Invoke` with the reserved method name: the process
would need the hold loop, which is the runtime's. One rpc with polling in the SDK: the interval the
spec forbids.

## R9. The module's import

**Decision**: the Rust crate gains `Client::await_end<W: Workflow>(id, timeout) ->
Result<W::State, CommandError>` and `invoke_then_await_end`, over a new `await_end` import in its
own `#[link(wasm_import_module = "ankka1")]` block dispatched by its own `call_await` function, so a
module that never waits does not import it (`wasm.md`, checked with `wasm-objdump -j Import`).
`HostImports.awaitEnd` blocks the calling virtual thread on `ClientLogic.awaitEnd`;
`ModuleLoader.Imports` lists it, `WasmHostSuite` holds the two lists equal. No stream form: a module
cannot stream (`limitations.md`), and the import runs on `BlockingPool`'s fresh instance per call,
which a long wait holds for its duration, as the spec's edge case says. `WASM-ABI.md` and
`wasm-abi.md` gain the row "since protocol 1.15". `CallSite.Permitted` is not touched: `await_end`
is allowed wherever `invoke` is.

## R10. Tests: the five feature files, the SDK kits, conformance

**Decision**:

- `modules/testkit/src/test/.../awaiting/`: `QuoteWorkflow` (steps `rates`, `margin`, `offer`;
  a `StepScript` keyed by workflow id says how long each step takes and whether it fails, and the
  final state carries the quote) and `AwaitSteps extends GherkinSuite(features) with LogCapturing`,
  one final class per feature file. `AwaitingFeatures` binds the twelve scenarios of
  `awaiting.feature` (the "not polled" scenario asserts the engine's lifecycle-query count stays at
  zero while a waiter is held, read from the recorder; "one call in the topology" reads the topology
  through `ObservabilityEndpoint`); `CompositionFeatures` an `OnboardingWorkflow` whose `verify`
  step awaits `kyc`; `ServingFeatures` a `QuoteEndpoint` with `sseEvents` and a whole-answer route
  under `idle-timeout = 3s`; `InstancesFeatures` two nodes through `startPeer`, the caller on the
  node that does not host the workflow (each step records the instance it ran on in the state), and
  `Peer.stop()` or `restartService()` for whichever hosts it; `LanguagesFeatures` runs the Scala rows
  and marks the Python and TypeScript rows `ranElsewhere`.
- Python: `sdks/python/examples/shopping_cart/test_await.py` through `AnkkaTestKit` and
  `kit.client.for_workflow("checkout", id).await_end(...)`; a unit test with a fake sidecar
  answering `UNIMPLEMENTED` for the "before waiting" scenario, as the recurring-timer tests do.
  TypeScript: `examples/shopping-cart/await.test.ts` likewise.
- `ConformanceSuite` gains `wf.start-and-await` and `wf.await-failed`, driven through a new route
  `POST /conformance/checkout-await/{id}` with body `ok` or `fail` in every reference (Scala,
  Python, TypeScript, Rust), answering the final state as JSON or the failure's status 424 with the
  step and the reason in the body; `conformance.md` lists both behaviours and the route.
- `CheckoutWorkflowSuite` (`samples/shopping-cart`) awaits the end and asserts the state; its
  private `eventually` goes.
- `AwaitingWorkflowsDocumentationSuite` in `testkit`'s tests, one test per scenario of
  `features/documentation/awaiting-workflows.feature`, after `TimersDocumentationSuite`.

**Rationale**: `testing.md`: a feature file one suite can run whole is run by `GherkinSuite`; a
scenario no suite can reach is a test named after it. `startPeer` (`AnkkaTestKit.scala:135`) is
the multi-instance kit the spec names.

## R11. Documentation

**Decision**: `docs/build/workflows.md` gains a section "Waiting for the end" after "Domain state
and lifecycle": starting with an await and awaiting later in three tabs (includes from
`QuoteEndpoint.scala`, `endpoint.py`, `endpoint.ts` regions), what a completed, failed, deleted
and paused workflow answer, that the timeout is the caller's and required, that a wait holds the
workflow in memory, and when to serve the wait as a stream. `component-client.md`'s table gains the
await row and its Timeouts section a paragraph. `streaming.md` gains "Serving a wait" and drops
"Only agents stream" where it is no longer true; `http-endpoints.md`'s route table gains
`sseEvents`. `error-codes.md` becomes nine codes with `details` and `WORKFLOW_FAILED = 8`.
`akka-divergences.md` gains a summary row and a section "A caller can wait for a workflow's end".
`limitations.md` gains the gateway's fifteen-second HTTP route bound (no entry exists today), that
a module gets the plain await, and the rolling-update rule. `sidecar-protocol.md`'s Versioning gains
1.15 and its two stale "1.12" sentences are corrected; the generated rpc table follows `docs sync`.
`wasm-abi.md`, `scala-sdk.md`, `python-sdk.md`, `typescript-sdk.md` and `rust-sdk.md` gain their
rows; `glossary.md` gains "wait" and "heartbeat". The skills `ankka-workflows` (a rule and a mistake:
polling the lifecycle where an await would do), `ankka-endpoints` (serving a wait), `ankka-python`,
`ankka-typescript`, `ankka-rust` (the client call lists) and `ankka` (the routing row) are edited at
their sources under `tools/docs/skill/`, and `just docs-sync` renders them.

**Rationale**: `docs.md`: samples come from tested code, a page stands alone, a new fact beside a
generated table needs prose. The limitations page says the gateway bound applies to a wait served
through it (FR-012), which means writing the bound down for the first time.

## R12. Passivation and a long wait

**Decision**: nothing changes in passivation. Each re-ask is a message to the workflow, so an
awaited workflow is not idle and is not passivated while a waiter re-asks (idle passivation is
120s, `reference.conf`); a paused workflow awaited for an hour stays in memory for the hour, and
the documentation says a wait holds the workflow in memory. When a workflow is passivated between
two re-asks, the next re-ask recovers it from the journal, and a terminal standing is answered at
once from the recovered `Run`.

## R13. What stays as it is

The blueprint run's and the task's polled awaits (`RunCalls.scala:128`, `Calls.scala:86`): entity
waits, out of scope by clarification 1. The gateway's HTTP route timeout
(`Rendering.scala:920`): documented, not changed. `WorkflowSettings`, the lifecycle query and
`thenReply`: unchanged. The `ProjectionRuntime`'s and the agent module's uses of
`ErrorCode.Timeout`: untouched.
