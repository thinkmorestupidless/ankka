"""Effects for keyed views: rows written and deleted by key, several for one change."""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import Any, Generic, TypeVar

Row = TypeVar("Row")


@dataclass(frozen=True)
class Upsert(Generic[Row]):
    """Write ``row`` under ``key``."""

    key: str
    row: Row


@dataclass(frozen=True)
class Delete:
    """Delete the row under ``key``."""

    key: str


RowChange = Upsert[Any] | Delete


@dataclass(frozen=True)
class KeyedViewEffect:
    """The rows one change writes and deletes, in order; empty is nothing. The platform deletes no
    row a handler did not name, so a row is moved by deleting its old key and writing its new one
    in one effect: ``effects.delete_row(old) + effects.update_row(new, row)``."""

    changes: tuple[RowChange, ...] = ()

    def __add__(self, other: KeyedViewEffect) -> KeyedViewEffect:
        return KeyedViewEffect(self.changes + other.changes)


class KeyedViewEffects(Generic[Row]):
    def update_row(self, key: str, row: Row) -> KeyedViewEffect:
        return KeyedViewEffect((Upsert(key, row),))

    def delete_row(self, key: str) -> KeyedViewEffect:
        return KeyedViewEffect((Delete(key),))

    def update_rows(self, rows: Mapping[str, Row] | Iterable[tuple[str, Row]]) -> KeyedViewEffect:
        pairs = rows.items() if isinstance(rows, Mapping) else rows
        return KeyedViewEffect(tuple(Upsert(key, row) for key, row in pairs))

    def delete_rows(self, keys: Iterable[str]) -> KeyedViewEffect:
        return KeyedViewEffect(tuple(Delete(key) for key in keys))

    def ignore(self) -> KeyedViewEffect:
        return KeyedViewEffect()


def reduce(changes: Iterable[RowChange]) -> list[tuple[str, Any | None]]:
    """The final change for each key, a later change to a key winning, keys in the order they first
    appear; ``None`` is a deletion. The reading the runtime applies an effect by."""
    order: list[str] = []
    last: dict[str, Any | None] = {}
    for change in changes:
        if change.key not in last:
            order.append(change.key)
        last[change.key] = change.row if isinstance(change, Upsert) else None
    return [(key, last[key]) for key in order]
