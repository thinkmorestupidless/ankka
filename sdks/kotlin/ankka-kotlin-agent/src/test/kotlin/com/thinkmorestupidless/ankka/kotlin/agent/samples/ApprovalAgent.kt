package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.agent.javasdk.Agent
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect
import com.thinkmorestupidless.ankka.agent.javasdk.AgentOutcome
import com.thinkmorestupidless.ankka.agent.javasdk.Decision
import com.thinkmorestupidless.ankka.agent.javasdk.Tool
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar
import com.thinkmorestupidless.ankka.kotlin.agent.agentRef
import com.thinkmorestupidless.ankka.kotlin.agent.ask
import com.thinkmorestupidless.ankka.kotlin.agent.effects
import com.thinkmorestupidless.ankka.kotlin.agent.forAgent
import com.thinkmorestupidless.ankka.kotlin.agent.tools
import com.thinkmorestupidless.ankka.kotlin.dsl.routes
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A support agent's tools, one of which waits for a person. Each records what it was run with, so
 * a test can assert that a tool did not run, ran once, or ran with the arguments the model proposed.
 */
class SupportTools {
    // docs:start requires-approval
    data class Refund(@Describe("The order to refund.") val order: String, @Describe("The amount to refund.") val amount: Int)

    @Tool(
        "issue_refund",
        description = "Refunds an order. A supervisor approves every refund before it is made.",
        requiresApproval = true,
    )
    fun issueRefund(refund: Refund): String {
        RUNS.add("issue_refund(${refund.order},${refund.amount})")
        return "refunded ${refund.amount} on ${refund.order}"
    }
    // docs:end requires-approval

    @Tool("read_order", description = "Reads an order.")
    fun readOrder(@Describe("The order to read.") order: String): String {
        RUNS.add("read_order($order)")
        return "$order: one teapot, 40"
    }

    // docs:start requires-approval-within
    @Tool("close_account", description = "Closes a customer's account.", requiresApproval = true, approvalWithin = "PT2S")
    fun closeAccount(@Describe("The customer whose account to close.") customer: String): String {
        RUNS.add("close_account($customer)")
        return "closed $customer"
    }
    // docs:end requires-approval-within

    companion object {
        /** One line per run: `tool(arguments)`. */
        val RUNS: Queue<String> = ConcurrentLinkedQueue()

        fun runsOf(tool: String): List<String> = RUNS.filter { it.startsWith("$tool(") }
    }
}

/** A support agent whose refund tool waits for a person. */
@ComponentId("support-agent")
class ApprovalAgent : Agent() {

    @Command("ask")
    suspend fun ask(question: String): AgentEffect<String> = effects {
        systemMessage(SYSTEM_MESSAGE).userMessage(question).tools<SupportTools>().thenReply()
    }

    companion object {
        const val SYSTEM_MESSAGE = "You are a support agent. Refunds need a supervisor's approval."
    }
}

/** A decision as a route reads it: `{"approved": bool, "by": name, "note": text}`. */
data class DecisionBody(val approved: Boolean, val by: String, val note: String = "") {
    fun on(id: String): Decision = if (approved) Decision.approved(id, by) else Decision.refused(id, by, note)
}

/** An outcome as the routes render it: the answer, or the requests the turn waits on. */
private fun render(outcome: AgentOutcome<String>): String =
    when (outcome) {
        is AgentOutcome.Answered -> """{"answered":"${outcome.value()}"}"""
        is AgentOutcome.AwaitingApproval ->
            outcome.requests().joinToString(",", """{"awaiting":[""", "]}") { """{"id":"${it.id()}","tool":"${it.tool()}"}""" }
    }

/** The support agent over HTTP: a turn that may wait, and the route a person's decision comes through. */
class SupportEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        // docs:start approver-routes
        // A turn that may wait: the model's answer, or the approval requests it waits on.
        post<String>("/approver/{session}") { question ->
            render(componentClient().forAgent(path("session")).ask(ApprovalAgent::ask).invokeSuspending(question))
        }
        // A person's decision, answered as the turn's caller would have been once the turn goes on.
        post<DecisionBody>("/approver/{session}/decide/{id}") { body ->
            render(componentClient().forAgent(path("session")).decide(agentRef(ApprovalAgent::ask), body.on(path("id"))))
        }
        // docs:end approver-routes
    }
}
