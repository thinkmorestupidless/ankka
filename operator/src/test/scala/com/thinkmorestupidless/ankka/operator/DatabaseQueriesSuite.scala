package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.ServiceVerification

import java.nio.file.Files
import java.time.Instant
import scala.jdk.CollectionConverters.*

/**
 * The SQL the operator runs inside a project database, by `psql` in the primary's own pod (feature
 * 041, research R11), and how it reads the answers: `psql -tA` writes one row a line, columns
 * separated by `|`.
 */
class DatabaseQueriesSuite extends munit.FunSuite:

  test("the archive's lag, its failures, and when it last failed") {
    assert(DatabaseQueries.Archive.sql.contains("pg_stat_archiver"))
    assertEquals(
      DatabaseQueries.Archive.parse("12.5|3|2026-10-08T10:00:00Z"),
      Some(
        DatabaseQueries.ArchiveState(Some(12.5), 3L, Some(Instant.parse("2026-10-08T10:00:00Z")))
      )
    )
    // Nothing archived yet: no lag to speak of, and no failure.
    assertEquals(
      DatabaseQueries.Archive.parse("||"),
      Some(DatabaseQueries.ArchiveState(None, 0L, None))
    )
    assertEquals(DatabaseQueries.Archive.parse(""), None)
  }

  test("which replicas a synchronous write waits for") {
    assert(DatabaseQueries.Replication.sql.contains("pg_stat_replication"))
    assertEquals(
      DatabaseQueries.Replication.parse("ankka-db-2|sync\nankka-db-3|potential"),
      Vector("ankka-db-2" -> "sync", "ankka-db-3" -> "potential")
    )
    assertEquals(DatabaseQueries.Replication.parse(""), Vector.empty)
  }

  test("the databases a restored cluster holds") {
    assertEquals(
      DatabaseQueries.Presence.parse("postgres\nwallet\nrewards\n"),
      Set("postgres", "wallet", "rewards")
    )
  }

  test("what one service's database holds, and the names of secrets changed after the moment") {
    val at  = Instant.parse("2026-10-08T09:20:00Z")
    val sql = DatabaseQueries.verification(at)
    for table <- Vector(
        "event_journal",
        "durable_state",
        "projection_timestamp_offset_store",
        "projection_offset_store",
        "ankka_timers",
        "ankka_secrets"
      )
    do assert(sql.contains(table), s"$table not in $sql")
    assert(sql.contains("max(seq_nr)"), sql)
    // The moment is a literal in UTC: the statement is run by psql, which has no parameters.
    assert(sql.contains("'2026-10-08T09:20:00Z'"), sql)
    assertEquals(
      DatabaseQueries
        .parseVerification("wallet", "1200|3|40|2|0|98765432101|stripe-key,webhook-secret"),
      ServiceVerification(
        name = "wallet",
        present = true,
        journalRows = 1200L,
        stateRows = 3L,
        offsetRows = 42L,
        timerRows = 0L,
        highestSequence = 98765432101L,
        changedSecrets = List("stripe-key", "webhook-secret")
      )
    )
    assertEquals(
      DatabaseQueries.parseVerification("wallet", "0|0|0|0|0||").changedSecrets,
      Nil
    )
  }

  test("a moment is refused unless it is an instant: no SQL is built from text") {
    // The one value interpolated into a statement is an Instant, formatted here; there is no
    // string path for a caller to put a quote through.
    val sql = DatabaseQueries.verification(Instant.parse("2026-10-08T09:20:00.123456Z"))
    assert(sql.contains("'2026-10-08T09:20:00.123456Z'"), sql)
  }

  test("no other file of the operator holds SQL") {
    val root = PkiStack.repoRoot.resolve("operator/src/main/scala")
    val offenders = Files
      .walk(root)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".scala"))
      .filterNot(_.getFileName.toString == "DatabaseQueries.scala")
      .filter(p =>
        """(?i)\bselect\s+[a-z_*(]+.*\bfrom\b""".r.findFirstIn(Files.readString(p)).isDefined
      )
      .map(root.relativize)
      .toVector
    assertEquals(offenders, Vector.empty)
  }
