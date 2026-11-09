package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.ViewTestKit
import com.thinkmorestupidless.ankka.kotlin.commandRef
import com.thinkmorestupidless.ankka.kotlin.getOrNull
import com.thinkmorestupidless.ankka.kotlin.orNull
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import shoppingcart.kotlin.CartEvent
import shoppingcart.kotlin.CartRows
import shoppingcart.kotlin.CheckoutNotifier
import shoppingcart.kotlin.CheckoutsSeen
import shoppingcart.kotlin.LineItem
import shoppingcart.kotlin.ShoppingCart
import shoppingcart.kotlin.service

/** The cart rows view: in the view test kit, and queried in a running service. */
class ViewSamplesTest {
    @Test
    fun `the view kit feeds a view one change at a time and keeps one row per key`() {
        // docs:start view-kit
        val kit = ViewTestKit.of(CartRows::class.java)
        kit.change("c1", CartEvent.ItemAdded(LineItem("p1", "Pen", 2)))
        assertEquals(CartRows.CartRow("c1", mapOf("p1" to 2), false, mapOf("p1" to "Pen")), kit.row("c1").orNull())
        kit.deleted("c1")
        assertNull(kit.row("c1").orNull())
        // docs:end view-kit
    }

    @Test
    fun `a running service's view is counted, listed and read by key, and the topic it feeds is read back`() {
        // docs:start start
        // Every port is a free loopback one, never 9000: `http()` is where the routes answer.
        val kit = AnkkaTestKit.start(service())
        val baseUrl = kit.http()
        // docs:end start
        kit.use {
            kit.componentClient().forEventSourcedEntity("view-q-1").call(commandRef(ShoppingCart::addItem)).invoke(LineItem("p1", "Pen", 2))
            // docs:start count
            val rows = kit.viewClient().forView(CartRows::class.java)
            kit.eventually(Duration.ofSeconds(10)) {
                assertTrue(rows.count() >= 1L)
                assertTrue(rows.all().isNotEmpty())
                assertEquals(mapOf("p1" to 2), rows.getOrNull("view-q-1")?.quantities)
            }
            // docs:end count
            val answer = kit.get("/carts/view-q-1")
            assertEquals(200, answer.status, "$baseUrl answered ${answer.body}")

            // docs:start in-memory-broker
            // The kit holds a broker in memory: what the notifier publishes to its topic, the view
            // over that topic reads back, with nothing started.
            kit.componentClient().forEventSourcedEntity("view-q-1").call(ShoppingCart::checkout).invoke()
            kit.eventually(Duration.ofSeconds(10)) {
                assertEquals(listOf("view-q-1"), kit.published(CheckoutNotifier.TOPIC).map { it.key().orNull() })
                assertEquals(1, kit.viewClient().forView(CheckoutsSeen::class.java).getOrNull("view-q-1")?.notices)
            }
            // docs:end in-memory-broker
        }
    }
}
