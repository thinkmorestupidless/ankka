"""A contract's fingerprint is the one every SDK computes: the fixtures the platform writes."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ankka import Contract, Publication

FIXTURES = Path(__file__).resolve().parent.parent / "proto" / "fixtures" / "contracts" / "fingerprints.json"


def rows() -> list[dict[str, object]]:
    loaded: list[dict[str, object]] = json.loads(FIXTURES.read_text())
    assert loaded, "the fixture file has no rows"
    return loaded


@pytest.mark.parametrize("row", rows(), ids=lambda row: str(row["name"]))
def test_every_fixture_row_fingerprints_as_the_platform_does(row: dict[str, object]) -> None:
    name = str(row["name"])
    # Re-serialised with other key order and whitespace: the fingerprint is of the document, not the bytes.
    reordered = json.dumps(row["schema"], indent=2, sort_keys=True, ensure_ascii=False)
    assert Contract.from_bytes(reordered.encode(), name=name) == Contract(name, str(row["fingerprint"]))
    unsorted = json.dumps(row["schema"], separators=(",", ":"), ensure_ascii=True)
    assert Contract.from_bytes(unsorted.encode(), name=name).fingerprint == row["fingerprint"]


def test_a_changed_field_changes_the_fingerprint() -> None:
    one = Contract.from_bytes(b'{"a": 1}', name="order.v1")
    two = Contract.from_bytes(b'{"a": 2}', name="order.v1")
    assert one != two


def test_a_document_that_is_not_json_is_refused() -> None:
    with pytest.raises(ValueError, match="not JSON"):
        Contract.from_bytes(b"{not json", name="order.v1")


@pytest.mark.parametrize("name", ["Order", ".v1", "a", "order v1"])
def test_a_name_outside_the_rule_is_refused(name: str) -> None:
    with pytest.raises(ValueError, match="contract name"):
        Contract.from_bytes(b"{}", name=name)


def test_from_file_reads_the_document(tmp_path: Path) -> None:
    path = tmp_path / "order.v1.json"
    path.write_text('{"type": "object"}')
    assert Contract.from_file(path, name="order.v1") == Contract.from_bytes(b'{"type":"object"}', name="order.v1")


def test_a_publication_carries_its_contract_and_broker_on_the_wire() -> None:
    contract = Contract.from_bytes(b"{}", name="order.v1")
    pb = Publication("orders", contract=contract, broker="legacy").to_pb()
    assert (pb.topic, pb.contract.name, pb.contract.fingerprint, pb.broker) == ("orders", "order.v1", contract.fingerprint, "legacy")
    plain = Publication("orders").to_pb()
    assert not plain.HasField("contract") and not plain.HasField("broker")


def test_another_projects_topic_is_named_by_its_project_on_the_wire() -> None:
    from ankka import StartFrom
    from ankka.codec import json_codec
    from ankka.consumer import Consumer
    from ankka.server import grants_refusal
    from ankka.service import Registry
    from dataclasses import dataclass

    @dataclass
    class Seen:
        n: int

    class Relay(Consumer[Seen, Seen]):
        component_id = "partner-relay"
        topic = "casino.players"
        project = "spinvibe"
        start_from = StartFrom.EARLIEST
        message_codec = json_codec(Seen, "seen")
        produces_to = Publication("payments.deposits", project="spinvibe")
        out_codec = json_codec(Seen, "seen")

        def on_message(self, message: Seen):  # type: ignore[no-untyped-def]
            return self.effects.produce(message)

    pb = Publication("payments.deposits", project="spinvibe").to_pb()
    assert (pb.topic, pb.project) == ("payments.deposits", "spinvibe")
    assert not Publication("orders").to_pb().HasField("project")
    registry = Registry()
    registry.consumers[Relay.component_id] = Relay
    spec = registry.spec()
    detail = next(c for c in spec.components if c.id == "partner-relay").consumer
    assert (detail.source.topic, detail.source.project) == ("casino.players", "spinvibe")
    assert detail.produces.project == "spinvibe"
    refusal = grants_refusal(registry, "1.14")
    assert refusal is not None and "Relay" in refusal
    assert grants_refusal(registry, "1.15") is None


def test_a_project_and_a_broker_together_are_refused() -> None:
    from ankka import RegistrationError, StartFrom
    from ankka.codec import json_codec
    from ankka.consumer import Consumer
    from dataclasses import dataclass

    @dataclass
    class Seen:
        n: int

    with pytest.raises(RegistrationError, match="names a project and a broker"):

        class Both(Consumer[Seen, Seen]):
            component_id = "both"
            topic = "casino.players"
            project = "spinvibe"
            broker = "legacy"
            start_from = StartFrom.EARLIEST
            message_codec = json_codec(Seen, "seen")

            def on_message(self, message: Seen):  # type: ignore[no-untyped-def]
                return self.effects.done()
