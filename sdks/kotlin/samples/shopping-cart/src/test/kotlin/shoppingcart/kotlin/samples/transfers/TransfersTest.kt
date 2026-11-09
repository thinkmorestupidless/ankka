package shoppingcart.kotlin.samples.transfers

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.TestClient
import com.thinkmorestupidless.ankka.javasdk.testkit.WorkflowTestKit
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import shoppingcart.kotlin.samples.get
import shoppingcart.kotlin.samples.send

/** The transfer workflow: its steps by hand in the workflow kit, and whole in a running service. */
class TransfersTest {
    private fun service(): ServiceBuilder =
        // docs:start register
        Ankka.service("transfers")
            .register(WalletEntity::class.java)
            .register(TransferWorkflow::class.java)
            // docs:end register
            .register(KycWorkflow::class.java)
            .register(OnboardingWorkflow::class.java)
            .withEndpoint(TransferEndpoint::class.java)

    @Test
    fun `the workflow kit runs the command and the steps it leads to, the wallets stubbed`() {
        // docs:start workflow-kit
        val wallets = TestClient.of(WalletEntity::class.java).stub(WalletEntity::withdraw) { Done.done() }.stub(WalletEntity::deposit) { Done.done() }
        val kit = WorkflowTestKit.of(TransferWorkflow::class.java, "t-1", wallets)
        val started = kit.call(TransferWorkflow::start, Transfer("a", "b", 10))
        assertEquals(Done.done(), started.reply())
        assertEquals(listOf("withdraw", "deposit"), started.steps()) // run after the command, in order
        assertEquals("completed", kit.state().status)
        assertTrue(kit.lifecycle().isCompleted())
        assertTrue(kit.call(TransferWorkflow::start, Transfer("a", "b", 10)).isError()) // already started
        // docs:end workflow-kit
    }

    @Test
    fun `a transfer completes, a refused deposit is compensated, and callers wait for either end`() =
        AnkkaTestKit.start(service()).use { kit ->
            val wallets = kit.componentClient()
            wallets.forKeyValueEntity("a").call(WalletEntity::deposit).invoke(100)
            wallets.forKeyValueEntity("c").call(WalletEntity::freeze).invoke()

            assertEquals(204, kit.send("POST", "/transfers/t-1", """{"from":"a","to":"b","amount":30}""").status)
            val ended = kit.get("/transfers/t-1/end")
            assertEquals(200, ended.status, ended.body)
            assertContains(ended.body, "\"status\":\"completed\"")
            assertEquals("Completed", kit.get("/transfers/t-1/lifecycle").body)
            assertEquals(Wallet(70, false), wallets.forKeyValueEntity("a").call(WalletEntity::get).invoke())
            assertEquals(Wallet(30, false), wallets.forKeyValueEntity("b").call(WalletEntity::get).invoke())

            // The deposit into a frozen wallet fails, is retried once, and the failover puts the money back.
            assertEquals(204, kit.send("POST", "/transfers/t-2", """{"from":"a","to":"c","amount":10}""").status)
            assertContains(kit.get("/transfers/t-2/end").body, "\"status\":\"compensated\"")
            assertEquals(Wallet(70, false), wallets.forKeyValueEntity("a").call(WalletEntity::get).invoke())

            val events = kit.get("/transfers/t-1/events")
            assertEquals(200, events.status, events.body)
            assertContains(events.body, "ended")
            assertContains(events.body, "completed")

            // A step that starts another workflow and waits for its end within its own timeout.
            wallets.forWorkflow("ob-1").call(OnboardingWorkflow::start).invoke("ada")
            wallets.forWorkflow("ob-2").call(OnboardingWorkflow::start).invoke("refused-bob")
            assertEquals("welcomed", wallets.forWorkflow("ob-1").awaitEnd(OnboardingWorkflow::class.java, Duration.ofSeconds(30)).status)
            assertEquals("declined", wallets.forWorkflow("ob-2").awaitEnd(OnboardingWorkflow::class.java, Duration.ofSeconds(30)).status)
        }
}
