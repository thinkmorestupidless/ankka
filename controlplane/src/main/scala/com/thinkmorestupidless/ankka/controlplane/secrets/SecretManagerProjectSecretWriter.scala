package com.thinkmorestupidless.ankka.controlplane.secrets

import com.thinkmorestupidless.ankka.controlplane.deploy.ProjectSecretWriter
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.secrets.{
  AccessTokens,
  DerivedIds,
  SecretManager,
  SecretManagerException
}
import com.typesafe.config.Config

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.FiniteDuration

/**
 * Project secrets on the Secret Manager backend: each entry of a project secret is a secret in
 * Secret Manager under the id `DerivedIds` derives from the project, the project secret and the
 * entry, and setting it adds a version.
 *
 * The control plane's own identity may add, list and disable versions of entries and may not read
 * one — as on Kubernetes, where it holds `create` and `patch` and no `get` — so this writer, like
 * the cluster's, can put a value where a service's instances are given it and can never read one
 * back. Removing an entry disables its versions rather than destroying them; the provider's sync
 * then removes it from the project's secret in the cluster.
 *
 * A refusal or an outage is thrown, and the route records nothing: the cloud is written first, then
 * the journal, as for every credential the control plane hands on.
 */
final class SecretManagerProjectSecretWriter(
    client: SecretManager,
    location: Option[String] = None,
    kmsKey: Option[String] = None
) extends ProjectSecretWriter:

  def setEntries(projectId: String, name: String, entries: Map[String, String]): Unit =
    entries.toVector.sortBy(_._1).foreach { (entry, value) =>
      val id = DerivedIds.projectEntry(projectId, name, entry)
      mapped(id) {
        client.createSecret(
          id,
          DerivedIds.entryAnnotations(projectId, name, entry),
          location,
          kmsKey
        ): Unit
        client.addVersion(id, value.getBytes(StandardCharsets.UTF_8)): Unit
      }
    }

  def removeEntry(projectId: String, name: String, entry: String): Unit =
    val id = DerivedIds.projectEntry(projectId, name, entry)
    mapped(id)(client.listEnabledVersions(id).foreach(number => client.disableVersion(id, number)))

  private def mapped(id: String)(work: => Unit): Unit =
    try work
    catch
      case e: SecretManagerException if e.kind == SecretManagerException.Kind.Denied =>
        throw CommandError(
          s"the control plane has not been given access to project secrets in Secret Manager " +
            s"(${e.getMessage}); its secret access must admit adding versions under 'p_' " +
            s"(the entry's id is '$id')",
          ErrorCode.Unavailable
        )

object SecretManagerProjectSecretWriter:

  /** The control plane's writer on Secret Manager, as the installation's settings describe it. */
  def from(config: Config, backend: SecretBackendConfig): SecretManagerProjectSecretWriter =
    val token = config.getString("ankka.secrets.secret-manager.token").trim
    val client = SecretManager(
      config.getString("ankka.secrets.secret-manager.endpoint"),
      backend.cloudAccount.getOrElse(
        throw IllegalArgumentException("Secret Manager needs a cloud account")
      ),
      if token.nonEmpty then AccessTokens.fixed(token) else AccessTokens.metadata(),
      FiniteDuration(config.getDuration("ankka.secrets.timeout").toMillis, "ms")
    )
    SecretManagerProjectSecretWriter(client, backend.cloudLocation)
