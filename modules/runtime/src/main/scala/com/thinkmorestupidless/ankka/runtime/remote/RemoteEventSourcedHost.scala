package com.thinkmorestupidless.ankka.runtime.remote

import com.thinkmorestupidless.ankka.core.effect.Retention
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode, MethodName}
import com.thinkmorestupidless.ankka.runtime.{
  EntityProtocol,
  JournalRecord,
  MetaEntry,
  Observability,
  SpanOutcome,
  StateRecord,
  Trace
}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{Behavior, PostStop}
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.query.scaladsl.CurrentEventsByPersistenceIdQuery
import org.apache.pekko.persistence.typed.scaladsl.{
  Effect as PekkoEffect,
  EventSourcedBehavior,
  ReplyEffect,
  RetentionCriteria
}
import org.apache.pekko.persistence.typed.{
  EventAdapter,
  EventSeq,
  PersistenceId,
  RecoveryCompleted,
  SnapshotAdapter
}
import org.apache.pekko.stream.scaladsl.Sink

import scala.util.{Failure, Success, Try}

/**
 * Hosts one remote event sourced entity kind on cluster sharding.
 *
 * The state is bytes: the latest snapshot the process produced, and how many events have been
 * journaled since. The sidecar never folds — the process does — so this host's job is to keep the
 * journal, open the instance's conversation with a replay, send commands one at a time, and persist
 * what comes back through `RemoteEffect.materialise`.
 *
 * Commands that arrive while one is in flight wait in an explicit queue in the actor's own state
 * (never `Effect.stash()`: a stopped actor drops its stash, and every caller waiting on a stashed
 * command would time out). From `PostStop` every queued and in-flight caller is answered
 * `Unavailable`; see `StashSpike` and research verify item 3.
 *
 * The journal and snapshot records are byte-for-byte the in-process host's (`JournalRecord`,
 * `StateRecord` under the payload's own manifest), which is what makes a journal portable between a
 * Scala service and its port.
 */
private[ankka] object RemoteEventSourcedHost:

  /**
   * The behaviour's state. `sinceSnapshot` counts domain events journaled after the snapshot, which
   * recovery reproduces by counting replayed events. `deletedAt` is the sequence of the last
   * deletion marker, so a replay after deletion starts after it.
   */
  final case class RemoteStored(
      snapshot: Option[Payload],
      snapshotAt: Long,
      sinceSnapshot: Int,
      afterSnapshot: Int,
      deleted: Boolean,
      deletedAt: Long,
      expiryMillis: Long
  ):
    def expired(nowMillis: Long): Boolean = expiryMillis > 0 && nowMillis >= expiryMillis
    def fresh(nowMillis: Long): Boolean   = deleted || expired(nowMillis)

  enum Journaled:
    case Domain(payload: Payload)
    case Deleted
    case Expiry(atEpochMillis: Long)

  // ── Internal commands: replies re-enter the actor as messages ─────────────

  private final case class Opened(result: Try[Unit]) extends EntityProtocol.ModuleCommand

  private final case class Replied(
      id: Long,
      invoke: EntityProtocol.Invoke,
      handler: RemoteHandler,
      snapshotRequested: Boolean,
      span: com.thinkmorestupidless.ankka.runtime.Span,
      result: Try[Either[ProcessFailure, Reply]]
  ) extends EntityProtocol.ModuleCommand

  def behavior(
      descriptor: RemoteEventSourcedDescriptor,
      entityId: EntityId,
      conversation: Conversation
  ): Behavior[EntityProtocol.Command] =
    Behaviors.setup { ctx =>
      val observability = Observability(ctx.system)
      val componentRef  = observability.names.intern(descriptor.componentId.toString)
      val persistenceId = PersistenceId(descriptor.componentId, entityId)
      val readJournal = PersistenceQuery(ctx.system)
        .readJournalFor[CurrentEventsByPersistenceIdQuery]("pekko.persistence.r2dbc.query")

      // In-memory only: the conversation and the queue. Never persisted, rebuilt on restart.
      var session: Option[InstanceSession]        = None
      var opening                                 = false
      var inFlight: Option[EntityProtocol.Invoke] = None
      var queued: Vector[EntityProtocol.Invoke]   = Vector.empty
      var nextId                                  = 1L
      var lastSequence                            = 0L
      // Set by the command handler, consumed by the event handler on the events it persisted.
      var pendingSnapshot: Option[Payload] = None

      def unavailable(invoke: EntityProtocol.Invoke, why: String): Unit =
        invoke.replyTo ! EntityProtocol.Rejected(CommandError(why, ErrorCode.Unavailable))

      def dropSession(): Unit =
        session.foreach(s => Try(s.close()))
        session = None
        opening = false

      /** Opens the conversation with a snapshot and a streamed replay of the events after it. */
      def open(state: RemoteStored): Unit =
        opening = true
        val now   = System.currentTimeMillis()
        val fresh = state.fresh(now)
        // A snapshot recovered from storage does not carry its sequence; every event replayed
        // after it was counted, so it is the last sequence minus that count.
        val snapshotSeq =
          if state.snapshotAt > 0 then state.snapshotAt else lastSequence - state.afterSnapshot
        val init = Init(
          descriptor.kind,
          descriptor.componentId,
          entityId,
          if fresh then None else state.snapshot.map(p => Snapshot(snapshotSeq, p))
        )
        val s = conversation.open(init)
        session = Some(s)
        val from =
          if fresh then lastSequence + 1
          else math.max(snapshotSeq, state.deletedAt) + 1
        val replay =
          if from > lastSequence then scala.concurrent.Future.successful(())
          else
            readJournal
              .currentEventsByPersistenceId(persistenceId.id, from, lastSequence)
              .runWith(Sink.foreach { envelope =>
                envelope.event match
                  case record: JournalRecord if record.kind == JournalRecord.KindDomain =>
                    s.event(
                      envelope.sequenceNr,
                      Payload(
                        Payload.contentTypeFor(record.manifest),
                        record.manifest,
                        record.payload
                      )
                    )
                  case _ => ()
              })(using org.apache.pekko.stream.Materializer.matFromSystem(using ctx.system))
              .map(_ => ())(using ctx.executionContext)
        ctx.pipeToSelf(replay)(Opened(_))

      def send(invoke: EntityProtocol.Invoke, state: RemoteStored): Unit =
        session match
          case None => open(state); queued :+= invoke
          case Some(s) =>
            descriptor.handler(MethodName(invoke.method)) match
              case None =>
                invoke.replyTo ! EntityProtocol.Rejected(
                  CommandError(
                    s"no handler '${invoke.method}' on component '${descriptor.componentId}'",
                    ErrorCode.NotFound
                  )
                )
              case Some(handler) =>
                val id = nextId
                nextId += 1
                inFlight = Some(invoke)
                val metadata = MetaEntry.toMetadata(invoke.metadata)
                val span = observability.recorder.begin(
                  traceId = Trace.traceIdOf(metadata).getOrElse(Trace.mint()),
                  parentSpanId = Trace.parentSpanIdOf(metadata).getOrElse(0L),
                  componentRef = componentRef,
                  handlerRef = observability.names.intern(invoke.method)
                )
                val snapshotRequested =
                  !handler.readOnly && descriptor.snapshotEvery.exists(n =>
                    state.sinceSnapshot + 1 >= n
                  )
                val payload = Payload(
                  metadata.get(PayloadKeys.ContentType).getOrElse(Payload.Json),
                  metadata.get(PayloadKeys.Manifest).getOrElse(""),
                  invoke.payload
                )
                val command = Command(
                  id,
                  handler.name,
                  payload,
                  // The span is the parent of anything the process calls back for.
                  Trace.into(metadata, span.traceId, span.id),
                  snapshotRequested
                )
                ctx.pipeToSelf(s.command(command))(
                  Replied(id, invoke, handler, snapshotRequested, span, _)
                )

      def drain(state: RemoteStored): Unit =
        if inFlight.isEmpty && !opening then
          queued.headOption.foreach { next =>
            queued = queued.tail
            send(next, state)
          }

      def onCommand(
          state: RemoteStored,
          command: EntityProtocol.Command
      ): ReplyEffect[Journaled, RemoteStored] =
        lastSequence = EventSourcedBehavior.lastSequenceNumber(ctx)
        command match
          case invoke: EntityProtocol.Invoke =>
            if inFlight.isDefined || opening || session.isEmpty then
              queued :+= invoke
              if session.isEmpty && !opening then open(state)
              PekkoEffect.none.thenNoReply()
            else
              send(invoke, state)
              PekkoEffect.none.thenNoReply()

          case Opened(Success(())) =>
            opening = false
            drain(state)
            PekkoEffect.none.thenNoReply()

          case Opened(Failure(e)) =>
            ctx.log.warn(
              "{}/{}: replay to the process failed: {}",
              descriptor.componentId,
              entityId,
              e.toString
            )
            dropSession()
            queued.foreach(unavailable(_, s"replay to the process failed: ${e.getMessage}"))
            queued = Vector.empty
            PekkoEffect.none.thenNoReply()

          case Replied(id, invoke, handler, snapshotRequested, span, result) =>
            inFlight = None
            val effect: ReplyEffect[Journaled, RemoteStored] = result match
              case Failure(e) =>
                observability.recorder.complete(span, SpanOutcome.Failed)
                dropSession()
                PekkoEffect.none
                  .thenRun(drain)
                  .thenReply(invoke.replyTo)(_ =>
                    EntityProtocol.Rejected(CommandError(e.getMessage, ErrorCode.Internal))
                  )
              case Success(Left(failure)) =>
                val outcome =
                  if failure.error.code == ErrorCode.Timeout then SpanOutcome.TimedOut
                  else SpanOutcome.Failed
                observability.recorder.complete(span, outcome)
                if failure.error.code == ErrorCode.Timeout || failure.error.code == ErrorCode.Unavailable
                then dropSession()
                PekkoEffect.none
                  .thenRun(drain)
                  .thenReply(invoke.replyTo)(_ => EntityProtocol.Rejected(failure.error))
              case Success(Right(reply)) =>
                RemoteEffect.materialise(reply, handler, id, snapshotRequested) match
                  case Left(violation) =>
                    ctx.log.warn(
                      "{}/{}: protocol violation from the process: {}",
                      descriptor.componentId,
                      entityId,
                      violation.getMessage
                    )
                    observability.recorder.complete(span, SpanOutcome.Failed)
                    dropSession()
                    PekkoEffect.none
                      .thenRun(drain)
                      .thenReply(invoke.replyTo)(_ =>
                        EntityProtocol.Rejected(
                          CommandError(violation.getMessage, ErrorCode.Internal)
                        )
                      )
                  case Right(m) =>
                    m.reply match
                      case Left(error) =>
                        observability.recorder.complete(span, SpanOutcome.Refused)
                        PekkoEffect.none
                          .thenRun(drain)
                          .thenReply(invoke.replyTo)(_ => EntityProtocol.Rejected(error))
                      case Right(answer) =>
                        observability.recorder.complete(span, SpanOutcome.Ok)
                        val events: Vector[Journaled] =
                          m.events.map(Journaled.Domain(_)) ++ retentionRecord(m.retention)
                        val persist =
                          if events.isEmpty then PekkoEffect.none[Journaled, RemoteStored]
                          else PekkoEffect.persist(events.toList)
                        // The snapshot the process sent is stored on the state; `snapshotWhen`
                        // then asks Pekko to snapshot at exactly this sequence.
                        pendingSnapshot = m.snapshot
                        // The next queued command goes out after the events are applied.
                        val next = persist.thenRun(drain)
                        answer match
                          case Some((payload, metadata)) =>
                            next.thenReply(invoke.replyTo)(_ =>
                              EntityProtocol.Succeeded(
                                payload.data,
                                MetaEntry.from(
                                  metadata
                                    .set(PayloadKeys.Manifest, payload.manifest)
                                    .set(PayloadKeys.ContentType, payload.contentType)
                                )
                              )
                            )
                          case None =>
                            next.thenNoReply()
            effect

          case request: EntityProtocol.InvokeStream =>
            request.tokens ! EntityProtocol.StreamFailed(
              CommandError(
                s"entity '${descriptor.componentId}' does not support streaming",
                ErrorCode.BadRequest
              )
            )
            PekkoEffect.noReply

          case _ =>
            PekkoEffect.unhandled.thenNoReply()
      end onCommand

      def onEvent(state: RemoteStored, event: Journaled): RemoteStored =
        val seq = EventSourcedBehavior.lastSequenceNumber(ctx)
        event match
          case Journaled.Domain(_) =>
            pendingSnapshot match
              case Some(snapshot) =>
                pendingSnapshot = None
                state.copy(
                  snapshot = Some(snapshot),
                  snapshotAt = seq,
                  sinceSnapshot = 0,
                  afterSnapshot = 0,
                  deleted = false
                )
              case None =>
                state.copy(
                  sinceSnapshot = state.sinceSnapshot + 1,
                  afterSnapshot = state.afterSnapshot + 1,
                  deleted = false
                )
          case Journaled.Deleted =>
            state.copy(
              deleted = true,
              deletedAt = seq,
              snapshot = None,
              sinceSnapshot = 0,
              afterSnapshot = state.afterSnapshot + 1
            )
          case Journaled.Expiry(at) =>
            state.copy(expiryMillis = at, afterSnapshot = state.afterSnapshot + 1)

      val empty =
        RemoteStored(None, 0L, 0, 0, deleted = false, deletedAt = 0L, expiryMillis = 0L)

      val base = EventSourcedBehavior
        .withEnforcedReplies[EntityProtocol.Command, Journaled, RemoteStored](
          persistenceId = persistenceId,
          emptyState = empty,
          commandHandler = onCommand,
          eventHandler = onEvent
        )
        .eventAdapter(eventAdapter)
        .snapshotAdapter(snapshotAdapter)
        .snapshotWhen((state, _, seq) => state.snapshotAt == seq && state.snapshot.isDefined)
        .receiveSignal {
          case (_, RecoveryCompleted) =>
            lastSequence = EventSourcedBehavior.lastSequenceNumber(ctx)
          case (_, PostStop) =>
            (inFlight.toVector ++ queued).foreach(unavailable(_, "the instance is stopping"))
            inFlight = None
            queued = Vector.empty
            dropSession()
        }

      descriptor.snapshotEvery match
        case Some(n) if n > 0 =>
          base.withRetention(RetentionCriteria.snapshotEvery(Int.MaxValue, 2))
        case _ => base
    }

  private def retentionRecord(retention: Option[Retention]): Vector[Journaled] =
    retention match
      case None                      => Vector.empty
      case Some(Retention.DeleteNow) => Vector(Journaled.Deleted)
      case Some(Retention.ExpireAfter(d)) =>
        Vector(Journaled.Expiry(System.currentTimeMillis() + d.toMillis))

  // ── Storage adapters: the in-process host's records, byte for byte ────────

  private val eventAdapter: EventAdapter[Journaled, JournalRecord] =
    new EventAdapter[Journaled, JournalRecord]:
      def toJournal(event: Journaled): JournalRecord = event match
        case Journaled.Domain(p)        => JournalRecord.domain(p.manifest, p.data)
        case Journaled.Deleted          => JournalRecord.deleted
        case Journaled.Expiry(atMillis) => JournalRecord.expiry(atMillis)

      def manifest(event: Journaled): String = event match
        case Journaled.Domain(p) => p.manifest
        case Journaled.Deleted   => "ankka.deleted"
        case Journaled.Expiry(_) => "ankka.expiry"

      def fromJournal(record: JournalRecord, manifest: String): EventSeq[Journaled] =
        record.kind match
          case JournalRecord.KindDomain =>
            EventSeq.single(
              Journaled.Domain(
                Payload(Payload.contentTypeFor(record.manifest), record.manifest, record.payload)
              )
            )
          case JournalRecord.KindDeleted => EventSeq.single(Journaled.Deleted)
          case JournalRecord.KindExpiry  => EventSeq.single(Journaled.Expiry(record.expiryMillis))
          case other =>
            throw IllegalStateException(
              s"unknown journal record kind $other (manifest '$manifest'); the journal was written by a newer runtime"
            )

  private val snapshotAdapter: SnapshotAdapter[RemoteStored] =
    new SnapshotAdapter[RemoteStored]:
      def toJournal(state: RemoteStored): Any =
        state.snapshot match
          case Some(p) => StateRecord(p.manifest, p.data, state.deleted, state.expiryMillis)
          case None    => StateRecord("", Array.emptyByteArray, state.deleted, state.expiryMillis)

      def fromJournal(from: Any): RemoteStored =
        val record = from.asInstanceOf[StateRecord]
        RemoteStored(
          snapshot =
            if record.manifest.isEmpty then None
            else
              Some(
                Payload(Payload.contentTypeFor(record.manifest), record.manifest, record.payload)
              )
          ,
          // Not known until recovery has replayed; `RecoveryCompleted` and `sinceSnapshot` fix it up.
          snapshotAt = 0L,
          sinceSnapshot = 0,
          afterSnapshot = 0,
          deleted = record.deleted,
          deletedAt = 0L,
          expiryMillis = record.expiryMillis
        )
