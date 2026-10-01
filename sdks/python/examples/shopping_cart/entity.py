from __future__ import annotations

from ankka import DONE, Done, ErrorCode, EventSourcedEffect, EventSourcedEntity, ReadOnlyEffect, command, json_codec, query

from examples.shopping_cart.domain import CheckedOut, Discarded, ItemAdded, ItemRemoved, LineItem, ShoppingCart, ShoppingCartEvent


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
            case Discarded():
                # The cart is deleted straight after; the event is there for what reads the journal.
                return state
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
        # As the Scala cart: a checked-out cart is kept, the record of what was ordered, and every
        # later change to it is refused.
        return self.effects.persist(CheckedOut()).then_reply_state()

    @command("discard")
    def discard(self) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]:
        if self.state.checkedOut:
            return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
        # The event is persisted, then the cart is deleted, so a consumer downstream still sees the
        # discard rather than a cart that vanished; the same id then starts again empty.
        return self.effects.persist(Discarded()).delete_entity().then_reply(lambda _: DONE)

    @query("get-cart")
    def get_cart(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, ShoppingCart]:
        return self.effects.reply(self.state)

    @query("total-quantity")
    def total_quantity(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, int]:
        return self.effects.reply(self.state.total_quantity)
