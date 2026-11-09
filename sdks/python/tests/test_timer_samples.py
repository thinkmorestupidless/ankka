"""The documentation's timed action sample (docs/build/timers.md): the handler that expires an order,
called directly with a client that answers."""

from __future__ import annotations

import asyncio

from ankka._proto.ankka.protocol.v1 import client_pb2, discovery_pb2, payload_pb2
from ankka.client import ComponentClient
from ankka.effects.timed_action import Done as TimerDone, TimedActionEffect
from ankka.timed_action import TimedAction, action

observed: list[str] = []


# docs:start order-timers
class OrderTimers(TimedAction):
    component_id = "order-timers"

    @action("expire-order")
    async def expire_order(self, order_id: str) -> TimedActionEffect:
        assert self.client is not None
        # Success whatever the order's state was: a confirmed order has nothing to cancel.
        outcome = await self.client.for_key_value_entity("order", order_id).call("cancel").invoke(reply=str)
        observed.append(f"{order_id}:{outcome}")
        return self.effects.done()
# docs:end order-timers


class _Sidecar:
    def __init__(self) -> None:
        self.cancelled: list[str] = []

    async def Invoke(self, request: client_pb2.InvokeRequest) -> client_pb2.InvokeReply:  # noqa: N802
        self.cancelled.append(request.entity_id)
        payload = payload_pb2.Payload(content_type="text/plain", manifest="string", data=b"cancelled")
        return client_pb2.InvokeReply(reply=payload_pb2.Outcome.Reply(payload=payload))


def test_the_handler_cancels_the_order_and_completes() -> None:
    observed.clear()
    sidecar = _Sidecar()
    client = ComponentClient(address="127.0.0.1:0")
    client._stub_cache = sidecar  # type: ignore[assignment]
    assert asyncio.run(OrderTimers(client).expire_order("o-7")) == TimerDone()
    assert sidecar.cancelled == ["o-7"] and observed == ["o-7:cancelled"]
    (handler,) = OrderTimers.to_component().handlers
    assert handler.name == "expire-order" and OrderTimers.to_component().kind == discovery_pb2.TIMED_ACTION
