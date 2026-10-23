package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery
import com.thinkmorestupidless.ankka.javasdk.client.WorkflowLifecycle
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource
import com.thinkmorestupidless.ankka.javasdk.component.View
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.orNull
import com.thinkmorestupidless.ankka.kotlin.standingOrNull
import java.util.Optional

/** A checkout's row: its state's status, and where the workflow stood when it recorded it. */
@ComponentId("checkout-rows")
class CheckoutRows : View<Checkout.CheckoutState, CheckoutRows.CheckoutRow>() {

    @Manifest("checkout-row")
    data class CheckoutRow(val id: String, val status: String, val standing: String, val step: String?, val failure: String?)

    override fun source(): ChangeSource<Checkout.CheckoutState> = ChangeSource.ofWorkflow(Checkout::class.java)

    override fun onChange(state: Checkout.CheckoutState): ViewEffect<CheckoutRow> {
        // A change from a workflow carries its standing: where it stood once the effect that
        // recorded `state` was applied.
        val standing = updateContext().standingOrNull ?: UNKNOWN
        return effects {
            updateRow(
                CheckoutRow(
                    updateContext().subject(),
                    state.status,
                    standing.status,
                    standing.pendingStep.orNull(),
                    standing.failure.orNull(),
                )
            )
        }
    }

    companion object {
        val BY_STANDING: ViewQuery =
            ViewQuery.declare(
                "by-standing",
                "SELECT payload FROM ${ViewQuery.tableOf(CheckoutRows::class.java)} WHERE payload::jsonb->>'standing' = :standing",
            )

        private val UNKNOWN = WorkflowLifecycle("Unknown", Optional.empty(), emptyMap(), Optional.empty())
    }
}
