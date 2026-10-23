package shoppingcart.kotlin

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource
import com.thinkmorestupidless.ankka.javasdk.component.Consumer
import com.thinkmorestupidless.ankka.javasdk.component.Publication
import com.thinkmorestupidless.ankka.javasdk.component.View
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.orNull
import java.util.Optional

// docs:start view
/**
 * Every cart as a row, to answer what the entity cannot: which carts hold a product, which are
 * checked out. The row's JSON is the Scala cart's `cart-row`.
 */
@ComponentId("cart-rows")
class CartRows : View<CartEvent, CartRows.CartRow>() {
    @Manifest("cart-row")
    data class CartRow(
        val cartId: String,
        val quantities: Map<String, Int>,
        val checkedOut: Boolean,
        val names: Map<String, String>,
    )

    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCart::class.java)

    override fun onChange(event: CartEvent): ViewEffect<CartRow> = effects {
        val row = rowState().orNull() ?: CartRow(updateContext().subject(), emptyMap(), false, emptyMap())
        when (event) {
            is CartEvent.ItemAdded ->
                updateRow(
                    row.copy(
                        quantities = row.quantities + (event.item.productId to (row.quantities[event.item.productId] ?: 0) + event.item.quantity),
                        names = row.names + (event.item.productId to event.item.name),
                    )
                )
            is CartEvent.ItemRemoved ->
                updateRow(row.copy(quantities = row.quantities - event.productId, names = row.names - event.productId))
            CartEvent.CheckedOut -> updateRow(row.copy(checkedOut = true))
            // The deletion that follows removes the row: a discarded cart leaves the listing.
            CartEvent.Discarded -> ignore()
        }
    }
}
// docs:end view

/**
 * Turns the cart's checkout into a notice on the `cart-checkouts` topic. The cart's own events stay
 * an implementation detail; the notice is what the outside world is told.
 */
@ComponentId("checkout-notifier")
class CheckoutNotifier : Consumer<CartEvent, CheckoutNotifier.CheckoutNotice>() {
    /** What leaves the service when a cart is checked out. */
    @Manifest("checkout-notice")
    data class CheckoutNotice(val cartId: String, val at: Long)

    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCart::class.java)

    override fun publishes(): Optional<Publication> = Optional.of(Publication.to(TOPIC))

    override fun onChange(event: CartEvent): ConsumerEffect<CheckoutNotice> = effects {
        if (event == CartEvent.CheckedOut) publish(CheckoutNotice(messageContext().subject(), System.currentTimeMillis()))
        else ignore()
    }

    companion object {
        /** The topic checkout notices are published to, and read from. */
        const val TOPIC = "cart-checkouts"
    }
}

/**
 * The checkouts read back from the topic they are published to, and how many notices of each were
 * read: one, unless a reader started over from the beginning.
 */
@ComponentId("checkouts-seen")
class CheckoutsSeen : View<CheckoutNotifier.CheckoutNotice, CheckoutsSeen.CheckoutSeen>() {
    @Manifest("checkout-seen")
    data class CheckoutSeen(val cartId: String, val at: Long, val notices: Int)

    override fun source(): ChangeSource<CheckoutNotifier.CheckoutNotice> =
        ChangeSource.ofTopic(CheckoutNotifier.TOPIC, CheckoutNotifier.CheckoutNotice::class.java)

    override fun onChange(event: CheckoutNotifier.CheckoutNotice): ViewEffect<CheckoutSeen> = effects {
        updateRow(CheckoutSeen(event.cartId, event.at, (rowState().orNull()?.notices ?: 0) + 1))
    }
}
