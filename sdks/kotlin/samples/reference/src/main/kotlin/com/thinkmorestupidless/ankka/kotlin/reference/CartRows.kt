package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource
import com.thinkmorestupidless.ankka.javasdk.component.View
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.orNull

/** One row per cart: how many of each product, and whether it is checked out. */
@ComponentId("cart-rows")
class CartRows : View<CartEvent, CartRows.CartRow>() {

    @Manifest("cart-row")
    data class CartRow(val cartId: String, val quantities: Map<String, Int>, val checkedOut: Boolean)

    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCartEntity::class.java)

    override fun onChange(event: CartEvent): ViewEffect<CartRow> = effects {
        val current = rowState().orNull() ?: CartRow(updateContext().subject(), emptyMap(), false)
        when (event) {
            is CartEvent.ItemAdded -> {
                val (productId, _, quantity) = event.item
                updateRow(current.copy(quantities = current.quantities + (productId to (current.quantities[productId] ?: 0) + quantity)))
            }
            is CartEvent.ItemRemoved -> updateRow(current.copy(quantities = current.quantities - event.productId))
            CartEvent.CheckedOut -> updateRow(current.copy(checkedOut = true))
            // The deletion that follows removes the row, by the view's default deletion handler.
            CartEvent.Discarded -> ignore()
        }
    }
}
