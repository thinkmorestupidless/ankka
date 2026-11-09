package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects

/** When a cart was checked out, and whether the notice went out. */
@Manifest("checkout-record")
data class CheckoutRecord(val cartId: String, val at: Long = 0, val notified: Boolean = false)

// docs:start key-value
/**
 * Where the notifier records checkouts: a key value entity per cart holding when it happened.
 *
 * Nothing needs to know how the record came to be, only what it is now, so this stores the latest
 * value rather than a journal of events. The cart itself is the opposite case, and is event sourced.
 */
@ComponentId("checkout-log")
class CheckoutLog : KeyValueEntity<CheckoutRecord>() {
    private val cartId: String
        get() = context().entityId()

    override fun emptyState() = CheckoutRecord(cartId)

    @Command("record")
    fun record(at: Long): Effect<Done> = effects { updateState(CheckoutRecord(cartId, at, notified = true)).thenDone() }

    @Query("get")
    fun get(): ReadOnlyEffect<CheckoutRecord> = effects { reply(currentState()) }
}
// docs:end key-value
