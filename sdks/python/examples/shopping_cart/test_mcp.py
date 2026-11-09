"""The cart's MCP endpoint: its tools' schemas and ACL with nothing started, a handler tool run as the
sidecar runs it, and, through the sidecar, the protocol's requests as an assistant sends them."""

from __future__ import annotations

import asyncio
import json
from typing import Any

import pytest

from ankka import Ankka
from ankka._proto.ankka.protocol.v1 import client_pb2, mcp_pb2, payload_pb2
from ankka.client import ComponentClient
from ankka.codec import default_codec_for
from ankka.server import HttpServicer, McpServicer
from ankka.testkit.integration import AnkkaTestKit

from examples.shopping_cart.cart_mcp import CartKeysEndpoint, CartMcpEndpoint
from examples.shopping_cart.domain import LineItem
from examples.shopping_cart.entity import ShoppingCartEntity
from examples.shopping_cart.main import service


def _registry() -> Any:
    return Ankka.service().register(ShoppingCartEntity).register(CartKeysEndpoint).register(CartMcpEndpoint).validate()


def test_the_reference_takes_the_id_and_the_handler_its_own_arguments() -> None:
    tools = _registry().tools_of("CartMcpEndpoint")
    assert tools["get_cart"].input_schema["properties"] == {"cartId": {"type": "string"}}
    assert tools["add_item"].input_schema["properties"] == {
        "cartId": {"type": "string"},
        "productId": {"type": "string"},
        "quantity": {"type": "integer"},
    }
    (mcp,) = _registry().spec().mcp_endpoints
    assert mcp.acl.client_keys and not mcp.acl.tokens and not mcp.acl.allow_all


class _Sidecar:
    """Answers the cart's `add-item` with done, recording what was asked."""

    def __init__(self) -> None:
        self.added: list[LineItem] = []

    async def Invoke(self, request: client_pb2.InvokeRequest) -> client_pb2.InvokeReply:  # noqa: N802
        self.added.append(default_codec_for(LineItem).decode(request.payload.data))
        return client_pb2.InvokeReply(reply=payload_pb2.Outcome.Reply())


def test_the_handler_tool_adds_the_item_through_the_client() -> None:
    sidecar = _Sidecar()
    client = ComponentClient(address="127.0.0.1:0")
    client._stub_cache = sidecar  # type: ignore[assignment]
    registry = _registry()
    servicer = McpServicer(registry, client, HttpServicer(registry, client))
    request = mcp_pb2.McpToolRequest(
        endpoint_id="CartMcpEndpoint", tool="add_item", arguments_json=json.dumps({"cartId": "c1", "productId": "p1", "quantity": 2})
    )
    reply = asyncio.run(servicer.InvokeTool(request, None))
    assert reply.HasField("ok"), reply
    assert sidecar.added == [LineItem("p1", "p1", 2)]


# docs:start mcp-request
async def mcp(kit: AnkkaTestKit, key: str, method: str, params: dict[str, Any]) -> dict[str, Any]:
    """One JSON-RPC request to the cart's MCP endpoint, as any MCP client sends it."""
    response = await kit.http.post(
        "/mcp",
        json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params},
        headers={"Authorization": f"Bearer {key}", "Accept": "application/json, text/event-stream"},
    )
    assert response.status_code == 200, response.text
    body: dict[str, Any] = response.json()
    result: dict[str, Any] = body["result"]
    return result
# docs:end mcp-request


@pytest.mark.slow
async def test_an_assistant_with_a_key_lists_and_calls_the_carts_tools() -> None:
    async with await AnkkaTestKit.start(service()) as kit:
        issued = await kit.http.post("/mcp-keys/", json={"subject": "customer-7", "label": "ada's assistant"})
        assert issued.status_code == 200, issued.text
        key = issued.json()["clientKey"]
        listed = await mcp(kit, key, "tools/list", {})
        assert sorted(t["name"] for t in listed["tools"]) == ["add_item", "get_cart"]
        added = await mcp(kit, key, "tools/call", {"name": "add_item", "arguments": {"cartId": "m1", "productId": "p1", "quantity": 2}})
        assert not added.get("isError"), added
        cart = await mcp(kit, key, "tools/call", {"name": "get_cart", "arguments": {"cartId": "m1"}})
        assert json.loads(cart["content"][0]["text"])["items"] == [{"productId": "p1", "name": "p1", "quantity": 2}]
        # Without a key, nothing.
        refused = await kit.http.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}})
        assert refused.status_code == 401
