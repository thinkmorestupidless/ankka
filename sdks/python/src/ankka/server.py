"""The gRPC server the sidecar dials: discovery, one conversation per loaded instance, forwarded
HTTP requests. Bound to loopback only.

Per stream, one asyncio task owns the instance's state and handles messages strictly in order,
so one command runs at a time and replies never reorder. When the stream ends — the sidecar
passivated the instance, or went away — the state is released. The two cannot be told apart at
that moment (research item 4), and both mean the same thing here.
"""

from __future__ import annotations

import asyncio
import logging
import traceback
from collections.abc import AsyncIterator
from typing import Any

import grpc
import grpc.aio

from ankka._proto.ankka.protocol.v1 import (
    discovery_pb2,
    discovery_pb2_grpc,
    endpoint_pb2,
    endpoint_pb2_grpc,
    event_sourced_pb2,
    event_sourced_pb2_grpc,
    payload_pb2,
)
from ankka.client import CommandError, ComponentClient
from ankka.context import CommandContext, Metadata, Principal, RequestContext
from ankka.effects.common import Fail, NoReply, Reply, retention_to_pb
from ankka.endpoint import HttpProblem
from ankka.event_sourced_entity import EventSourcedEntity
from ankka.service import Registry

log = logging.getLogger("ankka")


def _failure(command_id: int, message: str, code: Any = payload_pb2.INTERNAL) -> payload_pb2.Failure:
    return payload_pb2.Failure(command_id=command_id, error=payload_pb2.Error(message=message, code=code))


class DiscoveryServicer(discovery_pb2_grpc.DiscoveryServicer):
    def __init__(self, registry: Registry) -> None:
        self.registry = registry

    async def Discover(self, request: discovery_pb2.SidecarInfo, context: Any) -> discovery_pb2.Spec:
        log.info("sidecar %s (protocol %s) discovering", request.runtime_version, request.protocol_version)
        return self.registry.spec()

    async def ReportError(self, request: discovery_pb2.Problem, context: Any) -> payload_pb2.Empty:
        log.error("the sidecar refused this service:\n%s", request.message)
        return payload_pb2.Empty()


class EventSourcedServicer(event_sourced_pb2_grpc.EventSourcedServicer):
    def __init__(self, registry: Registry, client: ComponentClient) -> None:
        self.registry = registry
        self.client = client

    async def Handle(
        self, request_iterator: AsyncIterator[event_sourced_pb2.EventSourcedIn], context: Any
    ) -> AsyncIterator[event_sourced_pb2.EventSourcedOut]:
        entity: EventSourcedEntity[Any, Any] | None = None
        state: Any = None
        sequence = 0
        entity_id = ""
        async for message in request_iterator:
            kind = message.WhichOneof("message")
            if kind == "init":
                cls = self.registry.entities.get(message.init.component_id)
                if cls is None:
                    yield event_sourced_pb2.EventSourcedOut(
                        failure=_failure(0, f"unknown component {message.init.component_id!r}", payload_pb2.NOT_FOUND)
                    )
                    return
                entity = cls()
                entity_id = message.init.entity_id
                entity._bind(entity_id)
                if message.init.HasField("snapshot"):
                    state = cls.state_codec.decode(message.init.snapshot.payload.data)
                    sequence = message.init.snapshot.sequence
                else:
                    state = entity.empty_state()
                    sequence = 0
            elif kind == "event":
                assert entity is not None
                state = entity.apply_event(state, type(entity).event_codec.decode(message.event.payload.data))
                sequence = message.event.sequence
            elif kind == "command":
                assert entity is not None
                cmd = message.command
                spec = type(entity).handlers().get(cmd.name)
                if spec is None:
                    yield event_sourced_pb2.EventSourcedOut(
                        failure=_failure(cmd.id, f"no handler {cmd.name!r} on {type(entity).component_id!r}", payload_pb2.NOT_FOUND)
                    )
                    continue
                metadata = Metadata.from_pb(cmd.metadata)
                ctx = CommandContext(
                    entity_id=entity_id,
                    component_id=type(entity).component_id,
                    metadata=metadata,
                    sequence_number=sequence,
                    client=self.client.with_metadata(metadata),
                )
                try:
                    effect = await entity._run(spec, state, cmd.payload.data, ctx)
                except Exception as e:
                    log.warning("%s/%s %s raised: %s", type(entity).component_id, entity_id, cmd.name, e)
                    log.debug("%s", traceback.format_exc())
                    yield event_sourced_pb2.EventSourcedOut(failure=_failure(cmd.id, str(e) or type(e).__name__))
                    continue
                codec = type(entity).event_codec
                # Fold locally so the reply can be computed from the post-events state, and so this
                # instance's state stays in step with what the sidecar journals.
                new_state = state
                if not isinstance(effect.outcome, Fail):
                    for ev in effect.events:
                        new_state = entity.apply_event(new_state, ev)
                reply = event_sourced_pb2.EventSourcedOut.Reply(command_id=cmd.id)
                if isinstance(effect.outcome, Fail):
                    reply.outcome.error.CopyFrom(effect.outcome.error.to_pb())
                elif isinstance(effect.outcome, NoReply):
                    reply.outcome.no_reply.SetInParent()
                    reply.events.extend(
                        payload_pb2.Payload(content_type=codec.content_type, manifest=codec.manifest, data=codec.encode(ev))
                        for ev in effect.events
                    )
                else:
                    assert isinstance(effect.outcome, Reply)
                    try:
                        value = effect.outcome.compute(new_state)
                        encoded = spec.reply_codec.encode(value)
                    except Exception as e:
                        yield event_sourced_pb2.EventSourcedOut(failure=_failure(cmd.id, f"computing the reply failed: {e}"))
                        continue
                    reply.outcome.reply.payload.CopyFrom(
                        payload_pb2.Payload(content_type=spec.reply_codec.content_type, manifest=spec.reply_codec.manifest, data=encoded)
                    )
                    reply.outcome.reply.metadata.CopyFrom(effect.outcome.metadata.to_pb())
                    reply.events.extend(
                        payload_pb2.Payload(content_type=codec.content_type, manifest=codec.manifest, data=codec.encode(ev))
                        for ev in effect.events
                    )
                retention = retention_to_pb(effect.retention) if not isinstance(effect.outcome, Fail) else None
                if retention is not None:
                    reply.retention.CopyFrom(retention)
                if cmd.snapshot_requested and not isinstance(effect.outcome, Fail):
                    sc = type(entity).state_codec
                    reply.snapshot.CopyFrom(
                        payload_pb2.Payload(content_type=sc.content_type, manifest=sc.manifest, data=sc.encode(new_state))
                    )
                if not isinstance(effect.outcome, Fail):
                    state = new_state
                    sequence += len(effect.events)
                yield event_sourced_pb2.EventSourcedOut(reply=reply)
        # The stream ended: passivation or the sidecar went away. Either way the state is released.
        entity = None
        state = None


class HttpServicer(endpoint_pb2_grpc.HttpServicer):
    def __init__(self, registry: Registry, client: ComponentClient) -> None:
        self.registry = registry
        self.client = client
        self.instances: dict[str, Any] = {}

    def _instance(self, endpoint_id: str) -> Any:
        if endpoint_id not in self.instances:
            cls = self.registry.endpoints[endpoint_id]
            self.instances[endpoint_id] = cls(self.client) if _takes_client(cls) else cls()  # type: ignore[call-arg]
        return self.instances[endpoint_id]

    def _context(self, request: endpoint_pb2.HttpRequest) -> RequestContext:
        principal = None
        if request.HasField("principal"):
            p = request.principal
            principal = Principal(
                p.subject,
                p.name if p.HasField("name") else None,
                p.email if p.HasField("email") else None,
                p.email_verified,
                frozenset(p.roles),
            )
        return RequestContext(
            query=tuple((q.name, q.value) for q in request.query),
            headers=tuple((h.name, h.value) for h in request.headers),
            principal=principal,
            metadata=Metadata.from_pb(request.metadata),
        )

    async def Handle(self, request: endpoint_pb2.HttpRequest, context: Any) -> endpoint_pb2.HttpReply:
        cls = self.registry.endpoints.get(request.endpoint_id)
        spec = cls.routes().get(request.route_id) if cls else None
        if cls is None or spec is None:
            return endpoint_pb2.HttpReply(failure=_failure(0, f"unknown route {request.endpoint_id}/{request.route_id}", payload_pb2.NOT_FOUND))
        instance = self._instance(request.endpoint_id)
        ctx = self._context(request)
        try:
            status, content_type, body = await instance._handle(spec, list(request.path_args), request.body, ctx)
        except HttpProblem as p:
            return endpoint_pb2.HttpReply(
                response=endpoint_pb2.HttpResponse(status=p.status, content_type="text/plain", body=p.message.encode("utf-8"))
            )
        except CommandError as refused:
            # A refusal from a component the handler called: the caller's problem, with the code's
            # status, exactly as a Scala endpoint answers a CommandError.
            return endpoint_pb2.HttpReply(
                response=endpoint_pb2.HttpResponse(
                    status=refused.error.code.http_status, content_type="text/plain", body=refused.error.message.encode("utf-8")
                )
            )
        except Exception as e:
            log.warning("%s/%s raised: %s", request.endpoint_id, request.route_id, e)
            log.debug("%s", traceback.format_exc())
            return endpoint_pb2.HttpReply(failure=_failure(0, str(e) or type(e).__name__))
        return endpoint_pb2.HttpReply(response=endpoint_pb2.HttpResponse(status=status, content_type=content_type, body=body))

    async def HandleStream(self, request: endpoint_pb2.HttpRequest, context: Any) -> AsyncIterator[endpoint_pb2.StreamFrame]:
        cls = self.registry.endpoints.get(request.endpoint_id)
        spec = cls.routes().get(request.route_id) if cls else None
        if cls is None or spec is None:
            yield endpoint_pb2.StreamFrame(failed=payload_pb2.Error(message="unknown route", code=payload_pb2.NOT_FOUND))
            return
        instance = self._instance(request.endpoint_id)
        ctx = self._context(request)
        try:
            async for frame in instance._handle_stream(spec, list(request.path_args), request.body, ctx):
                yield endpoint_pb2.StreamFrame(text=frame)
            yield endpoint_pb2.StreamFrame(completed=payload_pb2.Empty())
        except Exception as e:
            yield endpoint_pb2.StreamFrame(failed=payload_pb2.Error(message=str(e) or type(e).__name__, code=payload_pb2.INTERNAL))


def _takes_client(cls: type) -> bool:
    import inspect

    try:
        params = [p for p in inspect.signature(cls.__init__).parameters.values() if p.name != "self"]  # type: ignore[misc]
    except (TypeError, ValueError):
        return False
    return len(params) >= 1


class Server:
    """The process's gRPC server. ``start`` binds loopback; ``wait`` runs until stopped."""

    def __init__(self, registry: Registry, client: ComponentClient | None = None) -> None:
        self.registry = registry
        self.client = client or ComponentClient()
        self._server: grpc.aio.Server | None = None
        self.port = 0

    def add_servicers(self, server: grpc.aio.Server) -> None:
        discovery_pb2_grpc.add_DiscoveryServicer_to_server(DiscoveryServicer(self.registry), server)  # type: ignore[no-untyped-call]
        event_sourced_pb2_grpc.add_EventSourcedServicer_to_server(EventSourcedServicer(self.registry, self.client), server)  # type: ignore[no-untyped-call]
        endpoint_pb2_grpc.add_HttpServicer_to_server(HttpServicer(self.registry, self.client), server)  # type: ignore[no-untyped-call]

    async def start(self, host: str = "127.0.0.1", port: int = 0) -> int:
        if host not in ("127.0.0.1", "localhost", "::1", "0.0.0.0"):
            raise ValueError("the process server binds loopback only")
        server = grpc.aio.server()
        self.add_servicers(server)
        self.port = server.add_insecure_port(f"{host}:{port}")
        await server.start()
        self._server = server
        log.info("ankka process listening on %s:%s", host, self.port)
        return self.port

    async def wait(self) -> None:
        assert self._server is not None
        await self._server.wait_for_termination()

    async def stop(self, grace: float | None = None) -> None:
        if self._server is not None:
            await self._server.stop(grace)
            self._server = None
        await self.client.close()
