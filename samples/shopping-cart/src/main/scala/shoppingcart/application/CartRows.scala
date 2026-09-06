package shoppingcart.application

import nakka.core.{Codecs, ComponentId}
import nakka.sdk.*
import shoppingcart.domain.ShoppingCartEvent
import shoppingcart.domain.ShoppingCartEvent.*

/**
 * A queryable projection of every cart.
 *
 * The entity can only be found by cart id. This view exists to answer the questions it
 * cannot: which carts contain a product, which have been checked out, which are the
 * largest.
 */
final case class CartRow(
    cartId: String,
    quantities: Map[String, Int],
    checkedOut: Boolean
):
  def productIds: List[String] = quantities.keys.toList.sorted
  def totalQuantity: Int       = quantities.values.sum

final class CartRowsView extends View[ShoppingCartEvent, CartRow]:

  def onChange(event: ShoppingCartEvent): Effect =
    val current = rowState.getOrElse(CartRow(updateContext.subject, Map.empty, checkedOut = false))
    event match
      case ItemAdded(item) =>
        val existing = current.quantities.getOrElse(item.productId, 0)
        effects.updateRow(
          current.copy(quantities = current.quantities.updated(item.productId, existing + item.quantity))
        )
      case ItemRemoved(productId) =>
        effects.updateRow(current.copy(quantities = current.quantities - productId))
      case CheckedOut =>
        effects.updateRow(current.copy(checkedOut = true))

  /**
   * Keeps the row after the cart is deleted.
   *
   * Checkout deletes the entity, but a checked-out cart is exactly what an order history
   * needs. This is the tombstone case: the row outlives the entity that produced it.
   */
  override def onDelete: Effect =
    rowState match
      case Some(row) => effects.updateRow(row.copy(checkedOut = true))
      case None      => effects.ignore()

object CartRows
    extends View.Companion[CartRowsView, ShoppingCartEvent, CartRow](
      componentId = ComponentId("cart-rows"),
      source = ChangeSource.eventsOf(ShoppingCartEntity),
      rowSerializer = Codecs.serializer[CartRow]("cart-row")
    ):
  def create(ctx: ViewComponentContext) = new CartRowsView
