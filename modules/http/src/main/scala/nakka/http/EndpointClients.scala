package nakka.http

import nakka.runtime.ViewClient
import nakka.sdk.ComponentClient

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
final class EndpointClients private[nakka] (
    val componentClient: ComponentClient,
    val viewClient: ViewClient
)
