"""SC-002, the cart half: a journal written through the Python cart is read by the Scala cart on
the same database, and the reverse. Needs Docker and the images `ankka-sidecar` and
`sample-shopping-cart` (`sbt docker:publishLocal`; `$ANKKA_SAMPLE_IMAGE` overrides the latter)."""

from __future__ import annotations

import os

import pytest

from ankka import Ankka
from ankka.testkit.integration import AnkkaTestKit
from examples.shopping_cart.endpoint import ShoppingCartEndpoint
from examples.shopping_cart.entity import ShoppingCartEntity

PEN = {"productId": "p1", "name": "Pen", "quantity": 2}
INK = {"productId": "p2", "name": "Ink", "quantity": 1}


@pytest.mark.slow
async def test_python_writes_scala_reads_and_the_reverse() -> None:
    scala_image = os.environ.get("ANKKA_SAMPLE_IMAGE", "sample-shopping-cart:latest")
    service = Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint)
    async with await AnkkaTestKit.start(service) as kit:
        # Python writes c1.
        assert (await kit.http.post("/carts/c1/items", json=PEN)).status_code == 204
        assert (await kit.http.post("/carts/c1/items", json=INK)).status_code == 204
        # The Scala cart, on the same database, reads it — replaying Python's JSON through the
        # Scala codecs.
        scala, scala_http = kit.start_beside(scala_image)
        try:
            await AnkkaTestKit.wait_healthy(scala_http)
            cart = (await scala_http.get("/carts/c1")).json()
            assert cart == {"cartId": "c1", "items": [PEN, INK], "checkedOut": False}
            # Scala writes c2.
            assert (await scala_http.post("/carts/c2/items", json=INK)).status_code == 204
            assert (await scala_http.post("/carts/c2/items", json={**PEN, "quantity": 5})).status_code == 204
        finally:
            await scala_http.aclose()
            scala.stop()
        # Python reads c2 — replaying Scala's JSON through the Python codecs.
        cart = (await kit.http.get("/carts/c2")).json()
        assert cart == {"cartId": "c2", "items": [{**PEN, "quantity": 5}, INK], "checkedOut": False}
        assert (await kit.http.get("/carts/c2/total")).json() == 6
