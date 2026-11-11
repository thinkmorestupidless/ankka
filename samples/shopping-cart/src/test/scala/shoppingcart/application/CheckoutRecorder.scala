package shoppingcart.application

// docs:start checkout-recorder
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import shoppingcart.domain.ShoppingCartEvent
import shoppingcart.domain.ShoppingCartEvent.*

final class CheckoutRecorder(client: ComponentClient) extends Consumer[ShoppingCartEvent, Nothing]:

  def onMessage(event: ShoppingCartEvent): Effect = event match
    case CheckedOut =>
      client
        .forKeyValueEntity(EntityId(messageContext.subject))
        .call(CheckoutLog.record)
        .invoke(System.currentTimeMillis()): Unit
      effects.done()
    case _ => effects.ignore()

object CheckoutRecorder
    extends Consumer.Companion[CheckoutRecorder, ShoppingCartEvent, Nothing](
      componentId = ComponentId("checkout-recorder"),
      source = ChangeSource.eventsOf(ShoppingCartEntity)
    ):
  def create(ctx: ConsumerContext) = new CheckoutRecorder(ctx.componentClient)
// docs:end checkout-recorder
