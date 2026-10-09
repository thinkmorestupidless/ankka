package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.CloudConfig
import com.thinkmorestupidless.ankka.http.*

/**
 * `GET /installation` (feature 044): what the installation is — its version, its cloud provider,
 * account and location when it names one, and where it keeps its secrets (feature 038). Any member
 * may read it. The wrapping key's name is shown only to an owner of an organization, or a platform
 * administrator: who is an owner is a fact of the organizations, so it is decided here, in the
 * endpoint, as every check across entities is.
 */
final class InstallationEndpoint(
    clients: EndpointClients,
    val acl: Acl,
    cloud: Option[CloudConfig],
    platformVersion: String,
    clock: java.time.Clock = java.time.Clock.systemUTC(),
    /** Where it keeps its secrets (feature 038); none from a control plane that says nothing. */
    secrets: () => Option[InstallationSecrets] = () => None
) extends HttpEndpoint("/installation"):

  private val authz = com.thinkmorestupidless.ankka.controlplane.auth.Authorization(clients, clock)

  get("/") { () =>
    Installation(
      platformVersion = platformVersion,
      cloud = cloud.map { c =>
        CloudInstallation(
          provider = c.provider,
          account = c.account,
          location = c.location,
          kmsKey = c.kmsKey.filter(_ => ownsSomething)
        )
      },
      secrets = secrets()
    )
  }

  private def ownsSomething: Boolean =
    authz.isAdmin(principal) ||
      authz.visible(principal).exists(_.roleOf(principal.subject).contains(Role.Owner))
