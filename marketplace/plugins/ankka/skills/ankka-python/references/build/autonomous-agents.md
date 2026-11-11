# Autonomous agents

> Write an autonomous agent in any SDK language — a task type with a typed result and rules, an agent that accepts it, running and reading tasks, watching an instance over server-sent events, and testing with a scripted model.

Source: https://docs.ankka.cloud/build/autonomous-agents/
An autonomous agent is handed a task and works it on its own until it completes the task with a typed
result, fails it, or spends its budget; every iteration is recorded, so a crash resumes where it stopped.
[Autonomous agents and tasks](../concepts/autonomous-agents.md) explains how that works and when to choose
one over a request agent or a workflow. This page builds the shopping cart's answerer: an agent that
answers questions about carts by looking them up, and cites what it looked at.

The examples are in every language. The TypeScript unit kit does not script an autonomous agent's
model; its test of one runs the loop end to end through the sidecar's scripted model.

## A task type

A task type is a kind of work: a name, a description the model reads, the shape of its result, and rules
a result must satisfy. The name is the wire name, written into every task of the type, so renaming the
code that holds it changes nothing stored. The result's shape is described to the model and decoded from
what it sends; rules run in the order they are declared, and the first to refuse a result sends its reason
back to the model.

**Scala**

```scala
/** An answer, and what the agent looked at to reach it. */
final case class Answer(answer: String, sources: List[String])

object Answer:
  given JsonValueCodec[Answer] = Codecs.make        // how the result is read and stored
  given JsonSchema[Answer]     = JsonSchema.derived // how it is described to the model

object CartTasks:
  /**
   * `"answer"` is the wire name, written into every task of this type: renaming the Scala value
   * changes nothing stored.
   */
  val answer: TaskType[Answer] = Task
    .named("answer")
    .describedAs("Answer a question about a shopping cart, citing what you looked at")
    .resultConformsTo[Answer]
    .rule("cites-sources")(answer =>
      if answer.sources.isEmpty then TaskRule.Rejected("say which tools you used in sources")
      else TaskRule.Accepted
    )
```

A case class result needs a `JsonValueCodec`, which decodes it, and a `JsonSchema`, which describes it to
the model; both derive from the same declaration. A task type declared without `resultConformsTo` has a
text result.

**Java**

```java
/** The kinds of work the cart answerer accepts. */
public final class CartTasks {

  private CartTasks() {}

  /** An answer, and what the agent looked at to reach it. */
  public record Answer(String answer, List<String> sources) {}

  /**
   * {@code "answer"} is the wire name, written into every task of this type: renaming the field
   * changes nothing stored.
   */
  public static final TaskType<Answer> ANSWER =
      Task.named("answer")
          .describedAs("Answer a question about a shopping cart, citing what you looked at")
          .resultConformsTo(Answer.class)
          .rule(
              "cites-sources",
              answer ->
                  answer.sources().isEmpty()
                      ? RuleResult.rejected("say which tools you used in sources")
                      : RuleResult.accepted());
}
```

The result is a record; its components are the schema the model is shown.

**Kotlin**

```kotlin
/** An answer, and the pages the agent read to reach it. */
data class Answer(val answer: String, val sources: List<String>)

/** `"answer"` is the wire name, written into every task of this type. */
val ANSWER: TaskType<Answer> =
    Task.named("answer")
        .describedAs("Answer a question from the guide, citing the pages you read")
        .resultConformsTo(Answer::class.java)
        .rule("cites-sources") {
            if (it.sources.isEmpty()) RuleResult.rejected("say which pages you read in sources") else RuleResult.accepted()
        }
```

The result is a data class; its properties are the schema the model is shown.

**Python**

```python
@dataclass
class Answer:
    answer: str
    sources: list[str]


def cites_sources(answer: Answer) -> Accepted | Rejected:
    return Rejected("say which tools you used in sources") if not answer.sources else Accepted()


# "answer" is the wire name, written into every task of this type.
ANSWER: TaskType[Answer] = TaskType(
    "answer",
    "Answer a question about a shopping cart, citing what you looked at",
    result=Answer,
    rules=(TaskRule("cites-sources", cites_sources),),
)
```

The result's dataclass fields are the schema the model sees, as a tool's input fields are. A task type
with no `result` has a text result.

**TypeScript**

```ts
/** An answer, and what the agent looked at to reach it. */
export const Answer = s.record("Answer", { answer: s.string, sources: s.list(s.string) })
export type Answer = Infer<typeof Answer>

/** "answer" is the wire name, written into every task of this type: renaming this constant changes nothing stored. */
export const ANSWER = taskType("answer", "Answer a question about a shopping cart, citing what you looked at", {
  result: Answer,
  rules: [
    taskRule<Answer>("cites-sources", (answer) =>
      answer.sources.length === 0 ? rejected("say which tools you used in sources") : accepted(),
    ),
  ],
})
```

**Rust**

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Answer {
    pub answer: String,
    pub sources: Vec<String>,
}

/// The one task type the answerer takes: an answer, and what it was drawn from.
pub fn answer() -> TaskType<Answer> {
    TaskType::new(
        "answer",
        "Answer a question, citing what you looked up",
        Schema::object()
            .string("answer", "the answer")
            .string_array("sources", "what the answer was drawn from"),
    )
    .rule("cites-sources", |a: &Answer, _: &Context| {
        if a.sources.is_empty() {
            Verdict::rejected("sources must not be empty")
        } else {
            Verdict::Accepted
        }
    })
    .rule("steady", steady)
}
```

**Go**

```go
// Answer is an answer, and what the agent looked at to reach it. Its fields are the schema the
// model is shown, as a tool's input fields are.
type Answer struct {
	Answer  string   `json:"answer"`
	Sources []string `json:"sources"`
}

// AnswerTask is the one kind of work the answerer accepts. "answer" is the wire name, written into
// every task of this type: renaming the Go value changes nothing stored.
var AnswerTask = ankka.NewTaskType[Answer]("answer",
	"Answer a question about a shopping cart, citing what you looked at",
	ankka.Rule("cites-sources", func(_ context.Context, a Answer) (ankka.Verdict, error) {
		if len(a.Sources) == 0 {
			return ankka.Rejected("say which tools you used in sources"), nil
		}
		return ankka.Accepted(), nil
	}),
)
```

## An agent

An autonomous agent declares what it is for, how it should behave, its tools and guardrails, and the task
types it accepts, each with an **iteration budget** — the most model calls it may spend on one task.
Every autonomous agent also has two tools of the platform's, `complete_task` and `fail_task`, which are
the only way the model ends a task; a tool of your own may not use either name.

**Scala**

```scala
/**
 * Answers questions about carts, on its own: it is handed a task and works it, iteration by
 * iteration, until it completes it with an `Answer` that cites its sources.
 *
 * Its tools read the cart entity through the component client, which the instance's context holds.
 * A tool may run more than once for one request of the model — after a crash the last recorded
 * request's tools run again — and these only read, so that costs nothing.
 */
final class CartAnswerer(context: AutonomousAgentContext) extends AutonomousAgent(context):

  private def cart(cartId: String) =
    context.componentClient
      .forEventSourcedEntity(EntityId(cartId))
      .call(ShoppingCartEntity.getCart)
      .invoke()

  override def tools: Seq[FunctionTool] = Seq(
    FunctionTool
      .named("cart_contents")
      .describedAs("Lists what is in a cart.")
      .param[String]("cartId", "The id of the cart.")
      .handle { cartId =>
        val items = cart(cartId).items
        if items.isEmpty then s"cart $cartId is empty"
        else items.map(i => s"${i.quantity} x ${i.name}").mkString(", ")
      },
    FunctionTool
      .named("cart_total")
      .describedAs("Counts the items in a cart.")
      .param[String]("cartId", "The id of the cart.")
      .handle(cartId => s"cart $cartId holds ${cart(cartId).totalQuantity} items")
  )

object CartAnswerer extends AutonomousAgent.Companion[CartAnswerer](ComponentId("cart-answerer")):
  def create(context: AutonomousAgentContext) = new CartAnswerer(context)

  def definition: AutonomousAgentDefinition =
    define
      .describedAs("Answers questions about shopping carts")
      .instructions("Look carts up rather than guessing. Be brief.")
      .guardrails(Guardrail.maxInputLength(2000))
      .capability(TaskAcceptance.of(CartTasks.answer).maxIterationsPerTask(5))
```

The definition is on the companion and the tools on the instance, which is what has the component client
a tool calls other components through. Register the agent, the runtime's own components and a
`ProjectionRuntime` — a task whose dependency fails is cancelled by a consumer, which only runs under one:

```scala
Ankka.service
  .register(CartAnswerer.descriptor)
  .registerAll(AgentRuntime.descriptors)
  .withExtension(AgentRuntime.withDefaultModel(AnthropicProvider.fromEnv()))
  .withExtension(ProjectionRuntime())
```

**Java**

```java
/**
 * Answers questions about carts, on its own: it is handed a task and works it, iteration by
 * iteration, until it completes it with an {@code Answer} that cites its sources.
 *
 * <p>Its tools read the cart entity through the component client. A tool may run more than once
 * for one request of the model — after a crash the last recorded request's tools run again — and
 * these only read, so that costs nothing.
 */
@ComponentId("cart-answerer")
public class CartAnswerer extends AutonomousAgent {

  public static final class CartTools {
    private final ComponentClient client;

    CartTools(ComponentClient client) {
      this.client = client;
    }

    private CartState cart(String cartId) {
      return client.forEventSourcedEntity(cartId).call(ShoppingCart::getCart).invoke();
    }

    @Tool(value = "cart_contents", description = "Lists what is in a cart.")
    public String contents(@Describe("The id of the cart.") String cartId) {
      var items = cart(cartId).items();
      if (items.isEmpty()) return "cart " + cartId + " is empty";
      return items.stream()
          .map(i -> i.quantity() + " x " + i.name())
          .collect(Collectors.joining(", "));
    }

    @Tool(value = "cart_total", description = "Counts the items in a cart.")
    public String total(@Describe("The id of the cart.") String cartId) {
      int total = cart(cartId).items().stream().mapToInt(LineItem::quantity).sum();
      return "cart " + cartId + " holds " + total + " items";
    }
  }

  @Override
  protected AutonomousDefinition definition() {
    return AutonomousDefinition.describedAs("Answers questions about shopping carts")
        .instructions("Look carts up rather than guessing. Be brief.")
        .guardrails(Guardrail.maxInputLength(2000))
        .accepts(CartTasks.ANSWER, 5);
  }

  @Override
  protected List<Object> tools() {
    return List.of(new CartTools(componentClient()));
  }
}
```

Register it beside the agents module, `Agents.module()`, which brings the components an autonomous agent needs.

**Kotlin**

```kotlin
/** The guide's pages, read by a suspending tool. */
class GuideTools {
    @Tool("read_page", description = "Reads one page of the guide.")
    suspend fun readPage(@Describe("The page's name.") page: String): String = "the $page page says: be brief"
}

/** Answers questions from the guide, on its own, until it completes the task with an [Answer]. */
@ComponentId("guide")
class Guide : AutonomousAgent() {
    override fun definition(): AutonomousDefinition =
        AutonomousDefinition.describedAs("Answers questions from the guide")
            .instructions("Read the guide rather than guessing. Be brief.")
            .guardrails(Guardrail.maxInputLength(2000))
            .accepts(ANSWER, 5)

    override fun tools(): List<Any> = listOf(GuideTools())
}
```

A tool may be a `suspend fun`, and a tool's lone data class parameter's properties are its arguments.

**Python**

```python
async def _cart(agent: AutonomousAgent, ref: CartRef) -> ShoppingCart:
    assert agent.client is not None
    return await agent.client.for_event_sourced_entity("shopping-cart", ref.cartId).call("get-cart").invoke(reply=ShoppingCart)


async def cart_contents(agent: AutonomousAgent, ref: CartRef) -> str:
    cart = await _cart(agent, ref)
    return ", ".join(f"{i.quantity} x {i.name}" for i in cart.items) or f"cart {ref.cartId} is empty"


async def cart_total(agent: AutonomousAgent, ref: CartRef) -> str:
    cart = await _cart(agent, ref)
    return f"cart {ref.cartId} holds {sum(i.quantity for i in cart.items)} items"


class CartAnswerer(AutonomousAgent):
    """Tools may run more than once for one request of the model — after a crash, the last recorded
    request's tools run again — and these only read, so that costs nothing."""

    component_id = "cart-answerer"
    description = "Answers questions about shopping carts"
    instructions = "Look carts up rather than guessing. Be brief."
    tools = {
        "cart_contents": Tool("Lists what is in a cart.", cart_contents, CartRef),
        "cart_total": Tool("Counts the items in a cart.", cart_total, CartRef),
    }
    accepts = [TaskAcceptance(ANSWER, max_iterations=5)]
```

Register it with `Ankka.service().register(CartAnswerer)`. The sidecar runs the loop and keeps the
records, so a projection runtime and session memory are the sidecar's concern, not the process's.

**TypeScript**

```ts
const CartRef = s.record("CartRef", { cartId: s.string })

/**
 * Answers questions about carts, on its own: it is handed a task and works it, iteration by iteration,
 * until it completes it with an `Answer` that cites its sources.
 *
 * Its tools read the cart entity through the client. A tool may run more than once for one request of
 * the model — after a crash the last recorded request's tools run again — and these only read, so that
 * costs nothing.
 */
export class CartAnswerer extends AutonomousAgent {
  static readonly componentId = "cart-answerer"
  static readonly description = "Answers questions about shopping carts"
  static readonly instructions = "Look carts up rather than guessing. Be brief."

  static readonly tools = {
    cartContents: tool("cart_contents", "Lists what is in a cart, by its id.", CartRef, async (a: CartAnswerer, ref) => {
      const { items } = await a.cart(ref.cartId)
      if (items.length === 0) return `cart ${ref.cartId} is empty`
      return items.map((i) => `${i.quantity} x ${i.name}`).join(", ")
    }),
    cartTotal: tool("cart_total", "Counts the items in a cart, by its id.", CartRef, async (a: CartAnswerer, ref) =>
      `cart ${ref.cartId} holds ${totalQuantity(await a.cart(ref.cartId))} items`,
    ),
  }
  static readonly guardrails = {
    maxInputLength: guardrail("max-input-length", (stage, text) =>
      stage === "input" && text.length > 2000 ? "too long" : null,
    ),
  }
  static readonly accepts = [taskAcceptance(ANSWER, { maxIterations: 5 })]

  cart(cartId: string) {
    return this.client.of(ShoppingCartEntity, cartId).call(ShoppingCartEntity.handlers.getCart).invoke()
  }
}
```

**Rust**

```rust
pub struct ConformanceAnswerer;

impl AutonomousAgent for ConformanceAnswerer {
    const COMPONENT_ID: &'static str = "answerer";
    const DESCRIPTION: &'static str = "Answers questions";

    fn accepts() -> Vec<TaskAcceptance> {
        vec![TaskAcceptance::new(answer(), 4)]
    }

    fn tools() -> Tools<ConformanceAnswerer> {
        Tools::new().tool(
            "lookup",
            "Looks up how many things were recorded under an id.",
            Schema::object().string("id", "the id things were recorded under"),
            ConformanceAssistant::lookup,
        )
    }

    fn guardrails() -> Guardrails<ConformanceAnswerer> {
        Guardrails::new().guardrail("no-secrets", ConformanceAssistant::no_secrets)
    }
}
```

**Go**

```go
// CartArguments name the cart a tool reads.
type CartArguments struct {
	CartID string `json:"cartId" desc:"The id of the cart."`
}

// cartOf reads the cart through the client, as the tools do. A tool may run more than once for
// one request of the model (after a crash the last recorded request's tools run again), and these
// only read, so that costs nothing.
func cartOf(ctx context.Context, cartID string) (cart.Cart, error) {
	return ankka.Invoke[cart.Cart](ctx, cart.ComponentID, cartID, "get-cart", ankka.Unit{})
}

// CartAnswerer answers questions about carts on its own: it is handed a task and works it,
// iteration by iteration, until it completes it with an Answer that cites its sources.
var CartAnswerer = ankka.Autonomous("cart-answerer", "Answers questions about shopping carts",
	ankka.Instructions("Look carts up rather than guessing. Be brief."),
	ankka.Tools(
		ankka.Tool("cart_contents", "Lists what is in a cart.",
			func(ctx context.Context, in CartArguments) (string, error) {
				c, err := cartOf(ctx, in.CartID)
				if err != nil {
					return "", err
				}
				if len(c.Items) == 0 {
					return "cart " + in.CartID + " is empty", nil
				}
				var lines []string
				for _, i := range c.Items {
					lines = append(lines, fmt.Sprintf("%d x %s", i.Quantity, i.Name))
				}
				return strings.Join(lines, ", "), nil
			}),
		ankka.Tool("cart_total", "Counts the items in a cart.",
			func(ctx context.Context, in CartArguments) (string, error) {
				c, err := cartOf(ctx, in.CartID)
				if err != nil {
					return "", err
				}
				total := 0
				for _, i := range c.Items {
					total += i.Quantity
				}
				return fmt.Sprintf("cart %s holds %d items", in.CartID, total), nil
			}),
	),
	ankka.Guardrails(maxInputLength2000),
	// The task type it accepts, and the most model calls it may spend on one task.
	ankka.Accepts(AnswerTask, 5),
)
```

**Tools may run more than once for one request of the model.** Each iteration is recorded as it goes: a
model response that was recorded is never asked for again, but a tool whose result had not been recorded
when the process stopped is run again when the task resumes. A tool that only reads, like these, costs
nothing to repeat; a tool with a side effect should check before it acts, or be idempotent.

In Scala, a definition's guardrails can include a [judged guardrail](judgments.md#judged-guardrails), which
asks a System One model typed questions instead of matching a pattern. Its input rules see a task's
instructions before any model call, and its output rules see the completed result, as JSON. A refusal has
the effect any guardrail's refusal has: refused instructions fail the task, and a refused result goes back
to the model with the reason. A check that cannot be made — the provider is down or slow — is a failed
iteration instead: it is tried again after a pause, and the task fails only after the definition's
`maxConsecutiveFailures` in a row. The tokens its judgments spend are recorded on the task's session, not in
the task's own usage. A judged guardrail with no rules is refused when the agent is registered.

## Tools that wait for a person

A tool can require approval, as a request agent's can. When the model calls it, the instance records an
approval request — an id, the tool and the model's arguments — on its own record, and the task waits:
the instance makes no model call and starts no iteration until every request of the iteration is
decided. Waiting spends none of the task's budget, and an instance with nothing else to do leaves memory
while it waits; a decision brings it back.

**Scala**

```scala
override def tools: Seq[FunctionTool] = Seq(
  FunctionTool
    .named("restart_service")
    .describedAs("Restarts a service. An operator approves every restart.")
    .param[String]("service", "the service to restart")
    .handle { (service: String) =>
      Operator.runs.add(s"restart_service($service)"): Unit
      s"restarted $service"
    }
    .requiresApproval,
  FunctionTool
    .named("drain_node")
    .describedAs("Drains a node of its work.")
    .param[String]("node", "the node to drain")
    .handle { (node: String) =>
      Operator.runs.add(s"drain_node($node)"): Unit
      s"drained $node"
    }
    .requiresApproval(2.seconds),
  FunctionTool
    .named("read_metrics")
    .describedAs("Reads a service's metrics.")
    .param[String]("service", "the service to read")
    .handle { (service: String) =>
      Operator.runs.add(s"read_metrics($service)"): Unit
      s"$service is healthy"
    }
)
```

**Java**

```java
public static final class Tools {

  @Tool(
      value = "restart_service",
      description = "Restarts a service. An operator approves every restart.",
      requiresApproval = true)
  public String restartService(@Describe("the service to restart") String service) {
    RUNS.add("restart_service(" + service + ")");
    return "restarted " + service;
  }

  @Tool(
      value = "drain_node",
      description = "Drains a node of its work.",
      requiresApproval = true,
      approvalWithin = "PT2S")
  public String drainNode(@Describe("the node to drain") String node) {
    RUNS.add("drain_node(" + node + ")");
    return "drained " + node;
  }

  @Tool(value = "read_metrics", description = "Reads a service's metrics.")
  public String readMetrics(@Describe("the service to read") String service) {
    RUNS.add("read_metrics(" + service + ")");
    return service + " is healthy";
  }
}

@Override
protected List<Object> tools() {
  return List.of(new Tools());
}
```

**Kotlin**

```kotlin
override fun tools(): List<Any> =
    listOf(
        FunctionTool.of("restart_service", "Restarts a service. An operator approves every restart.", Service::class.java) {
            RUNS.add("restart_service(${it.service})")
            "restarted ${it.service}"
        }
            .requiresApproval(),
        FunctionTool.of("drain_node", "Drains a node of its work.", Node::class.java) {
            RUNS.add("drain_node(${it.node})")
            "drained ${it.node}"
        }
            .requiresApproval(Duration.ofSeconds(2)),
        FunctionTool.of("read_metrics", "Reads a service's metrics.", Metrics::class.java) {
            RUNS.add("read_metrics(${it.service})")
            "${it.service} is healthy"
        },
    )
```

**Python**

```python
class Operator(AutonomousAgent):
    component_id = "operator"
    description = "Keeps services healthy, with an operator's approval for anything that changes them."
    tools = {
        "restart_service": Tool("Restarts a service. An operator approves every restart.", _restart_service, ServiceName, approval=True),
        "drain_node": Tool("Drains a node of its work.", _drain_node, NodeName, approval=Approval(within=timedelta(seconds=2))),
        "read_metrics": Tool("Reads a service's metrics.", _read_metrics, ServiceName),
    }
    accepts = [TaskAcceptance(OPERATE, max_iterations=5)]
```

**TypeScript**

```ts
static readonly tools = {
  restartService: tool(
    "restart_service",
    "Restarts a service. An operator approves every restart.",
    Service,
    (_a: Operator, { service }) => {
      runs.push(`restart_service(${service})`)
      return `restarted ${service}`
    },
    { approval: true },
  ),
  drainNode: tool(
    "drain_node",
    "Drains a node of its work.",
    Node,
    (_a: Operator, { node }) => {
      runs.push(`drain_node(${node})`)
      return `drained ${node}`
    },
    { approval: { withinMs: 2_000 } },
  ),
  readMetrics: tool("read_metrics", "Reads a service's metrics.", Service, (_a: Operator, { service }) => {
    runs.push(`read_metrics(${service})`)
    return `${service} is healthy`
  }),
}
```

**Go**

```go
// Operator runs the cluster; a restart waits for a person, a drain waits two seconds at most.
var Operator = ankka.Autonomous("operator", "Operates the cluster",
	ankka.Tools(
		ankka.ApprovedTool("restart_service",
			"Restarts a service. An operator approves every restart.", 0,
			func(_ context.Context, in ServiceArguments) (string, error) {
				return "restarted " + in.Service, nil
			}),
		ankka.ApprovedTool("drain_node", "Drains a node of its work.", 2*time.Second,
			func(_ context.Context, in NodeArguments) (string, error) {
				return "drained " + in.Node, nil
			}),
		ankka.Tool("read_metrics", "Reads a service's metrics.",
			func(_ context.Context, in ServiceArguments) (string, error) {
				return in.Service + " is healthy", nil
			}),
	),
	ankka.Accepts(OperationTask, 5),
)
```

The instance's state lists what it awaits (`awaiting`), and its subscribers are told
`ApprovalRequested` and `ApprovalDecided` as they happen. A decision goes to the instance:

**Scala**

```scala
client
  .forAutonomousAgent(Operator)("ops-1")
  .decide(Decision.approved(request.id, by = "dana")): Unit
```

**Java**

```java
public static void approve(ComponentClient client, String approvalId) {
  AgentClients.forAutonomousAgent(client, Operator.class, "ops-1")
      .decide(Decision.approved(approvalId, "dana"));
}
```

**Kotlin**

```kotlin
AgentClients.forAutonomousAgent(client, Operator::class.java, "ops-1").decide(Decision.approved(approvalId, "dana"))
```

**Python**

```python
async def approve(client: ComponentClient, approval_id: str) -> None:
    await client.for_autonomous_agent(Operator, "ops-1").decide(approval_id, True, "dana")
```

**TypeScript**

```ts
decide: post("/{instanceId}/decisions", Decision, Done, async (ep: OperatorEndpoint, req, { approvalId, by }) => {
  await ep.client.forAutonomousAgent(Operator, req.params.instanceId).decide(approvalId, { approved: true, by })
  return done
}),
```

**Go**

```go
return ankka.Instance("operator", "ops-1").
	Decide(ctx, approvalID, ankka.Decision{Approved: true, By: "dana"})
```

Approved, the tool runs and the model is told its result in the next iteration; refused, the model is
told who refused it and their note. The rules are a request agent's: who decided is required, a request
is decided once, and a time limit (`requiresApproval(2.seconds)` in Scala) has the platform refuse the
request when it passes, which needs a `TimerRuntime`. Unlike a request agent's, an approved tool may run
again if the instance stops after the decision and before the tool's result is recorded, as any of its
tools may.

An autonomous agent lists [MCP servers](mcp-servers.md) and result guardrails as a request agent does,
on its definition (`.mcpServers(...)`, `.resultGuardrails(...)`), or as class attributes in Python and
TypeScript.

## Running a task and reading its result

`runSingleTask` creates a task, starts an instance on it and answers the task's id at once, before any
model call. The instance ends when the task does. Anyone holding the id reads the task's record, with its
result decoded as the task type's, and can wait for it to end.

**Scala**

```scala
/** Starts the work and answers at once, with where to look for the answer. */
postBody("/ask") { (question: String) =>
  val taskId   = client.forAutonomousAgent(CartAnswerer).runSingleTask(CartTasks.answer, question)
  val instance = client.forTask(taskId).get().assignee.map(_.instanceId).getOrElse("")
  Asked(taskId, instance)
}
```

```scala
/** Where the task has got to, with the answer once there is one. */
get("/{taskId}") { (taskId: String) =>
  val task = client.forTask(taskId).get(CartTasks.answer)
  Question(taskId, task.status.wire, task.result, task.reason, task.record.iterations)
}
```

`forTask(id).await(task, timeout)` blocks until the task has completed, failed or been cancelled, and
fails with `Timeout` naming where it had got to otherwise.

**Java**

```java
/** Starts the work and answers at once, with the task's id. */
public static String startAnswer(ComponentClient client, String question) {
  return AgentClients.runSingleTask(client, CartAnswerer.class, CartTasks.ANSWER, question);
}

/** The task's record once it has ended, its result read as an {@code Answer}. */
public static TaskSnapshot<CartTasks.Answer> answer(ComponentClient client, String taskId) {
  return AgentClients.forTask(client, taskId).await(CartTasks.ANSWER, Duration.ofMinutes(1));
}
```

**Kotlin**

```kotlin
val taskId = AgentClients.runSingleTask(client, Guide::class.java, ANSWER, "How should I write?")
val done = AgentClients.forTask(client, taskId).await(ANSWER, Duration.ofMinutes(1))
```

**Python**

```python
@post("/ask")
async def ask(self, question: str) -> Asked:
    """Starts the work and answers at once, with where to look for the answer."""
    client = self.client.with_metadata(self.request.metadata)
    task_id = await client.for_autonomous_agent(CartAnswerer).run_single_task(ANSWER, question)
    task = await client.for_task(task_id).get(ANSWER)
    return Asked(task_id, task.assignee[1] if task.assignee else "")
```

```python
@get("/{taskId}")
async def read(self, taskId: str) -> Question:
    task = await self.client.with_metadata(self.request.metadata).for_task(taskId).get(ANSWER)
    return Question(taskId, task.status, task.result, task.reason, task.iterations)
```

`await client.for_task(id).wait(ANSWER, timeout)` waits until the task has ended.

**TypeScript**

```ts
/** Starts the work and answers at once, with where to look for the answer. */
ask: post("/ask", s.string, Asked, async (ep: QuestionsEndpoint, _req, question) => {
  const taskId = await ep.client.forAutonomousAgent(CartAnswerer).runSingleTask(ANSWER, question)
  const task = await ep.client.forTask(taskId).get()
  return { taskId, instanceId: task.assignee?.instanceId ?? "" }
}),
/** Where the task has got to, with the answer once there is one. */
read: get("/{taskId}", Question, async (ep: QuestionsEndpoint, req) => {
  const task = await ep.client.forTask(req.params.taskId).get(ANSWER)
  const { id: taskId, status, iterations } = task
  return { taskId, status, answer: task.result ?? null, reason: task.reason ?? null, iterations }
}),
```

**Rust**

```rust
fn run_task(request: &Request, instructions: String) -> Result<Ran, HttpProblem> {
    AutonomousEndpoint::only_answer(request)?;
    let client = request.client();
    let task_id = client
        .autonomous_agent(ConformanceAnswerer)
        .run_single_task(&answer(), instructions)?;
    let task = client.task(&task_id).get_as(&answer())?;
    let instance_id = task
        .assignee
        .map(|(_, instance)| instance)
        .unwrap_or_default();
    Ok(Ran {
        task_id,
        instance_id,
    })
}
```

**Go**

```go
// Starts the work and answers at once, with where to look for the answer.
ankka.Post("/ask", func(ctx context.Context, _ *ankka.Request, question string) (Asked, error) {
	taskID, err := ankka.RunSingleTask(ctx, "cart-answerer", AnswerTask, question,
		ankka.TaskOptions{})
	if err != nil {
		return Asked{}, err
	}
	task, err := ankka.GetTask(ctx, AnswerTask, taskID)
	if err != nil {
		return Asked{}, err
	}
	asked := Asked{TaskID: taskID}
	if task.Assignee != nil {
		asked.InstanceID = task.Assignee.InstanceID
	}
	return asked, nil
}),
```

To give an instance several tasks, create them — optionally with attachments and dependencies — and
assign them to an instance by an id you choose; they are worked one at a time, in order:

**Scala**

```scala
val first  = client.tasks.create(CartTasks.answer, "What is in cart c-1?").create()
val second = client.tasks.create(CartTasks.answer, "And in c-2?").dependsOn(first).create()
client.forAutonomousAgent(CartAnswerer)("reviewer-1").assign(first, second)
```

**Java**

```java
public static AssignResult review(ComponentClient client) {
  var tasks = AgentClients.tasks(client);
  var first = tasks.create(CartTasks.ANSWER, "What is in cart c-1?").create();
  var second = tasks.create(CartTasks.ANSWER, "And in c-2?").dependsOn(first).create();
  return AgentClients.forAutonomousAgent(client, CartAnswerer.class, "reviewer-1")
      .assign(first, second);
}
```

**Kotlin**

```kotlin
val first = AgentClients.tasks(client).create(ANSWER, "What is in cart c-1?").create()
val second = AgentClients.tasks(client).create(ANSWER, "And in c-2?").dependsOn(first).create()
AgentClients.forAutonomousAgent(client, Guide::class.java, "reviewer-1").assign(first, second)
```

**Python**

```python
@post("/review")
async def review(self) -> Done:
    """Two questions for one reviewer, the second once the first has been answered."""
    first = await self.client.tasks.create(ANSWER, "What is in cart c-1?")
    second = await self.client.tasks.create(ANSWER, "And in c-2?", depends_on=[first])
    await self.client.for_autonomous_agent(CartAnswerer, "reviewer-1").assign(first, second)
    return DONE
```

**TypeScript**

```ts
/** Two questions for one reviewer, the second once the first has been answered. */
review: post("/review", Done, async (ep: QuestionsEndpoint) => {
  const first = await ep.client.tasks.create(ANSWER, "What is in cart c-1?")
  const second = await ep.client.tasks.create(ANSWER, "And in c-2?", { dependsOn: [first] })
  await ep.client.forAutonomousAgent(CartAnswerer, "reviewer-1").assign(first, second)
  return done
}),
```

**Rust**

```rust
/// `{"instructions": "...", "dependsOn": ["..."]}`: creates without running.
fn create_task(request: &Request, body: Create) -> Result<Created, HttpProblem> {
    AutonomousEndpoint::only_answer(request)?;
    let task = NewTask::new(body.instructions).depends_on(body.depends_on);
    let task_id = request.client().tasks().create(&answer(), task)?;
    Ok(Created { task_id })
}

fn assign(request: &Request, task_ids: Vec<String>) -> Result<Accepted, HttpProblem> {
    let instance = request
        .client()
        .autonomous_agent(ConformanceAnswerer)
        .instance(request.path("instance"));
    let assignment = instance.assign(task_ids)?;
    Ok(Accepted {
        accepted: assignment.accepted,
    })
}
```

**Go**

```go
// Two questions for one instance, the second waiting on the first; they are worked in order.
ankka.Post("/review", func(ctx context.Context, _ *ankka.Request, _ ankka.Unit) (ankka.Assigned, error) {
	first, err := ankka.CreateTask(ctx, AnswerTask, "What is in cart c-1?",
		ankka.TaskOptions{})
	if err != nil {
		return ankka.Assigned{}, err
	}
	second, err := ankka.CreateTask(ctx, AnswerTask, "And in c-2?",
		ankka.TaskOptions{DependsOn: []string{first}})
	if err != nil {
		return ankka.Assigned{}, err
	}
	return ankka.Instance("cart-answerer", "reviewer-1").Assign(ctx, first, second)
}),
```

An instance can be suspended and resumed, terminated — its tasks go back to pending and its id is never
used again — and asked its state. A task can be cancelled; one being worked stops at the end of the
iteration in progress. A dependency must exist when the task is created; one that has already failed
cancels the new task at once.

## Watching an instance

An instance's notifications say what it does as it happens. Forwarded as server-sent events, each event's
`data` field is a JSON string holding the notification's JSON — the platform's rule for every
server-sent event — so a reader parses the field, then the notification:

**Scala**

```scala
/**
 * What an answerer instance does, as it happens, as server-sent events. Each event's data is the
 * notification's JSON — as a JSON string, as every event's data is, so a reader parses the field
 * and then the notification. Watching keeps the instance in memory while the stream is open.
 */
sse("/answerer/{instanceId}/notifications") { (instanceId: String) =>
  client
    .forAutonomousAgent(CartAnswerer)(instanceId)
    .notifications()
    .map(n => String(Notification.serializer.toBytes(n), "UTF-8"))
}
```

**Java**

```java
/**
 * What an answerer instance does, as it happens, as server-sent events. Each event's data is the
 * notification's JSON, as a JSON string, as every event's data is, so a reader parses the field
 * and then the notification. Watching keeps the instance in memory while the stream is open.
 */
@Sse("/answerer/{instanceId}/notifications")
public Flow.Publisher<SseEvent> notifications(String instanceId) {
  var notifications =
      AgentClients.forAutonomousAgent(componentClient(), CartAnswerer.class, instanceId)
          .notifications();
  return subscriber ->
      notifications.subscribe(
          new Flow.Subscriber<Notification>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
              subscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(Notification notification) {
              subscriber.onNext(SseEvent.text(notification.json()));
            }

            @Override
            public void onError(Throwable error) {
              subscriber.onError(error);
            }

            @Override
            public void onComplete() {
              subscriber.onComplete();
            }
          });
}
```

**Kotlin**

```kotlin
/**
 * What an instance does, as it happens, as server-sent events. Each event's data is the
 * notification's JSON, as a JSON string, as every event's data is, so a reader parses the field
 * and then the notification. Watching keeps the instance in memory while the stream is open.
 */
@Sse("/autonomous/instances/{instance}/notifications")
fun notifications(instance: String): Flow.Publisher<SseEvent> =
    instance(instance).notificationsFlow().map { SseEvent.text(it.json()) }.asPublisher()
```

**Python**

```python
@sse("/answerer/{instanceId}/notifications")
async def notifications(self, instanceId: str) -> AsyncIterator[str]:
    """What an answerer instance does, as it happens. Each event's data is the notification's
    JSON, as a JSON string: a reader parses the field, then the notification."""
    async for n in self.client.with_metadata(self.request.metadata).for_autonomous_agent(CartAnswerer, instanceId).notifications():
        yield n.to_json()
```

**TypeScript**

```ts
/**
 * What an answerer instance does, as it happens, as server-sent events. Each event's data is the
 * notification's JSON — as a JSON string, as every event's data is, so a reader parses the field
 * and then the notification. Watching keeps the instance in memory while the stream is open.
 */
notifications: sse("/answerer/{instanceId}/notifications", async function* (ep: QuestionsEndpoint, req) {
  for await (const n of ep.client.forAutonomousAgent(CartAnswerer, req.params.instanceId).notifications()) {
    yield JSON.stringify(n)
  }
}),
```

**Go**

```go
// What an answerer instance does, as it happens, as server-sent events. Each event's data is
// the notification's JSON, as a JSON string, as every event's data is: a reader parses the
// field, then the notification. Watching keeps the instance in memory while the stream is open.
ankka.SSE("/answerer/{instanceId}/notifications",
	func(ctx context.Context, req *ankka.Request) iter.Seq2[string, error] {
		return func(yield func(string, error) bool) {
			instance := ankka.Instance("cart-answerer", req.Param("instanceId"))
			for n, err := range instance.Notifications(ctx) {
				if err != nil {
					yield("", err)
					return
				}
				text, _ := json.Marshal(n)
				if !yield(string(text), nil) {
					return
				}
			}
		}
	}),
```

In a browser:

```javascript
const events = new EventSource(`/questions/answerer/${instanceId}/notifications`)
events.onmessage = (e) => {
  const notification = JSON.parse(JSON.parse(e.data))
  console.log(notification.type, notification.taskId)
}
```

A subscriber sees what happens from the moment it subscribes, and nothing before. One that reads too
slowly misses the oldest notifications it had not read and receives one `Dropped` notification saying how
many. While anyone watches an instance, it stays in memory.

## Testing

The scripted model drives an autonomous agent end to end. `expectCompleteTask(result)` scripts the model
completing the task, `expectFailTask(reason)` giving up, and `expectToolCall` calling one of the agent's
tools; `whenToolResult` and `whenUserAsks` answer by what a request holds rather than in order, once the
script is used up. A request that neither answers fails the task, naming the script, rather than leaving it
waiting.

**Scala**

```scala
model
  .expectToolCall("cart_total", Json.obj("cartId" -> Json.str("c-1")))
  .expectCompleteTask(Answer("Cart c-1 holds 3 items.", List("cart_total")))

val asked = post("/questions/ask", "How many items are in cart c-1?")
assertEquals(asked.statusCode, 200, asked.body)
val taskId = Json.parse(asked.body).toOption.flatMap(_("taskId")).flatMap(_.asString).get

val done = kit.awaitTask(taskId, CartTasks.answer)
assertEquals(done.result, Some(Answer("Cart c-1 holds 3 items.", List("cart_total"))))
```

`AnkkaTestKit.awaitTask` waits for the task and fails naming where it had got to;
`restartService()` stops the whole service mid-task, and the task still completes, which is how a test
proves an agent survives a crash.

**Java**

```java
var model =
    new TestModelProvider()
        .expectToolCall("cart_total", "{\"cartId\":\"c-1\"}")
        .expectCompleteTask(
            "{\"answer\":\"Cart c-1 holds 3 items.\",\"sources\":[\"cart_total\"]}");
try (var kit = AnkkaTestKit.start(service(model))) {
  var client = kit.componentClient();
  client.forEventSourcedEntity("c-1").call(ShoppingCart::addItem).invoke(new LineItem("p", "Pen", 3));

  var taskId = startAnswer(client, "How many items are in cart c-1?");
  return answer(client, taskId).result().orElseThrow();
}
```

In the whole-service kit the scripted `TestModelProvider` is the agents module's default model, and the task is worked to its end.

**Kotlin**

```kotlin
val model =
    TestModelProvider()
        .expectToolCall("read_page", """{"page":"style"}""")
        .expectCompleteTask("""{"answer":"Be brief.","sources":["style"]}""")
AnkkaTestKit.start(guideService(model)).use { kit ->
    val client = kit.componentClient()
    val taskId = AgentClients.runSingleTask(client, Guide::class.java, ANSWER, "How should I write?")
    val done = AgentClients.forTask(client, taskId).await(ANSWER, Duration.ofMinutes(1))
    assertEquals(Answer("Be brief.", listOf("style")), done.result().orElseThrow())
}
```

**Python**

The loop runs in the sidecar, so an end-to-end test scripts the sidecar's model with
`ANKKA_MODEL_SCRIPT`. A turn with `when` or `when_tool_result` answers by what the request holds, which
also lets a script survive the sidecar being replaced mid-task:

```python
ANSWER_SCRIPT = json.dumps(
    [
        {"tool": "cart_total", "arguments": {"cartId": "q1"}},
        # The first answer cites nothing, so the rule in this process sends it back.
        {"tool": "complete_task", "arguments": {"answer": "3 items", "sources": []}},
        {"tool": "complete_task", "arguments": {"answer": "Cart q1 holds 3 items.", "sources": ["cart_total"]}},
    ]
)


@pytest.mark.slow
async def test_autonomous_task_through_the_sidecar() -> None:
    """The loop runs in the sidecar; the tool runs here and reads the cart; the rule here rejects an
    answer that cites nothing; the typed result is read back through the task's record."""
    async with await AnkkaTestKit.start(service(), env={"ANKKA_MODEL_SCRIPT": ANSWER_SCRIPT}) as kit:
        assert (await kit.http.post("/carts/q1/items", json=PEN_JSON)).status_code == 204
        assert (await kit.http.post("/carts/q1/items", json=INK_JSON)).status_code == 204
        asked = await kit.http.post("/questions/ask", content="How many items are in q1?", headers={"content-type": "text/plain"})
        assert asked.status_code == 200, asked.text
        task_id = asked.json()["taskId"]

        done = await kit.await_task(task_id, ANSWER)
        assert done.status == "completed", done
        assert done.result == Answer("Cart q1 holds 3 items.", ["cart_total"])
        assert done.iterations == 3
        read = (await kit.http.get(f"/questions/{task_id}")).json()
        assert read["status"] == "completed" and read["answer"]["sources"] == ["cart_total"]
```

A task type's rules and an agent's tools can be tested without a sidecar:

```python
async def test_answerer_pieces_without_a_sidecar() -> None:
    """The tools, the result check and the rules, run directly: no sidecar, no model."""
    from ankka.testkit import AutonomousAgentTestKit

    kit = AutonomousAgentTestKit.of(CartAnswerer)
    assert await kit.check_result(ANSWER, Answer("3", [])) == ("cites-sources", "say which tools you used in sources")
    assert await kit.check_result(ANSWER, Answer("3", ["cart_total"])) is None
```

**TypeScript**

```ts
const ANSWER_SCRIPT = JSON.stringify([
  { tool: "cart_total", arguments: { cartId: "q1" } },
  // The first answer cites nothing, so the rule in this process sends it back.
  { tool: "complete_task", arguments: { answer: "3 items", sources: [] } },
  { tool: "complete_task", arguments: { answer: "Cart q1 holds 3 items.", sources: ["cart_total"] } },
])

test("the loop runs in the sidecar, the tool and the rule here, and the typed result is read back", async () => {
  const kit = await AnkkaTestKit.start(service(), { env: { ANKKA_MODEL_SCRIPT: ANSWER_SCRIPT } })
  try {
    assert.equal((await kit.http.post("/carts/q1/items", { productId: "p1", name: "Pen", quantity: 2 })).status, 204)
    assert.equal((await kit.http.post("/carts/q1/items", { productId: "p2", name: "Ink", quantity: 1 })).status, 204)
    const asked = await kit.http.post("/questions/ask", "How many items are in q1?")
    assert.equal(asked.status, 200, asked.text())
    const { taskId } = asked.json() as { taskId: string }

    const done = await kit.client.forTask(taskId).wait(ANSWER, { timeoutMs: 60_000 })
    assert.equal(done.status, "completed")
    assert.deepEqual(done.result, { answer: "Cart q1 holds 3 items.", sources: ["cart_total"] })
    assert.equal(done.iterations, 3)
  } finally {
    await kit.stop()
  }
})
```

**Rust**

```rust
#[test]
fn the_kit_runs_the_tools_the_rules_and_the_guardrails_for_one_task() {
    let kit = AutonomousAgentTestKit::<ConformanceAnswerer>::new("t7")
        .with_service(shopping_cart::conformance::build());
    // The tool acts through the client, against the kit's in-memory entities.
    assert_eq!(
        kit.run_tool("lookup", json!({"id": "c-1"})),
        Ok("count for c-1 is 1".to_string())
    );
    assert_eq!(
        kit.run_tool("lookup", json!({"id": ""})),
        Err("an id is needed".to_string())
    );
    // A result that cites nothing is sent back to the model; one that does is accepted.
    let quiet = Answer {
        answer: "three".into(),
        sources: vec![],
    };
    assert_eq!(
        kit.check_rule(&answer(), "cites-sources", &quiet),
        Verdict::rejected("sources must not be empty")
    );
    let cited = Answer {
        sources: vec!["cart_total".into()],
        ..quiet
    };
    assert_eq!(
        kit.check_rule(&answer(), "cites-sources", &cited),
        Verdict::Accepted
    );
    assert!(
        kit.check_guardrail("no-secrets", Stage::Output, "the key is sk-1")
            .is_err()
    );
}
```

**Go**

```go
// The sidecar's model, scripted: a tool call, an answer that cites nothing, which the rule in this
// process sends back, then the answer the task completes with.
const answerScript = `[
  {"tool": "cart_total", "arguments": {"cartId": "c-1"}},
  {"tool": "complete_task", "arguments": {"answer": "3 items", "sources": []}},
  {"tool": "complete_task", "arguments": {"answer": "Cart c-1 holds 3 items.", "sources": ["cart_total"]}}
]`

func TestATaskIsWorkedThroughTheSidecar(t *testing.T) {
	kit := testkit.StartT(t, ankka.NewService(cart.Entity, cart.API, CartAnswerer, Operator, QuestionsEndpoint),
		testkit.Env(map[string]string{"ANKKA_MODEL_SCRIPT": scripts(answerScript, operatorScript)}))
	ctx, h := t.Context(), kit.HTTP()
	for _, item := range []cart.LineItem{pen, ink} {
		if r, err := h.Post(ctx, "/carts/c-1/items", item); err != nil || r.Status != 204 {
			t.Fatalf("adding an item: %v, %v", r, err)
		}
	}

	asked, err := h.Post(ctx, "/questions/ask", "How many items are in cart c-1?")
	if err != nil || asked.Status != 200 {
		t.Fatalf("asking answered %v, %v", asked, err)
	}
	var where Asked
	if err := json.Unmarshal(asked.Body, &where); err != nil {
		t.Fatal(err)
	}

	// The loop runs in the sidecar; the tool runs here and reads the cart; the typed result is
	// read back through the task's record.
	done, err := ankka.WaitForTask(kit.Context(ctx), AnswerTask, where.TaskID, time.Minute)
	if err != nil || done.Status != ankka.TaskCompleted {
		t.Fatalf("the task ended %+v, %v\n%s", done, err, kit.SidecarLogs(ctx))
	}
	if done.Result.Answer != "Cart c-1 holds 3 items." || done.Iterations != 3 {
		t.Fatalf("the task's result is %+v after %d iterations", *done.Result, done.Iterations)
	}
	read, err := testkit.GetJSON[Question](ctx, h, "/questions/"+where.TaskID)
	if err != nil || read.Status != "completed" || read.Answer.Sources[0] != "cart_total" {
		t.Fatalf("reading the task answered %+v, %v", read, err)
	}
```
