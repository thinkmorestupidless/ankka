package {{package}}

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.kotlin.dsl.effects

// docs:start domain
/** A quantity of one product in a cart. */
@Manifest("line-item")
data class LineItem(val productId: String, val name: String, val quantity: Int)

/** A cart: its lines, ordered by product, and whether it has been checked out. */
@Manifest("shopping-cart")
data class Cart(val cartId: String, val items: List<LineItem>, val checkedOut: Boolean) {
    /** Adds [item], merging it into the line for the same product. */
    fun addItem(item: LineItem): Cart {
        val merged = items.find { it.productId == item.productId }?.let { item.copy(quantity = it.quantity + item.quantity) } ?: item
        return copy(items = (without(item.productId) + merged).sortedBy { it.productId })
    }

    fun removeItem(productId: String) = copy(items = without(productId))

    fun contains(productId: String) = items.any { it.productId == productId }

    val totalQuantity: Int
        get() = items.sumOf { it.quantity }

    private fun without(productId: String) = items.filterNot { it.productId == productId }

    companion object {
        fun empty(cartId: String) = Cart(cartId, emptyList(), false)
    }
}

/** What happened to a cart: the journal holds these, as JSON. */
@Manifest("shopping-cart-event")
sealed interface CartEvent {
    data class ItemAdded(val item: LineItem) : CartEvent

    data class ItemRemoved(val productId: String) : CartEvent

    data object CheckedOut : CartEvent

    data object Discarded : CartEvent
}
// docs:end domain

// docs:start entity
/** A customer's cart, one per cart id. Each handler's wire name is declared apart from its name. */
@ComponentId("shopping-cart")
class ShoppingCart : EventSourcedEntity<Cart, CartEvent>() {
    override fun emptyState() = Cart.empty(context().entityId())

    @Command("add-item")
    fun addItem(item: LineItem): Effect<Done> = effects {
        when {
            currentState().checkedOut -> error(checkedOut)
            item.quantity <= 0 -> error("quantity must be greater than zero, was ${item.quantity}")
            else -> persist(CartEvent.ItemAdded(item)).thenDone()
        }
    }

    @Command("remove-item")
    fun removeItem(productId: String): Effect<Done> = effects {
        when {
            currentState().checkedOut -> error(checkedOut)
            !currentState().contains(productId) -> error(Problem.notFound("cart does not contain '$productId'"))
            else -> persist(CartEvent.ItemRemoved(productId)).thenDone()
        }
    }

    @Command("checkout")
    fun checkout(): Effect<Cart> = effects {
        when {
            currentState().checkedOut -> error(checkedOut)
            currentState().items.isEmpty() -> error("cannot check out an empty cart")
            else -> persist(CartEvent.CheckedOut).thenReplyState()
        }
    }

    @Command("discard")
    fun discard(): Effect<Done> = effects {
        if (currentState().checkedOut) error(checkedOut) else persist(CartEvent.Discarded).thenDelete().thenDone()
    }

    @Query("get-cart")
    fun getCart(): ReadOnlyEffect<Cart> = effects { reply(currentState()) }

    @Query("total-quantity")
    fun totalQuantity(): ReadOnlyEffect<Int> = effects { reply(currentState().totalQuantity) }

    override fun applyEvent(state: Cart, event: CartEvent): Cart =
        when (event) {
            is CartEvent.ItemAdded -> state.addItem(event.item)
            is CartEvent.ItemRemoved -> state.removeItem(event.productId)
            CartEvent.CheckedOut -> state.copy(checkedOut = true)
            CartEvent.Discarded -> state
        }

    private val checkedOut: Problem
        get() = Problem.conflict("cart is already checked out")
}
// docs:end entity
