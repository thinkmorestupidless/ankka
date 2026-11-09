package com.thinkmorestupidless.ankka.kotlin.agent.samples.planner

import com.thinkmorestupidless.ankka.javasdk.codec.Manifest

/**
 * The planner domain: plain Kotlin, no ankka types. These are what the agents talk about; nothing
 * here knows an agent exists.
 */

/** Named once so the selector, the workflow and the agents cannot disagree. */
object Specialist {
    const val WEATHER = "weather"
    const val ACTIVITY = "activity"
    const val BUDGET = "budget"

    val ALL: List<String> = listOf(WEATHER, ACTIVITY, BUDGET)
}

@Manifest("preferences")
data class Preferences(
    val userId: String,
    val likes: List<String> = emptyList(),
    val dislikes: List<String> = emptyList(),
    val maxBudget: Int = 0,
) {
    val isEmpty: Boolean
        get() = likes.isEmpty() && dislikes.isEmpty()

    val summary: String
        get() =
            if (isEmpty) {
                "no stated preferences"
            } else {
                listOfNotNull(
                    likes.takeIf { it.isNotEmpty() }?.let { "likes ${it.joinToString(", ")}" },
                    dislikes.takeIf { it.isNotEmpty() }?.let { "dislikes ${it.joinToString(", ")}" },
                    "budget $maxBudget",
                ).joinToString("; ")
            }
}

/** What the selector agent decided, and why. */
data class AgentSelection(val specialists: List<String>, val reason: String)

/** One specialist's contribution. */
data class Contribution(val specialist: String, val answer: String)

/** The state a planning run accumulates. */
@Manifest("plan-state")
data class PlanState(
    val userId: String,
    val destination: String,
    val status: String,
    val selection: AgentSelection?,
    val contributions: List<Contribution>,
    val summary: String?,
) {
    fun contributionFrom(specialist: String): String? = contributions.find { it.specialist == specialist }?.answer

    companion object {
        const val NOT_STARTED = "not-started"
        const val SELECTING = "selecting"
        const val CONSULTING = "consulting"
        const val COMPLETED = "completed"

        fun empty(): PlanState = PlanState("", "", NOT_STARTED, null, emptyList(), null)
    }
}
