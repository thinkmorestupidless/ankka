package planner

import nakka.agent.*
import nakka.core.{CommandError, Done, EntityId, ErrorCode, SessionId}
import nakka.testkit.NakkaTestKit
import planner.application.*
import planner.domain.*

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * The multi-agent planner end to end.
 *
 * A scripted model, so the assertions are about the orchestration — which specialists were chosen,
 * that they ran in one shared session, that the summariser saw their contributions and not the
 * selector's routing — rather than about a model's wording.
 */
class PlannerSuite extends munit.FunSuite:

  override val munitTimeout = 5.minutes

  private var testKit: NakkaTestKit = null
  private val model                 = TestModelProvider()

  override def beforeAll(): Unit =
    testKit = NakkaTestKit.start(
      Seq(
        PreferencesEntity.descriptor,
        PlannerWorkflow.descriptor,
        SelectorAgent.descriptor,
        WeatherAgent.descriptor,
        ActivityAgent.descriptor,
        BudgetAgent.descriptor,
        SummaryAgent.descriptor
      ) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model))
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  override def beforeEach(context: BeforeEach): Unit = model.reset()

  private def planner(id: String) = testKit.componentClient.forWorkflow(EntityId(id))
  private def preferences(userId: String) =
    testKit.componentClient.forKeyValueEntity(EntityId(userId))

  private def sessionOf(planId: String) =
    testKit.componentClient
      .forSessionMemory(SessionId(planId))
      .call(SessionMemoryEntity.history)
      .invoke()

  private def eventually[A](description: String, within: FiniteDuration = 60.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  private def completedPlan(planId: String): PlanState =
    eventually(s"plan '$planId' completes")(
      Option(planner(planId).call(PlannerWorkflow.plan).invoke())
        .filter(_.status == PlanState.Completed)
    )

  /** Scripts the selector's structured reply plus one answer per specialist. */
  private def scriptPlan(specialists: List[String], summary: String): Unit =
    val json = specialists.map(s => s"\"$s\"").mkString("[", ",", "]")
    model.expectText(s"""{"specialists":$json,"reason":"chosen for the test"}""")
    specialists.foreach {
      case Specialist.Weather =>
        // The weather specialist has a tool, so it takes two model turns.
        model
          .expectToolCall("get_forecast", Json.obj("destination" -> Json.str("Lisbon")))
          .expectText("Lisbon is mild with occasional rain.")
      case Specialist.Activity => model.expectText("Try the tram and a pastel de nata.")
      case Specialist.Budget   => model.expectText("Budget about 90 euros a day.")
      case other               => fail(s"unscripted specialist '$other'")
    }
    model.expectText(summary): Unit

  test("the selector decides which specialists run, and only those run") {
    scriptPlan(List(Specialist.Weather, Specialist.Budget), "A mild, affordable trip.")

    assertEquals(
      planner("p-select")
        .call(PlannerWorkflow.start)
        .invoke(
          PlannerWorkflow.Start("u-1", "Lisbon")
        ),
      Done
    )

    val plan = completedPlan("p-select")
    assertEquals(
      plan.selection.map(_.specialists),
      Some(List(Specialist.Weather, Specialist.Budget))
    )
    assertEquals(plan.contributions.map(_.specialist), List(Specialist.Weather, Specialist.Budget))
    // The activity specialist was never consulted, so it contributed nothing.
    assertEquals(plan.contributionFrom(Specialist.Activity), None)
    assertEquals(plan.summary, Some("A mild, affordable trip."))
  }

  test("a different selection changes which agents run, with no orchestration change") {
    scriptPlan(List(Specialist.Activity), "Plenty to do.")
    val _ = planner("p-one")
      .call(PlannerWorkflow.start)
      .invoke(
        PlannerWorkflow.Start("u-1", "Lisbon")
      )

    val plan = completedPlan("p-one")
    assertEquals(plan.contributions.map(_.specialist), List(Specialist.Activity))
    assertEquals(
      plan.contributionFrom(Specialist.Activity),
      Some("Try the tram and a pastel de nata.")
    )
  }

  test("every specialist writes into one shared session") {
    scriptPlan(Specialist.All, "A full brief.")
    val _ = planner("p-shared")
      .call(PlannerWorkflow.start)
      .invoke(
        PlannerWorkflow.Start("u-1", "Lisbon")
      )
    val _ = completedPlan("p-shared")

    val history      = sessionOf("p-shared")
    val contributors = history.messages.map(_.agentId).distinct.sorted

    // The specialists and the summariser share the conversation; the selector opted out
    // of memory entirely, so it left no trace.
    assertEquals(contributors, Vector("activity", "budget", "summary", "weather"))
    assert(!contributors.contains("selector-agent"))
  }

  test("the summariser reads the specialists' contributions back from the session") {
    scriptPlan(List(Specialist.Weather, Specialist.Activity), "Mild weather, good trams.")
    val _ = planner("p-summary")
      .call(PlannerWorkflow.start)
      .invoke(
        PlannerWorkflow.Start("u-1", "Lisbon")
      )
    val _ = completedPlan("p-summary")

    // The last model call is the summariser's; it must have seen the specialists' answers.
    val summaryRequest = model.lastRequest
    val visible = summaryRequest.messages
      .collect {
        case ChatMessage.Assistant(text, _) => text
        case ChatMessage.User(content) =>
          content.collect { case MessageContent.Text(t) => t }.mkString
      }
      .mkString("\n")

    assert(visible.contains("mild with occasional rain"), visible)
    assert(visible.contains("pastel de nata"), visible)
    // Routing chatter stayed out of it.
    assert(!visible.contains("chosen for the test"), visible)
  }

  test("the activity specialist is given the traveller's stored preferences") {
    val _ = preferences("u-picky")
      .call(PreferencesEntity.set)
      .invoke(
        Preferences("u-picky", List("museums"), List("hiking"), 120)
      )

    scriptPlan(List(Specialist.Activity), "Museums it is.")
    val _ = planner("p-prefs")
      .call(PlannerWorkflow.start)
      .invoke(
        PlannerWorkflow.Start("u-picky", "Lisbon")
      )
    val _ = completedPlan("p-prefs")

    // The preferences reached the model as context, read from the entity by the agent.
    val activityRequest = model.requests(1)
    val text = activityRequest.messages.collect { case ChatMessage.User(content) =>
      content.collect { case MessageContent.Text(t) => t }.mkString
    }.mkString
    assert(text.contains("museums"), text)
    assert(text.contains("budget 120"), text)
  }

  test("a specialist with a tool runs it before answering") {
    scriptPlan(List(Specialist.Weather), "Bring a light coat.")
    val _ = planner("p-tool")
      .call(PlannerWorkflow.start)
      .invoke(
        PlannerWorkflow.Start("u-1", "Lisbon")
      )
    val _ = completedPlan("p-tool")

    // Selector, weather turn 1, weather turn 2 (after the tool), summariser.
    assertEquals(model.callCount, 4)
    val afterTool = model.requests(2)
    assert(
      afterTool.messages.exists {
        case ChatMessage.ToolResults(results) =>
          results.exists(_.content.contains("occasional rain"))
        case _ => false
      },
      afterTool.messages.toString
    )
  }

  test("a selector naming nothing usable still produces a plan") {
    model.expectText("""{"specialists":["astrology"],"reason":"nonsense"}""")
    model.expectText("Try the tram and a pastel de nata.")
    model.expectText("Fallback brief.")

    val _ = planner("p-junk")
      .call(PlannerWorkflow.start)
      .invoke(
        PlannerWorkflow.Start("u-1", "Lisbon")
      )
    val plan = completedPlan("p-junk")
    assertEquals(plan.selection.map(_.specialists), Some(List(Specialist.Activity)))
    assertEquals(plan.summary, Some("Fallback brief."))
  }

  test("a blank destination is rejected before any model is called") {
    val failure = intercept[CommandError] {
      planner("p-blank").call(PlannerWorkflow.start).invoke(PlannerWorkflow.Start("u-1", "  "))
    }
    assertEquals(failure.code, ErrorCode.BadRequest)
    assertEquals(model.callCount, 0)
  }

  test("starting the same plan twice conflicts") {
    scriptPlan(List(Specialist.Budget), "Cheap enough.")
    val _ = planner("p-twice")
      .call(PlannerWorkflow.start)
      .invoke(
        PlannerWorkflow.Start("u-1", "Lisbon")
      )
    val failure = intercept[CommandError] {
      planner("p-twice").call(PlannerWorkflow.start).invoke(PlannerWorkflow.Start("u-1", "Lisbon"))
    }
    assertEquals(failure.code, ErrorCode.Conflict)

    // Let the first plan finish before the test ends. A workflow left mid-flight keeps
    // consuming the shared scripted model, which would starve the next test.
    val _ = completedPlan("p-twice")
  }

  test("a completed plan reports Completed through the engine's own lifecycle") {
    scriptPlan(List(Specialist.Budget), "Cheap enough.")
    val _ = planner("p-lifecycle")
      .call(PlannerWorkflow.start)
      .invoke(
        PlannerWorkflow.Start("u-1", "Lisbon")
      )
    val _ = completedPlan("p-lifecycle")

    val lifecycle = planner("p-lifecycle").lifecycle(PlannerWorkflow).invoke()
    assert(lifecycle.isCompleted, lifecycle.toString)
    assertEquals(lifecycle.failure, None)
  }
