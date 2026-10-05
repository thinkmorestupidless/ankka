# Autonomous agents

> Write an autonomous agent in Scala or Python — a task type with a typed result and rules, an agent that accepts it, running and reading tasks, watching an instance over server-sent events, and testing with a scripted model.

Source: https://docs.ankka.cloud/build/autonomous-agents/
An autonomous agent is handed a task and works it on its own until it completes the task with a typed
result, fails it, or spends its budget; every iteration is recorded, so a crash resumes where it stopped.
[Autonomous agents and tasks](../concepts/autonomous-agents.md) explains how that works and when to choose
one over a request agent or a workflow. This page builds the shopping cart's answerer: an agent that
answers questions about carts by looking them up, and cites what it looked at.

The examples are in Scala and Python. The TypeScript SDK declares autonomous agents and has the client
operations below — `forAutonomousAgent`, `forTask`, `tasks`, and `notifications()` — with the same
shapes; its testkit does not yet script one.

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

**Python**

```python
@dataclass
class Answer:
    answer: str
    sources: list[str]


def cites_sources(answer: Answer) -> Accepted | Rejected:
    return Rejected("say which tools you used in sources") if not answer.sources else Accepted()


# "answer" is the wire name, written into every task of this type.
ANSWER = TaskType(
    "answer",
    "Answer a question about a shopping cart, citing what you looked at",
    result=Answer,
    rules=(TaskRule("cites-sources", cites_sources),),
)
```

The result's dataclass fields are the schema the model sees, as a tool's input fields are. A task type
with no `result` has a text result.

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

The instance's state lists what it awaits (`awaiting`), and its subscribers are told
`ApprovalRequested` and `ApprovalDecided` as they happen. A decision goes to the instance:

```scala
componentClient
  .forAutonomousAgent(Operator)("ops-1")
  .decide(Decision.approved(approvalId, by = "dana"))
```

```python
await client.for_autonomous_agent(Operator, "ops-1").decide(approval_id, True, "dana")
```

```ts
await client.forAutonomousAgent(Operator, "ops-1").decide(approvalId, { approved: true, by: "dana" })
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

**Python**

```python
@post("/ask")
async def ask(self, question: str) -> Asked:
    """Starts the work and answers at once, with where to look for the answer."""
    client = self.client.with_metadata(self.request.metadata)
    task_id = await client.for_autonomous_agent(CartAnswerer).run_single_task(ANSWER, question)
    task = await client.for_task(task_id).get()
    return Asked(task_id, task.assignee[1] if task.assignee else "")
```

```python
@get("/{taskId}")
async def read(self, taskId: str) -> Question:
    task = await self.client.with_metadata(self.request.metadata).for_task(taskId).get(ANSWER)
    return Question(taskId, task.status, task.result, task.reason, task.iterations)
```

`await client.for_task(id).wait(ANSWER, timeout)` waits until the task has ended.

To give an instance several tasks, create them — optionally with attachments and dependencies — and
assign them to an instance by an id you choose; they are worked one at a time, in order:

**Scala**

```scala
val first  = client.tasks.create(CartTasks.answer, "What is in cart c-1?").create()
val second = client.tasks.create(CartTasks.answer, "And in c-2?").dependsOn(first).create()
client.forAutonomousAgent(CartAnswerer)("reviewer-1").assign(first, second)
```

**Python**

```python
first = await client.tasks.create(ANSWER, "What is in cart c-1?")
second = await client.tasks.create(ANSWER, "And in c-2?", depends_on=[first])
await client.for_autonomous_agent(CartAnswerer, "reviewer-1").assign(first, second)
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

**Python**

```python
@sse("/answerer/{instanceId}/notifications")
async def notifications(self, instanceId: str) -> AsyncIterator[str]:
    """What an answerer instance does, as it happens. Each event's data is the notification's
    JSON, as a JSON string: a reader parses the field, then the notification."""
    async for n in self.client.with_metadata(self.request.metadata).for_autonomous_agent(CartAnswerer, instanceId).notifications():
        yield n.to_json()
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
tools; `whenToolResult` and `whenUserAsks` answer by what a request holds rather than in order. A script
that runs out fails the task, naming the script, rather than leaving it waiting.

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
