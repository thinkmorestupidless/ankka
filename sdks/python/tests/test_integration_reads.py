"""The test kit makes both reads against the running service (protocol 1.24): the Python rows of
"the test kit reads a workflow's standing from the running service" (``features/polyglot/standing.feature``)
and "the test kit reads how a copy stands from the running service" (``features/replication/status.feature``).

The cart sample, with the conformance reference's replicated vehicle beside it. The sidecar runs as
the installation ``kit`` beside one, ``elsewhere``, that it never reaches, so the vehicle has a home.
Needs Docker and the sidecar image from this branch (``ANKKA_SIDECAR_IMAGE``)."""

from __future__ import annotations

import pytest

from ankka import Status
from ankka.client import CommandError
from ankka.effects.common import ErrorCode
from ankka.testkit.integration import AnkkaTestKit
from examples.shopping_cart.conformance import ReplicatedVehicle, VehiclesEndpoint
from examples.shopping_cart.main import service

PEN_JSON = {"productId": "p1", "name": "Pen", "quantity": 2}

# An installation named beside this one, whose database is nowhere: the copy is homed here and
# replicates to nothing.
INSTALLATIONS = {
    "ANKKA_INSTALLATION": "kit",
    "ANKKA_INSTALLATION_ELSEWHERE_DB_HOST": "elsewhere.invalid",
}


@pytest.mark.slow
async def test_the_test_kit_reads_a_workflows_standing_and_how_a_copy_stands_from_the_running_service() -> None:
    reads = service().register(ReplicatedVehicle).register(VehiclesEndpoint)
    async with await AnkkaTestKit.start(reads, env=INSTALLATIONS) as kit:
        # A workflow that ended, one that failed, and one never started.
        assert (await kit.http.post("/carts/ir1/items", json=PEN_JSON)).status_code == 204
        assert (await kit.http.post("/carts/ir1/checkouts/wait", content="ok")).status_code == 200
        ended = await kit.client.for_workflow("checkout", "ir1").lifecycle()
        assert ended.status == Status.COMPLETED and ended.is_terminal
        assert (await kit.http.post("/carts/ir2/items", json=PEN_JSON)).status_code == 204
        assert (await kit.http.post("/carts/ir2/checkouts/wait", content="doomed")).status_code == 424
        failed = await kit.client.for_workflow("checkout", "ir2").lifecycle()
        assert failed.is_failed and failed.failure
        assert failed.retries.get("charge") == 1
        never = await kit.client.for_workflow("checkout", "ir3").lifecycle()
        assert never.status == Status.NOT_STARTED

        # A copy of the replicated vehicle: its home is this installation, and its position counts
        # its event and the platform's records of its home and its replication set.
        assert (await kit.http.post("/vehicles/ir4/steps", content="frame")).status_code == 200
        copy = await kit.client.for_event_sourced_entity("replicated-vehicle", "ir4").replication()
        assert copy.home == "kit"
        assert copy.replication_set == frozenset({"kit"})
        assert copy.position == 3
        assert not copy.in_conflict and copy.pending_handover is None

        # An entity that is not replicated is refused, as a Scala handler is.
        with pytest.raises(CommandError) as refused:
            await kit.client.for_event_sourced_entity("shopping-cart", "ir1").replication()
        assert refused.value.error.code == ErrorCode.NOT_FOUND
