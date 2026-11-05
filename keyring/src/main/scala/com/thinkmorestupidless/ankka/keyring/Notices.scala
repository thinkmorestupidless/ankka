package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.core.keyring.*
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * The notices every instance holds open (feature 042, R15, FR-022), whatever carries them: one
 * connection per instance, which says `hello` with the last notice it acknowledged and is handed
 * every applied erasure of its project after that, then each one as it is applied. A re-send goes
 * to the instances connected then.
 *
 * The keyring runs as several replicas, and an instance is connected to one of them. So a notice is
 * written to an outbox in the database and each replica, polling it, sends it to the instances
 * connected to it; and which instances are connected is a table each replica refreshes with every
 * heartbeat, so the status, asked of any replica, knows them all.
 *
 * A service is complete for an erasure when at least one instance of it has acknowledged and every
 * instance of it connected when the notice was sent has acknowledged or is gone (replaced by a
 * rolling deploy, say); an instance that connects later is handed the backlog, and its views are
 * shared and already redacted.
 */
final class Notices(
    database: Database,
    store: KeyStore,
    /** This replica of the keyring's name: every replica serves its own connections. */
    replica: String = java.util.UUID.randomUUID().toString,
    clock: Clock = Clock.systemUTC()
)(using ExecutionContext):

  private val log = LoggerFactory.getLogger(classOf[Notices])

  /** One instance's open channel: what it said, and how to send it a frame. */
  final class Connection private[Notices] (
      val hello: NoticeFrame.Hello,
      send: NoticeFrame => Unit
  ):
    /** Its backlog sent: from here it hears heartbeats, the first of which ends the backlog. */
    @volatile private[Notices] var opened: Boolean = false

    private[Notices] def deliver(frame: NoticeFrame): Unit =
      try send(frame)
      catch
        case NonFatal(e) => log.warn(s"a frame to ${hello.instance} was not sent: ${e.getMessage}")

    /** A frame the instance sent: an acknowledgement is recorded; anything else is ignored. */
    def received(frame: NoticeFrame): Future[Unit] = frame match
      case a: NoticeFrame.Ack if a.service == hello.service && a.instance == hello.instance =>
        acknowledge(hello.project, a.ack)
      case a: NoticeFrame.Ack =>
        Future.failed(
          IllegalArgumentException(
            s"${hello.instance} acknowledged as ${a.service}/${a.instance}, which it is not"
          )
        )
      case _ => Future.unit

    /** Ends the channel; the instance is gone at once for every replica's status. */
    def close(): Future[Unit] =
      if !connected.remove(hello.instance, this) then Future.unit
      else
        database
          .execute(
            sql"DELETE FROM keyring.notice_connection WHERE instance = ${hello.instance} AND replica = $replica"
          )
          .map(_ => ())
          .recover { case NonFatal(e) =>
            log.warn(s"${hello.instance}'s connection was not removed: ${e.getMessage}")
          }

  private val connected = ConcurrentHashMap[String, Connection]()

  /** The outbox's last row this replica has sent; read once, at the first need. */
  @volatile private var cursor: Option[Long] = None

  /** The instances connected now, of every project. */
  def connections: Vector[NoticeFrame.Hello] = connected.values.asScala.toVector.map(_.hello)

  /**
   * Opens `hello`'s channel: every applied erasure of its project after `hello.after` is sent
   * first, then each new one. A second hello from one instance replaces its first channel.
   */
  def connect(hello: NoticeFrame.Hello, send: NoticeFrame => Unit): Future[Connection] =
    val connection = Connection(hello, send)
    store.ensure().flatMap { _ =>
      // Registered before the backlog is read, so a notice applied in between is sent, perhaps
      // twice, never not at all: every duty is safe to run twice.
      connected.put(hello.instance, connection)
      seen(Vector(hello)).flatMap(_ => cursorNow()).flatMap(_ => backlog(hello, connection))
    }

  private def backlog(hello: NoticeFrame.Hello, connection: Connection): Future[Connection] =
    database
      .query(
        sql"SELECT erasure_seq, subject FROM keyring.applied_log WHERE project = ${hello.project} AND erasure_seq > ${hello.after} ORDER BY erasure_seq"
      )(r =>
        r.get("erasure_seq", classOf[java.lang.Long]).longValue -> r
          .get("subject", classOf[String])
      )
      .flatMap { backlog =>
        // In order, and then a heartbeat: an instance has its backlog once it hears the first one,
        // which is what a service switched to a restored database waits for (`ErasureReplay`).
        backlog.toVector
          .foldLeft(Future.unit) { case (done, (seq, subject)) =>
            done.flatMap(_ =>
              expect(seq, hello.project, Vector(hello)).map(_ =>
                connection.deliver(NoticeFrame.Notice(seq, hello.project, subject, rerun = false))
              )
            )
          }
          .map { _ =>
            connection.opened = true
            connection.deliver(NoticeFrame.Heartbeat(clock.instant()))
            connection
          }
      }

  private def seen(hellos: Vector[NoticeFrame.Hello]): Future[Unit] =
    val at = clock.instant()
    Future
      .traverse(hellos)(h =>
        database.execute(
          sql"INSERT INTO keyring.notice_connection (instance, project, service, replica, seen_at) VALUES (${h.instance}, ${h.project}, ${h.service}, $replica, $at) " ++
            SqlFragment.raw(
              "ON CONFLICT (instance) DO UPDATE SET project = EXCLUDED.project, " +
                "service = EXCLUDED.service, replica = EXCLUDED.replica, seen_at = EXCLUDED.seen_at"
            )
        )
      )
      .map(_ => ())

  /** Where this replica's sending starts: the outbox as it was at the first need. */
  private def cursorNow(): Future[Long] = cursor match
    case Some(c) => Future.successful(c)
    case None    =>
      store
        .ensure()
        .flatMap(_ =>
          database.queryOne(
            SqlFragment.raw("SELECT coalesce(max(id), 0) AS last FROM keyring.notice_outbox")
          )(_.get("last", classOf[java.lang.Long]).longValue)
        )
        .map { found =>
          synchronized { if cursor.isEmpty then cursor = Some(found.getOrElse(0L)) }
          cursor.get
        }

  /**
   * Sends erasure `seq` to every instance of `project` connected now, whichever replica it is
   * connected to: it is written to the outbox, and this replica sends it to its own at once.
   */
  def publish(seq: Long, project: String, subject: String, rerun: Boolean): Future[Unit] =
    cursorNow()
      .flatMap(_ =>
        database.execute(
          sql"INSERT INTO keyring.notice_outbox (erasure_seq, project, subject, rerun) VALUES ($seq, $project, $subject, $rerun)"
        )
      )
      .flatMap(_ => poll())

  /** Sends what the outbox holds past this replica's cursor to the instances connected to it. */
  def poll(): Future[Unit] = synchronized {
    polling = polling.recover { case NonFatal(_) => () }.flatMap(_ => pollOnce())
    polling
  }

  @volatile private var polling: Future[Unit] = Future.unit

  private def pollOnce(): Future[Unit] =
    cursorNow().flatMap { from =>
      database
        .query(
          sql"SELECT id, erasure_seq, project, subject, rerun FROM keyring.notice_outbox WHERE id > $from ORDER BY id"
        )(r =>
          (
            r.get("id", classOf[java.lang.Long]).longValue,
            NoticeFrame.Notice(
              r.get("erasure_seq", classOf[java.lang.Long]).longValue,
              r.get("project", classOf[String]),
              r.get("subject", classOf[String]),
              r.get("rerun", classOf[java.lang.Boolean]).booleanValue
            )
          )
        )
        .flatMap(rows =>
          rows.foldLeft(Future.unit) { case (done, (id, notice)) =>
            done.flatMap { _ =>
              val to = connected.values.asScala.toVector.filter(_.hello.project == notice.project)
              expect(notice.seq, notice.project, to.map(_.hello)).map { _ =>
                to.foreach(_.deliver(notice))
                cursor = Some(id)
              }
            }
          }
        )
    }

  /** A heartbeat to every connection, so an instance knows the channel is open (FR-022). */
  def heartbeat(): Future[Unit] =
    val beat = NoticeFrame.Heartbeat(clock.instant())
    val now  = connected.values.asScala.toVector
    // Not to a channel still sending its backlog: its first heartbeat says the backlog is done.
    now.filter(_.opened).foreach(_.deliver(beat))
    seen(now.map(_.hello))

  private def expect(
      seq: Long,
      project: String,
      instances: Vector[NoticeFrame.Hello]
  ): Future[Unit] =
    Future
      .traverse(instances)(h =>
        database.execute(
          sql"INSERT INTO keyring.notice_expect (erasure_seq, project, service, instance) VALUES ($seq, $project, ${h.service}, ${h.instance}) " ++
            SqlFragment.raw("ON CONFLICT (erasure_seq, instance) DO NOTHING")
        )
      )
      .map(_ => ())

  /**
   * Records an acknowledgement; a later one of the same instance (a re-run) replaces it. Only an
   * erasure of the instance's own project can be acknowledged: an ack naming another project's
   * erasure is refused, so no tenant writes into another's completion and certificate.
   */
  def acknowledge(project: String, ack: KeyringAck): Future[Unit] =
    val c       = ack.completion
    val handler = c.handler
    // Bound as sentinels the statement reads as null: a parameter is never an Option.
    val objects = handler.fold(-1L)(_.objectsErased)
    val final_  = handler.fold("")(_.finality)
    val error   = handler.flatMap(_.error).getOrElse("")
    val at      = clock.instant()
    store
      .ensure()
      .flatMap(_ => store.applied(ack.seq))
      .flatMap {
        case Some((p, _)) if p == project => Future.unit
        case _                            =>
          Future.failed(
            IllegalArgumentException(
              s"${ack.instance} acknowledged erasure ${ack.seq}, which is not an erasure of $project"
            )
          )
      }
      .flatMap(_ =>
        database
          .execute(
            sql"INSERT INTO keyring.notice_ack (erasure_seq, project, service, instance, rows_redacted, tokens_dropped, sessions_ended, objects_erased, finality, error, acked_at) " ++
              sql"VALUES (${ack.seq}, $project, ${ack.service}, ${ack.instance}, ${c.rowsRedacted}, ${c.tokensDropped}, ${c.sessionsEnded}, NULLIF($objects, -1), NULLIF($final_, ''), NULLIF($error, ''), $at) " ++
              SqlFragment.raw(
                "ON CONFLICT (erasure_seq, instance) DO UPDATE SET rows_redacted = EXCLUDED.rows_redacted, " +
                  "tokens_dropped = EXCLUDED.tokens_dropped, sessions_ended = EXCLUDED.sessions_ended, " +
                  "objects_erased = EXCLUDED.objects_erased, finality = EXCLUDED.finality, error = EXCLUDED.error, " +
                  "acked_at = EXCLUDED.acked_at"
              )
          )
          .map(_ => ())
      )

  /**
   * Every service told of erasure `seq`, and each one's completion once it is complete: the sum of
   * its instances' work, the handler's report from the one that ran it, at the last of its acks.
   */
  def status(seq: Long): Future[ErasureStatus] =
    store.applied(seq).flatMap {
      case None               => Future.successful(ErasureStatus(Vector.empty))
      case Some((project, _)) => statusOf(seq, project)
    }

  /** The status of `seq`, an erasure of `project`: only that project's services count. */
  private def statusOf(seq: Long, project: String): Future[ErasureStatus] =
    store.ensure().flatMap { _ =>
      val expected = database.query(
        sql"SELECT service, instance FROM keyring.notice_expect WHERE erasure_seq = $seq AND project = $project"
      )(r => r.get("service", classOf[String]) -> r.get("instance", classOf[String]))
      val acked = database.query(
        sql"SELECT * FROM keyring.notice_ack WHERE erasure_seq = $seq AND project = $project"
      ) { r =>
        val finality = Option(r.get("finality", classOf[String]))
        (
          r.get("service", classOf[String]),
          r.get("instance", classOf[String]),
          r.get("acked_at", classOf[java.time.OffsetDateTime]).toInstant,
          Completion(
            r.get("rows_redacted", classOf[java.lang.Long]).longValue,
            r.get("tokens_dropped", classOf[java.lang.Long]).longValue,
            r.get("sessions_ended", classOf[java.lang.Long]).longValue,
            finality.map(f =>
              HandlerReport(
                Option(r.get("objects_erased", classOf[java.lang.Long])).fold(0L)(_.longValue),
                f,
                Option(r.get("error", classOf[String]))
              )
            )
          )
        )
      }
      val since = clock.instant().minusMillis(Notices.GoneAfter.toMillis)
      val alive = database.query(
        sql"SELECT instance FROM keyring.notice_connection WHERE seen_at > $since AND project = $project"
      )(_.get("instance", classOf[String]))
      for
        e <- expected
        a <- acked
        l <- alive
      yield
        val live     = l.toSet
        val services = (e.map(_._1) ++ a.map(_._1)).distinct.sorted
        ErasureStatus(services.map { service =>
          val acks    = a.filter(_._1 == service)
          val ackedBy = acks.map(_._2).toSet
          val waiting = e.filter(_._1 == service).map(_._2).filter(i => !ackedBy(i) && live(i))
          if acks.isEmpty || waiting.nonEmpty then ServiceErasureStatus(service)
          else
            val sum = acks
              .map(_._4)
              .reduce((x, y) =>
                Completion(
                  x.rowsRedacted + y.rowsRedacted,
                  x.tokensDropped + y.tokensDropped,
                  x.sessionsEnded + y.sessionsEnded,
                  x.handler.orElse(y.handler)
                )
              )
            ServiceErasureStatus(service, Some(acks.map(_._3).max), Some(sum))
        })
    }

object Notices:
  import scala.concurrent.duration.*

  /** How often a connection is told the channel is open with nothing to send. */
  val HeartbeatEvery: FiniteDuration = 15.seconds

  /** An instance no replica has seen for this long is gone: three heartbeats missed. */
  val GoneAfter: FiniteDuration = HeartbeatEvery * 3

  /** How often a replica reads the outbox for notices another replica wrote. */
  val PollEvery: FiniteDuration = 1.second
