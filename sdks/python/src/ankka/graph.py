"""Publish a service's entities as a graph.

A ``GraphConsumer`` reads a source's changes like any consumer and says which elements of a graph
each change leaves in which state: this node with these labels and properties, this edge between
these two ids, this one gone. Each element is published as a *graph delta* under the contract
``ankka.graph-delta.v1`` — the element's whole state at a version — in a record whose key is the
element's own, ``node:<id>`` or ``edge:<id>``. The author writes no JSON, no key and no version:
the version is the sequence number of the change being handled unless one is stated.

    class CartGraph(GraphConsumer[CartEvent]):
        component_id = "cart-graph"
        source = ShoppingCartEntity
        produces_to = "cart-graph"
        message_codec = ShoppingCartEntity.event_codec

        def on_message(self, event: CartEvent) -> GraphEffect:
            cart_id = self.metadata.subject or ""
            return self.effects.publish([
                self.graph.node(f"cart:{cart_id}", labels=["Cart"], properties={"cartId": cart_id}),
            ])

The rules a writer of deltas keeps are not all checkable here. A delta is an element's whole state,
not a change to it; an element is written by exactly one entity, whose sequence numbers its
versions are; ids are prefixed by kind of thing so they cannot collide. What can be checked is:
an element the reader would refuse is refused where it is built, by a ``RefusedElement`` (a
``ValueError``) naming the fault, and the change is delivered again rather than a delta published
that stalls whatever reads the topic.
"""

from __future__ import annotations

import json
import math
import re
import typing
from collections.abc import Awaitable, Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any, ClassVar, Generic, Literal, TypeVar

from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.codec import JSON, Codec, write_json
from ankka.context import Metadata
from ankka.effects.consumer import ConsumerEffect, Done, Ignore, Message, ProduceAll
from ankka.event_sourced_entity import RegistrationError
from ankka import start_from as _start_from
from ankka.start_from import StartFrom
from ankka.view import _source_pb
from ankka.secrets import HasSecrets
from ankka.services import HasServices

if typing.TYPE_CHECKING:
    from ankka.client import ComponentClient

Src = TypeVar("Src")

SCHEMA_NAME = "ankka.graph-delta.v1"
"""The contract a delta is written under: the payload's manifest and its ``ce-type``."""

Scalar = str | bool | int | float
PropertyValue = Scalar | Sequence[Scalar]

_IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_RESERVED = frozenset({"id", "_version", "_deleted"})
_INT64_MIN, _INT64_MAX = -(2**63), 2**63 - 1


def node_key(id: str) -> str:  # noqa: A002 - `id` is the contract's word
    """The record key of every delta for the node ``id``."""
    return f"node:{id}"


def edge_key(id: str) -> str:  # noqa: A002
    """The record key of every delta for the edge ``id``. Nodes and edges are separate id spaces."""
    return f"edge:{id}"


class RefusedElement(ValueError):
    """An element the reader of a delta topic would refuse, or a result that cannot be published.
    ``why`` names the reason: ``id``, ``endpoints``, ``identifier``, ``reserved``,
    ``property-name``, ``property-value``, ``integer-range``, ``version``, ``duplicate`` or
    ``no-sequence``."""

    def __init__(self, why: str, message: str) -> None:
        super().__init__(message)
        self.why = why


@dataclass(frozen=True)
class Element:
    """A node, an edge, or a tombstone for either: what a handler builds and what ``read`` returns.

    ``kind`` is ``"node"``, ``"edge"`` or ``"tombstone"``; ``element`` is which kind of element it
    describes or marks. ``type``, ``from_id`` and ``to_id`` are set for an edge and for an edge's
    tombstone. ``version`` is None until the handler's result is returned, when an element that
    states none takes the sequence number of the change."""

    kind: Literal["node", "edge", "tombstone"]
    element: Literal["node", "edge"]
    id: str
    version: int | None
    labels: tuple[str, ...] = ()
    type: str | None = None
    from_id: str | None = None
    to_id: str | None = None
    properties: Mapping[str, Any] = field(default_factory=dict)

    @property
    def key(self) -> str:
        """The element key: the record key of every delta for this element."""
        return node_key(self.id) if self.element == "node" else edge_key(self.id)

    def _named(self) -> str:
        return f"the {'tombstone of ' if self.kind == 'tombstone' else ''}{self.element} '{self.id}'"


# ── validation: what the reader would refuse, refused where the element is built ────────────────


def _id(what: str, value: object) -> str:
    if not isinstance(value, str) or not value:
        raise RefusedElement("id", f"{what} must have an id that is a non-empty string, not {value!r}")
    return value


def _endpoint(what: str, name: str, value: object) -> str:
    if not isinstance(value, str) or not value:
        raise RefusedElement("endpoints", f"{what} needs a non-empty {name}, not {value!r}")
    return value


def _identifier(what: str, name: str, value: object) -> str:
    if not isinstance(value, str) or not _IDENTIFIER.fullmatch(value):
        raise RefusedElement("identifier", f"{what} has the {name} {value!r}, which is not an identifier ([A-Za-z_][A-Za-z0-9_]*)")
    return value


def _labels(what: str, value: object) -> tuple[str, ...]:
    if isinstance(value, (str, bytes)) or not isinstance(value, Sequence):
        raise RefusedElement("identifier", f"{what} needs its labels as a list of identifiers, not {value!r}")
    return tuple(_identifier(what, "label", label) for label in value)


def _stated_version(what: str, value: object) -> int | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= _INT64_MAX:
        raise RefusedElement("version", f"{what} states the version {value!r}; a version is a whole number of at least 1 that fits 64 bits")
    return value


def _scalar_kind(what: str, name: str, value: object) -> str:
    """The kind of one scalar: ``string``, ``boolean``, ``integer`` or ``float``. A float with a
    whole value is an integer, because that is how the reader stores it."""
    if isinstance(value, str):
        return "string"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, int):
        if not _INT64_MIN <= value <= _INT64_MAX:
            raise RefusedElement("integer-range", f"{what} has the property '{name}' with {value!r}, an integer that does not fit 64 bits")
        return "integer"
    if isinstance(value, float):
        if not math.isfinite(value):
            raise RefusedElement("property-value", f"{what} has the property '{name}' with {value!r}, which is not a finite number")
        if value == math.floor(value):
            if not _INT64_MIN <= value <= _INT64_MAX:
                raise RefusedElement("integer-range", f"{what} has the property '{name}' with {value!r}, a whole number that does not fit 64 bits")
            return "integer"
        return "float"
    raise RefusedElement("property-value", f"{what} has the property '{name}' with {value!r}; a property is a string, a number, a boolean, or a list of one of those")


def _properties(what: str, value: object) -> dict[str, Any]:
    if value is None:
        return {}
    if not isinstance(value, Mapping):
        raise RefusedElement("property-value", f"{what} needs its properties as a mapping, not {value!r}")
    checked: dict[str, Any] = {}
    for name, item in value.items():
        if not isinstance(name, str):
            raise RefusedElement("property-name", f"{what} has a property named {name!r}; a property's name is a string")
        if name in _RESERVED:
            raise RefusedElement("reserved", f"{what} has a property named '{name}', which belongs to the reader of the graph")
        if isinstance(item, (list, tuple)):
            if not item:
                raise RefusedElement("property-value", f"{what} has the property '{name}' with an empty list")
            kinds = {_scalar_kind(what, name, member) for member in item}
            if len(kinds) != 1:
                raise RefusedElement("property-value", f"{what} has the property '{name}' with a list of more than one kind: {sorted(kinds)}")
            checked[name] = list(item)
        else:
            _scalar_kind(what, name, item)
            checked[name] = item
    return checked


class Graph:
    """Builds elements. ``self.graph`` inside a ``GraphConsumer``; usable on its own in a test."""

    def node(
        self,
        id: str,  # noqa: A002
        *,
        labels: Sequence[str] = (),
        properties: Mapping[str, PropertyValue] | None = None,
        version: int | None = None,
    ) -> Element:
        """The node ``id`` with exactly these labels and properties: its whole state, not a change
        to it. To remove a property, build the node without it."""
        what = f"the node {id!r}"
        return Element("node", "node", _id("a node", id), _stated_version(what, version), _labels(what, labels), properties=_properties(what, properties))

    def edge(
        self,
        id: str,  # noqa: A002
        *,
        type: str,  # noqa: A002
        from_id: str,
        to_id: str,
        properties: Mapping[str, PropertyValue] | None = None,
        version: int | None = None,
    ) -> Element:
        """The edge ``id`` of one type, running from the node ``from_id`` to the node ``to_id``. A
        node it names that nobody has described is created as a placeholder by the reader."""
        what = f"the edge {id!r}"
        checked_id = _id("an edge", id)
        return Element(
            "edge",
            "edge",
            checked_id,
            _stated_version(what, version),
            type=_identifier(what, "type", _endpoint(what, "type", type)),
            from_id=_endpoint(what, "from_id", from_id),
            to_id=_endpoint(what, "to_id", to_id),
            properties=_properties(what, properties),
        )

    def tombstone_node(self, id: str, *, version: int | None = None) -> Element:  # noqa: A002
        """Marks the node ``id`` deleted. It stays in the graph, marked, so an older delta that
        arrives late cannot bring it back."""
        return Element("tombstone", "node", _id("a node's tombstone", id), _stated_version(f"the tombstone of the node {id!r}", version))

    def tombstone_edge(self, id: str, *, type: str, from_id: str, to_id: str, version: int | None = None) -> Element:  # noqa: A002
        """Marks the edge ``id`` deleted. It names the edge's type and endpoints, as the edge did,
        so the reader finds it without searching the graph."""
        what = f"the tombstone of the edge {id!r}"
        checked_id = _id("an edge's tombstone", id)
        return Element(
            "tombstone",
            "edge",
            checked_id,
            _stated_version(what, version),
            type=_identifier(what, "type", _endpoint(what, "type", type)),
            from_id=_endpoint(what, "from_id", from_id),
            to_id=_endpoint(what, "to_id", to_id),
        )


# ── the delta as it is written and read ─────────────────────────────────────────────────────────


def _delta(element: Element) -> dict[str, Any]:
    if element.version is None:
        raise RefusedElement("no-sequence", f"{element._named()} has no version yet; it takes one when its handler's result is returned")
    if element.kind == "node":
        return {"kind": "node", "id": element.id, "version": element.version, "labels": list(element.labels), "properties": dict(element.properties)}
    if element.kind == "edge":
        return {
            "kind": "edge",
            "id": element.id,
            "version": element.version,
            "type": element.type,
            "from": element.from_id,
            "to": element.to_id,
            "properties": dict(element.properties),
        }
    if element.element == "node":
        return {"kind": "tombstone", "element": "node", "id": element.id, "version": element.version}
    return {"kind": "tombstone", "element": "edge", "id": element.id, "version": element.version, "type": element.type, "from": element.from_id, "to": element.to_id}


def _read_version(value: object) -> int:
    whole = isinstance(value, int) or (isinstance(value, float) and math.isfinite(value) and value == math.floor(value))
    if isinstance(value, bool) or not whole or not 0 <= typing.cast(float, value) <= _INT64_MAX:
        raise ValueError("version is not a non-negative integer")
    return int(typing.cast(float, value))


def _read_scalar(name: str, value: object) -> tuple[str, Any]:
    """A property's scalar as the reader of the graph stores it, with its kind."""
    if isinstance(value, str):
        return "string", value
    if isinstance(value, bool):
        return "boolean", value
    if isinstance(value, (int, float)):
        if isinstance(value, float) and not math.isfinite(value):
            raise ValueError(f"property '{name}' is not a scalar or array of scalars")
        if value == math.floor(value):
            if not _INT64_MIN <= value <= _INT64_MAX:
                raise ValueError(f"property '{name}' is not a scalar or array of scalars")
            return "integer", int(value)
        return "float", value
    raise ValueError(f"property '{name}' is not a scalar or array of scalars")


def _read_properties(value: object) -> dict[str, Any]:
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise ValueError("properties must be an object")
    read_back: dict[str, Any] = {}
    for name, item in value.items():
        if name in _RESERVED:
            raise ValueError(f"property '{name}' is reserved")
        if isinstance(item, list):
            members = [_read_scalar(name, member) for member in item]
            if not members or len({kind for kind, _ in members}) != 1:
                raise ValueError(f"property '{name}' is not a scalar or array of scalars")
            read_back[name] = [member for _, member in members]
        else:
            read_back[name] = _read_scalar(name, item)[1]
    return read_back


def _read_identifier(value: object) -> bool:
    return isinstance(value, str) and _IDENTIFIER.fullmatch(value) is not None


def read(value: bytes, key: str | None = None) -> Element:
    """Reads a record's value back into the element it describes, as the reader of a delta topic
    does: for a test, or for a consumer of such a topic. Given the record's ``key`` as well, it must
    be the delta's element key. Raises ``ValueError`` naming what is wrong.

    A property is returned as the graph stores it: a number with a whole value is an integer."""
    try:
        obj = json.loads(value)
    except (ValueError, UnicodeDecodeError) as e:
        raise ValueError("not a JSON object") from e
    if not isinstance(obj, dict):
        raise ValueError("not a JSON object")
    kind = obj.get("kind")
    if kind is None:
        raise ValueError("kind missing")
    if kind not in ("node", "edge", "tombstone"):
        raise ValueError(f"unknown kind '{kind}'")
    id = obj.get("id")  # noqa: A001
    if not isinstance(id, str) or not id:
        raise ValueError("id missing or empty")
    version = _read_version(obj.get("version"))

    def endpoints(problem: str) -> tuple[str, str, str]:
        tpe, from_id, to_id = obj.get("type"), obj.get("from"), obj.get("to")
        if not _read_identifier(tpe) or not isinstance(from_id, str) or not from_id or not isinstance(to_id, str) or not to_id:
            raise ValueError(problem)
        return typing.cast(str, tpe), from_id, to_id

    element: Element
    if kind == "node":
        labels = obj.get("labels", [])
        if not isinstance(labels, list) or not all(_read_identifier(label) for label in labels):
            raise ValueError("labels must be an array of identifiers")
        element = Element("node", "node", id, version, tuple(labels), properties=_read_properties(obj.get("properties")))
    elif kind == "edge":
        tpe, from_id, to_id = endpoints("edge needs type, from and to")
        element = Element("edge", "edge", id, version, type=tpe, from_id=from_id, to_id=to_id, properties=_read_properties(obj.get("properties")))
    elif obj.get("element") == "node":
        element = Element("tombstone", "node", id, version)
    elif obj.get("element") == "edge":
        tpe, from_id, to_id = endpoints("tombstone of an edge needs type, from and to")
        element = Element("tombstone", "edge", id, version, type=tpe, from_id=from_id, to_id=to_id)
    else:
        raise ValueError("tombstone needs element 'node' or 'edge'")
    if key is not None and key != element.key:
        raise ValueError(f"key '{key}' is not this delta's element key '{element.key}'")
    return element


class _DeltaCodec:
    """A delta as its topic carries it: JSON under the contract's name. Encoding needs the element's
    version settled; decoding is ``read`` without a key."""

    manifest = SCHEMA_NAME
    content_type = JSON

    def encode(self, value: Element) -> bytes:
        return write_json(_delta(value)).encode("utf-8")

    def decode(self, data: bytes) -> Element:
        return read(data)


CODEC: Codec[Element] = _DeltaCodec()
"""The codec of a delta. A graph consumer publishes through it; a consumer that *reads* a delta
topic names it as its ``message_codec``."""


# ── what a handler returns ──────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Publish:
    """The elements a change leaves as they are described: each is published as one delta."""

    elements: tuple[Element, ...]


GraphEffect = Publish | Done | Ignore


class GraphEffects:
    """The ``effects`` of a graph consumer. There is nothing here that publishes anything but an
    element, and nothing that sets a record's key."""

    def publish(self, elements: Iterable[Element]) -> Publish:
        """Publish these elements, each as one delta under its element key, at the change's
        sequence number unless it states a version. None at all is handled with nothing published."""
        collected = tuple(elements)
        for element in collected:
            if not isinstance(element, Element):
                raise TypeError(f"publish takes elements built with self.graph, not {type(element).__name__}")
        return Publish(collected)

    def done(self) -> Done:
        return Done()

    def ignore(self) -> Ignore:
        return Ignore()


def resolve(elements: Sequence[Element], sequence: int | None) -> tuple[Element, ...]:
    """Settles a result: every element that states no version takes ``sequence``, the sequence
    number of the change, and the same element twice is refused. A change with no sequence number —
    a topic's message — needs every element to state its own."""
    seen: set[str] = set()
    resolved: list[Element] = []
    for element in elements:
        if element.key in seen:
            raise RefusedElement("duplicate", f"{element._named()} is in this result twice; a result describes each element once")
        seen.add(element.key)
        if element.version is not None:
            resolved.append(element)
        elif sequence is None or sequence < 1:
            raise RefusedElement(
                "no-sequence",
                f"{element._named()} states no version and this change has no sequence number (its source is a topic); state a version",
            )
        else:
            resolved.append(Element(element.kind, element.element, element.id, sequence, element.labels, element.type, element.from_id, element.to_id, element.properties))
    return tuple(resolved)


class GraphConsumer(HasSecrets, HasServices, Generic[Src]):
    """Subclass this. ``source`` is the entity class whose changes become elements (or ``topic``),
    ``message_codec`` decodes them, and ``produces_to`` names the delta topic. ``on_message`` says
    which elements a change leaves in which state; ``on_delete`` runs when the source is deleted
    and is where a tombstone belongs.

    It is a consumer to the runtime — registered, discovered and run as one — that can publish
    nothing but deltas."""

    component_id: ClassVar[str]
    source: ClassVar[Any] = None
    topic: ClassVar[str | None] = None
    # Where a topic source starts. A graph consumer over a topic must say: there is no default.
    start_from: ClassVar[StartFrom | None] = None
    # A new one reads its topic again from start_from, under a group of its own. Absent is 1.
    version: ClassVar[int | None] = None
    produces_to: ClassVar[str]
    message_codec: ClassVar[Codec[Any]]
    out_codec: ClassVar[Codec[Any]] = CODEC

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        for required in ("component_id", "message_codec", "produces_to"):
            if not hasattr(cls, required):
                raise RegistrationError(f"{cls.__name__} must declare {required}")
        if not isinstance(cls.produces_to, str) or not cls.produces_to:
            raise RegistrationError(f"{cls.__name__} must name the topic it publishes deltas to in produces_to")
        if cls.out_codec is not CODEC:
            raise RegistrationError(f"{cls.__name__} publishes graph deltas and cannot declare an out_codec")
        _source_pb(cls)
        found = _start_from.problems(cls, consumer=True)
        if found:
            raise RegistrationError("; ".join(found))

    def __init__(self, client: ComponentClient | None = None) -> None:
        self.graph = Graph()
        self.effects = GraphEffects()
        self.client = client
        self._metadata: Metadata = Metadata()

    @property
    def metadata(self) -> Metadata:
        return self._metadata

    def on_message(self, message: Src) -> GraphEffect:
        raise NotImplementedError

    def on_delete(self) -> GraphEffect:
        """The source was deleted. Ignored unless this says otherwise."""
        return self.effects.ignore()

    @classmethod
    def to_component(cls) -> discovery_pb2.Component:
        detail = discovery_pb2.ConsumerDetail(source=_source_pb(cls), produces_to=cls.produces_to, version=cls.version)
        return discovery_pb2.Component(kind=discovery_pb2.CONSUMER, id=cls.component_id, handlers=[], consumer=detail)

    async def _handle(self, message_bytes: bytes | None, metadata: Metadata) -> ConsumerEffect:
        """What the runtime is answered: each element as a message under its element key."""
        self._metadata = metadata
        result = self.on_delete() if message_bytes is None else self.on_message(self.message_codec.decode(message_bytes))
        if isinstance(result, Awaitable):
            result = await typing.cast(Awaitable[GraphEffect], result)
        if isinstance(result, (Done, Ignore)):
            return result
        if not isinstance(result, Publish):
            raise TypeError(f"{type(self).__name__}.on_message returned {type(result).__name__}, not a graph effect")
        headers = Metadata().set("ce-type", SCHEMA_NAME)
        return ProduceAll(tuple(Message(element, element.key, headers) for element in resolve(result.elements, metadata.sequence_number)))
