"""The cart's tools for a customer's assistant, over MCP, and the keys the cart issues for them."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import timedelta

from ankka import Acl, Done, Endpoint, Expiry, McpEndpoint, delete, post, tool

from examples.shopping_cart.domain import LineItem
from examples.shopping_cart.entity import ShoppingCartEntity


# docs:start endpoint
class CartMcpEndpoint(McpEndpoint):
    """The cart's tools for an assistant, at ``/mcp``: one a reference to a query the cart already
    has, one a handler of its own."""

    path = "/mcp"

    # docs:start acl
    # A request carrying one of the cart's client keys, as the key's subject (`CartKeysEndpoint`).
    acl = Acl.CLIENT_KEYS
    # docs:end acl

    # docs:start reference
    get_cart = tool.query(
        ShoppingCartEntity, "get-cart", id="cartId", description="Reads one cart: its lines, and whether it is checked out"
    )
    # docs:end reference

    # docs:start handler
    @tool("Adds a quantity of a product to a cart")
    async def add_item(self, cartId: str, productId: str, quantity: int) -> Done:
        assert self.client is not None
        cart = self.client.with_metadata(self.request.metadata).for_event_sourced_entity("shopping-cart", cartId)
        return await cart.call("add-item").invoke(LineItem(productId, productId, quantity), reply=Done)
    # docs:end handler
# docs:end endpoint


@dataclass(frozen=True)
class KeyRequest:
    subject: str
    scope: list[str] = field(default_factory=list)
    label: str | None = None


@dataclass(frozen=True)
class IssuedKey:
    id: str
    clientKey: str


# docs:start keys
class CartKeysEndpoint(Endpoint):
    """Client keys for the cart's tools: one per customer's assistant, issued by the cart's own code,
    because only the cart knows who its customers are. The key is in the answer to the issue, once;
    after that the cart revokes keys by id.

    Open to every caller here, as the rest of the sample is. A real product puts these routes behind
    its own sign-in, and issues a key for the customer who is signed in."""

    prefix = "/mcp-keys"
    acl = Acl.ALLOW_ALL

    @post("/")
    async def issue(self, asked: KeyRequest) -> IssuedKey:
        issued = await self.client_keys.issue(asked.subject, Expiry.after(timedelta(days=365)), scope=asked.scope, label=asked.label)
        return IssuedKey(issued.id, issued.client_key)

    @delete("/{id}")
    async def revoke(self, id: str) -> str:
        await self.client_keys.revoke(id)
        return "revoked"
# docs:end keys
