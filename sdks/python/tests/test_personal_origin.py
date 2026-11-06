"""FR-027 (feature 042): what a service keeps of a personal field it read from another project's
envelope stays sealed under the producing project's key, so an erasure there reaches the copy here
with no notice of its own. A field a handler makes, even with the same value, is the service's own.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import Iterator
from dataclasses import dataclass, replace

import pytest

from ankka import json_codec
from ankka import keyring as keyrings
from ankka.keyring import InMemoryKeyring, KeyringSettings
from ankka.personal import Erased, Personal, Present, origin_of

KEY = bytes(range(32))


@dataclass(frozen=True)
class Registered:
    player_id: str
    email: Personal[str]


@dataclass(frozen=True)
class Copy:
    email: Personal[str]


class _Clock:
    now = 1000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def setup() -> Iterator[tuple[InMemoryKeyring, _Clock, bytes]]:
    """A consumer of 'brand' with 'casino''s subject key under a grant, and a message 'casino'
    published: an envelope sealed under 'casino''s key."""
    clock = _Clock()
    producer = InMemoryKeyring("casino")
    producer.put("casino", "player/8c1f", KEY)
    before = keyrings.install(producer)
    codec = json_codec(Registered, "registered")
    message = Registered("8c1f", Personal.of("player/8c1f", "ada@example.com"))
    asyncio.run(producer.prefetch_values(message))
    published = codec.encode(message)
    consumer = InMemoryKeyring("brand", KeyringSettings(), clock)
    consumer.put("casino", "player/8c1f", KEY)
    keyrings.install(consumer)
    try:
        yield consumer, clock, published
    finally:
        keyrings.install(before)


def _read(ring: InMemoryKeyring, data: bytes, cls: type, manifest: str) -> object:
    asyncio.run(ring.prefetch_json(data))
    return json_codec(cls, manifest).decode(data)


def _write(ring: InMemoryKeyring, value: object, manifest: str) -> bytes:
    asyncio.run(ring.prefetch_values(value))
    return json_codec(type(value), manifest).encode(value)


def test_a_field_read_from_another_project_is_kept_under_that_projects_key(setup: tuple[InMemoryKeyring, _Clock, bytes]) -> None:
    ring, _, published = setup
    read = _read(ring, published, Registered, "registered")
    assert isinstance(read, Registered) and origin_of(read.email) == "casino"
    kept = _write(ring, Copy(read.email), "copy")
    envelope = json.loads(kept)["email"]
    assert envelope["project"] == "casino" and "c" in envelope
    assert b"ada@example.com" not in kept
    # This project made no key for the subject: nothing of the copy is under its own.
    assert ("brand", "player/8c1f") not in ring.keys
    again = _read(ring, kept, Copy, "copy")
    assert isinstance(again, Copy) and again.email == Present("player/8c1f", "ada@example.com")


def test_an_erasure_in_the_producing_project_reaches_the_kept_copy(setup: tuple[InMemoryKeyring, _Clock, bytes]) -> None:
    ring, clock, published = setup
    read = _read(ring, published, Registered, "registered")
    assert isinstance(read, Registered)
    kept = _write(ring, Copy(read.email), "copy")
    # 'casino' erases the subject; no notice of it reaches 'brand', whose key expires at foreign-ttl.
    ring.keys.pop(("casino", "player/8c1f"))
    ring.destroyed.add(("casino", "player/8c1f"))
    clock.now += 31
    again = _read(ring, kept, Copy, "copy")
    assert isinstance(again, Copy) and again.email == Erased("player/8c1f")


def test_a_kept_field_whose_key_is_gone_is_written_erased(setup: tuple[InMemoryKeyring, _Clock, bytes]) -> None:
    ring, clock, published = setup
    read = _read(ring, published, Registered, "registered")
    assert isinstance(read, Registered)
    ring.keys.pop(("casino", "player/8c1f"))  # the grant is revoked, or the subject erased there
    clock.now += 31
    kept = _write(ring, Copy(read.email), "copy")
    assert json.loads(kept)["email"] == {"$personal": 1, "project": "casino", "subject": "player/8c1f"}


def test_a_field_a_handler_makes_with_the_same_value_is_the_services_own(setup: tuple[InMemoryKeyring, _Clock, bytes]) -> None:
    ring, _, published = setup
    read = _read(ring, published, Registered, "registered")
    assert isinstance(read, Registered) and isinstance(read.email, Present)
    made = Personal.of(read.email.subject, read.email.value)
    assert made == read.email and origin_of(made) is None
    assert origin_of(replace(read, email=made).email) is None
    kept = _write(ring, Copy(made), "copy")
    assert json.loads(kept)["email"]["project"] == "brand"
