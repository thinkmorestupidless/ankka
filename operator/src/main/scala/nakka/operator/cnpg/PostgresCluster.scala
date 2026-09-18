package nakka.operator.cnpg

import com.fasterxml.jackson.annotation.JsonInclude
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, Version}

/**
 * A partial model of CNPG's `Cluster` — the six fields nakka ever sets, nothing else.
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
    bootstrap: Option[BootstrapSpec] = None
)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class StorageSpec(size: String = "1Gi")

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class BootstrapSpec(initdb: Option[InitdbSpec] = None)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class InitdbSpec(database: String = "", owner: String = "")

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ClusterStatus(readyInstances: Int = 0)

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
