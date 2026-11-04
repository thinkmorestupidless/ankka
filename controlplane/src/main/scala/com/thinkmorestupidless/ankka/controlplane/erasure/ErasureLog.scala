package com.thinkmorestupidless.ankka.controlplane.erasure

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import com.thinkmorestupidless.ankka.sdk.Bucket

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import scala.concurrent.{ExecutionContext, Future}

/** One erasure applied (feature 042, R22). */
final case class ErasureLogEntry(
    seq: Long,
    project: String,
    subject: String,
    rid: String,
    appliedAt: Instant
)

object ErasureLogEntry:
  given JsonValueCodec[ErasureLogEntry] = JsonCodecMaker.make

  /** Its object's name: the subject is in the body only, its hash in the name. */
  def objectName(entry: ErasureLogEntry): String =
    val hash = HexFormat.of.formatHex(
      MessageDigest.getInstance("SHA-256").digest(entry.subject.getBytes(UTF_8))
    )
    f"${ErasureLog.Prefix}${entry.seq}%012d-${entry.project}-$hash.json"

/** Where the log's second copy is kept: the platform bucket, or a test's. */
trait ErasureLogBucket:
  /** Keeps `bytes` under `name`, unless an object is there already. */
  def putOnce(name: String, bytes: Array[Byte]): Unit

  /** The entries the bucket holds, in no particular order. */
  def entries(): Vector[ErasureLogEntry]

object ErasureLogBucket:
  /** Over a bucket of the service's storage client (`platform.erasure-log` in a cluster). */
  def over(bucket: => Bucket): ErasureLogBucket = new ErasureLogBucket:
    def putOnce(name: String, bytes: Array[Byte]): Unit =
      if bucket.stat(name).isEmpty then bucket.putBytes(name, bytes, "application/json")
    def entries(): Vector[ErasureLogEntry] =
      bucket
        .list(ErasureLog.Prefix)
        .flatMap(o => bucket.getBytes(o.name).map((_, b) => readFromArray[ErasureLogEntry](b)))
        .toVector

/**
 * The erasure log (feature 042, R22): each application of an erasure, in the `erasure_log` table of
 * the control plane's database (made by code) and as one object in the platform bucket, both
 * written before the keyring is asked to destroy anything. The keyring replays their union after a
 * restore of its database, so a key restored from a backup is destroyed again before it serves.
 */
final class ErasureLog(database: Database, bucket: ErasureLogBucket)(using ExecutionContext):

  private val made = new java.util.concurrent.atomic.AtomicBoolean(false)

  private def ensure(): Future[Unit] =
    if made.get then Future.unit
    else database.execute(ErasureLog.Table).map(_ => made.set(true))

  /**
   * Writes the application of request `rid` to both copies and answers its entry: its sequence is
   * the table's, and writing it again answers the same entry. Fails, having destroyed nothing, when
   * either copy cannot be written; the request stays applying and is tried again.
   */
  def write(project: String, rid: String, subject: String, at: Instant): Future[ErasureLogEntry] =
    for
      _ <- ensure()
      _ <- database.execute(
        sql"INSERT INTO erasure_log (project, rid, subject, applied_at) VALUES ($project, $rid, $subject, $at) " ++
          SqlFragment.raw("ON CONFLICT (project, rid) DO NOTHING")
      )
      found <- database.queryOne(
        sql"SELECT seq, project, subject, rid, applied_at FROM erasure_log WHERE project = $project AND rid = $rid"
      )(ErasureLog.read)
      entry = found.getOrElse(
        throw IllegalStateException(s"no log entry for '$project/$rid' after writing it")
      )
      _ <- Future(bucket.putOnce(ErasureLogEntry.objectName(entry), writeToArray(entry)))(using
        com.thinkmorestupidless.ankka.runtime.AnkkaExecutors.virtual
      )
    yield entry

  /** The table's entries after `seq`, in order: `GET /internal/erasure-log?after=`. */
  def after(seq: Long): Future[Vector[ErasureLogEntry]] =
    ensure().flatMap(_ =>
      database.query(
        sql"SELECT seq, project, subject, rid, applied_at FROM erasure_log WHERE seq > $seq ORDER BY seq"
      )(ErasureLog.read)
    )

object ErasureLog:
  val Prefix: String = "erasure-log/"

  private val Table = SqlFragment.raw(
    """CREATE TABLE IF NOT EXISTS erasure_log (
      |  seq BIGSERIAL PRIMARY KEY, project TEXT NOT NULL, rid TEXT NOT NULL, subject TEXT NOT NULL,
      |  applied_at TIMESTAMPTZ NOT NULL, UNIQUE (project, rid))""".stripMargin
  )

  private def read(r: io.r2dbc.spi.Row): ErasureLogEntry =
    ErasureLogEntry(
      r.get("seq", classOf[java.lang.Long]).longValue,
      r.get("project", classOf[String]),
      r.get("subject", classOf[String]),
      r.get("rid", classOf[String]),
      r.get("applied_at", classOf[java.time.OffsetDateTime]).toInstant
    )
