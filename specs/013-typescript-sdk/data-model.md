# Data Model: TypeScript SDK

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

The protocol, the encoding and the sidecar are unchanged (FR-033); their models are in
`specs/009-polyglot-runtimes/data-model.md` and are referenced, not repeated. This document is the
SDK's own values: what a developer declares, what the SDK holds while it runs, and what the testkits
expose. Names are the ones the contract uses ([contracts/typescript-sdk.md](./contracts/typescript-sdk.md)).

## Shape declarations (`schema.ts`)

A `Schema<T>` is a runtime description of a value's structure from which two things are derived: the
static type (`Infer<typeof X>`) and the default codec (R3). Every value that crosses the protocol
has one. Schemas are plain frozen objects, built with the functions on `s`:

| builder | TypeScript type | JSON (ENCODING.md) | notes |
|---|---|---|---|
| `s.string` | `string` | string | |
| `s.int` | `number` | integer, no fraction | refuses a non-integer or a value outside ±2⁵³ on write |
| `s.long` | `bigint` | integer, no fraction | lossless past 2⁵³ (R3); a `number` is accepted on write when it is a safe integer |
| `s.double` | `number` | shortest round-trip repr, `1.0` for whole values, `1.0E10` past 10⁷ | rendered as jsoniter does (R3) |
| `s.boolean` | `boolean` | `true`/`false` | |
| `s.instant` | `Instant` | ISO-8601 UTC with `Z`, 0/3/6/9 fractional digits | own value class, nanosecond precision (R4) |
| `s.duration` | `Duration` | ISO-8601 (`PT1.5S`) | own value class |
| `s.localDate`, `s.localDateTime` | `LocalDate`, `LocalDateTime` | ISO-8601 strings | thin value classes over their string forms |
| `s.bytes` | `Uint8Array` | base64 string inside JSON | at top level: the binary `bytes` payload |
| `s.option(x)` | `T \| null` | `null` when absent | reads an absent field as `null`; `undefined` accepted on write |
| `s.list(x)` | `T[]` | array, `[]` when empty | |
| `s.stringMap(x)` | `Record<string, T>` | object | |
| `s.record(name, fields)` | `{ f1: T1, … }` | object, every field written | `name` is the default manifest |
| `s.sumType(name, cases)` | `{ type: "A", …} \| { type: "B", … }` | case object plus `"type": "<CaseName>"` | the value carries `type`; nothing is translated |
| `s.enumeration(name, ...values)` | `"A" \| "B"` | `{"type":"A"}` | the fieldless-enum-as-field rule; a bare string accepted on read |
| `s.lazy(() => X)` | `T` | inline | recursive shapes |

`Done` is a schema and a value: `done` is the only value of type `Done`; its codec is the binary
`done` payload. `s.unit` is `undefined` with the binary `unit` payload.

**Validation rules** (refused at declaration, before any service starts): a record or sum type with
no name; a sum type with a case named `type`, or with a field named `type` in any case; an
enumeration with no values or a duplicate; a record with a duplicate field. **Read rules**: unknown
fields ignored; a missing required field, an unknown `type`, a non-integer where an integer is
declared, or a number where a `bigint` schema is declared but the text has a fraction, refuse with
the path to the offending field.

## Codecs (`codec.ts`, `json.ts`)

```
Codec<T> { manifest: string; contentType: ContentType; encode(value: T): Uint8Array; decode(bytes: Uint8Array): T }
```

- `jsonCodec(schema, manifest?)` — `application/json`; the manifest defaults to the schema's name.
  Encoding and decoding go through the SDK's own writer and reader (R3), never `JSON.stringify` or
  `JSON.parse` on the whole document.
- Text codecs, `text/plain`: `string`, `int`, `long`, `short`, `byte`, `double`, `float`,
  `boolean`, `duration-millis`. `double` renders in Scala's `Double.toString` style; the readers
  accept any decimal or exponent form.
- Binary codecs, `application/octet-stream`: `done`, `unit`, `bytes`, `option[<inner>]` (zero
  bytes for none; `0x01` then the inner bytes).
- `defaultCodecFor(schema)`: a top-level scalar schema selects its text codec (`s.string` →
  `string`, `s.int` → `int`, `s.long` → `long`, `s.double` → `double`, `s.boolean` → `boolean`);
  `s.bytes` → `bytes`; `Done` → `done`; `s.option(x)` at top level → `option[<x's manifest>]`;
  everything else → `jsonCodec(schema)`.
- `codecForManifest(manifest)`: the inverse for primitives, including `option[...]`, used by the
  fixture test and the view client.

A `Payload` on the wire is `{ contentType, manifest, data }` exactly as `payload.proto` says.

## Components

Every component is a class with static declarations. The SDK never scans; a class exists to the
runtime only once `register`ed. The static shape each kind must have is a TypeScript type the
`register` method constrains, so a missing declaration is a compile error at the registration site
and a registration-time problem for a caller who bypassed the checker (FR-002, FR-005).

| kind | required statics | instance surface | methods the developer writes |
|---|---|---|---|
| `EventSourcedEntity<S, E>` | `componentId`, `state: Codec<S>`, `events: Codec<E>`, `handlers`; optional `snapshotEvery` (default 0: never) | `state`, `entityId`, `context`, `effects`, `client` | `emptyState()`, `applyEvent(state, event)`, handler methods |
| `KeyValueEntity<S>` | `componentId`, `state`, `handlers` | as above | `emptyState()`, handler methods |
| `Workflow<S>` | `componentId`, `state`, `handlers`, `steps`; optional `settings` | `state`, `entityId`, `context`, `effects`, `stepEffects`, `client` | `emptyState()`, handler and step methods |
| `View<Src, Row>` | `componentId`, `source` (a component class) or `topic`, `row: Codec<Row>`; optional `queries` | `client` | `onChange(event, row \| null)`, `onDelete(row)` |
| `Consumer<Msg, Out>` | `componentId`, `source` or `topic`, `message: Codec<Msg>`; optional `out: Codec<Out>`, `producesTo` | `client` | `onMessage(message, metadata)`, `onDelete(subject)` |
| `TimedAction` | `componentId`, `actions` | `client` | action methods |
| `Agent` | `componentId`, `role`, `handlers`; optional `maxToolCallSteps`, `tools`, `guardrails` | `sessionId`, `effects`, `client` | handler and tool methods |
| `Endpoint` | `prefix`, `acl`, `routes` | `request`, `client` | route methods |

**Handler table entries** (`handlers.ts`) carry everything discovery and dispatch need, and are also
what the testkits and the typed client accept:

```
HandlerRef<C, I, R> { name: string; kind: "command" | "query" | "stream" | "step" | "action";
                      input: Schema<I> | undefined; reply: Schema<R> | undefined; run: (self: C, input: I) => Effect | Promise<Effect> }
RouteRef<Ep, P, B, R> { id: string; method; template; params: P; body: Schema<B> | undefined; reply: Schema<R> | undefined;
                        streaming: boolean; acl: Acl | undefined; run: (self: Ep, req: Request<P>, body: B) => R | Promise<R> | AsyncIterable<string> }
ToolRef<A, I>      { name; description; input: Schema<I>; run: (self: A, input: I) => unknown }
GuardrailRef       { name; check: (stage: "input" | "output", text: string) => string | null | Promise<string | null> }
```

`query(...)` accepts only a `run` whose return type is `ReadOnlyEffect`, a type with `kind:
"read-only"` that a persisting effect does not satisfy (R5). `handlers`, `routes`, `tools` and
`steps` are objects keyed by the developer's own property names; the wire name is the entry's
`name`. Duplicate names across an object are a registration problem.

## Effects (`effects/*.ts`)

Inert frozen values, spelled as the Scala effects are, in JavaScript casing. Built through
`this.effects` (or `this.stepEffects`), never performing I/O.

| kind | values | builders |
|---|---|---|
| shared | `Outcome = Reply(compute: (s) => R, metadata) \| NoReply \| Fail(Error)`; `Retention = DeleteNow \| ExpireAfter(Duration)`; `ErrorCode` with `httpStatus` | |
| event sourced | `PersistEffect<S,E,R> { kind: "persist"; events: E[]; retention?; outcome }`; `ReadOnlyEffect<S,E,R> { kind: "read-only"; retention?; outcome }` | `persist(e, ...more) → PersistBuilder`: `.deleteEntity()`, `.expireAfter(d)`, `.thenReply(f)`, `.thenReplyState()`, `.thenNoReply()`; `reply(r)`, `error(msg, code)`, `noReply()`, `deleteEntity()` |
| key value | `UpdateEffect { kind: "update"; newState; retention?; outcome }`; `KeyValueReadOnlyEffect { kind: "read-only" }` | `updateState(s) → .thenReply/.thenReplyState/.thenNoReply/.deleteEntity/.expireAfter`; `deleteEntity()`, `reply`, `error`, `noReply` |
| workflow (command) | `WorkflowEffect { kind: "workflow"; newState?; transition?: StepRef; outcome }`; `WorkflowReadOnlyEffect { kind: "read-only" }` | `updateState(s).thenTransitionTo(step, input?).thenReply(f)`, `transitionTo(...)`, `reply`, `error` |
| workflow (step) | `StepEffect { newState?; next: TransitionTo(StepRef) \| Pause({ after?, onTimeout? }) \| End \| Fail(Error) }` | `stepEffects.updateState(s).thenTransitionTo / .thenPause / .thenEnd / .thenFail`, and the same without an update |
| view | `UpdateRow(row) \| DeleteRow \| Ignore` | `updateRow(row)`, `deleteRow()`, `ignore()` |
| consumer | `Produce(payload, metadata) \| Done \| Ignore` | `produce(value, schema?, metadata?)`, `done()`, `ignore()` |
| timed action | `Done \| Fail(Error)` | `done()`, `fail(msg, code)` |
| agent | `AgentEffect { model?; system?; user?; context: string[]; memory: "session" \| "none"; tools: string[]; guardrails: string[]; responseShape: text \| json(schemaHint); failure?: Error }` | `systemMessage(s).userMessage(u).withContext(...).memory(m).withModel(name).tools(...).guardrails(...).thenReply() \| .thenReplyJson(schema)`; `error(msg, code)` |

`WorkflowSettings { timeout?: Duration; defaultStepTimeout?: Duration; defaultRecovery?: Recovery; steps?: Record<string, StepSettings> }`,
`Recovery { maxRetries: number; failoverTo?: string }`; a `failoverTo` or a `steps` key that is not a
declared step is a registration problem.

## Service and registry (`service.ts`)

```
ServiceBuilder { register(cls): this; spec(): Spec; server(): Server; listen(opts?): Promise<void> }
Registry { components: Map<id, Registered>; endpoints: Map<id, RegisteredEndpoint>; problems: Problem[] }
```

`register` dispatches on the class's base and records `Problem`s rather than throwing; `validate()`
throws once with every problem (duplicate component id, duplicate wire name within a component,
duplicate route id or prefix, a `query` whose `run` is not read-only at runtime, a tool with no
description, workflow settings naming an unknown step, an endpoint without `acl`, a `view` with both
or neither of `source`/`topic`). `spec()` renders `discovery.proto`'s `Spec`: `protocol_version =
"1.0"`, `sdk = { name: "ankka-typescript", version: VERSION }`, components sorted by id with
handlers sorted by name, endpoints with routes in declaration order. `listen()` refuses a host other
than loopback or `0.0.0.0` (the testkit's), binds `ANKKA_PROCESS_PORT` (default 9010) on an `http2.createServer` carrying Connect's
adapter, plaintext, because the gRPC protocol and bidirectional streams need HTTP/2 (R2), and resolves
when the server closes.

## Server state (`server/*.ts`)

**Discovery**: `Discover` returns the registry's `Spec`; `ReportError` logs each problem and appends
to `problems: string[]` (the conformance reference exposes it at `GET /conformance/problems`).

**Event sourced stream** — one handler invocation per stream, its locals the whole state:

```
StreamState<S> { entity: C; entityId: string; state: S; sequence: bigint }
init(Init)     → entity = new C(); bind entityId; state = snapshot ? decode(snapshot) : entity.emptyState(); sequence = snapshot?.sequence ?? 0
event(Event)   → state = entity.applyEvent(state, decode(event)); sequence = event.sequence
command(Cmd)   → run handler with state → effect
                 persist: encode events; fold them locally into `after`; reply = outcome.compute(after); state = after; sequence += events.length
                          snapshot = command.snapshot_requested ? encode(after) : absent
                 read-only: reply from `state`; nothing changes
                 error outcome (refusal): Reply with Outcome.error; state unchanged
                 thrown / rejected: Failure{command_id}; state unchanged
                 unknown handler: Failure NOT_FOUND
stream end     → drop everything (passivation and sidecar loss are indistinguishable, R6)
```

Commands are awaited strictly in arrival order inside one async loop, which is what makes "one
command in flight" true without a lock.

**Key value stream**: the same shape with `state` replaced by `new_state` and no fold.

**Workflow stream** — two slots (R6):

```
WorkflowStream<S> { instance: C; state: S; step?: { id: bigint; task: Promise<void> }; out: AsyncQueue<WorkflowOut> }
command   → answered inline from `state`, before the step; `new_state` applied to `state`
run_step  → if step present: Failure "a step is already running"; else spawn on a *fresh* instance bound to the same entityId;
            on completion: StepReply{ new_state, next }; state = new_state ?? state; step = undefined
stream end → cancel the step task; drop the state
```

All replies leave through one queue so a command's reply is never behind a step's.

**Stateless kinds** (view, consumer, timed action, agent plan/tool/guardrail): a new instance per
request; a thrown handler is the request's `Failure` or, for a timed action, `fail`.

**HTTP**: `Handle` finds the endpoint by `endpoint_id` and the route by `route_id`; builds
`Request { params (typed from the template, parsed as the schema says), query: Query (multi-valued,
ordered), headers: Headers, principal?, metadata }`; sets it in an `AsyncLocalStorage` so
`this.request` reads it; decodes the body with the route's schema; encodes the return value with the
reply schema (status 200), `done`/`undefined` → 204, `HttpProblem` → its status as `text/plain`,
a `CommandError` → its code's HTTP status, anything else → `Failure`. `HandleStream` iterates the
returned `AsyncIterable<string>` into `StreamFrame{text}` and closes with `completed` or `failed`.
Endpoint instances are cached per endpoint id and hold no per-request state.

## Client (`client.ts`)

```
ComponentClient { forEventSourcedEntity(id, key): Calls; forKeyValueEntity; forWorkflow; forAgent(id, session): Calls;
                  of<C>(cls: C, key): TypedCalls<C>; views: Views; timers: Timers; withMetadata(md): ComponentClient }
Calls { call<I, R>(name, input?: Schema<I>, reply?: Schema<R>): Invocation<I, R> }
TypedCalls<C> { call<I, R>(ref: HandlerRef<C, I, R>): Invocation<I, R> }
Invocation { invoke(input?: I): Promise<R>; stream(input?: I): AsyncIterable<string> }
Views  { get(viewId, key, row: Schema<Row>): Promise<Row | null>; all(viewId, row): Promise<Row[]>; query(viewId, name, key, row): Promise<Row[]> }
Timers { schedule(timerId, delay: Duration, target: HandlerRef | { kind, componentId, name }, input?, entityId?): Promise<void>; cancel(timerId) }
CommandError extends Error { code: ErrorCode }
```

One channel per process, opened lazily on first use at `ANKKA_SIDECAR_ADDRESS` (default
`127.0.0.1:9011`); `reconnect(address)` for the integration testkit. Every handler receives a client
scoped to the incoming request's metadata, so the sidecar records child spans (R6). Outside a
handler the unscoped client works and its calls are root spans.

## Testkits (`testkit/*.ts`)

**Unit** (no sidecar, no network, asynchronous):

```
Materialised<S, E, R> { events: E[]; newState: S; retention: Retention | null; reply: R | undefined; error: Error | undefined }
EventSourcedTestKit<C>  { static of(cls, entityId, client?): kit; call(ref | name, input?): Promise<Materialised>; state; sequence; allEvents }
KeyValueTestKit<C>      { call → { newState, retention, reply, error }; state }
WorkflowTestKit<C>      { call; runStep(name, input?); runUntilEnd(): follows transitions, stops at a pause; state; currentStep }
ViewTestKit<C>          { onChange(key, event); onDelete(key); get(key): Row | null; rows }
ConsumerTestKit<C>      { onMessage(message, metadata?): Promise<ConsumerEffect>; produced }
TimedActionTestKit<C>   { invoke(ref | name, input?) }
EndpointTestKit<Ep>     { static of(cls, client?); get/post/put/delete/patch(path, body?, opts?): Promise<Response { status, contentType, text(), json(), body }>; matches literal before parameter }
AgentTestKit<A>         { static of(cls, sessionId, model: ScriptedModel); ask(ref | name, input); stream(...); history }
ScriptedModel           { expectText(text); expectToolCall(tool, argsJson); expectRefusal(); fails loudly when the script runs out }
```

Every input, event, state, row and reply is encoded and decoded through the component's codecs on
the way in and out (FR-019). Component calls from a unit-tested handler reach a `NoClient` that
throws naming the call, unless the test passes a stub `ComponentClient`.

**Integration** (Docker):

```
AnkkaTestKit { static start(service: ServiceBuilder, opts?: { image?: string; env?: Record<string,string> }): Promise<AnkkaTestKit>
               http: Http (get/post/put/delete against the sidecar's mapped HTTP port); client: ComponentClient (repointed at the mapped callback port)
               restart(): Promise<void>; startBeside(image, env?): Promise<Sidecar>; sidecarLogs(): Promise<string>; jdbcUrl: string
               stop(): Promise<void>; [Symbol.asyncDispose]() }
```

Startup order and what it guarantees are in the contract; the DDL comes out of the sidecar image
(`/opt/docker/ddl`), so a test can never pass against a schema the platform does not have.

## Package

`ankka` on npm, `version: "0.0.0"` in the tree, ESM only, `exports: { ".": …, "./testkit": … }`,
`engines.node: ">=22.22.0"` (R1), `files` naming `dist/` (which includes the generated
`_proto/`). The version the package reports in discovery is `VERSION` in `src/version.ts`, the one
placeholder the release job rewrites (R8).
