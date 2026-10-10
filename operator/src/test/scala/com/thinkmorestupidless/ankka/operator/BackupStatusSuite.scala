package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.operator.cnpg.{
  BackupStatus as CnpgBackupStatus,
  ClusterCondition,
  ClusterStatus,
  RecoveryWindow
}

import java.time.Instant

/**
 * What a project's status says of one line of history's backups, from what the operator read
 * (feature 041, research R9). Pure: every row of the rule is a case here, and none needs a cluster.
 */
class BackupStatusSuite extends munit.FunSuite:

  private val now = Instant.parse("2026-10-08T10:12:00Z")

  private val archiving = ClusterCondition(
    `type` = "ContinuousArchiving",
    status = "True",
    reason = Some("ContinuousArchivingSuccess"),
    message = Some("Continuous archiving is working")
  )

  private val healthy = BackupObservation(
    cluster = Some(ClusterStatus(readyInstances = 1, conditions = Vector(archiving))),
    window = Some(
      RecoveryWindow(
        firstRecoverabilityPoint = Some("2026-09-08T00:00:12Z"),
        lastSuccessfulBackupTime = Some("2026-10-08T00:00:12Z")
      )
    ),
    lastBackup = Some(CnpgBackupStatus(phase = Some("completed")))
  )

  private val archive = Some(DatabaseQueries.ArchiveState(Some(12.5), 0L, None))

  private def line(
      observation: BackupObservation = healthy,
      archive: Option[DatabaseQueries.ArchiveState] = archive,
      copiedAt: Option[Instant] = None,
      copyRequired: Boolean = false
  ) = BackupStatus.line("ankka-db", "ankka-db", observation, archive, copiedAt, copyRequired, now)

  test("a line archiving and with a base backup is backing up, with its window and its lag") {
    val status = line()
    assertEquals(status.phase, BackupStatus.BackingUp)
    assertEquals(status.lastBaseBackup, Some("2026-10-08T00:00:12Z"))
    assertEquals(status.firstRestorable, Some("2026-09-08T00:00:12Z"))
    // The latest moment is now less the lag: what has not reached the archive cannot be restored.
    assertEquals(status.lastRestorable, Some("2026-10-08T10:11:47.500Z"))
    assertEquals(status.archiveLagSeconds, Some(12.5))
    assertEquals(status.failing, None)
  }

  test("an archive CNPG says is not working is failing, in CNPG's words") {
    val failing = archiving.copy(
      status = "False",
      reason = Some("ContinuousArchivingFailing"),
      message = Some("unexpected failure invoking barman-cloud-wal-archive: exit status 4")
    )
    val status =
      line(healthy.copy(cluster = healthy.cluster.map(_.copy(conditions = Vector(failing)))))
    assertEquals(status.phase, BackupStatus.Failing)
    assert(status.failing.exists(_.contains("barman-cloud-wal-archive")), status.failing.toString)
  }

  test("a base backup that failed after the last one that completed is failing, with its error") {
    val status = line(
      healthy.copy(
        window = healthy.window.map(_.copy(lastFailedBackupTime = Some("2026-10-08T00:05:00Z"))),
        lastBackup = Some(CnpgBackupStatus(phase = Some("failed"), error = Some("AccessDenied")))
      )
    )
    assertEquals(status.phase, BackupStatus.Failing)
    assert(status.failing.exists(_.contains("AccessDenied")), status.failing.toString)
  }

  test("an earlier failure followed by a success is not failing") {
    val status = line(
      healthy.copy(window =
        healthy.window.map(_.copy(lastFailedBackupTime = Some("2026-10-07T00:00:00Z")))
      )
    )
    assertEquals(status.phase, BackupStatus.BackingUp)
  }

  test("archive failures Postgres counts, with none since the last success, are not failing") {
    val status = line(archive =
      Some(DatabaseQueries.ArchiveState(Some(3.0), 7L, Some(Instant.parse("2026-10-01T00:00:00Z"))))
    )
    assertEquals(status.phase, BackupStatus.BackingUp)
  }

  test("a line with no base backup yet is not backed up, and says it is waiting for the first") {
    val status = line(healthy.copy(window = None))
    assertEquals(status.phase, BackupStatus.NotBackedUp)
    assert(status.failing.exists(_.contains("first base backup")), status.failing.toString)
  }

  test("with a copy required, a line whose latest base backup has no copy is not backed up") {
    val before = Instant.parse("2026-10-07T23:00:00Z")
    val status = line(copiedAt = Some(before), copyRequired = true)
    assertEquals(status.phase, BackupStatus.NotBackedUp)
    assert(status.failing.exists(_.contains("outside the failure domain")), status.failing.toString)
    assertEquals(status.copiedAt, None)
    val after  = Instant.parse("2026-10-08T01:00:00Z")
    val copied = line(copiedAt = Some(after), copyRequired = true)
    assertEquals(copied.phase, BackupStatus.BackingUp)
    assertEquals(copied.copiedAt, Some(after.toString))
  }

  test("with no copy required, the copy says nothing either way") {
    assertEquals(line(copiedAt = None).phase, BackupStatus.BackingUp)
  }
