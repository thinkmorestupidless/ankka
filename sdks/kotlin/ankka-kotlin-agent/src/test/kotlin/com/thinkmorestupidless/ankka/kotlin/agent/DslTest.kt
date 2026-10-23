package com.thinkmorestupidless.ankka.kotlin.agent

import com.thinkmorestupidless.ankka.javasdk.testkit.AgentTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * A Kotlin agent through the Java agent test kit: a suspend handler is read and run, the effects
 * DSL builds the Java effect, a suspend tool runs with its data class argument, and a reply is read
 * as a data class.
 */
class DslTest {
    @BeforeTest
    fun clear() = WeatherTools.ASKED.clear()

    @Test
    fun `a suspend handler offers a suspend tool, which runs with its data class argument`() {
        val model =
            TestModelProvider()
                .expectToolCall("forecast", """{"city":"Oslo","days":2}""")
                .expectText("Oslo will be 21C")
        val kit = AgentTestKit.of(Forecaster::class.java, model)

        val result = kit.call(agentRef(Forecaster::ask), "How warm is Oslo?")

        assertEquals("Oslo will be 21C", result.reply())
        assertEquals(listOf("Oslo/2"), WeatherTools.ASKED)
        assertContains(model.lastRequest(), "Oslo: 21C")
    }

    @Test
    fun `the model is told the data class's properties and their descriptions`() {
        val model = TestModelProvider().expectText("fine")
        AgentTestKit.of(Forecaster::class.java, model).call(agentRef(Forecaster::ask), "Anything?").reply()

        val request = model.lastRequest()
        assertContains(request, "the city to forecast")
        assertContains(request, "days")
    }

    @Test
    fun `a reply is read as the data class the handler names`() {
        val model = TestModelProvider().expectText("""{"city":"Oslo","degrees":21}""")
        val kit = AgentTestKit.of(Forecaster::class.java, model)

        assertEquals(Answer("Oslo", 21), kit.call(Forecaster::structured, "Oslo?").reply())
    }
}
