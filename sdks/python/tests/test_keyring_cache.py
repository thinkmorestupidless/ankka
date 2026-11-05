"""The process's key cache (feature 042, FR-020, FR-022): the runtime's rules, on an injected clock.

A key of the own project is fresh for ``ttl`` and served in an outage until ``outage_bound``; a key
of another project, and a read's erased answer, for ``foreign_ttl``; at most ``max_keys`` are held;
and an answer whose fetch was overtaken by a notice is never cached.
"""

from __future__ import annotations

import asyncio
import json
from typing import Any

import pytest

from ankka.keyring import InMemoryKeyring, KeyringSettings
from ankka.personal import KeyringUnavailable, Personal

KEY = bytes(range(32))


class _Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


class _Counting(InMemoryKeyring):
    def __init__(self, clock: _Clock, settings: KeyringSettings | None = None) -> None:
        super().__init__("brand", settings, clock)
        self.asked: list[tuple[str, str, bool]] = []

    async def _fetch(self, project: str, subject: str, create: bool) -> tuple[str, bytes | None]:
        self.asked.append((project or self.home, subject, create))
        return await super()._fetch(project, subject, create)


def _envelope(project: str, subject: str) -> str:
    return json.dumps({"$personal": 1, "project": project, "subject": subject, "c": "AAAA"})


def _read(ring: InMemoryKeyring, project: str, subject: str) -> bytes | None:
    asyncio.run(ring.prefetch_json(_envelope(project, subject)))
    return ring.key_for_read(project, subject)


def test_an_own_projects_key_is_asked_for_again_after_the_ttl() -> None:
    clock = _Clock()
    ring = _Counting(clock)
    ring.put("brand", "player/1", KEY)
    assert _read(ring, "brand", "player/1") == KEY
    clock.now += 299
    assert _read(ring, "brand", "player/1") == KEY
    assert len(ring.asked) == 1
    clock.now += 2
    assert _read(ring, "brand", "player/1") == KEY
    assert len(ring.asked) == 2


def test_another_projects_key_is_held_for_the_foreign_ttl_only() -> None:
    clock = _Clock()
    ring = _Counting(clock)
    ring.put("casino", "player/1", KEY)
    assert _read(ring, "casino", "player/1") == KEY
    clock.now += 29
    _read(ring, "casino", "player/1")
    assert len(ring.asked) == 1
    # The producing project erases the subject: no notice of it reaches this process.
    ring.keys.pop(("casino", "player/1"))
    ring.destroyed.add(("casino", "player/1"))
    clock.now += 2
    assert _read(ring, "casino", "player/1") is None
    assert len(ring.asked) == 2


def test_a_reads_erased_answer_is_held_for_the_foreign_ttl_so_a_later_grant_is_honoured() -> None:
    clock = _Clock()
    ring = _Counting(clock)
    assert _read(ring, "casino", "player/1") is None  # no grant yet: read as erased
    ring.put("casino", "player/1", KEY)  # the grant is made
    clock.now += 29
    assert _read(ring, "casino", "player/1") is None
    clock.now += 2
    assert _read(ring, "casino", "player/1") == KEY


def test_the_cache_holds_at_most_max_keys_and_lets_the_least_recently_used_go() -> None:
    clock = _Clock()
    ring = _Counting(clock, KeyringSettings(max_keys=2))
    for s in ("player/1", "player/2", "player/3"):
        ring.put("brand", s, KEY)
    _read(ring, "brand", "player/1")
    _read(ring, "brand", "player/2")
    _read(ring, "brand", "player/1")  # player/1 is now the most recently used
    _read(ring, "brand", "player/3")  # player/2 goes
    assert len(ring) == 2
    assert ring.holds("brand", "player/1") and ring.holds("brand", "player/3")
    assert not ring.holds("brand", "player/2")


def test_in_an_outage_an_own_key_serves_until_the_outage_bound_then_is_unavailable() -> None:
    clock = _Clock()
    ring = _Counting(clock, KeyringSettings(ttl=20, outage_bound=50, notice_bound=60))
    ring.put("brand", "player/1", KEY)
    _read(ring, "brand", "player/1")
    ring.unavailable = True
    clock.now += 30  # past the ttl, inside the outage bound
    assert _read(ring, "brand", "player/1") == KEY
    clock.now += 25  # past the outage bound
    with pytest.raises(KeyringUnavailable):
        _read(ring, "brand", "player/1")
    with pytest.raises(KeyringUnavailable):
        ring.key_for_read("brand", "player/1")


def test_in_an_outage_another_projects_key_does_not_outlive_the_foreign_ttl() -> None:
    clock = _Clock()
    ring = _Counting(clock)
    ring.put("casino", "player/1", KEY)
    _read(ring, "casino", "player/1")
    ring.unavailable = True
    clock.now += 31
    with pytest.raises(KeyringUnavailable):
        _read(ring, "casino", "player/1")


def test_the_outage_bound_may_not_exceed_the_notice_bound() -> None:
    with pytest.raises(ValueError, match=r"outage-bound \(61s\) must not exceed ankka.keyring.notice-bound \(60s\)"):
        KeyringSettings(outage_bound=61)
    with pytest.raises(ValueError, match="cache.foreign-ttl must be positive"):
        KeyringSettings(foreign_ttl=0)
    with pytest.raises(ValueError, match="max-keys must be at least 1"):
        KeyringSettings(max_keys=0)
    assert KeyringSettings().outage_bound == 60


class _Held(InMemoryKeyring):
    """A keyring whose fetches wait until released: a notice can arrive while one is out."""

    def __init__(self) -> None:
        super().__init__("brand")
        self.release = asyncio.Event()
        self.started = asyncio.Event()
        self.answers: list[bytes | None] = []

    async def _fetch(self, project: str, subject: str, create: bool) -> tuple[str, bytes | None]:
        before = self.keys.get((project or self.home, subject))
        self.started.set()
        await self.release.wait()
        # The keyring answered with the key it held when the fetch began.
        self.answers.append(before)
        if before is None:
            return await super()._fetch(project, subject, create)
        return project or self.home, before


@pytest.mark.parametrize("drop", ["subject", "all"])
def test_a_fetch_in_flight_when_its_subject_is_dropped_is_not_cached(drop: str) -> None:
    ring = _Held()
    ring.put("brand", "player/1", KEY)

    async def run() -> Any:
        fetching = asyncio.create_task(ring.prefetch_json(_envelope("brand", "player/1")))
        await ring.started.wait()
        ring.erase("player/1") if drop == "subject" else (ring.keys.pop(("brand", "player/1")), ring.destroyed.add(("brand", "player/1")), ring.drop_all())
        ring.release.set()
        await fetching
        return ring.key_for_read("brand", "player/1")

    assert asyncio.run(run()) is None
    assert ring.answers[0] == KEY  # the first answer was the destroyed key, and was not believed


def test_a_write_after_a_dropped_fetch_is_refused_as_erased() -> None:
    from ankka.personal import SubjectErased

    ring = _Held()
    ring.put("brand", "player/1", KEY)

    async def run() -> None:
        writing = asyncio.create_task(ring.prefetch_values(Personal.of("player/1", "x")))
        await ring.started.wait()
        ring.erase("player/1")
        ring.release.set()
        await writing

    with pytest.raises(SubjectErased):
        asyncio.run(run())
