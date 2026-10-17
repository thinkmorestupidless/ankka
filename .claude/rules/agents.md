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

## Model providers

Feature 052. Two wire protocols, not a vendor each: `AnthropicProvider` (Messages) and
`OpenAiProvider` (`agent/openai`, Responses for OpenAI's own API, Chat Completions for every other
server), each over the vendor's SDK as transport only. `agent/providers` holds the rest: `ProviderConfig`
(the one set of rules and refusal wording for the sidecar and a Scala service alike), `Presets` (the one
table; the docs' preset table is generated from it by `ModelPresetsReferenceSuite`), `ModelProviders`
(the factory and the name rule) and `ToolCallIds`. The OpenAI provider builds each request as ankka's
`Json`, hands it to the SDK through `putAdditionalBodyProperty` (and a cast `JsonField` for Chat
Completions' required `messages`), and reads the answer back as JSON through the SDK's own mapper, so
the translation does not move with the SDK's model classes, which change every working day. The
sidecar's `Models.fromEnv` turns `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` and `ANKKA_MODEL_<NAME>_*` into
configs. `ModelStandIn` (agent main scope, beside `TestMcpServer`) is a loopback server speaking all three
shapes from one script of `ModelResponse`s.

An agent's model can be an **installation model** (feature 053): a provider whose preset is `installation`
names only a model (`ANKKA_MODEL_<X>_PRESET=installation`, `ANKKA_MODEL_<X>_MODEL`) and takes its address
from `ANKKA_MODELS_ADDRESS`, which the operator renders on the platform's container, and its client
certificate from the service's own. No address and no key: the operator fails a descriptor that gives
either, and holds the service until the model is served. Resolving the preset in the agent and the sidecar,
and the capability check that tells an agent at its first call that its model cannot call tools or think,
are not built yet.

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
- **Both vendors' SDKs refuse to build a client with no credential.** OpenAI's `build()` throws, and
  `.apiKey("")` builds but throws at the first request. An address that needs no key is sent the
  placeholder bearer `none` (`ModelProviders.NoKey`), which local servers ignore.
- **An SDK's `JsonValue.toString` is a Java map's rendering, not JSON.** `AnthropicProvider` read a tool
  call's arguments with `block._input().toString`, so `{city=Berlin}` failed to parse and every Claude tool
  call reached ankka with empty arguments; only the key-gated live suite could see it. Write SDK values
  through the SDK's own `ObjectMappers.jsonMapper()`.
- **A batch's usage is the whole turn's.** `SessionMemoryEntity.append` gave it to every AI message in the
  batch, so a turn with a tool round counted its tokens once per answer. It goes with the last answer
  only; journals written before keep what they recorded.
- **Provider state is dropped at `AgentLoop.recorded` on purpose**, and an autonomous worker keeps only the
  last response's (`IterationLoop.lastState`), attached to the replayed answer whose call ids match. Never
  put it on `SessionMessage`: a session is ankka's, not a vendor's.
- **A scripted model is known by its type, never its name.** `IterationLoop` fails a task fast when a
  `TestModelProvider` runs out; it checked `model.name == "test"`, which a scripted provider named `openai`
  for a multi-provider test would have turned into an outage retried for ever.
- **A provider's failure says its name, never the SDK's.** `ModelCallFailed` is built from the name, the
  status and a body summary; the SDK's exception text names the vendor (`OpenAIIoException`) and a provider
  the service called `local` must not report itself as `openai`.
- **`ANKKA_MODEL_NAME` and `ANKKA_MODEL_ANTHROPIC_MODEL` are one setting**, and an `ANKKA_MODEL_` variable
  that is no setting is refused at start: a misspelt `_URLS` must not become a provider refused for the
  wrong reason. Build those names from the prefix, never as a literal list, or `PlatformDeclarationSuite`
  fails the build.
- **The stand-in answers 418 with nothing scripted**, and holds a reasoning replay to the current turn only
  (calls since the last user message), as OpenAI does; an earlier turn's call goes back without its
  reasoning, which was never kept.
