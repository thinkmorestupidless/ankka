"""Where a process's personal fields get their keys (feature 042, protocol 1.23).

A codec is synchronous and a key is a call to the sidecar, so the keys are fetched first: before a
payload is decoded the server scans its JSON for sealed envelopes (``prefetch_json``), and before a
value is encoded it walks the value for ``Present`` fields (``prefetch_values``). Every key is then
in this process's cache when the codec runs, and the codec only reads it (``key_for_read``,
``key_for_write``, ``key_for_reseal``). A codec that finds a key missing raises
``KeyringUnavailable`` rather than reading the field as erased.

The cache keeps the runtime's rules (``KeyringSettings``, the JVM's ``ankka.keyring.*``): at most
``max_keys``, the least recently used going first; a key of the service's own project fresh for
``ttl``, and still served while the keyring cannot be reached until ``outage_bound`` after it was
fetched, which may not exceed ``notice_bound``; a key of another project, which no notice of that
project reaches, and a read's "erased" answer (which may be a grant refused, or a subject not yet
written), for ``foreign_ttl`` and no longer. A write's "erased" is a destroyed key, which never
changes.

The cache is dropped per data subject on a notice from the sidecar (``SubjectNotices``), and each
notice is acknowledged (``AckSubjectNotice``) once it is, which is what the runtime waits for. An
answer whose fetch was in flight when its subject (or the whole cache) was dropped is never cached:
it may be the key the notice destroyed, and it is asked for again.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import socket
import time
import uuid
from collections import OrderedDict
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from typing import Any

from ankka import personal
from ankka.personal import DataSubject, KeyringUnavailable, SubjectErased

log = logging.getLogger("ankka")

LOCAL_PROJECT = "local"
"""The project an ``InMemoryKeyring`` seals under unless told another: a unit test's, never a
deployed process's. A process beside a sidecar learns its project from the keyring's first answer
for its own project (``SubjectKeyReply.project``)."""

OWN = ""
"""How a key of this service's own project is asked for: the request names no project, and the
answer names the project it is for."""


@dataclass(frozen=True)
class KeyringSettings:
    """How a process holds the keys it fetched: the runtime's ``ankka.keyring`` settings, by the
    same names and defaults. Constructing one that breaks a rule raises ``ValueError`` naming it."""

    max_keys: int = 10_000
    """``ankka.keyring.cache.max-keys``: how many keys are held at once."""
    ttl: float = 300.0
    """``ankka.keyring.cache.ttl``, seconds: how long a key of the own project is held before it is
    asked for again."""
    foreign_ttl: float = 30.0
    """``ankka.keyring.cache.foreign-ttl``, seconds: how long a key of another project, or a read's
    erased answer, is held."""
    outage_bound: float = 60.0
    """``ankka.keyring.cache.outage-bound``, seconds after its fetch: how long a held key of the own
    project still serves while the keyring cannot be reached."""
    notice_bound: float = 60.0
    """``ankka.keyring.notice-bound``, seconds: the bound on an erasure reaching a read, which the
    outage bound may not exceed."""

    def __post_init__(self) -> None:
        if self.max_keys < 1:
            raise ValueError(f"ankka.keyring.cache.max-keys must be at least 1, was {self.max_keys}")
        for name, path in (
            ("ttl", "cache.ttl"),
            ("foreign_ttl", "cache.foreign-ttl"),
            ("outage_bound", "cache.outage-bound"),
            ("notice_bound", "notice-bound"),
        ):
            if not getattr(self, name) > 0:
                raise ValueError(f"ankka.keyring.{path} must be positive, was {getattr(self, name)}s")
        if self.outage_bound > self.notice_bound:
            raise ValueError(
                f"ankka.keyring.cache.outage-bound ({self.outage_bound:g}s) must not exceed "
                f"ankka.keyring.notice-bound ({self.notice_bound:g}s)"
            )


@dataclass(frozen=True)
class _Erased:
    """The keyring said the subject is erased. ``final`` when said to a write (``create``), which
    asks for the key to be made: a read of a subject that never had one is told erased too."""

    final: bool


_Entry = bytes | _Erased


@dataclass
class _Held:
    entry: _Entry
    at: float
    foreign: bool


@dataclass
class _Flight:
    """One fetch in flight: ``dropped`` once a notice (or the loss of the stream) dropped its subject
    while it was out, so its answer is not to be believed."""

    subject: str
    dropped: bool = field(default=False)


_ATTEMPTS = 3
"""How many times a fetch overtaken by a notice is made again before it is unavailable."""


class Keyring:
    """A process's keys: a cache in front of ``_fetch``, keyed by ``(project, subject)``.
    ``project`` is the project this service seals under, None until an answer for the service's own
    project has named it; a key of another project is fetched under that project's name."""

    def __init__(
        self,
        project: str | None = None,
        settings: KeyringSettings | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.project = project
        self.settings = settings or KeyringSettings()
        self._clock = clock
        self._cache: OrderedDict[tuple[str, str], _Held] = OrderedDict()
        self._flying: dict[int, _Flight] = {}

    async def _fetch(self, project: str, subject: str, create: bool) -> tuple[str, bytes | None]:
        """The project the answer is for and the key, None when the subject is erased. ``project``
        is ``OWN`` for this service's own. Raises ``KeyringUnavailable``."""
        raise KeyringUnavailable(
            "the keyring is unavailable: no keyring is installed for this process, so a personal field can be "
            "neither written nor read (does the service declare \"personal\": true, and does the installation "
            "run a keyring?)"
        )

    # ── the cache's rules ────────────────────────────────────────────────────

    def _age(self, held: _Held) -> float:
        return self._clock() - held.at

    def _fresh(self, held: _Held) -> bool:
        """Whether ``held`` is believed without asking again."""
        entry = held.entry
        if isinstance(entry, _Erased):
            # A destroyed key never comes back; a read's "erased" may be a refused grant, or a subject
            # with no key yet, and is held as briefly as another project's key.
            return (entry.final and not held.foreign) or self._age(held) < self.settings.foreign_ttl
        lifetime = self.settings.foreign_ttl if held.foreign else self.settings.ttl
        return self._age(held) < lifetime

    def _serves_in_outage(self, held: _Held) -> bool:
        """Whether ``held`` still serves while the keyring cannot be reached: a key of the own project,
        within the outage bound of its fetch."""
        return isinstance(held.entry, bytes) and not held.foreign and self._age(held) < self.settings.outage_bound

    def _usable(self, held: _Held) -> bool:
        return self._fresh(held) or self._serves_in_outage(held)

    def _held(self, pair: tuple[str, str]) -> _Held | None:
        """The entry for ``pair`` while it may be used, most recently used now; an expired one is
        forgotten."""
        held = self._cache.get(pair)
        if held is None:
            return None
        if not self._usable(held):
            del self._cache[pair]
            return None
        self._cache.move_to_end(pair)
        return held

    def _put(self, pair: tuple[str, str], entry: _Entry) -> None:
        foreign = self.project is None or pair[0] != self.project
        self._cache[pair] = _Held(entry, self._clock(), foreign)
        self._cache.move_to_end(pair)
        while len(self._cache) > self.settings.max_keys:
            self._cache.popitem(last=False)

    def _needs_fetch(self, pair: tuple[str, str]) -> bool:
        held = self._cache.get(pair)
        return held is None or not self._fresh(held)

    # ── what the codec reads, synchronously ──────────────────────────────────

    def own_project(self) -> str:
        """The project every envelope this process seals names; raises ``KeyringUnavailable``
        before an answer of the keyring has said which it is."""
        if not self.project:
            raise KeyringUnavailable(
                "the keyring is unavailable: no key was fetched before the value was encoded, so this process "
                "does not yet know the project it seals under"
            )
        return self.project

    def key_for_read(self, project: str, subject: str) -> bytes | None:
        """The key an envelope of ``subject`` in ``project`` opens with, or None once erased."""
        held = self._held((project, subject))
        if held is None:
            raise KeyringUnavailable(
                f"the keyring is unavailable: the key of '{subject}' was not fetched before the payload was decoded"
            )
        return None if isinstance(held.entry, _Erased) else held.entry

    def key_for_write(self, subject: str) -> bytes:
        """The key a value about ``subject`` is sealed with; raises ``SubjectErased`` once erased."""
        held = self._held((self.own_project(), subject))
        entry = held.entry if held is not None else None
        if isinstance(entry, bytes):
            return entry
        if isinstance(entry, _Erased) and entry.final:
            raise SubjectErased(subject)
        raise KeyringUnavailable(
            f"the keyring is unavailable: the key of '{subject}' was not fetched before the value was encoded"
        )

    def key_for_reseal(self, project: str, subject: str) -> bytes | None:
        """The key a field read from ``project``'s envelope is sealed again with (FR-027), or None
        when that project's key can no longer be had here: the field is then written erased."""
        return self.key_for_read(project, subject)

    # ── what the server awaits before the codec runs ─────────────────────────

    async def prefetch_json(self, data: bytes | str | None) -> None:
        """Fetches the key of every sealed envelope in a JSON payload that is not held fresh."""
        if not data:
            return
        raw = data if isinstance(data, bytes) else data.encode("utf-8")
        if b'"$personal"' not in raw:
            return
        try:
            document = json.loads(raw)
        except ValueError:
            return  # not JSON: the codec will say so
        wanted = {pair for pair in personal.envelopes_in(document) if self._needs_fetch(pair)}
        await self._fetch_all(wanted, create=False)

    async def prefetch_message(self, message: Any) -> None:
        """``prefetch_json`` over every payload a protobuf message carries, at any depth."""
        for data in _payloads(message):
            await self.prefetch_json(data)

    async def prefetch_values(self, value: Any) -> None:
        """Fetches, making it when absent, the key of every subject a ``Present`` in ``value`` is
        about. Raises ``SubjectErased`` for an erased one: nothing may be written."""
        wanted: set[tuple[str, str]] = set()
        for p in personal.present_in(value):
            subject = str(p.subject)
            held = self._cache.get((self.project, subject)) if self.project else None
            if held is not None and self._fresh(held):
                if isinstance(held.entry, bytes):
                    continue
                if held.entry.final:
                    raise SubjectErased(p.subject)
            wanted.add((OWN, subject))
        answered = await self._fetch_all(wanted, create=True)
        for project, subject in answered:
            held = self._cache.get((project, subject))
            if held is not None and isinstance(held.entry, _Erased):
                raise SubjectErased(subject)

    async def _fetch_all(self, wanted: Iterable[tuple[str, str]], create: bool) -> list[tuple[str, str]]:
        """Fetches each pair and caches it under the project the answer names; a pair asked as
        ``OWN`` tells this process its own project. Answers the pairs as cached. A pair whose fetch
        fails as unavailable keeps a held key the outage bound still allows, and otherwise raises."""
        pairs = list(wanted)
        if not pairs:
            return []
        outcomes = await asyncio.gather(*(self._fetch_one(project, subject, create) for project, subject in pairs), return_exceptions=True)
        cached: list[tuple[str, str]] = []
        failure: BaseException | None = None
        for (asked, subject), outcome in zip(pairs, outcomes, strict=True):
            if isinstance(outcome, KeyringUnavailable):
                named = self.project if asked == OWN else asked
                held = self._cache.get((named, subject)) if named else None
                if held is not None and self._serves_in_outage(held):
                    cached.append((named, subject))  # type: ignore[arg-type]
                    continue
                if held is not None:
                    del self._cache[(named, subject)]  # type: ignore[arg-type]
                failure = failure or outcome
            elif isinstance(outcome, BaseException):
                failure = failure or outcome
            else:
                cached.append(outcome)
        if failure is not None:
            raise failure
        return cached

    async def _fetch_one(self, asked: str, subject: str, create: bool) -> tuple[str, str]:
        """Fetches one pair and caches the answer, unless a notice dropped the subject while the
        fetch was out: that answer may be the destroyed key, so it is asked for again."""
        for _ in range(_ATTEMPTS):
            flight = _Flight(subject)
            self._flying[id(flight)] = flight
            try:
                answered, key = await self._fetch(asked, subject, create)
            finally:
                del self._flying[id(flight)]
            if flight.dropped:
                continue
            if key is not None and len(key) != 32:
                raise KeyringUnavailable(f"the keyring answered a key of {len(key)} bytes for '{subject}', not 32")
            if asked == OWN:
                if not answered:
                    raise KeyringUnavailable(
                        f"the keyring is unavailable: its answer for '{subject}' named no project, so this "
                        "process cannot tell the project it seals under"
                    )
                if self.project and self.project != answered:
                    raise KeyringUnavailable(
                        f"the keyring is unavailable: it answered for project '{answered}', but this process "
                        f"seals under '{self.project}'"
                    )
                self.project = answered
            project = answered or asked
            self._put((project, subject), key if key is not None else _Erased(final=create))
            return project, subject
        raise KeyringUnavailable(
            f"the keyring is unavailable: the key of '{subject}' was dropped by a notice each time it was fetched"
        )

    # ── notices ──────────────────────────────────────────────────────────────

    def drop_all(self) -> None:
        """Forgets every key: what the process does when its notice stream ends or opens again, since
        a notice sent while it held no stream is never sent again."""
        self._cache.clear()
        for flight in self._flying.values():
            flight.dropped = True

    def drop(self, project: str, subject: str) -> None:
        """Forgets the subject's key, as a notice of its erasure asks."""
        self._cache.pop((project, subject), None)
        if project == OWN and self.project:
            self._cache.pop((self.project, subject), None)
        for flight in self._flying.values():
            if flight.subject == subject:
                flight.dropped = True

    def holds(self, project: str, subject: str) -> bool:
        """Whether an answer for ``subject`` of ``project`` is held and may be used."""
        held = self._cache.get((project, subject))
        return held is not None and self._usable(held)

    def __len__(self) -> int:
        return len(self._cache)


class InMemoryKeyring(Keyring):
    """Keys in this process's memory, made on a write: for tests, and for a unit test kit. While
    ``unavailable`` is set every fetch fails as the keyring's outage does."""

    def __init__(
        self,
        project: str = LOCAL_PROJECT,
        settings: KeyringSettings | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        super().__init__(project, settings, clock)
        self.home = project
        self.keys: dict[tuple[str, str], bytes] = {}
        self.destroyed: set[tuple[str, str]] = set()
        self.unavailable = False

    def put(self, project: str, subject: str, key: bytes) -> None:
        self.keys[(project, subject)] = key

    def erase(self, subject: str, project: str | None = None) -> None:
        """Destroys the subject's key at once and drops it from the cache, as a notice would."""
        where = project or self.home
        self.keys.pop((where, subject), None)
        self.destroyed.add((where, subject))
        self.drop(where, subject)

    async def _fetch(self, project: str, subject: str, create: bool) -> tuple[str, bytes | None]:
        if self.unavailable:
            raise KeyringUnavailable("the keyring is unavailable: the in-memory keyring is in an outage")
        project = project or self.home
        if (project, subject) in self.destroyed:
            return project, None
        if (project, subject) not in self.keys:
            if not create:
                return project, None
            self.keys[(project, subject)] = os.urandom(32)
        return project, self.keys[(project, subject)]


class SidecarKeyring(Keyring):
    """Keys from the sidecar's ``GetSubjectKey``, which asks the runtime's keyring as the service.
    Its project is learnt from the first answer for the service's own project, never assumed."""

    def __init__(
        self,
        client: Any,
        settings: KeyringSettings | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        super().__init__(None, settings, clock)
        self.client = client
        self.instance = f"{socket.gethostname() or 'process'}-{uuid.uuid4().hex[:8]}"
        self._notices: asyncio.Task[None] | None = None

    async def _fetch(self, project: str, subject: str, create: bool) -> tuple[str, bytes | None]:
        import grpc

        from ankka._proto.ankka.protocol.v1 import client_pb2

        request = client_pb2.SubjectKeyRequest(
            subject=subject, create=create, project=OWN if project == self.project else project
        )
        try:
            reply = await self.client._stub.GetSubjectKey(request)
        except grpc.aio.AioRpcError as failure:
            if failure.code() == grpc.StatusCode.UNIMPLEMENTED:
                raise KeyringUnavailable(
                    "the keyring is unavailable: the runtime beside this process does not hand out subject keys, "
                    f"which needs protocol 1.23: {failure.details()}"
                ) from failure
            raise KeyringUnavailable(f"the keyring is unavailable: {failure.details()}") from failure
        which = reply.WhichOneof("reply")
        answered = reply.project or ("" if project == OWN else project)
        if which == "key":
            return answered, bytes(reply.key)
        if which == "erased":
            return answered, None
        message = reply.error.message if which == "error" else "it answered nothing"
        raise KeyringUnavailable(f"the keyring is unavailable: {message}")

    def follow_notices(self) -> None:
        """Holds the notice stream open, once, for as long as the process runs."""
        if self._notices is None or self._notices.done():
            self._notices = asyncio.get_running_loop().create_task(self._follow())

    async def stop(self) -> None:
        if self._notices is not None:
            self._notices.cancel()
            self._notices = None

    async def _follow(self) -> None:
        import grpc

        from ankka._proto.ankka.protocol.v1 import client_pb2

        backoff = 0.2
        while True:
            try:
                stream = self.client._stub.SubjectNotices(client_pb2.SubjectNoticesRequest(instance=self.instance))
                # A key fetched while no stream was held may have been erased since: the sidecar
                # counts a closed stream as acked and hands nothing again.
                self.drop_all()
                async for notice in stream:
                    backoff = 0.2
                    await self.notice(notice.seq, notice.project, notice.subject)
            except asyncio.CancelledError:
                raise
            except grpc.aio.AioRpcError as failure:
                self.drop_all()
                if failure.code() == grpc.StatusCode.UNIMPLEMENTED:
                    log.warning("the runtime sends no erasure notices (protocol 1.23): %s", failure.details())
                    return
                log.debug("the notice stream ended: %s", failure.details())
            except Exception as e:  # keep following: a missed notice is a key kept past erasure
                self.drop_all()
                log.warning("the notice stream failed: %s", e)
            else:
                self.drop_all()
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 5.0)

    async def notice(self, seq: int, project: str, subject: str) -> None:
        """Drops the subject's key, then acknowledges the notice: the runtime waits for the ack."""
        from ankka._proto.ankka.protocol.v1 import client_pb2

        self.drop(project, subject)
        reply = await self.client._stub.AckSubjectNotice(client_pb2.SubjectNoticeAck(seq=seq, instance=self.instance))
        if reply.HasField("error"):
            log.warning("the acknowledgement of notice %s was refused: %s", seq, reply.error.message)


_installed: Keyring = Keyring()


def current() -> Keyring:
    """The keyring this process's codecs read."""
    return _installed


def install(keyring: Keyring) -> Keyring:
    """Makes ``keyring`` the process's; answers the one it replaced."""
    global _installed
    before, _installed = _installed, keyring
    return before


def _payloads(message: Any) -> Iterable[bytes]:
    """Every ``bytes`` field of a protobuf message, at any depth: where a payload's JSON lives."""
    from google.protobuf.descriptor import FieldDescriptor

    stack = [message]
    while stack:
        node = stack.pop()
        for field, value in node.ListFields():
            if field.type == FieldDescriptor.TYPE_MESSAGE:
                if field.message_type.GetOptions().map_entry:
                    continue
                stack.extend([value] if hasattr(value, "ListFields") else value)
            elif field.type == FieldDescriptor.TYPE_BYTES:
                if isinstance(value, bytes):
                    yield value
                else:
                    yield from value


__all__ = ["DataSubject", "InMemoryKeyring", "Keyring", "KeyringSettings", "SidecarKeyring", "current", "install"]
