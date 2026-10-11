package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{ProjectDatabaseSpec, RestoreEntry}
import com.thinkmorestupidless.ankka.operator.cnpg.*

/**
 * A restore of a project's database (feature 041, research R12): a second cluster beside the
 * project database, made from the line's latest base backup before the moment and its archive up to
 * it. Pure, as `CnpgRendering` is.
 *
 * A restore archives nothing until a service is switched to it: CNPG refuses a cluster whose
 * archive is not empty, and a line of history begins at the switch. Once in use it archives under
 * its own name and takes base backups as the project database does.
 */
object RestoreRendering:

  /** What the restore reads its line of history through, in its own spec. */
  val Source: String = "line"

  /**
   * The restore's cluster. `database` and `owner` are `postgres`, so CNPG's step after recovery
   * creates nothing; the roles, the `ankka_tls` group and every service's database come with the
   * base backup, so every service's client certificate is accepted by it unchanged.
   */
  def cluster(
      namespace: String,
      entry: RestoreEntry,
      settings: Settings,
      database: Option[ProjectDatabaseSpec],
      inUse: Boolean
  ): PostgresCluster =
    val base = CnpgRendering.projectClusterSpec(
      settings,
      // A restore starts alone; the project's replicas follow once a service uses it.
      if inUse then database else None,
      line = Option.when(inUse)(entry.name)
    )
    PostgresCluster(
      namespace,
      entry.name,
      base.copy(
        bootstrap = Some(
          BootstrapSpec(recovery =
            Some(
              RecoverySpec(
                source = Source,
                database = Some("postgres"),
                owner = Some("postgres"),
                recoveryTarget = Some(RecoveryTarget(targetTime = Some(entry.targetTime)))
              )
            )
          )
        ),
        externalClusters = Some(
          Vector(
            ExternalCluster(
              Source,
              Some(
                PluginConfiguration(
                  name = BarmanObjectStore.Plugin,
                  parameters = Map(
                    "barmanObjectName" -> CnpgRendering.BackupObjectStoreName,
                    "serverName"       -> entry.line
                  )
                )
              )
            )
          )
        )
      )
    )

  /**
   * Everything one restore needs this pass: its cluster and who may connect to it, and once a
   * service uses it, its base backups.
   */
  def actions(
      namespace: String,
      entry: RestoreEntry,
      settings: Settings,
      database: Option[ProjectDatabaseSpec],
      inUse: Boolean
  ): Vector[Action] =
    Vector(
      Some(Action.EnsureCluster(cluster(namespace, entry, settings, database, inUse))),
      Some(Action.EnsureNetworkPolicy(CnpgRendering.databasePolicy(namespace, entry.name))),
      Option.when(inUse && settings.backups.enabled)(
        Action.EnsureScheduledBackup(CnpgRendering.scheduledBackup(namespace, entry.name, settings))
      )
    ).flatten
