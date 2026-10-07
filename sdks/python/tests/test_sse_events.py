"""A stream route's named event: what the servicer sends the sidecar when a handler yields one."""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from dataclasses import dataclass

import grpc.aio
import pytest

from ankka import Acl, Ankka, Endpoint, SseEvent, sse
from ankka._proto.ankka.protocol.v1 import endpoint_pb2, endpoint_pb2_grpc
from ankka.server import Server
from ankka.testkit.unit import _NoClient


@dataclass(frozen=True)
class Request:
    id: str
    tool: str


class Turns(Endpoint):
    prefix = "/turns"
    acl = Acl.ALLOW_ALL

    @sse("/{session}")
    async def turn(self, session: str) -> AsyncIterator[str | SseEvent]:
        yield "checking"
        yield SseEvent("approval", [Request("a-1", "refund")])


async def test_a_named_event_is_sent_as_its_own_frame_after_the_text() -> None:
    server = Server(Ankka.service().register(Turns).validate(), client=_NoClient())
    await server.start("127.0.0.1", 0)
    try:
        async with grpc.aio.insecure_channel(f"127.0.0.1:{server.port}") as channel:
            request = endpoint_pb2.HttpRequest(endpoint_id=Turns.endpoint_id(), route_id=next(iter(Turns.routes())), path_args=["s-1"])
            frames = [f async for f in endpoint_pb2_grpc.HttpStub(channel).HandleStream(request)]
    finally:
        await server.stop()
    assert [f.WhichOneof("frame") for f in frames] == ["text", "event", "completed"]
    assert frames[1].event.name == "approval"
    assert json.loads(frames[1].event.data) == [{"id": "a-1", "tool": "refund"}]
    assert "\n" not in frames[1].event.data


def test_an_event_name_is_one_non_empty_line() -> None:
    with pytest.raises(ValueError):
        SseEvent("", 1)
    with pytest.raises(ValueError):
        SseEvent("two\nlines", 1)
