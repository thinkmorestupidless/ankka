package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.LineStatus
import com.thinkmorestupidless.ankka.operator.cnpg.{
  BackupStatus as CnpgBackupStatus,
  ClusterStatus,
  RecoveryWindow
}

import java.time.Instant
import scala.util.Try

/** What the operator read of one archiving cluster (feature 041), in one place. */
final case class BackupObservation(
    cluster: Option[ClusterStatus],
    window: Option[RecoveryWindow],
    lastBackup: Option[CnpgBackupStatus]
)

object BackupObservation:
  val empty: BackupObservation = BackupObservation(None, None, None)

/**
 * What a project's status says of one line of history's backups (feature 041, research R9). Pure,
 * as `Provisioning.decide` is: every row of the rule is a unit test.
 *
 * Failing is one of two things CNPG and the plugin say: the archive's condition is `False`, or a
 * base backup failed after the last one that completed. Postgres's own count of archive failures is
 * not one of them: it counts since the database started and never goes down, so a line that failed
 * once last week and has archived since would read as failing for ever.
 */
object BackupStatus:

  val NotBackedUp: String = "NotBackedUp"
  val BackingUp: String   = "BackingUp"
  val Failing: String     = "Failing"

  def line(
      line: String,
      cluster: String,
      observation: BackupObservation,
      archive: Option[DatabaseQueries.ArchiveState],
      copiedAt: Option[Instant],
      copyRequired: Boolean,
      now: Instant
  ): LineStatus =
    val window      = observation.window
    val lastSuccess = window.flatMap(_.lastSuccessfulBackupTime).flatMap(instant)
    val lastFailure = window.flatMap(_.lastFailedBackupTime).flatMap(instant)
    val archiveDown = observation.cluster
      .flatMap(_.conditions.find(_.`type` == "ContinuousArchiving"))
      .filter(_.status == "False")
    val backupFailed = lastFailure.exists(f => lastSuccess.forall(_.isBefore(f)))
    val lag          = archive.flatMap(_.lagSeconds)
    val copied       = copiedAt.filter(c => lastSuccess.exists(s => !c.isBefore(s)))
    val (phase, reason) =
      if archiveDown.isDefined then
        Failing -> Some(
          archiveDown.flatMap(_.message).getOrElse("the archive is not working")
        )
      else if backupFailed then
        Failing -> Some(
          "the last base backup failed" +
            observation.lastBackup.flatMap(_.error).fold("")(e => s": $e")
        )
      else if lastSuccess.isEmpty then NotBackedUp -> Some("waiting for the first base backup")
      else if copyRequired && copied.isEmpty then
        NotBackedUp  -> Some("the latest base backup has no copy outside the failure domain")
      else BackingUp -> None
    LineStatus(
      line = line,
      cluster = cluster,
      phase = phase,
      lastBaseBackup = window.flatMap(_.lastSuccessfulBackupTime),
      firstRestorable = window.flatMap(_.firstRecoverabilityPoint),
      lastRestorable = lag.map(seconds => now.minusMillis((seconds * 1000).round).toString),
      archiveLagSeconds = lag,
      failing = reason,
      copiedAt = copied.map(_.toString)
    )

  private def instant(text: String): Option[Instant] = Try(Instant.parse(text)).toOption
