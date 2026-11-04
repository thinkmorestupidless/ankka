"""Asking for an erasure request from code (feature 042, protocol 1.23), against a stub client."""

from __future__ import annotations

import asyncio
from datetime import date
from typing import Any

import grpc
import pytest

from ankka import Endpoint, ErasureAsked, ErrorCode
from ankka._proto.ankka.protocol.v1 import client_pb2, payload_pb2
from ankka.client import CommandError
from ankka.context import Metadata
from ankka.endpoint import Acl
from ankka.erasure import ErasureContext
from ankka.erasures import Erasures


class _Stub:
    def __init__(self, reply: client_pb2.AskErasureReply) -> None:
        self.reply = reply
        self.asked: list[client_pb2.AskErasureRequest] = []

    async def AskErasure(self, request: client_pb2.AskErasureRequest) -> client_pb2.AskErasureReply:
        self.asked.append(request)
        return self.reply


class _Client:
    def __init__(self, stub: Any) -> None:
        self._stub = stub
        self._metadata = Metadata()


def _asked(id: str = "er-1", state: str = "held") -> _Stub:
    return _Stub(client_pb2.AskErasureReply(ok=client_pb2.ErasureAsked(id=id, state=state)))


def test_an_ask_for_its_own_project_sends_it_empty() -> None:
    stub = _asked()
    asyncio.run(Erasures(_Client(stub)).ask("customer-42"))
    request = stub.asked[0]
    assert request.project == "" and request.subject == "customer-42"
    assert not request.HasField("not_before") and not request.HasField("reason") and not request.HasField("correlation")


def test_an_ask_sends_its_date_as_iso_8601_and_its_words() -> None:
    stub = _asked()
    asyncio.run(
        Erasures(_Client(stub)).ask(
            "customer-42", project="ledger", not_before=date(2027, 1, 31), reason="aml-retention", correlation="c-7"
        )
    )
    request = stub.asked[0]
    assert request.project == "ledger"
    assert request.not_before == "2027-01-31"
    assert request.reason == "aml-retention" and request.correlation == "c-7"


def test_the_answer_is_the_request_id_and_its_state() -> None:
    answer = asyncio.run(Erasures(_Client(_asked("er-9", "applying"))).ask("customer-42"))
    assert answer == ErasureAsked("er-9", "applying")


def test_a_refusal_is_raised_with_its_code() -> None:
    stub = _Stub(
        client_pb2.AskErasureReply(
            error=payload_pb2.Error(code=payload_pb2.FORBIDDEN, message="this service has no erasure grant")
        )
    )
    with pytest.raises(CommandError) as refused:
        asyncio.run(Erasures(_Client(stub)).ask("customer-42"))
    assert refused.value.error.code == ErrorCode.FORBIDDEN
    assert refused.value.error.message == "this service has no erasure grant"


class _Unimplemented:
    async def AskErasure(self, _request: Any) -> Any:
        raise grpc.aio.AioRpcError(grpc.StatusCode.UNIMPLEMENTED, grpc.aio.Metadata(), grpc.aio.Metadata(), "Method not found")


def test_an_older_runtime_is_refused_naming_1_23() -> None:
    with pytest.raises(CommandError) as refused:
        asyncio.run(Erasures(_Client(_Unimplemented())).ask("customer-42"))
    assert "1.23" in refused.value.error.message


class _Asking(Endpoint):
    prefix = "/asking"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: Any) -> None:
        self.client = client


def test_endpoints_and_the_erasure_handler_reach_erasures_through_their_client() -> None:
    stub = _asked()
    asyncio.run(_Asking(_Client(stub)).erasures.ask("customer-1"))
    ctx = ErasureContext(1, False, Metadata(), _Client(stub))  # type: ignore[arg-type]
    asyncio.run(ctx.erasures.ask("customer-2"))
    assert [r.subject for r in stub.asked] == ["customer-1", "customer-2"]
