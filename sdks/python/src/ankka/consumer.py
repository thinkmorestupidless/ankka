"""Consumers: react to a source's changes, optionally producing onward to a topic."""

from __future__ import annotations

import typing
from collections.abc import Awaitable
from typing import Any, ClassVar, Generic, TypeVar

from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.codec import Codec
from ankka.context import Metadata
from ankka.effects.consumer import ConsumerEffect, ConsumerEffects, Done, Ignore, Produce
from ankka.event_sourced_entity import RegistrationError
from ankka.view import _source_pb

if typing.TYPE_CHECKING:
    from ankka.client import ComponentClient

Src = TypeVar("Src")
Out = TypeVar("Out")


class Consumer(Generic[Src, Out]):
    component_id: ClassVar[str]
    source: ClassVar[Any] = None
    topic: ClassVar[str | None] = None
    produces_to: ClassVar[str | None] = None
    message_codec: ClassVar[Codec[Any]]
    out_codec: ClassVar[Codec[Any] | None] = None

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        for required in ("component_id", "message_codec"):
            if not hasattr(cls, required):
                raise RegistrationError(f"{cls.__name__} must declare {required}")
        _source_pb(cls)
        if cls.produces_to is not None and cls.out_codec is None:
            raise RegistrationError(f"{cls.__name__} produces to '{cls.produces_to}' and needs an out_codec")

    def __init__(self, client: ComponentClient | None = None) -> None:
        self.effects: ConsumerEffects[Out] = ConsumerEffects()
        self.client = client
        self._metadata: Metadata = Metadata()

    @property
    def metadata(self) -> Metadata:
        return self._metadata

    def on_message(self, message: Src) -> ConsumerEffect:
        raise NotImplementedError

    def on_delete(self) -> ConsumerEffect:
        """The source was deleted. Ignored unless this says otherwise."""
        return self.effects.ignore()

    @classmethod
    def to_component(cls) -> discovery_pb2.Component:
        detail = discovery_pb2.ConsumerDetail(source=_source_pb(cls))
        if cls.produces_to is not None:
            detail.produces_to = cls.produces_to
        return discovery_pb2.Component(kind=discovery_pb2.CONSUMER, id=cls.component_id, handlers=[], consumer=detail)

    async def _handle(self, message_bytes: bytes | None, metadata: Metadata) -> ConsumerEffect:
        """``message_bytes`` is None when the source was deleted."""
        self._metadata = metadata
        result = self.on_delete() if message_bytes is None else self.on_message(self.message_codec.decode(message_bytes))
        if isinstance(result, Awaitable):
            result = await typing.cast(Awaitable[ConsumerEffect], result)
        if not isinstance(result, (Produce, Done, Ignore)):
            raise TypeError(f"{type(self).__name__}.on_message returned {type(result).__name__}, not a consumer effect")
        return result
