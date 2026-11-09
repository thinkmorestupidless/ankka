package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.agent.javasdk.Agent
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect
import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail
import com.thinkmorestupidless.ankka.agent.javasdk.Memory
import com.thinkmorestupidless.ankka.agent.javasdk.StreamEffect
import com.thinkmorestupidless.ankka.agent.javasdk.Tool
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe
import com.thinkmorestupidless.ankka.javasdk.annotations.Stream
import com.thinkmorestupidless.ankka.kotlin.agent.effects
import com.thinkmorestupidless.ankka.kotlin.agent.thenReply
import com.thinkmorestupidless.ankka.kotlin.agent.tools
import java.util.Optional
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

// docs:start structured-type
/** A structured answer. A data class needs no codec: its properties are what the model's JSON is read as. */
data class Forecast(val location: String, val summary: String, val degreesCelsius: Int)
// docs:end structured-type

// docs:start tools
/** What the model hands the weather tool: a location, and a date when it names one. */
data class WeatherQuery(
    @Describe("A location or city name.") val location: String,
    @Describe("Forecast date, in yyyy-MM-dd format.") val date: String? = null,
)

/** The agent's tools: each `@Tool` method is one, and its parameters are the schema the model sees. */
class WeatherTools {
    @Tool("get_weather", description = "Returns the weather forecast for a given city.")
    fun getWeather(query: WeatherQuery): String {
        TOOL_CALLS.add("get_weather(${query.location},${query.date ?: "-"})")
        if (query.location == "Nowhere") throw RuntimeException("unknown location")
        return "${query.location}: 18C, sunny"
    }

    @Tool("current_date", description = "Returns today's date in yyyy-MM-dd format.")
    fun currentDate(): String {
        TOOL_CALLS.add("current_date()")
        return "2026-09-06"
    }

    companion object {
        /** Records what the tools were asked, so a test can assert the model's arguments arrived. */
        val TOOL_CALLS: Queue<String> = ConcurrentLinkedQueue()
    }
}
// docs:end tools

// docs:start custom-guardrail
val noKeys: Guardrail =
    object : Guardrail {
        override fun name() = "no-keys"

        override fun checkOutput(text: String): Optional<String> =
            if (text.contains("sk-")) Optional.of("the reply contains what looks like a key") else Optional.empty()
    }
// docs:end custom-guardrail

/** An agent with tools, a system message and a structured variant, as the Scala `WeatherAgent`. */
@ComponentId("weather-agent")
class WeatherAgent : Agent() {

    @Command("ask")
    fun ask(question: String): AgentEffect<String> =
        if (question.isBlank()) {
            effects { error("ask me something") }
        } else {
            effects { systemMessage(SYSTEM_MESSAGE).userMessage(question).tools<WeatherTools>().thenReply() }
        }

    // docs:start chat
    /** Streams the reply token by token, tools and all. */
    @Stream("chat")
    fun chat(question: String): StreamEffect = effects {
        systemMessage(SYSTEM_MESSAGE).userMessage(question).tools<WeatherTools>().thenStream()
    }
    // docs:end chat

    // docs:start structured
    /** Same interaction, but the reply is parsed into a [Forecast]. */
    @Command("ask-structured")
    fun askStructured(question: String): AgentEffect<Forecast> = effects {
        systemMessage("$SYSTEM_MESSAGE Reply with JSON.").userMessage(question).thenReply<Forecast>()
    }
    // docs:end structured

    // docs:start guarded
    /** Guarded, to exercise rejection before a model is called. */
    @Command("guarded")
    fun guarded(question: String): AgentEffect<String> = effects {
        systemMessage(SYSTEM_MESSAGE).userMessage(question).guardrails(Guardrail.maxInputLength(40)).thenReply()
    }
    // docs:end guarded

    /** Checked on the way out by a guardrail of our own. */
    @Command("safe")
    fun safe(question: String): AgentEffect<String> = effects {
        systemMessage(SYSTEM_MESSAGE).userMessage(question).guardrails(noKeys).thenReply()
    }

    // docs:start short-memory
    /** Reads only the last two messages, to exercise the memory window. */
    @Command("ask-short-memory")
    fun askWithShortMemory(question: String): AgentEffect<String> = effects {
        systemMessage(SYSTEM_MESSAGE).userMessage(question).memory(Memory.session().readLast(2)).thenReply()
    }
    // docs:end short-memory

    companion object {
        const val SYSTEM_MESSAGE = "You are a concise weather assistant."
    }
}
