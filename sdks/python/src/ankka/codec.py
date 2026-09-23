"""Codecs: what the bytes in a payload mean.

Built to ``protocol/ENCODING.md``, which describes what the Scala SDK's default codecs produce
today, so that a journal written by a service in one language is read by the same service in
another. ``tests/test_encoding_fixtures.py`` proves every fixture both ways.

The default for a domain type is :func:`json_codec`: dataclasses become JSON objects with every
field written, a ``Union`` of dataclasses becomes an object with ``"type": "<ClassName>"``, an
``Enum`` becomes ``{"type": "<member name>"}``, ``None`` becomes ``null``. Top-level primitives use
the text and binary encodings from the same document.
"""

from __future__ import annotations

import base64
import dataclasses
import enum
import json
import types
import typing
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from typing import Any, Generic, Protocol, TypeVar, get_args, get_origin, get_type_hints

A = TypeVar("A")

JSON = "application/json"
TEXT = "text/plain"
BINARY = "application/octet-stream"

DISCRIMINATOR = "type"


class Codec(Protocol[A]):
    """Encodes and decodes one type under one manifest."""

    @property
    def manifest(self) -> str: ...

    @property
    def content_type(self) -> str: ...

    def encode(self, value: A) -> bytes: ...

    def decode(self, data: bytes) -> A: ...


class EncodingError(ValueError):
    """A value or document the encoding does not cover."""


@dataclass(frozen=True)
class Done:
    """The reply that says only that a command was handled. Zero bytes on the wire."""


DONE = Done()


# ── JSON ──────────────────────────────────────────────────────────────────────


def _is_dataclass_type(tp: Any) -> bool:
    return isinstance(tp, type) and dataclasses.is_dataclass(tp)


def _union_members(tp: Any) -> tuple[Any, ...] | None:
    origin = get_origin(tp)
    if origin is typing.Union or origin is types.UnionType:
        return get_args(tp)
    return None


def _strip_optional(tp: Any) -> tuple[Any, bool]:
    members = _union_members(tp)
    if members is None:
        return tp, False
    rest = tuple(m for m in members if m is not type(None))
    if len(rest) == len(members):
        return tp, False
    if len(rest) == 1:
        return rest[0], True
    return typing.Union[rest], True  # noqa: UP007 - a runtime Union of the remaining members


def _format_instant(value: datetime) -> str:
    """ISO-8601 in UTC with a Z suffix and 0, 3, 6 or 9 fractional digits, as jsoniter writes."""
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    value = value.astimezone(UTC)
    base = value.strftime("%Y-%m-%dT%H:%M:%S")
    micros = value.microsecond
    if micros == 0:
        return base + "Z"
    if micros % 1000 == 0:
        return f"{base}.{micros // 1000:03d}Z"
    return f"{base}.{micros:06d}Z"


def _parse_instant(text: str) -> datetime:
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    # Python parses at most 6 fractional digits; jsoniter may write 9.
    if "." in text:
        head, tail = text.split(".", 1)
        digits = ""
        i = 0
        while i < len(tail) and tail[i].isdigit():
            digits += tail[i]
            i += 1
        rest = tail[i:]
        digits = (digits + "000000")[:6]
        text = f"{head}.{digits}{rest}"
    return datetime.fromisoformat(text)


def _format_duration(value: timedelta) -> str:
    """ISO-8601 duration as java.time.Duration writes it: PTnHnMn.nS."""
    total = value.total_seconds()
    sign = "-" if total < 0 else ""
    total = abs(total)
    hours, rem = divmod(int(total), 3600)
    minutes, seconds = divmod(rem, 60)
    frac = total - int(total)
    parts = "PT"
    if hours:
        parts += f"{hours}H"
    if minutes:
        parts += f"{minutes}M"
    if seconds or frac or parts == "PT":
        s = f"{seconds + frac:.9f}".rstrip("0").rstrip(".") if frac else str(seconds)
        parts += f"{s}S"
    return sign + parts


def _parse_duration(text: str) -> timedelta:
    import re

    m = re.fullmatch(r"(-?)P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?", text)
    if m is None:
        raise EncodingError(f"not an ISO-8601 duration: {text!r}")
    sign, days, hours, minutes, seconds = m.groups()
    td = timedelta(
        days=int(days or 0),
        hours=int(hours or 0),
        minutes=int(minutes or 0),
        seconds=float(seconds or 0),
    )
    return -td if sign else td


def to_json_value(value: Any, tp: Any = None) -> Any:
    """A domain value as the JSON-compatible structure ENCODING.md prescribes."""
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    if isinstance(value, enum.Enum):
        return {DISCRIMINATOR: value.name}
    if isinstance(value, int) and tp is float:
        return float(value)
    if isinstance(value, (int, float, str)):
        return value
    if isinstance(value, datetime):
        return _format_instant(value)
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, timedelta):
        return _format_duration(value)
    if isinstance(value, bytes):
        return base64.b64encode(value).decode("ascii")
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        hints = get_type_hints(type(value))
        obj: dict[str, Any] = {}
        # A member of a sum type carries its discriminator; a plain record does not. The
        # declared type tells them apart: a Union at this position means a sum type.
        if tp is not None and _union_members(_strip_optional(tp)[0]) is not None:
            obj[DISCRIMINATOR] = type(value).__name__
        for f in dataclasses.fields(value):
            obj[f.name] = to_json_value(getattr(value, f.name), hints.get(f.name))
        return obj
    if isinstance(value, Mapping):
        return {str(k): to_json_value(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        inner = None
        if tp is not None:
            args = get_args(_strip_optional(tp)[0])
            inner = args[0] if args else None
        return [to_json_value(v, inner) for v in value]
    raise EncodingError(f"cannot encode a value of type {type(value).__name__}")


def from_json_value(value: Any, tp: Any) -> Any:
    """The inverse of :func:`to_json_value` for a declared type."""
    tp, optional = _strip_optional(tp)
    if value is None:
        if optional or tp is type(None) or tp is Any:
            return None
        raise EncodingError(f"null where a {tp} was required")
    if tp is Any:
        return value
    members = _union_members(tp)
    if members is not None:
        if not isinstance(value, dict) or DISCRIMINATOR not in value:
            raise EncodingError(f"a sum type needs a {DISCRIMINATOR!r} field: {value!r}")
        name = value[DISCRIMINATOR]
        for member in members:
            if isinstance(member, type) and member.__name__ == name:
                return _decode_dataclass(value, member)
        raise EncodingError(f"unknown {DISCRIMINATOR} {name!r}; expected one of {[m.__name__ for m in members]}")
    if isinstance(tp, type) and issubclass(tp, enum.Enum):
        if isinstance(value, dict) and DISCRIMINATOR in value:
            return tp[value[DISCRIMINATOR]]
        if isinstance(value, str):
            return tp[value]
        raise EncodingError(f"not an enumeration value: {value!r}")
    if _is_dataclass_type(tp):
        if not isinstance(value, dict):
            raise EncodingError(f"expected an object for {tp.__name__}, got {value!r}")
        return _decode_dataclass(value, tp)
    if tp is bool:
        if not isinstance(value, bool):
            raise EncodingError(f"expected a boolean, got {value!r}")
        return value
    if tp is int:
        if isinstance(value, bool) or not isinstance(value, int):
            raise EncodingError(f"expected an integer, got {value!r}")
        return value
    if tp is float:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise EncodingError(f"expected a number, got {value!r}")
        return float(value)
    if tp is str:
        if not isinstance(value, str):
            raise EncodingError(f"expected a string, got {value!r}")
        return value
    if tp is datetime:
        return _parse_instant(value)
    if tp is date:
        return date.fromisoformat(value)
    if tp is timedelta:
        return _parse_duration(value)
    if tp is bytes:
        return base64.b64decode(value)
    origin = get_origin(tp)
    if origin in (list, Sequence, typing.Sequence):
        (inner,) = get_args(tp) or (Any,)
        return [from_json_value(v, inner) for v in value]
    if origin is tuple:
        args = get_args(tp)
        inner = args[0] if args else Any
        return tuple(from_json_value(v, inner) for v in value)
    if origin in (set, frozenset):
        (inner,) = get_args(tp) or (Any,)
        return origin(from_json_value(v, inner) for v in value)
    if origin in (dict, Mapping, typing.Mapping):
        args = get_args(tp)
        inner = args[1] if len(args) == 2 else Any
        return {k: from_json_value(v, inner) for k, v in value.items()}
    raise EncodingError(f"cannot decode into {tp!r}")


def _decode_dataclass(obj: dict[str, Any], cls: type) -> Any:
    hints = get_type_hints(cls)
    kwargs: dict[str, Any] = {}
    for f in dataclasses.fields(cls):
        if f.name in obj:
            kwargs[f.name] = from_json_value(obj[f.name], hints[f.name])
        elif f.default is not dataclasses.MISSING or f.default_factory is not dataclasses.MISSING:
            continue
        elif _strip_optional(hints[f.name])[1]:
            kwargs[f.name] = None  # an absent optional reads as none (ENCODING.md: lenient reads)
        else:
            raise EncodingError(f"{cls.__name__} is missing the field {f.name!r}")
    return cls(**kwargs)


def _scala_double(d: float) -> str:
    """A JSON number as jsoniter writes a Scala Double: ``1.5``, ``0.1``, ``1.0E10`` — the shortest
    repr that round-trips, in Scala's ``Double.toString`` form for large and small magnitudes."""
    if d != d or d in (float("inf"), float("-inf")):
        raise EncodingError("NaN and infinities are not JSON")
    if d == int(d) and abs(d) < 1e7:
        return f"{int(d)}.0" if not (d == 0 and str(d).startswith("-")) else "-0.0"
    r = repr(d)
    if "e" in r:
        mantissa, exp = r.split("e")
        if "." not in mantissa:
            mantissa += ".0"
        return f"{mantissa}E{int(exp)}"
    if abs(d) >= 1e7 or (abs(d) < 1e-3 and d != 0):
        mantissa, exp = f"{d:.17e}".split("e")
        mantissa = mantissa.rstrip("0")
        # Shortest mantissa that still round-trips.
        for digits in range(1, 18):
            candidate = f"{d:.{digits}e}"
            if float(candidate) == d:
                mantissa, exp = candidate.split("e")
                break
        mantissa = mantissa.rstrip("0")
        if mantissa.endswith("."):
            mantissa += "0"
        return f"{mantissa}E{int(exp)}"
    return r


def write_json(value: Any) -> str:
    """Compact JSON over the structure :func:`to_json_value` returns, with numbers written the way
    the Scala codecs write them so a re-encoded fixture is byte-identical."""
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return _scala_double(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, dict):
        return "{" + ",".join(f"{json.dumps(str(k), ensure_ascii=False)}:{write_json(v)}" for k, v in value.items()) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(write_json(v) for v in value) + "]"
    raise EncodingError(f"not a JSON value: {value!r}")


@dataclass(frozen=True)
class JsonCodec(Generic[A]):
    cls: Any
    manifest: str
    content_type: str = JSON

    def encode(self, value: A) -> bytes:
        return write_json(to_json_value(value, self.cls)).encode("utf-8")

    def decode(self, data: bytes) -> A:
        return typing.cast(A, from_json_value(json.loads(data.decode("utf-8")), self.cls))


def json_codec(cls: Any, manifest: str) -> JsonCodec[Any]:
    """The default codec for a domain type: a dataclass, a Union of dataclasses, an Enum, or any
    JSON-shaped combination of them, lists, dicts and scalars."""
    return JsonCodec(cls, manifest)


# ── Text and binary primitives ───────────────────────────────────────────────


@dataclass(frozen=True)
class _Text(Generic[A]):
    manifest: str
    parse: Callable[[str], A]
    render: Callable[[A], str]
    content_type: str = TEXT

    def encode(self, value: A) -> bytes:
        return self.render(value).encode("utf-8")

    def decode(self, data: bytes) -> A:
        return self.parse(data.decode("utf-8"))


def _render_bool(b: bool) -> str:
    return "true" if b else "false"


def _parse_bool(s: str) -> bool:
    if s == "true":
        return True
    if s == "false":
        return False
    raise EncodingError(f"not a boolean: {s!r}")


def _render_double(d: float) -> str:
    # Scala's Double.toString: "1.5", "1.0", "1.0E10". Python's repr is "1.5", "1.0", "10000000000.0".
    # Readers accept either; writers follow Scala where it matters for fixtures.
    if d != d or d in (float("inf"), float("-inf")):
        return {float("inf"): "Infinity", float("-inf"): "-Infinity"}.get(d, "NaN")
    r = repr(d)
    if "e" in r or "E" in r:
        mantissa, exp = r.lower().split("e")
        if "." not in mantissa:
            mantissa += ".0"
        return f"{mantissa}E{int(exp)}"
    if abs(d) >= 1e7 and r.endswith(".0"):
        digits = r[:-2]
        return f"{digits[0]}.{digits[1:].rstrip('0') or '0'}E{len(digits) - 1}"
    return r


STRING: Codec[str] = _Text("string", str, str)
INT: Codec[int] = _Text("int", int, str)
LONG: Codec[int] = _Text("long", int, str)
SHORT: Codec[int] = _Text("short", int, str)
BYTE: Codec[int] = _Text("byte", int, str)
DOUBLE: Codec[float] = _Text("double", float, _render_double)
FLOAT: Codec[float] = _Text("float", float, _render_double)
BOOLEAN: Codec[bool] = _Text("boolean", _parse_bool, _render_bool)
DURATION_MILLIS: Codec[timedelta] = _Text(
    "duration-millis",
    lambda s: timedelta(milliseconds=int(s)),
    lambda d: str(int(d.total_seconds() * 1000)),
)


@dataclass(frozen=True)
class _Empty(Generic[A]):
    manifest: str
    value: A
    content_type: str = BINARY

    def encode(self, value: A) -> bytes:
        return b""

    def decode(self, data: bytes) -> A:
        return self.value


DONE_CODEC: Codec[Done] = _Empty("done", DONE)
UNIT: Codec[None] = _Empty("unit", None)


@dataclass(frozen=True)
class _Bytes:
    manifest: str = "bytes"
    content_type: str = BINARY

    def encode(self, value: bytes) -> bytes:
        return value

    def decode(self, data: bytes) -> bytes:
        return data


BYTES: Codec[bytes] = _Bytes()


@dataclass(frozen=True)
class OptionCodec(Generic[A]):
    """Top-level ``Option[A]``: zero bytes for none, ``0x01`` then A's bytes otherwise."""

    inner: Codec[A]
    content_type: str = BINARY

    @property
    def manifest(self) -> str:
        return f"option[{self.inner.manifest}]"

    def encode(self, value: A | None) -> bytes:
        return b"" if value is None else b"\x01" + self.inner.encode(value)

    def decode(self, data: bytes) -> A | None:
        return None if not data else self.inner.decode(data[1:])


def option_codec(inner: Codec[A]) -> OptionCodec[A]:
    return OptionCodec(inner)


_PRIMITIVES: dict[str, Codec[Any]] = {
    c.manifest: c
    for c in (STRING, INT, LONG, SHORT, BYTE, DOUBLE, FLOAT, BOOLEAN, DURATION_MILLIS, DONE_CODEC, UNIT, BYTES)
}


def primitive_for(manifest: str) -> Codec[Any] | None:
    """The primitive codec a manifest names, including ``option[...]`` of one, or None."""
    if manifest in _PRIMITIVES:
        return _PRIMITIVES[manifest]
    if manifest.startswith("option[") and manifest.endswith("]"):
        inner = primitive_for(manifest[7:-1])
        return None if inner is None else option_codec(inner)
    return None


def default_codec_for(tp: Any) -> Codec[Any]:
    """What ``invoke(item)`` and a handler's typed reply use when no codec is given."""
    if tp is Done:
        return DONE_CODEC
    if tp is type(None) or tp is None:
        return UNIT
    if tp is str:
        return STRING
    if tp is int:
        return INT
    if tp is float:
        return DOUBLE
    if tp is bool:
        return BOOLEAN
    if tp is bytes:
        return BYTES
    if tp is timedelta:
        return DURATION_MILLIS
    inner, optional = _strip_optional(tp)
    if optional:
        return option_codec(default_codec_for(inner))
    name = getattr(tp, "__name__", None) or str(tp)
    return json_codec(tp, name)
