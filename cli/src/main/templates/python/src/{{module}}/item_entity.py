"""An event sourced entity: one instance per item id, holding the item's state as the fold of its
events. A handler returns an *effect* — a description of what should happen — and never performs I/O
itself; that is what lets `tests/test_item.py` drive it with no sidecar."""

from __future__ import annotations

from ankka import DONE, Done, ErrorCode, EventSourcedEffect, EventSourcedEntity, ReadOnlyEffect, command, json_codec, query

from ankka.personal import personal

from {{module}}.domain import AddItem, Item, ItemAdded, ItemEvent, ItemRemoved, Owner, OwnerSet, RemoveItem, SetOwner


class ItemEntity(EventSourcedEntity[Item, ItemEvent]):
    component_id = "item"
    state_codec = json_codec(Item, "item")
    event_codec = json_codec(ItemEvent, "item-event")

    def empty_state(self) -> Item:
        return Item.empty(self.entity_id)

    def apply_event(self, state: Item, event: ItemEvent) -> Item:
        match event:
            case ItemAdded(name, count):
                return state.on_added(name, count)
            case ItemRemoved(count):
                return state.on_removed(count)
            case OwnerSet(user, email):
                return state.on_owner_set(Owner(user, email))
        raise AssertionError(event)

    # The decorator's argument is the wire name — the versioning boundary. Rename the method freely;
    # change the wire name and in-flight callers break.
    @command("add-item")
    def add_item(self, request: AddItem) -> EventSourcedEffect[Item, ItemEvent, Done]:
        """A command: validated, then persisted, then answered."""
        if request.count <= 0:
            return self.effects.error(f"count must be greater than zero, was {request.count}")
        return self.effects.persist(ItemAdded(request.name, request.count)).then_reply(lambda _: DONE)

    @command("remove-item")
    def remove_item(self, request: RemoveItem) -> EventSourcedEffect[Item, ItemEvent, Done]:
        if request.count <= 0:
            return self.effects.error(f"count must be greater than zero, was {request.count}")
        if request.count > self.state.count:
            return self.effects.error(f"only {self.state.count} to remove", ErrorCode.CONFLICT)
        return self.effects.persist(ItemRemoved(request.count)).then_reply(lambda _: DONE)

    @command("set-owner")
    def set_owner(self, request: SetOwner) -> EventSourcedEffect[Item, ItemEvent, Done]:
        """The email is the owner's, not the item's: it is marked personal for the data subject
        `user/<user>`, so erasing that subject leaves this item and its history, and only the email goes."""
        if not request.user:
            return self.effects.error("an owner needs a user id")
        email = personal(f"user/{request.user}", request.email)
        return self.effects.persist(OwnerSet(request.user, email)).then_reply(lambda _: DONE)

    @query("get-item")
    def get_item(self) -> ReadOnlyEffect[Item, ItemEvent, Item]:
        """A query: it must return a `ReadOnlyEffect`, and registration refuses anything else."""
        return self.effects.reply(self.state)
