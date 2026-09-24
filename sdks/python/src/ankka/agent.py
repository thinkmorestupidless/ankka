"""Agents: your process declares the instructions, the tools and the guardrails; the sidecar runs
the loop — the model call, tool dispatch, session memory, compaction and token accounting. Your
process is asked to plan an interaction, to run a tool, and to check a guardrail, and nothing
else: the model key lives on the sidecar, and this code never calls a model.
"""

from __future__ import annotations

import dataclasses
import inspect
import json
import types
import typing
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any, ClassVar, get_args, get_origin, get_type_hints

from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.codec import Codec, default_codec_for
from ankka.context import Metadata
from ankka.effects.agent import AgentEffect, AgentEffects
from ankka.event_sourced_entity import HandlerSpec, RegistrationError, collect_handlers

if typing.TYPE_CHECKING:
    from ankka.client import ComponentClient

_STREAM = "_ankka_stream"


def stream(name: str) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """Declares a handler whose reply streams, token by token, under wire name ``name``."""

    def decorate(fn: Callable[..., Any]) -> Callable[..., Any]:
        setattr(fn, _STREAM, name)
        return fn

    return decorate


@dataclass(frozen=True)
class Tool:
    """A function the model may call. ``input`` is a dataclass (or ``None``) the model's arguments
    decode into — its fields are the schema the model sees — and ``run`` answers text for the
    model. An exception is a tool error, fed back to the model, which usually corrects itself."""

    description: str
    run: Callable[..., Any]
    input: Any = None

    def input_schema(self) -> str:
        return json.dumps(_schema_for(self.input) if self.input is not None else {"type": "object", "properties": {}})


@dataclass(frozen=True)
class Guardrail:
    """A check on what goes into, or comes out of, the model: ``check(stage, text)`` answers a
    reason to block, or ``None``. ``stage`` is ``"input"`` or ``"output"``."""

    check: Callable[[str, str], str | None]


def _schema_for(tp: Any) -> dict[str, Any]:
    origin = get_origin(tp)
    if origin in (types.UnionType, typing.Union):
        members = [a for a in get_args(tp) if a is not type(None)]
        if len(members) == 1:
            return _schema_for(members[0])
        return {"anyOf": [_schema_for(m) for m in members]}
    if tp is str:
        return {"type": "string"}
    if tp is bool:
        return {"type": "boolean"}
    if tp is int:
        return {"type": "integer"}
    if tp is float:
        return {"type": "number"}
    if origin in (list, tuple, set, frozenset):
        args = get_args(tp)
        return {"type": "array", "items": _schema_for(args[0]) if args else {}}
    if origin in (dict, typing.Mapping):
        args = get_args(tp)
        return {"type": "object", "additionalProperties": _schema_for(args[1]) if len(args) == 2 else {}}
    if dataclasses.is_dataclass(tp) and isinstance(tp, type):
        hints = get_type_hints(tp)
        properties = {f.name: _schema_for(hints.get(f.name, Any)) for f in dataclasses.fields(tp)}
        required = [
            f.name
            for f in dataclasses.fields(tp)
            if f.default is dataclasses.MISSING and f.default_factory is dataclasses.MISSING
        ]
        return {"type": "object", "properties": properties, "required": required, "additionalProperties": False}
    return {}


class Agent:
    """Subclass this: ``component_id``, ``tools = {name: Tool(...)}``, ``guardrails = {name:
    Guardrail(...)}``, and handlers decorated ``@command`` or ``@stream`` that return an
    ``AgentEffect`` built from ``self.effects``."""

    component_id: ClassVar[str]
    role: ClassVar[str | None] = None
    max_tool_call_steps: ClassVar[int] = 100
    tools: ClassVar[dict[str, Tool]] = {}
    guardrails: ClassVar[dict[str, Guardrail]] = {}
    _handlers: ClassVar[dict[str, HandlerSpec]]

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        if not hasattr(cls, "component_id"):
            raise RegistrationError(f"{cls.__name__} must declare component_id")
        handlers = collect_handlers(cls, effect_base=AgentEffect, read_only_base=AgentEffect)
        for attr, member in inspect.getmembers(cls, predicate=inspect.isfunction):
            name = getattr(member, _STREAM, None)
            if name is None:
                continue
            if name in handlers:
                raise RegistrationError(f"{cls.__name__}: handler '{name}' is declared twice")
            hints = get_type_hints(member)
            params = [p for p in inspect.signature(member).parameters.values() if p.name != "self"]
            if len(params) > 1:
                raise RegistrationError(f"{cls.__name__}.{attr}: a handler takes at most one input")
            input_type = hints.get(params[0].name) if params else None
            in_codec: Codec[Any] = default_codec_for(input_type) if input_type is not None else default_codec_for(type(None))
            handlers[name] = HandlerSpec(name, False, attr, input_type, str, in_codec, default_codec_for(str), streaming=True)
        cls._handlers = handlers
        for name, tool in cls.tools.items():
            if not tool.description:
                raise RegistrationError(f"{cls.__name__}: tool '{name}' needs a description; the model decides by it")
        if cls.max_tool_call_steps <= 0:
            raise RegistrationError(f"{cls.__name__}: max_tool_call_steps must be positive")

    def __init__(self, client: ComponentClient | None = None) -> None:
        self.effects = AgentEffects()
        self.client = client
        self._session_id = ""
        self._metadata = Metadata()

    @property
    def session_id(self) -> str:
        return self._session_id

    @property
    def metadata(self) -> Metadata:
        return self._metadata

    @classmethod
    def handlers(cls) -> dict[str, HandlerSpec]:
        return cls._handlers

    @classmethod
    def to_component(cls) -> discovery_pb2.Component:
        return discovery_pb2.Component(
            kind=discovery_pb2.AGENT,
            id=cls.component_id,
            handlers=[h.to_pb() for h in sorted(cls._handlers.values(), key=lambda h: h.name)],
            agent=discovery_pb2.AgentDetail(
                role=cls.role or "",
                max_tool_call_steps=cls.max_tool_call_steps,
                tools=[
                    discovery_pb2.Tool(name=n, description=t.description, input_schema_json=t.input_schema())
                    for n, t in sorted(cls.tools.items())
                ],
                guardrails=sorted(cls.guardrails),
            ),
        )

    # ── What the server and the testkit drive ──────────────────────────────

    async def _plan(self, spec: HandlerSpec, input_bytes: bytes, session_id: str, metadata: Metadata) -> AgentEffect[Any]:
        self._session_id = session_id
        self._metadata = metadata
        method = getattr(self, spec.method_name)
        result = method() if spec.input_type is None else method(spec.input_codec.decode(input_bytes))
        if isinstance(result, Awaitable):
            result = await result
        if not isinstance(result, AgentEffect):
            raise TypeError(f"{type(self).__name__}.{spec.method_name} returned {type(result).__name__}, not an AgentEffect")
        effect = result
        unknown = [t for t in effect.tool_names if t not in type(self).tools] + [g for g in effect.guardrail_names if g not in type(self).guardrails]
        if unknown:
            raise RegistrationError(f"{type(self).__name__}: the plan names undeclared {', '.join(unknown)}")
        return effect

    async def _invoke_tool(self, name: str, arguments_json: str, session_id: str) -> str:
        self._session_id = session_id
        tool = type(self).tools[name]
        if tool.input is None:
            result = tool.run(self)
        else:
            result = tool.run(self, default_codec_for(tool.input).decode(arguments_json.encode("utf-8")))
        if isinstance(result, Awaitable):
            result = await result
        return result if isinstance(result, str) else json.dumps(result)

    async def _check_guardrail(self, name: str, stage: str, text: str, session_id: str) -> str | None:
        self._session_id = session_id
        result = type(self).guardrails[name].check(stage, text)
        if isinstance(result, Awaitable):
            result = await result
        return result
