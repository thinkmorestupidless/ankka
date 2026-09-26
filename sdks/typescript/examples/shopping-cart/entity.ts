import { Done, done, ErrorCode, EventSourcedEntity, command, jsonCodec, query, s } from "ankka"
import { LineItem, ShoppingCart, ShoppingCartEvent, addItem, contains, emptyCart, removeItem, totalQuantity } from "./domain.ts"

// docs:start entity
export class ShoppingCartEntity extends EventSourcedEntity<ShoppingCart, ShoppingCartEvent> {
  static readonly componentId = "shopping-cart"
  static readonly state = jsonCodec(ShoppingCart, "shopping-cart")
  static readonly events = jsonCodec(ShoppingCartEvent, "shopping-cart-event")
  static readonly snapshotEvery = 100

  static readonly handlers = {
    addItem: command("add-item", LineItem, Done, (cart: ShoppingCartEntity, item) => cart.addItem(item)),
    removeItem: command("remove-item", s.string, Done, (cart: ShoppingCartEntity, productId) => cart.removeItem(productId)),
    checkout: command("checkout", ShoppingCart, (cart: ShoppingCartEntity) => cart.checkout()),
    getCart: query("get-cart", ShoppingCart, (cart: ShoppingCartEntity) => cart.effects.reply(cart.state)),
    totalQuantity: query("total-quantity", s.int, (cart: ShoppingCartEntity) => cart.effects.reply(totalQuantity(cart.state))),
  }

  emptyState(): ShoppingCart {
    return emptyCart(this.entityId)
  }

  applyEvent(cart: ShoppingCart, event: ShoppingCartEvent): ShoppingCart {
    switch (event.type) {
      case "ItemAdded":
        return addItem(cart, event.item)
      case "ItemRemoved":
        return removeItem(cart, event.productId)
      case "CheckedOut":
        return { ...cart, checkedOut: true }
    }
  }

  addItem(item: LineItem) {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    if (item.quantity <= 0) return this.effects.error(`quantity must be greater than zero, was ${item.quantity}`)
    return this.effects.persist({ type: "ItemAdded", item }).thenReply(() => done)
  }

  removeItem(productId: string) {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    if (!contains(this.state, productId)) return this.effects.error(`cart does not contain '${productId}'`, ErrorCode.NotFound)
    return this.effects.persist({ type: "ItemRemoved", productId }).thenReply(() => done)
  }

  checkout() {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    if (this.state.items.length === 0) return this.effects.error("cannot check out an empty cart")
    // As the Scala cart: the event is persisted, then the cart is deleted, so a consumer downstream
    // still sees the checkout rather than a cart that vanished.
    return this.effects.persist({ type: "CheckedOut" }).deleteEntity().thenReplyState()
  }
}
// docs:end entity
