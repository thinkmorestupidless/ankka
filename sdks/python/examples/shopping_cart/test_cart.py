from __future__ import annotations

from typing import Any

from ankka import ErrorCode
from ankka.testkit import EventSourcedTestKit
from examples.shopping_cart.domain import CheckedOut, ItemAdded, ItemRemoved, LineItem, ShoppingCart
from examples.shopping_cart.entity import ShoppingCartEntity

PEN = LineItem("p1", "Pen", 2)
INK = LineItem("p2", "Ink", 1)


def test_add_merge_remove_and_checkout() -> None:
    kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
    assert kit.call("add-item", PEN).events == (ItemAdded(PEN),)
    assert kit.call("add-item", LineItem("p1", "Pen", 3)).persisted
    assert kit.state.items == [LineItem("p1", "Pen", 5)]
    kit.call("add-item", INK)
    assert kit.call("total-quantity").reply == 6
    assert kit.call("remove-item", "p2").events == (ItemRemoved("p2"),)
    checked = kit.call("checkout")
    assert checked.events == (CheckedOut(),)
    assert checked.reply == ShoppingCart("c1", [LineItem("p1", "Pen", 5)], True)


def _code(kit: EventSourcedTestKit[ShoppingCart, Any], name: str, input: object = None) -> ErrorCode:
    error = kit.call(name, input).error
    assert error is not None
    return error.code


def test_refusals() -> None:
    kit = EventSourcedTestKit.of(ShoppingCartEntity, "c2")
    assert _code(kit, "checkout") == ErrorCode.BAD_REQUEST
    assert _code(kit, "add-item", LineItem("p1", "Pen", 0)) == ErrorCode.BAD_REQUEST
    assert _code(kit, "remove-item", "nope") == ErrorCode.NOT_FOUND
    kit.call("add-item", PEN)
    checked = kit.call("checkout")
    assert checked.retention is not None  # the cart is deleted after the checkout event
    # A deleted cart is fresh, as in-process: the unit testkit models that with a new kit.
    fresh = EventSourcedTestKit.of(ShoppingCartEntity, "c2")
    assert fresh.state == ShoppingCart.empty("c2")


def test_the_json_is_the_scala_carts_json() -> None:
    kit = EventSourcedTestKit.of(ShoppingCartEntity, "c3")
    kit.call("add-item", PEN)
    assert ShoppingCartEntity.event_codec.encode(ItemAdded(PEN)) == b'{"type":"ItemAdded","item":{"productId":"p1","name":"Pen","quantity":2}}'
    assert ShoppingCartEntity.state_codec.encode(kit.state) == b'{"cartId":"c3","items":[{"productId":"p1","name":"Pen","quantity":2}],"checkedOut":false}'
    assert ShoppingCartEntity.event_codec.encode(CheckedOut()) == b'{"type":"CheckedOut"}'


# ── Through a real sidecar and a real Postgres (Docker) ────────────────────────


import pytest

from ankka import Ankka
from ankka.testkit.integration import AnkkaTestKit
from examples.shopping_cart.endpoint import ShoppingCartEndpoint

PEN_JSON = {"productId": "p1", "name": "Pen", "quantity": 2}
INK_JSON = {"productId": "p2", "name": "Ink", "quantity": 1}


@pytest.mark.slow
async def test_cart_through_the_sidecar_survives_a_restart() -> None:
    service = Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint)
    async with await AnkkaTestKit.start(service) as kit:
        assert (await kit.http.post("/carts/c1/items", json=PEN_JSON)).status_code == 204
        assert (await kit.http.post("/carts/c1/items", json=INK_JSON)).status_code == 204
        cart = (await kit.http.get("/carts/c1")).json()
        assert cart == {"cartId": "c1", "items": [PEN_JSON, INK_JSON], "checkedOut": False}
        assert (await kit.http.get("/carts/c1/total")).json() == 3
        # A refusal reaches the caller as its status.
        assert (await kit.http.post("/carts/c1/items", json={**PEN_JSON, "quantity": 0})).status_code == 400
        assert (await kit.http.delete("/carts/c1/items/nope")).status_code == 404

        await kit.restart()
        assert (await kit.http.get("/carts/c1")).json()["items"] == [PEN_JSON, INK_JSON]

        checked = (await kit.http.post("/carts/c1/checkout")).json()
        assert checked["checkedOut"] is True
        # Deleted after the checkout, as the Scala cart: the id is fresh again.
        assert (await kit.http.get("/carts/c1")).json() == {"cartId": "c1", "items": [], "checkedOut": False}
