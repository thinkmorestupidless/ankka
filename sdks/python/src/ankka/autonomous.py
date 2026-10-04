"""Autonomous agents: a durable agent that is handed a task and works it on its own, iteration by
iteration, until it completes the task with a result its type's rules accept, fails it, or spends
its budget.

Your process declares the agent — what it is for, its tools and guardrails, and the task types it
accepts, each with a result type, rules and an iteration budget. The sidecar runs everything else:
the loop, the model, the task records and the instance's own record. It asks this process for three
things only — run a tool, check a guardrail, check a task rule — each naming the task it is for.

A tool may run more than once for one request of the model: after a crash, the tools of the last
recorded model response run again. A tool with a side effect should tolerate that.
"""

from __future__ import annotations

import asyncio
import json
import time
import typing
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any, ClassVar, Generic, TypeVar

from ankka._proto.ankka.protocol.v1 import client_pb2, discovery_pb2, payload_pb2
from ankka.agent import Guardrail, Tool, _schema_for
from ankka.codec import default_codec_for
from ankka.effects.common import Error, ErrorCode
from ankka.event_sourced_entity import RegistrationError
from ankka.secrets import HasSecrets
from ankka.services import HasServices

if typing.TYPE_CHECKING:
    from ankka.client import ComponentClient

R = TypeVar("R")

COMPLETE_TASK = "complete_task"
FAIL_TASK = "fail_task"
TASK_COMPONENT = "ankka-task"
INSTANCE_COMPONENT = "ankka-agent-instance"


# ── Declaring ────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Accepted:
    """A rule's verdict: the result stands."""


@dataclass(frozen=True)
class Rejected:
    """A rule's verdict: the result does not stand, and ``reason`` goes back to the model."""

    reason: str


@dataclass(frozen=True)
class Malformed:
    """A result that does not decode as its task type's: the model is told ``problem``."""

    problem: str


@dataclass(frozen=True)
class TaskRule:
    """A check a result must pass: ``check(result)`` answers ``Accepted()`` or ``Rejected(reason)``.
    Rules run in the order they are declared, and the first rejection is the one reported. A rule
    that raises has not decided anything: the iteration is tried again."""

    name: str
    check: Callable[[Any], Accepted | Rejected]


@dataclass(frozen=True)
class TaskType(Generic[R]):
    """A kind of work. ``name`` is the wire name, written into every task of this type. ``result``
    is the result's type — a dataclass, whose fields are the schema the model sees — or ``None``
    for a result that is text."""

    name: str
    description: str
    result: Any = None
    rules: tuple[TaskRule, ...] = ()

    def __post_init__(self) -> None:
        if not self.name:
            raise RegistrationError("a task type needs a name")
        if not self.description:
            raise RegistrationError(f"task type '{self.name}' needs a description")
        names = [r.name for r in self.rules]
        if len(set(names)) != len(names):
            raise RegistrationError(f"task type '{self.name}' declares a rule twice")

    def result_schema_json(self) -> str | None:
        return json.dumps(_schema_for(self.result)) if self.result is not None else None

    def decode(self, stored: str) -> Any:
        """A stored result, as this type's result."""
        if self.result is None:
            return json.loads(stored)
        return default_codec_for(self.result).decode(stored.encode("utf-8"))


@dataclass(frozen=True)
class TaskAcceptance:
    """Accepts tasks of ``task_type``, spending at most ``max_iterations`` model calls on each."""

    task_type: TaskType[Any]
    max_iterations: int = 10


@dataclass(frozen=True)
class AutonomousSettings:
    """When an instance warns that it is struggling, and when it gives up. ``None`` keeps the
    sidecar's default."""

    approaching_budget_at: float | None = None
    repeated_failure_at: int | None = None
    max_consecutive_failures: int | None = None
    dependency_stuck_after_millis: int | None = None

    def to_pb(self) -> discovery_pb2.AutonomousAgentDetail.AutonomousSettings:
        pb = discovery_pb2.AutonomousAgentDetail.AutonomousSettings()
        if self.approaching_budget_at is not None:
            pb.approaching_budget_at = self.approaching_budget_at
        if self.repeated_failure_at is not None:
            pb.repeated_failure_at = self.repeated_failure_at
        if self.max_consecutive_failures is not None:
            pb.max_consecutive_failures = self.max_consecutive_failures
        if self.dependency_stuck_after_millis is not None:
            pb.dependency_stuck_after_millis = self.dependency_stuck_after_millis
        return pb


class AutonomousAgent(HasSecrets, HasServices):
    """Subclass this: ``component_id``, ``description``, optionally ``instructions``, ``tools =
    {name: Tool(...)}``, ``guardrails = {name: Guardrail(...)}``, ``accepts = [TaskAcceptance(...)]``
    and ``settings``. A tool's ``run`` receives the agent instance first, as for an ``Agent``, and
    ``self.task_id`` names the task it is being run for."""

    component_id: ClassVar[str]
    description: ClassVar[str]
    instructions: ClassVar[str | None] = None
    model: ClassVar[str | None] = None
    tools: ClassVar[dict[str, Tool]] = {}
    guardrails: ClassVar[dict[str, Guardrail]] = {}
    accepts: ClassVar[list[TaskAcceptance]] = []
    settings: ClassVar[AutonomousSettings | None] = None

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        problems: list[str] = []
        if not getattr(cls, "component_id", ""):
            problems.append("component_id is required")
        if not getattr(cls, "description", ""):
            problems.append("a description is required")
        if not cls.accepts:
            problems.append("it accepts no task type: set accepts = [TaskAcceptance(...)]")
        names = [a.task_type.name for a in cls.accepts]
        for name in sorted({n for n in names if names.count(n) > 1}):
            problems.append(f"task type '{name}' is accepted {names.count(name)} times")
        for a in cls.accepts:
            if a.max_iterations < 1:
                problems.append(f"task type '{a.task_type.name}' needs a budget of at least one iteration")
        for name, tool in cls.tools.items():
            if name in (COMPLETE_TASK, FAIL_TASK):
                problems.append(f"tool name '{name}' is reserved")
            if not tool.description:
                problems.append(f"tool '{name}' needs a description; the model decides by it")
        if problems:
            raise RegistrationError(f"invalid autonomous agent {cls.__name__}: " + "; ".join(problems))

    def __init__(self, client: ComponentClient | None = None) -> None:
        self.client = client
        self._session_id = ""

    @property
    def task_id(self) -> str:
        """The task a tool, a guardrail or a rule is running for."""
        return self._session_id.removeprefix("task:")

    @classmethod
    def task_types(cls) -> dict[str, TaskType[Any]]:
        return {a.task_type.name: a.task_type for a in cls.accepts}

    @classmethod
    def to_component(cls) -> discovery_pb2.Component:
        detail = discovery_pb2.AutonomousAgentDetail(
            description=cls.description,
            tools=[
                discovery_pb2.Tool(name=n, description=t.description, input_schema_json=t.input_schema())
                for n, t in sorted(cls.tools.items())
            ],
            guardrails=sorted(cls.guardrails),
            task_types=[
                discovery_pb2.AutonomousAgentDetail.TaskType(
                    name=t.name,
                    description=t.description,
                    result_schema_json=t.result_schema_json(),
                    rules=[r.name for r in t.rules],
                )
                for t in cls.task_types().values()
            ],
            accepts=[
                discovery_pb2.AutonomousAgentDetail.TaskAcceptance(task_type=a.task_type.name, max_iterations=a.max_iterations)
                for a in cls.accepts
            ],
        )
        if cls.instructions is not None:
            detail.instructions = cls.instructions
        if cls.model is not None:
            detail.model = cls.model
        if cls.settings is not None:
            detail.settings.CopyFrom(cls.settings.to_pb())
        return discovery_pb2.Component(kind=discovery_pb2.AUTONOMOUS_AGENT, id=cls.component_id, autonomous_agent=detail)

    # ── What the server and the testkit drive ──────────────────────────────

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

    async def _check_result(self, task_type: str, result_json: str, task_id: str) -> Malformed | tuple[str, str] | None:
        """Decodes a result as the task type's and runs its rules in order: ``Malformed`` when it
        does not decode, ``(rule, reason)`` for the first rule that rejects it, ``None`` when it
        stands. A rule that raises propagates: it has decided nothing."""
        self._session_id = f"task:{task_id}"
        declared = type(self).task_types().get(task_type)
        if declared is None:
            raise LookupError(f"no task type '{task_type}' on {type(self).__name__}")
        try:
            result = declared.decode(result_json)
        except Exception as e:
            return Malformed(str(e) or type(e).__name__)
        for rule in declared.rules:
            verdict: Any = rule.check(result)
            if isinstance(verdict, Awaitable):
                verdict = await verdict
            if isinstance(verdict, Rejected):
                return (rule.name, verdict.reason)
        return None


# ── Calling ──────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Attachment:
    """Content that travels with a task: ``content`` inline, or ``uri`` for the agent's tools to fetch."""

    name: str
    content_type: str
    content: str | None = None
    uri: str | None = None

    def to_json(self) -> dict[str, Any]:
        body = {"type": "Inline", "text": self.content} if self.uri is None else {"type": "Reference", "uri": self.uri}
        return {"name": self.name, "contentType": self.content_type, "content": body}


@dataclass(frozen=True)
class TaskSnapshot(Generic[R]):
    """A task as its record says it is. ``result`` is decoded as the task type's when one was given."""

    id: str
    type_name: str
    status: str
    result: R | None
    reason: str | None
    iterations: int
    assignee: tuple[str, str] | None
    record: dict[str, Any] = field(repr=False)

    @property
    def ended(self) -> bool:
        return self.status in ("completed", "failed", "cancelled")


@dataclass(frozen=True)
class AgentState:
    phase: str
    suspended: bool
    terminated: bool
    current_task: str | None
    iteration: int
    queued: list[str]


@dataclass(frozen=True)
class Notification:
    """One thing an instance did. ``type`` names it — ``TaskCompleted``, ``IterationStarted``… —
    and ``detail`` holds the rest of its fields as sent."""

    type: str
    component_id: str
    instance_id: str
    task_id: str | None
    at: int
    detail: dict[str, Any]

    def to_json(self) -> str:
        """The notification as the platform sends it: its common fields, and its own."""
        body: dict[str, Any] = {"type": self.type, "componentId": self.component_id, "instanceId": self.instance_id}
        if self.task_id is not None:
            body["taskId"] = self.task_id
        body.update(self.detail)
        body["at"] = self.at
        return json.dumps(body)

    @staticmethod
    def from_json(text: str) -> Notification:
        body = json.loads(text)
        return Notification(
            type=body.pop("type"),
            component_id=body.pop("componentId"),
            instance_id=body.pop("instanceId"),
            task_id=body.pop("taskId", None),
            at=body.pop("at"),
            detail=body,
        )


class _Raw:
    """Calls with JSON payloads, as the platform's own entities read them."""

    def __init__(self, client: ComponentClient) -> None:
        self._client = client

    async def call(self, kind: Any, component_id: str, entity_id: str, name: str, body: Any = None, manifest: str = "") -> Any:
        from ankka.client import CommandError

        data = b"" if body is None else json.dumps(body).encode("utf-8")
        request = client_pb2.InvokeRequest(
            kind=kind,
            component_id=component_id,
            entity_id=entity_id,
            name=name,
            payload=payload_pb2.Payload(content_type="application/json", manifest=manifest, data=data),
            metadata=self._client._metadata.to_pb(),
        )
        answer = await self._client._stub.Invoke(request)
        if answer.HasField("error"):
            raise CommandError(Error(answer.error.message, ErrorCode.from_pb(answer.error.code)))
        payload = answer.reply.payload.data
        return json.loads(payload) if payload else None

    def task(self, task_id: str, name: str, body: Any = None) -> Any:
        return self.call(discovery_pb2.EVENT_SOURCED_ENTITY, TASK_COMPONENT, task_id, name, body)


class Tasks:
    """Creates tasks: ``await client.tasks.create(ANSWER, "How many?")`` answers the new task's id."""

    def __init__(self, client: ComponentClient) -> None:
        self._raw = _Raw(client)
        self._client = client

    async def create(
        self,
        task_type: TaskType[Any],
        instructions: str,
        *,
        id: str | None = None,
        attachments: list[Attachment] | None = None,
        depends_on: list[str] | None = None,
    ) -> str:
        """A dependency that does not exist is refused before anything is written. One that has
        already failed or been cancelled cancels the new task at once, naming it."""
        task_id = id or str(uuid.uuid4())
        dependencies = list(dict.fromkeys(depends_on or []))
        for dep in dependencies:
            await self._raw.task(dep, "get")
        await self._raw.task(
            task_id,
            "create",
            {
                "typeName": task_type.name,
                "instructions": instructions,
                "attachments": [a.to_json() for a in attachments or []],
                "dependencies": dependencies,
            },
        )
        for dep in dependencies:
            answer = await self._raw.task(dep, "add-dependent", {"taskId": task_id})
            ended = answer.get("alreadyEnded") if answer else None
            if ended:
                await self._raw.task(task_id, "cancel", {"reason": f"dependency '{dep}' {ended}"})
                break
        return task_id


class TaskCalls:
    """Calls about one task."""

    def __init__(self, client: ComponentClient, task_id: str) -> None:
        self._raw = _Raw(client)
        self.task_id = task_id

    async def get(self, task_type: TaskType[R] | None = None) -> TaskSnapshot[R]:
        record = await self._raw.task(self.task_id, "get")
        if task_type is not None and record["typeName"] != task_type.name:
            from ankka.client import CommandError

            raise CommandError(
                Error(f"task '{self.task_id}' is a '{record['typeName']}' task, not '{task_type.name}'", ErrorCode.BAD_REQUEST)
            )
        stored = record.get("result")
        result = None if stored is None else (task_type.decode(stored) if task_type else json.loads(stored))
        assignee = record.get("assignee")
        return TaskSnapshot(
            id=record["id"],
            type_name=record["typeName"],
            status=record["status"],
            result=result,
            reason=record.get("reason"),
            iterations=record.get("iterations", 0),
            assignee=(assignee["componentId"], assignee["instanceId"]) if assignee else None,
            record=record,
        )

    async def wait(self, task_type: TaskType[R] | None = None, timeout: float = 600.0) -> TaskSnapshot[R]:
        """Waits until the task has completed, failed or been cancelled. Raises ``TimeoutError``
        naming where it had got to when it has not ended within ``timeout`` seconds."""
        deadline = time.monotonic() + timeout
        while True:
            snapshot = await self.get(task_type)
            if snapshot.ended:
                return snapshot
            if time.monotonic() > deadline:
                raise TimeoutError(f"task '{self.task_id}' had not ended after {timeout}s; it is {snapshot.status}")
            await asyncio.sleep(0.25)

    async def cancel(self, reason: str = "cancelled by caller") -> None:
        """Cancels the task: at once when it is waiting, at the end of the current iteration when
        an agent is working it. A task that has already ended refuses."""
        await self._raw.task(self.task_id, "cancel", {"reason": reason})
        record = await self._raw.task(self.task_id, "get")
        assignee = record.get("assignee")
        if assignee:
            await self._raw.call(
                discovery_pb2.AUTONOMOUS_AGENT,
                assignee["componentId"],
                assignee["instanceId"],
                "dequeue",
                {"taskId": self.task_id, "reason": reason},
            )


class AutonomousAgentCalls:
    """Calls to one instance of an autonomous agent, by the id the caller chose; or, with no id,
    ``run_single_task`` on an instance the platform names."""

    def __init__(self, client: ComponentClient, component_id: str, instance_id: str | None) -> None:
        self._client = client
        self._raw = _Raw(client)
        self.component_id = component_id
        self.instance_id = instance_id

    def _instance(self) -> str:
        if not self.instance_id:
            raise ValueError("this call needs an instance id: for_autonomous_agent(agent, instance_id)")
        return self.instance_id

    async def _op(self, name: str, body: Any = None) -> Any:
        return await self._raw.call(discovery_pb2.AUTONOMOUS_AGENT, self.component_id, self._instance(), name, body)

    async def run_single_task(self, task_type: TaskType[Any], instructions: str, **create: Any) -> str:
        """Creates the task, starts an instance on it, and answers the task's id at once."""
        task_id = await Tasks(self._client).create(task_type, instructions, **create)
        await self._raw.call(
            discovery_pb2.AUTONOMOUS_AGENT, self.component_id, str(uuid.uuid4()), "run-single-task", {"taskId": task_id}
        )
        return task_id

    async def assign(self, *task_ids: str) -> dict[str, Any]:
        """Queues pending tasks on this instance; answers ``{"accepted": [...], "refused": {...}}``."""
        answer: dict[str, Any] = await self._op("assign", {"taskIds": list(task_ids)})
        return answer

    async def suspend(self) -> None:
        await self._op("suspend")

    async def resume(self) -> None:
        await self._op("resume")

    async def terminate(self) -> None:
        """Stops the instance for good; its tasks go back to pending for another to take."""
        await self._op("terminate")

    async def state(self) -> AgentState:
        record = await self._raw.call(
            discovery_pb2.EVENT_SOURCED_ENTITY, INSTANCE_COMPONENT, f"{self.component_id}/{self._instance()}", "get"
        )
        current = record.get("current")
        if record.get("terminated"):
            phase = "terminated"
        elif record.get("suspended"):
            phase = "suspended"
        elif current:
            phase = "working"
        elif record.get("queue"):
            phase = "waiting"
        else:
            phase = "idle"
        return AgentState(
            phase=phase,
            suspended=bool(record.get("suspended")),
            terminated=bool(record.get("terminated")),
            current_task=current["taskId"] if current else None,
            iteration=current["iteration"] if current else 0,
            queued=list(record.get("queue", [])),
        )

    async def notifications(self) -> AsyncIterator[Notification]:
        """What the instance does from now on, as it happens. Nothing is replayed."""
        from ankka.client import CommandError

        request = client_pb2.InvokeRequest(
            kind=discovery_pb2.AUTONOMOUS_AGENT,
            component_id=self.component_id,
            entity_id=self._instance(),
            name="notifications",
            payload=payload_pb2.Payload(content_type="application/json", manifest="", data=b""),
            metadata=self._client._metadata.to_pb(),
        )
        async for token in self._client._stub.InvokeStream(request):
            if token.HasField("text"):
                yield Notification.from_json(token.text)
            elif token.HasField("failed"):
                raise CommandError(Error(token.failed.message, ErrorCode.from_pb(token.failed.code)))
            else:
                return


__all__ = [
    "Accepted",
    "AgentState",
    "Attachment",
    "AutonomousAgent",
    "AutonomousAgentCalls",
    "AutonomousSettings",
    "Guardrail",
    "Notification",
    "Rejected",
    "TaskAcceptance",
    "TaskCalls",
    "TaskRule",
    "TaskSnapshot",
    "TaskType",
    "Tasks",
    "Tool",
]

