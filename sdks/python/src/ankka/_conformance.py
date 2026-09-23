"""`uv run conformance`: serve the Python reference service and run the platform's conformance
suite against it, exiting with sbt's status. Needs Docker (the suite starts Postgres) and sbt on
PATH. The reference service is the SDK's example, so the SDK checkout is put on the path."""

from __future__ import annotations

import asyncio
import logging
import os
import subprocess
import sys
import threading
from pathlib import Path

SDK = Path(__file__).resolve().parents[2]
REPO = SDK.parent.parent
PORT = int(os.environ.get("ANKKA_PROCESS_PORT", "9010"))


def _serve(ready: threading.Event, stop: threading.Event) -> None:
    sys.path.insert(0, str(SDK))
    from examples.shopping_cart.conformance import reference_service

    async def run() -> None:
        server = reference_service().server()
        await server.start("127.0.0.1", PORT)
        ready.set()
        while not stop.is_set():
            await asyncio.sleep(0.2)
        await server.stop(grace=1.0)

    asyncio.run(run())


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
    ready, stop = threading.Event(), threading.Event()
    thread = threading.Thread(target=_serve, args=(ready, stop), daemon=True)
    thread.start()
    if not ready.wait(30):
        print("the reference service did not start", file=sys.stderr)
        return 1
    command = [
        "sbt",
        f"-Dankka.conformance.target=127.0.0.1:{PORT}",
        *(["-Dankka.benchmarks=on"] if os.environ.get("ANKKA_BENCHMARKS") else []),
        "-Dankka.cluster.tests=off",
        "-Dankka.template.tests=off",
        "sidecar/testOnly *ConformanceSuite" + (f" -- {only}" if (only := os.environ.get("ANKKA_CONFORMANCE_ONLY")) else ""),
    ]
    print("running:", " ".join(command), "in", REPO, flush=True)
    try:
        return subprocess.run(command, cwd=REPO).returncode
    finally:
        stop.set()
        thread.join(5)
