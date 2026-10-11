package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.{BackupConfig, BackupViews}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.{CloudConfig, PlatformBackupsReader}
import com.thinkmorestupidless.ankka.http.*

import scala.util.control.NonFatal

/**
 * `GET /installation`: what the installation is — its version, and its cloud provider, account and
 * location when it names one (feature 044) — and where its backups go, how long they are kept,
 * whether a copy outside the cluster is required, whether the backups share the cluster's failure
 * domain, how they are encrypted, and the control plane's own database's backups (feature 041). Any
 * member may read it: these are facts about the platform a member builds on, and name no
 * organization's data. The wrapping key's name is shown only to an owner of an organization, or a
 * platform administrator: who is an owner is a fact of the organizations, so it is decided here, in
 * the endpoint, as every check across entities is.
 */
final class InstallationEndpoint(
    clients: EndpointClients,
    val acl: Acl,
    cloud: Option[CloudConfig],
    platformVersion: String,
    clock: java.time.Clock = java.time.Clock.systemUTC(),
    backups: => BackupConfig = BackupConfig.default,
    /** Where the control plane's own database's backups are read; `None` reads nothing. */
    platform: Option[PlatformBackupsReader] = None,
    /** Whether the control plane is held after its own database was restored (feature 041). */
    hold: com.thinkmorestupidless.ankka.controlplane.deploy.RestoreHold =
      com.thinkmorestupidless.ankka.controlplane.deploy.RestoreHold.none
) extends HttpEndpoint("/installation"):

  private val authz = com.thinkmorestupidless.ankka.controlplane.auth.Authorization(clients, clock)

  /**
   * Whether the control plane is held after its own database was restored, and what differs between
   * what it records and what the cluster runs. Any authenticated caller, as above.
   */
  get("/restore")(() => hold.status)

  /**
   * Releases the hold: the projector makes the cluster what the database records from its next
   * sweep. A platform administrator's alone, and recorded on the restore marker with who it was.
   */
  post("/restore/release") { () =>
    if !com.thinkmorestupidless.ankka.controlplane.auth.Principals.isPlatformAdmin(principal) then
      throw com.thinkmorestupidless.ankka.core.CommandError(
        "only a platform administrator releases a held control plane",
        com.thinkmorestupidless.ankka.core.ErrorCode.Forbidden
      )
    hold.release(principal.name.getOrElse(principal.subject))
  }

  get("/") { () =>
    Installation(
      platformVersion = platformVersion,
      cloud = cloud.map { c =>
        CloudInstallation(
          provider = c.provider,
          account = c.account,
          location = c.location,
          kmsKey = c.kmsKey.filter(_ => ownsSomething)
        )
      },
      backups = Some(backupsNow())
    )
  }

  private def backupsNow(): InstallationStatus =
    val config = backups
    val controlPlane =
      if !config.enabled then None
      else
        platform
          .flatMap(reader =>
            try reader.controlPlaneBackups()
            catch case NonFatal(_) => None
          )
          .map(BackupViews.line)
    val copy =
      platform.flatMap(reader =>
        try BackupViews.secondary(reader.copyStatus())
        catch case NonFatal(_) => None
      )
    BackupViews.installation(config, controlPlane, copy)

  private def ownsSomething: Boolean =
    authz.isAdmin(principal) ||
      authz.visible(principal).exists(_.roleOf(principal.subject).contains(Role.Owner))
