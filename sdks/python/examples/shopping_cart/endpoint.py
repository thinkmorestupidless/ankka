from __future__ import annotations

import json

from collections.abc import AsyncIterator

from ankka import Acl, Done, Endpoint, HttpProblem, Socket, delete, get, post, socket, sse
from ankka.codec import default_codec_for
from ankka.client import Calls, ComponentClient

from examples.shopping_cart.cart_rows import CartRow
from examples.shopping_cart.checkout_log import CheckoutRecord
from examples.shopping_cart.checkout_workflow import Checkout
from examples.shopping_cart.domain import LineItem, ShoppingCart


# docs:start endpoint
class ShoppingCartEndpoint(Endpoint):
    """The Scala sample's routes, exactly: /carts/{cartId}, /total, /items, /items/{productId}, /checkout, DELETE /carts/{cartId}."""

    prefix = "/carts"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    def _cart(self, cart_id: str) -> Calls:
        return self.client.with_metadata(self.request.metadata).for_event_sourced_entity("shopping-cart", cart_id)

    @get("/{cartId}")
    async def get_cart(self, cartId: str) -> ShoppingCart:
        return await self._cart(cartId).call("get-cart").invoke(reply=ShoppingCart)

    @get("/{cartId}/total")
    async def total(self, cartId: str) -> int:
        return await self._cart(cartId).call("total-quantity").invoke(reply=int)

    @post("/{cartId}/items")
    async def add_item(self, cartId: str, item: LineItem) -> Done:
        return await self._cart(cartId).call("add-item").invoke(item, reply=Done)

    @delete("/{cartId}/items/{productId}")
    async def remove_item(self, cartId: str, productId: str) -> Done:
        return await self._cart(cartId).call("remove-item").invoke(productId, reply=Done)

    @post("/{cartId}/checkout")
    async def checkout(self, cartId: str) -> ShoppingCart:
        return await self._cart(cartId).call("checkout").invoke(reply=ShoppingCart)

    @delete("/{cartId}")
    async def discard(self, cartId: str) -> Done:
        return await self._cart(cartId).call("discard").invoke(reply=Done)
    # docs:end endpoint

    # docs:start socket
    # A socket: the client sends "refresh" and is sent the cart, for as long as it keeps the socket
    # open. `async for` ends when the socket is closed, and so does the handler.
    @socket("/{cartId}/watch")
    async def watch(self, cartId: str, socket: Socket) -> None:
        async for text in socket:
            if text == "refresh":
                cart = await self._cart(cartId).call("get-cart").invoke(reply=ShoppingCart)
                await socket.send(default_codec_for(ShoppingCart).encode(cart).decode("utf-8"))
            else:
                await socket.send(json.dumps({"error": f"unknown request {text!r}; send refresh"}))
    # docs:end socket

    # ── The view, the workflow and the notifier's log ──────────────────────

    @get("/awkward")
    def awkward(self) -> str:
        """A literal beside a parameter: the router must prefer it over ``/{cartId}``."""
        return "literal"

    # docs:start problem
    @get("/{cartId}/rows")
    async def row(self, cartId: str) -> CartRow:
        found = await self.client.views.get("cart-rows", cartId, CartRow)
        if found is None:
            raise HttpProblem(404, f"no row for cart '{cartId}'")
        return found  # type: ignore[no-any-return]
    # docs:end problem

    @get("/rows")
    async def rows(self) -> list[CartRow]:
        return await self.client.views.all("cart-rows", CartRow)

    # docs:start start-workflow
    @post("/{cartId}/checkouts")
    async def start_checkout(self, cartId: str, mode: str) -> Done:
        """``mode`` is the body: ``ok``, ``fail`` or ``pause``."""
        return await self.client.with_metadata(self.request.metadata).for_workflow("checkout", cartId).call("start").invoke(mode or "ok", reply=Done)
    # docs:end start-workflow

    @get("/{cartId}/checkouts")
    async def checkout_status(self, cartId: str) -> Checkout:
        return await self.client.with_metadata(self.request.metadata).for_workflow("checkout", cartId).call("status").invoke(reply=Checkout)

    # docs:start start-and-await
    @post("/{cartId}/checkouts/wait")
    async def check_out_and_wait(self, cartId: str, mode: str) -> Checkout:
        """Starts the checkout and answers with how it ended, as one request."""
        checkout = self.client.with_metadata(self.request.metadata).for_workflow("checkout", cartId)
        return await checkout.call("start").then_await_end(30.0).invoke(mode or "ok", reply=Checkout)
    # docs:end start-and-await

    # docs:start await-later
    @get("/{cartId}/checkouts/end")
    async def checkout_end(self, cartId: str) -> Checkout:
        """How a checkout someone else started ended: at once if it has, when it does if not."""
        return await self.client.with_metadata(self.request.metadata).for_workflow("checkout", cartId).await_end(10.0, reply=Checkout)
    # docs:end await-later

    # docs:start sse-await
    @sse("/{cartId}/checkouts/events")
    async def checkout_events(self, cartId: str) -> AsyncIterator[str]:
        """A heartbeat while the checkout goes on, then how it ended, on one connection."""
        checkout = self.client.with_metadata(self.request.metadata).for_workflow("checkout", cartId)
        async for part in checkout.await_end_parts(600.0, reply=Checkout):
            yield part.to_json()
    # docs:end sse-await

    # ── The assistant: a POST answers whole, a GET streams tokens as SSE ───

    # docs:start agent-routes
    @post("/ask/{session}")
    async def ask(self, session: str, question: str) -> str:
        return await self.client.with_metadata(self.request.metadata).for_agent("assistant", session).call("ask").invoke(question, reply=str)

    @sse("/chat/{session}")
    async def chat(self, session: str) -> AsyncIterator[str]:
        question = next((v for k, v in self.request.query if k == "q"), "")
        async for token in self.client.with_metadata(self.request.metadata).for_agent("assistant", session).call("chat").stream(question):
            yield token
    # docs:end agent-routes

    @get("/{cartId}/checkout-log")
    async def checkout_log(self, cartId: str) -> CheckoutRecord:
        return await self.client.with_metadata(self.request.metadata).for_key_value_entity("checkout-log", cartId).call("get").invoke(reply=CheckoutRecord)
