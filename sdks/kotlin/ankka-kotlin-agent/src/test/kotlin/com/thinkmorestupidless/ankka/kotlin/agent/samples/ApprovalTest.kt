package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.agent.javasdk.AgentOutcome
import com.thinkmorestupidless.ankka.agent.javasdk.Agents
import com.thinkmorestupidless.ankka.agent.javasdk.Decision
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider
import com.thinkmorestupidless.ankka.kotlin.agent.agentRef
import com.thinkmorestupidless.ankka.kotlin.agent.ask
import com.thinkmorestupidless.ankka.kotlin.agent.forAgent
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** A tool that waits for a person, driven through the client and through the routes. */
class ApprovalTest {
    private val model = TestModelProvider()
    private lateinit var kit: AnkkaTestKit

    private fun service(): ServiceBuilder =
        Ankka.service("support")
            .withModule(Agents.module().withModelProvider(model))
            .register(ApprovalAgent::class.java)
            .withEndpoint(SupportEndpoint::class.java)

    @BeforeTest
    fun start() {
        SupportTools.RUNS.clear()
        kit = AnkkaTestKit.start(service())
    }

    @AfterTest
    fun stop() = kit.close()

    @Test
    fun `a turn waits for a decision, and goes on once a person approves`() {
        model
            .expectToolCall("issue_refund", """{"order":"o-7","amount":40}""")
            .expectText("Your refund of 40 is on its way.")
        val componentClient = kit.componentClient()
        val answer = runBlocking {
            // docs:start ask-and-decide
            val session = componentClient.forAgent("s-42")
            when (val outcome = session.ask(ApprovalAgent::ask).invokeSuspending("refund order o-7")) {
                is AgentOutcome.Answered -> outcome.value()
                is AgentOutcome.AwaitingApproval -> {
                    // In a real service the requests are shown to a person, and the decision arrives later,
                    // from whatever route they make it through; that route's ACL is who may decide.
                    val decision = Decision.approved(outcome.requests().first().id(), "dana")
                    when (val decided = session.decide(agentRef(ApprovalAgent::ask), decision)) {
                        is AgentOutcome.Answered -> decided.value()
                        is AgentOutcome.AwaitingApproval -> "still waiting on ${decided.requests().size}"
                    }
                }
            }
            // docs:end ask-and-decide
        }
        assertEquals("Your refund of 40 is on its way.", answer)
        assertEquals(listOf("issue_refund(o-7,40)"), SupportTools.runsOf("issue_refund"))
    }

    @Test
    fun `a tool with a time limit records when its request expires`() {
        model.expectToolCall("close_account", """{"customer":"c-1"}""")
        val outcome = runBlocking {
            kit.componentClient().forAgent("s-close").ask(ApprovalAgent::ask).invokeSuspending("close account c-1")
        }
        val waiting = outcome as AgentOutcome.AwaitingApproval
        val request = waiting.requests().single()
        assertEquals("close_account", request.tool())
        assertTrue(request.expiresAt().isPresent, "no expiry on a tool with a time limit")
        assertTrue(SupportTools.runsOf("close_account").isEmpty(), "the tool ran before it was approved")
    }

    @Test
    fun `the routes answer the requests, then the answer once a person decides`() {
        model
            .expectToolCall("issue_refund", """{"order":"o-9","amount":12}""")
            .expectText("Refunded.")
        val asked = post("/approver/s-http", "refund order o-9", "text/plain")
        assertContains(asked, """"tool":"issue_refund"""")
        val id = Regex(""""id":"([^"]+)"""").find(asked)!!.groupValues[1]

        val decided = post("/approver/s-http/decide/$id", """{"approved":true,"by":"dana"}""", "application/json")
        assertEquals("""{"answered":"Refunded."}""", decided)
        assertEquals(listOf("issue_refund(o-9,12)"), SupportTools.runsOf("issue_refund"))
    }

    private fun post(path: String, body: String, contentType: String): String {
        val request =
            HttpRequest.newBuilder(kit.http().resolve(path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = kit.httpClient().send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return response.body()
    }
}
