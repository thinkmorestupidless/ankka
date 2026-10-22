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
 * domain, how they are encrypted, and the control plane's own database's backups (feature 041); and
 * where it keeps its secrets (feature 038); the model server's models (feature 053); and the facts
 * a product's requirements ask of it: the broker's nodes and the copies a topic is given by
 * default, the longest retention a topic may be given, and erasure, each reported as not stated
 * (absent) when the control plane cannot read it, never guessed. Any member may read it: these are
 * facts about the platform a member builds on, and name no organization's data. The wrapping key's
 * name is shown only to an owner of an organization, or a platform administrator: who is an owner
 * is a fact of the organizations, so it is decided here, in the endpoint, as every check across
 * entities is.
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
      com.thinkmorestupidless.ankka.controlplane.deploy.RestoreHold.none,
    /** Where it keeps its secrets (feature 038); none from a control plane that says nothing. */
    secrets: () => Option[InstallationSecrets] = () => None,
    /** The model server and what it serves (feature 053), read at each request; none by default. */
    models: com.thinkmorestupidless.ankka.controlplane.deploy.InstallationModelsReader =
      com.thinkmorestupidless.ankka.controlplane.deploy.InstallationModelsReader.none,
    /** The topic defaults and bounds the installation applies (feature 043). */
    topicPolicy: => com.thinkmorestupidless.ankka.controlplane.tenancy.TopicPolicy =
      com.thinkmorestupidless.ankka.controlplane.tenancy.TopicPolicy.default,
    /** The broker's node count, when the control plane was told it. */
    brokerFacts: => com.thinkmorestupidless.ankka.controlplane.tenancy.BrokerFacts =
      com.thinkmorestupidless.ankka.controlplane.tenancy.BrokerFacts.unstated
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
    val served =
      try models.read()
      catch case NonFatal(_) => None
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
      backups = Some(backupsNow()),
      secrets = secrets(),
      models = served,
      broker = Some(brokerNow()),
      retention = Some(retentionNow()),
      // Erasure, and the keyring that holds its keys, are not built yet (spec 042): the field is
      // reserved and reported as not stated, never as off, until the platform has erasure.
      erasure = None
    )
  }

  private def brokerNow(): InstallationBroker =
    val defaults = topicPolicy.defaults
    InstallationBroker(brokerFacts.nodes, defaults.copies, defaults.minInSync)

  private def retentionNow(): InstallationRetention =
    topicPolicy.bounds.longestRetention match
      case RetentionTime.Everything => InstallationRetention(None, unbounded = true)
      case bound                    => InstallationRetention(Some(bound.text), unbounded = false)

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
