---
title: Designing with agents
description: Decide when an agent is the right component, design its tools, sessions, guardrails and model choice, plan for failure and cost, put a person in the loop, and combine agents with workflows and entities.
kind: concept
languages: [scala, python]
components: [agent, workflow, http-endpoint]
related: [concepts/agents.md, concepts/designing-services.md, build/agents.md, build/multi-agent-orchestration.md, build/workflows.md, build/testing.md]
---

# Designing with agents

An agent is one component among several, and the design questions around it are the same as for any
other: what it may see, what it may change, what happens when it fails, and what it costs. This page is
about those decisions. [Agents and sessions](agents.md) explains what an agent is, and
[Agents](../build/agents.md) shows how to write one.

## When an agent is the right component

An agent earns its place when the *decision* needs a model: interpreting a request in natural language,
choosing among tools by judgement, producing text or a structured answer that rules cannot produce. When
the decision is deterministic, an agent is a slow and expensive way to make it.

| The task | Use |
|---|---|
| Interpret what a person asked, choose which tools to use, answer in prose or as structured data | an agent |
| Run a fixed sequence of steps that must complete or be undone, some of which consult a model | a workflow whose steps call agents |
| Enforce a rule about one thing with an id | an entity; an agent that wants the change calls the entity's command, so the rule still applies |
| Transform text once, with no tools, no history and no checks | an agent with `MemoryProvider.none` and no tools. Calling a `ModelProvider` directly from your own code is possible, and gives up the loop, the session, the guardrails and the token accounting for nothing: an agent without memory costs nothing extra |
| Answer a question the data already answers | a view or an entity query, called from an endpoint |

Two consequences follow from the runtime's shape:

- **An agent handler cannot pause, wait for a person, or run for hours.** One handler is one model
  interaction, run to completion. Anything that must wait belongs in a workflow, which can pause and be
  resumed by a command.
- **An agent's work is not journalled step by step; a workflow's is.** An agent that calls three other
  agents from a tool pays for all three again if the process dies before it answers. Coordination that
  costs money or takes minutes belongs in a workflow, as
  [Multi-agent orchestration](../build/multi-agent-orchestration.md) shows.

## Designing tools

A tool is a function the model may call, and the tools are the whole of what an agent can do beyond
talking. Design them as an API for a capable but literal reader.

**One tool, one intention.** A tool named `get_order` that takes an order id is easy for a model to choose
correctly; a tool named `orders` that takes an operation name and a bag of optional parameters is not.
Prefer several small tools with unambiguous names over one general tool. Keep the number small: a model
chooses among tools by reading their descriptions on every turn, and each tool costs tokens and attention.
An agent with more than ten or so tools is usually two agents.

**The description is the interface.** The model decides whether to call a tool, and with what, from the
tool's description and each parameter's description. Say what the tool returns, what a parameter means
and its units or format (`"an ISO-8601 date"`, `"the cart id, as shown to the customer"`), and when the
tool is *not* the right one. A parameter that is an enumeration is best described by listing its values.

**Prefer tools that read.** A tool that answers a question from a view or an entity query cannot do harm
when the model calls it twice or with the wrong argument. A tool that changes something should be rare,
named as an action (`cancel_order`), and should call an entity command so the entity's own rules decide
whether the change is allowed. A tool never bypasses an entity to write to storage.

**A tool may be called twice.** A model may repeat a call it has already made, and the loop retries
nothing on its own, so a writing tool must be safe to repeat in the same way a consumer's reaction must
be: address an entity that refuses a duplicate, or carry a key derived from the session or the request.
A tool that starts a workflow should use an id derived from the same, so a second call addresses the
workflow already running rather than starting another.

**What a tool may call.** Anything the component client reaches: an entity, a view, a workflow, another
agent, or the outside world over HTTP. Calling another agent from a tool is a plain call with no
journal, so it is right for a quick sub-question and wrong for a long collaboration. Read what a tool
needs from the handler's context, such as the session id, in the handler and capture it in the tool's
closure: tools run after the handler has returned, when its context is gone.

**Errors are answers.** When a tool throws, the model receives the exception's message as an error tool
result and usually corrects itself. Two things follow. Write messages the model can act on
(`"no order '42'; order ids look like ORD-…"`), because that text is the model's only clue. And never
let a message carry something the model should not see: a stack trace, a connection string, another
customer's data. A tool that calls another component gets that component's refusal as a `CommandError`
whose message was written for a caller, which is usually what the model needs.

**Bound the loop.** An agent stops after `maxToolCallSteps` round trips (100 by default) with an error
naming the agent and the limit. Lower it for an agent whose task is short, so a model that is going in
circles fails fast rather than spending a hundred calls.

## Designing sessions

A session id names a conversation. Every call with the same id shares one history, and the runtime
handles one request per session at a time.

**Choose the id for the memory you want.** One session per chat gives a person a conversation that
remembers. One session per task, such as a workflow's id, gives the agents working on that task a shared
context and nothing else. One session per user across all tasks gives every task every other task's
history, which is rarely wanted and grows without bound.

**Session ids are global to the service.** All agents share one session memory, keyed only by the id.
Two features that both use the id `user-1` share a conversation whether they meant to or not. Prefix ids
by purpose (`support:user-1`, `plan:order-42`) unless sharing is the intention.

**One request at a time has a ceiling.** Requests to a busy session queue, up to 32; a further request is
refused with `Unavailable` and a message naming the session. A streaming request to a busy session is
refused immediately rather than queued, because a stream holds the session for its whole duration. A
design in which many callers address one session, such as a single shared "assistant" session for all
users, hits both. Give independent conversations independent ids.

**Sharing has a cost.** Agents that share a session read the whole history on every turn, so every
message any of them writes is paid for by all of them thereafter. Narrow what each reads with
`readLast(n)`, `filtered(...)` or `MemoryProvider.none`, as the routing agent in a plan does; see
[Memory](../build/agents.md#memory). Turn on [compaction](../build/agents.md#compaction) for any session
expected to outlive a few dozen turns, and set `maxHistoryBytes` well below the model's context window,
since it counts characters rather than tokens.

**A session is a durable entity, and nothing deletes it for you.** History is kept until something
clears it. The session memory entity is an ordinary event sourced entity, reachable in Scala with
`componentClient.forSessionMemory(sessionId)`, whose `history` query returns the messages and the
session's total token usage, and whose `clear` command empties it. A service that must forget a
conversation after a time schedules that with a timer, and a service that must never store some content
redacts it with a memory interceptor before it is written.

## What guardrails see

A guardrail is a check on text: input guardrails on the user message before the model is called, output
guardrails on the model's final answer before it is stored or returned. Both are cheap ways to refuse
plainly bad requests and plainly bad answers, and they are the right tool for length limits, forbidden
patterns and simple policy. Their scope is narrow on purpose, and designing around it matters:

- **Only the user message and the final answer are checked.** Context added with `withContext`, the
  arguments the model passes to tools and the results tools return are not. A rule about what a tool may
  be asked to do belongs in the tool, and a rule about what may be changed belongs in the entity the tool
  calls.
- **On a streaming reply, an output guardrail runs after the text has been sent.** It still fails the
  call and keeps the reply out of memory, but the reader has seen it. Anything that must never be shown
  needs an input guardrail; see [Guardrails on a stream](../build/streaming.md#guardrails-on-a-stream).
- **A rejection and a model refusal look alike to a caller.** Both fail the call with `Forbidden`: a
  guardrail with a message naming it, a model that declined with the model's reason. An endpoint maps
  both to `403`. If a caller must tell them apart, give guardrails names the caller can recognise in the
  message.

## Choosing a model

The service names a default model on the agent runtime, and any handler may name a different one with
`effects.model(provider)` for that interaction. There is no per-agent default: an agent that should
always use a cheaper or a stronger model sets it in each of its handlers. Typical splits are a fast,
cheap model for routing and classification, where the answer is a few tokens, and the strongest model
for the answer a person reads. `effort` is the lever on Claude models; sampling parameters such as
temperature are not sent to them. In a Python service the choice is limited to the models the sidecar
was configured with.

Every interaction's model call waits at most the runtime's model timeout, two minutes by default, and
that limit applies to *each* round trip, so a request that makes many tool calls can take many times
that. Set the timeout for the slowest single model call, and bound the whole with `maxToolCallSteps`.

## Failure and cost

The agent loop does not retry. A model call that fails, is rate limited or times out fails the request
with the provider's message, and nothing is written to memory for that turn. Retrying is the caller's
decision, made where the cost is known: a workflow step with `RecoverStrategy.maxRetries`, or a person
who presses the button again. Both are safe because a failed interaction leaves no trace in the session.

Timeouts compose, and the composition is a trap. A caller's `invoke` waits for the component client's
ask timeout, 10 seconds by default, while a model interaction can legitimately take minutes. An endpoint
or a step that calls an agent must await with a timeout that fits the interaction, and a workflow step
must declare a step timeout to match; see [Timeouts](../build/component-client.md#timeouts). A call that
timed out at the client may still be running in the agent, and its result will be written to the session
when it finishes.

Cost is counted, not capped. Every interaction's input, output and cache tokens are added to the
session's history, which the `history` query reports as a total, and are recorded for the console. There
is no budget, quota or circuit breaker in the runtime: a service that must limit spend counts in a tool
or an entity of its own and refuses from there. The two levers the runtime offers are `maxToolCallSteps`
and `maxTokens` per turn.

Some failures arrive as answers. A reply the model cut short at `maxTokens` is returned as a normal reply,
so an agent whose answers can be long either sets `maxTokens` generously or asks for structure that makes
truncation visible. A structured reply that does not decode fails the call naming the type, so say the
shape you want in the system message rather than hoping.

## A person in the loop

An agent cannot wait for approval, but a workflow can. The pattern is an agent that *proposes* and a
workflow that *applies*: a step asks the agent for a structured proposal with `thenReplyAs[T]`, records it
in the workflow's state, and pauses with `thenPause(after, onTimeout)`. A person reads the proposal
through a query, and an endpoint sends a command that transitions the workflow to the step that carries
it out, or to one that discards it when the timeout passes first. Nothing about the model's answer is
acted on until a command says so, and the proposal survives a restart because it is in the workflow's
journal; see [Pausing for something outside](../build/workflows.md#pausing-for-something-outside).

## Patterns for several agents

- **Orchestrator and workers.** A workflow decides the sequence; each step calls one or more agents on a
  shared session; a final agent summarises. This is the shape of
  [Multi-agent orchestration](../build/multi-agent-orchestration.md), and it is the default choice.
- **Parallel specialists.** Independent questions fan out with `invokeAsync` and are collected with one
  await each, so the step takes as long as the slowest; see
  [Parallel](../build/multi-agent-orchestration.md#parallel).
- **Dynamic routing.** A selector agent with no memory names which specialists to consult, and the
  workflow validates the names before acting on them; see
  [Dynamic](../build/multi-agent-orchestration.md#dynamic).
- **Handoff.** One agent finishes its part and another continues the same conversation. On ankka that is
  two sequential steps calling two agents on one session: the second reads what the first wrote, filtered
  if it should not see everything. There is no transfer of control inside a model call.
- **An agent as a tool.** An agent's tool may call another agent for a quick sub-question. Use a separate
  session for the sub-question unless its transcript belongs in the conversation, and keep it short: a
  tool call is not journalled and is bounded by the same timeouts as any other call.

Prefer one agent with a few well-described tools over several agents until the instructions of one agent
become contradictory, its tool list becomes long, or parts of the task want different models. Those three
are the signs that a task has more than one role in it.

## Testing an agent-based design

Script the model and assert on behaviour: which tools were called and with what, which specialists ran,
that they shared a session, and that a refusal reached the caller with the right code. A scripted model
also makes guardrails and compaction testable on demand, by scripting the answer that trips them.
[Testing agents with a scripted model](../build/testing.md#testing-agents-with-a-scripted-model) covers
the mechanics. Test the HTTP path too, including a streaming route, through the integration test kit,
because the encoding of server-sent events and the mapping of refusals to statuses are not visible from
the agent's own tests.

Whether the *prose* is good is a different question, and no test in the service answers it. Keep a small
set of real requests with expected outcomes and run them against the real model outside the build, when
the instructions or the tools change. Do not assert on a model's wording in a unit test.
