package com.thinkmorestupidless.ankka.operator.cnpg

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
     * Rendered for roles provisioned before feature 014 only as the observation of an old role; the
     * operator no longer renders one. CNPG does not generate a password — given no secret it
     * creates a role with none — which is now exactly what is wanted.
     */
    passwordSecret: Option[PasswordSecretRef] = None,
    databaseRoleReclaimPolicy: String = "retain",
    /**
     * Feature 014: the role has no password at all, so the only way to log in as it is the `cert`
     * rule for `ankka_tls` members. Setting it on a pre-feature role is what retires that role's
     * generated password.
     */
    disablePassword: Option[Boolean] = None,
    inRoles: Vector[String] = Vector.empty
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
