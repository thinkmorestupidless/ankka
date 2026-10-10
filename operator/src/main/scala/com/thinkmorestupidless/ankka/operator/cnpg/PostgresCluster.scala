package com.thinkmorestupidless.ankka.operator.cnpg

import com.fasterxml.jackson.annotation.JsonInclude
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, Version}

/**
 * A partial model of CNPG's `Cluster` — the fields ankka ever sets, nothing else: six since feature
 * 001, and since feature 041 the archiver (`plugins`), a recovery source (`externalClusters`, and
 * `bootstrap.recovery`), parameters and synchronous replication. Every new field is an `Option`, so
 * a cluster that does not use it is rendered exactly as before and claims nothing.
 *
 * Partial is safe with server-side apply: a field manager owns only the fields it sends, so
 * modelling six of several hundred and leaving the rest to CNPG's own defaults is correct, not
 * lossy. Verified against CNPG 1.30.0's installed CRD schema during planning (research R1, R2).
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ClusterSpec(
    instances: Int = 1,
    storage: Option[StorageSpec] = None,
    /**
     * Only set for the control plane's own cluster.
     *
     * A project's cluster hosts databases that arrive as `Database` objects — bootstrapping one
     * named database into it would create a database no service owns. Omitted entirely for a
     * project cluster.
     */
    bootstrap: Option[BootstrapSpec] = None,
    /**
     * Feature 014: the project's own database authority as CNPG's client CA, and a replication
     * certificate from it — CNPG's documented cert-manager shape. The server CA stays CNPG's.
     */
    certificates: Option[CertificatesSpec] = None,
    /**
     * Feature 014: a `cert` rule for the members of `ankka_tls`, ahead of CNPG's password default.
     */
    postgresql: Option[PostgresqlSpec] = None,
    /** Feature 014: the `ankka_tls` group every provisioned role joins. */
    managed: Option[ManagedSpec] = None,
    /** Feature 041: the Barman Cloud plugin as the WAL archiver, naming the line of history. */
    plugins: Option[Vector[PluginConfiguration]] = None,
    /** Feature 041: where a restore reads its line of history from. */
    externalClusters: Option[Vector[ExternalCluster]] = None
)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class CertificatesSpec(clientCASecret: String = "", replicationTLSSecret: String = "")

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class PostgresqlSpec(
    @com.fasterxml.jackson.annotation.JsonProperty("pg_hba") pgHba: Vector[String] = Vector.empty,
    /** Feature 041: `archive_timeout`, which bounds the archive's lag at a low write rate. */
    parameters: Option[Map[String, String]] = None,
    /** Feature 041: a write waits for a replica (`dataDurability: required`). */
    synchronous: Option[SynchronousSpec] = None
)

/**
 * A plugin CNPG loads for the cluster (feature 041). `isWALArchiver` makes it the archiver, and
 * `serverName` among the parameters is the line of history it archives under.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class PluginConfiguration(
    name: String = "",
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentAs =
      classOf[java.lang.Boolean]
    )
    isWALArchiver: Option[Boolean] = None,
    parameters: Map[String, String] = Map.empty
)

/** A cluster read from elsewhere: for a restore, a line of history through the plugin. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ExternalCluster(name: String = "", plugin: Option[PluginConfiguration] = None)

/** Quorum of `number` among the replicas; `required` makes a write wait rather than be lost. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class SynchronousSpec(
    method: String = "any",
    number: Int = 1,
    dataDurability: String = "required"
)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ManagedSpec(roles: Vector[ManagedRole] = Vector.empty)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ManagedRole(name: String = "", login: Boolean = false, ensure: String = "present")

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class StorageSpec(size: String = "1Gi")

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class BootstrapSpec(
    initdb: Option[InitdbSpec] = None,
    /** Feature 041: a restore, from an external cluster's archive, to a moment. */
    recovery: Option[RecoverySpec] = None
)

/**
 * A cluster made from a line of history (feature 041). `database` and `owner` are `postgres` so
 * CNPG's step after recovery creates nothing: its default would add an `app` database and role to
 * every restore. `secret` names an existing owner's credential, for the control plane's restore.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class RecoverySpec(
    source: String = "",
    database: Option[String] = None,
    owner: Option[String] = None,
    secret: Option[LocalRef] = None,
    recoveryTarget: Option[RecoveryTarget] = None
)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class LocalRef(name: String = "")

/**
 * RFC 3339 with a zone: CNPG reads a time without one as UTC, and the platform always writes UTC.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class RecoveryTarget(targetTime: Option[String] = None)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class InitdbSpec(database: String = "", owner: String = "")

/**
 * What ankka reads of a cluster's status. Since feature 041, more than the instances: the phase and
 * its reason (a restore's progress), the conditions (`ContinuousArchiving` says whether the archive
 * is working), and the primary.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ClusterStatus(
    readyInstances: Int = 0,
    instances: Int = 0,
    phase: Option[String] = None,
    phaseReason: Option[String] = None,
    currentPrimary: Option[String] = None,
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentAs =
      classOf[java.lang.Integer]
    )
    timelineID: Option[Int] = None,
    instanceNames: Vector[String] = Vector.empty,
    conditions: Vector[ClusterCondition] = Vector.empty
)

/** A Kubernetes condition as CNPG writes one. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ClusterCondition(
    `type`: String = "",
    status: String = "",
    reason: Option[String] = None,
    message: Option[String] = None,
    lastTransitionTime: Option[String] = None
)

@Group("postgresql.cnpg.io")
@Version("v1")
@Kind("Cluster")
@Plural("clusters")
class PostgresCluster extends CustomResource[ClusterSpec, ClusterStatus] with Namespaced:
  override protected def initSpec(): ClusterSpec = ClusterSpec()
  // Null, not a default: no status at all is meaningfully different from "0 ready instances" —
  // the latter could mean CNPG has reported and found nothing ready yet.
  override protected def initStatus(): ClusterStatus = null

object PostgresCluster:
  val identity: CnpgDefinitions.Identity = CnpgDefinitions.identityOf(classOf[PostgresCluster])

  def apply(namespace: String, name: String, spec: ClusterSpec): PostgresCluster =
    val resource = new PostgresCluster
    resource.setMetadata(
      new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
        .withNamespace(namespace)
        .withName(name)
        .build()
    )
    resource.setSpec(spec)
    resource
