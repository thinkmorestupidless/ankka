# Feature Specification: Awaiting Workflows — A Caller Is Answered When the Workflow Ends

**Feature Branch**: `048-awaiting-workflows`

**Created**: 2026-10-10

**Status**: Draft

**Input**: User description: "A message sent to a workflow may be the trigger that starts it. Rather
than a query, we send a command that starts the workflow, but we also want the result of the workflow
in the response, rather than send the command to start and then poll. Let the command be sent and,
when the workflow reaches its terminal state, the response sent to the caller. Akka's SDK offers no
such thing: a workflow command replies when its handler returns, and a caller polls a query or
subscribes to notifications it may not get."

## Context

A workflow's command handler returns an effect — `updateState(s).transitionTo(step).thenReply(r)` —
and the engine (`WorkflowEngine.onInvoke`) answers `invoke.replyTo` with `thenReply` once the state
and the transition are journalled; the step runs afterwards, on its own thread, and comes back to the
actor as `StepSucceeded` or `StepFailed`. The reply to a starting command therefore confirms the start
and never the outcome. The engine reaches a terminal standing in four places: `onStepSucceeded` on
`StepOutcome.End` or `StepOutcome.Fail`, `onStepFailure` when retries are spent and there is no
failover, `onWorkflowTimedOut`, and a deletion; each journals `Ended` or `Failed` and cancels the
lifecycle timers. Nothing is waiting there to be told.

A caller learns a workflow ended by asking. `WorkflowLifecycle` (status `Running`, `Paused`,
`Completed` or `Failed`, the pending step, retries, the failure reason) is answered by the reserved
`ankka:lifecycle` query, and `isTerminal` is the test. The shopping cart's `CheckoutWorkflowSuite`
polls it in an `eventually` of forty seconds; the blueprint run's `RunCalls.await` and the autonomous
agent's `forTask(id).await(task, timeout)` each poll their own entity every 250 milliseconds until it
has ended or the timeout passes, with `ErrorCode.Timeout` after. Polling is already written three times
in the platform's own code, and a service that needs it writes a fourth.

A call's timeouts are not a wait's. The sharding transport's `ankka.ask-timeout` is ten seconds, and a
command is sent once: a dropped command and a slow one look alike, so the transport never resends a
command (`docs/reference/limitations.md`; a query is resent within the one ask). A workflow may run for
minutes and pause for days (`WorkflowSettings.timeout` is `None` by default; a step is thirty seconds).
An HTTP response has bounds of its own: pekko-http ends a connection idle for sixty seconds, and the
gateway applies a fifteen-second request timeout to a service's HTTP route (`Rendering.httpRoute`
leaves the HTTP rule as it was; the gRPC rule says `0s` "which would cut every stream"). A plain
response that takes two minutes is cut twice over, by the gateway and by the service; a stream of
server-sent events carrying a heartbeat is not cut by the service, and is bounded by the gateway as an
agent's stream is today.

Akka's documentation shows no await. Its command handlers reply on return; its `NotificationPublisher`
lets a client subscribe to progress as server-sent events and "does not guarantee delivery of every
notification"; otherwise a caller polls. So this is not a divergence to record so much as a thing ankka
does that Akka does not, and the divergences page says so.

Two shapes were considered. A **reply-at-the-end effect** — the handler says
`transitionTo(step).thenReplyOnEnd(state => r)` — puts the choice with the workflow's author and holds
the caller's `replyTo` inside the engine across steps; it answers only the caller who started the
workflow, cannot answer one who reconnects, and makes every workflow that wants to be awaited change
its code. A **caller-side await** — `forWorkflow(id).awaitEnd(Workflow, timeout)` — makes every
workflow awaitable with no change to it, answers a caller who arrives late or again, and composes with
the start as one call. The second is chosen, with the runtime holding the wait rather than the client
polling, so that the answer comes when the workflow ends and not at the next tick.

This feature makes these decisions.

- **Any workflow can be awaited, by any caller that may call it.** `awaitEnd(Workflow, timeout)` on a
  workflow's calls answers once the workflow has completed or failed, at once when it already has.
  `call(start).thenAwaitEnd(timeout).invoke(input)` sends the command and answers as `awaitEnd`
  would, so a caller that starts a process and wants its result makes one call. A process and a
  module reach both through the sidecar's client. An entity cannot: its handlers call nothing.
- **The answer is the end.** A completed workflow answers its final state; a failed one answers a
  failure naming the step and the reason, told apart from a refusal of the call itself. A workflow
  deleted while awaited answers a failure that says so. A paused workflow has not ended, and the wait
  goes on until the workflow ends or the timeout passes.
- **The caller states how long it waits.** A timeout is given on every await, bounded by nothing but
  the caller; there is no default, because a default of ten seconds would be wrong for a workflow and
  a default of a day would be wrong for an endpoint. A wait that times out is told so with
  `ErrorCode.Timeout`, and the workflow runs on; awaiting it again is answered when it ends.
- **The runtime holds the wait; the caller re-asks.** The engine keeps the waiters of a workflow in
  memory and answers them where it journals the end; a waiter held longer than one ask is told "not
  yet" and asks again until its own deadline, so a workflow whose shard moves or whose instance stops
  loses no waiter for longer than one ask. Nothing about a wait is journalled, and a workflow that
  starts, ends and is awaited later is answered from its standing.
- **Answered when it ends, not at the next tick.** A waiter is answered within the time it takes to
  journal the end and deliver a reply, however long the workflow ran; no interval is configured and
  none is polled.
- **Over HTTP, a wait that may outlast the connection is a stream.** The SDK offers the await as a
  `Source` that carries a heartbeat while it waits and the end when it comes, so an SSE route serves a
  long wait through the service's idle timeout; the documentation says the gateway's route bound still
  applies, as to any stream through it.

What this feature is not: a change to when a command handler replies, a change to the lifecycle query
or to workflow settings, a notification stream of a workflow's progress (046 reads progress as data),
or a change to the gateway's timeouts. It is not a way to wait for an entity, an agent's session or a
task, which have their own.

## Clarifications

### Session 2026-10-10

- Q: SC-004 names the blueprint run's and the autonomous agent's polled awaits, which poll entities, not
  workflows, while the feature excludes waits on an entity or a task. Which is right? → A: Workflows only.
  SC-004 names the shopping cart's `CheckoutWorkflowSuite` alone; the run's and the task's awaits stay as
  they are.
- Q: How is a failed workflow's answer told apart from a refusal and an internal error, and how does it
  name the step and the reason? → A: A new `ErrorCode.WorkflowFailed`, with the step and the reason as
  fields on the error and on the protocol's `Error`, not parsed from the message. A deleted workflow
  answers the same code with no step and the reason that it was deleted.
- Q: How are the stream form's timeout and heartbeat set? → A: The stream takes the caller's timeout as
  every await does and ends with the timeout when it passes; the heartbeat interval is the SDK's, a
  fixed fraction of the service's configured idle timeout, not the caller's to set.
- Q: The glossary marks "idle timeout" and "heartbeat" as proposed; settle them as written? → A: Yes,
  both settled as defined, with "keep-alive" and "ping" refused as synonyms of heartbeat.
- Q: A handler's wait is not declared, so an SDK cannot refuse an older runtime at discovery as it does
  for a socket route. How does an older runtime refuse a process that waits? → A: At the call. The
  process starts; its first wait is answered `UNIMPLEMENTED` by the older runtime and the SDK raises an
  error naming the protocol version waiting needs, as a recurring timer is refused today. The scenario
  is reworded accordingly.
- Q: Is the stream form Scala's alone, or every SDK's? → A: Every SDK's. A process gets the wait as a
  stream of heartbeats and the end through the sidecar, so a Python or TypeScript endpoint serves a
  long wait as server-sent events too. A module cannot stream, and gets the plain wait.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A command starts a workflow and its caller is answered with the result (Priority: P1)

A pricing endpoint receives a quote request. Its handler sends `start` to the `quote` workflow with the
request and awaits the end within thirty seconds; the workflow consults two agents and a rates entity
over three steps and ends with the quote in its state. The handler answers the request with the quote.
No query, no polling, no second route.

**Why this priority**: This is the request: a command that starts a process whose result the caller
wants in the response.

**Independent Test**: In the test kit, start a workflow of three steps with the await and assert the
answer is the state the workflow ended with and arrives after the last step; start one that fails at
its second step and assert the answer is a failure naming the step and the reason, within the timeout;
assert the lifecycle query was not asked while waiting.

**Acceptance Scenarios**:

- added `features/awaiting-workflows/awaiting.feature`: a caller sends a command and is answered with the state the workflow ended with
- added `features/awaiting-workflows/awaiting.feature`: a caller that waits for a workflow that fails is answered with the failure, not a refusal
- added `features/awaiting-workflows/awaiting.feature`: a command that is refused is answered at once and no wait begins
- added `features/awaiting-workflows/awaiting.feature`: a caller is answered when the workflow ends and not by asking again and again

---

### User Story 2 - A caller waits for a workflow it did not start (Priority: P1)

A consumer started a settlement workflow from an event. Later an endpoint is asked for the settlement's
result and awaits the workflow's end within ten seconds: if it has ended, the answer is immediate; if
not, the endpoint waits; if it still has not by then, the caller is told the wait timed out and may ask
again.

**Why this priority**: Separating the await from the start is what makes every workflow awaitable
without changing it, and what lets a caller that reconnects get its answer.

**Independent Test**: End a workflow, then await it and assert the answer is immediate; await a running
one with a short timeout and assert `Timeout` and that the workflow runs on; await it again and assert
the state.

**Acceptance Scenarios**:

- added `features/awaiting-workflows/awaiting.feature`: a workflow that has ended answers a wait at once
- added `features/awaiting-workflows/awaiting.feature`: a wait not answered in time is told it timed out and the workflow runs on
- added `features/awaiting-workflows/awaiting.feature`: a caller whose wait timed out waits again and is answered
- added `features/awaiting-workflows/awaiting.feature`: a paused workflow has not ended
- added `features/awaiting-workflows/awaiting.feature`: a workflow that has recorded nothing is waited for until the wait times out
- added `features/awaiting-workflows/awaiting.feature`: a workflow deleted while it is waited for answers a failure that says so
- added `features/awaiting-workflows/awaiting.feature`: two callers waiting for one workflow are each answered
- added `features/awaiting-workflows/awaiting.feature`: a wait is one call in the topology

---

### User Story 3 - The wait survives the workflow's instance moving (Priority: P2)

A service of three instances. A caller on one instance awaits a workflow whose shard is on another;
that instance stops mid-step. The workflow recovers on a third instance, runs its remaining steps and
ends, and the caller is answered with the state. The caller never saw the move.

**Why this priority**: A wait held in memory across a shard move would be lost silently; the re-ask
is what makes the await trustworthy on a cluster.

**Independent Test**: In the multi-instance test kit, await a workflow, stop the instance hosting it
during a step, and assert the awaiting caller is answered with the final state after the workflow
recovers elsewhere.

**Acceptance Scenarios**:

- added `features/awaiting-workflows/instances.feature`: a caller is answered after the instance running the workflow stops during a step
- added `features/awaiting-workflows/instances.feature`: no caller is lost when an instance stops

---

### User Story 4 - A workflow step awaits another workflow (Priority: P2)

An onboarding workflow's step starts a `kyc` workflow for the applicant and awaits its end within the
step's own timeout; the kyc's final state decides the onboarding's next step. A process in Python or
TypeScript does the same through its client.

**Why this priority**: Composition is the second reason to await: a parent process that needs a
child's result and would otherwise pause and be resumed by a consumer.

**Independent Test**: A parent workflow whose step starts and awaits a child; assert the parent's next
transition carries the child's state; a step whose timeout is shorter than the child's run fails as a
step failure naming the timeout.

**Acceptance Scenarios**:

- added `features/awaiting-workflows/composition.feature`: a step goes on with the state the other workflow ended with
- added `features/awaiting-workflows/composition.feature`: a step whose timeout passes before the other workflow ends fails as timed out
- added `features/awaiting-workflows/languages.feature`: a handler sends a command and is answered with the state in every language
- added `features/awaiting-workflows/languages.feature`: a handler that waits for a workflow that fails is answered with the failure in every language
- added `features/awaiting-workflows/languages.feature`: a runtime from before waiting refuses a handler's wait, naming the protocol version

---

### User Story 5 - A long wait is served over HTTP as a stream (Priority: P2)

A report workflow runs for three minutes. The endpoint serves `POST /reports` as server-sent events
from the await: a heartbeat every few seconds while it runs, then one event with the report. The
connection is not cut by the service's idle timeout; the documentation tells the developer the
gateway's bound still applies.

**Why this priority**: Without this an await longer than a minute cannot be delivered to an HTTP
caller at all, and the developer finds out from a cut connection.

**Independent Test**: Serve an await of a workflow that runs longer than the service's idle timeout as
an SSE route in the test kit; assert heartbeats arrive, the connection stays open and the final event
carries the state.

**Acceptance Scenarios**:

- added `features/awaiting-workflows/serving.feature`: a wait served as server-sent events outlasts the service's idle timeout
- added `features/awaiting-workflows/serving.feature`: a wait served as one whole answer is cut by the service's idle timeout

---

### User Story 6 - The documentation says how to await and what to expect (Priority: P3)

The workflows guide shows starting with an await and awaiting later, in each language, says what a
completed, failed, deleted and paused workflow answer, that the timeout is the caller's and required,
and when to serve the wait as a stream; the divergences page says Akka has no await; the component
client guide lists it.

**Acceptance Scenarios**:

- added `features/documentation/awaiting-workflows.feature`: the documentation describes starting a workflow and waiting for its end
- added `features/documentation/awaiting-workflows.feature`: the documentation says what each ending answers and when to serve a wait as a stream
- added `features/documentation/awaiting-workflows.feature`: the documentation says Akka offers no wait for a workflow's end

---

### Edge Cases

- **The command's own reply.** `thenAwaitEnd` discards the command's reply; a caller that wants both
  sends the command and then awaits. The documentation says so.
- **A workflow that ends in its starting command.** A command that transitions to a step that ends at
  once is answered after the end, as any other; a command that only updates state and never
  transitions leaves a workflow that never ends, and the wait times out — the documentation says a
  wait is for a workflow that will end.
- **Two callers await one workflow.** Each is answered; a workflow's waiters are a list.
- **A wait across a rolling update.** An instance from before this feature answers an await with
  "no handler", and the client reports that the runtime does not await; the documentation says both
  halves must be at this release. A process beside an older runtime starts, and its first wait is
  refused naming the protocol version waiting needs; nothing a process declares says it will wait.
- **The failure's shape.** A failed workflow's failure is `ErrorCode.WorkflowFailed` with the step and
  the reason as fields, which the lifecycle query also reports; it is not `ErrorCode.Internal`, which a
  caller would read as the call having broken, and a caller in another language reads the fields rather
  than parsing the message. The error's fields are a protocol change: the wire `Error` gains them.
- **A timed-out wait and the ask.** The caller's deadline is checked by the caller; the engine's hold
  per ask is shorter than `ankka.ask-timeout`, so no single ask times out at the transport while the
  wait is healthy.
- **A deleted workflow awaited after the deletion.** Answered with the deletion failure at once, from
  the standing.
- **A wait from a consumer.** Allowed, and holds that slice of the consumer's projection for the
  wait; the documentation says to start the workflow from a consumer and await it from an endpoint
  instead.
- **A wait from a module.** The module's import parks the calling virtual thread as any call does; a
  long wait holds a module instance for its duration, and the documentation says so.
- **The stream's end.** A stream whose caller's timeout passes ends with the timeout, as the plain await
  is told it; a stream whose client disconnects ends the wait, and the workflow runs on.
- **Tracing.** A wait is one call from the caller to the workflow in the trace, open from the ask to
  the answer; a span held for minutes is recorded when it ends, as a socket's is.

## Requirements *(mandatory)*

### Functional Requirements

**Awaiting**

- **FR-001**: A caller MUST be able to wait for a workflow's end by its id with a timeout it gives, and
  MUST be answered once the workflow has completed or failed, at once when it already had.
- **FR-002**: A caller MUST be able to send a command to a workflow and wait for its end as one call,
  answered as the wait is; a command the handler refuses MUST answer the refusal at once with no wait.
- **FR-003**: Every await MUST take a timeout from the caller; there is no default. A wait not answered
  by then MUST be told so with `ErrorCode.Timeout`, and the workflow MUST run on unaffected.
- **FR-004**: A completed workflow MUST answer its final state. A failed one MUST answer a failure with
  the code `WorkflowFailed`, told apart from a refusal of the call and from an internal error, carrying
  the step and the reason as fields of the error in every language, not only in its message. A deleted
  one MUST answer `WorkflowFailed` with no step and the reason that it was deleted. A paused one has not
  ended.
- **FR-005**: The runtime MUST answer a waiter when it journals the end, not at a polled interval; a
  waiter MUST be answered within one second of the end being recorded on an unloaded service.
- **FR-006**: A wait MUST survive the workflow's shard moving or its instance stopping, by the client
  asking again within its own deadline; nothing about a wait is journalled.
- **FR-007**: Several callers MAY wait for one workflow, and each MUST be answered.
- **FR-008**: An await MUST be available to every component that may call a workflow — an endpoint, a
  workflow step, a consumer, a timed action, an agent's tool — and to a process and a module through
  the sidecar's client at the next protocol version; a runtime at an earlier version MUST refuse a
  process's await when it is made, and the SDK MUST report the refusal naming the version waiting
  needs.

**Serving**

- **FR-009**: Every SDK but the module's MUST offer the await as a stream that carries a heartbeat
  while it waits and the end when it comes, so an SSE route in Scala, Python or TypeScript can serve a
  wait longer than the service's idle timeout; a module cannot stream and gets the plain await. The stream
  MUST take the caller's timeout as every await does, and end with the timeout when it passes. The
  heartbeat interval is the SDK's, a fixed fraction of the service's configured idle timeout, and MUST
  be shorter than it; the caller does not set it.
- **FR-010**: A wait MUST be one call in the trace and the topology, from the caller to the workflow,
  counted as handled when answered and as timed out when the caller's deadline passes.

**Testing**

- **FR-011**: The Scala test kit MUST support an await against a running service, including across an
  instance stopping in the multi-instance kit; the Python and TypeScript test kits MUST support it
  through the sidecar; the conformance suite MUST hold a start-and-await and an await of a failed
  workflow.

**Documentation**

- **FR-012**: The workflows guide MUST show starting with an await and awaiting later in each language,
  what each ending answers, that the timeout is the caller's and required, and when to serve the wait
  as a stream; the component client guide MUST list the await; the divergences page MUST say Akka has
  no await; the limitations page MUST say the gateway's route bound applies to a wait served through
  it.

### Key Entities

- **Await**: a caller's wait for a workflow's end, with the caller's timeout; answered with the end,
  or told it timed out.
- **End**: how a workflow finished: completed with its final state, failed with a step and a reason,
  or deleted; the last two answer as `WorkflowFailed`.
- **Waiter**: what the runtime holds in memory for one caller awaiting one workflow, answered where
  the end is journalled.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An endpoint answers a request with the result of a workflow of several steps in one
  call, with no query route and no polling, in Scala, Python and TypeScript.
- **SC-002**: A caller is answered within one second of a workflow's end being recorded, whether the
  workflow ran for one second or ten minutes.
- **SC-003**: On a service of three instances, an await is answered correctly when the workflow's
  instance stops mid-run, on every run of the multi-instance suite.
- **SC-004**: The shopping cart's `CheckoutWorkflowSuite` is written over the new await, with no
  `eventually` and no interval. The blueprint run's and the autonomous agent's awaits poll entities, not
  workflows, and stay as they are.
- **SC-005**: A wait longer than the service's idle timeout is delivered to an HTTP client over an
  SSE route without the connection being cut by the service.

## Assumptions

- The engine can hold a waiter's `replyTo` beside its `Run` state without journalling it, and answer
  it from `onStepSucceeded`, `onStepFailure`, `onWorkflowTimedOut` and the deletion path.
- The hold per ask is shorter than `ankka.ask-timeout` and the client re-asks; a query-shaped ask is
  already resent within one ask by `ShardingTransport`, and the await's re-ask is the client's own
  loop over its deadline.
- The sidecar's `Client` service carries the await as two rpcs of its own — one answering an
  `InvokeReply`, one a stream of tokens with a heartbeat and an end — so an older runtime refuses them
  as it refuses any rpc it lacks.
- pekko-http's idle timeout is sixty seconds by default and an SSE heartbeat resets it; the gateway's
  HTTP route request timeout is fifteen seconds and is not changed by this feature.
- An error can gain fields beside its message and code — on `CommandError`, on the protocol's `Error`
  and in each SDK's error type — without changing how every other error is read.

## Dependencies

- 046-workflow-sources: independent; a reader of many workflows reads them, a caller of one awaits
  it. The standing both deliver is one definition.
- 009-polyglot-runtimes and 016-wasm-hosting: the `Client` service and the module's imports gain the
  await.
- 028-websocket-routes and the SSE routes: what carries a long wait.
- 036-blueprints and 015-autonomous-agents: two polled awaits of entities, which this feature leaves as
  they are; a wait on an entity's end would be a feature of its own.

## Open Questions

- Whether an await should be able to end on a pause — "answer me when it pauses or ends" — for a
  caller that will supply the next command; a flag if wanted, not in this feature.
- Whether the gateway's HTTP route timeout should be lifted for SSE as for gRPC, which 047 asks too.
