package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource
import com.thinkmorestupidless.ankka.javasdk.component.Consumer
import com.thinkmorestupidless.ankka.javasdk.component.Publication
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.javasdk.effect.Outgoing
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.orNull
import com.thinkmorestupidless.ankka.kotlin.standingOrNull
import java.util.Optional

/** Records each checkout that ended, completed or failed, and nothing else. */
// docs:start workflow-consumer
@ComponentId("checkout-ends")
class CheckoutEnds : Consumer<Checkout.CheckoutState, Void>() {

    override fun source(): ChangeSource<Checkout.CheckoutState> = ChangeSource.ofWorkflow(Checkout::class.java)

    override fun onChange(state: Checkout.CheckoutState): ConsumerEffect<Void> {
        val lifecycle = messageContext().standingOrNull
        // Running or paused: not an end, and nothing to do.
        if (lifecycle == null || !lifecycle.isTerminal) return effects { ignore() }
        val end = lifecycle.failure.orNull()?.let { why -> "${lifecycle.status}: $why" } ?: lifecycle.status
        componentClient().forKeyValueEntity("end-${messageContext().subject()}").call(Profile::set).invoke(end)
        return effects { done() }
    }
}
// docs:end workflow-consumer

/** Records each cart checked out on the conformance entity of the same id. */
// docs:start calls-components
@ComponentId("checkout-recorder")
class CheckoutRecorder : Consumer<CartEvent, Void>() {

    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCartEntity::class.java)

    override fun onChange(event: CartEvent): ConsumerEffect<Void> {
        if (event != CartEvent.CheckedOut) return effects { ignore() }
        componentClient().forEventSourcedEntity(messageContext().subject()).call(ConformanceEntity::record).invoke("checkout")
        return effects { done() }
    }
}
// docs:end calls-components

/**
 * Several messages for one change: three for a checkout (the second under a key of its own, the
 * third with a header), none for an item added, and a single one for an item removed.
 */
// docs:start fanout
@ComponentId("checkout-fanout")
class CheckoutFanout : Consumer<CartEvent, Fanned>() {

    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCartEntity::class.java)

    override fun publishes(): Optional<Publication> = Optional.of(Publication.to("conformance-fanout"))

    override fun onChange(event: CartEvent): ConsumerEffect<Fanned> = effects {
        when (event) {
            is CartEvent.ItemAdded -> publishAll(emptyList())
            is CartEvent.ItemRemoved -> publish(Fanned(0))
            CartEvent.CheckedOut ->
                publishMessages(
                    listOf(
                        Outgoing.of(Fanned(1)),
                        Outgoing.of(Fanned(2)).withKey("second:${messageContext().subject()}"),
                        Outgoing.of(Fanned(3)).withMetadata("x-n", "3"),
                    )
                )
            CartEvent.Discarded -> ignore()
        }
    }
}
// docs:end fanout
