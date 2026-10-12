package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{
  BackupLine,
  BackupPhrases,
  InstallationStatus,
  ProjectCluster,
  ProjectDatabase,
  ProjectStatus,
  RehearsalView,
  RestoreView,
  ServiceVerification
}
import com.thinkmorestupidless.ankka.crd.{AnkkaProjectStatus, LineStatus}
import com.thinkmorestupidless.ankka.runtime.Gauges

import java.time.Instant
import scala.util.Try

/**
 * What a member reads of a project's and the installation's backups (feature 041), from what the
 * operator last wrote on the project's resource and the installation's settings. Pure.
 */
object BackupViews:

  def project(
      id: String,
      reported: Option[AnkkaProjectStatus],
      config: BackupConfig,
      /** The project's own record of its restores: who asked, when, how each ended. */
      restores: Map[String, com.thinkmorestupidless.ankka.controlplane.domain.Restore] = Map.empty,
      /** And of its rehearsals. */
      rehearsals: Map[String, com.thinkmorestupidless.ankka.controlplane.domain.Rehearsal] =
        Map.empty,
      /** The installation's copy outside its failure domain, which a project may need. */
      copy: Option[com.thinkmorestupidless.ankka.controlplane.api.SecondaryStore] = None
  ): ProjectStatus =
    val lines = reported.flatMap(_.backups).toVector.flatMap(_.lines).map(line)
    val database = reported.flatMap(_.database).map { d =>
      ProjectDatabase(
        d.cluster,
        d.instances,
        d.readyInstances,
        d.primary,
        d.synchronous,
        d.writesWaitingOn
      )
    }
    // FR-006a: under a required copy, a base backup newer than the last copy is not yet safe.
    val uncopied =
      config.enabled && config.copyRequired && {
        val copied = copy.flatMap(_.lastCompleted)
        lines.flatMap(_.lastBaseBackup).maxOption.exists(last => !copied.exists(_.isAfter(last)))
      }
    // FR-025: the latest rehearsal failing is a backup failure.
    val latest = rehearsalViews(rehearsals, reported).filter(_.outcome != "Running").lastOption
    val rehearsalFailed = latest.filter(_.outcome != "Completed")
    val detail =
      if !config.enabled then Some(BackupPhrases.NoTarget)
      else if reported.flatMap(_.backups).isEmpty then
        Some("the operator has not reported on the project's backups")
      else
        reported
          .flatMap(_.backups)
          .flatMap(_.detail)
          .orElse(
            Option.when(uncopied)(
              s"the latest base backup of $id has no copy outside the failure domain yet"
            )
          )
          .orElse(
            rehearsalFailed.map(r =>
              s"the latest rehearsal, ${r.name}, ${r.outcome.toLowerCase}" +
                r.detail.fold("")(d => s": $d")
            )
          )
    ProjectStatus(
      id = id,
      backedUp = config.enabled && lines.nonEmpty && lines.forall(_.phase == "backing up") &&
        rehearsalFailed.isEmpty && !uncopied,
      rehearsal = latest,
      clusters = reported.toVector
        .flatMap(_.clusters)
        .map(c =>
          ProjectCluster(
            c.name,
            c.line,
            c.phase,
            c.services.toVector,
            c.since.flatMap(instant),
            c.leftAt.flatMap(instant)
          )
        ),
      restores = restoreViews(restores, reported),
      target = if config.enabled then config.target else "none",
      lines = lines,
      database = database,
      detail = detail
    )

  /**
   * Every restore the project has asked for, oldest first: the project's record says who and when
   * and how it ended; the operator's report says how far it has got and what each service's
   * database holds. A restore the operator has not reported on yet is `Restoring`.
   */
  def restoreViews(
      recorded: Map[String, com.thinkmorestupidless.ankka.controlplane.domain.Restore],
      reported: Option[AnkkaProjectStatus]
  ): Vector[RestoreView] =
    val byName = reported.toVector.flatMap(_.restores).map(r => r.name -> r).toMap
    recorded.toVector
      .sortBy((name, r) => (r.requestedAt.getOrElse(Instant.EPOCH), name))
      .map { (name, restore) =>
        val status = byName.get(name)
        val phase = status
          .map(_.phase)
          .orElse(restore.outcome.map(o => if o.succeeded then "Verified" else "Failed"))
          .getOrElse("Restoring")
        RestoreView(
          name = name,
          line = restore.line,
          moment = restore.targetTime,
          phase = phase,
          requestedBy = restore.requestedBy.flatMap(a => a.display.orElse(Some(a.subject))),
          requestedAt = restore.requestedAt,
          reachedAt = status
            .flatMap(_.reachedAt)
            .flatMap(instant)
            .orElse(restore.outcome.flatMap(_.reachedAt)),
          services = status.toVector.flatMap(_.services).map { v =>
            ServiceVerification(
              v.name,
              v.present,
              v.journalRows,
              v.stateRows,
              v.offsetRows,
              v.timerRows,
              v.highestSequence,
              v.changedSecrets.toVector
            )
          },
          detail = status.flatMap(_.detail).orElse(restore.outcome.flatMap(_.detail))
        )
      }

  /**
   * Every rehearsal the project keeps, oldest first: the record says who asked, when and how it
   * ended; the operator's report says how far a running one has got and what it found.
   */
  def rehearsalViews(
      recorded: Map[String, com.thinkmorestupidless.ankka.controlplane.domain.Rehearsal],
      reported: Option[AnkkaProjectStatus]
  ): Vector[RehearsalView] =
    val byName = reported.toVector.flatMap(_.rehearsals).map(r => r.name -> r).toMap
    val names  = (recorded.keySet ++ byName.keySet).toVector
    names
      .flatMap { name =>
        val record = recorded.get(name)
        val status = byName.get(name)
        val moment = record
          .map(_.targetTime)
          .orElse(status.flatMap(s => instant(s.targetTime)))
        moment.map { at =>
          RehearsalView(
            name = name,
            line = record
              .map(_.line)
              .getOrElse(com.thinkmorestupidless.ankka.crd.Recovery.ProjectDatabase),
            moment = at,
            outcome = record
              .flatMap(_.outcome.map(_.outcome))
              .orElse(status.map(_.outcome))
              .getOrElse("Running"),
            requestedBy =
              record.flatMap(_.requestedBy).flatMap(a => a.display.orElse(Some(a.subject))),
            requestedAt = record
              .flatMap(_.requestedAt)
              .orElse(status.flatMap(_.startedAt).flatMap(instant)),
            elapsedSeconds = record
              .flatMap(_.outcome)
              .flatMap(_.elapsedSeconds)
              .orElse(status.flatMap(_.elapsedSeconds)),
            detail = record.flatMap(_.outcome).flatMap(_.detail).orElse(status.flatMap(_.detail)),
            services = status.toVector.flatMap(_.services).map { v =>
              ServiceVerification(
                v.name,
                v.present,
                v.journalRows,
                v.stateRows,
                v.offsetRows,
                v.timerRows,
                v.highestSequence,
                v.changedSecrets.toVector
              )
            }
          )
        }
      }
      .sortBy(v => (v.requestedAt.getOrElse(Instant.EPOCH), v.name))

  def line(reported: LineStatus): BackupLine =
    BackupLine(
      line = reported.line,
      cluster = reported.cluster,
      phase = BackupPhrases.phase(reported.phase),
      lastBaseBackup = reported.lastBaseBackup.flatMap(instant),
      firstRestorable = reported.firstRestorable.flatMap(instant),
      lastRestorable = reported.lastRestorable.flatMap(instant),
      archiveLagSeconds = reported.archiveLagSeconds,
      failing = reported.failing,
      copiedAt = reported.copiedAt.flatMap(instant)
    )

  def installation(
      config: BackupConfig,
      controlPlane: Option[BackupLine],
      copy: Option[com.thinkmorestupidless.ankka.controlplane.api.SecondaryStore] = None
  ): InstallationStatus =
    InstallationStatus(
      backupTarget = config.target,
      retentionDays = config.retentionDays,
      copyRequired = config.copyRequired,
      // Every target is the installation's Garage, in the cluster: only a copy that completed
      // takes the backups out of its failure domain.
      sharesFailureDomain = config.enabled && copy.flatMap(_.lastCompleted).isEmpty,
      encryption = if config.enabled then BackupPhrases.GarageEncryption else "none",
      controlPlane = controlPlane,
      notBackedUp = Option.unless(config.enabled)(BackupPhrases.NoTarget),
      secondaryStore = copy
    )

  /** The copy's status, as its ConfigMap's entries hold it; none when it has never written one. */
  def secondary(
      entries: Map[String, String]
  ): Option[com.thinkmorestupidless.ankka.controlplane.api.SecondaryStore] =
    def text(key: String) = entries.get(key).map(_.trim).filter(_.nonEmpty)
    Option.when(entries.nonEmpty)(
      com.thinkmorestupidless.ankka.controlplane.api.SecondaryStore(
        text("lastCompleted").flatMap(instant),
        text("lastFailed").flatMap(instant),
        text("failure"),
        text("buckets").flatMap(_.toIntOption),
        text("deletedObjects").flatMap(_.toLongOption)
      )
    )

  private def instant(text: String): Option[Instant] = Try(Instant.parse(text)).toOption

/**
 * The project's backups as numbers an alert is written against (feature 041, research R10),
 * exported with the control plane's own telemetry: the operator exports nothing.
 */
final class BackupMetrics(
    gauges: Gauges = Gauges.global,
    clock: () => Instant = () => Instant.now()
):

  import BackupMetrics.*

  /** Sets the project's gauges from its status; a project with no line has none. */
  def publish(projectId: String, status: ProjectStatus): Unit =
    val project = Map(ProjectAttribute -> projectId)
    if status.lines.isEmpty then forget(projectId)
    else
      val failing = status.lines.exists(_.phase == "failing")
      gauges.set(
        Failing,
        project,
        if failing then 1.0 else 0.0,
        "1 while the project's backups are failing"
      )
      status.lines
        .flatMap(_.archiveLagSeconds)
        .maxOption
        .foreach(lag =>
          gauges.set(
            ArchiveLag,
            project,
            lag,
            "How far the project database's archive is behind, in seconds"
          )
        )
      status.lines
        .flatMap(_.lastBaseBackup)
        .maxOption
        .foreach(last =>
          gauges.set(
            BaseBackupAge,
            project,
            (clock().toEpochMilli - last.toEpochMilli) / 1000.0,
            "Seconds since the project database's last completed base backup"
          )
        )

  /** Whether the project's latest rehearsal failed or was not removed (FR-025). */
  def rehearsal(projectId: String, failed: Boolean): Unit =
    gauges.set(
      RehearsalFailing,
      Map(ProjectAttribute -> projectId),
      if failed then 1.0 else 0.0,
      "1 while the project's latest rehearsal failed or was not removed"
    )

  /** Whether the installation's last copy to its secondary store failed (FR-006b). */
  def copy(status: Option[com.thinkmorestupidless.ankka.controlplane.api.SecondaryStore]): Unit =
    status.foreach { s =>
      val failing = s.lastFailed.exists(f => !s.lastCompleted.exists(_.isAfter(f)))
      gauges.set(
        CopyFailing,
        Map.empty,
        if failing then 1.0 else 0.0,
        "1 while the installation's last copy to its secondary store failed"
      )
    }

  /** A project that is gone, or no longer backed up, is no longer reported. */
  def forget(projectId: String): Unit =
    val project = Map(ProjectAttribute -> projectId)
    Vector(Failing, ArchiveLag, BaseBackupAge, RehearsalFailing).foreach(gauges.remove(_, project))

  /** Every project the gauges report, so a sweep can forget the ones it no longer saw. */
  def reported: Set[String] =
    Vector(Failing, ArchiveLag, BaseBackupAge, RehearsalFailing)
      .flatMap(gauges.attributesOf)
      .flatMap(_.get(ProjectAttribute))
      .toSet

object BackupMetrics:
  val ProjectAttribute: String = "ankka.project"
  val Failing: String          = "ankka.backups.failing"
  val ArchiveLag: String       = "ankka.backups.archive_lag_seconds"
  val BaseBackupAge: String    = "ankka.backups.last_base_backup_age_seconds"
  val RehearsalFailing: String = "ankka.rehearsals.failing"
  val CopyFailing: String      = "ankka.backups.copy_failing"
