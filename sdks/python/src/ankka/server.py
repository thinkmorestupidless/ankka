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
    agent_pb2,
    agent_pb2_grpc,
    consumer_pb2,
    consumer_pb2_grpc,
    discovery_pb2,
    discovery_pb2_grpc,
    endpoint_pb2,
    endpoint_pb2_grpc,
    event_sourced_pb2,
    event_sourced_pb2_grpc,
    key_value_pb2,
    key_value_pb2_grpc,
    payload_pb2,
    timed_action_pb2,
    timed_action_pb2_grpc,
    view_pb2,
    view_pb2_grpc,
    workflow_pb2,
    workflow_pb2_grpc,
)
from ankka.agent import Agent
from ankka.client import CommandError, ComponentClient
from ankka.context import CommandContext, Metadata, Principal, RequestContext
from ankka.codec import default_codec_for
from ankka.effects import consumer as consumer_effects
from ankka.effects import timed_action as timed_effects
from ankka.effects import view as view_effects
from ankka.effects.common import Fail, NoReply, Reply, retention_to_pb
from ankka.effects.workflow import End, Pause, StepFail, StepRef, TransitionTo
from ankka.endpoint import HttpProblem
from ankka.event_sourced_entity import EventSourcedEntity
from ankka.key_value_entity import KeyValueEntity
from ankka.service import Registry
from ankka.workflow import Workflow

log = logging.getLogger("ankka")

# What a sidecar told this process through ReportError, for a service that wants to show it.
PROBLEMS: list[str] = []


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
        PROBLEMS.append(request.message)
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


def _payload_of(codec: Any, value: Any) -> payload_pb2.Payload:
    return payload_pb2.Payload(content_type=codec.content_type, manifest=codec.manifest, data=codec.encode(value))


def _step_ref(ref: StepRef) -> workflow_pb2.StepRef:
    pb = workflow_pb2.StepRef(step=ref.step)
    if ref.input is not None:
        pb.input.CopyFrom(_payload_of(default_codec_for(type(ref.input)), ref.input))
    return pb


class KeyValueServicer(key_value_pb2_grpc.KeyValueServicer):
    def __init__(self, registry: Registry, client: ComponentClient) -> None:
        self.registry = registry
        self.client = client

    async def Handle(
        self, request_iterator: AsyncIterator[key_value_pb2.KeyValueIn], context: Any
    ) -> AsyncIterator[key_value_pb2.KeyValueOut]:
        entity: KeyValueEntity[Any] | None = None
        state: Any = None
        entity_id = ""
        async for message in request_iterator:
            kind = message.WhichOneof("message")
            if kind == "init":
                cls = self.registry.key_values.get(message.init.component_id)
                if cls is None:
                    yield key_value_pb2.KeyValueOut(failure=_failure(0, f"unknown component {message.init.component_id!r}", payload_pb2.NOT_FOUND))
                    return
                entity = cls()
                entity_id = message.init.entity_id
                entity._bind(entity_id)
                state = cls.state_codec.decode(message.init.state.data) if message.init.HasField("state") else entity.empty_state()
            elif kind == "command":
                assert entity is not None
                cmd = message.command
                spec = type(entity).handlers().get(cmd.name)
                if spec is None:
                    yield key_value_pb2.KeyValueOut(failure=_failure(cmd.id, f"no handler {cmd.name!r}", payload_pb2.NOT_FOUND))
                    continue
                metadata = Metadata.from_pb(cmd.metadata)
                ctx = CommandContext(entity_id, type(entity).component_id, metadata, 0, self.client.with_metadata(metadata))
                try:
                    effect = await entity._run(spec, state, cmd.payload.data, ctx)
                except Exception as e:
                    log.warning("%s/%s %s raised: %s", type(entity).component_id, entity_id, cmd.name, e)
                    yield key_value_pb2.KeyValueOut(failure=_failure(cmd.id, str(e) or type(e).__name__))
                    continue
                reply = key_value_pb2.KeyValueOut.Reply(command_id=cmd.id)
                new_state = state if effect.new_state is None else effect.new_state
                if isinstance(effect.outcome, Fail):
                    reply.outcome.error.CopyFrom(effect.outcome.error.to_pb())
                    yield key_value_pb2.KeyValueOut(reply=reply)
                    continue
                if effect.new_state is not None:
                    reply.new_state.CopyFrom(_payload_of(type(entity).state_codec, effect.new_state))
                retention = retention_to_pb(effect.retention)
                if retention is not None:
                    reply.retention.CopyFrom(retention)
                if isinstance(effect.outcome, NoReply):
                    reply.outcome.no_reply.SetInParent()
                else:
                    assert isinstance(effect.outcome, Reply)
                    try:
                        value = effect.outcome.compute(new_state)
                        reply.outcome.reply.payload.CopyFrom(_payload_of(spec.reply_codec, value))
                        reply.outcome.reply.metadata.CopyFrom(effect.outcome.metadata.to_pb())
                    except Exception as e:
                        yield key_value_pb2.KeyValueOut(failure=_failure(cmd.id, f"computing the reply failed: {e}"))
                        continue
                state = new_state
                yield key_value_pb2.KeyValueOut(reply=reply)


class _WorkflowStream:
    """One loaded workflow instance: its state, and the step running for it, if any. A command
    arriving while a step runs is answered from the state before the step; the step's new state
    applies when it replies, as the sidecar's engine journals it."""

    def __init__(self, workflow: Workflow[Any], state: Any, client: ComponentClient) -> None:
        self.workflow = workflow
        self.state = state
        self.client = client
        self.step_task: asyncio.Task[None] | None = None

    async def command(self, cmd: workflow_pb2.WorkflowIn.Command) -> workflow_pb2.WorkflowOut:
        workflow = self.workflow
        spec = type(workflow).handlers().get(cmd.name)
        if spec is None:
            return workflow_pb2.WorkflowOut(failure=_failure(cmd.id, f"no handler {cmd.name!r}", payload_pb2.NOT_FOUND))
        metadata = Metadata.from_pb(cmd.metadata)
        ctx = CommandContext(workflow.entity_id, type(workflow).component_id, metadata, 0, self.client.with_metadata(metadata))
        try:
            effect = await workflow._run(spec, self.state, cmd.payload.data, ctx)
        except Exception as e:
            log.warning("%s/%s %s raised: %s", type(workflow).component_id, workflow.entity_id, cmd.name, e)
            return workflow_pb2.WorkflowOut(failure=_failure(cmd.id, str(e) or type(e).__name__))
        reply = workflow_pb2.WorkflowOut.Reply(command_id=cmd.id)
        new_state = self.state if effect.new_state is None else effect.new_state
        if isinstance(effect.outcome, Fail):
            reply.outcome.error.CopyFrom(effect.outcome.error.to_pb())
            return workflow_pb2.WorkflowOut(reply=reply)
        if effect.new_state is not None:
            reply.new_state.CopyFrom(_payload_of(type(workflow).state_codec, effect.new_state))
        if effect.transition is not None:
            reply.transition.CopyFrom(_step_ref(effect.transition))
        if isinstance(effect.outcome, NoReply):
            reply.outcome.no_reply.SetInParent()
        else:
            assert isinstance(effect.outcome, Reply)
            value = effect.outcome.compute(new_state)
            reply.outcome.reply.payload.CopyFrom(_payload_of(spec.reply_codec, value))
            reply.outcome.reply.metadata.CopyFrom(effect.outcome.metadata.to_pb())
        self.state = new_state
        return workflow_pb2.WorkflowOut(reply=reply)

    async def step(self, run: workflow_pb2.WorkflowIn.RunStep) -> workflow_pb2.WorkflowOut:
        # A fresh instance per step, as the sidecar's engine does: the step runs while commands
        # keep arriving on the stream's instance, and the two must not share a context slot.
        workflow = type(self.workflow)()
        workflow._bind(self.workflow.entity_id)
        step_spec = type(workflow).steps().get(run.step)
        if step_spec is None:
            return workflow_pb2.WorkflowOut(failure=_failure(run.id, f"no step {run.step!r}", payload_pb2.NOT_FOUND))
        ctx = CommandContext(workflow.entity_id, type(workflow).component_id, Metadata(), 0, self.client)
        try:
            step_effect = await workflow._run_step(step_spec, self.state, run.input.data if run.HasField("input") else None, ctx)
        except Exception as e:
            log.warning("%s/%s step %s raised: %s", type(workflow).component_id, workflow.entity_id, run.step, e)
            return workflow_pb2.WorkflowOut(failure=_failure(run.id, str(e) or type(e).__name__))
        step_reply = workflow_pb2.WorkflowOut.StepReply(command_id=run.id)
        if step_effect.new_state is not None:
            step_reply.new_state.CopyFrom(_payload_of(type(workflow).state_codec, step_effect.new_state))
            self.state = step_effect.new_state
        nxt = step_effect.next
        if isinstance(nxt, TransitionTo):
            step_reply.next.transition_to.CopyFrom(_step_ref(nxt.ref))
        elif isinstance(nxt, Pause):
            step_reply.next.pause.SetInParent()
            if nxt.after is not None:
                step_reply.next.pause.after_millis = int(nxt.after.total_seconds() * 1000)
            if nxt.on_timeout is not None:
                step_reply.next.pause.on_timeout.CopyFrom(_step_ref(nxt.on_timeout))
        elif isinstance(nxt, StepFail):
            step_reply.next.fail.CopyFrom(nxt.error.to_pb())
        else:
            assert isinstance(nxt, End)
            step_reply.next.end.SetInParent()
        return workflow_pb2.WorkflowOut(step_reply=step_reply)


class WorkflowServicer(workflow_pb2_grpc.WorkflowServicer):
    def __init__(self, registry: Registry, client: ComponentClient) -> None:
        self.registry = registry
        self.client = client

    async def Handle(
        self, request_iterator: AsyncIterator[workflow_pb2.WorkflowIn], context: Any
    ) -> AsyncIterator[workflow_pb2.WorkflowOut]:
        # Replies leave through one queue: commands are answered inline by the reader, steps by
        # their own task, so a command arriving mid-step is not stuck behind it.
        out: asyncio.Queue[workflow_pb2.WorkflowOut | None] = asyncio.Queue()
        stream: _WorkflowStream | None = None

        async def run_step(s: _WorkflowStream, run: workflow_pb2.WorkflowIn.RunStep) -> None:
            await out.put(await s.step(run))
            s.step_task = None

        async def read() -> None:
            nonlocal stream
            try:
                async for message in request_iterator:
                    kind = message.WhichOneof("message")
                    if kind == "init":
                        cls = self.registry.workflows.get(message.init.component_id)
                        if cls is None:
                            await out.put(workflow_pb2.WorkflowOut(failure=_failure(0, f"unknown component {message.init.component_id!r}", payload_pb2.NOT_FOUND)))
                            return
                        workflow = cls()
                        workflow._bind(message.init.entity_id)
                        state = cls.state_codec.decode(message.init.state.data) if message.init.HasField("state") else workflow.empty_state()
                        stream = _WorkflowStream(workflow, state, self.client)
                    elif kind == "command":
                        assert stream is not None
                        await out.put(await stream.command(message.command))
                    elif kind == "run_step":
                        assert stream is not None
                        if stream.step_task is not None and not stream.step_task.done():
                            await out.put(workflow_pb2.WorkflowOut(failure=_failure(message.run_step.id, "a step is already running", payload_pb2.INTERNAL)))
                            continue
                        stream.step_task = asyncio.create_task(run_step(stream, message.run_step))
            finally:
                if stream is not None and stream.step_task is not None:
                    stream.step_task.cancel()
                await out.put(None)

        reader = asyncio.create_task(read())
        try:
            while True:
                message = await out.get()
                if message is None:
                    return
                yield message
        finally:
            reader.cancel()


class ViewServicer(view_pb2_grpc.ViewServicer):
    def __init__(self, registry: Registry) -> None:
        self.registry = registry

    async def Handle(self, request: view_pb2.ViewRequest, context: Any) -> view_pb2.ViewEffect:
        cls = self.registry.views.get(request.component_id)
        if cls is None:
            await context.abort(grpc.StatusCode.NOT_FOUND, f"unknown view {request.component_id!r}")
        assert cls is not None
        view = cls()
        effect = await view._handle(
            None if request.deleted else request.event.data,
            request.row.data if request.HasField("row") else None,
            Metadata.from_pb(request.metadata),
        )
        if isinstance(effect, view_effects.UpdateRow):
            return view_pb2.ViewEffect(update_row=_payload_of(cls.row_codec, effect.row))
        if isinstance(effect, view_effects.DeleteRow):
            return view_pb2.ViewEffect(delete_row=payload_pb2.Empty())
        return view_pb2.ViewEffect(ignore=payload_pb2.Empty())


class ConsumerServicer(consumer_pb2_grpc.ConsumerServicer):
    def __init__(self, registry: Registry, client: ComponentClient) -> None:
        self.registry = registry
        self.client = client

    async def Handle(self, request: consumer_pb2.ConsumerRequest, context: Any) -> consumer_pb2.ConsumerEffect:
        cls = self.registry.consumers.get(request.component_id)
        if cls is None:
            await context.abort(grpc.StatusCode.NOT_FOUND, f"unknown consumer {request.component_id!r}")
        assert cls is not None
        metadata = Metadata.from_pb(request.metadata)
        consumer = cls(self.client.with_metadata(metadata))
        effect = await consumer._handle(None if request.deleted else request.message.data, metadata)
        if isinstance(effect, consumer_effects.Produce):
            assert cls.out_codec is not None
            return consumer_pb2.ConsumerEffect(
                produce=consumer_pb2.ConsumerEffect.Produce(payload=_payload_of(cls.out_codec, effect.payload), metadata=effect.metadata.to_pb())
            )
        if isinstance(effect, consumer_effects.Done):
            return consumer_pb2.ConsumerEffect(done=payload_pb2.Empty())
        return consumer_pb2.ConsumerEffect(ignore=payload_pb2.Empty())


class TimedActionServicer(timed_action_pb2_grpc.TimedActionServicer):
    def __init__(self, registry: Registry, client: ComponentClient) -> None:
        self.registry = registry
        self.client = client

    async def Invoke(self, request: timed_action_pb2.TimedActionRequest, context: Any) -> timed_action_pb2.TimedActionEffect:
        cls = self.registry.timed_actions.get(request.component_id)
        spec = cls.handlers().get(request.name) if cls else None
        if cls is None or spec is None:
            await context.abort(grpc.StatusCode.NOT_FOUND, f"unknown timed action {request.component_id}/{request.name}")
        assert cls is not None and spec is not None
        metadata = Metadata.from_pb(request.metadata)
        action = cls(self.client.with_metadata(metadata))
        try:
            effect = await action._run(spec, request.payload.data, metadata)
        except Exception as e:
            return timed_action_pb2.TimedActionEffect(fail=payload_pb2.Error(message=str(e) or type(e).__name__, code=payload_pb2.INTERNAL))
        if isinstance(effect, timed_effects.Failed):
            return timed_action_pb2.TimedActionEffect(fail=effect.error.to_pb())
        return timed_action_pb2.TimedActionEffect(done=payload_pb2.Empty())


class AgentServicer(agent_pb2_grpc.AgentServicer):
    """The three things the sidecar's loop asks a process for: a plan, a tool's result, a
    guardrail's verdict. The loop, the model and the memory are the sidecar's."""

    def __init__(self, registry: Registry, client: ComponentClient) -> None:
        self.registry = registry
        self.client = client

    def _agent(self, component_id: str, session_id: str, metadata: Metadata | None = None) -> Agent | None:
        cls = self.registry.agents.get(component_id)
        if cls is None:
            return None
        scoped = self.client.with_metadata(metadata) if metadata is not None else self.client
        return cls(scoped)

    async def Plan(self, request: agent_pb2.PlanRequest, context: Any) -> agent_pb2.PlanReply:
        metadata = Metadata.from_pb(request.metadata)
        agent = self._agent(request.component_id, request.session_id, metadata)
        spec = type(agent).handlers().get(request.name) if agent is not None else None
        if agent is None or spec is None:
            return agent_pb2.PlanReply(failure=_failure(0, f"unknown agent handler {request.component_id}/{request.name}", payload_pb2.NOT_FOUND))
        try:
            effect = await agent._plan(spec, request.payload.data, request.session_id, metadata)
        except Exception as e:
            log.warning("%s/%s %s raised: %s", request.component_id, request.session_id, request.name, e)
            return agent_pb2.PlanReply(failure=_failure(0, str(e) or type(e).__name__))
        return agent_pb2.PlanReply(plan=effect.to_pb())

    async def InvokeTool(self, request: agent_pb2.ToolRequest, context: Any) -> agent_pb2.ToolResult:
        agent = self._agent(request.component_id, request.session_id)
        if agent is None or request.tool not in type(agent).tools:
            return agent_pb2.ToolResult(error=f"no tool named {request.tool!r} on {request.component_id!r}")
        try:
            return agent_pb2.ToolResult(ok=await agent._invoke_tool(request.tool, request.arguments_json, request.session_id))
        except Exception as e:  # a message for the model, as FunctionTool.invoke answers
            return agent_pb2.ToolResult(error=str(e) or type(e).__name__)

    async def CheckGuardrail(self, request: agent_pb2.GuardrailRequest, context: Any) -> agent_pb2.GuardrailResult:
        agent = self._agent(request.component_id, request.session_id)
        if agent is None or request.guardrail not in type(agent).guardrails:
            await context.abort(grpc.StatusCode.NOT_FOUND, f"unknown guardrail {request.component_id}/{request.guardrail}")
        assert agent is not None
        stage = "input" if request.stage == agent_pb2.GuardrailRequest.INPUT else "output"
        reason = await agent._check_guardrail(request.guardrail, stage, request.text, request.session_id)
        if reason is None:
            passed = agent_pb2.GuardrailResult()
            getattr(passed, "pass").SetInParent()  # `pass` is a keyword, so the field is reached by name
            return passed
        return agent_pb2.GuardrailResult(block=reason)


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
        key_value_pb2_grpc.add_KeyValueServicer_to_server(KeyValueServicer(self.registry, self.client), server)  # type: ignore[no-untyped-call]
        workflow_pb2_grpc.add_WorkflowServicer_to_server(WorkflowServicer(self.registry, self.client), server)  # type: ignore[no-untyped-call]
        view_pb2_grpc.add_ViewServicer_to_server(ViewServicer(self.registry), server)  # type: ignore[no-untyped-call]
        consumer_pb2_grpc.add_ConsumerServicer_to_server(ConsumerServicer(self.registry, self.client), server)  # type: ignore[no-untyped-call]
        timed_action_pb2_grpc.add_TimedActionServicer_to_server(TimedActionServicer(self.registry, self.client), server)  # type: ignore[no-untyped-call]
        endpoint_pb2_grpc.add_HttpServicer_to_server(HttpServicer(self.registry, self.client), server)  # type: ignore[no-untyped-call]
        agent_pb2_grpc.add_AgentServicer_to_server(AgentServicer(self.registry, self.client), server)  # type: ignore[no-untyped-call]

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
