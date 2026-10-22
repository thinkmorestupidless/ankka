package com.thinkmorestupidless.ankka.runtime.remote

import com.thinkmorestupidless.ankka.core.effect.Retention
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode, MethodName}
import com.thinkmorestupidless.ankka.runtime.replication.{
  CopyState,
  HostReplication,
  Mode,
  Platform,
  Replication,
  Where
}
import com.thinkmorestupidless.ankka.runtime.{
  Attribution,
  CallOrigin,
  EntityProtocol,
  JournalRecord,
  MetaEntry,
  Observability,
  Span,
  SpanKind,
  SpanOutcome,
  StateRecord,
  Trace,
  RemoteStateRecord
}
import com.thinkmorestupidless.ankka.sdk.ReplicationStatus
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{Behavior, PostStop}
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.query.scaladsl.CurrentEventsByPersistenceIdQuery
import org.apache.pekko.persistence.typed.scaladsl.{
  Effect as PekkoEffect,
  EffectBuilder,
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
 *
 * A process's replicated entity (feature 051) follows the in-process host's rules, which are
 * `HostReplication`'s: the home handles writes, a follower mirrors the home's records and answers
 * reads, a write anywhere else is forwarded. A mirrored record changes what the process's copy of
 * the state should be, so the conversation is dropped once nothing is in flight and the next
 * command opens it again with the records replayed. A process is told the installation of each
 * event it is replayed and of each command it handles, and whether it is at the home.
 */
private[ankka] object RemoteEventSourcedHost:

  /**
   * The behaviour's state. `snapshotSeq` is where in the journal the process produced `snapshot`,
   * `deletionSeq` where the last deletion marker sits (0 for never): absolute positions, stored in
   * the snapshot record, so Pekko may save its snapshot whenever it likes. `sinceSnapshot` counts
   * domain events since the process snapshot, which is what `snapshot_every` is measured in. A
   * replicated entity's home, replication set, handover and conflict are folded here too.
   */
  final case class RemoteStored(
      snapshot: Option[Payload],
      snapshotSeq: Long,
      sinceSnapshot: Int,
      deleted: Boolean,
      deletionSeq: Long,
      expiryMillis: Long,
      home: Option[String] = None,
      replicationSet: Set[String] = Set.empty,
      pendingHandover: Option[String] = None,
      conflict: Option[String] = None
  ) extends CopyState:
    def expired(nowMillis: Long): Boolean = expiryMillis > 0 && nowMillis >= expiryMillis
    def fresh(nowMillis: Long): Boolean   = deleted || expired(nowMillis)

  enum Journaled:
    /**
     * `installation` is the record's writer as read back; `None` for one being written here.
     * `attribution` and `forwardedFrom` as the in-process host's (spec 057).
     */
    case Domain(
        payload: Payload,
        installation: Option[String] = None,
        attribution: Option[String] = None,
        forwardedFrom: Option[String] = None
    )
    case Deleted
    case Expiry(atEpochMillis: Long)
    case Replication(record: Platform)
    case Mirrored(record: JournalRecord)

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
      conversation: Conversation,
      replication: Option[Replication] = None
  ): Behavior[EntityProtocol.Command] =
    // Only an entity the process declared replicated, on a service that names installations.
    val rep = replication.filter(r => descriptor.replicated && r.replicates(descriptor.componentId))
    val self = rep.map(_.self)
    Behaviors.withTimers { timers =>
      Behaviors.setup { ctx =>
        val observability = Observability(ctx.system)
        val componentRef  = observability.names.intern(descriptor.componentId.toString)
        val persistenceId = PersistenceId(descriptor.componentId, entityId)
        val readJournal = PersistenceQuery(ctx.system)
          .readJournalFor[CurrentEventsByPersistenceIdQuery]("pekko.persistence.r2dbc.query")
        val copy = rep.map(r => HostReplication(r, descriptor.componentId, entityId, ctx))

        // In-memory only: the conversation and the queue. Never persisted, rebuilt on restart.
        var session: Option[InstanceSession]        = None
        var opening                                 = false
        var inFlight: Option[EntityProtocol.Invoke] = None
        var queued: Vector[EntityProtocol.Invoke]   = Vector.empty
        var nextId                                  = 1L
        var lastSequence                            = 0L
        // Set by the command handler, consumed by the event handler on the events it persisted.
        var pendingSnapshot: Option[Payload] = None
        // A replicated copy's commands waiting for locate's answer or a held handover's end, and
        // whether a mirrored record has made the process's copy of the state stale.
        var waiting: Vector[EntityProtocol.Invoke] = Vector.empty
        var stale                                  = false

        val component = descriptor.componentId.toString
        // When the command in flight was sent: there is at most one, so one number is enough.
        var sentNanos = 0L

        /** Answered here, by the host: no handler ran, so the call was not delivered. */
        def unavailable(invoke: EntityProtocol.Invoke, why: String): Unit =
          observability.undelivered(
            MetaEntry.toMetadata(invoke.metadata),
            component,
            descriptor.handler(MethodName(invoke.method)).map(_ => invoke.method)
          )
          invoke.replyTo ! EntityProtocol.Rejected(CommandError(why, ErrorCode.Unavailable))

        /** The span's ending, and the same ending counted as a call from whoever sent it. */
        def finish(span: Span, invoke: EntityProtocol.Invoke, outcome: SpanOutcome): Unit =
          observability.recorder.complete(span, outcome)
          observability.handled(
            MetaEntry.toMetadata(invoke.metadata),
            component,
            invoke.method,
            outcome,
            System.nanoTime() - sentNanos
          )

        def dropSession(): Unit =
          session.foreach(s => Try(s.close()))
          session = None
          opening = false

        /** Drops a conversation a mirrored record made stale, once nothing is in flight on it. */
        def refreshIfStale(): Unit =
          if stale && inFlight.isEmpty && !opening then
            dropSession()
            stale = false

        /** Opens the conversation with a snapshot and a streamed replay of the events after it. */
        def open(state: RemoteStored): Unit =
          opening = true
          val now         = System.currentTimeMillis()
          val fresh       = state.fresh(now)
          val snapshotSeq = state.snapshotSeq
          val deletionSeq = state.deletionSeq
          val init = Init(
            descriptor.kind,
            descriptor.componentId,
            entityId,
            if fresh then None else state.snapshot.map(p => Snapshot(snapshotSeq, p)),
            self
          )
          val s = conversation.open(init)
          session = Some(s)
          val from =
            if fresh then lastSequence + 1
            else math.max(if state.snapshot.isDefined then snapshotSeq else 0L, deletionSeq) + 1
          val replay =
            if from > lastSequence then scala.concurrent.Future.successful(())
            else
              readJournal
                .currentEventsByPersistenceId(persistenceId.id, from, lastSequence)
                .runWith(Sink.foreach { envelope =>
                  envelope.event match
                    case record: JournalRecord if record.kind == JournalRecord.KindDomain =>
                      val payload =
                        Payload(
                          Payload.contentTypeFor(record.manifest),
                          record.manifest,
                          record.payload
                        )
                      // A record with no installation was written here (a follower stamps what it
                      // mirrors), so a replicated entity's process is told this installation.
                      if rep.isEmpty then
                        s.event(
                          envelope.sequenceNr,
                          payload,
                          None,
                          record.attribution,
                          record.forwardedFrom
                        )
                      else
                        s.event(
                          envelope.sequenceNr,
                          payload,
                          record.installation.orElse(self),
                          record.attribution,
                          record.forwardedFrom
                        )
                    case _ => ()
                })(using org.apache.pekko.stream.Materializer.matFromSystem(using ctx.system))
                .map(_ => ())(using ctx.executionContext)
          ctx.pipeToSelf(replay)(Opened(_))

        /** Whether this copy answers as the home: the home, or the holder creating the entity. */
        def atHome(state: RemoteStored): Boolean =
          copy.forall { c =>
            c.mode(state) match
              case Mode.Home     => true
              case Mode.Follower => false
              case Mode.Outside  => false
              case Mode.Unknown =>
                !c.where.exists {
                  case Where.At(l)           => l.home != c.me
                  case Where.Nobody(Some(_)) => true
                  case Where.Nobody(None)    => false
                }
          }

        def send(invoke: EntityProtocol.Invoke, state: RemoteStored): Unit =
          session match
            case None => open(state); queued :+= invoke
            case Some(s) =>
              descriptor.handler(MethodName(invoke.method)) match
                case None =>
                  observability.undelivered(MetaEntry.toMetadata(invoke.metadata), component, None)
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
                  sentNanos = System.nanoTime()
                  val metadata = MetaEntry.toMetadata(invoke.metadata)
                  val inbound  = Trace.inbound(metadata)
                  val span = observability.recorder.begin(
                    traceIdHigh = inbound.traceIdHigh,
                    traceId = inbound.traceId,
                    parentSpanId = inbound.parentSpanId,
                    componentRef = componentRef,
                    handlerRef = observability.names.intern(invoke.method),
                    kind = SpanKind.Internal
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
                    // The span is the parent of anything the process calls back for, and this
                    // handler is who such a call is from: not whoever called this one, whose name
                    // the metadata arrived with.
                    CallOrigin.into(
                      Trace.into(metadata, span.context),
                      CallOrigin(component, invoke.method)
                    ),
                    snapshotRequested,
                    self,
                    atHome(state),
                    Attribution.from(metadata),
                    metadata.get(HostReplication.ForwardedFrom)
                  )
                  ctx.pipeToSelf(s.command(command))(
                    Replied(id, invoke, handler, snapshotRequested, span, _)
                  )

        def drain(state: RemoteStored): Unit =
          refreshIfStale()
          if inFlight.isEmpty && !opening then
            queued.headOption.foreach { next =>
              queued = queued.tail
              send(next, state)
            }

        /** Handles `invoke` here, through the process: as every remote entity did before. */
        def local(invoke: EntityProtocol.Invoke, state: RemoteStored): Unit =
          // An expired instance is fresh at command time, as in-process; the process's copy of
          // the old state must go with it.
          if state.expired(System.currentTimeMillis()) && session.isDefined && inFlight.isEmpty
          then dropSession()
          refreshIfStale()
          if inFlight.isDefined || opening || session.isEmpty then
            queued :+= invoke
            if session.isEmpty && !opening then open(state)
          else send(invoke, state)

        /** The replicated commands that waited, handed back to the actor in their order. */
        def release(): Unit =
          waiting.foreach(ctx.self ! _)
          waiting = Vector.empty

        type Effect = ReplyEffect[Journaled, RemoteStored]

        def reply(invoke: EntityProtocol.Invoke, answer: EntityProtocol.Reply): Effect =
          PekkoEffect.reply(invoke.replyTo)(answer)

        /** A replicated entity's command: handled here, forwarded, held, or refused (051). */
        def replicated(
            c: HostReplication,
            state: RemoteStored,
            invoke: EntityProtocol.Invoke,
            lastSeq: Long
        ): Effect =
          val method = MethodName(invoke.method)
          if method == ReplicationStatus.Method then reply(invoke, c.status(state, lastSeq))
          else if method == ReplicationStatus.HandoverMethod then
            reply(invoke, c.handoverStatus(state, invoke.payload))
          else
            descriptor.handler(method) match
              case None =>
                local(invoke, state)
                PekkoEffect.noReply
              case Some(handler) =>
                val reads = handler.readOnly
                def forward(home: String): Effect =
                  c.forward(invoke, home, observability, componentRef) match
                    case Left(error) => reply(invoke, EntityProtocol.Rejected(error))
                    case Right(())   => PekkoEffect.noReply
                def here(): Effect =
                  local(invoke, state)
                  PekkoEffect.noReply
                val refused = c.admission(invoke)
                if refused.nonEmpty then reply(invoke, EntityProtocol.Rejected(refused.get))
                else if state.conflict.nonEmpty && !reads then
                  reply(invoke, EntityProtocol.Rejected(c.inConflict(state)))
                else
                  c.mode(state) match
                    case Mode.Home =>
                      if !reads && state.pendingHandover.nonEmpty && c.rep.settings.holdWrites then
                        waiting :+= invoke
                        PekkoEffect.noReply
                      else here()
                    case Mode.Follower =>
                      if reads then here() else forward(c.home(state).get)
                    case Mode.Outside =>
                      if reads then
                        reply(
                          invoke,
                          EntityProtocol.Rejected(c.notHome(c.home(state).get, outside = true))
                        )
                      else forward(c.home(state).get)
                    case Mode.Unknown =>
                      c.where match
                        case None =>
                          if !c.locating then c.locate(lastSeq)
                          waiting :+= invoke
                          PekkoEffect.noReply
                        case Some(Where.At(located)) if located.home != c.me =>
                          if reads then
                            if located.replicationSet.contains(c.me) then here()
                            else
                              reply(
                                invoke,
                                EntityProtocol.Rejected(c.notHome(located.home, outside = true))
                              )
                          else forward(located.home)
                        case Some(Where.Nobody(Some(writer))) =>
                          if reads then here()
                          else
                            reply(
                              invoke,
                              EntityProtocol.Rejected(c.notHome(writer, outside = false))
                            )
                        case Some(_) => here()

        /** What the handler's reply persists, with a replicated entity's records around it. */
        def records(
            state: RemoteStored,
            m: RemoteEffect.Materialised,
            handler: RemoteHandler,
            invoke: EntityProtocol.Invoke
        ): Either[CommandError, Vector[Journaled]] =
          // Each record says who acted and where the command was forwarded from (spec 057).
          val received      = MetaEntry.toMetadata(invoke.metadata)
          val attribution   = Attribution.from(received)
          val forwardedFrom = received.get(HostReplication.ForwardedFrom)
          val handlerRecords: Vector[Journaled] =
            m.events.map(Journaled.Domain(_, None, attribution, forwardedFrom)) ++
              retentionRecord(m.retention)
          copy match
            case None =>
              m.replication match
                case None => Right(handlerRecords)
                case Some(change) =>
                  Left(
                    CommandError(
                      s"'${descriptor.componentId}' is not replicated on this service, which names " +
                        s"no other installation; it cannot $change",
                      ErrorCode.BadRequest
                    )
                  )
            case Some(c) =>
              val creating = !handler.readOnly && c.mode(state) == Mode.Unknown
              c.records(state, m.replication, creating).map { (before, after) =>
                // A command that asks for nothing creates nothing.
                if handlerRecords.isEmpty && after.isEmpty && m.replication.isEmpty then
                  Vector.empty
                else
                  before.map(Journaled.Replication(_)) ++ handlerRecords ++
                    after.map(Journaled.Replication(_))
              }

        /** Persists, then hands every command that waited back to the actor, with no reply. */
        def thenRelease(builder: EffectBuilder[Journaled, RemoteStored]): Effect =
          builder.thenRun(_ => release()).thenNoReply()

        def onCommand(
            state: RemoteStored,
            command: EntityProtocol.Command
        ): Effect =
          lastSequence = EventSourcedBehavior.lastSequenceNumber(ctx)
          command match
            case invoke: EntityProtocol.Invoke =>
              copy match
                case Some(c) => replicated(c, state, invoke, lastSequence)
                case None =>
                  local(invoke, state)
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
              val effect: Effect = result match
                case Failure(e) =>
                  finish(span, invoke, SpanOutcome.Failed)
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
                  finish(span, invoke, outcome)
                  if failure.error.code == ErrorCode.Timeout ||
                    failure.error.code == ErrorCode.Unavailable
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
                      finish(span, invoke, SpanOutcome.Failed)
                      dropSession()
                      PekkoEffect.none
                        .thenRun(drain)
                        .thenReply(invoke.replyTo)(_ =>
                          EntityProtocol.Rejected(
                            CommandError(violation.getMessage, ErrorCode.Internal)
                          )
                        )
                    case Right(m) =>
                      (m.reply, records(state, m, handler, invoke)) match
                        case (Left(error), _) =>
                          finish(span, invoke, SpanOutcome.Refused)
                          PekkoEffect.none
                            .thenRun(drain)
                            .thenReply(invoke.replyTo)(_ => EntityProtocol.Rejected(error))
                        case (_, Left(error)) =>
                          // The process applied events the runtime will not persist: its copy of
                          // the state is wrong, so the next command opens it again.
                          finish(span, invoke, SpanOutcome.Refused)
                          dropSession()
                          PekkoEffect.none
                            .thenRun(drain)
                            .thenReply(invoke.replyTo)(_ => EntityProtocol.Rejected(error))
                        case (Right(answer), Right(events)) =>
                          finish(span, invoke, SpanOutcome.Ok)
                          val persist =
                            if events.isEmpty then PekkoEffect.none[Journaled, RemoteStored]
                            else PekkoEffect.persist(events.toList)
                          // The snapshot the process sent is stored on the state; `snapshotWhen`
                          // then asks Pekko to snapshot at exactly this sequence.
                          pendingSnapshot = m.snapshot
                          val before = lastSequence
                          // After the events are applied: a deletion makes the process's copy of
                          // the state stale, so the conversation is dropped and the next command
                          // re-opens it fresh; then the next queued command goes out.
                          val next = persist.thenRun { after =>
                            if m.retention.contains(Retention.DeleteNow) then dropSession()
                            copy.foreach { c =>
                              c.reconcile(after, before + events.size)
                              events
                                .collectFirst {
                                  case Journaled.Replication(Platform.Handover(target)) => target
                                }
                                .foreach { target =>
                                  if c.rep.settings.holdWrites then
                                    timers.startSingleTimer(
                                      HostReplication.HandoverTimer,
                                      EntityProtocol.HandoverTimedOut(target),
                                      c.rep.settings.handoverTimeout
                                    )
                                }
                            }
                            drain(after)
                          }
                          val handledAt =
                            copy.map(c => MetaEntry(HostReplication.HandledAt, c.me)).toVector
                          answer match
                            case Some((payload, metadata)) =>
                              next.thenReply(invoke.replyTo)(_ =>
                                EntityProtocol.Succeeded(
                                  payload.data,
                                  MetaEntry.from(
                                    metadata
                                      .set(PayloadKeys.Manifest, payload.manifest)
                                      .set(PayloadKeys.ContentType, payload.contentType)
                                  ) ++ handledAt
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

            // ── A replicated copy's own commands (feature 051) ──────────────

            case replicate: EntityProtocol.Replicate =>
              copy match
                case None =>
                  replicate.ack ! EntityProtocol.ReplicateAck.Refused(
                    s"'${descriptor.componentId}' is not replicated here"
                  )
                  PekkoEffect.noReply
                case Some(c) =>
                  if replicate.sequenceNr <= lastSequence then
                    replicate.ack ! EntityProtocol.ReplicateAck.Persisted(replicate.sequenceNr)
                    PekkoEffect.noReply
                  else
                    c.mirror(state, replicate, lastSequence) match
                      case Left(reason) =>
                        c.refuseMirror(replicate, reason)
                        PekkoEffect.noReply
                      case Right(record) =>
                        PekkoEffect
                          .persist[Journaled, RemoteStored](Journaled.Mirrored(record))
                          .thenRun { (after: RemoteStored) =>
                            // The process's copy of the state no longer is the journal's.
                            if record.kind <= JournalRecord.KindExpiry then
                              stale = true
                              refreshIfStale()
                            c.reconcile(after, replicate.sequenceNr)
                            c.reportIfTarget(after, replicate.sequenceNr)
                            replicate.ack ! EntityProtocol.ReplicateAck.Persisted(
                              replicate.sequenceNr
                            )
                            release()
                          }
                          .thenNoReply()

            case wake: EntityProtocol.Wake =>
              copy.foreach { c =>
                c.mode(state) match
                  case Mode.Unknown if wake.joined && !c.locating =>
                    c.where = None
                    c.locate(lastSequence)
                  case _ if c.shouldCompare(state, wake.installation) =>
                    c.compare(wake.installation)
                  case _ => c.reconcile(state, lastSequence)
              }
              PekkoEffect.noReply

            case reported: EntityProtocol.Reported =>
              copy match
                case Some(c)
                    if c.mode(state) == Mode.Home &&
                      state.pendingHandover.contains(reported.installation) &&
                      reported.position >= lastSequence =>
                  timers.cancel(HostReplication.HandoverTimer)
                  val before = lastSequence
                  thenRelease(
                    PekkoEffect
                      .persist[Journaled, RemoteStored](
                        Journaled.Replication(Platform.Home(reported.installation))
                      )
                      .thenRun((after: RemoteStored) => c.reconcile(after, before + 1))
                  )
                case _ => PekkoEffect.noReply

            case forwarded: EntityProtocol.Forwarded =>
              copy.fold(PekkoEffect.noReply[Journaled, RemoteStored])(c =>
                PekkoEffect.reply(forwarded.replyTo)(c.forwarded(forwarded))
              )

            case located: EntityProtocol.Located =>
              copy.foreach { c =>
                c.located(located)
                c.reconcile(state, lastSequence)
                release()
              }
              PekkoEffect.noReply

            case checked: EntityProtocol.ConflictChecked =>
              copy match
                case Some(c) if c.conflictFound(checked, state) =>
                  val before = lastSequence
                  PekkoEffect
                    .persist[Journaled, RemoteStored](
                      Journaled.Replication(Platform.Conflict(checked.other))
                    )
                    .thenRun((after: RemoteStored) => c.reconcile(after, before + 1))
                    .thenNoReply()
                case _ => PekkoEffect.noReply

            case timedOut: EntityProtocol.HandoverTimedOut =>
              copy match
                case Some(c)
                    if c.mode(state) == Mode.Home &&
                      state.pendingHandover.contains(timedOut.target) =>
                  c.logAbandoned(timedOut.target)
                  val before = lastSequence
                  thenRelease(
                    PekkoEffect
                      .persist[Journaled, RemoteStored](
                        Journaled.Replication(Platform.HandoverAbandoned(timedOut.target))
                      )
                      .thenRun((after: RemoteStored) => c.reconcile(after, before + 1))
                  )
                case _ => PekkoEffect.noReply

            case _ =>
              PekkoEffect.unhandled.thenNoReply()
        end onCommand

        def onEvent(state: RemoteStored, event: Journaled): RemoteStored =
          // Inside the event handler this is the sequence of the event being applied, on the live
          // path and on recovery alike (measured, not assumed).
          val seq = EventSourcedBehavior.lastSequenceNumber(ctx)
          event match
            case Journaled.Domain(_, _, _, _) =>
              pendingSnapshot match
                case Some(snapshot) =>
                  // The snapshot the process sent describes the state after the reply's events and
                  // lands on the last of them; `snapshotWhen` stores the state there.
                  pendingSnapshot = None
                  state.copy(
                    snapshot = Some(snapshot),
                    snapshotSeq = seq,
                    sinceSnapshot = 0,
                    deleted = false
                  )
                case None =>
                  state.copy(sinceSnapshot = state.sinceSnapshot + 1, deleted = false)
            case Journaled.Deleted =>
              state.copy(
                deleted = true,
                deletionSeq = seq,
                snapshot = None,
                snapshotSeq = 0L,
                sinceSnapshot = 0
              )
            case Journaled.Expiry(at) =>
              state.copy(expiryMillis = at)
            case Journaled.Replication(record) =>
              record match
                case Platform.Home(installation) =>
                  state.copy(home = Some(installation), pendingHandover = None)
                case Platform.ReplicationSet(installations) =>
                  state.copy(replicationSet = installations)
                case Platform.Handover(target) =>
                  state.copy(pendingHandover = Some(target))
                case Platform.HandoverAbandoned(_) =>
                  state.copy(pendingHandover = None)
                case Platform.Conflict(other, written) =>
                  if written.orElse(self) == self then state.copy(conflict = Some(other))
                  else state
            case Journaled.Mirrored(record) =>
              // Folded as the record it is. A snapshot the process made here, when this copy was
              // the home, stays good: its position is absolute, and what was mirrored after it is
              // replayed to the process when its conversation opens again.
              onEvent(state, decode(record))

        val empty = RemoteStored(
          snapshot = None,
          snapshotSeq = 0L,
          sinceSnapshot = 0,
          deleted = false,
          deletionSeq = 0L,
          expiryMillis = 0L
        )

        val base = EventSourcedBehavior
          .withEnforcedReplies[EntityProtocol.Command, Journaled, RemoteStored](
            persistenceId = persistenceId,
            emptyState = empty,
            commandHandler = onCommand,
            eventHandler = onEvent
          )
          .eventAdapter(eventAdapter(self))
          .snapshotAdapter(snapshotAdapter)
          // Pekko evaluates this with the state *before* the event it names (measured), so a
          // process-produced snapshot that landed on event N is first visible here at N+1. Either
          // position is accepted; positions in the record are absolute, so a save at any moment
          // recovers correctly, and the retention below bounds the replay when no event follows.
          .snapshotWhen { (state, _, seq) =>
            state.snapshot.isDefined && (state.snapshotSeq == seq || state.snapshotSeq == seq - 1)
          }
          .receiveSignal {
            case (state, RecoveryCompleted) =>
              lastSequence = EventSourcedBehavior.lastSequenceNumber(ctx)
              copy.foreach(_.recovered(state, lastSequence))
            case (_, PostStop) =>
              (inFlight.toVector ++ queued ++ waiting).foreach(
                unavailable(_, "the instance is stopping")
              )
              inFlight = None
              queued = Vector.empty
              waiting = Vector.empty
              copy.foreach(_.stopped())
              dropSession()
          }

        descriptor.snapshotEvery match
          case Some(n) if n > 0 => base.withRetention(RetentionCriteria.snapshotEvery(n, 2))
          case _                => base
      }
    }

  private def retentionRecord(retention: Option[Retention]): Vector[Journaled] =
    retention match
      case None                      => Vector.empty
      case Some(Retention.DeleteNow) => Vector(Journaled.Deleted)
      case Some(Retention.ExpireAfter(d)) =>
        Vector(Journaled.Expiry(System.currentTimeMillis() + d.toMillis))

  // ── Storage adapters: the in-process host's records, byte for byte ────────

  private def decode(record: JournalRecord): Journaled =
    record.kind match
      case JournalRecord.KindDomain =>
        Journaled.Domain(
          Payload(Payload.contentTypeFor(record.manifest), record.manifest, record.payload),
          record.installation,
          record.attribution,
          record.forwardedFrom
        )
      case JournalRecord.KindDeleted => Journaled.Deleted
      case JournalRecord.KindExpiry  => Journaled.Expiry(record.expiryMillis)
      case other =>
        Platform
          .of(record)
          .map(Journaled.Replication(_))
          .getOrElse(
            throw IllegalStateException(
              s"unknown journal record kind $other (manifest '${record.manifest}'); the journal " +
                "was written by a newer runtime"
            )
          )

  private def eventAdapter(self: Option[String]): EventAdapter[Journaled, JournalRecord] =
    new EventAdapter[Journaled, JournalRecord]:
      def toJournal(event: Journaled): JournalRecord = event match
        case Journaled.Domain(p, _, attribution, forwardedFrom) =>
          JournalRecord
            .domain(p.manifest, p.data)
            .at(self)
            .copy(attribution = attribution, forwardedFrom = forwardedFrom)
        case Journaled.Deleted          => JournalRecord.deleted.at(self)
        case Journaled.Expiry(atMillis) => JournalRecord.expiry(atMillis).at(self)
        case Journaled.Replication(p)   => p.record.at(self)
        case Journaled.Mirrored(record) => record

      def manifest(event: Journaled): String = event match
        case Journaled.Domain(p, _, _, _) => p.manifest
        case Journaled.Deleted            => "ankka.deleted"
        case Journaled.Expiry(_)          => "ankka.expiry"
        case Journaled.Replication(p)     => p.record.manifest
        case Journaled.Mirrored(r) =>
          r.kind match
            case JournalRecord.KindDeleted => "ankka.deleted"
            case JournalRecord.KindExpiry  => "ankka.expiry"
            case _                         => r.manifest

      def fromJournal(record: JournalRecord, manifest: String): EventSeq[Journaled] =
        EventSeq.single(decode(record))

  private val snapshotAdapter: SnapshotAdapter[RemoteStored] =
    new SnapshotAdapter[RemoteStored]:
      def toJournal(state: RemoteStored): Any =
        RemoteStateRecord(
          state.snapshot.map(_.manifest).getOrElse(""),
          state.snapshot.map(_.data).getOrElse(Array.emptyByteArray),
          state.deleted,
          state.expiryMillis,
          state.snapshotSeq,
          state.deletionSeq,
          state.home,
          Option.when(state.replicationSet.nonEmpty)(state.replicationSet.toVector.sorted),
          state.pendingHandover,
          state.conflict
        )

      def fromJournal(from: Any): RemoteStored =
        from match
          case record: RemoteStateRecord =>
            RemoteStored(
              snapshot =
                if record.manifest.isEmpty then None
                else
                  Some(
                    Payload(
                      Payload.contentTypeFor(record.manifest),
                      record.manifest,
                      record.payload
                    )
                  )
              ,
              snapshotSeq = record.snapshotSeq,
              sinceSnapshot = 0,
              deleted = record.deleted,
              deletionSeq = record.deletionSeq,
              expiryMillis = record.expiryMillis,
              home = record.home,
              replicationSet = record.replicationSet.fold(Set.empty[String])(_.toSet),
              pendingHandover = record.pendingHandover,
              conflict = record.conflict
            )
          case record: StateRecord =>
            // Written by the in-process host: a journal being ported. Pekko saved it at some
            // sequence this record does not carry, so the whole journal after it is replayed to
            // the process; correct, merely longer, and only until the next snapshot.
            RemoteStored(
              snapshot = None,
              snapshotSeq = 0L,
              sinceSnapshot = 0,
              deleted = record.deleted,
              deletionSeq = 0L,
              expiryMillis = record.expiryMillis,
              home = record.home,
              replicationSet = record.replicationSet.fold(Set.empty[String])(_.toSet),
              pendingHandover = record.pendingHandover,
              conflict = record.conflict
            )
          case other =>
            throw IllegalStateException(s"unexpected snapshot record ${other.getClass.getName}")
