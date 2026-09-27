"""The whole service definition: `uv run python -m {{module}}.main`.

Registration is explicit — nothing is discovered by scanning — so this is also the complete
inventory of what the service hosts, and a component you forget is simply not there.

`listen()` serves the sidecar protocol on port 9010 on loopback, where the sidecar finds it. Locally
the sidecar and Postgres come from `docker compose up -d`; in an ankka deployment the platform runs
the sidecar beside this process and provides the database.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from ankka import Ankka

from {{module}}.api import ItemEndpoint
from {{module}}.item_entity import ItemEntity
from {{module}}.item_rows import ItemRows


def service() -> Any:
    return Ankka.service().register(ItemEntity).register(ItemRows).register(ItemEndpoint)


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
    await service().listen()


if __name__ == "__main__":
    asyncio.run(main())
