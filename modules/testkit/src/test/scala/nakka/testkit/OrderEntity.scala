package nakka.testkit

import nakka.core.*
import nakka.core.Serializers.given
import nakka.sdk.*

/** An order that expires unless it is confirmed in time. */
final case class Order(item: String, status: String)

final class OrderEntity(context: KeyValueEntityContext) extends KeyValueEntity[Order]:

  private val orderId: String = context.entityId

  def emptyState: Order = Order("", "none")

  def place(item: String): Effect[Done] =
    if currentState.status != "none" then
      effects.error(s"order '$orderId' was already placed", ErrorCode.Conflict)
    else effects.updateState(Order(item, "pending")).thenReply(_ => Done)

  def confirm: Effect[Done] =
    if currentState.status != "pending" then
      effects.error(s"cannot confirm an order that is '${currentState.status}'", ErrorCode.Conflict)
    else effects.updateState(currentState.copy(status = "confirmed")).thenReply(_ => Done)

  /**
   * Cancels a pending order, and reports success for one already settled.
   *
   * The idempotence matters: this is what a timer calls, and a timer that "fails" because the order
   * was confirmed a moment earlier would retry forever.
   */
  def cancel: Effect[String] =
    if currentState.status == "pending" then
      effects.updateState(currentState.copy(status = "cancelled")).thenReply(_ => "cancelled")
    else effects.reply(s"already ${currentState.status}")

  def status: ReadOnlyEffect[String] = effects.reply(currentState.status)

object OrderEntity
    extends KeyValueEntity.Companion[OrderEntity, Order](
      componentId = ComponentId("order"),
      stateSerializer = Codecs.serializer[Order]("order")
    ):
  def create(context: KeyValueEntityContext) = new OrderEntity(context)

  val place   = command("place")(_.place)
  val confirm = command("confirm")(_.confirm)
  val cancel  = command("cancel")(_.cancel)
  val status  = query("status")(_.status)
