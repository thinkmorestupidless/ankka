package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import scala.concurrent.duration.*

/**
 * What the feature cannot say about a workflow source: the order of the changes one workflow is
 * handed, workflows spread over every slice, and how soon a consumer acts on an end (SC-002).
 */
class WorkflowSourceSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout: Duration = 5.minutes

  private var kit: AnkkaTestKit = null
  private val broker            = InMemoryBroker()

  override def beforeAll(): Unit =
    super.beforeAll()
    kit = AnkkaTestKit.start(
      Seq.empty,
      Seq(ProjectionRuntime.withBroker(broker, broker)),
      configure = _.registerAll(workflowSourceComponents)
    )

  override def afterAll(): Unit =
    if kit != null then kit.stop()
    super.afterAll()

  private def checkouts = kit.service.viewClient.forView(Checkouts)

  private def start(id: String): Unit =
    kit.componentClient.forWorkflow(EntityId(id)).call(CheckoutFlow.start).invoke("end"): Unit

  test("a workflow's recorded states are handed in order, each stamped with the step it moved to") {
    start("order-1")
    kit.eventually("order-1 completed")(
      checkouts.get("order-1").filter(_.standing == "completed")
    )
    val seen = FlowLog.of("checkouts", "order-1")
    assertEquals(
      seen.map(s => s.note -> s.standing.map(l => l.status -> l.pendingStep)),
      Vector(
        Some("started") -> Some("Running" -> Some("reserve")),
        Some("reserve") -> Some("Running" -> Some("charge")),
        Some("charge")  -> Some("Completed" -> None)
      )
    )
    assertEquals(seen.map(_.sequence), seen.map(_.sequence).sorted)
    assertEquals(checkouts.get("order-1").map(_.writes), Some(3))
  }

  test("workflows on every slice of the source reach their rows") {
    val ids = (1 to 24).map(n => s"slice-$n")
    ids.foreach(start)
    ids.foreach(id =>
      kit.eventually(s"$id completed")(checkouts.get(id).filter(_.standing == "completed")): Unit
    )
  }

  test("a consumer publishes a workflow's end within the bound an entity's consumer meets") {
    val began = System.nanoTime()
    kit.componentClient.forWorkflow(EntityId("quick")).call(TransferFlow.start).invoke("end"): Unit
    kit.eventually("quick settled")(
      broker.publishedTo("transfers-settled").find(_.message.subject.contains("quick"))
    ): Unit
    val took = (System.nanoTime() - began).nanos
    println(s"SC-002: a transfer's end was published ${took.toMillis} ms after it was started")
    // The feature suites' `eventually` default: what an entity-sourced consumer is held to.
    assert(took < 30.seconds, s"took $took")
  }
