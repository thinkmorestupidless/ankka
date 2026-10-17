package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.agent.openai.OpenAiProvider
import com.thinkmorestupidless.ankka.agent.providers.{Quirks, Shape as Wire}
import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.autonomous.Tasks
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import scala.concurrent.duration.*

/**
 * Blueprints and autonomous agents choose a model provider by name, with nothing new to learn: a
 * worker names one registered for blueprints, a definition names one directly.
 */
class BlueprintProvidersSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val anthropic = TestModelProvider("claude-opus-5", name = "anthropic")
  private val openai    = TestModelProvider("gpt-6.1-sol", name = "openai")

  private val text = Shape.obj("text" -> Shape.string)

  private val brief: Blueprint = Blueprint("brief")
    .input(Shape.obj("topic" -> Shape.string))
    .worker(Worker("research").instructions("You find things out.").model("openai").budget(2))
    .worker(Worker("write").instructions("You write it up.").budget(2))
    .step(Step("find").ask("research").reads("input").result(text))
    .step(Step("say").ask("write").reads("find").result(text))
    .runBudget(6)

  private val agents = AgentRuntime
    .withDefaultModel(anthropic)
    .withBlueprints(_ => BlueprintRegistry.empty.models("openai" -> openai).carrying(brief))

  private var kit: AnkkaTestKit = null
  private val standIn           = ModelStandIn.start().requireReasoningReplay()

  override def beforeAll(): Unit =
    BlueprintProvidersSuite.model = openai
    BlueprintProvidersSuite.reasoning = OpenAiProvider.at(
      "openai",
      s"${standIn.address}/v1",
      "gpt-6.1-sol",
      Some("k"),
      Wire.Responses,
      quirks = Quirks.openAi
    )
    kit = AnkkaTestKit.start(
      Seq(
        BlueprintProvidersSuite.Operator.descriptor,
        BlueprintProvidersSuite.Reasoner.descriptor
      ) ++
        AgentRuntime.descriptors,
      Seq(agents, ProjectionRuntime())
    )

  override def afterAll(): Unit =
    if kit != null then kit.stop()
    standIn.close()

  override def beforeEach(context: BeforeEach): Unit =
    anthropic.reset()
    openai.reset()

  test("a blueprint runs one worker on each model provider") {
    openai.respondWhen(r => Scripted.forStep(r, "find"))(_ =>
      Scripted.answer("""{"text":"ducks float"}""")
    )
    anthropic.respondWhen(r => Scripted.forStep(r, "say"))(_ =>
      Scripted.answer("""{"text":"Ducks float."}""")
    )
    agents.runs.start("brief", Json.obj("topic" -> Json.str("ducks")), runId = "brief-1"): Unit
    val run = agents.runs.await("brief-1", 60.seconds)
    assertEquals(run.status, RunStatus.Completed, run.toString)
    assert(
      openai.requests.nonEmpty && openai.requests.forall(Scripted.forStep(_, "find")),
      "research asked openai alone"
    )
    assert(
      anthropic.requests.nonEmpty && anthropic.requests.forall(Scripted.forStep(_, "say")),
      "write asked the default alone"
    )
  }

  test("an autonomous agent's definition names the model provider openai") {
    openai.expectCompleteTaskText("summarised")
    val id = kit.componentClient
      .forAutonomousAgent(BlueprintProvidersSuite.Operator)
      .runSingleTask(Tasks.summary, "Summarise the week")
    assertEquals(kit.awaitTask(id, Tasks.summary).status, TaskStatus.Completed)
    assertEquals(openai.callCount, 1, "every iteration is a call to openai")
    assertEquals(anthropic.callCount, 0)
  }

  test(
    "an autonomous agent's next iteration hands back the provider state of the tool calls it answers"
  ) {
    standIn.answer(
      ModelResponse(
        "",
        Vector(ToolCall("call_9", "lookup", Json.obj("city" -> Json.str("Oslo")))),
        StopReason.ToolUse
      )
    )
    standIn.answer(
      ModelResponse(
        "",
        Vector(ToolCall("done_1", "complete_task", Json.obj("result" -> Json.str("Oslo: cold")))),
        StopReason.ToolUse
      )
    )
    val id = kit.componentClient
      .forAutonomousAgent(BlueprintProvidersSuite.Reasoner)
      .runSingleTask(Tasks.summary, "How is Oslo?")
    val done = kit.awaitTask(id, Tasks.summary)
    assertEquals(done.status, TaskStatus.Completed, done.toString)
    val kinds = standIn.lastRequest
      .body("input")
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .flatMap(_("type").flatMap(_.asString))
    assert(
      kinds.containsSlice(Vector("reasoning", "function_call", "function_call_output")),
      kinds.toString
    )
  }

object BlueprintProvidersSuite:

  /**
   * A reasoning model at a stand-in, for the reasoner's definition; set before the service starts.
   */
  @volatile var reasoning: ModelProvider = TestModelProvider(name = "openai")

  final class Reasoner(context: AutonomousAgentContext) extends AutonomousAgent(context):
    override def tools: Seq[FunctionTool] = Seq(
      FunctionTool
        .named("lookup")
        .describedAs("Looks up a city.")
        .param[String]("city", "The city.")
        .handle(c => s"$c: cold")
    )

  object Reasoner extends AutonomousAgent.Companion[Reasoner](ComponentId("reasoner")):
    def create(context: AutonomousAgentContext) = new Reasoner(context)

    def definition: AutonomousAgentDefinition =
      define
        .describedAs("Looks things up")
        .model(reasoning)
        .capability(TaskAcceptance.of(Tasks.summary).maxIterationsPerTask(4))

  /** The provider the operator's definition names; set before the service starts. */
  @volatile var model: ModelProvider = TestModelProvider(name = "openai")

  final class Operator(context: AutonomousAgentContext) extends AutonomousAgent(context)

  object Operator extends AutonomousAgent.Companion[Operator](ComponentId("operator-on-openai")):
    def create(context: AutonomousAgentContext) = new Operator(context)

    def definition: AutonomousAgentDefinition =
      define
        .describedAs("Summarises")
        .model(model)
        .capability(TaskAcceptance.of(Tasks.summary).maxIterationsPerTask(2))
