package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.core.{ComponentId, SessionId}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

// docs:start run-context
/** A tool that records which run it was told it serves: `None` outside any run. */
object Told:
  val seen = CopyOnWriteArrayList[Option[RunRef]]()
  val tell: FunctionTool = FunctionTool
    .named("tell")
    .describedAs("Tells the test which run called it.")
    .handle { () =>
      seen.add(RunContext.current): Unit
      "told"
    }
// docs:end run-context

/** A request agent of the service's own, with the same tool, to call it in no run. */
final class Teller(context: AgentContext) extends Agent:
  def ask(question: String): Effect[String] =
    effects
      .systemMessage(s"Answer briefly; you are ${context.componentId}.")
      .userMessage(question)
      .tools(Told.tell)
      .thenReply()

object Teller extends Agent.Companion[Teller](ComponentId("teller-agent")):
  def create(context: AgentContext) = new Teller(context)
  val ask                           = command("ask")(_.ask)

// docs:start follow
/** A service's consumer of the platform's runs: every event of every run, by run id. */
final class RunFollower extends Consumer[RunEvent, Nothing]:
  def onMessage(event: RunEvent): Effect =
    RunFollower.seen.add(messageContext.subject -> event)
    effects.ignore()

object RunFollower:
  val seen = CopyOnWriteArrayList[(String, RunEvent)]()
  val descriptor: ConsumerDescriptor[RunFollower, RunEvent, Nothing] =
    ConsumerDescriptor(
      componentId = ComponentId("run-follower"),
      source = ChangeSource.eventsOf(RunEntity),
      outputSerializer = None,
      produceTo = None,
      create = _ => new RunFollower,
      parallelism = 1
    )
// docs:end follow

/** A service's consumer of the platform's blueprints: every version registered, by name. */
final class VersionWatcher extends Consumer[BlueprintEvent, Nothing]:
  def onMessage(event: BlueprintEvent): Effect =
    VersionWatcher.seen.add(messageContext.subject -> event)
    effects.ignore()

object VersionWatcher:
  val seen = CopyOnWriteArrayList[(String, BlueprintEvent)]()
  val descriptor: ConsumerDescriptor[VersionWatcher, BlueprintEvent, Nothing] =
    ConsumerDescriptor(
      componentId = ComponentId("version-watcher"),
      source = ChangeSource.eventsOf(BlueprintEntity),
      outputSerializer = None,
      produceTo = None,
      create = _ => new VersionWatcher,
      parallelism = 1
    )

/**
 * `features/blueprints/following.feature`: a tool is told its run, and a service's consumers follow
 * the platform's blueprints and runs as they would any entity of their own (FR-025, FR-026). This
 * is what ankka-reasoning's projection of runs is built on.
 */
class FollowingFeatures
    extends GherkinSuite("../../features/blueprints/following.feature")
    with LogCapturing:

  override val munitTimeout = 5.minutes

  private val model = TestModelProvider()
  private val agents = AgentRuntime
    .withDefaultModel(model)
    .withBlueprints(_ => BlueprintRegistry.empty.tools(Told.tell))
  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      AgentRuntime.descriptors ++ Seq(
        Teller.descriptor,
        RunFollower.descriptor,
        VersionWatcher.descriptor
      ),
      Seq(agents, ProjectionRuntime())
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    Told.seen.clear()
    RunFollower.seen.clear()
    VersionWatcher.seen.clear()
    draftCallsTell = false
    runId = None
    version2 = None
    script()

  private def name                        = s"$scenarioId-brief"
  private def named(s: String)            = s"$scenarioId-$s"
  private val text                        = Shape.obj("text" -> Shape.string)
  private val input                       = Json.obj("topic" -> Json.str("ducks"))
  private var draftCallsTell              = false
  private var runId: Option[String]       = None
  private var version2: Option[Blueprint] = None

  private def brief(instructions: String = "Write briefly."): Blueprint =
    Blueprint(name)
      .input(Shape.obj("topic" -> Shape.string))
      .worker(Worker("writer").instructions(instructions).tools("tell").budget(4))
      .step(Step("outline").ask("writer").reads("input").result(text))
      .step(Step("draft").ask("writer").reads("outline").result(text))

  private def script(): Unit =
    model.respondWhen(r => Scripted.forStep(r, "outline"))(_ =>
      Scripted.answer("""{"text":"outline-of-ducks"}""")
    )
    model.respondWhen(r => Scripted.forStep(r, "draft") && !Scripted.afterTool(r)) { _ =>
      if draftCallsTell then Scripted.call("tell")
      else Scripted.answer("""{"text":"draft-of-ducks"}""")
    }
    model.respondWhen(r => Scripted.forStep(r, "draft") && Scripted.afterTool(r))(_ =>
      Scripted.answer("""{"text":"draft-of-ducks"}""")
    ): Unit

  private def start(id: String): RunSnapshot =
    runId = Some(id)
    agents.runs.start(name, input, id)

  private def eventsOf(id: String): Vector[RunEvent] =
    RunFollower.seen.asScala.collect { case (`id`, e) => e }.toVector

  // ── Background ────────────────────────────────────────────────────────────

  Given("a service with a scripted model")(() => ())

  Given(
    "the blueprint {string} held at blueprint version 1, with the steps {string} and {string}"
  ) { (_: String, outline: String, draft: String) =>
    assertEquals(brief().steps.map(_.name), Vector(outline, draft))
    assertEquals(agents.blueprints.register(brief()).version, 1)
  }

  // ── Tools know their run ──────────────────────────────────────────────────

  Given("a worker in {string} with a tool that records what it is told") { (_: String) =>
    draftCallsTell = true
  }

  When("the worker calls the tool in a run of {string} under the run id {string}") {
    (_: String, id: String) =>
      start(id): Unit
      assertEquals(kit.awaitRun(agents.runs, id, 60.seconds).status, RunStatus.Completed)
  }

  Then(
    "the tool was told the run id {string}, the step {string} and blueprint version 1 of {string}"
  ) { (id: String, step: String, _: String) =>
    assertEquals(Told.seen.asScala.toVector, Vector(Some(RunRef(id, step, name, 1))))
  }

  Given("a request agent with a tool that records what it is told")(() => ())

  When("the model calls the tool in answer to a message") { () =>
    model.expectToolCall("tell", Json.obj()).expectText("Told.")
    val reply =
      kit.componentClient.forAgent(SessionId(named("s"))).call(Teller.ask).invoke("Tell them.")
    assertEquals(reply, "Told.")
  }

  Then("the tool was told it is in no run") { () =>
    assertEquals(Told.seen.asScala.toVector, Vector(None))
  }

  // ── Consumers ─────────────────────────────────────────────────────────────

  Given("a consumer subscribed to the service's runs")(() => ())
  Given("a consumer subscribed to the service's blueprint versions")(() => ())

  When("a run of {string} is started") { (_: String) =>
    start(named("run")): Unit
  }

  Then("the consumer is told the run has started, with its blueprint version and its input") { () =>
    val id = runId.get
    val started = kit.eventually("the watcher sees the run start")(
      eventsOf(id).collectFirst { case s: RunEvent.Started => s }
    )
    assertEquals(started.blueprint, name)
    assertEquals(started.version, 1)
    assertEquals(Json.parse(started.input), Right(input))
  }

  When("the service registers blueprint version 2 of {string}") { (_: String) =>
    val v2 = brief("Write at length.")
    version2 = Some(v2)
    assertEquals(agents.blueprints.register(v2).version, 2)
  }

  Then("the consumer is given blueprint version 2 of {string}") { (_: String) =>
    val mine = name
    val registered = kit.eventually("the watcher sees version 2")(
      VersionWatcher.seen.asScala.collectFirst {
        case (`mine`, v: BlueprintEvent.VersionRegistered) if v.number == 2 => v
      }
    )
    assertEquals(registered.canonical, version2.get.canonical)
    assertEquals(Blueprint.fromJson(registered.canonical).map(_.digest), Right(version2.get.digest))
  }

  When("a run of {string} is completed") { (_: String) =>
    start(named("run")): Unit
    assertEquals(kit.awaitRun(agents.runs, runId.get, 60.seconds).status, RunStatus.Completed)
  }

  Then(
    "the consumer is told the step {string} ended, then the step {string} ended, then the run ended, each with its result"
  ) { (first: String, second: String) =>
    val id  = runId.get
    val run = agents.runs.get(id)
    val events = kit.eventually("the watcher sees the run end")(
      Option(eventsOf(id)).filter(_.exists(_.isInstanceOf[RunEvent.Ended]))
    )
    val ends = events.collect {
      case RunEvent.StepEnded(step, result, _, _, _, _, _) => (step, Json.parse(result).toOption)
      case RunEvent.Ended(status, _, _) => ("run", Some(Json.str(status.toString)))
    }
    assertEquals(
      ends,
      Vector(
        (first, run.stepNamed(first).flatMap(_.result)),
        (second, run.stepNamed(second).flatMap(_.result)),
        ("run", Some(Json.str(RunStatus.Completed.toString)))
      )
    )
  }
