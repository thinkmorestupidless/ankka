package com.thinkmorestupidless.ankka.controlplane.secrets

import com.thinkmorestupidless.ankka.controlplane.deploy.CloudConfig
import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.runtime.secrets.SecretBackend
import com.typesafe.config.Config

/**
 * The installation's secret backend, and the cloud it is kept in, as the control plane reads them
 * at start. The cloud is 044's `CloudConfig`, read from the same settings the operator reads.
 *
 * The control plane is where a backend the installation cannot have is refused: `secret-manager`
 * needs a cloud provider that can fulfil it and a cloud account to keep secrets in (spec 044's
 * FR-012). A control plane that started anyway would write project secrets where nothing could read
 * them.
 */
final case class SecretBackendConfig(backend: SecretBackend, cloud: Option[CloudConfig]):

  /** The account secrets are kept in on Secret Manager; none on Postgres or without a cloud. */
  def cloudAccount: Option[String] = cloud.map(_.account).filter(_.nonEmpty)

  /** The location secrets are replicated to on Secret Manager, when the installation names one. */
  def cloudLocation: Option[String] = cloud.map(_.location).filter(_.nonEmpty)

object SecretBackendConfig:

  def from(config: Config): SecretBackendConfig =
    val backend = SecretBackend.from(config).fold(p => throw IllegalArgumentException(p), identity)
    val cloud   = CloudConfig.from(config)
    if backend == SecretBackend.SecretManager then
      if cloud.isEmpty then
        throw IllegalArgumentException(
          s"${PlatformVariables.SecretBackend} is secret-manager, which needs the cloud provider " +
            s"gcp; ${PlatformVariables.CloudProvider} is ${PlatformVariables.CloudProviderNone}"
        )
      if cloud.exists(_.account.isEmpty) then
        throw IllegalArgumentException(
          s"${PlatformVariables.SecretBackend} is secret-manager and " +
            s"${PlatformVariables.CloudAccount} is not set, so there is no Google Cloud project " +
            "to keep secrets in"
        )
    SecretBackendConfig(backend, cloud)
