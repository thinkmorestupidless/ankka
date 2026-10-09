"""Personal fields (protocol 1.15): a value of one data subject, kept encrypted under that subject's key.

A field annotated ``Personal[T]`` is written as the personal envelope every SDK writes,
``{"subject", "project", "data"[, "lookup"]}``, where ``data`` is AES-256-GCM over the value's JSON
with the subject and project as associated data. When the data subject is erased its key is
destroyed, and every copy of the field — journal, snapshot, view row, topic, backup — reads as
:class:`Erased` from then on.

The keys come from the runtime beside the process, which holds the keyring's channel; the process
caches each for as long as it is told and drops it the moment it hears the subject is erased. The
codecs are synchronous, so a key not yet held is fetched with one blocking call to the runtime.
"""

from __future__ import annotations

import re
import threading
import time
from collections import OrderedDict
from collections.abc import Callable
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Generic, Protocol, TypeVar

if TYPE_CHECKING:
    from collections.abc import Iterator

T = TypeVar("T")

_SUBJECT = re.compile(r"[A-Za-z0-9._\-/:]{1,253}")


class DataSubjectError(ValueError):
    """A data subject that is not one: empty, longer than 253 characters, or holding a character
    other than letters, digits, ``.``, ``_``, ``-``, ``/`` and ``:``."""


class PersonalFieldError(Exception):
    """A personal field that cannot be written or read: the subject is erased, the keyring refused,
    or no keyring is reachable. ``code`` is the platform's error code for it."""

    def __init__(self, message: str, code: str) -> None:
        super().__init__(message)
        self.message = message
        self.code = code


def check_subject(subject: str) -> str:
    if not subject:
        raise DataSubjectError("a data subject is required")
    if len(subject) > 253:
        raise DataSubjectError(f"a data subject is at most 253 characters, not {len(subject)}")
    if not _SUBJECT.fullmatch(subject):
        raise DataSubjectError(f"a data subject is letters, digits, '.', '_', '-', '/' and ':' only: {subject!r}")
    return subject


class Personal(Generic[T]):
    """A value of one data subject: :class:`Present` or :class:`Erased`. Annotate a field with
    ``Personal[str]`` (or any other type the codec writes) and build it with :func:`personal`."""

    subject: str
    project: str | None

    @property
    def value(self) -> T | None:
        """The value, or ``None`` when the subject is erased."""
        raise NotImplementedError

    @property
    def is_erased(self) -> bool:
        return self.value is None


@dataclass(frozen=True, eq=False)
class Present(Personal[T]):
    subject: str
    _value: T
    lookup: bool = False
    project: str | None = None
    # Read from a store, or already written once: re-writing it for an erased subject writes the
    # erased form, where a value made fresh for an erased subject is refused.
    stored: bool = field(default=False, compare=False)

    @property
    def value(self) -> T | None:
        source = _source
        if source is not None and source.is_destroyed(self.project or source.own_project, self.subject):
            return None
        return self._value

    def __eq__(self, other: object) -> bool:
        if isinstance(other, Personal):
            return self.subject == other.subject and self.value == other.value
        return NotImplemented

    def __hash__(self) -> int:
        return hash((self.subject, "present"))

    def __repr__(self) -> str:
        # Never the value: a personal field must not reach a log line by being printed.
        return f"Present(subject={self.subject!r}, value=<personal>)"


@dataclass(frozen=True)
class Erased(Personal[T]):
    subject: str
    project: str | None = None

    @property
    def value(self) -> T | None:
        return None

    def __eq__(self, other: object) -> bool:
        if isinstance(other, Personal):
            return self.subject == other.subject and other.value is None
        return NotImplemented

    def __hash__(self) -> int:
        return hash((self.subject, "present"))


def personal(subject: str, value: T, lookup: bool = False) -> Present[T]:
    """A personal value of ``subject``. ``lookup=True`` lets a view's declared query match the field
    by equality, through the token :meth:`ankka.client.ComponentClient.lookup_token` makes.

    Refused at once for a subject this process has been told is erased."""
    check_subject(subject)
    source = _source
    if source is not None and source.is_destroyed(source.own_project, subject):
        raise PersonalFieldError(
            f"data subject {subject} is erased: no personal field can be written for it", "BAD_REQUEST"
        )
    return Present(subject, value, lookup)


# ── Keys ─────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class KeyAnswer:
    """``kind`` is ``key``, ``destroyed``, ``refused`` or ``unavailable``."""

    kind: str
    project: str
    key: bytes = b""
    reason: str = ""


class KeySource(Protocol):
    @property
    def own_project(self) -> str | None: ...

    def key(self, project: str | None, subject: str, create: bool) -> KeyAnswer: ...

    def lookup_token(self, plaintext: bytes) -> str: ...

    def is_destroyed(self, project: str | None, subject: str) -> bool: ...


_source: KeySource | None = None
_lookup_allowed: ContextVar[bool] = ContextVar("ankka_personal_lookup", default=False)


def install(source: KeySource | None) -> None:
    """The process's keys: set once by the server when it starts, or by a test."""
    global _source
    _source = source


def source() -> KeySource:
    if _source is None:
        raise PersonalFieldError(
            "no keyring is available here: a personal field is written and read only inside a service "
            "the runtime hosts, or a test that installs a key source",
            "UNAVAILABLE",
        )
    return _source


@contextmanager
def allowing_lookup() -> Iterator[None]:
    """Around a view's row write: the one place a lookup token is written."""
    token = _lookup_allowed.set(True)
    try:
        yield
    finally:
        _lookup_allowed.reset(token)


def lookup_allowed() -> bool:
    return _lookup_allowed.get()


class SidecarKeys:
    """The keys of a process behind the sidecar: fetched from it on a miss with one blocking call,
    held until the expiry it states, and dropped when it says the subject is erased."""

    def __init__(
        self, address: str | Callable[[], str], max_entries: int = 10_000, clock: Callable[[], float] = time.monotonic
    ) -> None:
        # The address may still change until the first fetch: the integration testkit learns the
        # sidecar's port only after starting it.
        self._address = address if callable(address) else (lambda: address)
        self._stub_cache: Any = None
        self._lock = threading.Lock()
        self._keys: OrderedDict[tuple[str, str], tuple[KeyAnswer, float]] = OrderedDict()
        self._tokens: OrderedDict[bytes, str] = OrderedDict()
        self._destroyed: set[tuple[str, str]] = set()
        self._max = max_entries
        self._clock = clock
        self._own: str | None = None
        self._listening = False

    @property
    def _stub(self) -> Any:
        if self._stub_cache is None:
            import grpc

            from ankka._proto.ankka.protocol.v1 import client_pb2_grpc

            self._stub_cache = client_pb2_grpc.ClientStub(grpc.insecure_channel(self._address()))  # type: ignore[no-untyped-call]
        return self._stub_cache

    @property
    def own_project(self) -> str | None:
        return self._own

    def is_destroyed(self, project: str | None, subject: str) -> bool:
        return (project or self._own or "", subject) in self._destroyed

    def destroyed(self, project: str, subject: str) -> None:
        with self._lock:
            self._destroyed.add((project, subject))
            self._keys.pop((project, subject), None)

    def key(self, project: str | None, subject: str, create: bool) -> KeyAnswer:
        from ankka._proto.ankka.protocol.v1 import client_pb2

        self._listen()
        wanted = project or self._own
        if wanted is not None:
            if (wanted, subject) in self._destroyed:
                return KeyAnswer("destroyed", wanted)
            with self._lock:
                held = self._keys.get((wanted, subject))
                if held is not None and held[1] > self._clock():
                    self._keys.move_to_end((wanted, subject))
                    return held[0]
        try:
            answer = self._stub.FetchSubjectKey(
                client_pb2.KeyFetch(subject=subject, project=project or "", create=create), timeout=30
            )
        except Exception as e:  # noqa: BLE001 - every failure to reach the runtime is an outage
            if "UNIMPLEMENTED" in str(e):
                return KeyAnswer("unavailable", wanted or "", reason="the runtime is older than protocol 1.15 and holds no keys")
            return KeyAnswer("unavailable", wanted or "", reason=f"the runtime could not be reached: {e}")
        which = answer.WhichOneof("out")
        if which == "key":
            got = KeyAnswer("key", answer.key.project, key=bytes(answer.key.key))
            if project is None and self._own is None:
                self._own = answer.key.project
            with self._lock:
                self._keys[(got.project, subject)] = (got, self._clock() + answer.key.expires_millis / 1000)
                while len(self._keys) > self._max:
                    self._keys.popitem(last=False)
            return got
        if which == "destroyed":
            self.destroyed(answer.destroyed.project, subject)
            if project is None and self._own is None:
                self._own = answer.destroyed.project
            return KeyAnswer("destroyed", answer.destroyed.project)
        if which == "refused":
            r = answer.refused
            if project is None and self._own is None and r.project:
                self._own = r.project
            return KeyAnswer("unavailable" if r.unavailable else "refused", r.project, reason=r.reason)
        return KeyAnswer("unavailable", wanted or "", reason="the runtime answered nothing")

    def lookup_token(self, plaintext: bytes) -> str:
        from ankka._proto.ankka.protocol.v1 import client_pb2

        with self._lock:
            held = self._tokens.get(plaintext)
        if held is not None:
            return held
        reply = self._stub.LookupToken(client_pb2.LookupTokenRequest(plaintext=plaintext), timeout=30)
        if reply.WhichOneof("result") == "error":
            raise PersonalFieldError(reply.error.message, "UNAVAILABLE")
        with self._lock:
            self._tokens[plaintext] = reply.token
            while len(self._tokens) > self._max:
                self._tokens.popitem(last=False)
        return str(reply.token)

    def _listen(self) -> None:
        """Hears every subject the runtime is told is erased, on a thread of its own, from the first
        fetch on; reconnecting after a pause when the stream ends."""
        if self._listening:
            return
        self._listening = True

        def run() -> None:
            from ankka._proto.ankka.protocol.v1 import payload_pb2

            while True:
                try:
                    for d in self._stub.SubjectKeyEvents(payload_pb2.Empty()):
                        self.destroyed(d.project, d.subject)
                except Exception:  # noqa: BLE001 - a closed stream is reopened
                    pass
                # Whatever was cached while nobody listened may have been erased since.
                with self._lock:
                    self._keys.clear()
                time.sleep(1)

        threading.Thread(target=run, name="ankka-subject-key-events", daemon=True).start()


def install_if_absent(keys: KeySource) -> None:
    """What the server does at start: the runtime's keys, unless a test installed its own."""
    if _source is None:
        install(keys)


class FixedKeys:
    """Keys a test hands over: one key for every subject of ``project``, nothing destroyed but what
    it is told. For unit tests of a codec, never for a running service."""

    def __init__(self, project: str, key: bytes, lookup_key: bytes = b"", destroyed: set[str] | None = None) -> None:
        self._project = project
        self._key = key
        self._lookup = lookup_key
        self._gone = set(destroyed or ())
        self.other_projects: dict[str, bytes] = {}

    @property
    def own_project(self) -> str | None:
        return self._project

    def key(self, project: str | None, subject: str, create: bool) -> KeyAnswer:
        project = project or self._project
        if subject in self._gone and project == self._project:
            return KeyAnswer("destroyed", project)
        if project == self._project:
            return KeyAnswer("key", project, key=self._key)
        if project in self.other_projects:
            return KeyAnswer("key", project, key=self.other_projects[project])
        return KeyAnswer("refused", project, reason="no grant")

    def lookup_token(self, plaintext: bytes) -> str:
        import hashlib
        import hmac

        return hmac.new(self._lookup, plaintext, hashlib.sha256).hexdigest()

    def is_destroyed(self, project: str | None, subject: str) -> bool:
        return (project or self._project) == self._project and subject in self._gone

    def erase(self, subject: str) -> None:
        self._gone.add(subject)

