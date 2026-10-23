package com.thinkmorestupidless.ankka.javasdk.samples

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, EntityId}
import com.thinkmorestupidless.ankka.javasdk.bridge.JavaServiceBuilder
import com.thinkmorestupidless.ankka.runtime.Observability
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.domain.LineItem

import scala.concurrent.duration.DurationInt

/**
 * What the Java facade costs a command (SC-009, R13): the Scala cart and the Java cart, each
 * started in turn under the same kit, take 1,000 `add-item` commands after 200 not counted, and the
 * median of the entity handler's spans is compared, as the tracing measures a handler. The medians
 * are printed; the 10% bound is asserted only under `-Dankka.cost.assert=true`, since a shared
 * machine's numbers say little about the facade.
 */
class FacadeCostSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private val Warmup   = 200
  private val Measured = 1000

  private val scalaCart: Seq[ComponentDescriptor] = Seq(ShoppingCartEntity.descriptor)

  private val javaCart: Seq[ComponentDescriptor] =
    shoppingcart.javasdk.Main.service() match
      case b: JavaServiceBuilder => JavaServiceBuilder.read(b.classes, b.serializers).descriptors
      case other                 => fail(s"$other is not a Java service")

  /** The median duration of `add-item` on the cart, in microseconds. */
  private def median(descriptors: Seq[ComponentDescriptor]): Double =
    val kit = AnkkaTestKit.start(descriptors)
    try
      val cart = kit.componentClient.forEventSourcedEntity(EntityId("cost"))
      def add(): Unit =
        cart.call(ShoppingCartEntity.addItem).invoke(LineItem("a", "Apple", 1)): Unit
      (1 to Warmup).foreach(_ => add())
      val observability = Observability(kit.service.system)
      val before        = observability.recorder.snapshot().map(_.spanId).toSet
      (1 to Measured).foreach(_ => add())
      val component = observability.names.intern("shopping-cart")
      val handler   = observability.names.intern("add-item")
      val durations = observability.recorder
        .snapshot()
        .filter(s => !before(s.spanId) && s.componentRef == component && s.handlerRef == handler)
        .map(_.durationNanos)
        .sorted
      assertEquals(durations.size, Measured, "one handler span per command")
      durations(durations.size / 2) / 1000.0
    finally kit.stop()

  test("the Java cart's handler costs within 10% of the Scala cart's") {
    // Two rounds, the first only to warm the JVM: the cart measured first is otherwise measured
    // on colder code, and the first measurement put the Java cart at half the Scala one's cost.
    val first = (median(scalaCart), median(javaCart))
    val scala = median(scalaCart)
    val java  = median(javaCart)
    val ratio = java / scala
    println(
      f"facade cost: Scala median $scala%.1f µs, Java median $java%.1f µs, ratio $ratio%.3f " +
        f"(first round, not counted: ${first._1}%.1f and ${first._2}%.1f µs)"
    )
    if sys.props.get("ankka.cost.assert").contains("true") then
      assert(
        ratio <= 1.10,
        f"the Java handler's median is ${(ratio - 1) * 100}%.1f%% above Scala's"
      )
  }
