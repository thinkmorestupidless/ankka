"""Personal fields (feature 042): the envelope written byte for byte and read back, the erased and
corrupt cases, the edge form at an endpoint, the keyring's prefetch and notices, the erasure
handler, and the refusal of a runtime before 1.23. Against ``proto/fixtures/personal/``."""

from __future__ import annotations

import asyncio
import base64
import json
from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path
from typing import Any

import pytest

from ankka import Acl, Ankka, Endpoint, EventSourcedEffect, EventSourcedEntity, command, json_codec, post
from ankka import keyring as keyrings
from ankka import personal
from ankka._proto.ankka.protocol.v1 import client_pb2, erasure_pb2, event_sourced_pb2, payload_pb2
from ankka.codec import from_json_value, to_json_value, write_json
from ankka.context import Metadata
from ankka.erasure import ErasureContext, ErasureReport, ErasureServicer
from ankka.keyring import InMemoryKeyring, Keyring, SidecarKeyring
from ankka.personal import (
    CorruptEnvelope,
    DataSubject,
    Erased,
    KeyringUnavailable,
    Personal,
    Present,
    SubjectErased,
)
from ankka.server import EventSourcedServicer, personal_refusal
from ankka.service import Registry
from ankka.testkit import EndpointTestKit

FIXTURES = Path(__file__).resolve().parent.parent / "proto" / "fixtures" / "personal"
if not FIXTURES.is_dir():
    FIXTURES = Path(__file__).resolve().parents[3] / "protocol" / "fixtures" / "personal"

ENVELOPES = json.loads((FIXTURES / "envelopes.json").read_text(encoding="utf-8"))
SUBJECTS = json.loads((FIXTURES / "subjects.json").read_text(encoding="utf-8"))
EDGE = json.loads((FIXTURES / "edge.json").read_text(encoding="utf-8"))


@dataclass(frozen=True)
class Address:
    line1: str
    city: str


# The type each envelope case's plaintext is.
TYPES: dict[str, Any] = {
    "a string": str,
    "a date": date,
    "a record": Address,
    "unicode and every allowed subject character": str,
}


@pytest.fixture(autouse=True)
def _restore_keyring() -> Any:
    before = keyrings.current()
    yield
    keyrings.install(before)


def _keyring_for(case: dict[str, Any]) -> InMemoryKeyring:
    ring = InMemoryKeyring(case.get("project", "brand"))
    ring.put(case.get("project", ring.project), case["subject"], base64.b64decode(case["key"]))
    keyrings.install(ring)
    return ring


def _compact(value: Any) -> str:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


# ── Data subjects ───────────────────────────────────────────────────────────


@pytest.mark.parametrize("subject", SUBJECTS["valid"])
def test_a_valid_subject_is_one(subject: str) -> None:
    assert DataSubject(subject) == subject


@pytest.mark.parametrize("subject", SUBJECTS["invalid"])
def test_an_invalid_subject_is_refused_with_the_rule(subject: str) -> None:
    with pytest.raises(ValueError) as refused:
        DataSubject(subject)
    assert SUBJECTS["message"] in str(refused.value)
    with pytest.raises(ValueError):
        Personal.of(subject, "x")


def test_the_textual_form_names_the_subject_and_never_the_value() -> None:
    field = Personal.of(DataSubject("player/8c1f"), "ada@example.com")

    @dataclass
    class Holder:
        email: Personal[str]

    for text in (str(field), repr(field), f"{field}", repr(Holder(field)), str(Erased(DataSubject("player/8c1f")))):
        assert "ada@example.com" not in text
        assert "Personal(player/8c1f)" in text
    assert field.or_else("none") == "ada@example.com"
    assert Erased(DataSubject("player/8c1f")).or_else("none") == "none"


# ── The envelope, byte for byte ─────────────────────────────────────────────


@pytest.mark.parametrize("case", ENVELOPES["cases"], ids=lambda c: c["name"])
def test_each_fixture_envelope_is_written_exactly(case: dict[str, Any]) -> None:
    ring = _keyring_for(case)
    tp = TYPES[case["name"]]
    value = from_json_value(json.loads(case["plaintext"]), tp)
    field = Personal.of(case["subject"], value)
    asyncio.run(ring.prefetch_values(field))
    with personal.within(case["manifest"]), personal.fixed_nonce(base64.b64decode(case["nonce"])):
        written = write_json(to_json_value(field, Personal[tp]))  # type: ignore[valid-type]
    assert written == _compact(case["envelope"])


@pytest.mark.parametrize("case", ENVELOPES["cases"], ids=lambda c: c["name"])
def test_each_fixture_envelope_reads_back_present(case: dict[str, Any]) -> None:
    ring = _keyring_for(case)
    tp = TYPES[case["name"]]
    asyncio.run(ring.prefetch_json(_compact(case["envelope"])))
    with personal.within(case["manifest"]):
        read = from_json_value(case["envelope"], Personal[tp])  # type: ignore[valid-type]
    assert read == Present(DataSubject(case["subject"]), from_json_value(json.loads(case["plaintext"]), tp))


@dataclass(frozen=True)
class PlayerRegistered:
    player_id: str
    email: Personal[str]
    name: Personal[str] | None = None


def test_a_codec_seals_under_its_manifest_and_opens_what_it_sealed() -> None:
    case = ENVELOPES["cases"][0]
    ring = _keyring_for(case)
    codec = json_codec(PlayerRegistered, case["manifest"])
    event = PlayerRegistered("8c1f", Personal.of(case["subject"], "ada@example.com"))
    asyncio.run(ring.prefetch_values(event))
    with personal.fixed_nonce(base64.b64decode(case["nonce"])):
        data = codec.encode(event)
    assert json.loads(data)["email"] == case["envelope"]
    assert b"ada@example.com" not in data
    assert codec.decode(data) == event
    # Read under another manifest, the same bytes fail authentication: the manifest is bound.
    with pytest.raises(CorruptEnvelope):
        json_codec(PlayerRegistered, "other").decode(data)


def test_a_field_with_random_nonces_differs_each_time_and_opens() -> None:
    ring = InMemoryKeyring("brand")
    keyrings.install(ring)
    codec = json_codec(PlayerRegistered, "player-registered")
    event = PlayerRegistered("1", Personal.of("player/1", "ada@example.com"))
    asyncio.run(ring.prefetch_values(event))
    first, second = codec.encode(event), codec.encode(event)
    assert first != second
    assert codec.decode(first) == codec.decode(second) == event


# ── Erased, corrupt, unknown ────────────────────────────────────────────────


def test_the_erased_fixture_is_written_and_read() -> None:
    case = ENVELOPES["erased"][0]
    keyrings.install(InMemoryKeyring(case["project"]))
    with personal.within("player-registered"):
        assert write_json(to_json_value(Erased(DataSubject(case["subject"])), Personal[str])) == _compact(case["envelope"])
        assert from_json_value(case["envelope"], Personal[str]) == Erased(DataSubject(case["subject"]))


def test_a_sealed_envelope_of_an_erased_subject_decodes_as_erased_and_never_raises() -> None:
    case = ENVELOPES["cases"][0]
    ring = _keyring_for(case)
    ring.erase(case["subject"])
    codec = json_codec(PlayerRegistered, case["manifest"])
    data = _compact({"player_id": "8c1f", "email": case["envelope"]}).encode("utf-8")
    asyncio.run(ring.prefetch_json(data))
    assert codec.decode(data) == PlayerRegistered("8c1f", Erased(DataSubject(case["subject"])))


def test_a_write_for_an_erased_subject_is_refused_naming_it() -> None:
    ring = InMemoryKeyring("brand")
    keyrings.install(ring)
    ring.erase("player/8c1f")
    with pytest.raises(SubjectErased) as refused:
        asyncio.run(ring.prefetch_values(PlayerRegistered("8c1f", Personal.of("player/8c1f", "ada@example.com"))))
    assert str(refused.value) == "the data subject 'player/8c1f' is erased; nothing was written"
    assert refused.value.error.details == {"personal": "erased"}


@pytest.mark.parametrize("case", ENVELOPES["corrupt"], ids=lambda c: c["name"])
def test_a_corrupt_envelope_fails_authentication_and_is_never_erased(case: dict[str, Any]) -> None:
    ring = InMemoryKeyring("brand")
    ring.put("brand", case["envelope"]["subject"], base64.b64decode(case["key"]))
    keyrings.install(ring)
    asyncio.run(ring.prefetch_json(_compact(case["envelope"])))
    with personal.within(case["manifest"]), pytest.raises(CorruptEnvelope) as refused:
        from_json_value(case["envelope"], Personal[str])
    assert "failed authentication" in str(refused.value)
    assert f"'{case['envelope']['subject']}'" in str(refused.value)


def test_an_unknown_version_is_corrupt() -> None:
    keyrings.install(InMemoryKeyring("brand"))
    with personal.within("player-registered"), pytest.raises(CorruptEnvelope):
        from_json_value(ENVELOPES["unknown-version"], Personal[str])


def test_no_keyring_is_unavailable_for_a_write_and_a_read() -> None:
    keyrings.install(Keyring())
    with pytest.raises(KeyringUnavailable) as refused:
        asyncio.run(keyrings.current().prefetch_values(Personal.of("player/8c1f", "x")))
    assert str(refused.value).startswith("the keyring is unavailable")
    with pytest.raises(KeyringUnavailable):
        asyncio.run(keyrings.current().prefetch_json(_compact(ENVELOPES["cases"][0]["envelope"])))


def test_a_codec_run_before_the_prefetch_is_unavailable_never_erased() -> None:
    keyrings.install(InMemoryKeyring("brand"))
    with personal.within("player-registered"), pytest.raises(KeyringUnavailable):
        from_json_value(ENVELOPES["cases"][0]["envelope"], Personal[str])


# ── The edge form, at an endpoint ───────────────────────────────────────────


@dataclass(frozen=True)
class Profile:
    subject: str
    email: Personal[Any]


class ProfileEndpoint(Endpoint):
    prefix = "/profiles"
    acl = Acl.ALLOW_ALL

    @post("/echo")
    def echo(self, body: Profile) -> Profile:
        return body


@pytest.mark.parametrize("case", EDGE["cases"], ids=lambda c: c["name"])
def test_an_endpoint_reads_and_answers_the_edge_form(case: dict[str, Any]) -> None:
    keyrings.install(Keyring())  # the edge needs no keyring
    body = _compact({"subject": case["subject"], "email": case["edge"]}).encode("utf-8")
    seen: list[Profile] = []

    class Recording(ProfileEndpoint):
        def echo(self, body: Profile) -> Profile:
            seen.append(body)
            return body

    Recording._routes = ProfileEndpoint._routes  # type: ignore[attr-defined]
    reply = EndpointTestKit.of(Recording).post("/profiles/echo", body)
    assert reply.status == 200, reply.text()
    assert reply.json()["email"] == case["edge"]
    expected: Personal[Any] = (
        Present(DataSubject(case["subject"]), case["value"]) if "value" in case else Erased(DataSubject(case["subject"]))
    )
    assert seen == [Profile(case["subject"], expected)]


@pytest.mark.parametrize("case", EDGE["cases"], ids=lambda c: c["name"])
def test_outside_a_codec_scope_a_field_is_its_edge_form(case: dict[str, Any]) -> None:
    field: Personal[Any] = (
        Personal.of(case["subject"], case["value"]) if "value" in case else Erased(DataSubject(case["subject"]))
    )
    assert to_json_value(field, Personal[Any]) == case["edge"]
    assert from_json_value(case["edge"], Personal[Any]) == field


# ── An entity behind the server: prefetch, seal, refuse ─────────────────────


@dataclass(frozen=True)
class Player:
    emails: list[Personal[str]]


class PlayerEntity(EventSourcedEntity[Player, PlayerRegistered]):
    component_id = "player"
    state_codec = json_codec(Player, "player")
    event_codec = json_codec(PlayerRegistered, "player-registered")

    def empty_state(self) -> Player:
        return Player([])

    def apply_event(self, state: Player, event: PlayerRegistered) -> Player:
        return Player([*state.emails, event.email])

    @command("register")
    def register(self, email: str) -> EventSourcedEffect[Player, PlayerRegistered, int]:
        event = PlayerRegistered("8c1f", Personal.of("player/8c1f", email))
        return self.effects.persist(event).then_reply(lambda s: len(s.emails))


class _NoClient:
    def with_metadata(self, metadata: Any) -> _NoClient:
        return self


In = event_sourced_pb2.EventSourcedIn


def _drive(*messages: Any) -> list[event_sourced_pb2.EventSourcedOut]:
    registry = Registry()
    registry.entities["player"] = PlayerEntity
    servicer = EventSourcedServicer(registry, client=_NoClient())  # type: ignore[arg-type]

    async def feed() -> AsyncIterator[Any]:
        for m in messages:
            yield m

    async def run() -> list[event_sourced_pb2.EventSourcedOut]:
        return [out async for out in servicer.Handle(feed(), None)]

    return asyncio.run(run())


def _register(command_id: int) -> Any:
    codec = PlayerEntity.handlers()["register"].input_codec
    payload = payload_pb2.Payload(content_type=codec.content_type, manifest=codec.manifest, data=codec.encode("ada@example.com"))
    return In(command=In.Command(id=command_id, name="register", payload=payload))


def test_an_entity_journals_the_envelope_and_replays_it() -> None:
    ring = InMemoryKeyring("brand")
    keyrings.install(ring)
    out = _drive(In(init=In.Init(component_id="player", entity_id="8c1f")), _register(1))
    event = out[0].reply.events[0]
    assert b"ada@example.com" not in event.data
    assert json.loads(event.data)["email"]["subject"] == "player/8c1f"
    # A fresh process: nothing cached, the event replayed, the key fetched before it is decoded.
    replaying = InMemoryKeyring("brand")
    replaying.keys = dict(ring.keys)
    keyrings.install(replaying)
    out = _drive(
        In(init=In.Init(component_id="player", entity_id="8c1f")),
        In(event=In.Event(sequence=1, payload=event)),
        _register(2),
    )
    assert out[0].reply.events, out[0]
    assert int(out[0].reply.outcome.reply.payload.data) == 2


def test_an_entity_refuses_a_write_for_an_erased_subject_and_writes_nothing() -> None:
    ring = InMemoryKeyring("brand")
    ring.erase("player/8c1f")
    keyrings.install(ring)
    out = _drive(In(init=In.Init(component_id="player", entity_id="8c1f")), _register(1))
    reply = out[0].reply
    assert not reply.events
    assert reply.outcome.error.message == "the data subject 'player/8c1f' is erased; nothing was written"
    assert reply.outcome.error.code == payload_pb2.CONFLICT


# ── Notices ─────────────────────────────────────────────────────────────────


class _NoticeStub:
    """A sidecar's keyring calls: every key is ``key``, every answer names ``project``."""

    def __init__(self, project: str = "brand", key: bytes = bytes(32)) -> None:
        self.project = project
        self.key = key
        self.acks: list[client_pb2.SubjectNoticeAck] = []
        self.asked: list[client_pb2.SubjectKeyRequest] = []

    async def GetSubjectKey(self, request: client_pb2.SubjectKeyRequest) -> client_pb2.SubjectKeyReply:
        self.asked.append(request)
        return client_pb2.SubjectKeyReply(key=self.key, project=request.project or self.project)

    async def AckSubjectNotice(self, ack: client_pb2.SubjectNoticeAck) -> client_pb2.SubjectNoticeAckReply:
        self.acks.append(ack)
        return client_pb2.SubjectNoticeAckReply()


class _StubClient:
    def __init__(self, stub: _NoticeStub) -> None:
        self._stub = stub


def test_a_notice_drops_the_cached_key_and_then_acks() -> None:
    stub = _NoticeStub()
    ring = SidecarKeyring(_StubClient(stub))

    async def run() -> None:
        await ring.prefetch_values(Personal.of("player/8c1f", "x"))
        assert ring.key_for_write("player/8c1f") == bytes(32)
        await ring.notice(7, "brand", "player/8c1f")
        with pytest.raises(KeyringUnavailable):
            ring.key_for_write("player/8c1f")

    asyncio.run(run())
    assert [(a.seq, a.instance) for a in stub.acks] == [(7, ring.instance)]
    # Its own project is asked as the service's own: the request names none.
    assert stub.asked[0].project == "" and stub.asked[0].create


def test_a_process_seals_under_the_project_the_sidecar_answers() -> None:
    case = ENVELOPES["cases"][0]
    stub = _NoticeStub(project="brand", key=base64.b64decode(case["key"]))
    ring = SidecarKeyring(_StubClient(stub))
    keyrings.install(ring)
    codec = json_codec(PlayerRegistered, case["manifest"])
    event = PlayerRegistered("8c1f", Personal.of(case["subject"], "ada@example.com"))
    assert ring.project is None
    # Before any answer the process does not know its project: a write is unavailable, not "local".
    with personal.fixed_nonce(base64.b64decode(case["nonce"])), pytest.raises(KeyringUnavailable):
        codec.encode(event)
    asyncio.run(ring.prefetch_values(event))
    assert ring.project == "brand"
    with personal.fixed_nonce(base64.b64decode(case["nonce"])):
        data = codec.encode(event)
    envelope = json.loads(data)["email"]
    assert envelope["project"] == "brand"
    # "brand" is in the associated data too: the bytes are the fixture's, sealed under "brand".
    assert envelope == case["envelope"]
    assert codec.decode(data) == event
    # A later write still asks for its own project by naming none.
    asyncio.run(ring.prefetch_values(PlayerRegistered("2", Personal.of("player/2", "x"))))
    assert [r.project for r in stub.asked] == ["", ""]


# ── The erasure handler ─────────────────────────────────────────────────────


def test_the_erasure_handler_runs_and_reports() -> None:
    service = Ankka.service().register(PlayerEntity)
    calls: list[tuple[str, int, bool]] = []

    @service.erasure_handler
    async def erase(ctx: ErasureContext, subject: DataSubject) -> ErasureReport:
        calls.append((subject, ctx.erasure_seq, ctx.rerun))
        return ErasureReport(objects_erased=3, finality=timedelta(days=7))

    registry = service.validate()
    assert registry.spec().erasure_handler
    servicer = ErasureServicer(registry.erasure_handler, _NoClient())  # type: ignore[arg-type]
    reply = asyncio.run(servicer.Invoke(erasure_pb2.ErasureCall(subject="player/8c1f", erasure_seq=4, rerun=True), None))
    assert calls == [("player/8c1f", 4, True)]
    assert (reply.objects_erased, reply.finality, reply.HasField("error")) == (3, "after:PT168H", False)


def test_a_handler_that_raises_is_reported_failed() -> None:
    def erase(ctx: ErasureContext, subject: DataSubject) -> None:
        raise RuntimeError("the bucket is unreachable")

    reply = asyncio.run(ErasureServicer(erase, _NoClient()).Invoke(erasure_pb2.ErasureCall(subject="player/8c1f"), None))  # type: ignore[arg-type]
    assert reply.error.message == "the bucket is unreachable"


def test_a_service_declares_one_handler() -> None:
    service = Ankka.service()
    service.erasure_handler(lambda ctx, s: None)
    service.erasure_handler(lambda ctx, s: None)
    with pytest.raises(Exception, match="two erasure handlers"):
        service.validate()


# ── Refused under a runtime before 1.23 ─────────────────────────────────────


def test_a_runtime_before_1_23_is_refused_naming_it() -> None:
    registry = Ankka.service().register(PlayerEntity).validate()
    refusal = personal_refusal(registry, "1.22")
    assert refusal is not None and refusal.startswith("personal fields need protocol 1.23") and "PlayerEntity" in refusal
    assert personal_refusal(registry, "1.23") is None

    handled = Ankka.service()
    handled.erasure_handler(lambda ctx, s: None)
    refusal = personal_refusal(handled.validate(), "1.22")
    assert refusal is not None and "erasure handler" in refusal

    assert personal_refusal(Registry(), "1.22") is None


# ── Agents carry the data subject ───────────────────────────────────────────


class _InvokeStub:
    """A sidecar's ``Invoke``: keeps every request and answers an empty JSON object."""

    def __init__(self) -> None:
        self.sent: list[client_pb2.InvokeRequest] = []

    async def Invoke(self, request: client_pb2.InvokeRequest) -> client_pb2.InvokeReply:  # noqa: N802
        self.sent.append(request)
        data = b"{}" if request.name in ("assign", "create") else b"hello"
        return client_pb2.InvokeReply(
            reply=payload_pb2.Outcome.Reply(payload=payload_pb2.Payload(content_type="text/plain", manifest="string", data=data))
        )


def _subject_of(request: client_pb2.InvokeRequest) -> list[str]:
    return [e.value for e in request.metadata.entries if e.key == "ankka-data-subject"]


def test_an_agent_session_names_its_data_subject_on_every_call() -> None:
    from ankka.autonomous import TaskType
    from ankka.client import ComponentClient

    stub = _InvokeStub()
    client = ComponentClient(metadata=Metadata().set("traceparent", "00-t-s-01"))
    client._stub_cache = stub  # type: ignore[assignment]

    async def run() -> None:
        session = client.for_agent("support", "s-1", subject=DataSubject("player/8c1f"))
        await session.call("ask").invoke("hi", reply=str)
        await session.call("ask").invoke("again", reply=str)
        await client.for_agent("support", "s-2").call("ask").invoke("hi", reply=str)
        await client.tasks.create(TaskType("answer", "An answer."), "How many?", id="t-1", subject="player/8c1f")
        await client.tasks.create(TaskType("answer", "An answer."), "How many?", id="t-2")
        await client.for_autonomous_agent("operator", "i-1", subject="player/8c1f").assign("t-1")
        await client.for_autonomous_agent("operator", "i-2").assign("t-2")

    asyncio.run(run())
    with_subject, again, without, created, created_plain, assigned, assigned_plain = stub.sent
    assert _subject_of(with_subject) == _subject_of(again) == ["player/8c1f"]
    # The handler's own metadata goes on beside it.
    assert ("traceparent", "00-t-s-01") in [(e.key, e.value) for e in with_subject.metadata.entries]
    assert _subject_of(without) == []
    # A task and an instance carry theirs in the body, which is what their hosts read.
    assert json.loads(created.payload.data)["subject"] == "player/8c1f"
    assert "subject" not in json.loads(created_plain.payload.data)
    assert json.loads(assigned.payload.data) == {"taskIds": ["t-1"], "subject": "player/8c1f"}
    assert json.loads(assigned_plain.payload.data) == {"taskIds": ["t-2"]}


def test_an_invalid_data_subject_is_refused_before_anything_is_sent() -> None:
    from ankka.autonomous import TaskType
    from ankka.client import ComponentClient

    stub = _InvokeStub()
    client = ComponentClient()
    client._stub_cache = stub  # type: ignore[assignment]
    with pytest.raises(ValueError, match="is not a data subject"):
        client.for_agent("support", "s-1", subject="player 8c1f")
    with pytest.raises(ValueError, match="is not a data subject"):
        client.for_autonomous_agent("operator", "i-1", subject="")
    with pytest.raises(ValueError, match="is not a data subject"):
        asyncio.run(client.tasks.create(TaskType("answer", "An answer."), "How many?", subject="a b"))
    assert stub.sent == []
