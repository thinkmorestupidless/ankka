"""The component client: how a handler in this process calls other components.

Every call goes to the sidecar's callback service on loopback and is routed by the sidecar's
sharding, exactly as a call from a Scala endpoint would be. The metadata a handler received is
copied onto the call, which is what makes it a child span in the console.
"""

from __future__ import annotations

import os
from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import timedelta
from typing import Any, TypeVar, overload

import grpc
import grpc.aio

from ankka._proto.ankka.protocol.v1 import client_pb2, client_pb2_grpc, discovery_pb2, payload_pb2
from ankka.codec import DONE_CODEC, UNIT, Codec, Done, default_codec_for
from ankka.context import Metadata
from ankka.effects.common import Error, ErrorCode

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
        default codec decodes it) unless ``reply_codec`` is given. A refusal raises CommandError."""
        in_codec = codec or (UNIT if input is None else default_codec_for(type(input)))
        out_codec = reply_codec or (DONE_CODEC if reply is Done else default_codec_for(reply))
        request = client_pb2.InvokeRequest(
            kind=self.kind,
            component_id=self.component_id,
            entity_id=self.entity_id,
            name=self.name,
            payload=_payload(in_codec, input),
            metadata=self.metadata.to_pb(),
        )
        answer = await self._stub.Invoke(request)
        if answer.HasField("error"):
            raise CommandError(_error(answer.error))
        return out_codec.decode(answer.reply.payload.data)

    async def stream(self, input: Any = None, *, codec: Codec[Any] | None = None) -> AsyncIterator[str]:
        """Calls a streaming handler and yields its tokens as they arrive."""
        in_codec = codec or (UNIT if input is None else default_codec_for(type(input)))
        request = client_pb2.InvokeRequest(
            kind=self.kind,
            component_id=self.component_id,
            entity_id=self.entity_id,
            name=self.name,
            payload=_payload(in_codec, input),
            metadata=self.metadata.to_pb(),
        )
        async for token in self._stub.InvokeStream(request):
            if token.HasField("text"):
                yield token.text
            elif token.HasField("failed"):
                raise CommandError(_error(token.failed))
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

    async def get(self, view_id: str, key: str, row: Any) -> Any | None:
        """One row by key, decoded as ``row``'s default codec, or None."""
        rows = await self.query(view_id, "get", key, row)
        return rows[0] if rows else None

    async def all(self, view_id: str, row: Any) -> list[Any]:
        return await self.query(view_id, "all", None, row)

    async def query(self, view_id: str, name: str, key: str | None, row: Any) -> list[Any]:
        import json

        request = client_pb2.QueryRequest(
            view_id=view_id,
            name=name,
            payload=payload_pb2.Payload(content_type="text/plain", manifest="string", data=(key or "").encode()),
        )
        answer = await self._stub.Query(request)
        if answer.HasField("error"):
            raise CommandError(_error(answer.error))
        codec = default_codec_for(row)
        documents = json.loads(answer.rows.data.decode("utf-8"))
        return [codec.decode(json.dumps(d).encode("utf-8")) for d in documents]


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
        return Views(self._stub)

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
