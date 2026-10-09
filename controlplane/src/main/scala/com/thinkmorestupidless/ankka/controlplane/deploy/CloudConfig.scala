package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.typesafe.config.Config

/**
 * The installation's cloud as the control plane reads it (feature 044): the same four values the
 * operator reads, so the two cannot disagree about which provider and account the installation has.
 * `None` is an installation with no cloud provider.
 */
final case class CloudConfig(
    provider: String,
    account: String,
    location: String,
    kmsKey: Option[String]
)

object CloudConfig:

  /**
   * `ankka.controlplane.cloud`. A provider the platform does not know refuses to start, naming the
   * ones it does, as the operator refuses: one that started would show members a cloud nothing
   * fulfils.
   */
  def from(config: Config): Option[CloudConfig] =
    val section           = config.getConfig("ankka.controlplane.cloud")
    def text(key: String) = Option(section.getString(key)).map(_.trim).filter(_.nonEmpty)
    text("provider").filterNot(_ == PlatformVariables.CloudProviderNone).map { provider =>
      if !PlatformVariables.CloudProviders.contains(provider) then
        throw IllegalStateException(
          s"${PlatformVariables.CloudProvider} is '$provider', which the platform does not know; " +
            s"it is '${PlatformVariables.CloudProviderNone}' or one of " +
            PlatformVariables.CloudProviders.toVector.sorted.mkString(", ")
        )
      CloudConfig(
        provider = provider,
        account = text("account").getOrElse(""),
        location = text("location").getOrElse(""),
        kmsKey = text("kms-key")
      )
    }
