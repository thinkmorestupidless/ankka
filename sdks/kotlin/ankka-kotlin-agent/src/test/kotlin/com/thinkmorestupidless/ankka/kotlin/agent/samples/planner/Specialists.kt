package com.thinkmorestupidless.ankka.kotlin.agent.samples.planner

import com.thinkmorestupidless.ankka.agent.javasdk.Agent
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect
import com.thinkmorestupidless.ankka.agent.javasdk.AgentSettings
import com.thinkmorestupidless.ankka.agent.javasdk.FunctionTool
import com.thinkmorestupidless.ankka.agent.javasdk.Memory
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe
import com.thinkmorestupidless.ankka.kotlin.agent.effects
import com.thinkmorestupidless.ankka.kotlin.agent.thenReply
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending

/**
 * Decides which specialists a request actually needs.
 *
 * This is what makes the orchestration *dynamic*: the workflow does not hard-code which agents to
 * consult, it asks. A question about rain consults the weather specialist; a question about cost
 * consults the budget one; the workflow is unchanged either way.
 */
// docs:start selector
@ComponentId("selector-agent")
class SelectorAgent : Agent() {

    @Command("select")
    fun select(request: String): AgentEffect<AgentSelection> =
        if (request.isBlank()) {
            effects { error("nothing to plan") }
        } else {
            effects {
                systemMessage(
                    """You route planning requests to specialists.
                      |Available specialists: ${Specialist.ALL.joinToString(", ")}.
                      |Reply with JSON: {"specialists":["..."],"reason":"..."}.
                      |Choose only the specialists the request genuinely needs."""
                        .trimMargin(),
                )
                    .userMessage(request)
                    // The selector's own deliberation is not part of the conversation the
                    // specialists and summariser share.
                    .memory(Memory.none())
                    .thenReply<AgentSelection>()
            }
        }
}
// docs:end selector

/**
 * Answers weather questions, with a tool.
 *
 * The tool stands in for a forecast API. What matters for the sample is that the model decides to
 * call it and ankka runs it.
 */
// docs:start weather
@ComponentId("weather-agent")
class WeatherAgent : Agent() {

    /** The role every message this agent writes is stored under. */
    override fun settings(): AgentSettings = AgentSettings.defaults().role(Specialist.WEATHER)

    @Command("consult")
    fun consult(destination: String): AgentEffect<String> = effects {
        systemMessage("You are a concise weather specialist. Use your tools, then answer in one sentence.")
            .userMessage("What is the weather like in $destination?")
            .tools(FORECAST)
            .thenReply()
    }

    data class Destination(@Describe("The city or region to forecast.") val destination: String)

    companion object {
        /** A stand-in forecast service, deterministic so the sample behaves the same each run. */
        val FORECAST: FunctionTool =
            FunctionTool.of("get_forecast", "Returns a short weather forecast for a destination.", Destination::class.java) {
                val place = it.destination
                val outlook =
                    when {
                        place.contains("reykjav", ignoreCase = true) -> "cold and windy"
                        place.contains("cairo", ignoreCase = true) -> "hot and dry"
                        else -> "mild with occasional rain"
                    }
                "$place: $outlook"
            }
    }
}
// docs:end weather

/**
 * Suggests activities, informed by the user's stored preferences.
 *
 * Reads the preferences entity through `componentClient()` rather than being handed them, which is
 * the documented way an agent enriches its own context.
 */
// docs:start activity
@ComponentId("activity-agent")
class ActivityAgent : Agent() {

    data class Request(val userId: String, val destination: String)

    override fun settings(): AgentSettings = AgentSettings.defaults().role(Specialist.ACTIVITY)

    @Command("consult")
    suspend fun consult(request: Request): AgentEffect<String> {
        // `componentClient()` is inherited from `Agent`; no constructor plumbing needed.
        val preferences =
            componentClient().forKeyValueEntity(request.userId).call(PreferencesEntity::get).invokeSuspending()

        return effects {
            systemMessage("You are a concise activity specialist. Suggest two activities, in one sentence.")
                .userMessage("What should I do in ${request.destination}?")
                // Preferences are context, not something the user said: memory records the
                // question, not the whole assembled prompt.
                .withContext("Traveller preferences: ${preferences.summary}")
                .thenReply()
        }
    }
}
// docs:end activity

/** Answers cost questions. Deliberately tool-free, to vary the mix. */
@ComponentId("budget-agent")
class BudgetAgent : Agent() {

    override fun settings(): AgentSettings = AgentSettings.defaults().role(Specialist.BUDGET)

    @Command("consult")
    fun consult(destination: String): AgentEffect<String> = effects {
        systemMessage("You are a concise budget specialist. Answer in one sentence.")
            .userMessage("Roughly what should I budget per day in $destination?")
            .thenReply()
    }
}

/**
 * Combines the specialists' answers, reading them back from the shared session. The selector
 * opted out of memory, so the session holds the specialists' contributions and nothing else.
 */
// docs:start summary
@ComponentId("summary-agent")
class SummaryAgent : Agent() {

    override fun settings(): AgentSettings = AgentSettings.defaults().role("summary")

    @Command("summarise")
    fun summarise(destination: String): AgentEffect<String> = effects {
        systemMessage("You write short trip briefs. Combine what the specialists said into two sentences.")
            .userMessage("Write a brief for a trip to $destination.")
            .memory(Memory.session())
            .thenReply()
    }
}
// docs:end summary
