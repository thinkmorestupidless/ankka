package shoppingcart

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import shoppingcart.application.{CheckoutLog, CheckoutRecorder, ShoppingCartEntity}
import shoppingcart.domain.LineItem

import scala.concurrent.duration.DurationInt

/** A consumer that calls another component: every checkout is recorded in the checkout log. */
class CheckoutRecorderSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  test("a checked-out cart is recorded in its checkout log, and an added item is not") {
    val testKit = AnkkaTestKit.start(
      Seq(ShoppingCartEntity.descriptor, CheckoutLog.descriptor, CheckoutRecorder.descriptor),
      Seq(ProjectionRuntime())
    )
    try
      val client          = testKit.componentClient
      def log(id: String) = client.forKeyValueEntity(EntityId(id)).call(CheckoutLog.get).invoke()
      val cart            = client.forEventSourcedEntity(EntityId("recorded"))
      val _               = cart.call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Pen", 1))
      val _               = client
        .forEventSourcedEntity(EntityId("only-added"))
        .call(ShoppingCartEntity.addItem)
        .invoke(LineItem("p1", "Pen", 1))
      val _ = cart.call(ShoppingCartEntity.checkout).invoke()

      // The consumer records after the cart has replied: poll the log until a deadline.
      val deadline = System.nanoTime() + 30.seconds.toNanos
      var recorded = log("recorded")
      while !recorded.notified && System.nanoTime() < deadline do
        Thread.sleep(100)
        recorded = log("recorded")
      assert(recorded.notified, s"the checkout was not recorded: $recorded")
      assert(recorded.at > 0L, recorded.toString)
      assert(!log("only-added").notified)
    finally testKit.stop()
  }
