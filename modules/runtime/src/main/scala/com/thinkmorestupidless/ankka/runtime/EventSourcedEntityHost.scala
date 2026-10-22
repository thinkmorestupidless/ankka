package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.effect.*
import com.thinkmorestupidless.ankka.runtime.replication.{
  CopyState,
  HostReplication,
  Mode,
  Platform,
  Replication,
  Where
}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{Behavior, PostStop}
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
 * and derived on every command, never kept beside it; the rules are `HostReplication`'s, which a
 * process's remote host applies too. A service that replicates nothing, and an entity that is not
 * replicated, take none of these paths.
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
  ) extends CopyState:
    def expired(nowMillis: Long): Boolean = expiryMillis > 0 && nowMillis >= expiryMillis

  /** In-memory event type: domain events plus the runtime's own lifecycle markers. */
  enum Journaled[+E]:
    /**
     * `installation` is the record's writer as read back; `None` for one being written here.
     * `attribution` and `forwardedFrom` are who acted and the installation that forwarded the
     * command (spec 057): taken from the command when written, from the record when read back.
     */
    case Domain(
        event: E,
        installation: Option[String] = None,
        attribution: Option[String] = None,
        forwardedFrom: Option[String] = None
    )
    case Deleted
    case Expiry(atEpochMillis: Long)

    /** A replicated entity's home, replication set, handover or conflict. */
    case Replication(record: Platform)

    /** A record pulled from the home, persisted as it came. */
    case Mirrored(record: JournalRecord)

  /** A copy's reply metadata names the installation whose handler ran. */
  val HandledAt: String = HostReplication.HandledAt

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
        val copy          = rep.map(r => HostReplication(r, descriptor.componentId, entityId, ctx))
        val host =
          Host(descriptor, entity, entityId, observability, componentRef, copy, timers)

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

  /** One entity's host: everything a command needs, so the handlers below read as the rules. */
  private final class Host[C <: EventSourcedEntity[S, E], S, E](
      descriptor: EventSourcedEntityDescriptor[C, S, E],
      entity: C,
      entityId: EntityId,
      observability: Observability,
      componentRef: Int,
      copy: Option[HostReplication],
      timers: TimerScheduler[EntityProtocol.Command]
  ):
    private type Effect = ReplyEffect[Journaled[E], Stored[S]]

    private def componentId = descriptor.componentId

    /**
     * Persists, then hands the commands stashed meanwhile back to the handler, with no reply of its
     * own. `thenUnstashAll` is typed as a plain `Effect`, which a handler with enforced replies
     * cannot return; every effect Pekko builds is a `ReplyEffect` too, which the type test checks.
     */
    extension (builder: EffectBuilder[Journaled[E], Stored[S]])
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
          copy.fold(PekkoEffect.noReply[Journaled[E], Stored[S]])(c =>
            PekkoEffect.reply(forwarded.replyTo)(c.forwarded(forwarded))
          )

        case located: EntityProtocol.Located =>
          copy.fold(PekkoEffect.noReply[Journaled[E], Stored[S]]) { c =>
            c.located(located)
            c.reconcile(state, lastSeq)
            PekkoEffect.none[Journaled[E], Stored[S]].unstashingNoReply
          }

        case checked: EntityProtocol.ConflictChecked =>
          copy.fold(PekkoEffect.noReply[Journaled[E], Stored[S]]) { c =>
            if c.conflictFound(checked, state) then
              PekkoEffect
                .persist[Journaled[E], Stored[S]](
                  Journaled.Replication(Platform.Conflict(checked.other))
                )
                .thenRun((stored: Stored[S]) => c.reconcile(stored, lastSeq + 1))
                .thenNoReply()
            else PekkoEffect.noReply
          }

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
      case AtHome(c: HostReplication, creating: Boolean)

      /** A follower answering a read from its own copy. */
      case AtFollower(c: HostReplication)

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

          val received = MetaEntry.toMetadata(invoke.metadata)
          entity._setState(visible)
          entity._setContext(
            Some(
              SimpleCommandContext(
                entityId,
                descriptor.componentId,
                received,
                lastSeq,
                installation,
                isHome,
                Attribution.from(received),
                received.get(HostReplication.ForwardedFrom)
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
            // A call the handler makes is for whoever this command was for (spec 057).
            def handle =
              Trace.within(
                span,
                CallOrigin(descriptor.componentId.toString, invoke.method)
              )(interpret(binding, invoke, state, lastSeq, local))
            val (effect, handlerOutcome) =
              Attribution.from(metadata).fold(handle)(Attribution.within(_)(handle))
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
      val records: Either[CommandError, (Vector[Platform], Vector[Platform])] =
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
          case Local.AtFollower(_)       => Right((Vector.empty, Vector.empty))
          case Local.AtHome(c, creating) => c.records(state, effect.replication, creating)

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
          // Each record says who acted and where the command was forwarded from, when it was.
          val received      = MetaEntry.toMetadata(invoke.metadata)
          val attribution   = Attribution.from(received)
          val forwardedFrom = received.get(HostReplication.ForwardedFrom)
          val handlerRecords =
            effect.events.map(Journaled.Domain(_, None, attribution, forwardedFrom)) ++
              retentionRecord(effect.retention)
          // A command that asks for nothing creates nothing: a creation's records are written only
          // with what the handler persists or the replication change it asks for.
          val writes =
            if handlerRecords.isEmpty && after.isEmpty && effect.replication.isEmpty then
              Vector.empty
            else
              before.map(Journaled.Replication(_)) ++ handlerRecords ++
                after.map(Journaled.Replication(_))
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

    /** What a home does once it has persisted what a command wrote. */
    private def afterPersist(stored: Stored[S], writes: Vector[Journaled[E]], before: Long): Unit =
      copy.foreach { c =>
        c.reconcile(stored, before + writes.size)
        writes
          .collectFirst { case Journaled.Replication(Platform.Handover(target)) => target }
          .foreach { target =>
            if c.rep.settings.holdWrites then
              timers.startSingleTimer(
                HostReplication.HandoverTimer,
                EntityProtocol.HandoverTimedOut(target),
                c.rep.settings.handoverTimeout
              )
          }
      }

    private def persist(events: Vector[Journaled[E]]): EffectBuilder[Journaled[E], Stored[S]] =
      if events.isEmpty then PekkoEffect.none else PekkoEffect.persist(events.toList)

    // ── A replicated entity's commands ────────────────────────────────────

    private def onReplicatedInvoke(
        c: HostReplication,
        state: Stored[S],
        invoke: EntityProtocol.Invoke,
        lastSeq: Long
    ): Effect =
      val method = MethodName(invoke.method)
      if method == ReplicationStatus.Method then
        PekkoEffect.reply(invoke.replyTo)(c.status(state, lastSeq))
      else if method == ReplicationStatus.HandoverMethod then
        PekkoEffect.reply(invoke.replyTo)(c.handoverStatus(state, invoke.payload))
      else
        descriptor.handler(method) match
          // No such handler: answered as any entity answers it.
          case None => onInvoke(state, invoke, lastSeq, Local.Unreplicated)
          case Some(binding) =>
            val reads   = binding.readOnly
            val refused = c.admission(invoke)
            if refused.nonEmpty then refuse(invoke, refused.get)
            else if state.conflict.nonEmpty && !reads then refuse(invoke, c.inConflict(state))
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
                  if reads then refuse(invoke, c.notHome(c.home(state).get, outside = true))
                  else forward(c, invoke, c.home(state).get)
                case Mode.Unknown =>
                  c.where match
                    case None =>
                      if !c.locating then c.locate(lastSeq)
                      PekkoEffect.stash()
                    case Some(Where.At(located)) if located.home != c.me =>
                      if reads then
                        if located.replicationSet.contains(c.me) then
                          onInvoke(state, invoke, lastSeq, Local.AtFollower(c))
                        else refuse(invoke, c.notHome(located.home, outside = true))
                      else forward(c, invoke, located.home)
                    case Some(Where.Nobody(Some(writer))) =>
                      // Part way through mirroring a home that cannot now be reached.
                      if reads then onInvoke(state, invoke, lastSeq, Local.AtFollower(c))
                      else refuse(invoke, c.notHome(writer, outside = false))
                    case Some(_) =>
                      // Nobody else holds it, or it is this installation's own history from
                      // before replication: the first write here creates it, with this
                      // installation as its home.
                      onInvoke(state, invoke, lastSeq, Local.AtHome(c, creating = !reads))

    private def refuse(invoke: EntityProtocol.Invoke, error: CommandError): Effect =
      PekkoEffect.reply(invoke.replyTo)(EntityProtocol.Rejected(error))

    private def forward(c: HostReplication, invoke: EntityProtocol.Invoke, home: String): Effect =
      c.forward(invoke, home, observability, componentRef) match
        case Left(error) => refuse(invoke, error)
        case Right(())   => PekkoEffect.noReply

    // ── Mirroring ──────────────────────────────────────────────────────────

    /**
     * Persists the record a follower's puller handed it, as its next, unchanged but for the
     * installation of a record written before replication, which it stamps with the installation it
     * pulled it from. Refuses one that is not the next, or that the entity's home did not write.
     */
    private def onReplicate(
        c: HostReplication,
        state: Stored[S],
        replicate: EntityProtocol.Replicate,
        lastSeq: Long
    ): Effect =
      if replicate.sequenceNr <= lastSeq then
        replicate.ack ! EntityProtocol.ReplicateAck.Persisted(replicate.sequenceNr)
        PekkoEffect.noReply
      else
        c.mirror(state, replicate, lastSeq) match
          case Left(reason) =>
            c.refuseMirror(replicate, reason)
            PekkoEffect.noReply
          case Right(record) =>
            PekkoEffect
              .persist[Journaled[E], Stored[S]](Journaled.Mirrored(record))
              .thenRun { (stored: Stored[S]) =>
                c.reconcile(stored, replicate.sequenceNr)
                c.reportIfTarget(stored, replicate.sequenceNr)
                replicate.ack ! EntityProtocol.ReplicateAck.Persisted(replicate.sequenceNr)
              }
              .unstashingNoReply

    private def onWake(
        c: HostReplication,
        state: Stored[S],
        wake: EntityProtocol.Wake,
        lastSeq: Long
    ): Effect =
      c.mode(state) match
        case Mode.Unknown if wake.joined && !c.locating =>
          c.where = None
          c.locate(lastSeq)
        case _ if c.shouldCompare(state, wake.installation) => c.compare(wake.installation)
        case _                                              => c.reconcile(state, lastSeq)
      PekkoEffect.noReply

    // ── The handover ───────────────────────────────────────────────────────

    /**
     * The target of a pending handover says how much it holds. Once it holds everything this home
     * has written, the handover is recorded and the target is the home from the record on.
     */
    private def onReported(
        c: HostReplication,
        state: Stored[S],
        reported: EntityProtocol.Reported,
        lastSeq: Long
    ): Effect =
      if c.mode(state) == Mode.Home && state.pendingHandover.contains(reported.installation) &&
        reported.position >= lastSeq
      then
        timers.cancel(HostReplication.HandoverTimer)
        PekkoEffect
          .persist[Journaled[E], Stored[S]](
            Journaled.Replication(Platform.Home(reported.installation))
          )
          .thenRun((stored: Stored[S]) => c.reconcile(stored, lastSeq + 1))
          .unstashingNoReply
      else PekkoEffect.noReply

    /** A handover that holds writes waited as long as it may: it did not happen. */
    private def onHandoverTimedOut(
        c: HostReplication,
        state: Stored[S],
        timedOut: EntityProtocol.HandoverTimedOut,
        lastSeq: Long
    ): Effect =
      if c.mode(state) == Mode.Home && state.pendingHandover.contains(timedOut.target) then
        c.logAbandoned(timedOut.target)
        PekkoEffect
          .persist[Journaled[E], Stored[S]](
            Journaled.Replication(Platform.HandoverAbandoned(timedOut.target))
          )
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
      case Journaled.Domain(domainEvent, written, attribution, forwardedFrom) =>
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
          sequenceNumber,
          attribution,
          forwardedFrom
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
            // Folded only where it was found: a copy that mirrors a home in conflict holds the
            // record at its sequence number, so its positions stay the home's, and is not in
            // conflict itself.
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
        Journaled.Domain(
          descriptor.eventSerializer.fromBytes(record.payload),
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
              s"unknown journal record kind $other for '${descriptor.componentId}' " +
                s"(manifest '${record.manifest}'); the journal was written by a newer runtime"
            )
          )

  /** The adapter manifest a record of each kind is stored under, mirrored or written here. */
  private def manifestOf(record: JournalRecord): String = record.kind match
    case JournalRecord.KindDeleted => "ankka.deleted"
    case JournalRecord.KindExpiry  => "ankka.expiry"
    case _                         => record.manifest

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
        case Journaled.Domain(e, _, attribution, forwardedFrom) =>
          JournalRecord
            .domain(descriptor.eventSerializer.manifest, descriptor.eventSerializer.toBytes(e))
            .at(self)
            .copy(attribution = attribution, forwardedFrom = forwardedFrom)
        case Journaled.Deleted          => JournalRecord.deleted.at(self)
        case Journaled.Expiry(atMillis) => JournalRecord.expiry(atMillis).at(self)
        case Journaled.Replication(p)   => p.record.at(self)
        case Journaled.Mirrored(record) => record

      def manifest(event: Journaled[E]): String = event match
        case Journaled.Domain(_, _, _, _) => descriptor.eventSerializer.manifest
        case other                        => manifestOf(toJournal(other))

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
