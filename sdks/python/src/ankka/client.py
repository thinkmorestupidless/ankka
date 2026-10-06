"""The component client: how a handler in this process calls other components.

Every call goes to the sidecar's callback service on loopback and is routed by the sidecar's
sharding, exactly as a call from a Scala endpoint would be. The metadata a handler received is
copied onto the call, which is what makes it a child span in the console.
"""

from __future__ import annotations

import os
from collections.abc import AsyncIterator, Mapping
from dataclasses import dataclass
from datetime import timedelta
from typing import TYPE_CHECKING, Any, TypeVar, overload

import grpc
import grpc.aio

from ankka._proto.ankka.protocol.v1 import client_pb2, client_pb2_grpc, discovery_pb2, payload_pb2
from ankka.approvals import APPROVALS_SINCE, Answered, ApprovalAwaited, AwaitingApproval, awaiting_of
from ankka.codec import DONE_CODEC, UNIT, Codec, Done, default_codec_for
from ankka.context import Metadata
from ankka.effects.common import Error, ErrorCode

if TYPE_CHECKING:
    from ankka.autonomous import AutonomousAgent, AutonomousAgentCalls, TaskCalls, Tasks

R = TypeVar("R")

DEFAULT_SIDECAR_ADDRESS = "127.0.0.1:9011"


class CommandError(Exception):
    """A refusal from the component that was called: nothing was persisted, and here is why."""

    def __init__(self, error: Error) -> None:
        super().__init__(error.message)
        self.error = error


def _payload(codec: Codec[Any], value: Any) -> payload_pb2.Payload:
    return payload_pb2.Payload(content_type=codec.content_type, manifest=codec.manifest, data=codec.encode(value))


def _error(pb: payload_pb2.Error) -> Error:
    return Error(pb.message, ErrorCode.from_pb(pb.code))


def decide_request(
    kind: Any,
    component_id: str,
    entity_id: str,
    name: str,
    approval_id: str,
    approved: bool,
    by: str,
    note: str | None,
    metadata: Metadata,
) -> client_pb2.DecideRequest:
    request = client_pb2.DecideRequest(
        kind=kind,
        component_id=component_id,
        entity_id=entity_id,
        name=name,
        approval_id=approval_id,
        approved=approved,
        by=by,
        metadata=metadata.to_pb(),
    )
    if note:
        request.note = note
    return request


def decide_unimplemented(failure: grpc.aio.AioRpcError) -> CommandError:
    """What a runtime before approvals answers a decision with, said as what it is."""
    from ankka.service import PROTOCOL_VERSION

    return CommandError(
        Error(
            f"the runtime beside this process cannot record a decision on an approval request, which needs "
            f"protocol {APPROVALS_SINCE} (this SDK speaks {PROTOCOL_VERSION}): {failure.details()}",
            ErrorCode.INTERNAL,
        )
    )


@dataclass(frozen=True)
class Invocation:
    _stub: client_pb2_grpc.ClientStub
    kind: Any
    component_id: str
    entity_id: str
    name: str
    metadata: Metadata

    def with_metadata(self, metadata: Metadata) -> Invocation:
        return Invocation(self._stub, self.kind, self.component_id, self.entity_id, self.name, metadata)

    @overload
    async def invoke(self, input: Any = None, *, codec: Codec[Any] | None = None) -> Done: ...

    @overload
    async def invoke(
        self, input: Any = None, *, reply: type[R], codec: Codec[Any] | None = None, reply_codec: Codec[Any] | None = None
    ) -> R: ...

    async def invoke(
        self,
        input: Any = None,
        *,
        reply: Any = Done,
        codec: Codec[Any] | None = None,
        reply_codec: Codec[Any] | None = None,
    ) -> Any:
        """Calls the handler and returns its decoded reply. ``reply`` is the reply's type (its
        default codec decodes it) unless ``reply_codec`` is given. A refusal raises CommandError;
        an agent's turn that waits for a person raises ``ApprovalAwaited`` (``ask`` answers it)."""
        in_codec = codec or (UNIT if input is None else default_codec_for(type(input)))
        out_codec = reply_codec or (DONE_CODEC if reply is Done else default_codec_for(reply))
        outcome = await self._outcome(self._request(input, in_codec), out_codec)
        if isinstance(outcome, AwaitingApproval):
            raise ApprovalAwaited(outcome.requests)
        return outcome.value

    async def ask(
        self,
        input: Any = None,
        *,
        reply: Any = str,
        codec: Codec[Any] | None = None,
        reply_codec: Codec[Any] | None = None,
    ) -> Answered[Any] | AwaitingApproval:
        """Calls an agent's handler for its outcome: ``Answered(value)``, or ``AwaitingApproval``
        with the requests the turn waits on when the model called a tool that requires approval."""
        in_codec = codec or (UNIT if input is None else default_codec_for(type(input)))
        out_codec = reply_codec or (DONE_CODEC if reply is Done else default_codec_for(reply))
        return await self._outcome(self._request(input, in_codec), out_codec)

    async def decide(
        self,
        approval_id: str,
        *,
        approved: bool,
        by: str,
        note: str | None = None,
        reply: Any = str,
        reply_codec: Codec[Any] | None = None,
    ) -> Answered[Any] | AwaitingApproval:
        """Decides one of the session's approval requests; this invocation names the handler whose
        turn waits. ``by`` names who decided and is required. When it was the turn's last awaited
        decision the turn goes on, and this answers as ``ask`` would have: the model's answer, or
        requests still or newly awaiting. A request already decided is a conflict."""
        out_codec = reply_codec or (DONE_CODEC if reply is Done else default_codec_for(reply))
        request = decide_request(
            self.kind, self.component_id, self.entity_id, self.name, approval_id, approved, by, note, self.metadata
        )
        try:
            answer = await self._stub.Decide(request)
        except grpc.aio.AioRpcError as failure:
            if failure.code() == grpc.StatusCode.UNIMPLEMENTED:
                raise decide_unimplemented(failure) from failure
            raise
        return self._read(answer, out_codec)

    def _request(self, input: Any, in_codec: Codec[Any]) -> client_pb2.InvokeRequest:
        return client_pb2.InvokeRequest(
            kind=self.kind,
            component_id=self.component_id,
            entity_id=self.entity_id,
            name=self.name,
            payload=_payload(in_codec, input),
            metadata=self.metadata.to_pb(),
        )

    async def _outcome(self, request: client_pb2.InvokeRequest, out_codec: Codec[Any]) -> Answered[Any] | AwaitingApproval:
        return self._read(await self._stub.Invoke(request), out_codec)

    @staticmethod
    def _read(answer: client_pb2.InvokeReply, out_codec: Codec[Any]) -> Answered[Any] | AwaitingApproval:
        if answer.HasField("error"):
            raise CommandError(_error(answer.error))
        if answer.HasField("approval"):
            return awaiting_of(answer.approval)
        return Answered(out_codec.decode(answer.reply.payload.data))

    async def stream(self, input: Any = None, *, codec: Codec[Any] | None = None) -> AsyncIterator[str]:
        """Calls a streaming handler and yields its tokens as they arrive. A turn that stops to
        wait for a person raises ``ApprovalAwaited`` after its last token."""
        async for part in self.stream_parts(input, codec=codec):
            if isinstance(part, AwaitingApproval):
                raise ApprovalAwaited(part.requests)
            yield part

    async def stream_parts(self, input: Any = None, *, codec: Codec[Any] | None = None) -> AsyncIterator[str | AwaitingApproval]:
        """As ``stream``, ending with one ``AwaitingApproval`` when the turn waits."""
        in_codec = codec or (UNIT if input is None else default_codec_for(type(input)))
        async for token in self._stub.InvokeStream(self._request(input, in_codec)):
            if token.HasField("text"):
                yield token.text
            elif token.HasField("failed"):
                raise CommandError(_error(token.failed))
            elif token.HasField("approval"):
                yield awaiting_of(token.approval)
                return
            else:
                return


@dataclass(frozen=True)
class Calls:
    _stub: client_pb2_grpc.ClientStub
    kind: Any
    component_id: str
    entity_id: str
    metadata: Metadata

    def call(self, name: str) -> Invocation:
        return Invocation(self._stub, self.kind, self.component_id, self.entity_id, name, self.metadata)


@dataclass(frozen=True)
class Views:
    _stub: client_pb2_grpc.ClientStub
    _metadata: Metadata = Metadata()

    async def get(self, view_id: str, key: str, row: Any) -> Any | None:
        """One row by key, decoded as ``row``'s default codec, or None."""
        rows = await self.query(view_id, "get", key, row)
        return rows[0] if rows else None

    async def all(self, view_id: str, row: Any) -> list[Any]:
        return await self.query(view_id, "all", None, row)

    async def ask(
        self,
        view_id: str,
        name: str,
        row: Any,
        values: Mapping[str, str] | None = None,
        *,
        limit: int | None = None,
    ) -> list[Any]:
        """The rows of one of the view's declared queries, decoded as ``row``'s default codec, with
        each value it takes given by name in ``values``; at most ``limit`` rows, 1000 when it is not
        given. The values are a mapping, not keywords, so a value may be called anything."""
        request = client_pb2.QueryRequest(
            view_id=view_id, name=name, metadata=self._metadata.to_pb(), values=dict(values or {}), limit=limit
        )
        return await self._rows(request, row)

    async def query(self, view_id: str, name: str, key: str | None, row: Any) -> list[Any]:
        request = client_pb2.QueryRequest(
            view_id=view_id,
            name=name,
            payload=payload_pb2.Payload(content_type="text/plain", manifest="string", data=(key or "").encode()),
            metadata=self._metadata.to_pb(),
        )
        return await self._rows(request, row)

    async def _rows(self, request: client_pb2.QueryRequest, row: Any, codec: Codec[Any] | None = None) -> list[Any]:
        import json

        answer = await self._stub.Query(request)
        if answer.HasField("error"):
            raise CommandError(_error(answer.error))
        decoding = codec or default_codec_for(row)
        documents = json.loads(answer.rows.data.decode("utf-8"))
        return [decoding.decode(json.dumps(d).encode("utf-8")) for d in documents]


@dataclass(frozen=True)
class SidecarRows:
    """A keyed view's own rows as the sidecar holds them, decoded with the view's row codec."""

    views: Views

    async def get(self, view_id: str, key: str, codec: Codec[Any]) -> Any | None:
        request = client_pb2.QueryRequest(
            view_id=view_id,
            name="get",
            payload=payload_pb2.Payload(content_type="text/plain", manifest="string", data=key.encode()),
            metadata=self.views._metadata.to_pb(),
        )
        rows = await self.views._rows(request, None, codec)
        return rows[0] if rows else None

    async def ask(self, view_id: str, name: str, codec: Codec[Any], values: Mapping[str, str]) -> list[Any]:
        request = client_pb2.QueryRequest(
            view_id=view_id, name=name, metadata=self.views._metadata.to_pb(), values=dict(values)
        )
        return await self.views._rows(request, None, codec)


RECURRING_TIMERS_SINCE = "1.12"
MAX_PERIOD = timedelta(days=36500)


def _millis(duration: timedelta) -> int:
    # Exact, unlike total_seconds() * 1000, which a float rounds for a long period.
    return (duration.days * 86_400_000) + (duration.seconds * 1000) + (duration.microseconds // 1000)


def period_problem(timer_id: str, period: timedelta) -> str | None:
    """What is wrong with a recurring timer's period, if anything: the runtime's own rule and words."""
    if period < timedelta(milliseconds=1) or period > MAX_PERIOD:
        return (
            f"timer '{timer_id}' has a period of {_millis(period)} milliseconds; a period is from 1 millisecond "
            f"to {MAX_PERIOD.days} days"
        )
    return None


@dataclass(frozen=True)
class Timers:
    _stub: client_pb2_grpc.ClientStub

    async def schedule(
        self,
        timer_id: str,
        delay: timedelta,
        component_id: str,
        name: str,
        input: Any = None,
        *,
        codec: Codec[Any] | None = None,
    ) -> None:
        """Schedules a timed action ``name`` on ``component_id`` after ``delay``. Scheduling twice
        under one id replaces the earlier schedule."""
        in_codec = codec or (UNIT if input is None else default_codec_for(type(input)))
        await self._stub.Schedule(
            client_pb2.ScheduleRequest(
                timer_id=timer_id,
                delay_millis=int(delay.total_seconds() * 1000),
                kind=discovery_pb2.TIMED_ACTION,
                component_id=component_id,
                name=name,
                payload=_payload(in_codec, input),
            )
        )

    async def schedule_recurring(
        self,
        timer_id: str,
        delay: timedelta,
        period: timedelta,
        component_id: str,
        name: str,
        input: Any = None,
        *,
        codec: Codec[Any] | None = None,
    ) -> None:
        """Schedules timed action ``name`` on ``component_id`` first after ``delay`` and then every
        ``period`` from each due time, until cancelled. A delay of zero or less is due at once.

        Setting it again with the same handler and period keeps its next due time, so a service may
        set its recurring timers every time it starts; anything else under the id replaces it. A
        period outside 1 millisecond to 36,500 days is refused with ``CommandError``
        (``BAD_REQUEST``) and nothing is sent."""
        problem = period_problem(timer_id, period)
        if problem is not None:
            raise CommandError(Error(problem, ErrorCode.BAD_REQUEST))
        in_codec = codec or (UNIT if input is None else default_codec_for(type(input)))
        try:
            reply = await self._stub.ScheduleRecurring(
                client_pb2.ScheduleRecurringRequest(
                    timer_id=timer_id,
                    delay_millis=int(delay.total_seconds() * 1000),
                    period_millis=_millis(period),
                    component_id=component_id,
                    name=name,
                    payload=_payload(in_codec, input),
                )
            )
        except grpc.aio.AioRpcError as failure:
            if failure.code() == grpc.StatusCode.UNIMPLEMENTED:
                from ankka.service import PROTOCOL_VERSION

                raise CommandError(
                    Error(
                        f"the runtime beside this process does not offer recurring timers, which need protocol "
                        f"{RECURRING_TIMERS_SINCE} (this SDK speaks {PROTOCOL_VERSION}): {failure.details()}",
                        ErrorCode.INTERNAL,
                    )
                ) from failure
            raise
        if reply.HasField("error"):
            raise CommandError(_error(reply.error))

    async def cancel(self, timer_id: str) -> None:
        await self._stub.Cancel(client_pb2.CancelRequest(timer_id=timer_id))


class ComponentClient:
    """Calls into the platform from this process. One per process, shared by every handler; the
    metadata varies per call and is supplied by the context a handler received."""

    def __init__(self, address: str | None = None, metadata: Metadata | None = None) -> None:
        self.address = address or os.environ.get("ANKKA_SIDECAR_ADDRESS", DEFAULT_SIDECAR_ADDRESS)
        # The channel is opened on first use, so the address may still change until then — the
        # integration testkit learns the sidecar's published port only after starting it.
        self._channel: grpc.aio.Channel | None = None
        self._stub_cache: client_pb2_grpc.ClientStub | None = None
        self._metadata = metadata or Metadata()

    @property
    def _stub(self) -> client_pb2_grpc.ClientStub:
        if self._stub_cache is None:
            self._channel = grpc.aio.insecure_channel(self.address)
            self._stub_cache = client_pb2_grpc.ClientStub(self._channel)  # type: ignore[no-untyped-call]
        return self._stub_cache

    @property
    def views(self) -> Views:
        return Views(self._stub, self._metadata)

    @property
    def timers(self) -> Timers:
        return Timers(self._stub)

    def reconnect(self, address: str) -> None:
        """Points the client at a new sidecar address; the next call opens a fresh channel."""
        self.address = address
        self._channel = None
        self._stub_cache = None

    def with_metadata(self, metadata: Metadata) -> ComponentClient:
        """The same client, with this metadata on every call: what a context hands its handler."""
        return _Scoped(self, metadata)

    def for_event_sourced_entity(self, component_id: str, entity_id: str) -> Calls:
        return Calls(self._stub, discovery_pb2.EVENT_SOURCED_ENTITY, component_id, entity_id, self._metadata)

    def for_key_value_entity(self, component_id: str, entity_id: str) -> Calls:
        return Calls(self._stub, discovery_pb2.KEY_VALUE_ENTITY, component_id, entity_id, self._metadata)

    def for_workflow(self, component_id: str, workflow_id: str) -> Calls:
        return Calls(self._stub, discovery_pb2.WORKFLOW, component_id, workflow_id, self._metadata)

    def for_agent(self, component_id: str, session_id: str) -> Calls:
        return Calls(self._stub, discovery_pb2.AGENT, component_id, session_id, self._metadata)

    @property
    def tasks(self) -> Tasks:
        """Creates tasks for autonomous agents."""
        from ankka.autonomous import Tasks

        return Tasks(self)

    def for_task(self, task_id: str) -> TaskCalls:
        from ankka.autonomous import TaskCalls

        return TaskCalls(self, task_id)

    def for_autonomous_agent(self, agent: type[AutonomousAgent] | str, instance_id: str | None = None) -> AutonomousAgentCalls:
        """One instance of an autonomous agent, by the id the caller chose; without an id, only
        ``run_single_task``, on an instance the platform names."""
        from ankka.autonomous import AutonomousAgentCalls

        component_id = agent if isinstance(agent, str) else agent.component_id
        return AutonomousAgentCalls(self, component_id, instance_id)

    async def close(self) -> None:
        if self._channel is not None:
            await self._channel.close()
            self._channel = None
            self._stub_cache = None


class _Scoped(ComponentClient):
    """A view of a client with fixed metadata; shares the parent's channel."""

    def __init__(self, parent: ComponentClient, metadata: Metadata) -> None:  # noqa: D401
        self._parent = parent
        self._metadata = metadata

    @property
    def address(self) -> str:  # type: ignore[override]
        return self._parent.address

    @property
    def _stub(self) -> client_pb2_grpc.ClientStub:
        return self._parent._stub

    def with_metadata(self, metadata: Metadata) -> ComponentClient:
        return _Scoped(self._parent, metadata)

    async def close(self) -> None:
        return None
