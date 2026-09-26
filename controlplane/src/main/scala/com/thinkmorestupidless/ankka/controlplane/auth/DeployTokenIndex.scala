package com.thinkmorestupidless.ankka.controlplane.auth

import com.thinkmorestupidless.ankka.controlplane.application.DeployTokenEntity
import com.thinkmorestupidless.ankka.controlplane.domain.DeployTokenEvent
import com.thinkmorestupidless.ankka.controlplane.domain.DeployTokenEvent.*
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{AnkkaService, JournalRecord, RuntimeExtension}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.query.{NoOffset, Offset, PersistenceQuery}
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.persistence.{Persistence, typed}
import org.apache.pekko.stream.scaladsl.{Keep, Sink}
import org.apache.pekko.stream.{KillSwitches, UniqueKillSwitch}
import org.slf4j.{Logger, LoggerFactory}

import java.time.{Clock, Instant, LocalDate}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Every live deploy token, in this node's memory, so the ACL can verify one without I/O.
 *
 * Two facts force this shape. `Acl.Authenticate` is a synchronous function on the server's own
 * dispatcher, so verifying a token may not read a row — the OIDC path is offline against a cached
 * JWKS for exactly the same reason. And the ACL runs on *whichever node took the request*, so the
 * index cannot be a `Consumer`: the platform hosts one of those as a `ShardedDaemonProcess`, on a
 * single node. Every node therefore keeps its own copy.
 *
 * Keeping it warm is two queries, and the first one is why no offset store is needed.
 * `currentEventsBySlices` is bounded and completes, so running it from the beginning replays the
 * token journal and its *completion* is the signal that this node is caught up — which is what
 * `readiness` reports, so a pod is never routed to with a cold index. The live `eventsBySlices`
 * then continues from that query's last offset. The token journal is tokens, not traffic, so the
 * replay is milliseconds.
 *
 * Revocation reaches the node that handled it immediately, because the endpoint calls `evict`
 * write-through; every other node sees the event one read-refresh interval later (500ms for the
 * control plane, set in `reference.conf` — the polling knob, not `behind-current-time`).
 */
final class DeployTokenIndex(
    clock: Clock = Clock.systemUTC(),
    lastUseInterval: FiniteDuration = 1.minute
) extends RuntimeExtension:

  import DeployTokenIndex.*

  private val log: Logger = LoggerFactory.getLogger("ankka.controlplane.deploy-tokens")

  private val live = new ConcurrentHashMap[String, Live]()

  @volatile private var caughtUp                                          = false
  @volatile private var switch: Option[UniqueKillSwitch]                  = None
  @volatile private var sweep: Option[org.apache.pekko.actor.Cancellable] = None

  def name: String = "deploy-tokens"

  /** Not ready until this node has replayed the token journal: a cold index refuses everyone. */
  override def readiness: Option[() => Boolean] = Some(() => caughtUp)

  def start(service: AnkkaService): Unit =
    given system: ActorSystem[?] = service.system
    given ExecutionContext       = system.executionContext

    val journal =
      PersistenceQuery(system).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)
    val maxSlice = Persistence(system).numberOfSlices - 1

    // The cold replay. Its completion is the caught-up signal; until then this node says it is
    // not ready rather than answering `401` to a token that is perfectly good.
    journal
      .currentEventsBySlices[JournalRecord](EntityType, 0, maxSlice, NoOffset)
      .runWith(Sink.fold(NoOffset: Offset)((_, envelope) => apply(envelope)))
      .onComplete {
        case scala.util.Success(resume) =>
          caughtUp = true
          log.info("deploy token index caught up with {} live token(s)", live.size)
          follow(journal, maxSlice, resume)
        case scala.util.Failure(failure) =>
          // Readiness stays false, so this node is taken out of rotation rather than serving
          // with an index it knows is incomplete.
          log.error("deploy token index could not replay; this node will not become ready", failure)
      }

    sweep = Some(
      system.classicSystem.scheduler.scheduleAtFixedRate(
        lastUseInterval,
        lastUseInterval
      )(() => recordUses(service.componentClient))(using system.executionContext)
    )

  override def stop(): Unit =
    switch.foreach(_.shutdown())
    sweep.foreach(_.cancel(): Unit)
    switch = None
    sweep = None
    live.clear()

  /** The live query, from where the replay finished. */
  private def follow(journal: R2dbcReadJournal, maxSlice: Int, from: Offset)(using
      system: ActorSystem[?]
  ): Unit =
    val (killSwitch, done) = journal
      .eventsBySlices[JournalRecord](EntityType, 0, maxSlice, from)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.foreach(envelope => apply(envelope): Unit))(Keep.both)
      .run()
    switch = Some(killSwitch)
    done.failed.foreach(failure => log.error("deploy token index stopped following", failure))(using
      system.executionContext
    )

  /** Folds one envelope in, and answers with its offset so the replay can resume from it. */
  private def apply(envelope: EventEnvelope[JournalRecord]): Offset =
    val id = typed.PersistenceId.extractEntityId(envelope.persistenceId)
    decode(envelope.event).foreach(event => fold(live, id, event))
    envelope.offset

  private def decode(record: JournalRecord): Option[DeployTokenEvent] =
    if record.kind != JournalRecord.KindDomain then None
    else
      try Some(DeployTokenEntity.eventSerializer.fromBytes(record.payload))
      catch
        case NonFatal(failure) =>
          // A record this node cannot read must not stop the stream: the rest of the index is
          // still correct, and a token whose creation could not be read is simply unknown — a
          // `401`, which is the safe direction to fail in.
          log.warn("skipping an unreadable deploy token event", failure)
          None

  // ── what the ACL asks ─────────────────────────────────────────────────────

  /** The token, if this node knows it and it has not expired. */
  def lookup(id: String): Option[Live] =
    Option(live.get(id)).filterNot(_.expiredAt(clock.instant()))

  /** Whether this node has replayed the journal. The ACL answers `503` until it has. */
  def ready: Boolean = caughtUp

  /** Records that the token was used today, in memory only. Called on every admitted request. */
  def touch(id: String): Unit =
    val today = LocalDate.now(clock)
    Option(live.get(id)).foreach { entry =>
      entry.touched.updateAndGet(current =>
        if current.exists(!_.isBefore(today)) then current else Some(today)
      ): Unit
    }

  /**
   * Knows a token now, without waiting for its creation to come back through the journal.
   *
   * The endpoint that created it calls this. Without it, a token is unusable on its own creating
   * node until the next read-refresh — which breaks the obvious script: create a token, then use
   * it. Found by the HTTP suite doing exactly that.
   *
   * Other nodes learn from the event, as they do for a revocation. The asymmetry that matters is
   * the safe one: a node that has not yet heard of a token refuses it.
   */
  def admit(
      id: String,
      digest: String,
      organizationId: String,
      label: String,
      expiresAt: Option[Instant]
  ): Unit =
    live.put(id, Live(digest, organizationId, label, expiresAt)): Unit

  /**
   * Forgets a token now, without waiting for its revocation to come back through the journal.
   *
   * The endpoint that revoked calls this, which is what makes "revoke, then the next call is
   * refused" true on the node a CLI is talking to. Other nodes learn from the event.
   */
  def evict(id: String): Unit = live.remove(id): Unit

  /** For tests and for the benchmark: an index with known contents and no database behind it. */
  private[ankka] def put(id: String, entry: Live): Unit = live.put(id, entry): Unit

  private[ankka] def size: Int = live.size

  private[ankka] def markCaughtUp(): Unit = caughtUp = true

  // ── the last-use writer ───────────────────────────────────────────────────

  /**
   * Persists a later use date for every token used since the last sweep.
   *
   * Off the request thread, at most once per token per day: the entity refuses a date that is not
   * later than the one it holds, so several nodes reporting the same day produce one event between
   * them. A failure here is logged and forgotten — a missing last-use date is a cosmetic loss, and
   * retrying it is what the next sweep is.
   */
  private[ankka] def recordUses(client: ComponentClient): Unit =
    live.asScala.foreach { (id, entry) =>
      val touched = entry.touched.get()
      if touched.exists(day => entry.persistedLastUsed.forall(_.isBefore(day))) then
        val day = touched.get
        try
          client
            .forEventSourcedEntity(EntityId(id))
            .call(DeployTokenEntity.recordUse)
            .invoke(day): Unit
          // Not written back into the map: the event comes round through the stream and updates
          // `persistedLastUsed` there, which is the one place it is decided.
          ()
        catch
          case NonFatal(failure) =>
            log.debug(s"could not record use of deploy token $id", failure)
    }

object DeployTokenIndex:

  /** The persistence entity type the token journal is written under. */
  val EntityType: String = DeployTokenEntity.componentId

  /** One live token, as a node's ACL needs it. */
  final case class Live(
      digest: String,
      organizationId: String,
      label: String,
      expiresAt: Option[Instant],
      persistedLastUsed: Option[LocalDate] = None,
      /** This node's own record of use, never read by anything but the sweep. */
      touched: AtomicReference[Option[LocalDate]] = new AtomicReference(None)
  ):
    def expiredAt(now: Instant): Boolean = expiresAt.exists(!_.isAfter(now))

  /**
   * The fold, as a function over the map, so a test can drive it without a database.
   *
   * A revoked token is *removed* rather than flagged: there is no state in which the ACL should
   * find a revoked token and think about it.
   */
  def fold(into: ConcurrentHashMap[String, Live], id: String, event: DeployTokenEvent): Unit =
    event match
      case DeployTokenCreated(organizationId, label, digest, expiresAt, _, _) =>
        into.put(id, Live(digest, organizationId, label, expiresAt)): Unit
      case DeployTokenUsed(date) =>
        Option(into.get(id)).foreach(entry =>
          into.put(id, entry.copy(persistedLastUsed = Some(date))): Unit
        )
      case _: DeployTokenRevoked => into.remove(id): Unit
