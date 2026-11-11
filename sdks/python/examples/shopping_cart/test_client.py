"""The component client's samples (docs/build/component-client.md), run against a stand-in for
the sidecar: a component addressed, several calls at once, a refusal read as a `CommandError`, and
the view client."""

from __future__ import annotations

import asyncio
import json
from typing import Any

from ankka import Done, ErrorCode
from ankka._proto.ankka.protocol.v1 import client_pb2, payload_pb2
from ankka.client import Calls, CommandError, ComponentClient

from examples.shopping_cart.cart_rows import CartRow
from examples.shopping_cart.domain import LineItem, ShoppingCart


async def add_and_read(client: ComponentClient) -> ShoppingCart:
    # docs:start addressing
    cart = client.for_event_sourced_entity("shopping-cart", "c1")

    await cart.call("add-item").invoke(LineItem("p1", "Pen", 2))            # Done
    state = await cart.call("get-cart").invoke(reply=ShoppingCart)          # decoded as ShoppingCart
    # docs:end addressing
    return state


# docs:start fan-out
async def totals_of(client: ComponentClient, cart_ids: list[str]) -> list[int]:
    pending = [client.for_event_sourced_entity("shopping-cart", cart_id).call("total-quantity").invoke(reply=int) for cart_id in cart_ids]
    return list(await asyncio.gather(*pending))
# docs:end fan-out


# docs:start refusal
async def add_widget(cart: Calls) -> Done | str:
    try:
        return await cart.call("add-item").invoke(LineItem("p1", "Widget", 0), reply=Done)
    except CommandError as refused:
        if refused.error.code == ErrorCode.BAD_REQUEST:
            return refused.error.message
        raise
# docs:end refusal


# docs:start view-client
async def every_cart(client: ComponentClient) -> list[CartRow]:
    rows: list[CartRow] = await client.views.all("cart-rows", CartRow)
    return rows
# docs:end view-client


# docs:start get-and-all
async def a_row_and_every_row(client: ComponentClient, cart_id: str) -> tuple[CartRow | None, list[CartRow]]:
    row = await client.views.get("cart-rows", cart_id, CartRow)  # one row, or None
    rows = await client.views.all("cart-rows", CartRow)  # every row, up to 1000
    return row, rows
# docs:end get-and-all


class _Sidecar:
    """Answers as the sidecar would: a cart's total is the length of its id, an item with no
    quantity is refused, one with a quantity is kept for the cart, and the view holds two rows."""

    def __init__(self) -> None:
        self.items: list[dict[str, Any]] = []

    async def Invoke(self, request: client_pb2.InvokeRequest) -> client_pb2.InvokeReply:  # noqa: N802
        if request.name == "add-item":
            item = json.loads(request.payload.data)
            if item["quantity"] <= 0:
                return client_pb2.InvokeReply(error=payload_pb2.Error(message="quantity must be greater than zero, was 0", code=payload_pb2.BAD_REQUEST))
            self.items.append(item)
            done = payload_pb2.Payload(content_type="application/octet-stream", manifest="done")
            return client_pb2.InvokeReply(reply=payload_pb2.Outcome.Reply(payload=done))
        if request.name == "get-cart":
            cart = {"cartId": request.entity_id, "items": self.items, "checkedOut": False}
            state = payload_pb2.Payload(content_type="application/json", manifest="ShoppingCart", data=json.dumps(cart).encode())
            return client_pb2.InvokeReply(reply=payload_pb2.Outcome.Reply(payload=state))
        total = payload_pb2.Payload(content_type="text/plain", manifest="int", data=str(len(request.entity_id)).encode())
        return client_pb2.InvokeReply(reply=payload_pb2.Outcome.Reply(payload=total))

    async def Query(self, request: client_pb2.QueryRequest) -> client_pb2.QueryReply:  # noqa: N802
        rows = [{"cartId": "c1", "quantities": {"p1": 2}, "checkedOut": False}, {"cartId": "c2", "quantities": {}, "checkedOut": True}]
        return client_pb2.QueryReply(rows=payload_pb2.Payload(content_type="application/json", manifest="rows", data=json.dumps(rows).encode()))


def _client() -> ComponentClient:
    client = ComponentClient(address="127.0.0.1:0")
    client._stub_cache = _Sidecar()  # type: ignore[assignment]
    return client


def test_a_component_is_addressed_by_kind_and_instance_then_by_handler() -> None:
    assert asyncio.run(add_and_read(_client())) == ShoppingCart("c1", [LineItem("p1", "Pen", 2)], False)


def test_several_calls_at_once_take_as_long_as_the_slowest() -> None:
    assert asyncio.run(totals_of(_client(), ["c1", "cart-two", "c3"])) == [2, 8, 2]


def test_a_refusal_arrives_as_a_command_error_with_its_code() -> None:
    cart = _client().for_event_sourced_entity("shopping-cart", "c1")
    assert asyncio.run(add_widget(cart)) == "quantity must be greater than zero, was 0"


def test_the_view_client_answers_rows() -> None:
    rows: list[Any] = asyncio.run(every_cart(_client()))
    assert rows == [CartRow("c1", {"p1": 2}), CartRow("c2", {}, True)]


def test_the_view_client_answers_one_row_and_every_row() -> None:
    row, rows = asyncio.run(a_row_and_every_row(_client(), "c1"))
    assert row == CartRow("c1", {"p1": 2})
    assert rows == [CartRow("c1", {"p1": 2}), CartRow("c2", {}, True)]
