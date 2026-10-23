package com.thinkmorestupidless.ankka.kotlin.agent

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** The guide in a whole service: a task worked to its end by a scripted model, and a turn of an agent. */
class GuideTest {
    @Test
    fun `a task is worked to its end, its result read as the data class`() {
        // docs:start test
        val model =
            TestModelProvider()
                .expectToolCall("read_page", """{"page":"style"}""")
                .expectCompleteTask("""{"answer":"Be brief.","sources":["style"]}""")
        AnkkaTestKit.start(guideService(model)).use { kit ->
            val client = kit.componentClient()
            // docs:start run-task
            val taskId = AgentClients.runSingleTask(client, Guide::class.java, ANSWER, "How should I write?")
            val done = AgentClients.forTask(client, taskId).await(ANSWER, Duration.ofMinutes(1))
            // docs:end run-task
            assertEquals(Answer("Be brief.", listOf("style")), done.result().orElseThrow())
        }
        // docs:end test
    }

    @Test
    fun `a turn of an agent is answered through the component client`() {
        val model = TestModelProvider().expectText("Mild.")
        AnkkaTestKit.start(guideService(model)).use { kit ->
            // docs:start call-agent
            val reply = runBlocking {
                kit.componentClient().forAgent("s-1").call(Forecaster::ask).invokeSuspending("How warm is Oslo?")
            }
            // docs:end call-agent
            assertEquals("Mild.", reply)
        }
    }
}
