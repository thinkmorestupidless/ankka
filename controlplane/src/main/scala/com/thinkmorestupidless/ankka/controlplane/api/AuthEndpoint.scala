package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.auth.{AuthConfig, Principals}
import com.thinkmorestupidless.ankka.http.*

/**
 * `GET /auth` — where the identity provider is, so `ankka login` needs no setting beyond the
 * control plane's own address.
 *
 * The one route on the control plane that answers without a credential (besides the health probe),
 * and it reveals only what every token already names. A separate endpoint from `/auth/whoami`
 * because an endpoint has one ACL; the router picks the longest matching prefix, so the two can sit
 * beside each other whatever order they were registered in.
 */
final class AuthDiscoveryEndpoint(config: AuthConfig) extends HttpEndpoint("/auth"):

  val acl: Acl = Acl.AllowAll

  get("/")(() => AuthDiscovery(config.issuer, config.clientId, config.audience))

/** `GET /auth/whoami` — the caller as the control plane sees them, organizations and all. */
final class WhoamiEndpoint(
    clients: EndpointClients,
    val acl: Acl,
    clock: java.time.Clock = java.time.Clock.systemUTC()
) extends HttpEndpoint("/auth/whoami"):

  private val authz = com.thinkmorestupidless.ankka.controlplane.auth.Authorization(clients, clock)

  get("/") { () =>
    // The caller's organizations, from the view, after claiming any invitation waiting for them —
    // so the first `whoami` after being invited already shows the organization (research R9).
    val organizations =
      if authz.isAdmin(principal) then Vector.empty
      else
        authz
          .visible(principal)
          .flatMap(row =>
            row
              .roleOf(principal.subject)
              .map(role => OrganizationMembership(row.id, row.name, role))
          )
    Whoami(
      subject = principal.subject,
      name = principal.name,
      email = principal.email,
      emailVerified = principal.emailVerified,
      platformAdmin = Principals.isPlatformAdmin(principal),
      organizations = organizations
    )
  }
