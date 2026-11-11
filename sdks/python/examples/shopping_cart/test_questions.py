"""The questions endpoint's review route, run with a client whose sidecar keeps what it is asked."""

from __future__ import annotations

import asyncio
import json

from ankka import DONE
from ankka._proto.ankka.protocol.v1 import client_pb2, payload_pb2
from ankka.client import ComponentClient

from examples.shopping_cart.questions import QuestionsEndpoint


class _Sidecar:
    def __init__(self) -> None:
        self.sent: list[client_pb2.InvokeRequest] = []

    async def Invoke(self, request: client_pb2.InvokeRequest) -> client_pb2.InvokeReply:  # noqa: N802
        self.sent.append(request)
        payload = payload_pb2.Payload(content_type="application/json", manifest="json", data=b"{}")
        return client_pb2.InvokeReply(reply=payload_pb2.Outcome.Reply(payload=payload))


def test_review_creates_two_tasks_the_second_after_the_first_and_assigns_both_to_one_instance() -> None:
    sidecar = _Sidecar()
    client = ComponentClient(address="127.0.0.1:0")
    client._stub_cache = sidecar  # type: ignore[assignment]
    assert asyncio.run(QuestionsEndpoint(client).review()) == DONE
    creates = [r for r in sidecar.sent if r.name == "create"]
    (assigned,) = [r for r in sidecar.sent if r.name == "assign"]
    first, second = (r.entity_id for r in creates)
    assert json.loads(creates[1].payload.data)["dependencies"] == [first]
    assert assigned.entity_id == "reviewer-1"
    assert json.loads(assigned.payload.data)["taskIds"] == [first, second]
