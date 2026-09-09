package nakka.runtime

import nakka.core.*
import nakka.core.effect.*
import nakka.sdk.*
import nakka.sdk.ComponentClient
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.typed.state.scaladsl.{
  DurableStateBehavior,
  Effect as PekkoEffect,
  EffectBuilder,
  ReplyEffect
}
import org.apache.pekko.persistence.typed.{PersistenceId, SnapshotAdapter}

/**
 * Hosts one key value entity kind on cluster sharding, backed by Pekko's durable state.
 *
 * Unlike the event sourced host, this one resolves the whole effect up front with
 * `KeyValueEffect.materialise`. With no events to fold there is nothing for Pekko to compute on our
 * behalf, and deciding the reply ourselves keeps the deletion case — where "the state after
 * persisting" is not a meaningful notion — unambiguous.
 */
private[nakka] object KeyValueEntityHost:

  final case class Stored[S](value: S, deleted: Boolean, expiryMillis: Long):
    def expired(nowMillis: Long): Boolean = expiryMillis > 0 && nowMillis >= expiryMillis

  def behavior[C <: KeyValueEntity[S], S](
      descriptor: KeyValueEntityDescriptor[C, S],
      entityId: EntityId,
      componentClient: ComponentClient
  ): Behavior[EntityProtocol.Command] =
    Behaviors.setup { ctx =>
      val entity = descriptor.create(
        SimpleEntityContext(entityId, descriptor.componentId, componentClient)
      )
      val empty = Stored(entity.emptyState, deleted = false, expiryMillis = 0L)

      DurableStateBehavior
        .withEnforcedReplies[EntityProtocol.Command, Stored[S]](
          persistenceId = PersistenceId(descriptor.componentId, entityId),
          emptyState = empty,
          commandHandler = (state, command) =>
            onCommand(
              descriptor,
              entity,
              entityId,
              empty,
              state,
              command,
              DurableStateBehavior.lastSequenceNumber(ctx)
            )
        )
        .snapshotAdapter(snapshotAdapter(descriptor))
    }

  private def onCommand[C <: KeyValueEntity[S], S](
      descriptor: KeyValueEntityDescriptor[C, S],
      entity: C,
      entityId: EntityId,
      empty: Stored[S],
      state: Stored[S],
      command: EntityProtocol.Command,
      sequenceNumber: Long
  ): ReplyEffect[Stored[S]] =
    command match
      case invoke: EntityProtocol.Invoke =>
        descriptor.handler(MethodName(invoke.method)) match
          case None =>
            PekkoEffect.reply(invoke.replyTo)(
              EntityProtocol.Rejected(
                CommandError(
                  s"no handler '${invoke.method}' on component '${descriptor.componentId}'",
                  ErrorCode.NotFound
                )
              )
            )

          case Some(binding) =>
            val visible =
              if state.expired(System.currentTimeMillis()) then empty.value else state.value

            entity._setState(visible)
            entity._setContext(
              Some(
                SimpleCommandContext(
                  entityId,
                  descriptor.componentId,
                  MetaEntry.toMetadata(invoke.metadata),
                  sequenceNumber
                )
              )
            )
            try interpret(binding, entity, invoke, visible)
            finally entity._setContext(None)

      case request: EntityProtocol.InvokeStream =>
        // See the note in EventSourcedEntityHost: reply, do not drop.
        request.tokens ! EntityProtocol.StreamFailed(
          CommandError(
            s"entity '${descriptor.componentId}' does not support streaming",
            ErrorCode.BadRequest
          )
        )
        PekkoEffect.noReply

      case _ =>
        PekkoEffect.unhandled.thenNoReply()

  /** As in the event sourced host, the cast is guarded by what `Companion` accepts. */
  private def interpret[C <: KeyValueEntity[S], S](
      binding: HandlerBinding[C],
      entity: C,
      invoke: EntityProtocol.Invoke,
      visibleState: S
  ): ReplyEffect[Stored[S]] =
    val effect =
      binding.decodeAndInvoke(entity, invoke.payload).asInstanceOf[KeyValueEffect[S, Any]]

    val result = KeyValueEffect.materialise(effect, visibleState)

    val replyMetadata = effect.outcome match
      case Outcome.Reply(_, metadata) => metadata
      case _                          => Metadata.empty

    result.reply match
      case Left(error) =>
        PekkoEffect.reply(invoke.replyTo)(EntityProtocol.Rejected(error))

      case Right(replyValue) =>
        val builder = storageEffect(result)
        replyValue match
          case Some(value) =>
            builder.thenReply(invoke.replyTo) { _ =>
              EntityProtocol.Succeeded(
                binding.encodeReply(value),
                MetaEntry.from(replyMetadata)
              )
            }
          case None => builder.thenNoReply()
    end match
  end interpret

  private def storageEffect[S](
      result: KeyValueEffect.Materialised[S, ?]
  ): EffectBuilder[Stored[S]] =
    result.retention match
      case Some(Retention.DeleteNow) =>
        PekkoEffect.delete[Stored[S]]()

      case Some(Retention.ExpireAfter(duration)) =>
        PekkoEffect.persist(
          Stored(result.newState, deleted = false, System.currentTimeMillis() + duration.toMillis)
        )

      case None =>
        if result.changed then
          PekkoEffect.persist(Stored(result.newState, deleted = false, expiryMillis = 0L))
        else PekkoEffect.none[Stored[S]]

  private def snapshotAdapter[C <: KeyValueEntity[S], S](
      descriptor: KeyValueEntityDescriptor[C, S]
  ): SnapshotAdapter[Stored[S]] =
    new SnapshotAdapter[Stored[S]]:

      def toJournal(state: Stored[S]): Any =
        StateRecord(
          descriptor.stateSerializer.manifest,
          descriptor.stateSerializer.toBytes(state.value),
          state.deleted,
          state.expiryMillis
        )

      def fromJournal(from: Any): Stored[S] =
        val record = from.asInstanceOf[StateRecord]
        Stored(
          descriptor.stateSerializer.fromBytes(record.payload),
          record.deleted,
          record.expiryMillis
        )
