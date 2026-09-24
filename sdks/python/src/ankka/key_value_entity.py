"""Key value entities: the latest value only, no history.

The same shape as an event sourced entity without the fold: ``component_id``, ``state_codec``,
``empty_state``, and handlers decorated ``@command``/``@query`` returning ``KeyValueEffect``s.
"""

from __future__ import annotations

import typing
from collections.abc import Awaitable
from typing import Any, ClassVar, Generic, TypeVar

from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.codec import Codec
from ankka.context import CommandContext
from ankka.effects.key_value import KeyValueEffect, KeyValueEffects, KeyValueReadOnlyEffect
from ankka.event_sourced_entity import HandlerSpec, RegistrationError, collect_handlers

S = TypeVar("S")


class KeyValueEntity(Generic[S]):
    component_id: ClassVar[str]
    state_codec: ClassVar[Codec[Any]]
    _handlers: ClassVar[dict[str, HandlerSpec]]

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        for required in ("component_id", "state_codec"):
            if not hasattr(cls, required):
                raise RegistrationError(f"{cls.__name__} must declare {required}")
        cls._handlers = collect_handlers(cls, effect_base=KeyValueEffect, read_only_base=KeyValueReadOnlyEffect)

    def __init__(self) -> None:
        self.effects: KeyValueEffects[S] = KeyValueEffects()
        self._state: S | None = None
        self._context: CommandContext | None = None
        self._entity_id: str = ""

    def empty_state(self) -> S:
        raise NotImplementedError

    @property
    def entity_id(self) -> str:
        return self._entity_id

    def _bind(self, entity_id: str) -> None:
        self._entity_id = entity_id

    @property
    def state(self) -> S:
        if self._state is None:
            raise RuntimeError("state is only available inside a handler")
        return self._state

    @property
    def context(self) -> CommandContext:
        if self._context is None:
            raise RuntimeError("context is only available inside a handler")
        return self._context

    @classmethod
    def handlers(cls) -> dict[str, HandlerSpec]:
        return cls._handlers

    @classmethod
    def to_component(cls) -> discovery_pb2.Component:
        return discovery_pb2.Component(
            kind=discovery_pb2.KEY_VALUE_ENTITY,
            id=cls.component_id,
            handlers=[h.to_pb() for h in sorted(cls._handlers.values(), key=lambda h: h.name)],
            key_value=discovery_pb2.KeyValueDetail(),
        )

    async def _run(self, spec: HandlerSpec, state: S, input_bytes: bytes, context: CommandContext) -> KeyValueEffect[S, Any]:
        self._state = state
        self._context = context
        try:
            method = getattr(self, spec.method_name)
            result = method() if spec.input_type is None else method(spec.input_codec.decode(input_bytes))
            if isinstance(result, Awaitable):
                result = await result
            if not isinstance(result, KeyValueEffect):
                raise TypeError(f"{type(self).__name__}.{spec.method_name} returned {type(result).__name__}, not an effect")
            return typing.cast(KeyValueEffect[S, Any], result)
        finally:
            self._state = None
            self._context = None
