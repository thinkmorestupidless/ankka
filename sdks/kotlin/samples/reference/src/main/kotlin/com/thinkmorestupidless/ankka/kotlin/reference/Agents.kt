package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.agent.javasdk.Agent
import com.thinkmorestupidless.ankka.agent.javasdk.AgentBuilder
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect
import com.thinkmorestupidless.ankka.agent.javasdk.AgentSettings
import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousAgent
import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousDefinition
import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail
import com.thinkmorestupidless.ankka.agent.javasdk.McpServer
import com.thinkmorestupidless.ankka.agent.javasdk.RuleResult
import com.thinkmorestupidless.ankka.agent.javasdk.StreamEffect
import com.thinkmorestupidless.ankka.agent.javasdk.Task
import com.thinkmorestupidless.ankka.agent.javasdk.TaskType
import com.thinkmorestupidless.ankka.agent.javasdk.Tool
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe
import com.thinkmorestupidless.ankka.javasdk.annotations.Stream
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClients
import com.thinkmorestupidless.ankka.kotlin.agent.effects
import com.thinkmorestupidless.ankka.kotlin.call
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import java.util.concurrent.ConcurrentHashMap

/** Looks up how many things were recorded under an id, recording that it looked. */
class Lookup(private val client: ComponentClient) {
    @Tool(value = "lookup", description = "Looks up how many things were recorded under an id.")
    suspend fun lookup(@Describe("The id to look up.") id: String): String {
        require(id.isNotEmpty()) { "an id is needed" }
        val entity = client.forEventSourcedEntity(id)
        entity.call(ConformanceEntity::record).invokeSuspending("looked-up")
        return "count for $id is ${entity.call(ConformanceEntity::count).invokeSuspending()}"
    }
}

/** An agent whose tool acts through the client. */
@ComponentId("assistant")
class Assistant : Agent() {

    private fun describe(question: String): AgentBuilder = effects {
        systemMessage("You are helpful.")
            .userMessage(question)
            .tools(Lookup(componentClient()))
            .guardrails(Guardrail.forbidding("no-secrets", "sk-"))
    }

    @Command("ask")
    suspend fun ask(question: String): AgentEffect<String> = describe(question).thenReply()

    @Stream("stream")
    fun stream(question: String): StreamEffect = describe(question).thenStream()

    /** Answered by the model the service named `second`, not its default. */
    @Command("ask-second")
    fun askSecond(question: String): AgentEffect<String> = effects {
        model(KotlinReference.second()).systemMessage("You are helpful.").userMessage(question).thenReply()
    }
}

/** An agent whose tools wait for a person, with two MCP servers. */
@ComponentId("approver")
class Approver : Agent() {

    // Both found at ANKKA_MCP_<NAME>_URL; every tool of `guarded` waits for a person.
    override fun settings(): AgentSettings =
        AgentSettings.defaults()
            .mcpServer(McpServer.named("tickets"))
            .mcpServer(McpServer.named("guarded").requiresApproval())
            .resultGuardrail(Guardrail.forbidding("no-instructions", "(?i)ignore what you were told"))

    class Tools(private val client: ComponentClient, private val services: ServiceClients) {
        @Tool(
            value = "refund",
            description = "Refunds what was recorded under an id. A person approves every refund.",
            requiresApproval = true,
        )
        suspend fun refund(@Describe("The id to refund.") id: String): String {
            client.forEventSourcedEntity(id).call(ConformanceEntity::record).invokeSuspending("refunded")
            return "refunded $id"
        }

        // A tool calls another service as this service: the called service's ACL can admit it by name.
        @Tool(value = "ask_scripted", description = "Asks the scripted service for what is at a path.")
        fun askScripted(@Describe("The path to ask for.") path: String): String = services.of("scripted").getText(path)
    }

    @Command("ask")
    suspend fun ask(question: String): AgentEffect<String> = effects {
        systemMessage("You approve refunds.")
            .userMessage(question)
            .tools(Tools(componentClient(), services()))
            .thenReply()
    }
}

/** An autonomous agent, whose tools act through the client. */
@ComponentId("answerer")
class Answerer : AutonomousAgent() {

    data class Answer(val answer: String, val sources: List<String>)

    class Tools(private val client: ComponentClient) {
        @Tool(value = "lookup", description = "Looks up how many things were recorded under an id.")
        suspend fun lookup(@Describe("The id to look up.") id: String): String = Lookup(client).lookup(id)

        // Waits for a person before it runs; what it records is counted as a run.
        @Tool(
            value = "sensitive_lookup",
            description = "Looks up what was recorded under an id. A person approves every one.",
            requiresApproval = true,
        )
        suspend fun sensitiveLookup(@Describe("The id to look up.") id: String): String {
            val entity = client.forEventSourcedEntity(id)
            entity.call(ConformanceEntity::record).invokeSuspending("sensitive")
            return "sensitive count for $id is ${entity.call(ConformanceEntity::count).invokeSuspending()}"
        }
    }

    override fun definition(): AutonomousDefinition =
        AutonomousDefinition.describedAs("Answers questions")
            .guardrails(Guardrail.forbidding("no-secrets", "sk-"))
            .accepts(ANSWER, 4)

    override fun tools(): List<Any> = listOf(Tools(componentClient()))

    companion object {
        /** The answers the steady rule has thrown on once. */
        private val flakySeen: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val ANSWER: TaskType<Answer> =
            Task.named("answer")
                .describedAs("Answer a question, citing what you looked up")
                .resultConformsTo(Answer::class.java)
                .rule("cites-sources") {
                    if (it.sources.isEmpty()) RuleResult.rejected("sources must not be empty") else RuleResult.accepted()
                }
                // Throws the first time it sees "flaky-once": a rule that fails once, then decides.
                .rule("steady") {
                    check(!(it.answer == "flaky-once" && flakySeen.add(it.answer))) { "the rule threw" }
                    RuleResult.accepted()
                }
    }
}
