package nakka.testkit

import nakka.core.*
import nakka.core.effect.*
import nakka.sdk.*
import nakka.sdk.ComponentClient

/** Drives a single key value entity in memory. See `EventSourcedTestKit`. */
final class KeyValueEntityTestKit[C <: KeyValueEntity[S], S] private (
    companion: KeyValueEntity.Companion[C, S],
    val entityId: EntityId,
    componentClient: ComponentClient
):

  private val entity = companion.create(
    SimpleEntityContext(entityId, companion.componentId, componentClient)
  )

  private var state: S  = entity.emptyState
  private var deleted   = false
  private var revision  = 0L
  private var retention = Option.empty[Retention]

  def currentState: S                  = state
  def isDeleted: Boolean               = deleted
  def lastRetention: Option[Retention] = retention

  def call[I, O](handle: CommandHandle[C, I, O])(input: I): StateResult[S, O] =
    run(handle, handle.inputSerializer.toBytes(input), handle.outputSerializer)

  def call[O](handle: NoArgHandle[C, O]): StateResult[S, O] =
    run(handle, Array.emptyByteArray, handle.outputSerializer)

  private def run[O](
      binding: HandlerBinding[C],
      payload: Array[Byte],
      output: Serializer[O]
  ): StateResult[S, O] =
    entity._setState(state)
    entity._setContext(
      Some(SimpleCommandContext(entityId, companion.componentId, Metadata.empty, revision))
    )

    val effect =
      try binding.decodeAndInvoke(entity, payload).asInstanceOf[KeyValueEffect[S, Any]]
      finally entity._setContext(None)

    val outcome = KeyValueEffect.materialise[S, Any](effect, state)
    retention = outcome.retention

    outcome.retention match
      case Some(Retention.DeleteNow) =>
        state = entity.emptyState
        deleted = true
        revision += 1
      case _ =>
        state = outcome.newState
        if outcome.changed then revision += 1

    val reply = outcome.reply.map(_.map(value => output.fromBytes(binding.encodeReply(value))))
    StateResult(reply, state, outcome.changed, outcome.retention)

/** The outcome of one command against a key value test entity. */
final case class StateResult[S, O](
    reply: Either[CommandError, Option[O]],
    state: S,
    changed: Boolean,
    retention: Option[Retention]
):
  def replyValue: O = reply match
    case Right(Some(value)) => value
    case Right(None)        => throw AssertionError("handler replied with no value")
    case Left(error) => throw AssertionError(s"handler rejected the command: ${error.message}")

  def isError: Boolean = reply.isLeft

  def error: CommandError = reply.left.getOrElse(
    throw AssertionError(s"expected a rejection but the command succeeded with $reply")
  )

  def errorMessage: String = error.message

object KeyValueEntityTestKit:

  /** See `EventSourcedTestKit.of` for the `componentClient` default. */
  def of[C <: KeyValueEntity[S], S](
      companion: KeyValueEntity.Companion[C, S],
      entityId: String = "test-entity",
      componentClient: ComponentClient = TestTransport.unroutedClient
  ): KeyValueEntityTestKit[C, S] =
    new KeyValueEntityTestKit(companion, EntityId(entityId), componentClient)
