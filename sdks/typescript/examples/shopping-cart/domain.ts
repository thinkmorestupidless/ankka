// The cart's domain, field for field the Scala sample's: that is what makes the journal shared between
// a Scala, a Python and a TypeScript cart.
import { s, type Infer } from "ankka"

// docs:start domain
export const LineItem = s.record("LineItem", { productId: s.string, name: s.string, quantity: s.int })
export type LineItem = Infer<typeof LineItem>

export const ShoppingCart = s.record("ShoppingCart", { cartId: s.string, items: s.list(LineItem), checkedOut: s.boolean })
export type ShoppingCart = Infer<typeof ShoppingCart>

export const ShoppingCartEvent = s.sumType("ShoppingCartEvent", {
  ItemAdded: { item: LineItem },
  ItemRemoved: { productId: s.string },
  CheckedOut: {},
})
export type ShoppingCartEvent = Infer<typeof ShoppingCartEvent>
// docs:end domain

export function emptyCart(cartId: string): ShoppingCart {
  return { cartId, items: [], checkedOut: false }
}

/** Adds an item, merging quantities for a product already present; items stay sorted by product, as the Scala cart keeps them. */
export function addItem(cart: ShoppingCart, item: LineItem): ShoppingCart {
  const existing = cart.items.find((i) => i.productId === item.productId)
  const merged = existing ? { ...item, quantity: existing.quantity + item.quantity } : item
  const rest = cart.items.filter((i) => i.productId !== item.productId)
  return { ...cart, items: [merged, ...rest].sort((a, b) => a.productId.localeCompare(b.productId)) }
}

export function removeItem(cart: ShoppingCart, productId: string): ShoppingCart {
  return { ...cart, items: cart.items.filter((i) => i.productId !== productId) }
}

export function contains(cart: ShoppingCart, productId: string): boolean {
  return cart.items.some((i) => i.productId === productId)
}

export function totalQuantity(cart: ShoppingCart): number {
  return cart.items.reduce((sum, i) => sum + i.quantity, 0)
}
