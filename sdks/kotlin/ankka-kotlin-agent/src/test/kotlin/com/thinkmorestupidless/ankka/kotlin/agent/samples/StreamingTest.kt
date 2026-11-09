package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.agent.javasdk.Agents
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider
import com.thinkmorestupidless.ankka.kotlin.agent.forAgent
import com.thinkmorestupidless.ankka.kotlin.agent.streamFlow
import com.thinkmorestupidless.ankka.kotlin.agent.streamRef
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** A streamed reply: consumed as a flow, and served over HTTP as server-sent events. */
class StreamingTest {
    @Test
    fun `a streamed reply is a flow of tokens, and an SSE route serves it`() {
        // docs:start start-with-model
        val model = TestModelProvider().expectText("Oslo reaches 21")
        val service =
            Ankka.service("weather")
                .withModule(Agents.module().withModelProvider(model))
                .register(WeatherAgent::class.java)
                .withEndpoint(ChatEndpoint::class.java)
        AnkkaTestKit.start(service).use { kit ->
        // docs:end start-with-model
            // docs:start consume
            val tokens: Flow<String> =
                kit.componentClient().forAgent("s-1").streamFlow(streamRef(WeatherAgent::chat), "What is the weather?")
            val text = runBlocking { tokens.toList() }.joinToString("")
            // docs:end consume
            assertEquals("Oslo reaches 21", text)

            model.expectText("Oslo reaches 21")
            val request = HttpRequest.newBuilder(kit.http().resolve("/chat/s-2")).GET().build()
            val response = kit.httpClient().send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode(), response.body())
            assertContains(response.headers().firstValue("Content-Type").orElse(""), "text/event-stream")
            assertContains(response.body(), "Oslo")
        }
    }
}
