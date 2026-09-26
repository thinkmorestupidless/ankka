import { Acl, Done, Endpoint, HttpProblem, del, get, post, s, sse } from "ankka"
import { LineItem, ShoppingCart } from "./domain.ts"
import { ShoppingCartEntity } from "./entity.ts"
import { CartRow, CartRows } from "./cartRows.ts"
import { Checkout, CheckoutWorkflow } from "./checkoutWorkflow.ts"
import { CheckoutLog, CheckoutRecord } from "./checkoutLog.ts"
import { CartAssistant } from "./assistant.ts"

// docs:start endpoint
/** The Scala sample's routes, exactly: /carts/{cartId}, /total, /items, /items/{productId}, /checkout. */
export class ShoppingCartEndpoint extends Endpoint {
  static readonly prefix = "/carts"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    getCart: get("/{cartId}", ShoppingCart, (ep: ShoppingCartEndpoint, req) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.getCart).invoke()),
    total: get("/{cartId}/total", s.int, (ep: ShoppingCartEndpoint, req) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.totalQuantity).invoke()),
    addItem: post("/{cartId}/items", LineItem, Done, (ep: ShoppingCartEndpoint, req, item) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.addItem).invoke(item)),
    removeItem: del("/{cartId}/items/{productId}", Done, (ep: ShoppingCartEndpoint, req) =>
      ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.removeItem).invoke(req.params.productId),
    ),
    checkout: post("/{cartId}/checkout", ShoppingCart, (ep: ShoppingCartEndpoint, req) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.checkout).invoke()),
    // docs:end endpoint

    // A literal beside a parameter: the router must prefer it over /{cartId}.
    awkward: get("/awkward", s.string, () => "literal"),

    // ── The view, the workflow and the notifier's log ──
    // docs:start problem
    row: get("/{cartId}/rows", CartRow, async (ep: ShoppingCartEndpoint, req) => {
      const found = await ep.client.views.get(CartRows.componentId, req.params.cartId, CartRow)
      if (found === null) throw new HttpProblem(404, `no row for cart '${req.params.cartId}'`)
      return found
    }),
    // docs:end problem
    rows: get("/rows", s.list(CartRow), (ep: ShoppingCartEndpoint) => ep.client.views.all(CartRows.componentId, CartRow)),
    // docs:start start-workflow
    /** The body is the mode: `ok`, `fail` or `pause`. */
    startCheckout: post("/{cartId}/checkouts", s.string, Done, (ep: ShoppingCartEndpoint, req, mode) =>
      ep.client.of(CheckoutWorkflow, req.params.cartId).call(CheckoutWorkflow.handlers.start).invoke(mode || "ok"),
    ),
    // docs:end start-workflow
    checkoutStatus: get("/{cartId}/checkouts", Checkout, (ep: ShoppingCartEndpoint, req) =>
      ep.client.of(CheckoutWorkflow, req.params.cartId).call(CheckoutWorkflow.handlers.status).invoke(),
    ),
    checkoutLog: get("/{cartId}/checkout-log", CheckoutRecord, (ep: ShoppingCartEndpoint, req) =>
      ep.client.of(CheckoutLog, req.params.cartId).call(CheckoutLog.handlers.get).invoke(),
    ),

    // ── The assistant: a POST answers whole, a GET streams tokens as SSE ──
    // docs:start agent-routes
    ask: post("/ask/{session}", s.string, s.string, (ep: ShoppingCartEndpoint, req, question) =>
      ep.client.of(CartAssistant, req.params.session).call(CartAssistant.handlers.ask).invoke(question),
    ),
    chat: sse("/chat/{session}", (ep: ShoppingCartEndpoint, req) =>
      ep.client.of(CartAssistant, req.params.session).call(CartAssistant.handlers.chat).stream(req.query.get("q") ?? ""),
    ),
    // docs:end agent-routes
  }

  cart(cartId: string) {
    return this.client.of(ShoppingCartEntity, cartId)
  }
}
