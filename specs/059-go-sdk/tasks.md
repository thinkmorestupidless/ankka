# Tasks: Go SDK

**Input**: Design documents from `/specs/059-go-sdk/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included. This repository's suites are the proof for every feature; the spec's success
criteria name what must be proven (the fixtures, the conformance suite, the journal in both
directions, the module from an empty directory); and the conformance suite already exists, so most
of this feature's tests exist before its code and the work is making them pass.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel: different files, no dependency on an incomplete task
- **[Story]**: US1 (an entity in Go), US2 (tested without and with the sidecar), US3 (every
  component kind and every behaviour, proven compatible), US4 (fetched from the registry, started from
  the template, deployed), US5 (the paradigm, taught in Go)

Paths are repository-relative. Abbreviations: `GO` = `sdks/go`; `TK` = `sdks/go/testkit`; `SRV` =
`sdks/go/internal/server`; `EX` = `sdks/go/examples/shopping-cart`; `TS` = `sdks/typescript/src` and
`PY` = `sdks/python/src/ankka` (the working examples every SDK-side task mirrors); `SK` =
`tools/docs/skill`. Where a task names a TypeScript or Python file to mirror, read it first, then write
the Go that says the same thing in the language's idiom (plan, "Structure Decision"; contract,
`contracts/go-sdk.md`). The module path is `github.com/thinkmorestupidless/ankka-go`, imported as
`ankka`.

---

## Phase 1: Setup: verify first, then the skeleton (research V1, V3, V5)

**Purpose**: settle the assumptions the design rests on before building on them, and put the module
in place. Each spike's *answer* is the deliverable, recorded in research.md; its code is throwaway
unless a task below names it.

- [X] T001 Create `GO/go.mod` (`module github.com/thinkmorestupidless/ankka-go`, `go 1.26`, requires grpc v1.84.x, protobuf v1.36.x, testcontainers-go v0.44.x and its `modules/postgres`), `GO/tools/go.mod` (a nested module whose `tool` directives pin `github.com/bufbuild/buf/cmd/buf`, `google.golang.org/protobuf/cmd/protoc-gen-go`, `google.golang.org/grpc/cmd/protoc-gen-go-grpc`), `GO/go.work` (`use . ./tools ./examples/shopping-cart`), `GO/LICENSE` (a copy of the repository's), `GO/README.md` (what it is, the commands table from `contracts/go-sdk.md`, a pointer to the docs), `GO/ankka.go` with the package doc; `go mod tidy` in each module and record V3's answer (testcontainers-go's own `go` directive is at most 1.26) in [research.md](./research.md). `.gitignore` is not touched: Go writes no build output into the tree.
- [X] T002 Write `GO/buf.gen.yaml` (v2; input `protocol/src/main/protobuf`; plugins `protoc-gen-go` and `protoc-gen-go-grpc` with `paths=source_relative`, `out: internal/pb`) and `GO/scripts/proto.sh` mirroring `sdks/rust/scripts/proto.sh`: copy `protocol/src/main/protobuf`, `protocol/fixtures`, `protocol/ENCODING.md`, `protocol/README.md` verbatim into `GO/protocol/`, delete and regenerate `GO/internal/pb/` with `cd tools && go tool buf generate ..` (or the equivalent that runs buf from the tools module against the parent), and with `--check` regenerate into a temporary directory and `diff -r` it against `GO/internal/pb/`, exiting 1 on a difference. Run it; commit `GO/protocol/` and `GO/internal/pb/`. Name the Go command in `protocol/README.md` beside the other SDKs'.
- [X] T003 Spike V1 in `GO/spike_test.go` (throwaway): a grpc-go server on `127.0.0.1:0` implementing `Discovery.Discover` (an empty `Spec`, `protocol_version "1.22"`, `sdk {name "ankka-go"}`) and `EventSourced.Handle` as a bidi handler that logs each `EventSourcedIn` and answers every command with a `Reply` carrying its `command_id` and `no_reply`; start `docker compose --profile polyglot up -d` with `ANKKA_PROCESS_ADDRESS=host.docker.internal:<port>`, confirm the sidecar (grpc-java) completes discovery, opens the stream with `Init` on the first request to a route declared in a second run, that a stream the sidecar closes returns from `Recv` with `io.EOF`, and that `docker compose restart sidecar` cancels the stream's context. Record V1's answer in research.md; delete the spike.
- [X] T004 [P] Spike V5 in `GO/spike_version_test.go` (throwaway): a program in a temporary module that `require`s the SDK at a fake version through a `replace`, and one built from a local module proxy (`GOPROXY=file://...`) serving `v0.13.0` of a copy of `GO/`; confirm `debug.ReadBuildInfo()` reports `Replace != nil` for the first and `Version == "v0.13.0"` for the second. Record the answer; delete the spike.
- [X] T005 [P] Generate `GO/testdata/doubles.txt` (V7): a one-off Scala script in the scratchpad printing `Double.toString` for about 200 values (powers of ten from 1e-10 to 1e25, their neighbours, `0.1`, `1.0/3`, `-0.0`, `Double.MinPositiveValue`, `Double.MaxValue`, random doubles from a fixed seed) as `bits<TAB>text` lines, committed under `GO/testdata/` with a header line naming the script that made it. The JVM is the oracle; the Go renderer is tested against this file in T008.

---

## Phase 2: Foundational: time, codec, fixtures, effects, the server skeleton, the client

**Purpose**: everything every story stands on. Nothing crosses the protocol until the fixtures pass.

### Time values (research R4)

- [X] T006 [P] Write `GO/times.go`: rendering and parsing of `time.Time` as the encoding's instant (UTC, `Z`, 0/3/6/9 fractional digits, monotonic reading dropped), `time.Duration` as ISO-8601 (`PT1.5S`; a date part refused on read, naming the value) and as `duration-millis` text at the top level, and the string-backed `LocalDate` and `LocalDateTime` with `Parse`, `String` and `Time()`; `GO/times_test.go` with the fixture values and the edge cases.

### Codec (research R3; data-model "Type plans", "Case and value registrations")

- [X] T007 Write `GO/plan.go`: the reflection walk that builds a plan for a type once (field order, names from `json` tag or lower-camel field name, `json:"-"` excluded, kinds per the data model's table, nested plans, pointers as optional, slices, string-keyed maps, interfaces through a registered `SumType`, named strings through an `Enumeration`), returning a `Problem` for every refusal in the data model's list, naming the type and field. `GO/cases.go`: `Cases`, `Case`, `Enumeration`, `SumType`, `Enum`, and the duplicate-registration problems.
- [X] T008 Write `GO/doubles.go`: the Scala `Double.toString` renderer (shortest round-trip digits via `strconv.FormatFloat(f, 'e', -1, 64)` re-laid as Scala does: plain decimal with at least one fractional digit between 10⁻³ and 10⁷, else `d.dddE±n`; `-0.0`; `NaN` and infinities refused) and `GO/doubles_test.go` asserting every line of `GO/testdata/doubles.txt` (T005).
- [X] T009 Write `GO/json_write.go` (the writer: a plan and a value to bytes in declared order, every field written, `null` for nil pointers, `[]` for nil slices, map keys sorted, strings escaped as jsoniter does, doubles through T008, times through T006) and `GO/json_read.go` (the reader: a tokenizer over `encoding/json.Decoder` with `UseNumber`; a missing non-pointer field, a fraction where an integer is declared, an unknown `type`, an unregistered case refused with the path; an unknown field ignored; a bare string accepted for an enumeration field).
- [X] T010 Write `GO/codec.go`: `Codec[T]` with `Manifest`, `ContentType`, `Encode`, `Decode`; `CodecFor[T]()` selecting the text codec for a top-level scalar, `bytes` for `[]byte`, `done` for `Done`, `option[...]` for a top-level pointer, `application/json` with the type's name otherwise; `CustomCodec[T]`; the `Manifest(...)` option; `Personal[T]` (the erasure envelope, as `TS/json.ts` and `fixtures/erasure`); `GO/contract.go` with the RFC 8785 fingerprint (mirror `TS/contract.ts`). Depends on T006 to T009.
- [X] T011 Write `GO/fixtures_test.go`: for every file under `GO/protocol/fixtures/**` (38 today), build the plan the fixture's manifest and content type select from a Go type declared in the test for that fixture, decode `bytes_base64`, compare with the value, encode and compare byte for byte; both directions; 0 skipped; a fixture with no declared type fails naming it. Plus the map-of-several-keys ordering case and the registration-refusal cases of the data model's list, each asserting the field named. **The gate for every later phase.**

### Effects, context, materialisation (data-model "Effects", "Request scope")

- [X] T012 [P] Write `GO/effects.go`: every effect kind as values with the builders of the data model's table (`Persist(...).ThenReply/ThenReplyState/ThenNoReply/DeleteEntity/ExpireAfter`, `Reply`, `NoReply`, `Refuse` as an effect of every kind, `UpdateState`, `TransitionTo`, `StepEffects.*`, `UpdateRow/DeleteRow/Ignore`, `Produce/Done/Ignore`, the agent effect chain), `ReadOnlyEffect[S, R]` a distinct type no persisting builder returns, `ErrorCode` with its HTTP status; `GO/errors.go` (`RefusalError`, `ProtocolTooOldError`, `HttpProblem`); `GO/effects_test.go` asserting each builder builds a value and performs nothing.
- [X] T013 [P] Write `GO/context.go`: `Request`, `Caller`, `CommandScope`, `TimerScope` and the `RequestFrom`, `CallerFrom`, `CommandFrom`, `TimerFrom`, `ClientFrom`, `StorageFrom`, `ConfigFrom` accessors over `context.Context` keys, plus the `with*` setters the server uses; a test that a derived context cancelled ends a wait inside a handler.
- [X] T014 Write `GO/materialise.go` (mirror `TS/materialise.ts`): one reducer per kind turning an effect and the current state into the protocol's `Reply` parts (events encoded, new state, retention, outcome, snapshot when asked), used by the server and the test kits alike; `GO/materialise_test.go` with a case per outcome. Depends on T010, T012.

### The service, the server skeleton, discovery, the client (research R2, R6, R8; contracts/go-sdk.md)

- [X] T015 Write `GO/service.go` and `GO/spec.go`: `NewService`, `Problems()`, `Spec()` building the protocol's `Spec` from the registered declarations with `protocol_version "1.22"` and `sdk {name "ankka-go", version Version()}`, `Start(ctx, opts...)` (refusing with every problem; `Host` loopback or `0.0.0.0` only; `Port` from `ANKKA_PROCESS_PORT`; `SidecarAddress` from `ANKKA_SIDECAR_ADDRESS`), `Serve(listener)`, `SIGTERM`/`SIGINT` into `GracefulStop` with a deadline then `Stop`; the gate table (FR-018: oldest unmet reason first) applied to the sidecar's version at `Discover`; `ReportError` logging each problem once through `log/slog`. `GO/version.go`: `Version()` from `debug.ReadBuildInfo()` (the `ankka-go` dependency's version without `v`; `0.0.0` for a `Replace` or the main module), per V5.
- [X] T016 Write `SRV/discovery.go` and `SRV/server.go`: the grpc-go server registering every servicer the declarations need, the `Discovery` servicer, the `recover()` wrapper every handler call goes through (a panic into that request's `Failure`, state unchanged), the per-stream reply queue (one sender per stream), and the request-context plumbing from T013. `SRV/payloads.go`: protocol payload to and from `Codec` (manifest, content type, bytes). `GO/service_test.go`: `Start` refuses a duplicate id, a duplicate wire name, a shared prefix and a `nil` ACL in one refusal naming all four; refuses a host that is neither loopback nor any-address; `Spec()` carries the version and the SDK name; a sidecar at `1.7` is refused naming `1.8` and the call-to-service reason when a route uses `CallService`.
- [X] T017 Write `GO/client.go` (one lazy channel to the sidecar address; per-call metadata from the handler's context so the trace nests; `UNIMPLEMENTED` into `ProtocolTooOldError`; a refusal status into `RefusalError`), `GO/services.go` (`CallService`, 1.8), `GO/secrets.go`, `GO/clientkeys.go` (1.20), `GO/timers.go` (`Schedule`, `ScheduleRecurring` 1.12, `Cancel`), the typed generics `Invoke[R]`, `InvokeStream` (an `iter.Seq2`), `SendAndAwait[R]` (1.18), `QueryView[Row]`, `QueryStream`, `Watch` (1.17); `GO/client_test.go` against a fake `Client` servicer in-process asserting the wire of each call and the error mapping.

**Checkpoint**: fixtures green both ways; a service with no components starts, is discovered by the compose sidecar, and refuses what it must.

---

## Phase 3: User Story 1: An entity in Go (P1) 🎯 MVP

**Goal**: the shopping cart's entity and endpoint in Go, commanded through the real sidecar, durable
across a sidecar restart, in the same journal records the Scala cart writes.

**Independent test**: quickstart tiers 1 and 3's cart cases; `ANKKA_CONFORMANCE_ONLY='*es.*'` and
`'*discovery.*'` against the partial reference.

### The entity (mirror `TS/eventSourcedEntity.ts`, `TS/server/eventSourced.ts`)

- [X] T018 [US1] Write `GO/eventsourced.go`: `EventSourced[S, E](id, events, empty, apply, handlers...)` with `SnapshotEvery`, `StateCodec`, `Replicated()`; `Command[S, E, I, R]` and `Query[S, E, I, R]` (the latter typed on `ReadOnlyEffect`); the declaration's problems (unrenderable state, event or reply types through T007; duplicate wire names; a query whose effect kind is not read-only at registration).
- [X] T019 [US1] Write `SRV/eventsourced.go`: the `EventSourced.Handle` conversation of the data model's "Server state" row (fold snapshot and events before the first command; one command at a time; reply from the post-event state through T014; snapshot exactly when asked; `CommandFrom` on the context with entity id, sequence, installation and at-home; release on stream end; an error or panic a `Failure`). `GO/eventsourced_test.go` through an in-process gRPC client: init, replay of three events, a command, a snapshot request honoured, a refusal persisting nothing, a panic recovered with the state unchanged, a stream ended by the client releasing the instance.
- [X] T020 [US1] Write `GO/compilefail_test.go`: `//go:build ignore` files under `GO/testdata/compilefail/` (a query handler returning a persisting `Effect`; an endpoint constructed without the ACL argument) that the test compiles with `go vet` through `os/exec` and asserts fail naming the type error; and a positive twin that compiles. This is SC-005's compile-time half.

### The endpoint (mirror `TS/endpoint.ts`, `TS/server/http.ts`)

- [X] T021 [US1] Write `GO/endpoint.go` and `GO/acl.go`: `NewEndpoint(prefix, acl, routes...)` with `Issuers(...)`, `Get/Post/Put/Delete/Patch[B, R]` with `.ACL(acl)`, path templates with `{param}`, `AllowAll`, `DenyAll`, `AllowIf`, `Authenticate(Issuer(...))`, `AnyOf`; the problems (a `nil` ACL naming the endpoint, a shared prefix, an authenticated route with no issuer, a body or reply type the codec refuses). `SRV/http.go`: `Http.Handle` (decode the body by its codec, run the route with `RequestFrom` populated, encode the reply, `HttpProblem` to a status, an error to 500 with the message logged). `GO/endpoint_test.go`: a route round trip, `Param` and `Query`, a problem's status, the principal's claims by name.

### The example, the runner, the loop

- [X] T022 [US1] Write `EX/go.mod` (module `github.com/thinkmorestupidless/ankka-go/examples/shopping-cart`, `replace github.com/thinkmorestupidless/ankka-go => ../..`), `EX/cart/domain.go` (`LineItem`, `Cart`, the `CartEvent` cases with `// docs:start` markers), `EX/cart/entity.go` (`shopping-cart`: `add-item`, `remove-item`, `checkout`, `discard`, `get-cart`, `total-quantity`, snapshot every 3, the same wire names and manifests as `samples/shopping-cart`'s), `EX/cart/api.go` (`/carts` routes), `EX/cmd/cart/main.go`, `EX/service.json` (`{"name":"cart","service":{"image":"sample-shopping-cart-go:latest","hosting":"process","protocol":"1.22","provisionObjectStorage":true,"buckets":[{"name":"photos","exposure":"public"}]}}`; `protocol` is "the sidecar protocol version the image's SDK speaks" per `controlplane-api`'s `descriptors.scala`, so `1.22` is right even though the TypeScript example still says `1.0`; the storage parts used from T040 on), `EX/.dockerignore`, `EX/README.md`.
- [X] T023 [US1] Write `EX/Dockerfile` and `EX/scripts/image.sh`. The script stages `sdks/go` and the example into one temporary build context (so the example's `replace => ../..` resolves inside the build) and runs `docker build -t sample-shopping-cart-go:latest` there; it is the one way the example image is built (the slow image test, the kind deploy and CI all call it). The Dockerfile: stage one `golang:1.27-alpine` with `CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /cart ./cmd/cart`; stage two `gcr.io/distroless/static-debian12:nonroot`, `COPY --from=build /cart /cart`, `ENV ANKKA_PROCESS_PORT=9010`, `USER nonroot`, `ENTRYPOINT ["/cart"]`. The template's Dockerfile (T044) is the same two stages with no script, since it depends on the released module. Build it and run it under `docker compose --profile polyglot` by hand once.
- [X] T024 [US1] Write `EX/conformance/` skeleton and `EX/cmd/conformance/main.go` (mirror `sdks/typescript/bin/conformance.ts`): serve the cart plus the reference's `conformance` entity (`record`, `record-many`, `refuse`, `no-reply`, `delete`, `expire`, `misbehave`, `count`) and the `/conformance` routes those cases use on `127.0.0.1:$ANKKA_PROCESS_PORT` with `ANKKA_STORAGE_BACKEND=memory`, then run `sbt -Dankka.conformance.target=127.0.0.1:<port> -Dankka.cluster.tests=off -Dankka.template.tests=off "sidecar/testOnly *ConformanceSuite[ -- $ANKKA_CONFORMANCE_ONLY]"` from the repository root, `main` a one-line wrapper over `run() int`. Run `ANKKA_CONFORMANCE_ONLY='*es.*' go run ./cmd/conformance` and `'*discovery.*'` until green; record the pass count in quickstart.md's measurements.
- [ ] T025 [US1] Run the US1 loop by hand and fix what it finds: `docker compose --profile polyglot up -d`, `go run ./cmd/cart` in `EX`, `curl` add-item, `docker compose restart sidecar`, read the cart back; then `sbt shoppingCart/docker:publishLocal` and the Scala cart started against the same Postgres reading the Go cart's journal and the reverse (the portability test proper is T029; this is the first manual proof). Record the first `es.journal-portable` pass.

**Checkpoint**: an entity written in Go is commanded, persisted, recovered and replayed; the Scala cart reads its journal; the compiler refuses a persisting query.

---

## Phase 4: User Story 2: Tested without and with the sidecar (P2)

**Goal**: the component test kits and the integration kit under `go test`, no third-party runner.

**Independent test**: `go test ./...` with Docker stopped passes the cart's unit tests; `ANKKA_SLOW=1 go test ./examples/...` starts the sidecar, exercises every route, restarts the sidecar and reads the state back.

- [ ] T026 [P] [US2] Write `TK/unit.go` and `TK/kinds.go` (mirror `TS/testkit/unit.ts`, `kinds.ts`): `EventSourced(component)`, `KeyValue`, `Workflow` (`RunStep`, `Pending()`, following transitions to a pause), `View` (a change from any declared source including a workflow standing), `Consumer` (`Published()` with keys; `Elements()` for a graph consumer), `TimedAction`, `Endpoint`, `Agent` against `TK/scripted_model.go` (`ScriptedModel(turns...)`, failing when the script runs out); every value round-tripped through the component's codecs via T014; a `Result` with `Reply()`, `Events()`, `State()`, `Refusal()`, `Failure()`. `TK/slow.go`: `Slow(t)` skipping unless `ANKKA_SLOW=1` (and under `-short`). `GO/testkit_unit_test.go`: the kits for the entity and endpoint of US1 (the other kinds' cases land with the kinds in T037), an unrenderable field failing in the kit naming it.
- [ ] T027 [P] [US2] Write `TK/ddl.go` (the sidecar image's `/opt/docker/ddl` copied out through testcontainers' Docker client: create a container from the image without starting it, `CopyFromContainer`, untar into a temporary directory, chmod 0755/0644), `TK/postgres.go` (`postgres.Run` with `postgres:17-alpine`, db/user/password `ankka`, `WithInitScripts` of those files, on a network of the kit's own), `TK/sidecar.go` (the sidecar container with `ANKKA_PROCESS_ADDRESS=host.docker.internal:<port>`, `ANKKA_SIDECAR_BIND=0.0.0.0`, `ANKKA_HTTP_PORT=9000`, `ANKKA_DB_*`, a random `ANKKA_SECRET_KEY`, the test's `Env`, `host.docker.internal:host-gateway`, exposing 9000 and 9011, waiting on `/_ankka/health` 200; the image from `ANKKA_SIDECAR_IMAGE`, else `ankka-sidecar:latest` at version `0.0.0`, else `ghcr.io/thinkmorestupidless/ankka-sidecar:<version>`).
- [ ] T028 [US2] Write `TK/integration.go`, `TK/http.go`, `TK/beside.go`: `Start(ctx, service, opts...)` serving the service on `0.0.0.0:0`, starting Postgres then the sidecar, the `Kit` with `HTTP` (`Get`, `Post`, `GetJSON[T]`, raw access), `Client` (a `*ankka.Client` dialled to the kit's sidecar), `Storage`, `Restart(ctx)` (replace the sidecar on the same Postgres and wait ready again), `StartBeside(ctx, image, env)` (another image on the kit's network and database), `Stop(ctx)` cleaning up also when startup failed; `StartT(t, ...)` registering `Stop` with `t.Cleanup`. Depends on T027.
- [ ] T029 [US2] Write `EX/cart_test.go` (unit, through T026: add, remove, checkout, a refusal, the fold) and `EX/cart_slow_test.go` (`Slow(t)`; through T028: every `/carts` route, `Restart` and the cart read back, `StartBeside("sample-shopping-cart:latest", ...)` reading what Go wrote and the Go cart reading what the Scala cart wrote, asserting 0 differences in `get-cart`). Run both under `go test` and `ANKKA_SLOW=1 go test`; record the times in quickstart.md.
- [ ] T030 [US2] Write `GO/testkit_integration_test.go`: the kit is ready before the test body runs (a request succeeds on the first line), `Restart` keeps state, `Stop` after a failed start leaves no container (assert through the Docker client), and the image choice rule for each of the three cases (unit, by reading the chosen reference without starting it).

**Checkpoint**: `go test ./...` green without Docker; `ANKKA_SLOW=1 go test ./...` green with it; the workflow-pause and graph-consumer kit scenarios are completed in Phase 5 with their kinds.

---

## Phase 5: User Story 3: Every component kind and every behaviour, proven compatible (P3)

**Goal**: every kind, every effect, the storage client, the full reference service, the conformance suite green, and the Go rows in the living features backed by the suite.

**Independent test**: `go run ./cmd/conformance` passes every case; one behaviour broken on purpose is named and no other.

### Kinds (mirror `TS/keyValueEntity.ts`, `workflow.ts`, `view.ts`, `keyedView.ts`, `consumer.ts`, `timedAction.ts`, `agent.ts`, `autonomous.ts`, `socket.ts`, `mcp-endpoint.ts`, `graph.ts` and `TS/server/*`)

- [ ] T031 [P] [US3] Write `GO/keyvalue.go` and `SRV/keyvalue.go`: `KeyValue[S]`, `Update`, `Query` with `KeyValueEffect`, the `KeyValue.Handle` conversation; `GO/keyvalue_test.go` through an in-process client (set, get, delete, recovery from state).
- [ ] T032 [P] [US3] Write `GO/workflow.go` and `SRV/workflow.go`: `Workflow[S]`, `Steps`, `Step`, `Settings` (`StepTimeout`, `Timeout`, `Recovery`, per-step overrides), commands returning `WorkflowEffect`, `StepEffect` outcomes (transition, pause with timeout, end, fail); the conversation with one command and one step in flight, a step on a fresh instance in its own goroutine replying through the stream's one channel, a mid-step command from the pre-step state, a second step refused; `GO/workflow_test.go` for each (the mid-step query and the refused second step especially).
- [ ] T033 [P] [US3] Write `GO/view.go`, `GO/keyedview.go`, `GO/consumer.go`, `GO/topic.go`, `GO/graph.go`, `SRV/stateless.go`: `View[Row]` with `OnChange` per source (`Changes(entity)`, `Topic(name, StartFrom(...))`, `WorkflowSource`), `Queries` (declared, `Recursive`, `Watched`), `Version(n)`; `KeyedView[Row]` with `keyOf`; `Consumer[Msg]` with `ProducesTo`, `StartFrom`, `Project` (a grant, 1.15), `Produce` of one or several with keys and metadata; `GraphConsumer` publishing `ankka.graph-delta.v1` deltas; the `View.Handle` and `Consumer.Handle` unary servicers; a topic consumer without a start position a problem; `GO/view_test.go` and `GO/consumer_test.go`.
- [ ] T034 [P] [US3] Write `GO/timedaction.go` and the `TimedAction.Invoke` servicer in `SRV/stateless.go`: `TimedAction(id, actions...)`, `Action[I]`, `TimerFrom(ctx)` with the due time; `GO/timedaction_test.go`.
- [ ] T035 [P] [US3] Write `GO/agent.go`, `GO/autonomous.go`, `SRV/agent.go`: `Agent(id, ...)` with `Ask`, `AskStream`, `Tools(Tool, ApprovedTool)`, `Guardrails`, `McpServers`, `MaxToolCallSteps`; the tool input struct to JSON Schema from the plan with `desc` tags (FR-014; a tool without a description a problem); `Agent.Plan`, `InvokeTool`, `CheckGuardrail`, `CheckTaskResult` servicers; `Autonomous(id, ...)` as feature 015's model; `GO/agent_test.go` (schema derivation, a tool run with typed arguments, a guardrail consulted, a tool error reported to the model not thrown).
- [ ] T036 [P] [US3] Write `GO/socket.go`, `GO/sse.go`, `GO/mcp.go`, `SRV/mcp.go` and the `HandleStream`/`HandleSocket` halves of `SRV/http.go`: `SSE(path, func(ctx, *Request) iter.Seq2[string, error])`, `Socket(path, func(ctx, *Socket) error)` with `Send`, `Receive`, closed when the handler returns, the handler's context ending with the socket; `NewMcpEndpoint(path, acl, tools...)` with `Brokered()`, `McpTool[I]`, `McpCommandRef`, `McpQueryRef`, `McpRouteRef`, the `Mcp.InvokeTool` servicer running a tool with typed arguments and the principal; the sockets (1.9) and MCP (1.20) gates in T015's table exercised; `GO/socket_test.go`, `GO/mcp_test.go`.
- [ ] T037 [US3] Extend `TK/kinds.go` and `GO/testkit_unit_test.go` for every kind from T031 to T036: the workflow kit following transitions to a pause, the view kit taking a workflow standing, the consumer kit's `Published()` keys and the graph consumer's `Elements()`, the agent kit against the scripted model, the MCP tool kit. Depends on T031 to T036.

### The storage client (research R11; mirror `TS/storage.ts`, `TS/sigv4.ts`, `TS/server/storageEndpoint.ts`)

- [ ] T038 [P] [US3] Write `GO/sigv4.go` (header-signed and presigned requests over `net/http`, `crypto/hmac`, `crypto/sha256`; test vectors from the TypeScript suite), `GO/storage.go` (the `Storage` interface: `Put`, `Get`, `Stat`, `List`, `Delete`, `SignedURL`, `PublicURL`; the object-name refusals the `storage/` fixture states; `ANKKA_STORAGE_BACKEND` and the choice from the `ANKKA_S3_*` variables, named and supplied buckets), `GO/storage_s3.go`, `GO/storage_dir.go` (`ANKKA_STORAGE_DIR`, default `.ankka/storage`), `GO/storage_mem.go`, `GO/storage_endpoint.go` (the `/_ankka/storage/<bucket|_default>/<name>` GET and PUT routes to depth 16 with `read` and `keep` tokens, declared only to a sidecar at 1.21 or later, endpoint id `ankka-storage`); `GO/storage_test.go` against a fake S3 on loopback, the directory and memory stores, and the name refusals.

### The reference, the suite, the features

- [ ] T039 [US3] Complete `EX/conformance/` to the data model's "Reference service" list: `profile`, `checkout` workflow, `cart-rows`, `checkout-rows`, `tree-rows` (recursive `under`, watched `all-rows`), `topic-rows`, `joined-rows` (keyed, `of-right`), `replicated-vehicle`, `tree-node`, `joined-left`, `joined-right`, the seven consumers, `cart-graph`, `profile-graph`, `reminder`, `assistant`, `approver`, `answerer`, the endpoints `/carts`, `/joined`, `/vehicles`, `/tree`, `/conformance` (every route `ConformanceReference.scala` declares), `/autonomous`, `/callers`, `/private`, `/mcp-keys`, and `/mcp` with `get_cart`, `add_item`, `checkout`. Same ids, wire names, manifests and route shapes as the Scala reference; read `sidecar/src/test/scala/.../conformance/ConformanceReference.scala` section by section. Depends on T031 to T038.
- [ ] T040 [US3] Run `go run ./cmd/conformance` in `EX` to green: every case passing, the skips only those the suite excludes for a process target (compare the skip list with the TypeScript run's). Then break `remove-item` of an absent product to reply instead of refuse and confirm exactly its case goes red; revert. Record passed/skipped/failed and the time in quickstart.md. Add the storage routes and the cart's `photos` bucket to `EX/cart_slow_test.go` against the memory and directory stores.
- [ ] T041 [US3] Run `just features` to 0 findings, then write the table FR-031 needs into `.claude/rules/sdk-go.md` (created here, completed in T056): one row per Go `Examples` row the spec added (41 feature files), with the feature file, the scenario name and the Go test or conformance case that backs it (a `go test` name, a conformance case id, or the Scala suite that `assume`s the row away naming `sdks/go`, as `AwaitSteps.languageRow` does). An empty cell fails the task: add the test, never move the row to the limitations list. The quickstart's reviewer checklist reads this table.

**Checkpoint**: the conformance suite is green against the Go reference; every kind has a kit; every Go row in the features has a test behind it.

---

## Phase 6: User Story 4: Fetched from the registry, started from the template, deployed (P4)

**Goal**: `go get` from the proxy at a release's version; `ankka init --language go`; a deployment on kind that reaches `Ready` and names its SDK; CI and release jobs.

**Independent test**: quickstart tiers 5 and 6; the release job's smoke on the first tag.

- [ ] T042 [P] [US4] Write `GO/scripts/pack-smoke.sh` (the pack smoke of `contracts/ci-and-release.md` step 8: copy `GO` to a temporary mirror, `GOFLAGS=-mod=mod go build ./... && go vet ./...` there; an empty module with a `replace` to it, a `main` importing `ankka` printing `Version()` and a `_test.go` importing `ankka/testkit`; `go build`, `go test`, and `go list -deps .` of the `main` must name no `testcontainers` package; prints `0.0.0`). Run it on `go1.26.10` and `go1.27.3` through `GOTOOLCHAIN`; record V4 (the dependency graph) in research.md.
- [ ] T043 [P] [US4] Write `EX/image_slow_test.go`: run `scripts/image.sh` (T023) through `os/exec`, create a container without starting it, `CopyFromContainer` of `/`, and assert the filesystem holds `/cart`, CA certificates, no `/bin/sh`, no package manager, no `go` toolchain (the installing scenario "a service's image holds its one executable and no language runtime"); record the image size in quickstart.md.
- [ ] T044 [US4] Add the template: `cli/src/main/templates/go/` with `go.mod` (`module {{module}}`, `go 1.26`, `require github.com/thinkmorestupidless/ankka-go v{{ankka_version}}`), `main.go`, `domain.go`, `item_entity.go`, `item_rows.go`, `api.go`, `item_test.go` (component kits), `integration_test.go` (`testkit.StartT`, `Slow`), `Dockerfile` (the two stages of T023 without the replace), `.dockerignore`, `.gitignore`, `README.md`, `service.json` (`"hosting": "process"`, `"protocol": "{{protocol_version}}"`), `.github/workflows/ci.yml` and `deploy.yml` (the TypeScript template's with `actions/setup-go@v6`); `index.txt` listing them. Mirror `cli/src/main/templates/typescript/` file for file.
- [ ] T045 [US4] Wire the language into the CLI: `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/Init.scala` (`Language.Go("go")`, `parse` accepting `go` and `golang`, the error message listing `go`, `problems` refusing `--package` and `--template` as for TypeScript), `Scaffold.scala`'s comment, `Main.scala` (help strings and the Go next steps: `go vet ./...`, `go test ./...`, `ANKKA_SLOW=1 go test ./...`, `docker compose up -d && go run .`), `build.sbt` (`"go"` in the template resource generator's `Seq`), `cli/src/main/scala/.../mcp/AnkkaTools.scala` (the two descriptions), `cli/native-smoke.sh` (`go` in the loop), `InitSuite.scala` (the negative case names a language nobody will add, say `cobol`: one of the three language-enumerating tests FR-041 carves out); `just docs-reference` for `docs/reference/cli.md`.
- [ ] T046 [US4] Write `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/GoTemplateSuite.scala` extending `PolyglotTemplateSuite(Language.Go, "go")`: assert the pin `v$Version` in `go.mod`, append `replace github.com/thinkmorestupidless/ankka-go => <checkout>/sdks/go`, run `go vet ./...` then `ANKKA_SLOW=1 go test -count=1 -v ./...`, assert the output has no `--- SKIP` and ends in `ok`; register `go` in `TemplateSwitch.scala` if it lists languages. Run `sbt -Dankka.cluster.tests=off -Dankka.template.tests=go 'cli/testOnly *GoTemplateSuite'` green.
- [ ] T047 [US4] Add the CI job to `.github/workflows/ci.yml` as `contracts/ci-and-release.md` gives it: the `sdk-go` filter and output in `changes`, the `sdk-go` job with the Go 1.26 and 1.27 matrix (fast steps on both; sbt images, slow tests, conformance and the template suite on 1.27 only), `defaults.run.working-directory: sdks/go`, module cache keyed on `sdks/go/**/go.sum`; the header comment; `.github/ci-coverage.py` claiming `sdks/go/**`, `cli/src/main/templates/go/**` and the suite. Copy every action's version (`checkout`, `setup-go`, `setup-java`, `setup-sbt`) from the `sdk-typescript` job, not from the contract. Run `python3 .github/ci-coverage.py` locally; push the branch and confirm the job runs and is green on both lines.
- [ ] T048 [US4] Add the release job to `.github/workflows/release.yml` as the contract gives it: `sdk-go` after `publish`; `persist-credentials: false`; the `git ls-remote --tags` guard; `git subtree split --prefix sdks/go -b ankka-go`; force-push `ankka-go:main`; `git tag` without `-f` and `git push` of the tag without `--force`; the proxy smoke (`go get ...@${GITHUB_REF_NAME}` with six attempts a minute apart, build and run a program printing `Version()`, assert it prints `$version`); name the Go SDK in the comment at line 121. Dry-run the split and the guard locally against a scratch bare repository in the scratchpad. Record in `CLAUDE.md`'s publishing notes (via `.claude/rules/build-and-release.md`) that `thinkmorestupidless/ankka-go` must exist with `TEMPLATE_REPO_TOKEN` push access before the first tag, and that a Go tag is never moved; a broken version gets a `retract`.
- [ ] T049 [US4] Deploy to the local installation (quickstart tier 6): `kind load docker-image sample-shopping-cart-go:latest`, `ankka services apply -f EX/service.json`, `Ready`, `services get` showing `sdk ankka-go 0.0.0` (V6), expose and `curl`, `scale`, `restart` one instance at a time with a request loop refusing nothing (V8), `pause` and `resume`, a trace attributing handler time to the component, `kubectl delete pod` of the app container and the process restarted in place. Record V6 and V8 in research.md. Confirm `git diff --stat -- sidecar modules operator controlplane controlplane-api crd protocol` is empty (FR-041).

**Checkpoint**: the module builds from a mirror copy on both Go lines with no Docker library in a service's graph; the template suite is green; CI's `sdk-go` is green; the release job is dry-run; the cart runs on kind.

---

## Phase 7: User Story 5: The paradigm, taught in Go (P5)

**Goal**: two pages, one skill, five differences sections, every language list, `docs check` clean.

**Independent test**: `just docs-sync && just docs` with 0 problems; the SC-004 timed run from the getting-started page.

- [ ] T050 [P] [US5] Write `GO/examples/first-service/` (what the getting-started page walks through: a project as `ankka init` would write it, with `// docs:start` markers on the entity, the endpoint, the unit test and the integration test) and `docs/get-started/first-service-go.md` with the TypeScript page's sections (Create the project; An entity; An endpoint; Run it; Test it without the sidecar; Test it through the real sidecar; Watch it in the local console; Next), every Go block an include, `languages: [go]` in the front matter; time the run from an empty directory to the first `curl` and record it in quickstart.md (SC-004).
- [ ] T051 [P] [US5] Write `docs/reference/go-sdk.md` with `docs/reference/typescript-sdk.md`'s sections: installing and the toolchain floor, the module and its two packages, values and codecs (the type table, `Cases`, `Enumeration`, tags, custom codecs, what is refused), every component kind with an included sample from `EX`, effects, the request context and goroutines, the client, secrets, client keys, timers, calls to other services, the storage client, the test kits, `Version()` and the development build, the image, the limitations. Pin the next release's version where the TypeScript page pinned its first.
- [ ] T052 [P] [US5] Write `SK/ankka-go/SKILL.md` mirroring `SK/ankka-typescript/SKILL.md` (frontmatter name, description ≤ 1024 characters, `pages:` naming the Go pages and the shared ones; body: Rules, Development loop, Mistakes to check for, Calling another service), and add a `## Go differences` section beside each `## Rust differences` in `SK/ankka-entities`, `SK/ankka-endpoints`, `SK/ankka-workflows`, `SK/ankka-views-consumers`, `SK/ankka-agents`; the routing row in `SK/ankka`, the command row in `SK/ankka-inspect`, the hosting line in `SK/ankka-deploy`, the list in `SK/ankka-port`, and every skill description listing the languages. Then `just docs-sync` to regenerate `marketplace/plugins/ankka/skills/` and `ankka.g8/src/main/g8/.claude/skills/`.
- [ ] T053 [P] [US5] Add `go` to `mkdocs.yml` (`extra.docs.facets.languages`, the nav entry "Your first service in Go", the `site_description`, `site-links.files` for `sdks/go/README.md`) and `go` to `languages:` on every page that carries `typescript` and `rust` and now shows a Go sample (list the pages touched in the commit message); add Go samples to those pages where the other languages' tabs exist (entities, endpoints, workflows, views, consumers, timers, agents, secrets, storage client, sockets, MCP endpoints, service calls), each an include from `EX`.
- [ ] T054 [P] [US5] Update the language lists: `docs/index.md`, `docs/concepts/polyglot.md` ("One journal, five languages"), `docs/contributing/language-sdks.md` (a fourth worked example), `docs/get-started/install.md` ("Install the Go SDK"), `docs/deploy/images.md`, `README.md` (badge row, prose, `ankka init` line, first-service links), `CLAUDE.md` (rules table row for `sdk-go.md`, the Go commands line, the template-suite line, the CI job list), `homebrew/README.md`, `marketplace/README.md`, `GLOSSARY.md` line 3069's language list, `McpEndpointsDocumentationSuite.scala`'s regex `(scala|python|typescript|rust|go)`, `ModelProvidersDocumentationSuite.scala`'s heading "In a Python, TypeScript, Rust or Go service" and the docs page it checks (these two suites and `InitSuite` are the only files under the platform's directories this feature touches: the carve-out in FR-041, the word `go` and nothing else).
- [ ] T055 [US5] Update `docs/reference/limitations.md`: every bullet naming Python and TypeScript (lines 110, 113, 187, 259, 385, 386, 398, 423, 449) names Go; "Four languages" (456) becomes five; the TypeScript autonomous-agent kit limitation (454) gains a Go counterpart only if T037 shows it holds. Depends on T037.
- [ ] T056 [US5] Write `.claude/rules/sdk-go.md` (`paths: sdks/go/**, cli/src/main/templates/go/**`): the module layout, the commands, the committed generated code and `scripts/proto.sh --check`, the one-module decision and V4, the version rule, the mirror and the never-moved tag, and every trap the implementation met, dated. Then `just docs-sync && just docs` to 0 problems (SC-009) and `just features` to 0 findings (SC-010). Depends on T050 to T055.

**Checkpoint**: the docs build; a coding agent with the `ankka-go` skill is told how Go differs; the getting-started run is timed.

---

## Phase 8: Polish and cross-cutting

- [ ] T057 [P] `gofmt -l .` and `go vet ./...` clean across the workspace; `golangci-lint` is not introduced (no new tool in CI beyond the contract's steps).
- [ ] T058 [P] Review every `Problem` message and every refusal for the path and name it carries (FR-005, FR-013): one test per refusal asserts the field or wire name appears in the message.
- [ ] T059 Reconcile the spec: fill the measurements table in quickstart.md; update research.md's V1 to V8 rows with their answers; note in `spec.md`'s Assumptions anything that changed (the floor, the image base, the one-module decision).
- [ ] T060 Run the full verification once, awake: `sbt -Dankka.cluster.tests=off test` (SC-011: every existing test passes, and `git diff --stat` on the platform's directories names only the three carved-out suites), the four other SDKs' end-to-end commands from `CLAUDE.md` (SC-011's second half, the suite and fixtures untouched), `cd sdks/go && go test ./... && ANKKA_SLOW=1 go test ./... && (cd examples/shopping-cart && go run ./cmd/conformance)`, `sbt -Dankka.template.tests=go 'cli/testOnly *GoTemplateSuite'`, `just docs`, `just features`; `gh workflow run ci --ref 059-go-sdk` and `gh workflow run cluster --ref 059-go-sdk -f suite=<the sidecar's k3s suite>` green. Work through quickstart.md's reviewer's checklist and tick each item in the commit message.
- [ ] T061 Open the pull request as a draft with the plan's summary, the quickstart measurements and the one action the owner must take (create `thinkmorestupidless/ankka-go`, grant the token); mark it ready only when every check is green.

---

## Dependencies

- **Phase 1 → Phase 2 → every story.** T003 (V1) gates T016 and T019: if grpc-go against grpc-java misbehaves in a way the design cannot absorb, R2 is revisited before any servicer is written. T011 (fixtures) gates every story: nothing crosses the protocol through a codec that has not passed them. T004 (V5) gates T015's `Version()`.
- **US1 (Phase 3)** needs only Phase 2. It is the MVP and proves the transport, the codec and the entity conversation against the real sidecar. T018 → T019; T021 independent of them; T022 needs T018 and T021; T023 needs T022; T024 needs T019 and T021; T025 needs T023 and T024.
- **US2 (Phase 4)** needs US1's entity and endpoint to have something to test. T026 and T027 are independent; T028 needs T027; T029 needs T026 and T028; T030 needs T028. Its workflow-pause and graph-consumer scenarios are completed by T037.
- **US3 (Phase 5)** needs US1 and US2. T031 to T036 and T038 are independent of each other; T037 needs T031 to T036; T039 needs T031 to T038; T040 needs T039; T041 needs T040.
- **US4 (Phase 6)** needs US3 for the conformance step of the CI job (T047) and the full reference image (T043, T049); T042 and T044 to T046 can start after Phase 4. T045 before T046; T046 and T042 before T047; T047 before T048; T049 last.
- **US5 (Phase 7)** needs US1 to US3 for the regions its pages include, and US4 for the install instructions and the version pin. T050 to T054 are independent of each other; T055 needs T037; T056 needs T041 (the table it completes) and T050 to T055.
- **Phase 8** needs everything.

## Parallel execution examples

```text
Phase 1:  T001 → T002 → (T003 | T004 | T005)
Phase 2:  T006 | T012 | T013                 then T007 → T008 → T009 → T010 → T011 (the codec chain), T014 → T015 → T016 → T017
Phase 3:  T018 | T021                        then T019 | T020 → T022 → T023 | T024 → T025
Phase 4:  T026 | T027                        then T028 → T029 | T030
Phase 5:  T031 | T032 | T033 | T034 | T035 | T036 | T038   then T037 → T039 → T040 → T041
Phase 6:  T042 | T043 | T044                 then T045 → T046 → T047 → T048 → T049
Phase 7:  T050 | T051 | T052 | T053 | T054   then T055 → T056
Phase 8:  T057 | T058                        then T059 → T060 → T061
```

## Implementation strategy

**MVP first (US1)**: Phases 1 to 3. At the checkpoint a Go entity is commanded through the real
sidecar, its journal is readable by the Scala cart and vice versa, the fixtures pass, and the
compiler refuses a persisting query. That is enough to show a Go developer and to decide whether the
API reads right *before* the other kinds copy its shape, which is the cheapest moment to change a
naming decision (generic constructors versus methods, option functions versus a struct).

**Then the test kits (US2)**, because every later kind is tested with them and because the audience
learns the paradigm by seeing effects as values in a test.

**Then the kinds and the suite (US3)**, six kinds and the storage client in parallel, the reference
completed, and the platform's own definition of compatible turned green. **Then packaging, the
template, CI, the release job and deployment (US4)**, which proves "a project, not a platform
change" end to end. **Then the docs (US5)**, last because they include code the earlier stories
wrote, and because the timed run is the only honest measure of SC-004.

Stop at any checkpoint and the work delivered stands on its own. Commit after each task or logical
group, on this branch; the release that carries the SDK is a tag on `main` after the branch merges.
Unlike npm and crates.io, nothing is published by hand: the first tag goes through the `sdk-go` job
like every later one, once the mirror repository exists and the token can push to it.
