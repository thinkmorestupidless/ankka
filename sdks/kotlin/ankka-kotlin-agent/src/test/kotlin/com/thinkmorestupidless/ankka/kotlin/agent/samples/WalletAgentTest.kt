package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.agent.javasdk.Agents
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.client.ServiceResponse
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.Settings
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider
import com.thinkmorestupidless.ankka.kotlin.agent.forAgent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** A tool that calls another service, that service played by the kit's stand-in. */
class WalletAgentTest {
    @Test
    fun `the tool's call reaches the wallet service and its answer reaches the model`() {
        val model =
            TestModelProvider()
                .expectToolCall("read_balance", """{"customer":"c-1"}""")
                .expectText("c-1 has 40 left.")
        val service = Ankka.service("support").withModule(Agents.module().withModelProvider(model)).register(WalletAgent::class.java)
        AnkkaTestKit.start(service, Settings.defaults().withScriptedService("wallet")).use { kit ->
            kit.service("wallet").answer { ServiceResponse(200, "text/plain", "40".toByteArray(), emptyList()) }

            val answer = kit.componentClient().forAgent("s-1").call(WalletAgent::ask).invoke("How much does c-1 have?")

            assertEquals("c-1 has 40 left.", answer)
            assertEquals(listOf("/wallet/balances/c-1"), kit.service("wallet").requests().map { it.path() })
            assertContains(model.lastRequest(), "40")
        }
    }
}
