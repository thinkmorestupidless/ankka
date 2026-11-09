package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.client.CommandException
import com.thinkmorestupidless.ankka.kotlin.call
import com.thinkmorestupidless.ankka.kotlin.commandRef
import com.thinkmorestupidless.ankka.kotlin.getOrNull
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import com.thinkmorestupidless.ankka.kotlin.testkit.ankkaTestKit
import com.thinkmorestupidless.ankka.kotlin.testkit.eventSourcedTestKit
import com.thinkmorestupidless.ankka.kotlin.testkit.eventually
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import shoppingcart.kotlin.Cart
import shoppingcart.kotlin.CartEvent
import shoppingcart.kotlin.CartRows
import shoppingcart.kotlin.LineItem
import shoppingcart.kotlin.ShoppingCart
import shoppingcart.kotlin.service

/** The component client page: addressing, fanning out, refusals and views, against the cart. */
class ClientSamplesTest {
    @Test
    fun `a component is addressed by its id, and answers through the client`() =
        ankkaTestKit(service()) {
            val componentClient = componentClient()
            runBlocking {
                // docs:start addressing
                val cart = componentClient.forEventSourcedEntity("cart-1")
                cart.call(ShoppingCart::addItem).invokeSuspending(LineItem("p1", "Widget", 2))
                val held: Cart = cart.call(ShoppingCart::getCart).invokeSuspending()
                // docs:end addressing
                assertEquals(Cart("cart-1", listOf(LineItem("p1", "Widget", 2)), false), held)
            }
        }

    @Test
    fun `calls fan out as coroutines, and the whole takes as long as the slowest`() =
        ankkaTestKit(service()) {
            val componentClient = componentClient()
            val cartIds = listOf("f1", "f2", "f3")
            runBlocking {
                cartIds.forEachIndexed { i, id ->
                    componentClient.forEventSourcedEntity(id).call(ShoppingCart::addItem).invokeSuspending(LineItem("p1", "Widget", i + 1))
                }
                // docs:start fan-out
                val totals: List<Int> = coroutineScope {
                    cartIds
                        .map { id -> async { componentClient.forEventSourcedEntity(id).call(ShoppingCart::totalQuantity).invokeSuspending() } }
                        .awaitAll()
                }
                // docs:end fan-out
                assertEquals(listOf(1, 2, 3), totals)
            }
        }

    @Test
    fun `a refusal arrives as a CommandException carrying the problem`() =
        ankkaTestKit(service()) {
            val cart = componentClient().forEventSourcedEntity("r1")
            runBlocking {
                // docs:start refusals
                try {
                    cart.call(ShoppingCart::addItem).invokeSuspending(LineItem("p1", "Widget", 0))
                    fail("a quantity of zero was accepted")
                } catch (e: CommandException) {
                    assertEquals(400, e.problem().status())
                }
                // docs:end refusals
                assertEquals(0, cart.call(ShoppingCart::totalQuantity).invokeSuspending())
            }
        }

    @Test
    fun `a view is queried through the view client, by the view's class`() =
        ankkaTestKit(service()) {
            runBlocking {
                componentClient().forEventSourcedEntity("v1").call(ShoppingCart::addItem).invokeSuspending(LineItem("p1", "Widget", 2))
                // docs:start views
                val rows = viewClient().forView(CartRows::class.java)
                // docs:end views
                eventually { assertEquals(mapOf("p1" to 2), rows.getOrNull("v1")?.quantities) }
            }
        }

    @Test
    fun `the entity test kit drives one cart with nothing started`() {
        // docs:start unit-test
        val kit = eventSourcedTestKit(ShoppingCart::class, "cart-1")
        val result = kit.call(commandRef(ShoppingCart::addItem), LineItem("p1", "Widget", 2))
        assertEquals(Done.done(), result.reply())
        assertEquals(listOf(CartEvent.ItemAdded(LineItem("p1", "Widget", 2))), result.events())
        assertEquals(2, kit.state().totalQuantity)

        val refused = kit.call(commandRef(ShoppingCart::addItem), LineItem("p1", "Widget", 0))
        assertTrue(refused.isError())
        assertEquals(400, refused.problem().status())
        assertEquals(1, kit.events().size)
        // docs:end unit-test
    }
}
