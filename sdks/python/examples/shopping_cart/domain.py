"""The cart's domain, field for field the Scala sample's — that is what makes the journal shared."""

from __future__ import annotations

from dataclasses import dataclass, replace


@dataclass(frozen=True)
class LineItem:
    productId: str
    name: str
    quantity: int


@dataclass(frozen=True)
class ShoppingCart:
    cartId: str
    items: list[LineItem]
    checkedOut: bool

    @staticmethod
    def empty(cart_id: str) -> ShoppingCart:
        return ShoppingCart(cart_id, [], False)

    def add_item(self, item: LineItem) -> ShoppingCart:
        existing = next((i for i in self.items if i.productId == item.productId), None)
        merged = replace(item, quantity=existing.quantity + item.quantity) if existing else item
        rest = [i for i in self.items if i.productId != item.productId]
        # Sorted, as the Scala cart sorts: two carts with the same products are equal whatever
        # the order they were added in.
        return replace(self, items=sorted([merged, *rest], key=lambda i: i.productId))

    def remove_item(self, product_id: str) -> ShoppingCart:
        return replace(self, items=[i for i in self.items if i.productId != product_id])

    def on_checked_out(self) -> ShoppingCart:
        return replace(self, checkedOut=True)

    def contains(self, product_id: str) -> bool:
        return any(i.productId == product_id for i in self.items)

    @property
    def total_quantity(self) -> int:
        return sum(i.quantity for i in self.items)

    @property
    def is_empty(self) -> bool:
        return not self.items


# docs:start events
@dataclass(frozen=True)
class ItemAdded:
    item: LineItem


@dataclass(frozen=True)
class ItemRemoved:
    productId: str


@dataclass(frozen=True)
class CheckedOut:
    pass


ShoppingCartEvent = ItemAdded | ItemRemoved | CheckedOut
# docs:end events
