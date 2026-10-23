package com.thinkmorestupidless.ankka.javasdk.testkit.kinds

import com.thinkmorestupidless.ankka.javasdk.bridge.{Clients, JavaServiceBuilder}
import com.thinkmorestupidless.ankka.javasdk.client.CommandException
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.broken.BrokenWorkflows
import com.thinkmorestupidless.ankka.testkit as stk
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * Java workflows, hosted by the Scala workflow runtime under the Scala `AnkkaTestKit`: steps named
 * by method reference, a pause with a timeout, a failing step's failover, a wait for the end, and
 * the workflow's state as a view's source.
 */
class WorkflowsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private val read = JavaServiceBuilder.read(
    Vector(classOf[Checkout], classOf[CheckoutRows]).map(_ -> None),
    Map.empty
  )

  private var kit: stk.AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = stk.AnkkaTestKit.start(read.descriptors, extensions = read.platformExtensions)

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def client               = Clients.of(kit.componentClient, read.refs)
  private def checkout(id: String) = client.forWorkflow(id)
  private def state(id: String)    = checkout(id).call(KindRefs.getCheckout).invoke()
  private val longWait             = Duration.ofSeconds(20)

  private def start(id: String, cart: String, confirmWithinMillis: Long = 60000): Unit =
    checkout(id)
      .call(KindRefs.startCheckout)
      .invoke(Checkout.Start(cart, confirmWithinMillis)): Unit

  private def eventually(what: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + 20.seconds.toNanos
    while !check do
      if System.nanoTime() > deadline then fail(s"never: $what")
      Thread.sleep(100)

  test("a workflow pauses for a command, then a step ends it; the wait for the end answers it") {
    start("w-confirm", "cart-1")
    eventually("awaiting confirmation")(state("w-confirm").status == "awaiting")
    checkout("w-confirm").call(KindRefs.confirmCheckout).invoke(): Unit
    val ended = checkout("w-confirm").awaitEnd(classOf[Checkout], longWait)
    assertEquals(ended.status, "done")
    assertEquals(
      ended.log.asScala.toVector,
      Vector("started", "reserved", "awaiting", "confirmed", "done")
    )
  }

  test("a pause with a timeout transitions to its step when no command comes") {
    start("w-expire", "cart-2", confirmWithinMillis = 300)
    val ended = checkout("w-expire").awaitEnd(classOf[Checkout], longWait)
    assertEquals(ended.log.asScala.toVector, Vector("started", "reserved", "awaiting", "expired"))
  }

  test("a step that keeps failing is retried, then compensated by its failover step") {
    val before = Checkout.flakyRuns
    start("w-big", "big-cart")
    val ended = checkout("w-big").awaitEnd(classOf[Checkout], longWait)
    assertEquals(ended.log.asScala.toVector, Vector("started", "reserved", "refunded"))
    assertEquals(Checkout.flakyRuns - before, 2, "the step and its one retry")
  }

  test("a command a workflow refuses is a problem for its caller, and changes nothing") {
    start("w-twice", "cart-3")
    val e = intercept[CommandException](start("w-twice", "cart-3"))
    assert(e.getMessage.contains("already started"), e.getMessage)
  }

  test("a wait that runs out is a timeout, and the workflow runs on") {
    start("w-slow", "cart-4")
    eventually("awaiting confirmation")(state("w-slow").status == "awaiting")
    val e = intercept[CommandException](
      checkout("w-slow").awaitEnd(classOf[Checkout], Duration.ofMillis(300))
    )
    assertEquals(e.problem.status, 504, e.getMessage)
    assertEquals(state("w-slow").status, "awaiting")
  }

  test("the engine says where a workflow has got to: paused, then completed") {
    start("w-life", "cart-6")
    eventually("awaiting confirmation")(state("w-life").status == "awaiting")
    val paused = checkout("w-life").lifecycle(classOf[Checkout]).invoke()
    assert(paused.isPaused && !paused.isTerminal, paused.toString)
    checkout("w-life").call(KindRefs.confirmCheckout).invoke(): Unit
    checkout("w-life").awaitEnd(classOf[Checkout], longWait): Unit
    assert(checkout("w-life").lifecycle(classOf[Checkout]).invoke().isCompleted)
  }

  test("a command and a wait for the end, as one call, answer the state the workflow ended with") {
    val ended = checkout("w-one-call")
      .callThenAwaitEnd[
        Checkout,
        Checkout.State,
        Checkout.Start,
        com.thinkmorestupidless.ankka.javasdk.Done
      ](
        KindRefs.startCheckout,
        longWait
      )
      .invoke(Checkout.Start("cart-7", 200))
    assertEquals(ended.status, "expired")
  }

  test("a wait for a workflow that was deleted is refused, saying it was deleted") {
    start("w-cancel", "cart-8")
    eventually("awaiting confirmation")(state("w-cancel").status == "awaiting")
    checkout("w-cancel").call(KindRefs.cancelCheckout).invoke(): Unit
    val e = intercept[CommandException](checkout("w-cancel").awaitEnd(classOf[Checkout], longWait))
    val failure = e.workflowFailure.orElseThrow(() => AssertionError(s"no failure on $e"))
    assert(failure.deleted, failure.toString)
  }

  test("a view reads the workflow's state after each transition") {
    val rows = read.viewClient(kit.service.viewClient).forView(classOf[CheckoutRows])
    start("w-view", "cart-5", confirmWithinMillis = 200)
    checkout("w-view").awaitEnd(classOf[Checkout], longWait): Unit
    eventually("the expired row")(rows.get("w-view").map(_.status).orElse("") == "expired")
    assertEquals(rows.get("w-view").get.cartId, "cart-5")
  }

  test("a workflow declared wrongly stops the start, saying what is wrong with each") {
    val e = intercept[ServiceProblems](
      JavaServiceBuilder.read(
        Vector(
          classOf[BrokenWorkflows.MisshapenSteps],
          classOf[BrokenWorkflows.WrongSettings]
        ).map(_ -> None),
        Map.empty
      )
    )
    val messages = e.problems.asScala.map(_.toString).mkString("\n")
    assert(messages.contains("has the step name 'same', as one does"), messages)
    assert(messages.contains("takes 2 parameters; a step takes one or none"), messages)
    assert(messages.contains("returns a StepEffect but has no @Step"), messages)
    assert(messages.contains("Checkout::finish is not a @Step of WrongSettings"), messages)
  }

  test("a view reading a workflow as an entity is refused") {
    val e = intercept[ServiceProblems](
      JavaServiceBuilder.read(
        Vector(classOf[Checkout], classOf[broken.BrokenViews.ReadsWorkflowAsEntity]).map(_ -> None),
        Map.empty
      )
    )
    assert(
      e.getMessage.contains("but it is a workflow; read it with ChangeSource.ofWorkflow"),
      e.getMessage
    )
  }
