# Agents

> Write an agent in Scala or Python — instructions, tools, guardrails, session memory, structured replies and compaction — and configure the model it talks to.

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

## An agent in Scala

An agent is a class extending `Agent` whose handlers return an `Effect[R]`, and a companion that
registers them:

```scala
final class WeatherAgent extends Agent:

  def consult(destination: String): Effect[String] =
    effects
      .systemMessage(
        "You are a concise weather specialist. Use your tools, then answer in one sentence."
      )
      .userMessage(s"What is the weather like in $destination?")
      .tools(WeatherAgent.forecast)
      .thenReply()

object WeatherAgent extends Agent.Companion[WeatherAgent](ComponentId("weather-agent")):

  override val role: String = Specialist.Weather

  /** A stand-in forecast service, deterministic so the sample behaves the same each run. */
  val forecast = FunctionTool
    .named("get_forecast")
    .describedAs("Returns a short weather forecast for a destination.")
    .param[String]("destination", "The city or region to forecast.")
    .handle { destination =>
      val outlook =
        if destination.toLowerCase.contains("reykjav") then "cold and windy"
        else if destination.toLowerCase.contains("cairo") then "hot and dry"
        else "mild with occasional rain"
      s"$destination: $outlook"
    }

  def create(context: AgentContext) = new WeatherAgent

  val consult = command("consult")(_.consult)
```

The companion's `command("consult")(_.consult)` declares the handler under its wire name, as on an
entity. `role` names the agent in shared memory; it defaults to the component id. A handler that takes
no argument is declared from a method with no parameters.

Agents live in the `ankka-agent` module, and `import com.thinkmorestupidless.ankka.agent.*` brings in
`Agent`, `FunctionTool`, `MemoryProvider`, `Guardrail` and the `forAgent` method on the component client.

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

```scala
import com.thinkmorestupidless.ankka.agent.*

val answer: String =
  componentClient.forAgent(SessionId("session-42")).call(WeatherAgent.consult).invoke("Lisbon")
```

Choose session ids to match conversations: one per chat, or one per workflow instance when several
agents collaborate on one task. The [multi-agent orchestration](multi-agent-orchestration.md) guide uses
the workflow's id.

## An agent in Python

A Python agent declares its tools and guardrails as class attributes and returns an `AgentEffect` — a
plan, as data. **The loop runs in the sidecar.** The sidecar calls the model, keeps the session, and
calls back into your process to run a tool or check a guardrail, and for nothing else. The process never
calls a model and never holds the model's key.

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

A `Tool` is a description, an async or plain function taking the agent and the decoded input, and an
input dataclass whose fields are the JSON Schema the model sees. An exception from the function is fed
back to the model as a tool error. A `Guardrail` wraps a function of `(stage, text)`, where `stage` is
`"input"` or `"output"`, returning a reason to block or `None`.

The Python effect has the same shape as the Scala one:

| Python | Meaning |
|---|---|
| `system_message(text)`, `user_message(text)`, `with_context(text)` | As in Scala. |
| `tools(*names)`, `guardrails(*names)` | By their keys in the class's `tools` and `guardrails`. |
| `memory(False)` | No session memory, for a one-shot task. The default reads and writes the session. |
| `with_model(name)` | A model the sidecar configured, by name: `anthropic` or `scripted`. |
| `then_reply()` | Reply with the text. |
| `then_reply_json()` | Reply with the model's JSON, decoded by the handler's reply type. |
| `self.effects.error(message, code)` | Refuse without calling a model. |

A handler declared with `@stream` streams its reply; see [Streaming responses](streaming.md). The class
attributes `role` and `max_tool_call_steps` match the Scala companion's.

The model is configured on the sidecar, through the descriptor's environment, which the platform routes
to the sidecar container rather than to your process:

| Variable | Meaning |
|---|---|
| `ANTHROPIC_API_KEY` | Enables the `anthropic` model and makes it the default. |
| `ANKKA_MODEL_NAME` | The Claude model to use; `claude-opus-5` when unset. |
| `ANKKA_MODEL_SCRIPT` | A scripted model for tests: a JSON array of turns, or the path of a file holding one. See [Testing](testing.md). |

A sidecar with neither a key nor a script refuses agent calls, naming both variables. Compaction is not
yet configurable for a Python service's sessions.

## What to read next

- [Streaming responses](streaming.md) sends tokens to the caller as the model produces them.
- [Multi-agent orchestration](multi-agent-orchestration.md) coordinates several agents from a workflow.
- [Testing](testing.md) scripts the model so a test asserts on behaviour, not on prose.
