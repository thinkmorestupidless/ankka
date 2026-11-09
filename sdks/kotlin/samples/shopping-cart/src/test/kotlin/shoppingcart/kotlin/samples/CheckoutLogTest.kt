package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.testkit.KeyValueEntityTestKit
import com.thinkmorestupidless.ankka.kotlin.testkit.ankkaTestKit
import kotlin.test.Test
import kotlin.test.assertEquals

/** The checkout log in the key value entity test kit, and registered in a running service. */
class CheckoutLogTest {
    @Test
    fun `recording a checkout replaces the value and acknowledges`() {
        // docs:start key-value-test
        val kit = KeyValueEntityTestKit.of(CheckoutLog::class.java, "c1")
        val result = kit.call(CheckoutLog::record, 1_700_000_000_000L)

        assertEquals(Done.done(), result.reply())
        assertEquals(1_700_000_000_000L, kit.state().at)
        assertEquals(true, kit.state().notified)
        // docs:end key-value-test
    }

    @Test
    fun `a later recording replaces the earlier one, with no history to accumulate`() {
        val kit = KeyValueEntityTestKit.of(CheckoutLog::class.java, "c1")
        kit.call(CheckoutLog::record, 1L)
        kit.call(CheckoutLog::record, 2L)
        assertEquals(2L, kit.state().at)
        assertEquals(CheckoutRecord("c1", 2L, true), kit.call(CheckoutLog::get).reply())
    }

    @Test
    fun `registered like any other component, the entity is called through the client`() {
        // docs:start register-and-call
        val service = Ankka.service("shopping-cart").register(CheckoutLog::class.java)
        ankkaTestKit(service) { // `service.start()` in a main; here the kit starts it
            val record = componentClient().forKeyValueEntity("c1").call(CheckoutLog::get).invoke()
            // docs:end register-and-call
            assertEquals(CheckoutRecord("c1"), record)
            componentClient().forKeyValueEntity("c1").call(CheckoutLog::record).invoke(7L)
            assertEquals(CheckoutRecord("c1", 7L, true), componentClient().forKeyValueEntity("c1").call(CheckoutLog::get).invoke())
        }
    }
}
