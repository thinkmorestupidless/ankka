package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{BackupPhrases, ProjectStatus, Wire}
import com.thinkmorestupidless.ankka.crd.{
  AnkkaProjectStatus,
  BackupsStatus,
  LineStatus,
  ProjectDatabaseStatus
}
import com.thinkmorestupidless.ankka.runtime.Gauges
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}

import java.time.Instant

/**
 * What a member reads of a project's backups and the installation's, and the gauges set from them
 * (feature 041). Pure: the operator's status is a fixture.
 */
class BackupViewsSuite extends munit.FunSuite:

  private val backedUp = BackupConfig("object-store", 30, copyRequired = false)

  private def reported(phase: String, failing: Option[String] = None, lag: Double = 12.5) =
    AnkkaProjectStatus(
      backups = Some(
        BackupsStatus(
          target = "object-store",
          lines = List(
            LineStatus(
              line = "ankka-db",
              cluster = "ankka-db",
              phase = phase,
              lastBaseBackup = Some("2026-10-08T00:00:12Z"),
              firstRestorable = Some("2026-09-08T00:00:12Z"),
              lastRestorable = Some("2026-10-08T10:11:47.500Z"),
              archiveLagSeconds = Some(lag),
              failing = failing
            )
          )
        )
      ),
      database = Some(ProjectDatabaseStatus("ankka-db", 1, 1, Some("ankka-db-1")))
    )

  test("a project archiving with a base backup is backed up, with its window, lag and instances") {
    val status = BackupViews.project("shop", Some(reported("BackingUp")), backedUp)
    assert(status.backedUp)
    assertEquals(status.target, "object-store")
    val line = status.lines.head
    assertEquals(line.phase, "backing up")
    assertEquals(line.lastBaseBackup, Some(Instant.parse("2026-10-08T00:00:12Z")))
    assertEquals(line.firstRestorable, Some(Instant.parse("2026-09-08T00:00:12Z")))
    assertEquals(line.archiveLagSeconds, Some(12.5))
    assertEquals(status.database.map(_.primary), Some(Some("ankka-db-1")))
  }

  test("a failing line makes the project not backed up, and says why") {
    val status =
      BackupViews.project("shop", Some(reported("Failing", Some("exit status 4"))), backedUp)
    assert(!status.backedUp)
    assertEquals(status.lines.head.phase, "failing")
    assertEquals(status.lines.head.failing, Some("exit status 4"))
  }

  test("with no backup target nothing is backed up, and the status says so whatever was reported") {
    val status = BackupViews.project("shop", Some(reported("BackingUp")), BackupConfig.default)
    assert(!status.backedUp)
    assertEquals(status.target, "none")
    assertEquals(status.detail, Some(BackupPhrases.NoTarget))
  }

  test("a project the operator has not reported on is not backed up, and says that") {
    val status = BackupViews.project("shop", None, backedUp)
    assert(!status.backedUp)
    assert(status.detail.exists(_.contains("has not reported")), status.detail.toString)
  }

  test("the project's status round-trips on the wire") {
    import Wire.given
    val status = BackupViews.project("shop", Some(reported("BackingUp")), backedUp)
    assertEquals(readFromString[ProjectStatus](writeToString(status)), status)
  }

  test(
    "the installation says where backups go, that they share its failure domain, and how encrypted"
  ) {
    val installation = BackupViews.installation(backedUp, None)
    assertEquals(installation.backupTarget, "object-store")
    assertEquals(installation.retentionDays, 30)
    assert(installation.sharesFailureDomain)
    assert(installation.encryption.startsWith("none:"), installation.encryption)
    assertEquals(installation.notBackedUp, None)
    val none = BackupViews.installation(BackupConfig.default, None)
    assertEquals(none.notBackedUp, Some(BackupPhrases.NoTarget))
  }

  test("a service's own database is its owner's to back up, and its status says so") {
    assertEquals(
      com.thinkmorestupidless.ankka.controlplane.domain.Service.databasePhrase("Supplied"),
      "supplied; its owner's to back up"
    )
  }

  // ── The gauges ────────────────────────────────────────────────────────────

  private val now = Instant.parse("2026-10-08T10:12:00Z")

  test("a failing project and a healthy one set the gauges an alert reads, labelled by project") {
    val gauges  = new Gauges
    val metrics = new BackupMetrics(gauges, () => now)
    metrics.publish(
      "shop",
      BackupViews.project("shop", Some(reported("Failing", lag = 30)), backedUp)
    )
    metrics.publish("lab", BackupViews.project("lab", Some(reported("BackingUp")), backedUp))
    def value(name: String, project: String) =
      gauges.snapshot(name).collectFirst {
        case (a, v) if a.get("ankka.project").contains(project) => v
      }
    assertEquals(value(BackupMetrics.Failing, "shop"), Some(1.0))
    assertEquals(value(BackupMetrics.Failing, "lab"), Some(0.0))
    assertEquals(value(BackupMetrics.ArchiveLag, "shop"), Some(30.0))
    // Midnight's base backup, read at 10:12.
    assertEquals(value(BackupMetrics.BaseBackupAge, "lab"), Some(36708.0))
  }

  test("a rehearsal that failed is a gauge of its own") {
    val gauges = new Gauges
    new BackupMetrics(gauges).rehearsal("shop", failed = true)
    assertEquals(gauges.snapshot(BackupMetrics.RehearsalFailing).map(_._2), Vector(1.0))
  }

  test("a project that is gone, or no longer backed up, is no longer reported") {
    val gauges  = new Gauges
    val metrics = new BackupMetrics(gauges, () => now)
    metrics.publish("shop", BackupViews.project("shop", Some(reported("BackingUp")), backedUp))
    assertEquals(metrics.reported, Set("shop"))
    metrics.publish("shop", BackupViews.project("shop", None, backedUp))
    assertEquals(gauges.snapshot(BackupMetrics.Failing), Vector.empty)
    assertEquals(metrics.reported, Set.empty[String])
  }
