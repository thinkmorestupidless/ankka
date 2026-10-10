# Implementation Plan: Streaming View Queries — Rows a Part at a Time, and a Query Kept Open

**Branch**: `047-streaming-view-queries` (implementation on `047-streaming-view-queries-impl`) |
**Date**: 2026-10-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/047-streaming-view-queries/spec.md`, clarified 2026-10-10.

## Summary

A view's query gains a streaming twin — `allStream`, `whereStream`, `orderedStream`, `askStream` —
that holds one r2dbc statement open under a portal and yields each row as the database does, with no
limit and the connection's life bound to the stream's (research R1). A declared query may be declared
**watched**; a watch delivers the rows now, one **caught up** marker, then each row that comes to match
and a **removal** for each it gave that stops matching, live and coalesced per key under an **unread
bound** with a Pekko overflow strategy (R2, R5, R6). What tells a watcher on any instance that a row
was written is the database itself: every view write runs `pg_notify` in its own transaction, so the
announcement is made exactly when the write commits, and a rebuild's `TRUNCATE` announces itself the
same way; one dedicated `LISTEN` connection per instance feeds the instance's open watches, whose
match is decided by re-running the watched statement for the written row (R3, R4). The protocol gains
two server-streaming rpcs and a `watched` flag at 1.15; Python and TypeScript read async iterables; a
module is refused a watched query at start (R10, R11). Every event stream gains an SSE heartbeat so a
quiet watch outlives pekko-http's idle timeout (R12), and the sample's `WatchCart` stops polling (R13).

## Technical Context

**Language/Version**: Scala 3 on the JVM (`runtime`, `http`, `sidecar`, `testkit`); protobuf for the
protocol; Python 3 and TypeScript (Node 22/24) for the SDKs; Rust for the module refusal only.

**Primary Dependencies**: r2dbc-postgresql 1.0.7 (`getNotifications`, `fetchSize`), Pekko Streams
(`Source.fromPublisher`, `watchTermination`, `backpressureTimeout`, `keepAlive`, a `GraphStage`),
JSqlParser (the watched rules), grpc-java (the sidecar's rpcs), pekko-http's `EventStreamMarshalling`.
**No new library.**

**Storage**: the view tables as they are; one notification channel `ankka_views` per service database;
no table, no event, no DDL.

**Testing**: munit offline suites in `runtime` and `testkit`; three `GherkinSuite`s over
`features/view-streams/`; `ConformanceSuite` cases against four references; `WasmHostSuite`;
`ViewStreamsDocumentationSuite`; `SidecarClusterSuite` on k3s for the TLS listener.

**Target Platform**: wherever a service runs — local, the test kit's cluster, Kubernetes.

**Project Type**: platform runtime feature with an SDK surface in four languages and documentation.

**Performance Goals**: SC-001 memory flat over 5 000+ rows (fetch size 256); SC-002 a write reaches an
SSE client within one second (a notification arrives in milliseconds; one evaluation statement per
distinct target); SC-003 coalescing under a writer faster than its reader on every run; SC-004 every
watch ends within one second of a rebuild (the `!rebuilt` notification); SC-005 `WatchCart` with no
polling.

**Constraints**: a watcher never slows a view's write (no backpressure strategy; the evaluator reads
after the fact); the ACL is checked once at open; the request context is read while building the
`Source`; no span held open for a watch's life; the sidecar's rpcs apply flow control; the heartbeat
must be shorter than the idle timeout; every new test switch forwarded in `Test / javaOptions` (none
is added); no test binds a fixed port.

**Scale/Scope**: four stream forms, two watch forms, one marker, one stage, one listener, four
settings, two rpcs and one flag, three SDK surfaces, one sample rewrite, six documentation pages.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template: no principles are declared, so no gate is
derived from it. The repository's standing rules (`CLAUDE.md`, `.claude/rules/runtime.md`,
`messaging.md`, `observability.md`, `sidecar.md`, `wasm.md`, `docs.md`, `testing.md`) are the gates:

| Gate | Pre-research | Post-design |
|---|---|---|
| Effects inert, the runtime interprets: nothing new is decided in a handler | pass: a stream and a watch are `Source`s a handler returns; the runtime decides how | pass |
| `runtime` does not depend on `http`, `agent` or the protocol | pass by design | pass: `WatchEvent`, `Watching`, `WatchEnded` in `sdk`; `asSse` in `http`; frames in `sidecar` |
| One place reduces a write's statements (`ViewStore`) | pass: the announcement is `ViewStore.announce` | pass: `ViewWrites` pairs it with every write, `ViewAnnounceSuite` holds every path |
| A projection's `R2dbcSession` takes no `SET`; a transaction-local setting is a query | n/a | pass: `pg_notify` is a `SELECT` through `selectOne` |
| A behaviour an older runtime must refuse is a new call, not a new field | n/a | pass: two new rpcs (`UNIMPLEMENTED` on an old runtime); the one new field, `watched`, is refused at discovery from both ends |
| Work handed to another thread cannot see a thread-local | pass: known | pass: origin and request read while the `Source` is built (R8, R9) |
| Never intern anything unbounded into the recorder | n/a | pass: the handler name is the declared query's, bounded by declaration; a row key never reaches the recorder |
| A span held open is lost | n/a | pass: no span for a watch; the count fires at termination |
| `Sink.last` on connection publishers | n/a | pass: `Database.stream` acquires as `withConnection` does |
| Reference facts generated, every variable described | n/a | pass: four variables on `configuration.md`, `docs check` |
| Every acceptance scenario ends as a test that fails without the feature | n/a | pass: R14 names the suite per feature file; the documentation feature has its suite rather than a read-off |
| Could this check pass while the thing it checks is false? | | the "produced no faster than read" count is a stage before the reader, not after; "connection returned" is proven by a query after fifty cancellations against a small pool; "announced on commit" is read from a second connection's `LISTEN`, not from the writer; the heartbeat suite uses a 2 s idle timeout so a missing heartbeat is red |

## Project Structure

### Documentation (this feature)

```text
specs/047-streaming-view-queries/
├── plan.md              # this file
├── research.md          # R1–R15, verify-first V1–V8
├── data-model.md        # the values, the settings, one watch's transitions
├── quickstart.md        # eight steps, pure to k3s
├── contracts/
│   ├── scala-api.md     # declaring, streaming, watching, serving, the watched rules
│   ├── protocol.md      # 1.15: rpcs, frames, discovery, refusals, the SDK surfaces, conformance
│   └── runtime.md       # announcements, the listener, watches, settings, heartbeat, topology
└── tasks.md             # /speckit-tasks output, not created here
```

### Source Code (repository root)

```text
modules/sdk/src/main/scala/com/thinkmorestupidless/ankka/sdk/
├── DeclaredQuery.scala              # + watched: Boolean = false; .watched on the companion's query(...)
├── View.scala, KeyedView.scala      # query(name)(statement) returns a DeclaredQuery that can be .watched
└── WatchEvent.scala                 # NEW: WatchEvent, Watching, Overflow, WatchEnded, WatchEnd

modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/PlatformVariables.scala   # + ANKKA_VIEW_, ANKKA_SSE_
modules/core/src/test/.../PlatformVariablesSuite.scala

modules/runtime/src/main/resources/reference.conf      # ankka.view.{watch-bound, unread-bound, fetch-size}, listener-backoff
modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/
├── Database.scala                   # + stream(timeout)(statement): Source — connection for the stream's life
├── ViewStore.scala                  # + announce(table, key), announceRebuilt(table)
├── ViewWrites.scala                 # NEW: a write and its announcement, as one pair of fragments
├── QueryCheck.scala                 # CheckedQuery.watched; the three watched rules
├── ViewClient.scala                 # ViewQueries: *Stream forms, watch, watchRow; counted(streaming)
├── ViewListener.scala               # NEW: the LISTEN connection, built from the pool's block + DatabaseTls
├── ViewWatches.scala                # NEW: registry, bound, pending keys, the evaluator
├── KeyedBuffer.scala                # NEW: the coalescing GraphStage with the overflow strategies
├── ViewVersions.scala               # rebuild announces !rebuilt in the TRUNCATE transaction
├── ProjectionSupport.scala          # applyView writes through ViewWrites
├── ProjectionRuntime.scala          # ViewStateHandler through ViewWrites; starts/stops ViewWatches
├── TopicHandlers.scala / KeyedViewHandlers.scala / remote/RemoteProjection.scala   # through ViewWrites
├── Observability.scala              # made(..., streaming)
└── remote/Conversation.scala        # WireProtocol.Version = "1.15"
modules/runtime/src/test/.../{KeyedBufferSuite, QueryCheckSuite}.scala

modules/http/src/main/resources/reference.conf         # ankka.http.sse.heartbeat
modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/
├── HttpServer.scala                 # dispatchStream: keepAlive(heartbeat); SseSettings.problems at start
├── SseEvent.scala                   # + the WatchEvent.asSse extension (names row, removed, caught-up, ended)
└── (RemoteEndpoint in sidecar gets the heartbeat through the same dispatch)

modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/
├── views/ViewStreamSteps.scala      # NEW: RowStreamFeatures, WatchingFeatures, WatchRulesFeatures
├── views/ViewsKit.scala             # + a watched query, a slow (pg_sleep) query, an aggregate and a limited one
├── views/{DatabaseStreamSuite, ViewAnnounceSuite, ViewListenerSuite}.scala   # NEW
├── SseHeartbeatSuite.scala          # NEW
└── ViewStreamsDocumentationSuite.scala   # NEW

protocol/src/main/protobuf/ankka/protocol/v1/
├── client.proto                     # QueryStream, Watch, RowFrame, Row, Removed, Ended, WatchRequest, Named, Overflow
└── discovery.proto                  # DeclaredQuery.watched
protocol/README.md                   # 1.15
protocol/WASM-ABI.md                 # a module reads a view whole; no stream import

sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/
├── ClientService.scala              # the two rpcs, ready-aware loop
├── ClientLogic.scala                # queryStream, watch; WatchedSince = 15 refusals
├── Discovery.scala                  # watched → QueryCheck; the version gate; the comment
├── wasm/WasmDiscovery.scala         # refuses a watched query
└── RemoteEndpoint.scala             # unchanged (heartbeat is HttpServer's)
sidecar/src/test/.../conformance/{ConformanceSuite, ConformanceReference}.scala   # view.* cases and routes
sidecar/src/test/.../wasm/WasmHostSuite.scala      # the module refusal

controlplane-api/src/main/scala/.../api/Compatibility.scala   # ProtocolVersion(1, 15)

sdks/python/src/ankka/{client.py, service.py, endpoint.py, views.py}   # Views.stream/watch/watch_row, WatchEnded, sse_events, PROTOCOL_VERSION, watched=
sdks/python/src/ankka/server.py                     # discovery refusal for a watched query on an old sidecar
sdks/python/examples/shopping_cart/conformance.py   # the view.* routes
sdks/typescript/src/{client.ts, spec.ts, routes.ts, server/discovery.ts}   # the same
sdks/typescript/examples/shopping-cart/conformance.ts
sdks/rust/ankka/README.md (protocol version); no API change

samples/shopping-cart/src/main/scala/shoppingcart/api/CartStreamsEndpoint.scala   # WatchCart as watchRow
samples/shopping-cart/src/main/scala/shoppingcart/application/CartRows.scala       # a watched open-carts query; row fields WatchCart needs (V6)
samples/shopping-cart/src/main/scala/shoppingcart/api/ShoppingCartEndpoint.scala   # GET /carts/open as SSE (quickstart §6)

docs/build/views.md, docs/build/streaming.md, docs/build/grpc-endpoints.md (regenerated include),
docs/reference/{limitations, akka-divergences, configuration, wasm-abi}.md
tools/docs/skill/ankka-views-consumers/SKILL.md; marketplace and template mirrors via `just docs-sync`
GLOSSARY.md                          # the proposed terms lose their marker
features/view-streams/languages.feature   # two Givens reworded to "whose view declares a watched query" (R10, R11)
```

**Structure Decision**: no new module. The developer-facing values (`WatchEvent`, `Watching`,
`WatchEnded`, `DeclaredQuery.watched`) sit in `sdk` because `runtime` interprets the SDK's descriptors
and the SDK must not see the runtime; the SSE adapter sits in `http` because `runtime` must not know
SSE; the listener, the watches and the buffer sit in `runtime` beside `ViewClient`, which already owns
every read of a view. The sidecar translates frames and holds its own refusals, as it does for every
other rpc. No cluster message type is added (R3 chose the database over the cluster), so no serializer
binding changes.

## Order of work

Each step leaves the tree green; later steps are tested against earlier ones.

1. **The row stream.** `Database.stream` with its termination handling and `backpressureTimeout`;
   `ViewQueries`' four `*Stream` forms; `ankka.view.fetch-size`; `DatabaseStreamSuite` (V2, V3);
   `RowStreamFeatures` over `row-streams.feature`. The spec's "Backpressure on a one-shot stream" edge
   case is reworded to the runtime's bound.
2. **The watched declaration.** `DeclaredQuery.watched`, `.watched` on the companions,
   `CheckedQuery.watched`, the three rules in `QueryCheck` and `QueryCheckSuite`; `ViewsKit` gains the
   fixtures (a watched query, an aggregate, a limited one, a `pg_sleep` one).
3. **The announcement.** `ViewStore.announce`/`announceRebuilt`, `ViewWrites`, every write path through
   it, the rebuild's notification; `ViewAnnounceSuite` (V7) reading from a second connection.
4. **The listener and the watches.** `ViewListener` (V1) from the pool's block with `DatabaseTls`;
   `ViewWatches` with the bound, the pending set and the evaluator; `KeyedBuffer` and its suite;
   `watch`/`watchRow` on `ViewQueries` as `register ++ rowsNow ++ CaughtUp ++ live`; the settings and
   `PlatformVariables`; `Observability.made(streaming)`; `ViewListenerSuite`; `WatchingFeatures` and
   `WatchRulesFeatures` (V4 through the topology scenario).
5. **Serving.** `asSse` in `http`; the SSE heartbeat (V5) with its startup check and `SseHeartbeatSuite`;
   the sample's `WatchCart` as a watch of one row (V6) and `GET /carts/open`; `grpc/test` still green.
6. **The protocol and the sidecar.** 1.15 in every place; `client.proto`, `discovery.proto`;
   `ClientService` with the ready-aware loop (V8); `ClientLogic.queryStream`/`watch` and the refusals;
   `Discovery`'s gate; `WasmDiscovery`'s refusal; `WasmHostSuite`; the Scala reference's routes and
   the `view.*` conformance cases.
7. **The SDKs.** Python: `scripts/proto.py`, `Views.stream/watch/watch_row`, `WatchEnded`,
   `sse_events`, `watched=`, the discovery refusal, the reference routes, `uv run conformance`.
   TypeScript: `npm run proto`, the same surface, `npm run conformance`. Rust: the README's version;
   `./conformance.sh` (streaming cases skipped, the module refusal held). Point `ANKKA_SIDECAR_IMAGE` at
   this branch's image for the SDK test kits.
8. **Documentation and the glossary.** The six pages, the skill's rules, `just docs-sync`, `just docs`,
   `ViewStreamsDocumentationSuite`, the proposed terms settled, `just features`.
9. **Verify first.** Each of V1–V8 moved from "gathered" to "verified during implementation" in
   `research.md` with what the database or the library actually did; `gh workflow run cluster -f
   suite=SidecarClusterSuite` on the branch before merge.
10. **Rule files.** `.claude/rules/messaging.md` gains the announcement and the listener; `runtime.md`
    the statement-timeout-per-fetch fact, the stream's connection life and the heartbeat; any trap the
    runs taught.

## Complexity Tracking

No gate is violated. Three choices are larger than the smallest possible and are noted, not justified
as violations:

| Choice | Why | Simpler alternative rejected because |
|---|---|---|
| A dedicated unpooled `LISTEN` connection per instance (R3) | notifications must survive the pool's idle eviction, and `LISTEN` is per session | a pooled connection held forever is still a pool slot and is evicted; cluster pub-sub cannot say a write committed |
| Re-running the watched statement per written row, with `row_key` required (R2) | the only evaluation that is exact for every statement shape the check admits, recursive ones included | a one-row CTE shadowing the table needs no `row_key` but is wrong for any statement reading more than the written row |
| A coalescing `GraphStage` rather than `Source.queue` (R6) | Pekko's strategies are per element; the spec's coalescing is per key | per-element buffering would deliver fifty versions of one row to a slow reader, which FR-007 forbids |
| An SSE heartbeat on every event stream (R12) | without it a quiet watch dies at sixty seconds with no reason, which FR-009 forbids and nothing in a watch can prevent | a heartbeat only on watches would leave agent streams and process streams with the same silent ending |
