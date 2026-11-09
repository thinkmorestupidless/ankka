package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, TestModelProvider}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.http.{HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The Java samples of the multi-agent orchestration page: the planner workflow and its agents. */
class DocsPlannerSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val model             = TestModelProvider()
  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(PlannerCalls.service(model))
  override def afterAll(): Unit  = if kit != null then kit.close()
  override def beforeEach(context: BeforeEach): Unit = model.reset()

  test("the selector decides which specialists run, and only those run") {
    val plan = PlannerChecks.selectorDecides(kit, model)
    assertEquals(plan.status, "completed")
    assertEquals(plan.contributionFrom("weather").toScala, Some("Lisbon is mild, with rain."))
    assertEquals(plan.contributionFrom("budget").toScala, Some("Budget about 90 euros a day."))

    // The summariser read the specialists' answers from the shared session, not the routing.
    val summaryRequest = model.lastRequest
    assert(summaryRequest.contains("Lisbon is mild, with rain."), summaryRequest)
    assert(summaryRequest.contains("Budget about 90 euros a day."), summaryRequest)
    assert(!summaryRequest.contains("chosen for the test"), summaryRequest)

    // The domain state and the engine's state, read from outside.
    assertEquals(PlannerCalls.plan(kit.componentClient, "p-select"), plan)
    val lifecycle = PlannerCalls.lifecycle(kit.componentClient, "p-select")
    assert(lifecycle.isCompleted, lifecycle.toString)

    // The same two through the routes: the engine's word, and a wait that answers at once.
    assertEquals(get("/plans/p-select/lifecycle").body, "Completed")
    val waited = get("/plans/p-select/wait")
    assertEquals(waited.statusCode, 200, waited.body)
    assert(waited.body.contains("\"summary\":\"A mild, affordable trip.\""), waited.body)
  }

  private def get(path: String): HttpResponse[String] =
    kit.httpClient.send(
      HttpRequest.newBuilder(kit.http.resolve(path)).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )

  test("a plan drained before the next starts leaves the scripted model to the next") {
    val plan = PlannerChecks.plansTwice(kit, model)
    assertEquals(plan.selection.toScala.map(_.specialists.asScala.toList), Some(List("budget")))
    assertEquals(plan.summary.toScala, Some("A cheap trip."))
    assertEquals(model.callCount, 6)
  }
