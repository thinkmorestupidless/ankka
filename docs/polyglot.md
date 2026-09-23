# Services in another language

ankka hosts a service written in Python the way it hosts one written in Scala: the same
entities, endpoints, journal, cluster, deployment and console. What differs is where the code
runs. A Scala service compiles into one JVM with the runtime. A Python service runs as its own
process, and ankka's runtime runs beside it as a **sidecar** — the same `ankka-runtime`, booted
from a handshake with your process instead of from a Scala builder.

The sidecar owns everything stateful and everything distributed: sharding, the journal,
snapshots, projections, timers, HTTP, cluster formation, observability. Your process owns one
thing: given this command and this state, what should happen. The two speak a protobuf protocol
over gRPC on loopback, and your code never sees it — the SDK does.

This page is the walkthrough. `protocol/` holds the protocol itself, `ENCODING.md` what the bytes
mean, and `sdks/python/` the SDK.

## Prerequisites

Python 3.12, [uv](https://docs.astral.sh/uv/), and Docker. No JVM: the sidecar is an image.

## An entity

```python
# cart.py
from dataclasses import dataclass, replace
from ankka import DONE, Done, ErrorCode, EventSourcedEffect, EventSourcedEntity, ReadOnlyEffect, command, json_codec, query


@dataclass(frozen=True)
class LineItem:
    productId: str
    name: str
    quantity: int


@dataclass(frozen=True)
class ShoppingCart:
    cartId: str
    items: list[LineItem]
    checkedOut: bool


@dataclass(frozen=True)
class ItemAdded:
    item: LineItem


@dataclass(frozen=True)
class CheckedOut:
    pass


ShoppingCartEvent = ItemAdded | CheckedOut


class ShoppingCartEntity(EventSourcedEntity[ShoppingCart, ShoppingCartEvent]):
    component_id = "shopping-cart"
    state_codec = json_codec(ShoppingCart, "shopping-cart")
    event_codec = json_codec(ShoppingCartEvent, "shopping-cart-event")

    def empty_state(self) -> ShoppingCart:
        return ShoppingCart(self.entity_id, [], False)

    def apply_event(self, state: ShoppingCart, event: ShoppingCartEvent) -> ShoppingCart:
        match event:
            case ItemAdded(item):
                return replace(state, items=[*state.items, item])
            case CheckedOut():
                return replace(state, checkedOut=True)

    @command("add-item")
    def add_item(self, item: LineItem) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]:
        if self.state.checkedOut:
            return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
        return self.effects.persist(ItemAdded(item)).then_reply(lambda _: DONE)

    @query("get-cart")
    def get_cart(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, ShoppingCart]:
        return self.effects.reply(self.state)
```

Three things carry over from the Scala SDK unchanged:

- **Effects are values.** A handler returns a description — persist these events, then reply
  with this — and performs no I/O. That is why the unit testkit needs nothing running.
- **The wire name is the decorator's argument**, not the method's name. Renaming `add_item`
  changes nothing on the wire; renaming `"add-item"` is a protocol change.
- **A query cannot persist.** `@query` must return a `ReadOnlyEffect`; registration refuses
  anything else, and the sidecar refuses events from a read-only handler regardless.

## An endpoint

```python
# api.py
from ankka import Done, Endpoint, get, post
from ankka.client import ComponentClient


class ShoppingCartEndpoint(Endpoint):
    prefix = "/carts"

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    def _cart(self, cart_id: str):
        return self.client.with_metadata(self.request.metadata).for_event_sourced_entity("shopping-cart", cart_id)

    @post("/{cartId}/items")
    async def add_item(self, cartId: str, item: LineItem) -> Done:
        return await self._cart(cartId).call("add-item").invoke(item, reply=Done)

    @get("/{cartId}")
    async def get_cart(self, cartId: str) -> ShoppingCart:
        return await self._cart(cartId).call("get-cart").invoke(reply=ShoppingCart)
```

Path parameters bind by name from the template; one further typed parameter is the body; the
return value is encoded by its type. The process never binds an HTTP port: the sidecar serves the
routes you declared, applies the ACL, and forwards each request. `self.request.metadata` on a
nested call is what makes it a child span in the console.

## Run it

```python
# main.py
import asyncio
from ankka import Ankka
from api import ShoppingCartEndpoint
from cart import ShoppingCartEntity

asyncio.run(Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint).listen())
```

```bash
docker compose --profile polyglot up -d      # Postgres and the sidecar, from the repository root
uv run python main.py                        # your process, on 9010; the sidecar finds it
curl -X POST localhost:9000/carts/c1/items -H 'content-type: application/json' \
     -d '{"productId":"p1","name":"Pen","quantity":2}'
curl localhost:9000/carts/c1
ankka console                                # the sidecar is a local service like any other
```

Stop both, start both, `curl` the cart again: it is still there. The journal is the sidecar's,
in Postgres, under the same records a Scala service writes.

## Test it

```python
from ankka.testkit import EventSourcedTestKit, EndpointTestKit

kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
assert kit.call("add-item", LineItem("p1", "Pen", 2)).events == (ItemAdded(LineItem("p1", "Pen", 2)),)
assert kit.call("get-cart").reply.items[0].name == "Pen"
```

No sidecar, no network, milliseconds. Inputs, events, state and replies still round-trip through
your codecs, so a type the default codec cannot encode fails here, not on first deployment.

```python
from ankka.testkit.integration import AnkkaTestKit

async with await AnkkaTestKit.start(Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint)) as kit:
    await kit.http.post("/carts/c1/items", json={"productId": "p1", "name": "Pen", "quantity": 2})
    await kit.restart()                                     # a new sidecar, the same database
    assert (await kit.http.get("/carts/c1")).json()["items"][0]["name"] == "Pen"
```

This one starts Postgres and the real sidecar image (Docker) and drives your routes through it;
`restart` is how a test proves durability rather than caching.

## Deploy it

Build your process into an image containing only your code — the sidecar is the platform's — and
apply a descriptor that says so:

```json
{ "name": "cart", "service": { "image": "my-cart:1.0.0", "hosting": "process", "protocol": "1.0" } }
```

```bash
ankka services apply -f service.json -p checkout
ankka services get cart -p checkout        # hosting: process, protocol: 1.0, Ready
```

Scale, restart, expose, pause and observe it exactly as a Scala service. Nothing in the descriptor
names the sidecar's image or version: those are the platform's.

## One journal, two languages

The Python SDK's default codec writes the JSON the Scala SDK's writes — records with every field,
sum types with `"type": "<CaseName>"`, `null` for an absent option — under a mapping written down
in `protocol/ENCODING.md` and proven by fixtures every SDK passes. So a cart written by the Scala
sample is read by the Python one on the same database, and the reverse. Field names are the
contract: `productId`, not `product_id`.

## What is not there yet

Key value entities, views, consumers, timed actions, workflows and agents follow, each a
conversation on the same protocol.
