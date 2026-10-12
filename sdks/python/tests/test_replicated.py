"""A replicated entity in Python (protocol 1.19): the declaration, the effects, the contexts, the
follower in the unit kit, and the refusal of a runtime from before replication."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

import pytest

from ankka import (
    ErrorCode,
    EventSourcedEffect,
    EventSourcedEntity,
    HandOver,
    ReadOnlyEffect,
    SetChange,
    command,
    json_codec,
    query,
)
from ankka._proto.ankka.protocol.v1 import event_sourced_pb2, payload_pb2
from ankka.server import EventSourcedServicer, replication_refusal
from ankka.service import Registry
from ankka.testkit import EventSourcedTestKit


@dataclass(frozen=True)
class Vehicle:
    steps: list[str] = field(default_factory=list)
    written_at: list[str | None] = field(default_factory=list)
    local: list[bool] = field(default_factory=list)


@dataclass(frozen=True)
class StepAdded:
    step: str


@dataclass(frozen=True)
class Placement:
    installation: str | None
    home: bool


class VehicleEntity(EventSourcedEntity[Vehicle, StepAdded]):
    component_id = "vehicle"
    replicated = True
    state_codec = json_codec(Vehicle, "vehicle")
    event_codec = json_codec(StepAdded, "step-added")

    def empty_state(self) -> Vehicle:
        return Vehicle()

    def apply_event(self, state: Vehicle, event: StepAdded) -> Vehicle:
        here = self.event_context
        return Vehicle(state.steps + [event.step], state.written_at + [here.installation], state.local + [here.is_local])

    @command("add-step")
    def add_step(self, step: str) -> EventSourcedEffect[Vehicle, StepAdded, Placement]:
        placement = Placement(self.context.installation, self.context.is_home)
        return self.effects.persist(StepAdded(step)).then_reply(lambda _: placement)

    @command("hand-over")
    def hand_over(self, target: str) -> EventSourcedEffect[Vehicle, StepAdded, str]:
        return self.effects.hand_over_to(target).then_reply(lambda _: target)

    @command("allot")
    def allot(self, target: str) -> EventSourcedEffect[Vehicle, StepAdded, str]:
        return self.effects.persist(StepAdded("allotted")).replicate_to(target).stop_replicating_to("factory").then_reply(
            lambda _: target
        )

    @query("get")
    def get(self) -> ReadOnlyEffect[Vehicle, StepAdded, Vehicle]:
        return self.effects.reply(self.state)


def test_an_entity_declared_replicated_says_so_in_discovery() -> None:
    assert VehicleEntity.to_component().event_sourced.replicated is True


def test_an_entity_that_says_nothing_is_not_replicated() -> None:
    class Plain(EventSourcedEntity[Vehicle, StepAdded]):
        component_id = "plain"
        state_codec = json_codec(Vehicle, "vehicle")
        event_codec = json_codec(StepAdded, "step-added")

    assert Plain.to_component().event_sourced.replicated is False


def test_the_effects_are_carried_beside_the_events() -> None:
    kit = EventSourcedTestKit.of(VehicleEntity, "v1", mode="home", installation="leipzig")
    assert kit.call("hand-over", "vienna").replication == HandOver("vienna")
    allotted = kit.call("allot", "vienna")
    assert allotted.events == (StepAdded("allotted"),)
    assert allotted.replication == SetChange(frozenset({"vienna"}), frozenset({"factory"}))


def test_two_changes_on_one_command_are_refused_where_they_are_built() -> None:
    effects = VehicleEntity().effects
    with pytest.raises(ValueError):
        effects.hand_over_to("a").replicate_to("b")
    with pytest.raises(ValueError):
        effects.replicate_to("b").stop_replicating_to("b")


def test_at_the_home_a_command_is_told_it_is_at_the_home() -> None:
    kit = EventSourcedTestKit.of(VehicleEntity, "v1", mode="home", installation="leipzig")
    assert kit.call("add-step", "frame").reply == Placement("leipzig", True)
    assert kit.state.written_at == ["leipzig"] and kit.state.local == [True]


def test_a_follower_refuses_a_persisting_command_naming_the_home_and_answers_a_query() -> None:
    kit = EventSourcedTestKit.of(VehicleEntity, "v1", mode="follower", home="leipzig", installation="bratislava")
    kit.replay(StepAdded("frame"), written_at="leipzig")
    refused = kit.call("add-step", "wheels")
    assert refused.error is not None and refused.error.code == ErrorCode.NOT_HOME
    assert "leipzig" in refused.error.message
    answered = kit.call("get").reply
    assert answered == Vehicle(["frame"], ["leipzig"], [False])


def test_event_context_is_only_readable_inside_apply_event() -> None:
    with pytest.raises(RuntimeError, match="event_context"):
        _ = VehicleEntity().event_context


def test_an_entity_not_replicated_in_the_kit_refuses_a_replication_change() -> None:
    kit = EventSourcedTestKit.of(VehicleEntity, "v1")
    refused = kit.call("hand-over", "vienna")
    assert refused.error is not None and refused.error.code == ErrorCode.BAD_REQUEST


def _stream(*messages: event_sourced_pb2.EventSourcedIn) -> list[event_sourced_pb2.EventSourcedOut]:
    registry = Registry()
    registry.entities["vehicle"] = VehicleEntity
    servicer = EventSourcedServicer(registry, client=_NoClient())

    async def feed() -> AsyncIterator[event_sourced_pb2.EventSourcedIn]:
        for m in messages:
            yield m

    async def run() -> list[event_sourced_pb2.EventSourcedOut]:
        return [out async for out in servicer.Handle(feed(), None)]

    return asyncio.run(run())


class _NoClient:
    def with_metadata(self, metadata: Any) -> _NoClient:
        return self


def _payload(value: Any, codec: Any) -> payload_pb2.Payload:
    return payload_pb2.Payload(content_type=codec.content_type, manifest=codec.manifest, data=codec.encode(value))


def test_over_the_protocol_the_event_handler_is_told_where_each_event_was_written() -> None:
    In = event_sourced_pb2.EventSourcedIn
    out = _stream(
        In(init=In.Init(component_id="vehicle", entity_id="v1", installation="bratislava")),
        In(event=In.Event(sequence=1, payload=_payload(StepAdded("frame"), VehicleEntity.event_codec), installation="leipzig")),
        In(command=In.Command(id=1, name="get", installation="bratislava", at_home=False)),
    )
    state = VehicleEntity.state_codec.decode(out[0].reply.outcome.reply.payload.data)
    assert state.written_at == ["leipzig"] and state.local == [False]


def test_over_the_protocol_a_command_at_the_home_is_told_so_and_its_events_are_local() -> None:
    In = event_sourced_pb2.EventSourcedIn
    out = _stream(
        In(init=In.Init(component_id="vehicle", entity_id="v1", installation="leipzig")),
        In(command=In.Command(id=1, name="add-step", payload=_payload("frame", VehicleEntity.handlers()["add-step"].input_codec), installation="leipzig", at_home=True)),
        In(command=In.Command(id=2, name="get", installation="leipzig", at_home=True)),
    )
    placement = json_codec(Placement, "placement").decode(out[0].reply.outcome.reply.payload.data)
    assert placement == Placement("leipzig", True)
    state = VehicleEntity.state_codec.decode(out[1].reply.outcome.reply.payload.data)
    assert state.written_at == ["leipzig"] and state.local == [True]


def test_over_the_protocol_a_handover_is_sent_on_the_reply() -> None:
    In = event_sourced_pb2.EventSourcedIn
    out = _stream(
        In(init=In.Init(component_id="vehicle", entity_id="v1", installation="leipzig")),
        In(command=In.Command(id=1, name="hand-over", payload=_payload("vienna", VehicleEntity.handlers()["hand-over"].input_codec), installation="leipzig", at_home=True)),
    )
    assert out[0].reply.replication.hand_over_to == "vienna"


def test_a_runtime_from_before_replication_is_refused_naming_the_protocol_version() -> None:
    registry = Registry()
    registry.entities["vehicle"] = VehicleEntity
    refusal = replication_refusal(registry, "1.18")
    assert refusal is not None
    assert "1.19" in refusal and "VehicleEntity" in refusal
    assert replication_refusal(registry, "1.19") is None
