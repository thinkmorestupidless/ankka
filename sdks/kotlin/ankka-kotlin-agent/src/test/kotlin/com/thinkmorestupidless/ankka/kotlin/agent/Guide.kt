package com.thinkmorestupidless.ankka.kotlin.agent

import com.thinkmorestupidless.ankka.agent.javasdk.Agents
import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousAgent
import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousDefinition
import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProvider
import com.thinkmorestupidless.ankka.agent.javasdk.RuleResult
import com.thinkmorestupidless.ankka.agent.javasdk.Task
import com.thinkmorestupidless.ankka.agent.javasdk.TaskType
import com.thinkmorestupidless.ankka.agent.javasdk.Tool
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe

// docs:start task-type
/** An answer, and the pages the agent read to reach it. */
data class Answer(val answer: String, val sources: List<String>)

/** `"answer"` is the wire name, written into every task of this type. */
val ANSWER: TaskType<Answer> =
    Task.named("answer")
        .describedAs("Answer a question from the guide, citing the pages you read")
        .resultConformsTo(Answer::class.java)
        .rule("cites-sources") {
            if (it.sources.isEmpty()) RuleResult.rejected("say which pages you read in sources") else RuleResult.accepted()
        }
// docs:end task-type

// docs:start agent
/** The guide's pages, read by a suspending tool. */
class GuideTools {
    @Tool("read_page", description = "Reads one page of the guide.")
    suspend fun readPage(@Describe("The page's name.") page: String): String = "the $page page says: be brief"
}

/** Answers questions from the guide, on its own, until it completes the task with an [Answer]. */
@ComponentId("guide")
class Guide : AutonomousAgent() {
    override fun definition(): AutonomousDefinition =
        AutonomousDefinition.describedAs("Answers questions from the guide")
            .instructions("Read the guide rather than guessing. Be brief.")
            .guardrails(Guardrail.maxInputLength(2000))
            .accepts(ANSWER, 5)

    override fun tools(): List<Any> = listOf(GuideTools())
}
// docs:end agent

// docs:start registration
fun guideService(model: ModelProvider): ServiceBuilder =
    Ankka.service("guide")
        .withModule(Agents.module().withModelProvider(model))
        .register(Guide::class.java)
        .register(Forecaster::class.java)
// docs:end registration
