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
    retention: Option[Retention],
    /** The handover or change to the replication set the handler asked for, if any. */
    replication: Option[ReplicationChange] = None
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
      case Vector()       =>
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
    componentClient: ComponentClient,
    /** As a replicated entity's copy stands here: its home, a follower, or not replicated. */
    val mode: EventSourcedTestKit.Mode,
    /** The installation the copy is at, when it is replicated. */
    installation: String
):
  import EventSourcedTestKit.Mode

  private val here: Option[String] = mode match
    case Mode.Unreplicated => None
    case _                 => Some(installation)

  /** Where an event applied by a command here was written: here, as `eventContext` tells it. */
  private def writtenHere(sequence: Long) = SimpleEventContext(here, isLocal = true, sequence)

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

  /**
   * Applies `events` as history the copy already holds, written at `writtenAt`: for a follower, the
   * home's events it has mirrored, which its queries answer from. Nothing is recorded in
   * `allEvents`, which holds what commands here persisted.
   */
  def replay(events: E*)(using writtenAt: EventSourcedTestKit.WrittenAt): Unit =
    events.foreach { event =>
      sequenceNumber += 1
      val context = SimpleEventContext(
        writtenAt.installation.orElse(here),
        isLocal = writtenAt.installation.forall(here.contains),
        sequenceNumber
      )
      state = entity._applyEvent(state, event, context)
    }

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

  /**
   * Runs a handler named on the wire, from and to bytes — what a test transport routing real calls
   * to this kit needs. A name the entity does not declare is `NotFound`, as the runtime answers.
   */
  private[ankka] def callRaw(
      name: MethodName,
      payload: Array[Byte]
  ): Either[CommandError, Array[Byte]] =
    companion.descriptor.handler(name) match
      case None =>
        Left(CommandError(s"no handler '$name' on '${companion.componentId}'", ErrorCode.NotFound))
      case Some(binding) =>
        // The identity serializer makes `run`'s reply round-trip yield the encoded reply itself.
        run(binding, payload, Serializer.bytes, Metadata.empty).reply
          .map(_.getOrElse(Array.emptyByteArray))

  private def run[O](
      binding: HandlerBinding[C],
      payload: Array[Byte],
      output: Serializer[O],
      metadata: Metadata
  ): CommandResult[S, E, O] =
    mode match
      case Mode.Follower(home) if !binding.readOnly =>
        // A follower never runs a handler that may persist: the runtime forwards the command to
        // the home. Here there is no home to forward to, so the command is refused as the runtime
        // refuses it when the home cannot be reached.
        return CommandResult(
          Left(
            CommandError(
              s"the home of '$entityId' is '$home'; a follower handles no command that may persist",
              ErrorCode.NotHome
            )
          ),
          Vector.empty,
          state,
          None
        )
      case _ => ()

    entity._setState(state)
    entity._setContext(
      Some(
        SimpleCommandContext(
          entityId,
          companion.componentId,
          metadata,
          sequenceNumber,
          here,
          isHome = mode match
            case Mode.Follower(_) => false
            case _                => true
        )
      )
    )

    val effect =
      try binding.decodeAndInvoke(entity, payload).asInstanceOf[EventSourcedEffect[S, E, Any]]
      finally entity._setContext(None)

    var applied = sequenceNumber
    val outcome = EventSourcedEffect.materialise[S, E, Any](
      effect,
      state,
      (current, event) =>
        applied += 1
        entity._applyEvent(current, event, writtenHere(applied))
    )

    if outcome.replication.nonEmpty && mode == Mode.Unreplicated then
      return CommandResult(
        Left(
          CommandError(
            s"'${companion.componentId}' is not replicated in this test kit; drive it as " +
              "Mode.Home to see the handover or the change to the replication set it asks for",
            ErrorCode.BadRequest
          )
        ),
        Vector.empty,
        state,
        None
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

    CommandResult(reply, outcome.events, state, outcome.retention, outcome.replication)

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
      componentClient: ComponentClient = TestTransport.unroutedClient,
      /**
       * How a replicated entity's copy stands (feature 051): `Mode.Home`, where every command runs
       * and a handover or a change to the replication set is reported on the result; or
       * `Mode.Follower(home)`, where a command that may persist is refused `NotHome` and a query is
       * answered from what `replay` gave it. Not replicated by default.
       */
      mode: Mode = Mode.Unreplicated,
      /** The installation the copy is at, which its contexts name. */
      installation: String = "test"
  ): EventSourcedTestKit[C, S, E] =
    new EventSourcedTestKit(companion, EntityId(entityId), componentClient, mode, installation)

  /** How a replicated entity's copy stands in the test kit. */
  enum Mode:
    /** Not replicated: as every entity was driven before replication. */
    case Unreplicated

    /** The entity's home: every command runs. */
    case Home

    /** A follower of `home`: a command that may persist is refused, a query is answered. */
    case Follower(home: String)

  /** Where events given to `replay` were written; the copy's own installation by default. */
  final case class WrittenAt(installation: Option[String])

  object WrittenAt:
    given here: WrittenAt = WrittenAt(None)
