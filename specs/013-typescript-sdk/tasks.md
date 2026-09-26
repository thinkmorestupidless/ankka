# Tasks: TypeScript SDK

**Input**: Design documents from `/specs/013-typescript-sdk/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included. This repository's suites are the proof for every feature; the spec's success
criteria name what must be proven (the fixtures, the conformance suite, the journal in both
directions, the package from an empty directory); and the conformance suite already exists, so most
of this feature's tests are written before its code and the work is making them pass.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (an entity in TypeScript), US2 (tested without and with the sidecar), US3 (every
  component kind, proven compatible), US4 (installed from the registry, deployed to the platform),
  US5 (the paradigm, taught)

Paths are repository-relative. Abbreviations: `TS` = `sdks/typescript`; `SRC` = `sdks/typescript/src`;
`TST` = `sdks/typescript/test`; `EX` = `sdks/typescript/examples/shopping-cart`; `PY` =
`sdks/python/src/ankka` (the working example every SDK-side task mirrors); `SK` = `tools/docs/skill`.
The Python file a task mirrors is named where one exists; read it first, then write the TypeScript
that says the same thing in the language's idiom (plan, "Structure Decision").

---

## Phase 1: Setup — verify first, then the skeleton (research "verify at implementation" V1, V3, V4, V5)

**Purpose**: settle the assumptions the design rests on before building on them, and put the
project in place. Each spike's *answer* is the deliverable, recorded in research.md; its code is
throwaway unless a task below names it.

- [X] T001 Create `TS/package.json` exactly as `contracts/ci-and-release.md` gives it (name `ankka`, version `0.0.0`, `type: module`, `engines.node >=22.22.0`, `files: ["dist"]`, `exports` for `.` and `./testkit` with `types` first, `repository.directory`, the scripts table, the dependency sets), `TS/tsconfig.json` (`noEmit`, `module: nodenext`, `strict`, `erasableSyntaxOnly`, `verbatimModuleSyntax`, `allowImportingTsExtensions`, `rewriteRelativeImportExtensions`, `types: ["node"]`, includes `src`, `test`, `examples`, `bin`, `scripts`), `TS/tsconfig.build.json` (extends it; `noEmit: false`, `outDir: dist`, `declaration`, `rootDir: .`, includes `src` and `examples`), `TS/README.md` (what it is, the commands, a pointer to the docs), and `TS/.npmrc` with `engine-strict=true`; run `npm install` and commit `package-lock.json`.
- [X] T002 Add to `.gitignore`: `sdks/typescript/node_modules/`, `sdks/typescript/src/_proto/`, `sdks/typescript/dist/`, `sdks/typescript/dist-pack/`, under a `# TypeScript (the SDK under sdks/typescript)` heading beside the Python one.
- [X] T003 Write `TS/buf.gen.yaml` (v2; input `proto/src/main/protobuf`; plugin `local: protoc-gen-es`, `out: src/_proto`, `include_imports: true`, opts `target=ts`, `import_extension=ts`) and `TS/scripts/proto.ts` mirroring `sdks/python/scripts/proto.py`: copy `protocol/src/main/protobuf`, `protocol/fixtures`, `ENCODING.md` and `README.md` verbatim into `TS/proto/` (committed), delete and regenerate `SRC/_proto/` with `npx buf generate`, and write `SRC/version.ts` (`export const VERSION = "<package.json version>"`); run it and commit `TS/proto/`. Fix the stale `uv run proto` mention in `protocol/README.md` to name both SDKs' commands.
- [X] T004 Spike V1 in `TST/spike-connect-bidi.test.ts` (throwaway): `http2.createServer(connectNodeAdapter(...))` on `127.0.0.1:0` implementing `Discovery.Discover` (an empty `Spec`, `protocol_version "1.0"`, `sdk { name: "ankka-typescript" }`) and `EventSourced.Handle` as an async generator that logs each `EventSourcedIn` and answers every command with a `Reply` carrying the `command_id`, `Outcome.no_reply`; start `docker compose --profile polyglot up -d` with `ANKKA_PROCESS_ADDRESS=host.docker.internal:<port>` pointing at it, confirm the sidecar (grpc-java) completes discovery and, on `curl -X POST localhost:9000/...` for a route declared in a second run, opens the stream with `Init`; confirm a stream closed by the sidecar ends the generator, and a `docker compose restart sidecar` aborts it. Record V1's answer in [research.md](./research.md); if it fails in a way Connect will not fix, switch R2 to the fallback before T020. Delete the spike.
- [X] T005 [P] Spike V3 and V4 in `TST/spike-runner.test.ts` (throwaway) on Node 22.22 and 24 (`nvm`): `node --test 'test/**/*.test.ts'` collects only `.test.ts` and no `.d.ts` placed in `test/`; `await using` parses and runs on 24 and fails to parse on 22. Record both answers; V4's decides whether the docs show `await using` or `try`/`finally`. Delete the spike.
- [X] T006 [P] Spike V5 in the same setup: with a generated `_pb.ts` importing `./payload_pb.ts`, `tsc -p tsconfig.build.json` emits `dist/_proto/.../payload_pb.js` and rewrites the import to `.js`; `node dist/_proto/ankka/protocol/v1/discovery_pb.js` loads. Record the answer.

**Checkpoint**: `npm run proto`, `npm run typecheck` (on an empty `src/index.ts`) and `npm test`
(no tests) succeed; V1, V3, V4, V5 answered in research.md.

---

## Phase 2: Foundational — time, schema, codec, fixtures, the server skeleton, the client

**Purpose**: what every story needs. Blocks all stories. Everything here is the encoding and the
transport; nothing here knows about a component kind.

### Time values (research R4)

- [X] T007 [P] Write `SRC/time.ts`: `Instant` (epoch seconds `bigint` or `number` plus nanos; `now()`, `parse()` accepting 0–9 fractional digits and remembering how many, `fromDate()`, `toDate()`, `toString()` writing 0/3/6/9 digits as `ENCODING.md` says, `equals`, `compare`), `Duration` (`ofMillis`, `ofSeconds`, `ofNanos`, `parse` for ISO-8601 `PT…`, `toMillis`, `toString` producing `PT1.5S`-style output identical to `java.time.Duration.toString`), `LocalDate` and `LocalDateTime` (string-backed, `parse`, `toString`); and `TST/time.test.ts` covering each format in the fixtures (`record-instants-and-options.json`, `sum-type-with-instant.json`, `text-duration-millis.json`).

### Schema and codec (research R3, data-model.md "Shape declarations", "Codecs")

- [X] T008 Write `SRC/schema.ts`: the `Schema<T>` type (`kind`, `name`, fields/cases/values/inner), the `s` namespace (`string`, `int`, `long`, `double`, `boolean`, `instant`, `duration`, `localDate`, `localDateTime`, `bytes`, `unit`, `option`, `list`, `stringMap`, `record`, `sumType`, `enumeration`, `lazy`), `Done`/`done`, `Infer<S>` mapping every kind to its TypeScript type (a `sumType` to a discriminated union on `type`; `long` to `bigint`; `option` to `T | null`), the declaration-time validations from data-model.md (no name, a case or field named `type`, duplicate fields, empty enumeration), and `toJsonSchema(schema)` for agent tools (`additionalProperties: false`, `integer` for `int`/`long`, `string` with `format: date-time` for instants).
- [X] T009 Write `SRC/json.ts`: `writeJson(schema, value): string` — the schema-directed writer (fields in declaration order, every field written, `null` for absent options, `[]`, integers checked whole and within ±2⁵³ for `int`, `bigint` digits for `long`, `renderDouble` matching Scala's `Double.toString` as `PY/codec.py`'s `_scala_double` does, `Instant`/`Duration`/dates via `time.ts`, `bytes` as base64, sum types with `"type"` first, enumerations as `{"type":…}`); `readJson(schema, text): T` — `JSON.parse` with the source-access reviver (`typeof v === "number" && Number.isInteger(v) && !Number.isSafeInteger(v) ? BigInt(ctx.source) : v`) followed by the schema-directed decode (lenient: unknown fields ignored, absent optional → `null`, bare string accepted for an enumeration; strict: missing required field, unknown `type`, non-integer for `int`, refusing with a path like `items[2].quantity`). `TST/json.test.ts` for each rule, including `9007199254740993` both ways and `1.0` / `1.0E10`.
- [X] T010 Write `SRC/codec.ts`: `Codec<T>`, `ContentType`, `jsonCodec(schema, manifest?)`, the text codecs (`string`, `int`, `long`, `short`, `byte`, `double`, `float`, `boolean`, `duration-millis`, rendering `double` via `renderDouble`), the binary codecs (`done`, `unit`, `bytes`, `option[<inner>]`), `defaultCodecFor(schema)` and `codecForManifest(manifest)` exactly as data-model.md tables them; `TST/schema.test.ts` for `Infer` (compile-time, via `satisfies` and `@ts-expect-error`) and for the declaration-time refusals.
- [X] T011 Write `TST/encoding-fixtures.test.ts` mirroring `sdks/python/tests/test_encoding_fixtures.py`: one test per file in `TS/proto/fixtures` (falling back to `protocol/fixtures`), decode `bytes_base64` with `codecForManifest`/the declared JSON shapes and compare with `value`, re-encode and compare bytes; the JSON shapes redeclared with `s.*` from the Scala `EncodingShapes` (`modules/core/src/test/.../EncodingFixturesSuite.scala`); a fixture with no codec fails; a final test asserts every file was named. Make it pass — this is SC-001 and the riskiest task in the feature.

### Handlers, effects, context, materialisation (data-model.md "Components", "Effects")

- [X] T012 [P] Write `SRC/effects/common.ts` (`Outcome`, `Reply`, `NoReply`, `Fail`, `Retention`, `DeleteNow`, `ExpireAfter`, `ErrorCode` with `httpStatus`, `AnkkaError`, `CommandError`) mirroring `PY/effects/common.py`; and `SRC/handlers.ts`: `HandlerRef`, `RouteRef`, `ToolRef`, `GuardrailRef` types and the functions `command`, `query`, `step`, `action`, `stream`, `tool`, `guardrail` with the signatures the contract shows (`query`'s `run` constrained to `ReadOnlyEffect`; overloads with and without an input schema).
- [X] T013 [P] Write `SRC/context.ts`: `CommandContext` (`componentId`, `entityId`, `sequenceNumber`, `metadata`, `now()`), the `AsyncLocalStorage` holder for the HTTP `Request`, `Metadata` as `Record<string, string>` with `toProto`/`fromProto`; and `SRC/server/payloads.ts`: `encodePayload(codec, value)` / `decodePayload(codec, payload)` to and from the generated `Payload` message.
- [X] T014 Write `SRC/materialise.ts` with one exported function per kind (`materialiseEventSourced`, `materialiseKeyValue`, `materialiseWorkflowCommand`, `materialiseStep`, and pass-throughs for view/consumer/timed action/agent) that reduce an effect plus the current state into the `Materialised` shape the unit testkit exposes and the server encodes — the one place the reply is computed from the post-event state. Kinds land in later phases; write the event sourced one now and stub the rest with `TODO` throws that T043 removes.

### The service, the server skeleton, discovery, the client (research R2, R6; contracts/typescript-sdk.md)

- [X] T015 Write `SRC/service.ts` mirroring `PY/service.py`: `Ankka.service()`, `ServiceBuilder.register(cls)` constrained by per-kind static-shape types (`EventSourcedEntityClass`, …, `EndpointClass` requiring `acl`), the `Registry` collecting `Problem`s (duplicate ids, duplicate wire names, duplicate prefixes/route ids, missing statics, a `query` whose sample effect is not `kind: "read-only"`, a tool without description, settings naming an unknown step), `validate()` throwing once with all of them, `spec()` rendering `discovery.proto`'s `Spec` (components sorted by id, handlers by name, `sdk { name: "ankka-typescript", version: VERSION }`), `server()`, `listen({ host, port })` refusing a host other than `127.0.0.1`/`localhost`/`0.0.0.0` and reading `ANKKA_PROCESS_PORT`.
- [X] T016 Write `SRC/server/server.ts`: `http2.createServer(connectNodeAdapter({ routes, readMaxBytes, shutdownSignal }))`, `start()`/`stop()` (`shutdownSignal` aborts every handler; `close()` awaited), the routes registered from `SRC/server/*.ts` servicers; and `SRC/server/discovery.ts`: `Discover` returns the registry's `Spec`, `ReportError` logs and appends to the exported `problems: string[]`. `TST/server-stream.test.ts` begins here: an in-process Connect client (`createGrpcTransport` to the bound port) calls `Discover` and gets the `Spec`; binding `0.0.0.0` works, binding another interface is refused.
- [X] T017 Write `SRC/client.ts` mirroring `PY/client.py`: one lazily-opened `createGrpcTransport` at `ANKKA_SIDECAR_ADDRESS` (default `127.0.0.1:9011`, `pingIntervalMs` unset), `reconnect(address)`, `forEventSourcedEntity`/`forKeyValueEntity`/`forWorkflow`/`forAgent` → `Calls.call(name, input?, reply?)` → `Invocation.invoke()`/`.stream()`, `of(cls, key).call(handlerRef)`, `views.get/all/query`, `timers.schedule/cancel`, `withMetadata(md)` returning a scoped view sharing the transport, `CommandError` on `InvokeReply.error`; a `NoClient` for unit tests that throws naming the call.
- [X] T018 Write `SRC/index.ts`: first statement the Node floor check (`process.versions.node` against `22.22.0`, throwing an `Error` naming both), then the public exports (`Ankka`, `s`, `Infer`, `jsonCodec`, `Done`, `done`, every base class, every handler/route/tool function, `Acl`, `HttpProblem`, `ErrorCode`, `CommandError`, `Instant`, `Duration`, `VERSION`); and `SRC/testkit/index.ts` re-exporting `unit.ts` and `integration.ts` (created in US2; export what exists).

**Checkpoint**: `npm run typecheck` clean; `npm test` runs `time`, `json`, `schema`,
`encoding-fixtures` (all 24 fixtures, none skipped) and the discovery case of `server-stream`.

---

## Phase 3: User Story 1 — An entity in TypeScript (P1) 🎯 MVP

**Goal**: a TypeScript event sourced entity behind an HTTP endpoint, commanded through the local
sidecar, persisted, recovered and replayed; a persisting query refused by the compiler; the
conformance suite's `es.*`, `discovery.*` and `http.path-params-bind` behaviours green.

**Independent test**: quickstart tiers 1–2, tier 3's manual loop, and
`ANKKA_CONFORMANCE_ONLY='es.*|discovery.lists-every-component|discovery.read-only-flag|http.path-params-bind' npm run conformance`.

### The entity (mirror `PY/event_sourced_entity.py`, `PY/effects/event_sourced.py`)

- [X] T019 [P] [US1] Write `SRC/effects/eventSourced.ts`: `PersistEffect`, `ReadOnlyEffect` (branded by `kind`), `EventSourcedEffect` union, `PersistBuilder` (`deleteEntity`, `expireAfter`, `thenReply`, `thenReplyState`, `thenNoReply`), `EventSourcedEffects` factory (`persist`, `persistAll`, `reply`, `error`, `noReply`, `deleteEntity`); and `SRC/eventSourcedEntity.ts`: the abstract base (`state`, `entityId`, `context`, `effects`, `client` bound per call; abstract `emptyState`, `applyEvent`), the `EventSourcedEntityClass<S,E>` static-shape type (`componentId`, `state`, `events`, `handlers`, `snapshotEvery?`).
- [X] T020 [US1] Write `SRC/server/eventSourced.ts` implementing `EventSourced.Handle` as the async generator data-model.md specifies (init from snapshot or `emptyState`; replay via `applyEvent`; commands awaited in order; reply from the post-event state through `materialiseEventSourced`; snapshot when requested; unknown handler → `Failure` NOT_FOUND; thrown/rejected → `Failure` with state unchanged; state dropped on stream end); extend `TST/server-stream.test.ts` with the Python `test_server_stream.py` cases (init/replay/command/snapshot/failure, init from snapshot, strict ordering) and write `TST/stream-close.test.ts` mirroring `test_stream_close_spike.py`.
- [X] T021 [P] [US1] Write `TST/event-sourced-entity.test.ts` mirroring `test_event_sourced_entity.py` through the server (the unit testkit arrives in US2): persist and reply from the post-event state, refusal persists nothing, async handler, `noReply`, query read-only, retention, discovery flags; plus `TST/registration.test.ts`: a persisting `query` fails to compile (`// @ts-expect-error`), a class missing `state` fails at `register` (`@ts-expect-error`) and at runtime with a message naming the static, duplicate wire names and duplicate component ids reported together.

### The endpoint (mirror `PY/endpoint.py`, `PY/server.py` `HttpServicer`)

- [X] T022 [P] [US1] Write `SRC/routes.ts`: `Acl` (`allowAll`, `denyAll`, `authenticated`), `HttpProblem`, `Request<P>` (`params`, `query` with `get`/`getAll` in order, `headers`, `principal?`, `metadata`), the template literal type `ParamsOf<"/{a}/x/{b}">`, the route functions `get`/`post`/`put`/`del`/`patch`/`sse` with overloads (with/without body schema; optional `{ acl, params }` options), the declaration-time refusals (a `GET` with a body; a template that does not parse; a params schema naming a parameter not in the template); and `SRC/endpoint.ts`: the abstract base (`request` from the `AsyncLocalStorage`, `client`), the `EndpointClass` static-shape type requiring `prefix`, `acl`, `routes`.
- [X] T023 [US1] Write `SRC/server/http.ts`: `Http.Handle` and `Http.HandleStream` as data-model.md specifies (endpoint instances cached per id; `Request` built and stored; params parsed per schema; body decoded; return encoded with the reply schema, 200; `done`/`undefined` → 204; `HttpProblem` → status as `text/plain`; `CommandError` → `ErrorCode.httpStatus`; else `Failure`; SSE frames from the `AsyncIterable`, then `completed`/`failed`); the discovery rendering of endpoints and routes (route `acl` left unset when absent) in `service.ts`; `TST/endpoint.test.ts` mirroring `test_endpoint.py` through the servicer (path binding, literal before parameter is the sidecar's job but the route *ids* are unique, body/query/headers, SSE, 404 for an unknown route id, discovery, per-route ACL, required ACL refused at compile time and at registration).

### The example, the runner, the loop

- [X] T024 [P] [US1] Write `EX/domain.ts` (`LineItem`, `ShoppingCart`, `ShoppingCartEvent` as in the contract, field names as the Scala cart stores them), `EX/entity.ts` (`ShoppingCartEntity`: `add-item`, `remove-item`, `checkout`, `get-cart`, `total-quantity`; `snapshotEvery = 3` as the conformance reference requires), `EX/endpoint.ts` (`ShoppingCartEndpoint` at `/carts`: `POST /{cartId}/items`, `DELETE /{cartId}/items/{productId}`, `POST /{cartId}/checkout`, `GET /{cartId}`, `GET /awkward`; `GET /{cartId}/rows` added in US3) and `EX/main.ts` (`Ankka.service().register(...).listen()`), with `// docs:start`/`// docs:end` regions named `domain`, `entity`, `endpoint`, `main` for US5's includes.
- [X] T025 [US1] Write `EX/conformance.ts`'s first slice — `Conformance` entity (`record`, `record-many`, `refuse`, `no-reply`, `delete`, `expire` (1s), `count`, `misbehave`) and `ConformanceEndpoint` at `/conformance` with `POST /{id}/{handler}` (generic forwarder, body passed through as bytes), `GET /{id}/count`, `GET /echo`, `GET /status/{code}`, `GET /boom`, `GET /problems` (the discovery `problems` list) — and `referenceService()` registering the cart and these; `TS/bin/conformance.ts` mirroring `PY/_conformance.py`: serve `referenceService()` on `127.0.0.1:$ANKKA_PROCESS_PORT`, run `sbt -Dankka.conformance.target=127.0.0.1:9010 -Dankka.cluster.tests=off -Dankka.template.tests=off "sidecar/testOnly *ConformanceSuite [-- $ANKKA_CONFORMANCE_ONLY]"` from the repository root (`-Dankka.benchmarks=on` when `ANKKA_BENCHMARKS` is set), `main(): Promise<number>` and a one-line `process.exit` wrapper.
- [X] T026 [US1] Run the MVP loop by hand and record it: `docker compose --profile polyglot up -d`, `npm run example`, the three `curl`s from quickstart tier 3, `docker compose restart sidecar`, `GET /carts/c1` still answers; then `ANKKA_CONFORMANCE_ONLY='es.*|discovery.lists-every-component|discovery.read-only-flag|http.path-params-bind' npm run conformance` green (`es.journal-portable` inside it is US1's scenario 5). Fix what fails; add a `TST` case for each defect found.

**Checkpoint**: the MVP. Tiers 1–2 green; the manual loop works; the narrowed conformance run is
green including `es.journal-portable` and `es.snapshot-on-request`; a persisting query, an
unstated ACL and a missing codec are compile errors.

---

## Phase 4: User Story 2 — Tested without and with the sidecar (P2)

**Goal**: a unit testkit for every kind that exists so far and an integration testkit that runs the
real sidecar; the cart's own tests written with them; the journal proven portable from the SDK's
own runner.

**Independent test**: `npm test` (no Docker) and `npm run test:slow` (Docker) on the cart's tests;
`journal-portable.test.ts` both directions.

- [X] T027 [P] [US2] Write `SRC/testkit/unit.ts` mirroring `PY/testkit/unit.py`: `EventSourcedTestKit.of(cls, id, client?)` (`call(ref | name, input?)` → `Materialised` via `materialiseEventSourced`; `state`, `sequence`, `allEvents`; every input/event/state/reply round-tripped through the class's codecs), `EndpointTestKit.of(cls, client?)` (`get/post/put/delete/patch(path, body?, { query, headers, principal })` → `Response` with `status`, `contentType`, `text()`, `json()`; matching literals before parameters as `Router` does), and `NoClient`; kinds from US3 are added by T043. Rewrite `TST/event-sourced-entity.test.ts` and `TST/endpoint.test.ts` to use the kits where the Python tests do, keeping the servicer-level cases in `server-stream.test.ts`.
- [X] T028 [P] [US2] Write `SRC/testkit/integration.ts` mirroring `PY/testkit/integration.py` with testcontainers 12 (R7): `AnkkaTestKit.start(service, { image = process.env.ANKKA_SIDECAR_IMAGE ?? "ankka-sidecar:latest", env })` — a `Network`; DDL out of the image via `docker create`/`docker cp` of `/opt/docker/ddl` into a temp dir (mode 0755); `PostgreSqlContainer("postgres:17-alpine")` with database/user/password `ankka`, alias `postgres`, `withCopyDirectoriesToContainer` into `/docker-entrypoint-initdb.d`; the service's `server()` started on `0.0.0.0:0`; the sidecar `GenericContainer` with `ANKKA_PROCESS_ADDRESS=host.docker.internal:<port>`, `withExtraHosts([{ host: "host.docker.internal", ipAddress: "host-gateway" }])`, `ANKKA_SIDECAR_BIND=0.0.0.0`, `ANKKA_HTTP_PORT=9000`, the `ANKKA_DB_*` variables, ports 9000 and 9011, `Wait.forHttp("/_ankka/health", 9000).forStatusCode(200)`; `client.reconnect` to the mapped 9011; `http` (a small `fetch` wrapper); `restart()`; `startBeside(image, env)`; `sidecarLogs()`; `jdbcUrl`; `stop()` and `[Symbol.asyncDispose]`; a half-finished start stops what it started and throws with the sidecar's logs. Slow tests gate on `process.env.ANKKA_SLOW` with `test.skip` otherwise.
- [X] T029 [US2] Write `EX/cart.test.ts` mirroring `sdks/python/examples/shopping_cart/test_cart.py`: unit cases through the kits (with `// docs:start unit-test` for US5), and slow integration cases through `AnkkaTestKit` (every route, a restart, state surviving it; the other kinds' cases added by T044) with `// docs:start integration-test`.
- [X] T030 [US2] Write `TST/journal-portable.test.ts` mirroring `test_journal_portable.py` (slow): the TypeScript cart writes three items through `AnkkaTestKit`; `startBeside("sample-shopping-cart:latest")` reads `GET /carts/c1` with identical state; and the reverse. Record the image-tag rule (CI sets `ANKKA_SIDECAR_IMAGE`/the sample tag to what the same sbt session built; `:latest` is the laptop default) in the test's header comment.

**Checkpoint**: `npm test` green with no Docker; `npm run test:slow` green with the images from
`sbt sidecar/docker:publishLocal shoppingCart/docker:publishLocal`; SC-003's journal proof holds
from the SDK's runner as well as from the conformance suite.

---

## Phase 5: User Story 3 — Every component kind, proven compatible (P3)

**Goal**: key value entities, views, consumers, timed actions, workflows with two slots, agents with
tools and guardrails, the full client, a unit kit per kind, the complete reference service, and
`npm run conformance` green on all 49 behaviours.

**Independent test**: quickstart tier 4, then break `refuse` and see `es.refusal-persists-nothing`
named alone.

### Kinds (mirror `PY/key_value_entity.py`, `view.py`, `consumer.py`, `timed_action.py`, `workflow.py`, `agent.py` and `PY/effects/*`)

- [X] T031 [P] [US3] Write `SRC/effects/keyValue.ts`, `SRC/keyValueEntity.ts` (`updateState` builder, `deleteEntity`, `reply`, `error`, `noReply`; `KeyValueEntityClass`), `materialiseKeyValue` in `SRC/materialise.ts`, and `SRC/server/keyValue.ts` (`KeyValue.Handle`, the same generator shape as event sourced without a fold).
- [X] T032 [P] [US3] Write `SRC/effects/view.ts`, `SRC/effects/consumer.ts`, `SRC/effects/timedAction.ts`, and `SRC/view.ts`, `SRC/consumer.ts`, `SRC/timedAction.ts` (static `source` as a component class or `topic`; `row`/`message`/`out` codecs; `producesTo`; `actions` table; `ViewClass`/`ConsumerClass`/`TimedActionClass`), plus `SRC/server/stateless.ts` for `View.Handle`, `Consumer.Handle`, `TimedAction.Invoke` (a new instance per request; `deleted = true` handled via `onDelete`; `ce-subject` and `ankka.sequence` read from metadata; a thrown handler → `Failure`/`fail`).
- [X] T033 [P] [US3] Write `SRC/effects/workflow.ts` (`WorkflowEffect`, `WorkflowReadOnlyEffect`, `StepEffect`, `StepRef`, `Pause`, the two builders `effects` and `stepEffects`, `workflowSettings`, `Recovery`, `StepSettings`), `SRC/workflow.ts` (`steps` table via `step(name, input?, run)`; `WorkflowClass`; settings validated against declared steps at registration), `materialiseWorkflowCommand`/`materialiseStep`, and `SRC/server/workflow.ts` with the two slots and one outbound queue exactly as data-model.md specifies (a command mid-step answered from the pre-step state; a second step refused; the step task cancelled on stream end; `WorkflowDetail.settings` rendered in discovery).
- [X] T034 [P] [US3] Write `SRC/effects/agent.ts` (immutable `AgentEffect` with the builder methods and `thenReply`/`thenReplyJson`; `error`), `SRC/agent.ts` (`role`, `maxToolCallSteps`, `tools` via `tool(name, description, inputSchema, run)` with `toJsonSchema`, `guardrails` via `guardrail(name, check)`, `handlers` with `command`/`stream`; `sessionId` captured on the effect at plan time; a plan naming an undeclared tool or guardrail refused; a tool with no description refused at registration), and `SRC/server/agent.ts` (`Plan` → `AgentPlan`; `InvokeTool` decoding `arguments_json` with the tool's schema and returning `ok` text or JSON-stringified value, an exception → `error`; `CheckGuardrail` → `pass`/`block`).
- [X] T035 [US3] Complete `SRC/client.ts`: `stream()` over `InvokeStream`, `views.get/all/query` decoding the rows array with the row schema, `timers.schedule` accepting a `HandlerRef` or `{ kind, componentId, name }` with optional `entityId`, `cancel`; the per-request scoping wired into every servicer so a handler's `this.client` carries the request's metadata (R6).

### Unit kits, tests, examples

- [X] T036 [US3] Extend `SRC/testkit/unit.ts` with `KeyValueTestKit`, `WorkflowTestKit` (`call`, `runStep`, `runUntilEnd` following transitions and stopping at a pause; `currentStep`), `ViewTestKit`, `ConsumerTestKit`, `TimedActionTestKit`, `AgentTestKit` with `ScriptedModel` (`expectText`, `expectToolCall`, `expectRefusal`; input guardrails → tool loop with a step bound → output guardrails → memory history; fails loudly when the script runs out), each round-tripping through the class's codecs; remove the `TODO` throws in `materialise.ts`.
- [X] T037 [P] [US3] Write `TST/other-kinds.test.ts` mirroring `test_other_kinds.py` (KV; workflow transitions, compensation, pause, settings validation, a command mid-step from the pre-step state through the servicer; view; consumer with `deleted`; timed action; discovery of every kind) and `TST/agent.test.ts` mirroring `test_agent.py` (tool JSON Schema, plan/tool/reply, tool error fed back, guardrails, script exhaustion, undeclared tool refused).
- [X] T038 [P] [US3] Write `EX/cartRows.ts` (`CartRows` view over `ShoppingCartEntity`, query `by-id`, row tombstoned on checkout), `EX/checkoutNotifier.ts` (consumer over the cart), `EX/checkoutLog.ts` (a `Reminder`-style timed action and a key value `Profile` if the sample wants one), `EX/checkoutWorkflow.ts` (`checkout`: `start`, `status`; steps `reserve` calling `shopping-cart/total-quantity` through `this.client.of(...)`, `wait` (1.5s pause then `charge`), `charge` (declined on `fail`), `compensate`; settings with `failoverTo: "compensate"`), `EX/assistant.ts` (`assistant`: `ask`, `stream`; tool `lookup` calling `conformance/count`; guardrail `no-secrets`), each with a `// docs:start` region; add `GET /{cartId}/rows` to `EX/endpoint.ts` and register everything in `EX/main.ts`.
- [X] T039 [US3] Complete `EX/conformance.ts` to the full reference in `specs/009-polyglot-runtimes/contracts/conformance.md`: `profile` (KV: `set`, `get`, `delete`), `checkout` workflow, `cart-rows`, `checkout-recorder` (consumer recording checkouts to `conformance` via the client), `reminder` (`remind` invoking `conformance/record`), `assistant`; the `conformance` endpoint's remaining routes (`POST /profile/{id}`, `GET /profile/{id}`, `POST /checkout/{id}`, `GET /checkout/{id}`, `POST /remind/{id}`, `POST /ask/{session}`, `GET /stream/{session}` SSE with a frame carrying a leading space and one a newline); `PrivateEndpoint` at `/private` with `Acl.authenticated`. Compare wire names and routes line by line against `sidecar/src/test/.../ConformanceReference.scala` and `sdks/python/examples/shopping_cart/conformance.py`.
- [X] T040 [US3] `npm run conformance` on all 49 behaviours until green; then the deliberate break (make `refuse` persist an event) and confirm `es.refusal-persists-nothing` alone fails; revert. Extend `EX/cart.test.ts`'s slow cases to every kind and a scripted-model agent turn (`env: { ANKKA_MODEL_SCRIPT: … }` as the Python test does). Record V8's answer (the idle callback channel) with an integration case that waits past the idle timeout or a documented reconnect.

**Checkpoint**: SC-002 holds; `npm test`, `npm run test:slow` and `npm run conformance` green;
every kind has a unit kit and an example.

---

## Phase 6: User Story 4 — Installed from the registry, deployed to the platform (P4)

**Goal**: the package as npm receives it, proven from an empty directory on both Node lines; the
sample image and descriptor deployed to the local installation; CI and release jobs in place; the
one-time manual publish written down.

**Independent test**: quickstart tiers 5 and 6; the `sdk-typescript` CI job green on a push.

- [X] T041 [P] [US4] Make `npm run build` emit `dist/` (V5 confirmed): `tsconfig.build.json` includes `src` and `examples`; `dist/_proto/` present; `dist/testkit/index.d.ts` present; run the tier-5 smoke by hand on Node 24 and 22.22 (`nvm`), and on Node 20 confirm the import throws the floor message (FR-018). Fix the `exports`/`types` until `import 'ankka'` and `import 'ankka/testkit'` both resolve from the empty directory.
- [X] T042 [P] [US4] Write `EX/Dockerfile` (`node:24-slim`; copy manifests, `proto/`, `scripts/`, `src/`, `examples/`, `tsconfig*.json`; `npm ci && npm run proto && npm run build`; `ENV ANKKA_PROCESS_PORT=9010`; `CMD ["node", "dist/examples/shopping-cart/main.js"]`) and `EX/service.json` (`{"name": "cart", "service": {"image": "sample-shopping-cart-typescript:latest", "hosting": "process", "protocol": "1.0"}}`); build it, run it beside the compose sidecar with `ANKKA_PROCESS_ADDRESS` pointed at the container, `curl` a cart.
- [X] T043 [US4] Add the `sdk-typescript` job to `.github/workflows/ci.yml` exactly as `contracts/ci-and-release.md` gives it (matrix 22 and 24; protocol and fixtures `diff -r`; `npm ci`, `proto`, `typecheck`, `test`; build, pack, install into an empty directory, import; on 24 only: build the sidecar and cart images, `test:slow`, `conformance`), with the header comment explaining, as the Python job's does, why the images are built here. Push the branch and make the job green.
- [X] T044 [US4] Add the `sdk-typescript` job to `.github/workflows/release.yml` exactly as the contract gives it (`needs: publish`; environment `npm`; `id-token: write`; Node 24; `npm version` from the tag; proto, build, pack; the tarball smoke asserting `VERSION`; `npm view` guard; `npm publish --access public`), with the header comment recording that the first publish was manual and why (R8). Create the `npm` environment on the repository.
- [ ] T045 [US4] Deploy to the local installation by hand (quickstart tier 6): `deploy-local.sh`, `docker build` and `kind load` the sample image, `ankka services apply` its descriptor, `services get` shows `Ready` and (V7) the SDK's name and version from discovery, a `curl` through the gateway, `scale 3`, `restart`; record the outcome and `git diff --stat main -- sidecar modules operator controlplane controlplane-api crd protocol` being empty in `research.md` (FR-033, SC-006).
- [X] T046 [US4] Write the Publishing section additions in `CLAUDE.md`: the TypeScript SDK ships through npm as `ankka`; `version` in `package.json` is `0.0.0` like the other placeholders; the first publish is by hand after the tag's `publish` job (the exact commands from `contracts/ci-and-release.md`) because npm's trusted publishing cannot create a package, and every later tag publishes via OIDC; where the trusted publisher is configured.

**Checkpoint**: SC-006 and the packaging half of SC-007 hold (the registry half is the first tag);
CI green on both Node lines.

---

## Phase 7: User Story 5 — The paradigm, taught (P5)

**Goal**: a getting-started page and a reference page for TypeScript, a TypeScript skill, TypeScript
sections beside every Python one, every language list updated, every sample an include from tested
code, `docs check` clean.

**Independent test**: `just docs-sync && just docs` clean; the timed SC-004 run; each shared skill
that mentions Python mentions TypeScript.

- [X] T047 [P] [US5] Write `docs/get-started/first-service-typescript.md` with the section structure of `first-service-python.md` (create the project with `npm init`, `npm install ankka@<next version>`; an entity; an endpoint; run it — `docker compose --profile polyglot up -d` then `node main.ts`; test it without the sidecar; test it through the real sidecar; watch it in the local console; next), every code block an `<!-- include: sdks/typescript/examples/shopping-cart/...#region -->` from T024/T029's regions, frontmatter `kind: tutorial`, the version pinned to the release this ships in.
- [X] T048 [P] [US5] Write `docs/reference/typescript-sdk.md` with `reference/python-sdk.md`'s sections (installing; the shape every component shares; event sourced entity; key value entity; view; consumer; workflow; timed action and timers; agent; HTTP endpoint; calling components; running a service; testing — `node --test`, "vitest works"; developing the SDK — `npm run proto/typecheck/test/test:slow/conformance`), plus one section Python does not need: "Shapes and types" (`s.*`, `Infer`, `int` vs `double`, `long` as `bigint`, `Instant`, `option` as `null`), every sample included from `EX` or `TST`.
- [X] T049 [P] [US5] Write `SK/ankka-typescript/SKILL.md` on the model of `SK/ankka-python/SKILL.md` (frontmatter description naming TypeScript, Node, npm, the schema builder, the static handler table, the sidecar; `pages:` listing `concepts/polyglot.md`, `get-started/first-service-typescript.md`, `reference/typescript-sdk.md`, `reference/sidecar-protocol.md`, `build/serialization.md`, `build/testing.md`, `deploy/deploy-a-service.md`; rules: shapes declared once, wire names in the handler table, no decorators/enums/parameter properties, `acl` required, `this.client`, `.ts` imports, `node main.ts`); run `just docs-sync` so the committed copies under `marketplace/plugins/ankka/skills/` and `ankka.g8/src/main/g8/.claude/skills/` (with `$` escaped) are regenerated.
- [X] T050 [P] [US5] Add a "TypeScript differences" section to `SK/ankka-agents/SKILL.md` beside "Python differences", and TypeScript mentions beside each Python one in `SK/ankka-entities/SKILL.md` (rules 1, 4, 5, 8, 10; `snapshotEvery`; the testkits), `SK/ankka-endpoints/SKILL.md` (rules on `acl` and "the process never binds a port"), `SK/ankka-deploy/SKILL.md` (the descriptor checklist), and the descriptions of `SK/ankka-workflows` and `SK/ankka-views-consumers` where they name languages.
- [X] T051 [P] [US5] Update every language list: `mkdocs.yml` `site_description` and `nav` (both new pages under Get started and Reference), `docs/index.md`, `docs/concepts/polyglot.md` ("One journal, two languages" → three; "What makes an SDK compatible" names both SDKs), `docs/contributing/language-sdks.md` (two working examples; `npm run proto` beside `scripts/proto.py`; the fixture test's TypeScript twin; `npm run conformance`), `docs/get-started/install.md` (an "Install the TypeScript SDK" section after the Python one: Node 24, `npm install ankka@<version>`, the testkit's optional peers), `docs/deploy/images.md` (a Node image beside the Python one), `docs/reference/limitations.md` if it lists SDK languages, `README.md`, and `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/mcp/AnkkaTools.scala` (the two strings: "Scala, Python or TypeScript"; run `sbt cli/test` — `CliReferenceSuite` may need `-Dankka.docs.update=true` if a description is generated into a page).
- [X] T052 [US5] `just docs-sync && just docs` until clean (nav, skill `pages:`, includes current, no positional references or internal history on the new pages); update `docker-compose.yml`'s profile comment to name both SDKs' run commands; update `CLAUDE.md`'s Commands (`cd sdks/typescript && npm ci && npm run proto && npm test && npm run conformance`), the module map ("sdks/typescript" beside python), and the Documentation section's skill list.
- [X] T053 [US5] The SC-004 run: on a machine with Node 24 and Docker and no JVM on `PATH`, follow `first-service-typescript.md` from `mkdir` to the first `curl` answering; record the minutes in research.md; fix the page where the reader stumbled.

**Checkpoint**: SC-004 and SC-008 hold; `docs check` clean; every shared skill that speaks of Python
speaks of TypeScript.

---

## Phase 8: Polish and cross-cutting

- [X] T054 [P] Record the measurements quickstart names (`npm test` and `test:slow` durations; one command through the TypeScript cart versus the Python cart through the same sidecar, both measured as feature 009's SC-003 was) in `research.md`'s end section and `TS/README.md`.
- [X] T055 [P] Add to `CLAUDE.md`'s traps every lesson this feature paid for (candidates already known: `import_extension=ts` with `rewriteRelativeImportExtensions` so sources run and `dist/` resolves; `files` overrides `.gitignore` for packing; type stripping refuses enums, decorators and parameter properties, so a component gets `this.client` from its base class; the source-access reviver for longs; `withCopyDirectoriesToContainer` versus the 0700 bind mount; Connect needs `http2.createServer` for bidi; npm cannot create a package by OIDC) — one bullet each, only those that actually bit.
- [X] T056 Close the "verify at implementation" table in `specs/013-typescript-sdk/research.md`: every row V1–V8 answered with what settled it; move any that changed a decision into the relevant R-section.
- [X] T057 Run the reviewer's checklist in [quickstart.md](./quickstart.md) top to bottom, including the `grep` for forbidden syntax and the empty `git diff --stat` on platform directories; `sbt -Dankka.cluster.tests=off test` still green (SC-009); `sbt 'sidecar/testOnly *ConformanceSuite'` (in-process Scala) still green.
- [X] T058 Format and commit: `npm run typecheck` clean, `sbt scalafmtCheckAll` (for `AnkkaTools.scala`), the branch rebased on `main`, ready for the release tag that carries the SDK and the one-time manual publish.

---

## Dependencies

- **Phase 1 → Phase 2 → every story.** T004 (V1) gates T020: if the spike fails, R2's fallback
  changes `server.ts` and `client.ts` before any servicer is written. T011 (fixtures) gates every
  story: nothing may cross the protocol through a codec that has not passed them.
- **US1 (Phase 3)** needs only Phase 2. It is the MVP and proves the transport, the codec and the
  entity conversation against the real sidecar.
- **US2 (Phase 4)** needs US1's entity and endpoint to have something to test. Its unit kits for the
  other kinds arrive with those kinds (T036), and its scenario 5 (a workflow with a pause under the
  kit) is completed by T033 + T036.
- **US3 (Phase 5)** needs US1 and US2 (the kits are how each kind is tested as it lands). Tasks
  T031–T034 are independent of each other and can run in parallel; T035 depends on them; T036–T039
  depend on T031–T035; T040 depends on all.
- **US4 (Phase 6)** needs US3 for the conformance run in CI (T043) and the full sample image (T042);
  T041 can start after Phase 2.
- **US5 (Phase 7)** needs US1–US3 for the regions its pages include, and US4 for the install
  instructions and version pin. T047–T051 are independent of each other.
- **Phase 8** needs everything.

## Parallel execution examples

```text
Phase 1:  T004 | T005 | T006                (three spikes, three throwaway files)
Phase 2:  T007 | T012 | T013                 then T008 → T009 → T010 → T011 (the codec chain), T014–T018
Phase 3:  T019 | T022 | T024                 then T020 → T021, T023 → T025 → T026
Phase 4:  T027 | T028                        then T029 → T030
Phase 5:  T031 | T032 | T033 | T034          then T035 → T036 → (T037 | T038) → T039 → T040
Phase 6:  T041 | T042                        then T043 → T044 → T045 → T046
Phase 7:  T047 | T048 | T049 | T050 | T051   then T052 → T053
Phase 8:  T054 | T055                        then T056 → T057 → T058
```

## Implementation strategy

**MVP first (US1)**: Phases 1–3. At the checkpoint a TypeScript entity is commanded through the real
sidecar, its journal is readable by the Scala cart and vice versa (inside `es.journal-portable`), the
fixtures pass, and the compiler refuses a persisting query. That is enough to show a Node developer
and to decide whether the API reads right *before* the other six kinds copy its shape — the cheapest
moment to change a naming decision.

**Then the testkits (US2)**, because every later kind is tested with them and because the audience
learns the paradigm by seeing effects as values in a test.

**Then the kinds and the suite (US3)**, four kinds in parallel, the reference completed, and the
platform's own definition of compatible turned green. **Then packaging and deployment (US4)**, which
proves "a project, not a platform change" end to end. **Then the docs (US5)**, last because they
include code the earlier stories wrote, and because the timed run is the only honest measure of
SC-004.

Stop at any checkpoint and the work delivered stands on its own. Commit after each task or logical
group, on this branch; the release that carries the SDK is a tag on `main` after the branch merges,
followed by the one manual publish (T046) that lets every later tag publish itself.
