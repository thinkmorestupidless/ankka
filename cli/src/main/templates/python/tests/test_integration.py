"""The whole service through the real sidecar: Postgres and the sidecar image in Docker, this process
serving the components to it, and an HTTP client for the routes. Seconds, not milliseconds.

The sidecar is the one published with the SDK's version, pulled on first use; `$ANKKA_SIDECAR_IMAGE`
names another. Needs Docker."""

import asyncio
from collections.abc import Callable
from typing import Any

from ankka.testkit.integration import AnkkaTestKit

from {{module}}.main import service


async def _json_when(kit: AnkkaTestKit, path: str, ready: Callable[[Any], bool], timeout: float = 20.0) -> Any:
    """A view is eventually consistent: retry until the answer is the one expected, then return it."""
    deadline = asyncio.get_running_loop().time() + timeout
    while True:
        body = (await kit.http.get(path)).json()
        if ready(body) or asyncio.get_running_loop().time() > deadline:
            return body
        await asyncio.sleep(0.2)


async def test_an_item_survives_a_restart_and_is_listed() -> None:
    async with await AnkkaTestKit.start(service()) as kit:
        assert (await kit.http.post("/items/i1", json={"name": "Widget", "count": 2})).status_code < 300
        await kit.restart()  # a new sidecar, the same database: the state is durable, not cached
        assert (await kit.http.get("/items/i1")).json() == {"id": "i1", "name": "Widget", "count": 2}
        rows = await _json_when(kit, "/items/", lambda body: len(body) == 1)
        assert rows == [{"id": "i1", "name": "Widget", "count": 2}]
