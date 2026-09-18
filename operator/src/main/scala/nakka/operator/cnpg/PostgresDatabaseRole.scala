package nakka.operator.cnpg

import com.fasterxml.jackson.annotation.JsonInclude
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, Version}

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class PasswordSecretRef(name: String = "")

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class DatabaseRoleSpec(
    name: String = "",
    cluster: ClusterRef = ClusterRef(),
    login: Boolean = true,
    /**
     * Must already exist. CNPG does not generate a password — verified (research R3): given no
     * secret, it creates a role with no password at all. The secret must be
     * `kubernetes.io/basic-auth` with both `username` and `password`.
     */
    passwordSecret: PasswordSecretRef = PasswordSecretRef(),
    databaseRoleReclaimPolicy: String = "retain"
)

@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class DatabaseRoleStatus(applied: Boolean = false, message: Option[String] = None)
    extends CnpgReconcileStatus

@Group("postgresql.cnpg.io")
@Version("v1")
@Kind("DatabaseRole")
@Plural("databaseroles")
class PostgresDatabaseRole
    extends CustomResource[DatabaseRoleSpec, DatabaseRoleStatus]
    with Namespaced:
  override protected def initSpec(): DatabaseRoleSpec     = DatabaseRoleSpec()
  override protected def initStatus(): DatabaseRoleStatus = null

object PostgresDatabaseRole:
  val identity: CnpgDefinitions.Identity = CnpgDefinitions.identityOf(classOf[PostgresDatabaseRole])

  def apply(namespace: String, name: String, spec: DatabaseRoleSpec): PostgresDatabaseRole =
    val resource = new PostgresDatabaseRole
    resource.setMetadata(
      new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
        .withNamespace(namespace)
        .withName(name)
        .build()
    )
    resource.setSpec(spec)
    resource
