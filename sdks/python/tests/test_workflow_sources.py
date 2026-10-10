"""A workflow as the source of a view or a consumer (protocol 1.15): the standing it is handed."""

from __future__ import annotations

from dataclasses import dataclass

from ankka import Standing, json_codec
from ankka._proto.ankka.protocol.v1 import discovery_pb2, payload_pb2
from ankka.consumer import Consumer
from ankka.effects.consumer import ConsumerEffect
from ankka.effects.view import ViewEffect
from ankka.server import DiscoveryServicer, reads_workflow
from ankka.service import Ankka
from ankka.testkit.unit import ConsumerTestKit, ViewTestKit
from ankka.view import View

from examples.shopping_cart.checkout_workflow import Checkout, CheckoutWorkflow
from tests.counter import CounterEntity


@dataclass(frozen=True)
class Row:
    status: str
    standing: str


class Rows(View[Checkout, Row]):
    component_id = "checkout-standings"
    source = CheckoutWorkflow
    event_codec = CheckoutWorkflow.state_codec
    row_codec = json_codec(Row, "row")

    def on_change(self, state: Checkout) -> ViewEffect:
        return self.effects.update_row(Row(state.status, self.standing.status if self.standing else "none"))


class Ends(Consumer[Checkout, None]):
    component_id = "checkout-ends-test"
    source = CheckoutWorkflow
    message_codec = CheckoutWorkflow.state_codec
    seen: list[Standing | None] = []

    def on_message(self, state: Checkout) -> ConsumerEffect:
        Ends.seen.append(self.standing)
        return self.effects.ignore()


class EntityRows(View[int, Row]):
    component_id = "entity-rows"
    source = CounterEntity
    event_codec = CounterEntity.event_codec
    row_codec = json_codec(Row, "row")

    def on_change(self, event: int) -> ViewEffect:
        return self.effects.update_row(Row("", self.standing.status if self.standing else "none"))


def test_a_view_of_a_workflow_is_handed_the_standing() -> None:
    kit = ViewTestKit.of(Rows)
    kit.on_change("c1", Checkout("c1", status="charged"), Standing("Completed"))
    assert kit.get("c1") == Row("charged", "Completed")


def test_a_change_handed_no_standing_has_none() -> None:
    kit = ViewTestKit.of(Rows)
    kit.on_change("c1", Checkout("c1", status="charged"))
    assert kit.get("c1") == Row("charged", "none")


def test_a_consumer_of_a_workflow_is_handed_the_standing() -> None:
    Ends.seen = []
    failed = Standing("Failed", failure="payment declined")
    ConsumerTestKit.of(Ends).on_message(Checkout("c1", status="aborted"), "c1", standing=failed)
    assert Ends.seen == [failed]
    assert failed.is_failed and failed.is_terminal and not failed.is_unknown


def test_a_standing_reads_from_the_wire_as_it_was_written() -> None:
    wire = payload_pb2.WorkflowStanding(status="Paused", step="charge", retries={"reserve": 2})
    standing = Standing.from_pb(wire)
    assert standing == Standing("Paused", "charge", {"reserve": 2}, None)
    assert Standing.from_pb(standing.to_pb()) == standing
    assert Standing.from_pb(payload_pb2.WorkflowStanding(status="Unknown")).is_unknown


def test_a_view_of_a_workflow_declares_the_workflow_as_its_source() -> None:
    source = Rows.to_component().view.source
    assert source.component.kind == discovery_pb2.WORKFLOW
    assert source.component.id == "checkout"
    assert reads_workflow(Rows) and reads_workflow(Ends) and not reads_workflow(EntityRows)


def test_a_sidecar_too_old_for_workflow_sources_is_refused_naming_them() -> None:
    servicer = DiscoveryServicer(Ankka.service().register(CheckoutWorkflow).register(Rows).register(Ends)._registry)
    refusal = servicer.refusal("1.14")
    assert refusal is not None
    assert "Rows" in refusal and "Ends" in refusal and "1.14" in refusal and "1.15" in refusal
    assert servicer.refusal("1.15") is None


def test_a_service_reading_no_workflow_is_served_by_a_1_14_sidecar() -> None:
    servicer = DiscoveryServicer(Ankka.service().register(CounterEntity).register(EntityRows)._registry)
    assert servicer.refusal("1.14") is None
