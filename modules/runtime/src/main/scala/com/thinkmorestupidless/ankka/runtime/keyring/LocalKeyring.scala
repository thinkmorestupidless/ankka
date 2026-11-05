package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.{
  CommandError,
  DataSubject,
  ErrorCode,
  KeyAnswer,
  Personal
}
import com.thinkmorestupidless.ankka.core.keyring.{
  Completion,
  HandlerReport,
  KeyringAck,
  KeyringNotice
}
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import org.slf4j.LoggerFactory

import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * The keyring of a service run on a developer's machine (feature 042, R20): its keys and tombstones
 * in the schema `ankka_keyring` of the service's own database, made by code on first use. An
 * erasure deletes the key and writes its tombstone in one transaction. For development only: its
 * keys share the database's backups, which is exactly what the installation's keyring exists not to
 * do.
 *
 * Notices are rows too, so every instance of a service run as a cluster on one machine hears an
 * erasure filed on any of them: each subscription reads the rows after its cursor every `poll`.
 */
final class LocalKeyring(
    project: String,
    database: Database,
    timeout: FiniteDuration = 10.seconds,
    poll: FiniteDuration = 500.millis
)(using ExecutionContext)
    extends KeyStoreClient:

  private val log                               = LoggerFactory.getLogger(classOf[LocalKeyring])
  private val random                            = SecureRandom()
  private val confirmed                         = AtomicReference[Option[Instant]](None)
  private val made                              = AtomicBoolean(false)
  private val polling: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { r =>
      val t = Thread(r, "ankka-local-keyring")
      t.setDaemon(true)
      t
    }

  /** Until when the keyring refuses everything, as a test kit asked (`outage`); `None` when up. */
  private val down = AtomicReference[Option[Instant]](None)

  /** The end of the outage in force now, if there is one. */
  def outageUntil: Option[Instant] =
    down.get.filter(Instant.now().isBefore)

  /**
   * Refuses every request as unavailable for `duration`, erasures included, and confirms no notices
   * while it lasts: what a test kit's sidecar is told by `POST /_ankka/keyring/outage`, as the
   * kit's keyring in memory is told by `outage`. Kept by this instance only.
   */
  def outage(duration: FiniteDuration): Unit =
    down.set(Some(Instant.now().plusMillis(duration.toMillis)))

  /** Ends an outage now. */
  def recover(): Unit = down.set(None)

  private def checkUp(): Unit =
    if outageUntil.isDefined then
      throw CommandError(
        "the keyring is unavailable: the local keyring is in an outage a test asked for",
        ErrorCode.Unavailable,
        Map(Personal.ErrorKey -> Personal.Unavailable)
      )

  private def await[A](what: String)(f: => Future[A]): A =
    try Await.result(f, timeout)
    catch
      case e: CommandError => throw e
      case NonFatal(e)     =>
        throw CommandError(
          s"the keyring is unavailable: the local keyring could not $what (${e.getMessage})",
          ErrorCode.Unavailable,
          Map(Personal.ErrorKey -> Personal.Unavailable)
        )

  /** The schema and its tables, made once, under a lock two instances share. */
  private def ensure(): Unit =
    if !made.get then
      await("make its tables")(database.inTransaction { tx =>
        LocalKeyring.Schema.foldLeft(
          tx.query(SqlFragment.raw("SELECT pg_advisory_xact_lock(hashtext('ankka_keyring'))"))(_ =>
            ()
          ).map(_ => ())
        )((done, ddl) => done.flatMap(_ => tx.execute(ddl).map(_ => ())))
      })
      made.set(true)

  private def fresh(): Array[Byte] =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    bytes

  def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
    checkUp()
    ensure()
    val s = subject.value
    if create then
      val k = fresh()
      await("make a key")(
        database.execute(
          sql"INSERT INTO ankka_keyring.subject_key (project, subject, key) SELECT $project, $s, $k " ++
            sql"WHERE NOT EXISTS (SELECT 1 FROM ankka_keyring.tombstone WHERE project = $project AND subject = $s) " ++
            SqlFragment.raw("ON CONFLICT (project, subject) DO NOTHING")
        )
      ): Unit
    val found = await("read a key")(
      database.queryOne(
        sql"SELECT key FROM ankka_keyring.subject_key WHERE project = $project AND subject = $s"
      )(_.get("key", classOf[Array[Byte]]))
    )
    found match
      case Some(k) => KeyAnswer.Key(IArray.from(k))
      case None    =>
        val gone = await("read a tombstone")(
          database.queryOne(
            sql"SELECT 1 AS gone FROM ankka_keyring.tombstone WHERE project = $project AND subject = $s"
          )(_ => ())
        )
        if gone.isDefined then KeyAnswer.Destroyed else KeyAnswer.Absent

  def lookupKey(project: String): IArray[Byte] =
    checkUp()
    ensure()
    val k = fresh()
    await("make a lookup key")(
      database.execute(
        sql"INSERT INTO ankka_keyring.lookup_key (project, key) VALUES ($project, $k) " ++
          SqlFragment.raw("ON CONFLICT (project) DO NOTHING")
      )
    ): Unit
    IArray.from(
      await("read a lookup key")(
        database.queryOne(sql"SELECT key FROM ankka_keyring.lookup_key WHERE project = $project")(
          _.get("key", classOf[Array[Byte]])
        )
      ).getOrElse(throw IllegalStateException(s"no lookup key for '$project' after making one"))
    )

  /**
   * Destroys `subject`'s key: the key deleted and its tombstone written in one transaction, and a
   * notice for every instance. Answers the notice's sequence. Erasing a subject already erased
   * writes nothing but a new notice, so the duties and the handler run again. Refused as
   * unavailable in an outage: a keyring that cannot be reached destroys nothing.
   */
  def erase(subject: DataSubject, rerun: Boolean = false): Long =
    checkUp()
    ensure()
    val s   = subject.value
    val seq = await("destroy a key")(database.inTransaction { tx =>
      for
        _ <- tx.execute(
          sql"DELETE FROM ankka_keyring.subject_key WHERE project = $project AND subject = $s"
        )
        seq <- tx.query(
          sql"INSERT INTO ankka_keyring.notice (project, subject, rerun) VALUES ($project, $s, $rerun) " ++
            SqlFragment.raw("RETURNING seq")
        )(_.get("seq", classOf[java.lang.Long]).longValue)
        _ <- tx.execute(
          sql"INSERT INTO ankka_keyring.tombstone (project, subject, erasure_seq) VALUES ($project, $s, ${seq.head}) " ++
            SqlFragment.raw("ON CONFLICT (project, subject) DO NOTHING")
        )
      yield seq.head
    })
    // Outside the transaction, as it must be: no dead row keeps the key's bytes.
    try await("vacuum")(database.execute(SqlFragment.raw("VACUUM ankka_keyring.subject_key"))): Unit
    catch case NonFatal(e) => log.debug("the local keyring's vacuum did not run: {}", e.getMessage)
    seq

  private val read                            = scala.concurrent.Promise[Unit]()
  def caughtUp: scala.concurrent.Future[Unit] = read.future

  def subscribe(after: Long)(listener: KeyringNotice => Unit): AutoCloseable =
    val cursor = AtomicReference[Long](after)
    val task   = polling.scheduleWithFixedDelay(
      () =>
        // In an outage the channel is down: nothing is read and nothing confirmed.
        try
          if outageUntil.isDefined then throw IllegalStateException("in an outage")
          val exists = Await.result(
            database.queryOne(
              SqlFragment.raw("SELECT to_regclass('ankka_keyring.notice') IS NOT NULL AS here")
            )(
              _.get("here", classOf[java.lang.Boolean]).booleanValue
            ),
            timeout
          )
          if exists.contains(true) then
            val from    = cursor.get
            val notices = Await.result(
              database.query(
                sql"SELECT seq, subject, rerun FROM ankka_keyring.notice WHERE project = $project AND seq > $from ORDER BY seq"
              )(r =>
                KeyringNotice(
                  r.get("seq", classOf[java.lang.Long]).longValue,
                  project,
                  r.get("subject", classOf[String]),
                  r.get("rerun", classOf[java.lang.Boolean]).booleanValue
                )
              ),
              timeout
            )
            notices.foreach { n =>
              listener(n)
              cursor.set(n.seq)
            }
          confirmed.set(Some(Instant.now()))
          read.trySuccess(()): Unit
        catch
          case NonFatal(e) =>
            log.debug("the local keyring's notices were not read: {}", e.getMessage),
      0,
      poll.toMillis,
      TimeUnit.MILLISECONDS
    )
    () => task.cancel(false): Unit

  def confirmedAt(): Option[Instant] = confirmed.get

  def ack(ack: KeyringAck): scala.concurrent.Future[Unit] =
    scala.concurrent.Future.fromTry(scala.util.Try {
      ensure()
      val c       = ack.completion
      val handler = c.handler
      // Bound as sentinels the statement reads as null: a parameter is never an Option.
      val objects = handler.fold(-1L)(_.objectsErased)
      val final_  = handler.fold("")(_.finality)
      val error   = handler.flatMap(_.error).getOrElse("")
      await("record an acknowledgement")(
        database.execute(
          sql"INSERT INTO ankka_keyring.notice_ack (erasure_seq, service, instance, rows_redacted, tokens_dropped, sessions_ended, objects_erased, finality, error) " ++
            sql"VALUES (${ack.seq}, ${ack.service}, ${ack.instance}, ${c.rowsRedacted}, ${c.tokensDropped}, ${c.sessionsEnded}, NULLIF($objects, -1), NULLIF($final_, ''), NULLIF($error, '')) " ++
            SqlFragment.raw("ON CONFLICT (erasure_seq, instance) DO NOTHING")
        )
      ): Unit
    })

  /** The acknowledgements of notice `seq`, from every instance that has carried it out. */
  def acks(seq: Long): Vector[KeyringAck] =
    ensure()
    await("read acknowledgements")(
      database.query(
        sql"SELECT * FROM ankka_keyring.notice_ack WHERE erasure_seq = $seq ORDER BY instance"
      ) { r =>
        val finality = Option(r.get("finality", classOf[String]))
        KeyringAck(
          seq,
          r.get("service", classOf[String]),
          r.get("instance", classOf[String]),
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
    )

  override def close(): Unit = polling.shutdownNow(): Unit

object LocalKeyring:

  private val Schema: Vector[SqlFragment] = Vector(
    "CREATE SCHEMA IF NOT EXISTS ankka_keyring",
    """CREATE TABLE IF NOT EXISTS ankka_keyring.subject_key (
      |  project TEXT NOT NULL, subject TEXT NOT NULL, key BYTEA NOT NULL,
      |  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (project, subject))""".stripMargin,
    """CREATE TABLE IF NOT EXISTS ankka_keyring.lookup_key (
      |  project TEXT PRIMARY KEY, key BYTEA NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())""".stripMargin,
    """CREATE TABLE IF NOT EXISTS ankka_keyring.tombstone (
      |  project TEXT NOT NULL, subject TEXT NOT NULL, erasure_seq BIGINT NOT NULL,
      |  destroyed_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (project, subject))""".stripMargin,
    """CREATE TABLE IF NOT EXISTS ankka_keyring.notice (
      |  seq BIGSERIAL PRIMARY KEY, project TEXT NOT NULL, subject TEXT NOT NULL,
      |  rerun BOOLEAN NOT NULL, at TIMESTAMPTZ NOT NULL DEFAULT now())""".stripMargin,
    """CREATE TABLE IF NOT EXISTS ankka_keyring.notice_ack (
      |  erasure_seq BIGINT NOT NULL, service TEXT NOT NULL, instance TEXT NOT NULL,
      |  rows_redacted BIGINT NOT NULL, tokens_dropped BIGINT NOT NULL, sessions_ended BIGINT NOT NULL,
      |  objects_erased BIGINT, finality TEXT, error TEXT,
      |  acked_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (erasure_seq, instance))""".stripMargin
  ).map(SqlFragment.raw)
