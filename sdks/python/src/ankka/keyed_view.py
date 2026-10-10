"""Keyed views: one or more entities' changes, each through a handler of its own, written to the
rows each handler names by key.

The second shape of view. A plain ``View`` reads one source and keeps one row per entity of it,
under the entity's id. A keyed view reads several entities, and its handlers write whichever rows a
change is about: one event can write several rows, and several sources can write one row. A handler
is handed no row; it reads the rows it needs from its own view through ``self.rows``, by key or by
one of the view's declared queries. The platform handles one change of a keyed view at a time,
across every source and instance, so what a handler reads is what it writes over::

    class Shipments(KeyedView[ShipmentRow]):
        component_id = "shipments"
        row_codec = SHIPMENT_ROW
        of_customer = query("of-customer", f"SELECT payload FROM {table_of('shipments')} "
                                           "WHERE payload::jsonb->>'customer' = :customer")

        @on(CustomerEntity, CUSTOMER_EVENTS)
        async def on_customer(self, event: CustomerRenamed) -> KeyedViewEffect:
            theirs = await self.rows.ask("of-customer", customer=self.subject)
            return self.effects.update_rows({r.key: replace(r, name=event.name) for r in theirs})
"""

from __future__ import annotations

import typing
from collections.abc import Awaitable, Callable, Mapping
from typing import Any, ClassVar, Generic, Protocol, TypeVar

from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.codec import Codec
from ankka.context import Metadata
from ankka.effects.keyed_view import KeyedViewEffect, KeyedViewEffects
from ankka.event_sourced_entity import RegistrationError
from ankka.view import DeclaredQuery
from ankka.standing import Standing

Row = TypeVar("Row")
F = TypeVar("F", bound=Callable[..., Any])

# The first protocol in which a keyed view can be declared; an older sidecar would not know one.
KEYED_VIEW_PROTOCOL = (1, 13)


class RowReader(Protocol):
    """Where a keyed view's own rows are read from: the sidecar, or a test kit."""

    async def get(self, view_id: str, key: str, codec: Codec[Any]) -> Any | None: ...

    async def ask(self, view_id: str, name: str, codec: Codec[Any], values: Mapping[str, str]) -> list[Any]: ...


class ViewRows(Generic[Row]):
    """A keyed view's own rows, for the length of one change. It reaches no other view."""

    def __init__(self, view_id: str, codec: Codec[Any], reader: RowReader) -> None:
        self._view_id = view_id
        self._codec = codec
        self._reader = reader

    async def get(self, key: str) -> Row | None:
        """The row under ``key``, or None."""
        return typing.cast("Row | None", await self._reader.get(self._view_id, key, self._codec))

    async def ask(self, query_name: str, values: Mapping[str, str] | None = None, /, **named: str) -> list[Row]:
        """The rows of one of this view's declared queries, at most 1000 of them, with each value it
        takes given by name, as a mapping or as keywords."""
        given = {**(values or {}), **named}
        return typing.cast("list[Row]", await self._reader.ask(self._view_id, query_name, self._codec, given))


def on(source: type, event_codec: Codec[Any]) -> Callable[[F], F]:
    """Marks a keyed view's handler for every change of ``source``, decoded with ``event_codec``."""

    def mark(fn: F) -> F:
        fn._ankka_keyed_on = (source, event_codec)  # type: ignore[attr-defined]
        return fn

    return mark


def on_deleted(source: type) -> Callable[[F], F]:
    """Marks a keyed view's handler for the deletion of an entity of ``source``. Without one, the
    view does nothing when a source entity is deleted."""

    def mark(fn: F) -> F:
        fn._ankka_keyed_on_deleted = source  # type: ignore[attr-defined]
        return fn

    return mark


class _Source:
    def __init__(self, entity: Any, codec: Codec[Any], handler: str) -> None:
        self.entity = entity
        self.codec = codec
        self.handler = handler
        self.deleted: str | None = None

    @property
    def component_id(self) -> str:
        return typing.cast(str, self.entity.component_id)

    def to_pb(self) -> discovery_pb2.Source:
        kind = discovery_pb2.EVENT_SOURCED_ENTITY
        if hasattr(self.entity, "to_component"):
            kind = self.entity.to_component().kind
        return discovery_pb2.Source(component=discovery_pb2.Source.ComponentRef(kind=kind, id=self.component_id))


class KeyedView(Generic[Row]):
    """Subclass this. ``component_id`` names the view, ``row_codec`` encodes its rows, and each
    source has a handler marked ``@on(EntityClass, event_codec)``; ``version``, raised, has the view
    emptied and every source read again from its beginning."""

    component_id: ClassVar[str]
    row_codec: ClassVar[Codec[Any]]
    version: ClassVar[int | None] = None
    _declared_queries: ClassVar[tuple[DeclaredQuery, ...]] = ()
    _sources: ClassVar[dict[str, _Source]] = {}

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        cls._declared_queries = tuple(
            value for klass in reversed(cls.__mro__) for value in vars(klass).values() if isinstance(value, DeclaredQuery)
        )
        for required in ("component_id", "row_codec"):
            if not hasattr(cls, required):
                raise RegistrationError(f"{cls.__name__} must declare {required}")
        sources: dict[str, _Source] = {}
        problems: list[str] = []
        members = {name: value for klass in reversed(cls.__mro__) for name, value in vars(klass).items()}
        for name, value in members.items():
            marked = getattr(value, "_ankka_keyed_on", None)
            if marked is not None:
                entity, codec = marked
                cid = entity.component_id
                if cid in sources:
                    problems.append(f"{cls.__name__} reads '{cid}' twice; each source is read once")
                sources[cid] = _Source(entity, codec, name)
        for name, value in members.items():
            deleted = getattr(value, "_ankka_keyed_on_deleted", None)
            if deleted is not None:
                cid = deleted.component_id
                if cid not in sources:
                    problems.append(f"{cls.__name__} handles the deletion of '{cid}', which it does not read")
                else:
                    sources[cid].deleted = name
        if not sources:
            problems.append(f"{cls.__name__} declares no source; a keyed view reads one or more, each with @on")
        version = cls.version
        if version is not None and (isinstance(version, bool) or not isinstance(version, int) or version < 1):
            problems.append(f"{cls.__name__} declares version {version!r}; a version is a whole number of 1 or more")
        if problems:
            raise RegistrationError("; ".join(problems))
        cls._sources = sources

    def __init__(self) -> None:
        self.effects: KeyedViewEffects[Row] = KeyedViewEffects()
        self._metadata: Metadata = Metadata()
        self._rows: ViewRows[Row] | None = None
        self._standing: Standing | None = None

    @property
    def standing(self) -> Standing | None:
        """Of a change from a workflow: where it stood once the effect that recorded the state was
        applied. None for a change from an entity."""
        return self._standing

    @property
    def metadata(self) -> Metadata:
        return self._metadata

    @property
    def subject(self) -> str:
        """The id of the entity the change came from."""
        return self._metadata.subject or ""

    @property
    def rows(self) -> ViewRows[Row]:
        """The view's own rows; only while a change is being handled."""
        if self._rows is None:
            raise RuntimeError("rows are only available while a keyed view handles a change")
        return self._rows

    @classmethod
    def to_component(cls) -> discovery_pb2.Component:
        return discovery_pb2.Component(
            kind=discovery_pb2.VIEW,
            id=cls.component_id,
            handlers=[],
            view=discovery_pb2.ViewDetail(
                row_manifest=cls.row_codec.manifest,
                version=cls.version,
                sources=[source.to_pb() for source in cls._sources.values()],
                declared_queries=[
                    discovery_pb2.DeclaredQuery(name=q.name, statement=q.statement) for q in cls._declared_queries
                ],
            ),
        )

    async def _handle(
        self,
        source_id: str,
        event_bytes: bytes | None,
        metadata: Metadata,
        reader: RowReader,
        standing: Standing | None = None,
    ) -> KeyedViewEffect:
        """``event_bytes`` is None when the source entity was deleted."""
        source = type(self)._sources.get(source_id)
        if source is None:
            raise LookupError(f"{type(self).__name__} reads no source '{source_id}'")
        self._metadata = metadata
        self._standing = standing
        self._rows = ViewRows(type(self).component_id, type(self).row_codec, reader)
        try:
            if event_bytes is None:
                if source.deleted is None:
                    return self.effects.ignore()
                result: Any = getattr(self, source.deleted)()
            else:
                result = getattr(self, source.handler)(source.codec.decode(event_bytes))
            if isinstance(result, Awaitable):
                result = await typing.cast(Awaitable[KeyedViewEffect], result)
            if not isinstance(result, KeyedViewEffect):
                raise TypeError(f"{type(self).__name__} returned {type(result).__name__}, not a keyed view effect")
            return result
        finally:
            self._rows = None


def declares_keyed(cls: type) -> bool:
    return isinstance(cls, type) and issubclass(cls, KeyedView)
