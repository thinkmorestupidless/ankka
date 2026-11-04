"""Personal fields (feature 042): a value about one data subject, never held in the clear.

Mark a field ``Personal[T]`` and build it with ``Personal.of(DataSubject("player/8c1f"), value)``.
Written by a component's codec (into a journal, a state, a row, a message) it is the personal
envelope of ``protocol/ENCODING.md``, sealed with AES-256-GCM under the data subject's key; read back
it is ``Present``, or ``Erased`` once the subject's key is destroyed. Decoding never raises for an
erased subject. Where no codec is writing for a store (an endpoint's reply or body) it is the edge
form, ``{"subject": s, "value": v}`` or ``{"subject": s}``.

Its textual form is ``Personal(<subject>)`` and never the value, so a log line holds no personal data.
"""

from __future__ import annotations

import base64
import contextlib
import os
import re
import struct
from collections.abc import Iterator
from contextvars import ContextVar
from dataclasses import dataclass
from typing import Any, Generic, TypeVar, overload

from ankka.client import CommandError
from ankka.effects.common import Error, ErrorCode

T = TypeVar("T")
D = TypeVar("D")

RULE = "a data subject is 1 to 253 characters from letters, digits, '.', '_', '-', '/' and ':'"
"""The rule, in the words every SDK refuses with (``protocol/fixtures/personal/subjects.json``)."""

_PATTERN = re.compile(r"[A-Za-z0-9._/:-]{1,253}")

VERSION = 1
"""The envelope's version; a reader refuses one it does not know, as corrupt."""

ERROR_KEY = "personal"
"""The ``details`` key of an error a personal field fails with, and its values below."""
UNAVAILABLE = "unavailable"
ERASED = "erased"
CORRUPT = "corrupt"

PERSONAL_SINCE = (1, 23)
"""The protocol version whose runtimes hand a process its subject keys and run its erasure handler."""

NONCE_BYTES = 12
TAG_BYTES = 16


class DataSubject(str):
    """Who a personal field is about: 1 to 253 characters from letters, digits, ``.``, ``_``,
    ``-``, ``/`` and ``:``. Constructing one that breaks the rule raises ``ValueError``."""

    __slots__ = ()

    def __new__(cls, value: str) -> DataSubject:
        problem = subject_problem(value)
        if problem is not None:
            raise ValueError(problem)
        return super().__new__(cls, value)


def subject_problem(value: object) -> str | None:
    """Why ``value`` is not a data subject, or None when it is one."""
    if isinstance(value, str) and _PATTERN.fullmatch(value) is not None:
        return None
    return f"'{value}' is not a data subject: {RULE}"


class Personal(Generic[T]):
    """A personal field: ``Present(subject, value)`` or ``Erased(subject)``. ``Erased`` is made by
    decoding, once the subject's key is destroyed; a handler builds ``Personal.of(subject, value)``."""

    __slots__ = ()

    subject: DataSubject

    @staticmethod
    def of(subject: DataSubject | str, value: T) -> Personal[T]:
        """The field holding ``value`` about ``subject``; a string that is no data subject raises."""
        return Present(subject if isinstance(subject, DataSubject) else DataSubject(subject), value)

    @overload
    def or_else(self, default: T) -> T: ...

    @overload
    def or_else(self, default: D) -> T | D: ...

    def or_else(self, default: Any) -> Any:
        """The value, or ``default`` once erased."""
        return self.value if isinstance(self, Present) else default

    @property
    def is_erased(self) -> bool:
        return isinstance(self, Erased)

    def __repr__(self) -> str:
        return f"Personal({self.subject})"

    __str__ = __repr__

    def __format__(self, spec: str) -> str:
        return format(repr(self), spec)


@dataclass(frozen=True, repr=False, slots=True)
class Present(Personal[T]):
    subject: DataSubject
    value: T

    def __post_init__(self) -> None:
        if not isinstance(self.subject, DataSubject):
            object.__setattr__(self, "subject", DataSubject(self.subject))

    def __repr__(self) -> str:
        return f"Personal({self.subject})"

    __str__ = __repr__


@dataclass(frozen=True, repr=False, slots=True)
class Erased(Personal[Any]):
    subject: DataSubject

    def __post_init__(self) -> None:
        if not isinstance(self.subject, DataSubject):
            object.__setattr__(self, "subject", DataSubject(self.subject))

    def __repr__(self) -> str:
        return f"Personal({self.subject})"

    __str__ = __repr__


def is_personal_type(tp: Any) -> bool:
    """Whether a declared type is ``Personal[...]`` (or ``Present``/``Erased``, bare or not)."""
    from typing import get_origin

    target = get_origin(tp) or tp
    return isinstance(target, type) and issubclass(target, Personal)


def mentions_personal(tp: Any, seen: set[int] | None = None) -> bool:
    """Whether a declared type holds a personal field at any depth: what makes a service need 1.23."""
    import dataclasses
    from typing import get_args, get_type_hints

    seen = set() if seen is None else seen
    if id(tp) in seen:
        return False
    seen.add(id(tp))
    if is_personal_type(tp):
        return True
    if any(mentions_personal(arg, seen) for arg in get_args(tp)):
        return True
    if isinstance(tp, type) and dataclasses.is_dataclass(tp):
        try:
            hints = get_type_hints(tp)
        except Exception:
            return False
        return any(mentions_personal(h, seen) for h in hints.values())
    return False


# ── The errors of every SDK's table ─────────────────────────────────────────


class KeyringUnavailable(CommandError):
    """No keyring is installed, it could not be reached, or it did not answer in time: a personal
    field can be neither written nor read. Never read as erased."""

    def __init__(self, message: str) -> None:
        super().__init__(Error(message, ErrorCode.UNAVAILABLE, {ERROR_KEY: UNAVAILABLE}))


class SubjectErased(CommandError):
    """A write for a data subject whose key is destroyed: nothing was written."""

    def __init__(self, subject: str) -> None:
        super().__init__(
            Error(f"the data subject '{subject}' is erased; nothing was written", ErrorCode.CONFLICT, {ERROR_KEY: ERASED})
        )


class CorruptEnvelope(CommandError):
    """An envelope whose tag does not verify (another subject, project, manifest or key), whose
    version is unknown, or whose ciphertext is not base64. Never read as erased."""

    def __init__(self, subject: str, why: str) -> None:
        super().__init__(
            Error(f"the personal envelope of '{subject}' is corrupt: {why}", ErrorCode.INTERNAL, {ERROR_KEY: CORRUPT})
        )


# ── The scope a codec writes under ───────────────────────────────────────────


@dataclass(frozen=True)
class Scope:
    """The manifest a component's codec is encoding or decoding under, for one call: bound into an
    envelope's associated data. No scope is the edge, where the edge form is written instead."""

    manifest: str


_scope: ContextVar[Scope | None] = ContextVar("ankka_personal_scope", default=None)
_edge: ContextVar[bool] = ContextVar("ankka_personal_edge", default=False)
_nonce: ContextVar[bytes | None] = ContextVar("ankka_personal_nonce", default=None)


def current_scope() -> Scope | None:
    """The scope bound here, or None at the edge."""
    return None if _edge.get() else _scope.get()


@contextlib.contextmanager
def within(manifest: str) -> Iterator[None]:
    """Runs the body under ``manifest``, unless it runs at the edge (``at_edge``)."""
    token = _scope.set(Scope(manifest))
    try:
        yield
    finally:
        _scope.reset(token)


@contextlib.contextmanager
def at_edge() -> Iterator[None]:
    """Runs the body as at the edge (an endpoint's reply or body): personal fields in their edge
    form, whatever codec runs inside."""
    token = _edge.set(True)
    try:
        yield
    finally:
        _edge.reset(token)


@contextlib.contextmanager
def fixed_nonce(nonce: bytes) -> Iterator[None]:
    """For the fixtures' tests only: every envelope sealed in the body uses ``nonce``. A nonce used
    twice under one key breaks GCM, so nothing else may call this."""
    if len(nonce) != NONCE_BYTES:
        raise ValueError(f"a nonce is {NONCE_BYTES} bytes, was {len(nonce)}")
    token = _nonce.set(nonce)
    try:
        yield
    finally:
        _nonce.reset(token)


# ── The cipher ──────────────────────────────────────────────────────────────


def associated_data(subject: str, project: str, manifest: str) -> bytes:
    """Each part as a 4-byte big-endian length and its UTF-8 bytes: subject, project, manifest."""
    out = b""
    for part in (subject, project, manifest):
        raw = part.encode("utf-8")
        out += struct.pack(">I", len(raw)) + raw
    return out


def seal(key: bytes, plaintext: bytes, ad: bytes) -> bytes:
    """``nonce ‖ ciphertext ‖ tag``, with a random nonce unless a test fixed one."""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    nonce = _nonce.get() or os.urandom(NONCE_BYTES)
    return nonce + AESGCM(key).encrypt(nonce, plaintext, ad)


def open_sealed(key: bytes, boxed: bytes, ad: bytes) -> bytes | None:
    """The plaintext, or None when the tag does not verify under this key and associated data."""
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    if len(boxed) < NONCE_BYTES + TAG_BYTES:
        return None
    try:
        return AESGCM(key).decrypt(boxed[:NONCE_BYTES], boxed[NONCE_BYTES:], ad)
    except InvalidTag:
        return None


def b64encode(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def b64decode(text: str) -> bytes | None:
    try:
        return base64.b64decode(text, validate=True)
    except (ValueError, TypeError):
        return None


# ── Envelopes in, and personal values out, for the keyring's prefetch ───────


def envelopes_in(document: Any) -> Iterator[tuple[str, str]]:
    """``(project, subject)`` of every sealed envelope (one with ``c``) in a JSON document."""
    stack = [document]
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            if "$personal" in node:
                project, subject = node.get("project"), node.get("subject")
                if isinstance(project, str) and isinstance(subject, str) and "c" in node:
                    yield project, subject
                continue
            stack.extend(node.values())
        elif isinstance(node, list):
            stack.extend(node)


def present_in(value: Any) -> Iterator[Present[Any]]:
    """Every ``Present`` in a value at any depth: dataclasses (an effect is one), mappings and
    collections. A callable (an effect's computed reply) is not entered."""
    import dataclasses

    seen: set[int] = set()
    stack: list[Any] = [value]
    while stack:
        node = stack.pop()
        if node is None or isinstance(node, (str, bytes, bytearray, int, float, bool)):
            continue
        if id(node) in seen:
            continue
        seen.add(id(node))
        if isinstance(node, Present):
            yield node
            stack.append(node.value)
        elif isinstance(node, Personal):
            continue
        elif isinstance(node, dict):
            stack.extend(node.values())
        elif isinstance(node, (list, tuple, set, frozenset)):
            stack.extend(node)
        elif dataclasses.is_dataclass(node) and not isinstance(node, type):
            stack.extend(getattr(node, f.name) for f in dataclasses.fields(node))


