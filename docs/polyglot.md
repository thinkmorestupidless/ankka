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

## The other kinds

Every component kind but agents is hosted the same way: the sidecar owns the durable and the
distributed half, your class owns the decision. The sample under `sdks/python/examples/shopping_cart/`
has one of each; the sections below point at them.

### A key value entity

```python
class CheckoutLog(KeyValueEntity[CheckoutRecord]):
    component_id = "checkout-log"
    state_codec = json_codec(CheckoutRecord, "checkout-record")

    def empty_state(self) -> CheckoutRecord: ...

    @command("record")
    def record(self, at: int) -> KeyValueEffect[CheckoutRecord, Done]:
        return self.effects.update_state(CheckoutRecord(self.entity_id, at, True)).then_reply(lambda _: DONE)
```

The latest value only, no history: `update_state` stores it, `delete` and `expire_after` are the
retention effects, `@query` handlers may only reply. The sidecar keeps it in the same durable
state table as a Scala key value entity, under your `state_codec`'s manifest — see
[`checkout_log.py`](../sdks/python/examples/shopping_cart/checkout_log.py).

### A workflow

```python
class CheckoutWorkflow(Workflow[Checkout]):
    component_id = "checkout"
    state_codec = json_codec(Checkout, "checkout")
    settings = WorkflowSettings(steps={"charge": StepSettings(recovery=Recovery(max_retries=1, failover_to="compensate"))})

    @command("start")
    def start(self) -> WorkflowEffect[Checkout, Done]:
        return self.effects.update_state(...).then_transition_to("reserve").then_reply(lambda _: DONE)

    @step("reserve")
    async def reserve(self) -> WorkflowStepEffect[Checkout]:
        total = await self.context.client.for_event_sourced_entity("shopping-cart", self.state.cartId).call("total-quantity").invoke(reply=int)
        return self.step_effects.update_state(...).then_transition_to("charge")
```

Commands change state and start steps; steps run here, one at a time per instance, and say what
happens next: another step, a pause, the end, or a failure. The sidecar's engine journals every
transition and runs the steps on its schedule, so an instance survives restarts mid-flight. What
the engine enforces — timeouts, retries, failover — you declare in `settings`, since your process
cannot; a step that raises is retried as declared and then failed over to a step that takes no
input, where compensation reads what the workflow accumulated. Queries are answered while a step
runs. See [`checkout_workflow.py`](../sdks/python/examples/shopping_cart/checkout_workflow.py).

### A view

```python
class CartRows(View[ShoppingCartEvent, CartRow]):
    component_id = "cart-rows"
    source = ShoppingCartEntity
    event_codec = ShoppingCartEntity.event_codec
    row_codec = json_codec(CartRow, "cart-row")

    def on_change(self, event: ShoppingCartEvent) -> ViewEffect:
        current = self.row or CartRow(self.metadata.subject or "")
        ...
        return self.effects.update_row(replace(current, quantities=quantities))

    def on_delete(self) -> ViewEffect:               # the source was deleted; default: delete_row
        return self.effects.update_row(replace(self.row, checkedOut=True)) if self.row else self.effects.ignore()
```

The sidecar runs the projection — exactly-once over an entity's events, at-least-once over a key
value entity or a `topic` — and stores the rows; your class only says what an event does to the
current row (`self.row`, `None` when there is none; `self.metadata.subject` is the source's id).
Rows are queried through the client, `client.views.get("cart-rows", cart_id, CartRow)` and
`views.all(...)`, or from an endpoint as the sample's `/carts/{cartId}/row` does. See
[`cart_rows.py`](../sdks/python/examples/shopping_cart/cart_rows.py).

### A consumer

```python
class CheckoutNotifier(Consumer[ShoppingCartEvent, None]):
    component_id = "checkout-notifier"
    source = ShoppingCartEntity
    message_codec = ShoppingCartEntity.event_codec

    async def on_message(self, event: ShoppingCartEvent) -> ConsumerEffect:
        if not isinstance(event, CheckedOut):
            return self.effects.ignore()
        await self.client.for_key_value_entity("checkout-log", self.metadata.subject).call("record").invoke(now, reply=Done)
        return self.effects.done()
```

A consumer reacts to a source's changes and either acts through the client, as here, or
`produce`s to a topic — for that it declares `produces_to` and an `out_codec`, and the sidecar
needs a broker, `ANKKA_KAFKA_BOOTSTRAP_SERVERS`, or refuses to start naming the consumer.
Delivery is at-least-once, so what a consumer does must tolerate a repeat. See
[`checkout_notifier.py`](../sdks/python/examples/shopping_cart/checkout_notifier.py).

### A timed action

```python
class Reminder(TimedAction):
    component_id = "reminder"

    @action("nudge")
    async def nudge(self, cart_id: str) -> TimedActionEffect:
        ...
        return self.effects.done()

await client.timers.schedule("nudge-c1", timedelta(hours=1), "reminder", "nudge", "c1")
```

A call the platform makes later. The timer lives in the sidecar's database, so it outlives the
process that set it; the sweeper delivers it here and a `failed` effect, an exception or an
unreachable process is retried with backoff, `self.metadata` carrying the timer's name and the
attempt count. `client.timers.cancel(id)` removes one; scheduling twice under one id replaces it.

## What is not there yet

Agents follow, a conversation on the same protocol.
