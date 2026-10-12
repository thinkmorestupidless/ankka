package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.effect.*
import com.thinkmorestupidless.ankka.runtime.replication.{
  ForwardedCall,
  ForwardedReply,
  Mode,
  Replication
}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop}
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
import org.apache.pekko.stream.scaladsl.{Keep, RestartSource, Sink}
import org.apache.pekko.stream.{KillSwitches, Materializer, RestartSettings, UniqueKillSwitch}
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Hosts one event sourced entity kind on cluster sharding, translating ankka's Effects into
 * Pekko's.
 *
 * The translation is deliberately thin. `Outcome.Reply` holds a `S => R` and Pekko's `thenReply`
 * hands back the state *after* the events have been applied, so the two line up exactly and ankka
 * never has to fold events itself on the write path.
 *
 * A replicated entity (feature 051) is the same behaviour under the same persistence id at every
 * installation that holds it. Its home handles its writes; every other copy in its replication set
 * mirrors the home's journal record for record and answers reads; a write that reaches a copy that
 * is not the home is forwarded there. Where a copy stands is folded from records in its own journal
 * and derived on every command (`Mode`), never kept beside it. A service that replicates nothing,
 * and an entity that is not replicated, take none of these paths.
 */
private[ankka] object EventSourcedEntityHost:

  /**
   * The behaviour's state: the developer's state plus the lifecycle facts the runtime needs.
   * Wrapping keeps deletion and TTL out of the domain model entirely. A replicated entity's home,
   * replication set, pending handover and conflict are folded here from its journal.
   */
  final case class Stored[S](
      value: S,
      deleted: Boolean,
      expiryMillis: Long,
      home: Option[String] = None,
      replicationSet: Set[String] = Set.empty,
      pendingHandover: Option[String] = None,
      conflict: Option[String] = None
  ):
    def expired(nowMillis: Long): Boolean = expiryMillis > 0 && nowMillis >= expiryMillis

  /** In-memory event type: domain events plus the runtime's own lifecycle markers. */
  enum Journaled[+E]:
    /** `installation` is the record's writer as read back; `None` for one being written here. */
    case Domain(event: E, installation: Option[String] = None)
    case Deleted
    case Expiry(atEpochMillis: Long)
    case Home(installation: String)
    case ReplicationSet(installations: Set[String])
    case Handover(target: String)
    case HandoverAbandoned(target: String)

    /** `installation` is who found the conflict: only there is it folded. */
    case Conflict(other: String, installation: Option[String] = None)

    /** A record pulled from the home, persisted as it came. */
    case Mirrored(record: JournalRecord)

  /** A copy's reply metadata names the installation whose handler ran. */
  val HandledAt: String = "ankka.handled-at"

  /** A forwarded call names the installation that forwarded it, and is never forwarded again. */
  val ForwardedFrom: String = "ankka.forwarded-from"

  def behavior[C <: EventSourcedEntity[S, E], S, E](
      descriptor: EventSourcedEntityDescriptor[C, S, E],
      entityId: EntityId,
      componentClient: ComponentClient,
      replication: Option[Replication] = None
  ): Behavior[EntityProtocol.Command] =
    // Only an entity that declares itself replicated, on a service that names installations.
    val rep = replication.filter(r => descriptor.replicated && r.replicates(descriptor.componentId))
    Behaviors.withTimers { timers =>
      Behaviors.setup { ctx =>
        val entity = descriptor.create(
          SimpleEntityContext(entityId, descriptor.componentId, componentClient)
        )
        // Resolved here, inside setup, where touching the context is safe. Never from a Future
        // callback, and never per invocation: the component's name is interned once per entity.
        val observability = Observability(ctx.system)
        val componentRef  = observability.names.intern(descriptor.componentId.toString)
        val empty         = Stored(entity.emptyState, deleted = false, expiryMillis = 0L)
        val copy          = rep.map(r => Copy(r, descriptor.componentId, entityId, ctx))
        val host = Host(
          descriptor,
          entity,
          entityId,
          observability,
          componentRef,
          copy,
          timers,
          ctx
        )

        val base = EventSourcedBehavior
          .withEnforcedReplies[
            EntityProtocol.Command,
            Journaled[E],
            Stored[S]
          ](
            persistenceId = PersistenceId(descriptor.componentId, entityId),
            emptyState = empty,
            commandHandler = (state, command) =>
              host.onCommand(state, command, EventSourcedBehavior.lastSequenceNumber(ctx)),
            eventHandler = (state, event) =>
              onEvent(
                descriptor,
                entity,
                state,
                event,
                rep.map(_.self),
                EventSourcedBehavior.lastSequenceNumber(ctx)
              )
          )
          .eventAdapter(eventAdapter(descriptor, rep.map(_.self)))
          .snapshotAdapter(snapshotAdapter(descriptor, entity))
          .receiveSignal {
            case (state, RecoveryCompleted) =>
              copy.foreach(_.recovered(state, EventSourcedBehavior.lastSequenceNumber(ctx)))
            case (_, PostStop) => copy.foreach(_.stopped())
          }

        descriptor.snapshotEvery match
          case Some(n) if n > 0 => base.withRetention(RetentionCriteria.snapshotEvery(n, 2))
          case _                => base
      }
    }

  // ── One copy of a replicated entity ───────────────────────────────────────

  /** What locate found for a copy that holds no record saying where the entity lives. */
  private enum Where:
    /** Another installation holds it, as `located` says. */
    case At(located: replication.Located)

    /**
     * No installation that could be reached holds it. `writer` is who wrote the copy's own first
     * record when that was another installation: a copy part way through mirroring a home it cannot
     * now reach.
     */
    case Nobody(writer: Option[String])

  /**
   * What one running copy of a replicated entity keeps in memory beside its journal: its puller,
   * where locate said the entity lives, and which installations' histories it has compared with its
   * own. Touched only on the actor's thread, but for `position`, which its puller reads.
   */
  private final class Copy(
      val rep: Replication,
      componentId: ComponentId,
      entityId: EntityId,
      ctx: ActorContext[EntityProtocol.Command]
  ):
    val me: String = rep.self

    /** The last sequence number this copy holds, read by the puller when it opens again. */
    @volatile var position: Long = 0L

    private var puller: Option[(String, UniqueKillSwitch)] = None
    var where: Option[Where]                               = None
    var locating: Boolean                                  = false
    var checked: Set[String]                               = Set.empty

    private val system: ActorSystem[?] = ctx.system
    private val self                   = ctx.self

    /**
     * The entity as locate found it, while this copy is catching up to a history that includes it:
     * a copy added to the replication set mirrors from the first record, through sets and homes of
     * the past that may not name it, and follows the home locate named until its own journal says
     * the same.
     */
    private def joining: Option[replication.Located] =
      where.collect { case Where.At(l) if l.home != me && l.replicationSet.contains(me) => l }

    /** Where the copy stands by its own journal alone. */
    private def journalMode[S](state: Stored[S]): Mode =
      // A home with no set known is a first pull cut short: where the entity lives is asked again.
      if state.home.nonEmpty && state.replicationSet.isEmpty then Mode.Unknown
      else Mode.of(me, state.home, state.replicationSet)

    private def caughtUp[S](state: Stored[S], located: replication.Located): Boolean =
      journalMode(state) == Mode.Follower && state.home.contains(located.home)

    def mode[S](state: Stored[S]): Mode =
      joining match
        case Some(l) if !caughtUp(state, l) => Mode.Follower
        case _                              => journalMode(state)

    /** The home, as the journal says it, or as locate said it while the copy catches up. */
    def home[S](state: Stored[S]): Option[String] =
      joining match
        case Some(l) if !caughtUp(state, l) => Some(l.home)
        case _                              => state.home

    /** The installation this copy should be mirroring, if any. */
    private def source[S](state: Stored[S]): Option[String] =
      if state.conflict.nonEmpty then None
      else if mode(state) == Mode.Follower then home(state)
      else None

    /** Starts, switches or stops the puller to match what the journal now says. */
    def reconcile[S](state: Stored[S], lastSequenceNr: Long): Unit =
      position = lastSequenceNr
      // Caught up: from here the copy's own journal says where it stands.
      if joining.exists(caughtUp(state, _)) then where = None
      val wanted = source(state)
      if puller.map(_._1) != wanted then
        puller.foreach(_._2.shutdown())
        puller = wanted.map(home => home -> pull(home))

    def recovered[S](state: Stored[S], lastSequenceNr: Long): Unit =
      reconcile(state, lastSequenceNr)
      reportIfTarget(state, lastSequenceNr)

    def stopped(): Unit =
      puller.foreach(_._2.shutdown())
      puller = None

    /** The target of a pending handover tells the home how much it holds. */
    def reportIfTarget[S](state: Stored[S], lastSequenceNr: Long): Unit =
      if state.pendingHandover.contains(me) then
        state.home.foreach(home =>
          rep.transport.report(home, componentId, entityId, lastSequenceNr): Unit
        )

    /**
     * The home's records from the one after the last this copy holds, each handed to the copy to
     * persist as its next and acknowledged before the next is asked for. Opened again from the
     * copy's own position after any end: a refused record, a transport that failed, a source that
     * completed.
     */
    private def pull(home: String): UniqueKillSwitch =
      given Materializer                           = Materializer(system)
      given Timeout                                = Timeout(30.seconds)
      given org.apache.pekko.actor.typed.Scheduler = system.scheduler
      import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
      RestartSource
        .withBackoff(RestartSettings(250.millis, 5.seconds, 0.2)) { () =>
          rep.transport
            .records(home, componentId, entityId, position + 1)
            .mapAsync(1) { replicated =>
              self.ask[EntityProtocol.ReplicateAck](ack =>
                EntityProtocol.Replicate(replicated.sequenceNr, replicated.record, home, ack)
              )
            }
            .map {
              case EntityProtocol.ReplicateAck.Persisted(_) => ()
              case EntityProtocol.ReplicateAck.Refused(reason) =>
                throw IllegalStateException(s"a record from '$home' was refused: $reason")
            }
        }
        .viaMat(KillSwitches.single)(Keep.right)
        .to(Sink.ignore)
        .run()

    /** Who wrote this copy's first record, when it was not this installation. */
    def firstWriter(): Future[Option[String]] =
      given ExecutionContext = system.executionContext
      firstRecord().map(_.flatMap(_.installation).filter(_ != me))

    /** This copy's first record, read from its own journal. */
    def firstRecord(): Future[Option[JournalRecord]] =
      val pid = PersistenceId(componentId, entityId).id.replace("'", "''")
      val serialization =
        org.apache.pekko.serialization.SerializationExtension(system.classicSystem)
      Database()(using system)
        .queryOne(
          SqlFragment.raw(
            "SELECT event_ser_id, event_ser_manifest, event_payload FROM event_journal " +
              s"WHERE persistence_id = '$pid' AND seq_nr = 1"
          )
        ) { row =>
          serialization
            .deserialize(
              row.get("event_payload", classOf[Array[Byte]]),
              row.get("event_ser_id", classOf[java.lang.Integer]).intValue,
              row.get("event_ser_manifest", classOf[String])
            )
            .get
            .asInstanceOf[JournalRecord]
        }

  /** One history's first record, as two installations holding the same history both hold it. */
  private def fingerprint(record: JournalRecord, holder: String): (Int, String, Seq[Byte], String) =
    (record.kind, record.manifest, record.payload.toSeq, record.installation.getOrElse(holder))

  /** One entity's host: everything a command needs, so the handlers below read as the rules. */
  private final class Host[C <: EventSourcedEntity[S, E], S, E](
      descriptor: EventSourcedEntityDescriptor[C, S, E],
      entity: C,
      entityId: EntityId,
      observability: Observability,
      componentRef: Int,
      copy: Option[Copy],
      timers: TimerScheduler[EntityProtocol.Command],
      ctx: ActorContext[EntityProtocol.Command]
  ):
    private type Effect = ReplyEffect[Journaled[E], Stored[S]]

    private def componentId = descriptor.componentId

    /**
     * Persists, then hands the commands stashed meanwhile back to the handler, with no reply of its
     * own. `thenUnstashAll` is typed as a plain `Effect`, which a handler with enforced replies
     * cannot return; every effect Pekko builds is a `ReplyEffect` too, which the type test checks.
     */
    extension (
        builder: org.apache.pekko.persistence.typed.scaladsl.EffectBuilder[Journaled[E], Stored[S]]
    )
      private def unstashingNoReply: Effect = builder.thenUnstashAll() match
        case reply: ReplyEffect[?, ?] => reply.asInstanceOf[Effect]
        case other =>
          throw IllegalStateException(s"Pekko built an effect that is not a reply effect: $other")

    def onCommand(state: Stored[S], command: EntityProtocol.Command, lastSeq: Long): Effect =
      command match
        case invoke: EntityProtocol.Invoke =>
          copy match
            case None    => onInvoke(state, invoke, lastSeq, Local.Unreplicated)
            case Some(c) => onReplicatedInvoke(c, state, invoke, lastSeq)

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

        case replicate: EntityProtocol.Replicate =>
          copy match
            case Some(c) => onReplicate(c, state, replicate, lastSeq)
            case None =>
              replicate.ack ! EntityProtocol.ReplicateAck.Refused(
                s"'$componentId' is not replicated here"
              )
              PekkoEffect.noReply

        case wake: EntityProtocol.Wake =>
          copy.fold(PekkoEffect.noReply[Journaled[E], Stored[S]])(onWake(_, state, wake, lastSeq))

        case reported: EntityProtocol.Reported =>
          copy.fold(PekkoEffect.noReply[Journaled[E], Stored[S]])(
            onReported(_, state, reported, lastSeq)
          )

        case forwarded: EntityProtocol.Forwarded =>
          onForwarded(forwarded)

        case located: EntityProtocol.Located =>
          copy.fold(PekkoEffect.noReply[Journaled[E], Stored[S]])(
            onLocated(_, state, located, lastSeq)
          )

        case checked: EntityProtocol.ConflictChecked =>
          copy.fold(PekkoEffect.noReply[Journaled[E], Stored[S]])(
            onConflictChecked(_, state, checked, lastSeq)
          )

        case timedOut: EntityProtocol.HandoverTimedOut =>
          copy.fold(PekkoEffect.noReply[Journaled[E], Stored[S]])(
            onHandoverTimedOut(_, state, timedOut, lastSeq)
          )

        case _ =>
          // Entities and workflows share one command protocol so the transport needs only
          // one sharding key type. The workflow engine's internal commands are self-sent,
          // so an entity can never actually receive one.
          PekkoEffect.unhandled.thenNoReply()

    // ── How a command is handled here ─────────────────────────────────────

    /** How a copy handles a command it runs a handler for itself. */
    private enum Local:
      /** Not replicated: as every entity did before replication. */
      case Unreplicated

      /** The home, or a copy creating the entity as its home. */
      case AtHome(c: Copy, creating: Boolean)

      /** A follower answering a read from its own copy. */
      case AtFollower(c: Copy)

    private def onInvoke(
        state: Stored[S],
        invoke: EntityProtocol.Invoke,
        lastSeq: Long,
        local: Local
    ): Effect =
      descriptor.handler(MethodName(invoke.method)) match
        case None =>
          // Answered by the platform, and no handler ran: a call that was not delivered. It is
          // counted under no name that was sent, since the method is not one anyone declared.
          observability.undelivered(
            MetaEntry.toMetadata(invoke.metadata),
            descriptor.componentId.toString,
            None
          )
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

          val (installation, isHome) = local match
            case Local.Unreplicated  => (None, true)
            case Local.AtHome(c, _)  => (Some(c.me), true)
            case Local.AtFollower(c) => (Some(c.me), false)

          entity._setState(visible)
          entity._setContext(
            Some(
              SimpleCommandContext(
                entityId,
                descriptor.componentId,
                MetaEntry.toMetadata(invoke.metadata),
                lastSeq,
                installation,
                isHome
              )
            )
          )
          // The span covers the handler and the effect it returns — the work this component
          // did for this request. The parent comes from the caller's metadata, which is how a
          // trace survives a sharding hop without any protocol type changing.
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
          val started = System.nanoTime()
          // Failed until proven otherwise: if the handler throws, that is what is recorded.
          var outcome = SpanOutcome.Failed
          try
            // Published as current for the duration of the handler, so a nested
            // ComponentClient call is recorded as this span's child rather than a root.
            val (effect, handlerOutcome) =
              Trace.within(
                span,
                CallOrigin(descriptor.componentId.toString, invoke.method)
              )(interpret(binding, invoke, state, lastSeq, local))
            outcome = handlerOutcome
            effect
          finally
            observability.recorder.complete(span, outcome)
            // The same ending, counted as a call from whoever the metadata names: only this
            // host knows whether the handler refused or failed.
            observability.handled(
              metadata,
              descriptor.componentId.toString,
              invoke.method,
              outcome,
              System.nanoTime() - started
            )
            entity._setContext(None)

    /**
     * The single cast in the runtime's write path.
     *
     * It is safe by construction: `EventSourcedEntity.Companion.command` and `.query` only accept
     * functions returning `EventSourcedEffect[S, E, ?]`, so nothing else can ever reach a binding
     * registered on this descriptor.
     */
    private def interpret(
        binding: HandlerBinding[C],
        invoke: EntityProtocol.Invoke,
        state: Stored[S],
        lastSeq: Long,
        local: Local
    ): (Effect, SpanOutcome) =
      val effect =
        binding
          .decodeAndInvoke(entity, invoke.payload)
          .asInstanceOf[EventSourcedEffect[S, E, Any]]

      // What a replicated entity writes beside the handler's events: the records that create it,
      // and the handover or change to its replication set the handler asked for.
      val records: Either[CommandError, (Vector[Journaled[E]], Vector[Journaled[E]])] =
        local match
          case Local.Unreplicated =>
            effect.replication match
              case None => Right((Vector.empty, Vector.empty))
              case Some(change) =>
                Left(
                  CommandError(
                    s"'$componentId' is not replicated on this service, which names no other " +
                      s"installation; it cannot $change",
                    ErrorCode.BadRequest
                  )
                )
          case Local.AtFollower(_) => Right((Vector.empty, Vector.empty))
          case Local.AtHome(c, creating) =>
            replicationRecords(c, state, effect, creating)

      // The span outcome is returned rather than inferred by the caller, because the caller cannot
      // see it: `effects.error(...)` produces a *value*, not an exception, so a refusal reaches the
      // caller looking exactly like a success. A console that painted a working ACL red would teach
      // its reader that red means nothing, which is how a real failure gets ignored.
      (effect.outcome, records) match
        case (Outcome.Fail(error), _) =>
          (PekkoEffect.reply(invoke.replyTo)(EntityProtocol.Rejected(error)), SpanOutcome.Refused)

        case (_, Left(error)) =>
          (PekkoEffect.reply(invoke.replyTo)(EntityProtocol.Rejected(error)), SpanOutcome.Refused)

        case (outcome, Right((before, after))) =>
          val handlerRecords =
            effect.events.map(Journaled.Domain(_)) ++ retentionRecord(effect.retention)
          // A command that asks for nothing creates nothing: a creation's records are written only
          // with what the handler persists or the replication change it asks for.
          val writes =
            if handlerRecords.isEmpty && after.isEmpty && effect.replication.isEmpty then
              Vector.empty
            else before ++ handlerRecords ++ after
          val handledAt = local match
            case Local.AtHome(c, _)  => Vector(MetaEntry(HandledAt, c.me))
            case Local.AtFollower(c) => Vector(MetaEntry(HandledAt, c.me))
            case Local.Unreplicated  => Vector.empty
          val persisted =
            persist(writes).thenRun((stored: Stored[S]) => afterPersist(stored, writes, lastSeq))
          outcome match
            case Outcome.NoReply =>
              (persisted.thenNoReply(), SpanOutcome.Ok)
            case Outcome.Reply(compute, metadata) =>
              val reply: Effect =
                persisted.thenReply(invoke.replyTo) { stored =>
                  EntityProtocol.Succeeded(
                    binding.encodeReply(compute(stored.value)),
                    MetaEntry.from(metadata) ++ handledAt
                  )
                }
              (reply, SpanOutcome.Ok)
            case Outcome.Fail(error) =>
              (
                PekkoEffect.reply(invoke.replyTo)(EntityProtocol.Rejected(error)),
                SpanOutcome.Refused
              )
      end match
    end interpret

    /**
     * The records a home writes beside a handler's events: for a creation, that this installation
     * is the home and the replication set it starts with (itself, and whoever the handler added);
     * for a handover, that it is pending; for a change to the set, the whole set after it.
     */
    private def replicationRecords(
        c: Copy,
        state: Stored[S],
        effect: EventSourcedEffect[S, E, Any],
        creating: Boolean
    ): Either[CommandError, (Vector[Journaled[E]], Vector[Journaled[E]])] =
      val known = c.rep.settings.installations + c.me
      def unknown(names: Set[String]) =
        (names -- known).toVector.sorted.headOption.map { name =>
          CommandError(
            s"'$name' is not an installation this service names; it names ${known.toVector.sorted.mkString(", ")}",
            ErrorCode.BadRequest
          )
        }
      val current = if creating then Set(c.me) else state.replicationSet
      effect.replication match
        case None =>
          Right(
            (
              if creating then Vector(Journaled.Home(c.me), Journaled.ReplicationSet(current))
              else Vector.empty,
              Vector.empty
            )
          )
        case Some(ReplicationChange.SetChange(add, remove)) =>
          unknown(add ++ remove) match
            case Some(error) => Left(error)
            case None if remove.contains(c.me) =>
              Left(
                CommandError(
                  s"'${c.me}' is the home of '$entityId' and cannot leave its replication set; " +
                    "hand the home over first",
                  ErrorCode.BadRequest
                )
              )
            case None =>
              val next = current ++ add -- remove
              if creating then
                Right((Vector(Journaled.Home(c.me), Journaled.ReplicationSet(next)), Vector.empty))
              else Right((Vector.empty, Vector(Journaled.ReplicationSet(next))))
        case Some(ReplicationChange.HandOver(target)) =>
          val refusal =
            if target == c.me then Some(s"'$target' is already the home of '$entityId'")
            else if !known.contains(target) then
              Some(s"'$target' is not an installation this service names")
            else if !current.contains(target) then
              Some(
                s"'$target' is outside the replication set of '$entityId' " +
                  s"(${current.toVector.sorted.mkString(", ")}); add it before handing the home to it"
              )
            else
              state.pendingHandover.map(pending =>
                s"a handover of '$entityId' to '$pending' is already waiting"
              )
          refusal match
            case Some(message) => Left(CommandError(message, ErrorCode.BadRequest))
            case None =>
              Right(
                (
                  if creating then Vector(Journaled.Home(c.me), Journaled.ReplicationSet(current))
                  else Vector.empty,
                  Vector(Journaled.Handover(target))
                )
              )

    /** What a home does once it has persisted what a command wrote. */
    private def afterPersist(stored: Stored[S], writes: Vector[Journaled[E]], before: Long): Unit =
      copy.foreach { c =>
        c.reconcile(stored, before + writes.size)
        writes.collectFirst { case Journaled.Handover(target) => target }.foreach { target =>
          if c.rep.settings.holdWrites then
            timers.startSingleTimer(
              HandoverTimer,
              EntityProtocol.HandoverTimedOut(target),
              c.rep.settings.handoverTimeout
            )
        }
      }

    private val HandoverTimer = "ankka-handover"

    private def persist(
        events: Vector[Journaled[E]]
    ): org.apache.pekko.persistence.typed.scaladsl.EffectBuilder[Journaled[E], Stored[S]] =
      if events.isEmpty then PekkoEffect.none else PekkoEffect.persist(events.toList)

    // ── A replicated entity's commands ────────────────────────────────────

    private def onReplicatedInvoke(
        c: Copy,
        state: Stored[S],
        invoke: EntityProtocol.Invoke,
        lastSeq: Long
    ): Effect =
      val method = MethodName(invoke.method)
      if method == ReplicationStatus.Method then status(c, state, invoke, lastSeq)
      else if method == ReplicationStatus.HandoverMethod then handoverStatus(state, invoke)
      else
        descriptor.handler(method) match
          // No such handler: answered as any entity answers it.
          case None => onInvoke(state, invoke, lastSeq, Local.Unreplicated)
          case Some(binding) =>
            val reads = binding.readOnly
            if state.conflict.nonEmpty && !reads then
              refuse(
                invoke,
                CommandError(
                  s"'$entityId' is in conflict with '${state.conflict.get}': it was created at two " +
                    "installations, and takes no write until an operator resolves it",
                  ErrorCode.Conflict
                )
              )
            else
              c.mode(state) match
                case Mode.Home =>
                  if !reads && state.pendingHandover.nonEmpty && c.rep.settings.holdWrites then
                    // A handover configured to hold writes holds them until it is recorded or
                    // abandoned; then they are forwarded to the new home or handled here.
                    PekkoEffect.stash()
                  else onInvoke(state, invoke, lastSeq, Local.AtHome(c, creating = false))
                case Mode.Follower =>
                  if reads then onInvoke(state, invoke, lastSeq, Local.AtFollower(c))
                  else forward(c, invoke, c.home(state).get)
                case Mode.Outside =>
                  if reads then refuse(invoke, notHome(c.home(state).get, outside = true))
                  else forward(c, invoke, c.home(state).get)
                case Mode.Unknown =>
                  c.where match
                    case None =>
                      if !c.locating then locate(c, lastSeq)
                      PekkoEffect.stash()
                    case Some(Where.At(located)) if located.home != c.me =>
                      if reads then
                        if located.replicationSet.contains(c.me) then
                          onInvoke(state, invoke, lastSeq, Local.AtFollower(c))
                        else refuse(invoke, notHome(located.home, outside = true))
                      else forward(c, invoke, located.home)
                    case Some(Where.Nobody(Some(writer))) =>
                      // Part way through mirroring a home that cannot now be reached.
                      if reads then onInvoke(state, invoke, lastSeq, Local.AtFollower(c))
                      else refuse(invoke, notHome(writer, outside = false))
                    case Some(_) =>
                      // Nobody else holds it, or it is this installation's own history from
                      // before replication: the first write here creates it, with this
                      // installation as its home.
                      if reads then
                        onInvoke(state, invoke, lastSeq, Local.AtHome(c, creating = false))
                      else onInvoke(state, invoke, lastSeq, Local.AtHome(c, creating = true))

    private def refuse(invoke: EntityProtocol.Invoke, error: CommandError): Effect =
      PekkoEffect.reply(invoke.replyTo)(EntityProtocol.Rejected(error))

    private def notHome(home: String, outside: Boolean): CommandError =
      if outside then
        CommandError(
          s"this installation is outside the replication set of '$entityId'; its home is '$home'",
          ErrorCode.NotHome
        )
      else
        CommandError(
          s"the home of '$entityId' is '$home', which cannot be reached",
          ErrorCode.NotHome
        )

    private def status(
        c: Copy,
        state: Stored[S],
        invoke: EntityProtocol.Invoke,
        lastSeq: Long
    ): Effect =
      val answer = ReplicationStatus(
        state.home,
        state.replicationSet,
        state.pendingHandover,
        state.conflict,
        lastSeq
      )
      PekkoEffect.reply(invoke.replyTo)(
        EntityProtocol.Succeeded(
          ReplicationStatus.serializer.toBytes(answer),
          Vector(MetaEntry(HandledAt, c.me))
        )
      )

    private def handoverStatus(state: Stored[S], invoke: EntityProtocol.Invoke): Effect =
      val target = String(invoke.payload, java.nio.charset.StandardCharsets.UTF_8)
      val reply =
        if state.pendingHandover.contains(target) then
          EntityProtocol.Succeeded(ReplicationStatus.Pending.getBytes("UTF-8"), Vector.empty)
        else if state.home.contains(target) then
          EntityProtocol.Succeeded(ReplicationStatus.Completed.getBytes("UTF-8"), Vector.empty)
        else
          EntityProtocol.Rejected(
            CommandError(
              s"the handover of '$entityId' to '$target' did not happen",
              ErrorCode.Unavailable
            )
          )
      PekkoEffect.reply(invoke.replyTo)(reply)

    // ── Forwarding ─────────────────────────────────────────────────────────

    /**
     * Hands a command that may persist to the home and answers the caller with the home's reply.
     * One call in the trace: a client span here, under the caller's, which the home's handler span
     * is a child of. Counted where it is handled, at the home; here only when it went unanswered. A
     * forwarded call is never forwarded again, and never sent twice.
     */
    private def forward(c: Copy, invoke: EntityProtocol.Invoke, home: String): Effect =
      val metadata = MetaEntry.toMetadata(invoke.metadata)
      if metadata.get(ForwardedFrom).nonEmpty then
        refuse(
          invoke,
          CommandError(
            s"'${c.me}' is not the home of '$entityId' either; a write forwarded once is not " +
              "forwarded again",
            ErrorCode.NotHome
          )
        )
      else if !c.rep.transport.reachable(home) then refuse(invoke, notHome(home, outside = false))
      else
        val inbound = Trace.inbound(metadata)
        val span = observability.recorder.reserve(
          inbound.traceIdHigh,
          inbound.traceId,
          componentRef,
          observability.names.intern(invoke.method)
        )
        val carried = Trace.into(metadata, span.context).set(ForwardedFrom, c.me)
        val call    = ForwardedCall(MethodName(invoke.method), invoke.payload, carried)
        val origin  = CallOrigin.from(metadata)
        val method  = invoke.method
        ctx.pipeToSelf(c.rep.transport.forward(home, componentId, entityId, call)) { result =>
          val outcome = result match
            case Success(ForwardedReply.Succeeded(_, _))           => SpanOutcome.Ok
            case Success(ForwardedReply.Rejected(e))               => Observability.outcomeOf(e)
            case Failure(_: java.util.concurrent.TimeoutException) => SpanOutcome.TimedOut
            case Failure(_)                                        => SpanOutcome.Failed
          observability.recorder.record(span, inbound.parentSpanId, SpanKind.Client, outcome)
          result match
            case Failure(_: java.util.concurrent.TimeoutException) =>
              observability.unanswered(origin, componentId.toString, method, Unanswered.TimedOut)
            case Failure(_) =>
              observability.unanswered(origin, componentId.toString, method, Unanswered.Undelivered)
            case Success(_) => ()
          EntityProtocol.Forwarded(invoke.replyTo, result, home)
        }
        PekkoEffect.noReply

    private def onForwarded(forwarded: EntityProtocol.Forwarded): Effect =
      val reply = forwarded.result match
        case Success(ForwardedReply.Succeeded(bytes, metadata)) =>
          EntityProtocol.Succeeded(bytes, MetaEntry.from(metadata))
        case Success(ForwardedReply.Rejected(error)) => EntityProtocol.Rejected(error)
        case Failure(_: java.util.concurrent.TimeoutException) =>
          EntityProtocol.Rejected(
            CommandError(
              s"the home of '$entityId', '${forwarded.home}', did not answer in time; the write " +
                "may or may not have happened",
              ErrorCode.Timeout
            )
          )
        case Failure(_) => EntityProtocol.Rejected(notHome(forwarded.home, outside = false))
      PekkoEffect.reply(forwarded.replyTo)(reply)

    // ── Where an entity lives ─────────────────────────────────────────────

    private def locate(c: Copy, lastSeq: Long): Unit =
      c.locating = true
      given ExecutionContext = ExecutionContext.parasitic
      val writer =
        if lastSeq == 0 then Future.successful(None)
        else c.firstWriter().recover { case _ => None }
      val asked = c.rep.transport.locate(componentId, entityId)
      ctx.pipeToSelf(writer.zip(asked.transform(scala.util.Success(_)))) {
        case Success((first, located)) => EntityProtocol.Located(located, first)
        case Failure(e)                => EntityProtocol.Located(Failure(e), None)
      }

    private def onLocated(
        c: Copy,
        state: Stored[S],
        located: EntityProtocol.Located,
        lastSeq: Long
    ): Effect =
      c.locating = false
      c.where = Some(located.result match
        case Success(Some(l)) if l.home != c.me => Where.At(l)
        case Success(Some(_))                   => Where.Nobody(None)
        case _                                  => Where.Nobody(located.firstWriter))
      c.reconcile(state, lastSeq)
      PekkoEffect.none[Journaled[E], Stored[S]].unstashingNoReply

    // ── Mirroring ──────────────────────────────────────────────────────────

    /**
     * Persists the record a follower's puller handed it, as its next, unchanged but for the
     * installation of a record written before replication, which it stamps with the installation it
     * pulled it from. Refuses one that is not the next, or that the entity's home did not write.
     */
    private def onReplicate(
        c: Copy,
        state: Stored[S],
        replicate: EntityProtocol.Replicate,
        lastSeq: Long
    ): Effect =
      val writer   = replicate.record.installation.getOrElse(replicate.source)
      val expected = state.home.getOrElse(replicate.source)
      def refused(reason: String): Effect =
        ctx.log.warn(
          "refused a record of {} '{}' at {}: {}",
          componentId,
          entityId,
          replicate.sequenceNr,
          reason
        )
        replicate.ack ! EntityProtocol.ReplicateAck.Refused(reason)
        PekkoEffect.noReply
      if replicate.sequenceNr <= lastSeq then
        replicate.ack ! EntityProtocol.ReplicateAck.Persisted(replicate.sequenceNr)
        PekkoEffect.noReply
      else if c.mode(state) == Mode.Home then refused("this installation is the home")
      else if state.conflict.nonEmpty then refused("this copy is in conflict")
      else if replicate.sequenceNr != lastSeq + 1 then
        refused(s"not the next record: this copy holds $lastSeq")
      else if writer != expected then refused(s"written by '$writer', and the home is '$expected'")
      else
        val record =
          if replicate.record.installation.isEmpty then replicate.record.at(Some(replicate.source))
          else replicate.record
        PekkoEffect
          .persist[Journaled[E], Stored[S]](Journaled.Mirrored(record))
          .thenRun { (stored: Stored[S]) =>
            c.reconcile(stored, replicate.sequenceNr)
            c.reportIfTarget(stored, replicate.sequenceNr)
            replicate.ack ! EntityProtocol.ReplicateAck.Persisted(replicate.sequenceNr)
          }
          .unstashingNoReply

    private def onWake(
        c: Copy,
        state: Stored[S],
        wake: EntityProtocol.Wake,
        lastSeq: Long
    ): Effect =
      c.mode(state) match
        case Mode.Unknown =>
          if wake.joined && !c.locating then
            c.where = None
            locate(c, lastSeq)
          else c.reconcile(state, lastSeq)
        case Mode.Home if wake.installation != c.me && !c.checked(wake.installation) =>
          // Another installation wrote an entity this one is the home of. Either it was the home
          // before this one, and its history is this one's, or it created the entity too.
          c.checked += wake.installation
          checkConflict(c, wake.installation)
        case _ => c.reconcile(state, lastSeq)
      PekkoEffect.noReply

    /** Compares the first record of `other`'s history with this copy's. */
    private def checkConflict(c: Copy, other: String): Unit =
      given Materializer     = Materializer(ctx.system)
      given ExecutionContext = ctx.system.executionContext
      val theirs = c.rep.transport
        .records(other, componentId, entityId, 1L)
        .take(1)
        .completionTimeout(10.seconds)
        .runWith(Sink.headOption)
      val differs = c.firstRecord().zip(theirs).map {
        case (Some(mine), Some(first)) =>
          fingerprint(mine, c.me) != fingerprint(first.record, other)
        case _ => false
      }
      ctx.pipeToSelf(differs)(EntityProtocol.ConflictChecked(other, _))

    private def onConflictChecked(
        c: Copy,
        state: Stored[S],
        checked: EntityProtocol.ConflictChecked,
        lastSeq: Long
    ): Effect =
      checked.differs match
        case Success(true) if state.conflict.isEmpty =>
          ctx.log.warn(
            "{} '{}' was created at '{}' too: marked in conflict",
            componentId,
            entityId,
            checked.other
          )
          PekkoEffect
            .persist[Journaled[E], Stored[S]](Journaled.Conflict(checked.other))
            .thenRun((stored: Stored[S]) => c.reconcile(stored, lastSeq + 1))
            .thenNoReply()
        case Success(_) => PekkoEffect.noReply
        case Failure(_) =>
          // Not known either way: compared again at the next wake from it.
          c.checked -= checked.other
          PekkoEffect.noReply

    // ── The handover ───────────────────────────────────────────────────────

    /**
     * The target of a pending handover says how much it holds. Once it holds everything this home
     * has written, the handover is recorded and the target is the home from the record on.
     */
    private def onReported(
        c: Copy,
        state: Stored[S],
        reported: EntityProtocol.Reported,
        lastSeq: Long
    ): Effect =
      if c.mode(state) == Mode.Home && state.pendingHandover.contains(reported.installation) &&
        reported.position >= lastSeq
      then
        timers.cancel(HandoverTimer)
        PekkoEffect
          .persist[Journaled[E], Stored[S]](Journaled.Home(reported.installation))
          .thenRun((stored: Stored[S]) => c.reconcile(stored, lastSeq + 1))
          .unstashingNoReply
      else PekkoEffect.noReply

    /** A handover that holds writes waited as long as it may: it did not happen. */
    private def onHandoverTimedOut(
        c: Copy,
        state: Stored[S],
        timedOut: EntityProtocol.HandoverTimedOut,
        lastSeq: Long
    ): Effect =
      if c.mode(state) == Mode.Home && state.pendingHandover.contains(timedOut.target) then
        ctx.log.warn(
          "the handover of {} '{}' to '{}' was abandoned after {}",
          componentId,
          entityId,
          timedOut.target,
          c.rep.settings.handoverTimeout
        )
        PekkoEffect
          .persist[Journaled[E], Stored[S]](Journaled.HandoverAbandoned(timedOut.target))
          .thenRun((stored: Stored[S]) => c.reconcile(stored, lastSeq + 1))
          .unstashingNoReply
      else PekkoEffect.noReply
  end Host

  private def retentionRecord[E](retention: Option[Retention]): Vector[Journaled[E]] =
    retention match
      case None                      => Vector.empty
      case Some(Retention.DeleteNow) => Vector(Journaled.Deleted)
      case Some(Retention.ExpireAfter(d)) =>
        Vector(Journaled.Expiry(System.currentTimeMillis() + d.toMillis))

  // ── Event handling ────────────────────────────────────────────────────────

  private def onEvent[C <: EventSourcedEntity[S, E], S, E](
      descriptor: EventSourcedEntityDescriptor[C, S, E],
      entity: C,
      state: Stored[S],
      event: Journaled[E],
      self: Option[String],
      sequenceNumber: Long
  ): Stored[S] =
    event match
      case Journaled.Domain(domainEvent, written) =>
        // `_applyEvent` sets `currentState` before delegating, so a handler written as
        // `currentState.addItem(...)` — the idiom the docs use — works on replay too.
        // After a deletion or an expiry the handler was shown `emptyState` (see `onCommand`),
        // so the fold starts from it as well: applying onto the kept value would resurrect
        // what was deleted — a deleted entity that was written to again answered with both
        // lives' events, which the conformance suite caught (`es.delete-then-fresh`).
        val base =
          if state.deleted || state.expired(System.currentTimeMillis()) then entity.emptyState
          else state.value
        // A record with no installation was written here: a follower stamps every record it
        // mirrors, so only the home's own journal holds one, from before replication.
        val installation = written.orElse(self)
        val context = SimpleEventContext(
          installation,
          isLocal = installation == self,
          sequenceNumber
        )
        state.copy(
          value = entity._applyEvent(base, domainEvent, context),
          deleted = false,
          expiryMillis = 0L
        )

      case Journaled.Deleted =>
        // The value is kept rather than blanked. Pekko folds every event before
        // `thenReply` runs, so blanking here would make
        // `persist(CheckedOut).deleteEntity().thenReplyState` reply with an empty cart —
        // losing exactly the state the caller asked to be told about. Handlers are shown
        // `emptyState` for a deleted entity at command time instead (see `onCommand`).
        state.copy(deleted = true)

      case Journaled.Expiry(atEpochMillis) =>
        state.copy(expiryMillis = atEpochMillis)

      case Journaled.Home(installation) =>
        state.copy(home = Some(installation), pendingHandover = None)

      case Journaled.ReplicationSet(installations) =>
        state.copy(replicationSet = installations)

      case Journaled.Handover(target) =>
        state.copy(pendingHandover = Some(target))

      case Journaled.HandoverAbandoned(_) =>
        state.copy(pendingHandover = None)

      case Journaled.Conflict(other, written) =>
        // Folded only where it was found: a copy that mirrors a home in conflict holds the record
        // at its sequence number, so its positions stay the home's, and is not in conflict itself.
        if written.orElse(self) == self then state.copy(conflict = Some(other)) else state

      case Journaled.Mirrored(record) =>
        onEvent(descriptor, entity, state, decode(descriptor, record), self, sequenceNumber)

  // ── Storage adapters ──────────────────────────────────────────────────────

  /** A journal record as the event it stands for. */
  private def decode[C <: EventSourcedEntity[S, E], S, E](
      descriptor: EventSourcedEntityDescriptor[C, S, E],
      record: JournalRecord
  ): Journaled[E] =
    record.kind match
      case JournalRecord.KindDomain =>
        Journaled.Domain(descriptor.eventSerializer.fromBytes(record.payload), record.installation)
      case JournalRecord.KindDeleted           => Journaled.Deleted
      case JournalRecord.KindExpiry            => Journaled.Expiry(record.expiryMillis)
      case JournalRecord.KindHome              => Journaled.Home(record.named)
      case JournalRecord.KindReplicationSet    => Journaled.ReplicationSet(record.names)
      case JournalRecord.KindHandover          => Journaled.Handover(record.named)
      case JournalRecord.KindHandoverAbandoned => Journaled.HandoverAbandoned(record.named)
      case JournalRecord.KindConflict => Journaled.Conflict(record.named, record.installation)
      case other =>
        throw IllegalStateException(
          s"unknown journal record kind $other for '${descriptor.componentId}' " +
            s"(manifest '${record.manifest}'); the journal was written by a newer runtime"
        )

  /** The adapter manifest a record of each kind is stored under, mirrored or written here. */
  private def manifestOf(kind: Int, recordManifest: String): String = kind match
    case JournalRecord.KindDomain  => recordManifest
    case JournalRecord.KindDeleted => "ankka.deleted"
    case JournalRecord.KindExpiry  => "ankka.expiry"
    case _                         => recordManifest

  /**
   * Encodes events with the entity's own serializer before they reach Pekko, so the journal holds
   * ankka's JSON under ankka's manifest rather than a Java-serialised or Jackson-reflected form of
   * the domain type. A replicated entity's records name the installation that wrote them, `self`; a
   * mirrored record is persisted as it came.
   */
  private def eventAdapter[C <: EventSourcedEntity[S, E], S, E](
      descriptor: EventSourcedEntityDescriptor[C, S, E],
      self: Option[String]
  ): EventAdapter[Journaled[E], JournalRecord] =
    new EventAdapter[Journaled[E], JournalRecord]:

      def toJournal(event: Journaled[E]): JournalRecord = event match
        case Journaled.Domain(e, _) =>
          JournalRecord
            .domain(descriptor.eventSerializer.manifest, descriptor.eventSerializer.toBytes(e))
            .at(self)
        case Journaled.Deleted                   => JournalRecord.deleted.at(self)
        case Journaled.Expiry(atMillis)          => JournalRecord.expiry(atMillis).at(self)
        case Journaled.Home(installation)        => JournalRecord.home(installation).at(self)
        case Journaled.ReplicationSet(set)       => JournalRecord.replicationSet(set).at(self)
        case Journaled.Handover(target)          => JournalRecord.handover(target).at(self)
        case Journaled.HandoverAbandoned(target) => JournalRecord.handoverAbandoned(target).at(self)
        case Journaled.Conflict(other, _)        => JournalRecord.conflict(other).at(self)
        case Journaled.Mirrored(record)          => record

      def manifest(event: Journaled[E]): String = event match
        case Journaled.Domain(_, _) => descriptor.eventSerializer.manifest
        case Journaled.Mirrored(r)  => manifestOf(r.kind, r.manifest)
        case other                  => manifestOf(toJournal(other).kind, toJournal(other).manifest)

      def fromJournal(record: JournalRecord, manifest: String): EventSeq[Journaled[E]] =
        EventSeq.single(decode(descriptor, record))

  private def snapshotAdapter[C <: EventSourcedEntity[S, E], S, E](
      descriptor: EventSourcedEntityDescriptor[C, S, E],
      entity: C
  ): SnapshotAdapter[Stored[S]] =
    new SnapshotAdapter[Stored[S]]:

      def toJournal(state: Stored[S]): Any =
        StateRecord(
          descriptor.stateSerializer.manifest,
          descriptor.stateSerializer.toBytes(state.value),
          state.deleted,
          state.expiryMillis,
          state.home,
          Option.when(state.replicationSet.nonEmpty)(state.replicationSet.toVector.sorted),
          state.pendingHandover,
          state.conflict
        )

      def fromJournal(from: Any): Stored[S] =
        // A `RemoteStateRecord` is what a sidecar-hosted port of this entity wrote (feature 009);
        // its extra positions are the sidecar's concern. Reading it is what makes the journal
        // portable in that direction.
        from match
          case record: StateRecord =>
            Stored(
              descriptor.stateSerializer.fromBytes(record.payload),
              record.deleted,
              record.expiryMillis,
              record.home,
              record.replicationSet.fold(Set.empty[String])(_.toSet),
              record.pendingHandover,
              record.conflict
            )
          case record: RemoteStateRecord =>
            Stored(
              if record.manifest.isEmpty then entity.emptyState
              else descriptor.stateSerializer.fromBytes(record.payload),
              record.deleted,
              record.expiryMillis,
              record.home,
              record.replicationSet.fold(Set.empty[String])(_.toSet),
              record.pendingHandover,
              record.conflict
            )
          case other =>
            throw IllegalStateException(
              s"unexpected snapshot record ${other.getClass.getName} for '${descriptor.componentId}'"
            )
