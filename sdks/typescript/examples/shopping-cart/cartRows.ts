// A queryable projection of every cart: the entity answers by cart id, this answers the rest — which
// carts contain a product, which have been checked out, which are the largest.
import { View, jsonCodec, s, type Infer } from "ankka"
import { ShoppingCartEvent } from "./domain.ts"
import { ShoppingCartEntity } from "./entity.ts"

// docs:start view
export const CartRow = s.record("CartRow", { cartId: s.string, quantities: s.stringMap(s.int), checkedOut: s.boolean })
export type CartRow = Infer<typeof CartRow>

export class CartRows extends View<ShoppingCartEvent, CartRow> {
  static readonly componentId = "cart-rows"
  static readonly source = ShoppingCartEntity
  static readonly events = ShoppingCartEntity.events
  static readonly row = jsonCodec(CartRow, "cart-row")
  static readonly queries = ["by-id", "all"]

  onChange(event: ShoppingCartEvent) {
    const current = this.row ?? { cartId: this.subject, quantities: {}, checkedOut: false }
    switch (event.type) {
      case "ItemAdded": {
        const { productId, quantity } = event.item
        return this.effects.updateRow({ ...current, quantities: { ...current.quantities, [productId]: (current.quantities[productId] ?? 0) + quantity } })
      }
      case "ItemRemoved": {
        const { [event.productId]: _removed, ...rest } = current.quantities
        return this.effects.updateRow({ ...current, quantities: rest })
      }
      case "CheckedOut":
        return this.effects.updateRow({ ...current, checkedOut: true })
      case "Discarded":
        // The deletion that follows removes the row: a discarded cart leaves the listing, which is the
        // view's default when its source is deleted.
        return this.effects.ignore()
    }
  }
}
// docs:end view
