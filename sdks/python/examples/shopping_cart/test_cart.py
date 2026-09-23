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


# ── The view, the notifier and the workflow, without a sidecar ─────────────────

from ankka.effects.view import DeleteRow, UpdateRow
from ankka.effects.workflow import End, TransitionTo
from ankka.testkit import ConsumerTestKit, KeyValueTestKit, ViewTestKit, WorkflowTestKit
from examples.shopping_cart.cart_rows import CartRow, CartRows
from examples.shopping_cart.checkout_log import CheckoutLog, CheckoutRecord
from examples.shopping_cart.checkout_notifier import CheckoutNotifier
from examples.shopping_cart.checkout_workflow import CheckoutWorkflow


def test_cart_rows_follow_the_events_and_outlive_the_cart() -> None:
    kit = ViewTestKit.of(CartRows)
    assert isinstance(kit.on_change("c1", ItemAdded(PEN)), UpdateRow)
    kit.on_change("c1", ItemAdded(LineItem("p1", "Pen", 3)))
    kit.on_change("c1", ItemAdded(INK))
    kit.on_change("c1", ItemRemoved("p2"))
    assert kit.get("c1") == CartRow("c1", {"p1": 5}, False)
    kit.on_change("c1", CheckedOut())
    assert isinstance(kit.on_delete("c1"), UpdateRow)  # the tombstone: a checked-out cart stays queryable
    assert kit.get("c1") == CartRow("c1", {"p1": 5}, True)
    assert kit.on_delete("never-seen").__class__.__name__ == "Ignore"
    assert CartRows.row_codec.encode(CartRow("c1", {"p1": 5}, True)) == b'{"cartId":"c1","quantities":{"p1":5},"checkedOut":true}'


def test_notifier_only_cares_about_checkouts() -> None:
    kit = ConsumerTestKit.of(CheckoutNotifier)
    assert kit.on_message(ItemAdded(PEN)).__class__.__name__ == "Ignore"
    assert kit.on_delete().__class__.__name__ == "Ignore"
    log = KeyValueTestKit.of(CheckoutLog, "c1")
    assert log.call("record", 1700000000000).reply is not None
    assert log.call("get").reply == CheckoutRecord("c1", 1700000000000, True)


def test_checkout_workflow_declares_its_recovery() -> None:
    kit = WorkflowTestKit.of(CheckoutWorkflow, "c1")
    started = kit.call("start")
    assert started.transition is not None and started.transition.step == "reserve"
    assert kit.state.status == "reserving"
    assert kit.call("start").error is not None
    assert kit.run_step("compensate").next == End()
    assert kit.state.status == "compensated"
    settings = CheckoutWorkflow.to_component().workflow.settings
    assert {s.step: s.recovery.failover_to for s in settings.steps} == {"reserve": "compensate", "charge": "compensate"}


# ── Through a real sidecar and a real Postgres (Docker) ────────────────────────


import pytest

from ankka import Ankka
from ankka.testkit.integration import AnkkaTestKit
from examples.shopping_cart.endpoint import ShoppingCartEndpoint
from examples.shopping_cart.main import service

PEN_JSON = {"productId": "p1", "name": "Pen", "quantity": 2}
INK_JSON = {"productId": "p2", "name": "Ink", "quantity": 1}


async def _eventually(check: Any, timeout: float = 20.0) -> Any:
    import asyncio
    import time

    deadline = time.monotonic() + timeout
    while True:
        found = await check()
        if found is not None or time.monotonic() > deadline:
            return found
        await asyncio.sleep(0.2)


async def _json_when(kit: AnkkaTestKit, path: str, accept: Any, timeout: float = 20.0) -> Any:
    """GETs ``path`` until it answers 200 with a body ``accept`` likes; fails naming the last answer."""
    last: Any = None

    async def check() -> Any:
        nonlocal last
        last = await kit.http.get(path)
        if last.status_code != 200:
            return None
        body = last.json()
        return body if accept(body) else None

    found = await _eventually(check, timeout)
    assert found is not None, f"GET {path}: {last.status_code} {last.text!r}\n{kit.sidecar_logs()[-3000:]}"
    return found


@pytest.mark.slow
async def test_every_kind_through_the_sidecar() -> None:
    async with await AnkkaTestKit.start(service()) as kit:
        assert (await kit.http.post("/carts/k1/items", json=PEN_JSON)).status_code == 204
        assert (await kit.http.post("/carts/k1/items", json=INK_JSON)).status_code == 204

        # The view's row appears, keyed by the cart, and is queryable through the client.
        await _json_when(kit, "/carts/k1/row", lambda r: r["quantities"] == {"p1": 2, "p2": 1})
        assert (await kit.http.get("/carts/rows")).json()[0]["cartId"] == "k1"

        # The workflow reserves, charges (checking the cart out) and ends; the notifier records.
        assert (await kit.http.post("/carts/k1/checkouts")).status_code == 204
        charged = await _json_when(kit, "/carts/k1/checkouts", lambda s: s["status"] == "charged")
        assert charged == {"cartId": "k1", "status": "charged", "reserved": 3}
        assert (await kit.http.post("/carts/k1/checkouts")).status_code == 409
        assert (await kit.http.get("/carts/k1")).json() == {"cartId": "k1", "items": [], "checkedOut": False}
        logged = await _json_when(kit, "/carts/k1/checkout-log", lambda r: r["notified"])
        assert logged["cartId"] == "k1" and logged["at"] > 0
        tombstone = await _json_when(kit, "/carts/k1/row", lambda r: r["checkedOut"])
        assert tombstone == {"cartId": "k1", "quantities": {"p1": 2, "p2": 1}, "checkedOut": True}

        # An empty cart cannot be reserved: the step fails over to compensation.
        assert (await kit.http.post("/carts/k2/checkouts")).status_code == 204
        await _json_when(kit, "/carts/k2/checkouts", lambda s: s["status"] == "compensated")

        # Everything above is journaled by the sidecar: a new sidecar on the same database agrees.
        await kit.restart()
        assert (await kit.http.get("/carts/k1/checkouts")).json()["status"] == "charged"
        assert (await kit.http.get("/carts/k1/checkout-log")).json()["notified"] is True
        assert (await kit.http.get("/carts/k1/row")).json()["checkedOut"] is True


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
