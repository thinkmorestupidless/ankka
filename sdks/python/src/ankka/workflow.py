"""Workflows: a durable multi-step process. Commands change state and start steps; steps run in
this process, one at a time per instance, and say what happens next. The sidecar journals every
transition and drives the steps, retries and timeouts — a step that fails is retried and
eventually compensated as it declared.
"""

from __future__ import annotations

import inspect
import typing
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import timedelta
from typing import Any, ClassVar, Generic, TypeVar, get_type_hints

from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.codec import UNIT, Codec, default_codec_for
from ankka.context import CommandContext
from ankka.effects.workflow import StepEffects, WorkflowEffect, WorkflowEffects, WorkflowReadOnlyEffect, WorkflowStepEffect
from ankka.event_sourced_entity import HandlerSpec, RegistrationError, collect_handlers

S = TypeVar("S")

_STEP = "_ankka_step"


def step(name: str) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """Declares a step under wire name ``name``. Takes an optional typed input and returns a
    ``WorkflowStepEffect``."""

    def decorate(fn: Callable[..., Any]) -> Callable[..., Any]:
        setattr(fn, _STEP, name)
        return fn

    return decorate


@dataclass(frozen=True)
class Recovery:
    """What the sidecar does when a step throws or times out: retry it, then fail over to a step
    that takes no input (compensation reads the state), or fail the workflow."""

    max_retries: int = 0
    failover_to: str | None = None

    def to_pb(self) -> discovery_pb2.WorkflowDetail.Recovery:
        pb = discovery_pb2.WorkflowDetail.Recovery(max_retries=self.max_retries)
        if self.failover_to is not None:
            pb.failover_to = self.failover_to
        return pb


@dataclass(frozen=True)
class StepSettings:
    timeout: timedelta | None = None
    recovery: Recovery | None = None


@dataclass(frozen=True)
class WorkflowSettings:
    """Timeouts and recovery, enforced by the sidecar's engine, so declared rather than applied.
    Absent values are the engine's defaults: no overall limit, 30 seconds a step, a failed step
    fails the workflow."""

    timeout: timedelta | None = None
    default_step_timeout: timedelta | None = None
    default_recovery: Recovery | None = None
    steps: dict[str, StepSettings] = field(default_factory=dict)

    def to_pb(self) -> discovery_pb2.WorkflowDetail.Settings:
        pb = discovery_pb2.WorkflowDetail.Settings()
        if self.timeout is not None:
            pb.timeout_millis = _millis(self.timeout)
        if self.default_step_timeout is not None:
            pb.default_step_timeout_millis = _millis(self.default_step_timeout)
        if self.default_recovery is not None:
            pb.default_recovery.CopyFrom(self.default_recovery.to_pb())
        for name in sorted(self.steps):
            st = self.steps[name]
            entry = discovery_pb2.WorkflowDetail.StepSettings(step=name)
            if st.timeout is not None:
                entry.timeout_millis = _millis(st.timeout)
            if st.recovery is not None:
                entry.recovery.CopyFrom(st.recovery.to_pb())
            pb.steps.append(entry)
        return pb


def _millis(d: timedelta) -> int:
    return int(d.total_seconds() * 1000)


@dataclass(frozen=True)
class StepSpec:
    name: str
    method_name: str
    input_type: Any
    input_codec: Codec[Any]


def collect_steps(cls: type) -> dict[str, StepSpec]:
    found: dict[str, StepSpec] = {}
    for attr, member in inspect.getmembers(cls, predicate=inspect.isfunction):
        name = getattr(member, _STEP, None)
        if name is None:
            continue
        if name in found:
            raise RegistrationError(f"{cls.__name__}: step '{name}' is declared twice")
        hints = get_type_hints(member)
        params = [p for p in inspect.signature(member).parameters.values() if p.name != "self"]
        if len(params) > 1:
            raise RegistrationError(f"{cls.__name__}.{attr}: a step takes at most one input")
        input_type = hints.get(params[0].name) if params else None
        found[name] = StepSpec(name, attr, input_type, UNIT if input_type is None else default_codec_for(input_type))
    return found


def _check_settings(owner: str, settings: WorkflowSettings, steps: dict[str, StepSpec]) -> None:
    """The sidecar refuses these at discovery too; failing here names the class instead."""
    targets = [(f"{owner}: default recovery", settings.default_recovery)]
    for name, st in settings.steps.items():
        if name not in steps:
            raise RegistrationError(f"{owner}: settings name step '{name}', which is not declared")
        targets.append((f"{owner}: step '{name}' recovery", st.recovery))
    for what, recovery in targets:
        if recovery is not None and recovery.failover_to is not None and recovery.failover_to not in steps:
            raise RegistrationError(f"{what} fails over to '{recovery.failover_to}', which is not a declared step")


class Workflow(Generic[S]):
    component_id: ClassVar[str]
    state_codec: ClassVar[Codec[Any]]
    settings: ClassVar[WorkflowSettings | None] = None
    _handlers: ClassVar[dict[str, HandlerSpec]]
    _steps: ClassVar[dict[str, StepSpec]]

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        for required in ("component_id", "state_codec"):
            if not hasattr(cls, required):
                raise RegistrationError(f"{cls.__name__} must declare {required}")
        cls._handlers = collect_handlers(cls, effect_base=WorkflowEffect, read_only_base=WorkflowReadOnlyEffect)
        cls._steps = collect_steps(cls)
        if cls.settings is not None:
            _check_settings(cls.__name__, cls.settings, cls._steps)

    def __init__(self) -> None:
        self.effects: WorkflowEffects[S] = WorkflowEffects()
        self.step_effects: StepEffects[S] = StepEffects()
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
            raise RuntimeError("state is only available inside a handler or a step")
        return self._state

    @property
    def context(self) -> CommandContext:
        if self._context is None:
            raise RuntimeError("context is only available inside a handler or a step")
        return self._context

    @classmethod
    def handlers(cls) -> dict[str, HandlerSpec]:
        return cls._handlers

    @classmethod
    def steps(cls) -> dict[str, StepSpec]:
        return cls._steps

    @classmethod
    def to_component(cls) -> discovery_pb2.Component:
        detail = discovery_pb2.WorkflowDetail(steps=sorted(cls._steps))
        if cls.settings is not None:
            detail.settings.CopyFrom(cls.settings.to_pb())
        return discovery_pb2.Component(
            kind=discovery_pb2.WORKFLOW,
            id=cls.component_id,
            handlers=[h.to_pb() for h in sorted(cls._handlers.values(), key=lambda h: h.name)],
            workflow=detail,
        )

    async def _run(self, spec: HandlerSpec, state: S, input_bytes: bytes, context: CommandContext) -> WorkflowEffect[S, Any]:
        self._state = state
        self._context = context
        try:
            method = getattr(self, spec.method_name)
            result = method() if spec.input_type is None else method(spec.input_codec.decode(input_bytes))
            if isinstance(result, Awaitable):
                result = await result
            if not isinstance(result, WorkflowEffect):
                raise TypeError(f"{type(self).__name__}.{spec.method_name} returned {type(result).__name__}, not an effect")
            return typing.cast(WorkflowEffect[S, Any], result)
        finally:
            self._state = None
            self._context = None

    async def _run_step(self, spec: StepSpec, state: S, input_bytes: bytes | None, context: CommandContext) -> WorkflowStepEffect[S]:
        self._state = state
        self._context = context
        try:
            method = getattr(self, spec.method_name)
            if spec.input_type is None:
                result = method()
            else:
                result = method(spec.input_codec.decode(input_bytes or b""))
            if isinstance(result, Awaitable):
                result = await result
            if not isinstance(result, WorkflowStepEffect):
                raise TypeError(f"{type(self).__name__}.{spec.method_name} returned {type(result).__name__}, not a step effect")
            return typing.cast(WorkflowStepEffect[S], result)
        finally:
            self._state = None
            self._context = None
