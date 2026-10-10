"""A view's rows as a stream and a watch, as the SDK reads the sidecar's frames."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any

import pytest

from ankka import CaughtUp, Overflow, Removed, WatchEnded, json_codec, sse_events
from ankka._proto.ankka.protocol.v1 import client_pb2, payload_pb2
from ankka.client import CommandError, Views
from ankka.view import declares_watched
from ankka.views import Row


@dataclass
class Item:
    key: str


class _Stub:
    def __init__(self, frames: list[client_pb2.RowFrame]) -> None:
        self.frames = frames
        self.asked: Any = None

    def _iterate(self, request: Any) -> Any:
        self.asked = request

        async def gen() -> Any:
            for f in self.frames:
                yield f

        return gen()

    def QueryStream(self, request: Any) -> Any:  # noqa: N802
        return self._iterate(request)

    def Watch(self, request: Any) -> Any:  # noqa: N802
        return self._iterate(request)


def _row(key: str) -> client_pb2.RowFrame:
    data = json_codec(Item, "item").encode(Item(key))
    return client_pb2.RowFrame(row=client_pb2.WatchedRow(key=key, payload=payload_pb2.Payload(data=data)))


async def _all(it: Any) -> list[Any]:
    return [x async for x in it]


def test_a_watch_gives_rows_then_caught_up_then_changes_and_ends_with_its_reason() -> None:
    stub = _Stub([
        _row("a"),
        client_pb2.RowFrame(caught_up=payload_pb2.Empty()),
        _row("b"),
        client_pb2.RowFrame(removed=client_pb2.Removed(key="a")),
        client_pb2.RowFrame(ended=client_pb2.Ended(reason="rebuilt")),
    ])
    views = Views(stub)  # type: ignore[arg-type]
    seen: list[Any] = []

    async def read() -> None:
        async for e in views.watch("items", "all", Item, unread=10, overflow=Overflow.FAIL):
            seen.append(e)

    with pytest.raises(WatchEnded) as ended:
        asyncio.run(read())
    assert ended.value.reason == "rebuilt"
    assert seen == [Row("a", Item("a")), CaughtUp(), Row("b", Item("b")), Removed("a")]
    assert stub.asked.named.name == "all"
    assert stub.asked.unread_bound == 10
    assert stub.asked.overflow == client_pb2.FAIL


def test_a_stream_gives_the_rows_and_raises_a_refusal() -> None:
    stub = _Stub([_row("a"), _row("b"), client_pb2.RowFrame(failed=payload_pb2.Error(message="no", code=payload_pb2.BAD_REQUEST))])
    views = Views(stub)  # type: ignore[arg-type]
    rows: list[Any] = []

    async def read() -> None:
        async for r in views.stream("items", "all", Item):
            rows.append(r)

    with pytest.raises(CommandError):
        asyncio.run(read())
    assert rows == [Item("a"), Item("b")]
    assert not stub.asked.HasField("limit")


def test_a_watch_is_served_as_named_events() -> None:
    async def watch() -> Any:
        yield Row("a", Item("a"))
        yield CaughtUp()
        yield Removed("a")
        raise WatchEnded("unread")

    events = asyncio.run(_all(sse_events(watch())))
    assert [(e.name, e.data()) for e in events] == [
        ("row", '{"key":"a","row":{"key":"a"}}'),
        ("caught-up", "{}"),
        ("removed", '{"key":"a"}'),
        ("ended", '{"reason":"unread"}'),
    ]


def test_a_runtime_from_before_view_streams_refuses_a_program_that_asks_for_one() -> None:
    """features/view-streams/languages.feature: a program whose view declares a watched query is
    refused by a sidecar from before 1.15, naming the version it needs."""
    from ankka import Ankka
    from ankka.server import DiscoveryServicer
    from examples.shopping_cart.conformance import TreeRows

    assert declares_watched(TreeRows)
    servicer = DiscoveryServicer(Ankka.service().register(TreeRows)._registry)
    refusal = servicer.refusal("1.14")
    assert refusal is not None and "TreeRows" in refusal and "1.15" in refusal
    assert servicer.refusal("1.15") is None
