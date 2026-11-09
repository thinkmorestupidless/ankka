package shoppingcart

import com.thinkmorestupidless.ankka.testkit.{TestTransport, WorkflowTestKit}
import shoppingcart.application.{CheckoutWorkflow, ShoppingCartEntity}
import shoppingcart.domain.ShoppingCart

/**
 * The checkout workflow driven in memory: a command, then every step it transitions to, on this
 * thread, with the cart entity stubbed. The engine's own retries and failover apply as the
 * workflow's settings declare them, so a declined charge is seen to retry and then compensate with
 * no actor system and no database.
 */
class CheckoutWorkflowUnitSuite extends munit.FunSuite:

  // docs:start workflow-test
  test("a declined charge is retried once, then fails over to compensation") {
    val carts = TestTransport().stub(ShoppingCartEntity.totalQuantity)(() => 3)
    val kit   = WorkflowTestKit.of(CheckoutWorkflow, "c1", carts.client)

    val started = kit.call(CheckoutWorkflow.start)("fail")
    assertEquals(started.steps, Vector("reserve", "charge", "charge", "compensate"))
    assertEquals(kit.state.status, "compensated")
    assert(kit.lifecycle.isCompleted, kit.lifecycle.toString)
    // A second start is refused: the checkout is already compensated.
    assert(kit.call(CheckoutWorkflow.start)("ok").isError)
  }
  // docs:end workflow-test

  test("a checkout that pauses waits for its own timeout, then charges") {
    val carts = TestTransport()
      .stub(ShoppingCartEntity.totalQuantity)(() => 3)
      .stub(ShoppingCartEntity.checkout)(() => ShoppingCart.empty("c2").onCheckedOut)
    val kit = WorkflowTestKit.of(CheckoutWorkflow, "c2", carts.client)

    val paused = kit.call(CheckoutWorkflow.start)("pause")
    assertEquals(paused.steps, Vector("reserve", "wait"))
    assertEquals(kit.state.status, "waiting")
    assertEquals(paused.pauseTimeout.map(_._2), Some("charge"))

    val resumed = kit.timeOut()
    assertEquals(resumed.steps, Vector("charge"))
    assertEquals(kit.state.status, "charged")
  }
