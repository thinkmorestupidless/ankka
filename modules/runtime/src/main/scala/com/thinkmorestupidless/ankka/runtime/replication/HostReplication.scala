package com.thinkmorestupidless.ankka.runtime.replication

import com.thinkmorestupidless.ankka.core.effect.ReplicationChange
import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  EntityId,
  ErrorCode,
  MethodName
}
import com.thinkmorestupidless.ankka.runtime.{
  CallOrigin,
  Database,
  EntityProtocol,
  JournalRecord,
  MetaEntry,
  Observability,
  SpanKind,
  SpanOutcome,
  SqlFragment,
  Trace,
  Unanswered
}
import com.thinkmorestupidless.ankka.sdk.ReplicationStatus
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.stream.scaladsl.{Keep, RestartSource, Sink}
import org.apache.pekko.stream.{KillSwitches, Materializer, RestartSettings, UniqueKillSwitch}
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * What a replicated copy's state says about where the entity lives, whichever host folds it
 * (feature 051): the in-process host's and a process's remote host's both carry these four, folded
 * from the same records, so the rules below hold for both.
 */
trait CopyState:
  def home: Option[String]
  def replicationSet: Set[String]
  def pendingHandover: Option[String]
  def conflict: Option[String]

/** A replicated entity's platform records, as both hosts write and fold them. */
enum Platform:
  case Home(installation: String)
  case ReplicationSet(installations: Set[String])
  case Handover(target: String)
  case HandoverAbandoned(target: String)

  /** `installation` is who found the conflict, as read back; `None` for one being written here. */
  case Conflict(other: String, installation: Option[String] = None)

  /** The record, unstamped: the host stamps what it writes with its installation. */
  def record: JournalRecord = this match
    case Home(i)              => JournalRecord.home(i)
    case ReplicationSet(set)  => JournalRecord.replicationSet(set)
    case Handover(t)          => JournalRecord.handover(t)
    case HandoverAbandoned(t) => JournalRecord.handoverAbandoned(t)
    case Conflict(o, _)       => JournalRecord.conflict(o)

object Platform:
  /** The platform record `record` is, or `None` for a domain event, a deletion or an expiry. */
  def of(record: JournalRecord): Option[Platform] = record.kind match
    case JournalRecord.KindHome              => Some(Home(record.named))
    case JournalRecord.KindReplicationSet    => Some(ReplicationSet(record.names))
    case JournalRecord.KindHandover          => Some(Handover(record.named))
    case JournalRecord.KindHandoverAbandoned => Some(HandoverAbandoned(record.named))
    case JournalRecord.KindConflict          => Some(Conflict(record.named, record.installation))
    case _                                   => None

/** What locate found for a copy that holds no record saying where the entity lives. */
private[ankka] enum Where:
  /** Another installation holds it, as `located` says. */
  case At(located: Located)

  /**
   * No installation that could be reached holds it. `writer` is who wrote the copy's own first
   * record when that was another installation: a copy part way through mirroring a home it cannot
   * now reach.
   */
  case Nobody(writer: Option[String])

/**
 * One running copy of a replicated entity, whichever host runs it: what it keeps in memory beside
 * its journal (its puller, where locate said the entity lives, which installations' histories it
 * has compared with its own), and the rules a host applies to a command, a mirrored record and a
 * forwarded call. Touched only on the actor's thread, but for `position`, which its puller reads.
 */
private[ankka] final class HostReplication(
    val rep: Replication,
    componentId: ComponentId,
    entityId: EntityId,
    ctx: ActorContext[EntityProtocol.Command]
):
  import HostReplication.*

  val me: String = rep.self

  /** The last sequence number this copy holds, read by the puller when it opens again. */
  @volatile var position: Long = 0L

  private var puller: Option[(String, UniqueKillSwitch)] = None
  var where: Option[Where]                               = None
  var locating: Boolean                                  = false
  var checked: Set[String]                               = Set.empty

  private val system: ActorSystem[?] = ctx.system
  private val self                   = ctx.self

  // ── where the copy stands ─────────────────────────────────────────────────

  /**
   * The entity as locate found it, while this copy is catching up to a history that includes it: a
   * copy added to the replication set mirrors from the first record, through sets and homes of the
   * past that may not name it, and follows the home locate named until its own journal says the
   * same.
   */
  private def joining: Option[Located] =
    where.collect { case Where.At(l) if l.home != me && l.replicationSet.contains(me) => l }

  /** Where the copy stands by its own journal alone. */
  private def journalMode(state: CopyState): Mode =
    // A home with no set known is a first pull cut short: where the entity lives is asked again.
    if state.home.nonEmpty && state.replicationSet.isEmpty then Mode.Unknown
    else Mode.of(me, state.home, state.replicationSet)

  private def caughtUp(state: CopyState, located: Located): Boolean =
    journalMode(state) == Mode.Follower && state.home.contains(located.home)

  def mode(state: CopyState): Mode =
    joining match
      case Some(l) if !caughtUp(state, l) => Mode.Follower
      case _                              => journalMode(state)

  /** The home, as the journal says it, or as locate said it while the copy catches up. */
  def home(state: CopyState): Option[String] =
    joining match
      case Some(l) if !caughtUp(state, l) => Some(l.home)
      case _                              => state.home

  /** The installation this copy should be mirroring, if any. */
  private def source(state: CopyState): Option[String] =
    if state.conflict.nonEmpty then None
    else if mode(state) == Mode.Follower then home(state)
    else None

  // ── the puller ────────────────────────────────────────────────────────────

  /** Starts, switches or stops the puller to match what the journal now says. */
  def reconcile(state: CopyState, lastSequenceNr: Long): Unit =
    position = lastSequenceNr
    // Caught up: from here the copy's own journal says where it stands.
    if joining.exists(caughtUp(state, _)) then where = None
    val wanted = source(state)
    if puller.map(_._1) != wanted then
      puller.foreach(_._2.shutdown())
      puller = wanted.map(home => home -> pull(home))

  def recovered(state: CopyState, lastSequenceNr: Long): Unit =
    reconcile(state, lastSequenceNr)
    reportIfTarget(state, lastSequenceNr)

  def stopped(): Unit =
    puller.foreach(_._2.shutdown())
    puller = None

  /** The target of a pending handover tells the home how much it holds. */
  def reportIfTarget(state: CopyState, lastSequenceNr: Long): Unit =
    if state.pendingHandover.contains(me) then
      state.home.foreach(home =>
        rep.transport.report(home, componentId, entityId, lastSequenceNr): Unit
      )

  /**
   * The home's records from the one after the last this copy holds, each handed to the copy to
   * persist as its next and acknowledged before the next is asked for. Opened again from the copy's
   * own position after any end: a refused record, a transport that failed, a source that completed.
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

  // ── a mirrored record ─────────────────────────────────────────────────────

  /**
   * The record a follower's puller handed it, as the copy is to persist it, or why it refuses: not
   * the next record, or not written by the entity's home. A record written before replication is
   * stamped with the installation it was pulled from; nothing else in it changes.
   */
  def mirror(
      state: CopyState,
      replicate: EntityProtocol.Replicate,
      lastSeq: Long
  ): Either[String, JournalRecord] =
    val writer   = replicate.record.installation.getOrElse(replicate.source)
    val expected = state.home.getOrElse(replicate.source)
    if mode(state) == Mode.Home then Left("this installation is the home")
    else if state.conflict.nonEmpty then Left("this copy is in conflict")
    else if replicate.sequenceNr != lastSeq + 1 then
      Left(s"not the next record: this copy holds $lastSeq")
    else if writer != expected then Left(s"written by '$writer', and the home is '$expected'")
    else if replicate.record.installation.isEmpty then
      Right(replicate.record.at(Some(replicate.source)))
    else Right(replicate.record)

  def refuseMirror(replicate: EntityProtocol.Replicate, reason: String): Unit =
    ctx.log.warn(
      "refused a record of {} '{}' at {}: {}",
      componentId,
      entityId,
      replicate.sequenceNr,
      reason
    )
    replicate.ack ! EntityProtocol.ReplicateAck.Refused(reason)

  // ── what a home writes beside a handler's events ──────────────────────────

  /**
   * The records a home writes beside a handler's events: for a creation, that this installation is
   * the home and the replication set it starts with (itself, and whoever the handler added); for a
   * handover, that it is pending; for a change to the set, the whole set after it. `before` goes
   * ahead of the handler's events and `after` behind them.
   */
  def records(
      state: CopyState,
      change: Option[ReplicationChange],
      creating: Boolean
  ): Either[CommandError, (Vector[Platform], Vector[Platform])] =
    val known = rep.settings.installations + me
    def unknown(names: Set[String]) =
      (names -- known).toVector.sorted.headOption.map { name =>
        CommandError(
          s"'$name' is not an installation this service names; it names " +
            known.toVector.sorted.mkString(", "),
          ErrorCode.BadRequest
        )
      }
    val current = if creating then Set(me) else state.replicationSet
    val created =
      (set: Set[String]) =>
        if creating then Vector(Platform.Home(me), Platform.ReplicationSet(set)) else Vector.empty
    change match
      case None => Right((created(current), Vector.empty))
      case Some(ReplicationChange.SetChange(add, remove)) =>
        unknown(add ++ remove) match
          case Some(error) => Left(error)
          case None if remove.contains(me) =>
            Left(
              CommandError(
                s"'$me' is the home of '$entityId' and cannot leave its replication set; hand the " +
                  "home over first",
                ErrorCode.BadRequest
              )
            )
          case None =>
            val next = current ++ add -- remove
            if creating then Right((created(next), Vector.empty))
            else Right((Vector.empty, Vector(Platform.ReplicationSet(next))))
      case Some(ReplicationChange.HandOver(target)) =>
        val refusal =
          if target == me then Some(s"'$target' is already the home of '$entityId'")
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
          case None          => Right((created(current), Vector(Platform.Handover(target))))

  // ── answers a copy gives without a handler ────────────────────────────────

  def notHome(home: String, outside: Boolean): CommandError =
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

  def inConflict(state: CopyState): CommandError =
    CommandError(
      s"'$entityId' is in conflict with '${state.conflict.getOrElse("")}': it was created at two " +
        "installations, and takes no write until an operator resolves it",
      ErrorCode.Conflict
    )

  /** The answer to the reserved status query: how this copy stands, from its own journal. */
  def status(state: CopyState, lastSeq: Long): EntityProtocol.Reply =
    EntityProtocol.Succeeded(
      ReplicationStatus.serializer.toBytes(
        ReplicationStatus(
          state.home,
          state.replicationSet,
          state.pendingHandover,
          state.conflict,
          lastSeq
        )
      ),
      Vector(MetaEntry(HandledAt, me))
    )

  /** The answer to the reserved handover query for the installation `payload` names. */
  def handoverStatus(state: CopyState, payload: Array[Byte]): EntityProtocol.Reply =
    val target = String(payload, java.nio.charset.StandardCharsets.UTF_8)
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

  // ── forwarding ────────────────────────────────────────────────────────────

  /**
   * Hands a command that may persist to the home; its answer comes back as `Forwarded` on the
   * copy's own thread. One call in the trace: a client span here, under the caller's, which the
   * home's handler span is a child of. Counted where it is handled, at the home; here only when it
   * went unanswered. A forwarded call is never forwarded again, and never sent twice. `Left` is a
   * refusal given at once, with nothing sent.
   */
  def forward(
      invoke: EntityProtocol.Invoke,
      home: String,
      observability: Observability,
      componentRef: Int
  ): Either[CommandError, Unit] =
    val metadata = MetaEntry.toMetadata(invoke.metadata)
    if metadata.get(ForwardedFrom).nonEmpty then
      Left(
        CommandError(
          s"'$me' is not the home of '$entityId' either; a write forwarded once is not forwarded " +
            "again",
          ErrorCode.NotHome
        )
      )
    else if !rep.transport.reachable(home) then Left(notHome(home, outside = false))
    else
      val inbound = Trace.inbound(metadata)
      val span = observability.recorder.reserve(
        inbound.traceIdHigh,
        inbound.traceId,
        componentRef,
        observability.names.intern(invoke.method)
      )
      val carried = Trace.into(metadata, span.context).set(ForwardedFrom, me)
      val call    = ForwardedCall(MethodName(invoke.method), invoke.payload, carried)
      val origin  = CallOrigin.from(metadata)
      val method  = invoke.method
      ctx.pipeToSelf(rep.transport.forward(home, componentId, entityId, call)) { result =>
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
      Right(())

  /**
   * Whether a call another installation forwarded is admitted here (spec 057): only from an
   * installation this one names among those it replicates with. `None` admits it, and admits every
   * call nobody forwarded; the refusal is given before any handler runs, and the forwarder returns
   * it to its caller unchanged.
   */
  def admission(invoke: EntityProtocol.Invoke): Option[CommandError] =
    MetaEntry
      .toMetadata(invoke.metadata)
      .get(ForwardedFrom)
      .filterNot(rep.settings.installations.contains)
      .map(from =>
        CommandError(s"installation '$from' is not admitted at '$me'", ErrorCode.Forbidden)
      )

  /** The caller's answer to a forwarded call, as the home gave it or as its failure stands. */
  def forwarded(forwarded: EntityProtocol.Forwarded): EntityProtocol.Reply =
    forwarded.result match
      case Success(ForwardedReply.Succeeded(bytes, metadata)) =>
        EntityProtocol.Succeeded(bytes, MetaEntry.from(metadata))
      case Success(ForwardedReply.Rejected(error)) => EntityProtocol.Rejected(error)
      case Failure(_: java.util.concurrent.TimeoutException) =>
        EntityProtocol.Rejected(
          CommandError(
            s"the home of '$entityId', '${forwarded.home}', did not answer in time; the write may " +
              "or may not have happened",
            ErrorCode.Timeout
          )
        )
      case Failure(_) => EntityProtocol.Rejected(notHome(forwarded.home, outside = false))

  // ── where an entity lives ─────────────────────────────────────────────────

  /** Asks where the entity lives; the answer comes back as `Located` on the copy's own thread. */
  def locate(lastSeq: Long): Unit =
    locating = true
    given ExecutionContext = ExecutionContext.parasitic
    val writer =
      if lastSeq == 0 then Future.successful(None)
      else firstRecord().map(_.flatMap(_.installation).filter(_ != me)).recover { case _ => None }
    val asked = rep.transport.locate(componentId, entityId)
    ctx.pipeToSelf(writer.zip(asked.transform(Success(_)))) {
      case Success((first, located)) => EntityProtocol.Located(located, first)
      case Failure(e)                => EntityProtocol.Located(Failure(e), None)
    }

  /** Records what locate found. */
  def located(located: EntityProtocol.Located): Unit =
    locating = false
    where = Some(located.result match
      case Success(Some(l)) if l.home != me => Where.At(l)
      case Success(Some(_))                 => Where.Nobody(None)
      case _                                => Where.Nobody(located.firstWriter))

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

  /**
   * Whether a wake from `from` calls for comparing histories: this copy is the home, and another
   * installation wrote the entity. Either it was the home before this one, and its history is this
   * one's, or it created the entity too. Each installation is compared once per running copy.
   */
  def shouldCompare(state: CopyState, from: String): Boolean =
    mode(state) == Mode.Home && from != me && !checked(from)

  /**
   * Compares the first record of `other`'s history with this copy's; answered as `ConflictChecked`.
   */
  def compare(other: String): Unit =
    checked += other
    given Materializer     = Materializer(system)
    given ExecutionContext = system.executionContext
    val theirs = rep.transport
      .records(other, componentId, entityId, 1L)
      .take(1)
      .completionTimeout(10.seconds)
      .runWith(Sink.headOption)
    val differs = firstRecord().zip(theirs).map {
      case (Some(mine), Some(first)) =>
        fingerprint(mine, me) != fingerprint(first.record, other)
      case _ => false
    }
    ctx.pipeToSelf(differs)(EntityProtocol.ConflictChecked(other, _))

  /** Whether `checked` found another history; a failure is compared again at the next wake. */
  def conflictFound(checked: EntityProtocol.ConflictChecked, state: CopyState): Boolean =
    checked.differs match
      case Success(true) if state.conflict.isEmpty =>
        ctx.log.warn(
          "{} '{}' was created at '{}' too: marked in conflict",
          componentId,
          entityId,
          checked.other
        )
        true
      case Success(_) => false
      case Failure(_) =>
        this.checked -= checked.other
        false

  def logAbandoned(target: String): Unit =
    ctx.log.warn(
      "the handover of {} '{}' to '{}' was abandoned after {}",
      componentId,
      entityId,
      target,
      rep.settings.handoverTimeout
    )

object HostReplication:

  /** A copy's reply metadata names the installation whose handler ran. */
  val HandledAt: String = "ankka.handled-at"

  /** A forwarded call names the installation that forwarded it, and is never forwarded again. */
  val ForwardedFrom: String = "ankka.forwarded-from"

  /** The timer a held handover waits on. */
  val HandoverTimer: String = "ankka-handover"

  /** One history's first record, as two installations holding the same history both hold it. */
  private def fingerprint(record: JournalRecord, holder: String): (Int, String, Seq[Byte], String) =
    (record.kind, record.manifest, record.payload.toSeq, record.installation.getOrElse(holder))

  /** Whether `method` is one of the reserved methods every replicated copy answers itself. */
  def reserved(method: MethodName): Boolean =
    method == ReplicationStatus.Method || method == ReplicationStatus.HandoverMethod
