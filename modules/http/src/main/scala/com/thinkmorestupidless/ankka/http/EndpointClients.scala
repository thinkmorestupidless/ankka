package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, ServiceClient, ServiceClients}

/**
 * What an endpoint is handed when the server builds it.
 *
 * A bundle rather than a bare `ComponentClient` because an endpoint routinely needs both halves of
 * the read/write split: commands go to an entity by id, listings come from a view queried by
 * attribute. Passing one object also means adding a client later is not a breaking change to every
 * endpoint's registration.
 *
 * An endpoint that needs only one takes only one — `MyEndpoint(_.componentClient)`.
 */
final class EndpointClients private[ankka] (
    val componentClient: ComponentClient,
    val viewClient: ViewClient,
    /**
     * Other services, called as this one (feature 014):
     * `clients.services("orders").get[Order](...)`. In a cluster the call carries this service's
     * certificate, so the callee's ACL knows who is calling; locally it reaches the named service
     * on this machine.
     */
    val services: ServiceClients = EndpointClients.noServices
)

object EndpointClients:
  private[ankka] val noServices: ServiceClients = new ServiceClients:
    def apply(name: String): ServiceClient =
      throw IllegalStateException(s"no service client is configured; cannot call '$name'")
    def apply(project: String, name: String): ServiceClient = apply(name)
