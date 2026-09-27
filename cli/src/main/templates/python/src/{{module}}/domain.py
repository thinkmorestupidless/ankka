"""The stub domain: an item with a name and a count. Replace it with yours — this file, the entity
that keeps it, the endpoint that exposes it and the view that lists it are the whole shape of an
ankka service, and they are all in this project.

Plain data, no I/O. The entity decides what changes an item; this file only says how a change is
applied — which is what makes both testable without a sidecar.
"""

from __future__ import annotations

from dataclasses import dataclass, replace


@dataclass(frozen=True)
class Item:
    id: str
    name: str
    count: int

    @staticmethod
    def empty(item_id: str) -> Item:
        return Item(item_id, "", 0)

    def on_added(self, name: str, count: int) -> Item:
        return replace(self, name=name, count=self.count + count)

    def on_removed(self, count: int) -> Item:
        return replace(self, count=self.count - count)


# What has happened to an item. Events are the durable record; state is derived from them.


@dataclass(frozen=True)
class ItemAdded:
    name: str
    count: int


@dataclass(frozen=True)
class ItemRemoved:
    count: int


# A union, even while it is small: the codec writes a union's members with a "type" field and a lone
# dataclass without one, so an event type that started as one class would change its stored format
# the day a second event arrived — and the journal already holds the first.
ItemEvent = ItemAdded | ItemRemoved


@dataclass(frozen=True)
class AddItem:
    """The request body of `add-item`."""

    name: str
    count: int


@dataclass(frozen=True)
class RemoveItem:
    """The request body of `remove-item`."""

    count: int
