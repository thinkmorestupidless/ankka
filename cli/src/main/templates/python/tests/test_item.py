"""The entity and the view with no sidecar, no database and no network: milliseconds.

Inputs, events, state and replies still round-trip through the codecs, so a type the codec cannot
encode fails here rather than on first deployment."""

from ankka import ErrorCode
from ankka.effects.view import UpdateRow
from ankka.testkit import EventSourcedTestKit, ViewTestKit

from {{module}}.domain import AddItem, Item, ItemAdded, ItemRemoved, RemoveItem
from {{module}}.item_entity import ItemEntity
from {{module}}.item_rows import ItemRow, ItemRows


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


def test_events_are_stored_with_their_type() -> None:
    assert ItemEntity.event_codec.encode(ItemAdded("Widget", 2)) == b'{"type":"ItemAdded","name":"Widget","count":2}'
