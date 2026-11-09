package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Action
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity
import com.thinkmorestupidless.ankka.javasdk.component.TimedAction
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.effect.TimedActionEffect
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import java.util.concurrent.ConcurrentLinkedQueue

@Manifest("order")
data class Order(val item: String, val status: String)

/** An order that a timer may cancel while it is still pending. */
@ComponentId("order")
class OrderEntity : KeyValueEntity<Order>() {
    private val orderId: String
        get() = context().entityId()

    override fun emptyState() = Order("", "none")

    @Command("place")
    fun place(item: String): Effect<Done> = effects {
        if (currentState().status != "none") error(Problem.conflict("order '$orderId' was already placed"))
        else updateState(Order(item, "pending")).thenDone()
    }

    @Command("confirm")
    fun confirm(): Effect<Done> = effects {
        if (currentState().status != "pending") error(Problem.conflict("cannot confirm an order that is '${currentState().status}'"))
        else updateState(currentState().copy(status = "confirmed")).thenDone()
    }

    /**
     * Cancels a pending order, and reports success for one already settled. The idempotence
     * matters: a timer calls this, and one that "failed" because the order was confirmed a moment
     * earlier would retry forever.
     */
    @Command("cancel")
    fun cancel(): Effect<String> = effects {
        if (currentState().status == "pending") updateState(currentState().copy(status = "cancelled")).thenReply("cancelled")
        else reply("already ${currentState().status}")
    }

    @Query("status")
    fun status(): ReadOnlyEffect<String> = effects { reply(currentState().status) }
}

// docs:start action
/** The order's deadlines: what runs when a timer set on an order fires. */
@ComponentId("order-timers")
class OrderTimers : TimedAction() {
    @Action("expire-order")
    fun expireOrder(orderId: String): TimedActionEffect {
        val outcome = context().componentClient().forKeyValueEntity(orderId).call(OrderEntity::cancel).invoke()
        OBSERVED.add("$orderId:$outcome")
        return effects { done() }
    }

    companion object {
        /** Records what fired, so tests can assert on it. */
        val OBSERVED = ConcurrentLinkedQueue<String>()
    }
}
// docs:end action
