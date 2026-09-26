// The first service's entity, as the getting-started page shows it. Tested by cart.test.ts.
// docs:start entity
import { Done, done, ErrorCode, EventSourcedEntity, command, jsonCodec, query, s, type Infer } from "ankka"

export const LineItem = s.record("LineItem", { productId: s.string, name: s.string, quantity: s.int })
export type LineItem = Infer<typeof LineItem>

export const ShoppingCart = s.record("ShoppingCart", { cartId: s.string, items: s.list(LineItem), checkedOut: s.boolean })
export type ShoppingCart = Infer<typeof ShoppingCart>

export const ShoppingCartEvent = s.sumType("ShoppingCartEvent", { ItemAdded: { item: LineItem }, CheckedOut: {} })
export type ShoppingCartEvent = Infer<typeof ShoppingCartEvent>

export class ShoppingCartEntity extends EventSourcedEntity<ShoppingCart, ShoppingCartEvent> {
  static readonly componentId = "shopping-cart"
  static readonly state = jsonCodec(ShoppingCart, "shopping-cart")
  static readonly events = jsonCodec(ShoppingCartEvent, "shopping-cart-event")

  static readonly handlers = {
    addItem: command("add-item", LineItem, Done, (cart: ShoppingCartEntity, item) => cart.addItem(item)),
    checkout: command("checkout", ShoppingCart, (cart: ShoppingCartEntity) => cart.checkout()),
    getCart: query("get-cart", ShoppingCart, (cart: ShoppingCartEntity) => cart.effects.reply(cart.state)),
  }

  emptyState(): ShoppingCart {
    return { cartId: this.entityId, items: [], checkedOut: false }
  }

  applyEvent(cart: ShoppingCart, event: ShoppingCartEvent): ShoppingCart {
    switch (event.type) {
      case "ItemAdded":
        return { ...cart, items: [...cart.items, event.item] }
      case "CheckedOut":
        return { ...cart, checkedOut: true }
    }
  }

  addItem(item: LineItem) {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    if (item.quantity <= 0) return this.effects.error(`quantity must be greater than zero, was ${item.quantity}`)
    return this.effects.persist({ type: "ItemAdded", item }).thenReply(() => done)
  }

  checkout() {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    return this.effects.persist({ type: "CheckedOut" }).thenReplyState()
  }
}
// docs:end entity
