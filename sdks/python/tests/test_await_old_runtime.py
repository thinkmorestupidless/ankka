"""Waiting for a workflow's end, as this SDK asks the sidecar for it (protocol 1.15).

The scenario "a runtime from before waiting refuses a handler's wait, naming the protocol version"
of ``features/awaiting-workflows/languages.feature`` is the first test here: a runtime before 1.15
answers the rpc UNIMPLEMENTED, and the handler is told which protocol waiting needs.
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from typing import Any

import grpc
import grpc.aio
import pytest

from ankka._proto.ankka.protocol.v1 import client_pb2, discovery_pb2, payload_pb2
from ankka.client import AWAIT_SINCE, Calls, CommandError, Ended, Failed, Heartbeat, Invocation, TimedOut
from ankka.context import Metadata
from ankka.effects.common import ErrorCode


@dataclass
class Brew:
    steps: list[str]


def _unimplemented() -> grpc.aio.AioRpcError:
    return grpc.aio.AioRpcError(grpc.StatusCode.UNIMPLEMENTED, grpc.aio.Metadata(), grpc.aio.Metadata(), "Method not found")


class _Old:
    """A runtime that predates waiting: grpc-java answers UNIMPLEMENTED for both rpcs."""

    async def AwaitEnd(self, _request: Any) -> Any:
        raise _unimplemented()

    def AwaitEndStream(self, _request: Any) -> Any:
        async def tokens() -> Any:
            raise _unimplemented()
            yield  # pragma: no cover

        return tokens()


class _Recording:
    """A runtime that records what it was asked, and answers a command, then a wait, as told."""

    def __init__(self, end: client_pb2.InvokeReply, tokens: list[client_pb2.StreamToken] | None = None) -> None:
        self.sent: list[Any] = []
        self.end = end
        self.tokens = tokens or []

    async def Invoke(self, request: Any) -> Any:
        self.sent.append(request)
        return client_pb2.InvokeReply(reply=payload_pb2.Outcome.Reply(payload=payload_pb2.Payload(manifest="done")))

    async def AwaitEnd(self, request: Any) -> Any:
        self.sent.append(request)
        return self.end

    def AwaitEndStream(self, request: Any) -> Any:
        self.sent.append(request)

        async def tokens() -> Any:
            for token in self.tokens:
                yield token

        return tokens()


def _calls(stub: Any) -> Calls:
    return Calls(stub, discovery_pb2.WORKFLOW, "brew", "b1", Metadata())


_STATE = client_pb2.InvokeReply(
    reply=payload_pb2.Outcome.Reply(
        payload=payload_pb2.Payload(content_type="application/json", manifest="brew", data=b'{"steps":["boil","pour"]}')
    )
)

_FAILED = client_pb2.InvokeReply(
    error=payload_pb2.Error(
        message="workflow brew 'b1' failed: step 'boil' failed: dry",
        code=payload_pb2.WORKFLOW_FAILED,
        details={"step": "boil", "reason": "step 'boil' failed: dry"},
    )
)


def test_a_runtime_from_before_waiting_refuses_a_handlers_wait_naming_the_protocol_version() -> None:
    with pytest.raises(CommandError) as refused:
        asyncio.run(_calls(_Old()).await_end(5.0, reply=Brew))
    assert AWAIT_SINCE == "1.15"
    assert "protocol 1.15" in refused.value.error.message
    assert refused.value.error.code == ErrorCode.UNAVAILABLE

    async def drain() -> None:
        async for _ in _calls(_Old()).await_end_parts(5.0, reply=Brew):
            pass

    with pytest.raises(CommandError) as streamed:
        asyncio.run(drain())
    assert "protocol 1.15" in streamed.value.error.message


def test_a_wait_names_the_workflow_its_time_and_the_handlers_metadata() -> None:
    stub = _Recording(_STATE)
    state = asyncio.run(_calls(stub).await_end(2.5, reply=Brew))
    assert state == Brew(steps=["boil", "pour"])
    (asked,) = stub.sent
    assert isinstance(asked, client_pb2.AwaitEndRequest)
    assert (asked.component_id, asked.entity_id, asked.timeout_millis) == ("brew", "b1", 2500)


def test_a_failed_workflow_raises_with_its_step_and_reason_as_details() -> None:
    with pytest.raises(CommandError) as failed:
        asyncio.run(_calls(_Recording(_FAILED)).await_end(5.0, reply=Brew))
    assert failed.value.error.code == ErrorCode.WORKFLOW_FAILED
    assert failed.value.error.code.http_status == 424
    assert failed.value.error.details["step"] == "boil"
    assert failed.value.error.details["reason"] == "step 'boil' failed: dry"


def test_there_is_no_default_a_timeout_of_zero_is_refused_and_nothing_is_sent() -> None:
    stub = _Recording(_STATE)
    with pytest.raises(CommandError) as refused:
        asyncio.run(_calls(stub).await_end(0, reply=Brew))
    assert refused.value.error.code == ErrorCode.BAD_REQUEST
    with pytest.raises(TypeError):
        asyncio.run(_calls(stub).await_end(1.0))
    assert stub.sent == []


def test_a_command_then_a_wait_is_one_call_the_command_first() -> None:
    stub = _Recording(_STATE)
    invocation = Invocation(stub, discovery_pb2.WORKFLOW, "brew", "b1", "start", Metadata())  # type: ignore[arg-type]
    state = asyncio.run(invocation.then_await_end(5.0).invoke("kettle", reply=Brew))
    assert state == Brew(steps=["boil", "pour"])
    command, wait = stub.sent
    assert isinstance(command, client_pb2.InvokeRequest) and command.name == "start"
    assert isinstance(wait, client_pb2.AwaitEndRequest)
    assert 0 < wait.timeout_millis <= 5000


def test_as_a_stream_heartbeats_then_one_end_each_with_its_json() -> None:
    heartbeat = client_pb2.StreamToken(heartbeat=payload_pb2.Empty())
    ended = client_pb2.StreamToken(ended=_STATE.reply.payload)
    stub = _Recording(_STATE, [heartbeat, heartbeat, ended])

    async def parts() -> list[Any]:
        return [part async for part in _calls(stub).await_end_parts(5.0, reply=Brew)]

    seen = asyncio.run(parts())
    assert seen[:2] == [Heartbeat(), Heartbeat()]
    assert isinstance(seen[2], Ended) and seen[2].state == Brew(steps=["boil", "pour"])
    assert [part.to_json() for part in seen] == [
        '{"heartbeat":true}',
        '{"heartbeat":true}',
        '{"ended":{"steps":["boil","pour"]}}',
    ]

    timed_out = client_pb2.StreamToken(failed=payload_pb2.Error(message="late", code=payload_pb2.TIMEOUT))
    failed = client_pb2.StreamToken(failed=_FAILED.error)

    async def last(token: client_pb2.StreamToken) -> Any:
        return [part async for part in _calls(_Recording(_STATE, [token])).await_end_parts(5.0, reply=Brew)][-1]

    assert isinstance(asyncio.run(last(timed_out)), TimedOut)
    assert isinstance(asyncio.run(last(failed)), Failed)
    assert json.loads(asyncio.run(last(failed)).to_json()) == {
        "failed": {
            "code": "WORKFLOW_FAILED",
            "message": "workflow brew 'b1' failed: step 'boil' failed: dry",
            "step": "boil",
            "reason": "step 'boil' failed: dry",
        }
    }
