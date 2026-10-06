# Agents

> Write an agent in Scala, Python or TypeScript — instructions, tools, guardrails, session memory, structured replies and compaction — and configure the model it talks to.

Source: https://docs.ankka.cloud/build/agents/
An agent is a component that carries out a task by talking to a model. Its handler describes one
interaction — the instructions, the user's message, the tools the model may call, the checks to apply,
which memory to use — and returns that description as an effect. The runtime runs the loop: it calls the
model, runs the tools the model asks for, feeds their results back, repeats until the model answers,
applies the guardrails, writes the session's memory and counts the tokens.

An agent is addressed by a **session id**, not an entity id. Every call with the same session id belongs
to one conversation, and the runtime handles one request per session at a time. Two overlapping requests
to one session would otherwise read the same history, both append to it, and produce a conversation in
which neither turn acknowledges the other. Several agents can share a session, which is how agents
collaborate; see [Agents and sessions](../concepts/agents.md).

## An agent

An agent declares its instructions, its tools and its guardrails, and returns an effect describing the
interaction. It never calls a model itself: the runtime runs the loop, keeps the session, and calls back
to run a tool or check a guardrail. The shopping cart's assistant is the same agent in each language:

**Scala**

```scala
final class CartAssistant extends Agent:

  /**
   * One description, two ways of delivering it: a single reply, or a token stream.
   *
   * The tool is built *here*, inside the handler, and closes over `componentClient` as a value.
   * That is not a style choice. `componentClient` reads the session context, the loop runs a tool
   * only after this handler has returned, and by then the session context is gone — so a tool whose
   * body says `componentClient` throws "sessionContext is only available inside a command handler"
   * when it is finally called. Reading it once, while still inside the handler, is what makes the
   * call work.
   */
  private def describe(question: String) =
    val client = componentClient

    val lookup = FunctionTool
      .named("lookup")
      .describedAs("Looks up what is in a cart by its id.")
      .param[String]("cartId", "The id of the cart to look up.")
      .handle { cartId =>
        val cart = client
          .forEventSourcedEntity(EntityId(cartId))
          .call(ShoppingCartEntity.getCart)
          .invoke()
        if cart.items.isEmpty then s"cart $cartId is empty"
        else cart.items.map(item => s"${item.quantity} x ${item.name}").mkString(", ")
      }

    effects
      .systemMessage(
        "You help shoppers with their carts. Use the lookup tool before answering about a cart."
      )
      .userMessage(question)
      .tools(lookup)
      .guardrails(CartAssistant.noSecrets)

  def ask(question: String): Effect[String] = describe(question).thenReply()

  def chat(question: String): StreamEffect = describe(question).thenStream()

object CartAssistant extends Agent.Companion[CartAssistant](ComponentId("assistant")):

  /**
   * Refuses a reply that looks like it is carrying an API key.
   *
   * Output only: the check runs before memory is written, so a rejected reply leaves no trace in
   * the conversation. Nothing is wrong with a shopper *asking* about a key.
   */
  val noSecrets: Guardrail = new Guardrail:
    val name = "no-secrets"
    override def checkOutput(text: String): Either[String, Unit] =
      if text.contains("sk-") then Left("a key leaked") else Right(())

  def create(context: AgentContext) = new CartAssistant

  val ask  = command("ask")(_.ask)
  val chat = stream("chat")(_.chat)
```

**Python**

```python
"""An agent that answers questions about a cart. It declares its instructions, one tool and one
guardrail; the sidecar runs the loop — the model, memory, compaction — and asks this process to run
the tool and check the guardrail. No model key lives here."""

from __future__ import annotations

from dataclasses import dataclass

from ankka.agent import Agent, Guardrail, Tool, stream
from ankka.effects.agent import AgentEffect
from ankka.event_sourced_entity import command

from examples.shopping_cart.domain import ShoppingCart


@dataclass(frozen=True)
class CartLookup:
    cartId: str


async def _lookup(agent: Agent, arguments: CartLookup) -> str:
    assert agent.client is not None
    cart = await agent.client.for_event_sourced_entity("shopping-cart", arguments.cartId).call("get-cart").invoke(reply=ShoppingCart)
    if not cart.items:
        return f"cart {arguments.cartId} is empty"
    return ", ".join(f"{i.quantity} x {i.name}" for i in cart.items)


class CartAssistant(Agent):
    component_id = "assistant"
    tools = {"lookup": Tool("Looks up what is in a cart by its id.", _lookup, CartLookup)}
    guardrails = {"no-secrets": Guardrail(lambda stage, text: "a key leaked" if stage == "output" and "sk-" in text else None)}

    def _describe(self, question: str) -> AgentEffect[str]:
        return (
            self.effects.system_message("You help shoppers with their carts. Use the lookup tool before answering about a cart.")
            .user_message(question)
            .tools("lookup")
            .guardrails("no-secrets")
            .then_reply()
        )

    @command("ask")
    def ask(self, question: str) -> AgentEffect[str]:
        return self._describe(question)

    @stream("chat")
    def chat(self, question: str) -> AgentEffect[str]:
        return self._describe(question)
```

**TypeScript**

```ts
const CartLookup = s.record("CartLookup", { cartId: s.string })

export class CartAssistant extends Agent {
  static readonly componentId = "assistant"
  static readonly role = "helps shoppers with their carts"

  static readonly tools = {
    lookup: tool("lookup", "Looks up what is in a cart by its id.", CartLookup, (a: CartAssistant, input) => a.lookup(input.cartId)),
  }

  static readonly guardrails = {
    noSecrets: guardrail("no-secrets", (stage, text) => (stage === "output" && text.includes("sk-") ? "a key leaked" : null)),
  }

  static readonly handlers = {
    ask: command("ask", s.string, s.string, (a: CartAssistant, question) => a.describe(question)),
    chat: stream("chat", s.string, (a: CartAssistant, question) => a.describe(question)),
  }

  describe(question: string) {
    return this.effects
      .systemMessage("You help shoppers with their carts. Use the lookup tool before answering about a cart.")
      .userMessage(question)
      .tools("lookup")
      .guardrails("no-secrets")
      .thenReply()
  }

  async lookup(cartId: string): Promise<string> {
    const cart = await this.client.of(ShoppingCartEntity, cartId).call(ShoppingCartEntity.handlers.getCart).invoke()
    if (cart.items.length === 0) return `cart ${cartId} is empty`
    return cart.items.map((i) => `${i.quantity} x ${i.name}`).join(", ")
  }
}
```

In Scala the class extends `Agent`, the companion registers the handlers under their wire names, and the
agent's component id identifies it. Python declares its tools and guardrails as class attributes;
TypeScript declares them as statics. **In Python and TypeScript the loop runs in the sidecar**, which
calls the model, keeps the session, and calls back into your process only to run a tool or check a
guardrail — so the process never calls a model and never holds the model's key.

A tool that calls another component must take the client while the handler is still running. The loop
runs a tool *after* the handler has returned, so a tool that reaches for per-request state when it is
finally called finds it gone.

## Describing an interaction

A handler starts from `effects` and chains what the interaction needs. Nothing runs until the handler has
returned.

| Scala | Meaning |
|---|---|
| `systemMessage(text)` | The instruction that frames the conversation. |
| `userMessage(text)` | What the user said. This is what memory records as the user's turn. |
| `withContext(text)` | Context the user did not type: an entity's state, a retrieved document. Sent to the model, but kept out of memory. |
| `tools(tool, …)` | Functions the model may call. |
| `guardrails(guardrail, …)` | Checks on the input before the model is called and on the output before it is returned. |
| `memory(provider)` | Which of the session's history to read and whether to write to it. The default reads and writes the whole session. |
| `model(provider)` | A different model for this interaction than the service default. |
| `thenReply()` | Reply with the model's text. |
| `thenReplyAs[T]` | Reply with the model's JSON, decoded into `T`. |
| `thenStream()` | Stream the reply as it is produced. See [Streaming responses](streaming.md). |
| `effects.error(message, code)` | Refuse without calling a model. |

**Use `withContext`, not string concatenation, for anything retrieved.** Memory then records the question
the user asked, not the whole assembled prompt, and the next turn's history is not filled with documents
the user never saw:

```scala
final class ActivityAgent extends Agent:

  def consult(request: ActivityAgent.Request): Effect[String] =
    // `componentClient` is inherited from `Agent`; no constructor plumbing needed.
    val preferences = componentClient
      .forKeyValueEntity(EntityId(request.userId))
      .call(PreferencesEntity.get)
      .invoke()

    effects
      .systemMessage(
        "You are a concise activity specialist. Suggest two activities, in one sentence."
      )
      .userMessage(s"What should I do in ${request.destination}?")
      // Preferences are context, not something the user said — so memory records the
      // question, not the whole assembled prompt.
      .withContext(s"Traveller preferences: ${preferences.summary}")
      .thenReply()
```

An agent reads what it needs through `componentClient` rather than being handed it, so the code that
calls it does not have to know what each agent wants.

## Structured replies

`thenReplyAs[T]` decodes the model's reply as JSON into `T` with the `JsonValueCodec[T]` in scope. The
schema is not sent to the model; say what you want in the system message. A reply that does not decode
fails the call with an error naming the type.

```scala
/** Same interaction, but the reply is parsed into a `Forecast`. */
def askStructured(question: String): Effect[Forecast] =
  effects
    .systemMessage(WeatherAgent.SystemMessage + " Reply with JSON.")
    .userMessage(question)
    .thenReplyAs[Forecast]
```

Declare the codec at the top level of the file, beside the case class, not in the agent's companion:
`thenReplyAs` is called inside the agent class, which does not see its companion's givens.

```scala
final case class Forecast(location: String, summary: String, degreesCelsius: Int)

given JsonValueCodec[Forecast] = Codecs.make[Forecast]
given Serializer[Forecast]     = Codecs.serializer[Forecast]("forecast")
```

The `Serializer` is needed as well because `Forecast` is the handler's reply type, which crosses the wire
to whoever called the agent.

## Tools

A tool is declared with `FunctionTool`: a name, a description the model decides by, typed parameters
each with a description, and the function to run.

```scala
val getWeather = FunctionTool
  .named("get_weather")
  .describedAs("Returns the weather forecast for a given city.")
  .param[String]("location", "A location or city name.")
  .param[Option[String]]("date", "Forecast date, in yyyy-MM-dd format.")
  .handle { (location, date) =>
    toolCalls.add(s"get_weather($location,${date.getOrElse("-")})"): Unit
    if location == "Nowhere" then throw RuntimeException("unknown location")
    else s"$location: 18C, sunny"
  }

val currentDate = FunctionTool
  .named("current_date")
  .describedAs("Returns today's date in yyyy-MM-dd format.")
  .handle { () =>
    toolCalls.add("current_date()"): Unit
    "2026-09-06"
  }
```

The same `SchemaType` instance produces a parameter's JSON Schema and decodes the value the model sends,
so a parameter cannot be described as one type and read as another. Mistakes in arity or types are
compile errors: the handler's parameters are the declared parameters, in order.

| Parameter type | JSON Schema |
|---|---|
| `String` | `string` |
| `Int`, `Long` | `integer` |
| `Double` | `number` |
| `Boolean` | `boolean` |
| `Option[A]` | `A`, not required |
| `List[A]` | `array` of `A` |

A tool takes up to three parameters. For more, take one parameter holding an id and look the rest up, or
split the tool. A tool returns a `String`, a number, a `Boolean`, a `Json` value, or any type with a
`JsonValueCodec`, which is rendered as JSON for the model.

**A failing tool is an answer, not an exception.** When a tool throws, or the model's arguments do not
decode, the model receives an error tool result saying why. That is what lets it recover, usually by
fixing its arguments. The loop allows 100 tool round trips per request by default; override
`maxToolCallSteps` in the companion to change it.

Tools run after the handler has returned, inside the runtime's loop. Read anything a tool needs from the
session — its id, for instance — in the handler, and capture it in the tool's closure.

## Tools that wait for a person

A tool can require **approval**: when the model calls it, the tool does not run. The agent records an
**approval request** — an id, the tool and the arguments the model proposed — and answers the caller
with it instead of an answer. The turn waits in the session until a person decides. Approved, the tool
runs once and the model is told its result; refused, the tool never runs and the model is told who
refused it and their note, then goes on.

```scala
val issueRefund: FunctionTool = FunctionTool
  .named("issue_refund")
  .describedAs("Refunds an order. A supervisor approves every refund before it is made.")
  .param[String]("order", "The order to refund.")
  .param[Int]("amount", "The amount to refund.")
  .handle { (order, amount) =>
    runs.add(s"issue_refund($order,$amount)"): Unit
    s"refunded $amount on $order"
  }
  .requiresApproval
```

A time limit makes the platform decide for the person: when it passes with no decision, the request is
refused, as `ankka` with a note saying it expired. A service with a time limit on any tool registers a
`TimerRuntime`, or it does not start.

```scala
val closeAccount: FunctionTool = FunctionTool
  .named("close_account")
  .describedAs("Closes a customer's account.")
  .param[String]("customer", "The customer whose account to close.")
  .handle { customer =>
    runs.add(s"close_account($customer)"): Unit
    s"closed $customer"
  }
  .requiresApproval(2.seconds)
```

A caller that may meet an approval asks for the turn's **outcome** rather than a value: `ask` answers
`AgentOutcome.Answered` with the model's answer, or `AgentOutcome.AwaitingApproval` with the requests.
A decision goes to the session through `decide`, naming the handler that was called; when it was the
turn's last awaited decision, the turn goes on and `decide` answers as `ask` would have.

```scala
val session = componentClient.forAgent(SessionId("s-42"))
val answer = session.ask(ApprovalAgent.ask).invoke("refund order o-7") match
  case AgentOutcome.Answered(text)             => text
  case AgentOutcome.AwaitingApproval(requests) =>
    // In a real service the requests are shown to a person, and the decision arrives later,
    // from whatever route they make it through; that route's ACL is who may decide.
    val decision = Decision.approved(requests.head.id, by = "dana")
    session.decide(ApprovalAgent.ask)(decision) match
      case AgentOutcome.Answered(text)         => text
      case AgentOutcome.AwaitingApproval(more) => s"still waiting on ${more.size}"
```

The rules a decision follows:

- **Who decided is required.** A decision names who made it (`by`), and a decision that names nobody is
  refused with `BadRequest` and decides nothing. The name is recorded and shown; it is not what
  authorizes the decision. Who *may* decide is the ACL of the route the decision is sent through.
- **A request is decided once.** A second decision is `Conflict`, whether the first approved or refused
  it. An id the session never held is `NotFound`.
- **A session waiting for a decision takes no new request.** A new message is `Conflict` until every
  request of the waiting turn is decided.
- **An approved tool runs at most once.** The decision is recorded before the tool runs; if the service
  stops between the two, the turn is ended and the tool is not run again.
- **The wait survives a restart.** The request is part of the session, so a decision sent after the
  service restarted, or to another node, goes on with the same turn. The turn's effect is rebuilt by
  running the handler again on the message it was given, which is safe because building an effect does
  no I/O.

`call` and `stream`, which answer only with a value, throw `ApprovalAwaited` carrying the requests when
the turn waits. `streamParts` answers a stream's text, then one `AgentPart.AwaitingApproval` last. An HTTP
endpoint can send that as a server-sent event of its own name, so a browser knows the stream ended
waiting rather than finished:

```scala
sseEvents("/support/{session}") { (session: String) =>
  client
    .forAgent(SessionId(session))
    .streamParts(ApprovalAgent.chat)("refund order o-7")
    .map {
      case AgentPart.Text(text)                 => SseEvent.text(text)
      case AgentPart.AwaitingApproval(requests) => SseEvent.json("approval", requests)
    }
}
```

## A tool that calls another service

A tool can call another ankka service through the context's `services`, as an endpoint does. The call
is made as this service: on the platform it presents this service's certificate, so the called
service's ACL admits it by name, and the call is recorded inside the tool call's span.

```scala
/** A support agent whose tool reads a balance from the wallet service, as its own service. */
final class WalletAgent(context: AgentContext) extends Agent:

  private val readBalance = FunctionTool
    .named("read_balance")
    .describedAs("Reads a customer's wallet balance.")
    .param[String]("customer", "The customer whose balance to read.")
    .handle(customer => context.services("wallet").getText(s"/wallet/balances/$customer"))
```

A refusal, or no answer, is the tool's error: the model is told it and goes on. See
[Calling other services](calling-services.md) for the client itself.

## MCP servers

An agent can list MCP servers, whose tools are offered to the model beside its own as
`mcp__<server>__<tool>`, each server with its own credential, approval and result guardrails. See
[MCP servers](mcp-servers.md).

## Guardrails

A guardrail checks the text going into the model and the text coming out. Input guardrails run before any
model is called, so a rejection there costs nothing. Output guardrails run before memory is written, so a
rejected reply leaves no trace in the conversation. A rejection fails the call with `Forbidden`, naming
the guardrail.

```scala
/** Guarded, to exercise rejection before a model is called. */
def guarded(question: String): Effect[String] =
  effects
    .systemMessage(WeatherAgent.SystemMessage)
    .userMessage(question)
    .guardrails(Guardrail.maxInputLength(40))
    .thenReply()
```

`Guardrail.maxInputLength(n)` and `Guardrail.forbidding(name, regex)` are provided. A guardrail of your
own implements `name` and either or both of `checkInput` and `checkOutput`, returning `Left(reason)` to
reject:

```scala
val noKeys: Guardrail = new Guardrail:
  val name = "no-keys"
  override def checkOutput(text: String): Either[String, Unit] =
    if text.contains("sk-") then Left("the reply contains what looks like a key") else Right(())
```

Guardrails run in the order they are declared and stop at the first rejection, so put cheap checks first.
On a streaming handler, output guardrails run after the tokens have been sent; see
[Streaming responses](streaming.md).

A check too fuzzy for a pattern — whether a message tries to override the agent's instructions, whether a
reply gives medical advice — is a judged guardrail: `Guardrail.judged(name)` with rules such as
`Refuse.ifYes(question, atLeast = 0.7)`, which asks a System One model typed questions about the same text.
It goes in the same list, after the free checks. When its provider cannot be reached the interaction does
not proceed and the caller gets `Unavailable` rather than `Forbidden`. See
[Judged guardrails](judgments.md#judged-guardrails).

## Memory

Session memory is an event-sourced entity keyed by session id. It is durable — a conversation survives a
restart — and it is shared by every agent that uses the session. `memory(provider)` decides what one
interaction reads and writes:

| Provider | Meaning |
|---|---|
| `MemoryProvider.limitedWindow` | Read and write the whole session. The default. |
| `MemoryProvider.none` | No memory at all. Right for a one-shot classification or routing decision, which a shared conversation makes worse. |
| `….readLast(n)` | Read only the most recent `n` messages. |
| `….readOnly` | Read the history, contribute nothing. |
| `….writeOnly` | Contribute to the history without being influenced by it. |
| `….filtered(filter)` | Read only some agents' messages. |
| `….withInterceptor(i)` | Rewrite messages on their way into memory — the place to redact. |

```scala
/** Reads only the last two messages, to exercise the memory window. */
def askWithShortMemory(question: String): Effect[String] =
  effects
    .systemMessage(WeatherAgent.SystemMessage)
    .userMessage(question)
    .memory(MemoryProvider.limitedWindow.readLast(2))
    .thenReply()
```

Every stored message carries the `role` of the agent that wrote it, which is what a `MemoryFilter`
selects on. `MemoryFilter.includeFromAgentId(role)` adds an agent; filters combine with OR;
`withoutToolResults` drops tool traffic and keeps the conversation. The
[multi-agent orchestration](multi-agent-orchestration.md) guide shows a summariser that reads only its
specialists' contributions.

## Registering agents and choosing a model

Agents are hosted by the `AgentRuntime` extension, which supplies the service's default model. Register
the agents, the session memory entity (`AgentRuntime.descriptors`), and the extension:

```scala
val model = AnthropicProvider.fromEnv()

val service = Ankka.service
  .register(PreferencesEntity.descriptor)
  .register(PlannerWorkflow.descriptor)
  .register(SelectorAgent.descriptor)
  .register(WeatherAgent.descriptor)
  .register(ActivityAgent.descriptor)
  .register(BudgetAgent.descriptor)
  .registerAll(AgentRuntime.descriptors)
  .register(SummaryAgent.descriptor)
  .withExtension(AgentRuntime.withDefaultModel(model))
  .withExtension(HttpServer.of(clients => PlannerEndpoint(clients.componentClient)))
  .start()
```

`AnthropicProvider.fromEnv()` reads `ANTHROPIC_API_KEY` from the environment and uses `claude-opus-5`
unless given another model name. Its defaults can be changed:

```scala
val model = AnthropicProvider.fromEnv(
  modelName = "claude-opus-5",
  defaults = AnthropicProvider.Defaults(maxTokens = 16000L, thinking = true, effort = Some(Effort.High))
)
```

| Setting | Default | Meaning |
|---|---|---|
| `maxTokens` | 16000 | The most the model may produce in one turn. |
| `thinking` | on | Adaptive thinking: the model decides how much to reason. There is no token budget to set. |
| `effort` | the provider's | `Low`, `Medium`, `High`, `XHigh` or `Max`: how hard the model works, trading cost against quality. |

Sampling parameters such as temperature are not sent to Claude models, which reject them; use `effort`.
`AnthropicProvider.withApiKey(key)` takes the key directly, and `AnthropicProvider(client)` takes a
configured Anthropic client, for a proxy, a custom timeout, Bedrock or Vertex. Any other model is one
class implementing `ModelProvider`, whose `complete` and `stream` methods are the whole contract: the
loop, tools, memory and guardrails are above it.

`AgentRuntime.withDefaultModel(model, modelTimeout)` waits two minutes for a model call by default,
because a model working through a multi-step task can legitimately take that long. An `AgentRuntime()`
with no default model requires every handler to name one with `effects.model(provider)`.

`.withJudgments(provider, timeout)` adds the service's judgment provider — a System One model that answers
typed questions rather than writing text — for judgment effects and judged guardrails; `JevProvider.fromEnv()`
is TypeSafe AI's Jev. A service that only judges needs no text model: `AgentRuntime().withJudgments(provider)`.
See [Judgments](judgments.md).

## Compaction

A long session eventually outgrows a model's context window. With compaction on, the oldest messages
are replaced by a summary once the session's text passes a size:

```scala
val agents = AgentRuntime
  .withDefaultModel(model)
  .withCompaction(CompactionSettings(maxHistoryBytes = 100_000, keepRecentMessages = 10))

Ankka.service
  .registerAll(agents.descriptors)      // session memory and the compactor
  .withExtension(agents)
  .withExtension(ProjectionRuntime())   // the compactor is a consumer
  .start()
```

| Setting | Default | Meaning |
|---|---|---|
| `maxHistoryBytes` | 100000 | Compact once the session's text exceeds this many characters. A coarse ceiling, not a token count. |
| `keepRecentMessages` | 10 | The most recent messages, left verbatim. |
| `minMessagesToCompact` | 4 | Do not summarise fewer than this many messages. |

Use `agents.descriptors`, not `AgentRuntime.descriptors`, when compaction is on: it adds the compactor.
The compactor is a consumer over session memory's events, so it runs after the turn that crossed the
limit rather than making that turn wait, and it needs a `ProjectionRuntime` registered. A second
compaction folds the earlier summary into the new one rather than accumulating summaries.

The summariser calls the model directly rather than through an agent, because an agent would append its
own turns to the history it is trying to shrink. Pass your own `Summariser` to
`withCompaction(settings, Some(summariser))` to change how summaries are written. Compaction is
best-effort: a summarisation that fails skips that compaction and moves on, rather than stalling
compaction for every session.

## Calling an agent

Agents are called through the component client, addressed by session:

**Scala**

```scala
import com.thinkmorestupidless.ankka.agent.*

val answer: String =
  componentClient.forAgent(SessionId("session-42")).call(WeatherAgent.consult).invoke("Lisbon")
```

**Python**

```python
answer = await client.for_agent("assistant", "session-42").call("ask").invoke("what is in cart c1?", reply=str)
```

**TypeScript**

```ts
const answer = await client.of(CartAssistant, "session-42").call(CartAssistant.handlers.ask).invoke("what is in cart c1?")
```

Choose session ids to match conversations: one per chat, or one per workflow instance when several
agents collaborate on one task. The [multi-agent orchestration](multi-agent-orchestration.md) guide uses
the workflow's id.

## Agents in Python and TypeScript

A `Tool` is a description, an async or plain function taking the agent and the decoded input, and an
input dataclass whose fields are the JSON Schema the model sees. An exception from the function is fed
back to the model as a tool error. A `Guardrail` wraps a function of `(stage, text)`, where `stage` is
`"input"` or `"output"`, returning a reason to block or `None`.

The effect has the same shape as the Scala one, with each SDK's spelling:

| Python and TypeScript | Meaning |
|---|---|
| `system_message(text)`, `user_message(text)`, `with_context(text)` | As in Scala. |
| `tools(*names)`, `guardrails(*names)` | By their keys in the class's `tools` and `guardrails`. |
| `memory(False)` | No session memory, for a one-shot task. The default reads and writes the session. |
| `with_model(name)` | A model the sidecar configured, by name: `anthropic` or `scripted`. |
| `then_reply()` | Reply with the text. |
| `then_reply_json()` | Reply with the model's JSON, decoded by the handler's reply type. |
| `self.effects.error(message, code)` | Refuse without calling a model. |

A tool that waits for a person is declared with `approval`: `Tool(..., approval=True)` or
`Tool(..., approval=Approval(within=timedelta(minutes=30)))` in Python, `tool(..., run, { approval: true })`
or `{ approval: { withinMs: 1_800_000 } }` in TypeScript. A caller asks for the outcome and decides
through the same invocation:

**Python**

```python
@post("/approver/{session}")
async def ask_approver(self, session: str, question: str) -> str:
    """A turn that may wait: the model's answer, or the approval requests it waits on."""
    return _render_outcome(await self._scoped().for_agent("approver", session).call("ask").ask(question))

@post("/approver/{session}/decide/{id}")
async def decide_approval(self, session: str, id: str, body: str) -> str:
    """A person's decision, answered as the turn's caller would have been once it goes on."""
    return _render_outcome(await self._scoped().for_agent("approver", session).call("ask").decide(id, **_decision(body)))
```

**TypeScript**

```ts
// A turn that may wait: the model's answer, or the approval requests it waits on.
askApprover: post("/approver/{session}", s.string, s.string, async (ep: ConformanceEndpoint, req, question) =>
  renderOutcome(await ep.client.of(Approver, req.params.session).call(Approver.handlers.ask).ask(question)),
),
// A person's decision, answered as the turn's caller would have been once it goes on.
decideApproval: post("/approver/{session}/decide/{id}", s.string, s.string, async (ep: ConformanceEndpoint, req, body) =>
  renderOutcome(await ep.client.of(Approver, req.params.session).call(Approver.handlers.ask).decide(req.params.id, decisionOf(body))),
),
```

`invoke` and `stream` raise (or reject with) `ApprovalAwaited` when the turn waits, and `stream_parts`
(`streamParts`) yields the text and then the awaited requests. An `@sse` (`sse`) route can pass those on
as a server-sent event of its own name by yielding `SseEvent("approval", requests)` in Python or
`sseEvent("approval", requests)` in TypeScript after the text: the value is sent as JSON, as the Scala
endpoint's `SseEvent.json` sends it. The rules of a decision are the same as
in Scala, and the sidecar enforces them: the process is never asked to run a tool that awaits a decision.

A handler declared with `@stream` in Python, or `stream` in TypeScript, streams its reply; see
[Streaming responses](streaming.md). The class attributes `role` and `max_tool_call_steps` match the
Scala companion's.

The model is configured on the sidecar, through the descriptor's environment, which the platform routes
to the sidecar container rather than to your process:

| Variable | Meaning |
|---|---|
| `ANTHROPIC_API_KEY` | Enables the `anthropic` model and makes it the default. |
| `ANKKA_MODEL_NAME` | The Claude model to use; `claude-opus-5` when unset. |
| `ANKKA_MODEL_SCRIPT` | A scripted model for tests: a JSON array of turns, or the path of a file holding one. See [Testing](testing.md). |

A sidecar with neither a key nor a script refuses agent calls, naming both variables. Compaction is not
yet configurable for a Python or TypeScript service's sessions.

## What to read next

- [Streaming responses](streaming.md) sends tokens to the caller as the model produces them.
- [Multi-agent orchestration](multi-agent-orchestration.md) coordinates several agents from a workflow.
- [Testing](testing.md) scripts the model so a test asserts on behaviour, not on prose.
