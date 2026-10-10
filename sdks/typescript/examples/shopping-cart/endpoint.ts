import { Acl, Done, Endpoint, HttpProblem, awaitPartJson, defaultCodecFor, del, get, post, s, socket, sse } from "ankka"
import { LineItem, ShoppingCart } from "./domain.ts"
import { ShoppingCartEntity } from "./entity.ts"
import { CartRow, CartRows } from "./cartRows.ts"
import { Checkout, CheckoutWorkflow } from "./checkoutWorkflow.ts"
import { CheckoutLog, CheckoutRecord } from "./checkoutLog.ts"
import { CartAssistant } from "./assistant.ts"

// docs:start endpoint
/** The Scala sample's routes, exactly: /carts/{cartId}, /total, /items, /items/{productId}, /checkout, DELETE /carts/{cartId}. */
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
    discard: del("/{cartId}", Done, (ep: ShoppingCartEndpoint, req) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.discard).invoke()),
    // docs:end endpoint

    // docs:start socket
    // A socket: the client sends "refresh" and is sent the cart, for as long as it keeps the socket open.
    // `for await` ends when the socket is closed, and so does the handler.
    watch: socket("/{cartId}/watch", async (ep: ShoppingCartEndpoint, req, socket) => {
      for await (const text of socket) {
        if (text === "refresh") {
          const cart = await ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.getCart).invoke()
          await socket.send(new TextDecoder().decode(defaultCodecFor(ShoppingCart).encode(cart)))
        } else {
          await socket.send(JSON.stringify({ error: `unknown request '${text}'; send refresh` }))
        }
      }
    }),
    // docs:end socket

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
    // docs:start start-and-await
    /** Starts the checkout and answers with how it ended, as one request. */
    checkOutAndWait: post("/{cartId}/checkouts/wait", s.string, Checkout, (ep: ShoppingCartEndpoint, req, mode) =>
      ep.client.of(CheckoutWorkflow, req.params.cartId).call(CheckoutWorkflow.handlers.start).thenAwaitEnd<Checkout>(30_000).invoke(mode || "ok"),
    ),
    // docs:end start-and-await
    // docs:start await-later
    /** How a checkout someone else started ended: at once if it has, when it does if not. */
    checkoutEnd: get("/{cartId}/checkouts/end", Checkout, (ep: ShoppingCartEndpoint, req) =>
      ep.client.of(CheckoutWorkflow, req.params.cartId).awaitEnd<Checkout>(10_000),
    ),
    // docs:end await-later
    // docs:start sse-await
    /** A heartbeat while the checkout goes on, then how it ended, on one connection. */
    checkoutEvents: sse("/{cartId}/checkouts/events", async function* (ep: ShoppingCartEndpoint, req) {
      for await (const part of ep.client.of(CheckoutWorkflow, req.params.cartId).awaitEndParts<Checkout>(600_000)) yield awaitPartJson(part)
    }),
    // docs:end sse-await
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
