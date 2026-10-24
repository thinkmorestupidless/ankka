package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.SnapshotEvery
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.kotlin.dsl.effects

/** The cart: the Scala reference's `ShoppingCartEntity`, handler for handler. */
@ComponentId("shopping-cart")
@SnapshotEvery(3)
class ShoppingCartEntity : EventSourcedEntity<ShoppingCart, CartEvent>() {

    override fun emptyState(): ShoppingCart = ShoppingCart.empty(context().entityId())

    override fun applyEvent(state: ShoppingCart, event: CartEvent): ShoppingCart =
        when (event) {
            is CartEvent.ItemAdded -> state.addItem(event.item)
            is CartEvent.ItemRemoved -> state.removeItem(event.productId)
            CartEvent.CheckedOut -> state.checkOut()
            CartEvent.Discarded -> state
        }

    @Command("add-item")
    fun addItem(item: LineItem): Effect<Done> = effects {
        when {
            currentState().checkedOut -> error(CHECKED_OUT)
            item.quantity <= 0 -> error("quantity must be greater than zero")
            else -> persist(CartEvent.ItemAdded(item)).thenDone()
        }
    }

    @Command("remove-item")
    fun removeItem(productId: String): Effect<Done> = effects {
        when {
            currentState().checkedOut -> error(CHECKED_OUT)
            !currentState().holds(productId) -> error(Problem.notFound("cart does not contain '$productId'"))
            else -> persist(CartEvent.ItemRemoved(productId)).thenDone()
        }
    }

    @Command("checkout")
    fun checkout(): Effect<ShoppingCart> = effects {
        when {
            currentState().checkedOut -> error(CHECKED_OUT)
            currentState().items.isEmpty() -> error("cannot check out an empty cart")
            else -> persist(CartEvent.CheckedOut).thenReplyState()
        }
    }

    @Command("discard")
    fun discard(): Effect<Done> = effects {
        if (currentState().checkedOut) error(CHECKED_OUT) else persist(CartEvent.Discarded).thenDelete().thenDone()
    }

    @Query("get-cart")
    fun getCart(): ReadOnlyEffect<ShoppingCart> = effects { reply(currentState()) }

    @Query("total-quantity")
    fun totalQuantity(): ReadOnlyEffect<Int> = effects { reply(currentState().totalQuantity) }

    private companion object {
        val CHECKED_OUT: Problem = Problem.conflict("cart is already checked out")
    }
}
