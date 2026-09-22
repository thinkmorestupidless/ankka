# Contract: the TypeScript SDK

**Feature**: [spec.md](../spec.md) | **Research**: R9, R11 | **Protocol**: [protocol.md](./protocol.md)

Package `sdks/typescript`, name to be confirmed at publish time (R9, item 8); Node 22, ESM,
TypeScript 5. It implements the process side of every service in `protocol.md` and the client
side of `Client`. Its shape is the Scala SDK's shape: handlers return inert effect values, wire
names are declared separately from function names, a query cannot persist.

## Declaring a service

```ts
import { Ankka } from "@thinkmorestupidless/ankka";
import { ShoppingCartEntity } from "./shoppingCartEntity.js";
import { CartRows } from "./cartRows.js";

await Ankka.service()
  .register(ShoppingCartEntity)
  .register(CartRows)
  .listen();                       // ANKKA_PROCESS_PORT, default 9010, loopback only
```

`listen()` starts the gRPC server, answers `Discovery.Discover` with a `Spec` built from the
registered companions, and resolves once bound. A duplicate registration throws before `listen`.
`Ankka.service().spec()` returns the `Spec` without listening, for tests.

## An event sourced entity

```ts
export const ShoppingCartEntity = eventSourcedEntity<ShoppingCart, ShoppingCartEvent>({
  id: "shopping-cart",
  state: jsonCodec<ShoppingCart>("shopping-cart"),          // manifest, as Codecs.serializer
  events: jsonCodec<ShoppingCartEvent>("shopping-cart-event"),
  emptyState: () => ({ items: [], checkedOut: false }),
  applyEvent: (cart, event) => { /* the fold */ },
  snapshotEvery: 100,
}).handlers((effects) => ({
  "add-item": command<LineItem, Done>((cart, item, ctx) =>
    item.quantity <= 0
      ? effects.error("quantity must be positive", "BAD_REQUEST")
      : effects.persist({ type: "ItemAdded", item }).thenReply(() => Done)),
  "get-cart": query<void, ShoppingCart>((cart) => effects.reply(cart)),
}));
```

- `command` handlers return `EventSourcedEffect`; `query` handlers return `ReadOnlyEffect`, whose
  type has no `events`, so a persisting effect in a query is a compile error.
- `ctx` is `{ entityId, componentId, metadata, sequenceNumber, client: ComponentClient }`.
- The handler key is the wire name. Renaming the TypeScript function changes nothing on the wire.
- Companions for `keyValueEntity`, `workflow` (with `steps`), `view`, `consumer`, `timedAction`
  and `agent` follow the same pattern with their own effect builders, each mirroring the Scala
  `effects` surface field for field (data-model.md).

## Effects

Plain objects, built by the `effects` argument and never performing I/O:

| kind | builders |
|---|---|
| event sourced | `persist(e, ...more).thenReply(s => r) \| .thenReplyState \| .thenNoReply \| .deleteEntity() \| .expireAfter(ms)`; `reply(r)`; `error(message, code)`; `noReply` |
| key value | `updateState(s).thenReply(...)`; `deleteEntity()`; `reply`; `error` |
| workflow | `updateState(s).thenTransitionTo(step, input?) \| .thenPause(...) \| .thenEnd \| .thenReply(...)`; step effects likewise |
| view | `updateRow(row)`, `deleteRow`, `ignore` |
| consumer | `produce(payload, metadata?)`, `done`, `ignore` |
| timed action | `done`, `fail(message, code)` |
| agent | `systemMessage(s).userMessage(u).context(...).tools(...names).memory("session" \| "none").guardrails(...names).thenReply() \| .thenReplyJson(schemaHint)`; `error` |

An agent companion declares `tools: { [name]: { description, input: JsonSchema, run: (args, ctx) => Promise<string> } }`
and `guardrails: { [name]: { stage: "input" \| "output", check: (text, ctx) => "pass" \| { block: string } } }`;
the plan names them and the sidecar calls them back.

## The component client

```ts
const done = await ctx.client.forEventSourcedEntity("shopping-cart", cartId)
  .call("add-item").invoke<LineItem, Done>(item);
```

Typed by the handle when the target is a local companion (`ShoppingCartEntity.handle("add-item")`),
or by explicit type arguments when it is not. Every call copies the trace metadata from `ctx` so
the sidecar records a child span. `stream(...)` returns an async iterable of tokens for streaming
agent handlers. `views.query(...)` and `timers.schedule(...)` / `cancel(...)` wrap the rest of
`Client`.

## The unit testkit (`@thinkmorestupidless/ankka/testkit`)

```ts
const kit = EventSourcedTestKit.of(ShoppingCartEntity, "cart-1");
const result = kit.call("add-item", { productId: "p1", quantity: 2 });
expect(result.events).toEqual([{ type: "ItemAdded", item: {...} }]);
expect(result.reply).toEqual(Done);
expect(kit.state.items).toHaveLength(1);
```

No sidecar, no network. Inputs, events, state and replies round-trip through the companion's
codecs, so a missing codec fails here (the Scala testkit's rule). `result` is the materialised
effect: `{ events, newState, retention, reply | error }`. Equivalents exist for every kind; the
agent testkit takes a scripted model (`{ responses: [...] }`) and fails loudly when the script
runs out.

## The integration testkit

```ts
const ankka = await AnkkaTestKit.start({ service: myService });   // Postgres + ankka-sidecar via testcontainers
const reply = await ankka.invoke("event-sourced", "shopping-cart", "cart-1", "add-item", item);
await ankka.restart();                                             // drops every instance: proves durability
expect(await ankka.invoke("event-sourced", "shopping-cart", "cart-1", "get-cart")).toMatchObject({...});
await ankka.stop();
```

Starts Postgres with the platform's DDL (copied from the sidecar image, so a test can never pass
against a schema the platform does not have) and `ankka-sidecar:<version>` with
`ANKKA_PROCESS_ADDRESS` pointing at the test's own listener; `invoke` goes through the sidecar's
generic HTTP route. `restart()` restarts the sidecar container against the same database. The
sidecar image version is the SDK's declared `ankkaVersion`, never a literal `latest`.

## Guarantees the SDK gives the sidecar

- One command handled at a time per stream, replied to in order, with the sidecar's `command_id`.
- State released on stream close, whether clean or errored.
- A thrown handler is a `Failure`, never a silent `no_reply`.
- `snapshot` included exactly when `snapshot_requested`.
- The `Spec` lists every registered component with its handlers' wire names and `read_only`
  flags exactly as the companion declared them.

## Scripts

`npm run proto` copies `protocol/src/main/protobuf` in and regenerates; `npm test` runs vitest;
`npm run conformance` starts `examples/shopping-cart` and runs the platform's conformance suite
against it (`conformance.md`); `npm run example` starts the cart against a compose sidecar.
