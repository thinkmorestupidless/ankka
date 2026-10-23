package com.thinkmorestupidless.ankka.javasdk.testkit.bridge

import com.thinkmorestupidless.ankka.agent
import com.thinkmorestupidless.ankka.agent.javasdk.bridge.{AgentBridge, AgentsPlan}
import com.thinkmorestupidless.ankka.agent.javasdk.{Agents, ApprovalRequest}
import com.thinkmorestupidless.ankka.agent.{AgentOutcome, Json, ModelResponse}
import com.thinkmorestupidless.ankka.javasdk.bridge.{Effects, JavaServiceBuilder, Modules}
import com.thinkmorestupidless.ankka.javasdk.client.CallerError
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.testkit.AgentResult
import com.thinkmorestupidless.ankka.sdk.{CommandHandle, NoArgHandle}
import com.thinkmorestupidless.ankka.testkit as stk

import scala.jdk.CollectionConverters.*

/** What the Java agent test kit and its scripted model reach. */
object AgentKits:

  /** The Scala scripted model, in the forms a Java test calls it with. */
  final class Script private[bridge] ():
    private[bridge] val scala = agent.TestModelProvider()

    def provider(): AnyRef = scala

    def expectText(text: String): Unit = scala.expectText(text): Unit
    def expectToolCall(tool: String, argumentsJson: String, callId: String): Unit =
      scala.expectToolCall(tool, json(argumentsJson), callId): Unit
    def expectRefusal(reason: String): Unit = scala.expectRefusal(reason): Unit
    def expectCompleteTask(resultJson: String): Unit =
      scala.expectCompleteTaskJson(resultJson): Unit
    def expectCompleteTaskText(text: String): Unit = scala.expectCompleteTaskText(text): Unit
    def expectFailTask(reason: String): Unit       = scala.expectFailTask(reason): Unit
    def whenUserAsks(substring: String, text: String): Unit =
      scala.whenUserAsks(substring)(ModelResponse(text)): Unit
    def callCount(): Int                   = scala.callCount
    def requests(): java.util.List[String] = scala.requests.map(_.toString).asJava
    def lastRequest(): String              = scala.lastRequest.toString
    def reset(): Unit                      = scala.reset()

    private def json(text: String): Json =
      Json
        .parse(text)
        .fold(e => throw IllegalArgumentException(s"the arguments are not JSON: $e"), identity)

  def script(): Script = Script()

  def agentKit(
      cls: Class[?],
      model: AnyRef,
      sessionId: String,
      stubs: KindKits.Stubs
  ): AgentKit = AgentKit(cls, model, sessionId, stubs)

  /** One Java agent, read as a service with the agents module reads it, driven turn by turn. */
  final class AgentKit private[bridge] (
      cls: Class[?],
      model: AnyRef,
      sessionId: String,
      stubs: KindKits.Stubs
  ):
    private val read = JavaServiceBuilder.read(
      (cls +: stubs.classes).distinct.map(_ -> None),
      Map.empty,
      checkReferences = false,
      modules = Vector(Modules.asJava(Agents.module()))
    )
    // Reading the descriptors is what gives the agents the service's references.
    read.descriptors: Unit

    private val plan = read.refs
      .module(classOf[AgentsPlan])
      .getOrElse(throw IllegalArgumentException(s"${cls.getName} is not an agent"))
    private val companion =
      plan.companionOf(cls).asInstanceOf[agent.Agent.Companion[agent.Agent]]
    private val kit =
      stk.AgentTestKit.of(
        companion,
        model.asInstanceOf[agent.ModelProvider],
        sessionId,
        others = stubs.scalaClient.transportRef
      )

    def call[O](ref: AnyRef, input: Any): AgentResult[O] = handleOf(ref) match
      case h: CommandHandle[agent.Agent, Any, Any] @unchecked => JavaAgentResult(kit.call(h)(input))
      case _ => throw CallerError("that handler takes no input; call it without one")

    def call[O](ref: AnyRef): AgentResult[O] = handleOf(ref) match
      case h: NoArgHandle[agent.Agent, Any] @unchecked => JavaAgentResult(kit.call(h))
      case _ => throw CallerError("that handler takes an input; call it with one")

    private def handleOf(ref: AnyRef): AnyRef =
      val handle = plan.handle(ref)
      val owner = handle match
        case h: CommandHandle[?, ?, ?] => h.componentId
        case h: NoArgHandle[?, ?]      => h.componentId
        case _                         => ""
      if owner != companion.componentId.toString then
        throw CallerError(
          s"that handler is not one of ${cls.getSimpleName}'s, the agent this kit drives"
        )
      handle

  private final case class JavaAgentResult[O](scala: stk.AgentResult[Any]) extends AgentResult[O]:
    def reply(): O         = scala.reply.asInstanceOf[O]
    def isError(): Boolean = scala.isError
    def error(): String    = scala.error.message
    def problem(): Problem = Effects.toProblem(scala.error)
    def isAwaiting(): Boolean =
      scala.outcome.exists(_.isInstanceOf[AgentOutcome.AwaitingApproval[?]])
    def awaiting(): java.util.List[ApprovalRequest] =
      scala.awaiting.map(AgentBridge.approvalRequest).asJava
    def messages(): Int = scala.messages.size
