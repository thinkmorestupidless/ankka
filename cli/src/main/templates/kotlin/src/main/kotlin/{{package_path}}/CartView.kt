package {{package}}

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource
import com.thinkmorestupidless.ankka.javasdk.component.View
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.orNull

// docs:start view
/** Every cart as a row, kept from the cart's events: how many of each product, and whether it is checked out. */
@ComponentId("cart-rows")
class CartRows : View<CartEvent, CartRows.CartRow>() {
    @Manifest("cart-row")
    data class CartRow(val cartId: String, val quantities: Map<String, Int>, val checkedOut: Boolean)

    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCart::class.java)

    override fun onChange(event: CartEvent): ViewEffect<CartRow> = effects {
        val row = rowState().orNull() ?: CartRow(updateContext().subject(), emptyMap(), false)
        when (event) {
            is CartEvent.ItemAdded -> {
                val productId = event.item.productId
                updateRow(row.copy(quantities = row.quantities + (productId to (row.quantities[productId] ?: 0) + event.item.quantity)))
            }
            is CartEvent.ItemRemoved -> updateRow(row.copy(quantities = row.quantities - event.productId))
            CartEvent.CheckedOut -> updateRow(row.copy(checkedOut = true))
            // The deletion that follows removes the row: a discarded cart leaves the view.
            CartEvent.Discarded -> ignore()
        }
    }
}
// docs:end view
