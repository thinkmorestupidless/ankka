package com.thinkmorestupidless.ankka.kotlin.agent

import com.thinkmorestupidless.ankka.agent.javasdk.Agent
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect
import com.thinkmorestupidless.ankka.agent.javasdk.Tool
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList

data class Forecast(val city: String, val degrees: Int)

// docs:start agent
data class Place(@Describe("the city to forecast") val city: String, val days: Int = 1)

/** A tool class whose tool is a suspend fun taking a data class, built by its own constructor. */
class WeatherTools {
    @Tool("forecast", description = "The forecast for a city")
    suspend fun forecast(place: Place): String {
        delay(1)
        ASKED.add("${place.city}/${place.days}")
        return "${place.city}: 21C"
    }

    companion object {
        val ASKED: MutableList<String> = CopyOnWriteArrayList()
    }
}

/** An agent written as a Kotlin developer writes one: suspend handlers and the effects DSL. */
@ComponentId("forecaster")
class Forecaster : Agent() {
    @Command("ask")
    suspend fun ask(question: String): AgentEffect<String> {
        delay(1)
        return effects {
            systemMessage("You answer questions about the weather.")
                .userMessage(question)
                .tools<WeatherTools>()
                .thenReply()
        }
    }

    @Command("structured")
    fun structured(question: String): AgentEffect<Forecast> =
        effects { userMessage(question).thenReply<Forecast>() }
}
// docs:end agent
