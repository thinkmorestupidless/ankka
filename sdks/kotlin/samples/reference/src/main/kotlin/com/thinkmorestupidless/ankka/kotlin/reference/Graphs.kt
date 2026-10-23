package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource
import com.thinkmorestupidless.ankka.javasdk.component.GraphConsumer
import com.thinkmorestupidless.ankka.javasdk.effect.GraphEffect
import com.thinkmorestupidless.ankka.javasdk.effect.GraphElement
import com.thinkmorestupidless.ankka.kotlin.dsl.effects

// A graph consumer's effects have no block form in the Kotlin SDK; they are the Java SDK's.

/**
 * The carts as a graph: the cart's node for an item added or removed, the cart checked out with
 * its checkout and the edge between them for a checkout, the cart's tombstone when it is deleted.
 */
@ComponentId("cart-graph")
class CartGraph : GraphConsumer<CartEvent>() {

    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCartEntity::class.java)

    override fun topic(): String = "conformance-graph"

    private fun cart(id: String, checkedOut: Boolean): GraphElement =
        graph().node("cart:$id", listOf("Cart"), mapOf("cartId" to id, "checkedOut" to checkedOut))

    override fun onChange(event: CartEvent): GraphEffect = effects {
        val id = messageContext().subject()
        when (event) {
            is CartEvent.ItemAdded, is CartEvent.ItemRemoved -> publish(cart(id, false))
            CartEvent.CheckedOut ->
                publish(
                    cart(id, true),
                    graph().node("checkout:$id", listOf("Checkout"), mapOf("cartId" to id)),
                    graph().edge("checked-out:$id", "CHECKED_OUT", "cart:$id", "checkout:$id", emptyMap()),
                )
            CartEvent.Discarded -> ignore()
        }
    }

    override fun onDelete(): GraphEffect = effects { publish(graph().tombstoneNode("cart:${messageContext().subject()}")) }
}

/** The key value entity as a graph: its node at each state, its tombstone after. */
@ComponentId("profile-graph")
class ProfileGraph : GraphConsumer<Profile.ProfileState>() {

    override fun source(): ChangeSource<Profile.ProfileState> = ChangeSource.of(Profile::class.java)

    override fun topic(): String = "conformance-profile-graph"

    override fun onChange(state: Profile.ProfileState): GraphEffect = effects {
        publish(graph().node("profile:${messageContext().subject()}", listOf("Profile"), mapOf("name" to state.name)))
    }

    override fun onDelete(): GraphEffect = effects { publish(graph().tombstoneNode("profile:${messageContext().subject()}")) }
}
