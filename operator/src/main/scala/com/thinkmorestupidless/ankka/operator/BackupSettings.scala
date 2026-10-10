package com.thinkmorestupidless.ankka.operator

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Where an installation's backups go (feature 041). */
enum BackupTarget:
  /** The installation's own object store, the one `ANKKA_OBJECT_STORE_*` names (feature 034). */
  case ObjectStore

/**
 * The installation's backups, as the `backups` component tells the operator and the control plane
 * (feature 041, research R3). `target` absent is `none`: nothing is archived, and every status says
 * so. The other four have a default, so an installation that names a target and nothing else is
 * backed up daily and kept for 30 days.
 *
 * @param retentionDays
 *   the floor: a project may keep its backups longer, never for less time
 * @param schedule
 *   a base backup's schedule, as CNPG reads it: six fields, seconds first
 * @param copyRequired
 *   a project is not backed up until its latest base backup has a copy outside the failure domain
 * @param rehearsalTtl
 *   how long a rehearsal's database may exist before the operator removes it, whatever happened
 * @param restoreTimeout
 *   how long a restore or a rehearsal may take to become healthy before it is reported failed: CNPG
 *   reports a recovery that cannot reach its moment as still setting up, never as failed (R12)
 */
final case class BackupSettings(
    target: Option[BackupTarget] = None,
    retentionDays: Int = 30,
    schedule: String = "0 0 0 * * *",
    copyRequired: Boolean = false,
    rehearsalTtl: FiniteDuration = 24.hours,
    restoreTimeout: FiniteDuration = 30.minutes
):
  def enabled: Boolean = target.isDefined

object BackupSettings:

  val none: BackupSettings = BackupSettings()

  /** The five settings, as system property and variable: the property wins, as everywhere here. */
  val Variables: Vector[(String, String)] = Vector(
    "ankka.operator.backup.target"         -> "ANKKA_BACKUP_TARGET",
    "ankka.operator.backup.retention-days" -> "ANKKA_BACKUP_RETENTION_DAYS",
    "ankka.operator.backup.schedule"       -> "ANKKA_BACKUP_SCHEDULE",
    "ankka.operator.backup.copy-required"  -> "ANKKA_BACKUP_COPY_REQUIRED",
    "ankka.operator.rehearsal.ttl-hours"   -> "ANKKA_REHEARSAL_TTL_HOURS"
  )

  /**
   * Every setting read, and any that cannot be one refused at start, naming the variable: a backup
   * that half works is found on the day it is needed.
   *
   * @param read
   *   a property and its variable to the value, if either is set
   */
  def read(
      read: (String, String) => Option[String],
      objectStore: Option[ObjectStoreSettings]
  ): BackupSettings =
    val Vector(target, retention, schedule, copy, ttl) = Variables.map(read.tupled)
    val named = target.getOrElse("none") match
      case "none" => None
      case "object-store" =>
        if objectStore.isEmpty then
          throw new IllegalArgumentException(
            "ANKKA_BACKUP_TARGET is object-store, but the installation has no object store: " +
              "ANKKA_OBJECT_STORE_ADMIN_URL and its companions must be set"
          )
        Some(BackupTarget.ObjectStore)
      case "gcs" =>
        throw new IllegalArgumentException(
          "ANKKA_BACKUP_TARGET is gcs, which needs the cloud provider of feature 044; until it " +
            "exists the target is object-store or none"
        )
      case other =>
        throw new IllegalArgumentException(
          s"ANKKA_BACKUP_TARGET is '$other'; it must be none or object-store"
        )
    BackupSettings(
      target = named,
      retentionDays = retention.map(positive(_, "ANKKA_BACKUP_RETENTION_DAYS")).getOrElse(30),
      schedule = schedule.map(sixFields).getOrElse(none.schedule),
      copyRequired = copy.map(onOff).getOrElse(false),
      rehearsalTtl = ttl.map(positive(_, "ANKKA_REHEARSAL_TTL_HOURS").hours).getOrElse(24.hours)
    )

  private def positive(value: String, variable: String): Int =
    value.toIntOption
      .filter(_ > 0)
      .getOrElse(
        throw new IllegalArgumentException(s"$variable is '$value'; it must be a positive number")
      )

  private def sixFields(value: String): String =
    if value.split("\\s+").length == 6 then value
    else
      throw new IllegalArgumentException(
        s"ANKKA_BACKUP_SCHEDULE is '$value'; CNPG's schedule has six fields, seconds first " +
          "(0 0 0 * * * is midnight)"
      )

  private def onOff(value: String): Boolean = value match
    case "on"  => true
    case "off" => false
    case other =>
      throw new IllegalArgumentException(s"ANKKA_BACKUP_COPY_REQUIRED is '$other'; it is on or off")
