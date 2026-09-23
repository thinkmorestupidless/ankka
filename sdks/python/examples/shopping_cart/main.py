"""Run the cart: `uv run example`, beside `docker compose --profile polyglot up`."""

from __future__ import annotations

import asyncio
import logging

from ankka import Ankka

from examples.shopping_cart.endpoint import ShoppingCartEndpoint
from examples.shopping_cart.entity import ShoppingCartEntity


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
    await Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint).listen()


if __name__ == "__main__":
    asyncio.run(main())
