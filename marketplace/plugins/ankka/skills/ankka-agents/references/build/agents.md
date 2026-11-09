# Agents

> Write an agent in Scala, Java, Kotlin, Python, TypeScript, Rust or Go — instructions, tools, guardrails, session memory, structured replies and compaction — and configure the model it talks to.

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
    val name                                                     = "no-secrets"
    override def checkOutput(text: String): Either[String, Unit] =
      if text.contains("sk-") then Left("a key leaked") else Right(())

  def create(context: AgentContext) = new CartAssistant

  val ask  = command("ask")(_.ask)
  val chat = stream("chat")(_.chat)
```

**Java**

```java
/** An agent whose tool reads a cart, one that refunds only once approved, and a guarded handler. */
@ComponentId("cart-helper")
public class CartHelper extends Agent {

  /** What the refund tool refunded. */
  public static final List<String> REFUNDED = new CopyOnWriteArrayList<>();

  public record CartQuery(@Describe("the cart to read") String cartId) {}

  public record Summary(String cartId, int items) {}

  public static final class Tools {
    private final ComponentClient client;

    Tools(ComponentClient client) {
      this.client = client;
    }

    @Tool(value = "cart_items", description = "How many items a cart holds")
    public int items(CartQuery query) {
      return client
          .forEventSourcedEntity(query.cartId())
          .call(ShoppingCart::getCart)
          .invoke()
          .items()
          .size();
    }

    @Tool(value = "refund", description = "Refunds a cart", requiresApproval = true)
    public String refund(CartQuery query) {
      REFUNDED.add(query.cartId());
      return "refunded " + query.cartId();
    }
  }

  @Command("ask")
  public AgentEffect<String> ask(String question) {
    return effects()
        .systemMessage("You help with carts.")
        .userMessage(question)
        .tools(new Tools(componentClient()))
        .thenReply();
  }

  @Command("summarise")
  public AgentEffect<Summary> summarise(String cartId) {
    return effects().userMessage("Summarise cart " + cartId).thenReply(Summary.class);
  }

  @Command("short")
  public AgentEffect<String> shortQuestion(String question) {
    return effects()
        .userMessage(question)
        .guardrails(Guardrail.maxInputLength(10))
        .thenReply();
  }
}
```

A handler is a method annotated `@Command` with its wire name, answering `AgentEffect<R>`; a tool is a
method annotated `@Tool` on an object the handler offers, and a lone record parameter's components are
the tool's arguments. Register the agent beside the agents module, which gives it its default model:

```java
/** The cart, the agent that helps with it and the one that answers questions about it. */
public static ServiceBuilder service(ModelProvider model) {
  return Ankka.service("shop")
      .withModule(Agents.module().withModelProvider(model))
      .register(ShoppingCart.class)
      .register(CartHelper.class)
      .register(CartAnswerer.class);
}
```

**Kotlin**

```kotlin
data class Place(@Describe("the city to forecast") val city: String, val days: Int = 1)

/** A tool class whose tool is a suspend fun taking a data class, built by its own constructor. */
class WeatherTools {
    @Tool("forecast", description = "The forecast for a city")
    suspend fun forecast(place: Place): String {
        delay(1)
        ASKED.add("${place.city}/${place.days}")
        return "${place.city}: 21C"
    }

    companion object {
        val ASKED: MutableList<String> = CopyOnWriteArrayList()
    }
}

/** An agent written as a Kotlin developer writes one: suspend handlers and the effects DSL. */
@ComponentId("forecaster")
class Forecaster : Agent() {
    @Command("ask")
    suspend fun ask(question: String): AgentEffect<String> {
        delay(1)
        return effects {
            systemMessage("You answer questions about the weather.")
                .userMessage(question)
                .tools<WeatherTools>()
                .thenReply()
        }
    }

    @Command("structured")
    fun structured(question: String): AgentEffect<Forecast> =
        effects { userMessage(question).thenReply<Forecast>() }
}
```

Kotlin uses the Java agent library: a handler and a tool may be a `suspend fun`, `effects { ... }` builds
the interaction, and `thenReply<R>()` reads a data class.

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

**Rust**

```rust
pub struct CartAssistant;

impl CartAssistant {
    fn ask(question: String, _: &Context) -> AgentEffect {
        agent::system_message(
            "You help shoppers with their carts. Use the lookup tool before answering about a cart.",
        )
        .user_message(question)
        .tools(["lookup"])
        .guardrails(["no-secrets"])
        .then_reply()
    }

    fn lookup(args: CartLookup, ctx: &Context) -> Result<String, String> {
        let cart: Cart = ctx
            .client()
            .invoke(ShoppingCart, &args.cart_id, "get-cart", ())
            .map_err(|e| e.message)?;
        if cart.items.is_empty() {
            return Ok(format!("cart {} is empty", args.cart_id));
        }
        let lines: Vec<String> = cart
            .items
            .iter()
            .map(|i| format!("{} x {}", i.quantity, i.name))
            .collect();
        Ok(lines.join(", "))
    }

    fn no_secrets(stage: Stage, text: &str, _: &Context) -> Result<(), String> {
        if stage == Stage::Output && text.contains("sk-") {
            Err("a key leaked".to_string())
        } else {
            Ok(())
        }
    }
}

impl Agent for CartAssistant {
    const COMPONENT_ID: &'static str = "assistant";

    fn handlers() -> AgentHandlers<CartAssistant> {
        AgentHandlers::new().command("ask", CartAssistant::ask)
    }

    fn tools() -> Tools<CartAssistant> {
        Tools::new().tool(
            "lookup",
            "Looks up what is in a cart by its id.",
            Schema::object().string("cartId", "the cart's id"),
            CartAssistant::lookup,
        )
    }

    fn guardrails() -> Guardrails<CartAssistant> {
        Guardrails::new().guardrail("no-secrets", CartAssistant::no_secrets)
    }
}
```

**Go**

```go
// LookupArguments are the lookup tool's.
type LookupArguments struct {
	ID string `json:"id"`
}

// lookup records under the id and answers how many things are recorded there.
func lookup(mark, label string) func(context.Context, LookupArguments) (string, error) {
	return func(ctx context.Context, in LookupArguments) (string, error) {
		if in.ID == "" {
			return "", errors.New("an id is needed")
		}
		if _, err := ankka.Invoke[string](ctx, "conformance", in.ID, "record", mark); err != nil {
			return "", err
		}
		n, err := ankka.Invoke[int](ctx, "conformance", in.ID, "count", ankka.Unit{})
		if err != nil {
			return "", err
		}
		return fmt.Sprintf("%s for %s is %d", label, in.ID, n), nil
	}
}

var noSecrets = ankka.Guardrail("no-secrets", func(_ context.Context, stage ankka.GuardrailStage, text string) (string, error) {
	if strings.Contains(text, "sk-") {
		return string(stage) + " rejected by no-secrets", nil
	}
	return "", nil
})

func describe(_ context.Context, question string) (ankka.AgentEffect, error) {
	return ankka.SystemMessage("You are helpful.").UserMessage(question).Tools("lookup").Guardrails("no-secrets").ThenReply(), nil
}

// Assistant is an agent whose tool acts through the client.
var Assistant = ankka.Agent("assistant",
	ankka.Tools(ankka.Tool("lookup", "Looks up how many things were recorded under an id.", lookup("looked-up", "count"))),
	ankka.Guardrails(noSecrets),
	ankka.Ask("ask", describe),
	ankka.AskStream("stream", describe),
	// Answered by the model the sidecar has under the name second, not its default.
	ankka.Ask("ask-second", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.AgentModel("second").SystemMessage("You are helpful.").UserMessage(question).ThenReply(), nil
	}),
)
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

**Scala**

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

**Java**

```java
/** Suggests activities for a destination, reading the traveller's preferences itself. */
@ComponentId("activity-agent")
public class ActivityAgent extends Agent {

  public record Request(String userId, String destination) {}

  @Command("consult")
  public AgentEffect<String> consult(Request request) {
    // componentClient() is inherited from Agent; no constructor plumbing needed.
    var preferences =
        componentClient()
            .forKeyValueEntity(request.userId())
            .call(Preferences::get)
            .invoke();

    return effects()
        .systemMessage(
            "You are a concise activity specialist. Suggest two activities, in one sentence.")
        .userMessage("What should I do in " + request.destination() + "?")
        // Preferences are context, not something the user said, so memory records the
        // question, not the whole assembled prompt.
        .withContext("Traveller preferences: " + preferences.summary())
        .thenReply();
  }
}
```

**Kotlin**

```kotlin
@ComponentId("activity-agent")
class ActivityAgent : Agent() {

    data class Request(val userId: String, val destination: String)

    override fun settings(): AgentSettings = AgentSettings.defaults().role(Specialist.ACTIVITY)

    @Command("consult")
    suspend fun consult(request: Request): AgentEffect<String> {
        // `componentClient()` is inherited from `Agent`; no constructor plumbing needed.
        val preferences =
            componentClient().forKeyValueEntity(request.userId).call(PreferencesEntity::get).invokeSuspending()

        return effects {
            systemMessage("You are a concise activity specialist. Suggest two activities, in one sentence.")
                .userMessage("What should I do in ${request.destination}?")
                // Preferences are context, not something the user said: memory records the
                // question, not the whole assembled prompt.
                .withContext("Traveller preferences: ${preferences.summary}")
                .thenReply()
        }
    }
}
```

**Python**

```python
class ActivityAgent(Agent):
    component_id = "activity-agent"
    role = ACTIVITY

    @command("consult")
    async def consult(self, request: ActivityRequest) -> AgentEffect[str]:
        # `client` is the agent's own; no constructor plumbing needed.
        assert self.client is not None
        preferences = await self.client.for_key_value_entity("preferences", request.userId).call("get").invoke(reply=Preferences)
        return (
            self.effects.system_message("You are a concise activity specialist. Suggest two activities, in one sentence.")
            .user_message(f"What should I do in {request.destination}?")
            # Preferences are context, not something the user said — so memory records the
            # question, not the whole assembled prompt.
            .with_context(f"Traveller preferences: {preferences.summary}")
            .then_reply()
        )
```

**TypeScript**

```ts
const ActivityRequest = s.record("ActivityRequest", { userId: s.string, destination: s.string })
export type ActivityRequest = Infer<typeof ActivityRequest>

export class ActivityAgent extends Agent {
  static readonly componentId = "activity-agent"
  static readonly role = Specialist.Activity

  static readonly handlers = {
    consult: command("consult", ActivityRequest, s.string, (a: ActivityAgent, request) => a.consult(request)),
  }

  async consult(request: ActivityRequest) {
    // `client` is inherited from `Agent`; no constructor plumbing needed.
    const preferences = await this.client.of(PreferencesEntity, request.userId).call(PreferencesEntity.handlers.get).invoke()

    return this.effects
      .systemMessage("You are a concise activity specialist. Suggest two activities, in one sentence.")
      .userMessage(`What should I do in ${request.destination}?`)
      // Preferences are context, not something the user said — so memory records the
      // question, not the whole assembled prompt.
      .withContext(`Traveller preferences: ${summary(preferences)}`)
      .thenReply()
  }
}
```

**Rust**

```rust
pub struct ActivityAgent;

impl ActivityAgent {
    fn consult(request: ActivityRequest, ctx: &Context) -> AgentEffect {
        // The client is the context's; no constructor plumbing needed.
        let preferences: Preferences =
            match ctx
                .client()
                .invoke(PreferencesEntity, &request.user_id, "get", ())
            {
                Ok(preferences) => preferences,
                Err(e) => return agent::error(e.code, e.message),
            };

        agent::system_message(
            "You are a concise activity specialist. Suggest two activities, in one sentence.",
        )
        .user_message(format!("What should I do in {}?", request.destination))
        // Preferences are context, not something the user said — so memory records the
        // question, not the whole assembled prompt.
        .context(format!("Traveller preferences: {}", preferences.summary()))
        .then_reply()
    }
}

impl Agent for ActivityAgent {
    const COMPONENT_ID: &'static str = "activity-agent";

    fn handlers() -> AgentHandlers<ActivityAgent> {
        AgentHandlers::new().command("consult", ActivityAgent::consult)
    }
}
```

**Go**

```go
// ActivityRequest is who is asking and where they are going.
type ActivityRequest struct {
	UserID      string `json:"userId"`
	Destination string `json:"destination"`
}

// ActivityAgent suggests activities, reading the traveller's preferences itself.
var ActivityAgent = ankka.Agent("activity-agent",
	// The role every message this agent writes to a session carries.
	ankka.Role(SpecialistActivity),
	ankka.Ask("consult", func(ctx context.Context, req ActivityRequest) (ankka.AgentEffect, error) {
		// The agent reads what it needs through the client; no constructor plumbing needed.
		prefs, err := ankka.Invoke[Preferences](ctx, "preferences", req.UserID, "get", ankka.Unit{})
		if err != nil {
			return ankka.AgentEffect{}, err
		}
		return ankka.SystemMessage(
			"You are a concise activity specialist. Suggest two activities, in one sentence.").
			UserMessage("What should I do in " + req.Destination + "?").
			// Preferences are context, not something the user said, so memory records the
			// question, not the whole assembled prompt.
			WithContext("Traveller preferences: " + prefs.Summary).
			ThenReply(), nil
	}),
)
```

An agent reads what it needs through `componentClient` rather than being handed it, so the code that
calls it does not have to know what each agent wants.

## Structured replies

`thenReplyAs[T]` decodes the model's reply as JSON into `T` with the `JsonValueCodec[T]` in scope. The
schema is not sent to the model; say what you want in the system message. A reply that does not decode
fails the call with an error naming the type.

**Scala**

```scala
/** Same interaction, but the reply is parsed into a `Forecast`. */
def askStructured(question: String): Effect[Forecast] =
  effects
    .systemMessage(WeatherAgent.SystemMessage + " Reply with JSON.")
    .userMessage(question)
    .thenReplyAs[Forecast]
```

**Java**

```java
/** Same interaction, but the reply is parsed into a {@code Forecast}. */
@Command("ask-structured")
public AgentEffect<Forecast> askStructured(String question) {
  return effects()
      .systemMessage(SYSTEM_MESSAGE + " Reply with JSON.")
      .userMessage(question)
      .thenReply(Forecast.class);
}
```

**Kotlin**

```kotlin
/** Same interaction, but the reply is parsed into a [Forecast]. */
@Command("ask-structured")
fun askStructured(question: String): AgentEffect<Forecast> = effects {
    systemMessage("$SYSTEM_MESSAGE Reply with JSON.").userMessage(question).thenReply<Forecast>()
}
```

**Python**

```python
@command("ask-structured")
def ask_structured(self, question: str) -> AgentEffect[Forecast]:
    """Same interaction, but the reply is parsed into a ``Forecast``."""
    return self.effects.system_message(SYSTEM_MESSAGE + " Reply with JSON.").user_message(question).then_reply_json()
```

**TypeScript**

```ts
/** Same interaction, but the reply is parsed into a `Forecast`. */
askStructured(question: string) {
  return this.effects
    .systemMessage(WeatherAgent.systemMessage + " Reply with JSON.")
    .userMessage(question)
    .thenReplyJson<Forecast>()
}
```

**Rust**

```rust
/// Same interaction, but the model is asked for a `Forecast` as JSON; whoever called the
/// agent decodes the reply into one.
fn ask_structured(question: String, _: &Context) -> AgentEffect {
    agent::system_message(format!("{} Reply with JSON.", WeatherAgent::SYSTEM_MESSAGE))
        .user_message(question)
        .json_shape(r#"{"location":"...","summary":"...","degreesCelsius":0}"#)
        .then_reply()
}
```

**Go**

```go
// Same interaction, but the reply is parsed into a Forecast.
ankka.Ask("ask-structured", func(_ context.Context, question string) (ankka.AgentEffect, error) {
	return ankka.SystemMessage(weatherSystemMessage + " Reply with JSON.").UserMessage(question).
		ThenReplyJSON("a forecast: location, summary, degreesCelsius"), nil
}),
```

Declare the codec at the top level of the file, beside the case class, not in the agent's companion:
`thenReplyAs` is called inside the agent class, which does not see its companion's givens.

**Scala**

```scala
final case class Forecast(location: String, summary: String, degreesCelsius: Int)

given JsonValueCodec[Forecast] = Codecs.make[Forecast]
given Serializer[Forecast]     = Codecs.serializer[Forecast]("forecast")
```

**Java**

```java
/** The model's reply, read as JSON into this record; the record is all that is declared. */
public record Forecast(String location, String summary, int degreesCelsius) {}
```

**Kotlin**

```kotlin
/** A structured answer. A data class needs no codec: its properties are what the model's JSON is read as. */
data class Forecast(val location: String, val summary: String, val degreesCelsius: Int)
```

**Python**

```python
@dataclass(frozen=True)
class Forecast:
    location: str
    summary: str
    degreesCelsius: int
```

**TypeScript**

```ts
export const Forecast = s.record("Forecast", { location: s.string, summary: s.string, degreesCelsius: s.int })
export type Forecast = Infer<typeof Forecast>
```

**Rust**

```rust
/// A structured answer: the model is asked for this shape, and the caller decodes it.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Forecast {
    pub location: String,
    pub summary: String,
    #[serde(rename = "degreesCelsius")]
    pub degrees_celsius: i32,
}
```

**Go**

```go
// Forecast is the reply, decoded from the model's JSON. Its fields are its codec: no separate
// declaration is needed, and the caller decodes it with Invoke[Forecast].
type Forecast struct {
	Location       string `json:"location"`
	Summary        string `json:"summary"`
	DegreesCelsius int    `json:"degreesCelsius"`
}
```

The `Serializer` is needed as well because `Forecast` is the handler's reply type, which crosses the wire
to whoever called the agent.

## Tools

A tool is declared with `FunctionTool`: a name, a description the model decides by, typed parameters
each with a description, and the function to run.

**Scala**

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

**Java**

```java
/** The tools the model may call; each method's name and description are what it decides by. */
public static final class Tools {

  @Tool(value = "get_weather", description = "Returns the weather forecast for a given city.")
  public String getWeather(
      @Describe("A location or city name.") String location,
      @Describe("Forecast date, in yyyy-MM-dd format.") Optional<String> date) {
    TOOL_CALLS.add("get_weather(" + location + "," + date.orElse("-") + ")");
    if (location.equals("Nowhere")) throw new IllegalArgumentException("unknown location");
    return location + ": 18C, sunny";
  }

  @Tool(value = "current_date", description = "Returns today's date in yyyy-MM-dd format.")
  public String currentDate() {
    TOOL_CALLS.add("current_date()");
    return "2026-09-06";
  }
}
```

**Kotlin**

```kotlin
/** What the model hands the weather tool: a location, and a date when it names one. */
data class WeatherQuery(
    @Describe("A location or city name.") val location: String,
    @Describe("Forecast date, in yyyy-MM-dd format.") val date: String? = null,
)

/** The agent's tools: each `@Tool` method is one, and its parameters are the schema the model sees. */
class WeatherTools {
    @Tool("get_weather", description = "Returns the weather forecast for a given city.")
    fun getWeather(query: WeatherQuery): String {
        TOOL_CALLS.add("get_weather(${query.location},${query.date ?: "-"})")
        if (query.location == "Nowhere") throw RuntimeException("unknown location")
        return "${query.location}: 18C, sunny"
    }

    @Tool("current_date", description = "Returns today's date in yyyy-MM-dd format.")
    fun currentDate(): String {
        TOOL_CALLS.add("current_date()")
        return "2026-09-06"
    }

    companion object {
        /** Records what the tools were asked, so a test can assert the model's arguments arrived. */
        val TOOL_CALLS: Queue<String> = ConcurrentLinkedQueue()
    }
}
```

**Python**

```python
@dataclass(frozen=True)
class WeatherLookup:
    """The model's arguments: a location or city name, and a forecast date in yyyy-MM-dd format."""

    location: str
    date: str | None = None


def _get_weather(agent: Agent, arguments: WeatherLookup) -> str:
    tool_calls.append(f"get_weather({arguments.location},{arguments.date or '-'})")
    if arguments.location == "Nowhere":
        raise RuntimeError("unknown location")
    return f"{arguments.location}: 18C, sunny"


def _current_date(agent: Agent) -> str:
    return date.today().isoformat()


class WeatherAgent(Agent):
    component_id = "weather-agent"
    tools = {
        "get_weather": Tool("Returns the weather forecast for a given city.", _get_weather, WeatherLookup),
        "current_date": Tool("Returns today's date in yyyy-MM-dd format.", _current_date),
    }
```

**TypeScript**

```ts
const GetWeather = s.record("GetWeather", { location: s.string, date: s.option(s.string) })
const NoArguments = s.record("NoArguments", {})

export class WeatherAgent extends Agent {
  static readonly componentId = "weather"
  static readonly systemMessage = "You are a weather assistant. Use your tools, then answer in one sentence."

  static readonly tools = {
    // The model sees the description and the input's JSON Schema: `location` required, `date` not.
    getWeather: tool(
      "get_weather",
      "Returns the weather forecast for a given city. Takes a location or city name, and a date in yyyy-MM-dd format.",
      GetWeather,
      (_a: WeatherAgent, { location, date }) => {
        toolCalls.push(`get_weather(${location},${date ?? "-"})`)
        if (location === "Nowhere") throw new Error("unknown location")
        return `${location}: 18C, sunny`
      },
    ),
    currentDate: tool("current_date", "Returns today's date in yyyy-MM-dd format.", NoArguments, () => {
      toolCalls.push("current_date()")
      return "2026-09-06"
    }),
  }
```

**Rust**

```rust
fn tools() -> Tools<WeatherAgent> {
    Tools::new()
        .tool(
            "get_weather",
            "Returns the weather forecast for a given city.",
            Schema::object()
                .string("location", "A location or city name.")
                .optional_string("date", "Forecast date, in yyyy-MM-dd format."),
            WeatherAgent::get_weather,
        )
        .tool(
            "current_date",
            "Returns today's date in yyyy-MM-dd format.",
            Schema::object(),
            WeatherAgent::current_date,
        )
}
```

**Go**

```go
// WeatherArguments are the get_weather tool's; `desc` is what the model reads.
type WeatherArguments struct {
	Location string  `json:"location" desc:"A location or city name."`
	Date     *string `json:"date" desc:"Forecast date, in yyyy-MM-dd format."`
}

var getWeather = ankka.Tool("get_weather", "Returns the weather forecast for a given city.",
	func(_ context.Context, in WeatherArguments) (string, error) {
		if in.Location == "Nowhere" {
			return "", errors.New("unknown location")
		}
		return in.Location + ": 18C, sunny", nil
	})

var currentDate = ankka.Tool("current_date", "Returns today's date in yyyy-MM-dd format.",
	func(_ context.Context, _ struct{}) (string, error) {
		return "2026-09-06", nil
	})
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

**Scala**

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

**Java**

```java
@Tool(
    value = "issue_refund",
    description = "Refunds an order. A supervisor approves every refund before it is made.",
    requiresApproval = true)
public String issueRefund(
    @Describe("The order to refund.") String order,
    @Describe("The amount to refund.") int amount) {
  RUNS.add("issue_refund(" + order + "," + amount + ")");
  return "refunded " + amount + " on " + order;
}
```

**Kotlin**

```kotlin
data class Refund(@Describe("The order to refund.") val order: String, @Describe("The amount to refund.") val amount: Int)

@Tool(
    "issue_refund",
    description = "Refunds an order. A supervisor approves every refund before it is made.",
    requiresApproval = true,
)
fun issueRefund(refund: Refund): String {
    RUNS.add("issue_refund(${refund.order},${refund.amount})")
    return "refunded ${refund.amount} on ${refund.order}"
}
```

**Python**

```python
@dataclass(frozen=True)
class RefundRequest:
    """The order to refund, and the amount."""

    order: str
    amount: int


def _issue_refund(agent: Agent, arguments: RefundRequest) -> str:
    runs.append(f"issue_refund({arguments.order},{arguments.amount})")
    return f"refunded {arguments.amount} on {arguments.order}"


issue_refund = Tool(
    "Refunds an order. A supervisor approves every refund before it is made.", _issue_refund, RefundRequest, approval=True
)
```

**TypeScript**

```ts
issueRefund: tool(
  "issue_refund",
  "Refunds an order. A supervisor approves every refund before it is made.",
  RefundOrder,
  (_a: ApprovalAgent, { order, amount }) => {
    runs.push(`issue_refund(${order},${amount})`)
    return `refunded ${amount} on ${order}`
  },
  { approval: true },
),
```

**Go**

```go
var issueRefund = ankka.ApprovedTool("issue_refund",
	"Refunds an order. A supervisor approves every refund before it is made.", 0,
	func(_ context.Context, in RefundArguments) (string, error) {
		return fmt.Sprintf("refunded %d on %s", in.Amount, in.Order), nil
	})
```

A time limit makes the platform decide for the person: when it passes with no decision, the request is
refused, as `ankka` with a note saying it expired. A service with a time limit on any tool registers a
`TimerRuntime`, or it does not start.

**Scala**

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

**Java**

```java
@Tool(
    value = "close_account",
    description = "Closes a customer's account.",
    requiresApproval = true,
    approvalWithin = "PT2S")
public String closeAccount(@Describe("The customer whose account to close.") String customer) {
  RUNS.add("close_account(" + customer + ")");
  return "closed " + customer;
}
```

**Kotlin**

```kotlin
@Tool("close_account", description = "Closes a customer's account.", requiresApproval = true, approvalWithin = "PT2S")
fun closeAccount(@Describe("The customer whose account to close.") customer: String): String {
    RUNS.add("close_account($customer)")
    return "closed $customer"
}
```

**Python**

```python
@dataclass(frozen=True)
class CloseRequest:
    """The customer whose account to close."""

    customer: str


def _close_account(agent: Agent, arguments: CloseRequest) -> str:
    runs.append(f"close_account({arguments.customer})")
    return f"closed {arguments.customer}"


close_account = Tool("Closes a customer's account.", _close_account, CloseRequest, approval=Approval(within=timedelta(seconds=2)))
```

**TypeScript**

```ts
closeAccount: tool(
  "close_account",
  "Closes a customer's account.",
  CloseAccount,
  (_a: ApprovalAgent, { customer }) => {
    runs.push(`close_account(${customer})`)
    return `closed ${customer}`
  },
  { approval: { withinMs: 2_000 } },
),
```

**Go**

```go
var closeAccount = ankka.ApprovedTool("close_account", "Closes a customer's account.",
	2*time.Second,
	func(_ context.Context, in CustomerArguments) (string, error) {
		return "closed " + in.Customer, nil
	})
```

A caller that may meet an approval asks for the turn's **outcome** rather than a value: `ask` answers
`AgentOutcome.Answered` with the model's answer, or `AgentOutcome.AwaitingApproval` with the requests.
A decision goes to the session through `decide`, naming the handler that was called; when it was the
turn's last awaited decision, the turn goes on and `decide` answers as `ask` would have.

**Scala**

```scala
val session = componentClient.forAgent(SessionId("s-42"))
val answer  = session.ask(ApprovalAgent.ask).invoke("refund order o-7") match
  case AgentOutcome.Answered(text)             => text
  case AgentOutcome.AwaitingApproval(requests) =>
    // In a real service the requests are shown to a person, and the decision arrives later,
    // from whatever route they make it through; that route's ACL is who may decide.
    val decision = Decision.approved(requests.head.id, by = "dana")
    session.decide(ApprovalAgent.ask)(decision) match
      case AgentOutcome.Answered(text)         => text
      case AgentOutcome.AwaitingApproval(more) => s"still waiting on ${more.size}"
```

**Java**

```java
public static String refund(ComponentClient client) {
  var session = AgentClients.forAgent(client, "s-42");
  return switch (session.ask(ApprovalAgent::ask).invoke("refund order o-7")) {
    case AgentOutcome.Answered<String> answered -> answered.value();
    case AgentOutcome.AwaitingApproval<String> waiting -> {
      // In a real service the requests are shown to a person, and the decision arrives later,
      // from whatever route they make it through; that route's ACL is who may decide.
      var decision = Decision.approved(waiting.requests().get(0).id(), "dana");
      yield switch (session.decide(ApprovalAgent::ask, decision)) {
        case AgentOutcome.Answered<String> answered -> answered.value();
        case AgentOutcome.AwaitingApproval<String> more ->
            "still waiting on " + more.requests().size();
      };
    }
  };
}
```

**Kotlin**

```kotlin
val session = componentClient.forAgent("s-42")
when (val outcome = session.ask(ApprovalAgent::ask).invokeSuspending("refund order o-7")) {
    is AgentOutcome.Answered -> outcome.value()
    is AgentOutcome.AwaitingApproval -> {
        // In a real service the requests are shown to a person, and the decision arrives later,
        // from whatever route they make it through; that route's ACL is who may decide.
        val decision = Decision.approved(outcome.requests().first().id(), "dana")
        when (val decided = session.decide(agentRef(ApprovalAgent::ask), decision)) {
            is AgentOutcome.Answered -> decided.value()
            is AgentOutcome.AwaitingApproval -> "still waiting on ${decided.requests().size}"
        }
    }
}
```

**Python**

```python
async def refund_with_approval(client: ComponentClient) -> str:
    session = client.for_agent("approval-agent", "s-42").call("ask")
    match await session.ask("refund order o-7"):
        case Answered(text):
            return str(text)
        case AwaitingApproval(requests):
            # In a real service the requests are shown to a person, and the decision arrives later,
            # from whatever route they make it through; that route's ACL is who may decide.
            match await session.decide(requests[0].id, approved=True, by="dana"):
                case Answered(text):
                    return str(text)
                case AwaitingApproval(more):
                    return f"still waiting on {len(more)}"
    raise AssertionError("an outcome is answered or awaiting")
```

**TypeScript**

```ts
refund: post("/{session}/refunds", s.string, s.string, async (ep: SupportEndpoint, req, order) => {
  const session = ep.client.of(ApprovalAgent, req.params.session).call(ApprovalAgent.handlers.ask)
  const outcome = await session.ask(`refund order ${order}`)
  if (outcome.kind === "answered") return outcome.value
  // In a real service the requests are shown to a person, and the decision arrives later,
  // from whatever route they make it through; that route's ACL is who may decide.
  const decided = await session.decide(outcome.requests[0]!.id, { approved: true, by: "dana" })
  return decided.kind === "answered" ? decided.value : `still waiting on ${decided.requests.length}`
}),
```

**Go**

```go
// askAndDecide asks the agent in session s-42 and, when a tool waits for a person, decides for it.
func askAndDecide(ctx context.Context) (string, error) {
	answer, err := ankka.Invoke[string](ctx, "approval-agent", "s-42", "ask", "refund order o-7")
	var waiting *ankka.ApprovalAwaitedError
	if !errors.As(err, &waiting) {
		return answer, err
	}
	// In a real service the requests are shown to a person, and the decision arrives later,
	// from whatever route they make it through; that route's ACL is who may decide.
	decision := ankka.Decision{Approved: true, By: "dana"}
	answer, err = ankka.DecideApproval[string](ctx, "approval-agent", "s-42", "ask",
		waiting.Requests[0].ID, decision)
	if errors.As(err, &waiting) {
		return fmt.Sprintf("still waiting on %d", len(waiting.Requests)), nil
	}
	return answer, err
}
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

**Scala**

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

**Python**

```python
@sse("/{session}")
async def support(self, session: str) -> AsyncIterator[str | SseEvent]:
    async for part in self.client.for_agent("approval-agent", session).call("chat").stream_parts("refund order o-7"):
        if isinstance(part, AwaitingApproval):
            yield SseEvent("approval", part.requests)
        else:
            yield part
```

**TypeScript**

```ts
events: sse("/{session}", async function* (ep: SupportEndpoint, req) {
  const parts = ep.client.of(ApprovalAgent, req.params.session).call(ApprovalAgent.handlers.chat)
  for await (const part of parts.streamParts("refund order o-7")) {
    yield typeof part === "string" ? part : sseEvent("approval", part.requests)
  }
}),
```

**Go**

```go
// The reply's text as it comes; a turn that ends waiting for a person ends with an event of its
// own name, carrying the requests, so a browser knows the stream waits rather than finished.
ankka.SSEEvents("/{session}", func(ctx context.Context, req *ankka.Request) iter.Seq2[ankka.SSEEvent, error] {
	return func(yield func(ankka.SSEEvent, error) bool) {
		session := req.Param("session")
		stream := ankka.InvokeStream(ctx, "approval-agent", session, "chat", "refund order o-7")
		for text, err := range stream {
			var waiting *ankka.ApprovalAwaitedError
			switch {
			case errors.As(err, &waiting):
				yield(ankka.SSEEvent{Name: "approval", Data: waiting.Requests}, nil)
				return
			case err != nil:
				yield(ankka.SSEEvent{}, err)
				return
			case !yield(ankka.SSEEvent{Text: text}, nil):
				return
			}
		}
	}
}),
```

## A tool that calls another service

A tool can call another ankka service through the context's `services`, as an endpoint does. The call
is made as this service: on the platform it presents this service's certificate, so the called
service's ACL admits it by name, and the call is recorded inside the tool call's span.

**Scala**

```scala
/** A support agent whose tool reads a balance from the wallet service, as its own service. */
final class WalletAgent(context: AgentContext) extends Agent:

  private val readBalance = FunctionTool
    .named("read_balance")
    .describedAs("Reads a customer's wallet balance.")
    .param[String]("customer", "The customer whose balance to read.")
    .handle(customer => context.services("wallet").getText(s"/wallet/balances/$customer"))
```

**Java**

```java
/** A support agent whose tool reads a balance from the wallet service, as its own service. */
@ComponentId("wallet-agent")
public class WalletAgent extends Agent {

  public record Customer(@Describe("The customer whose balance to read.") String customer) {}

  private final FunctionTool readBalance =
      FunctionTool.of(
          "read_balance",
          "Reads a customer's wallet balance.",
          Customer.class,
          c -> services().of("wallet").getText("/wallet/balances/" + c.customer()));

  @Command("ask")
  public AgentEffect<String> ask(String question) {
    return effects()
        .systemMessage("You answer questions about customers' wallets.")
        .userMessage(question)
        .tools(readBalance)
        .thenReply();
  }
}
```

**Kotlin**

```kotlin
/** A support agent whose tool reads a balance from the wallet service, as its own service. */
@ComponentId("wallet-agent")
class WalletAgent : Agent() {

    class Tools(private val services: ServiceClients) {
        @Tool("read_balance", description = "Reads a customer's wallet balance.")
        fun readBalance(@Describe("The customer whose balance to read.") customer: String): String =
            services.of("wallet").getText("/wallet/balances/$customer")
    }

    @Command("ask")
    fun ask(question: String): AgentEffect<String> = effects {
        systemMessage("You answer questions about wallets.").userMessage(question).tools(Tools(services())).thenReply()
    }
}
```

**Python**

```python
@dataclass(frozen=True)
class BalanceRequest:
    """The customer whose balance to read."""

    customer: str


async def _read_balance(agent: Agent, arguments: BalanceRequest) -> str:
    # The call is made as this service: the wallet service's ACL admits it by name.
    return await agent.services("wallet").get_text(f"/wallet/balances/{arguments.customer}")


class WalletAgent(Agent):
    """A support agent whose tool reads a balance from the wallet service, as its own service."""

    component_id = "wallet-agent"
    tools = {"read_balance": Tool("Reads a customer's wallet balance.", _read_balance, BalanceRequest)}

    @command("ask")
    def ask(self, question: str) -> AgentEffect[str]:
        return self.effects.system_message("You answer questions about wallets.").user_message(question).tools("read_balance").then_reply()
```

**TypeScript**

```ts
const ReadBalance = s.record("ReadBalance", { customer: s.string })

/** A support agent whose tool reads a balance from the wallet service, as its own service. */
export class WalletAgent extends Agent {
  static readonly componentId = "wallet-agent"

  static readonly tools = {
    readBalance: tool("read_balance", "Reads a customer's wallet balance.", ReadBalance, (a: WalletAgent, { customer }) =>
      a.services.service("wallet").getText(`/wallet/balances/${customer}`),
    ),
  }

  static readonly handlers = {
    ask: command("ask", s.string, s.string, (a: WalletAgent, question) =>
      a.effects.systemMessage("You answer questions about wallets.").userMessage(question).tools("read_balance").thenReply(),
    ),
  }
}
```

**Rust**

```rust
/// A support agent whose tool reads a balance from the wallet service, as its own service.
pub struct WalletAgent;

impl WalletAgent {
    fn ask(question: String, _: &Context) -> AgentEffect {
        agent::system_message("You answer questions about a customer's wallet.")
            .user_message(question)
            .tools(["read_balance"])
            .then_reply()
    }

    fn read_balance(args: Customer, ctx: &Context) -> Result<String, String> {
        let services = ctx
            .services()
            .ok_or_else(|| "a tool may call another service".to_string())?;
        services
            .service("wallet")
            .get_text(&format!("/wallet/balances/{}", args.customer))
            .map_err(|e| e.to_string())
    }
}

impl Agent for WalletAgent {
    const COMPONENT_ID: &'static str = "wallet-agent";

    fn handlers() -> AgentHandlers<WalletAgent> {
        AgentHandlers::new().command("ask", WalletAgent::ask)
    }

    fn tools() -> Tools<WalletAgent> {
        Tools::new().tool(
            "read_balance",
            "Reads a customer's wallet balance.",
            Schema::object().string("customer", "The customer whose balance to read."),
            WalletAgent::read_balance,
        )
    }
}
```

**Go**

```go
// WalletAgent is a support agent whose tool reads a balance from the wallet service, as its own
// service: the call presents this service's certificate, so wallet's ACL can admit it by name.
var WalletAgent = ankka.Agent("wallet-agent",
	ankka.Tools(ankka.Tool("read_balance", "Reads a customer's wallet balance.",
		func(ctx context.Context, in BalanceArguments) (string, error) {
			res, err := ankka.CallService(ctx, "wallet", ankka.ServiceRequest{
				Method: "GET", Path: "/wallet/balances/" + in.Customer})
			if err != nil {
				return "", err
			}
			if res.Status != 200 {
				return "", fmt.Errorf("wallet answered %d", res.Status)
			}
			return string(res.Body), nil
		})),
	ankka.Ask("ask", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.SystemMessage("You answer questions about wallets.").UserMessage(question).
			Tools("read_balance").ThenReply(), nil
	}),
)
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

**Scala**

```scala
/** Guarded, to exercise rejection before a model is called. */
def guarded(question: String): Effect[String] =
  effects
    .systemMessage(WeatherAgent.SystemMessage)
    .userMessage(question)
    .guardrails(Guardrail.maxInputLength(40))
    .thenReply()
```

**Java**

```java
/** Guarded, to exercise rejection before a model is called. */
@Command("guarded")
public AgentEffect<String> guarded(String question) {
  return effects()
      .systemMessage(SYSTEM_MESSAGE)
      .userMessage(question)
      .guardrails(Guardrail.maxInputLength(40))
      .thenReply();
}
```

**Kotlin**

```kotlin
/** Guarded, to exercise rejection before a model is called. */
@Command("guarded")
fun guarded(question: String): AgentEffect<String> = effects {
    systemMessage(SYSTEM_MESSAGE).userMessage(question).guardrails(Guardrail.maxInputLength(40)).thenReply()
}
```

**Python**

```python
@command("guarded")
def guarded(self, question: str) -> AgentEffect[str]:
    """Guarded, to exercise rejection before a model is called."""
    return self.effects.system_message(SYSTEM_MESSAGE).user_message(question).guardrails("max-input-length").then_reply()
```

**TypeScript**

```ts
// No guardrail is provided ready-made: each is a named check, declared here and named in the plan.
static readonly guardrails = {
  maxInputLength: guardrail("max-input-length", (stage, text) =>
    stage === "input" && text.length > 40 ? `the input is longer than 40 characters` : null,
  ),
  noKeys,
}

/** Guarded, to exercise rejection before a model is called. */
guarded(question: string) {
  return this.effects
    .systemMessage(WeatherAgent.systemMessage)
    .userMessage(question)
    .guardrails("max-input-length")
    .thenReply()
}
```

**Rust**

```rust
/// Guarded, to exercise rejection before a model is called.
fn guarded(question: String, _: &Context) -> AgentEffect {
    agent::system_message(WeatherAgent::SYSTEM_MESSAGE)
        .user_message(question)
        .guardrails(["max-input-length"])
        .then_reply()
}

/// Checks the input only: a question longer than forty characters is rejected before any
/// model is called.
fn max_input_length(stage: Stage, text: &str, _: &Context) -> Result<(), String> {
    if stage == Stage::Input && text.chars().count() > 40 {
        Err("the input is longer than 40 characters".to_string())
    } else {
        Ok(())
    }
}
```

**Go**

```go
// Guarded, to exercise rejection before a model is called.
ankka.Ask("guarded", func(_ context.Context, question string) (ankka.AgentEffect, error) {
	return ankka.SystemMessage(weatherSystemMessage).UserMessage(question).
		Guardrails("max-input-length", "no-keys").ThenReply(), nil
}),
```

`Guardrail.maxInputLength(n)` and `Guardrail.forbidding(name, regex)` are provided. A guardrail of your
own implements `name` and either or both of `checkInput` and `checkOutput`, returning `Left(reason)` to
reject:

**Scala**

```scala
val noKeys: Guardrail = new Guardrail:
  val name = "no-keys"
  override def checkOutput(text: String): Either[String, Unit] =
    if text.contains("sk-") then Left("the reply contains what looks like a key") else Right(())
```

**Java**

```java
/** Refuses a reply that carries what looks like a key. */
public final class NoKeys implements Guardrail {

  @Override
  public String name() {
    return "no-keys";
  }

  @Override
  public Optional<String> checkOutput(String text) {
    return text.contains("sk-")
        ? Optional.of("the reply contains what looks like a key")
        : Optional.empty();
  }
}
```

**Kotlin**

```kotlin
val noKeys: Guardrail =
    object : Guardrail {
        override fun name() = "no-keys"

        override fun checkOutput(text: String): Optional<String> =
            if (text.contains("sk-")) Optional.of("the reply contains what looks like a key") else Optional.empty()
    }
```

**Python**

```python
guardrails = {
    "max-input-length": Guardrail(lambda stage, text: "the question is too long" if stage == "input" and len(text) > 40 else None),
    "no-keys": Guardrail(lambda stage, text: "the reply contains what looks like a key" if stage == "output" and "sk-" in text else None),
}
```

**TypeScript**

```ts
export const noKeys = guardrail("no-keys", (stage, text) =>
  stage === "output" && text.includes("sk-") ? "the reply contains what looks like a key" : null,
)
```

**Rust**

```rust
/// Checks the output only: a reply that looks like it carries a key is rejected before memory
/// is written.
fn no_keys(stage: Stage, text: &str, _: &Context) -> Result<(), String> {
    if stage == Stage::Output && text.contains("sk-") {
        Err("the reply contains what looks like a key".to_string())
    } else {
        Ok(())
    }
}
```

**Go**

```go
var noKeys = ankka.Guardrail("no-keys",
	func(_ context.Context, stage ankka.GuardrailStage, text string) (string, error) {
		if stage == ankka.GuardrailOutput && strings.Contains(text, "sk-") {
			return "the reply contains what looks like a key", nil
		}
		return "", nil
	})
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

**Scala**

```scala
/** Reads only the last two messages, to exercise the memory window. */
def askWithShortMemory(question: String): Effect[String] =
  effects
    .systemMessage(WeatherAgent.SystemMessage)
    .userMessage(question)
    .memory(MemoryProvider.limitedWindow.readLast(2))
    .thenReply()
```

**Java**

```java
/** Reads only the last two messages, to exercise the memory window. */
@Command("ask-with-short-memory")
public AgentEffect<String> askWithShortMemory(String question) {
  return effects()
      .systemMessage(SYSTEM_MESSAGE)
      .userMessage(question)
      .memory(Memory.session().readLast(2))
      .thenReply();
}
```

**Kotlin**

```kotlin
/** Reads only the last two messages, to exercise the memory window. */
@Command("ask-short-memory")
fun askWithShortMemory(question: String): AgentEffect<String> = effects {
    systemMessage(SYSTEM_MESSAGE).userMessage(question).memory(Memory.session().readLast(2)).thenReply()
}
```

Every stored message carries the `role` of the agent that wrote it, which is what a `MemoryFilter`
selects on. `MemoryFilter.includeFromAgentId(role)` adds an agent; filters combine with OR;
`withoutToolResults` drops tool traffic and keeps the conversation. The
[multi-agent orchestration](multi-agent-orchestration.md) guide shows a summariser that reads only its
specialists' contributions.

## Registering agents and choosing a model

Agents are hosted by the `AgentRuntime` extension, which supplies the service's default model. Register
the agents, the session memory entity (`AgentRuntime.descriptors`), and the extension:

**Scala**

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
  // The same three steps as a blueprint too, run by the platform rather than the workflow.
  .withExtension(AgentRuntime.withDefaultModel(model).withBlueprints(PlannerBlueprint.registry))
  .withExtension(HttpServer.of(clients => PlannerEndpoint(clients.componentClient)))
  .start()
```

**Java**

```java
/** The agents and the entity they read, hosted by the agents module with its default model. */
public static ServiceBuilder service(ModelProvider model) {
  return Ankka.service("planner")
      .withModule(Agents.module().withModelProvider(model))
      .register(Preferences.class)
      .register(ActivityAgent.class)
      .register(WeatherAgent.class)
      .register(ApprovalAgent.class)
      .register(WalletAgent.class)
      .register(Triage.class)
      .withEndpoint(ApprovalRoutes.class);
}
```

**Kotlin**

```kotlin
/** The planner's service: its entity, its workflow, its agents and the agents module that hosts them. */
fun plannerService(model: ModelProvider): ServiceBuilder =
    Ankka.service("planner")
        .register(PreferencesEntity::class.java)
        .register(PlannerWorkflow::class.java)
        .register(SelectorAgent::class.java)
        .register(WeatherAgent::class.java)
        .register(ActivityAgent::class.java)
        .register(BudgetAgent::class.java)
        .register(SummaryAgent::class.java)
        // The module hosts the agents and the session memory, with the service's default model.
        .withModule(Agents.module().withModelProvider(model))

fun main() {
    plannerService(ModelProviders.anthropic("claude-opus-5")).start()
}
```

**TypeScript**

```ts
// The agents are registered as any component is. The model is the sidecar's: `ANTHROPIC_API_KEY` or
// `OPENAI_API_KEY` in the descriptor's environment configures its provider, and no key lives here.
export function plannerService() {
  return Ankka.service()
    .register(PreferencesEntity)
    .register(PlannerWorkflow)
    .register(SelectorAgent)
    .register(ForecastAgent)
    .register(ActivityAgent)
    .register(BudgetAgent)
    .register(SummaryAgent)
}

if (import.meta.main) {
  await plannerService().listen()
}
```

`AnthropicProvider.fromEnv()` reads `ANTHROPIC_API_KEY` from the environment and uses `claude-opus-5`
unless given another model name. `OpenAiProvider.fromEnv()` reads `OPENAI_API_KEY` and speaks OpenAI's
Responses API, the one its current models call tools through while they reason; its default model is
`gpt-6.1-sol`. A provider built from the environment whose key is not set still exists, and every call
to it fails `Unavailable`, naming the variable, so a service with one key and not the other starts and
refuses only the work that needs the missing one.

A service may have any number of providers. Each has a name, which logs, failures and the session's model
usage carry: `anthropic` and `openai` for the two vendors' own APIs, and whatever the service chooses for
the rest. `ModelProviders.named` builds a provider for any server speaking either protocol, from a
protocol, an address, a model and a key, or from a preset that names a tested endpoint. `AnthropicProvider`
is in `com.thinkmorestupidless.ankka.agent`, `OpenAiProvider` in its `openai` package and `ModelProviders`
in its `providers` package:

**Scala**

```scala
import com.thinkmorestupidless.ankka.agent.* // AnthropicProvider, AgentRuntime
import com.thinkmorestupidless.ankka.agent.openai.OpenAiProvider
import com.thinkmorestupidless.ankka.agent.providers.ModelProviders
```

**Java**

```java
import com.thinkmorestupidless.ankka.agent.javasdk.AgentModule;
import com.thinkmorestupidless.ankka.agent.javasdk.Agents; // the module every agent is hosted by
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProvider;
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProviders;
```

**Kotlin**

```kotlin
import com.thinkmorestupidless.ankka.agent.javasdk.Agents // the agents module
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProviders // every provider
```

**Scala**

```scala
// Both read their key from the environment. Without it a provider still exists and every call to
// it fails naming the variable, so the service starts with either key missing.
val claude = AnthropicProvider.fromEnv() // ANTHROPIC_API_KEY, named "anthropic"
val gpt    = OpenAiProvider.fromEnv()    // OPENAI_API_KEY, named "openai"

// Any server speaking either protocol, under a name the service chooses.
val local = ModelProviders.named("local", preset = Some("ollama"), model = Some("qwen3:8b"))

// One is the default; a handler names another with `effects.model(...)`.
val runtime = AgentRuntime.withDefaultModel(claude)
```

**Java**

```java
// Both read their key from the environment. Without it a provider still exists and every call to
// it fails naming the variable, so the service starts with either key missing.
public static final ModelProvider CLAUDE = ModelProviders.anthropic("claude-opus-5");
public static final ModelProvider GPT = ModelProviders.openAi("gpt-6.1-sol");

// Any server speaking either protocol, under a name the service chooses.
public static final ModelProvider LOCAL = ModelProviders.preset("local", "ollama", "qwen3:8b");

// One is the default; a handler names another with `effects().model(...)`.
public static AgentModule module() {
  return Agents.module().withModelProvider(CLAUDE);
}
```

**Kotlin**

```kotlin
// Both read their key from the environment. Without it a provider still exists and every call to
// it fails naming the variable, so the service starts with either key missing.
val claude: ModelProvider = ModelProviders.anthropic("claude-opus-5") // ANTHROPIC_API_KEY, named "anthropic"
val gpt: ModelProvider = ModelProviders.openAi("gpt-6.1-sol") // OPENAI_API_KEY, named "openai"

// Any server speaking either protocol, under a name the service chooses.
val local: ModelProvider = ModelProviders.preset("local", "ollama", "qwen3:8b")

// One is the module's default; a handler names another with `model(...)` in its effects block.
val agents: AgentModule = Agents.module().withModelProvider(claude)
```

One provider is the runtime's default; a handler names another with `effects.model(provider)`, an
autonomous agent's definition with `.model(provider)`, and a blueprint's worker by a name registered with
`BlueprintRegistry.models`:

**Scala**

```scala
final class Triage extends Agent:
  def route(question: String): Effect[String] =
    effects
      .model(gpt)
      .systemMessage("Say which team should answer.")
      .userMessage(question)
      .thenReply()

object Triage extends Agent.Companion[Triage](ComponentId("triage")):
  def create(context: AgentContext) = new Triage
  val route                         = command("route")(_.route)
```

**Java**

```java
/** Routes a question to a team, on a provider other than the service's default. */
@ComponentId("triage")
public class Triage extends Agent {

  @Command("route")
  public AgentEffect<String> route(String question) {
    return effects()
        .model(Providers.GPT)
        .systemMessage("Say which team should answer.")
        .userMessage(question)
        .thenReply();
  }
}
```

**Kotlin**

```kotlin
@ComponentId("triage")
class Triage : Agent() {
    @Command("route")
    fun route(question: String): AgentEffect<String> = effects {
        model(ModelProviderSamples.gpt).systemMessage("Say which team should answer.").userMessage(question).thenReply()
    }
}
```

**Python**

```python
class Triage(Agent):
    component_id = "triage"

    @command("route")
    def route(self, question: str) -> AgentEffect[str]:
        return self.effects.model("openai").system_message("Say which team should answer.").user_message(question).then_reply()
```

**TypeScript**

```ts
/** Answered by the provider the sidecar has under the name `openai`, not its default. */
export class Triage extends Agent {
  static readonly componentId = "triage"

  static readonly handlers = {
    route: command("route", s.string, s.string, (a: Triage, question) =>
      a.effects.model("openai").systemMessage("Say which team should answer.").userMessage(question).thenReply(),
    ),
  }
}
```

**Rust**

```rust
pub struct Triage;

impl Triage {
    fn route(question: String, _: &Context) -> AgentEffect {
        agent::system_message("Say which team should answer.")
            .user_message(question)
            // A provider the runtime's configuration knows, by its name; the runtime's default
            // when none is named.
            .model("gpt")
            .then_reply()
    }
}

impl Agent for Triage {
    const COMPONENT_ID: &'static str = "triage";

    fn handlers() -> AgentHandlers<Triage> {
        AgentHandlers::new().command("route", Triage::route)
    }
}
```

**Go**

```go
// Triage is answered by the provider the sidecar configured under the name openai, whatever the
// service's default is.
var Triage = ankka.Agent("triage",
	ankka.Ask("route", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.AgentModel("openai").
			SystemMessage("Say which team should answer.").
			UserMessage(question).
			ThenReply(), nil
	}),
)
```

A session is ankka's, not a provider's: an agent on one provider continues a conversation an agent on
another began, tool calls and results included, and the session's usage is kept per provider by name.

Each provider's defaults can be changed:

**Scala**

```scala
val model = AnthropicProvider.fromEnv(
  modelName = "claude-opus-5",
  defaults = AnthropicProvider.Defaults(maxTokens = 16000L, thinking = true, effort = Some(Effort.High))
)
```

| Setting | Default | Meaning |
|---|---|---|
| `maxTokens` | 16000 | The most the model may produce in one turn. |
| `thinking` | on | Whether the model may reason before answering. Off is the lowest effort the endpoint offers. |
| `effort` | the provider's | `Low`, `Medium`, `High`, `XHigh` or `Max`: how hard the model works, trading cost against quality. An effort above what a model offers is the highest it offers. |
| `temperature` | none | `OpenAiProvider.Defaults` only: sent where the endpoint takes one, withheld from OpenAI's own reasoning models and from Claude, which reject it. |

A setting an endpoint does not accept is withheld, never a failed call; [Model providers](../reference/model-providers.md)
says what each setting does on each protocol and each preset. `withApiKey(key)` takes the key directly,
`at(name, address, model, key)` points either protocol at any address, and `AnthropicProvider(client)` or
`OpenAiProvider(client)` takes a configured client, for a proxy, a custom timeout, Bedrock, Vertex or
Azure. Any other model is one class implementing `ModelProvider`, whose `complete` and `stream` methods
are the whole contract: the loop, tools, memory and guardrails are above it. A model running on your own
machine is a provider at a local address: see [Run a model on your own machine](../deploy/local-models.md).

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

**Scala**

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

**Java**

```java
/** The same module with compaction on: the oldest messages become a summary past 100k chars. */
public static ServiceBuilder compacting(ModelProvider model) {
  var agents = Agents.module().withModelProvider(model).withCompaction(100_000, 10);
  return Ankka.service("planner").withModule(agents).register(WeatherAgent.class);
}
```

**Kotlin**

```kotlin
/** The oldest messages of a session are summarised once its text passes 100,000 characters. */
fun compactingService(model: ModelProvider): ServiceBuilder {
    val agents = Agents.module().withModelProvider(model).withCompaction(100_000, 10)
    return Ankka.service("support").withModule(agents).register(ApprovalAgent::class.java)
}
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

**Java**

```java
/** One turn of the session {@code session}: the model's answer, its tools run on the way. */
public static String ask(ComponentClient client, String session, String question) {
  return AgentClients.forAgent(client, session).call(CartHelper::ask).invoke(question);
}
```

**Kotlin**

```kotlin
val reply = runBlocking {
    kit.componentClient().forAgent("s-1").call(Forecaster::ask).invokeSuspending("How warm is Oslo?")
}
```

**Python**

```python
answer = await client.for_agent("assistant", "session-42").call("ask").invoke("what is in cart c1?", reply=str)
```

**TypeScript**

```ts
const answer = await client.of(CartAssistant, "session-42").call(CartAssistant.handlers.ask).invoke("what is in cart c1?")
```

**Rust**

```rust
pub struct WeatherApi;

impl WeatherApi {
    /// Calls the agent for the session in the path; the reply is whole, since a module cannot
    /// stream one.
    fn ask(request: &Request, question: String) -> Result<String, HttpProblem> {
        let session = request.path("session");
        Ok(request
            .client()
            .invoke(WeatherAgent, session, "ask", question)?)
    }

    /// The structured variant: the reply is decoded as a `Forecast` on the way out.
    fn forecast(request: &Request, question: String) -> Result<Forecast, HttpProblem> {
        let reply: String = request.client().invoke(
            WeatherAgent,
            request.path("session"),
            "ask-structured",
            question,
        )?;
        ankka::serde_json::from_str(&reply)
            .map_err(|e| HttpProblem::new(502, format!("the reply is not a forecast: {e}")))
    }
}

impl Endpoint for WeatherApi {
    const ENDPOINT_ID: &'static str = "WeatherApi";
    const PREFIX: &'static str = "/weather";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<WeatherApi> {
        Routes::new()
            .post("/{session}", WeatherApi::ask)
            .post("/{session}/forecast", WeatherApi::forecast)
    }
}
```

**Go**

```go
ankka.Post("/ask/{session}", func(ctx context.Context, req *ankka.Request, question string) (string, error) {
	return ankka.Invoke[string](ctx, "assistant", req.Param("session"), "ask", question)
}),
```

Choose session ids to match conversations: one per chat, or one per workflow instance when several
agents collaborate on one task. The [multi-agent orchestration](multi-agent-orchestration.md) guide uses
the workflow's id.

## A session about a person

A conversation holds a person's own words, which no field can mark. Start a session that is about a person
with that person's [data subject](personal-data.md), and everything the session keeps (every message, tool
call and tool result) is kept encrypted under the data subject's key:

**Scala**

```scala
componentClient
  .forAgent(SessionId("support-91"), subject = Some(DataSubject("player/8c1f")))
  .call(HelperAgent.ask)
  .invoke("my card was declined")
```

**Python**

```python
await client.for_agent("helper", "support-91", subject="player/8c1f").call("ask").invoke("my card was declined", reply=str)
```

**TypeScript**

```typescript
await client.forAgent("helper", "support-91", { subject: "player/8c1f" }).call("ask").invoke("my card was declined");
```

**Rust**

```rust
let client = client.with_data_subject(DataSubject::new("player/8c1f")?);
```

Every call to an agent made through `client` names the data subject.

**Go**

```go
ctx = ankka.WithDataSubject(ctx, ankka.MustSubject("player/8c1f"))
```

Every call to an agent made with `ctx` names the data subject.

The data subject is fixed by the session's first call, and a later call naming another is refused. Once
the data subject is erased, the session's conversation reads as empty, the model is told at its next turn
that the earlier conversation was erased, and a turn in the session is answered with nothing of it
recorded. A session started with no data subject is not changed by any erasure. What a session sent to its
model provider has left the installation, and no erasure reaches it. An autonomous agent's instance and a
task take a data subject the same way; see [Agents](personal-data.md#agents) on the personal data page.

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
| `with_model(name)` | A model provider the sidecar configured, by the name the service gave it: `anthropic`, `openai`, `scripted`, or one of its own such as `local`. |
| `then_reply()` | Reply with the text. |
| `then_reply_json()` | Reply with the model's JSON, decoded by the handler's reply type. |
| `self.effects.error(message, code)` | Refuse without calling a model. |

A tool that waits for a person is declared with `approval`: `Tool(..., approval=True)` or
`Tool(..., approval=Approval(within=timedelta(minutes=30)))` in Python, `tool(..., run, { approval: true })`
or `{ approval: { withinMs: 1_800_000 } }` in TypeScript, and `ankka.ApprovedTool(name, description, within, run)` in Go, where a
`within` of zero sets no limit. A caller asks for the outcome and decides
through the same invocation:

**Scala**

```scala
// A turn that may wait: the model's answer, or the approval requests it waits on.
postBody("/approver/{session}") { (session: String, question: String) =>
  renderOutcome(agent(session).ask(Approver.ask).invoke(question))
}
// A person's decision, answered as the turn's caller would have been once the turn goes on.
postBody("/approver/{session}/decide/{id}") { (session: String, id: String, body: String) =>
  renderOutcome(agent(session).decide(Approver.ask)(decisionFrom(id, body)))
}
```

**Java**

```java
/** A turn that may wait: the model's answer, or the approval requests it waits on. */
@Post("/approver/{session}")
public Outcome ask(String session, @Body String question) {
  return render(session(session).ask(ApprovalAgent::ask).invoke(question));
}

/** A person's decision, answered as the turn's caller would have been once it goes on. */
@Post("/approver/{session}/decide/{id}")
public Outcome decide(String session, String id, @Body DecisionBody body) {
  var decision =
      body.approved()
          ? Decision.approved(id, body.by())
          : Decision.refused(id, body.by(), body.note().orElse(""));
  return render(session(session).decide(ApprovalAgent::ask, decision));
}
```

**Kotlin**

```kotlin
// A turn that may wait: the model's answer, or the approval requests it waits on.
post<String>("/approver/{session}") { question ->
    render(componentClient().forAgent(path("session")).ask(ApprovalAgent::ask).invokeSuspending(question))
}
// A person's decision, answered as the turn's caller would have been once the turn goes on.
post<DecisionBody>("/approver/{session}/decide/{id}") { body ->
    render(componentClient().forAgent(path("session")).decide(agentRef(ApprovalAgent::ask), body.on(path("id"))))
}
```

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

**Go**

```go
// A turn that may wait: the model's answer, or the approval requests it waits on.
ankka.Post("/approver/{session}", func(ctx context.Context, req *ankka.Request, question string) (string, error) {
	return outcomeOf(ankka.Invoke[string](ctx, "approver", req.Param("session"), "ask", question))
}),
// A person's decision, answered as the turn's caller would have been once the turn goes on.
ankka.Post("/approver/{session}/decide/{id}", func(ctx context.Context, req *ankka.Request, body string) (string, error) {
	d, err := decisionOf(body)
	if err != nil {
		return "", err
	}
	return outcomeOf(ankka.DecideApproval[string](ctx, "approver", req.Param("session"), "ask", req.Param("id"), d))
}),
```

`invoke` and `stream` raise (or reject with) `ApprovalAwaited` when the turn waits (in Go, `ankka.Invoke`
returns an `*ankka.ApprovalAwaitedError` carrying the requests, and `ankka.DecideApproval` goes on), and `stream_parts`
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
| `ANTHROPIC_API_KEY` | Configures `anthropic`, Anthropic's own API. |
| `ANKKA_MODEL_NAME` | The Claude model `anthropic` uses; `claude-opus-5` when unset. |
| `OPENAI_API_KEY` | Configures `openai`, OpenAI's own API. |
| `ANKKA_MODEL_<NAME>_PROTOCOL`, `_URL`, `_MODEL`, `_KEY`, `_SHAPE`, `_PRESET` | Configure a provider named `<name>` at any address, by hand or from a preset. |
| `ANKKA_MODEL_DEFAULT` | Which provider answers a plan that names none, when there is more than one. |
| `ANKKA_MODEL_SCRIPT` | A scripted model for tests: a JSON array of turns, or the path of a file holding one. See [Testing](testing.md). |

With one provider configured it is the default. With more than one, `ANKKA_MODEL_DEFAULT` names it;
without it, a plan that names no model is refused naming the variable and the providers, and a plan that
names one is answered. A configuration that cannot work — a misspelt variable, a reserved name, a
missing address or model, a preset that needs a key given none — stops the sidecar at start, naming the
variable. [Model providers](../reference/model-providers.md) lists every variable and preset.

A sidecar with no provider at all refuses agent calls, naming the variables that would configure one. Compaction is not
yet configurable for a Python or TypeScript service's sessions.

## What to read next

- [Streaming responses](streaming.md) sends tokens to the caller as the model produces them.
- [Multi-agent orchestration](multi-agent-orchestration.md) coordinates several agents from a workflow.
- [Testing](testing.md) scripts the model so a test asserts on behaviour, not on prose.
