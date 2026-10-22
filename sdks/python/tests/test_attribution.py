"""Who acted, and where a command was forwarded from (protocol 1.22): read off the command into
the handler's context, and off each event into the event handler's, replayed or just persisted."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

from ankka import EventSourcedEffect, EventSourcedEntity, ReadOnlyEffect, command, json_codec, query
from ankka._proto.ankka.protocol.v1 import event_sourced_pb2, payload_pb2
from ankka.server import EventSourcedServicer
from ankka.service import PROTOCOL_VERSION, Registry


@dataclass(frozen=True)
class Inspections:
    acted_by: list[str | None] = field(default_factory=list)
    forwarded_from: list[str | None] = field(default_factory=list)


@dataclass(frozen=True)
class Inspected:
    note: str


@dataclass(frozen=True)
class Seen:
    attribution: str | None
    forwarded_from: str | None


class Vehicle(EventSourcedEntity[Inspections, Inspected]):
    component_id = "vehicle"
    state_codec = json_codec(Inspections, "inspections")
    event_codec = json_codec(Inspected, "inspected")

    def empty_state(self) -> Inspections:
        return Inspections()

    def apply_event(self, state: Inspections, event: Inspected) -> Inspections:
        at = self.event_context
        return Inspections(state.acted_by + [at.attribution], state.forwarded_from + [at.forwarded_from])

    @command("inspect")
    def inspect(self, note: str) -> EventSourcedEffect[Inspections, Inspected, Seen]:
        seen = Seen(self.context.attribution, self.context.forwarded_from)
        return self.effects.persist(Inspected(note)).then_reply(lambda _: seen)

    @query("get")
    def get(self) -> ReadOnlyEffect[Inspections, Inspected, Inspections]:
        return self.effects.reply(self.state)


class _NoClient:
    def with_metadata(self, metadata: Any) -> _NoClient:
        return self


def _stream(*messages: event_sourced_pb2.EventSourcedIn) -> list[event_sourced_pb2.EventSourcedOut]:
    registry = Registry()
    registry.entities["vehicle"] = Vehicle
    servicer = EventSourcedServicer(registry, client=_NoClient())

    async def feed() -> AsyncIterator[event_sourced_pb2.EventSourcedIn]:
        for m in messages:
            yield m

    async def run() -> list[event_sourced_pb2.EventSourcedOut]:
        return [out async for out in servicer.Handle(feed(), None)]

    return asyncio.run(run())


def _payload(value: Any, codec: Any) -> payload_pb2.Payload:
    return payload_pb2.Payload(content_type=codec.content_type, manifest=codec.manifest, data=codec.encode(value))


In = event_sourced_pb2.EventSourcedIn


def test_the_protocol_version_is_1_22() -> None:
    assert PROTOCOL_VERSION == "1.22"


def test_a_command_tells_its_handler_and_its_events_who_acted_and_where_it_was_forwarded_from() -> None:
    codec = Vehicle.handlers()["inspect"].input_codec
    out = _stream(
        In(init=In.Init(component_id="vehicle", entity_id="v1")),
        In(command=In.Command(id=1, name="inspect", payload=_payload("brakes", codec), attribution="ada", forwarded_from="bratislava")),
        In(command=In.Command(id=2, name="get")),
    )
    assert json_codec(Seen, "seen").decode(out[0].reply.outcome.reply.payload.data) == Seen("ada", "bratislava")
    state = Vehicle.state_codec.decode(out[1].reply.outcome.reply.payload.data)
    assert state == Inspections(["ada"], ["bratislava"])


def test_a_replayed_event_tells_the_event_handler_what_its_record_says() -> None:
    out = _stream(
        In(init=In.Init(component_id="vehicle", entity_id="v1")),
        In(event=In.Event(sequence=1, payload=_payload(Inspected("brakes"), Vehicle.event_codec), attribution="ada", forwarded_from="bratislava")),
        In(event=In.Event(sequence=2, payload=_payload(Inspected("tyres"), Vehicle.event_codec))),
        In(command=In.Command(id=1, name="get")),
    )
    state = Vehicle.state_codec.decode(out[0].reply.outcome.reply.payload.data)
    assert state == Inspections(["ada", None], ["bratislava", None])


def test_a_command_nobody_attributed_reads_none() -> None:
    codec = Vehicle.handlers()["inspect"].input_codec
    out = _stream(
        In(init=In.Init(component_id="vehicle", entity_id="v1")),
        In(command=In.Command(id=1, name="inspect", payload=_payload("brakes", codec))),
    )
    assert json_codec(Seen, "seen").decode(out[0].reply.outcome.reply.payload.data) == Seen(None, None)
