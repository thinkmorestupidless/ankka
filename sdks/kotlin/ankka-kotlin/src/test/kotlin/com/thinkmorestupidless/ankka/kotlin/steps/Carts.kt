package com.thinkmorestupidless.ankka.kotlin.steps

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.Step
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.component.Workflow
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.kotlin.call
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.dsl.stepEffects
import com.thinkmorestupidless.ankka.kotlin.dsl.thenReply
import com.thinkmorestupidless.ankka.kotlin.dsl.transitionTo
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

// The services of features/java-kotlin, written in Kotlin. Each state and event has the shape and
// manifest of JavaCart's, so either language reads what the other recorded.

@Manifest("item")
data class Item(val name: String)

@Manifest("cart-state")
data class CartState(val items: List<Item>)

@Manifest("cart-event")
sealed interface CartEvent {
    data class Added(val item: Item) : CartEvent
}

private fun CartState.apply(event: CartEvent) =
    when (event) {
        is CartEvent.Added -> copy(items = items + event.item)
    }

/** The cart in Kotlin's own form. */
@ComponentId("cart")
class KotlinCart : EventSourcedEntity<CartState, CartEvent>() {
    override fun emptyState() = CartState(emptyList())

    override fun applyEvent(state: CartState, event: CartEvent) = state.apply(event)

    @Command("add-item")
    fun addItem(item: Item): Effect<Done> = effects { persist(CartEvent.Added(item)).thenDone() }

    @Query("get-cart")
    fun getCart(): ReadOnlyEffect<CartState> = effects { reply(currentState()) }
}

/** [KotlinCart] with its add-item function renamed; the handler's name is unchanged. */
@ComponentId("cart")
class RenamedCart : EventSourcedEntity<CartState, CartEvent>() {
    override fun emptyState() = CartState(emptyList())

    override fun applyEvent(state: CartState, event: CartEvent) = state.apply(event)

    @Command("add-item")
    fun putItem(item: Item): Effect<Done> = effects { persist(CartEvent.Added(item)).thenDone() }

    @Query("get-cart")
    fun getCart(): ReadOnlyEffect<CartState> = effects { reply(currentState()) }
}

@ComponentId("cart")
class DuplicateNames : EventSourcedEntity<CartState, CartEvent>() {
    override fun emptyState() = CartState(emptyList())

    override fun applyEvent(state: CartState, event: CartEvent) = state.apply(event)

    @Command("add-item")
    fun addItem(item: Item): Effect<Done> = effects { persist(CartEvent.Added(item)).thenDone() }

    @Command("add-item")
    fun addAnother(item: Item): Effect<Done> = effects { persist(CartEvent.Added(item)).thenDone() }
}

/** A function shaped like a handler that carries no name. */
@ComponentId("cart")
class Unannotated : EventSourcedEntity<CartState, CartEvent>() {
    override fun emptyState() = CartState(emptyList())

    override fun applyEvent(state: CartState, event: CartEvent) = state.apply(event)

    fun get(): ReadOnlyEffect<CartState> = effects { reply(currentState()) }
}

/** A query whose declared outcome could record an event. */
@ComponentId("cart")
class QueryThatPersists : EventSourcedEntity<CartState, CartEvent>() {
    override fun emptyState() = CartState(emptyList())

    override fun applyEvent(state: CartState, event: CartEvent) = state.apply(event)

    @Query("get")
    fun get(): Effect<CartState> = effects { reply(currentState()) }
}

/** An endpoint calling the cart, in a service that does not register it. */
class OrphanEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    @Get("/carts/{id}")
    fun get(id: String): CartState {
        requests.incrementAndGet()
        return componentClient().forEventSourcedEntity(id).call(KotlinCart::getCart).invoke()
    }

    companion object {
        val requests = AtomicInteger()
    }
}

/** A state whose field may hold nothing. */
data class Priced(val name: String, val discount: Int?)

// ── a suspending step ──────────────────────────────────────────────────────────

/** A cart that takes its time to answer a reservation. */
@ComponentId("slow-cart")
class SlowCart : EventSourcedEntity<CartState, CartEvent>() {
    override fun emptyState() = CartState(emptyList())

    override fun applyEvent(state: CartState, event: CartEvent) = state.apply(event)

    @Command("reserve")
    suspend fun reserve(item: Item): Effect<Int> {
        delay(1000)
        return effects { persist(CartEvent.Added(item)) thenReply { it.items.size } }
    }
}

data class Reservation(val cartId: String, val reserved: Int?)

@ComponentId("reserving")
class Reserving : Workflow<Reservation>() {
    @Command("start")
    fun start(cartId: String): Effect<Done> = effects {
        updateState(Reservation(cartId, null)).transitionTo(Reserving::reserve).thenDone()
    }

    @Query("get")
    fun get(): ReadOnlyEffect<Reservation> = effects { reply(currentState()) }

    @Step("reserve")
    suspend fun reserve(): StepEffect {
        val reserved =
            context().componentClient().forEventSourcedEntity(currentState().cartId).call(SlowCart::reserve).invokeSuspending(Item("socks"))
        return stepEffects { updateState(currentState().copy(reserved = reserved)).thenEnd() }
    }
}

// ── a field added after the cart recorded its state ────────────────────────────

/** The cart's state before either field was added. */
data class Recorded(val name: String)

data class WithDefault(val name: String, val discount: Int = 0)

data class WithoutDefault(val name: String, val owner: String)
