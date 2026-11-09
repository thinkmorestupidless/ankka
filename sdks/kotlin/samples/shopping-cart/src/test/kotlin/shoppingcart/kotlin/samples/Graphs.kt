package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource
import com.thinkmorestupidless.ankka.javasdk.component.Consumer
import com.thinkmorestupidless.ankka.javasdk.component.GraphConsumer
import com.thinkmorestupidless.ankka.javasdk.component.Publication
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.javasdk.effect.GraphEffect
import com.thinkmorestupidless.ankka.javasdk.effect.GraphElement
import com.thinkmorestupidless.ankka.javasdk.effect.Outgoing
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import java.util.Optional
import shoppingcart.kotlin.CartEvent
import shoppingcart.kotlin.ShoppingCart

/** The cart as a graph: a node per cart, and a checkout node joined to it once it is checked out. */
@ComponentId("cart-graph")
class CartGraph : GraphConsumer<CartEvent>() {
    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCart::class.java)

    override fun topic(): String = "cart-graph"

    private fun cart(id: String, checkedOut: Boolean): GraphElement =
        graph().node("cart:$id", listOf("Cart"), mapOf("cartId" to id, "checkedOut" to checkedOut))

    override fun onChange(event: CartEvent): GraphEffect {
        val id = messageContext().subject()
        return effects {
            when (event) {
                is CartEvent.ItemAdded -> publish(cart(id, false))
                is CartEvent.ItemRemoved -> publish(cart(id, false))
                CartEvent.CheckedOut ->
                    publish(
                        cart(id, true),
                        graph().node("checkout:$id", listOf("Checkout"), mapOf("cartId" to id)),
                        graph().edge("checked-out:$id", "CHECKED_OUT", "cart:$id", "checkout:$id", emptyMap()),
                    )
                CartEvent.Discarded -> ignore()
            }
        }
    }

    override fun onDelete(): GraphEffect = effects { publish(graph().tombstoneNode("cart:${messageContext().subject()}")) }
}

// docs:start graph-from-state
/**
 * Publishes what each cart holds, as a node of its own beside the cart's.
 *
 * An event says what changed: one item added. An element is its whole state: how many lines the
 * cart has now. So this consumer does not build the element from the event. It reads the cart
 * through the component client and publishes what it finds, at the version of the event it is
 * handling.
 *
 * The cart it reads may already be ahead of that event. The delta then carries a later state at an
 * earlier version, and the events still to come publish it again at their own: the graph is never
 * behind for longer than the consumer is, and ends where the cart is.
 */
@ComponentId("cart-contents-graph")
class CartContentsGraph : GraphConsumer<CartEvent>() {
    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCart::class.java)

    override fun topic(): String = "cart-graph"

    override fun onChange(event: CartEvent): GraphEffect {
        if (event == CartEvent.Discarded) return effects { ignore() }
        val cartId = messageContext().subject()
        val cart = componentClient().forEventSourcedEntity(cartId).call(ShoppingCart::getCart).invoke()
        return effects {
            publish(
                graph().node(
                    "cart-contents:$cartId",
                    listOf("CartContents"),
                    mapOf("cartId" to cartId, "lines" to cart.items.size, "quantity" to cart.totalQuantity),
                )
            )
        }
    }

    override fun onDelete(): GraphEffect = effects {
        publish(graph().tombstoneNode("cart-contents:${messageContext().subject()}"))
    }
}
// docs:end graph-from-state

/** One of the three messages a checkout fans out into. */
data class Fanned(val n: Int)

/**
 * Three messages for a checkout (the second under a key of its own, the third with a header), none
 * for an item added, and a single one for an item removed.
 */
@ComponentId("checkout-fanout")
class CheckoutFanout : Consumer<CartEvent, Fanned>() {
    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCart::class.java)

    override fun publishes(): Optional<Publication> = Optional.of(Publication.to("cart-fanout"))

    override fun onChange(event: CartEvent): ConsumerEffect<Fanned> = effects {
        when (event) {
            is CartEvent.ItemAdded -> publishAll(emptyList())
            is CartEvent.ItemRemoved -> publish(Fanned(0))
            CartEvent.CheckedOut ->
                publishMessages(
                    listOf(
                        Outgoing.of(Fanned(1)),
                        Outgoing.of(Fanned(2)).withKey("second:${messageContext().subject()}"),
                        Outgoing.of(Fanned(3)).withMetadata("x-n", "3"),
                    )
                )
            CartEvent.Discarded -> ignore()
        }
    }
}
