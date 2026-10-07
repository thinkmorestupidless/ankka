package planner

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.blueprint.Scripted
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import planner.application.*
import planner.domain.Preferences

import scala.concurrent.duration.DurationInt

/**
 * The planner as a blueprint, with the planner's scripted model: the same plans `PlannerSuite`
 * asserts of the workflow, for every case but the fallback, which a blueprint does not have. A
 * selector naming nothing usable is refused by the shape and asked again.
 */
class PlannerBlueprintSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private var testKit: AnkkaTestKit = null
  private val model                 = TestModelProvider()
  private val agents =
    AgentRuntime.withDefaultModel(model).withBlueprints(PlannerBlueprint.registry)

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(PreferencesEntity.descriptor) ++ AgentRuntime.descriptors,
      Seq(agents)
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    selections = Vector.empty
    scriptSpecialists()

  private def preferences(userId: String) =
    testKit.componentClient.forKeyValueEntity(EntityId(userId))

  /** The selector's answers, in order: a second one is given when the first is refused. */
  private var selections: Vector[Vector[String]] = Vector.empty

  private def selectionJson(names: Vector[String]): String =
    Json
      .obj(
        "specialists" -> Json.Arr(names.map(Json.str)),
        "reason"      -> Json.str("chosen for the test")
      )
      .render

  /** Scripts the specialists once; what a plan needs is which of them select names. */
  private def scriptSpecialists(): Unit =
    model.respondWhen(r => Scripted.forStep(r, "select")) { r =>
      // One answer per time asked: the shape's refusal is a message in the same session.
      val asked = r.messages.count {
        case ChatMessage.User(_) => true
        case _                   => false
      }
      Scripted.answer(selectionJson(selections.lift(asked - 1).getOrElse(selections.last)))
    }
    model.respondWhen(r =>
      Scripted.forStep(r, "consult") && isWorker(r, "weather") && !Scripted.afterTool(r)
    )(_ => Scripted.call("get_forecast", Json.obj("destination" -> Json.str("Lisbon"))))
    model.respondWhen(r =>
      Scripted.forStep(r, "consult") && isWorker(r, "weather") && Scripted.afterTool(r)
    )(_ => Scripted.answer("\"Lisbon is mild with occasional rain.\""))
    model.respondWhen(r =>
      Scripted.forStep(r, "consult") && isWorker(r, "activity") && !Scripted.afterTool(r)
    )(_ => Scripted.call("get_preferences", Json.obj("userId" -> Json.str("u-1"))))
    model.respondWhen(r =>
      Scripted.forStep(r, "consult") && isWorker(r, "activity") && Scripted.afterTool(r)
    )(_ => Scripted.answer("\"Try the tram and a pastel de nata.\""))
    model.respondWhen(r => Scripted.forStep(r, "consult") && isWorker(r, "budget"))(_ =>
      Scripted.answer("\"Budget about 90 euros a day.\"")
    )
    model.respondWhen(r => Scripted.forStep(r, "summarise"))(_ =>
      Scripted.answer("\"A mild, affordable trip.\"")
    ): Unit

  private def isWorker(request: ModelRequest, name: String): Boolean =
    request.systemMessage.exists(_.contains(s"concise $name specialist"))

  private val input = Json.obj("userId" -> Json.str("u-1"), "destination" -> Json.str("Lisbon"))

  private def plan(id: String): RunSnapshot =
    agents.runs.start("planner", input, id): Unit
    val run = testKit.awaitRun(agents.runs, id, 60.seconds)
    assertEquals(run.status, RunStatus.Completed, run.reason.toString)
    run

  private def consulted(run: RunSnapshot): Vector[String] =
    run
      .stepNamed("consult")
      .flatMap(_.result)
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .flatMap(_("worker"))
      .flatMap(_.asString)

  test("the selector decides which specialists run, and only those run") {
    selections = Vector(Vector(Specialist.Weather, Specialist.Budget))
    val run = plan("bp-select")
    assertEquals(consulted(run), Vector(Specialist.Weather, Specialist.Budget))
    assertEquals(
      run.stepNamed("summarise").flatMap(_.result),
      Some(Json.str("A mild, affordable trip."))
    )
    // The activity specialist was never consulted: no session of its own in the step.
    assertEquals(
      run.stepNamed("consult").map(_.sessions.toSet),
      Some(Set("run:bp-select:consult:weather", "run:bp-select:consult:budget"))
    )
  }

  test("a different selection changes which workers run, with no change to the blueprint") {
    selections = Vector(Vector(Specialist.Activity))
    val run = plan("bp-one")
    assertEquals(consulted(run), Vector(Specialist.Activity))
    assertEquals(agents.blueprints.versions("planner").size, 1)
  }

  test("the activity specialist reads the traveller's stored preferences through its tool") {
    preferences("u-1")
      .call(PreferencesEntity.set)
      .invoke(Preferences("u-1", List("museums"), List("hiking"), 120)): Unit
    selections = Vector(Vector(Specialist.Activity))
    val _ = plan("bp-prefs")
    val afterTool =
      model.requests.find(r => Scripted.forStep(r, "consult") && Scripted.afterTool(r)).get
    val toolText = afterTool.messages
      .collect { case ChatMessage.ToolResults(results) => results.map(_.content) }
      .flatten
      .mkString
    assert(toolText.contains("museums") && toolText.contains("budget 120"), toolText)
  }

  test("a specialist with a tool runs it before answering") {
    selections = Vector(Vector(Specialist.Weather))
    val run = plan("bp-tool")
    assertEquals(run.stepNamed("consult").map(_.modelCalls), Some(2))
    val afterTool =
      model.requests.find(r => Scripted.forStep(r, "consult") && Scripted.afterTool(r)).get
    assert(afterTool.messages.exists {
      case ChatMessage.ToolResults(results) => results.exists(_.content.contains("occasional rain"))
      case _                                => false
    })
  }

  test("a selector naming nothing usable is refused by the shape and asked again") {
    selections = Vector(Vector("astrology"), Vector(Specialist.Activity))
    val run = plan("bp-junk")
    assertEquals(consulted(run), Vector(Specialist.Activity))
    // Two answers from the selector: the refused one and the corrected one.
    assertEquals(run.stepNamed("select").map(_.modelCalls), Some(2))
    val retry = model.requests.filter(r => Scripted.forStep(r, "select")).last
    val refusal = retry.messages.collect { case ChatMessage.User(content) =>
      content.collect { case MessageContent.Text(t) => t }.mkString
    }.last
    assert(refusal.contains("astrology"), refusal)
  }
