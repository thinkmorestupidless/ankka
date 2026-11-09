package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder
import com.thinkmorestupidless.ankka.javasdk.http.Caller
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.client.ServiceResponse
import com.thinkmorestupidless.ankka.javasdk.testkit.Settings
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Calls between services, and who may make them: the callers page's endpoints over HTTP. */
class ServiceCallsTest {
    private lateinit var kit: AnkkaTestKit

    private fun callers(): ServiceBuilder =
        Ankka.service("shopping-cart")
            .register(WalletEntity::class.java)
            .register(PayoutWorkflow::class.java)
            .withEndpoint(CallersEndpoint::class.java)
            .withEndpoint(OnlyOrdersEndpoint::class.java)
            .withEndpoint(OnlySelfEndpoint::class.java)
            .withEndpoint(WalletEndpoint::class.java)

    @BeforeTest
    fun start() {
        kit = AnkkaTestKit.start(callers(), Settings.defaults().withScriptedService("orders").withScriptedService("psp-gateway"))
    }

    @AfterTest
    fun stop() = kit.close()

    private fun text(body: String) = ServiceResponse(200, "text/plain", body.toByteArray(), emptyList())

    @Test
    fun `on a laptop every caller is this machine`() {
        assertEquals("this machine", kit.get("/callers/whoami").body)
    }

    @Test
    fun `a route calls another service as this service, and an unknown service is 503`() {
        kit.service("orders").answer { text("the shopping-cart service in project local") }
        val answer = kit.get("/callers/call/orders")
        assertEquals(200, answer.status, answer.body)
        assertEquals("the shopping-cart service in project local", answer.body)
        assertEquals(listOf("/callers/whoami"), kit.service("orders").requests().map { it.path() })
        kit.get("/callers/call/orders?path=/orders/open")
        assertEquals("/orders/open", kit.service("orders").requests().last().path())
        assertEquals(503, kit.get("/callers/call/nowhere").status)
    }

    @Test
    fun `the orders service is admitted and the payments service is not`() {
        val seen = kit.get("/callers/whoami", caller = Caller.Service("local", "orders"))
        assertEquals("the orders service in project local", seen.body)
        // docs:start test-as-caller
        val orders = kit.get("/callers/only-orders", caller = Caller.Service("local", "orders"))
        assertEquals(200, orders.status, "${orders.body} ${orders.headers}")
        assertEquals("admitted: service:local/orders", orders.body)
        assertEquals(403, kit.get("/callers/only-orders", caller = Caller.Service("local", "payments")).status)
        // docs:end test-as-caller
        // Outside a cluster the service's own identity is `local/local`.
        assertEquals(200, kit.get("/callers/only-self", caller = Caller.Service("local", "local")).status)
        assertEquals(403, kit.get("/callers/only-self", caller = Caller.Service("local", "orders")).status)
    }

    private fun deposit(key: String?, caller: Caller? = null): Answer =
        kit.send(
            "POST",
            "/v1/wallets/p1/eur/deposits",
            """{"amount":25}""",
            key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap(),
            caller,
        )

    @Test
    fun `a deposit is applied once per key, and a service the grants do not name is refused`() {
        val first = deposit("dep-1")
        assertEquals(200, first.status, first.body)
        assertEquals("""{"currency":"eur","balance":25,"applied":true,"by":"local"}""", first.body)
        assertContains(deposit("dep-1").body, "\"applied\":false")
        assertEquals(400, deposit(null).status)
        assertEquals(403, deposit("dep-2", Caller.Service("payments", "psp-gateway")).status)
    }

    @Test
    fun `a step calls another service and goes on with its answer`() {
        kit.service("psp-gateway").answer { text("payout-accepted") }
        kit.componentClient().forWorkflow("pay-1").call(PayoutWorkflow::start).invoke(40)
        kit.eventually(Duration.ofSeconds(10)) {
            assertEquals("payout-accepted", kit.componentClient().forWorkflow("pay-1").call(PayoutWorkflow::get).invoke().answer)
        }
        assertEquals(listOf("/payouts?amount=40"), kit.service("psp-gateway").requests().map { it.path() })
    }
}
