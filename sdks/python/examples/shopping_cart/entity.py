from __future__ import annotations

from ankka import DONE, Done, ErrorCode, EventSourcedEffect, EventSourcedEntity, ReadOnlyEffect, command, json_codec, query

from examples.shopping_cart.domain import CheckedOut, ItemAdded, ItemRemoved, LineItem, ShoppingCart, ShoppingCartEvent


class ShoppingCartEntity(EventSourcedEntity[ShoppingCart, ShoppingCartEvent]):
    component_id = "shopping-cart"
    state_codec = json_codec(ShoppingCart, "shopping-cart")
    event_codec = json_codec(ShoppingCartEvent, "shopping-cart-event")
    snapshot_every = 100

    def empty_state(self) -> ShoppingCart:
        return ShoppingCart.empty(self.entity_id)

    def apply_event(self, state: ShoppingCart, event: ShoppingCartEvent) -> ShoppingCart:
        match event:
            case ItemAdded(item):
                return state.add_item(item)
            case ItemRemoved(product_id):
                return state.remove_item(product_id)
            case CheckedOut():
                return state.on_checked_out()
        raise AssertionError(event)

    @command("add-item")
    def add_item(self, item: LineItem) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]:
        if self.state.checkedOut:
            return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
        if item.quantity <= 0:
            return self.effects.error(f"quantity must be greater than zero, was {item.quantity}")
        return self.effects.persist(ItemAdded(item)).then_reply(lambda _: DONE)

    @command("remove-item")
    def remove_item(self, product_id: str) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]:
        if self.state.checkedOut:
            return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
        if not self.state.contains(product_id):
            return self.effects.error(f"cart does not contain '{product_id}'", ErrorCode.NOT_FOUND)
        return self.effects.persist(ItemRemoved(product_id)).then_reply(lambda _: DONE)

    @command("checkout")
    def checkout(self) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, ShoppingCart]:
        if self.state.checkedOut:
            return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
        if self.state.is_empty:
            return self.effects.error("cannot check out an empty cart")
        # As the Scala cart: the event is persisted, then the cart is deleted, so a consumer
        # downstream still sees the checkout rather than a cart that vanished.
        return self.effects.persist(CheckedOut()).delete_entity().then_reply_state()

    @query("get-cart")
    def get_cart(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, ShoppingCart]:
        return self.effects.reply(self.state)

    @query("total-quantity")
    def total_quantity(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, int]:
        return self.effects.reply(self.state.total_quantity)
