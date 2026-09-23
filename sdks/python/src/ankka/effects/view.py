"""Effects for views: update the row, delete it, or ignore the event."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Generic, TypeVar

Row = TypeVar("Row")


@dataclass(frozen=True)
class UpdateRow(Generic[Row]):
    row: Row


@dataclass(frozen=True)
class DeleteRow:
    pass


@dataclass(frozen=True)
class Ignore:
    pass


ViewEffect = UpdateRow[Any] | DeleteRow | Ignore


class ViewEffects(Generic[Row]):
    def update_row(self, row: Row) -> UpdateRow[Row]:
        return UpdateRow(row)

    def delete_row(self) -> DeleteRow:
        return DeleteRow()

    def ignore(self) -> Ignore:
        return Ignore()
