"""A socket route through the servicer, against an in-process grpc.aio client: what the sidecar's
conversation sees on ``Http.HandleSocket``."""

from __future__ import annotations

import grpc
import grpc.aio
import pytest

from ankka import Acl, Ankka, Endpoint, RegistrationError, Socket, socket
from ankka._proto.ankka.protocol.v1 import discovery_pb2, discovery_pb2_grpc, endpoint_pb2, endpoint_pb2_grpc
from ankka.server import Server
from ankka.testkit import EndpointTestKit
from ankka.testkit.unit import _NoClient
from tests.counter import CounterEndpoint, CounterEntity
from tests.rooms import RoomsEndpoint


async def _server(*endpoints: type[Endpoint]) -> Server:
    builder = Ankka.service().register(CounterEntity)
    for endpoint in endpoints:
        builder = builder.register(endpoint)
    server = Server(builder.validate(), client=_NoClient())
    await server.start("127.0.0.1", 0)
    return server


def _open(route: str, *args: str, query: list[tuple[str, str]] | None = None) -> endpoint_pb2.SocketIn:
    request = endpoint_pb2.HttpRequest(endpoint_id="RoomsEndpoint", route_id=route, path_args=list(args))
    for name, value in query or []:
        request.query.add(name=name, value=value)
    return endpoint_pb2.SocketIn(open=request)


def _frame(text: str) -> endpoint_pb2.SocketIn:
    return endpoint_pb2.SocketIn(frame=endpoint_pb2.SocketFrame(text=text))


async def test_discovery_marks_a_socket_route() -> None:
    server = await _server(RoomsEndpoint)
    async with grpc.aio.insecure_channel(f"127.0.0.1:{server.port}") as channel:
        spec = await discovery_pb2_grpc.DiscoveryStub(channel).Discover(discovery_pb2.SidecarInfo(protocol_version="1.9"))
        routes = {r.id: r for e in spec.endpoints if e.id == "RoomsEndpoint" for r in e.routes}
        assert routes["chat"].socket and routes["chat"].method == "GET" and not routes["chat"].has_body
        assert spec.protocol_version == "1.11"
    await server.stop()


async def test_discovery_refuses_a_runtime_older_than_sockets() -> None:
    server = await _server(RoomsEndpoint)
    async with grpc.aio.insecure_channel(f"127.0.0.1:{server.port}") as channel:
        with pytest.raises(grpc.aio.AioRpcError) as refused:
            await discovery_pb2_grpc.DiscoveryStub(channel).Discover(discovery_pb2.SidecarInfo(protocol_version="1.8"))
        assert refused.value.code() == grpc.StatusCode.FAILED_PRECONDITION
        assert "1.9" in (refused.value.details() or "")
    await server.stop()


async def test_a_service_without_a_socket_route_is_discovered_by_an_older_runtime() -> None:
    server = await _server(CounterEndpoint)
    async with grpc.aio.insecure_channel(f"127.0.0.1:{server.port}") as channel:
        spec = await discovery_pb2_grpc.DiscoveryStub(channel).Discover(discovery_pb2.SidecarInfo(protocol_version="1.8"))
        assert [e.id for e in spec.endpoints] == ["CounterEndpoint"]
    await server.stop()


async def test_frames_cross_both_ways_and_the_request_is_read_after_them() -> None:
    server = await _server(RoomsEndpoint)
    async with grpc.aio.insecure_channel(f"127.0.0.1:{server.port}") as channel:
        call = endpoint_pb2_grpc.HttpStub(channel).HandleSocket()
        await call.write(_open("chat", "lobby", query=[("tag", "a")]))
        for text in ["x", "y", "context"]:
            await call.write(_frame(text))
        replies = [await call.read() for _ in range(3)]
        assert [r.frame.text for r in replies] == ["x", "y", "lobby a"]
        await call.write(endpoint_pb2.SocketIn(closed=endpoint_pb2.SocketClosed(reason="client")))
        await call.done_writing()
        assert (await call.read()).WhichOneof("message") == "completed"
    await server.stop()


async def test_a_handler_that_raises_is_failed() -> None:
    server = await _server(RoomsEndpoint)
    async with grpc.aio.insecure_channel(f"127.0.0.1:{server.port}") as channel:
        call = endpoint_pb2_grpc.HttpStub(channel).HandleSocket()
        await call.write(_open("fail"))
        await call.write(_frame("go"))
        reply = await call.read()
        assert reply.WhichOneof("message") == "failed"
        assert "broke" in reply.failed.message
    await server.stop()


async def test_handle_on_a_socket_route_names_the_version() -> None:
    server = await _server(RoomsEndpoint)
    async with grpc.aio.insecure_channel(f"127.0.0.1:{server.port}") as channel:
        reply = await endpoint_pb2_grpc.HttpStub(channel).Handle(endpoint_pb2.HttpRequest(endpoint_id="RoomsEndpoint", route_id="chat"))
        assert reply.WhichOneof("message") == "failure"
        assert "1.9" in reply.failure.error.message
    await server.stop()


def test_the_unit_kit_runs_a_socket_route() -> None:
    kit = EndpointTestKit.of(RoomsEndpoint)
    run = kit.socket("/rooms/lobby", ["x", "context"], query=[("tag", "b")])
    assert (run.sent, run.ended) == (["x", "lobby b"], "finished")
    failed = kit.socket("/rooms/fail", ["go"])
    assert failed.ended == "failed" and "broke" in str(failed.error)


def test_a_socket_route_takes_one_socket_and_no_body() -> None:
    with pytest.raises(RegistrationError, match="one parameter annotated Socket"):

        class NoSocket(Endpoint):
            prefix = "/a"
            acl = Acl.ALLOW_ALL

            @socket("/x")
            async def handler(self) -> None: ...

    with pytest.raises(RegistrationError, match="takes no body"):

        class WithBody(Endpoint):
            prefix = "/b"
            acl = Acl.ALLOW_ALL

            @socket("/x")
            async def handler(self, socket: Socket, body: str) -> None: ...
