"""The HTTP surface. Your process never binds an HTTP port: the sidecar serves these routes, applies
the endpoint's access control list, and forwards each request here.

`acl` is a decision every endpoint makes explicitly. `ALLOW_ALL` is right for a public API and wrong
for anything else — and once this service is *exposed* (`ankka services expose`), an `ALLOW_ALL`
endpoint is reachable from the internet. Exposure changes who can reach an endpoint, not who is
allowed to; this line does.
"""

from __future__ import annotations

from ankka import Acl, Done, Endpoint, HttpProblem, get, post
from ankka.client import Calls, ComponentClient

from {{module}}.domain import AddItem, Item, RemoveItem
from {{module}}.item_rows import ItemRow, ItemRows


class ItemEndpoint(Endpoint):
    prefix = "/items"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    def _item(self, item_id: str) -> Calls:
        # Passing the request's metadata on is what makes the entity call a child of the request in
        # a trace.
        return self.client.with_metadata(self.request.metadata).for_event_sourced_entity("item", item_id)

    @get("/")
    async def list_items(self) -> list[ItemRow]:
        return await self.client.views.all(ItemRows.component_id, ItemRow)

    @get("/{id}")
    async def get_item(self, id: str) -> Item:
        return await self._item(id).call("get-item").invoke(reply=Item)

    @post("/{id}")
    async def add_item(self, id: str, request: AddItem) -> Done:
        if not request.name:
            raise HttpProblem(400, "an item needs a name")
        return await self._item(id).call("add-item").invoke(request, reply=Done)

    @post("/{id}/remove")
    async def remove_item(self, id: str, request: RemoveItem) -> Done:
        return await self._item(id).call("remove-item").invoke(request, reply=Done)
