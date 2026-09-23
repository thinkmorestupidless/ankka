"""Run the cart: `uv run example`, beside `docker compose --profile polyglot up`."""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from ankka import Ankka

from examples.shopping_cart.cart_rows import CartRows
from examples.shopping_cart.checkout_log import CheckoutLog
from examples.shopping_cart.checkout_notifier import CheckoutNotifier
from examples.shopping_cart.checkout_workflow import CheckoutWorkflow
from examples.shopping_cart.endpoint import ShoppingCartEndpoint
from examples.shopping_cart.entity import ShoppingCartEntity


def service() -> Any:
    """The whole inventory: registration is explicit, so nothing is discovered by scanning."""
    return (
        Ankka.service()
        .register(ShoppingCartEntity)
        .register(CartRows)
        .register(CheckoutNotifier)
        .register(CheckoutLog)
        .register(CheckoutWorkflow)
        .register(ShoppingCartEndpoint)
    )


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
    await service().listen()


if __name__ == "__main__":
    asyncio.run(main())
