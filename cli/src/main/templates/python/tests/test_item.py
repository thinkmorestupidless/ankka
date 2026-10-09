"""The entity and the view with no sidecar, no database and no network: milliseconds.

Inputs, events, state and replies still round-trip through the codecs, so a type the codec cannot
encode fails here rather than on first deployment."""

import os
from collections.abc import Iterator

import pytest

from ankka import ErrorCode, personal as personal_fields
from ankka.effects.view import Ignore, UpdateRow
from ankka.personal import FixedKeys, PersonalFieldError
from ankka.testkit import EventSourcedTestKit, ViewTestKit

from {{module}}.domain import AddItem, Item, ItemAdded, ItemDetails, ItemRemoved, OwnerDetails, OwnerSet, RemoveItem, SetOwner
from {{module}}.item_entity import ItemEntity
from {{module}}.item_rows import ItemRow, ItemRows


@pytest.fixture(autouse=True)
def keys() -> Iterator[FixedKeys]:
    """A personal field is encrypted whenever it is encoded, here too; with no sidecar the test hands
    over a key."""
    source = FixedKeys("local", os.urandom(32))
    personal_fields.install(source)
    yield source
    personal_fields.install(None)


def test_adding_persists_an_event_and_updates_the_state() -> None:
    kit = EventSourcedTestKit.of(ItemEntity, "i1")
    assert kit.call("add-item", AddItem("Widget", 2)).events == (ItemAdded("Widget", 2),)
    kit.call("add-item", AddItem("Widget", 3))
    assert kit.call("get-item").reply == Item("i1", "Widget", 5)


def test_removing_more_than_there_is_is_refused_and_persists_nothing() -> None:
    kit = EventSourcedTestKit.of(ItemEntity, "i1")
    kit.call("add-item", AddItem("Widget", 2))
    refused = kit.call("remove-item", RemoveItem(3))
    assert refused.error is not None and refused.error.code == ErrorCode.CONFLICT
    assert refused.events == ()
    assert kit.call("remove-item", RemoveItem(2)).events == (ItemRemoved(2),)
    assert kit.state.count == 0


def test_a_count_below_one_is_a_bad_request() -> None:
    kit = EventSourcedTestKit.of(ItemEntity, "i1")
    refused = kit.call("add-item", AddItem("Widget", 0))
    assert refused.error is not None and refused.error.code == ErrorCode.BAD_REQUEST


def test_the_view_keeps_one_row_per_item() -> None:
    kit = ViewTestKit.of(ItemRows)
    assert isinstance(kit.on_change("i1", ItemAdded("Widget", 2)), UpdateRow)
    kit.on_change("i1", ItemRemoved(1))
    assert kit.get("i1") == ItemRow("i1", "Widget", 1)


def test_an_owners_email_is_kept_as_a_personal_field() -> None:
    kit = EventSourcedTestKit.of(ItemEntity, "i1")
    kit.call("add-item", AddItem("Widget", 1))
    (event,) = kit.call("set-owner", SetOwner("u1", "ada@example.com")).events
    assert isinstance(event, OwnerSet) and event.email.subject == "user/u1"
    # Stored encrypted: the journal holds an envelope, never the address.
    assert b"ada@example.com" not in ItemEntity.event_codec.encode(event)
    assert ItemDetails.of(kit.call("get-item").reply) == ItemDetails("i1", "Widget", 1, OwnerDetails("u1", "ada@example.com"))


def test_an_erased_owners_email_reads_as_null_and_the_item_stays(keys: FixedKeys) -> None:
    kit = EventSourcedTestKit.of(ItemEntity, "i1")
    kit.call("add-item", AddItem("Widget", 1))
    kit.call("set-owner", SetOwner("u2", "grace@example.com"))
    keys.erase("user/u2")  # as erasing the data subject does: the key is destroyed
    assert ItemDetails.of(kit.call("get-item").reply) == ItemDetails("i1", "Widget", 1, OwnerDetails("u2", None))
    # And no new email can be written for that subject: the service answers the request 400.
    with pytest.raises(PersonalFieldError):
        kit.call("set-owner", SetOwner("u2", "again@example.com"))


def test_the_view_holds_nothing_personal() -> None:
    kit = ViewTestKit.of(ItemRows)
    kit.on_change("i1", ItemAdded("Widget", 2))
    assert isinstance(kit.on_change("i1", OwnerSet("u1", personal_fields.personal("user/u1", "ada@example.com"))), Ignore)


def test_events_are_stored_with_their_type() -> None:
    assert ItemEntity.event_codec.encode(ItemAdded("Widget", 2)) == b'{"type":"ItemAdded","name":"Widget","count":2}'
