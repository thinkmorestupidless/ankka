package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.Describe
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest

/** One line of a cart, field for field the Scala reference's. */
@Manifest("line-item")
data class LineItem(
    @Describe("The product's id") val productId: String,
    @Describe("The product's name") val name: String,
    @Describe("How many; greater than zero") val quantity: Int,
)

/** A cart's state, field for field the Scala reference's: what makes the journal shared. */
@Manifest("shopping-cart")
data class ShoppingCart(val cartId: String, val items: List<LineItem>, val checkedOut: Boolean) {

    /** The item added, merged with a line for the same product, the lines kept by product. */
    fun addItem(item: LineItem): ShoppingCart {
        val merged = items.find { it.productId == item.productId }?.let { item.copy(quantity = it.quantity + item.quantity) } ?: item
        return copy(items = (withoutProduct(item.productId) + merged).sortedBy { it.productId })
    }

    fun removeItem(productId: String): ShoppingCart = copy(items = withoutProduct(productId))

    fun checkOut(): ShoppingCart = copy(checkedOut = true)

    fun holds(productId: String): Boolean = items.any { it.productId == productId }

    val totalQuantity: Int
        get() = items.sumOf { it.quantity }

    private fun withoutProduct(productId: String) = items.filterNot { it.productId == productId }

    companion object {
        fun empty(cartId: String) = ShoppingCart(cartId, emptyList(), false)
    }
}

/** The Scala reference's `ShoppingCartEvent`, case for case. */
@Manifest("shopping-cart-event")
sealed interface CartEvent {
    data class ItemAdded(val item: LineItem) : CartEvent

    data class ItemRemoved(val productId: String) : CartEvent

    data object CheckedOut : CartEvent

    data object Discarded : CartEvent
}
