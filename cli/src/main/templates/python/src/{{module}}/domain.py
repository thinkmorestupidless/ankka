"""The stub domain: an item with a name and a count. Replace it with yours — this file, the entity
that keeps it, the endpoint that exposes it and the view that lists it are the whole shape of an
ankka service, and they are all in this project.

Plain data, no I/O. The entity decides what changes an item; this file only says how a change is
applied — which is what makes both testable without a sidecar.
"""

from __future__ import annotations

from dataclasses import dataclass, replace

from ankka.personal import Personal


@dataclass(frozen=True)
class Owner:
    """Who owns an item. `user` is an opaque id, never a name or an address: it is what identifies the
    data subject, and it stays readable when everything personal about them is gone."""

    user: str
    # A personal field: stored encrypted under the key of the data subject `user/<user>`, and read as
    # erased once that subject is erased — in the journal, in every snapshot, wherever it was kept.
    email: Personal[str]


@dataclass(frozen=True)
class Item:
    id: str
    name: str
    count: int
    owner: Owner | None = None

    @staticmethod
    def empty(item_id: str) -> Item:
        return Item(item_id, "", 0)

    def on_added(self, name: str, count: int) -> Item:
        return replace(self, name=name, count=self.count + count)

    def on_removed(self, count: int) -> Item:
        return replace(self, count=self.count - count)

    def on_owner_set(self, owner: Owner) -> Item:
        return replace(self, owner=owner)


# What has happened to an item. Events are the durable record; state is derived from them.


@dataclass(frozen=True)
class ItemAdded:
    name: str
    count: int


@dataclass(frozen=True)
class ItemRemoved:
    count: int


@dataclass(frozen=True)
class OwnerSet:
    user: str
    email: Personal[str]


# A union, even while it is small: the codec writes a union's members with a "type" field and a lone
# dataclass without one, so an event type that started as one class would change its stored format
# the day a second event arrived — and the journal already holds the first.
ItemEvent = ItemAdded | ItemRemoved | OwnerSet


@dataclass(frozen=True)
class AddItem:
    """The request body of `add-item`."""

    name: str
    count: int


@dataclass(frozen=True)
class RemoveItem:
    """The request body of `remove-item`."""

    count: int


@dataclass(frozen=True)
class SetOwner:
    """The request body of `set-owner`: plain values. The entity knows whose they are."""

    user: str
    email: str


@dataclass(frozen=True)
class OwnerDetails:
    """An owner as a caller reads one: `email` is `"erased"` once the owner has been erased."""

    user: str
    email: str


@dataclass(frozen=True)
class ItemDetails:
    """An item as a caller reads one, every personal field opened, or `"erased"` where it reads as erased."""

    id: str
    name: str
    count: int
    owner: OwnerDetails | None = None

    @staticmethod
    def of(item: Item) -> ItemDetails:
        if item.owner is None:
            return ItemDetails(item.id, item.name, item.count, None)
        email = item.owner.email.value
        owner = OwnerDetails(item.owner.user, "erased" if email is None else email)
        return ItemDetails(item.id, item.name, item.count, owner)
