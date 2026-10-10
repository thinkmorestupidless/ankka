"""What a stream of a view's rows and a watch of a view's query give a handler.

A watch first gives every row the watched query matches now, then ``CaughtUp`` once, then, for as
long as the handler reads, each row as the view writes it and matches and a ``Removed`` for each row
it gave that stops matching or is deleted. A watch is live, not a record: rows are coalesced per key,
so a row written faster than the handler reads reaches it fewer times than it was written, never an
older version after a newer one, and what was written while nobody watched is not replayed.
"""

from __future__ import annotations

import enum
import json
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any, Generic, TypeVar

from ankka.endpoint import SseEvent

T = TypeVar("T")

# The first protocol in which a view's query can be streamed and watched.
VIEW_STREAMS_PROTOCOL = (1, 15)
VIEW_STREAMS_SINCE = "1.15"


@dataclass(frozen=True)
class Row(Generic[T]):
    """The row under ``key``, as the watched query sees it now."""

    key: str
    row: T


@dataclass(frozen=True)
class Removed:
    """The row under ``key``, given earlier, no longer matches or was deleted."""

    key: str


@dataclass(frozen=True)
class CaughtUp:
    """Given once, after the rows the query matched when the watch began, and before any change."""


class Overflow(enum.Enum):
    """What a watch does when it holds as many unread rows as its bound and another key's row
    arrives. None slows the view: a watcher never holds back the view's writes."""

    DROP_HEAD = 0
    """Drop the row changed longest ago; the default."""
    DROP_TAIL = 1
    """Drop the row changed most recently."""
    DROP_NEW = 2
    """Drop the row that arrived."""
    DROP_ALL = 3
    """Drop every unread row."""
    FAIL = 4
    """End the watch, telling the watcher it ended unread."""


class WatchEnded(Exception):
    """A watch ended for ``reason``: ``rebuilt``, ``instance-stopping``, ``listener-lost`` or
    ``unread``. A handler that still wants the rows watches again, and is given the rows now."""

    def __init__(self, reason: str) -> None:
        super().__init__(f"the watch ended: {reason}")
        self.reason = reason


def _plain(value: Any) -> Any:
    import dataclasses

    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return {k: _plain(x) for k, x in dataclasses.asdict(value).items()}
    if isinstance(value, (list, tuple)):
        return [_plain(x) for x in value]
    if isinstance(value, dict):
        return {k: _plain(x) for k, x in value.items()}
    return value


async def sse_events(watch: AsyncIterator[Row[Any] | Removed | CaughtUp]) -> AsyncIterator[SseEvent]:
    """A watch as server-sent events, for an ``@sse`` route: each row as ``row`` carrying the key and
    the row, each removal as ``removed`` carrying the key, the marker as ``caught-up``, and a watch
    that ended with a reason as a last event ``ended`` carrying it, so a page is told why."""
    try:
        async for event in watch:
            if isinstance(event, Row):
                yield SseEvent("row", {"key": event.key, "row": _plain(event.row)})
            elif isinstance(event, Removed):
                yield SseEvent("removed", {"key": event.key})
            else:
                yield SseEvent("caught-up", {})
    except WatchEnded as ended:
        yield SseEvent("ended", {"reason": ended.reason})


def _decode_json(data: bytes) -> Any:
    return json.loads(data.decode("utf-8"))
