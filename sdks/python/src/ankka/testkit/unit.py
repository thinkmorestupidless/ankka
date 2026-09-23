"""Unit testkits: one component, no sidecar, no network.

Effects are values, so a handler can be run against an in-memory state and its effect inspected.
Inputs, events, state and replies still round-trip through the component's own codecs, so a type
the default codec cannot encode fails here rather than on first deployment (the Scala testkit's
rule).
"""

from __future__ import annotations

import asyncio
import re
import typing
from dataclasses import dataclass
from typing import Any, Generic, TypeVar

from ankka.client import ComponentClient
from ankka.context import CommandContext, Metadata, Principal, RequestContext
from ankka.effects.common import Error, Fail, NoReply, Reply, Retention
from ankka.endpoint import Endpoint, HttpProblem, RouteSpec
from ankka.event_sourced_entity import EventSourcedEntity, HandlerSpec

S = TypeVar("S")
E = TypeVar("E")


@dataclass(frozen=True)
class Materialised(Generic[S, E]):
    """What running an effect against a state produced: the shape the sidecar reduces to."""

    events: tuple[E, ...]
    new_state: S
    retention: Retention | None
    reply: Any
    error: Error | None

    @property
    def persisted(self) -> bool:
        return len(self.events) > 0

    def reply_or_raise(self) -> Any:
        if self.error is not None:
            raise AssertionError(f"the command was refused: {self.error.message} ({self.error.code.name})")
        return self.reply


def _run(coro: Any) -> Any:
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.run(coro)
    raise RuntimeError("the unit testkit is synchronous; call it outside a running event loop")


class _NoClient(ComponentClient):
    """A client that refuses: a unit test must not reach the platform."""

    def __init__(self) -> None:  # noqa: D401 - deliberately not calling super
        self.address = "unit-test"
        self._metadata = Metadata()

    def with_metadata(self, metadata: Metadata) -> ComponentClient:
        return self

    def _refuse(self) -> Any:
        raise RuntimeError("a unit test cannot call other components; use the integration testkit")

    def for_event_sourced_entity(self, component_id: str, entity_id: str) -> Any:
        return self._refuse()

    def for_key_value_entity(self, component_id: str, entity_id: str) -> Any:
        return self._refuse()

    def for_workflow(self, component_id: str, workflow_id: str) -> Any:
        return self._refuse()

    def for_agent(self, component_id: str, session_id: str) -> Any:
        return self._refuse()

    async def close(self) -> None:
        return None


class EventSourcedTestKit(Generic[S, E]):
    """Drives one event sourced entity instance through its handlers, folding events as it goes."""

    def __init__(self, entity_cls: type[EventSourcedEntity[S, E]], entity_id: str) -> None:
        self.entity_cls = entity_cls
        self.entity_id = entity_id
        self.entity = entity_cls()
        self.entity._bind(entity_id)
        self.state: S = self.entity.empty_state()
        self.sequence = 0
        self.deleted = False
        self.all_events: list[E] = []

    @classmethod
    def of(cls, entity_cls: type[EventSourcedEntity[S, E]], entity_id: str = "test") -> EventSourcedTestKit[S, E]:
        return cls(entity_cls, entity_id)

    def call(self, name: str, input: Any = None, metadata: Metadata | None = None) -> Materialised[S, E]:
        spec: HandlerSpec | None = self.entity_cls.handlers().get(name)
        if spec is None:
            raise AssertionError(f"{self.entity_cls.__name__} has no handler {name!r}; declared: {sorted(self.entity_cls.handlers())}")
        # Round-trip the input through the handler's codec, as the wire would.
        input_bytes = spec.input_codec.encode(input) if spec.input_type is not None else b""
        ctx = CommandContext(self.entity_id, self.entity_cls.component_id, metadata or Metadata(), self.sequence, _NoClient())
        effect = _run(self.entity._run(spec, self.state, input_bytes, ctx))
        if isinstance(effect.outcome, Fail):
            return Materialised((), self.state, None, None, effect.outcome.error)
        codec = self.entity_cls.event_codec
        events = tuple(codec.decode(codec.encode(ev)) for ev in effect.events)  # the wire's round trip
        new_state = self.state
        for ev in events:
            new_state = self.entity.apply_event(new_state, ev)
        reply: Any = None
        if isinstance(effect.outcome, Reply):
            value = effect.outcome.compute(new_state)
            reply = spec.reply_codec.decode(spec.reply_codec.encode(value))
        elif isinstance(effect.outcome, NoReply):
            reply = None
        # The state codec must round-trip too: a snapshot would.
        sc = self.entity_cls.state_codec
        new_state = sc.decode(sc.encode(new_state))
        self.state = new_state
        self.sequence += len(events)
        self.all_events.extend(events)
        return Materialised(events, new_state, effect.retention, reply, None)


_PLACEHOLDER = re.compile(r"\{[A-Za-z_][A-Za-z0-9_]*\}")


@dataclass(frozen=True)
class Response:
    status: int
    content_type: str
    body: bytes

    def text(self) -> str:
        return self.body.decode("utf-8")

    def json(self) -> Any:
        import json

        return json.loads(self.body.decode("utf-8"))


class EndpointTestKit:
    """Calls an endpoint's routes by path, binding parameters as the sidecar's router would."""

    def __init__(self, endpoint: Endpoint) -> None:
        self.endpoint = endpoint

    @classmethod
    def of(cls, endpoint_cls: type[Endpoint], *args: Any, **kwargs: Any) -> EndpointTestKit:
        return cls(endpoint_cls(*args, **kwargs))

    def _match(self, method: str, path: str) -> tuple[RouteSpec, list[str]] | None:
        prefix = type(self.endpoint).prefix
        if not path.startswith(prefix):
            return None
        rest = path[len(prefix):] or "/"
        candidates: list[tuple[int, RouteSpec, list[str]]] = []
        for spec in type(self.endpoint).routes().values():
            if spec.method != method.upper():
                continue
            pattern = "^" + _PLACEHOLDER.sub("([^/]+)", re.escape(spec.template).replace("\\{", "{").replace("\\}", "}")) + "$"
            m = re.match(pattern, rest)
            if m:
                literal_segments = sum(1 for seg in spec.template.split("/") if seg and not seg.startswith("{"))
                candidates.append((literal_segments, spec, list(m.groups())))
        if not candidates:
            return None
        candidates.sort(key=lambda c: -c[0])  # literal segments outrank parameters
        _, spec, args = candidates[0]
        return spec, args

    def request(
        self,
        method: str,
        path: str,
        *,
        body: Any = None,
        query: list[tuple[str, str]] | None = None,
        headers: list[tuple[str, str]] | None = None,
        principal: Principal | None = None,
    ) -> Response:
        matched = self._match(method, path)
        if matched is None:
            return Response(404, "text/plain", f"no route {method} {path}".encode())
        spec, args = matched
        body_bytes = b""
        if body is not None and spec.body_codec is not None:
            body_bytes = body if isinstance(body, bytes) else spec.body_codec.encode(body)
        ctx = RequestContext(tuple(query or ()), tuple(headers or ()), principal, Metadata())
        try:
            if spec.streaming:
                frames = _run(self._collect(spec, args, body_bytes, ctx))
                return Response(200, "text/event-stream", "\n".join(frames).encode("utf-8"))
            status, content_type, out = _run(self.endpoint._handle(spec, args, body_bytes, ctx))
            return Response(status, content_type, out)
        except HttpProblem as p:
            return Response(p.status, "text/plain", p.message.encode("utf-8"))

    async def _collect(self, spec: RouteSpec, args: list[str], body: bytes, ctx: RequestContext) -> list[str]:
        return [frame async for frame in self.endpoint._handle_stream(spec, args, body, ctx)]

    def get(self, path: str, **kwargs: Any) -> Response:
        return self.request("GET", path, **kwargs)

    def post(self, path: str, body: Any = None, **kwargs: Any) -> Response:
        return self.request("POST", path, body=body, **kwargs)

    def put(self, path: str, body: Any = None, **kwargs: Any) -> Response:
        return self.request("PUT", path, body=body, **kwargs)

    def delete(self, path: str, **kwargs: Any) -> Response:
        return self.request("DELETE", path, **kwargs)


# ── The other kinds ──────────────────────────────────────────────────────────


from ankka.consumer import Consumer  # noqa: E402
from ankka.effects.consumer import ConsumerEffect  # noqa: E402
from ankka.effects.key_value import KeyValueEffect  # noqa: E402
from ankka.effects.timed_action import TimedActionEffect  # noqa: E402
from ankka.effects.view import ViewEffect  # noqa: E402
from ankka.effects.workflow import StepOutcome, StepRef, TransitionTo, WorkflowStepEffect  # noqa: E402
from ankka.key_value_entity import KeyValueEntity  # noqa: E402
from ankka.timed_action import TimedAction  # noqa: E402
from ankka.view import View  # noqa: E402
from ankka.workflow import Workflow  # noqa: E402

Row = TypeVar("Row")


@dataclass(frozen=True)
class KeyValueMaterialised(Generic[S]):
    new_state: S
    retention: Retention | None
    reply: Any
    error: Error | None
    changed: bool


class KeyValueTestKit(Generic[S]):
    def __init__(self, entity_cls: type[KeyValueEntity[S]], entity_id: str) -> None:
        self.entity_cls = entity_cls
        self.entity = entity_cls()
        self.entity.__class__  # noqa: B018 - keeps mypy honest about the generic
        self.entity._bind(entity_id)
        self.entity_id = entity_id
        self.state: S = self.entity.empty_state()

    @classmethod
    def of(cls, entity_cls: type[KeyValueEntity[S]], entity_id: str = "test") -> KeyValueTestKit[S]:
        return cls(entity_cls, entity_id)

    def call(self, name: str, input: Any = None) -> KeyValueMaterialised[S]:
        spec = self.entity_cls.handlers().get(name)
        if spec is None:
            raise AssertionError(f"{self.entity_cls.__name__} has no handler {name!r}")
        input_bytes = spec.input_codec.encode(input) if spec.input_type is not None else b""
        ctx = CommandContext(self.entity_id, self.entity_cls.component_id, Metadata(), 0, _NoClient())
        effect: KeyValueEffect[S, Any] = _run(self.entity._run(spec, self.state, input_bytes, ctx))
        if isinstance(effect.outcome, Fail):
            return KeyValueMaterialised(self.state, None, None, effect.outcome.error, False)
        sc = self.entity_cls.state_codec
        new_state = self.state if effect.new_state is None else sc.decode(sc.encode(effect.new_state))
        reply: Any = None
        if isinstance(effect.outcome, Reply):
            reply = spec.reply_codec.decode(spec.reply_codec.encode(effect.outcome.compute(new_state)))
        changed = effect.new_state is not None
        self.state = new_state
        return KeyValueMaterialised(new_state, effect.retention, reply, None, changed)


@dataclass(frozen=True)
class WorkflowMaterialised(Generic[S]):
    new_state: S
    transition: StepRef | None
    reply: Any
    error: Error | None


@dataclass(frozen=True)
class StepMaterialised(Generic[S]):
    new_state: S
    next: StepOutcome


class WorkflowTestKit(Generic[S]):
    """Drives commands and steps by hand: ``call`` runs a command, ``run_step`` a step, and
    ``run_until_end`` follows transitions (not pauses) until the workflow ends."""

    def __init__(self, workflow_cls: type[Workflow[S]], workflow_id: str) -> None:
        self.workflow_cls = workflow_cls
        self.workflow = workflow_cls()
        self.workflow._bind(workflow_id)
        self.workflow_id = workflow_id
        self.state: S = self.workflow.empty_state()
        self.pending: StepRef | None = None
        self.transitions: list[str] = []

    @classmethod
    def of(cls, workflow_cls: type[Workflow[S]], workflow_id: str = "test") -> WorkflowTestKit[S]:
        return cls(workflow_cls, workflow_id)

    def call(self, name: str, input: Any = None) -> WorkflowMaterialised[S]:
        spec = self.workflow_cls.handlers().get(name)
        if spec is None:
            raise AssertionError(f"{self.workflow_cls.__name__} has no handler {name!r}")
        input_bytes = spec.input_codec.encode(input) if spec.input_type is not None else b""
        ctx = CommandContext(self.workflow_id, self.workflow_cls.component_id, Metadata(), 0, _NoClient())
        effect = _run(self.workflow._run(spec, self.state, input_bytes, ctx))
        if isinstance(effect.outcome, Fail):
            return WorkflowMaterialised(self.state, None, None, effect.outcome.error)
        new_state = self.state if effect.new_state is None else effect.new_state
        reply: Any = None
        if isinstance(effect.outcome, Reply):
            reply = spec.reply_codec.decode(spec.reply_codec.encode(effect.outcome.compute(new_state)))
        self.state = new_state
        if effect.transition is not None:
            self.pending = effect.transition
            self.transitions.append(effect.transition.step)
        return WorkflowMaterialised(new_state, effect.transition, reply, None)

    def run_step(self, step: str | None = None, input: Any = None) -> StepMaterialised[S]:
        ref = StepRef(step, input) if step is not None else self.pending
        if ref is None:
            raise AssertionError("no step is pending; name one")
        spec = self.workflow_cls.steps().get(ref.step)
        if spec is None:
            raise AssertionError(f"{self.workflow_cls.__name__} has no step {ref.step!r}")
        input_bytes = spec.input_codec.encode(ref.input) if spec.input_type is not None else None
        ctx = CommandContext(self.workflow_id, self.workflow_cls.component_id, Metadata(), 0, _NoClient())
        effect: WorkflowStepEffect[S] = _run(self.workflow._run_step(spec, self.state, input_bytes, ctx))
        if effect.new_state is not None:
            self.state = effect.new_state
        self.pending = effect.next.ref if isinstance(effect.next, TransitionTo) else None
        if self.pending is not None:
            self.transitions.append(self.pending.step)
        return StepMaterialised(self.state, effect.next)

    def run_until_end(self, limit: int = 100) -> StepOutcome:
        last: StepOutcome | None = None
        for _ in range(limit):
            if self.pending is None:
                break
            last = self.run_step().next
        if last is None:
            raise AssertionError("nothing to run")
        return last


class ViewTestKit(Generic[Row]):
    """Feeds events to a view, keeping one row per key as the sidecar's projection would."""

    def __init__(self, view_cls: type[View[Any, Row]]) -> None:
        self.view_cls = view_cls
        self.rows: dict[str, Row] = {}

    @classmethod
    def of(cls, view_cls: type[View[Any, Row]]) -> ViewTestKit[Row]:
        return cls(view_cls)

    def on_change(self, key: str, event: Any) -> ViewEffect:
        view = self.view_cls()
        ec, rc = self.view_cls.event_codec, self.view_cls.row_codec
        current = self.rows.get(key)
        effect = _run(view._handle(ec.encode(event), rc.encode(current) if current is not None else None, Metadata()))
        from ankka.effects.view import DeleteRow, UpdateRow

        if isinstance(effect, UpdateRow):
            self.rows[key] = rc.decode(rc.encode(effect.row))
        elif isinstance(effect, DeleteRow):
            self.rows.pop(key, None)
        return typing.cast(ViewEffect, effect)

    def get(self, key: str) -> Row | None:
        return self.rows.get(key)


class ConsumerTestKit:
    def __init__(self, consumer_cls: type[Consumer[Any, Any]]) -> None:
        self.consumer_cls = consumer_cls
        self.produced: list[Any] = []

    @classmethod
    def of(cls, consumer_cls: type[Consumer[Any, Any]]) -> ConsumerTestKit:
        return cls(consumer_cls)

    def on_message(self, message: Any) -> ConsumerEffect:
        consumer = self.consumer_cls(_NoClient())
        mc = self.consumer_cls.message_codec
        effect = _run(consumer._handle(mc.encode(message), Metadata()))
        from ankka.effects.consumer import Produce

        if isinstance(effect, Produce):
            oc = self.consumer_cls.out_codec
            assert oc is not None
            self.produced.append(oc.decode(oc.encode(effect.payload)))
        return typing.cast(ConsumerEffect, effect)


class TimedActionTestKit:
    def __init__(self, action_cls: type[TimedAction]) -> None:
        self.action_cls = action_cls

    @classmethod
    def of(cls, action_cls: type[TimedAction]) -> TimedActionTestKit:
        return cls(action_cls)

    def call(self, name: str, input: Any = None) -> TimedActionEffect:
        spec = self.action_cls.handlers().get(name)
        if spec is None:
            raise AssertionError(f"{self.action_cls.__name__} has no handler {name!r}")
        input_bytes = spec.input_codec.encode(input) if spec.input_type is not None else b""
        action = self.action_cls(_NoClient())
        return typing.cast(TimedActionEffect, _run(action._run(spec, input_bytes, Metadata())))
