package shoppingcart.application

import nakka.core.{Codecs, ComponentId, Serializer}
import nakka.sdk.*
import shoppingcart.domain.ShoppingCartEvent
import shoppingcart.domain.ShoppingCartEvent.*

/** What leaves the service when a cart is checked out. */
final case class CheckoutNotice(cartId: String, at: Long)

/**
 * Turns an internal event into a published one.
 *
 * The cart's own event type is an implementation detail; this consumer decides which
 * events are worth telling the outside world about and in what shape — so the domain
 * stays free to change without breaking downstream consumers.
 */
final class CheckoutNotifier extends Consumer[ShoppingCartEvent, CheckoutNotice]:

  def onMessage(event: ShoppingCartEvent): Effect = event match
    case CheckedOut =>
      effects.produce(CheckoutNotice(messageContext.subject, System.currentTimeMillis()))
    case ItemAdded(_) | ItemRemoved(_) =>
      effects.ignore()

object CheckoutNotifier
    extends Consumer.Companion[CheckoutNotifier, ShoppingCartEvent, CheckoutNotice](
      componentId = ComponentId("checkout-notifier"),
      source = ChangeSource.eventsOf(ShoppingCartEntity)
    ):
  def create(ctx: ConsumerContext) = new CheckoutNotifier

  override val outputSerializer: Option[Serializer[CheckoutNotice]] =
    Some(Codecs.serializer[CheckoutNotice]("checkout-notice"))

  override val produceTo: Option[String] = Some("cart-checkouts")
