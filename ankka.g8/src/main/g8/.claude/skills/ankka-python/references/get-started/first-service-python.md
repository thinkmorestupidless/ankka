# Your first service in Python

> Write a Python service with an event sourced entity and an HTTP endpoint, run it beside the ankka sidecar, test it with and without the sidecar, and watch it in the local console.

Source: https://docs.ankka.cloud/get-started/first-service-python/
This tutorial writes a shopping cart in Python, runs it on your machine, tests it, and looks at it in
the local console. You need uv, Python 3.12, Docker, the Python SDK and the `ankka-sidecar` image from
[Install the tools](install.md).

A Python service runs as its own process. ankka's runtime runs beside it as a **sidecar**: a container
that owns everything stateful and distributed — the journal, sharding, projections, timers, HTTP and
the agent loop. Your process owns the decisions: given this command and this state, what should happen.
The two talk over gRPC on loopback, and the SDK hides that entirely.
[Services in other languages](../concepts/polyglot.md) explains the model.

## Create the project

```bash
uv init cart && cd cart
uv add "ankka[testkit]==0.3.1"          # the version of the platform you will deploy to
uv add --dev pytest pytest-asyncio
```

## An entity

An event sourced entity keeps its state as the fold of the events it persisted. Create `cart.py`:

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
        raise AssertionError(event)

    @command("add-item")
    def add_item(self, item: LineItem) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]:
        if self.state.checkedOut:
            return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
        if item.quantity <= 0:
            return self.effects.error(f"quantity must be greater than zero, was {item.quantity}")
        return self.effects.persist(ItemAdded(item)).then_reply(lambda _: DONE)

    @command("checkout")
    def checkout(self) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, ShoppingCart]:
        if self.state.checkedOut:
            return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
        return self.effects.persist(CheckedOut()).then_reply_state()

    @query("get-cart")
    def get_cart(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, ShoppingCart]:
        return self.effects.reply(self.state)
```

Four things in this file are true of every ankka component:

- **A handler returns an effect.** `persist(...).then_reply(...)` is a description of what should
  happen. The handler performs no I/O, which is why it can be tested with nothing running.
- **The wire name is the decorator's argument.** `"add-item"` is what the platform stores and routes by.
  Renaming the method `add_item` changes nothing; renaming `"add-item"` is a protocol change.
- **A query cannot persist.** `@query` handlers must return a `ReadOnlyEffect`, and registration refuses
  anything else.
- **Field names are the stored format.** The codec writes `productId` exactly as declared. A Scala service
  reading the same journal expects the same names, so keep them as they are once data exists.

## An endpoint

An endpoint is the service's HTTP edge. Create `api.py`:

```python
# api.py
from ankka import Done, Endpoint, get, post
from ankka.client import Calls, ComponentClient

from cart import LineItem, ShoppingCart


class ShoppingCartEndpoint(Endpoint):
    prefix = "/carts"

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    def _cart(self, cart_id: str) -> Calls:
        return self.client.with_metadata(self.request.metadata).for_event_sourced_entity("shopping-cart", cart_id)

    @post("/{cartId}/items")
    async def add_item(self, cartId: str, item: LineItem) -> Done:
        return await self._cart(cartId).call("add-item").invoke(item, reply=Done)

    @post("/{cartId}/checkout")
    async def checkout(self, cartId: str) -> ShoppingCart:
        return await self._cart(cartId).call("checkout").invoke(reply=ShoppingCart)

    @get("/{cartId}")
    async def get_cart(self, cartId: str) -> ShoppingCart:
        return await self._cart(cartId).call("get-cart").invoke(reply=ShoppingCart)
```

Path parameters bind by name from the route template. One further typed parameter is the request body,
and the return value is encoded by its type. Your process never binds an HTTP port: the sidecar serves
the routes you declared, applies the endpoint's access control list, and forwards each request. This
endpoint does not set `acl`, and the default allows every caller. That is fine on your machine; decide
it deliberately before the service is exposed, as [HTTP endpoints](../build/http-endpoints.md) shows. Passing `self.request.metadata` on to the entity call
is what makes that call appear as a child of the request in a trace.

## Run it

Register both components and listen. Create `main.py`:

```python
# main.py
import asyncio

from ankka import Ankka

from api import ShoppingCartEndpoint
from cart import ShoppingCartEntity

asyncio.run(Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint).listen())
```

Registration is explicit: a component that is not registered does not exist. `listen()` serves the
protocol on port 9010 on loopback, where the sidecar finds it.

Start Postgres and the sidecar from the root of the ankka repository, then your process:

```bash
docker compose --profile polyglot up -d      # in the ankka repository: Postgres and the sidecar
uv run python main.py                        # in your project: your process, on 9010
```

In another terminal:

```bash
curl -X POST localhost:9000/carts/c1/items -H 'content-type: application/json' \
     -d '{"productId":"p1","name":"Pen","quantity":2}'
curl localhost:9000/carts/c1
# {"cartId":"c1","items":[{"productId":"p1","name":"Pen","quantity":2}],"checkedOut":false}
```

Stop both and start both again, then read the cart: it is still there. The journal belongs to the
sidecar and lives in Postgres, under the same records a Scala service writes.

## Test it without the sidecar

The unit testkit runs a component with no sidecar and no network. Create `test_cart.py`:

```python
# test_cart.py
from ankka import ErrorCode
from ankka.testkit import EventSourcedTestKit

from cart import ItemAdded, LineItem, ShoppingCartEntity

PEN = LineItem("p1", "Pen", 2)


def test_adding_an_item_persists_one_event() -> None:
    kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
    assert kit.call("add-item", PEN).events == (ItemAdded(PEN),)
    assert kit.call("get-cart").reply.items == [PEN]


def test_a_refused_command_persists_nothing() -> None:
    kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
    result = kit.call("add-item", LineItem("p1", "Pen", 0))
    assert result.error is not None and result.error.code == ErrorCode.BAD_REQUEST
    assert result.events == ()
```

```bash
uv run pytest -q
```

These take milliseconds. Inputs, events, state and replies still round-trip through your codecs, so a
type the codec cannot encode fails here rather than on first deployment.

## Test it through the real sidecar

The integration testkit starts Postgres and the sidecar image in Docker, serves your components to it,
and gives you an HTTP client for your routes:

```python
# test_integration.py
from ankka import Ankka
from ankka.testkit.integration import AnkkaTestKit

from api import ShoppingCartEndpoint
from cart import ShoppingCartEntity


async def test_a_cart_survives_a_restart() -> None:
    service = Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint)
    async with await AnkkaTestKit.start(service) as kit:
        await kit.http.post("/carts/c1/items", json={"productId": "p1", "name": "Pen", "quantity": 2})
        await kit.restart()                                  # a new sidecar, the same database
        assert (await kit.http.get("/carts/c1")).json()["items"][0]["name"] == "Pen"
```

The testkit needs the `testkit` extra (`testcontainers` and `httpx`) and `asyncio_mode = "auto"` in your
pytest configuration. `restart()` replaces the sidecar and keeps the database, which is how a test proves
the state is durable rather than cached.

## Watch it in the local console

```bash
ankka local console       # http://localhost:9889
```

The sidecar is a local ankka service like any other, so the console lists it: its components, a form
per route, and a trace of each request. The trace of `POST /{cartId}/items` shows the endpoint and the
`shopping-cart#add-item` call beneath it. The console reads a cart's state through the entity's declared
query `get-cart`, and refuses to run a command. [The local console](../operate/local-console.md) covers
the rest.

## Next

[Deploy to a local platform](deploy-locally.md). A Python service is deployed exactly as a Scala one is,
with two extra fields in its descriptor: `"hosting": "process"` and the protocol version its SDK speaks.
