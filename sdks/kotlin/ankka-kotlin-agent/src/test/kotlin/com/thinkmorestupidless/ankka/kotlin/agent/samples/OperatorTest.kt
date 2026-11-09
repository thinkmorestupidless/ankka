package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients
import com.thinkmorestupidless.ankka.agent.javasdk.Agents
import com.thinkmorestupidless.ankka.agent.javasdk.Decision
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An autonomous agent's tool that waits for a person: the task waits, a decision lets it go on. */
class OperatorTest {
    @Test
    fun `a tool that requires approval stops the task until a person decides`() {
        Operator.RUNS.clear()
        val model = TestModelProvider().expectToolCall("restart_service", """{"service":"cart"}""")
        val service = Ankka.service("ops").withModule(Agents.module().withModelProvider(model)).register(Operator::class.java)
        AnkkaTestKit.start(service).use { kit ->
            val client = kit.componentClient()
            val instance = AgentClients.forAutonomousAgent(client, Operator::class.java, "ops-1")
            val taskId = AgentClients.tasks(client).create(SUMMARY, "restart the cart service").create()
            instance.assign(taskId)

            kit.eventually(Duration.ofSeconds(30)) { assertTrue(instance.state().awaiting().isNotEmpty(), "nothing awaited") }
            val request = instance.state().awaiting().single()
            assertEquals("restart_service", request.tool())
            assertTrue(Operator.runsOf("restart_service").isEmpty(), "the tool ran before it was approved")

            model.expectCompleteTaskText("the cart is back")
            val approvalId = request.id()
            // docs:start decide
            AgentClients.forAutonomousAgent(client, Operator::class.java, "ops-1").decide(Decision.approved(approvalId, "dana"))
            // docs:end decide

            val done = AgentClients.forTask(client, taskId).await(SUMMARY, Duration.ofMinutes(1))
            assertEquals("the cart is back", done.result().orElseThrow())
            assertEquals(listOf("restart_service(cart)"), Operator.runsOf("restart_service"))
        }
    }
}
