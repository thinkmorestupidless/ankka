"""Views: a queryable projection of a source's changes. The sidecar runs the projection and
stores the rows; this process only says what each event does to a row."""

from __future__ import annotations

import typing
from collections.abc import Awaitable
from dataclasses import dataclass
from typing import Any, ClassVar, Generic, TypeVar

from ankka import contract, start_from
from ankka.contract import Contract
from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.codec import Codec
from ankka.start_from import StartFrom
from ankka.context import Metadata
from ankka.effects.view import DeleteRow, Ignore, UpdateRow, ViewEffect, ViewEffects
from ankka.event_sourced_entity import RegistrationError

Src = TypeVar("Src")
Row = TypeVar("Row")

# The first protocol in which a view can declare queries; an older sidecar would not know them.
DECLARED_QUERY_PROTOCOL = (1, 13)


@dataclass(frozen=True)
class DeclaredQuery:
    """A question a view can be asked by name: one SQL statement over the view's own table, whose
    values are the ``:name``s it holds. Kept as a class attribute of the view; the platform checks
    the statement when the service starts, and a value is bound, never part of the text."""

    name: str
    statement: str


def query(name: str, statement: str) -> DeclaredQuery:
    """Declares a query a view can be asked by ``name``; keep it as a class attribute of the view."""
    return DeclaredQuery(name, statement)


def table_of(component_id: str) -> str:
    """The table holding a view's rows, for a declared query's statement to name."""
    return "ankka_view_" + "".join(c if c.isalnum() else "_" for c in component_id)


def declares_queries(cls: type) -> bool:
    """Whether ``cls`` declares a query, which a sidecar older than 1.13 would not know."""
    return bool(getattr(cls, "_declared_queries", ()))


def _source_pb(cls: type) -> discovery_pb2.Source:
    source = getattr(cls, "source", None)
    topic = getattr(cls, "topic", None)
    if source is not None:
        kind = discovery_pb2.EVENT_SOURCED_ENTITY
        if hasattr(source, "to_component"):
            kind = source.to_component().kind
        return discovery_pb2.Source(component=discovery_pb2.Source.ComponentRef(kind=kind, id=source.component_id))
    if topic is not None:
        return contract.apply(start_from.apply(discovery_pb2.Source(topic=topic), cls), cls)
    raise RegistrationError(f"{cls.__name__} must declare a source (a component class) or a topic")


class View(Generic[Src, Row]):
    """Subclass this. ``source`` is the entity class whose events feed the view (or ``topic``),
    ``event_codec`` decodes them, ``row_codec`` encodes rows; ``on_change`` says what an event
    does to the current row (``self.row``, None when there is none)."""

    component_id: ClassVar[str]
    source: ClassVar[Any] = None
    topic: ClassVar[str | None] = None
    # Where a topic source starts; a view that says nothing starts at the earliest message.
    start_from: ClassVar[StartFrom | None] = None
    # Raised to have the view emptied and read again: its topic from its start position, or its
    # entity from the first thing it recorded. Absent is 1.
    version: ClassVar[int | None] = None
    # What the project must know about a topic source (1.14): the contract the view expects the
    # topic to carry, the declared broker it is on, and whether its partitions are read in parallel.
    contract: ClassVar[Contract | None] = None
    broker: ClassVar[str | None] = None
    parallel: ClassVar[bool] = False
    # Another project's topic (1.15): the id of the project whose topic `topic` is. The broker
    # serves it while that project grants this service consume on it.
    project: ClassVar[str | None] = None
    event_codec: ClassVar[Codec[Any]]
    row_codec: ClassVar[Codec[Any]]
    queries: ClassVar[tuple[str, ...]] = ("get", "all")
    _declared_queries: ClassVar[tuple[DeclaredQuery, ...]] = ()

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        cls._declared_queries = tuple(
            value
            for klass in reversed(cls.__mro__)
            for value in vars(klass).values()
            if isinstance(value, DeclaredQuery)
        )
        for required in ("component_id", "event_codec", "row_codec"):
            if not hasattr(cls, required):
                raise RegistrationError(f"{cls.__name__} must declare {required}")
        _source_pb(cls)
        found = start_from.problems(cls, consumer=False) + contract.problems(cls)
        if found:
            raise RegistrationError("; ".join(found))

    def __init__(self) -> None:
        self.effects: ViewEffects[Row] = ViewEffects()
        self._row: Row | None = None
        self._metadata: Metadata = Metadata()

    @property
    def row(self) -> Row | None:
        return self._row

    @property
    def metadata(self) -> Metadata:
        return self._metadata

    def on_change(self, event: Src) -> ViewEffect:
        raise NotImplementedError

    def on_delete(self) -> ViewEffect:
        """The source was deleted. The row goes with it unless this says otherwise — an order
        history keeps a checked-out cart's row as a tombstone."""
        return self.effects.delete_row()

    @classmethod
    def to_component(cls) -> discovery_pb2.Component:
        return discovery_pb2.Component(
            kind=discovery_pb2.VIEW,
            id=cls.component_id,
            handlers=[],
            view=discovery_pb2.ViewDetail(
                source=_source_pb(cls),
                row_manifest=cls.row_codec.manifest,
                queries=list(cls.queries),
                version=cls.version,
                declared_queries=[
                    discovery_pb2.DeclaredQuery(name=q.name, statement=q.statement) for q in cls._declared_queries
                ],
            ),
        )

    async def _handle(self, event_bytes: bytes | None, row_bytes: bytes | None, metadata: Metadata) -> ViewEffect:
        """``event_bytes`` is None when the source was deleted."""
        self._row = self.row_codec.decode(row_bytes) if row_bytes is not None else None
        self._metadata = metadata
        try:
            result = self.on_delete() if event_bytes is None else self.on_change(self.event_codec.decode(event_bytes))
            if isinstance(result, Awaitable):
                result = await typing.cast(Awaitable[ViewEffect], result)
            if not isinstance(result, (UpdateRow, DeleteRow, Ignore)):
                raise TypeError(f"{type(self).__name__}.on_change returned {type(result).__name__}, not a view effect")
            return result
        finally:
            self._row = None
