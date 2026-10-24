package shoppingcart.kotlin

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.Step
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.Workflow
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.dsl.stepEffects
import com.thinkmorestupidless.ankka.kotlin.dsl.transitionTo
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending

// docs:start workflow
/** A checkout carried out as a workflow: what it is checking out, and what the cart held once it was. */
@Manifest("checkout")
data class Checkout(val cartId: String, val ordered: Cart?)

/**
 * Checks a cart out in a step. The step is a suspend fun: while it waits for the cart's answer the
 * workflow holds no thread.
 */
@ComponentId("checkout")
class CheckoutWorkflow : Workflow<Checkout>() {
    @Command("start")
    fun start(cartId: String): Effect<Done> = effects {
        updateState(Checkout(cartId, null)).transitionTo(CheckoutWorkflow::checkOut).thenDone()
    }

    @Query("get")
    fun get(): ReadOnlyEffect<Checkout> = effects { reply(currentState()) }

    @Step("check-out")
    suspend fun checkOut(): StepEffect {
        val ordered =
            context().componentClient().forEventSourcedEntity(currentState().cartId).call(ShoppingCart::checkout).invokeSuspending()
        return stepEffects { updateState(currentState().copy(ordered = ordered)).thenEnd() }
    }
}
// docs:end workflow
