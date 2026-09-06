package shoppingcart.application

import nakka.core.*
import nakka.core.Serializers.given
import nakka.sdk.*
import shoppingcart.domain.*
import shoppingcart.domain.ShoppingCartEvent.*

/**
 * The application layer: connects the cart domain to the nakka runtime.
 *
 * Every handler is ordinary sequential code returning a description of what should
 * happen. Nothing here knows about sharding, Postgres, replay or JSON.
 */
final class ShoppingCartEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[ShoppingCart, ShoppingCartEvent]:

  private val cartId: String = context.entityId

  def emptyState: ShoppingCart = ShoppingCart.empty(cartId)

  /** The only place state changes, and the only thing replay needs to be correct. */
  def applyEvent(event: ShoppingCartEvent): ShoppingCart = event match
    case ItemAdded(item)        => currentState.addItem(item)
    case ItemRemoved(productId) => currentState.removeItem(productId)
    case CheckedOut             => currentState.onCheckedOut

  def addItem(item: LineItem): Effect[Done] =
    if currentState.checkedOut then
      effects.error("cart is already checked out", ErrorCode.Conflict)
    else if item.quantity <= 0 then
      effects.error(s"quantity must be greater than zero, was ${item.quantity}")
    else effects.persist(ItemAdded(item)).thenReply(_ => Done)

  def removeItem(productId: String): Effect[Done] =
    if currentState.checkedOut then
      effects.error("cart is already checked out", ErrorCode.Conflict)
    else if !currentState.contains(productId) then
      effects.error(s"cart does not contain '$productId'", ErrorCode.NotFound)
    else effects.persist(ItemRemoved(productId)).thenReply(_ => Done)

  /**
   * Records the checkout and then deletes the cart.
   *
   * The event is persisted before the deletion takes effect, so a consumer or view
   * downstream still observes that this cart was checked out rather than merely vanishing.
   */
  def checkout: Effect[ShoppingCart] =
    if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
    else if currentState.isEmpty then effects.error("cannot check out an empty cart")
    else effects.persist(CheckedOut).deleteEntity().thenReplyState

  def getCart: ReadOnlyEffect[ShoppingCart] = effects.reply(currentState)

  def totalQuantity: ReadOnlyEffect[Int] = effects.reply(currentState.totalQuantity)

object ShoppingCartEntity
    extends EventSourcedEntity.Companion[ShoppingCartEntity, ShoppingCart, ShoppingCartEvent](
      componentId = ComponentId("shopping-cart"),
      stateSerializer = Codecs.serializer[ShoppingCart]("shopping-cart"),
      eventSerializer = Codecs.serializer[ShoppingCartEvent]("shopping-cart-event")
    ):

  /**
   * `LineItem` crosses the wire as a command argument, so it needs a manifest of its
   * own. Declared before the handlers below because object initialisation runs in order.
   */
  given Serializer[LineItem] = Codecs.serializer[LineItem]("line-item")

  def create(context: EventSourcedEntityContext) = new ShoppingCartEntity(context)

  val addItem       = command("add-item")(_.addItem)
  val removeItem    = command("remove-item")(_.removeItem)
  val checkout      = command("checkout")(_.checkout)
  val getCart       = query("get-cart")(_.getCart)
  val totalQuantity = query("total-quantity")(_.totalQuantity)
