package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.testkit.ConsumerTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.TestClient
import com.thinkmorestupidless.ankka.kotlin.call
import com.thinkmorestupidless.ankka.kotlin.orNull
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import com.thinkmorestupidless.ankka.kotlin.testkit.ankkaTestKit
import com.thinkmorestupidless.ankka.kotlin.testkit.eventually
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import shoppingcart.kotlin.Cart
import shoppingcart.kotlin.CartEvent
import shoppingcart.kotlin.LineItem
import shoppingcart.kotlin.ShoppingCart
import shoppingcart.kotlin.service

/** Consumers and graph consumers in their unit test kits, and a notice reaching its topic. */
class ConsumerSamplesTest {
    @Test
    fun `a checkout is answered with three messages, the second under a key of its own`() {
        // docs:start consumer-test
        val kit = ConsumerTestKit.of(CheckoutFanout::class.java)
        val result = kit.onMessage(CartEvent.CheckedOut, "c1", 4)

        assertEquals(listOf(Fanned(1), Fanned(2), Fanned(3)), result.payloads())
        // The key each record carries: the one a message named, or the subject, the cart's id.
        assertEquals(listOf(Optional.of("c1"), Optional.of("second:c1"), Optional.of("c1")), result.recordKeys())
        assertEquals("3", result.messages()[2].metadata()["x-n"])
        // docs:end consumer-test
    }

    @Test
    fun `a checked-out cart's notice reaches the topic, keyed by the cart`() =
        ankkaTestKit(service()) {
            runBlocking {
                val cart = componentClient().forEventSourcedEntity("n1")
                cart.call(ShoppingCart::addItem).invokeSuspending(LineItem("p1", "Pen", 1))
                cart.call(ShoppingCart::checkout).invokeSuspending()
                // docs:start published
                eventually {
                    val notices = published("cart-checkouts")
                    assertEquals(listOf("n1"), notices.map { it.key().orNull() })
                    assertTrue(notices.single().text().contains("\"cartId\":\"n1\""), notices.single().text())
                }
                // docs:end published
            }
        }

    // docs:start graph-test
    private val kit = ConsumerTestKit.graph(CartGraph::class.java)

    @Test
    fun `an item added publishes the cart's node, at the event's sequence number`() {
        val deltas = kit.onMessage(CartEvent.ItemAdded(LineItem("p1", "Pen", 1)), "c1", 1)

        assertEquals(listOf("node:cart:c1"), deltas.map { it.key() })
        val cart = deltas.first()
        assertEquals(1L, cart.version())
        assertEquals(listOf("Cart"), cart.labels())
        assertEquals(mapOf("cartId" to "c1", "checkedOut" to false), cart.properties())
    }

    @Test
    fun `a checkout publishes the cart as checked out, the checkout, and the edge between them`() {
        val deltas = kit.onMessage(CartEvent.CheckedOut, "c1", 4)

        assertEquals(listOf("node:cart:c1", "node:checkout:c1", "edge:checked-out:c1"), deltas.map { it.key() })
        assertEquals(listOf(4L), deltas.map { it.version() }.distinct())
        assertEquals(true, deltas[0].properties()["checkedOut"])
        val edge = deltas[2]
        assertEquals(Triple("CHECKED_OUT", "cart:c1", "checkout:c1"), Triple(edge.edgeType().orNull(), edge.from().orNull(), edge.to().orNull()))
    }

    @Test
    fun `a discarded cart's deletion publishes its tombstone`() {
        assertEquals(emptyList(), kit.onMessage(CartEvent.Discarded, "c1", 2))

        val deltas = kit.onDelete("c1")
        assertEquals(listOf("node:cart:c1" to true), deltas.map { it.key() to it.isTombstone })
    }
    // docs:end graph-test

    @Test
    fun `a consumer that reads the cart publishes what it holds, at the event's version`() {
        val client = TestClient.of(ShoppingCart::class.java).stub(ShoppingCart::getCart) { Cart("c1", listOf(LineItem("p1", "Pen", 2), LineItem("p2", "Ink", 1)), false) }
        val kit = ConsumerTestKit.graph(CartContentsGraph::class.java, client)

        val deltas = kit.onMessage(CartEvent.ItemAdded(LineItem("p2", "Ink", 1)), "c1", 2)
        assertEquals(listOf("node:cart-contents:c1"), deltas.map { it.key() })
        assertEquals(mapOf("cartId" to "c1", "lines" to 2L, "quantity" to 3L), deltas.single().properties())
        assertEquals(2L, deltas.single().version())
        assertEquals(emptyList(), kit.onMessage(CartEvent.Discarded, "c1", 3))
        assertTrue(kit.onDelete("c1").single().isTombstone)
    }
}
