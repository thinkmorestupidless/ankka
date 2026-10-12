package com.thinkmorestupidless.ankka.operator.cnpg

import com.fasterxml.jackson.annotation.JsonInclude
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, Version}

/** The plugin a base backup is taken by (feature 041). */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class BackupPluginConfiguration(name: String = BarmanObjectStore.Plugin)

/**
 * A base backup on a schedule (feature 041): six fields, seconds first; `immediate` takes the first
 * at once, so a project is restorable on the day its target is named; owned by the cluster, so it
 * goes with it.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ScheduledBackupSpec(
    schedule: String = "0 0 0 * * *",
    immediate: Boolean = true,
    backupOwnerReference: String = "cluster",
    method: String = "plugin",
    pluginConfiguration: BackupPluginConfiguration = BackupPluginConfiguration(),
    cluster: ClusterRef = ClusterRef()
)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ScheduledBackupStatus(lastScheduleTime: Option[String] = None)

@Group("postgresql.cnpg.io")
@Version("v1")
@Kind("ScheduledBackup")
@Plural("scheduledbackups")
class PostgresScheduledBackup
    extends CustomResource[ScheduledBackupSpec, ScheduledBackupStatus]
    with Namespaced:
  override protected def initSpec(): ScheduledBackupSpec     = ScheduledBackupSpec()
  override protected def initStatus(): ScheduledBackupStatus = null

object PostgresScheduledBackup:
  val identity: CnpgDefinitions.Identity =
    CnpgDefinitions.identityOf(classOf[PostgresScheduledBackup])

  def apply(namespace: String, name: String, spec: ScheduledBackupSpec): PostgresScheduledBackup =
    val resource = new PostgresScheduledBackup
    resource.setMetadata(
      new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
        .withNamespace(namespace)
        .withName(name)
        .build()
    )
    resource.setSpec(spec)
    resource

/** One base backup, as CNPG records it. The operator only reads these, for a failure's words. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class BackupSpec(
    cluster: ClusterRef = ClusterRef(),
    method: String = "plugin",
    pluginConfiguration: Option[BackupPluginConfiguration] = None
)

/** `phase` is CNPG's: `completed` and `failed` are the ones that end it. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class BackupStatus(
    phase: Option[String] = None,
    startedAt: Option[String] = None,
    stoppedAt: Option[String] = None,
    error: Option[String] = None
)

@Group("postgresql.cnpg.io")
@Version("v1")
@Kind("Backup")
@Plural("backups")
class PostgresBackup extends CustomResource[BackupSpec, BackupStatus] with Namespaced:
  override protected def initSpec(): BackupSpec     = BackupSpec()
  override protected def initStatus(): BackupStatus = null

object PostgresBackup:
  val identity: CnpgDefinitions.Identity = CnpgDefinitions.identityOf(classOf[PostgresBackup])
