"""Research verify item 4: how a grpc.aio server-side bidirectional handler observes the client
closing cleanly versus aborting. Answer: both end the request iterator promptly and without an
error, and they cannot be told apart at that moment; both mean "release the state". The servicers
do exactly that, and the spike stays as the proof."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from typing import Any

import grpc
import grpc.aio

METHOD = "/spike.Spike/Handle"


def _identity(b: bytes) -> bytes:
    return b


class _Handler(grpc.aio.ServicerContext):  # type: ignore[misc]
    pass


class _Spike:
    def __init__(self) -> None:
        self.ended: asyncio.Future[str] = asyncio.get_event_loop().create_future()

    async def handle(self, request_iterator: AsyncIterator[bytes], context: Any) -> AsyncIterator[bytes]:
        try:
            async for msg in request_iterator:
                yield msg
            # Finding (research item 4): a client cancel ALSO ends the iterator without an error,
            # and `context.cancelled()` is still false at that moment, so a handler cannot tell
            # passivation (a clean half-close) from an abort when the iterator ends. Both mean
            # "release the state", which is what the SDK does; the distinction is not needed.
            self.ended.set_result("ended")
        except asyncio.CancelledError:
            self.ended.set_result("ended")
            raise


async def _server(spike: _Spike) -> tuple[grpc.aio.Server, int]:
    server = grpc.aio.server()
    handler = grpc.stream_stream_rpc_method_handler(spike.handle, _identity, _identity)
    server.add_generic_rpc_handlers((grpc.method_handlers_generic_handler("spike.Spike", {"Handle": handler}),))
    port = server.add_insecure_port("127.0.0.1:0")
    await server.start()
    return server, port


async def test_clean_close_is_observed_promptly() -> None:
    spike = _Spike()
    server, port = await _server(spike)
    async with grpc.aio.insecure_channel(f"127.0.0.1:{port}") as channel:
        call = channel.stream_stream(METHOD, request_serializer=_identity, response_deserializer=_identity)()
        await call.write(b"a")
        assert await call.read() == b"a"
        await call.done_writing()
        assert await call.read() is grpc.aio.EOF
    assert await asyncio.wait_for(spike.ended, 1.0) == "ended"
    await server.stop(None)


async def test_abort_is_observed_promptly() -> None:
    spike = _Spike()
    server, port = await _server(spike)
    channel = grpc.aio.insecure_channel(f"127.0.0.1:{port}")
    call = channel.stream_stream(METHOD, request_serializer=_identity, response_deserializer=_identity)()
    await call.write(b"a")
    assert await call.read() == b"a"
    call.cancel()
    await channel.close()
    assert await asyncio.wait_for(spike.ended, 1.0) == "ended"
    await server.stop(None)
