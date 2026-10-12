"""Waiting for a workflow's end from a Python handler, through the sidecar: the Python rows of
``features/awaiting-workflows/languages.feature`` and the stream form of ``serving.feature``.
Needs Docker and the sidecar image from this branch (``ANKKA_SIDECAR_IMAGE``)."""

from __future__ import annotations

import json

import pytest

from ankka.client import CommandError
from ankka.effects.common import ErrorCode
from ankka.testkit.integration import AnkkaTestKit
from examples.shopping_cart.checkout_workflow import Checkout
from examples.shopping_cart.main import service

PEN_JSON = {"productId": "p1", "name": "Pen", "quantity": 2}


@pytest.mark.slow
async def test_a_handler_sends_a_command_and_waits_for_the_end_and_a_failure_names_its_step() -> None:
    async with await AnkkaTestKit.start(service()) as kit:
        # A handler sends a command and is answered with the state, in one request.
        assert (await kit.http.post("/carts/aw1/items", json=PEN_JSON)).status_code == 204
        answer = await kit.http.post("/carts/aw1/checkouts/wait", content="ok")
        assert answer.status_code == 200, answer.text
        assert answer.json() == {"cartId": "aw1", "status": "charged", "reserved": 2, "mode": "ok"}

        # Waited for later, by a caller that did not start it: at once, it has ended.
        ended = await kit.client.for_workflow("checkout", "aw1").await_end(10.0, reply=Checkout)
        assert ended.status == "charged"

        # A workflow that fails is answered with the failure, its step and its reason.
        failed = await kit.http.post("/carts/aw2/checkouts/wait", content="doomed")
        assert failed.status_code == 424, failed.text
        with pytest.raises(CommandError) as refused:
            await kit.client.for_workflow("checkout", "aw2").await_end(5.0, reply=Checkout)
        assert refused.value.error.code == ErrorCode.WORKFLOW_FAILED
        assert refused.value.error.details["step"] == "compensate"
        assert "compensation failed too" in refused.value.error.details["reason"]

        # Not answered in time: told so, and the workflow runs on.
        await kit.http.post("/carts/aw3/checkouts", content="pause")
        with pytest.raises(CommandError) as late:
            await kit.client.for_workflow("checkout", "aw3").await_end(0.2, reply=Checkout)
        assert late.value.error.code == ErrorCode.TIMEOUT
        assert (await kit.client.for_workflow("checkout", "aw3").await_end(20.0, reply=Checkout)).status == "charged"


@pytest.mark.slow
async def test_a_wait_served_as_server_sent_events_ends_with_the_state() -> None:
    async with await AnkkaTestKit.start(service()) as kit:
        await kit.http.post("/carts/aw4/checkouts", content="pause")
        events: list[str] = []
        async with kit.http.stream("GET", "/carts/aw4/checkouts/events", timeout=30.0) as response:
            assert response.status_code == 200
            async for line in response.aiter_lines():
                if line.startswith("data:"):
                    events.append(json.loads(line.removeprefix("data:").strip()))
        last = json.loads(events[-1])
        assert last["ended"]["status"] == "charged", events
        assert all(json.loads(e) == {"heartbeat": True} for e in events[:-1]), events
