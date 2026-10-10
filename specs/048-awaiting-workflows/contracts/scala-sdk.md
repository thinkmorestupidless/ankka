# Contract: the Scala SDK

## Awaiting later

```scala
// modules/sdk — WorkflowCalls
def awaitEnd[W <: Workflow[S], S](companion: Workflow.Companion[W, S], timeout: FiniteDuration): S
def awaitEndAsync[W <: Workflow[S], S](companion: Workflow.Companion[W, S], timeout: FiniteDuration): Future[S]
def withMetadata(metadata: Metadata): WorkflowCalls     // as Invocation.withMetadata; the await carries it
```

```scala
val quote: Quote = client.forWorkflow(EntityId(id)).awaitEnd(QuoteWorkflow, 30.seconds)
```

- Answers the final state when the workflow has completed, at once when it already had.
- Throws `CommandError` with code `WorkflowFailed` when it failed or was deleted;
  `WorkflowEnd.failure(error)` gives `Failure(step, reason, deleted)`.
- Throws `CommandError` with code `Timeout` when `timeout` passes first; the workflow runs on.
- Throws `CommandError(BadRequest)` for a non-positive timeout; there is no default.
- Blocks the calling virtual thread (free), like `invoke`.

## Starting and awaiting as one call

```scala
// modules/sdk — WorkflowCalls
def call[W <: Workflow[?], I, O](handle: CommandHandle[W, I, O]): WorkflowInvocation[W, I, O]
def call[W <: Workflow[?], O](handle: NoArgHandle[W, O]): WorkflowNoArgInvocation[W, O]

final class WorkflowInvocation[W <: Workflow[?], I, O]:
  def invoke(input: I): O                 // as today
  def invokeAsync(input: I): Future[O]
  def withMetadata(metadata: Metadata): WorkflowInvocation[W, I, O]
  def thenAwaitEnd[S](timeout: FiniteDuration)(using W <:< Workflow[S]): AwaitingInvocation[I, S]

final class AwaitingInvocation[I, S]:
  def invoke(input: I): S
  def invokeAsync(input: I): Future[S]
```

```scala
val quote = client.forWorkflow(EntityId(id)).call(QuoteWorkflow.start).thenAwaitEnd(30.seconds).invoke(request)
```

- Sends the command; a refusal of the command is thrown at once and no wait begins.
- Discards the command's own reply; a caller that wants both sends the command and then awaits.
- Then awaits as `awaitEnd` does, with the same `timeout` from the moment the command was sent.
- `S` is decoded with the state serializer the workflow's companion put on the handle; a
  `CommandHandle` built by anything but a `Workflow.Companion` has none, and `thenAwaitEnd` is not
  offered on an entity's invocation (it is a method of `WorkflowInvocation` only).

## The end as a type (core)

```scala
object WorkflowEnd:
  final case class Failure(step: Option[String], reason: String, deleted: Boolean)
  def failure(error: CommandError): Option[Failure]   // Some only for ErrorCode.WorkflowFailed
```

## Serving a wait as server-sent events (ankka-http)

```scala
// modules/http — EndpointClients
def awaitEnd[W <: Workflow[S], S](workflowId: EntityId, companion: Workflow.Companion[W, S], timeout: FiniteDuration): Source[SseEvent, NotUsed]
```

```scala
sseEvents("/reports/{id}") { id => clients.awaitEnd(EntityId(id), ReportWorkflow, 10.minutes) }
```

- Emits `SseEvent.heartbeat` every heartbeat interval (a third of the server's idle timeout, 20s
  when infinite), then exactly one of `SseEvent.ended(stateJson)`, `SseEvent.failed(error)` or
  `SseEvent.timedOut(timeout)`, and completes.
- `SseEvent` gains `heartbeat: SseEvent`, `ended(json: String)`, `failed(error: CommandError)`,
  `timedOut(timeout: FiniteDuration)`, each a named event with JSON data (`runtime.md`: SSE payloads
  are JSON).
- `HttpEndpoint` gains `sseEvents(template)(handler: () => Source[SseEvent, ?])` for a route with
  no path parameter, beside the one-parameter form at `HttpEndpoint.scala:401`.
- A whole-answer route (`post(...) { … awaitEnd(...) }`) is served as today, and the service's
  idle timeout cuts it when the wait outlasts the timeout; the documentation says so.

## Errors over HTTP

`HttpProblem.from(CommandError)` maps `WorkflowFailed` to **424** and the problem body gains the
error's `details` (`step`, `reason`, `deleted`). `ToResponse` is unchanged.

## Errors over gRPC

`GrpcStatus` maps `WorkflowFailed` ↔ `ABORTED`. As for every code today, the status description
is the message and nothing else crosses; a gRPC caller reads the step and the reason from the
message, and the gRPC endpoint page says so. Carrying `details` in trailers is a gRPC concern
outside this feature.

## Test kit

- `TestTransport.awaitEnd` answers from its stubs: a stubbed terminal state is answered at once, a
  stubbed `WorkflowFailed` thrown, nothing stubbed fails `NotFound`.
- `AnkkaTestKit.startPeer` is what the instances feature uses; no new kit method.
- `CheckoutWorkflowSuite` reads `componentClient.forWorkflow(id).awaitEnd(CheckoutWorkflow, 40.seconds)`.
