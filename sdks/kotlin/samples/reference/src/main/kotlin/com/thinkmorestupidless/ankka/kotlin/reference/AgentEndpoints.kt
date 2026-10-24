package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients
import com.thinkmorestupidless.ankka.agent.javasdk.AgentOutcome
import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousRef
import com.thinkmorestupidless.ankka.agent.javasdk.Decision
import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar
import com.thinkmorestupidless.ankka.kotlin.agent.agentRef
import com.thinkmorestupidless.ankka.kotlin.agent.ask
import com.thinkmorestupidless.ankka.kotlin.agent.call
import com.thinkmorestupidless.ankka.kotlin.agent.forAgent
import com.thinkmorestupidless.ankka.kotlin.agent.notificationsFlow
import com.thinkmorestupidless.ankka.kotlin.agent.streamFlow
import com.thinkmorestupidless.ankka.kotlin.agent.streamRef
import com.thinkmorestupidless.ankka.kotlin.dsl.routes
import com.thinkmorestupidless.ankka.kotlin.suspend.asPublisher
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import java.util.concurrent.Flow
import kotlinx.coroutines.flow.map

/** A decision as every reference reads it: `{"approved": bool, "by": name, "note": text}`. */
data class DecisionBody(val approved: Boolean, val by: String, val note: String = "") {
    fun on(id: String): Decision = if (approved) Decision.approved(id, by) else Decision.refused(id, by, note)
}

/** An outcome as every reference renders it: `{"answered": text}`, or `{"awaiting": [{"id", "tool", "arguments"}]}`. */
internal fun render(outcome: AgentOutcome<String>): String =
    when (outcome) {
        is AgentOutcome.Answered -> """{"answered":${quoted(outcome.value())}}"""
        is AgentOutcome.AwaitingApproval ->
            outcome.requests().joinToString(",", """{"awaiting":[""", "]}") {
                """{"id":${quoted(it.id())},"tool":${quoted(it.tool())},"arguments":${it.arguments()}}"""
            }
    }

/** The Scala reference's agent routes under `/conformance`, route for route. */
class AgentsEndpoint : HttpEndpoint(), RouteRegistrar {

    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        post<String>("/conformance/ask/{session}") { question ->
            componentClient().forAgent(path("session")).call(Assistant::ask).invokeSuspending(question)
        }
        post<String>("/conformance/ask-second/{session}") { question ->
            componentClient().forAgent(path("session")).call(agentRef(Assistant::askSecond)).invokeSuspending(question)
        }
        // A turn that may wait: the model's answer, or the approval requests it waits on.
        post<String>("/conformance/approver/{session}") { question ->
            render(componentClient().forAgent(path("session")).ask(Approver::ask).invokeSuspending(question))
        }
        // A person's decision, answered as the turn's caller would have been once the turn goes on.
        post<DecisionBody>("/conformance/approver/{session}/decide/{id}") { body ->
            render(componentClient().forAgent(path("session")).decide(agentRef(Approver::ask), body.on(path("id"))))
        }
    }

    @Sse("/conformance/stream-ask/{session}")
    fun streamAsk(session: String): Flow.Publisher<SseEvent> =
        componentClient().forAgent(session)
            .streamFlow(streamRef(Assistant::stream), request().query("q").orElse(""))
            .map { SseEvent.text(it) }
            .asPublisher()
}

/** Autonomous agents: tasks run, read and cancelled; instances driven and watched. */
class AutonomousEndpoint : HttpEndpoint(), RouteRegistrar {

    /** What `create` is given: the task's instructions, and the tasks it waits on. */
    data class CreateBody(val instructions: String, val dependsOn: List<String> = emptyList())

    override fun acl(): Acl = Acl.allowAll()

    private fun instance(id: String): AutonomousRef =
        AgentClients.forAutonomousAgent(componentClient(), Answerer::class.java, id)

    private fun answerType(type: String) {
        if (type != Answerer.ANSWER.name()) throw ProblemException(Problem.badRequest("no task type '$type'"))
    }

    override fun routes() = routes {
        post<String>("/autonomous/tasks/{type}") { instructions ->
            answerType(path("type"))
            val id = AgentClients.runSingleTask(componentClient(), Answerer::class.java, Answerer.ANSWER, instructions)
            val assignee = AgentClients.forTask(componentClient(), id).get().instanceId().orElse("")
            """{"taskId":${quoted(id)},"instanceId":${quoted(assignee)}}"""
        }
        get("/autonomous/tasks/{id}") { AgentClients.forTask(componentClient(), path("id")).get().json() }
        post("/autonomous/tasks/{id}/cancel") {
            AgentClients.forTask(componentClient(), path("id")).cancel("cancelled by caller")
            Done.done()
        }
        // Creates without running.
        post<CreateBody>("/autonomous/tasks/{type}/create") { body ->
            answerType(path("type"))
            val id = AgentClients.tasks(componentClient())
                .create(Answerer.ANSWER, body.instructions)
                .dependsOn(*body.dependsOn.toTypedArray())
                .create()
            """{"taskId":${quoted(id)}}"""
        }
        post<List<String>>("/autonomous/instances/{instance}/assign") { ids ->
            val result = instance(path("instance")).assign(*ids.toTypedArray())
            """{"accepted":${result.accepted().joinToString(",", "[", "]") { quoted(it) }}}"""
        }
        post("/autonomous/instances/{instance}/{op}") {
            val calls = instance(path("instance"))
            when (val op = path("op")) {
                "suspend" -> calls.suspend()
                "resume" -> calls.resume()
                "terminate" -> calls.terminate()
                else -> throw ProblemException(Problem.notFound("no operation '$op'"))
            }
            Done.done()
        }
        post<DecisionBody>("/autonomous/instances/{instance}/decide/{id}") { body ->
            instance(path("instance")).decide(body.on(path("id")))
            Done.done()
        }
        get("/autonomous/instances/{instance}/state") { instance(path("instance")).state().json() }
    }

    @Sse("/autonomous/instances/{instance}/notifications")
    fun notifications(instance: String): Flow.Publisher<SseEvent> =
        instance(instance).notificationsFlow().map { SseEvent.text(it.json()) }.asPublisher()
}
