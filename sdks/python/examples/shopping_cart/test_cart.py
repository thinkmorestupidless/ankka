from __future__ import annotations

import asyncio

import json

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


# docs:start key-value-test
def test_recording_a_checkout_replaces_the_value() -> None:
    log = KeyValueTestKit.of(CheckoutLog, "c1")
    assert log.call("record", 1700000000000).reply is not None
    assert log.call("get").reply == CheckoutRecord("c1", 1700000000000, True)
# docs:end key-value-test


# docs:start workflow-test
def test_checkout_workflow_declares_its_recovery() -> None:
    kit = WorkflowTestKit.of(CheckoutWorkflow, "c1")
    started = kit.call("start", "fail")
    assert started.transition is not None and started.transition.step == "reserve"
    assert kit.state.status == "reserving" and kit.state.mode == "fail"
    assert kit.call("start", "ok").error is not None
    assert kit.run_step("compensate").next == End()
    assert kit.state.status == "compensated"
    settings = CheckoutWorkflow.to_component().workflow.settings
    assert {s.step: s.recovery.failover_to for s in settings.steps} == {"charge": "compensate"}
# docs:end workflow-test


# docs:start agent-test
def test_assistant_plans_and_the_tool_reads_the_cart() -> None:
    from ankka.testkit import AgentTestKit, ScriptedModel
    from examples.shopping_cart.assistant import CartAssistant

    model = ScriptedModel().expect_tool_call("lookup", {"cartId": "c9"}).expect_text("Your cart is empty.")
    answer = AgentTestKit.of(CartAssistant, "s1", model).call("ask", "what is in cart c9?")
    assert answer.plan.tool_names == ("lookup",) and answer.plan.guardrail_names == ("no-secrets",)
    assert answer.reply == "Your cart is empty."
    # The tool ran in this process — the unit testkit's client answers nothing, so it reports that.
    assert answer.tool_results and answer.tool_results[0].startswith("error:")
# docs:end agent-test


# ── Through a real sidecar and a real Postgres (Docker) ────────────────────────


import pytest

from ankka import Ankka
from ankka.testkit.integration import AnkkaTestKit
from ankka.agent import Tool
from ankka.autonomous import AutonomousAgent, TaskAcceptance
from examples.shopping_cart.answerer import ANSWER, Answer, CartAnswerer, CartRef
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
        await _json_when(kit, "/carts/k1/rows", lambda r: r["quantities"] == {"p1": 2, "p2": 1})
        assert (await kit.http.get("/carts/rows")).json()[0]["cartId"] == "k1"

        # The workflow reserves, charges (checking the cart out) and ends; the notifier records.
        assert (await kit.http.post("/carts/k1/checkouts", content="ok")).status_code == 204
        charged = await _json_when(kit, "/carts/k1/checkouts", lambda s: s["status"] == "charged")
        assert charged == {"cartId": "k1", "status": "charged", "reserved": 3, "mode": "ok"}
        assert (await kit.http.post("/carts/k1/checkouts", content="ok")).status_code == 409
        assert (await kit.http.get("/carts/k1")).json() == {"cartId": "k1", "items": [], "checkedOut": False}
        logged = await _json_when(kit, "/carts/k1/checkout-log", lambda r: r["notified"])
        assert logged["cartId"] == "k1" and logged["at"] > 0
        tombstone = await _json_when(kit, "/carts/k1/rows", lambda r: r["checkedOut"])
        assert tombstone == {"cartId": "k1", "quantities": {"p1": 2, "p2": 1}, "checkedOut": True}

        # A declined charge is retried once, then fails over to compensation.
        assert (await kit.http.post("/carts/k2/checkouts", content="fail")).status_code == 204
        await _json_when(kit, "/carts/k2/checkouts", lambda s: s["status"] == "compensated")

        # Everything above is journaled by the sidecar: a new sidecar on the same database agrees.
        await kit.restart()
        assert (await kit.http.get("/carts/k1/checkouts")).json()["status"] == "charged"
        assert (await kit.http.get("/carts/k1/checkout-log")).json()["notified"] is True
        assert (await kit.http.get("/carts/k1/rows")).json()["checkedOut"] is True


# docs:start scripted-sidecar
SCRIPT = json.dumps(
    [
        {"tool": "lookup", "arguments": {"cartId": "a1"}},
        {"text": "Your cart holds 2 x Pen and 1 x Ink."},
        {"text": "Streamed answer here"},
        {"text": "the key is sk-000"},
    ]
)


@pytest.mark.slow
async def test_assistant_through_the_sidecar_with_a_scripted_model() -> None:
    """The loop runs in the sidecar against its scripted model; the tool runs here and reads the
    cart through the client; tokens stream back as SSE; the guardrail here blocks a leak."""
    async with await AnkkaTestKit.start(service(), env={"ANKKA_MODEL_SCRIPT": SCRIPT}) as kit:
        assert (await kit.http.post("/carts/a1/items", json=PEN_JSON)).status_code == 204
        assert (await kit.http.post("/carts/a1/items", json=INK_JSON)).status_code == 204
        # A str body and a str reply are text/plain, as a Scala endpoint's String is.
        asked = await kit.http.post("/carts/ask/s1", content="what is in cart a1?", headers={"content-type": "text/plain"})
        assert asked.status_code == 200, asked.text
        assert asked.text == "Your cart holds 2 x Pen and 1 x Ink."
        async with kit.http.stream("GET", "/carts/chat/s2?q=hello") as r:
            body = "".join([chunk async for chunk in r.aiter_text()])
        frames = [line[len("data:") :].strip() for line in body.splitlines() if line.startswith("data:")]
        assert [json.loads(f) for f in frames] == ["Streamed", " answer", " here"], f"body: {body!r}\n{kit.sidecar_logs()[-2500:]}"
        leaked = await kit.http.post("/carts/ask/s3", content="key?", headers={"content-type": "text/plain"})
        assert leaked.status_code == 403, leaked.text
# docs:end scripted-sidecar


# docs:start integration
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
# docs:end integration


# docs:start scripted-autonomous
ANSWER_SCRIPT = json.dumps(
    [
        {"tool": "cart_total", "arguments": {"cartId": "q1"}},
        # The first answer cites nothing, so the rule in this process sends it back.
        {"tool": "complete_task", "arguments": {"answer": "3 items", "sources": []}},
        {"tool": "complete_task", "arguments": {"answer": "Cart q1 holds 3 items.", "sources": ["cart_total"]}},
    ]
)


@pytest.mark.slow
async def test_autonomous_task_through_the_sidecar() -> None:
    """The loop runs in the sidecar; the tool runs here and reads the cart; the rule here rejects an
    answer that cites nothing; the typed result is read back through the task's record."""
    async with await AnkkaTestKit.start(service(), env={"ANKKA_MODEL_SCRIPT": ANSWER_SCRIPT}) as kit:
        assert (await kit.http.post("/carts/q1/items", json=PEN_JSON)).status_code == 204
        assert (await kit.http.post("/carts/q1/items", json=INK_JSON)).status_code == 204
        asked = await kit.http.post("/questions/ask", content="How many items are in q1?", headers={"content-type": "text/plain"})
        assert asked.status_code == 200, asked.text
        task_id = asked.json()["taskId"]

        done = await kit.await_task(task_id, ANSWER)
        assert done.status == "completed", done
        assert done.result == Answer("Cart q1 holds 3 items.", ["cart_total"])
        assert done.iterations == 3
        read = (await kit.http.get(f"/questions/{task_id}")).json()
        assert read["status"] == "completed" and read["answer"]["sources"] == ["cart_total"]
# docs:end scripted-autonomous


FAIL_SCRIPT = json.dumps([{"tool": "fail_task", "arguments": {"reason": "carts cannot see the future"}}])


@pytest.mark.slow
async def test_autonomous_task_fails_on_request() -> None:
    async with await AnkkaTestKit.start(service(), env={"ANKKA_MODEL_SCRIPT": FAIL_SCRIPT}) as kit:
        task_id = await kit.client.for_autonomous_agent(CartAnswerer).run_single_task(ANSWER, "What will be in q2 next year?")
        done = await kit.await_task(task_id, ANSWER)
        assert (done.status, done.reason, done.result) == ("failed", "carts cannot see the future", None)



# A tool that holds its first call until the test lets it go: an instance frozen mid-iteration.
_entered = asyncio.Event()
_release = asyncio.Event()


async def _slow_total(agent: AutonomousAgent, ref: CartRef) -> str:
    if not _entered.is_set():
        _entered.set()
        await _release.wait()
    return f"cart {ref.cartId} holds 3 items"


class SlowAnswerer(AutonomousAgent):
    component_id = "slow-answerer"
    description = "Answers questions about carts, slowly"
    tools = {"cart_total": Tool("Counts the items in a cart.", _slow_total, CartRef)}
    accepts = [TaskAcceptance(ANSWER, max_iterations=5)]


# Rules only, chosen by what each request holds, so a replacement sidecar's fresh script picks up
# where the task stands rather than from its first turn.
RESUME_SCRIPT = json.dumps(
    [
        {"when_tool_result": "holds", "tool": "complete_task", "arguments": {"answer": "3", "sources": ["cart_total"]}},
        {"when": "q3", "tool": "cart_total", "arguments": {"cartId": "q3"}},
    ]
)


@pytest.mark.slow
async def test_autonomous_task_survives_the_sidecar_being_replaced() -> None:
    """The sidecar runs the loop and holds the records: replaced mid-tool, the new one resumes the
    task from its journal, runs the unrecorded tool again, and finishes."""
    service = Ankka.service().register(SlowAnswerer)
    async with await AnkkaTestKit.start(service, env={"ANKKA_MODEL_SCRIPT": RESUME_SCRIPT}) as kit:
        task_id = await kit.client.for_autonomous_agent(SlowAnswerer).run_single_task(ANSWER, "How many items in q3?")
        await asyncio.wait_for(_entered.wait(), 30)
        await kit.restart()
        _release.set()
        done = await kit.await_task(task_id, ANSWER, timeout=60)
        assert done.status == "completed", done
        assert done.result == Answer("3", ["cart_total"])


@pytest.mark.slow
async def test_autonomous_notifications_ordered() -> None:
    """A subscriber hears a task's run in order, from the moment it subscribed."""
    script = json.dumps([{"tool": "complete_task", "arguments": {"answer": "empty", "sources": ["memory"]}}])
    async with await AnkkaTestKit.start(service(), env={"ANKKA_MODEL_SCRIPT": script}) as kit:
        seen: list[str] = []
        started = asyncio.Event()

        async def watch() -> None:
            async for n in kit.notifications("cart-answerer", "watched"):
                seen.append(n.type)
                started.set()
                if n.type == "TaskCompleted":
                    return

        watcher = asyncio.create_task(watch())
        await asyncio.wait_for(started.wait(), 30)
        task_id = await kit.client.tasks.create(ANSWER, "What is in q9?")
        await kit.client.for_autonomous_agent(CartAnswerer, "watched").assign(task_id)
        await asyncio.wait_for(watcher, 30)
        assert seen[0] == "Activated"
        assert seen[1:] == ["TaskAssigned", "TaskStarted", "IterationStarted", "IterationCompleted", "TaskCompleted"], seen


# docs:start unit-test
async def test_answerer_pieces_without_a_sidecar() -> None:
    """The tools, the result check and the rules, run directly: no sidecar, no model."""
    from ankka.testkit import AutonomousAgentTestKit

    kit = AutonomousAgentTestKit.of(CartAnswerer)
    assert await kit.check_result(ANSWER, Answer("3", [])) == ("cites-sources", "say which tools you used in sources")
    assert await kit.check_result(ANSWER, Answer("3", ["cart_total"])) is None
# docs:end unit-test

