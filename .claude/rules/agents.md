---
paths:
  - "modules/agent/**"
  - "modules/testkit/**"
  - "samples/multi-agent-planner/**"
  - "sidecar/**"
---

# Agents, autonomous agents and judgments

## Agents

The agent loop, tool dispatch, session memory, guardrails and token accounting all sit
above `ModelProvider`, which has two methods. Adding a provider means writing one
adapter, not re-implementing agent behaviour. `AnthropicProvider` uses the official Java
SDK as transport only.

Session memory is an event-sourced entity, which is what makes multi-agent collaboration,
compaction hooks and durability fall out rather than being features.

A **judgment** (feature 018, `agent/judgment`) is the second thing a service can ask a model for: typed
questions about a state — a choice, a score, a yes/no — answered with probabilities by a System One model
that writes no text (TypeSafe AI's Jev, `JevProvider`). It has its own seam, `JudgmentProvider`, beside
`ModelProvider`, because it is neither a component, nor a text model, nor an agent. Questions are values
with declared wire ids and option keys, checked where they are built; a `Judgment` stores answers by those
names and types them at the read. `Judgments.ask` is the one place the platform calls a provider, and it
verifies every answer against the request whoever the provider is. The effect is an ordinary
`AgentEffect` carrying a `JudgmentPlan`, so a judgment handler is registered and called like any other
and the sidecar never sees one. `Guardrails.check` is the one function both loops run guardrails through,
which is where a `JudgedGuardrail` gets the service's provider, counts what it spent and says it could not
decide. Judgment tokens are `SessionHistory.judgmentUsage`, recorded by `JudgmentUsageAdded` — with a
turn's messages, or alone and best effort when there are none. Scala only; `TestJudgmentProvider` is the
script.

An **autonomous agent** (feature 015, `agent/autonomous`) is the second kind: handed a task, it iterates
until the model calls the built-in `complete_task` or `fail_task`, or the budget runs out. Three
platform components carry it, all in `AgentRuntime.descriptors`: `ankka-task` (the task's record),
`ankka-agent-instance` (the instance's record, written only by its host, through one `record(event)`
command) and `ankka-task-cascade` (a consumer that cancels a failed task's dependents — so it needs a
`ProjectionRuntime`). The host is an actor shell (subscribers, operations one at a time, passivation) plus
one worker virtual thread running `IterationLoop`, which re-reads both records at every boundary. What an
instance did on a task is session memory, session `task:<id>`. Each iteration records its start, the
model's response, its completion, then the tool results; `IterationLoop.resumePoint` reads the instance
record and only the session's last message to pick up exactly — a recorded model call is never repeated,
a tool is run at least once. The entity type uses `remember-entities` with the event-sourced store, so a
working instance comes back after a crash with nothing sent to it (proven by `RememberEntitiesSpike`). In
the sidecar the definition arrives whole in discovery; the process runs tools, guardrails and
`CheckTaskResult` (decode as the type, then every rule, in one call).

A tool can **require approval** (feature 029, `FunctionTool.requiresApproval`, `agent/Approvals.scala`).
In a request agent the loop stops before running such a call and records a **suspended turn** in session
memory (the `suspend-turn` command: the handler, its encoded input, the turn's messages so far and the requests), and the
caller is answered `AgentOutcome.AwaitingApproval`. A decision (`AgentCalls.decide`, the reserved method
`ankka:decide`) is recorded first (`decide-approval`), and when it was the turn's last the turn is resumed
by **running the handler again** on the recorded input — safe because building an effect does no I/O — and
`AgentLoop.resume` goes on from the recorded messages. An approved tool runs at most once: a turn cut off
after its last decision is ended, not resumed. The session refuses a new turn while one is suspended, and a
second decision is `Conflict` found through `approvalId` on the recorded tool result. An autonomous agent
records its requests on the instance record (`Working.approvals`, cleared when the next iteration starts),
makes no model call while any awaits, and passivates while it waits; `IterationLoop` settles the calls of
the last response before anything else. A time limit is a platform timed action, `ankka-approval-expiry`
(in `AgentRuntime.descriptors`), scheduled **before** the request is recorded and decided as the platform
(`Decision.Platform`), so a service with a limit needs a `TimerRuntime`. The decide path records its span
under the handler the request names, when the agent declares it, so a call an approved tool makes is that
handler's.

An agent can list **MCP servers** (`agent/mcp`): `McpTools.connect` connects each when `AgentRuntime`
starts (Streamable HTTP, protocol `2025-06-18`), and their tools become `FunctionTool`s named
`mcp__<server>__<tool>` carrying the server's approval and an `Mcp` origin. Addresses and header values
come from `ANKKA_MCP_` variables read through `AgentRuntime.withVariables` (the environment unless a test
gives a map), which `PlatformVariables` routes to the platform's program only. **Result guardrails** are a
separate list, run by `ResultChecks` in `ToolRunner` on an MCP result that is not an error; a refusal
replaces the result, so the text reaches neither the model nor the session. In the sidecar `RemoteMcp`
turns the declarations into the Scala agent's, the process answers `CheckGuardrail` at stage `RESULT`, and
`Decide` on `Client` (protocol 1.9) carries a decision; the process is never asked to run an MCP tool nor
a call that awaits a decision. `TestMcpServer` (agent module, main scope) is the scripted server.

## Traps

- **One `TestModelProvider` cannot serve both an agent and an async consumer.** The
  compactor runs asynchronously, so whether the agent or the summariser reaches the queue
  first is a race, and a response queued for one gets consumed by the other. Give each a
  provider. (A compaction test passed for the wrong reason until this was separated.)
- **A workflow left mid-flight keeps consuming a shared scripted model**, starving the
  next test. Drain it before the test ends.
- **A judged guardrail's fault is an exception, never a `Left`.** A `Left` from a guardrail is a
  refusal — `Forbidden`, and on an autonomous agent a failed task or a rejected result. A judged
  guardrail whose provider failed has refused nothing, so `Guardrails.check` throws
  `GuardrailCheckFailed`, which the request loop answers `Unavailable`/`Timeout` (`Internal` for no
  provider at all) and the autonomous host treats as a failed iteration. Before this, a guardrail that
  threw at a task's start escaped to the worker's catch-all and was retried every second forever; it is
  now counted against `maxConsecutiveFailures` like any failed iteration.
- **A scripted judgment that cannot answer is `JudgmentScriptFailed`, and must never take the retry
  path.** It is deliberately not a `JudgmentFailed`: a test that added a question and forgot its answer
  must fail now, naming it, not back off for fifteen seconds as an outage would. `failNext` is how a test
  produces a real `JudgmentFailed`, on purpose, and each call queues exactly one.
- **Nothing wakes a passivated entity, and idle passivation stops a working one.** A workflow mid-step
  survives a restart only because something polls it. Autonomous agents are the one entity type with
  `remember-entities` (event-sourced store — the coordinator's list of shards is journaled too, so no LMDB
  on a pod's disk); remembering turns automatic passivation off, so the host passivates itself when it is
  idle and unwatched. A subscription keeps an instance alive.
- **A host that stops under an operation loses the reply.** Sharding's default stop message stopped the
  autonomous host while an `assign` it had started was in flight, and the caller timed out instead of
  hearing `Conflict`. The entity has its own `Stop` (`withStopMessage`): the host finishes the operation
  and everything stashed behind it first.
- **Work done for a task outside its iterations needs the task too.** A remote guardrail names the task's
  session, and the input guardrails run when a task *starts*, before any iteration. The current task is set
  around all work on a task (`AutonomousAgent.CurrentTask.within`), not per iteration.
- **Only the process can decode a remote result.** A per-rule check sent a malformed result to a Python
  rule, which raised, which is a failed iteration, retried forever — and a remote type with no rules would
  have accepted anything. `CheckTaskResult` decodes and runs every rule in one call, answering `malformed`
  as the Scala agent's decode failure is answered: a tool error the model corrects.
- **The agent loop runs tools and guardrails after the handler has returned and its session context
  is gone.** A `FunctionTool` invoker that reads `sessionId` when called throws "sessionContext is
  only available inside a command handler"; `RemoteAgent` captures the session at plan time. And in
  an anonymous `Guardrail`, `val name: String = name` is the val naming itself — null, and a
  `GuardrailRequest` that cannot be serialized; grpc-java reports that as `CANCELLED: Failed to
  stream message`, which reads like a network fault and is a NullPointerException in a field.
- **A handler name a call carries is believed only when it is declared**, and the reserved `ankka:`
  methods are not. The decide path first recorded its span as `ankka:decide`, so everything an approved
  tool called was counted from the unknown caller — invisible to every approval suite, found by the
  conformance suite's `topology.call-attributed` once an approved tool recorded a call. Work done on a
  handler's behalf through a reserved method is recorded as that handler's.
- **Two suspended-turn shapes must agree on the turn's input.** The turn is resumed by decoding the
  recorded payload with the handler's own serializer; a streaming handler's input is the stream handle's.
  A handler renamed between the suspension and the decision is answered `Internal` and the turn ended,
  never run as another handler.
