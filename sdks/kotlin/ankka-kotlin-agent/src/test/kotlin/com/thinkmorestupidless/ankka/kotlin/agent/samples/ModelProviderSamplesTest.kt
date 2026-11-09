package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.javasdk.testkit.AgentTestKit
import com.thinkmorestupidless.ankka.kotlin.agent.agentRef
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The provider samples exist without a key, under the names the page gives them. */
class ModelProviderSamplesTest {
    @Test
    fun `each provider is named as the page says`() {
        assertEquals("anthropic", ModelProviderSamples.claude.name())
        assertEquals("openai", ModelProviderSamples.gpt.name())
        assertEquals("local", ModelProviderSamples.local.name())
    }

    @Test
    fun `a handler that names a provider calls it, and without its key the turn says which variable is missing`() {
        val result = AgentTestKit.of(Triage::class.java, TestModelProvider()).call(agentRef(Triage::route), "My invoice?")
        assertTrue(result.isError(), "the turn was answered without a key")
        assertContains(result.error(), "OPENAI_API_KEY")
    }

    @Test
    fun `the compacting service is a service`() {
        assertTrue(ModelProviderSamples.compactingService(TestModelProvider()).toString().isNotEmpty())
    }
}
