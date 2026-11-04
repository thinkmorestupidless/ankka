"""Where a process's personal fields get their keys (feature 042, protocol 1.23).

A codec is synchronous and a key is a call to the sidecar, so the keys are fetched first: before a
payload is decoded the server scans its JSON for sealed envelopes (``prefetch_json``), and before a
value is encoded it walks the value for ``Present`` fields (``prefetch_values``). Every key is then
in this process's cache when the codec runs, and the codec only reads it (``key_for_read``,
``key_for_write``). A codec that finds a key missing raises ``KeyringUnavailable`` rather than
reading the field as erased.

The cache is dropped per data subject on a notice from the sidecar (``SubjectNotices``), and each
notice is acknowledged (``AckSubjectNotice``) once it is, which is what the runtime waits for.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import socket
import uuid
from collections.abc import Iterable
from dataclasses import dataclass
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
class _Erased:
    """The keyring said the subject is erased. ``final`` when said to a write (``create``), which
    asks for the key to be made: a read of a subject that never had one is told erased too."""

    final: bool


_Entry = bytes | _Erased


class Keyring:
    """A process's keys: a cache in front of ``_fetch``, keyed by ``(project, subject)``.
    ``project`` is the project this service seals under, None until an answer for the service's own
    project has named it; a key of another project is fetched under that project's name."""

    def __init__(self, project: str | None = None) -> None:
        self.project = project
        self._cache: dict[tuple[str, str], _Entry] = {}

    async def _fetch(self, project: str, subject: str, create: bool) -> tuple[str, bytes | None]:
        """The project the answer is for and the key, None when the subject is erased. ``project``
        is ``OWN`` for this service's own. Raises ``KeyringUnavailable``."""
        raise KeyringUnavailable(
            "the keyring is unavailable: no keyring is installed for this process, so a personal field can be "
            "neither written nor read (does the service declare \"personal\": true, and does the installation "
            "run a keyring?)"
        )

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
        entry = self._cache.get((project, subject))
        if entry is None:
            raise KeyringUnavailable(
                f"the keyring is unavailable: the key of '{subject}' was not fetched before the payload was decoded"
            )
        return None if isinstance(entry, _Erased) else entry

    def key_for_write(self, subject: str) -> bytes:
        """The key a value about ``subject`` is sealed with; raises ``SubjectErased`` once erased."""
        entry = self._cache.get((self.own_project(), subject))
        if isinstance(entry, bytes):
            return entry
        if isinstance(entry, _Erased) and entry.final:
            raise SubjectErased(subject)
        raise KeyringUnavailable(
            f"the keyring is unavailable: the key of '{subject}' was not fetched before the value was encoded"
        )

    # ── what the server awaits before the codec runs ─────────────────────────

    async def prefetch_json(self, data: bytes | str | None) -> None:
        """Fetches the key of every sealed envelope in a JSON payload that is not cached."""
        if not data:
            return
        raw = data if isinstance(data, bytes) else data.encode("utf-8")
        if b'"$personal"' not in raw:
            return
        try:
            document = json.loads(raw)
        except ValueError:
            return  # not JSON: the codec will say so
        wanted = {pair for pair in personal.envelopes_in(document) if pair not in self._cache}
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
            entry = self._cache.get((self.project, str(p.subject))) if self.project else None
            if isinstance(entry, bytes):
                continue
            if isinstance(entry, _Erased) and entry.final:
                raise SubjectErased(p.subject)
            wanted.add((OWN, str(p.subject)))
        answered = await self._fetch_all(wanted, create=True)
        for project, subject in answered:
            entry = self._cache.get((project, subject))
            if isinstance(entry, _Erased):
                raise SubjectErased(subject)

    async def _fetch_all(self, wanted: Iterable[tuple[str, str]], create: bool) -> list[tuple[str, str]]:
        """Fetches each pair and caches it under the project the answer names; a pair asked as
        ``OWN`` tells this process its own project. Answers the pairs as cached."""
        pairs = list(wanted)
        if not pairs:
            return []
        answers = await asyncio.gather(*(self._fetch(project, subject, create) for project, subject in pairs))
        cached: list[tuple[str, str]] = []
        for (asked, subject), (answered, key) in zip(pairs, answers, strict=True):
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
            self._cache[(project, subject)] = key if key is not None else _Erased(final=create)
            cached.append((project, subject))
        return cached

    # ── notices ──────────────────────────────────────────────────────────────

    def drop(self, project: str, subject: str) -> None:
        """Forgets the subject's key, as a notice of its erasure asks."""
        self._cache.pop((project, subject), None)
        if project == OWN and self.project:
            self._cache.pop((self.project, subject), None)


class InMemoryKeyring(Keyring):
    """Keys in this process's memory, made on a write: for tests, and for a unit test kit."""

    def __init__(self, project: str = LOCAL_PROJECT) -> None:
        super().__init__(project)
        self.home = project
        self.keys: dict[tuple[str, str], bytes] = {}
        self.destroyed: set[tuple[str, str]] = set()

    def put(self, project: str, subject: str, key: bytes) -> None:
        self.keys[(project, subject)] = key

    def erase(self, subject: str, project: str | None = None) -> None:
        """Destroys the subject's key at once and drops it from the cache, as a notice would."""
        where = project or self.home
        self.keys.pop((where, subject), None)
        self.destroyed.add((where, subject))
        self.drop(where, subject)

    async def _fetch(self, project: str, subject: str, create: bool) -> tuple[str, bytes | None]:
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

    def __init__(self, client: Any) -> None:
        super().__init__(None)
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
                async for notice in stream:
                    backoff = 0.2
                    await self.notice(notice.seq, notice.project, notice.subject)
            except asyncio.CancelledError:
                raise
            except grpc.aio.AioRpcError as failure:
                if failure.code() == grpc.StatusCode.UNIMPLEMENTED:
                    log.warning("the runtime sends no erasure notices (protocol 1.23): %s", failure.details())
                    return
                log.debug("the notice stream ended: %s", failure.details())
            except Exception as e:  # keep following: a missed notice is a key kept past erasure
                log.warning("the notice stream failed: %s", e)
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


__all__ = ["DataSubject", "InMemoryKeyring", "Keyring", "SidecarKeyring", "current", "install"]
