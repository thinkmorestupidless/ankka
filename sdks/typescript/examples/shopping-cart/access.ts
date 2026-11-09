// Who may call: an endpoint whose users sign in, routes that name the workloads they admit, a route
// another project may be granted, and one endpoint with two audiences. The sidecar applies each rule
// before the process is asked anything, and hands the handler the caller and the principal.
import { Acl, Callers, Endpoint, HttpProblem, type Caller, del, get, post, s, sse, type Infer } from "ankka"
import { WalletEntity } from "./wallet.ts"

// docs:start query
const SearchResult = s.record("SearchResult", { term: s.string, limit: s.int, tags: s.list(s.string), verbose: s.boolean })

export class SearchEndpoint extends Endpoint {
  static readonly prefix = "/search"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    /** Required, optional-with-default, repeated, and flag parameters. */
    search: get("/", SearchResult, (_ep: SearchEndpoint, req) => {
      const term = req.query.get("q")
      if (term === null) throw new HttpProblem(400, "the query parameter 'q' is required")
      const limit = req.query.get("limit")
      return {
        term,
        limit: limit === null ? 20 : Number(limit),
        tags: req.query.getAll("tag"),
        verbose: req.query.has("verbose") && req.query.get("verbose") !== "false",
      }
    }),
    // docs:end query
    // docs:start header
    /** Headers. */
    trace: get("/trace", s.string, (_ep: SearchEndpoint, req) => req.headers.get("X-Trace-Id") ?? "none"),
    // docs:end header
    // docs:start sse-query
    stream: sse("/stream", async function* (_ep: SearchEndpoint, req) {
      const term = req.query.get("q") ?? ""
      const count = Number(req.query.get("count") ?? 2)
      for (let n = 1; n <= count; n++) yield `${term}-${n}`
    }),
    // docs:end sse-query
  }
}

// docs:start account
/** An endpoint whose users sign in with an identity provider the service lists. */
export class AccountEndpoint extends Endpoint {
  static readonly prefix = "/account"
  static readonly acl = Acl.authenticated

  static readonly routes = {
    me: get("/me", s.string, (_ep: AccountEndpoint, req) => {
      const p = req.principal!
      return `${p.subject} from ${p.issuer ?? "?"} roles=${[...p.roles].sort().join(",")} tier=${p.claims.tier ?? ""}`
    }),
  }
}
// docs:end account

export class CallersEndpoint extends Endpoint {
  static readonly prefix = "/callers"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    // docs:start allow-callers
    // Only the internet and the orders service in this project; any other caller is refused 403.
    onlyOrders: get("/only-orders", s.string, (_ep: CallersEndpoint, req) => `admitted: ${describe(req.caller)}`, {
      acl: Acl.allowCallers(Callers.internet, Callers.service("orders")),
    }),
    // Another instance of this very service, and nothing else.
    onlySelf: get("/only-self", s.string, () => "admitted: myself", { acl: Acl.allowCallers(Callers.self) }),
    // docs:end allow-callers
    // docs:start who-is-calling
    whoami: get("/whoami", s.string, (_ep: CallersEndpoint, req) => whoIsCalling(req.caller)),
    // docs:end who-is-calling
  }
}

function whoIsCalling(c: Caller): string {
  switch (c.kind) {
    case "gateway":
      return "the internet, through the gateway"
    case "service":
      return `the ${c.name} service in project ${c.project}`
    case "machine":
      return `the machine ${c.name} of organization ${c.organization}`
    case "local":
      return "this machine"
  }
}

function describe(c: Caller): string {
  return c.kind === "service" ? `${c.project}/${c.name}` : c.kind
}

// docs:start granted-callers
const DepositRequest = s.record("DepositRequest", { amount: s.long })
const DepositMade = s.record("DepositMade", { currency: s.string, balance: s.long, applied: s.boolean, by: s.string })

/**
 * A player's wallet, which another project may be granted. The endpoint's ACL admits granted
 * callers: whether anyone outside the project may ever call these routes is decided here, in
 * reviewed code; who may, today, is a grant the project's owners make and revoke as data.
 */
export class WalletEndpoint extends Endpoint {
  static readonly prefix = "/v1/wallets"
  static readonly acl = Acl.allowCallers(Callers.granted)

  static readonly routes = {
    /**
     * A deposit, with the caller's idempotency key: a granted call is delivered at least once, and
     * the wallet applies each key once. The answer names the caller as the platform established it.
     */
    deposit: post("/{player}/{currency}/deposits", DepositRequest, DepositMade, async (ep: WalletEndpoint, req, body) => {
      const key = req.headers.get("Idempotency-Key")
      if (key === null) throw new HttpProblem(400, "a deposit needs an Idempotency-Key header")
      const deposit = { key, currency: req.params.currency, amount: body.amount }
      const reply = await ep.client.of(WalletEntity, req.params.player).call(WalletEntity.handlers.deposit).invoke(deposit)
      return { ...reply, by: describe(req.caller) }
    }),
  }
}
// docs:end granted-callers

// docs:start route-acl
/**
 * One endpoint, two audiences: reading a cart is public, purging one is not.
 *
 * A route's `acl` replaces the endpoint's for that route, so neither audience needs an endpoint of
 * its own at a second prefix.
 */
export class MixedAclEndpoint extends Endpoint {
  static readonly prefix = "/mixed"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    read: get("/{cartId}", s.string, (_ep: MixedAclEndpoint, req) => `cart:${req.params.cartId}`),
    purge: del("/{cartId}", s.string, (_ep: MixedAclEndpoint, req) => `purged:${req.params.cartId} by ${req.principal!.subject}`, {
      acl: Acl.authenticated,
    }),
  }
}
// docs:end route-acl

// docs:start route-acl-closed
export class CartsEndpoint extends Endpoint {
  static readonly prefix = "/carts"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    getCart: get("/{cartId}", s.string, (_ep: CartsEndpoint, req) => `cart:${req.params.cartId}`),
    purge: del("/{cartId}", s.string, () => "never reached", { acl: Acl.denyAll }),
  }
}
// docs:end route-acl-closed

export type SearchResult = Infer<typeof SearchResult>
