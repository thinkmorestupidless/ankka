package com.thinkmorestupidless.ankka.kotlin.agent

import com.thinkmorestupidless.ankka.agent.javasdk.Agent
import com.thinkmorestupidless.ankka.agent.javasdk.AgentBuilder
import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients
import com.thinkmorestupidless.ankka.agent.javasdk.AgentCommandRef
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffects
import com.thinkmorestupidless.ankka.agent.javasdk.AgentNoArgRef
import com.thinkmorestupidless.ankka.agent.javasdk.AgentOutcome
import com.thinkmorestupidless.ankka.agent.javasdk.AgentRef
import com.thinkmorestupidless.ankka.agent.javasdk.AgentStreamRef
import com.thinkmorestupidless.ankka.agent.javasdk.StreamEffect
import com.thinkmorestupidless.ankka.agent.javasdk.spi.AgentComponentEffects
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient
import com.thinkmorestupidless.ankka.javasdk.client.Invocation
import com.thinkmorestupidless.ankka.kotlin.functionReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.jdk9.asFlow
import kotlin.reflect.KFunction
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

// An agent's effects, built in a block whose receiver is the agent's own effects:
//
//   @Command("ask")
//   suspend fun ask(question: String): AgentEffect<Answer> = effects {
//       systemMessage("You answer questions about the weather.")
//           .userMessage(question)
//           .tools<WeatherTools>()
//           .thenReply<Answer>()
//   }
//
// Each block produces exactly the Java effect the chained form does; the builders are inline, so a
// block is no method of the agent's class. A tool class's @Tool methods may be suspend funs, called
// on the turn's virtual thread through the same adapter as a suspending handler.

inline fun <X> Agent.effects(build: AgentEffects.() -> X): X = AgentComponentEffects.of(this).build()

/** Reply with the model's answer read as [R], a data class or any type the SDK encodes. */
inline fun <reified R : Any> AgentBuilder.thenReply(): AgentEffect<R> = thenReply(R::class.java)

/** Offer the model the @Tool methods of [T], built with its constructor that takes nothing. */
inline fun <reified T : Any> AgentBuilder.tools(): AgentBuilder = tools(T::class.java)

// References to an agent's handlers where the Java SDK takes one, for a handler that is a suspend
// fun, whose reference cannot become a Java functional interface.

/** The handler [handler] names, which takes [I]: `agentRef(Assistant::ask)`. */
@JvmName("suspendingAgentRef")
fun <A : Agent, I : Any, O : Any> agentRef(handler: KSuspendFunction2<A, I, AgentEffect<O>>): AgentCommandRef<A, I, O> =
    NamedAgentCommand(handler)

/** The handler [handler] names, which takes [I]. */
fun <A : Agent, I : Any, O : Any> agentRef(handler: KFunction2<A, I, AgentEffect<O>>): AgentCommandRef<A, I, O> =
    NamedAgentCommand(handler)

/** The handler [handler] names, which takes nothing. */
@JvmName("suspendingAgentNoArgRef")
fun <A : Agent, O : Any> agentRef(handler: KSuspendFunction1<A, AgentEffect<O>>): AgentNoArgRef<A, O> =
    NamedAgentNoArg(handler)

/** The handler [handler] names, which takes nothing. */
@JvmName("agentNoArgRef")
fun <A : Agent, O : Any> agentRef(handler: KFunction1<A, AgentEffect<O>>): AgentNoArgRef<A, O> = NamedAgentNoArg(handler)

/** The streaming handler [handler] names. */
@JvmName("suspendingStreamRef")
fun <A : Agent, I : Any> streamRef(handler: KSuspendFunction2<A, I, StreamEffect>): AgentStreamRef<A, I> =
    NamedAgentStream(handler)

/** The streaming handler [handler] names. */
fun <A : Agent, I : Any> streamRef(handler: KFunction2<A, I, StreamEffect>): AgentStreamRef<A, I> =
    NamedAgentStream(handler)

// Calls to an agent.

/** The agent session [sessionId]. */
fun ComponentClient.forAgent(sessionId: String): AgentRef = AgentClients.forAgent(this, sessionId)

/** A call to the `suspend fun` handler [handler] names, which takes [I]. */
@JvmName("callSuspendingHandler")
fun <A : Agent, I : Any, O : Any> AgentRef.call(handler: KSuspendFunction2<A, I, AgentEffect<O>>): Invocation<I, O> =
    call(agentRef(handler))

/** A call to the `suspend fun` handler [handler] names, which takes nothing. */
@JvmName("callSuspendingNoArgHandler")
fun <A : Agent, O : Any> AgentRef.call(handler: KSuspendFunction1<A, AgentEffect<O>>): Invocation<Void, O> =
    call(agentRef(handler))

/** The outcome of the `suspend fun` handler [handler] names: its answer, or what it waits on. */
@JvmName("askSuspendingHandler")
fun <A : Agent, I : Any, O : Any> AgentRef.ask(
    handler: KSuspendFunction2<A, I, AgentEffect<O>>,
): Invocation<I, AgentOutcome<O>> = ask(agentRef(handler))

/** The reply of the streaming handler [handler] names, as it is written. */
fun <A : Agent, I : Any> AgentRef.streamFlow(handler: AgentStreamRef<A, I>, input: I): Flow<String> =
    stream(handler, input).asFlow()

private class NamedAgentCommand<A : Agent, I : Any, O : Any>(private val handler: KFunction<AgentEffect<O>>) :
    AgentCommandRef<A, I, O> {
    override fun apply(agent: A, input: I): AgentEffect<O> = refused(handler)

    @Suppress("unused") // read by the SDK, as it reads a method reference's
    private fun writeReplace(): Any = functionReference(handler, AgentCommandRef::class.java)
}

private class NamedAgentNoArg<A : Agent, O : Any>(private val handler: KFunction<AgentEffect<O>>) : AgentNoArgRef<A, O> {
    override fun apply(agent: A): AgentEffect<O> = refused(handler)

    @Suppress("unused") // read by the SDK, as it reads a method reference's
    private fun writeReplace(): Any = functionReference(handler, AgentNoArgRef::class.java)
}

private class NamedAgentStream<A : Agent, I : Any>(private val handler: KFunction<StreamEffect>) : AgentStreamRef<A, I> {
    override fun apply(agent: A, input: I): StreamEffect = refused(handler)

    @Suppress("unused") // read by the SDK, as it reads a method reference's
    private fun writeReplace(): Any = functionReference(handler, AgentStreamRef::class.java)
}

private fun refused(function: KFunction<*>): Nothing =
    throw UnsupportedOperationException("$function names a handler for the SDK to call; it is not called through this")
