package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.judgment.{JudgmentProvider, Judgments}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.{
  CallTransport,
  CommandHandle,
  ComponentClient,
  NoArgHandle,
  SecretStore,
  ServiceClients
}

import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * What one turn of an agent came to: its answer, the requests it stopped for, or the rejection the
 * caller would be answered with; and the messages the turn added to the session.
 */
final case class AgentResult[O](
    outcome: Either[CommandError, AgentOutcome[O]],
    messages: Vector[SessionMessage]
):

  /** The answer. Fails the test when the turn was rejected or waits for a decision. */
  def reply: O = outcome match
    case Right(AgentOutcome.Answered(value))            => value
    case Right(AgentOutcome.AwaitingApproval(requests)) =>
      throw AssertionError(
        s"the turn waits for ${requests.map(_.tool).mkString(", ")}, not answered"
      )
    case Left(error) => throw AssertionError(s"the turn was rejected: ${error.message}")

  def isError: Boolean = outcome.isLeft

  /** The rejection. Fails the test when the turn was not rejected. */
  def error: CommandError =
    outcome.left.getOrElse(throw AssertionError(s"the turn was not rejected: $outcome"))

  /** The tool calls waiting for a person; empty when the turn answered or was rejected. */
  def awaiting: Vector[ApprovalRequest] = outcome match
    case Right(AgentOutcome.AwaitingApproval(requests)) => requests
    case _                                              => Vector.empty

/**
 * One agent driven turn by turn against a scripted model, with no actor system, cluster or
 * database: milliseconds a turn.
 *
 * A turn is the runtime's: the handler builds its effect, the agent loop calls the model, runs the
 * tools it asks for and checks the guardrails, and the reply is round-tripped through the handler's
 * own serializer. The session is a real session memory entity in an [[EventSourcedTestKit]], so
 * what a turn records is what the runtime records and the next turn reads it.
 *
 * {{{
 * val model = TestModelProvider().expectToolCall("forecast", args).expectText("Oslo reaches 21")
 * val kit   = AgentTestKit.of(Assistant, model)
 * assertEquals(kit.call(Assistant.ask)("How warm is Oslo?").reply, "Oslo reaches 21")
 * }}}
 *
 * A tool's calls to other components go to `others`, refused with an explanation by default. Not
 * covered: compaction (a consumer), an approval's time limit (a timer), and MCP servers, which the
 * runtime connects at start.
 */
final class AgentTestKit[A <: Agent] private (
    companion: Agent.Companion[A],
    model: ModelProvider,
    val sessionId: SessionId,
    judgments: Option[JudgmentProvider],
    others: CallTransport,
    secrets: SecretStore,
    services: ServiceClients
):

  private val descriptor = companion.descriptor
  private val session    = EventSourcedTestKit.of(SessionMemoryEntity, sessionId.toString)

  private val transport = new CallTransport:
    def askTimeout: FiniteDuration = others.askTimeout
    def ask(
        componentId: ComponentId,
        entityId: EntityId,
        method: MethodName,
        payload: Array[Byte],
        metadata: Metadata
    ): Future[Array[Byte]] =
      if componentId == SessionMemoryEntity.componentId && entityId.toString == sessionId.toString
      then
        session
          .synchronized(session.callRaw(method, payload))
          .fold(Future.failed, Future.successful)
      else others.ask(componentId, entityId, method, payload, metadata)
    def tell(componentId: ComponentId, entityId: EntityId, message: Any): Unit =
      others.tell(componentId, entityId, message)

  private val client = ComponentClient(transport)

  private val context = SimpleAgentContext(
    sessionId,
    descriptor.componentId,
    client,
    Some(model),
    secrets,
    services
  )

  private val loop = new AgentLoop(
    descriptor.asInstanceOf[AgentDescriptor[Agent]],
    sessionId,
    client,
    2.minutes,
    Judgments(judgments, Judgments.DefaultTimeout)
  )

  /** A turn of the handler `handle` names, given `input`. */
  def call[I, O](handle: CommandHandle[A, I, O])(input: I): AgentResult[O] =
    turn(handle.name, handle.inputSerializer.toBytes(input), handle.outputSerializer)

  /** A turn of a handler that takes nothing. */
  def call[O](handle: NoArgHandle[A, O]): AgentResult[O] =
    turn(handle.name, Array.emptyByteArray, handle.outputSerializer)

  /** The session as it is now. */
  def history: SessionHistory = session.currentState

  private def turn[O](name: String, payload: Array[Byte], output: Serializer[O]): AgentResult[O] =
    val binding = descriptor
      .handler(MethodName(name))
      .getOrElse(throw AssertionError(s"no handler '$name' on agent '${descriptor.componentId}'"))
    val before = history.messages.size
    // A fresh instance per turn, as the host builds one per request.
    val agent = descriptor.create(context)
    agent._setContext(Some(context))
    val effect =
      try binding.decodeAndInvoke(agent, payload).asInstanceOf[AgentEffect[Any]]
      finally agent._setContext(None)
    val outcome = loop.run(effect, TurnOrigin(name, streaming = false, payload)) match
      case Right(AgentOutcome.Answered(value)) =>
        Right(AgentOutcome.Answered(output.fromBytes(binding.encodeReply(value))))
      case Right(AgentOutcome.AwaitingApproval(requests)) =>
        Right(AgentOutcome.AwaitingApproval[O](requests))
      case Left(error) => Left(error)
    AgentResult(outcome, history.messages.drop(before))

object AgentTestKit:

  def of[A <: Agent](
      companion: Agent.Companion[A],
      model: ModelProvider,
      sessionId: String = "test-session",
      judgments: Option[JudgmentProvider] = None,
      others: CallTransport = TestTransport(),
      secrets: SecretStore = InMemorySecretStore(),
      services: ServiceClients = ScriptedServices()
  ): AgentTestKit[A] =
    new AgentTestKit(companion, model, SessionId(sessionId), judgments, others, secrets, services)
