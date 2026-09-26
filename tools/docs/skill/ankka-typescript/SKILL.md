---
name: ankka-typescript
description: Write, run, test or deploy an ankka service in TypeScript on Node.js — components as classes with static declarations (componentId, codecs, a handlers table of command/query/step/action/stream, a routes table of get/post/sse), shapes declared once with `s` and typed by Infer, the SDK's effects and testkits, the sidecar that hosts the process, the gRPC protocol and discovery, the shared JSON encoding with Scala and Python, and the descriptor fields of a process-hosted service. Use when the task involves TypeScript, JavaScript, Node.js, npm, the ankka TypeScript SDK, or a service in a language other than Scala.
pages:
  - concepts/polyglot.md
  - get-started/first-service-typescript.md
  - reference/typescript-sdk.md
  - reference/sidecar-protocol.md
  - build/serialization.md
  - build/testing.md
  - deploy/deploy-a-service.md
---

# ankka in TypeScript

A TypeScript service runs as its own Node.js process with the ankka runtime beside it as a sidecar. The
sidecar owns everything durable and distributed (sharding, the journal, projections, timers, the workflow
engine, the agent loop and the model's key, HTTP binding and ACLs, cluster membership); the process owns
the decisions: which events for this command, what a step does, an agent's plan and tools. The SDK speaks
the gRPC protocol so your code never does. Every component guide in the other ankka skills applies; this
skill holds what differs.

## Rules

1. **Every component is a class with static declarations.** `static readonly componentId`, codecs
   (`state = jsonCodec(State, "manifest")`, `events`, `row`, `message`, `out`), and a table: `handlers =
   { addItem: command("add-item", Input, Reply, (self: Cls, input) => self.addItem(input)) }`, `query(...)`
   (its function must return a read-only effect, or it does not compile), `steps = { ... step(...) }`,
   `actions = { ... action(...) }`, `tools = { ... tool(name, description, Input, run) }`, `guardrails =
   { ... guardrail(name, check) }`, `routes = { ... get("/{id}", Reply, run) }`. The first argument is the
   wire name; the property name is yours. Register classes on `Ankka.service().register(Cls)`.
2. **Shapes are declared once, with `s`, and the type comes from them.** `const Cart = s.record("Cart",
   { cartId: s.string, items: s.list(LineItem) })` and `type Cart = Infer<typeof Cart>`. Sum types are
   `s.sumType("Event", { ItemAdded: { item: LineItem }, CheckedOut: {} })`, a discriminated union on
   `type` in TypeScript and `"type"` on the wire. `s.int` is a whole `number`, `s.long` a `bigint`,
   `s.double` a `number`, `s.instant` an `Instant`, `s.option(x)` is `x | null`. Field names are the
   stored JSON (`productId`), so one journal reads from Scala, Python and TypeScript. A top-level
   `s.string`, `s.int` or `s.boolean` crosses as `text/plain`, so an endpoint returning `s.string`
   answers text, not JSON.
3. **No decorators, no enums, no parameter properties, no `reflect-metadata`.** Node runs the sources
   directly, and its type stripping refuses that syntax. A component never writes a constructor; it gets
   `this.client` from its base class. Imports of local files name the `.ts` extension.
4. **Effects mirror Scala's in camel case.** `this.effects.persist(e).thenReply(() => done)`,
   `.thenReplyState()`, `.deleteEntity()`, `.expireAfter(Duration.ofSeconds(1))`, `this.effects.error(msg,
   ErrorCode.Conflict)`; `updateState`, `updateRow`/`deleteRow`/`ignore`, `produce`/`done`/`ignore`,
   `this.stepEffects.updateState(s).thenTransitionTo("charge", input)`, `.thenPause({ after, onTimeout:
   "step" })`, `.thenEnd()`, `.thenFail()`. `this.state`, `this.row`, `this.entityId`, `this.subject`.
5. **Calls are awaited, typed from the handler table.** `await this.client.of(ShoppingCartEntity,
   id).call(ShoppingCartEntity.handlers.addItem).invoke(item)` takes the wire name and shapes from the
   declaration; `forEventSourcedEntity("shopping-cart", id).call("add-item", LineItem, Done)` is the form
   by name. `forKeyValueEntity`, `forWorkflow`, `forAgent(...).call(...).stream(input)`, `views.get(viewId,
   key, Row)`, `views.all`, `timers.schedule(id, Duration, { component: Cls, handler: Cls.actions.x },
   input)`, `timers.cancel`. A refusal rejects with `CommandError` carrying `code`. Inside an endpoint
   `this.client` is already scoped to the request's trace.
6. **Endpoints declare `acl` and their routes' shapes.** `static readonly acl = Acl.allowAll |
   Acl.denyAll | Acl.authenticated` is required (it does not compile without). `post("/{cartId}/items",
   LineItem, Done, (ep, req, item) => ...)`: `req.params.cartId` is typed from the template, `req.query`,
   `req.headers`, `req.principal`; a route's own `{ acl }` option replaces the endpoint's. `throw new
   HttpProblem(404, "...")` for a status; `done` or `undefined` answers 204; `sse(template, run)` returns an
   `AsyncIterable<string>`. The process never binds an HTTP port.
7. **Steps and agents run in the sidecar's engines.** Settings the engine enforces are declared:
   `static readonly settings = workflowSettings({ defaultStepTimeout, steps: { charge: { recovery: {
   maxRetries: 1, failoverTo: "compensate" } } } })`. A step that *throws* is retried and failed over; a
   step that returns `thenFail()` ends the workflow. An agent's plan names tools and guardrails by wire
   name (`.tools("lookup")`); `memory(false)` for one-shot; the model is configured on the sidecar
   (`ANTHROPIC_API_KEY`, `ANKKA_MODEL_NAME`, `ANKKA_MODEL_SCRIPT`) and the process never sees the key.
8. **Test with the kits, on any runner.** `EventSourcedTestKit.of(Cls, id)`, `KeyValueTestKit`,
   `WorkflowTestKit` (`call`, `runStep`, `runUntilEnd`, `resume`), `ViewTestKit`, `ConsumerTestKit`,
   `TimedActionTestKit`, `AgentTestKit.of(Cls, session, new ScriptedModel().expectToolCall(...).expectText(...))`,
   `EndpointTestKit` — no sidecar, every value round-tripped through its codec. `AnkkaTestKit.start(service)`
   runs the real sidecar image and Postgres in Docker (`testcontainers` and `@testcontainers/postgresql`
   installed as dev dependencies), with `restart()` to prove durability. The docs use `node --test`;
   vitest works too.
9. **Discovery reports every problem at once.** Registration collects problems (duplicate ids or wire
   names, a missing static, an endpoint without `acl`, settings naming an unknown step) and throws them
   together; the sidecar does the same for what it refuses. Ports: the process listens on 9010, the
   sidecar on 9011, both loopback.
10. **Deploy as a process-hosted service.** The descriptor sets `hosting: "process"` and `protocol: "1.0"`;
    the image holds only your process (`node:24-slim`, `node main.ts`); the platform adds the sidecar.
    `ANTHROPIC_*`, `ANKKA_MODEL_*` and `ANKKA_DB_*` go to the sidecar, everything else to the process.
11. **The process holds no durable state.** It may restart freely; the sidecar re-opens entities when it
    returns. Do not cache state across commands.

## Development loop

Node 22.22 or later (24 recommended). `npm install ankka`; `node main.ts` runs the service; `node --test`
runs tests. In the SDK's own checkout: `npm ci`, `npm run proto`, `npm run typecheck`, `npm test`, `npm run
test:slow`, `npm run conformance`. See `references/get-started/first-service-typescript.md` for the first
service and `references/reference/typescript-sdk.md` for the map of every class, static and function.

## Mistakes to check for

- A `query(...)` whose function persists or updates: it does not compile; do not cast around it.
- A parameter property (`constructor(private x)`), an `enum`, a decorator, or an import without `.ts`.
- A number field meant as a double declared `s.int` (refuses `1.5`) or a 64-bit id declared `s.int`
  (refuses past 2⁵³; use `s.long`).
- A test parsing a `s.string` reply as JSON, or posting a string body as a JSON string.
- Transitioning to a step name that is not in `steps`, or `settings` naming one.
- Reading `this.sessionId` inside a tool after the plan; it is captured on the agent instance the tool runs on.
