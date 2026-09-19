package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.effect.*
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.typed.scaladsl.{
  Effect as PekkoEffect,
  EventSourcedBehavior,
  ReplyEffect,
  RetentionCriteria
}
import org.apache.pekko.persistence.typed.{EventAdapter, EventSeq, PersistenceId, SnapshotAdapter}

/**
 * Hosts one event sourced entity kind on cluster sharding, translating ankka's Effects into
 * Pekko's.
 *
 * The translation is deliberately thin. `Outcome.Reply` holds a `S => R` and Pekko's `thenReply`
 * hands back the state *after* the events have been applied, so the two line up exactly and ankka
 * never has to fold events itself on the write path.
 */
private[ankka] object EventSourcedEntityHost:

  /**
   * The behaviour's state: the developer's state plus the lifecycle facts the runtime needs.
   * Wrapping keeps deletion and TTL out of the domain model entirely.
   */
  final case class Stored[S](value: S, deleted: Boolean, expiryMillis: Long):
    def expired(nowMillis: Long): Boolean = expiryMillis > 0 && nowMillis >= expiryMillis

  /** In-memory event type: domain events plus the runtime's own lifecycle markers. */
  enum Journaled[+E]:
    case Domain(event: E)
    case Deleted
    case Expiry(atEpochMillis: Long)

  def behavior[C <: EventSourcedEntity[S, E], S, E](
      descriptor: EventSourcedEntityDescriptor[C, S, E],
      entityId: EntityId,
      componentClient: ComponentClient
  ): Behavior[EntityProtocol.Command] =
    Behaviors.setup { ctx =>
      val entity = descriptor.create(
        SimpleEntityContext(entityId, descriptor.componentId, componentClient)
      )
      // Resolved here, inside setup, where touching the context is safe. Never from a Future
      // callback, and never per invocation: the component's name is interned once per entity.
      val observability = Observability(ctx.system)
      val componentRef  = observability.names.intern(descriptor.componentId.toString)
      val empty         = Stored(entity.emptyState, deleted = false, expiryMillis = 0L)

      val base = EventSourcedBehavior
        .withEnforcedReplies[
          EntityProtocol.Command,
          Journaled[E],
          Stored[S]
        ](
          persistenceId = PersistenceId(descriptor.componentId, entityId),
          emptyState = empty,
          commandHandler = (state, command) =>
            onCommand(
              descriptor,
              entity,
              entityId,
              state,
              command,
              EventSourcedBehavior.lastSequenceNumber(ctx),
              observability,
              componentRef
            ),
          eventHandler = (state, event) => onEvent(entity, state, event)
        )
        .eventAdapter(eventAdapter(descriptor))
        .snapshotAdapter(snapshotAdapter(descriptor))

      descriptor.snapshotEvery match
        case Some(n) if n > 0 => base.withRetention(RetentionCriteria.snapshotEvery(n, 2))
        case _                => base
    }

  // ── Command handling ──────────────────────────────────────────────────────

  private def onCommand[C <: EventSourcedEntity[S, E], S, E](
      descriptor: EventSourcedEntityDescriptor[C, S, E],
      entity: C,
      entityId: EntityId,
      state: Stored[S],
      command: EntityProtocol.Command,
      sequenceNumber: Long,
      observability: Observability,
      componentRef: Int
  ): ReplyEffect[Journaled[E], Stored[S]] =
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
            // A deleted or expired entity behaves as a fresh one: its state is gone,
            // but its id stays usable. Expiry is observed lazily here rather than by a
            // sweeper — reclaiming storage is a separate concern from visible semantics.
            val visible =
              if state.deleted || state.expired(System.currentTimeMillis()) then entity.emptyState
              else state.value

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
            // The span covers the handler and the effect it returns — the work this component
            // did for this request. The parent comes from the caller's metadata, which is how a
            // trace survives a sharding hop without any protocol type changing.
            val metadata = MetaEntry.toMetadata(invoke.metadata)
            val span = observability.recorder.begin(
              traceId = Trace.traceIdOf(metadata).getOrElse(Trace.mint()),
              parentSpanId = Trace.parentSpanIdOf(metadata).getOrElse(0L),
              componentRef = componentRef,
              handlerRef = observability.names.intern(invoke.method)
            )
            // Failed until proven otherwise: if the handler throws, that is what is recorded.
            var outcome = SpanOutcome.Failed
            try
              // Published as current for the duration of the handler, so a nested
              // ComponentClient call is recorded as this span's child rather than a root.
              val (effect, handlerOutcome) =
                Trace.within(span.traceId, span.id)(interpret(binding, entity, invoke))
              outcome = handlerOutcome
              effect
            finally
              observability.recorder.complete(span, outcome)
              entity._setContext(None)

      case request: EntityProtocol.InvokeStream =>
        // Entities have no streaming surface. Reply rather than drop it, so a caller
        // waiting on the token stream fails fast instead of hanging.
        request.tokens ! EntityProtocol.StreamFailed(
          CommandError(
            s"entity '${descriptor.componentId}' does not support streaming",
            ErrorCode.BadRequest
          )
        )
        PekkoEffect.noReply

      case _ =>
        // Entities and workflows share one command protocol so the transport needs only
        // one sharding key type. The workflow engine's internal commands are self-sent,
        // so an entity can never actually receive one.
        PekkoEffect.unhandled.thenNoReply()

  /**
   * The single cast in the runtime's write path.
   *
   * It is safe by construction: `EventSourcedEntity.Companion.command` and `.query` only accept
   * functions returning `EventSourcedEffect[S, E, ?]`, so nothing else can ever reach a binding
   * registered on this descriptor.
   */
  private def interpret[C <: EventSourcedEntity[S, E], S, E](
      binding: HandlerBinding[C],
      entity: C,
      invoke: EntityProtocol.Invoke
  ): (ReplyEffect[Journaled[E], Stored[S]], SpanOutcome) =
    val effect =
      binding
        .decodeAndInvoke(entity, invoke.payload)
        .asInstanceOf[EventSourcedEffect[S, E, Any]]

    val journaled: Vector[Journaled[E]] =
      effect.events.map(Journaled.Domain(_)) ++ retentionRecord(effect.retention)

    // The span outcome is returned rather than inferred by the caller, because the caller cannot
    // see it: `effects.error(...)` produces a *value*, not an exception, so a refusal reaches the
    // caller looking exactly like a success. A console that painted a working ACL red would teach
    // its reader that red means nothing, which is how a real failure gets ignored.
    effect.outcome match
      case Outcome.Fail(error) =>
        (PekkoEffect.reply(invoke.replyTo)(EntityProtocol.Rejected(error)), SpanOutcome.Refused)

      case Outcome.NoReply =>
        (persist(journaled).thenNoReply(), SpanOutcome.Ok)

      case Outcome.Reply(compute, metadata) =>
        val reply: ReplyEffect[Journaled[E], Stored[S]] =
          persist(journaled).thenReply(invoke.replyTo) { stored =>
            EntityProtocol.Succeeded(
              binding.encodeReply(compute(stored.value)),
              MetaEntry.from(metadata)
            )
          }
        (reply, SpanOutcome.Ok)
    end match
  end interpret

  private def persist[S, E](
      events: Vector[Journaled[E]]
  ): org.apache.pekko.persistence.typed.scaladsl.EffectBuilder[Journaled[E], Stored[S]] =
    if events.isEmpty then PekkoEffect.none else PekkoEffect.persist(events.toList)

  private def retentionRecord[E](retention: Option[Retention]): Vector[Journaled[E]] =
    retention match
      case None                      => Vector.empty
      case Some(Retention.DeleteNow) => Vector(Journaled.Deleted)
      case Some(Retention.ExpireAfter(d)) =>
        Vector(Journaled.Expiry(System.currentTimeMillis() + d.toMillis))

  // ── Event handling ────────────────────────────────────────────────────────

  private def onEvent[C <: EventSourcedEntity[S, E], S, E](
      entity: C,
      state: Stored[S],
      event: Journaled[E]
  ): Stored[S] =
    event match
      case Journaled.Domain(domainEvent) =>
        // `_applyEvent` sets `currentState` before delegating, so a handler written as
        // `currentState.addItem(...)` — the idiom the docs use — works on replay too.
        state.copy(value = entity._applyEvent(state.value, domainEvent), deleted = false)

      case Journaled.Deleted =>
        // The value is kept rather than blanked. Pekko folds every event before
        // `thenReply` runs, so blanking here would make
        // `persist(CheckedOut).deleteEntity().thenReplyState` reply with an empty cart —
        // losing exactly the state the caller asked to be told about. Handlers are shown
        // `emptyState` for a deleted entity at command time instead (see `onCommand`).
        state.copy(deleted = true)

      case Journaled.Expiry(atEpochMillis) =>
        state.copy(expiryMillis = atEpochMillis)

  // ── Storage adapters ──────────────────────────────────────────────────────

  /**
   * Encodes events with the entity's own serializer before they reach Pekko, so the journal holds
   * ankka's JSON under ankka's manifest rather than a Java-serialised or Jackson-reflected form of
   * the domain type.
   */
  private def eventAdapter[C <: EventSourcedEntity[S, E], S, E](
      descriptor: EventSourcedEntityDescriptor[C, S, E]
  ): EventAdapter[Journaled[E], JournalRecord] =
    new EventAdapter[Journaled[E], JournalRecord]:

      def toJournal(event: Journaled[E]): JournalRecord = event match
        case Journaled.Domain(e) =>
          JournalRecord.domain(
            descriptor.eventSerializer.manifest,
            descriptor.eventSerializer.toBytes(e)
          )
        case Journaled.Deleted          => JournalRecord.deleted
        case Journaled.Expiry(atMillis) => JournalRecord.expiry(atMillis)

      def manifest(event: Journaled[E]): String = event match
        case Journaled.Domain(_) => descriptor.eventSerializer.manifest
        case Journaled.Deleted   => "ankka.deleted"
        case Journaled.Expiry(_) => "ankka.expiry"

      def fromJournal(record: JournalRecord, manifest: String): EventSeq[Journaled[E]] =
        record.kind match
          case JournalRecord.KindDomain =>
            EventSeq.single(Journaled.Domain(descriptor.eventSerializer.fromBytes(record.payload)))
          case JournalRecord.KindDeleted =>
            EventSeq.single(Journaled.Deleted)
          case JournalRecord.KindExpiry =>
            EventSeq.single(Journaled.Expiry(record.expiryMillis))
          case other =>
            throw IllegalStateException(
              s"unknown journal record kind $other for '${descriptor.componentId}' " +
                s"(manifest '$manifest'); the journal was written by a newer runtime"
            )

  private def snapshotAdapter[C <: EventSourcedEntity[S, E], S, E](
      descriptor: EventSourcedEntityDescriptor[C, S, E]
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
