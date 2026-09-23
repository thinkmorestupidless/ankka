"""Timed actions: a call the platform makes later. Scheduled through ``client.timers``; the
sidecar's sweeper delivers it here, retrying a failure with backoff."""

from __future__ import annotations

import typing
from collections.abc import Awaitable, Callable
from typing import Any, ClassVar

from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.context import Metadata
from ankka.effects.timed_action import Done, Failed, TimedActionEffect, TimedActionEffects
from ankka.event_sourced_entity import HandlerSpec, RegistrationError, collect_handlers, command

if typing.TYPE_CHECKING:
    from ankka.client import ComponentClient


def action(name: str) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """Declares a timed action handler under wire name ``name``."""
    return command(name)


class TimedAction:
    component_id: ClassVar[str]
    _handlers: ClassVar[dict[str, HandlerSpec]]

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        if not hasattr(cls, "component_id"):
            raise RegistrationError(f"{cls.__name__} must declare component_id")
        cls._handlers = collect_handlers(cls, effect_base=object, read_only_base=object)

    def __init__(self, client: ComponentClient | None = None) -> None:
        self.effects = TimedActionEffects()
        self.client = client
        self._metadata: Metadata = Metadata()

    @property
    def metadata(self) -> Metadata:
        return self._metadata

    @classmethod
    def handlers(cls) -> dict[str, HandlerSpec]:
        return cls._handlers

    @classmethod
    def to_component(cls) -> discovery_pb2.Component:
        return discovery_pb2.Component(
            kind=discovery_pb2.TIMED_ACTION,
            id=cls.component_id,
            handlers=[h.to_pb() for h in sorted(cls._handlers.values(), key=lambda h: h.name)],
            timed_action=discovery_pb2.TimedActionDetail(),
        )

    async def _run(self, spec: HandlerSpec, input_bytes: bytes, metadata: Metadata) -> TimedActionEffect:
        self._metadata = metadata
        method = getattr(self, spec.method_name)
        result = method() if spec.input_type is None else method(spec.input_codec.decode(input_bytes))
        if isinstance(result, Awaitable):
            result = await typing.cast(Awaitable[TimedActionEffect], result)
        if not isinstance(result, (Done, Failed)):
            raise TypeError(f"{type(self).__name__}.{spec.method_name} returned {type(result).__name__}, not a timed action effect")
        return result
