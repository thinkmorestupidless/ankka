"""What a view or consumer that reads a topic declares about it: where it starts, and its version."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

import pytest

from ankka import RegistrationError, StartFrom, json_codec
from ankka._proto.ankka.protocol.v1 import discovery_pb2
from ankka.consumer import Consumer
from ankka.effects.consumer import ConsumerEffect
from ankka.effects.view import ViewEffect
from ankka.server import DiscoveryServicer
from ankka.service import Ankka
from ankka.view import View

from tests.counter import CounterEntity

CODEC = json_codec(dict, "message")


def consumer(**declared: Any) -> type[Consumer[Any, None]]:
    body: dict[str, Any] = {"component_id": "notifier", "message_codec": CODEC, **declared}
    body.setdefault("topic", "orders")

    def on_message(self: Any, message: Any) -> ConsumerEffect:
        return self.effects.done()

    body["on_message"] = on_message
    return type("Notifier", (Consumer,), body)


def view(**declared: Any) -> type[View[Any, Any]]:
    body: dict[str, Any] = {"component_id": "summary", "event_codec": CODEC, "row_codec": CODEC, **declared}
    body.setdefault("topic", "orders")

    def on_change(self: Any, event: Any) -> ViewEffect:
        return self.effects.update_row(event)

    body["on_change"] = on_change
    return type("Summary", (View,), body)


def test_each_start_position_is_written_into_discovery() -> None:
    when = datetime(2026, 10, 1, 12, tzinfo=UTC)
    earliest = consumer(start_from=StartFrom.EARLIEST).to_component().consumer.source.start_from
    latest = consumer(start_from=StartFrom.LATEST).to_component().consumer.source.start_from
    at = consumer(start_from=StartFrom.at(when)).to_component().consumer.source.start_from
    assert earliest.named == discovery_pb2.StartFrom.EARLIEST
    assert latest.named == discovery_pb2.StartFrom.LATEST
    assert at.at_millis == int(when.timestamp() * 1000)


def test_a_view_declaring_nothing_writes_no_start_position_and_no_version() -> None:
    detail = view().to_component().view
    assert not detail.source.HasField("start_from")
    assert not detail.HasField("version")


def test_a_version_is_written_into_discovery() -> None:
    assert view(version=2).to_component().view.version == 2
    assert consumer(start_from=StartFrom.LATEST, version=3).to_component().consumer.version == 3


def test_a_consumer_reading_a_topic_must_declare_its_start_position() -> None:
    with pytest.raises(RegistrationError, match="Notifier reads topic 'orders' and declares no start position"):
        consumer()


def test_a_start_time_must_say_its_timezone() -> None:
    with pytest.raises(ValueError, match="timezone"):
        StartFrom.at(datetime(2026, 10, 1, 12))


@pytest.mark.parametrize("version", [0, -1, True, 2.5, "2"])
def test_a_version_that_is_not_a_positive_whole_number_is_refused(version: Any) -> None:
    with pytest.raises(RegistrationError, match="a version is a whole number of 1 or more"):
        view(version=version)


def test_a_version_or_start_on_a_component_that_reads_an_entity_is_refused() -> None:
    with pytest.raises(RegistrationError, match="declares a version, which applies to a topic"):
        view(topic=None, source=CounterEntity, version=2)
    with pytest.raises(RegistrationError, match="declares a start position, which applies to a topic"):
        consumer(topic=None, source=CounterEntity, start_from=StartFrom.LATEST)


def test_a_sidecar_too_old_for_start_positions_is_refused_naming_what_declares_one() -> None:
    declaring = consumer(start_from=StartFrom.LATEST)
    service = Ankka.service().register(declaring)
    servicer = DiscoveryServicer(service._registry)
    refusal = servicer.refusal("1.6")
    assert refusal is not None
    assert "Notifier" in refusal and "1.6" in refusal and "1.7" in refusal
    assert servicer.refusal("1.7") is None


def test_a_service_that_declares_neither_is_served_by_an_older_sidecar() -> None:
    plain = view(topic=None, source=CounterEntity)
    servicer = DiscoveryServicer(Ankka.service().register(plain)._registry)
    assert servicer.refusal("1.6") is None
