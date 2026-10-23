package {{package}}

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.kotlin.testkit.eventSourcedTestKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The entity alone: no runtime, no database, milliseconds. */
class ShoppingCartTest {
    // docs:start entity-test
    @Test
    fun `an item added is in the cart`() {
        val cart = eventSourcedTestKit(ShoppingCart::class, "c1")
        val result = cart.call(ShoppingCart::addItem, LineItem("p1", "Pen", 2))
        assertEquals(Done.DONE, result.reply())
        assertEquals(listOf<CartEvent>(CartEvent.ItemAdded(LineItem("p1", "Pen", 2))), result.events())
        assertEquals(2, cart.state().totalQuantity)
    }
    // docs:end entity-test

    @Test
    fun `a checked out cart takes no more items`() {
        val cart = eventSourcedTestKit(ShoppingCart::class)
        cart.call(ShoppingCart::addItem, LineItem("p1", "Pen", 1))
        cart.call(ShoppingCart::checkout)
        val refused = cart.call(ShoppingCart::addItem, LineItem("p2", "Ink", 1))
        assertTrue(refused.isError)
        assertEquals(409, refused.problem().status())
    }
}
