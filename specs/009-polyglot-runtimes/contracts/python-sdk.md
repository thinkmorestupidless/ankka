# Contract: the Python SDK

**Feature**: [spec.md](../spec.md) | **Research**: R9, R11, R13 | **Protocol**: [protocol.md](./protocol.md)

Package `sdks/python`, distribution name to be confirmed at publish time (R9, item 9), import
name `ankka`; CPython 3.12, `uv`-managed, `mypy --strict` clean. It implements the process side of
every service in `protocol.md` and the client side of `Client`. Its shape is the Scala SDK's
shape: handlers return inert effect values, wire names are declared separately from function
names, a query cannot persist (enforced at registration, since the language cannot at compile
time, and by the sidecar).

## Declaring a service

```python
from ankka import Ankka
from .shopping_cart_entity import ShoppingCartEntity
from .cart_rows import CartRows
from .shopping_cart_endpoint import ShoppingCartEndpoint

async def main() -> None:
    await (
        Ankka.service()
        .register(ShoppingCartEntity)
        .register(CartRows)
        .register(ShoppingCartEndpoint)
        .listen()                    # ANKKA_PROCESS_PORT, default 9010, loopback only
    )
```

`listen()` starts the `grpc.aio` server, answers `Discovery.Discover` with a `Spec` built from the
registered classes, and runs until cancelled. A duplicate registration, a `@query` whose return
annotation is not a read-only effect, or a route template that does not parse raises before
`listen`. `Ankka.service().spec()` returns the `Spec` without listening, for tests.

## An event sourced entity

```python
from dataclasses import dataclass
from ankka import EventSourcedEntity, command, query, json_codec, Done

@dataclass(frozen=True)
class LineItem:
    product_id: str
    name: str
    quantity: int

@dataclass(frozen=True)
class ItemAdded:            # a case of the sum type ShoppingCartEvent
    item: LineItem

@dataclass(frozen=True)
class CheckedOut: ...

ShoppingCartEvent = ItemAdded | ItemRemoved | CheckedOut

class ShoppingCartEntity(EventSourcedEntity[ShoppingCart, ShoppingCartEvent]):
    component_id = "shopping-cart"
    state_codec = json_codec(ShoppingCart, "shopping-cart")           # manifest, as Codecs.serializer
    event_codec = json_codec(ShoppingCartEvent, "shopping-cart-event")
    snapshot_every = 100

    def empty_state(self) -> ShoppingCart:
        return ShoppingCart(items=(), checked_out=False)

    def apply_event(self, cart: ShoppingCart, event: ShoppingCartEvent) -> ShoppingCart:
        match event: ...                                              # the fold

    @command("add-item")
    def add_item(self, item: LineItem) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]:
        if item.quantity <= 0:
            return self.effects.error("quantity must be positive", ErrorCode.BAD_REQUEST)
        return self.effects.persist(ItemAdded(item)).then_reply(lambda _: Done)

    @query("get-cart")
    def get_cart(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, ShoppingCart]:
        return self.effects.reply(self.state)
```

- The decorator's argument is the wire name. Renaming the method changes nothing on the wire.
- `self.state` is the current state; `self.context` is `{entity_id, component_id, metadata,
  sequence_number, client}`.
- A handler may be `async def`; the SDK awaits it. One command runs at a time per instance.
- `@query` requires the return annotation to be `ReadOnlyEffect`; a `@command`'s effect may be
  either. Registration refuses a `@query` annotated otherwise, and the sidecar refuses events from
  it regardless (R3).
- `KeyValueEntity`, `Workflow` (with `@step("reserve")`), `View`, `Consumer`, `TimedAction` and
  `Agent` follow the same pattern with their own effect builders, each mirroring the Scala
  `effects` surface field for field (data-model.md).

## An endpoint

```python
from ankka import Endpoint, get, post, sse, Acl

class ShoppingCartEndpoint(Endpoint):
    prefix = "/carts"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: ComponentClient) -> None: ...

    @post("/{cart_id}/items")
    async def add_item(self, cart_id: str, item: LineItem) -> Done:
        return await self.client.for_event_sourced_entity("shopping-cart", cart_id) \
            .call("add-item").invoke(item, reply=Done)

    @get("/{cart_id}")
    async def get_cart(self, cart_id: str) -> ShoppingCart:
        page = self.request.query.get("page")                      # query parameters, headers
        ...

    @sse("/{cart_id}/events")
    async def events(self, cart_id: str) -> AsyncIterator[str]: ...
```

Path parameters bind by name from the template; a typed body parameter is decoded with the
type's default codec; the return value is encoded with its default codec into the response
body; raising `HttpProblem(status, message)` answers that status. The endpoint is declared in
discovery and served by the sidecar (`protocol.md`, `endpoint.proto`); the process never binds an
HTTP port.

## Effects

Frozen dataclasses, built by `self.effects` and never performing I/O:

| kind | builders |
|---|---|
| event sourced | `persist(e, *more).then_reply(f) \| .then_reply_state() \| .then_no_reply() \| .delete_entity() \| .expire_after(timedelta)`; `reply(r)`; `error(message, code)`; `no_reply()` |
| key value | `update_state(s).then_reply(...)`; `delete_entity()`; `reply`; `error` |
| workflow | `update_state(s).then_transition_to(step, input=None) \| .then_pause(...) \| .then_end() \| .then_reply(...)`; step effects likewise |
| view | `update_row(row)`, `delete_row()`, `ignore()` |
| consumer | `produce(payload, metadata=None)`, `done()`, `ignore()` |
| timed action | `done()`, `fail(message, code)` |
| agent | `system_message(s).user_message(u).context(...).tools(*names).memory("session" \| "none").guardrails(*names).then_reply() \| .then_reply_json(schema_hint)`; `error` |

An agent class declares `tools = {name: Tool(description, input_schema, run)}` and
`guardrails = {name: Guardrail(stage, check)}`; the plan names them and the sidecar calls them
back.

## Codecs (`ankka.codec`)

`json_codec(cls, manifest)` builds the default codec for a dataclass, a `Union` of dataclasses
(a sum type: encoded with `"type": "<ClassName>"`), `Optional`, `list`, `tuple`, `dict[str, …]`,
`int`, `float`, `bool`, `str`, `datetime`, `timedelta`, exactly to `protocol/ENCODING.md`.
Primitive top-level values (`int`, `str`, `Done`, `Optional[...]`, `bytes`) use the primitive
encodings and manifests from the same document. `tests/test_encoding_fixtures.py` runs every
fixture in `protocol/fixtures` through the default codec both ways and is part of `uv run test`.
A developer may supply a `Codec[A]` of their own; then portability is their contract.

## The component client

```python
done = await ctx.client.for_event_sourced_entity("shopping-cart", cart_id) \
    .call("add-item").invoke(item, reply=Done)
```

Typed by the `reply=` type, whose default codec decodes the reply. Every call copies the trace
metadata from the handler's context so the sidecar records a child span. `stream(...)` returns
an async iterator of tokens for streaming agent handlers. `views.query(...)` and
`timers.schedule(...)` / `cancel(...)` wrap the rest of `Client`.

## The unit testkit (`ankka.testkit`)

```python
kit = EventSourcedTestKit.of(ShoppingCartEntity, "cart-1")
result = kit.call("add-item", LineItem("p1", "Pen", 2))
assert result.events == (ItemAdded(LineItem("p1", "Pen", 2)),)
assert result.reply == Done
assert len(kit.state.items) == 1
```

No sidecar, no network. Inputs, events, state and replies round-trip through the class's codecs,
so a missing codec fails here (the Scala testkit's rule). `result` is the materialised effect:
`{events, new_state, retention, reply | error}`. Equivalents exist for every kind; the endpoint
testkit calls a route with path arguments, query and body and returns the status and decoded
body; the agent testkit takes a scripted model (`responses=[...]`) and fails loudly when the
script runs out.

## The integration testkit

```python
async with AnkkaTestKit.start(service=my_service) as ankka:      # Postgres + ankka-sidecar via testcontainers
    r = await ankka.http.post("/carts/c1/items", json={...})   # through the sidecar's HTTP, the declared routes
    await ankka.restart()                                        # restarts the sidecar: proves durability
    assert (await ankka.http.get("/carts/c1")).json()["items"] == [...]
```

Starts Postgres with the platform's DDL (copied from the sidecar image, so a test can never pass
against a schema the platform does not have) and `ankka-sidecar:<version>` with
`ANKKA_PROCESS_ADDRESS` pointing at the test's own listener. `restart()` restarts the sidecar
container against the same database. The sidecar image version is the SDK's declared
`ankka_version`, never a literal `latest`.

## Guarantees the SDK gives the sidecar

- One command handled at a time per stream, replied to in order, with the sidecar's `command_id`.
- State released on stream close, whether clean or errored.
- A thrown handler is a `Failure`, never a silent `no_reply`.
- `snapshot` included exactly when `snapshot_requested`.
- The `Spec` lists every registered component and endpoint with wire names, `read_only` flags and
  route templates exactly as declared.
- The default codec passes every fixture in `protocol/fixtures`.

## Scripts (`uv run …`)

`proto` copies `protocol/` in and regenerates; `test` runs pytest including the fixtures;
`conformance` starts `examples/shopping_cart` and runs the platform's conformance suite against
it (`conformance.md`); `example` starts the cart against a compose sidecar; `typecheck` runs
`mypy --strict`.
