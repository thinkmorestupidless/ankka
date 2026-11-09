"""The documentation's consumer samples (docs/build/consumers.md): a consumer of a replicated entity
reading where each change was written."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass

from ankka import Metadata, json_codec
from ankka.consumer import Consumer
from ankka.effects.consumer import ConsumerEffect, Produce
from tests.test_replicated import StepAdded, VehicleEntity


@dataclass(frozen=True)
class Build:
    """A vehicle's step as published: which installation wrote it, and whether that was this one."""

    vehicle: str
    step: str
    installation: str | None
    localOrigin: bool


# docs:start mirrored-change
class VehicleBuilds(Consumer[StepAdded, Build]):
    component_id = "vehicle-builds"
    source = VehicleEntity
    message_codec = VehicleEntity.event_codec
    produces_to = "vehicle-builds"
    out_codec = json_codec(Build, "build")

    def on_message(self, event: StepAdded) -> ConsumerEffect:
        change = self.metadata
        return self.effects.produce(Build(change.subject or "", event.step, self.installation, self.local_origin))
# docs:end mirrored-change


def _change(installation: str, local: bool) -> Metadata:
    return Metadata().set("ce-subject", "v1").set("ankka.installation", installation).set("ankka.local", str(local).lower())


def test_a_change_says_where_it_was_written_and_whether_that_was_here() -> None:
    consumer = VehicleBuilds()
    frame = VehicleEntity.event_codec.encode(StepAdded("frame"))
    local = asyncio.run(consumer._handle(frame, _change("leipzig", True), None))
    assert isinstance(local, Produce) and local.payload == Build("v1", "frame", "leipzig", True)
    mirrored = asyncio.run(consumer._handle(frame, _change("leipzig", False), None))
    assert isinstance(mirrored, Produce) and mirrored.payload == Build("v1", "frame", "leipzig", False)
    # A consumer that publishes is given local changes only unless it says otherwise.
    assert VehicleBuilds.to_component().consumer.mirrored_changes is False
