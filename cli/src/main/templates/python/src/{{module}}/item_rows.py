"""A view: fed the entity's events in order, keeping one row per item — the read side an entity
cannot answer on its own. Rows live in Postgres, beside the journal, and belong to the sidecar."""

from __future__ import annotations

from dataclasses import dataclass, replace

from ankka import json_codec
from ankka.effects.view import ViewEffect
from ankka.view import View

from {{module}}.domain import ItemAdded, ItemEvent, ItemRemoved, OwnerSet
from {{module}}.item_entity import ItemEntity


@dataclass(frozen=True)
class ItemRow:
    id: str
    name: str
    count: int


class ItemRows(View[ItemEvent, ItemRow]):
    component_id = "item-rows"
    source = ItemEntity
    event_codec = ItemEntity.event_codec
    row_codec = json_codec(ItemRow, "item-row")
    queries = ("by-id", "all")

    def on_change(self, event: ItemEvent) -> ViewEffect:
        current = self.row or ItemRow(self.metadata.subject or "", "", 0)
        match event:
            case ItemAdded(name, count):
                return self.effects.update_row(replace(current, name=name, count=current.count + count))
            case ItemRemoved(count):
                return self.effects.update_row(replace(current, count=current.count - count))
            case OwnerSet():
                # The listing holds nothing personal, so there is nothing of the owner's to erase in it.
                return self.effects.ignore()
        raise AssertionError(event)
