// An HTTP endpoint: a prefix, an access rule and a table of routes. The sidecar serves the routes on
// the service's HTTP port and forwards each request here; the process never binds an HTTP port. One
// instance serves every request, so per-request state lives on `this.request`, never on a field.
//
//   export class ShoppingCartEndpoint extends Endpoint {
//     static readonly prefix = "/carts"
//     static readonly acl = Acl.allowAll                 // required; there is no default
//     static readonly routes = { getCart: get("/{cartId}", ShoppingCart, (ep: ShoppingCartEndpoint, req) => ep.getCart(req.params.cartId)) }
//     getCart(cartId: string) { return this.client.of(ShoppingCartEntity, cartId).call(ShoppingCartEntity.handlers.getCart).invoke() }
//   }

import { secretsFor, type ComponentClient, type Secrets } from "./client.ts"
import { currentRequest, requestIfAny, type RequestContext } from "./context.ts"
import type { Acl, RouteTable } from "./routes.ts"
import { servicesFor, type Services } from "./services.ts"

export abstract class Endpoint {
  #client: ComponentClient | undefined

  /** The request being handled: path parameters, query, headers, principal. Request-scoped. */
  get request(): RequestContext {
    return currentRequest()
  }

  /** The component client, scoped to the current request's trace so the sidecar records child spans. */
  get client(): ComponentClient {
    if (!this.#client) throw new Error("client is only available inside a route handler")
    const request = requestIfAny()
    return request ? this.#client.withMetadata(request.metadata) : this.#client
  }

  #secrets: Secrets | undefined

  /** The service's secret store: values kept encrypted in the service's own database, never in a journal or a view. */
  get secrets(): Secrets {
    return this.#secrets ?? secretsFor(this.client)
  }

  /** A unit test's store in place of the runtime's: `component.secrets = new InMemorySecrets()`. */
  set secrets(store: Secrets) {
    this.#secrets = store
  }

  #services: Services | undefined

  /** Other services, called as this one, through the runtime: `this.services.service("orders")`. */
  get services(): Services {
    return this.#services ?? servicesFor(this.client)
  }

  /** A unit test's in place of the runtime's: `component.services = new ScriptedServices()`. */
  set services(services: Services) {
    this.#services = services
  }

  /** @internal */
  _bindClient(client: ComponentClient | undefined): void {
    this.#client = client
  }
}

/** The statics an endpoint class must declare; `register` constrains on this type, so a missing `acl` is a compile error. */
export interface EndpointClass<C extends Endpoint = Endpoint> {
  new (): C
  /** `/carts`: every route is relative to it. */
  readonly prefix: string
  /** Who may call, for every route unless a route states its own. */
  readonly acl: Acl
  readonly routes: RouteTable<C>
}
