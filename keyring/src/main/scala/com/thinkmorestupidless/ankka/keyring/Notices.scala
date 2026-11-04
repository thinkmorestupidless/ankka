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
 * A service is complete for an erasure when at least one instance of it has acknowledged and every
 * instance of it connected when the notice was sent has acknowledged or is gone (replaced by a
 * rolling deploy, say); an instance that connects later is handed the backlog, and its views are
 * shared and already redacted.
 */
final class Notices(database: Database, store: KeyStore, clock: Clock = Clock.systemUTC())(using
    ExecutionContext
):

  private val log = LoggerFactory.getLogger(classOf[Notices])

  /** One instance's open channel: what it said, and how to send it a frame. */
  final class Connection private[Notices] (
      val hello: NoticeFrame.Hello,
      send: NoticeFrame => Unit
  ):
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

    def close(): Unit = connected.remove(hello.instance, this): Unit

  private val connected = ConcurrentHashMap[String, Connection]()

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
      database
        .query(
          sql"SELECT erasure_seq, subject FROM keyring.applied_log WHERE project = ${hello.project} AND erasure_seq > ${hello.after} ORDER BY erasure_seq"
        )(r =>
          r.get("erasure_seq", classOf[java.lang.Long]).longValue -> r
            .get("subject", classOf[String])
        )
        .flatMap { backlog =>
          Future
            .traverse(backlog.toVector) { (seq, subject) =>
              expect(seq, hello.project, Vector(hello)).map(_ =>
                connection.deliver(NoticeFrame.Notice(seq, hello.project, subject, rerun = false))
              )
            }
            .map(_ => connection)
        }
    }

  /**
   * Sends erasure `seq` to every instance of `project` connected now, remembering which they were.
   */
  def publish(seq: Long, project: String, subject: String, rerun: Boolean): Future[Unit] =
    val to = connected.values.asScala.toVector.filter(_.hello.project == project)
    expect(seq, project, to.map(_.hello)).map { _ =>
      to.foreach(_.deliver(NoticeFrame.Notice(seq, project, subject, rerun)))
    }

  /** A heartbeat to every connection, so an instance knows the channel is open (FR-022). */
  def heartbeat(): Unit =
    val beat = NoticeFrame.Heartbeat(clock.instant())
    connected.values.asScala.foreach(_.deliver(beat))

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

  /** Records an acknowledgement; a later one of the same instance (a re-run) replaces it. */
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
    store.ensure().flatMap { _ =>
      val expected = database.query(
        sql"SELECT service, instance FROM keyring.notice_expect WHERE erasure_seq = $seq"
      )(r => r.get("service", classOf[String]) -> r.get("instance", classOf[String]))
      val acked = database.query(
        sql"SELECT * FROM keyring.notice_ack WHERE erasure_seq = $seq"
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
      for
        e <- expected
        a <- acked
      yield
        val live     = connected.keySet.asScala.toSet
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
  /** How often a connection is told the channel is open with nothing to send. */
  val HeartbeatEvery: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.FiniteDuration(15, java.util.concurrent.TimeUnit.SECONDS)
