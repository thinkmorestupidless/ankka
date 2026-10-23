package shoppingcart.kotlin

import com.thinkmorestupidless.ankka.kotlin.call
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import com.thinkmorestupidless.ankka.kotlin.testkit.ankkaTestKit
import com.thinkmorestupidless.ankka.kotlin.testkit.eventually
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class CheckoutWorkflowTest {

    @Test
    fun `a checkout's suspending step checks the cart out and keeps what it held`() =
        ankkaTestKit(service()) {
            val widget = LineItem("widget", "Widget", 2)
            runBlocking {
                componentClient().forEventSourcedEntity("c1").call(ShoppingCart::addItem).invokeSuspending(widget)
                componentClient().forWorkflow("checkout-c1").call(CheckoutWorkflow::start).invoke("c1")
                eventually {
                    val checkout = componentClient().forWorkflow("checkout-c1").call(CheckoutWorkflow::get).invoke()
                    assertEquals(Cart("c1", listOf(widget), true), checkout.ordered)
                }
            }
        }
}
