package shoppingcart

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.core.{Done, EntityId}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import shoppingcart.application.{CheckoutWorkflow, ShoppingCartEntity}
import shoppingcart.domain.LineItem

import scala.concurrent.duration.DurationInt

/**
 * The checkout workflow against real sharding, persistence and recovery.
 *
 * A workflow cannot be driven by a unit kit the way an entity can: the steps run asynchronously and
 * the engine's retries and failover are the behaviour under test, so this needs the whole runtime
 * and a real journal.
 */
class CheckoutWorkflowSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(Seq(ShoppingCartEntity.descriptor, CheckoutWorkflow.descriptor))

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def cart(id: String)     = testKit.componentClient.forEventSourcedEntity(EntityId(id))
  private def checkout(id: String) = testKit.componentClient.forWorkflow(EntityId(id))

  /** Starts the checkout and answers with the state it ended with: one call, nothing polled. */
  private def checkOut(id: String, mode: String) =
    checkout(id).call(CheckoutWorkflow.start).thenAwaitEnd(40.seconds).invoke(mode)

  private def fill(id: String): Unit =
    val _ = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 2))
    val _ = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p2", "Gadget", 1))

  test("a checkout reserves what the cart holds, charges, and checks the cart out") {
    fill("cart-ok")

    val state = checkOut("cart-ok", "ok")
    assertEquals(state.status, "charged")
    assertEquals(state.reserved, 3, "the reserve step should have read the cart's total quantity")
    // The charge step checked the cart out, and a checked-out cart keeps what it held.
    val checkedOut = cart("cart-ok").call(ShoppingCartEntity.getCart).invoke()
    assert(checkedOut.checkedOut, checkedOut.toString)
    assertEquals(checkedOut.totalQuantity, 3)
  }

  test("a declined charge is retried, fails over to compensation, and leaves the cart alone") {
    fill("cart-fail")

    // Compensation is how this workflow ends when the charge is declined: it completes, it does not fail.
    val state = checkOut("cart-fail", "fail")
    assertEquals(state.status, "compensated")
    assertEquals(state.reserved, 0, "compensation should release the reservation")
    // The charge threw before it could call the entity, so the cart is untouched and still buyable.
    assertEquals(cart("cart-fail").call(ShoppingCartEntity.totalQuantity).invoke(), 3)
  }

  test("a paused checkout resumes on its own timeout and still charges") {
    fill("cart-pause")

    // The wait step pauses for 1.5s with `charge` as its timeout target, so this proves the pause
    // resumes without anything nudging it — and that a wait goes on through a pause.
    assertEquals(checkOut("cart-pause", "pause").status, "charged")

    val checkedOut = cart("cart-pause").call(ShoppingCartEntity.getCart).invoke()
    assert(checkedOut.checkedOut, checkedOut.toString)
    assertEquals(checkedOut.totalQuantity, 3)
  }

  test("starting a checkout twice is refused") {
    fill("cart-twice")
    assertEquals(checkout("cart-twice").call(CheckoutWorkflow.start).invoke("ok"), Done)

    val error =
      intercept[Exception](checkout("cart-twice").call(CheckoutWorkflow.start).invoke("ok"))
    assert(
      error.getMessage.contains("already"),
      s"expected a refusal naming the current status, got: ${error.getMessage}"
    )
  }
