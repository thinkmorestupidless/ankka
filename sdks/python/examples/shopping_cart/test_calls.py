"""The entity pages' samples of registering and calling an entity (docs/build/event-sourced-entities.md,
docs/build/key-value-entities.md), run against a stand-in for the sidecar that records each call."""

from __future__ import annotations

import asyncio
import json
from typing import Any

from ankka import Ankka
from ankka._proto.ankka.protocol.v1 import client_pb2, payload_pb2
from ankka.client import ComponentClient

from examples.shopping_cart.checkout_log import CheckoutLog, CheckoutRecord
from examples.shopping_cart.domain import LineItem, ShoppingCart
from examples.shopping_cart.entity import ShoppingCartEntity


# docs:start call-entity
async def add_and_read(client: ComponentClient, item: LineItem) -> ShoppingCart:
    cart = client.for_event_sourced_entity("shopping-cart", "c1")
    await cart.call("add-item").invoke(item)  # Done
    return await cart.call("get-cart").invoke(reply=ShoppingCart)
# docs:end call-entity


# docs:start key-value-call
def checkout_log_service() -> Any:
    """Registered like any other component, and called through the component client."""
    return Ankka.service().register(CheckoutLog)


async def checkout_record(client: ComponentClient, cart_id: str) -> CheckoutRecord:
    return await client.for_key_value_entity("checkout-log", cart_id).call("get").invoke(reply=CheckoutRecord)
# docs:end key-value-call


class _Sidecar:
    """Answers as the sidecar would, and keeps what it was asked: every cart holds a pen, and every
    checkout log records a checkout at 42."""

    def __init__(self) -> None:
        self.asked: list[tuple[str, str, str]] = []

    async def Invoke(self, request: client_pb2.InvokeRequest) -> client_pb2.InvokeReply:  # noqa: N802
        self.asked.append((request.component_id, request.entity_id, request.name))
        answers = {
            "add-item": b"",
            "get-cart": json.dumps({"cartId": request.entity_id, "items": [{"productId": "p1", "name": "Pen", "quantity": 2}], "checkedOut": False}).encode(),
            "get": json.dumps({"cartId": request.entity_id, "at": 42, "notified": True}).encode(),
        }
        payload = payload_pb2.Payload(content_type="application/json", manifest=request.name, data=answers[request.name])
        return client_pb2.InvokeReply(reply=payload_pb2.Outcome.Reply(payload=payload))


def _client(sidecar: _Sidecar) -> ComponentClient:
    client = ComponentClient(address="127.0.0.1:0")
    client._stub_cache = sidecar  # type: ignore[assignment]
    return client


def test_an_entity_is_called_by_its_component_id_and_entity_id() -> None:
    sidecar = _Sidecar()
    cart = asyncio.run(add_and_read(_client(sidecar), LineItem("p1", "Pen", 2)))
    assert cart == ShoppingCart("c1", [LineItem("p1", "Pen", 2)], False)
    assert sidecar.asked == [("shopping-cart", "c1", "add-item"), ("shopping-cart", "c1", "get-cart")]


def test_a_key_value_entity_is_registered_and_called_like_any_other() -> None:
    assert [c.id for c in checkout_log_service().spec().components] == ["checkout-log"]
    sidecar = _Sidecar()
    assert asyncio.run(checkout_record(_client(sidecar), "c1")) == CheckoutRecord("c1", 42, True)
    assert sidecar.asked == [("checkout-log", "c1", "get")]


def test_the_cart_entity_is_the_one_called() -> None:
    assert ShoppingCartEntity.component_id == "shopping-cart"
