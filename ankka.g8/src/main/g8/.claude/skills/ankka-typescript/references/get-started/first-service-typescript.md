# Your first service in TypeScript

> Write a TypeScript service on Node.js with an event sourced entity and an HTTP endpoint, run it from source beside the ankka sidecar, test it with and without the sidecar, and watch it in the local console.

Source: https://docs.ankka.cloud/get-started/first-service-typescript/
This tutorial writes a shopping cart in TypeScript, runs it on your machine with no build step, tests it,
and looks at it in the local console. You need Node.js 22.22 or later (24 is what these pages assume),
Docker, and the `ankka-sidecar` image from [Install the tools](install.md). No JVM.

A TypeScript service runs as its own Node.js process. ankka's runtime runs beside it as a **sidecar**: a
container that owns everything stateful and distributed — the journal, sharding, projections, timers, HTTP
and the agent loop. Your process owns the decisions: given this command and this state, what should
happen. The two talk over gRPC on loopback, and the SDK hides that entirely.
[Services in other languages](../concepts/polyglot.md) explains the model.

## Create the project

```bash
mkdir cart && cd cart
npm init -y
npm pkg set type=module
npm install ankka@0.5.0                                        # the version of the platform you will deploy to
npm install -D typescript @types/node testcontainers @testcontainers/postgresql
```

TypeScript is here for the editor and the type checker; Node runs `.ts` files directly, so nothing is
compiled before it runs. A `tsconfig.json` that matches how Node reads the files:

```json
{
  "compilerOptions": {
    "module": "nodenext",
    "target": "es2024",
    "types": ["node"],
    "strict": true,
    "noEmit": true,
    "erasableSyntaxOnly": true,
    "verbatimModuleSyntax": true,
    "allowImportingTsExtensions": true
  }
}
```

Two of these matter beyond taste. `erasableSyntaxOnly` refuses the TypeScript syntax Node cannot run
(`enum`, parameter properties, decorators). `allowImportingTsExtensions` lets you write `./cart.ts` in an
import, which Node requires.

## An entity

An event sourced entity keeps its state as the fold of the events it persisted. Create `cart.ts`:

```ts
import { Done, done, ErrorCode, EventSourcedEntity, command, jsonCodec, query, s, type Infer } from "ankka"

export const LineItem = s.record("LineItem", { productId: s.string, name: s.string, quantity: s.int })
export type LineItem = Infer<typeof LineItem>

export const ShoppingCart = s.record("ShoppingCart", { cartId: s.string, items: s.list(LineItem), checkedOut: s.boolean })
export type ShoppingCart = Infer<typeof ShoppingCart>

export const ShoppingCartEvent = s.sumType("ShoppingCartEvent", { ItemAdded: { item: LineItem }, CheckedOut: {} })
export type ShoppingCartEvent = Infer<typeof ShoppingCartEvent>

export class ShoppingCartEntity extends EventSourcedEntity<ShoppingCart, ShoppingCartEvent> {
  static readonly componentId = "shopping-cart"
  static readonly state = jsonCodec(ShoppingCart, "shopping-cart")
  static readonly events = jsonCodec(ShoppingCartEvent, "shopping-cart-event")

  static readonly handlers = {
    addItem: command("add-item", LineItem, Done, (cart: ShoppingCartEntity, item) => cart.addItem(item)),
    checkout: command("checkout", ShoppingCart, (cart: ShoppingCartEntity) => cart.checkout()),
    getCart: query("get-cart", ShoppingCart, (cart: ShoppingCartEntity) => cart.effects.reply(cart.state)),
  }

  emptyState(): ShoppingCart {
    return { cartId: this.entityId, items: [], checkedOut: false }
  }

  applyEvent(cart: ShoppingCart, event: ShoppingCartEvent): ShoppingCart {
    switch (event.type) {
      case "ItemAdded":
        return { ...cart, items: [...cart.items, event.item] }
      case "CheckedOut":
        return { ...cart, checkedOut: true }
    }
  }

  addItem(item: LineItem) {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    if (item.quantity <= 0) return this.effects.error(`quantity must be greater than zero, was \${item.quantity}`)
    return this.effects.persist({ type: "ItemAdded", item }).thenReply(() => done)
  }

  checkout() {
    if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
    return this.effects.persist({ type: "CheckedOut" }).thenReplyState()
  }
}
```

Five things in this file are true of every ankka component in TypeScript:

- **A shape is declared once.** `s.record(...)` describes the JSON; `Infer<typeof LineItem>` is the
  TypeScript type. The sum type `ShoppingCartEvent` is a discriminated union on `type`, which is also what
  the journal holds, so the value in a handler is the JSON in the journal.
- **A handler returns an effect.** `persist(...).thenReply(...)` is a description of what should happen.
  The handler performs no I/O, which is why it can be tested with nothing running.
- **The wire name is the first argument of `command` and `query`.** `"add-item"` is what the platform
  stores and routes by. Renaming the method `addItem`, or the property `addItem` in the table, changes
  nothing; renaming `"add-item"` is a protocol change.
- **A query cannot persist.** The function passed to `query` must return a read-only effect. Pass one
  that persists and the file does not compile.
- **Field names are the stored format.** The codec writes `productId` exactly as declared. A Scala or
  Python service reading the same journal expects the same names, so keep them once data exists.

## An endpoint

An endpoint is the service's HTTP edge. Create `api.ts`:

```ts
import { Acl, Done, Endpoint, get, post } from "ankka"
import { LineItem, ShoppingCart, ShoppingCartEntity } from "./cart.ts"

export class ShoppingCartEndpoint extends Endpoint {
  static readonly prefix = "/carts"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    addItem: post("/{cartId}/items", LineItem, Done, (ep: ShoppingCartEndpoint, req, item) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.addItem).invoke(item)),
    checkout: post("/{cartId}/checkout", ShoppingCart, (ep: ShoppingCartEndpoint, req) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.checkout).invoke()),
    getCart: get("/{cartId}", ShoppingCart, (ep: ShoppingCartEndpoint, req) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.getCart).invoke()),
  }

  cart(cartId: string) {
    return this.client.of(ShoppingCartEntity, cartId)
  }
}
```

A route names its template, the shapes of its body and reply, and the function that runs it.
`req.params.cartId` is typed from the template. The return value is encoded with the reply shape; `done`
answers 204. Your process never binds an HTTP port: the sidecar serves the routes you declared, applies the
endpoint's access rule, and forwards each request. `acl` is required: an endpoint without one does not
compile, because an access rule nobody chose is not a default worth having. `Acl.allowAll` is fine on your
machine; decide it deliberately before the service is exposed, as [HTTP endpoints](../build/http-endpoints.md)
shows. `this.client` inside a route is already scoped to the request, which is what makes the entity call
appear as a child of the request in a trace.

## Run it

Register both components and listen. Create `main.ts`:

```ts
import { Ankka } from "ankka"
import { ShoppingCartEntity } from "./cart.ts"
import { ShoppingCartEndpoint } from "./api.ts"

export const service = () => Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint)

if (import.meta.main) await service().listen()
```

Registration is explicit: a component that is not registered does not exist. `listen()` serves the protocol
on port 9010 on loopback, where the sidecar finds it.

Start Postgres and the sidecar from the root of the ankka repository, then your process:

```bash
docker compose --profile polyglot up -d      # in the ankka repository: Postgres and the sidecar
node main.ts                                 # in your project: your process, on 9010
```

In another terminal:

```bash
curl -X POST localhost:9000/carts/c1/items -H 'content-type: application/json' \
     -d '{"productId":"p1","name":"Pen","quantity":2}'
curl localhost:9000/carts/c1
# {"cartId":"c1","items":[{"productId":"p1","name":"Pen","quantity":2}],"checkedOut":false}
```

Stop both and start both again, then read the cart: it is still there. The journal belongs to the sidecar
and lives in Postgres, under the same records a Scala service writes.

## Test it without the sidecar

The unit testkit runs a component with no sidecar and no network. Create `cart.test.ts`:

```ts
const pen = { productId: "p1", name: "Pen", quantity: 2 }

test("adding an item persists one event", async () => {
  const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
  const result = await kit.call(ShoppingCartEntity.handlers.addItem, pen)
  assert.deepEqual(result.events, [{ type: "ItemAdded", item: pen }])
  assert.deepEqual((await kit.call(ShoppingCartEntity.handlers.getCart)).reply?.items, [pen])
})

test("a refused command persists nothing", async () => {
  const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
  const result = await kit.call(ShoppingCartEntity.handlers.addItem, { ...pen, quantity: 0 })
  assert.equal(result.error?.code, "BAD_REQUEST")
  assert.deepEqual(result.events, [])
})
```

```bash
node --test
```

These take milliseconds. Inputs, events, state and replies still round-trip through your codecs, so a shape
the codec cannot express fails here rather than on first deployment. `node --test` is Node's own runner and
needs no configuration; the testkits do not depend on it, so vitest works the same way.

## Test it through the real sidecar

The integration testkit starts Postgres and the sidecar image in Docker, serves your components to it, and
gives you an HTTP client for your routes:

```ts
test("a cart survives a restart", async () => {
  const kit = await AnkkaTestKit.start(service())
  try {
    await kit.http.post("/carts/c1/items", { productId: "p1", name: "Pen", quantity: 2 })
    await kit.restart()                                  // a new sidecar, the same database
    const cart = (await kit.http.get("/carts/c1")).json() as { items: { name: string }[] }
    assert.equal(cart.items[0]?.name, "Pen")
  } finally {
    await kit.stop()
  }
})
```

The testkit needs `testcontainers` and `@testcontainers/postgresql`, installed above as development
dependencies; the SDK itself does not depend on them. `restart()` replaces the sidecar and keeps the
database, which is how a test proves the state is durable rather than cached.

## Watch it in the local console

```bash
ankka local console       # http://localhost:9889
```

The sidecar is a local ankka service like any other, so the console lists it: its components, a form per
route, and a trace of each request. The trace of `POST /{cartId}/items` shows the endpoint and the
`shopping-cart#add-item` call beneath it. The console reads a cart's state through the entity's declared
query `get-cart`, and refuses to run a command. [The local console](../operate/local-console.md) covers the
rest.

## Next

[Deploy to a local platform](deploy-locally.md). A TypeScript service is deployed exactly as a Scala one
is, with two extra fields in its descriptor: `"hosting": "process"` and the protocol version its SDK speaks.
The image holds only your process; `node:24-slim` with `node main.ts` as its command is enough.
