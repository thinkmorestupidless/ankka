package com.thinkmorestupidless.ankka.runtime.replication

import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  EntityKeys,
  EntityProtocol,
  JournalRecord,
  MetaEntry,
  SqlFragment
}
import io.r2dbc.pool.{ConnectionPool, ConnectionPoolConfiguration}
import io.r2dbc.postgresql.{PostgresqlConnectionConfiguration, PostgresqlConnectionFactory}
import io.r2dbc.spi.Row
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.pattern.after
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.serialization.SerializationExtension
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout
import org.apache.pekko.{Done, NotUsed}

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
 * The runtime's own replication transport: another installation's journal read straight from its
 * database, and a forwarded call or a report handed to a sibling started in this JVM, or over
 * loopback to one running beside it on this machine (`LoopbackReplication`).
 *
 * Enough for the test kit and for two services on one machine, and not a transport between
 * installations: it reads another installation's database, which nothing across a network should be
 * able to. Each sibling names its database in `ankka.replication.installations`, and is read
 * through a pool of its own of at most four connections.
 */
final class DatabaseReplicationTransport(
    settings: ReplicationSettings,
    /** The replicated components this service hosts: the wake feed reads only their records. */
    components: Set[ComponentId],
    /** Reaches a sibling that is not in this JVM; by default, none can be. */
    loopback: LoopbackReplication = LoopbackReplication.none,
    /** Whether a sibling started in this JVM is reached through its sharding directly. */
    inJvm: Boolean = true
)(using system: ActorSystem[?])
    extends ReplicationTransport:

  private given ExecutionContext = system.executionContext

  val name: String = "the runtime's own database transport"

  private val self: String = settings.installation.getOrElse("")

  private val serialization = SerializationExtension(system.classicSystem)

  private val pools: Map[String, ConnectionPool] = settings.databases.map { (installation, db) =>
    val factory = PostgresqlConnectionFactory(
      PostgresqlConnectionConfiguration
        .builder()
        .host(db.host)
        .port(db.port)
        .database(db.name)
        .username(db.user)
        .password(db.password)
        .build()
    )
    installation -> ConnectionPool(
      ConnectionPoolConfiguration
        .builder(factory)
        .initialSize(0)
        .maxSize(4)
        .validationQuery("SELECT 1")
        .build()
    )
  }

  private val databases: Map[String, Database] =
    pools.map((installation, pool) => installation -> new Database(pool))

  /** The installations the last attempt to reach failed. */
  private val unreachable = ConcurrentHashMap.newKeySet[String]()

  private def noted[A](installation: String)(attempt: Future[A]): Future[A] =
    attempt.andThen {
      case Success(_) => unreachable.remove(installation): Unit
      case Failure(_) => unreachable.add(installation): Unit
    }(using ExecutionContext.parasitic)

  def reachable(installation: String): Boolean = !unreachable.contains(installation)

  private def database(installation: String): Future[Database] =
    databases.get(installation) match
      case Some(db) => Future.successful(db)
      case None =>
        Future.failed(
          IllegalStateException(
            s"installation '$installation' names no database the runtime's own transport can read"
          )
        )

  private def pid(componentId: ComponentId, entityId: EntityId): String =
    PersistenceId(componentId, entityId).id

  private def decode(row: Row): Replicated =
    val record = serialization
      .deserialize(
        row.get("event_payload", classOf[Array[Byte]]),
        row.get("event_ser_id", classOf[java.lang.Integer]).intValue,
        row.get("event_ser_manifest", classOf[String])
      )
      .get
      .asInstanceOf[JournalRecord]
    Replicated(row.get("seq_nr", classOf[java.lang.Long]).longValue, record)

  private val Batch = 200

  /** Up to `Batch` records of `pid` at `installation` from `from`, in order. */
  private def batch(installation: String, pid: String, from: Long): Future[Vector[Replicated]] =
    noted(installation) {
      database(installation).flatMap(
        _.query(
          SqlFragment.raw(
            "SELECT seq_nr, event_ser_id, event_ser_manifest, event_payload FROM event_journal " +
              s"WHERE persistence_id = '${pid.replace("'", "''")}' AND seq_nr >= $from " +
              s"AND deleted = false ORDER BY seq_nr LIMIT $Batch"
          )
        )(decode)
      )
    }

  def records(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      from: Long
  ): Source[Replicated, NotUsed] =
    val id = pid(componentId, entityId)
    Source
      .unfoldAsync(from.max(1L)) { next =>
        batch(installation, id, next).flatMap { rows =>
          if rows.isEmpty then
            after(settings.poll)(Future.successful(Some((next, Vector.empty[Replicated]))))(using
              system.classicSystem
            )
          else Future.successful(Some((rows.last.sequenceNr + 1, rows)))
        }
      }
      .mapConcat(identity)

  def locate(componentId: ComponentId, entityId: EntityId): Future[Option[Located]] =
    locate(componentId, entityId, settings.installations)

  /** `locate`, asking only `among`. */
  def locate(
      componentId: ComponentId,
      entityId: EntityId,
      among: Set[String]
  ): Future[Option[Located]] =
    val id = pid(componentId, entityId)
    // Every sibling that answers, in turn: one whose own journal says it is the home is believed
    // first, since a copy outside the set may hold an old home; a journal from before replication
    // makes the installation holding it the home.
    def read(installation: String): Future[Option[Located]] =
      def all(from: Long, acc: Vector[Replicated]): Future[Vector[Replicated]] =
        batch(installation, id, from).flatMap { rows =>
          if rows.size < Batch then Future.successful(acc ++ rows)
          else all(rows.last.sequenceNr + 1, acc ++ rows)
        }
      all(1L, Vector.empty).map { held =>
        if held.isEmpty then None
        else
          val records = held.map(_.record)
          // A journal with no home record is one from before replication: its home is whoever
          // wrote its first record, which a copy mirroring it names and the holder does not.
          val home = records.reverseIterator
            .collectFirst { case r if r.kind == JournalRecord.KindHome => r.named }
            .orElse(records.head.installation)
            .getOrElse(installation)
          val set = records.reverseIterator
            .collectFirst { case r if r.kind == JournalRecord.KindReplicationSet => r.names }
            .getOrElse(Set(installation))
          Some(Located(home, set))
      }
    val answers = among.intersect(settings.installations).toVector.sorted.map { installation =>
      read(installation).map(installation -> _).recover { case NonFatal(_) => installation -> None }
    }
    Future.sequence(answers).map { found =>
      val held = found.collect { case (installation, Some(located)) => installation -> located }
      held
        .collectFirst { case (installation, located) if located.home == installation => located }
        .orElse(held.headOption.map(_._2))
    }

  def forward(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      call: ForwardedCall
  ): Future[ForwardedReply] =
    noted(installation) {
      LocalInstallations.lookup(installation).filter(_ => inJvm) match
        case Some(local) =>
          ClusterSharding(local.system)
            .entityRefFor(EntityKeys.forComponent(componentId), entityId)
            .ask[EntityProtocol.Reply](replyTo =>
              EntityProtocol.Invoke(
                call.method,
                call.payload,
                MetaEntry.from(call.metadata),
                replyTo
              )
            )(using Timeout(local.askTimeout))
            .map(DatabaseReplicationTransport.toForwarded)
        case None => loopback.forward(installation, componentId, entityId, call)
    }

  def report(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      position: Long
  ): Future[Done] =
    noted(installation) {
      LocalInstallations.lookup(installation).filter(_ => inJvm) match
        case Some(local) =>
          ReplicationPeer.reported(local.system, componentId, entityId, self, position)
        case None => loopback.report(installation, componentId, entityId, self, position)
    }

  /**
   * Each sibling's journal, polled for records written there of the replicated components this
   * service hosts, newest first seen. Best effort: a poll looks back a little past the last record
   * it saw, since a commit can land behind one already read, and remembers what it has emitted.
   */
  def advanced: Source[Advanced, NotUsed] =
    if components.isEmpty || databases.isEmpty then Source.empty
    else
      val types = components.toVector.map(c => s"'${c.toString.replace("'", "''")}'")
      Source(databases.toVector.sortBy(_._1)).flatMapMerge(
        databases.size.max(1),
        (installation, db) => advancedAt(installation, db, types)
      )

  private val LookBack = 5.seconds

  private def advancedAt(
      installation: String,
      db: Database,
      types: Vector[String]
  ): Source[Advanced, NotUsed] =
    final case class Seen(since: Instant, emitted: Set[(String, Long)])
    def poll(seen: Seen): Future[(Seen, Vector[Advanced])] =
      db.query(
        SqlFragment.raw(
          "SELECT persistence_id, entity_type, seq_nr, db_timestamp, event_ser_id, " +
            "event_ser_manifest, event_payload FROM event_journal " +
            s"WHERE entity_type IN (${types.mkString(",")}) " +
            s"AND db_timestamp > '${seen.since.minusMillis(LookBack.toMillis)}' " +
            "ORDER BY db_timestamp, persistence_id, seq_nr LIMIT 1000"
        )
      ) { row =>
        val replicated = decode(row)
        (
          row.get("persistence_id", classOf[String]),
          row.get("entity_type", classOf[String]),
          row.get("db_timestamp", classOf[java.time.OffsetDateTime]).toInstant,
          replicated
        )
      }.map { rows =>
        val fresh = rows.filterNot((pid, _, _, r) => seen.emitted((pid, r.sequenceNr)))
        val advanced = fresh.collect {
          case (pid, entityType, _, r) if r.record.installation.contains(installation) =>
            Advanced(
              installation,
              ComponentId(entityType),
              EntityId(pid.stripPrefix(entityType + PersistenceId.DefaultSeparator)),
              r.sequenceNr,
              joined = r.record.kind == JournalRecord.KindReplicationSet &&
                r.record.names.contains(self)
            )
        }
        val latest = rows.map(_._3).maxOption.getOrElse(seen.since)
        // Remembered only within the look-back window: anything older is never read again.
        val horizon = latest.minusMillis(LookBack.toMillis * 2)
        val keep = rows.collect {
          case (pid, _, at, r) if at.isAfter(horizon) => (pid, r.sequenceNr)
        }.toSet
        (Seen(if latest.isAfter(seen.since) then latest else seen.since, keep), advanced)
      }
    Source
      .unfoldAsync(Seen(Instant.now().minusMillis(LookBack.toMillis), Set.empty)) { seen =>
        poll(seen)
          .recover { case NonFatal(_) => (seen, Vector.empty) }
          .flatMap { (next, found) =>
            val pause =
              if found.isEmpty then
                after(settings.poll)(Future.successful(()))(using system.classicSystem)
              else Future.successful(())
            pause.map(_ => Some((next, found)))
          }
      }
      .mapConcat(identity)

  override def stop(): Unit =
    pools.values.foreach(pool =>
      try pool.dispose()
      catch case NonFatal(_) => ()
    )

object DatabaseReplicationTransport:

  private[replication] def toForwarded(reply: EntityProtocol.Reply): ForwardedReply = reply match
    case EntityProtocol.Succeeded(bytes, metadata) =>
      ForwardedReply.Succeeded(bytes, MetaEntry.toMetadata(metadata))
    case rejected: EntityProtocol.Rejected => ForwardedReply.Rejected(rejected.toCommandError)
    case other =>
      ForwardedReply.Rejected(
        CommandError(s"unexpected reply to a forwarded call: $other", ErrorCode.Internal)
      )
