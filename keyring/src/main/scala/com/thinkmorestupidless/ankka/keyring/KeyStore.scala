package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * The keyring's own database (feature 042, R10, `data-model.md`): every key wrapped, a tombstone
 * for each destroyed one, and what this database has applied of the erasure log. The tables are
 * made by code under the schema `keyring`, as `secret-reads` makes its own; a key is wrapped and
 * unwrapped by the caller (`Wrapping`), so nothing here sees one in the clear.
 *
 * A data subject's create and destroy take one advisory lock of the subject's, so a destroy never
 * interleaves with a first write: a create after a destroy finds the tombstone and is refused.
 */
final class KeyStore(database: Database)(using ExecutionContext):

  private val log = LoggerFactory.getLogger(classOf[KeyStore])

  @volatile private var made = false

  /** The schema and its tables, made once, under a lock every replica of the keyring shares. */
  def ensure(): Future[Unit] =
    if made then Future.unit
    else
      database
        .inTransaction { tx =>
          KeyStore.Schema.foldLeft(
            tx.query(SqlFragment.raw("SELECT pg_advisory_xact_lock(hashtext('ankka-keyring'))"))(
              _ => ()
            ).map(_ => ())
          )((done, ddl) => done.flatMap(_ => tx.execute(ddl).map(_ => ())))
        }
        .map(_ => made = true)

  private def lockOf(project: String, subject: String) =
    sql"SELECT pg_advisory_xact_lock(hashtext('ankka-keyring-subject'), hashtext(${s"$project/$subject"}))"

  /**
   * The wrapped key of `subject`: made with `wrap` when absent and `create`, the same one for every
   * caller that races to make it (FR-005). A tombstoned subject is `Destroyed`, and is never made
   * again.
   */
  def subjectKey(project: String, subject: String, create: Boolean)(
      wrap: => Array[Byte]
  ): Future[KeyStore.Stored] =
    ensure().flatMap { _ =>
      database.inTransaction { tx =>
        for
          _    <- tx.query(lockOf(project, subject))(_ => ())
          gone <- tx.query(
            sql"SELECT 1 AS gone FROM keyring.tombstone WHERE project = $project AND subject = $subject"
          )(_ => true)
          held <-
            if gone.nonEmpty then Future.successful(Vector.empty)
            else
              tx.query(
                sql"SELECT wrapped FROM keyring.subject_key WHERE project = $project AND subject = $subject"
              )(_.get("wrapped", classOf[Array[Byte]]))
          answer <-
            if gone.nonEmpty then Future.successful(KeyStore.Stored.Destroyed)
            else if held.nonEmpty then Future.successful(KeyStore.Stored.Key(held.head))
            else if !create then Future.successful(KeyStore.Stored.Absent)
            else
              val wrapped = wrap
              tx.execute(
                sql"INSERT INTO keyring.subject_key (project, subject, wrapped) VALUES ($project, $subject, $wrapped) " ++
                  SqlFragment.raw("ON CONFLICT (project, subject) DO NOTHING")
              ).flatMap(_ =>
                tx.query(
                  sql"SELECT wrapped FROM keyring.subject_key WHERE project = $project AND subject = $subject"
                )(_.get("wrapped", classOf[Array[Byte]]))
              ).map(read => KeyStore.Stored.Key(read.head))
        yield answer
      }
    }

  /**
   * Destroys `subject`'s key for erasure log entry `seq`: the key's row deleted, the tombstone and
   * the applied entry written, in one transaction, then the table vacuumed so no dead row keeps the
   * key. Applying an entry already applied changes nothing and answers when it was.
   */
  def destroy(seq: Long, project: String, subject: String, at: Instant): Future[Instant] =
    ensure().flatMap { _ =>
      database
        .inTransaction { tx =>
          for
            _    <- tx.query(lockOf(project, subject))(_ => ())
            done <- tx.query(
              sql"SELECT applied_at FROM keyring.applied_log WHERE erasure_seq = $seq"
            )(
              _.get("applied_at", classOf[java.time.OffsetDateTime]).toInstant
            )
            when <- done.headOption match
              case Some(already) => Future.successful(already)
              case None          =>
                for
                  _ <- tx.execute(
                    sql"DELETE FROM keyring.subject_key WHERE project = $project AND subject = $subject"
                  )
                  _ <- tx.execute(
                    sql"INSERT INTO keyring.tombstone (project, subject, erasure_seq, destroyed_at) VALUES ($project, $subject, $seq, $at) " ++
                      SqlFragment.raw("ON CONFLICT (project, subject) DO NOTHING")
                  )
                  _ <- tx.execute(
                    sql"INSERT INTO keyring.applied_log (erasure_seq, project, subject, applied_at) VALUES ($seq, $project, $subject, $at)"
                  )
                yield at
          yield when
        }
        .flatMap(when => vacuum().map(_ => when))
    }

  /** Outside any transaction, as it must be. A vacuum that cannot run is logged, not failed. */
  private def vacuum(): Future[Unit] =
    database
      .execute(SqlFragment.raw("VACUUM keyring.subject_key"))
      .map(_ => ())
      .recover { case NonFatal(e) =>
        log.warn(s"the keyring could not vacuum its keys after a destroy: ${e.getMessage}")
      }

  /** The last erasure log entry this database applied; 0 before the first. */
  def lastApplied(): Future[Long] =
    ensure().flatMap(_ =>
      database
        .queryOne(
          SqlFragment.raw("SELECT coalesce(max(erasure_seq), 0) AS last FROM keyring.applied_log")
        )(
          _.get("last", classOf[java.lang.Long]).longValue
        )
        .map(_.getOrElse(0L))
    )

  /** The tombstone of `subject`, when its key was destroyed. */
  def tombstone(project: String, subject: String): Future[Option[(Long, Instant)]] =
    ensure().flatMap(_ =>
      database.queryOne(
        sql"SELECT erasure_seq, destroyed_at FROM keyring.tombstone WHERE project = $project AND subject = $subject"
      )(r =>
        r.get("erasure_seq", classOf[java.lang.Long]).longValue ->
          r.get("destroyed_at", classOf[java.time.OffsetDateTime]).toInstant
      )
    )

  /** `project`'s key-encryption key as wrapped by the root key, made with `wrap` when absent. */
  def kek(project: String, rootRef: String)(wrap: => Array[Byte]): Future[Array[Byte]] =
    oneOf("kek", project)(
      sql"INSERT INTO keyring.kek (project, wrapped, root_ref) VALUES ($project, $wrap, $rootRef) "
    )

  /** `project`'s lookup key as wrapped by its KEK, made with `wrap` when absent. */
  def lookupKey(project: String)(wrap: => Array[Byte]): Future[Array[Byte]] =
    oneOf("lookup_key", project)(
      sql"INSERT INTO keyring.lookup_key (project, wrapped) VALUES ($project, $wrap) "
    )

  private def oneOf(table: String, project: String)(insert: => SqlFragment): Future[Array[Byte]] =
    val select =
      SqlFragment.raw(s"SELECT wrapped FROM keyring.$table WHERE project = ") ++ sql"$project"
    ensure().flatMap(_ =>
      database.queryOne(select)(_.get("wrapped", classOf[Array[Byte]])).flatMap {
        case Some(held) => Future.successful(held)
        case None       =>
          database
            .execute(insert ++ SqlFragment.raw("ON CONFLICT (project) DO NOTHING"))
            .flatMap(_ => database.queryOne(select)(_.get("wrapped", classOf[Array[Byte]])))
            .map(
              _.getOrElse(throw IllegalStateException(s"no $table for $project after making one"))
            )
      }
    )

  /** FR-018's record: a fetch refused, who asked and why. */
  def refused(caller: String, project: String, subject: String, reason: String): Future[Unit] =
    ensure().flatMap(_ =>
      database
        .execute(
          sql"INSERT INTO keyring.fetch_refusal (caller, project, subject, reason) VALUES ($caller, $project, $subject, $reason)"
        )
        .map(_ => ())
    )

  /** FR-031's record: a machine's decryption, how many fields of whom; never a value. */
  def decrypted(
      machine: String,
      grant: String,
      project: String,
      subject: String,
      fields: Int
  ): Future[Unit] =
    ensure().flatMap(_ =>
      database
        .execute(
          sql"INSERT INTO keyring.decryption (machine, grant_id, project, subject, field_count) VALUES ($machine, $grant, $project, $subject, $fields)"
        )
        .map(_ => ())
    )

object KeyStore:

  /** What the store holds of a data subject. */
  enum Stored:
    case Key(wrapped: Array[Byte])
    case Destroyed
    case Absent

  val Schema: Vector[SqlFragment] = Vector(
    "CREATE SCHEMA IF NOT EXISTS keyring",
    """CREATE TABLE IF NOT EXISTS keyring.kek (
      |  project TEXT PRIMARY KEY, wrapped BYTEA NOT NULL, root_ref TEXT NOT NULL,
      |  created_at TIMESTAMPTZ NOT NULL DEFAULT now())""".stripMargin,
    """CREATE TABLE IF NOT EXISTS keyring.lookup_key (
      |  project TEXT PRIMARY KEY, wrapped BYTEA NOT NULL,
      |  created_at TIMESTAMPTZ NOT NULL DEFAULT now())""".stripMargin,
    """CREATE TABLE IF NOT EXISTS keyring.subject_key (
      |  project TEXT NOT NULL, subject TEXT NOT NULL, wrapped BYTEA NOT NULL,
      |  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (project, subject))""".stripMargin,
    """CREATE TABLE IF NOT EXISTS keyring.tombstone (
      |  project TEXT NOT NULL, subject TEXT NOT NULL, erasure_seq BIGINT NOT NULL,
      |  destroyed_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (project, subject))""".stripMargin,
    """CREATE TABLE IF NOT EXISTS keyring.applied_log (
      |  erasure_seq BIGINT PRIMARY KEY, project TEXT NOT NULL, subject TEXT NOT NULL,
      |  applied_at TIMESTAMPTZ NOT NULL)""".stripMargin,
    // Each instance's completion beside its acknowledgement, which the status answers (R15).
    """CREATE TABLE IF NOT EXISTS keyring.notice_ack (
      |  erasure_seq BIGINT NOT NULL, project TEXT NOT NULL, service TEXT NOT NULL,
      |  instance TEXT NOT NULL, rows_redacted BIGINT NOT NULL, tokens_dropped BIGINT NOT NULL,
      |  sessions_ended BIGINT NOT NULL, objects_erased BIGINT, finality TEXT, error TEXT,
      |  acked_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (erasure_seq, instance))""".stripMargin,
    // The instances connected when a notice was sent: the ones its service's completion waits for.
    """CREATE TABLE IF NOT EXISTS keyring.notice_expect (
      |  erasure_seq BIGINT NOT NULL, project TEXT NOT NULL, service TEXT NOT NULL,
      |  instance TEXT NOT NULL, PRIMARY KEY (erasure_seq, instance))""".stripMargin,
    """CREATE TABLE IF NOT EXISTS keyring.fetch_refusal (
      |  at TIMESTAMPTZ NOT NULL DEFAULT now(), caller TEXT NOT NULL, project TEXT NOT NULL,
      |  subject TEXT NOT NULL, reason TEXT NOT NULL)""".stripMargin,
    """CREATE TABLE IF NOT EXISTS keyring.decryption (
      |  at TIMESTAMPTZ NOT NULL DEFAULT now(), machine TEXT NOT NULL, grant_id TEXT NOT NULL,
      |  project TEXT NOT NULL, subject TEXT NOT NULL, field_count INT NOT NULL)""".stripMargin
  ).map(SqlFragment.raw)
