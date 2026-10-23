package com.thinkmorestupidless.ankka.kotlin.dsl

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.Step
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity
import com.thinkmorestupidless.ankka.javasdk.component.Workflow
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar
import com.thinkmorestupidless.ankka.kotlin.call
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import java.util.function.Function
import kotlinx.coroutines.yield

// One basket declared twice: through the Java SDK's chained builders, and in Kotlin's own form.
// DslTest drives both and expects the same of each.

data class Item(val sku: String, val quantity: Int)

data class Basket(val items: List<Item>, val closed: Boolean)

sealed interface BasketEvent {
    data class Added(val item: Item) : BasketEvent

    data object Closed : BasketEvent
}

private fun Basket.apply(event: BasketEvent): Basket =
    when (event) {
        is BasketEvent.Added -> copy(items = items + event.item)
        BasketEvent.Closed -> copy(closed = true)
    }

@ComponentId("chained-basket")
class ChainedBasket : EventSourcedEntity<Basket, BasketEvent>() {
    override fun emptyState() = Basket(emptyList(), false)

    override fun applyEvent(state: Basket, event: BasketEvent) = state.apply(event)

    @Command("add")
    fun add(item: Item): Effect<Int> =
        if (currentState().closed) effects().error("the basket is closed")
        else effects().persist(BasketEvent.Added(item)).thenReply(Function { s: Basket -> s.items.size })

    @Command("close")
    fun close(): Effect<Done> = effects().persist(BasketEvent.Closed).thenDone()

    @Query("get")
    fun get(): ReadOnlyEffect<Basket> = effects().reply(currentState())
}

@ComponentId("dsl-basket")
class DslBasket : EventSourcedEntity<Basket, BasketEvent>() {
    override fun emptyState() = Basket(emptyList(), false)

    override fun applyEvent(state: Basket, event: BasketEvent) = state.apply(event)

    @Command("add")
    fun add(item: Item): Effect<Int> = effects {
        if (currentState().closed) error("the basket is closed")
        else persist(BasketEvent.Added(item)) thenReply { it.items.size }
    }

    @Command("close")
    fun close(): Effect<Done> = effects { persist(BasketEvent.Closed).thenDone() }

    @Query("get")
    fun get(): ReadOnlyEffect<Basket> = effects { reply(currentState()) }
}

/** The DSL's handlers written as suspend funs. */
@ComponentId("suspending-basket")
class SuspendingBasket : EventSourcedEntity<Basket, BasketEvent>() {
    override fun emptyState() = Basket(emptyList(), false)

    override fun applyEvent(state: Basket, event: BasketEvent) = state.apply(event)

    @Command("add")
    suspend fun add(item: Item): Effect<Int> {
        yield()
        return effects { persist(BasketEvent.Added(item)) thenReply { it.items.size } }
    }

    @Query("get")
    suspend fun get(): ReadOnlyEffect<Basket> = effects { reply(currentState()) }
}

data class Counter(val value: Int)

@ComponentId("dsl-counter")
class DslCounter : KeyValueEntity<Counter>() {
    override fun emptyState() = Counter(0)

    @Command("add")
    fun add(by: Int): Effect<Counter> = effects { updateState(Counter(currentState().value + by)) thenReply { it } }
}

data class Steps(val taken: List<String>)

@ComponentId("dsl-steps")
class DslSteps : Workflow<Steps>() {
    @Command("start")
    fun start(): Effect<Done> = effects { updateState(Steps(listOf("started"))).transitionTo(DslSteps::first).thenDone() }

    @Query("get")
    fun get(): ReadOnlyEffect<Steps> = effects { reply(currentState()) }

    @Step("first")
    fun first(): StepEffect = stepEffects { updateState(Steps(currentState().taken + "first")) thenTransitionTo DslSteps::second }

    @Step("second")
    suspend fun second(): StepEffect {
        yield()
        return stepEffects { updateState(Steps(currentState().taken + "second")).thenEnd() }
    }
}

/** Routes in code beside an annotated one, calling the baskets by Kotlin function reference. */
class BasketEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    @Get("/annotated/{id}")
    fun annotated(id: String): Basket = componentClient().forEventSourcedEntity(id).call(DslBasket::get).invoke()

    override fun routes() = routes {
        get("/baskets/{id}") {
            componentClient().forEventSourcedEntity(path("id")).call(SuspendingBasket::get).invokeSuspending()
        }
        post<Item>("/baskets/{id}/items") { item ->
            componentClient().forEventSourcedEntity(path("id")).call(SuspendingBasket::add).invokeSuspending(item)
        }
        delete("/baskets/{id}") { Done.DONE }
    }
}

/** The same route twice, once annotated and once in code. */
class TwiceRoutedEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    @Get("/twice")
    fun annotated(): String = "annotated"

    override fun routes() = routes { get("/twice") { "in code" } }
}

data class AddArgs(val basketId: String, val sku: String)

/** Tools named by Kotlin function references, suspending or not, and one with a suspending handler. */
class BasketTools : McpEndpoint() {
    init {
        tool("get_basket").describedAs("Reads a basket").query(DslBasket::get, "basketId")
        tool("close_basket").describedAs("Closes a basket").command(DslBasket::close, "basketId")
        tool("get_suspending").describedAs("Reads a suspending basket").query(SuspendingBasket::get, "basketId")
        tool("add_one").describedAs("Adds one of a product").handling<AddArgs> { args ->
            componentClient().forEventSourcedEntity(args.basketId).call(SuspendingBasket::add).invokeSuspending(Item(args.sku, 1))
        }
    }

    override fun acl(): Acl = Acl.allowAll()
}
