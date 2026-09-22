package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.effect.*
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient

import scala.collection.mutable
import scala.reflect.ClassTag

/**
 * The outcome of one command against a test entity.
 *
 * Carries both what the caller would have seen and what would have reached the journal, because for
 * an event sourced entity those are two separate things worth asserting on.
 */
final case class CommandResult[S, E, O](
    reply: Either[CommandError, Option[O]],
    events: Vector[E],
    state: S,
    retention: Option[Retention]
):

  /** The reply value, or a test failure if the handler rejected or replied with nothing. */
  def replyValue: O = reply match
    case Right(Some(value)) => value
    case Right(None)        => throw AssertionError("handler replied with no value")
    case Left(error) => throw AssertionError(s"handler rejected the command: ${error.message}")

  def isError: Boolean = reply.isLeft

  /** The rejection, or a test failure if the command actually succeeded. */
  def error: CommandError = reply.left.getOrElse(
    throw AssertionError(s"expected a rejection but the command succeeded with $reply")
  )

  def errorMessage: String = error.message

  def persisted: Boolean = events.nonEmpty

  /** The single event of type `T` this command emitted. */
  def eventOfType[T <: E](using tag: ClassTag[T]): T =
    events.collect { case tag(e) => e } match
      case Vector(single) => single
      case Vector() =>
        throw AssertionError(
          s"no ${tag.runtimeClass.getSimpleName} among ${events.map(_.getClass.getSimpleName)}"
        )
      case many =>
        throw AssertionError(s"expected one ${tag.runtimeClass.getSimpleName}, got $many")

/**
 * Drives a single entity in memory, with no actor system, no cluster and no database.
 *
 * Every call round-trips its input and reply through the entity's own serializers. That is
 * deliberate: a missing or broken codec is otherwise invisible until the first real deployment, and
 * catching it in a millisecond-scale unit test is much cheaper.
 */
final class EventSourcedTestKit[C <: EventSourcedEntity[S, E], S, E] private (
    companion: EventSourcedEntity.Companion[C, S, E],
    val entityId: EntityId,
    componentClient: ComponentClient
):

  private val entity = companion.create(
    SimpleEntityContext(entityId, companion.componentId, componentClient)
  )
  private val journal = mutable.ListBuffer.empty[E]

  private var state: S       = entity.emptyState
  private var deleted        = false
  private var retention      = Option.empty[Retention]
  private var sequenceNumber = 0L

  /** State as the entity currently sees it. */
  def currentState: S = state

  /** Every event persisted since this test kit was created. */
  def allEvents: Vector[E] = journal.toVector

  def isDeleted: Boolean = deleted

  /** The retention recorded by the most recent command, if any. */
  def lastRetention: Option[Retention] = retention

  /** Invokes a one-argument handler. */
  def call[I, O](handle: CommandHandle[C, I, O])(input: I): CommandResult[S, E, O] =
    run(handle, handle.inputSerializer.toBytes(input), handle.outputSerializer, Metadata.empty)

  /** Invokes a no-argument handler. */
  def call[O](handle: NoArgHandle[C, O]): CommandResult[S, E, O] =
    run(handle, Array.emptyByteArray, handle.outputSerializer, Metadata.empty)

  /**
   * Invokes a handler with command metadata, as a caller using `withMetadata` would.
   *
   * What a handler reads from `commandContext.metadata` — who is asking, a trace — arrives this way
   * in production, and a test of a handler that records it needs to supply it.
   */
  def call[I, O](handle: CommandHandle[C, I, O], metadata: Metadata)(
      input: I
  ): CommandResult[S, E, O] =
    run(handle, handle.inputSerializer.toBytes(input), handle.outputSerializer, metadata)

  def call[O](handle: NoArgHandle[C, O], metadata: Metadata): CommandResult[S, E, O] =
    run(handle, Array.emptyByteArray, handle.outputSerializer, metadata)

  private def run[O](
      binding: HandlerBinding[C],
      payload: Array[Byte],
      output: Serializer[O],
      metadata: Metadata
  ): CommandResult[S, E, O] =
    entity._setState(state)
    entity._setContext(
      Some(SimpleCommandContext(entityId, companion.componentId, metadata, sequenceNumber))
    )

    val effect =
      try binding.decodeAndInvoke(entity, payload).asInstanceOf[EventSourcedEffect[S, E, Any]]
      finally entity._setContext(None)

    val outcome = EventSourcedEffect.materialise[S, E, Any](
      effect,
      state,
      (current, event) => entity._applyEvent(current, event)
    )

    journal ++= outcome.events
    sequenceNumber += outcome.events.size
    retention = outcome.retention

    outcome.retention match
      case Some(Retention.DeleteNow) =>
        state = entity.emptyState
        deleted = true
      case _ =>
        state = outcome.newState

    // Round-trip the reply so an unserialisable response fails here, not in production.
    val reply = outcome.reply.map(_.map(value => output.fromBytes(binding.encodeReply(value))))

    CommandResult(reply, outcome.events, state, outcome.retention)

object EventSourcedTestKit:

  /**
   * Drives `companion` in isolation.
   *
   * `componentClient` defaults to one that fails on any call, so an entity that reaches for a
   * collaborator the test forgot to stub says so instead of quietly succeeding. Pass
   * `TestTransport().stub(…).client` to stub those calls.
   */
  def of[C <: EventSourcedEntity[S, E], S, E](
      companion: EventSourcedEntity.Companion[C, S, E],
      entityId: String = "test-entity",
      componentClient: ComponentClient = TestTransport.unroutedClient
  ): EventSourcedTestKit[C, S, E] =
    new EventSourcedTestKit(companion, EntityId(entityId), componentClient)
