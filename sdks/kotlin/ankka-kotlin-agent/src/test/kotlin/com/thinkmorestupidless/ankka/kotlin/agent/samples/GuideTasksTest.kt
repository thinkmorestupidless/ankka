package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider
import com.thinkmorestupidless.ankka.kotlin.agent.ANSWER
import com.thinkmorestupidless.ankka.kotlin.agent.Answer
import com.thinkmorestupidless.ankka.kotlin.agent.Guide
import com.thinkmorestupidless.ankka.kotlin.agent.guideService
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** Several tasks for one instance, worked one at a time, in order. */
class GuideTasksTest {
    @Test
    fun `an instance is given tasks with a dependency between them, and works them in order`() {
        val model =
            TestModelProvider()
                .expectCompleteTask("""{"answer":"a teapot","sources":["c-1"]}""")
                .expectCompleteTask("""{"answer":"nothing","sources":["c-2"]}""")
        AnkkaTestKit.start(guideService(model)).use { kit ->
            val client = kit.componentClient()
            // docs:start assign-tasks
            val first = AgentClients.tasks(client).create(ANSWER, "What is in cart c-1?").create()
            val second = AgentClients.tasks(client).create(ANSWER, "And in c-2?").dependsOn(first).create()
            AgentClients.forAutonomousAgent(client, Guide::class.java, "reviewer-1").assign(first, second)
            // docs:end assign-tasks

            val timeout = Duration.ofMinutes(1)
            assertEquals(Answer("a teapot", listOf("c-1")), AgentClients.forTask(client, first).await(ANSWER, timeout).result().orElseThrow())
            assertEquals(Answer("nothing", listOf("c-2")), AgentClients.forTask(client, second).await(ANSWER, timeout).result().orElseThrow())
        }
    }
}
