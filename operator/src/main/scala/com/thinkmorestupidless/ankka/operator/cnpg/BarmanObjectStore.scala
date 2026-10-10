package com.thinkmorestupidless.ankka.operator.cnpg

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, Version}

/**
 * A partial model of the Barman Cloud plugin's `ObjectStore` (feature 041): where a namespace's
 * clusters archive, with what credential, and for how long. Named for the plugin so it is not the
 * operator's own `ObjectStore`, the store a service's bucket is in.
 *
 * Partial, as `PostgresCluster` is: server-side apply owns only what it sends. `serverName` is not
 * here: the plugin requires it empty on the resource, and a cluster names its line in its own
 * `plugins` parameters.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class BarmanObjectStoreSpec(
    /** `<days>d`: the window inside which every moment stays restorable. */
    retentionPolicy: Option[String] = None,
    configuration: BarmanConfiguration = BarmanConfiguration()
)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class BarmanConfiguration(
    /** `s3://<bucket>/`: each line of history is a prefix below it, by its server name. */
    destinationPath: String = "",
    endpointURL: Option[String] = None,
    s3Credentials: Option[S3Credentials] = None,
    wal: Option[WalConfiguration] = None,
    data: Option[DataConfiguration] = None
)

/** Each a reference into the backup credential's Secret, which the database's sidecar reads. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class S3Credentials(
    accessKeyId: SecretKeyRef = SecretKeyRef(),
    secretAccessKey: SecretKeyRef = SecretKeyRef(),
    region: Option[SecretKeyRef] = None
)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class SecretKeyRef(name: String = "", key: String = "")

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class WalConfiguration(
    compression: Option[String] = None,
    @JsonDeserialize(contentAs = classOf[java.lang.Integer])
    maxParallel: Option[Int] = None
)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class DataConfiguration(
    compression: Option[String] = None,
    @JsonDeserialize(contentAs = classOf[java.lang.Integer])
    jobs: Option[Int] = None
)

/** What the plugin reports: per line of history, the window it can restore and its last backups. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class BarmanObjectStoreStatus(
    serverRecoveryWindow: Map[String, RecoveryWindow] = Map.empty
)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class RecoveryWindow(
    firstRecoverabilityPoint: Option[String] = None,
    lastSuccessfulBackupTime: Option[String] = None,
    lastFailedBackupTime: Option[String] = None
)

@Group("barmancloud.cnpg.io")
@Version("v1")
@Kind("ObjectStore")
@Plural("objectstores")
class BarmanObjectStore
    extends CustomResource[BarmanObjectStoreSpec, BarmanObjectStoreStatus]
    with Namespaced:
  override protected def initSpec(): BarmanObjectStoreSpec     = BarmanObjectStoreSpec()
  override protected def initStatus(): BarmanObjectStoreStatus = null

object BarmanObjectStore:
  val identity: CnpgDefinitions.Identity = CnpgDefinitions.identityOf(classOf[BarmanObjectStore])

  /** The plugin's name, as a cluster names it among its plugins and a backup names its method. */
  val Plugin: String = "barman-cloud.cloudnative-pg.io"

  def apply(namespace: String, name: String, spec: BarmanObjectStoreSpec): BarmanObjectStore =
    val resource = new BarmanObjectStore
    resource.setMetadata(
      new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
        .withNamespace(namespace)
        .withName(name)
        .build()
    )
    resource.setSpec(spec)
    resource
