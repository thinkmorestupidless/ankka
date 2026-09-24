"""Every fixture in ``proto/fixtures`` (copied from ``protocol/fixtures``) decodes to its stated
value with the codec its manifest and content type select, and re-encodes to the same bytes.

A fixture with no matching codec is a failure, never a skip: that is the whole point.
"""

from __future__ import annotations

import base64
import json
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any

import pytest

from ankka import codec
from ankka.codec import Codec, Done

FIXTURES = Path(__file__).resolve().parent.parent / "proto" / "fixtures"
if not FIXTURES.is_dir():  # before `uv run proto` has copied them in, read the source directly
    FIXTURES = Path(__file__).resolve().parents[3] / "protocol" / "fixtures"


# ── The shapes the fixtures were generated from (EncodingShapes in modules/core) ──


@dataclass(frozen=True)
class LineItem:
    productId: str
    name: str
    quantity: int


@dataclass(frozen=True)
class ShoppingCart:
    items: list[LineItem]
    checkedOut: bool


@dataclass(frozen=True)
class ItemAdded:
    item: LineItem


@dataclass(frozen=True)
class ItemRemoved:
    productId: str


@dataclass(frozen=True)
class CheckedOut:
    pass


ShoppingCartEvent = ItemAdded | ItemRemoved | CheckedOut


@dataclass(frozen=True)
class Step:
    name: str
    order: int


@dataclass(frozen=True)
class Plan:
    title: str
    steps: list[Step]
    labels: dict[str, str]


class Status(Enum):
    Ready = "Ready"
    Failed = "Failed"


@dataclass(frozen=True)
class Service:
    name: str
    createdAt: datetime
    owner: str | None
    note: str | None
    status: Status


@dataclass(frozen=True)
class Applied:
    generation: int
    at: datetime | None


ServiceEvent = Applied  # a one-case sum type is still a sum type on the wire


@dataclass(frozen=True)
class Numbers:
    big: int
    half: float
    tenBillion: float
    tenth: float
    negative: int


@dataclass(frozen=True)
class Tree:
    label: str
    children: list["Tree"]


JSON_SHAPES: dict[str, Any] = {
    "shopping-cart": ShoppingCart,
    "shopping-cart-event": ShoppingCartEvent,
    "plan": Plan,
    "service": Service,
    "service-event": Applied | None,  # the Union marker: one case, discriminated
    "status": Status,
    "numbers": Numbers,
    "tree": Tree,
}


def _codec_for(manifest: str, content_type: str) -> Codec[Any]:
    if content_type == codec.JSON:
        shape = JSON_SHAPES.get(manifest)
        if shape is None:
            pytest.fail(f"no shape declared for JSON manifest {manifest!r}; add it to JSON_SHAPES")
        if manifest == "service-event":
            # A single-case sum type: declare it as a Union so the discriminator is written.
            return codec.json_codec(Applied | CheckedOut, manifest)
        return codec.json_codec(shape, manifest)
    c = codec.primitive_for(manifest)
    if c is None:
        pytest.fail(f"no codec for manifest {manifest!r} ({content_type})")
    return c


def _neutral(value: Any, content_type: str) -> Any:
    """A decoded value in the fixture's language-neutral JSON form."""
    if content_type == codec.JSON:
        return codec.to_json_value(value)
    if isinstance(value, Done) or value is None:
        return None
    if isinstance(value, bytes):
        return base64.b64encode(value).decode("ascii")
    if hasattr(value, "total_seconds"):
        return int(value.total_seconds() * 1000)
    return value


@pytest.mark.parametrize("path", sorted(FIXTURES.glob("*.json")), ids=lambda p: p.stem)
def test_fixture_round_trips(path: Path) -> None:
    fixture = json.loads(path.read_text())
    manifest, content_type = fixture["manifest"], fixture["content_type"]
    data = base64.b64decode(fixture["bytes_base64"])
    c = _codec_for(manifest, content_type)
    assert c.manifest == manifest
    assert c.content_type == content_type
    decoded = c.decode(data)
    expected = fixture["value"]
    if content_type == codec.JSON:
        # Compare as JSON documents (the neutral form is JSON); then as bytes.
        assert codec.to_json_value(decoded, _declared(manifest)) == expected
    else:
        assert _neutral(decoded, content_type) == expected
    assert c.encode(decoded) == data, f"re-encoding {path.stem} changed the bytes"


def _declared(manifest: str) -> Any:
    return Applied | CheckedOut if manifest == "service-event" else JSON_SHAPES[manifest]


def test_no_fixture_is_missing() -> None:
    assert any(FIXTURES.glob("*.json")), f"no fixtures under {FIXTURES}; run the core suite first"
