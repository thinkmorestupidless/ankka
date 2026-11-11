"""The documentation's personal data samples (docs/build/personal-data.md): a personal field marked
on an event, an agent session and an instance started with a data subject, the erasure handler, and
an erasure asked from an endpoint."""

from __future__ import annotations

import asyncio
import json
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import date

import pytest

from ankka import Acl, Ankka, Endpoint, json_codec, post
from ankka import keyring as keyrings
from ankka._proto.ankka.protocol.v1 import client_pb2, erasure_pb2, payload_pb2
from ankka.autonomous import AutonomousAgent, TaskAcceptance, TaskType
from ankka.client import ComponentClient
from ankka.context import Metadata
from ankka.erasure import ErasureContext, ErasureReport, ErasureServicer
from ankka.keyring import InMemoryKeyring
# docs:start marking
from ankka.personal import DataSubject, Personal
# docs:end marking
from ankka.storage import InMemoryBuckets, subject_prefix
# docs:start marking


@dataclass(frozen=True)
class PlayerRegistered:
    playerId: str
    email: Personal[str]
    currency: str


def registered(player_id: str, email: str) -> PlayerRegistered:
    return PlayerRegistered(player_id, Personal.of(DataSubject(f"player/{player_id}"), email), "EUR")
# docs:end marking


@pytest.fixture(autouse=True)
def _keyring() -> Iterator[InMemoryKeyring]:
    before = keyrings.current()
    ring = InMemoryKeyring("brand")
    keyrings.install(ring)
    yield ring
    keyrings.install(before)


def test_a_marked_field_is_sealed_in_the_store_and_read_back(_keyring: InMemoryKeyring) -> None:
    codec = json_codec(PlayerRegistered, "player-registered")
    event = registered("8c1f", "ada@example.com")
    asyncio.run(_keyring.prefetch_values(event))
    data = codec.encode(event)
    assert b"ada@example.com" not in data
    assert json.loads(data)["email"]["subject"] == "player/8c1f"
    assert codec.decode(data) == event
    assert "ada@example.com" not in repr(event.email)


def test_a_data_subject_outside_the_rule_raises_value_error() -> None:
    with pytest.raises(ValueError, match="is not a data subject"):
        DataSubject("player 8c1f")


# ── Agents ───────────────────────────────────────────────────────────────────

RESOLVE: TaskType[None] = TaskType("resolve", "Resolve a support case")


class Resolver(AutonomousAgent):
    component_id = "resolver"
    description = "Resolves a support case."
    accepts = [TaskAcceptance(RESOLVE, max_iterations=3)]


class _Sidecar:
    """A sidecar's ``Invoke``: keeps every request and answers as an agent and an instance would."""

    def __init__(self) -> None:
        self.sent: list[client_pb2.InvokeRequest] = []

    async def Invoke(self, request: client_pb2.InvokeRequest) -> client_pb2.InvokeReply:  # noqa: N802
        self.sent.append(request)
        data = b"{}" if request.name == "assign" else b"we will look into it"
        return client_pb2.InvokeReply(
            reply=payload_pb2.Outcome.Reply(payload=payload_pb2.Payload(content_type="text/plain", manifest="string", data=data))
        )


async def ask_about_a_player(client: ComponentClient) -> None:
    # docs:start agent-subject
    calls = client.for_agent("helper", "support-91", subject="player/8c1f")
    await calls.call("ask").invoke("my card was declined", reply=str)

    resolver = client.for_autonomous_agent(Resolver, "case-91", subject="player/8c1f")
    # docs:end agent-subject
    await resolver.assign("t-1")


def test_a_session_and_an_instance_started_with_a_data_subject_name_it() -> None:
    sidecar = _Sidecar()
    client = ComponentClient(address="127.0.0.1:0")
    client._stub_cache = sidecar  # type: ignore[assignment]
    asyncio.run(ask_about_a_player(client))
    asked, assigned = sidecar.sent
    assert (asked.component_id, asked.entity_id, asked.name) == ("helper", "support-91", "ask")
    assert [e.value for e in asked.metadata.entries if e.key == "ankka-data-subject"] == ["player/8c1f"]
    assert (assigned.component_id, assigned.entity_id) == ("resolver", "case-91")
    assert json.loads(assigned.payload.data) == {"taskIds": ["t-1"], "subject": "player/8c1f"}


# ── The erasure handler ──────────────────────────────────────────────────────

service = Ankka.service()


# docs:start erasure-handler
@service.erasure_handler
async def erase(ctx: ErasureContext, subject: DataSubject) -> ErasureReport:
    removed = await ctx.storage.bucket("kyc").erase(subject)
    return ErasureReport(objects_erased=removed.count, finality=removed.finality)
# docs:end erasure-handler


class _NoClient:
    def with_metadata(self, metadata: Metadata) -> _NoClient:
        return self


def test_the_handler_erases_the_subjects_objects_and_reports_them() -> None:
    buckets = InMemoryBuckets({"kyc": "private"})

    async def run() -> tuple[erasure_pb2.ErasureReply, erasure_pb2.ErasureReply]:
        kyc = buckets.bucket("kyc")
        await kyc.put(subject_prefix("player/8c1f") + "passport.jpg", b"x", content_type="image/jpeg")
        await kyc.put(subject_prefix("player/9d2e") + "passport.jpg", b"x", content_type="image/jpeg")
        registry = service.validate()
        servicer = ErasureServicer(registry.erasure_handler, _NoClient(), buckets)  # type: ignore[arg-type]
        first = await servicer.Invoke(erasure_pb2.ErasureCall(subject="player/8c1f", erasure_seq=1), None)
        again = await servicer.Invoke(erasure_pb2.ErasureCall(subject="player/8c1f", erasure_seq=1, rerun=True), None)
        return first, again

    first, again = asyncio.run(run())
    assert (first.objects_erased, first.finality, first.HasField("error")) == (1, "immediate", False)
    assert again.objects_erased == 0


# ── Asking for an erasure from code ──────────────────────────────────────────


class _Asks:
    def __init__(self) -> None:
        self.asked: list[client_pb2.AskErasureRequest] = []

    async def AskErasure(self, request: client_pb2.AskErasureRequest) -> client_pb2.AskErasureReply:  # noqa: N802
        self.asked.append(request)
        return client_pb2.AskErasureReply(ok=client_pb2.ErasureAsked(id="er-1", state="held"))


class _Client:
    def __init__(self, stub: _Asks) -> None:
        self._stub = stub
        self._metadata = Metadata()


class AccountsEndpoint(Endpoint):
    prefix = "/accounts"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: object) -> None:
        self.client = client

    @post("/close")
    async def close(self) -> str:
        # docs:start ask-erasure
        asked = await self.erasures.ask("player/8c1f", not_before=date(2031, 10, 8), reason="aml-retention")
        # docs:end ask-erasure
        return asked.state


def test_an_endpoint_asks_for_an_erasure_held_until_its_date() -> None:
    stub = _Asks()
    assert asyncio.run(AccountsEndpoint(_Client(stub)).close()) == "held"
    (request,) = stub.asked
    assert (request.subject, request.not_before, request.reason) == ("player/8c1f", "2031-10-08", "aml-retention")
