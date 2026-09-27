package shoppingcart.domain

/**
 * The shopping cart domain.
 *
 * Plain Scala with no ankka types anywhere — no effects, no entity base class, no serializers. That
 * is the point of the domain layer: these rules can be tested with nothing running, and they would
 * survive the platform underneath them being replaced.
 */
final case class LineItem(productId: String, name: String, quantity: Int)

final case class ShoppingCart(
    cartId: String,
    items: List[LineItem],
    checkedOut: Boolean
):

  /** Adds a line, folding the quantity into an existing line for the same product. */
  def addItem(item: LineItem): ShoppingCart =
    val merged = items.find(_.productId == item.productId) match
      case Some(existing) => item.copy(quantity = existing.quantity + item.quantity)
      case None           => item
    // Sorted so that two carts holding the same products are equal regardless of the
    // order they were added — which is what makes replay assertions stable.
    copy(items = (merged :: items.filterNot(_.productId == item.productId)).sortBy(_.productId))

  def removeItem(productId: String): ShoppingCart =
    copy(items = items.filterNot(_.productId == productId))

  def onCheckedOut: ShoppingCart = copy(checkedOut = true)

  def contains(productId: String): Boolean = items.exists(_.productId == productId)

  def totalQuantity: Int = items.map(_.quantity).sum

  def isEmpty: Boolean = items.isEmpty

object ShoppingCart:
  def empty(cartId: String): ShoppingCart = ShoppingCart(cartId, Nil, checkedOut = false)

// docs:start events
/** Everything that can happen to a cart. */
enum ShoppingCartEvent:
  case ItemAdded(item: LineItem)
  case ItemRemoved(productId: String)
  case CheckedOut
// docs:end events

/**
 * What the checkout log holds for one cart: when it was checked out, and whether anything was told.
 *
 * Field for field the Python and TypeScript carts' `CheckoutRecord`, because the three samples are
 * the same service written three times and a journal written by one must replay in the others.
 */
final case class CheckoutRecord(cartId: String, at: Long = 0L, notified: Boolean = false)
