package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.agent.javasdk.Agents
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider
import com.thinkmorestupidless.ankka.kotlin.agent.agentRef
import com.thinkmorestupidless.ankka.kotlin.agent.forAgent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The MCP servers guide's agent. The Java and Kotlin kits hold no MCP server of their own, so this
 * proves the declaration is read: a server's address comes from its variable, and a service whose
 * environment lacks it is refused naming the variable.
 */
class TicketAgentTest {
    @Test
    fun `a listed server's address is its variable, and a service without it is refused naming it`() {
        val model = TestModelProvider().expectText("unused")
        val service = Ankka.service("support").withModule(Agents.module().withModelProvider(model)).register(TicketAgent::class.java)
        val e =
            assertFailsWith<IllegalStateException> {
                AnkkaTestKit.start(service).use { kit ->
                    kit.componentClient().forAgent("t-1").call(agentRef(TicketAgent::ask)).invoke("Open a ticket")
                }
            }
        assertContains(e.message ?: "", "ANKKA_MCP_TICKETS_URL")
        assertEquals(0, model.requests().size)
    }
}
