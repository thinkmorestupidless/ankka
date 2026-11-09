// The cart's tools for an assistant: an MCP endpoint whose tools refer to the cart's own handlers or
// run here, admitting the client keys the cart issues from a route of its own. The protocol, the keys'
// checking and the ACL are the sidecar's.
import { Acl, Endpoint, Expiry, McpEndpoint, del, done, post, s, tools, type Infer } from "ankka"
import { ShoppingCartEntity } from "./entity.ts"

const AddItem = s.record("AddItem", { cartId: s.string, productId: s.string, quantity: s.int })

export class CartMcp extends McpEndpoint {
  static readonly path = "/mcp"
  // docs:start acl
  // A request carrying one of the cart's client keys, as the key's subject (`CartKeysEndpoint`).
  static readonly acl = Acl.clientKeys
  // docs:end acl

  static readonly tools = tools<CartMcp>()
    // docs:start reference
    .query("get_cart", "Reads one cart: its lines, and whether it is checked out", ShoppingCartEntity, "get-cart", {
      id: "cartId",
    })
    // docs:end reference
    // docs:start handler
    .tool("add_item", "Adds a quantity of a product to a cart", AddItem, undefined, async (args, ep) => {
      const item = { productId: args.productId, name: args.productId, quantity: args.quantity }
      await ep.client.of(ShoppingCartEntity, args.cartId).call(ShoppingCartEntity.handlers.addItem).invoke(item)
      return done
    })
  // docs:end handler
}

// docs:start keys
const KeyRequest = s.record("KeyRequest", { subject: s.string, scope: s.list(s.string), label: s.option(s.string) })
const IssuedKey = s.record("IssuedKey", { id: s.string, clientKey: s.string })
export type IssuedKey = Infer<typeof IssuedKey>

/**
 * Client keys for the cart's tools: one per customer's assistant, issued by the cart's own code,
 * because only the cart knows who its customers are. The key is in the answer to the issue, once;
 * after that the cart lists and revokes keys by id.
 *
 * Open to every caller here, as the rest of the sample is. A real product puts these routes behind
 * its own sign-in, and issues a key for the customer who is signed in.
 */
export class CartKeysEndpoint extends Endpoint {
  static readonly prefix = "/mcp-keys"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    issue: post("/", KeyRequest, IssuedKey, async (ep: CartKeysEndpoint, _req, asked) => {
      const issued = await ep.clientKeys.issue({
        subject: asked.subject,
        expiry: Expiry.after({ days: 365 }),
        scope: asked.scope,
        label: asked.label ?? undefined,
      })
      return { id: issued.id, clientKey: issued.clientKey }
    }),
    revoke: del("/{id}", s.string, async (ep: CartKeysEndpoint, req) => {
      await ep.clientKeys.revoke(req.params.id)
      return "revoked"
    }),
  }
}
// docs:end keys
