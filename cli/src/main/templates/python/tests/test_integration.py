"""The whole service through the real sidecar: Postgres and the sidecar image in Docker, this process
serving the components to it, and an HTTP client for the routes. Seconds, not milliseconds.

The sidecar image is `$ANKKA_SIDECAR_IMAGE`, or `ankka-sidecar:latest`. These tests are skipped, and
say so, when that image is not on this machine; see README.md, "The sidecar image"."""

import asyncio
import os
import shutil
import subprocess
from collections.abc import Callable
from typing import Any

import pytest
from ankka.testkit.integration import AnkkaTestKit

from {{module}}.main import service

IMAGE = os.environ.get("ANKKA_SIDECAR_IMAGE", "ankka-sidecar:latest")


def _have_image() -> bool:
    if shutil.which("docker") is None:
        return False
    return subprocess.run(["docker", "image", "inspect", IMAGE], capture_output=True).returncode == 0


pytestmark = pytest.mark.skipif(not _have_image(), reason=f"the sidecar image {IMAGE} is not on this machine; see README.md")


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
