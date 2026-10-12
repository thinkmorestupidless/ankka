package com.thinkmorestupidless.ankka.controlplane.secrets

import com.thinkmorestupidless.ankka.core.secrets.ReadRecord
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.r2dbc.ConnectionFactoryProvider

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Where the control plane keeps the record of every read, keep and removal of a secret.
 *
 * Apart from every service's database, so a service cannot remove the record of its own reads and a
 * restore of its database does not rewind it; and apart from the control plane's own journal, where
 * an event per read would be kept for ever. On an installation it is a database of its own (the
 * `secret-reads` component), backed up as a store in its own right.
 */
trait ReadRecordStore:

  /** Keeps `record`; returns once it is committed. */
  def insert(record: ReadRecord): Unit

  /** The records of `project`, newest first, narrowed by what is given. */
  def list(
      project: String,
      service: Option[String] = None,
      name: Option[String] = None,
      from: Option[Instant] = None,
      to: Option[Instant] = None,
      limit: Int = ReadRecordStore.DefaultLimit
  ): Vector[ReadRecord]

  /** Removes every record older than `before`; how many. */
  def deleteOlderThan(before: Instant): Long

object ReadRecordStore:
  val DefaultLimit: Int = 200
  val MaxLimit: Int     = 1000

  /** Where the store's pool is configured: a copy of the journal's, pointed at its own database. */
  val ConnectionFactoryPath: String = "ankka.controlplane.secret-records.connection-factory"

/** In memory: for a control plane with no database of its own for records, in a suite. */
final class InMemoryReadRecordStore extends ReadRecordStore:
  private val records = ConcurrentLinkedQueue[ReadRecord]()

  def insert(record: ReadRecord): Unit = records.add(record): Unit

  def list(
      project: String,
      service: Option[String],
      name: Option[String],
      from: Option[Instant],
      to: Option[Instant],
      limit: Int
  ): Vector[ReadRecord] =
    records.asScala.toVector
      .filter(r =>
        r.project == project && service.forall(_ == r.service) && name.forall(_ == r.name) &&
          from.forall(f => !r.at.isBefore(f)) && to.forall(t => r.at.isBefore(t))
      )
      .sortBy(_.at)(using Ordering[Instant].reverse)
      .take(math.min(limit, ReadRecordStore.MaxLimit))

  def deleteOlderThan(before: Instant): Long =
    val old = records.asScala.filter(_.at.isBefore(before)).toVector
    old.foreach(records.remove)
    old.size.toLong

/**
 * In Postgres, over a pool of its own. The table is the store's to make: created if absent when the
 * store opens, by the role that then owns it, so the database's own bootstrap needs no schema and
 * no grant.
 */
final class PostgresReadRecordStore private (database: Database, timeout: FiniteDuration)
    extends ReadRecordStore:
  import PostgresReadRecordStore.*

  private def await[A](work: scala.concurrent.Future[A]): A = Await.result(work, timeout)

  def insert(record: ReadRecord): Unit =
    await(
      database.execute(
        SqlFragment.raw(
          "INSERT INTO secret_reads (at, project, service, hosting, name, operation, outcome, " +
            "backend, trace_id, span_id, component, component_kind, latest_skipped) VALUES ("
        ) ++
          sql"${record.at}, ${record.project}, ${record.service}, ${record.hosting}, " ++
          sql"${record.name}, ${record.operation}, ${record.outcome}, ${record.backend}, " ++
          sql"${record.traceId.getOrElse("")}, ${record.spanId.getOrElse("")}, " ++
          sql"${record.component.getOrElse("")}, ${record.componentKind.getOrElse("")}, " ++
          sql"${record.latestSkipped}" ++ SqlFragment.raw(")")
      )
    ): Unit

  def list(
      project: String,
      service: Option[String],
      name: Option[String],
      from: Option[Instant],
      to: Option[Instant],
      limit: Int
  ): Vector[ReadRecord] =
    val conditions =
      Vector(sql"project = $project") ++
        service.map(s => sql"service = $s") ++
        name.map(n => sql"name = $n") ++
        from.map(f => sql"at >= $f") ++
        to.map(t => sql"at < $t")
    val where   = conditions.reduce((a, b) => a ++ SqlFragment.raw(" AND ") ++ b)
    val bounded = math.max(1, math.min(limit, ReadRecordStore.MaxLimit))
    await(
      database.query(
        SqlFragment.raw(s"SELECT $Columns FROM secret_reads WHERE ") ++ where ++
          SqlFragment.raw(s" ORDER BY at DESC, id DESC LIMIT $bounded")
      )(decode)
    )

  def deleteOlderThan(before: Instant): Long =
    await(database.execute(sql"DELETE FROM secret_reads WHERE at < $before"))

object PostgresReadRecordStore:

  private val Columns =
    "at, project, service, hosting, name, operation, outcome, backend, trace_id, span_id, " +
      "component, component_kind, latest_skipped"

  /** The table and its indexes; the store applies them when it opens. */
  val Schema: Vector[String] = Vector(
    """CREATE TABLE IF NOT EXISTS secret_reads (
      |  id BIGSERIAL PRIMARY KEY,
      |  at TIMESTAMPTZ NOT NULL,
      |  project TEXT NOT NULL,
      |  service TEXT NOT NULL,
      |  hosting TEXT NOT NULL,
      |  name TEXT NOT NULL,
      |  operation TEXT NOT NULL,
      |  outcome TEXT NOT NULL,
      |  backend TEXT NOT NULL,
      |  trace_id TEXT NOT NULL DEFAULT '',
      |  span_id TEXT NOT NULL DEFAULT '',
      |  component TEXT NOT NULL DEFAULT '',
      |  component_kind TEXT NOT NULL DEFAULT '',
      |  latest_skipped BOOLEAN NOT NULL DEFAULT false
      |)""".stripMargin,
    "CREATE INDEX IF NOT EXISTS secret_reads_by_service ON secret_reads (project, service, at DESC)",
    "CREATE INDEX IF NOT EXISTS secret_reads_by_name ON secret_reads (project, name, at DESC)",
    "CREATE INDEX IF NOT EXISTS secret_reads_by_age ON secret_reads (at)"
  )

  /** Opens the pool at `ReadRecordStore.ConnectionFactoryPath` and makes the table if absent. */
  def open(
      timeout: FiniteDuration = 10.seconds,
      /** Where the pool is configured; a suite points it at the journal's own database. */
      path: String = ReadRecordStore.ConnectionFactoryPath
  )(using system: ActorSystem[?]): PostgresReadRecordStore =
    val factory  = ConnectionFactoryProvider.get(system).connectionFactoryFor(path)
    val database = new Database(factory)
    Await.result(database.executeAll(Schema.map(SqlFragment.raw)), timeout * 3)
    new PostgresReadRecordStore(database, timeout)

  private def text(row: io.r2dbc.spi.Row, column: String): Option[String] =
    Option(row.get(column, classOf[String])).filter(_.nonEmpty)

  private def decode(row: io.r2dbc.spi.Row): ReadRecord =
    ReadRecord(
      at = row.get("at", classOf[java.time.OffsetDateTime]).toInstant,
      project = row.get("project", classOf[String]),
      service = row.get("service", classOf[String]),
      hosting = row.get("hosting", classOf[String]),
      name = row.get("name", classOf[String]),
      operation = row.get("operation", classOf[String]),
      outcome = row.get("outcome", classOf[String]),
      backend = row.get("backend", classOf[String]),
      traceId = text(row, "trace_id"),
      spanId = text(row, "span_id"),
      component = text(row, "component"),
      componentKind = text(row, "component_kind"),
      latestSkipped = row.get("latest_skipped", classOf[java.lang.Boolean]).booleanValue
    )
