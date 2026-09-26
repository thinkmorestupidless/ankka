# Contract: the TypeScript SDK

**Feature**: [spec.md](../spec.md) | **Research**: R1–R7 | **Protocol**: unchanged, `specs/009-polyglot-runtimes/contracts/protocol.md`

Package `ankka` on npm, source in `sdks/typescript`, ESM only, Node `>=22.22.0` (R1; the docs show 24). It
implements the process side of every service in the protocol and the client side of `Client`. Its
shape is the Scala SDK's shape: handlers return inert effect values, wire names are declared
separately from method names, a query cannot persist — refused by the type checker, again at
registration, and by the sidecar regardless.

The rule every part of this contract follows, from the audience: a service reads as ordinary modern
Node, and everything the runtime needs is written down once where a reader can see it. No decorators,
no reflection metadata, no code generation from the developer's code, no dependency container, and
no syntax Node cannot execute from source (R2).

## Declaring a service

```ts
// main.ts — run with `node main.ts`
import { Ankka } from "ankka"
import { ShoppingCartEntity } from "./entity.ts"
import { CartRows } from "./cartRows.ts"
import { ShoppingCartEndpoint } from "./endpoint.ts"

await Ankka.service()
  .register(ShoppingCartEntity)
  .register(CartRows)
  .register(ShoppingCartEndpoint)
  .listen()                                   // ANKKA_PROCESS_PORT, default 9010, loopback only
```

`register` accepts a component class and refuses, at the call site, a class missing a static the
kind requires (`componentId`, codecs, `handlers`, an endpoint's `acl`). `listen()` validates the
whole registry and throws once naming every problem, binds, answers `Discovery.Discover` with the
`Spec`, and resolves when the server closes. `spec()` returns the `Spec` without listening; `server()`
returns an unstarted server for the integration testkit.

## Shapes and codecs

```ts
import { s, type Infer, jsonCodec, Done } from "ankka"

export const LineItem = s.record("LineItem", { productId: s.string, name: s.string, quantity: s.int })
export type LineItem = Infer<typeof LineItem>

export const ShoppingCart = s.record("ShoppingCart", {
  cartId: s.string, items: s.list(LineItem), checkedOut: s.boolean, checkedOutAt: s.option(s.instant),
})
export type ShoppingCart = Infer<typeof ShoppingCart>

export const ShoppingCartEvent = s.sumType("ShoppingCartEvent", {
  ItemAdded:   { item: LineItem },
  ItemRemoved: { productId: s.string },
  CheckedOut:  {},
})
export type ShoppingCartEvent = Infer<typeof ShoppingCartEvent>
// { type: "ItemAdded"; item: LineItem } | { type: "ItemRemoved"; productId: string } | { type: "CheckedOut" }
```

A shape is declared once; the type comes from it. Field names are the stored JSON (`productId`, as
Scala and Python write it). A sum type's cases are discriminated by `type`, which is also what the
journal holds, so the value in a handler is the JSON in the journal. `s.int` is a `number` that must
be a whole number; `s.long` is a `bigint`; `s.double` is a `number` rendered as the Scala codecs
render it; `s.instant` is the SDK's `Instant` (nanosecond precision, `toDate()`, `Instant.now()`,
`Instant.parse()`); `s.option(x)` is `T | null`.

`jsonCodec(ShoppingCart, "shopping-cart")` is the default codec under that manifest. A handler's
input and reply codecs are derived from the schemas named in the handler table; a top-level
`s.string`, `s.int`, `s.boolean` crosses as `text/plain`, `Done` as the empty `done` payload. A
developer may pass any object satisfying `Codec<T>` where a codec is expected; then portability is
their contract. The default codec passes every fixture in `proto/fixtures` (`test/encoding-fixtures.test.ts`).

## An event sourced entity

```ts
import { EventSourcedEntity, command, query, jsonCodec, ErrorCode, done, Done } from "ankka"
import { ShoppingCart, ShoppingCartEvent, LineItem } from "./domain.ts"

export class ShoppingCartEntity extends EventSourcedEntity<ShoppingCart, ShoppingCartEvent> {
  static readonly componentId = "shopping-cart"
  static readonly state = jsonCodec(ShoppingCart, "shopping-cart")
  static readonly events = jsonCodec(ShoppingCartEvent, "shopping-cart-event")
  static readonly snapshotEvery = 100

  static readonly handlers = {
    addItem: command("add-item", LineItem, Done, (cart: ShoppingCartEntity, item) => cart.addItem(item)),
    getCart: query("get-cart", ShoppingCart, (cart: ShoppingCartEntity) => cart.getCart()),
  }

  emptyState(): ShoppingCart {
    return { cartId: this.entityId, items: [], checkedOut: false, checkedOutAt: null }
  }

  applyEvent(cart: ShoppingCart, event: ShoppingCartEvent): ShoppingCart {
    switch (event.type) {
      case "ItemAdded":   return { ...cart, items: [...cart.items, event.item] }
      case "ItemRemoved": return { ...cart, items: cart.items.filter(i => i.productId !== event.productId) }
      case "CheckedOut":  return { ...cart, checkedOut: true, checkedOutAt: this.context.now() }
    }
  }

  addItem(item: LineItem) {
    if (item.quantity <= 0) return this.effects.error("quantity must be positive", ErrorCode.BadRequest)
    if (this.state.checkedOut) return this.effects.error("cart is checked out", ErrorCode.Conflict)
    return this.effects.persist({ type: "ItemAdded", item }).thenReply(() => done)
  }

  getCart() {
    return this.effects.reply(this.state)
  }
}
```

- The first argument to `command`, `query`, `step`, `action` and `stream` is the wire name.
  Renaming the method changes nothing on the wire.
- `command(name, input?, reply, run)`: `input` and `reply` are schemas (or codecs); the handler is
  typed from them. A handler with no input omits the schema: `command("checkout", Done, run)`.
- `query(...)` accepts only a `run` returning `ReadOnlyEffect`. `this.effects.persist(...)`
  produces a different type, so a query that persists does not compile; registration checks the
  effect's `kind` again at runtime.
- `this.state`, `this.entityId`, `this.context` (`componentId`, `sequenceNumber`, `metadata`,
  `now()`) and `this.client` are bound for the duration of one handler call. A handler may be
  `async`; one command runs at a time per instance.
- `KeyValueEntity<S>`, `Workflow<S>`, `View<Src, Row>`, `Consumer<Msg, Out>`, `TimedAction` and
  `Agent` follow the same pattern with their own effect builders, mirroring the Scala `effects`
  surface field for field (data-model.md).

## A workflow

```ts
export class CheckoutWorkflow extends Workflow<Checkout> {
  static readonly componentId = "checkout"
  static readonly state = jsonCodec(Checkout, "checkout")
  static readonly settings = workflowSettings({ defaultStepTimeout: Duration.ofSeconds(10), steps: { charge: { recovery: { maxRetries: 2, failoverTo: "compensate" } } } })
  static readonly handlers = {
    start:  command("start", s.string, Done, (w: CheckoutWorkflow, mode) => w.start(mode)),
    status: query("status", s.string, (w: CheckoutWorkflow) => w.effects.reply(w.state.status)),
  }
  static readonly steps = {
    reserve:    step("reserve", (w: CheckoutWorkflow) => w.reserve()),
    charge:     step("charge", s.int, (w: CheckoutWorkflow, quantity) => w.charge(quantity)),
    compensate: step("compensate", (w: CheckoutWorkflow) => w.compensate()),
  }

  async reserve() {
    const quantity = await this.client.of(ShoppingCartEntity, this.state.cartId).call(ShoppingCartEntity.handlers.totalQuantity).invoke()
    return this.stepEffects.updateState({ ...this.state, status: "reserved" }).thenTransitionTo("charge", quantity)
  }
  …
}
```

Steps are declared in `steps` with their own wire names and input schemas; `settings` may only
name declared steps. Steps run on a fresh instance so a command answered mid-step never shares
context with it (data-model.md, R6).

## An endpoint

```ts
import { Endpoint, Acl, get, post, sse, HttpProblem, Done } from "ankka"

export class ShoppingCartEndpoint extends Endpoint {
  static readonly prefix = "/carts"
  static readonly acl = Acl.allowAll                                  // required; there is no default

  static readonly routes = {
    addItem: post("/{cartId}/items", LineItem, Done, (ep: ShoppingCartEndpoint, req, item) => ep.addItem(req.params.cartId, item)),
    getCart: get("/{cartId}", ShoppingCart, (ep: ShoppingCartEndpoint, req) => ep.getCart(req.params.cartId)),
    events:  sse("/{cartId}/events", (ep: ShoppingCartEndpoint, req) => ep.events(req.params.cartId)),
    admin:   get("/admin/{cartId}", ShoppingCart, (ep: ShoppingCartEndpoint, req) => ep.getCart(req.params.cartId), { acl: Acl.authenticated }),
  }

  addItem(cartId: string, item: LineItem) {
    return this.client.of(ShoppingCartEntity, cartId).call(ShoppingCartEntity.handlers.addItem).invoke(item)
  }

  async getCart(cartId: string) {
    const page = this.request.query.get("page")                        // query parameters, headers, principal
    return this.client.of(ShoppingCartEntity, cartId).call(ShoppingCartEntity.handlers.getCart).invoke()
  }

  async *events(cartId: string): AsyncIterable<string> { … }
}
```

- `req.params` is typed from the template: `"/{cartId}/items"` gives `{ cartId: string }`. A
  parameter's type may be narrowed with a third argument (`{ cartId: s.string, page: s.int }`).
- A route with a body names its schema; a `GET` with a body is refused at declaration.
- The return value is encoded with the reply schema, status 200; `done` or `undefined` answers 204;
  `throw new HttpProblem(418, "…")` answers that status as `text/plain`; a `CommandError` escaping a
  handler answers its code's HTTP status. An `sse` route returns an `AsyncIterable<string>`; each
  string is one frame, JSON-encoded by the sidecar.
- `acl` on a route replaces the endpoint's for that route alone. `Acl.allowAll`, `Acl.denyAll`,
  `Acl.authenticated`; when authenticated, `req.principal` is set.
- The route id is the property name; the endpoint id is the class name. Endpoint instances are
  shared across requests: per-request state lives on `this.request`, which is request-scoped.

## An agent

```ts
export class Assistant extends Agent {
  static readonly componentId = "assistant"
  static readonly role = "helps with carts"
  static readonly tools = {
    lookup: tool("lookup", "Count the records for an id", s.record("Lookup", { id: s.string }), (a: Assistant, input) => a.lookup(input.id)),
  }
  static readonly guardrails = {
    noSecrets: guardrail("no-secrets", (stage, text) => text.includes("sk-") ? "secrets are not allowed" : null),
  }
  static readonly handlers = {
    ask:    command("ask", s.string, s.string, (a: Assistant, question) => a.ask(question)),
    stream: stream("stream", s.string, (a: Assistant, question) => a.ask(question)),
  }

  ask(question: string) {
    return this.effects.systemMessage("You help with shopping carts.").userMessage(question)
      .tools("lookup").guardrails("no-secrets").thenReply()
  }
  async lookup(id: string) { return this.client.forEventSourcedEntity("conformance", id).call("count", undefined, s.int).invoke() }
}
```

A tool's input schema yields the JSON Schema the model sees; a tool with no description is refused.
The plan names the model, tools and guardrails; the sidecar resolves and runs them. Tools run after
the handler has returned, so `this.sessionId` is captured on the effect at plan time.

## The component client

```ts
// typed, within the same codebase: the handler ref carries the schemas
await this.client.of(ShoppingCartEntity, cartId).call(ShoppingCartEntity.handlers.addItem).invoke(item)
// by name, for a component whose class is not at hand
await this.client.forEventSourcedEntity("shopping-cart", cartId).call("add-item", LineItem, Done).invoke(item)
for await (const token of this.client.forAgent("assistant", session).call("stream", s.string).stream(question)) …
const rows = await this.client.views.query("cart-rows", "by-id", cartId, CartRow)
await this.client.timers.schedule(`remind-${id}`, Duration.ofSeconds(1), Reminder.actions.remind, id)
```

A refusal rejects with `CommandError` carrying its `code`. Every call from inside a handler carries
that request's trace metadata, so the sidecar records a child span.

## The unit testkit (`ankka/testkit`)

```ts
import { EventSourcedTestKit } from "ankka/testkit"
import { test } from "node:test"
import assert from "node:assert/strict"

test("adds an item", async () => {
  const kit = EventSourcedTestKit.of(ShoppingCartEntity, "c1")
  const result = await kit.call(ShoppingCartEntity.handlers.addItem, { productId: "p1", name: "Pen", quantity: 2 })
  assert.deepEqual(result.events, [{ type: "ItemAdded", item: { productId: "p1", name: "Pen", quantity: 2 } }])
  assert.equal(result.reply, done)
  assert.equal(kit.state.items.length, 1)
})
```

No sidecar, no network, any runner. Inputs, events, state and replies round-trip through the class's
codecs, so a shape the codec cannot express fails here. Equivalents exist for every kind
(data-model.md): the endpoint testkit calls a route by path with body and query and returns the
status and decoded body, matching literals before parameters as the sidecar's router does; the
agent testkit takes a `ScriptedModel` and fails loudly when the script runs out; the workflow testkit
runs steps and follows transitions to a pause or the end.

## The integration testkit

```ts
import { AnkkaTestKit } from "ankka/testkit"

const kit = await AnkkaTestKit.start(service)                        // Postgres + ankka-sidecar in Docker
try {
  await kit.http.post("/carts/c1/items", { productId: "p1", name: "Pen", quantity: 2 })
  await kit.restart()                                                 // a new sidecar, the same database
  assert.deepEqual((await kit.http.get("/carts/c1")).json().items.length, 1)
} finally { await kit.stop() }                                        // or `await using kit = …` where supported
```

Copies the DDL out of the sidecar image (`$ANKKA_SIDECAR_IMAGE`, default `ankka-sidecar:latest`)
into Postgres's init directory (mode 0755 — the Linux trap), starts this process's server on
`0.0.0.0:0`, starts the sidecar with `ANKKA_PROCESS_ADDRESS=host.docker.internal:<port>` (with the
`host-gateway` extra host Linux needs), `ANKKA_SIDECAR_BIND=0.0.0.0`, `ANKKA_HTTP_PORT=9000` and the
database variables, repoints the client at the mapped callback port and waits for `/_ankka/health`.
`env` passes through to the sidecar (`ANKKA_MODEL_SCRIPT` for a scripted model). `startBeside(image)`
runs another service on the same Postgres, for the journal portability test. A start that fails
half-way stops what it started and raises with the sidecar's logs attached.

## Guarantees the SDK gives the sidecar

- One command handled at a time per stream, answered in order with the sidecar's `command_id`; a
  workflow additionally runs at most one step, and answers commands during it from the pre-step state.
- State released on stream close, clean or errored.
- A thrown or rejected handler is a `Failure`, never a silent `no_reply`, and never a process exit.
- `snapshot` included exactly when `snapshot_requested`, describing the state after the reply's events.
- The `Spec` lists every registered component and endpoint with wire names, `read_only` and
  `streaming` flags, and route templates exactly as declared; a route's `acl` is absent unless set.
- Only loopback (or `0.0.0.0` for the testkit) is bound; `ANKKA_PROCESS_PORT` and
  `ANKKA_SIDECAR_ADDRESS` are the only addresses read.
- The default codec passes every fixture in `proto/fixtures`.

## Scripts (`npm run …`)

| script | does |
|---|---|
| `proto` | copies `protocol/` into `proto/` (committed; CI diffs the two), generates `src/_proto/` (ignored) with `buf generate` and `protoc-gen-es` (`target=ts`, `import_extension=ts`), and writes `src/version.ts` from `package.json` — R2 |
| `build` | typechecks and emits `dist/` (JS and declarations); what the package ships |
| `test` | the SDK's own tests under `node --test`, fixtures included; `test:slow` adds the Docker-backed ones |
| `typecheck` | `tsc --noEmit` over `src`, `test` and `examples` |
| `conformance` | serves `examples/shopping-cart`'s reference service on `127.0.0.1:$ANKKA_PROCESS_PORT` and runs the platform's conformance suite against it, exiting with sbt's status; `ANKKA_CONFORMANCE_ONLY` narrows |
| `example` | starts the shopping cart against the compose sidecar (`docker compose --profile polyglot up -d` in the repository root) |
