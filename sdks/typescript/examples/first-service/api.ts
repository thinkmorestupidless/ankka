// The first service's endpoint, as the getting-started page shows it.
// docs:start endpoint
import { Acl, Done, Endpoint, get, post } from "ankka"
import { LineItem, ShoppingCart, ShoppingCartEntity } from "./cart.ts"

export class ShoppingCartEndpoint extends Endpoint {
  static readonly prefix = "/carts"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    addItem: post("/{cartId}/items", LineItem, Done, (ep: ShoppingCartEndpoint, req, item) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.addItem).invoke(item)),
    checkout: post("/{cartId}/checkout", ShoppingCart, (ep: ShoppingCartEndpoint, req) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.checkout).invoke()),
    getCart: get("/{cartId}", ShoppingCart, (ep: ShoppingCartEndpoint, req) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.getCart).invoke()),
  }

  cart(cartId: string) {
    return this.client.of(ShoppingCartEntity, cartId)
  }
}
// docs:end endpoint
