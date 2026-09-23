"""Event sourced entities: state derived by replaying persisted events.

A subclass declares its identity and codecs as class attributes, ``empty_state`` and
``apply_event`` as methods, and handlers decorated ``@command("wire-name")`` or
``@query("wire-name")``. The decorator's argument is the wire name: renaming the method changes
nothing on the wire, which is what makes it a versioning boundary.
"""

from __future__ import annotations

import inspect
import typing
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any, ClassVar, Generic, TypeVar, get_args, get_origin, get_type_hints

from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.codec import UNIT, Codec, default_codec_for
from ankka.context import CommandContext
from ankka.effects.event_sourced import EventSourcedEffect, EventSourcedEffects, ReadOnlyEffect

S = TypeVar("S")
E = TypeVar("E")


class RegistrationError(ValueError):
    """A component that cannot be hosted as declared. Raised at registration, not at first call."""


@dataclass(frozen=True)
class HandlerSpec:
    """One handler as discovery describes it and as the server dispatches it."""

    name: str
    read_only: bool
    method_name: str
    input_type: Any  # None when the handler takes no input
    reply_type: Any
    input_codec: Codec[Any]
    reply_codec: Codec[Any]
    streaming: bool = False

    def to_pb(self) -> discovery_pb2.Handler:
        return discovery_pb2.Handler(name=self.name, read_only=self.read_only, streaming=self.streaming)


_MARK = "_ankka_handler"


def command(name: str) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """Declares a command handler under wire name ``name``. May persist."""

    def decorate(fn: Callable[..., Any]) -> Callable[..., Any]:
        setattr(fn, _MARK, (name, False))
        return fn

    return decorate


def query(name: str) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """Declares a read-only handler under wire name ``name``. Must return a ReadOnlyEffect; the
    return annotation is checked at registration, and the sidecar refuses events from it anyway."""

    def decorate(fn: Callable[..., Any]) -> Callable[..., Any]:
        setattr(fn, _MARK, (name, True))
        return fn

    return decorate


def _reply_type_of(annotation: Any) -> Any:
    """The R in ``EventSourcedEffect[S, E, R]`` (or another effect's last type argument)."""
    args = get_args(annotation)
    if args:
        return args[-1]
    return Any


def _is_read_only_annotation(annotation: Any) -> bool:
    origin = get_origin(annotation) or annotation
    return inspect.isclass(origin) and issubclass(origin, ReadOnlyEffect)


def collect_handlers(cls: type, *, effect_base: type = EventSourcedEffect) -> dict[str, HandlerSpec]:
    """Every decorated method on ``cls``, validated: wire names unique, a query annotated as
    read-only, an input codec and a reply codec resolved from the annotations."""
    found: dict[str, HandlerSpec] = {}
    for attr, member in inspect.getmembers(cls, predicate=inspect.isfunction):
        mark = getattr(member, _MARK, None)
        if mark is None:
            continue
        name, read_only = mark
        if name in found:
            raise RegistrationError(f"{cls.__name__}: handler '{name}' is declared twice")
        hints = get_type_hints(member)
        params = [p for p in inspect.signature(member).parameters.values() if p.name != "self"]
        if len(params) > 1:
            raise RegistrationError(f"{cls.__name__}.{attr}: a handler takes at most one input, not {len(params)}")
        input_type = hints.get(params[0].name) if params else None
        if params and input_type is None:
            raise RegistrationError(f"{cls.__name__}.{attr}: the input parameter needs a type annotation")
        ret = hints.get("return")
        if ret is None:
            raise RegistrationError(f"{cls.__name__}.{attr}: a handler needs a return annotation")
        if read_only and not _is_read_only_annotation(ret):
            raise RegistrationError(
                f"{cls.__name__}.{attr}: a @query must be annotated as returning ReadOnlyEffect, "
                f"so that it provably cannot persist"
            )
        reply_type = _reply_type_of(ret)
        found[name] = HandlerSpec(
            name=name,
            read_only=read_only,
            method_name=attr,
            input_type=input_type,
            reply_type=reply_type,
            input_codec=UNIT if input_type is None else default_codec_for(input_type),
            reply_codec=default_codec_for(reply_type),
        )
    _unused(effect_base)
    return found


def _unused(*_: Any) -> None:
    return None


class EventSourcedEntity(Generic[S, E]):
    """Subclass this. Class attributes: ``component_id``, ``state_codec``, ``event_codec``,
    optionally ``snapshot_every``. Methods: ``empty_state``, ``apply_event``, and handlers."""

    component_id: ClassVar[str]
    state_codec: ClassVar[Codec[Any]]
    event_codec: ClassVar[Codec[Any]]
    snapshot_every: ClassVar[int] = 0

    _handlers: ClassVar[dict[str, HandlerSpec]]

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        for required in ("component_id", "state_codec", "event_codec"):
            if not hasattr(cls, required):
                raise RegistrationError(f"{cls.__name__} must declare {required}")
        cls._handlers = collect_handlers(cls)

    def __init__(self) -> None:
        self.effects: EventSourcedEffects[S, E] = EventSourcedEffects()
        self._state: S | None = None
        self._context: CommandContext | None = None

    # ── What a subclass implements ─────────────────────────────────────────

    def empty_state(self) -> S:
        raise NotImplementedError

    def apply_event(self, state: S, event: E) -> S:
        raise NotImplementedError

    # ── What a handler may read ────────────────────────────────────────────

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

    # ── Discovery ──────────────────────────────────────────────────────────

    @classmethod
    def handlers(cls) -> dict[str, HandlerSpec]:
        return cls._handlers

    @classmethod
    def to_component(cls) -> discovery_pb2.Component:
        return discovery_pb2.Component(
            kind=discovery_pb2.EVENT_SOURCED_ENTITY,
            id=cls.component_id,
            handlers=[h.to_pb() for h in sorted(cls._handlers.values(), key=lambda h: h.name)],
            event_sourced=discovery_pb2.EventSourcedDetail(snapshot_every=cls.snapshot_every),
        )

    # ── Running a handler (used by the server and the unit testkit) ───────

    async def _run(self, spec: HandlerSpec, state: S, input_bytes: bytes, context: CommandContext) -> EventSourcedEffect[S, E, Any]:
        self._state = state
        self._context = context
        try:
            method = getattr(self, spec.method_name)
            if spec.input_type is None:
                result = method()
            else:
                result = method(spec.input_codec.decode(input_bytes))
            if isinstance(result, Awaitable):
                result = await result
            if not isinstance(result, EventSourcedEffect):
                raise TypeError(f"{type(self).__name__}.{spec.method_name} returned {type(result).__name__}, not an effect")
            return typing.cast(EventSourcedEffect[S, E, Any], result)
        finally:
            self._state = None
            self._context = None
