# TypeScript SDK

> A compact map of the TypeScript SDK — installing it, the shapes and codecs, and for every component kind its base class, statics, declaration functions, effect builders and testkit — plus the SDK's own development commands.

Source: https://docs.ankka.cloud/reference/typescript-sdk/
The TypeScript SDK is the npm package `ankka`, for Node.js 22.22 and later. A TypeScript service is a
process that the ankka sidecar hosts: the sidecar owns the journal, sharding, projections, timers, HTTP and
the agent loop, and your classes decide what each command does. This page lists what each component kind
is made of. [Services in other languages](../concepts/polyglot.md) explains the model.

## Installing

The SDK is on npm as [`ankka`](https://www.npmjs.com/package/ankka). Every ankka release publishes the SDK
at the same version, so pin the version of the platform you deploy to:

```bash
npm install ankka@0.6.0                                        # the SDK
npm install -D testcontainers @testcontainers/postgresql        # only for the integration testkit
```

The package is an ES module and depends on `@connectrpc/connect`, `@connectrpc/connect-node` and
`@bufbuild/protobuf`, nothing else. The integration testkit's Docker dependencies are optional peers, so a
service that installs the SDK gets no Docker tooling. Docker is needed for integration tests and for running
the sidecar locally; a JVM is not. To work against an unreleased SDK, `npm install /path/to/ankka/sdks/typescript`
after building it as described under [Developing the SDK](#developing-the-sdk).

A service runs from source: Node strips the types, so `node main.ts` is the whole build. That rules out the
TypeScript syntax Node cannot run — `enum`, parameter properties, decorators — and the SDK uses none of
it. Set `erasableSyntaxOnly` in `tsconfig.json` and the compiler refuses it too.

## Shapes and codecs

Every value that crosses the protocol is declared once as a shape, and two things are derived from it: the
TypeScript type and the codec.

```ts
import { s, type Infer, jsonCodec } from "ankka"

const LineItem = s.record("LineItem", { productId: s.string, name: s.string, quantity: s.int })
type LineItem = Infer<typeof LineItem>                     // { productId: string; name: string; quantity: number }

const Event = s.sumType("ShoppingCartEvent", { ItemAdded: { item: LineItem }, CheckedOut: {} })
type Event = Infer<typeof Event>                           // { type: "ItemAdded"; item: LineItem } | { type: "CheckedOut" }

const codec = jsonCodec(Event, "shopping-cart-event")      // the manifest the journal stores it under
```

| Shape | TypeScript type | On the wire |
|---|---|---|
| `s.string` | `string` | a JSON string; at top level, `text/plain` |
| `s.int` | `number`, whole, within ±2⁵³ | a JSON integer; at top level, `text/plain` |
| `s.long` | `bigint` | a JSON integer, lossless past 2⁵³ |
| `s.double` | `number` | a JSON number as Scala writes it: `1.0`, `1.0E10` |
| `s.boolean` | `boolean` | `true` / `false` |
| `s.instant` | `Instant` | ISO-8601 in UTC with 0, 3, 6 or 9 fractional digits |
| `s.duration` | `Duration` | ISO-8601, `PT1.5S` |
| `s.localDate`, `s.localDateTime` | `LocalDate`, `LocalDateTime` | ISO-8601 strings |
| `s.bytes` | `Uint8Array` | base64 inside JSON; raw bytes at top level |
| `s.option(x)` | `T \| null` | `null` when absent; an absent field reads as `null` |
| `s.list(x)` | `T[]` | a JSON array |
| `s.stringMap(x)` | `Record<string, T>` | a JSON object |
| `s.record(name, fields)` | an object type | a JSON object, every field written, in declaration order |
| `s.sumType(name, cases)` | a discriminated union on `type` | the case's object with `"type": "<Case>"` |
| `s.enumeration(name, ...values)` | a string literal union | `{"type": "Ready"}` |
| `s.lazy(() => X)` | `T` | inline, for recursive shapes |
| `Done` / `done` | the one value `done` | the empty `done` payload |

The encoding is exactly what the Scala and Python SDKs write, so one journal is readable from all three;
[Serialization](../build/serialization.md) has the rules. Field names are the wire contract: use `productId`
rather than `product_id` when a Scala service shares the data. A codec of your own is any object with
`manifest`, `contentType`, `encode` and `decode`; pass it wherever a shape is accepted.

## The shape every component shares

A component is a class that extends the kind's base class and declares, as statics, its `componentId`, its
codecs, and a table of handlers whose first argument is the wire name:

```ts
export class ShoppingCartEntity extends EventSourcedEntity<ShoppingCart, ShoppingCartEvent> {
  static readonly componentId = "shopping-cart"
  static readonly state = jsonCodec(ShoppingCart, "shopping-cart")
  static readonly events = jsonCodec(ShoppingCartEvent, "shopping-cart-event")
  static readonly handlers = {
    addItem: command("add-item", LineItem, Done, (cart: ShoppingCartEntity, item) => cart.addItem(item)),
    getCart: query("get-cart", ShoppingCart, (cart: ShoppingCartEntity) => cart.effects.reply(cart.state)),
  }
  ...
}
```

`command(name, input, reply, run)` and `query(name, reply, run)` take shapes for the input (omitted when
there is none) and the reply, and type `run` from them; `query` accepts only a function returning a
read-only effect, so a query that persists does not compile. The property name (`addItem`) is yours; the
wire name (`"add-item"`) is the platform's. `register` refuses a class missing a static its kind requires,
at compile time; `validate` reports every remaining problem at once.

## Event sourced entity

| Part | API |
|---|---|
| Base class | `EventSourcedEntity<S, E>` |
| Statics | `componentId`, `state`, `events`, `handlers`, optionally `snapshotEvery` |
| Must define | `emptyState(): S`, `applyEvent(state, event): S` |
| Declarations | `command(name, input?, reply, run)`, `query(name, input?, reply, run)` |
| In a handler | `this.state`, `this.entityId`, `this.context`, `this.effects`, `this.client` |
| Effects | `persist(e, ...more)`, `persistAll(events)`, `deleteEntity()`, `expireAfter(d)`, then `.thenReply(s => r)`, `.thenReplyState()`, `.thenNoReply()`, `.deleteEntity()`, `.expireAfter(Duration)`; `reply(r)`, `error(msg, code)`, `noReply()` |
| Types | a command returns `EventSourcedEffect<S, E, R>`; a query returns `ReadOnlyEffect<S, E, R>` |

See [Event sourced entities](../build/event-sourced-entities.md).

## Key value entity

| Part | API |
|---|---|
| Base class | `KeyValueEntity<S>` |
| Statics | `componentId`, `state`, `handlers` |
| Must define | `emptyState(): S` |
| In a handler | `this.state`, `this.entityId`, `this.context`, `this.effects`, `this.client` |
| Effects | `updateState(s)`, `deleteEntity()`, then `.thenReply(s => r)`, `.thenReplyState()`, `.thenNoReply()`, `.expireAfter(Duration)`; `reply(r)`, `error(msg, code)`, `noReply()` |

See [Key value entities](../build/key-value-entities.md).

## View

| Part | API |
|---|---|
| Base class | `View<E, Row>` |
| Statics | `componentId`, `source` (a component class) or `topic`, `events`, `row`, optionally `queries` (`["get", "all"]` by default) |
| Must define | `onChange(event): ViewEffect<Row>` |
| May override | `onDelete(): ViewEffect<Row>`, which deletes the row by default |
| In a handler | `this.row` (the current row or `null`), `this.subject`, `this.metadata`, `this.effects` |
| Effects | `updateRow(row)`, `deleteRow()`, `ignore()` |
| Querying | `client.views.get(viewId, key, Row)`, `client.views.all(viewId, Row)`, `client.views.query(viewId, name, key, Row)` |

See [Views](../build/views.md).

## Consumer

| Part | API |
|---|---|
| Base class | `Consumer<M, Out>` |
| Statics | `componentId`, `source` or `topic`, `message`; to publish, `producesTo` and `out` |
| Must define | `onMessage(message): ConsumerEffect<Out>` |
| May override | `onDelete()`, which ignores by default |
| In a handler | `this.subject`, `this.metadata`, `this.client`, `this.effects` |
| Effects | `produce(out, metadata?)`, `done()`, `ignore()` |

Delivery is at least once. A consumer that produces needs `ANKKA_KAFKA_BOOTSTRAP_SERVERS` on the sidecar.
See [Consumers](../build/consumers.md).

## Workflow

| Part | API |
|---|---|
| Base class | `Workflow<S>` |
| Statics | `componentId`, `state`, `handlers`, `steps`, optionally `settings` |
| Must define | `emptyState(): S` |
| Declarations | `command`, `query` in `handlers`; `step(name, input?, run)` in `steps` |
| In a handler or step | `this.state`, `this.entityId`, `this.context`, `this.client`, `this.effects`, `this.stepEffects` |
| Command effects | `updateState(s)`, `transitionTo(step, input?)`, then `.thenTransitionTo(step, input?)`, `.thenReply(s => r)`, `.thenReplyState()`, `.thenNoReply()`; `reply(r)`, `error(msg, code)` |
| Step effects | `updateState(s)` then, or directly: `transitionTo(step, input?)`, `pause({ after, onTimeout, onTimeoutInput })`, `end()`, `fail(msg, code)` |
| Settings | `workflowSettings({ timeout, defaultStepTimeout, defaultRecovery, steps: { name: { timeout, recovery } } })` |
| Recovery | `{ maxRetries: n, failoverTo: "step" }` |

Steps may be `async` and may call other components. A step that throws is retried as its recovery says,
then failed over; a step that returns `fail(...)` ends the workflow. A transition names a step by wire
name, and its input is encoded with that step's declared shape. See [Workflows](../build/workflows.md).

## Timed action and timers

| Part | API |
|---|---|
| Base class | `TimedAction` |
| Statics | `componentId`, `actions` |
| Declarations | `action(name, input?, run)` |
| In a handler | `this.metadata` (`ankka.timer`, `ankka.attempts`), `this.client`, `this.effects` |
| Effects | `done()`, `fail(msg, code)` |
| Scheduling | `await client.timers.schedule(timerId, Duration, { component: Cls, handler: Cls.actions.name }, input)`, or by name `{ kind: "timed-action", componentId, name, input: Shape }`; `await client.timers.cancel(timerId)` |

Scheduling twice under one id replaces the earlier timer. See [Timers](../build/timers.md).

## Agent

| Part | API |
|---|---|
| Base class | `Agent` |
| Statics | `componentId`, `handlers`, optionally `role`, `maxToolCallSteps`, `tools`, `guardrails` |
| Declarations | `command(...)` and `stream(name, input?, run)` in `handlers`; `tool(name, description, Input, run)` in `tools`; `guardrail(name, check)` in `guardrails` |
| In a handler | `this.sessionId`, `this.metadata`, `this.client`, `this.effects` |
| Effects | `systemMessage(t)`, `userMessage(t)`, `model(name)`, then `.withModel(name)`, `.withContext(t)`, `.memory(bool)`, `.tools(...names)`, `.guardrails(...names)`, `.thenReply()`, `.thenReplyJson<R>()`; `error(msg, code)` |

A handler returns a plan; the sidecar runs the model loop, calls tools back in the process with the model's
arguments decoded by the tool's input shape, and checks guardrails. A tool's input shape is also the JSON
Schema the model sees. A guardrail's `check(stage, text)` returns `null` to pass or a reason to block.
See [Agents](../build/agents.md).

## HTTP endpoint

| Part | API |
|---|---|
| Base class | `Endpoint` |
| Statics | `prefix`, `acl` (required: `Acl.allowAll`, `Acl.denyAll` or `Acl.authenticated`), `routes` |
| Declarations | `get(template, reply, run, options?)`, `post`/`put`/`patch`/`del(template, body?, reply, run, options?)`, `sse(template, run, options?)`; `options` may carry `acl` for that route alone and `params` schemas narrowing path parameters |
| Handlers | `(self, req, body) => reply`, sync or `async`; `req.params` is typed from the template; the return value is encoded with the reply shape, `done` or `undefined` answers 204 |
| In a handler | `this.request`: `params`, `query.get`/`getAll`, `headers.get`, `principal`, `metadata`; `this.client`, scoped to the request |
| Errors | `throw new HttpProblem(status, message)`; a `CommandError` from a call answers with its code's status |

The process never binds an HTTP port: the sidecar serves the routes and forwards each request. `acl` is
required, as it is in the Scala and Python SDKs: a class without one is refused where it is registered, by
the type checker, rather than serving requests under a posture nobody chose. A route's `acl` option replaces
the endpoint's for that route. See [HTTP endpoints](../build/http-endpoints.md).

## Calling components

```ts
// typed, from the handler table
await this.client.of(ShoppingCartEntity, cartId).call(ShoppingCartEntity.handlers.addItem).invoke(item)
// by name, for a component whose class is not at hand
await this.client.forEventSourcedEntity("shopping-cart", cartId).call("add-item", LineItem, Done).invoke(item)
for await (const token of this.client.forAgent("assistant", session).call("chat", s.string).stream(question)) ...
```

| Target | Call |
|---|---|
| Any component, typed | `client.of(Cls, entityId).call(Cls.handlers.name)` |
| Event sourced entity | `client.forEventSourcedEntity(componentId, entityId)` |
| Key value entity | `client.forKeyValueEntity(componentId, entityId)` |
| Workflow | `client.forWorkflow(componentId, workflowId)` |
| Agent | `client.forAgent(componentId, sessionId)` |

`.call(...)` gives an invocation: `await invocation.invoke(input)`, or `for await (const token of
invocation.stream(input))` for a streaming agent handler. A refusal rejects with `CommandError`, whose
`code` is the refusal's. Inside a handler `this.client` already carries the request's trace, so the call is
a child span; `client.withMetadata(md)` scopes a client by hand. See
[Calling components](../build/component-client.md).

## Running a service

```ts
import { Ankka } from "ankka"
await Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint).listen()
```

`listen()` serves the protocol on port 9010, or `ANKKA_PROCESS_PORT`, on loopback, and waits for the
sidecar to connect. Locally, run the sidecar with `docker compose --profile polyglot up -d` from the ankka
repository. `Ankka.service().spec()` returns what discovery will say without listening.

## Testing

| Kit | Import | Drives |
|---|---|---|
| `EventSourcedTestKit.of(Cls, id)` | `ankka/testkit` | One entity; `await call(Cls.handlers.x, input)` returns events, state, retention and reply. |
| `KeyValueTestKit.of(Cls, id)` | `ankka/testkit` | One key value entity. |
| `WorkflowTestKit.of(Cls, id)` | `ankka/testkit` | One workflow: `call`, `runStep`, `runUntilEnd` (stops at a pause), `resume`, `progress`. |
| `ViewTestKit.of(Cls)` | `ankka/testkit` | A view's `onChange(key, event)` and `onDelete(key)`, and the rows. |
| `ConsumerTestKit.of(Cls)` | `ankka/testkit` | A consumer's `onMessage` and `onDelete`, and what it `produced`. |
| `TimedActionTestKit.of(Cls)` | `ankka/testkit` | A timed action's handlers. |
| `AgentTestKit.of(Cls, sessionId, new ScriptedModel())` | `ankka/testkit` | An agent's plan, tools and guardrails, against a scripted model that fails when the script runs out. |
| `EndpointTestKit.of(Cls)` | `ankka/testkit` | An endpoint's routes by path, with no sidecar. |
| `AnkkaTestKit.start(service)` | `ankka/testkit` | The whole service through the real sidecar image and a throwaway Postgres. `restart()` starts a new sidecar on the same database. |

Unit testkits need nothing running and still round-trip every value through its codec. They depend on no
test runner: `node --test` and vitest both work. See [Testing](../build/testing.md).

## Developing the SDK

From `sdks/typescript` in the ankka repository:

```bash
npm ci                                         # install, with the development dependencies
npm run proto                                  # copy the protocol in and regenerate the stubs
npm run typecheck                              # tsc over src, test and examples
npm test                                       # unit testkits, the encoding fixtures, the servicers
npm run test:slow                              # through the real sidecar and Postgres; needs Docker
npm run conformance                            # serve the reference service and run the platform's conformance suite
npm run example                                # the sample, beside `docker compose --profile polyglot up -d`
npm run build                                  # emit dist/, what the package ships
```

The SDK carries a copy of the protocol so it can be built on its own; `npm run proto` refreshes it. The
conformance suite needs sbt and runs the platform's suite against the SDK's reference service. See
[Adding a language SDK](../contributing/language-sdks.md).
