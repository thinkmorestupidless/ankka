package com.thinkmorestupidless.ankka.controlplane

import com.typesafe.config.Config

/**
 * The installation's backups as the control plane reads them (feature 041): the same three of the
 * five settings the operator reads that this side needs — `ankka.controlplane.backups` in
 * `reference.conf`, read once at start like `OrganizationPolicy`. Configuration, never state.
 *
 * @param target
 *   `none` or `object-store`; the operator refuses one without a store, so this side need not
 * @param retentionDays
 *   the floor a project's own retention may not go below
 * @param copyRequired
 *   whether "backed up" needs a copy outside the failure domain
 */
final case class BackupConfig(
    target: String = "none",
    retentionDays: Int = 30,
    copyRequired: Boolean = false
):
  def enabled: Boolean = target != "none"

object BackupConfig:

  val default: BackupConfig = BackupConfig()

  /** Refuses at start anything the operator would refuse, naming the variable. */
  def from(config: Config): BackupConfig =
    val section = config.getConfig("ankka.controlplane.backups")
    val target  = section.getString("target").trim
    if !Set("none", "object-store").contains(target) then
      throw IllegalArgumentException(
        s"ankka.controlplane.backups.target (ANKKA_BACKUP_TARGET) is '$target'; it must be none " +
          "or object-store"
      )
    val retention = section.getString("retention-days").trim
    val days = retention.toIntOption
      .filter(_ > 0)
      .getOrElse(
        throw IllegalArgumentException(
          s"ankka.controlplane.backups.retention-days (ANKKA_BACKUP_RETENTION_DAYS) is " +
            s"'$retention'; it must be a positive number"
        )
      )
    val copy = section.getString("copy-required").trim match
      case "on"  => true
      case "off" => false
      case other =>
        throw IllegalArgumentException(
          s"ankka.controlplane.backups.copy-required (ANKKA_BACKUP_COPY_REQUIRED) is '$other'; " +
            "it is on or off"
        )
    BackupConfig(target, days, copy)
