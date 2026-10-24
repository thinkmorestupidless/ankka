package {{package}}

import com.thinkmorestupidless.ankka.kotlin.orNull
import com.thinkmorestupidless.ankka.kotlin.testkit.viewTestKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The view alone: its handler given the cart's events, its rows read back. */
class CartViewTest {
    // docs:start view-test
    @Test
    fun `a cart's row counts each product and says when it is checked out`() {
        val view = viewTestKit(CartRows::class)
        view.change("c1", CartEvent.ItemAdded(LineItem("p1", "Pen", 2)))
        view.change("c1", CartEvent.ItemAdded(LineItem("p1", "Pen", 1)))
        view.change("c1", CartEvent.CheckedOut)
        assertEquals(CartRows.CartRow("c1", mapOf("p1" to 3), true), view.row("c1").orNull())
    }
    // docs:end view-test

    @Test
    fun `a discarded cart leaves the view`() {
        val view = viewTestKit(CartRows::class)
        view.change("c1", CartEvent.ItemAdded(LineItem("p1", "Pen", 1)))
        view.change("c1", CartEvent.Discarded)
        view.deleted("c1")
        assertNull(view.row("c1").orNull())
    }
}
