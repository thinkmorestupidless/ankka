"""A queryable projection of every cart: the entity answers by cart id, this answers the rest —
which carts contain a product, which have been checked out, which are the largest."""

from __future__ import annotations

from dataclasses import dataclass, field, replace

from ankka import json_codec
from ankka.effects.view import ViewEffect
from ankka.view import View

from examples.shopping_cart.domain import CheckedOut, ItemAdded, ItemRemoved, ShoppingCartEvent
from examples.shopping_cart.entity import ShoppingCartEntity


@dataclass(frozen=True)
class CartRow:
    cartId: str
    quantities: dict[str, int] = field(default_factory=dict)
    checkedOut: bool = False

    @property
    def total_quantity(self) -> int:
        return sum(self.quantities.values())


class CartRows(View[ShoppingCartEvent, CartRow]):
    component_id = "cart-rows"
    source = ShoppingCartEntity
    event_codec = ShoppingCartEntity.event_codec
    row_codec = json_codec(CartRow, "cart-row")
    queries = ("by-id", "all")

    def on_change(self, event: ShoppingCartEvent) -> ViewEffect:
        current = self.row or CartRow(self.metadata.subject or "")
        match event:
            case ItemAdded(item):
                quantities = {**current.quantities, item.productId: current.quantities.get(item.productId, 0) + item.quantity}
                return self.effects.update_row(replace(current, quantities=quantities))
            case ItemRemoved(product_id):
                return self.effects.update_row(replace(current, quantities={k: v for k, v in current.quantities.items() if k != product_id}))
            case CheckedOut():
                return self.effects.update_row(replace(current, checkedOut=True))
        raise AssertionError(event)

    def on_delete(self) -> ViewEffect:
        """Checkout deletes the cart, but a checked-out cart is exactly what an order history
        needs: the row outlives the entity that produced it."""
        if self.row is None:
            return self.effects.ignore()
        return self.effects.update_row(replace(self.row, checkedOut=True))
