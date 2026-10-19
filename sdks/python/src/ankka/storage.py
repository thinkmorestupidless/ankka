"""The storage client: a service's buckets, reached by name (feature 055).

``self.storage.default`` is the bucket ``provisionObjectStorage`` asks for, and
``self.storage.bucket("photos")`` one the descriptor's ``buckets`` list names. Offered where a
blocking call belongs, as the secret store is: endpoints, workflow steps, consumers, graph
consumers, timed actions, agents and autonomous agents — never an entity or a view. An entity keeps
an object's *name*; the bucket keeps the bytes.

A process speaks S3 itself, from the ``ANKKA_S3_*`` variables the platform gives it: no object
passes through the sidecar. On a developer's machine the store is a directory
(``DirectoryBuckets``), whose signed URLs and public addresses the process serves at
``/_ankka/storage`` through its sidecar; in a test it is ``InMemoryBuckets``.

Every call is ``async`` and refuses with ``CommandError``: ``BAD_REQUEST`` for a name, a prefix, a
length or a span that breaks a rule, and for a signed URL or an address the bucket's exposure does
not allow; ``UNAVAILABLE`` when the store cannot be reached, when the service has no such bucket, or
when it has no storage credential to sign with; ``INTERNAL`` for an answer the client cannot read.
The words are the runtime's (``StorageRefusals``, ``ObjectRules``), so a refusal reads the same in
every language.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import http.client
import json
import os
import queue
import re
import secrets as _random
import socket
import threading
import uuid
from abc import ABC, abstractmethod
from collections.abc import AsyncIterable, AsyncIterator, Awaitable, Callable, Iterable, Iterator, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import TYPE_CHECKING, Any, Literal, cast
from urllib.parse import unquote, urlsplit
from xml.etree import ElementTree

from ankka import sigv4
from ankka.codec import _format_instant, _parse_instant
from ankka.effects.common import Error, ErrorCode

if TYPE_CHECKING:
    from ankka._proto.ankka.protocol.v1 import discovery_pb2

Exposure = Literal["private", "signed", "public", "supplied"]
"""How a bucket is reached from outside the installation: ``private`` (inside it only), ``signed``
(through URLs the service signs), ``public`` (every object readable with no signature), or
``supplied`` (a bucket the developer made in a store of their own)."""

Access = Literal["read", "keep"]
"""What a signed URL lets its holder do with one object."""

EXPOSURES: tuple[Exposure, ...] = ("private", "signed", "public", "supplied")

BODY_ITEM = bytes | bytearray | memoryview

PREFIX = "ANKKA_S3_"
STORAGE_BACKEND = "ANKKA_STORAGE_BACKEND"
STORAGE_DIR = "ANKKA_STORAGE_DIR"
DEFAULT_DIRECTORY = ".ankka/storage"
DEFAULT_TIMEOUT_SECONDS = 30.0

STORAGE_PATH = "/_ankka/storage"
"""Where a directory store's signed URLs and public addresses point: the service's own port."""

STORAGE_ENDPOINT_ID = "ankka-storage"
"""The endpoint a process declares at ``/_ankka/storage`` when its store is a directory."""

STORAGE_SINCE = (1, 20)
"""The protocol whose sidecars admit a process's endpoint at ``/_ankka/storage``."""

DEFAULT_SEGMENT = "_default"
"""The unnamed bucket's directory and URL segment: no bucket name begins with ``_``."""


# ── the rules ─────────────────────────────────────────────────────────────────


def _shown(text: str) -> str:
    visible = "".join("?" if (c < " " or c == "\x7f") else c for c in text)
    return visible[:60] + "…" if len(visible) > 60 else visible


def _problem(subject: str, text: str, trailing_slash: bool) -> str | None:
    size = len(text.encode("utf-8"))
    body = text[:-1] if trailing_slash and text.endswith("/") else text
    segments = body.split("/")
    found: str | None
    if text == "":
        found = f"{subject} must not be empty"
    elif size > ObjectRules.MAX_NAME_BYTES:
        found = f"{subject} is at most {ObjectRules.MAX_NAME_BYTES} bytes as UTF-8; this one is {size}"
    elif text.startswith("/"):
        found = f"{subject} must not begin with '/'"
    elif any(c < " " or c == "\x7f" for c in text):
        found = f"{subject} must not hold a control character"
    elif any(s == "" for s in segments):
        found = f"{subject} must not have an empty segment"
    elif any(s in (".", "..") for s in segments):
        found = f"{subject} must not have a segment '.' or '..'"
    else:
        found = None
    return None if found is None else f'{found} ("{_shown(text)}")'


class ObjectRules:
    """What an object's name, a prefix, a bucket's name and a signed URL's span may be: the runtime's
    rules in its words. Every store here applies them, and ``protocol/fixtures/storage/rules.json``
    holds them to the other SDKs."""

    MAX_NAME_BYTES = 1024
    MAX_BUCKET_NAME = 32
    MAX_SIGNED_URL = timedelta(days=7)
    DEFAULT_SIGNED_URL = timedelta(minutes=15)
    BUCKET_NAME_RULE = "lower-case letters, digits and '-', 1 to 32 characters"
    _VALID_BUCKET_NAME = re.compile(r"[a-z0-9-]{1,32}")

    @staticmethod
    def name_problem(name: str) -> str | None:
        """What is wrong with an object's ``name``, if anything."""
        return _problem("an object's name", name, trailing_slash=False)

    @staticmethod
    def prefix_problem(prefix: str) -> str | None:
        """What is wrong with a listing's ``prefix``, if anything. Empty is everything."""
        return None if prefix == "" else _problem("a prefix", prefix, trailing_slash=True)

    @staticmethod
    def bucket_name_problem(name: str) -> str | None:
        """What is wrong with a bucket's name, if anything, in the descriptor's words."""
        if ObjectRules._VALID_BUCKET_NAME.fullmatch(name):
            return None
        return f"'{_shown(name)}' is not a bucket name: {ObjectRules.BUCKET_NAME_RULE}"

    @staticmethod
    def span_problem(span: timedelta) -> str | None:
        """What is wrong with a signed URL's span, if anything."""
        seconds = int(span.total_seconds())
        if span <= timedelta(0):
            return f"a signed URL's span is positive; this one is {seconds} seconds"
        if span > ObjectRules.MAX_SIGNED_URL:
            maximum = int(ObjectRules.MAX_SIGNED_URL.total_seconds())
            return f"a signed URL is made for at most 7 days ({maximum} seconds); this one asks for {seconds}"
        return None

    @staticmethod
    def check_name(name: str) -> None:
        problem = ObjectRules.name_problem(name)
        if problem is not None:
            raise _refused(problem, ErrorCode.BAD_REQUEST)

    @staticmethod
    def check_prefix(prefix: str) -> None:
        problem = ObjectRules.prefix_problem(prefix)
        if problem is not None:
            raise _refused(problem, ErrorCode.BAD_REQUEST)

    @staticmethod
    def check_span(span: timedelta) -> None:
        problem = ObjectRules.span_problem(span)
        if problem is not None:
            raise _refused(problem, ErrorCode.BAD_REQUEST)


def _refused(message: str, code: ErrorCode) -> Exception:
    from ankka.client import CommandError

    return CommandError(Error(message, code))


class StorageRefusals:
    """What the storage client says when it refuses: the runtime's words, said once."""

    @staticmethod
    def no_bucket() -> Exception:
        return _refused(
            "this service has no bucket: it asks for one with provisionObjectStorage, or names one in buckets",
            ErrorCode.UNAVAILABLE,
        )

    @staticmethod
    def unknown(name: str, known: Iterable[str]) -> Exception:
        names = sorted(known)
        named = "names none" if not names else "names " + ", ".join(f"'{n}'" for n in names)
        return _refused(f"this service has no bucket named '{name}'; its descriptor {named}", ErrorCode.UNAVAILABLE)

    @staticmethod
    def no_credential() -> Exception:
        return _refused(
            "this service has a bucket and no storage credential to sign with: it sets "
            "objectStorageCredential to false, so it reaches its bucket as its workload identity, with "
            "its store's own client",
            ErrorCode.UNAVAILABLE,
        )

    @staticmethod
    def not_exposed() -> Exception:
        return _refused(
            "the service's bucket is not reachable from the internet, so it signs no URL: the descriptor "
            "sets exposeObjectStorage, or names a signed bucket",
            ErrorCode.BAD_REQUEST,
        )

    @staticmethod
    def not_signed(bucket: str, exposure: Exposure) -> Exception:
        return _refused(f"bucket '{bucket}' is {exposure}; a signed URL is made for a signed bucket", ErrorCode.BAD_REQUEST)

    @staticmethod
    def not_public(bucket: str | None, exposure: Exposure) -> Exception:
        label = "the service's unnamed bucket" if bucket is None else f"bucket '{bucket}'"
        return _refused(f"{label} is {exposure}; an address with no signature is a public bucket's", ErrorCode.BAD_REQUEST)

    @staticmethod
    def unreachable(endpoint: str, cause: str) -> Exception:
        return _refused(f"the object store at {endpoint} cannot be reached: {cause}", ErrorCode.UNAVAILABLE)

    @staticmethod
    def refused_by_store(endpoint: str, status: int, detail: str) -> Exception:
        tail = "" if not detail else ": " + detail
        return _refused(f"the object store at {endpoint} refused the request ({status}){tail}", ErrorCode.UNAVAILABLE)

    @staticmethod
    def unreadable(endpoint: str, cause: str) -> Exception:
        return _refused(f"the object store at {endpoint} answered with something unreadable: {cause}", ErrorCode.INTERNAL)

    @staticmethod
    def short_body(declared: int, read: int) -> Exception:
        return _refused(f"the stream ended after {read} of its declared {declared} bytes; nothing was kept", ErrorCode.BAD_REQUEST)

    @staticmethod
    def negative_length(length: int) -> Exception:
        return _refused(f"an object's length is not negative; this one is {length}", ErrorCode.BAD_REQUEST)

    @staticmethod
    def no_length() -> Exception:
        return _refused("a put from a stream declares its length", ErrorCode.BAD_REQUEST)

    @staticmethod
    def steps_only() -> Exception:
        return _refused("a workflow keeps and reads an object in a step, not in a command handler", ErrorCode.BAD_REQUEST)


# ── what a bucket answers ─────────────────────────────────────────────────────


@dataclass(frozen=True)
class ObjectInfo:
    """What a bucket keeps under a name, without the bytes."""

    name: str
    content_type: str
    length: int
    last_kept: datetime


@dataclass(frozen=True)
class ObjectEntry:
    """One object in a listing."""

    name: str
    length: int
    last_kept: datetime


class StoredObject:
    """An object read from a bucket. ``body`` is its bytes as an async iterator, read once; ``read``
    reads it whole. Reading to the end, or ``aclose``, releases what holds it (a connection or a
    file)."""

    def __init__(
        self,
        content_type: str,
        length: int,
        last_kept: datetime,
        body: AsyncIterator[bytes],
        close: Callable[[], Awaitable[None]] | None = None,
    ) -> None:
        self.content_type = content_type
        self.length = length
        self.last_kept = last_kept
        self.body = body
        self._close = close

    def __aiter__(self) -> AsyncIterator[bytes]:
        return self.body

    async def read(self) -> bytes:
        """The whole object, then closed."""
        try:
            return b"".join([chunk async for chunk in self.body])
        finally:
            await self.aclose()

    async def aclose(self) -> None:
        aclose = getattr(self.body, "aclose", None)
        if aclose is not None:
            await aclose()
        if self._close is not None:
            close, self._close = self._close, None
            await close()

    async def __aenter__(self) -> StoredObject:
        return self

    async def __aexit__(self, *_: Any) -> None:
        await self.aclose()


def _now() -> datetime:
    return datetime.now(UTC)


async def _chunks(body: AsyncIterable[bytes] | BODY_ITEM) -> AsyncIterator[bytes]:
    if isinstance(body, (bytes, bytearray, memoryview)):
        if len(body):
            yield bytes(body)
        return
    async for chunk in body:
        yield bytes(chunk)


def _declared_length(body: AsyncIterable[bytes] | BODY_ITEM, length: int | None) -> int:
    if length is None:
        if isinstance(body, (bytes, bytearray, memoryview)):
            return len(body)
        raise StorageRefusals.no_length()
    if length < 0:
        raise StorageRefusals.negative_length(length)
    return length


async def _bounded(body: AsyncIterable[bytes] | BODY_ITEM, length: int) -> AsyncIterator[bytes]:
    """At most ``length`` bytes of ``body``; the caller counts what arrived."""
    taken = 0
    if length == 0:
        return
    async for chunk in _chunks(body):
        piece = chunk[: length - taken]
        if piece:
            taken += len(piece)
            yield piece
        if taken >= length:
            return


class Bucket(ABC):
    """One bucket. Its objects are named with ``/`` between segments, so a listing by prefix reads
    like a directory; it is not one. There is no directory to make or remove, and no rename, copy,
    append or partial write: a put replaces the whole object or leaves the old one."""

    name: str | None
    """The descriptor's name for it; ``None`` for the unnamed bucket."""

    exposure: Exposure

    @abstractmethod
    async def put(
        self, name: str, body: AsyncIterable[bytes] | BODY_ITEM, *, length: int | None = None, content_type: str
    ) -> None:
        """Keeps ``length`` bytes of ``body`` under ``name``, with ``content_type``, replacing what was
        there. ``length`` is required when ``body`` is a stream; a stream that ends before it fails
        and keeps nothing, and the old object, if any, stays."""

    @abstractmethod
    async def get(self, name: str) -> StoredObject | None:
        """The object kept under ``name``, or ``None`` when there is none."""

    @abstractmethod
    async def delete(self, name: str) -> None:
        """Removes what is kept under ``name``. Removing nothing is not an error."""

    @abstractmethod
    def list(self, prefix: str) -> AsyncIterator[ObjectEntry]:
        """Every object whose name begins with ``prefix``, in name order, paged behind the
        iterator. The prefix is checked at once."""

    @abstractmethod
    async def stat(self, name: str) -> ObjectInfo | None:
        """What is kept under ``name``, without its bytes, or ``None`` when there is nothing."""

    @abstractmethod
    def signed_url(self, name: str, access: Access, span: timedelta = ObjectRules.DEFAULT_SIGNED_URL) -> str:
        """A URL a browser may use, until ``span`` has passed, to read (``read``) or keep (``keep``)
        the object under ``name``, made by computation alone: no request reaches the store. A signed
        bucket's, or the unnamed bucket's when the service sets ``exposeObjectStorage``; at most
        seven days."""

    @abstractmethod
    def url(self, name: str) -> str:
        """The address a browser reads the object under ``name`` at, with no signature: a public
        bucket's only. Computed, never fetched."""


class Buckets(ABC):
    """A service's buckets: ``default``, the unnamed one, and ``bucket(name)`` for a named one."""

    @property
    @abstractmethod
    def default(self) -> Bucket:
        """The service's unnamed bucket. Refused, naming ``provisionObjectStorage``, when it has
        none."""

    @abstractmethod
    def bucket(self, name: str) -> Bucket:
        """A bucket the descriptor's ``buckets`` list names. Refused, naming the ones it does name,
        otherwise."""

    @property
    @abstractmethod
    def buckets(self) -> list[Bucket]:
        """Every named bucket the service has, in no particular order."""


class UnavailableBuckets(Buckets):
    """A client that refuses every call, naming ``provisionObjectStorage``: a deployed service with
    no bucket."""

    @property
    def default(self) -> Bucket:
        raise StorageRefusals.no_bucket()

    def bucket(self, name: str) -> Bucket:
        raise StorageRefusals.unknown(name, [])

    @property
    def buckets(self) -> list[Bucket]:
        return []


# ── in memory ─────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class _Kept:
    content_type: str
    last_kept: datetime
    data: bytes


def _memory_public_base(bucket: str) -> str:
    return f"http://memory/public/{bucket}"


class InMemoryBuckets(Buckets):
    """A service's buckets in memory: the test kits' store, so a test of a handler that keeps or
    reads an object needs no object store and no Docker.

    It applies every rule the other stores apply, in the same words: names and prefixes, the
    declared length, a signed URL only from a signed bucket and an address only from a public one,
    and a bucket the service does not declare refused naming those it does. A signed URL is computed
    (``http://memory/...``) and reaches nothing.

    ``buckets`` are the named buckets and their exposure (``declare`` adds more); ``unnamed`` whether
    the service has the unnamed bucket, which is signed, as for a service that sets
    ``exposeObjectStorage``."""

    def __init__(
        self,
        buckets: Mapping[str, Exposure] | None = None,
        *,
        unnamed: bool = True,
        public_base: Callable[[str], str] = _memory_public_base,
        clock: Callable[[], datetime] = _now,
    ) -> None:
        self._exposures: dict[str, Exposure] = {}
        self._unnamed = unnamed
        self._public_base = public_base
        self._clock = clock
        self.objects: dict[tuple[str | None, str], _Kept] = {}
        for name, exposure in (buckets or {}).items():
            self.declare(name, exposure)

    def declare(self, name: str, exposure: Exposure) -> None:
        """Declares a named bucket, before or after the service starts."""
        problem = ObjectRules.bucket_name_problem(name)
        if problem is not None:
            raise ValueError(problem)
        if exposure not in EXPOSURES:
            raise ValueError(f"exposure '{exposure}' is one of {', '.join(EXPOSURES)}")
        self._exposures[name] = exposure

    def clear(self) -> None:
        """Forgets every object; the declared buckets stay."""
        self.objects.clear()

    @property
    def default(self) -> Bucket:
        if not self._unnamed:
            raise StorageRefusals.no_bucket()
        return _MemoryBucket(self, None, "signed")

    def bucket(self, name: str) -> Bucket:
        exposure = self._exposures.get(name)
        if exposure is None:
            raise StorageRefusals.unknown(name, list(self._exposures))
        return _MemoryBucket(self, name, exposure)

    @property
    def buckets(self) -> list[Bucket]:
        return [_MemoryBucket(self, n, e) for n, e in self._exposures.items()]


class _MemoryBucket(Bucket):
    def __init__(self, store: InMemoryBuckets, name: str | None, exposure: Exposure) -> None:
        self._store = store
        self.name = name
        self.exposure = exposure

    async def put(
        self, name: str, body: AsyncIterable[bytes] | BODY_ITEM, *, length: int | None = None, content_type: str
    ) -> None:
        ObjectRules.check_name(name)
        declared = _declared_length(body, length)
        data = b"".join([chunk async for chunk in _bounded(body, declared)])
        if len(data) < declared:
            raise StorageRefusals.short_body(declared, len(data))
        self._store.objects[(self.name, name)] = _Kept(content_type, self._store._clock(), data)

    async def get(self, name: str) -> StoredObject | None:
        ObjectRules.check_name(name)
        kept = self._store.objects.get((self.name, name))
        if kept is None:
            return None
        return StoredObject(kept.content_type, len(kept.data), kept.last_kept, _chunks(kept.data))

    async def delete(self, name: str) -> None:
        ObjectRules.check_name(name)
        self._store.objects.pop((self.name, name), None)

    def list(self, prefix: str) -> AsyncIterator[ObjectEntry]:
        ObjectRules.check_prefix(prefix)
        found = sorted(
            (ObjectEntry(n, len(k.data), k.last_kept) for (b, n), k in self._store.objects.items() if b == self.name and n.startswith(prefix)),
            key=lambda e: e.name,
        )
        return _iterate(found)

    async def stat(self, name: str) -> ObjectInfo | None:
        ObjectRules.check_name(name)
        kept = self._store.objects.get((self.name, name))
        return None if kept is None else ObjectInfo(name, kept.content_type, len(kept.data), kept.last_kept)

    def signed_url(self, name: str, access: Access, span: timedelta = ObjectRules.DEFAULT_SIGNED_URL) -> str:
        ObjectRules.check_name(name)
        if self.exposure not in ("signed", "supplied"):
            raise StorageRefusals.not_exposed() if self.name is None else StorageRefusals.not_signed(self.name, self.exposure)
        ObjectRules.check_span(span)
        expires = int(self._store._clock().timestamp()) + int(span.total_seconds())
        segment = self.name if self.name is not None else "default"
        return f"http://memory/{segment}/{sigv4.encode(name, False)}?access={_access_word(access)}&expires={expires}"

    def url(self, name: str) -> str:
        ObjectRules.check_name(name)
        if self.name is not None and self.exposure == "public":
            return self._store._public_base(self.name).rstrip("/") + "/" + sigv4.encode(name, False)
        raise StorageRefusals.not_public(self.name, self.exposure)


async def _iterate(entries: Sequence[ObjectEntry]) -> AsyncIterator[ObjectEntry]:
    for entry in entries:
        yield entry


def _access_word(access: str) -> str:
    if access not in ("read", "keep"):
        raise _refused(f"a signed URL's access is read or keep, not '{access}'", ErrorCode.BAD_REQUEST)
    return access


# ── in a directory ────────────────────────────────────────────────────────────


def default_address_base(env: Mapping[str, str] | None = None) -> str:
    """The service's own address on a developer's machine, where its sidecar serves HTTP:
    ``http://<ANKKA_HTTP_INTERFACE or 127.0.0.1>:<ANKKA_HTTP_PORT or 9000>``."""
    e = os.environ if env is None else env
    interface = e.get("ANKKA_HTTP_INTERFACE") or "127.0.0.1"
    if interface in ("0.0.0.0", "::"):
        interface = "127.0.0.1"
    return f"http://{interface}:{e.get('ANKKA_HTTP_PORT') or '9000'}"


class DirectorySigner:
    """Signs and checks a directory store's URLs: ``HMAC-SHA256(key, "<bucket>/<name>|<access>|<expires>")``
    in hex, over a key made once and kept in the store's root (``.key``, readable by its owner only),
    so a restart honours the URLs made before it. The runtime's form, so the two read each other's
    root."""

    def __init__(self, key: bytes) -> None:
        self._key = key

    @staticmethod
    def at(root: Path) -> DirectorySigner:
        file = root / ".key"
        if file.is_file():
            return DirectorySigner(file.read_bytes())
        root.mkdir(parents=True, exist_ok=True)
        part = root / f".key-{uuid.uuid4()}"
        fd = os.open(part, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            os.write(fd, _random.token_bytes(32))
        finally:
            os.close(fd)
        try:
            # A link, not a rename: the first of two starting at once wins, and both read its key.
            os.link(part, file)
        except FileExistsError:
            pass
        finally:
            part.unlink(missing_ok=True)
        return DirectorySigner(file.read_bytes())

    def token(self, segment: str, name: str, access: str, expires: int) -> str:
        return hmac.new(self._key, f"{segment}/{name}|{access}|{expires}".encode("utf-8"), hashlib.sha256).hexdigest()

    def admits(self, segment: str, name: str, access: str, expires: int, token: str, now: datetime) -> bool:
        """Whether ``token`` signs this access until ``expires``, and ``expires`` is still to come."""
        return expires >= int(now.timestamp()) and hmac.compare_digest(
            token.encode("utf-8"), self.token(segment, name, access, expires).encode("utf-8")
        )


class DirectoryBuckets(Buckets):
    """A service's buckets in a directory on a developer's machine: what the storage client keeps
    when the service runs with no variable of an object store.

    The runtime's layout, so a Scala service and this one read the same root: each bucket a directory
    of its own under ``root`` (``_default`` for the unnamed one), its objects under ``objects/``, each
    name encoded into one file name (under its hash when too long), and beside each under ``meta/``
    its name, content type, length and when it was kept. A put writes a temporary file and moves it
    into place, so a stream that ends short leaves the old object.

    A signed URL and a public address point at ``address_base`` (the sidecar's HTTP port) under
    ``/_ankka/storage``, which the process serves through its sidecar.

    ``buckets`` are the named buckets the descriptor declares; ``None`` admits any name asked for, as
    private. The root is made on first use."""

    def __init__(
        self,
        path: str | os.PathLike[str],
        buckets: Mapping[str, Exposure] | None = None,
        *,
        unnamed: bool = True,
        address_base: str | Callable[[], str] | None = None,
        clock: Callable[[], datetime] = _now,
    ) -> None:
        self.root = Path(path).absolute()
        self._declared = None if buckets is None else dict(buckets)
        self._unnamed = unnamed
        base = address_base
        self._address_base: Callable[[], str] = (
            default_address_base if base is None else (lambda: base) if isinstance(base, str) else base
        )
        self._clock = clock
        self._signer: DirectorySigner | None = None

    @property
    def signer(self) -> DirectorySigner:
        if self._signer is None:
            try:
                self._signer = DirectorySigner.at(self.root)
            except OSError as e:
                raise self._failed(e) from e
        return self._signer

    @property
    def default(self) -> Bucket:
        if not self._unnamed:
            raise StorageRefusals.no_bucket()
        return _DirectoryBucket(self, None, "signed")

    def bucket(self, name: str) -> Bucket:
        if self._declared is not None:
            exposure = self._declared.get(name)
            if exposure is None:
                raise StorageRefusals.unknown(name, list(self._declared))
            return _DirectoryBucket(self, name, exposure)
        problem = ObjectRules.bucket_name_problem(name)
        if problem is not None:
            raise _refused(problem, ErrorCode.BAD_REQUEST)
        return _DirectoryBucket(self, name, "private")

    @property
    def buckets(self) -> list[Bucket]:
        return [_DirectoryBucket(self, n, e) for n, e in (self._declared or {}).items()]

    def by_segment(self, segment: str) -> _DirectoryBucket | None:
        """The bucket a URL's segment names: ``_default`` or a bucket's name."""
        try:
            found = self.default if segment == DEFAULT_SEGMENT else self.bucket(segment)
        except Exception:
            return None
        return found if isinstance(found, _DirectoryBucket) else None

    def _failed(self, e: OSError) -> Exception:
        return _refused(f"the directory store at {self.root} cannot be used: {e}", ErrorCode.UNAVAILABLE)

    # ── what the process serves at /_ankka/storage ────────────────────────

    async def answer(
        self, method: str, path_args: Sequence[str], query: Sequence[tuple[str, str]], content_type: str | None, body: bytes
    ) -> tuple[int, str, bytes]:
        """A request to ``/_ankka/storage/<bucket>/<name>``, as ``(status, content type, body)``:
        a ``GET`` reads with a token for ``read``, or with none on a public bucket; a ``PUT`` keeps
        with a token for ``keep``. Anything else is ``403``, and an object not there ``404``. The
        token is the whole of the authority, as a signature is on an object store."""
        if len(path_args) != 2:
            return 404, "text/plain", b"no object at this address"
        segment, name = unquote(path_args[0]), unquote(path_args[1])
        bucket = self.by_segment(segment)
        if bucket is None:
            return 404, "text/plain", b"no object at this address"
        params = dict(query)

        def signed_for(access: str) -> bool:
            try:
                expires = int(params.get("expires", ""))
            except ValueError:
                return False
            token = params.get("token")
            return params.get("access") == access and token is not None and self.signer.admits(
                bucket.segment, name, access, expires, token, self._clock()
            )

        from ankka.client import CommandError

        forbidden = (403, "text/plain", b"this address is not signed for that, or its signature has expired")
        try:
            if method == "GET":
                if not ((bucket.exposure == "public" and bucket.name is not None) or signed_for("read")):
                    return forbidden
                found = await bucket.get(name)
                if found is None:
                    return 404, "text/plain", f"no object '{name}'".encode("utf-8")
                return 200, found.content_type, await found.read()
            if method == "PUT":
                if not signed_for("keep"):
                    return forbidden
                await bucket.put(name, body, content_type=content_type or "application/octet-stream")
                return 200, "text/plain", b""
            return 405, "text/plain", f"{method} is not served at a storage address".encode("utf-8")
        except CommandError as refused:
            return refused.error.code.http_status, "text/plain", refused.error.message.encode("utf-8")


def storage_endpoint() -> discovery_pb2.Endpoint:
    """What a process declares at ``/_ankka/storage`` when its store is a directory: one read and
    one keep, the object's whole name one segment (``/`` encoded), as the store's URLs make it."""
    from ankka._proto.ankka.protocol.v1 import discovery_pb2

    template = "/{bucket}/{name}"
    return discovery_pb2.Endpoint(
        id=STORAGE_ENDPOINT_ID,
        prefix=STORAGE_PATH,
        acl=discovery_pb2.Endpoint.ALLOW_ALL,
        routes=[
            discovery_pb2.Route(id="keep", method="PUT", template=template, has_body=True),
            discovery_pb2.Route(id="read", method="GET", template=template),
        ],
    )


def _file_name(name: str) -> str:
    encoded = sigv4.encode(name, encode_slash=True)
    return encoded if len(encoded) <= 200 else "h-" + hashlib.sha256(name.encode("utf-8")).hexdigest()


class _DirectoryBucket(Bucket):
    def __init__(self, store: DirectoryBuckets, name: str | None, exposure: Exposure) -> None:
        self._store = store
        self.name = name
        self.exposure = exposure
        self.segment = name if name is not None else DEFAULT_SEGMENT
        self._objects = store.root / self.segment / "objects"
        self._metas = store.root / self.segment / "meta"

    def _object_file(self, name: str) -> Path:
        return self._objects / _file_name(name)

    def _meta_file(self, name: str) -> Path:
        return self._metas / (_file_name(name) + ".json")

    def _io(self, e: OSError) -> Exception:
        return self._store._failed(e)

    async def put(
        self, name: str, body: AsyncIterable[bytes] | BODY_ITEM, *, length: int | None = None, content_type: str
    ) -> None:
        ObjectRules.check_name(name)
        declared = _declared_length(body, length)
        scratch = self._store.root / ".tmp"
        try:
            for d in (self._objects, self._metas, scratch):
                d.mkdir(parents=True, exist_ok=True)
        except OSError as e:
            raise self._io(e) from e
        part = scratch / f"{uuid.uuid4()}.part"
        try:
            written = 0
            try:
                with open(part, "wb") as out:
                    async for chunk in _bounded(body, declared):
                        await asyncio.to_thread(out.write, chunk)
                        written += len(chunk)
            except OSError as e:
                raise self._io(e) from e
            if written < declared:
                raise StorageRefusals.short_body(declared, written)
            meta = {"name": name, "contentType": content_type, "length": declared, "lastKept": _format_instant(self._store._clock())}
            meta_part = scratch / f"{uuid.uuid4()}.meta"
            try:
                meta_part.write_bytes(json.dumps(meta, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
                os.replace(part, self._object_file(name))
                os.replace(meta_part, self._meta_file(name))
            except OSError as e:
                meta_part.unlink(missing_ok=True)
                raise self._io(e) from e
        finally:
            part.unlink(missing_ok=True)

    def _meta(self, name: str) -> dict[str, Any] | None:
        try:
            return cast(dict[str, Any], json.loads(self._meta_file(name).read_bytes()))
        except FileNotFoundError:
            return None
        except OSError as e:
            raise self._io(e) from e

    async def get(self, name: str) -> StoredObject | None:
        ObjectRules.check_name(name)
        meta = self._meta(name)
        if meta is None:
            return None
        try:
            handle = open(self._object_file(name), "rb")
        except FileNotFoundError:
            return None
        except OSError as e:
            raise self._io(e) from e

        async def body() -> AsyncIterator[bytes]:
            try:
                while chunk := await asyncio.to_thread(handle.read, 64 * 1024):
                    yield chunk
            finally:
                handle.close()

        async def close() -> None:
            handle.close()

        return StoredObject(str(meta["contentType"]), int(meta["length"]), _parse_instant(str(meta["lastKept"])), body(), close)

    async def delete(self, name: str) -> None:
        ObjectRules.check_name(name)
        try:
            self._meta_file(name).unlink(missing_ok=True)
            self._object_file(name).unlink(missing_ok=True)
        except OSError as e:
            raise self._io(e) from e

    def list(self, prefix: str) -> AsyncIterator[ObjectEntry]:
        ObjectRules.check_prefix(prefix)
        found: list[ObjectEntry] = []
        if self._metas.is_dir():
            try:
                files = list(self._metas.iterdir())
            except OSError as e:
                raise self._io(e) from e
            for f in files:
                try:
                    m = json.loads(f.read_bytes())
                    entry = ObjectEntry(str(m["name"]), int(m["length"]), _parse_instant(str(m["lastKept"])))
                except Exception:  # noqa: BLE001 - a file being replaced, or not one of ours
                    continue
                if entry.name.startswith(prefix):
                    found.append(entry)
        found.sort(key=lambda e: e.name)
        return _iterate(found)

    async def stat(self, name: str) -> ObjectInfo | None:
        ObjectRules.check_name(name)
        meta = self._meta(name)
        if meta is None:
            return None
        return ObjectInfo(str(meta["name"]), str(meta["contentType"]), int(meta["length"]), _parse_instant(str(meta["lastKept"])))

    def signed_url(self, name: str, access: Access, span: timedelta = ObjectRules.DEFAULT_SIGNED_URL) -> str:
        ObjectRules.check_name(name)
        if self.exposure not in ("signed", "supplied"):
            raise StorageRefusals.not_exposed() if self.name is None else StorageRefusals.not_signed(self.name, self.exposure)
        ObjectRules.check_span(span)
        word = _access_word(access)
        expires = int(self._store._clock().timestamp()) + int(span.total_seconds())
        token = self._store.signer.token(self.segment, name, word, expires)
        return f"{self._address(name)}?access={word}&expires={expires}&token={token}"

    def url(self, name: str) -> str:
        ObjectRules.check_name(name)
        if self.exposure == "public" and self.name is not None:
            return self._address(name)
        raise StorageRefusals.not_public(self.name, self.exposure)

    def _address(self, name: str) -> str:
        # The whole name is one path segment, '/' encoded: the route a process declares names the
        # bucket and the object, and a template's parameter is one segment.
        return f"{self._store._address_base().rstrip('/')}{STORAGE_PATH}/{self.segment}/{sigv4.encode(name, encode_slash=True)}"


# ── an S3 store ───────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class S3Target:
    """One S3 bucket and how to reach it. ``bucket`` empty: the endpoint and key named buckets
    share, with no unnamed bucket."""

    endpoint: str
    region: str
    bucket: str
    credentials: sigv4.Credentials | None
    public_endpoint: str | None = None


_ABORT = object()


class _ShortBody(Exception):
    pass


class S3Bucket(Bucket):
    """One bucket in an S3 store, spoken to with ``http.client`` and ``sigv4``: path-style requests,
    a put streamed once with an unsigned payload and its ``Content-Length``, a listing paged behind
    its iterator. Every request runs on a worker thread (``asyncio.to_thread``)."""

    def __init__(
        self,
        target: S3Target,
        name: str | None,
        exposure: Exposure,
        public_url: str | None = None,
        *,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        max_signed_url: timedelta = ObjectRules.MAX_SIGNED_URL,
        clock: Callable[[], datetime] = _now,
    ) -> None:
        self._target = target
        self.name = name
        self.exposure = exposure
        self._public_url = public_url
        self._timeout = timeout
        self._max_signed_url = max_signed_url
        self._clock = clock

    # ── requests ──────────────────────────────────────────────────────────

    @property
    def _credentials(self) -> sigv4.Credentials:
        if self._target.credentials is None:
            raise StorageRefusals.no_credential()
        return self._target.credentials

    def _object_path(self, name: str) -> str:
        return f"/{self._target.bucket}/{name}"

    def _connection(self) -> http.client.HTTPConnection:
        parts = urlsplit(self._target.endpoint)
        if parts.scheme == "https":
            return http.client.HTTPSConnection(parts.hostname or "", parts.port, timeout=self._timeout)
        return http.client.HTTPConnection(parts.hostname or "", parts.port, timeout=self._timeout)

    def _unreachable(self, e: BaseException) -> Exception:
        if isinstance(e, (socket.timeout, TimeoutError)):
            cause = f"no answer within {int(self._timeout)} seconds"
        else:
            cause = str(e) or type(e).__name__
        return StorageRefusals.unreachable(self._target.endpoint, cause)

    def _refused(self, status: int, body: bytes) -> Exception:
        found = re.search(r"<Code>([^<]*)</Code>", body.decode("utf-8", errors="replace"))
        return StorageRefusals.refused_by_store(self._target.endpoint, status, found.group(1) if found else "")

    def _open(
        self,
        method: str,
        path: str,
        query: tuple[tuple[str, str], ...] = (),
        *,
        headers: Mapping[str, str] | None = None,
        payload: str = sigv4.EMPTY_PAYLOAD,
        body: Any = None,
    ) -> tuple[http.client.HTTPConnection, http.client.HTTPResponse]:
        """Sends one signed request and answers the connection and its response, unread. On a
        worker thread."""
        request = sigv4.Request(method, self._target.endpoint, path, query)
        signed = {"x-amz-content-sha256": payload, **(headers or {})}
        auth = sigv4.sign(request, signed, payload, self._credentials, self._target.region, "s3", self._clock())
        conn = self._connection()
        try:
            conn.request(method, request.target, body=body, headers={"Host": request.host, **signed, **auth})
            return conn, conn.getresponse()
        except BaseException:
            conn.close()
            raise

    def _call(
        self, method: str, path: str, query: tuple[tuple[str, str], ...] = ()
    ) -> tuple[int, http.client.HTTPMessage, bytes]:
        try:
            conn, response = self._open(method, path, query)
            try:
                return response.status, response.headers, response.read()
            finally:
                conn.close()
        except (OSError, http.client.HTTPException) as e:
            raise self._unreachable(e) from e

    async def put(
        self, name: str, body: AsyncIterable[bytes] | BODY_ITEM, *, length: int | None = None, content_type: str
    ) -> None:
        ObjectRules.check_name(name)
        declared = _declared_length(body, length)
        _ = self._credentials  # refuse before a byte is read
        # The body crosses from this task to the request's thread through a small queue, so it is
        # read once, as it is sent; a stream that ends short aborts the request and nothing is kept.
        chunks: queue.Queue[object] = queue.Queue(maxsize=8)
        ended = threading.Event()

        def stream() -> Iterator[bytes]:
            while True:
                item = chunks.get()
                if item is None:
                    return
                if item is _ABORT:
                    raise _ShortBody()
                yield cast(bytes, item)

        headers = {"Content-Type": content_type, "Content-Length": str(declared)}

        def send() -> tuple[int, bytes]:
            try:
                conn, response = self._open(
                    "PUT", self._object_path(name), headers=headers, payload=sigv4.UNSIGNED_PAYLOAD, body=stream() if declared else b""
                )
                try:
                    return response.status, response.read()
                finally:
                    conn.close()
            finally:
                ended.set()

        def offer(item: object) -> bool:
            while not ended.is_set():
                try:
                    chunks.put(item, timeout=0.1)
                    return True
                except queue.Full:
                    continue
            return False

        sender = asyncio.ensure_future(asyncio.to_thread(send))
        consumed = 0
        short = False
        try:
            if declared:
                async for chunk in _bounded(body, declared):
                    if not await asyncio.to_thread(offer, chunk):
                        break  # the request ended before the body did: its answer says why
                    consumed += len(chunk)
                else:
                    short = consumed < declared
                await asyncio.to_thread(offer, _ABORT if short else None)
        except BaseException:
            await asyncio.to_thread(offer, _ABORT)
            try:
                await sender
            except BaseException:  # noqa: BLE001 - the caller's failure is the one to raise
                pass
            raise
        try:
            status, answer = await sender
        except _ShortBody:
            raise StorageRefusals.short_body(declared, consumed) from None
        except (OSError, http.client.HTTPException) as e:
            if short:
                raise StorageRefusals.short_body(declared, consumed) from e
            raise self._unreachable(e) from e
        if status != 200:
            raise self._refused(status, answer)
        if consumed < declared:
            raise StorageRefusals.short_body(declared, consumed)

    async def get(self, name: str) -> StoredObject | None:
        ObjectRules.check_name(name)
        _ = self._credentials

        def open_() -> tuple[http.client.HTTPConnection, http.client.HTTPResponse]:
            try:
                return self._open("GET", self._object_path(name))
            except (OSError, http.client.HTTPException) as e:
                raise self._unreachable(e) from e

        conn, response = await asyncio.to_thread(open_)
        if response.status == 404:
            conn.close()
            return None
        if response.status != 200:
            detail = await asyncio.to_thread(response.read, 4096)
            conn.close()
            raise self._refused(response.status, detail)
        length = int(response.headers.get("Content-Length") or -1)

        async def body() -> AsyncIterator[bytes]:
            try:
                while True:
                    try:
                        chunk = await asyncio.to_thread(response.read, 64 * 1024)
                    except (OSError, http.client.HTTPException) as e:
                        raise self._unreachable(e) from e
                    if not chunk:
                        return
                    yield chunk
            finally:
                conn.close()

        async def close() -> None:
            conn.close()

        return StoredObject(_content_type(response.headers), length, _last_modified(response.headers), body(), close)

    async def stat(self, name: str) -> ObjectInfo | None:
        ObjectRules.check_name(name)
        _ = self._credentials
        status, headers, _body = await asyncio.to_thread(self._call, "HEAD", self._object_path(name))
        if status == 404:
            return None
        if status != 200:
            raise self._refused(status, b"")
        return ObjectInfo(name, _content_type(headers), int(headers.get("Content-Length") or 0), _last_modified(headers))

    async def delete(self, name: str) -> None:
        ObjectRules.check_name(name)
        _ = self._credentials
        status, _headers, body = await asyncio.to_thread(self._call, "DELETE", self._object_path(name))
        if status not in (200, 204, 404):
            raise self._refused(status, body)

    def list(self, prefix: str) -> AsyncIterator[ObjectEntry]:
        ObjectRules.check_prefix(prefix)
        return self._pages(prefix)

    async def _pages(self, prefix: str) -> AsyncIterator[ObjectEntry]:
        token: str | None = None
        while True:
            query: tuple[tuple[str, str], ...] = (("list-type", "2"), ("prefix", prefix))
            if token is not None:
                query += (("continuation-token", token),)
            status, _headers, body = await asyncio.to_thread(self._call, "GET", f"/{self._target.bucket}", query)
            if status != 200:
                raise self._refused(status, body)
            try:
                entries, token = parse_listing(body)
            except Exception as e:  # noqa: BLE001 - anything unreadable is the store's answer
                raise StorageRefusals.unreadable(self._target.endpoint, str(e)) from e
            for entry in entries:
                yield entry
            if token is None:
                return

    def signed_url(self, name: str, access: Access, span: timedelta = ObjectRules.DEFAULT_SIGNED_URL) -> str:
        ObjectRules.check_name(name)
        if self.exposure == "supplied":
            base = self._target.endpoint
        elif self.name is None or self.exposure == "signed":
            if self._target.public_endpoint is None:
                raise StorageRefusals.not_exposed()
            base = self._target.public_endpoint
        else:
            raise StorageRefusals.not_signed(self.name, self.exposure)
        ObjectRules.check_span(span)
        seconds = int(span.total_seconds())
        if span > self._max_signed_url:
            raise _refused(
                f"a signed URL is made for at most {int(self._max_signed_url.total_seconds())} seconds here; this one asks for {seconds}",
                ErrorCode.BAD_REQUEST,
            )
        method = "GET" if _access_word(access) == "read" else "PUT"
        return sigv4.presign(
            sigv4.Request(method, base, self._object_path(name)), self._credentials, self._target.region, "s3", seconds, self._clock()
        )

    def url(self, name: str) -> str:
        ObjectRules.check_name(name)
        if self.exposure == "public" and self._public_url is not None:
            return self._public_url.rstrip("/") + "/" + sigv4.encode(name, encode_slash=False)
        raise StorageRefusals.not_public(self.name, self.exposure)


def _content_type(headers: http.client.HTTPMessage) -> str:
    return headers.get("Content-Type") or "application/octet-stream"


def _last_modified(headers: http.client.HTTPMessage) -> datetime:
    value = headers.get("Last-Modified")
    if value:
        try:
            return parsedate_to_datetime(value).astimezone(UTC)
        except (TypeError, ValueError):
            pass
    return datetime.fromtimestamp(0, UTC)


def parse_listing(xml: bytes) -> tuple[list[ObjectEntry], str | None]:
    """A ``ListObjectsV2`` answer: its entries, and the token for the next page while it is
    truncated."""
    root = ElementTree.fromstring(xml)

    def local(tag: str) -> str:
        return tag.rsplit("}", 1)[-1]

    entries: list[ObjectEntry] = []
    token: str | None = None
    truncated = False
    for child in root:
        tag = local(child.tag)
        if tag == "Contents":
            fields = {local(c.tag): (c.text or "") for c in child}
            modified = fields.get("LastModified", "").strip()
            entries.append(
                ObjectEntry(
                    fields.get("Key", ""),
                    int(fields.get("Size", "0").strip() or 0),
                    _parse_instant(modified) if modified else datetime.fromtimestamp(0, UTC),
                )
            )
        elif tag == "NextContinuationToken":
            token = child.text or ""
        elif tag == "IsTruncated":
            truncated = (child.text or "").strip() == "true"
    return entries, token if truncated else None


class S3Buckets(Buckets):
    """The S3 store's buckets, by name: the unnamed one and each named one."""

    def __init__(self, unnamed: Bucket | None, named: Mapping[str, Bucket]) -> None:
        self._unnamed = unnamed
        self._named = dict(named)

    @staticmethod
    def from_environment(env: Mapping[str, str] | None = None, *, timeout: float = DEFAULT_TIMEOUT_SECONDS) -> S3Buckets:
        """The buckets the ``ANKKA_S3_*`` variables name, read as the runtime reads them."""
        settings = StorageSettings.read(os.environ if env is None else env)
        return settings.s3_buckets(timeout=timeout)

    @property
    def default(self) -> Bucket:
        if self._unnamed is None:
            raise StorageRefusals.no_bucket()
        return self._unnamed

    def bucket(self, name: str) -> Bucket:
        found = self._named.get(name)
        if found is None:
            raise StorageRefusals.unknown(name, list(self._named))
        return found

    @property
    def buckets(self) -> list[Bucket]:
        return list(self._named.values())


# ── the settings ──────────────────────────────────────────────────────────────


class StorageSettingsError(ValueError):
    """A storage variable the client cannot use: the reason the process does not start."""


@dataclass(frozen=True)
class NamedTarget:
    name: str
    exposure: Exposure
    target: S3Target
    public_url: str | None


def bucket_variable_suffix(bucket: str) -> str:
    """The ``<NAME>`` in a named bucket's variables: its name upper-cased, ``-`` as ``_``."""
    return bucket.upper().replace("-", "_")


def bucket_name_of(suffix: str) -> str:
    """The bucket a variable's ``<NAME>`` names: the inverse of ``bucket_variable_suffix``."""
    return suffix.lower().replace("_", "-")


def _url(name: str, value: str) -> str:
    parts = urlsplit(value)
    if not parts.scheme or not parts.hostname:
        raise StorageSettingsError(f"{name} is not a URL with a scheme and a host: '{value}'")
    return value


def _required(env: Mapping[str, str], name: str) -> str:
    value = env.get(name, "")
    if not value:
        raise StorageSettingsError(f"{name} is not set")
    return value


def _credentials(env: Mapping[str, str], access: str, secret: str) -> sigv4.Credentials | None:
    a, s = env.get(access, ""), env.get(secret, "")
    return sigv4.Credentials(a, s) if a and s else None


@dataclass(frozen=True)
class StorageSettings:
    """The buckets the ``ANKKA_S3_*`` variables name, read as the runtime's ``StorageSettings``
    reads them: the unnamed family ``ANKKA_S3_{ENDPOINT,REGION,BUCKET,ACCESS_KEY,SECRET_KEY,PUBLIC_ENDPOINT}``;
    a named bucket the platform made, ``ANKKA_S3_BUCKET_<NAME>``, ``ANKKA_S3_EXPOSURE_<NAME>`` and
    ``ANKKA_S3_PUBLIC_URL_<NAME>``, sharing the unnamed family's endpoint, region and key; a supplied
    bucket, its own five ``ANKKA_S3_{ENDPOINT,REGION,BUCKET,ACCESS_KEY,SECRET_KEY}_<NAME>``."""

    unnamed: S3Target | None
    """The unnamed family's endpoint, region and key; its ``bucket`` empty when only named buckets
    share them."""
    named: tuple[NamedTarget, ...]

    @property
    def any_s3(self) -> bool:
        return self.unnamed is not None or bool(self.named)

    @staticmethod
    def read(env: Mapping[str, str]) -> StorageSettings:
        unnamed = StorageSettings._unnamed(env)
        return StorageSettings(unnamed, StorageSettings._named(env, unnamed))

    @staticmethod
    def _unnamed(env: Mapping[str, str]) -> S3Target | None:
        endpoint = env.get(PREFIX + "ENDPOINT", "")
        if not endpoint:
            return None
        _url(PREFIX + "ENDPOINT", endpoint)
        region = _required(env, PREFIX + "REGION")
        public = env.get(PREFIX + "PUBLIC_ENDPOINT", "")
        if public:
            _url(PREFIX + "PUBLIC_ENDPOINT", public)
        return S3Target(
            endpoint,
            region,
            env.get(PREFIX + "BUCKET", ""),
            _credentials(env, PREFIX + "ACCESS_KEY", PREFIX + "SECRET_KEY"),
            public or None,
        )

    @staticmethod
    def _named(env: Mapping[str, str], shared: S3Target | None) -> tuple[NamedTarget, ...]:
        suffixes = sorted(
            {k[len(p):] for k in env for p in (PREFIX + "BUCKET_", PREFIX + "ENDPOINT_") if k.startswith(p) and len(k) > len(p)}
        )
        return tuple(StorageSettings._named_one(env, s, shared) for s in suffixes)

    @staticmethod
    def _named_one(env: Mapping[str, str], suffix: str, shared: S3Target | None) -> NamedTarget:
        name = bucket_name_of(suffix)
        endpoint = env.get(f"{PREFIX}ENDPOINT_{suffix}", "")
        if endpoint:
            _url(f"{PREFIX}ENDPOINT_{suffix}", endpoint)
            region = _required(env, f"{PREFIX}REGION_{suffix}")
            bucket = _required(env, f"{PREFIX}BUCKET_{suffix}")
            access = _required(env, f"{PREFIX}ACCESS_KEY_{suffix}")
            secret = _required(env, f"{PREFIX}SECRET_KEY_{suffix}")
            return NamedTarget(name, "supplied", S3Target(endpoint, region, bucket, sigv4.Credentials(access, secret)), None)
        bucket = _required(env, f"{PREFIX}BUCKET_{suffix}")
        if shared is None:
            raise StorageSettingsError(f"bucket '{name}' ({PREFIX}BUCKET_{suffix}) is made by the platform and {PREFIX}ENDPOINT is not set")
        word = env.get(f"{PREFIX}EXPOSURE_{suffix}", "")
        exposure: Exposure
        if not word:
            exposure = "private"
        elif word in ("private", "signed", "public"):
            exposure = cast(Exposure, word)
        else:
            raise StorageSettingsError(f"{PREFIX}EXPOSURE_{suffix} is '{word}'; it is one of private, signed or public")
        public_url = env.get(f"{PREFIX}PUBLIC_URL_{suffix}", "").rstrip("/") or None
        if exposure == "public" and public_url is None:
            raise StorageSettingsError(f"bucket '{name}' is public and {PREFIX}PUBLIC_URL_{suffix} is not set")
        target = S3Target(shared.endpoint, shared.region, bucket, shared.credentials, shared.public_endpoint)
        return NamedTarget(name, exposure, target, public_url)

    def s3_buckets(self, *, timeout: float = DEFAULT_TIMEOUT_SECONDS) -> S3Buckets:
        unnamed: Bucket | None = None
        if self.unnamed is not None and self.unnamed.bucket:
            exposure: Exposure = "signed" if self.unnamed.public_endpoint else "private"
            unnamed = S3Bucket(self.unnamed, None, exposure, timeout=timeout)
        named: dict[str, Bucket] = {
            n.name: S3Bucket(n.target, n.name, n.exposure, n.public_url, timeout=timeout) for n in self.named
        }
        return S3Buckets(unnamed, named)


@dataclass(frozen=True)
class DeclaredBuckets:
    """What a ``service.json`` declares: whether the unnamed bucket is asked for, and each named
    one."""

    unnamed: bool
    buckets: dict[str, Exposure]


def read_declared(directory: str | os.PathLike[str] | None = None) -> DeclaredBuckets | None:
    """The buckets the ``service.json`` in ``directory`` (the working directory by default)
    declares; ``None`` when there is no such file. Only the two fields that say which buckets there
    are: the descriptor's other rules are the control plane's."""
    file = Path(directory if directory is not None else Path.cwd()) / "service.json"
    if not file.is_file():
        return None
    try:
        descriptor = json.loads(file.read_bytes())
        service = descriptor.get("service") or {}
        entries = service.get("buckets") or []
        unnamed = bool(service.get("provisionObjectStorage", False))
    except Exception as e:  # noqa: BLE001 - whatever it was, the file is unreadable
        raise StorageSettingsError(f"{file} cannot be read: {e}") from e
    buckets: dict[str, Exposure] = {}
    for entry in entries:
        name = str(entry.get("name", ""))
        if entry.get("supplied") is not None:
            buckets[name] = "supplied"
            continue
        word = entry.get("exposure") or "private"
        if word not in ("private", "signed", "public"):
            raise StorageSettingsError(f"buckets: '{name}' has exposure '{word}'; it is one of private, signed or public")
        buckets[name] = cast(Exposure, word)
    return DeclaredBuckets(unnamed, buckets)


def running_locally(env: Mapping[str, str]) -> bool:
    """Whether this process runs on a developer's machine: not in a Kubernetes pod, where every
    container is told ``KUBERNETES_SERVICE_HOST``. Only there is a process given the directory
    store when nothing else is named."""
    return not env.get("KUBERNETES_SERVICE_HOST") and env.get("ANKKA_CLUSTER_MODE") != "kubernetes"


def from_environment(
    env: Mapping[str, str] | None = None,
    *,
    descriptor_directory: str | os.PathLike[str] | None = None,
    address_base: str | Callable[[], str] | None = None,
) -> Buckets:
    """The one place a process's store is chosen, as the runtime chooses: ``ANKKA_STORAGE_BACKEND``
    names ``s3``, ``directory`` or ``memory``; empty, the S3 store when ``ANKKA_S3_ENDPOINT`` or a
    supplied bucket is set, the directory store (``ANKKA_STORAGE_DIR``, else ``.ankka/storage``)
    when the process runs on a developer's machine, else none, every call refused naming
    ``provisionObjectStorage``. The directory and memory stores hold the buckets the ``service.json``
    in ``descriptor_directory`` (the working directory) declares. A setting it cannot use raises
    ``StorageSettingsError`` naming it."""
    e = os.environ if env is None else env
    settings = StorageSettings.read(e)
    word = e.get(STORAGE_BACKEND, "").strip()
    if word == "":
        word = "s3" if settings.any_s3 else "directory" if running_locally(e) else "unavailable"
    if word == "s3":
        if not settings.any_s3:
            raise StorageSettingsError(f"{STORAGE_BACKEND} is s3 and {PREFIX}ENDPOINT is not set: the s3 store needs an endpoint")
        return settings.s3_buckets()
    if word == "unavailable":
        return UnavailableBuckets()
    if word not in ("directory", "memory"):
        raise StorageSettingsError(f"{STORAGE_BACKEND} is '{word}'; it is one of s3, directory or memory")
    declared = read_declared(descriptor_directory)
    named = None if declared is None else declared.buckets
    unnamed = True if declared is None else declared.unnamed
    if word == "memory":
        return InMemoryBuckets(named, unnamed=unnamed)
    directory = Path(e.get(STORAGE_DIR) or DEFAULT_DIRECTORY)
    return DirectoryBuckets(directory, named, unnamed=unnamed, address_base=address_base)


# ── who is given one ──────────────────────────────────────────────────────────


class HasStorage:
    """What gives an endpoint, a consumer, a graph consumer, a timed action, an agent and an
    autonomous agent their ``storage``: the service's buckets, which the server hands each instance
    it builds. A unit test assigns its own (``component.storage = InMemoryBuckets()``), as the test
    kits do."""

    _storage: Buckets | None = None

    @property
    def storage(self) -> Buckets:
        if self._storage is None:
            raise RuntimeError(
                f"{type(self).__name__} has no storage: it is given the service's buckets when the runtime builds "
                "it, and a unit test assigns them (InMemoryBuckets)"
            )
        return self._storage

    @storage.setter
    def storage(self, buckets: Buckets) -> None:
        self._storage = buckets


__all__ = [
    "Access",
    "Bucket",
    "Buckets",
    "DeclaredBuckets",
    "DirectoryBuckets",
    "DirectorySigner",
    "Exposure",
    "HasStorage",
    "InMemoryBuckets",
    "ObjectEntry",
    "ObjectInfo",
    "ObjectRules",
    "S3Bucket",
    "S3Buckets",
    "S3Target",
    "StorageRefusals",
    "StorageSettings",
    "StorageSettingsError",
    "StoredObject",
    "UnavailableBuckets",
    "from_environment",
    "read_declared",
    "storage_endpoint",
]
