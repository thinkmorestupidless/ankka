package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.ServiceVerification

import java.time.Instant
import scala.util.Try

/**
 * Every statement the operator runs inside a project database, and how it reads the answer (feature
 * 041, research R11). Run by `psql -tA` as `postgres` in the primary's own pod, over its unix
 * socket, by `Executor.query`; the answer is one row a line, its columns separated by `|`.
 *
 * The one place the operator holds SQL (`DatabaseQueriesSuite` refuses it anywhere else). No
 * statement takes text from anywhere: the only value ever put into one is an `Instant`, formatted
 * here, since `psql -c` has no parameters.
 */
object DatabaseQueries:

  /** How far behind the archive is, and whether archiving has been failing. */
  final case class ArchiveState(
      lagSeconds: Option[Double],
      failedCount: Long,
      lastFailed: Option[Instant]
  )

  object Archive:
    val sql: String =
      "select extract(epoch from now() - last_archived_time), failed_count, " +
        "to_char(last_failed_time at time zone 'utc', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') " +
        "from pg_stat_archiver"

    def parse(output: String): Option[ArchiveState] =
      rows(output).headOption.map { columns =>
        ArchiveState(
          lagSeconds = columns.lift(0).flatMap(_.toDoubleOption),
          failedCount = columns.lift(1).flatMap(_.toLongOption).getOrElse(0L),
          lastFailed =
            columns.lift(2).filter(_.nonEmpty).flatMap(t => Try(Instant.parse(t)).toOption)
        )
      }

  /**
   * A recovery point (`RecoveryPoint`): a commit of its own, the segment it was written in sent to
   * the archive, and the last segment the archive holds.
   */
  object Recovery:
    val Commit: String   = "select txid_current()"
    val Switch: String   = "select pg_walfile_name(pg_switch_wal())"
    val Archived: String = "select coalesce(last_archived_wal, '') from pg_stat_archiver"

  /** The replicas the primary streams to, and each one's part in a synchronous write. */
  object Replication:
    val sql: String = "select application_name, sync_state from pg_stat_replication order by 1"

    def parse(output: String): Vector[(String, String)] =
      rows(output).collect { case Vector(name, state) => name -> state }

  /** The databases a cluster holds: a service's is absent from a restore made before it existed. */
  object Presence:
    val sql: String = "select datname from pg_database where not datistemplate"

    def parse(output: String): Set[String] = rows(output).flatMap(_.headOption).toSet

  /**
   * What one service's database holds, run in that database: the rows of each table the platform
   * keeps, the highest journal sequence, and the names of the service secrets changed after
   * `moment`, comma-separated. Never a secret's value.
   */
  def verification(moment: Instant): String =
    val at = s"'$moment'"
    "select " +
      "(select count(*) from event_journal), " +
      "(select count(*) from durable_state), " +
      "(select count(*) from projection_timestamp_offset_store), " +
      "(select count(*) from projection_offset_store), " +
      "(select count(*) from ankka_timers), " +
      "(select max(seq_nr) from event_journal), " +
      s"(select string_agg(name, ',' order by name) from ankka_secrets where updated_at > $at)"

  def parseVerification(service: String, output: String): ServiceVerification =
    val columns       = rows(output).headOption.getOrElse(Vector.empty)
    def count(i: Int) = columns.lift(i).flatMap(_.toLongOption).getOrElse(0L)
    ServiceVerification(
      name = service,
      present = true,
      journalRows = count(0),
      stateRows = count(1),
      offsetRows = count(2) + count(3),
      timerRows = count(4),
      highestSequence = count(5),
      changedSecrets = columns.lift(6).filter(_.nonEmpty).map(_.split(',').toList).getOrElse(Nil)
    )

  /** The newest event a service's database holds: the moment a restore of it reached. */
  object LastWrite:
    val sql: String =
      "select to_char(max(db_timestamp) at time zone 'utc', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') " +
        "from event_journal"

    def parse(output: String): Option[Instant] =
      rows(output).headOption
        .flatMap(_.headOption)
        .filter(_.nonEmpty)
        .flatMap(t => Try(Instant.parse(t)).toOption)

  private def rows(output: String): Vector[Vector[String]] =
    output.linesIterator.map(_.trim).filter(_.nonEmpty).map(_.split("\\|", -1).toVector).toVector
