package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import org.apache.pekko.actor.typed.{ActorSystem, Extension, ExtensionId}
import org.apache.pekko.persistence.query.{
  DeletedDurableState,
  DurableStateChange,
  UpdatedDurableState
}

import java.time.Instant
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

/**
 * The lines of history a service's database has been (feature 041): one row per cluster the service
 * first started on, with the moment it did, in `ankka_history_lines`.
 *
 * A restore copies the table with everything else, so on a restore the rows say which line every
 * earlier event was written on, and the restore's own row, written when the service first starts
 * there, begins its line. An event belongs to the latest line whose start is not after the event's
 * own time; an event older than every row belongs to the first. A failover writes no row: the line
 * is the platform's cluster, never Postgres's timeline.
 *
 * With no line configured (`ankka.history-line` empty, as in a local run) nothing is recorded and
 * `lineOf` answers nothing, so a message keeps the random id it always had.
 */
final class HistoryLines private[ankka] (val current: String, database: () => Database)
    extends Extension:

  /** The lines known, oldest start first. */
  @volatile private var known: Vector[(Instant, String)] = Vector.empty

  /** False once recording failed: no line is claimed that the database does not hold. */
  @volatile private var recorded = true

  def enabled: Boolean = current.nonEmpty && recorded

  /**
   * Records the current line if this database has never been on it, and reads every line. Blocking,
   * and called once before anything publishes, so no event is stamped from an incomplete list.
   */
  def start(): Unit =
    if enabled then
      // A database without the table (one the service supplies, or a local one made before it)
      // keeps the random ids every message had before; nothing else depends on the lines.
      try
        val db = database()
        val written = db
          .execute(
            SqlFragment.raw("INSERT INTO ankka_history_lines (line_id) VALUES (") ++
              sql"$current" ++ SqlFragment.raw(") ON CONFLICT (line_id) DO NOTHING")
          )
          .flatMap(_ => read(db))(using scala.concurrent.ExecutionContext.parasitic)
        known = Await.result(written, 30.seconds)
      catch
        case scala.util.control.NonFatal(failure) =>
          recorded = false
          org.slf4j.LoggerFactory
            .getLogger(classOf[HistoryLines])
            .warn(
              s"the line of history '$current' could not be recorded, so published messages keep " +
                s"random ids: ${failure.getMessage}"
            )

  private def read(db: Database): Future[Vector[(Instant, String)]] =
    db.query(
      SqlFragment.raw("SELECT line_id, started_at FROM ankka_history_lines ORDER BY started_at")
    ) { row =>
      val at = row.get("started_at", classOf[java.time.OffsetDateTime]).toInstant
      (at, row.get("line_id", classOf[String]))
    }

  /** The line an event recorded at `at` was written on; nothing when no line is configured. */
  def lineOf(at: Instant): Option[String] =
    if !enabled then None
    else
      val lines = known
      if lines.isEmpty then Some(current)
      else Some(lines.takeWhile(!_._1.isAfter(at)).lastOption.getOrElse(lines.head)._2)

  /** For a test: the lines as read, oldest first. */
  private[ankka] def lines: Vector[(Instant, String)] = known

object HistoryLines extends ExtensionId[HistoryLines]:

  def createExtension(system: ActorSystem[?]): HistoryLines =
    new HistoryLines(lineFrom(system.settings.config), () => Database()(using system))

  private val HostPath = "pekko.persistence.r2dbc.connection-factory.host"

  /**
   * The configured line; else, on the platform, the cluster the database's host names. A service on
   * its project database is given only the host, `ankka-db-rw`, through its credential Secret, and
   * naming the line there too would roll every pod on upgrade. A switched service is given both.
   */
  private[ankka] def lineFrom(config: com.typesafe.config.Config): String =
    def text(path: String) = if config.hasPath(path) then config.getString(path).trim else ""
    val configured         = text("ankka.history-line")
    if configured.nonEmpty then configured
    else if text("ankka.cluster.formation") == "bootstrap" && text(HostPath).endsWith("-rw") then
      text(HostPath).stripSuffix("-rw")
    else ""

  /** A message's id from the line its event was written on, its source and its position. */
  def messageId(line: String, persistenceId: String, position: Long): String =
    s"$line/$persistenceId/$position"

  /** When a key value state was written; only its two kinds of change carry the time. */
  def writtenAt(change: DurableStateChange[?]): Instant = change match
    case updated: UpdatedDurableState[?] => Instant.ofEpochMilli(updated.timestamp)
    case deleted: DeletedDurableState[?] => Instant.ofEpochMilli(deleted.timestamp)
