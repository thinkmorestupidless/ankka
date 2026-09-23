"""The servicer against an in-process grpc.aio client: what the sidecar's conversation sees."""

from __future__ import annotations

import asyncio

import grpc.aio

from ankka import Ankka
from ankka._proto.ankka.protocol.v1 import discovery_pb2, discovery_pb2_grpc, event_sourced_pb2, event_sourced_pb2_grpc, payload_pb2
from ankka.testkit.unit import _NoClient
from ankka.server import Server
from tests.counter import CounterEndpoint, CounterEntity


async def _server() -> Server:
    registry = Ankka.service().register(CounterEntity).register(CounterEndpoint).validate()
    server = Server(registry, client=_NoClient())
    await server.start("127.0.0.1", 0)
    return server


def _init(entity_id: str = "c1", snapshot: bytes | None = None, sequence: int = 0) -> event_sourced_pb2.EventSourcedIn:
    init = event_sourced_pb2.EventSourcedIn.Init(component_id="counter", entity_id=entity_id)
    if snapshot is not None:
        init.snapshot.sequence = sequence
        init.snapshot.payload.CopyFrom(payload_pb2.Payload(content_type="application/json", manifest="counter", data=snapshot))
    return event_sourced_pb2.EventSourcedIn(init=init)


def _command(id: int, name: str, text: str = "", snapshot_requested: bool = False) -> event_sourced_pb2.EventSourcedIn:
    return event_sourced_pb2.EventSourcedIn(
        command=event_sourced_pb2.EventSourcedIn.Command(
            id=id,
            name=name,
            payload=payload_pb2.Payload(content_type="text/plain", manifest="int", data=text.encode()),
            snapshot_requested=snapshot_requested,
        )
    )


def _event(sequence: int, json: str) -> event_sourced_pb2.EventSourcedIn:
    return event_sourced_pb2.EventSourcedIn(
        event=event_sourced_pb2.EventSourcedIn.Event(
            sequence=sequence, payload=payload_pb2.Payload(content_type="application/json", manifest="counter-event", data=json.encode())
        )
    )


async def test_discovery_answers_the_spec() -> None:
    server = await _server()
    async with grpc.aio.insecure_channel(f"127.0.0.1:{server.port}") as channel:
        stub = discovery_pb2_grpc.DiscoveryStub(channel)
        spec = await stub.Discover(discovery_pb2.SidecarInfo(protocol_version="1.0", runtime_version="test"))
        assert [c.id for c in spec.components] == ["counter"]
        assert [e.id for e in spec.endpoints] == ["CounterEndpoint"]
        assert spec.protocol_version == "1.0"
    await server.stop()


async def test_init_replay_command_snapshot_and_failure() -> None:
    server = await _server()
    async with grpc.aio.insecure_channel(f"127.0.0.1:{server.port}") as channel:
        stub = event_sourced_pb2_grpc.EventSourcedStub(channel)
        call = stub.Handle()
        await call.write(_init())
        await call.write(_event(1, '{"type":"Incremented","by":5}'))
        await call.write(_command(1, "increment", "2"))
        reply = (await call.read()).reply
        assert reply.command_id == 1
        assert [e.manifest for e in reply.events] == ["counter-event"]
        assert reply.events[0].data == b'{"type":"Incremented","by":2}'
        assert reply.outcome.reply.payload.data == b"7"
        assert not reply.HasField("snapshot")
        # A snapshot only when requested; it is the state after the events.
        await call.write(_command(2, "increment", "1", snapshot_requested=True))
        reply = (await call.read()).reply
        assert reply.snapshot.data == b'{"total":8,"notes":[]}'
        assert reply.snapshot.manifest == "counter"
        # A refusal persists nothing.
        await call.write(_command(3, "increment", "0"))
        reply = (await call.read()).reply
        assert reply.outcome.error.code == payload_pb2.BAD_REQUEST and len(reply.events) == 0
        # A fault is a Failure, not a reply.
        await call.write(_command(4, "boom"))
        out = await call.read()
        assert out.failure.command_id == 4 and "boom" in out.failure.error.message
        # The instance still answers afterwards.
        await call.write(_command(5, "total"))
        assert (await call.read()).reply.outcome.reply.payload.data == b"8"
        await call.done_writing()
    await server.stop()


async def test_init_from_a_snapshot() -> None:
    server = await _server()
    async with grpc.aio.insecure_channel(f"127.0.0.1:{server.port}") as channel:
        stub = event_sourced_pb2_grpc.EventSourcedStub(channel)
        call = stub.Handle()
        await call.write(_init(snapshot=b'{"total":40,"notes":["x"]}', sequence=9))
        await call.write(_event(10, '{"type":"Incremented","by":2}'))
        await call.write(_command(1, "total"))
        assert (await call.read()).reply.outcome.reply.payload.data == b"42"
        await call.done_writing()
    await server.stop()


async def test_commands_are_handled_strictly_in_order() -> None:
    server = await _server()
    async with grpc.aio.insecure_channel(f"127.0.0.1:{server.port}") as channel:
        stub = event_sourced_pb2_grpc.EventSourcedStub(channel)
        call = stub.Handle()
        await call.write(_init("ordered"))
        for i in range(1, 51):
            await call.write(_command(i, "increment", "1"))
        ids = [(await call.read()).reply.command_id for _ in range(50)]
        assert ids == list(range(1, 51))
        await call.write(_command(51, "total"))
        assert (await call.read()).reply.outcome.reply.payload.data == b"50"
        await call.done_writing()
    await server.stop()


async def test_the_process_binds_loopback_only() -> None:
    server = Server(Ankka.service().register(CounterEntity).validate(), client=_NoClient())
    try:
        await server.start("192.168.1.1", 0)
        raise AssertionError("bound a non-loopback address")
    except ValueError:
        pass
    await asyncio.sleep(0)
