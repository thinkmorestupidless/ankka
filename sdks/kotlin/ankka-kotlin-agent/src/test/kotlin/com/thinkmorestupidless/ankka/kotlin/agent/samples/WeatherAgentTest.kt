package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.javasdk.testkit.AgentTestKit
import com.thinkmorestupidless.ankka.kotlin.agent.agentRef
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The agents guide's weather agent, one turn at a time against a scripted model. */
class WeatherAgentTest {
    @BeforeTest
    fun clear() = WeatherTools.TOOL_CALLS.clear()

    @Test
    fun `the tools are offered with their descriptions, and run with the model's arguments`() {
        // docs:start scripted-model
        val model =
            TestModelProvider()
                .expectToolCall("current_date", "{}")
                .expectToolCall("get_weather", """{"location":"Lisbon","date":"2026-09-06"}""")
                .expectText("Lisbon: sunny today.")
        // docs:end scripted-model
        val kit = AgentTestKit.of(WeatherAgent::class.java, model)

        val result = kit.call(agentRef(WeatherAgent::ask), "Weather in Lisbon today?")

        assertEquals("Lisbon: sunny today.", result.reply())
        assertEquals(listOf("current_date()", "get_weather(Lisbon,2026-09-06)"), WeatherTools.TOOL_CALLS.toList())
        assertContains(model.requests().first(), "A location or city name.")
        assertContains(model.lastRequest(), "Lisbon: 18C, sunny")
    }

    @Test
    fun `a date the model leaves out is read as none`() {
        val model = TestModelProvider().expectToolCall("get_weather", """{"location":"Oslo"}""").expectText("Oslo: sunny.")
        AgentTestKit.of(WeatherAgent::class.java, model).call(agentRef(WeatherAgent::ask), "Oslo?")
        assertEquals(listOf("get_weather(Oslo,-)"), WeatherTools.TOOL_CALLS.toList())
    }

    @Test
    fun `a structured reply is read as the data class`() {
        val model = TestModelProvider().expectText("""{"location":"Lisbon","summary":"sunny","degreesCelsius":18}""")
        val kit = AgentTestKit.of(WeatherAgent::class.java, model)

        assertEquals(Forecast("Lisbon", "sunny", 18), kit.call(agentRef(WeatherAgent::askStructured), "Lisbon?").reply())
    }

    @Test
    fun `an input guardrail refuses before any model is called`() {
        val model = TestModelProvider()
        val result = AgentTestKit.of(WeatherAgent::class.java, model).call(agentRef(WeatherAgent::guarded), "x".repeat(41))

        assertTrue(result.isError(), "the long question was answered")
        assertEquals(403, result.problem().status())
        assertEquals(0, model.callCount())
    }

    @Test
    fun `a guardrail of our own checks the reply on the way out`() {
        val model = TestModelProvider().expectText("use sk-12345")
        val result = AgentTestKit.of(WeatherAgent::class.java, model).call(agentRef(WeatherAgent::safe), "Any key?")

        assertTrue(result.isError(), "the reply with a key was let through")
        assertContains(result.error(), "no-keys")
    }

    @Test
    fun `a short memory reads only the last two messages`() {
        val model = TestModelProvider().expectText("Sunny.").expectText("Rainy.").expectText("Windy.")
        val kit = AgentTestKit.of(WeatherAgent::class.java, model)
        kit.call(agentRef(WeatherAgent::askWithShortMemory), "Lisbon?")
        kit.call(agentRef(WeatherAgent::askWithShortMemory), "Oslo?")
        kit.call(agentRef(WeatherAgent::askWithShortMemory), "Rome?")

        // The third turn sees the second turn's two messages and its own question, not the first turn.
        assertContains(model.lastRequest(), "Rainy.")
        assertTrue(!model.lastRequest().contains("Sunny."), model.lastRequest())
    }

    @Test
    fun `a blank question is refused without a model`() {
        val model = TestModelProvider()
        val result = AgentTestKit.of(WeatherAgent::class.java, model).call(agentRef(WeatherAgent::ask), " ")
        assertTrue(result.isError())
        assertEquals(0, model.callCount())
    }
}
