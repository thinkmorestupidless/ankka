package shoppingcart

import com.thinkmorestupidless.ankka.core.{Done, EntityId}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import shoppingcart.application.{CheckoutWorkflow, ShoppingCartEntity}
import shoppingcart.domain.LineItem

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * The checkout workflow against real sharding, persistence and recovery.
 *
 * A workflow cannot be driven by a unit kit the way an entity can: the steps run asynchronously and
 * the engine's retries and failover are the behaviour under test, so this needs the whole runtime
 * and a real journal.
 */
class CheckoutWorkflowSuite extends munit.FunSuite:

  override val munitTimeout = 5.minutes

  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(Seq(ShoppingCartEntity.descriptor, CheckoutWorkflow.descriptor))

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def cart(id: String)     = testKit.componentClient.forEventSourcedEntity(EntityId(id))
  private def checkout(id: String) = testKit.componentClient.forWorkflow(EntityId(id))

  private def eventually[A](description: String, within: FiniteDuration = 40.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  private def statusOf(id: String): String =
    checkout(id).call(CheckoutWorkflow.status).invoke().status

  /**
   * Retries on the value that changes, and asserts on it — never on a value read outside the wait.
   */
  private def awaitStatus(id: String, expected: String): Unit =
    val _ = eventually(s"checkout $id reaches '$expected'")(
      Option(statusOf(id)).filter(_ == expected)
    )

  private def fill(id: String): Unit =
    val _ = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 2))
    val _ = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p2", "Gadget", 1))

  test("a checkout reserves what the cart holds, charges, and checks the cart out") {
    fill("cart-ok")

    assertEquals(checkout("cart-ok").call(CheckoutWorkflow.start).invoke("ok"), Done)
    awaitStatus("cart-ok", "charged")

    val state = checkout("cart-ok").call(CheckoutWorkflow.status).invoke()
    assertEquals(state.reserved, 3, "the reserve step should have read the cart's total quantity")
    // The charge step checked the cart out, and checking out deletes it — so the id starts again
    // from the empty state rather than answering with the old contents.
    assertEquals(cart("cart-ok").call(ShoppingCartEntity.totalQuantity).invoke(), 0)
  }

  test("a declined charge is retried, fails over to compensation, and leaves the cart alone") {
    fill("cart-fail")

    assertEquals(checkout("cart-fail").call(CheckoutWorkflow.start).invoke("fail"), Done)
    awaitStatus("cart-fail", "compensated")

    val state = checkout("cart-fail").call(CheckoutWorkflow.status).invoke()
    assertEquals(state.reserved, 0, "compensation should release the reservation")
    // The charge threw before it could call the entity, so the cart is untouched and still buyable.
    assertEquals(cart("cart-fail").call(ShoppingCartEntity.totalQuantity).invoke(), 3)
  }

  test("a paused checkout resumes on its own timeout and still charges") {
    fill("cart-pause")

    assertEquals(checkout("cart-pause").call(CheckoutWorkflow.start).invoke("pause"), Done)
    // The wait step pauses for 1.5s with `charge` as its timeout target, so this proves the pause
    // resumes without anything nudging it.
    awaitStatus("cart-pause", "charged")

    assertEquals(cart("cart-pause").call(ShoppingCartEntity.totalQuantity).invoke(), 0)
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
