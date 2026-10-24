---
name: ankka-agents
description: Design, write, change or test an ankka agent in Scala, Java, Kotlin, Python, TypeScript, Go or Rust — the effect for one model interaction (messages, withContext, tools, guardrails, memory, model), FunctionTool design, sessions, MemoryProvider and compaction, thenReplyAs, streaming over SSE, model providers, TestModelProvider scripts, judgments (typed questions answered by a System One model such as Jev, judged guardrails), agents coordinated from a workflow, autonomous agents (tasks with typed results, rules and budgets, instances, notifications, at-least-once tools), and blueprints (workers and steps as data the platform runs, every pattern, runs, schedules, following runs). Use when the task names an agent, a tool, a session, a guardrail, a model, a prompt, an LLM, Claude or OpenAI, a judgment, a classification, Jev or TypeSafe, multi-agent orchestration, streaming, an autonomous agent, a task, a background job for a model, a blueprint, fan-out, map-reduce or critique, a scheduled run, or reasoning.
---

# ankka agents

An agent's handler describes one interaction as an effect (instructions, the message, tools, guardrails,
which memory, which model) and returns it. The runtime runs the loop: calls the model, runs the tools it
asks for, feeds results back until it answers, applies guardrails, writes the session and counts tokens.
An agent is addressed by a **session id**, one request per session at a time. In Python the loop runs in
the sidecar, which calls back into the process only to run a tool or a guardrail. A Scala handler can
instead ask for a **judgment** — typed questions about a state answered by a System One model, with the
probabilities behind each answer — for a decision whose answer is one of a known set.

## Rules

1. **Decide whether an agent is right before writing one.** A decision that needs a model is an agent; a
   fixed sequence is a workflow whose steps call agents; a rule about one thing is an entity the agent's
   tool calls. A tool can wait for a person's approval, but an agent handler cannot run for hours. Work through
   `references/concepts/designing-agents.md` for a new agent.
2. **`withContext` for anything retrieved, `userMessage` for what the user said.** Memory records the
   user's turn only, so retrieved documents and entity state do not fill the next turn's history. An
   agent reads what it needs through `componentClient` itself rather than being handed it.
3. **Tools are declared, small and well described.** `FunctionTool.named("get_forecast")
   .describedAs(...).param[String]("destination", "...").handle { ... }`: up to three typed parameters,
   each described; the description is the interface the model chooses by. Prefer tools that read (a view,
   an entity query). A writing tool calls an entity command so the entity's rules apply, is named as an
   action, and is safe to call twice. A failing tool is an answer: the exception's message goes to the
   model, so make it actionable and never let it leak internals.
4. **Tools run after the handler returns.** Read the session id or anything from context in the handler
   and capture it in the closure; reading it inside the tool throws.
5. **Pick the memory deliberately.** `MemoryProvider.limitedWindow` (the default) reads and writes the
   whole session; `.none` for one-shot routing or classification that must not pollute a conversation;
   `.readLast(n)`, `.readOnly`, `.writeOnly`, `.filtered(MemoryFilter.includeFromAgentId(role))` to
   narrow; `.withInterceptor` to redact before writing. Every stored message carries the agent's `role`.
6. **Session ids are global to the service and name the memory.** One per chat, or one per workflow
   instance when agents collaborate; prefix by purpose (`support:user-1`) unless sharing is intended. A
   busy session queues up to 32 requests and then refuses with `Unavailable`; a stream on a busy session
   is refused at once. Independent conversations get independent ids.
7. **Guardrails see only the user message and the final answer**, not context, tool arguments or tool
   results. Input guardrails run before any model call and cost nothing; output guardrails run before
   memory is written, and on a stream *after* the text was sent. A rejection fails with `Forbidden`
   naming the guardrail; a model refusal also fails with `Forbidden`. Cheap checks first; they stop at the
   first rejection.
8. **Structured replies are asked for in the prompt.** `thenReplyAs[T]` decodes the model's JSON with a
   `JsonValueCodec[T]` declared at the top level of the file (the agent class cannot see its companion's
   givens); the schema is not sent, so describe it in the system message. The reply type also needs a
   `Serializer` because it crosses the wire to the caller. A reply that does not decode fails naming `T`.
9. **Register the runtime pieces.** `AgentRuntime.withDefaultModel(AnthropicProvider.fromEnv(), timeout)`
   (or `OpenAiProvider.fromEnv()`, or `ModelProviders.named(name, preset = ..., model = ...)` for any
   server speaking either protocol, a local one included)
   plus `AgentRuntime.descriptors` (the session memory entity); with compaction, `agents.descriptors` and
   a `ProjectionRuntime`, because the compactor is a consumer over session events. Streaming handlers are
   registered with `stream`, not `command`, and consumed with `.stream(...)`.
10. **Model choice is per interaction.** `effects.model(provider)` overrides the service default in that
    handler; there is no per-agent default. Several providers may serve one service, each under the name
    it was built with, and a session continues across them. `effort` is the lever on Claude and OpenAI
    reasoning models, not temperature; a setting an endpoint refuses is withheld. The
    model timeout (two minutes by default) applies to each round trip, so bound the whole with
    `maxToolCallSteps` (100 by default; lower it for short tasks). A caller's `invoke` waits only the
    10-second ask timeout: await agent calls with an explicit, longer timeout and match the step timeout.
11. **The loop does not retry, and cost is counted, not capped.** A failed model call fails the request
    and writes nothing; retry from a workflow step or the caller. Tokens accumulate on the session
    (`forSessionMemory(id)` → `history`); there is no budget or circuit breaker, so a spend limit is a
    tool or entity of your own. A reply cut short at `maxTokens` returns as a normal reply.
12. **Coordinate several agents from a workflow** when it costs money or takes minutes: sequential steps,
    `invokeAsync` fan-out, a memory-less selector for dynamic routing whose output the workflow
    validates, all on the workflow's id as the session, a summariser filtered to the specialists' roles.
    A person in the loop is an agent that proposes (`thenReplyAs`) and a workflow that pauses and applies
    on a command.
13. **A decision with a bounded answer is a judgment, not a text model call.** Which team, how severe,
    whether a refund is asked for: declare `Question.choice`/`score`/`yesNo` values with wire ids on the
    companion, return `effects.judgment.state(...).question(...).thenReply()` (or `thenReply(f)` to
    reply with your own type), and read answers through the questions. It reads and writes no session
    history, and one handler cannot judge and then call the text model — do that as two calls. Configure
    `AgentRuntime....withJudgments(JevProvider.fromEnv())` (`TYPESAFE_API_KEY`). A check too fuzzy for a
    pattern is a `Guardrail.judged(name).onInput(Refuse.ifYes(q, atLeast = 0.7))`, placed after the
    free deterministic guardrails; a check it could not make is `Unavailable`, never `Forbidden`. Test
    with `TestJudgmentProvider` — `expect` per judgment, `always` for a guardrail's questions.

## Streaming

`thenStream()` on a handler registered with `stream`; the client gets a `Source[String, NotUsed]`, and
`sse(template)` / `sseBody` serve it as `text/event-stream` with every `data` field a JSON-encoded
string (raw text loses a leading space and splits on newlines). Every turn streams, including text before
a tool call. Read query parameters and headers while building the `Source`, not inside it. A refusal ends
the stream with a `CommandError`.

## Python differences

Tools and guardrails are class attributes named in the effect (`tools("forecast")`); `memory(False)` is
the memory-less case; `with_model(name)` chooses among the sidecar's configured providers only, by the
name the service gave each; `then_reply_json()` decodes by the handler's reply type; compaction is not yet
configurable. Providers are configured on the sidecar through the descriptor's `env`: `ANTHROPIC_API_KEY`
(with `ANKKA_MODEL_NAME`) for `anthropic`, `OPENAI_API_KEY` for `openai`,
`ANKKA_MODEL_<NAME>_{PROTOCOL,URL,MODEL,KEY,SHAPE,PRESET}` for any other, `ANKKA_MODEL_DEFAULT` when there
are several, or `ANKKA_MODEL_SCRIPT` for tests. A bad configuration stops the sidecar at start naming the
variable. The process never holds a key.

## TypeScript differences

Tools and guardrails are static tables of `tool(name, description, InputShape, run)` and `guardrail(name,
check)`, named in the effect by wire name (`.tools("lookup")`); the tool's input shape is also the JSON
Schema the model sees, and its arguments arrive decoded. Handlers are `command(...)` or `stream(...)` entries
returning `this.effects.systemMessage(...).userMessage(q)....thenReply()`; `thenReplyJson<R>()` types the
JSON reply for the caller; `memory(false)` is the memory-less case; `withModel(name)` chooses among the
sidecar's configured providers, by name. A tool runs on a fresh agent instance bound to the session, so `this.sessionId`
and `this.client` are available in it. `AgentTestKit.of(Cls, session, new ScriptedModel().expectToolCall(...)
.expectText(...))` runs the loop in process and fails when the script runs out. The model is configured on
the sidecar as for Python; the process never holds the key.

## Rust differences

An agent implements `Agent`: `handlers()` (`AgentHandlers::new().command("ask", Self::ask)`, each returning
`agent::system_message(..).user_message(q).tools(["lookup"]).guardrails(["no-secrets"]).then_reply()`),
`tools()` (`Tools::new().tool(name, description, Schema::object().string("cartId", "the cart's id"),
Self::lookup)`, the schema written out because a Rust type carries no field descriptions, the arguments
decoded into the tool's `Args`, an `Err` a message for the model) and `guardrails()`
(`fn(Stage, &str, &Context) -> Result<(), String>`). The runtime runs the loop and holds the model's key;
a module cannot stream a reply. `AgentTestKit::<C>::new(session, ScriptedModel::new().expect_tool_call(..)
.expect_text(..))` runs a plan in process and fails when the script runs out. An autonomous agent implements
`AutonomousAgent` (`COMPONENT_ID`, `DESCRIPTION`, `accepts()` returning `TaskAcceptance::new(task_type,
max_iterations)`, and the same `Tools` and `Guardrails`); a task type is a value from a function,
`TaskType::<R>::new(name, description, Schema)` or `TaskType::text(..)`, with `.rule(name, fn(&R, &Context)
-> Verdict)`, and a tool reads its task as `ctx.task_id()`. A rule that panics traps and is checked again,
and a module keeps nothing between calls, so a rule that must remember uses an entity. The client offers
`tasks().create`, `task(id).get`/`get_as`/`wait(reads)`/`cancel` and `autonomous_agent(A).run_single_task`
or `.instance(id).assign`/`suspend`/`resume`/`terminate`/`state`; a module cannot subscribe to
notifications, and `wait` has no clock to sleep on, so it suits only a task that is nearly done.
`AutonomousAgentTestKit::<C>::new(task_id)` runs `run_tool`, `check_rule` and `check_guardrail`, with no
loop.

## Java differences

Agents live in `ankka-java-agent` (package `com.thinkmorestupidless.ankka.agent.javasdk`) and run in the
service's own JVM, as Scala's do.

- **The module is required.** `Ankka.service(...).withModule(Agents.module().withModelProvider(p))`; an
  agent registered without it is refused at start, naming the module. Judgments (`withJudgments`),
  compaction (`withCompaction`) and blueprints (`withBlueprints(context -> registry)`) are configured on
  the same module.
- **Handlers are annotated methods.** `@Command("wire-name")` answering `AgentEffect<R>`, `@Stream`
  answering `StreamEffect`; `effects()` builds the turn and `thenReply(Type.class)` reads a structured
  reply as a record. MCP servers, the role and result guardrails are `settings()`.
- **Tools are annotated methods on an object.** `@Tool(value, description)`, with `requiresApproval = true`
  and `approvalWithin = "PT10M"`; a lone record parameter's components are the arguments, each described
  with `@Describe`. A tool runs after the handler has returned, so give a tool object the clients it needs
  when you build it.
- **Calls are static on `AgentClients`.** `forAgent(client, session).call(Agent::handler).invoke(input)`,
  `.ask(...)` for an outcome that may await approval, `.decide(handler, Decision.approved(id, by))`;
  `runSingleTask`, `tasks`, `forTask`, `forAutonomousAgent`, `blueprints` and `runs` likewise.
- **Autonomous agents** extend `AutonomousAgent` with `definition()` and `tools()`; a task type's result
  is a record, and a rule is a function answering `RuleResult.accepted()` or `RuleResult.rejected(reason)`.
- **Models** are `ModelProviders.anthropic(model)`, `openAi(model)`, `preset(...)`, `at(...)` and
  `installation(model)`.
- **Testing:** `AgentTestKit.of(Agent.class, new TestModelProvider()...)` runs one turn with no runtime,
  a `TestClient` answering the tools' calls; the whole-service `AnkkaTestKit` with the scripted model as
  the module's default runs an autonomous agent's task to its end. There is no scripted judgment provider.

## Kotlin differences

Kotlin uses the Java agent library, with `ankka-kotlin-agent`'s extensions:

- A handler and a tool may be `suspend fun`s; `effects { ... }` builds the turn, `thenReply<R>()` reads a
  data class, `tools<T>()` offers a class's tools.
- A tool's lone data class parameter's properties are its arguments, described with `@Describe` on the
  constructor parameter; a task type's result is a data class.
- `componentClient().forAgent(session).call(Agent::handler).invokeSuspending(input)`;
  `agentRef(Agent::handler)` and `streamRef(...)` name a handler wherever the Java API takes a reference;
  `streamFlow` and `AutonomousRef.notificationsFlow()` are `Flow`s.
- Tests use the Java SDK's `AgentTestKit` and `TestModelProvider`.

## Go differences

An agent is `ankka.Agent(id, parts...)`: `ankka.Ask(wire, f)` and `ankka.AskStream`, each `func(ctx, I)
(ankka.AgentEffect, error)` returning `ankka.SystemMessage(..).UserMessage(q).Tools("lookup")
.Guardrails("no-secrets").ThenReply()` (`.ThenReplyJSON(hint)`, `.Memory(false)`, `.WithModel(name)`,
`.WithContext(text)`); `ankka.Tools(ankka.Tool(name, description, f))`, `f` being `func(ctx, I) (R, error)`,
its input struct the JSON Schema the model sees with a `desc` tag per field, an error told to the model and
never thrown, and `ankka.ApprovedTool` for one a person must approve; `ankka.Guardrails(ankka.Guardrail(name,
func(ctx, stage, text) (string, error)))`, where `""` passes; `ankka.McpServers`, `ankka.MaxToolCallSteps`.
A tool reads its session with `ankka.AgentFrom(ctx)` and calls components with that `ctx`. An autonomous
agent is `ankka.Autonomous(id, description, ankka.Accepts(taskType, maxIterations), ankka.Instructions(…),
ankka.Tools(…))` over `ankka.NewTaskType[R](name, description, ankka.Rule(name, check))`; the client is
`ankka.CreateTask`, `GetTask`, `WaitForTask`, `CancelTask`, `RunSingleTask` and `ankka.Instance(id,
instanceID)` (`Assign`, `Suspend`, `Resume`, `Terminate`, `State`, `Notifications`). The model is
configured on the sidecar as for Python; the process never holds the key. `testkit.Agent(Assistant,
session, testkit.NewScriptedModel().ExpectToolCall(tool, args).ExpectText(text))` runs the loop in process
(`kit.Ask(ctx, wire, input)`) and fails when the script runs out; an autonomous agent has no unit kit in
Go, so test it through `testkit.StartT`.

## Testing

Script the model with `TestModelProvider` (`expectText`, `expectToolCall`, `expectParallelToolCalls`,
`whenUserSays`) and assert on behaviour: which tools ran with what arguments, which agents ran, that they
shared a session, that a refusal reached the caller as `Forbidden`. It fails loudly when the script runs
out. Give the agent and the compactor separate providers, because the compactor is asynchronous and the
two would race for one queue. Drain a workflow before the test ends. Never assert on the model's prose;
keep an evaluation set for the real model outside the build. A scripted model answers what the test told
it to, so assert on what the platform did with the answer — the tool that ran, the state it changed, the
refusal — and ask whether the test could pass while that is broken (`references/build/testing.md`, "A
test must be able to fail").

## Mistakes to check for

- Concatenating retrieved text into `userMessage` instead of `withContext`.
- A `JsonValueCodec` for `thenReplyAs` declared in the companion, where the class cannot see it.
- Reading `sessionId` inside a tool body; a `Guardrail` whose `val name = name` names itself (null).
- An endpoint calling an agent with the default 10-second `invoke`.
- One `TestModelProvider` shared by an agent and the compactor; a test that leaves a workflow mid-flight.
- A shared session id for unrelated features, or many users on one session.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Concepts

- `references/concepts/agents.md` — How an ankka agent works — sharded by session, one request at a time, with session memory kept as an event sourced entity and the model loop, tools and guardrails run by the platform.
- `references/concepts/designing-agents.md` — Decide when an agent is the right component, design its tools, sessions, guardrails and model choice, plan for failure and cost, put a person in the loop, and combine agents with workflows and entities.
- `references/concepts/autonomous-agents.md` — How an autonomous agent works — handed a typed task, iterating on its own until it completes, fails or spends its budget, with every iteration recorded so a crash resumes where it stopped.

### Build

- `references/build/workflows.md` — Build a durable multi-step process in Scala, Java, Kotlin, Python or TypeScript — commands, steps, transitions, pauses, timeouts, retries and compensation — that resumes where it stopped after a crash.
- `references/build/agents.md` — Write an agent in Scala, Java, Kotlin, Python or TypeScript — instructions, tools, guardrails, session memory, structured replies and compaction — and configure the model it talks to.
- `references/build/judgments.md` — Ask a System One model typed questions about a state — a choice, a score, a yes or no — read typed answers with their probabilities, guard agents with them, and test them offline.
- `references/build/streaming.md` — Stream an agent's reply token by token to a caller and over HTTP as server-sent events, and know what streaming changes about guardrails and sessions.
- `references/build/multi-agent-orchestration.md` — Coordinate several agents from a workflow — sequentially, in parallel, or chosen dynamically by another agent — sharing one session, and test the coordination with a scripted model.
- `references/build/autonomous-agents.md` — Write an autonomous agent in Scala or Python — a task type with a typed result and rules, an agent that accepts it, running and reading tasks, watching an instance over server-sent events, and testing with a scripted model.
- `references/build/blueprints.md` — Describe workers and the steps between them as data the platform runs — ask, work, for-each, gather, judge, critique and call steps — register it, start runs by hand or on a schedule, follow them, and test with a scripted model.
- `references/build/mcp-servers.md` — Offer an agent the tools of MCP servers beside its own — where each server is, the credential it is sent, approval for a whole server, result guardrails that check what a server answers — and test it against a scripted server.
- `references/build/component-client.md` — Call entities, workflows and agents through the component client — blocking or asynchronous, with typed refusals and timeouts — and query views through the view client.
- `references/build/testing.md` — Test ankka components at two levels in every language, with unit test kits that run one component and nothing else, integration test kits that run the whole service against a real database, and scripted models.

### Run and deploy

- `references/deploy/local-models.md` — Point an ankka agent at a model server on your own machine — Ollama, LM Studio, llama.cpp, Docker Model Runner — with no key, from a Scala service or a sidecar, and which models call tools.

### Reference

- `references/reference/model-providers.md` — Every way a service is given a model provider — a vendor's API, any server speaking either protocol, a preset — the sidecar's variables, the default, each setting per protocol, and every preset.
