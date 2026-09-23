from __future__ import annotations

from collections.abc import AsyncIterator

from ankka import Done, Endpoint, HttpProblem, delete, get, post, sse
from ankka.client import Calls, ComponentClient

from examples.shopping_cart.cart_rows import CartRow
from examples.shopping_cart.checkout_log import CheckoutRecord
from examples.shopping_cart.checkout_workflow import Checkout
from examples.shopping_cart.domain import LineItem, ShoppingCart


class ShoppingCartEndpoint(Endpoint):
    """The Scala sample's routes, exactly: /carts/{cartId}, /total, /items, /items/{productId}, /checkout."""

    prefix = "/carts"

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

    # ── The view, the workflow and the notifier's log ──────────────────────

    @get("/{cartId}/row")
    async def row(self, cartId: str) -> CartRow:
        found = await self.client.views.get("cart-rows", cartId, CartRow)
        if found is None:
            raise HttpProblem(404, f"no row for cart '{cartId}'")
        return found  # type: ignore[no-any-return]

    @get("/rows")
    async def rows(self) -> list[CartRow]:
        return await self.client.views.all("cart-rows", CartRow)

    @post("/{cartId}/checkouts")
    async def start_checkout(self, cartId: str) -> Done:
        return await self.client.with_metadata(self.request.metadata).for_workflow("checkout", cartId).call("start").invoke(reply=Done)

    @get("/{cartId}/checkouts")
    async def checkout_status(self, cartId: str) -> Checkout:
        return await self.client.with_metadata(self.request.metadata).for_workflow("checkout", cartId).call("status").invoke(reply=Checkout)

    # ── The assistant: a POST answers whole, a GET streams tokens as SSE ───

    @post("/ask/{session}")
    async def ask(self, session: str, question: str) -> str:
        return await self.client.with_metadata(self.request.metadata).for_agent("assistant", session).call("ask").invoke(question, reply=str)

    @sse("/chat/{session}")
    async def chat(self, session: str) -> AsyncIterator[str]:
        question = next((v for k, v in self.request.query if k == "q"), "")
        async for token in self.client.with_metadata(self.request.metadata).for_agent("assistant", session).call("chat").stream(question):
            yield token

    @get("/{cartId}/checkout-log")
    async def checkout_log(self, cartId: str) -> CheckoutRecord:
        return await self.client.with_metadata(self.request.metadata).for_key_value_entity("checkout-log", cartId).call("get").invoke(reply=CheckoutRecord)
