package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.effect.*
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
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
private[ankka] object KeyValueEntityHost:

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
      // Resolved inside setup, where touching the context is safe, and once per entity rather
      // than once per invocation.
      val observability = Observability(ctx.system)
      val componentRef  = observability.names.intern(descriptor.componentId.toString)

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
              DurableStateBehavior.lastSequenceNumber(ctx),
              observability,
              componentRef
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
      sequenceNumber: Long,
      observability: Observability,
      componentRef: Int
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
            // Same span as the event sourced host records: the handler and the effect it
            // returned, parented by whatever the caller's metadata carried.
            val metadata = MetaEntry.toMetadata(invoke.metadata)
            val span = observability.recorder.begin(
              traceId = Trace.traceIdOf(metadata).getOrElse(Trace.mint()),
              parentSpanId = Trace.parentSpanIdOf(metadata).getOrElse(0L),
              componentRef = componentRef,
              handlerRef = observability.names.intern(invoke.method)
            )
            // Failed until proven otherwise: if the handler throws, that is what is recorded.
            var spanOutcome = SpanOutcome.Failed
            try
              val (effect, handlerOutcome) = interpret(binding, entity, invoke, visible)
              spanOutcome = handlerOutcome
              effect
            finally
              observability.recorder.complete(span, spanOutcome)
              entity._setContext(None)

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
  ): (ReplyEffect[Stored[S]], SpanOutcome) =
    val effect =
      binding.decodeAndInvoke(entity, invoke.payload).asInstanceOf[KeyValueEffect[S, Any]]

    val result = KeyValueEffect.materialise(effect, visibleState)

    val replyMetadata = effect.outcome match
      case Outcome.Reply(_, metadata) => metadata
      case _                          => Metadata.empty

    // Returned, not inferred: `effects.error(...)` is a value rather than an exception, so a
    // refusal is indistinguishable from success to the caller. See the note in the event sourced
    // host for why a console must not show the two the same way.
    result.reply match
      case Left(error) =>
        (PekkoEffect.reply(invoke.replyTo)(EntityProtocol.Rejected(error)), SpanOutcome.Refused)

      case Right(replyValue) =>
        val builder = storageEffect(result)
        val effectOut = replyValue match
          case Some(value) =>
            builder.thenReply(invoke.replyTo) { _ =>
              EntityProtocol.Succeeded(
                binding.encodeReply(value),
                MetaEntry.from(replyMetadata)
              )
            }
          case None => builder.thenNoReply()
        (effectOut, SpanOutcome.Ok)
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
