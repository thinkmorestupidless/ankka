# Contract: the Scala API (`modules/agent`, package `…ankka.agent.autonomous`)

Everything below is public API of `ankka-agent`. Names are final; bodies are not shown.

## Declaring a task type

```scala
final case class Answer(answer: String, confidence: Int, sources: List[String])
object Answer:
  given JsonValueCodec[Answer] = Codecs.make      // decoding: the truth
  given JsonSchema[Answer]     = JsonSchema.derived // the description the model sees

object CatalogueTasks:
  val answer: TaskType[Answer] = Task
    .named("answer")                              // the wire name
    .describedAs("Answer a question about the catalogue, citing what you looked up")
    .resultConformsTo[Answer]                     // needs both givens
    .rule("cites-sources") { a =>
      if a.sources.isEmpty then TaskRule.Rejected("sources must not be empty")
      else TaskRule.Accepted
    }
  val summary: TaskType[String] = Task.named("summary").describedAs("…") // no result shape: text
```

`JsonSchema[A]`: instances for `String`, `Int`, `Long`, `Double`, `Boolean`, `Option[A]` (not
required), `List[A]`, `Vector[A]`, `Map[String, A]`, and `JsonSchema.derived` for a case class
(via `scala.deriving.Mirror.ProductOf`). Sum types are not derived in this phase; a field of a
sealed type fails to compile with a message saying so.

## Declaring an agent

```scala
final class CatalogueAnswerer(ctx: AutonomousAgentContext) extends AutonomousAgent(ctx):
  // Tools are declared on the instance, which holds the component client they call through.
  override def tools = Seq(countItems, cartTotal)

object CatalogueAnswerer
    extends AutonomousAgent.Companion[CatalogueAnswerer](ComponentId("catalogue-answerer")):
  def create(ctx: AutonomousAgentContext) = new CatalogueAnswerer(ctx)

  def definition: AutonomousAgentDefinition =
    define
      .describedAs("Answers questions about the catalogue")
      .instructions("Be brief. Look things up rather than guessing.")
      .guardrails(Guardrail.maxInputLength(4000))
      .capability(TaskAcceptance.of(CatalogueTasks.answer).maxIterationsPerTask(5))
      .settings(AutonomousAgentSettings(approachingBudgetAt = 0.8))
```

`AutonomousAgentContext` gives `componentId`, `instanceId`, `componentClient`, `defaultModel`.
Tools may use `ctx.componentClient`; the instance is constructed once per host activation, not
per iteration, and tools run on the loop's virtual thread. `descriptor` is a `def` (as for every
companion) and validates §1 of `data-model.md`; registration is `register(CatalogueAnswerer.descriptor)`
plus `registerAll(AgentRuntime.descriptors)` and an `AgentRuntime` extension, as for request agents.

## Client

```scala
extension (client: ComponentClient)
  def forAutonomousAgent[A <: AutonomousAgent](companion: AutonomousAgent.Companion[A])(instanceId: String): AutonomousAgentCalls
  def forTask(taskId: String): TaskCalls
  def tasks: TaskCreation                          // create without an id in hand

final class TaskCreation:
  def create[R](task: TaskType[R], instructions: String): TaskBuilder[R]

final class TaskBuilder[R]:
  def withId(id: String): TaskBuilder[R]
  def attach(name: String, contentType: String, content: String): TaskBuilder[R]
  def attachReference(name: String, contentType: String, uri: String): TaskBuilder[R]
  def dependsOn(taskIds: String*): TaskBuilder[R]
  def create(): String                            // the task id; refuses a missing dependency (NotFound)
  def createAsync(): Future[String]

final class AutonomousAgentCalls:
  def runSingleTask[R](task: TaskType[R], instructions: String): String   // task id; instance id generated
  def runSingleTask[R](builder: TaskBuilder[R]): String
  def assign(taskIds: String*): AssignResult      // AssignResult(accepted: Vector[String], refused: Map[String, CommandError]); the record decides
  def suspend(): Done
  def resume(): Done
  def terminate(): Done
  def state(): AgentState                          // answered by the entity; does not wake an idle host
  def notifications(): Source[Notification, NotUsed]
  // …Async variants of each returning Future

final class TaskCalls:
  def get(): TaskSnapshot                          // status, reason, timestamps, iterations, usage, assignee, raw result JSON
  def get[R](task: TaskType[R]): TypedTaskSnapshot[R]   // result decoded; NotFound; a type mismatch is BadRequest
  def await[R](task: TaskType[R], timeout: FiniteDuration = 10.minutes): TypedTaskSnapshot[R]
  def cancel(reason: String = "cancelled by caller"): Done
  // …Async variants
```

`runSingleTask` on a `forAutonomousAgent(...)(instanceId)` whose id the caller chose is refused
(`BadRequest`): a single-task instance's id is the platform's. Use `client.forAutonomousAgent(X)`
without an id — the overload `def forAutonomousAgent(companion)` with no instance id returns a
`SingleTaskCalls` exposing only `runSingleTask`.

## Testkit additions (`modules/testkit`)

```scala
// AnkkaTestKit
def awaitTask[R](taskId: String, task: TaskType[R], within: FiniteDuration = 30.seconds): TypedTaskSnapshot[R]
def eventually[A](description: String, within: FiniteDuration = 30.seconds)(check: => Option[A]): A
def startPeer(extensions: Seq[RuntimeExtension]): AnkkaTestKit.Peer   // a second service on the same database with fresh extensions, joined to this cluster; Peer.stop()

// TestModelProvider (modules/agent)
def expectCompleteTask[R](result: R)(using JsonValueCodec[R], callId: String = "complete"): TestModelProvider
def expectCompleteTaskJson(json: String): TestModelProvider
def expectFailTask(reason: String): TestModelProvider
def expectCompleteTaskText(text: String): TestModelProvider                         // a task type with no result shape
def whenToolResult(substring: String)(response: ModelResponse): TestModelProvider   // matches the latest ToolResults turn
def whenUserAsks(substring: String)(response: ModelResponse): TestModelProvider     // any response, keyed on the instructions
```

`whenUserSays` already matches the latest user turn, which for an autonomous agent is the task's
instructions.

## Reserved names

Tool names `complete_task` and `fail_task`; host method names `assign`, `dequeue`, `suspend`,
`resume`, `terminate`, `state`, `notifications`; component ids `ankka-task`, `ankka-agent-instance`,
`ankka-task-cascade`; session id prefix `task:`.

## Errors

| Situation | `ErrorCode` |
|---|---|
| task or instance not found | `NotFound` |
| task not in a state that allows the command; instance terminated or already suspended | `Conflict` |
| task type not accepted by the agent; result type mismatch on `get[R]`; caller-chosen id on `runSingleTask` | `BadRequest` |
| `await` timed out | `Timeout` |
| host busy hand-off (from `Unavailable` retry, as every remote host) | `Unavailable` |
