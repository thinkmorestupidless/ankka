package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.javasdk.testkit.AgentTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider
import com.thinkmorestupidless.ankka.kotlin.agent.agentRef
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The judgments guide's triage agent. The Java and Kotlin kits script a model, not a judgment
 * provider, so these prove the declarations and what a judgment does without one.
 */
class TriageAgentTest {
    @Test
    fun `a question is a value, declared once and read back by its id`() {
        assertEquals("route", TriageAgent.ROUTE.id())
        assertEquals("How frustrated is the customer?", TriageAgent.FRUSTRATION.instructions())
        assertEquals(listOf("refund", "urgent"), listOf(TriageAgent.REFUND.id(), TriageAgent.URGENT.id()))
        assertEquals("topic", TriageAgent.Companion.Safety.TOPIC.id())
    }

    @Test
    fun `a judgment asked with no provider fails, saying what to configure`() {
        val result = AgentTestKit.of(TriageAgent::class.java, TestModelProvider()).call(agentRef(TriageAgent::triage), Ticket("t-1", "My invoice is wrong"))
        assertTrue(result.isError(), "a judgment with no provider answered")
        assertContains(result.error(), "withJudgments")
    }

    @Test
    fun `a judged guardrail with no provider refuses the turn before any model is called`() {
        val model = TestModelProvider()
        val result = AgentTestKit.of(TriageAgent::class.java, model).call(agentRef(TriageAgent::guarded), "Where is my order?")
        assertTrue(result.isError(), "a judged guardrail with no provider let the turn through")
        assertEquals(0, model.requests().size)
    }

    @Test
    fun `the provider is read from its variable when the service is built`() {
        if (System.getenv("TYPESAFE_API_KEY") == null) {
            val e = assertFailsWith<Exception> { TriageAgent.triageService() }
            assertContains(generateSequence<Throwable>(e) { it.cause }.joinToString { it.message ?: "" }, "TYPESAFE_API_KEY")
        } else {
            TriageAgent.triageService()
        }
    }
}
