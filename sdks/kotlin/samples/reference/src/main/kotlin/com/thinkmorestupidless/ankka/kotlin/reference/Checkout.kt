package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.Step
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.RecoverStrategy
import com.thinkmorestupidless.ankka.javasdk.component.Workflow
import com.thinkmorestupidless.ankka.javasdk.component.WorkflowSettings
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.dsl.stepEffects
import com.thinkmorestupidless.ankka.kotlin.dsl.thenTransitionTo
import com.thinkmorestupidless.ankka.kotlin.dsl.transitionTo
import com.thinkmorestupidless.ankka.kotlin.stepRef
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import java.time.Duration

/**
 * A checkout with a failing step and a pause. `mode`: `ok`, `fail` (charge is declined and
 * compensated), `pause` (a pause before charging), `abort` (declined; the compensation records it
 * and fails the workflow), `drop` (declined; the compensation fails the workflow and records
 * nothing) or `doomed` (declined, and the compensation fails too).
 */
@ComponentId("checkout")
class Checkout : Workflow<Checkout.CheckoutState>() {

    @Manifest("checkout")
    data class CheckoutState(val id: String, val mode: String, val status: String)

    override fun emptyState(): CheckoutState = CheckoutState(context().entityId(), "", "new")

    override fun settings(): WorkflowSettings =
        WorkflowSettings.builder()
            .defaultStepTimeout(Duration.ofSeconds(10))
            .stepRecovery(stepRef(Checkout::charge), RecoverStrategy.maxRetries(1).failoverTo(stepRef(Checkout::compensate)))
            .build()

    @Command("start")
    fun start(mode: String): Effect<String> = effects {
        if (currentState().status != "new") error(Problem.conflict("already started"))
        else updateState(currentState().copy(mode = mode, status = "reserving")).transitionTo(Checkout::reserve).thenReply("started")
    }

    @Step("reserve")
    suspend fun reserve(): StepEffect {
        // A client call from a step: what the cart holds.
        context().componentClient().forEventSourcedEntity(currentState().id).call(ShoppingCartEntity::totalQuantity).invokeSuspending()
        return stepEffects {
            val reserved = updateState(currentState().copy(status = "reserved"))
            if (currentState().mode == "pause") reserved thenTransitionTo Checkout::waitStep
            else reserved thenTransitionTo Checkout::charge
        }
    }

    // Not `wait`: that would be Object.wait() on the JVM.
    @Step("wait")
    fun waitStep(): StepEffect = stepEffects {
        updateState(currentState().copy(status = "waiting")).thenPause(Duration.ofMillis(1500), stepRef(Checkout::charge))
    }

    @Step("charge")
    fun charge(): StepEffect {
        if (currentState().mode in DECLINED) throw RuntimeException("payment declined")
        return stepEffects { updateState(currentState().copy(status = "charged")).thenEnd() }
    }

    @Step("compensate")
    fun compensate(): StepEffect = stepEffects {
        when (currentState().mode) {
            "abort" -> updateState(currentState().copy(status = "aborted")).thenFail("payment declined")
            "drop" -> thenFail("payment declined")
            "doomed" -> throw RuntimeException("compensation failed too")
            else -> updateState(currentState().copy(status = "compensated")).thenEnd()
        }
    }

    @Query("status")
    fun status(): ReadOnlyEffect<String> = effects { reply(currentState().status) }

    private companion object {
        val DECLINED = setOf("fail", "abort", "drop", "doomed")
    }
}
