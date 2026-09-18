package nakka.operator.cnpg

import com.fasterxml.jackson.annotation.JsonInclude
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, Version}

/**
 * The reference to a `Cluster`, shared by `Database` and `DatabaseRole` — same-namespace only,
 * verified directly (research R2): a cross-namespace reference reconciles nothing at all.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ClusterRef(name: String = "")

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class DatabaseSpec(
    name: String = "",
    owner: String = "",
    cluster: ClusterRef = ClusterRef(),
    /** Never `delete` (FR-024) — withheld at the RBAC layer too, so this is belt and braces. */
    databaseReclaimPolicy: String = "retain"
)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class DatabaseStatus(applied: Boolean = false, message: Option[String] = None)
    extends CnpgReconcileStatus

@Group("postgresql.cnpg.io")
@Version("v1")
@Kind("Database")
@Plural("databases")
class PostgresDatabase extends CustomResource[DatabaseSpec, DatabaseStatus] with Namespaced:
  override protected def initSpec(): DatabaseSpec     = DatabaseSpec()
  override protected def initStatus(): DatabaseStatus = null

object PostgresDatabase:
  val identity: CnpgDefinitions.Identity = CnpgDefinitions.identityOf(classOf[PostgresDatabase])

  def apply(namespace: String, name: String, spec: DatabaseSpec): PostgresDatabase =
    val resource = new PostgresDatabase
    resource.setMetadata(
      new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
        .withNamespace(namespace)
        .withName(name)
        .build()
    )
    resource.setSpec(spec)
    resource
