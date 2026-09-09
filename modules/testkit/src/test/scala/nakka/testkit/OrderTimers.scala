package nakka.testkit

import nakka.core.*
import nakka.core.Serializers.given
import nakka.sdk.*

/**
 * Expires orders that were never confirmed.
 *
 * Note what it does *not* do: fail when the order has already been confirmed. A timed action that
 * errors gets rescheduled, so "there was nothing to do" has to be reported as success.
 */
final class OrderTimers(context: TimedActionContext) extends TimedAction:

  private val client = context.componentClient

  def expireOrder(orderId: String): Effect =
    val outcome = client.forKeyValueEntity(EntityId(orderId)).call(OrderEntity.cancel).invoke()
    OrderTimers.observed.add(s"$orderId:$outcome"): Unit
    effects.done()

  /** Always fails, to exercise the backoff path. */
  def alwaysFails(orderId: String): Effect =
    OrderTimers.observed.add(s"$orderId:attempt-${context.previousAttempts}"): Unit
    effects.error("this timer never succeeds")

object OrderTimers extends TimedAction.Companion[OrderTimers](ComponentId("order-timers")):

  /** Records what fired, so tests can assert on it. */
  val observed: java.util.concurrent.ConcurrentLinkedQueue[String] =
    java.util.concurrent.ConcurrentLinkedQueue[String]()

  def create(context: TimedActionContext) = new OrderTimers(context)

  val expireOrder = handler("expire-order")(_.expireOrder)
  val alwaysFails = handler("always-fails")(_.alwaysFails)
