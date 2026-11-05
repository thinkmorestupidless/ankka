"""FR-032 (feature 042): a replicated entity's keys are its home's. A copy at a follower tracks the
home from Init.home, each event's installation and Command.home, asks for every key it decodes or
seals with that home named, never asks for one to be made there, and keeps another home's keys apart
from its own installation's. A home's keyring that cannot be reached fails the copy's read; it is
never read as erased."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Callable
from dataclasses import dataclass
from typing import Any

import pytest

from ankka import EventSourcedEntity, ReadOnlyEffect, json_codec, query
from ankka import keyring as keyrings
from ankka._proto.ankka.protocol.v1 import event_sourced_pb2, payload_pb2
from ankka.keyring import Keyring, KeyringSettings
from ankka.personal import KeyringUnavailable, Personal, Present
from ankka.server import EventSourcedServicer
from ankka.service import Registry

SUBJECT = "player/8c1f"


@dataclass(frozen=True)
class Joined:
    email: Personal[str]


@dataclass(frozen=True)
class Member:
    email: Personal[str] | None = None


class MemberEntity(EventSourcedEntity[Member, Joined]):
    component_id = "member"
    replicated = True
    state_codec = json_codec(Member, "member")
    event_codec = json_codec(Joined, "joined")

    def empty_state(self) -> Member:
        return Member()

    def apply_event(self, state: Member, event: Joined) -> Member:
        return Member(event.email)

    @query("email")
    def email(self) -> ReadOnlyEffect[Member, Joined, str]:
        found = self.state.email
        return self.effects.reply("none" if found is None else found.or_else("erased"))


class Homes(Keyring):
    """The sidecar beside a follower at "b" of an entity whose home is "a": "a" keeps the subject's
    key, and "b"'s own keyring a different key of the same subject. A home in ``down`` cannot be
    reached. Every fetch is recorded as (home, project, subject, create)."""

    def __init__(self, clock: Callable[[], float]) -> None:
        super().__init__(None, KeyringSettings(), clock)
        self.keys: dict[tuple[str, str, str], bytes] = {
            ("a", "club", SUBJECT): bytes([1]) * 32,
            ("", "club", SUBJECT): bytes([2]) * 32,
        }
        self.down: set[str] = set()
        self.asked: list[tuple[str, str, str, bool]] = []

    async def _fetch(self, project: str, subject: str, create: bool) -> tuple[str, bytes | None]:
        home = keyrings.current_home()
        self.asked.append((home, project, subject, create))
        if home in self.down:
            raise KeyringUnavailable(f"the keyring is unavailable: installation '{home}' cannot be reached")
        project = project or "club"
        at = (home, project, subject)
        if at not in self.keys and create:
            self.keys[at] = bytes([9]) * 32
        return project, self.keys.get(at)


class _Clock:
    def __init__(self) -> None:
        self.now = 1_000_000.0

    def __call__(self) -> float:
        return self.now


class _NoClient:
    def with_metadata(self, metadata: Any) -> _NoClient:
        return self


In = event_sourced_pb2.EventSourcedIn


@pytest.fixture(autouse=True)
def _restore_keyring() -> Any:
    before = keyrings.current()
    yield
    keyrings.install(before)


def _follower() -> tuple[Homes, _Clock, payload_pb2.Payload]:
    clock = _Clock()
    ring = Homes(clock)
    keyrings.install(ring)
    codec = MemberEntity.event_codec
    event = Joined(Personal.of(SUBJECT, "ada@example.com"))

    async def seal() -> bytes:
        await ring.prefetch_values([Personal.of("player/0", "x")])  # learns its own project
        with keyrings.at_home("a"):
            await ring.prefetch_values([event])
            return codec.encode(event)

    data = asyncio.run(seal())
    ring.drop_all()  # nothing the writing held is kept: the follower asks for what it reads
    ring.asked.clear()
    return ring, clock, payload_pb2.Payload(content_type=codec.content_type, manifest=codec.manifest, data=data)


def _drive(*messages: Any, between: Callable[[int], None] = lambda _: None) -> list[event_sourced_pb2.EventSourcedOut]:
    registry = Registry()
    registry.entities["member"] = MemberEntity
    servicer = EventSourcedServicer(registry, client=_NoClient())  # type: ignore[arg-type]

    async def feed() -> AsyncIterator[Any]:
        for i, m in enumerate(messages):
            between(i)
            yield m

    async def run() -> list[event_sourced_pb2.EventSourcedOut]:
        return [out async for out in servicer.Handle(feed(), None)]

    return asyncio.run(run())


def _init(home: str = "", snapshot: payload_pb2.Payload | None = None) -> Any:
    init = In.Init(component_id="member", entity_id="m1", installation="b", home=home)
    if snapshot is not None:
        init.snapshot.CopyFrom(In.Snapshot(sequence=1, payload=snapshot))
    return In(init=init)


def _event(payload: payload_pb2.Payload) -> Any:
    return In(event=In.Event(sequence=1, payload=payload, installation="a"))


def _ask() -> Any:
    return In(command=In.Command(id=1, name="email", installation="b", at_home=False, home="a", snapshot_requested=True))


def test_a_follower_reads_and_writes_with_its_homes_keys_and_makes_none() -> None:
    ring, _, joined = _follower()
    out = _drive(_init(), _event(joined), _ask())
    assert out[0].WhichOneof("message") == "reply", out[0]
    assert out[0].reply.outcome.reply.payload.data == b"ada@example.com"
    assert ring.asked, "no key was asked for"
    assert all(home == "a" and not create for home, _, _, create in ring.asked), ring.asked
    assert len(ring.keys) == 3, f"a key was made: {sorted(ring.keys)}"  # player/0, and the subject's at "a" and at "b"

    # Recovered again from the snapshot the follower wrote: Init.home names whose keys it is.
    ring.drop_all()
    again = _drive(_init("a", out[0].reply.snapshot), _ask())
    assert again[0].reply.outcome.reply.payload.data == b"ada@example.com", again[0]

    # A subject the home holds no key for, written with the home's keys: no key is made, there or here.
    fresh = Member(Personal.of("player/new", "x"))

    async def write() -> None:
        with keyrings.at_home("a"):
            await ring.prefetch_values([fresh])
            MemberEntity.state_codec.encode(fresh)

    with pytest.raises(Exception):
        asyncio.run(write())
    assert ("a", "club", "player/new") not in ring.keys and ("", "club", "player/new") not in ring.keys


def test_two_homes_keys_of_one_subject_never_mix() -> None:
    ring, _, joined = _follower()

    async def own_then_home() -> Any:
        await ring.prefetch_json(b'{"$personal":1,"project":"club","subject":"' + SUBJECT.encode() + b'","c":"AAAA"}')
        assert ring.holds("club", SUBJECT)
        with keyrings.at_home("a"):
            await ring.prefetch_json(joined.data)
            return MemberEntity.event_codec.decode(joined.data)

    read = asyncio.run(own_then_home())
    assert isinstance(read.email, Present) and read.email.value == "ada@example.com"
    assert ring.holds("club", SUBJECT) and ring.holds("club", SUBJECT, "a")


def test_a_home_that_cannot_be_reached_fails_the_read_and_is_never_erased() -> None:
    ring, clock, joined = _follower()
    ring.down.add("a")
    out = _drive(_init(), _event(joined), _ask())
    assert out[0].WhichOneof("message") == "failure", out[0]
    assert out[0].failure.error.code == payload_pb2.UNAVAILABLE, out[0]

    # Recovered while the home answers, then the home goes past the held key's life: the command
    # fails, unavailable, rather than reading the field erased.
    ring.down.clear()

    def outage(i: int) -> None:
        if i == 2:
            ring.down.add("a")
            clock.now += 31

    later = _drive(_init(), _event(joined), _ask(), between=outage)
    message = later[0]
    which = message.WhichOneof("message")
    error = message.failure.error if which == "failure" else message.reply.outcome.error
    assert error.code == payload_pb2.UNAVAILABLE, message
