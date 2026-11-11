"""The unit kits' ``ClientStub``: ``features/polyglot/standing.feature`` and
``features/replication/status.feature``, "the component test kit answers ... as it was told" and
"the component test kit refuses a standing it was not told", for an endpoint that reads both."""

from __future__ import annotations

from dataclasses import dataclass

import pytest

from ankka import Acl, Endpoint, Lifecycle, ReplicationStatus, Status, get
from ankka.client import ComponentClient
from ankka.testkit import ClientStub, EndpointTestKit


@dataclass
class Standing:
    status: str
    failure: str | None = None


@dataclass
class Copy:
    home: str | None
    position: int
    pendingHandover: str | None = None  # noqa: N815
    conflict: str | None = None


class PlanEndpoint(Endpoint):
    """Reads a plan's standing and how a vehicle's copy stands, as a handler would."""

    prefix = "/reads"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    @get("/plans/{id}")
    async def standing(self, id: str) -> Standing:
        read = await self.client.for_workflow("plan", id).lifecycle()
        return Standing(read.status.value, read.failure)

    @get("/vehicles/{id}")
    async def copy(self, id: str) -> Copy:
        read = await self.client.for_event_sourced_entity("vehicle", id).replication()
        return Copy(read.home, read.position, read.pending_handover, read.conflict)

    @get("/plans/{id}/status")
    async def ask(self, id: str) -> str:
        return str(await self.client.for_workflow("plan", id).call("status").invoke(reply=str))


def test_the_component_test_kit_answers_a_workflows_standing_it_was_told() -> None:
    client = ClientStub().lifecycle("plan", "p2", Lifecycle(Status.FAILED, None, {"consult": 2}, "the provider refused"))
    answer = EndpointTestKit.of(PlanEndpoint, client).get("/reads/plans/p2").json()
    assert answer == {"status": "Failed", "failure": "the provider refused"}


def test_the_component_test_kit_answers_how_a_copy_stands_as_it_was_told() -> None:
    client = ClientStub().replication("vehicle", "v1", ReplicationStatus("leipzig", frozenset({"leipzig"}), None, None, 3))
    answer = EndpointTestKit.of(PlanEndpoint, client).get("/reads/vehicles/v1").json()
    assert (answer["home"], answer["position"]) == ("leipzig", 3)


def test_the_component_test_kit_answers_a_handover_waiting_and_a_conflict_as_it_was_told() -> None:
    both = frozenset({"leipzig", "vienna"})
    client = (
        ClientStub()
        .replication("vehicle", "v1", ReplicationStatus("leipzig", both, "vienna", None, 4))
        .replication("vehicle", "v2", ReplicationStatus("leipzig", both, None, "vienna", 2))
    )
    kit = EndpointTestKit.of(PlanEndpoint, client)
    assert kit.get("/reads/vehicles/v1").json()["pendingHandover"] == "vienna"
    assert kit.get("/reads/vehicles/v2").json()["conflict"] == "vienna"


def test_the_component_test_kit_refuses_a_standing_it_was_not_told() -> None:
    kit = EndpointTestKit.of(PlanEndpoint, ClientStub())
    with pytest.raises(RuntimeError, match="the standing of 'p9' of 'plan' was not stubbed"):
        kit.get("/reads/plans/p9")
    with pytest.raises(RuntimeError, match="how 'v9' of 'vehicle' stands was not stubbed"):
        kit.get("/reads/vehicles/v9")


def test_every_other_call_is_refused_as_a_unit_kits_client_refuses_it() -> None:
    kit = EndpointTestKit.of(PlanEndpoint, ClientStub().lifecycle("plan", "p1", Lifecycle(Status.RUNNING, "consult", {}, None)))
    with pytest.raises(RuntimeError, match="a unit test cannot call other components"):
        kit.get("/reads/plans/p1/status")


def test_a_component_is_named_by_its_class_as_well_as_by_its_id() -> None:
    class Plan:
        component_id = "plan"

    client = ClientStub().lifecycle(Plan, "p3", Lifecycle(Status.PAUSED, "consult", {}, None))
    assert EndpointTestKit.of(PlanEndpoint, client).get("/reads/plans/p3").json()["status"] == "Paused"
