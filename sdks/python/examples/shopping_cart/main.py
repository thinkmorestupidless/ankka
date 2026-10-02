"""Run the cart: `uv run example`, beside `docker compose --profile polyglot up`."""

from __future__ import annotations

import asyncio
import logging
import os
from typing import Any

from ankka import Ankka

from examples.shopping_cart.answerer import CartAnswerer
from examples.shopping_cart.assistant import CartAssistant
from examples.shopping_cart.cart_graph import CartGraph
from examples.shopping_cart.cart_rows import CartRows
from examples.shopping_cart.checkout_log import CheckoutLog
from examples.shopping_cart.checkout_notifier import CheckoutNotifier
from examples.shopping_cart.checkout_workflow import CheckoutWorkflow
from examples.shopping_cart.endpoint import ShoppingCartEndpoint
from examples.shopping_cart.entity import ShoppingCartEntity
from examples.shopping_cart.questions import QuestionsEndpoint


def service() -> Any:
    """The whole inventory: registration is explicit, so nothing is discovered by scanning."""
    registered = (
        Ankka.service()
        .register(ShoppingCartEntity)
        .register(CartRows)
        .register(CheckoutNotifier)
        .register(CheckoutLog)
        .register(CheckoutWorkflow)
        .register(CartAssistant)
        .register(CartAnswerer)
        .register(ShoppingCartEndpoint)
        .register(QuestionsEndpoint)
    )
    # The graph is published to a topic, and a sidecar with no broker refuses a service that
    # publishes to one. So the cart runs as it is with no broker, and with one it publishes its graph.
    if os.environ.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS"):
        registered = registered.register(CartGraph)
    return registered


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
    await service().listen()


if __name__ == "__main__":
    asyncio.run(main())
