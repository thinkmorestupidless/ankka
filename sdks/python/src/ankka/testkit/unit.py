"""Unit testkits: one component, no sidecar, no network.

Effects are values, so a handler can be run against an in-memory state and its effect inspected.
Inputs, events, state and replies still round-trip through the component's own codecs, so a type
the default codec cannot encode fails here rather than on first deployment (the Scala testkit's
rule).
"""

from __future__ import annotations

import asyncio
import re
import time
import typing
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Any, Generic, TypeVar

from ankka.agent import Agent
from ankka.approvals import ApprovalRequest, approval_of
from ankka.autonomous import AutonomousAgent, Malformed, TaskType
from ankka.client import ComponentClient
from ankka.context import Caller, CommandContext, LocalCaller, Metadata, Principal, RequestContext
from ankka.effects.agent import AgentEffect
from ankka.effects.common import Error, ErrorCode, Fail, NoReply, Reply, Retention
from ankka.endpoint import Endpoint, HttpProblem, RouteSpec, Socket, SocketClosed
from ankka.event_sourced_entity import EventSourcedEntity, HandlerSpec
from ankka.mcp import TOOL_PREFIX

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
class SocketRun:
    """What a socket route's handler did with a scripted socket: the frames it sent, in order, and
    how it ended — ``"finished"`` when it returned (or let ``SocketClosed`` escape), ``"failed"``
    when it raised, with ``error``."""

    sent: list[str]
    ended: str
    error: Exception | None = None


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
            # A socket route is opened through `socket`, never answered as a GET.
            if ("SOCKET" if spec.socket else spec.method) != method.upper():
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
        caller: Caller | None = None,
    ) -> Response:
        """``caller`` is what ``request.caller`` reads; the ACL itself is the sidecar's to apply, so a
        handler test states the caller it expects to have been admitted."""
        matched = self._match(method, path)
        if matched is None:
            return Response(404, "text/plain", f"no route {method} {path}".encode())
        spec, args = matched
        body_bytes = b""
        if body is not None and spec.body_codec is not None:
            body_bytes = body if isinstance(body, bytes) else spec.body_codec.encode(body)
        ctx = RequestContext(
            tuple(query or ()), tuple(headers or ()), principal, Metadata(), caller if caller is not None else LocalCaller()
        )
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

    def socket(
        self,
        path: str,
        frames: list[str] | None = None,
        *,
        query: list[tuple[str, str]] | None = None,
        headers: list[tuple[str, str]] | None = None,
        principal: Principal | None = None,
        caller: Caller | None = None,
    ) -> SocketRun:
        """Opens a socket route with no runtime: the handler is given ``frames`` one at a time and
        then told the socket is closed, as when the client closes it after sending them."""
        prefix = type(self.endpoint).prefix
        matched = self._match("SOCKET", path)
        if matched is None:
            raise ValueError(f"no socket route at {path} under {prefix}")
        spec, args = matched
        ctx = RequestContext(
            tuple(query or ()), tuple(headers or ()), principal, Metadata(), caller if caller is not None else LocalCaller()
        )
        pending = list(frames or [])
        sent: list[str] = []
        closed = False

        async def receive() -> str | None:
            nonlocal closed
            if pending:
                return pending.pop(0)
            closed = True
            return None

        async def send(text: str) -> None:
            if closed:
                raise SocketClosed("the socket is closed")
            sent.append(text)

        try:
            _run(self.endpoint._handle_socket(spec, args, Socket(receive, send), ctx))
            return SocketRun(sent, "finished")
        except SocketClosed:
            return SocketRun(sent, "finished")
        except Exception as e:
            return SocketRun(sent, "failed", e)

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
from ankka.graph import Element, GraphConsumer  # noqa: E402
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
        # As the sidecar sends it: the source's id under ce-subject.
        effect = _run(view._handle(ec.encode(event), rc.encode(current) if current is not None else None, Metadata().set("ce-subject", key)))
        from ankka.effects.view import DeleteRow, UpdateRow

        if isinstance(effect, UpdateRow):
            self.rows[key] = rc.decode(rc.encode(effect.row))
        elif isinstance(effect, DeleteRow):
            self.rows.pop(key, None)
        return typing.cast(ViewEffect, effect)

    def on_delete(self, key: str) -> ViewEffect:
        """The source with this key was deleted."""
        view = self.view_cls()
        rc = self.view_cls.row_codec
        current = self.rows.get(key)
        effect = _run(view._handle(None, rc.encode(current) if current is not None else None, Metadata().set("ce-subject", key)))
        from ankka.effects.view import DeleteRow, UpdateRow

        if isinstance(effect, UpdateRow):
            self.rows[key] = rc.decode(rc.encode(effect.row))
        elif isinstance(effect, DeleteRow):
            self.rows.pop(key, None)
        return typing.cast(ViewEffect, effect)

    def get(self, key: str) -> Row | None:
        return self.rows.get(key)


@dataclass(frozen=True)
class Produced:
    """One message a consumer produced: its payload after the wire's round trip, the record key it
    named (None: its subject), and its metadata."""

    payload: Any
    key: str | None
    metadata: Metadata


def _change_metadata(subject: str, sequence: int | None) -> Metadata:
    """As the runtime sends a change: the source's id, its sequence number when it has one, and
    the protocol the runtime speaks — this SDK's own, so several messages are accepted."""
    from ankka.service import PROTOCOL_VERSION

    metadata = Metadata().set("ce-subject", subject)
    if sequence is not None:
        metadata = metadata.set("ankka.sequence", str(sequence))
    return metadata.set("ankka.protocol", PROTOCOL_VERSION)


class ConsumerTestKit:
    """Hands a consumer a change and collects what it produced, with no sidecar and no broker.
    ``produced`` holds each message's payload, one entry per message; ``messages`` holds the same
    messages with the record key each named and its metadata."""

    def __init__(self, consumer_cls: type[Consumer[Any, Any]]) -> None:
        self.consumer_cls = consumer_cls
        self.produced: list[Any] = []
        self.messages: list[Produced] = []

    @classmethod
    def of(cls, consumer_cls: type[Consumer[Any, Any]]) -> ConsumerTestKit:
        return cls(consumer_cls)

    def on_message(self, message: Any, subject: str = "test", *, sequence: int | None = None) -> ConsumerEffect:
        """``sequence`` is the change's sequence number, as ``self.metadata.sequence_number``."""
        mc = self.consumer_cls.message_codec
        return self._handle(mc.encode(message), subject, sequence)

    def on_delete(self, subject: str = "test", *, sequence: int | None = None) -> ConsumerEffect:
        return self._handle(None, subject, sequence)

    def _handle(self, message_bytes: bytes | None, subject: str, sequence: int | None) -> ConsumerEffect:
        consumer = self.consumer_cls(_NoClient())
        effect = _run(consumer._handle(message_bytes, _change_metadata(subject, sequence)))
        from ankka.effects.consumer import Produce, ProduceAll

        oc = self.consumer_cls.out_codec
        if isinstance(effect, Produce):
            assert oc is not None
            self._record(Produced(oc.decode(oc.encode(effect.payload)), None, effect.metadata))
        elif isinstance(effect, ProduceAll) and effect.messages:
            assert oc is not None, f"{self.consumer_cls.__name__} produced messages and declares no out_codec"
            for message in effect.messages:
                self._record(Produced(oc.decode(oc.encode(message.payload)), message.key, message.metadata))
        return typing.cast(ConsumerEffect, effect)

    def _record(self, produced: Produced) -> None:
        self.produced.append(produced.payload)
        self.messages.append(produced)


class GraphConsumerTestKit:
    """Hands a graph consumer a change and returns the elements it published: each read back from
    the bytes that would be on the topic, under the key it would have. No sidecar, no broker."""

    def __init__(self, consumer_cls: type[GraphConsumer[Any]], client: ComponentClient | None = None) -> None:
        self.consumer_cls = consumer_cls
        self.client = client if client is not None else _NoClient()

    @classmethod
    def of(cls, consumer_cls: type[GraphConsumer[Any]], client: ComponentClient | None = None) -> GraphConsumerTestKit:
        return cls(consumer_cls, client)

    def on_message(self, message: Any, subject: str = "test", *, sequence: int = 1) -> list[Element]:
        """``sequence`` is the change's sequence number: the version of every element that states
        none. Zero is what a topic's message has, and such a change needs versions stated."""
        mc = self.consumer_cls.message_codec
        return self._handle(mc.encode(message), subject, sequence)

    def on_delete(self, subject: str = "test", *, sequence: int = 1) -> list[Element]:
        return self._handle(None, subject, sequence)

    def _handle(self, message_bytes: bytes | None, subject: str, sequence: int) -> list[Element]:
        from ankka.effects.consumer import ProduceAll
        from ankka.graph import read

        consumer = self.consumer_cls(self.client)
        effect = _run(consumer._handle(message_bytes, _change_metadata(subject, sequence)))
        if not isinstance(effect, ProduceAll):
            return []
        codec = self.consumer_cls.out_codec
        return [read(codec.encode(message.payload), message.key) for message in effect.messages]


@dataclass(frozen=True)
class ToolCall:
    name: str
    arguments: dict[str, Any]


@dataclass(frozen=True)
class AgentReply:
    """One interaction as the sidecar's loop would run it: the plan the handler produced, the
    tool calls the scripted model asked for (run in-process), and the reply or the refusal — or,
    when the model called a tool that requires approval, the requests the turn ``awaiting``."""

    plan: AgentEffect[Any]
    tool_calls: list[ToolCall]
    tool_results: list[str]
    reply: Any
    error: Error | None
    awaiting: list[ApprovalRequest] = field(default_factory=list)


class ScriptedModel:
    """Answers from a script, in order — and fails loudly when it runs out: a test whose model
    quietly returned a default is no longer testing what it says."""

    def __init__(self) -> None:
        self._script: list[tuple[str, Any]] = []
        self.requests: list[AgentEffect[Any]] = []

    def expect_text(self, text: str) -> ScriptedModel:
        self._script.append(("text", text))
        return self

    def expect_tool_call(self, name: str, arguments: dict[str, Any] | None = None) -> ScriptedModel:
        self._script.append(("tool", ToolCall(name, arguments or {})))
        return self

    def expect_refusal(self, reason: str) -> ScriptedModel:
        self._script.append(("refusal", reason))
        return self

    def _next(self) -> tuple[str, Any]:
        if not self._script:
            raise AssertionError("the scripted model ran out of script; add expect_text or expect_tool_call")
        return self._script.pop(0)


McpTools = Mapping[str, Mapping[str, Callable[[dict[str, Any]], str]]]


@dataclass
class _Turn:
    """A turn in progress: what the loop has done so far, and the request it waits on, if any."""

    spec: HandlerSpec
    plan: AgentEffect[Any]
    agent: Agent
    user: str
    calls: list[ToolCall]
    results: list[str]
    steps: int = 0
    pending: tuple[ApprovalRequest, ToolCall] | None = None


class AgentTestKit:
    """Runs an agent's handler and then the loop the sidecar would run, against a scripted
    model: tools are invoked in-process with the scripted arguments, guardrails are checked.

    ``mcp`` scripts the agent's MCP servers, ``{server: {tool: fn(arguments) -> text}}``: the
    model calls them as ``mcp__<server>__<tool>`` and the agent's result guardrails check what they
    answer. A call to a tool that requires approval — the agent's own, or any of a server listed
    with ``approval`` — is not run: the reply is ``awaiting`` its request, and ``decide`` goes on."""

    def __init__(
        self, agent_cls: type[Agent], session_id: str, model: ScriptedModel | None = None, mcp: McpTools | None = None
    ) -> None:
        self.agent_cls = agent_cls
        self.session_id = session_id
        self.model = model or ScriptedModel()
        self.mcp: McpTools = mcp or {}
        self.history: list[tuple[str, str]] = []
        self._turn: _Turn | None = None
        self._decided: set[str] = set()
        self._next_id = 0

    @classmethod
    def of(
        cls, agent_cls: type[Agent], session_id: str = "test", model: ScriptedModel | None = None, mcp: McpTools | None = None
    ) -> AgentTestKit:
        return cls(agent_cls, session_id, model, mcp)

    def call(self, name: str, input: Any = None) -> AgentReply:
        spec = self.agent_cls.handlers().get(name)
        if spec is None:
            raise AssertionError(f"{self.agent_cls.__name__} has no handler {name!r}")
        if self._turn is not None and self._turn.pending is not None:
            return AgentReply(
                self._turn.plan, [], [], None,
                Error(f"session '{self.session_id}' has a turn awaiting a decision", ErrorCode.CONFLICT),
            )
        input_bytes = spec.input_codec.encode(input) if spec.input_type is not None else b""
        agent = self.agent_cls(_NoClient())
        plan: AgentEffect[Any] = _run(agent._plan(spec, input_bytes, self.session_id, Metadata()))
        self.model.requests.append(plan)
        if plan.failure is not None:
            return AgentReply(plan, [], [], None, plan.failure)
        user = plan.user or ""
        for g in plan.guardrail_names:
            reason = _run(agent._check_guardrail(g, "input", user, self.session_id))
            if reason is not None:
                return AgentReply(plan, [], [], None, Error(f"guardrail '{g}': {reason}", ErrorCode.FORBIDDEN))
        self._turn = _Turn(spec, plan, agent, user, [], [])
        return self._go_on(self._turn)

    def decide(self, approval_id: str, approved: bool, by: str, note: str | None = None) -> AgentReply:
        """Decides the request the last reply awaited, and goes on with the turn as the sidecar
        would: approved, the tool runs and the model is told its result; refused, the model is told
        who refused it and why."""
        turn = self._turn
        plan = turn.plan if turn is not None else AgentEffect()
        if not by.strip():
            return AgentReply(plan, [], [], None, Error("a decision must name who made it", ErrorCode.BAD_REQUEST))
        if approval_id in self._decided:
            return AgentReply(plan, [], [], None, Error(f"approval request '{approval_id}' is decided", ErrorCode.CONFLICT))
        if turn is None or turn.pending is None or turn.pending[0].id != approval_id:
            return AgentReply(
                plan, [], [], None,
                Error(f"no approval request '{approval_id}' in session '{self.session_id}'", ErrorCode.NOT_FOUND),
            )
        _, call = turn.pending
        turn.pending = None
        self._decided.add(approval_id)
        if approved:
            turn.results.append(self._run_tool(turn, call))
        else:
            refusal = f"A person ({by}) refused this tool call; the tool did not run."
            turn.results.append(refusal + (f" Their note: {note}" if note else ""))
        return self._go_on(turn)

    def _requires_approval(self, name: str) -> bool:
        if name.startswith(TOOL_PREFIX):
            server = name[len(TOOL_PREFIX):].split("__", 1)[0]
            declared = self.agent_cls.mcp_servers.get(server)
            return declared is not None and approval_of(declared.approval) is not None
        tool = self.agent_cls.tools.get(name)
        return tool is not None and approval_of(tool.approval) is not None

    def _offered(self, turn: _Turn, name: str) -> bool:
        if name.startswith(TOOL_PREFIX):
            server, _, tool = name[len(TOOL_PREFIX):].partition("__")
            return server in self.agent_cls.mcp_servers and tool in self.mcp.get(server, {})
        return name in turn.plan.tool_names

    def _run_tool(self, turn: _Turn, call: ToolCall) -> str:
        import json as _json

        if call.name.startswith(TOOL_PREFIX):
            server, _, tool = call.name[len(TOOL_PREFIX):].partition("__")
            try:
                text = self.mcp[server][tool](call.arguments)
            except Exception as e:
                return f"error: {e}"
            for g in sorted(self.agent_cls.result_guardrails):
                reason = _run(turn.agent._check_tool_result(g, call.name, text, self.session_id))
                if reason is not None:
                    return f"A result guardrail refused what this tool answered, so it is not shown: guardrail '{g}': {reason}"
            return text
        try:
            return typing.cast(str, _run(turn.agent._invoke_tool(call.name, _json.dumps(call.arguments), self.session_id)))
        except Exception as e:
            return f"error: {e}"

    def _go_on(self, turn: _Turn) -> AgentReply:
        while True:
            kind, value = self.model._next()
            if kind == "refusal":
                self._turn = None
                return AgentReply(turn.plan, turn.calls, turn.results, None, Error(value, ErrorCode.FORBIDDEN))
            if kind == "tool":
                turn.steps += 1
                if turn.steps > self.agent_cls.max_tool_call_steps:
                    self._turn = None
                    return AgentReply(
                        turn.plan, turn.calls, turn.results, None,
                        Error(f"exceeded {self.agent_cls.max_tool_call_steps} tool-call steps", ErrorCode.INTERNAL),
                    )
                call = typing.cast(ToolCall, value)
                turn.calls.append(call)
                if not self._offered(turn, call.name):
                    turn.results.append(f"no tool named '{call.name}' is available")
                    continue
                if self._requires_approval(call.name):
                    self._next_id += 1
                    request = ApprovalRequest(
                        id=f"approval-{self._next_id}", tool=call.name, arguments=call.arguments, requested_at=int(time.time() * 1000)
                    )
                    turn.pending = (request, call)
                    return AgentReply(turn.plan, turn.calls, turn.results, None, None, [request])
                turn.results.append(self._run_tool(turn, call))
                continue
            text = typing.cast(str, value)
            self._turn = None
            for g in turn.plan.guardrail_names:
                reason = _run(turn.agent._check_guardrail(g, "output", text, self.session_id))
                if reason is not None:
                    return AgentReply(turn.plan, turn.calls, turn.results, None, Error(f"guardrail '{g}': {reason}", ErrorCode.FORBIDDEN))
            if turn.plan.session_memory:
                self.history.append((turn.user, text))
            reply: Any = turn.spec.reply_codec.decode(text.encode("utf-8")) if turn.plan.json_reply else text
            return AgentReply(turn.plan, turn.calls, turn.results, reply, None)


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


class AutonomousAgentTestKit:
    """Runs an autonomous agent's pieces directly: its tools, the check of a result against a task
    type, and its guardrails. The loop itself is the sidecar's, and is tested there — through the
    integration testkit's scripted sidecar — rather than re-implemented here.

    ``client`` is what a tool calls other components through; the default refuses every call, so a
    tool that reaches for one says so."""

    def __init__(self, agent_cls: type[AutonomousAgent], client: ComponentClient | None = None) -> None:
        self.agent_cls = agent_cls
        self.client = client

    @classmethod
    def of(cls, agent_cls: type[AutonomousAgent], client: ComponentClient | None = None) -> AutonomousAgentTestKit:
        return cls(agent_cls, client)

    async def run_tool(self, name: str, arguments: dict[str, Any], task_id: str = "test-task") -> str:
        """Runs the tool with the model's arguments, as the sidecar would, and answers its text."""
        import json

        agent = self.agent_cls(self.client)
        return await agent._invoke_tool(name, json.dumps(arguments), f"task:{task_id}")

    async def check_result(self, task_type: TaskType[Any], result: Any, task_id: str = "test-task") -> Malformed | tuple[str, str] | None:
        """What the sidecar would be told about ``result`` for ``task_type``: ``None`` when it stands,
        ``(rule, reason)`` for the first rule that rejects it, ``Malformed`` when it does not decode."""
        import dataclasses
        import json

        text = result if isinstance(result, str) and task_type.result is not None else json.dumps(
            dataclasses.asdict(result) if dataclasses.is_dataclass(result) and not isinstance(result, type) else result
        )
        agent = self.agent_cls(self.client)
        return await agent._check_result(task_type.name, text, task_id)

    async def check_guardrail(self, name: str, stage: str, text: str, task_id: str = "test-task") -> str | None:
        """The reason the guardrail blocks ``text`` at ``stage`` ("input" or "output"), or ``None``."""
        agent = self.agent_cls(self.client)
        return await agent._check_guardrail(name, stage, text, f"task:{task_id}")

