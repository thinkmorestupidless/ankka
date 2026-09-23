"""Views: a queryable projection of a source's changes. The sidecar runs the projection and
stores the rows; this process only says what each event does to a row."""

from __future__ import annotations

import typing
from collections.abc import Awaitable
from typing import Any, ClassVar, Generic, TypeVar

from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.codec import Codec
from ankka.context import Metadata
from ankka.effects.view import DeleteRow, Ignore, UpdateRow, ViewEffect, ViewEffects
from ankka.event_sourced_entity import RegistrationError

Src = TypeVar("Src")
Row = TypeVar("Row")


def _source_pb(cls: type) -> discovery_pb2.Source:
    source = getattr(cls, "source", None)
    topic = getattr(cls, "topic", None)
    if source is not None:
        kind = discovery_pb2.EVENT_SOURCED_ENTITY
        if hasattr(source, "to_component"):
            kind = source.to_component().kind
        return discovery_pb2.Source(component=discovery_pb2.Source.ComponentRef(kind=kind, id=source.component_id))
    if topic is not None:
        return discovery_pb2.Source(topic=topic)
    raise RegistrationError(f"{cls.__name__} must declare a source (a component class) or a topic")


class View(Generic[Src, Row]):
    """Subclass this. ``source`` is the entity class whose events feed the view (or ``topic``),
    ``event_codec`` decodes them, ``row_codec`` encodes rows; ``on_change`` says what an event
    does to the current row (``self.row``, None when there is none)."""

    component_id: ClassVar[str]
    source: ClassVar[Any] = None
    topic: ClassVar[str | None] = None
    event_codec: ClassVar[Codec[Any]]
    row_codec: ClassVar[Codec[Any]]
    queries: ClassVar[tuple[str, ...]] = ("get", "all")

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        for required in ("component_id", "event_codec", "row_codec"):
            if not hasattr(cls, required):
                raise RegistrationError(f"{cls.__name__} must declare {required}")
        _source_pb(cls)

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
            view=discovery_pb2.ViewDetail(source=_source_pb(cls), row_manifest=cls.row_codec.manifest, queries=list(cls.queries)),
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
