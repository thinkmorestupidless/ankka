package com.thinkmorestupidless.ankka.controlplane.secrets

import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.runtime.secrets.SecretBackend
import com.typesafe.config.Config

/**
 * The installation's secret backend and cloud, as the control plane reads them at start.
 *
 * The control plane is where a backend the installation cannot have is refused: `secret-manager`
 * needs a cloud provider that can fulfil it and a cloud account to keep secrets in (spec 044's
 * FR-012). A control plane that started anyway would write project secrets where nothing could read
 * them.
 */
final case class SecretBackendConfig(
    backend: SecretBackend,
    cloudProvider: String,
    cloudAccount: Option[String],
    cloudLocation: Option[String]
)

object SecretBackendConfig:

  /** The providers the platform knows, and which backends each can fulfil. */
  val KnownProviders: Vector[String] = Vector("none", "gcp")

  def from(config: Config): SecretBackendConfig =
    def text(key: String) = if config.hasPath(key) then config.getString(key).trim else ""
    val backend = SecretBackend.from(config).fold(p => throw IllegalArgumentException(p), identity)
    val provider = text("ankka.cloud.provider") match
      case ""   => "none"
      case name => name
    if !KnownProviders.contains(provider) then
      throw IllegalArgumentException(
        s"${PlatformVariables.CloudProvider} is '$provider', which is not a cloud provider the " +
          s"platform knows; use one of ${KnownProviders.mkString(", ")}"
      )
    val account  = Some(text("ankka.cloud.account")).filter(_.nonEmpty)
    val location = Some(text("ankka.cloud.location")).filter(_.nonEmpty)
    if backend == SecretBackend.SecretManager then
      if provider == "none" then
        throw IllegalArgumentException(
          s"${PlatformVariables.SecretBackend} is secret-manager, which needs the cloud provider " +
            s"gcp; ${PlatformVariables.CloudProvider} is none"
        )
      if account.isEmpty then
        throw IllegalArgumentException(
          s"${PlatformVariables.SecretBackend} is secret-manager and " +
            s"${PlatformVariables.CloudAccount} is not set, so there is no Google Cloud project " +
            "to keep secrets in"
        )
    SecretBackendConfig(backend, provider, account, location)
