package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.ClientKeySummary
import com.thinkmorestupidless.ankka.mcp.ClientKeyEvent.*
import com.thinkmorestupidless.ankka.runtime.{AnkkaService, JournalRecord}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.query.{NoOffset, Offset, PersistenceQuery}
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.persistence.{Persistence, typed}
import org.apache.pekko.stream.scaladsl.{Keep, Sink}
import org.apache.pekko.stream.{KillSwitches, UniqueKillSwitch}
import org.slf4j.{Logger, LoggerFactory}

import java.time.{Clock, Instant}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Every client key of the service, in this instance's memory, so an ACL can admit one without I/O.
 *
 * The shape is the control plane's deploy token index, for its reasons: `Acl.Authenticate` decides
 * synchronously on whichever instance took the request, so that instance must already know the key.
 * A cold replay (`currentEventsBySlices`) whose completion is readiness, then a live follow from
 * where it ended, through `ankka.mcp.client-keys.query`, the runtime's query plugin polling every
 * 500ms (spike S1 measured 505 to 662 ms for a write to reach another node), so that a revoked key
 * is refused everywhere within a second without every projection of the service polling that often.
 *
 * Issue and revoke write through on the instance that made them (`admit`, `evict`); a revoked id is
 * remembered, so a late delivery of its issue cannot bring it back. Revoked keys stay listed.
 */
final class ClientKeyIndex(clock: Clock):

  import ClientKeyIndex.*

  private val log: Logger = LoggerFactory.getLogger("ankka.mcp.client-keys")

  private val keys    = new ConcurrentHashMap[String, Live]()
  private val revoked = new ConcurrentHashMap[String, Instant]()

  @volatile private var caughtUp                                          = false
  @volatile private var switch: Option[UniqueKillSwitch]                  = None
  @volatile private var sweep: Option[org.apache.pekko.actor.Cancellable] = None

  def start(service: AnkkaService, lastUseInterval: FiniteDuration): Unit =
    given system: ActorSystem[?] = service.system
    given ExecutionContext       = system.executionContext

    val journal  = PersistenceQuery(system).readJournalFor[R2dbcReadJournal](QueryPlugin)
    val maxSlice = Persistence(system).numberOfSlices - 1

    journal
      .currentEventsBySlices[JournalRecord](EntityType, 0, maxSlice, NoOffset)
      .runWith(Sink.fold(NoOffset: Offset)((_, envelope) => apply(envelope)))
      .onComplete {
        case scala.util.Success(resume) =>
          caughtUp = true
          log.info("client key index caught up with {} key(s)", keys.size)
          follow(journal, maxSlice, resume)
        case scala.util.Failure(failure) =>
          log.error(
            "client key index could not replay; this instance will not become ready",
            failure
          )
      }

    val client = service.componentClient
    sweep = Some(
      system.classicSystem.scheduler.scheduleAtFixedRate(lastUseInterval, lastUseInterval)(() =>
        recordUses(lastUseInterval)((id, at) => send(client, id, at))
      )(using system.executionContext)
    )

  def stop(): Unit =
    switch.foreach(_.shutdown())
    sweep.foreach(_.cancel(): Unit)
    switch = None
    sweep = None
    caughtUp = false
    keys.clear()
    revoked.clear()

  private def follow(journal: R2dbcReadJournal, maxSlice: Int, from: Offset)(using
      system: ActorSystem[?]
  ): Unit =
    val (killSwitch, done) = journal
      .eventsBySlices[JournalRecord](EntityType, 0, maxSlice, from)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.foreach(envelope => apply(envelope): Unit))(Keep.both)
      .run()
    switch = Some(killSwitch)
    done.failed.foreach(failure => log.error("client key index stopped following", failure))(using
      system.executionContext
    )

  private def apply(envelope: EventEnvelope[JournalRecord]): Offset =
    val id = typed.PersistenceId.extractEntityId(envelope.persistenceId)
    decode(envelope.event).foreach(applyEvent(id, _))
    envelope.offset

  private def decode(record: JournalRecord): Option[ClientKeyEvent] =
    if record.kind != JournalRecord.KindDomain then None
    else
      try Some(ClientKeyEntity.eventSerializer.fromBytes(record.payload))
      catch
        case NonFatal(failure) =>
          // A key whose issue cannot be read is unknown, and refused: the safe direction.
          log.warn("skipping an unreadable client key event", failure)
          None

  // ── what the ACL asks ─────────────────────────────────────────────────────

  /**
   * Whether this instance has replayed the journal. Until it has, a key is neither admitted nor
   * refused.
   */
  def ready: Boolean = caughtUp

  def lookup(id: String): Lookup =
    Option(keys.get(id)) match
      case None                                        => Lookup.Missing
      case Some(_) if revoked.containsKey(id)          => Lookup.Missing
      case Some(key) if key.expiredAt(clock.instant()) => Lookup.Expired(key)
      case Some(key)                                   => Lookup.Usable(key)

  /** Records a use in memory only; the sweep writes it down. Called on every admitted request. */
  def touch(id: String): Unit =
    val now = clock.instant()
    Option(keys.get(id)).foreach(
      _.touched.updateAndGet(current =>
        Some(current.fold(now)(c => if c.isAfter(now) then c else now))
      ): Unit
    )

  /** Knows a key now, on the instance that issued it, without waiting for the journal. */
  def admit(id: String, key: Live): Unit =
    if !revoked.containsKey(id) then keys.putIfAbsent(id, key): Unit

  /** Forgets a key now, on the instance that revoked it. */
  def evict(id: String, at: Instant): Unit = revoked.putIfAbsent(id, at): Unit

  /** Every key, revoked ones with when, never a digest. */
  def summaries: Vector[ClientKeySummary] =
    keys.asScala.toVector
      .map((id, key) => key.summary(id, Option(revoked.get(id))))
      .sortBy(s => (s.issuedAt, s.id))

  def summary(id: String): Option[ClientKeySummary] =
    Option(keys.get(id)).map(_.summary(id, Option(revoked.get(id))))

  private[mcp] def applyEvent(id: String, event: ClientKeyEvent): Unit =
    event match
      case ClientKeyIssued(
            digest,
            subject,
            attributes,
            label,
            endpoint,
            scope,
            issuedAt,
            expiresAt
          ) =>
        keys.putIfAbsent(
          id,
          Live(digest, subject, attributes, label, endpoint, scope.toSet, issuedAt, expiresAt)
        ): Unit
      case ClientKeyUsed(at) =>
        keys.computeIfPresent(id, (_, key) => key.copy(persistedLastUsed = Some(at))): Unit
      case ClientKeyRevoked(at) => revoked.putIfAbsent(id, at): Unit

  private[mcp] def markCaughtUp(): Unit = caughtUp = true

  // ── the last-use writer ───────────────────────────────────────────────────

  /**
   * Sends the latest use of every key used since it was last written down or sent, at most once an
   * interval per key. Off the request thread; a failure is the next sweep's to retry.
   */
  private[mcp] def recordUses(interval: FiniteDuration)(send: (String, Instant) => Unit): Unit =
    val gap = java.time.Duration.ofMillis(interval.toMillis)
    keys.asScala.foreach { (id, key) =>
      key.touched.get().foreach { used =>
        val written = Seq(key.persistedLastUsed, key.sent.get()).flatten.maxOption
        if !revoked.containsKey(id) && written.forall(w => !used.isBefore(w.plus(gap))) then
          try
            send(id, used)
            key.sent.set(Some(used))
          catch
            case NonFatal(failure) =>
              log.debug(s"could not record the use of client key $id", failure)
      }
    }

  private def send(client: ComponentClient, id: String, at: Instant): Unit =
    client
      .forEventSourcedEntity(EntityId(id))
      .call(ClientKeyEntity.recordUse)
      .invoke(RecordClientKeyUse(at)): Unit

object ClientKeyIndex:

  val EntityType: String  = ClientKeyEntity.componentId.toString
  val QueryPlugin: String = "ankka.mcp.client-keys.query"

  enum Lookup:
    case Missing
    case Expired(key: Live)
    case Usable(key: Live)

  /** One key, as an instance's ACL needs it. */
  final case class Live(
      digest: String,
      subject: String,
      attributes: Map[String, String],
      label: Option[String],
      endpoint: String,
      scope: Set[String],
      issuedAt: Instant,
      expiresAt: Option[Instant],
      persistedLastUsed: Option[Instant] = None,
      /** This instance's own record of use, read only by the sweep and a listing. */
      touched: AtomicReference[Option[Instant]] = new AtomicReference(None),
      /** The use this instance last sent, so a sweep does not send it twice. */
      sent: AtomicReference[Option[Instant]] = new AtomicReference(None)
  ):
    def expiredAt(now: Instant): Boolean = expiresAt.exists(!_.isAfter(now))

    def summary(id: String, revokedAt: Option[Instant]): ClientKeySummary =
      ClientKeySummary(
        id,
        subject,
        attributes,
        label,
        endpoint,
        scope,
        issuedAt,
        expiresAt,
        Seq(persistedLastUsed, touched.get()).flatten.maxOption,
        revokedAt
      )

    override def toString: String = s"Live($subject, $endpoint, <digest>)"
