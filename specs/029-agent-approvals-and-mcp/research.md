# Research: Agent Approvals and MCP

Decisions for [plan.md](plan.md), each with what in the repository it rests on. File references
are to this branch's base (`64102d79`); `agent/` is
`modules/agent/src/main/scala/com/thinkmorestupidless/ankka/agent/`. "Verify first" marks a claim
read from code or from a dependency's documentation and not yet run; the task that touches it
starts with a test that would show it false. The words are the glossary's: an **approval request**
is what an agent records when the model makes a **tool call** that **requires approval**; it is
**awaiting** a **decision** until a person decides it, it expires or it is **discarded**; an **MCP
server** has tools the agent offers the model; a **result guardrail** checks what one answers.

## R1. Requiring approval is a property of a tool

**Decision**: `FunctionTool` gains `approval: Option[Approval]`, set by
`tool.requiresApproval` or `tool.requiresApproval(within: FiniteDuration)`, where
`final case class Approval(within: Option[FiniteDuration])`. Both return a copy; a tool built as
today has `None`. `ToolSpec`, which is what the model sees, does not change: the model is offered
an approval-requiring tool exactly as any other.

**Rationale**: a `FunctionTool` is `(spec, invoker)` with no other metadata
(`agent/FunctionTool.scala:13`), and it is the one type both loops and the sidecar's
`FunctionTool.raw` already share (`sidecar/…/RemoteAgent.scala:103`,
`RemoteAutonomousAgent.scala:145`). Request agents attach tools per effect
(`AgentEffect.tools`, `agent/AgentEffect.scala:99`) and autonomous agents per instance
(`AutonomousAgent.tools`, `agent/autonomous/AutonomousAgent.scala:49`), so the tool is the only
place a declaration can sit that both kinds read.

**Alternatives considered**: a set of tool names on the effect (`.approvalFor("issue_refund")`) —
rejected: an autonomous agent has no effect, and a name can drift from its tool. A flag on
`ToolSpec` — rejected: `ToolSpec` is sent to the provider.

## R2. A request agent's wait is a suspended turn in the session, apart from its history

**Decision**: `SessionHistory` gains `suspended: Option[SuspendedTurn] = None`. A suspended turn
holds what the loop needs to go on and nothing the history does not already hold in its own form:

- the agent's component id, the handler's wire name and the request's payload;
- the messages the turn has produced so far (the user message, the model's message with its tool
  calls, the results of the tool calls that needed no approval), token usage and the step count;
- the approval requests, each with its id, the tool call it is for, when it was asked, when it
  expires if it does, and its decision once made.

Three events carry it: `TurnSuspended(turn)`, `ApprovalDecided(approvalId, decision)` and
`TurnEnded`. The turn's messages reach `messages` only when the turn ends with an answer, in the
same `persistAll` as `TurnEnded`.

**Rationale**: the request loop persists nothing between steps and writes the whole turn once, on
success, after the output guardrails and the decode (`agent/AgentLoop.scala:77-110`, `:406-450`).
Writing a half-finished turn into `messages` would change three things that hold today: a turn
that fails leaves no trace; `MemoryProvider`'s `filter` and `readLast` decide what a later turn
reads (`agent/memory.scala:84`), and either could cut a tool call from its result; and the
compactor counts messages and may drop the older part (`agent/compaction.scala:98-150`). Kept
apart, a suspended turn is untouched by all three, which is the spec's "a pending call is not a
message and is never compacted away" as a property of the shape. It also works for an agent whose
effect uses `MemoryProvider.none`: the turn is in the session whether or not history is.

The session is already an event sourced entity with a defaulted-field compatibility rule
(`Codecs.make` omits a field at its default, `core/Serializer.scala:59`), pinned by
`SessionMemoryCompatibilitySuite`, so `suspended = None` reads from every journal written before.

**Alternatives considered**: appending the model's message and an `ApprovalRequested` marker to
`messages` — rejected for the three reasons above. A table — rejected by the spec; nothing needs a
query across sessions.

## R3. Resuming rebuilds the effect by running the handler again

**Decision**: on the decision that leaves no approval request awaiting, the host decodes the
suspended turn's payload with the recorded handler's binding and runs the handler again to obtain
the `AgentEffect`. The loop then goes on from the suspended turn: the prompt is `buildPrompt` over
the history as read now, followed by the turn's recorded messages, followed by one tool-results
turn that joins the results already recorded with the results of the decided calls. Input
guardrails are not run again; output guardrails run on the answer as they do today.

**Rationale**: an effect is inert data, and building one performs no I/O and calls no model — the
codebase's organising rule — so running the handler a second time is safe by construction, and it
is the only way to recover an effect, which holds closures (the tools' invokers, the response
shape's codec) and cannot be journaled. A remote agent's handler is `RemoteAgent.plan`
(`sidecar/…/RemoteAgent.scala:50`), which asks the process for its plan again; the plan is data
too. The history cannot have changed while the turn was suspended: a session with a suspended
turn takes no new request (R5), and the compactor fires only on `AiMessageAdded`
(`agent/compaction.scala:110`), which nothing writes meanwhile.

A handler that no longer exists when the decision arrives (a deploy renamed it) ends the turn and
answers `Internal` naming the handler. That is the versioning boundary wire names already are.

**Alternatives considered**: journaling the system message, the tool specs and the response shape
— rejected: the tools' invokers still have to come from the handler, so the handler runs anyway,
and a second copy of the prompt could disagree with it after a deploy.

## R4. Tools run when the last decision arrives, at most once

**Decision**: a decision is recorded before anything acts on it. While another approval request
of the turn is awaiting, `decide` answers with those still awaiting and runs nothing. On the last
decision the approved calls run in the order the model made them; a refused or expired call's
result is an error result whose text says it was refused, by whom, and the note. If the service
stops, or the continuation fails, after the decision is recorded and before the turn ends, the
turn is **ended**: nothing is added to history, the session takes requests again, and the tool is
not run a second time. `decide`'s caller is told the failure.

**Rationale**: this is what a failed turn already means. Today a turn whose model call fails
after its tools ran leaves no history and answers `Unavailable` (`AgentLoop.scala:110`); the
tools' effects on the world stand and the conversation does not record them. A turn cut off after
approval is the same case. For a tool that needs a person's approval, running twice is the worse
failure, so the request agent chooses at most once; the autonomous agent keeps its existing rule,
at least once (R8), because there the platform, not a caller, drives the task to its end.

The host detects a cut-off turn without a timer: a suspended turn with no approval request
awaiting, met by a host that is not running it, was abandoned. The host ends it before doing what
it was asked.

FR-004's "exactly once" holds for the case SC-001 names — a restart between the model's call and
the decision — and the spec's edge cases gain the cut-off case.

**Alternatives considered**: running each approved call as its decision arrives — rejected: its
result would need its own event, and a crash between the run and the record makes it at least
once. Re-running on the next `decide` — rejected: the id is already decided, and a second
decision is a conflict by FR-005.

## R5. One check admits a request, and it rides on the history read

**Decision**: `AgentLoop.execute` refuses a request with `Conflict` when the session's
`suspended` turn has an approval request awaiting. Where the effect reads history, the check uses
that read; where it does not (`MemoryProvider.none`), it is one `suspended` query. `Conflict` is
new to the agent module's refusals (`core/CommandError.scala:35`).

**Rationale**: `SessionMemoryEntity.history` returns the whole `SessionHistory`
(`agent/SessionMemoryEntity.scala:142`), so the common case costs nothing. The host's own
serialising (`AgentHost.busy` stashes an `Invoke`, `agent/AgentRuntime.scala:396`) already keeps
a decision and a turn from interleaving on one node; the check is what holds across a restart and
across nodes, because it reads the journal.

## R6. How a caller is answered: an outcome, carried in the reply's metadata

**Decision**: `AgentCalls` gains `ask(handle)`, whose invocation returns
`AgentOutcome[O] = Answered(value) | AwaitingApproval(requests)`, and `decide(handle)(approvalId,
decision)`, which returns the same type. `call(handle)` keeps returning `O` and throws
`ApprovalAwaited(requests)` when the turn is waiting. On the wire the reply is the existing
`EntityProtocol.Succeeded(payload, metadata)` with a metadata entry `ankka-outcome: approval` and
the approval requests as the payload.

`decide` is typed by the handle of the handler that was called, which is what gives its reply a
codec; a handle that names another handler than the one suspended is refused `BadRequest`. It
reaches the agent's host as an `Invoke` whose method is the reserved `ankka:decide`; a developer's
handler may not be named with the `ankka:` prefix, checked in `Agent.Companion.descriptor`.

**Rationale**: `Reply` is a sealed trait that crosses nodes (`runtime/wire.scala:116-129`). A new
case is a class an older node cannot read during the one rolling deploy that introduces the
feature; a metadata entry is read by an older node as an ordinary reply whose payload fails to
decode — the same single window, with no serializer change. The agent host already handles
methods by name (`AgentHost.idle`, `AgentRuntime.scala:355`), and the autonomous host reserves
names the same way (`HostProtocol`, `agent/autonomous/Calls.scala:20-37`).

`http` cannot see `agent` (`build.sbt:254-256`, `:319-321`), so the HTTP module learns nothing of
approvals: an endpoint calls `ask` and chooses its own status. The documentation shows 202.

**Alternatives considered**: a new `Reply` case — rejected above. Changing `call` to return an
outcome — rejected: every existing call site would change for a feature it does not use.

## R7. A stream ends with the approval request, which needs a named event

**Decision**: `EntityProtocol.StreamToken` gains `StreamAwaiting(payload)`, sent in place of
`StreamCompleted`. `AgentCalls.streamParts(handle)(input)` returns
`Source[AgentPart, NotUsed]` with `AgentPart = Text(text) | AwaitingApproval(requests)`, the
second only ever last. `AgentCalls.stream` keeps its `Source[String, NotUsed]` and fails with
`ApprovalAwaited` at the end. `http` gains `SseEvent(name, data)` and a stream route that accepts
it, so an endpoint can send the approval request as an event named `approval`. A decision for a
turn that began as a stream is answered whole, not as a stream.

**Rationale**: a stream route today is `Source[String, ?]`, each element one JSON-encoded SSE
`data:` frame (`http/HttpServer.scala:536`, `HttpEndpoint.scala:145`). An approval request sent
as one more string would be indistinguishable from the model's text. `StreamToken` is sealed and
crosses nodes (`wire.scala:97-112`); unlike a reply it has no metadata to carry a marker, so this
is a new class, read by no older node — acceptable because only a service that declares an
approval-requiring tool can send it, and that service is the new version. The streaming loop
emits tokens as they arrive and learns of tool calls at `Completed` (`AgentLoop.scala:164-219`),
so the text the model wrote before its tool call has already been sent, which is what the spec's
"last frame" means.

**Verify first**: that a stream route can be given a second element type without breaking lambda
inference at existing call sites (`HttpEndpoint.scala:298-324` has four overloads already); if it
cannot, the route for events gets its own name.

**Alternatives considered**: a streamed `decide` — deferred: it doubles the client surface in
three languages, and the person deciding is rarely the one watching tokens.

## R8. An autonomous agent's approval requests are in its instance record

**Decision**: `Working` gains `approvals: Vector[ApprovalRequest] = Vector.empty`, written by two
new `InstanceEvent`s, `ApprovalRequested(request)` and `ApprovalDecided(approvalId, decision)`,
through the one `record(event)` command. `IterationStarted` and `TaskEnded` clear them. The host
gains the operation `decide`; `Notification` gains `ApprovalRequested` and `ApprovalDecided`;
`AgentState` gains `awaiting`.

`IterationLoop.runTools` records every `ApprovalRequested` **before** it runs any tool of that
response, then runs the calls that need no approval and appends their results. A new resume
point, `SettleCalls(n, message)`, applies whenever the last model message has a tool call with no
result in the session: a call with an approval request that is awaiting is waited for; a call
whose approval request is decided is run, or answered as refused; a call with no approval request
is run. The worker makes no model call, starts no iteration and counts no failure while any is
awaiting. A cancelled task ends as it does today, which clears the approvals.

**Rationale**: the instance record is "written only by its host, through one `record(event)`
command" (`agent/autonomous/InstanceEntity.scala:179`), and `resumePoint` already reads the
record and the session's tail to decide where to pick up (`IterationLoop.scala:70-86`). Recording
the request first is what makes a crash between the record and the tools safe: resume finds calls
without results and settles each by the rule above, which for a call with no approval request is
today's `RunTools`, at least once. `IterationCompleted` is recorded before the tools run
(`IterationLoop.scala:180-190`), so the iteration that asked is counted once and the wait is
between iterations, where the budget is checked (`:105`) and the suspend flag is read
(`AutonomousAgentHost.scala:531`) — nothing new is needed for "the clock stops".

The session `task:<id>` (`IterationLoop.scala:390`) is written as the loop goes, not at the end,
so the autonomous agent needs no suspended turn: the model's message is already there.

A waiting instance is idle for passivation (`AutonomousAgentHost.scala:124`), so an hour's wait
with no subscriber holds no actor; `decide` is a message, and a message starts it. The host
already passivates itself when it has no task; what is new is doing so with one.

**Verify first**: that an instance with a current task which passivates while awaiting is started
again by `decide`, by a cancelled task's dequeue and by the expiry action, and by nothing else —
the shard does not restart it in between. If it does not come back on a message, a waiting
instance stays in memory instead.

**Alternatives considered**: reusing the session's suspended turn — rejected: the autonomous
loop's resume reads the instance record, and two records of one wait can disagree.

## R9. Who decided is on the decision, and then on the tool's result

**Decision**: `Decision(approvalId: String, approved: Boolean, by: String, note: Option[String],
at: Long, expired: Boolean = false)`. `by` is required and not blank for a person's decision; an expiry is recorded
with `expired = true` and `by = "ankka"`. `SessionMessage.ToolResultMessage` gains
`decision: Option[Decision] = None`, set on the result of a call that needed approval, in both
kinds of agent.

**Rationale**: a suspended turn and an instance's `approvals` are cleared when the wait is over,
so the lasting record has to be in what stays, and the tool's result is the message the decision
produced. It is compacted with its message like any other, and a defaulted field keeps every
recorded `ToolResultMessage` readable (`agent/memory.scala:34`).

The decision carries its approval id because that is what answers a second `decide`. Once the
last request of a turn is decided the turn goes on and ends, and the suspended turn is gone; an
instance's approvals are cleared when the next iteration starts. Without the id on the result, a
second decision would be "not found" a moment after it would have been "conflict". With it, an
id that was decided is a conflict for as long as its result is in the history, and "not found"
means what it says: never asked, discarded, cut off, or no longer recorded.

## R10. Expiry is a timed action

**Decision**: `AgentRuntime.descriptors` gains a platform timed action, `ankka-approval-expiry`.
When an approval request with a time limit is recorded, a single timer is scheduled under the
name `approval:<component>:<session or instance>:<approval id>`; when it fires, the action sends
the expiry as a decision, to the agent's host or the autonomous agent's. A reply of `Conflict` or
`NotFound` means it was decided or discarded first and the action is done. A decision deletes its
timer, best effort. A time limit needs the service to register `TimerRuntime`: an autonomous
agent's tool or an MCP server that declares one fails the start without it, naming it, and a
request agent's tool — known only when its effect is built — fails the turn with `Internal`
before anything is recorded.

**Rationale**: nothing wakes a passivated entity, so a deadline that outlives the process has to
live where timers do: `ankka_timers` and its cluster-singleton sweeper
(`runtime/TimerRuntime.scala:14-60`). A timer's name is its identity and its payload is capped at
1024 bytes (`:76-95`), which an id triple fits. The sweeper runs only the timed actions the
registry holds and deletes rows for a component it does not know, so the action has to be a
registered component; `ankka-task-cascade` is the precedent for a platform component that needs
an extension the service may not have registered (`AgentRuntime.scala:256`, `:301-306`). That one
warns; this one refuses, because a deadline that silently never fires is a check that passes
while the thing it checks is false.

**Verify first**: that `AgentRuntime.start` can tell whether `TimerRuntime` is registered — the
runtime checks extensions by name for gRPC (`runtime/Ankka.scala:140`), and `TimerRuntime.name`
is `"timers"`.

**Alternatives considered**: an actor timer in the host — rejected: a request agent's host is not
remembered and holds no state. Expiring lazily at the next request — rejected: an autonomous
task would wait for ever, and "the model is told" would depend on someone asking.

## R11. Tools get the service client through the context

**Decision**: `AgentContext` and `AutonomousAgentContext` gain `services: ServiceClients`, passed
from `AnkkaService.services` at the three sites that pass `service.secrets`. A tool is a closure
over its agent, so it reaches the client as it reaches `componentClient`.

**Rationale**: `AnkkaService.services` already exists (`runtime/Ankka.scala:385`), built from
`HttpServiceClients`, which presents the service's certificate and expects the called service's
identity (`runtime/HttpServiceClients.scala:37-107`); the trait is in `sdk`, which `agent` sees.
The change is the one feature 023 made for `secrets` (`agent/Agent.scala:48-65`,
`AgentRuntime.scala:123`, `:186`, `:227`). The loop runs tools after the handler has returned;
`services` is a value on the context, not a thread-local, so nothing has to be captured.

025-polyglot-service-client's FR-009 asks for the same field on the same contexts. Whichever of
the two features lands first adds it; the other finds it there. See R19.

## R12. A tool call is a span, and a service call sits under it

**Decision**: both loops run a tool inside a span named for the agent and the tool, so that a
call the tool makes — to a component, or to another service — is recorded under it.

**Rationale**: neither loop records a tool call today; `AgentLoop` and `PromptReplay.runTool`
name no `Observability` (grep finds none in `agent/AgentLoop.scala` or `agent/PromptReplay.scala`),
so a tool's calls are children of the agent's handler span. `HttpServiceClients` counts and
records its calls already (`:136`). Names are interned once and the table must stay bounded:
an agent's own tool names are declared, and an MCP server's are read once at start (R14), so both
are; arguments, session ids and approval ids never go in a name.

**Verify first**: what `Observability` offers for a span that is not a handler's
(`runtime/Observability.scala`, `Recorder.scala`), and that a tool's name can be admitted to
`DeclaredNames` when the agent starts.

## R13. The MCP client is the platform's own, over the JDK's HTTP client

**Decision**: `agent/mcp/` holds a client for the part of the Model Context Protocol this feature
uses: JSON-RPC 2.0 over streamable HTTP, `initialize`, `notifications/initialized`, `tools/list`
with its cursor, and `tools/call`. It sends `Accept: application/json, text/event-stream` and
reads either answer; keeps the session id a server gives; and, told its session is gone, starts
one again and repeats the call once. It declares no client capability, so a server has nothing to
ask of it. A result's text parts are joined into the tool's result; a result with no text and
structured content is that content's JSON; a part that is not text is named and left out. A
result marked as an error, and a JSON-RPC error, reach the model as the tool's error.

It speaks through a small `McpTransport` — one POST, answered with a status, headers and a body —
with two implementations: a URL, over `java.net.http.HttpClient`; and an ankka service, over
`ServiceClient.request` (R15).

**Rationale**: `ankka-agent` is a published module, and what it names reaches every service's
build; the one HTTP client the runtime already uses is the JDK's
(`runtime/HttpServiceClients.scala:66`), and the module has its own `Json`. The surface needed is
four methods. Writing it also gives the service form its transport for free.

**Verify first**: against a real server, not only the scripted one — a spike suite
(`-Dankka.spikes=on`) runs the client against the reference "everything" server over streamable
HTTP, because the scripted server is a second implementation of the protocol's rules and will
agree with the client by construction. And: that the official Java SDK brings Reactor and Jackson
with it, which is the reason it was not chosen.

**Alternatives considered**: the official Java SDK — rejected for its dependencies in a published
module, and because the service form would still need a transport of our own for the certificate.

## R14. MCP tools are read at start, named so a provider accepts them, and offered with every plan

**Decision**: an agent's companion gains `def mcpServers: Vector[McpServer] = Vector.empty`, and
an autonomous agent's definition gains `.mcpServers(...)`. `AgentRuntime.start` connects to each
before the agent is hosted, reads its tools and builds a `FunctionTool` for each, named
`mcp__<server>__<tool>`. They are added to the tools of every effect the agent's handlers return,
and to an autonomous agent's tools; a judgment's effect is unchanged. A server that cannot be
reached, or refuses the platform, fails the start naming the server and the agent.

A server's name is lower-case letters, digits and hyphens. A name that breaks the rule, a server
listed twice, an agent's own tool whose name starts `mcp__`, and a tool whose whole name is longer
than 128 characters are each refused — the first three where the definition is built, the last at
start, naming the tool.

**Rationale**: the spec's form, `mcp:<server>:<tool>`, cannot be sent to a model. Anthropic's API
accepts a tool name matching `^[a-zA-Z0-9_-]{1,128}$`, and a colon is not in it; the name a
provider is given is the name the session records and the name a result is matched by, so it has
to be one name everywhere. Two underscores is the form Claude's own tools use for MCP. Because a
server's name has no underscore, the first `__` after `mcp` ends it and the name parses one way.
The spec and the features are amended to the new form.

Start is where "an unregistered component fails at startup" already lives
(`AgentRuntime.start`, `AgentRuntime.scala:92-237`, with the probe-instance check at `:180-197`).
Offering the tools with every effect is what "beside the agent's own" means for a request agent,
whose tools are otherwise chosen per effect; it also means a process's plan need not name them,
so FR-014's "the `Plan` MUST carry them" is amended: discovery carries the servers, and the
runtime adds their tools to every plan.

**Verify first**: the name rule, with `AnthropicProviderSuite`'s live case when a key is set, and
by reading the provider's reference when it is not.

## R15. An MCP server that is an ankka service is called as a service is

**Decision**: `McpServer.service(name, service, path)` (and the form with a project) reaches the
server through `context.services`, so the platform presents the agent's service's certificate and
the server's ACL admits it by name. `McpServer.at(name, url)` is any other server.

**Rationale**: `ServiceClient.request` is "one request, answered whatever its status", with a
body, a content type and headers both ways (`sdk/ServiceClient.scala:24-30`, `:64`). An MCP
server ends its event stream once it has answered, so a buffered response is enough for the four
methods used. Resolution, the certificate, the expected identity and the local lookup are then
exactly a tool's service call (R11) and need no TLS code in `agent`.

**Verify first**: that a `text/event-stream` answer is returned whole by `HttpServiceClients`
once the server closes it, with a test server that answers that way.

## R16. Where an MCP server is and what it is sent come from variables the runtime keeps

**Decision**: a server's address may be given in the definition or by the variable
`ANKKA_MCP_<SERVER>_URL`, which wins; with neither, the start fails naming the variable. A header
is declared as a name and the name of a variable its value is taken from, and that variable's
name must start `ANKKA_MCP_`. `PlatformVariables.RuntimeOnlyPrefixes` gains `ANKKA_MCP_`.

**Rationale**: for a process-hosted service it is the platform's program that connects to the
server, and the operator gives a descriptor's variables to the process, not to the platform's
container, unless the declaration says otherwise
(`core/PlatformVariables.scala:60-61`: `ANTHROPIC_`, `ANKKA_MODEL_`, `ANKKA_DB_`, `ANKKA_AUTH_`).
A header taken from an arbitrary variable, as the spec first said, would be set in the wrong
container and read as unset. A prefix the declaration routes is the `ANKKA_AUTH_` pattern, and
the same declaration withholds it from a module's `config`. The address by variable follows from
the same thought — a server's address differs between a laptop and a cluster, and a definition
is code — and it is what lets the conformance suite point a reference service at a scripted
server on an ephemeral port. FR-017 and its scenarios are amended to the prefix.

Variables are read through a function the runtime is built with, `sys.env.get` by default, so a
test sets them without touching the process's environment.

## R17. A result guardrail is a guardrail, declared apart, run where a tool's result is produced

**Decision**: `Guardrail` gains `checkResult(text: String): Either[String, Unit]`, allowing by
default; `Guardrails.Direction` gains `Result`; `JudgedGuardrail` gains `onResult(rules*)`. An
agent declares them apart from its input and output guardrails: `def resultGuardrails` on a
request agent's companion and `.resultGuardrails(...)` on an autonomous agent's definition. Both
loops run a tool through one function that, for a tool that came from an MCP server and answered
without an error, runs `Guardrails.check` with `Direction.Result`. A refusal replaces the result
with an error result naming the guardrail and its reason; the refused text is not kept. A
guardrail that cannot decide throws `GuardrailCheckFailed`, as it does today.

**Rationale**: "separate guardrails" is a separate declaration and a separate moment, not a
second type: `Guardrail.forbidding` and a judged guardrail's rules are as useful on a server's
text as on the model's, and `Guardrails.check` is the one function both loops run guardrails
through, which is where a judged guardrail gets its provider and its tokens are counted
(`agent/Guardrails.scala:61-80`). Request agents declare input and output guardrails per effect
(`AgentEffect.scala:102`); result guardrails go on the companion because the MCP servers they
guard are declared there. `PromptReplay.runTool` is the function both loops share
(`agent/PromptReplay.scala:53`), so the check is written once. A `FunctionTool` gains
`origin: ToolOrigin = Own | Mcp(server)` to say which results are checked.

A fault is an exception and never a refusal, by the rule a judged guardrail already follows: the
request loop answers `Unavailable` or `Timeout`, and the autonomous host counts a failed
iteration.

## R18. The protocol: one minor version

**Decision**: one minor version carries all of it. In `discovery.proto`, `Tool` gains an optional
`Approval`; `AgentDetail` and `AutonomousAgentDetail` gain `mcp_servers` and `result_guardrails`.
In `agent.proto`, `GuardrailRequest.Stage` gains `RESULT` and the request an optional tool name.
In `client.proto`, `InvokeReply` gains an approval case, `StreamToken` a terminal approval case,
and `Client` gains `Decide`. Nothing is removed or renumbered. The process's `AgentPlan` is
unchanged (R14).

**Rationale**: every one is an addition, which is a minor by the protocol's own rule
(`protocol/README.md`, "Version"). An older process sends none of the new fields and the sidecar
reads absence as today's behaviour; an older runtime answers `Decide` `UNIMPLEMENTED`, which an
SDK reports as "needs protocol 1.N" the way it does for the secret calls
(`sdks/python/src/ankka/secrets.py:28`, `sdks/typescript/src/client.ts:233`).

The sidecar holds every part of the behaviour: `RemoteAgent` and `RemoteAutonomousAgent` build
ordinary descriptors (`sidecar/…/RemoteAgent.scala:181`, `RemoteAutonomousAgent.scala:69`), so the
loops, the session, the timers and the MCP client are the ones the Scala agent uses. The process
gains only what it must answer itself: a result guardrail's check.

The Rust crate builds prost messages with exhaustive struct literals (`sdks/rust/ankka/src/
components/agent.rs:290`, `:466`; `autonomous.rs:508`; `effects/agent.rs:118`), so copying the
protocol breaks its build until each literal names the new fields. That is mechanical and is the
whole of the Rust change: a module's agent declares none of this, and the ABI's exports and
imports are unchanged.

The version number is the next minor after what `main` holds when the protocol slice starts:
1.7, or 1.8 if 025 has landed. The checklist of files a bump touches is feature 023's, in
[contracts/protocol.md](contracts/protocol.md).

## R19. What waits for 025, and what does not

**Decision**: approvals (both kinds of agent), MCP servers, their credentials and result
guardrails do not depend on 025 and are built first. A Scala tool's service call needs only R11.
A Python or TypeScript tool's service call is 025's `Request` and its SDK clients, and this
feature adds only its scenario and its conformance case; that slice is last and is built when 025
is on `main`.

**Rationale**: 025 is a draft with no code (`grep "rpc Request"` finds nothing). Its FR-009 and
this feature's FR-010 are the same field; its `Request` RPC is the only way a process reaches the
service client, and building it here would be building 025.

## R20. Test kits and doubles

**Decision**: `TestModelProvider` needs nothing new: `expectToolCall` and
`expectParallelToolCalls` script what the approval cases need. `agent` gains `TestMcpServer`, a
scriptable server on the JDK's `HttpServer`, loopback and an ephemeral port, in main sources as
`TestModelProvider` is, so the testkit, the sidecar's suites and a developer's tests share it. It
answers as plain JSON or as an event stream, by setting, so both branches of the client are
tested. The Python and TypeScript unit kits, which run their own copy of the loop
(`sdks/python/src/ankka/testkit/unit.py:505`, `sdks/typescript/src/testkit/kinds.ts:398`), gain
the pause, `decide`, scripted MCP tools and result guardrails.

**Rationale**: an SDK's unit kit is a second implementation of the loop and drifts; the
conformance cases, which run the real sidecar, are what hold it (R21). Time is asserted as the
autonomous suites assert it — by `model.callCount`, the record's iteration count and
`eventually` — and an expiry is tested with a limit of a second or two against the real sweeper,
which polls each second (`TimerRuntime.scala:68`).

## R21. Where each scenario is tested

| Feature file | Level | Suite |
|---|---|---|
| `approvals.feature` | whole service, Postgres | `ApprovalSuite` (testkit), through `GherkinSuite` where a step needs no restart, and as named tests where it does |
| the stream scenario | whole service over HTTP | `HttpSseSuite` |
| the two compatibility scenarios | pure | `SessionMemoryCompatibilitySuite`, which must list the three new events |
| `autonomous-approvals.feature` | whole service | `AutonomousApprovalSuite`; the resume cases also in `ResumePointSuite`, with no runtime |
| `service-calls.feature` | two services under TLS in one JVM, then k3s for SC-003 | a new suite on `TlsServing`; one case added to `EndToEndClusterSuite` |
| `mcp-servers.feature` | pure client; whole service | `McpClientSuite` (agent), `McpAgentSuite` (testkit), with an autonomous agent for its two scenarios |
| the MCP-server-is-a-service scenario | two services under TLS in one JVM | with `service-calls` |
| `languages.feature` | the real sidecar | `ConformanceSuite`'s `approval.*`, `auto.approval.*`, `mcp.*` cases against the Scala reference and each SDK; the service-call row waits for 025 |

Each acceptance scenario ends as a test that fails without the feature. Three places where a test
could pass while the thing is false, named now: the scripted MCP server agreeing with the client
it was written beside (the spike, R13); a "not counted" assertion that passes because the worker
never ran (assert a model call count that first reaches one, then holds); and a credential
"shown in no trace" assertion that passes because no trace was recorded (assert the tool call's
span is there first).

## R22. Documentation

`docs/build/agents.md` and `autonomous-agents.md` gain approvals and the service client in tools;
a new page, `docs/build/mcp-servers.md`, covers servers, their addresses and credentials, approval
per server and result guardrails; `docs/reference/limitations.md` replaces "There are no MCP
tools" with what is not covered (stdio, resources, prompts, OAuth, a streamed decision, modules);
`configuration.md` gains the `ANKKA_MCP_` variables and the two timeouts; the protocol page and
the three SDK reference pages follow the code; `docs/reference/glossary.md` gains the terms.
Samples are marked regions of tested code. The new page goes in `mkdocs.yml`'s `nav` and the
skills' `pages:` lists.

## Verify first, gathered

1. A stream route can take a second element type without breaking inference (R7).
2. `AgentRuntime.start` can tell that `TimerRuntime` is registered (R10).
3. What `Observability` offers for a tool's span, and admitting tool names (R12).
4. The client against a real MCP server over streamable HTTP; the official SDK's dependencies (R13).
5. The provider's rule for a tool's name (R14).
6. `HttpServiceClients` returns an event-stream answer whole (R15).
7. The frame a process's stream route sends, and whether it can carry an event's name
   ([contracts/protocol.md](contracts/protocol.md)).
8. A waiting autonomous agent that passivated comes back on `decide`, and only on a message (R8).

## Verified during implementation

- **V5 (T002)**: Anthropic's Messages API reference gives a tool's `name` as
  `^[a-zA-Z0-9_-]{1,128}$`, read 2026-10-04 at platform.claude.com/docs/en/api/messages. A colon
  is refused, so `mcp__<server>__<tool>` stands. The limit is 128, not the 64 first written here;
  R14, the Scala contract and T051 say 128. No live case was run: `ANTHROPIC_API_KEY` was not set.
- **R6 amended (T016, T018)**: `CallTransport.ask` returns only a reply's bytes, so a marker in the
  reply's metadata could not reach a caller as R6 first said. `CallTransport` gained
  `askWithMetadata`, defaulting to `ask` with empty metadata so the test transports are unchanged;
  `ShardingTransport` overrides it. `AgentCalls.call` now returns the agent module's own
  `AgentInvocation`, with the same `invoke`, `invokeAsync` and `withMetadata`, so existing call
  sites compile unchanged and a waiting turn throws `ApprovalAwaited` rather than failing to decode.
- **Scala contract, as built (T005, T018)**: `Decision` carries its `approvalId`, so `decide` takes
  the decision alone — `decide(handle)(Decision.approved(id, "dana"))`. Times on `ApprovalRequest`
  and `Decision` are epoch milliseconds, as a session's messages are, not `Instant`s.
- **V1 (T019)**: the event route has its own name, `sseEvents` (and `sseEventsBody`), rather than an
  overload of `sse`: with four overloads already, a second element type would have made a lambda's
  return type decide the overload. `StreamRoute` carries `SseEvent`s; `sse` routes wrap their text
  with `SseEvent.text`, and the sidecar's `RemoteEndpoint` does the same. Pekko writes a named
  event as `event:approval`, with no space, which the SSE grammar allows.
- **Until Phase 8 (T063)**: the sidecar's `ClientLogic` ends a stream whose turn waits with a
  `CONFLICT` failure naming the wait, so a process is never left on an open stream.
- **R8 as built (T023, T028)**: no `SettleCalls` resume point was added. `RunTools` now means
  "the last response's calls do not all have results", read by call id from the results after it,
  and settling is what running them does: record any missing approval request first, then run the
  calls with no approval and those decided, and answer refused ones. The existing `ResumePointSuite`
  fixture answered call `c` after a response whose call was `c1`; it now answers the call it
  follows, which the stricter rule needs.
- **V8 (T025)**: a waiting autonomous instance passivates (the operator fixture's
  `idlePassivationAfter` is one second) and comes back on `decide`, and on a cancelled task's
  dequeue. A waiting worker reports itself idle and pauses until an operation pokes it; a version
  that kept going round its task left the budget untouched too, so the budget case also asserts the
  instance leaves memory — shown failing once with the wait removed.
- **T030**: the awaiting state is a new fixture, `agent-state-awaiting.json`, beside
  `agent-notification-approval-requested.json`; `agent-state.json` is unchanged, so it still shows
  the form with nothing awaiting decodes.
- **V2 (T032)**: `AnkkaService.extensionNames` already says whether `TimerRuntime` (`"timers"`)
  is registered. `AgentRuntime.start` builds its own `DatabaseTimerScheduler` over `Database()`
  when it is, and passes it to both hosts; the timed action is `ankka-approval-expiry`, in
  `AgentRuntime.descriptors`. `TimedActionDescriptor` gained `platform`, as `ConsumerDescriptor`
  has, so the console and the topology mark the action as the platform's.
- **Expiry as built (T035, T036)**: the timer is scheduled before the request is recorded, so a
  stop between the two leaves a timer that finds nothing and is done rather than a request that
  waits for ever; a decision deletes it, best effort. A request agent's expiry is sent through
  `ankka:decide` naming no handler, so it goes to whichever turn holds the request. Without
  `TimerRuntime`, a request agent's tool with a limit is refused `Internal` before anything runs or
  is recorded; an autonomous agent with one fails its service's start, naming the tool.
- **V3 and R12 as built (T038, T040)**: a tool call is a span (`ToolSpans`, in `ToolRunner`),
  named for the agent's component and the tool, child of the agent's span, with the call's origin
  left as the agent's handler so topology attribution is unchanged. A call to another service was
  counted but recorded no span, so "the call appears inside the tool call" could not hold;
  `HttpServiceClients` now records one in the caller's trace, named for the admitted service and
  the method — both already bounded for the count.
- **R11 as built (T039)**: `services` is on both contexts; `sdk` gained `ServiceClients.unavailable`
  (beside `SecretStore.unavailable`) as the default for a context built with no service, and the
  HTTP module's own placeholder now points at it.
- **T041 narrowed**: `AnkkaService.services` resolves other services by Kubernetes DNS whenever TLS
  is configured, and offers no way to point it at a loopback port, so an in-JVM test cannot run
  the agent's call under mutual TLS without a runtime change made only for tests.
  `AgentServiceCallSuite` therefore runs the callee as plain HTTP found through
  `ankka.local-services`, and holds what this feature adds: the tool reaches the client from its
  context, the answer reaches the model, a refusal reaches the model as the tool's error, and the
  call sits inside the tool's span. That the client presents the service's certificate and is
  admitted by name is the client's own behaviour, held by `ServiceClientSuite`, and on a cluster
  by T042.
- **T042 not built yet**: no deployed Scala service can be given a scripted model today
  (`ANKKA_MODEL_SCRIPT` is read by the sidecar), so the k3s case needs an agent added to a sample
  image and a way to script its model. Held for the user's decision; SC-003 is met under mutual
  TLS by `ServiceClientSuite` and the plumbing by `AgentServiceCallSuite` until then.
- **V4 (T049)**: the client ran against the reference server
  `@modelcontextprotocol/server-everything` over streamable HTTP (2026-10-04, Node 24): `initialize`,
  `tools/list` (13 tools) and `tools/call` of `echo` all succeeded, answer `Echo: hello from ankka`.
  The server's tool names include hyphens (`get-tiny-image`), which the provider's name rule
  allows. The official Java SDK's dependencies were not re-examined; the client stays our own for
  the service transport regardless (R13, R15).
- **R14, R16 as built (T050–T054)**: `McpTools.connect` connects each server when `AgentRuntime`
  starts and gives its tools as `FunctionTool`s carrying the server's approval and origin; the
  request loop adds them to every effect (never a judgment's) in one place, `withMcp`, and the
  autonomous loop offers them beside the instance's own. Variables are read through
  `AgentRuntime.withVariables`, the process's environment by default, so no test-kit change was
  needed. The start-up refusals are tested on `McpTools.connect` directly (`McpToolsSuite`), with
  one whole-service case showing a refusal stops the start. A server that is an ankka service is
  tested through the real `HttpServiceClients` in local mode, plain JSON and an event stream (V6:
  the service client returns an event-stream answer whole); under mutual TLS it is the same
  client `ServiceClientSuite` holds, for the reason given for T041.
- **R17 as built (T055–T057)**: `ResultChecks`, in `ToolRunner`, runs the agent's result
  guardrails on an MCP tool's result that is not an error; a refusal replaces the result with an
  error naming the guardrail, so the text reaches neither the model nor the session. The request
  loop answers a guardrail that cannot decide as it answers any (`guarded`), and the autonomous
  loop counts it as a failed iteration whose calls are settled again. Shown failing once with the
  check removed. Not built: a whole-service case for a request agent's judged result guardrail
  that cannot decide — the path is the one every guardrail fault already takes there.
- **R18 as built (T059–T063)**: `main` reached 1.7 with feature 024 while this was being built, then
  1.8, 1.9 and 1.10 with features 025, 028 and 030 before it merged, so this feature is **protocol
  1.11**. Discovery carries a tool's
  `approval`, an agent's `mcp_servers` and `result_guardrails`; `GuardrailRequest` has the `RESULT`
  stage and an optional `tool`; `InvokeReply` and `StreamToken` have an `approval` case; `Client`
  has `Decide`. `GuardrailStage.Result(tool)` carries the tool so `checkGuardrail`'s signature is
  unchanged. A request agent's remote result guardrail is checked with no session id: it runs on
  the loop's thread after a tool answered, where no session is known, and no SDK's check reads one.
  The Rust crate names the new fields at their defaults, and a module's client answers an approval
  reply as a `CONFLICT` naming the wait, since a module takes no part in approvals.
- **T064 not built**: a process's HTTP stream route sends text frames only, so a process-hosted
  endpoint cannot yet forward an approval request as a named SSE event. No 1.11 scenario needs it
  (the stream scenario is the Scala agent's, held by `HttpSseSuite`); it goes on the limitations
  page.
- **Conformance as built (T065, T068, T071–T074)**: each reference has the agent `approver` (`refund`
  with approval, `ask_scripted` calling the service `scripted`, the servers `tickets` and `guarded` by
  name, the result guardrail `no-instructions`), a tool with approval on `answerer`, and the routes
  `/conformance/approver/{session}`, `…/decide/{id}` and `/autonomous/instances/{instance}/decide/{id}`;
  an outcome renders as `{"answered": text}` or `{"awaiting": [{"id", "tool", "arguments"}]}` in
  every language. The suite starts two `TestMcpServer`s before the target and gives the agent
  runtime their addresses through `withVariables`, for every target. A module declares no `approver`
  and skips the cases; `discovery.lists-every-component` expects the shorter set from a module. Run
  in process, against the Python and TypeScript processes and the Rust module in both shapes.
- **A decision is the work of the handler whose turn it decides**, found by the conformance suite's
  `topology.call-attributed` once an approved tool recorded a call: the decide path recorded its
  span under `ankka:decide`, which is not a declared name, so what an approved tool called was
  counted from nobody. The decide path now records under the handler the request names — taken only
  when the agent declares it, so a caller cannot grow the names table — and falls back to the
  reserved name otherwise.
- **The SDKs' shapes as built**: Python as `sdk-apis.md` says, with `ask`, `decide` and
  `stream_parts` on an agent's `Invocation` (`client.for_agent(id, session).call("ask")`), since that
  is how the SDK names a handler. TypeScript names a server and a result guardrail as it names a
  tool, wire name first (`mcpServer("tickets", {…})`, `resultGuardrail("no-instructions", …)`), and
  its unit kit keeps `ask` answering text — rejecting with `ApprovalAwaited` when the turn waits — with
  `outcome` for the outcome and `decide` to go on, so tests written before this feature are unchanged.
