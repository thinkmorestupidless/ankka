package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.kotlin.getOrNull
import com.thinkmorestupidless.ankka.kotlin.testkit.ankkaTestKit
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The secret store, through the endpoint, the workflow's step and the kit. */
class SecretsTest {
    private fun charging() =
        Ankka.service("charging").register(ChargeWorkflow::class.java).withEndpoint(ProviderCredentialsEndpoint::class.java)

    @Test
    fun `a credential kept through the endpoint is read by the step, and never recorded`() =
        ankkaTestKit(charging()) {
            // docs:start kit-secrets
            // The kit's `secrets()` is the service's store: what one keeps, the other reads.
            assertEquals(204, send("POST", "/credentials", """{"name":"provider/stripe","value":"sk-test-1"}""").status)
            assertEquals("sk-test-1", secrets().getOrNull("provider/stripe"))

            componentClient().forWorkflow("ch-1").call(ChargeWorkflow::start).invoke(Charge("stripe", 1200))
            eventually(Duration.ofSeconds(10)) {
                assertEquals(ChargeState("stripe", 9, "charged"), componentClient().forWorkflow("ch-1").call(ChargeWorkflow::get).invoke())
            }
            // docs:end kit-secrets
            assertEquals("sk-test-1", get("/credentials?name=provider/stripe").body)
            assertEquals(204, send("DELETE", "/credentials?name=provider/stripe").status)
            assertEquals(404, get("/credentials?name=provider/stripe").status)
            assertNull(secrets().getOrNull("provider/stripe"))
        }
}
