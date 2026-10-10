# Tasks: Streaming View Queries — Rows a Part at a Time, and a Query Kept Open

**Input**: Design documents from `/specs/047-streaming-view-queries/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a test
that fails without the feature, and research's *Verify first, gathered* list (V1–V8) turns each fact
assumed about Postgres, the driver or pekko-http into a case before code relies on it. The scenarios
are in `features/view-streams/` and `features/documentation/view-streams.feature`; research R14 says
where each is run. Where a task says "case", it means a `test(...)` in the named suite, or a step
definition that makes a named scenario run, named for the scenario or the rule it holds.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a page shows the open carts as they change), US2 (a handler reads a large result a
  row at a time), US3 (one row is watched by its key), US4 (a watch is honest about what it is), US5
  (a process streams and a module is told it cannot), US6 (the documentation says what a stream and a
  watch are)

Paths are repository-relative. Abbreviations, each followed by `…/com/thinkmorestupidless/ankka/<pkg>`
where it is a Scala tree: `SDK` = `modules/sdk/src/main/scala/…/sdk`; `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `RT`/`RTT` = `modules/runtime/src/{main,test}/scala/…/runtime`;
`HTTP` = `modules/http/src/main/scala/…/http`; `TKT` = `modules/testkit/src/test/scala/…/testkit`;
`SC`/`SCT` = `sidecar/src/{main,test}/scala/…/sidecar`; `PROTO` =
`protocol/src/main/protobuf/ankka/protocol/v1`; `PY` = `sdks/python`; `TS` = `sdks/typescript`;
`SAMPLE` = `samples/shopping-cart/src/main/scala/shoppingcart`; `DOCS` = `docs`; `FEAT` =
`features/view-streams`. "R*n*" is a section of `research.md`, "V*n*" a verify-first fact; a contract
is named by its file under `contracts/`.

Work on branch `047-streaming-view-queries-impl` in a worktree `.claude/worktrees/047-streaming-view-
queries-impl` off this branch once the design is committed (the spec branch's name is taken by this
worktree). Every `sbt` command takes `-Dankka.cluster.tests=off` unless the task names a k3s suite; a k3s
suite runs on demand with `gh workflow run cluster --ref 047-streaming-view-queries-impl -f suite=<Name>`.
sbt's project ids are `core`, `sdk`, `runtime`, `http`, `testkit`, `sidecar`, `grpc`, `shoppingCart`,
`controlPlaneApi`. A munit filter needs a leading wildcard (`-- '*view.*'`) or it runs nothing and reports
green.

---

## Phase 1: Setup — the words, the settings, the types every later task spells the same way

**Purpose**: declarations with no behaviour behind them yet, so every story compiles against one spelling.

- [X] T001 Add to `modules/runtime/src/main/resources/reference.conf` under `ankka.view`: `watch-bound = 1000` (`${?ANKKA_VIEW_WATCH_BOUND}`), `unread-bound = 256` (`${?ANKKA_VIEW_UNREAD_BOUND}`), `fetch-size = 256` (`${?ANKKA_VIEW_FETCH_SIZE}`), `listener-backoff { min = 1s, max = 30s }` (no variable), each with the comment the generated configuration table will carry (runtime.md "Watches on an instance"); and to `modules/http/src/main/resources/reference.conf` under `ankka.http.sse`: `heartbeat = 15s` (`${?ANKKA_SSE_HEARTBEAT}`) with a comment naming pekko-http's idle timeout (R12)
- [X] T002 Add `ANKKA_VIEW_` and `ANKKA_SSE_` to `RuntimeOnlyPrefixes` in `CORE/PlatformVariables.scala` beside `ANKKA_SOCKET_`, importing nothing; extend `CORET/PlatformVariablesSuite.scala`'s "runtime-only matches a prefix or an exact name" with both and its module case so a module's `config` answers them absent; `sbt core/test` green (R7)
- [X] T003 [P] Create `SDK/WatchEvent.scala` with `enum WatchEvent[+Row] { Row(key, row), Removed(key), CaughtUp }`, `final case class Watching(unread: Option[Int] = None, overflow: Overflow = Overflow.DropHead)` refusing `unread` below 1, `enum Overflow { DropHead, DropTail, DropNew, DropAll, Fail }`, `enum WatchEnd { Rebuilt, InstanceStopping, ListenerLost, Unread }` each with its sentence and its wire word (`"rebuilt"`, `"instance-stopping"`, `"listener-lost"`, `"unread"`), and `final case class WatchEnded(reason: WatchEnd) extends RuntimeException(reason.sentence)` (data-model.md "In sdk")
- [X] T004 [P] Add `watched: Boolean = false` to `DeclaredQuery` in `SDK/DeclaredQuery.scala` with `def watched: DeclaredQuery = copy(watched = true)` so a companion writes `query("open-carts")(…).watched`; carry it through `View.descriptor` and `KeyedView.descriptor` in `SDK/View.scala` and `SDK/KeyedView.scala` (whatever descriptor field lists the queries gains the flag); `sbt sdk/compile` green (scala-api.md "Declaring")
- [X] T005 [P] Add `streaming: Boolean = false` to `Observability.made` in `RT/Observability.scala`, passed to `calls.handled` as `invocation` passes it; `sbt runtime/compile` green (R9)
- [ ] T006 [P] Add the fixtures every view-streams suite drives to `TKT/views/ViewsKit.scala`: a `CartEntity` (open, add item, check out, delete) and a `CartsView` plain view with `row_key`-selecting queries `open-carts` (`.watched`, matches `checkedOut = false`), `due-carts` (`.watched`, a `now()`-dependent match on a `dueAt` field), `cart-count` (an aggregate, **not** watched — used by the refusal scenario through a second `Refused`-style descriptor declared `.watched`), `newest-carts` (`ORDER BY … LIMIT 10`, likewise), `by-customer` (ordered, by a `:customer` value), `all-orders` (`SELECT row_key, payload FROM $table ORDER BY row_key`), and `slow` (`SELECT row_key, payload FROM $table, pg_sleep(0.05)` — first confirm `QueryCheck` admits `pg_sleep`, V9; if not, a self cross join `FROM $table a, $table b, $table c` that outlasts the timeout); a `RefusedWatched` descriptor set holding the aggregate and the limited query declared `.watched`, built only by the refusal scenario; `docs:start`/`docs:end` markers around the watched declaration for US6

**Checkpoint**: every module compiles; nothing streams yet.

---

## Phase 2: Foundational — the stream's connection, the announcement, the listener, the buffer, the registry

**Purpose**: the runtime machinery every story's `Source` is built from. No story's scenario runs without it.

**⚠️ CRITICAL**: no story phase begins until T007–T018 are green.

### The row stream (R1)

- [X] T007 Write `TKT/views/DatabaseStreamSuite.scala` first, on `SharedPostgres` with a pool of ten: a 5 000-row table streamed in order and complete (past 1 000); fifty streams cancelled after ten rows, then one `queryOne` answers within the ask timeout (V3); a `pg_sleep(1)`-per-row statement under a 200 ms statement timeout fails `CommandError(Timeout)` (V2, the database's half); a stream with `fetchSize 2` read one row every 300 ms under a 200 ms statement timeout *completes* (V2, per-fetch half); a stream whose reader pulls nothing for the statement timeout fails `CommandError(Timeout)` whose message names the duration ("was not read for"); `LogCapturing`; it fails to compile until T008
- [X] T008 Add `stream[A](timeout: FiniteDuration, fetchSize: Int)(sql: String, binds: Vector[Any])(decode: (Row, RowMetadata) => A): Source[A, NotUsed]` to `RT/Database.scala`: acquire with `Source.fromPublisher(factory.create()).runWith(Sink.last)` inside `Source.lazyFutureSource`, `beginTransaction`, `SET TRANSACTION READ ONLY`, `SET LOCAL statement_timeout`, `createStatement(sql).fetchSize(fetchSize)` with binds, `Source.fromPublisher(execute()).flatMapConcat(r => Source.fromPublisher(r.map(decode)))`, `.backpressureTimeout(timeout)` mapped to `CommandError(Timeout, s"the stream was not read for $timeout")`, `.watchTermination` committing on completion and rolling back on failure or cancellation, then `close()`; the `57014` mapping shared with `ViewQueries.refusal`; a comment carrying the per-fetch `statement_timeout` fact; T007 green
- [ ] T009 Add `private[ankka] def streamed(query: String)(source: => Source[Row, NotUsed]): Source[Row, NotUsed]` to `ViewQueries` in `RT/ViewClient.scala`: captures `Trace.currentOrigin` when called, counts through `observability.made(…, streaming = true)` on `watchTermination` with ok/failed, only when `declared.registered(view)` — the streaming twin of `counted`; a unit case in `RTT/ViewQueriesCountingSuite.scala` (new) that a completed and a failed stream each count once, and that nothing is counted before termination

### The announcement (R3)

- [ ] T010 Add `announce(table: String, key: String): SqlFragment` (`SELECT pg_notify('ankka_views', $1 || '|' || $2)`) and `announceRebuilt(table: String): SqlFragment` (`… || '|!rebuilt'`) to `RT/ViewStore.scala`; create `RT/ViewWrites.scala` with `upsert(table, key, payload): Vector[SqlFragment]` and `delete(table, key): Vector[SqlFragment]` pairing the write with its announcement, refusing to announce (and logging once per table) a key over 7 900 bytes (runtime.md "The announcement")
- [ ] T011 Write `TKT/views/ViewAnnounceSuite.scala` first: a second connection `LISTEN ankka_views` (through the driver directly, `getNotifications()` as a `Source`), then one write through each path — a plain event-sourced view (`CartsView`), a key-value-sourced plain view, a topic view (in-memory broker), a keyed view's event and state handlers, the remote `RemoteView.apply` path through the sidecar's test double if one exists in `testkit`, else noted `ranElsewhere` with the sidecar suite that covers it — asserting payload `<table>|<key>`; a delete announced the same; a row upserted twice in one keyed-view change announced once (V7); a rebuild (`restartService` with `CartsView` at version 2) announcing `<table>|!rebuilt`; fails until T012
- [ ] T012 Route every write through `ViewWrites`: `ProjectionSupport.applyView` (`RT/ProjectionSupport.scala`, session: `updateOne` for the write then `selectOne` for the announcement — never `updateOne` for a `SELECT`, the `R2dbcSession` row-count trap), `ViewStateHandler` and `RemoteViewStateHandler` (`RT/ProjectionRuntime.scala`, `RT/remote/RemoteProjection.scala`, `tx.execute` both), `ViewVersions.guarded` (`RT/ViewVersions.scala`, used by `ViewGuard.write` for topic views in process and remote), `KeyedViewEventHandler` and `KeyedViewStateHandler` (`RT/KeyedViewHandlers.scala`); and `ViewVersions.rebuild` runs `announceRebuilt` after `TRUNCATE` in the same transaction; T011 green; `sbt testkit/test` still green on every existing view suite

### The listener (R3, V1)

- [ ] T013 Write `TKT/views/ViewListenerSuite.scala` first: a listener built from the kit's configuration receives a notification raised on another connection; two subscribers for two tables each get only their own; a connection killed with `pg_terminate_backend` ends every subscriber with `ListenerLost` and the next `subscribe` reconnects and receives again (within the backoff's minimum plus a second); stopping the listener closes the connection (`pg_stat_activity` shows none for `ankka_views`); fails until T014
- [ ] T014 Create `RT/ViewListener.scala`: builds an **unpooled** `PostgresqlConnectionFactory` from `pekko.persistence.r2dbc.connection-factory`'s `host`, `port`, `database`, `user`, `password` through `ConnectionFactoryOptions.builder()` and `DatabaseTls(system).apply(builder, system.settings.config)` (V1); `LISTEN ankka_views`; `Source.fromPublisher(connection.getNotifications())` fanned out by the payload's table (split on the first `|`) to `subscribe(table): Source[Announcement, Cancellable]` where `Announcement = Written(key) | Rebuilt`; states `Idle | Listening | Reconnecting` with the backoff from T001; failure ends every subscriber with `WatchEnded(ListenerLost)`; `stop()` closes; log lines `view listener: listening | lost (…) | reconnected`; T013 green

### The buffer (R6)

- [ ] T015 [P] Write `RTT/KeyedBufferSuite.scala` first with a `TestSource`/`TestSink` pair and no actor system beyond `ActorTestKit`: coalescing (fifty versions of `c1` with no demand, then one pull gives the last), each strategy at bound 3 with keys `a b c d` arriving under no demand — `DropHead` gives `b c d`, `DropTail` gives `a b d`, `DropNew` gives `a b c`, `DropAll` gives `d`, `Fail` fails with `WatchEnded(Unread)` — a re-arriving key keeps the map at size (`a b c a` under bound 3 gives `b c a`), and the stage completes when upstream completes with the pending elements drained; fails until T016
- [ ] T016 [P] Create `RT/KeyedBuffer.scala`: `final class KeyedBuffer(bound: Int, overflow: Overflow) extends GraphStage[FlowShape[WatchEvent[?], WatchEvent[?]]]` over a `LinkedHashMap[String, WatchEvent[?]]` per data-model.md's table, keyed by `Row.key`/`Removed.key`; T015 green

### The registry and the evaluator (R2, R4)

- [ ] T017 Create `RT/ViewWatches.scala`: one per `ViewClient`, holding the settings from T001, the `ViewListener` (started lazily), `byView: Map[view, Entry]` with `Entry(watches, pending: LinkedHashSet[key], evaluating)`, `open: AtomicInteger` against the watch bound, `register(view, table, target, watching, origin): Either[CommandError, Watch]` refusing above the bound with `Unavailable` naming it ("this instance holds N open watches, its watch bound"), the per-view evaluator that drains `pending` one key at a time running `SELECT payload FROM (<sql>) ankka_watched WHERE ankka_watched.row_key = $n` per distinct `Named` target and `ViewStore.selectByKey` per `ByKey` target in `database.readOnly(statementTimeout)`, offering each watch `Row`/`Removed`/nothing against its `given` set (a failed evaluation logged, the key dropped); `Rebuilt` ending every watch of the view with `WatchEnded(Rebuilt)`; `stop()` ending every watch with `WatchEnded(InstanceStopping)` then stopping the listener, registered with `AnkkaService.registerShutdown`'s first phase beside the other extensions (`RT/Ankka.scala`); unit cases in `RTT/ViewWatchesSuite.scala` (new) for the bound, the grouping by target (two watches, one statement — count statements through a scripted `Database` or the kit's statement log), and the `given`-set rule (no removal for a key never given)
- [ ] T018 Construct `ViewWatches` in `ViewClient` (`RT/ViewClient.scala`, lazily, as `observability` is — an extension looked up in a constructor breaks suites without a system) and hand it to each `ViewQueries` built by `forView`; `sbt runtime/test testkit/test` green

**Checkpoint**: the runtime can stream a statement, announce every write, listen, buffer and evaluate. No handler can yet ask for any of it.

---

## Phase 3: User Story 2 — A handler reads a large result a row at a time (Priority: P1) 🎯 MVP

**Goal**: `allStream`, `whereStream`, `orderedStream`, `askStream` on `ViewQueries`, no limit unless given, the whole forms unchanged.

**Independent Test**: `sbt 'testkit/testOnly *RowStreamFeatures'` runs every scenario of `FEAT/row-streams.feature` green and ignores none; `DatabaseStreamSuite` from Phase 2 holds the connection and timeout rules.

- [X] T019 [US2] Write `TKT/views/ViewStreamSteps.scala` as `abstract class ViewStreamSteps(feature: String) extends GherkinSuite(feature) with LogCapturing` on one `AnkkaTestKit.start(Seq(CartEntity.descriptor, CartsView.descriptor), Seq(ProjectionRuntime()))` with per-scenario `s<n>-` prefixes as `QuerySteps` does, and the concrete `RowStreamFeatures("../../features/view-streams/row-streams.feature")`; steps for: "holds N rows" (writes through the entity and waits with `eventually` on a count), "asks for every row as a stream" (`allStream()` into a `Sink.seq` with a `map` counter *before* the sink), "is given N rows" and "in the statement's order", "asks the query as a stream with values" (`askStream`), "whole" keeps 1 000, "the statement timeout is shorter than reading them takes" (the `slow` query under the kit's ask timeout lowered by `configure`), "ends in a failure that says the statement was ended" (`CommandError(Timeout)`), "reads 10 rows" then "fewer than 1000 rows have been produced" (the counter stage), "goes away after 10 rows" then "the stream stops being produced" (cancel; the counter stops within a second and the pool answers a query); fails until T020
- [X] T020 [US2] Add to `ViewQueries` in `RT/ViewClient.scala`: `allStream(limit: Option[Int] = None)`, `whereStream(condition, limit = None)`, `orderedStream(condition, order, limit = None)`, `askStream(query, values*)` and `askStream(query, limit: Int, values*)`, each `streamed(name)(database.stream(statementTimeout, fetchSize)(sql, binds)(decodePayload))` with the SQL from `ViewStore.selectWhere`/`selectOrdered` without their `LIMIT` unless given and the declared query's `CheckedQuery.sql` as `askNamed` builds it (the same `NotFound`/`BadRequest` refusals for an unknown name, a missing or extra value, a limit below 1, each failing the `Source` at once); T019 green; `sbt 'testkit/testOnly *QuerySteps* *DeclaredQuerySuite'` still green
- [ ] T021 [P] [US2] Put `docs:start stream-all` / `docs:end` and `docs:start stream-named` markers around the two stream calls in `TKT/views/ViewStreamSteps.scala` for `DOCS/build/views.md` (US6)

**Checkpoint**: a Scala handler reads every row of a view. MVP.

---

## Phase 4: User Story 1 — A page shows the open carts as they change (Priority: P1)

**Goal**: a declared query declared `.watched` is checked at start, watched through `watch(query, values*)`, served as SSE with `asSse`, and a quiet SSE stream outlives the idle timeout.

**Independent Test**: `sbt 'testkit/testOnly *WatchingFeatures'` runs the ten declared-query scenarios of `FEAT/watching.feature` green (the three by-key scenarios are US3's and may be `ranElsewhere` until Phase 5); `sbt 'runtime/testOnly *QueryCheckSuite'` holds the watched rules; `sbt 'testkit/testOnly *SseHeartbeatSuite'` holds the heartbeat; `curl -N` against the sample shows the open carts, `caught-up`, then live events (quickstart §6).

### The watched check (R2)

- [ ] T022 [US1] Extend `RTT/QueryCheckSuite.scala` (or create it beside the existing `QueryCheck` cases) with watched cases: `SELECT payload FROM t WHERE …` watched is refused "does not select row_key and payload; a watched query gives the view's rows"; `SELECT row_key, payload FROM t … LIMIT 10` refused "has a limit; a watched query decides each row alone" (also `OFFSET`, `FETCH FIRST`); `SELECT row_key, count(*) …`, `SELECT DISTINCT row_key, payload …`, `… GROUP BY row_key` refused "aggregates; a watched query gives the view's rows"; `SELECT * FROM t WHERE …` admitted; `SELECT row_key, payload FROM t ORDER BY updated_at` admitted; `WITH RECURSIVE tree AS (…) SELECT row_key, payload FROM tree` admitted; a `LIMIT` inside the CTE admitted (outermost only); an unwatched statement with any of these still admitted as today; fails until T023
- [ ] T023 [US1] Add `watched: Boolean` to `CheckedQuery` in `RT/QueryCheck.scala` and the three rules in `check`, applied only when `query.watched`, each with the refusal text from scala-api.md "What a watched statement may say", read from JSqlParser's outermost `PlainSelect` (`getSelectItems`, `getLimit`/`getOffset`/`getFetch`, `getGroupBy`, `getDistinct`, aggregate function names among the select items); the discovered-view path (`QueryCheck.problems` over remote descriptors) applies the same once discovery carries `watched` (T044); T022 green

### Watching a declared query (R5)

- [ ] T024 [US1] Add `WatchingFeatures("../../features/view-streams/watching.feature")` to `TKT/views/ViewStreamSteps.scala` with steps for every declared-query scenario: "holds the open carts … and the cart … that is checked out", "watches open-carts" (into a `Sink.queue`/`TestSink` the step pulls from), "is first given the rows … and no other", "is then told it is caught up" (`CaughtUp` after the rows now and before any live element; at once for "holds no open cart"), "is given a row that comes to match" / "written again" / "a removal naming" / "nothing for" (a short `expectNoMessage`), "stops reading" then "the watch ends and no further row is produced" (cancel; `ViewWatches.open` back down; a later write produces nothing — read the kit's statement log for no evaluation), "wrote the row while no handler was watching … is not given", "an HTTP endpoint of shop that serves open-carts as server-sent events" (a test `HttpEndpoint` in `ViewsKit` on `HttpServer.at("127.0.0.1", 0)` with `sseEvents("/carts/open")(_ => views.watch(CartsView.openCarts).asSse)`), "a browser reads the route and the cart is then opened" (the JDK client reading the chunked body line by line), "receives the rows … and then the row … as one stream" (parses `event:`/`data:` pairs: `row`, `row`, `caught-up`, `row`); plus one named munit `test` beside the scenarios (a `GherkinSuite` is a `FunSuite`), "a socket route carries a watch" (FR-012's third carrier, which no scenario names): the test endpoint's `socket("/carts/live") { s => views.watch(CartsView.openCarts).asSse.map(_.data).runWith(Sink.foreach(s.send)); s.receive() }` and a WebSocket client receiving the rows now, the caught-up datum and a live row as text frames; the three by-key scenarios `ranElsewhere("Phase 5, US3")` until T030; fails until T025–T027
- [ ] T025 [US1] Add `watch(query: DeclaredQuery, values: (String, String)*)` and `watch(query, watching: Watching, values*)` to `ViewQueries` in `RT/ViewClient.scala`: refuse (failing the `Source` at once) an unknown query `NotFound`, `watched = false` `BadRequest` ("view 'v' declares the query 'q' without watched; declare it .watched to watch it"), a missing or extra value `BadRequest`; otherwise `streamed(query.name)` over `Source.lazySource { watches.register(…) match { Left(e) => Source.failed(e); Right(w) => rowsNow(w) ++ Source.single(CaughtUp) ++ w.live } }` where `rowsNow` is the declared statement as an R1 stream (no limit, `Row(key, row)` from `row_key` and `payload`, each key added to `w.given`) and `w.live` is the evaluator's offers through `KeyedBuffer(watching.unread.getOrElse(unreadBound), watching.overflow)`, registered **before** `rowsNow` runs and the listener confirmed listening (R5's ordering argument, written as a comment); cancellation unregisters; a `WatchEnded` fails the `Source`; termination counts `ok` for completion, `Rebuilt`, `InstanceStopping`, and `failed` for `ListenerLost`, `Unread`, a timeout
- [ ] T026 [US1] Add `extension [Row, M](source: Source[WatchEvent[Row], M]) def asSse(using JsonValueCodec[Row]): Source[SseEvent, M]` to `HTTP/SseEvent.scala`: `Row` → `SseEvent.json("row", RowJson(key, row))`, `Removed` → `SseEvent.json("removed", KeyJson(key))`, `CaughtUp` → `SseEvent.json("caught-up", Empty)`, `.recover { case WatchEnded(r) => SseEvent.json("ended", ReasonJson(r.wire)) }`; a unit case in `modules/http/src/test/.../SseEventSuite.scala` (new or existing) asserting each event's name and one-line JSON data and that another failure is not recovered
- [ ] T027 [US1] Write `TKT/SseHeartbeatSuite.scala` first (V5): an `HttpServer.at("127.0.0.1", 0)` configured with `pekko.http.server.idle-timeout = 2s` and `ankka.http.sse.heartbeat = 500ms` serves an `sse` route whose `Source` emits one event then stays quiet for 5 s and then emits a second: the JDK client receives both and sees comment lines (`:` prefixed) between them; with `heartbeat = 3s` under the same idle timeout the server refuses to start naming both durations; then add `.keepAlive(settings.heartbeat, () => ServerSentEvent.heartbeat)` to `dispatchStream`'s marshalled source in `HTTP/HttpServer.scala` and `SseSettings` read beside `SocketSettings` with its `problems` check at start ("the SSE heartbeat (…) must be shorter than the server's idle timeout (…)"); `sbt 'testkit/testOnly *SseHeartbeatSuite *SocketSuite'` green and the agent streaming suites unchanged
- [ ] T028 [US1] In the sample: declare `openCarts = query("open-carts")(s"SELECT row_key, payload FROM $table WHERE (payload::jsonb ->> 'checkedOut') = 'false'").watched` on `CartRows` in `SAMPLE/application/CartRows.scala`, serve `sseEvents("/carts/open")(_ => views.watch(CartRows.openCarts).asSse)` from `SAMPLE/api/ShoppingCartEndpoint.scala` (the endpoint takes `clients.viewClient` beside `componentClient`), inside `docs:start watch-sse` markers; a case in `samples/shopping-cart/src/test/.../CartViewSuite.scala` reading the route; `sbt shoppingCart/test` green

**Checkpoint**: US1 and US2 together are the feature's two P1 stories; a Scala service has everything the spec promises Scala.

---

## Phase 5: User Story 3 — One row is watched by its key (Priority: P2)

**Goal**: `watchRow(key)` and the sample's `WatchCart` with no polling.

**Independent Test**: the three by-key scenarios of `FEAT/watching.feature` run green in `WatchingFeatures`; `sbt 'shoppingCart/testOnly *CartGrpcMoreSuite' grpc/test` green with no `Source.tick` in `CartStreamsEndpoint.scala`.

- [ ] T029 [US3] Replace the three `ranElsewhere` by-key scenarios in `TKT/views/ViewStreamSteps.scala` with steps: "holds the row c1" / "holds no row c9", "watching the row c1" (`watchRow`), "writes the row c1 three times" (three item additions), "was first given the row as it stood", "the versions in the order they were written" and "never an older version after a newer one" (compare a monotonic field), "deletes the row c1" (the entity's delete, which the plain view maps to a row delete) then "a removal naming c1", "told it is caught up at once" then "is given the row c9" when it is written; fails until T030
- [ ] T030 [US3] Add `watchRow(key: String, watching: Watching = Watching())` to `ViewQueries` in `RT/ViewClient.scala`: `ByKey(key)` target, rows-now as `ViewStore.selectByKey` (zero or one `Row`), then `CaughtUp`, then live offers from the evaluator's by-key statement; `given` holds the key once given; counted under the handler name `get`; T029 green
- [ ] T031 [US3] Rewrite `WatchCart` in `SAMPLE/api/CartStreamsEndpoint.scala` as `views.forView(CartRows).watchRow(request.cartId).collect { case WatchEvent.Row(_, row) => toProto(row) }` inside the existing `docs:start server-stream` markers, the endpoint taking `clients.viewClient`; first confirm `CartRow` carries what `toProto` needs (V6) and, if not, add the fields to `CartRow` and `CartRowsView`'s handler (`SAMPLE/application/CartRows.scala`) with the sample's own features still green; `sbt 'shoppingCart/testOnly *CartGrpcMoreSuite'` and `sbt grpc/test` (`features/grpc/streaming.feature`'s five scenarios against the rewritten method, including "produced no faster than it is read") green; `just docs-sync` refreshes `DOCS/build/grpc-endpoints.md`'s include

**Checkpoint**: SC-005 holds — the sample polls nothing.

---

## Phase 6: User Story 4 — A watch is honest about what it is (Priority: P2)

**Goal**: the rules of `FEAT/watch-rules.feature`: cross-instance delivery, coalescing and overflow, the rebuild and instance-stop endings, the bound, the topology, evaluation on writes, the start refusal.

**Independent Test**: `sbt 'testkit/testOnly *WatchRulesFeatures'` runs all ten scenarios green and ignores none.

- [ ] T032 [US4] Add `WatchRulesFeatures("../../features/view-streams/watch-rules.feature")` to `TKT/views/ViewStreamSteps.scala` with steps: "two instances of shop" (`kit.startPeer(Seq(ProjectionRuntime()))`), "a handler on the first instance watching" / "the second instance writes the row" (through the peer's `componentClient`) then "is given the row" within a second (SC-002); "watching … that does not read" (no pull) then "writes c1 50 times" then "reads" → "the last version" and "fewer than 50 versions"; "with an unread bound of 10 rows" (`Watching(unread = Some(10))`) and "writes 15 rows for different carts" → "the 10 written last" and "not the 5 written first until they are written again"; "the overflow strategy fail" → "the watch ends" and "told the watch ended unread" (`WatchEnded(Unread)`); "handlers watching" then "shop restarts with carts at a higher version" (`kit.restartService` with `CartsView` declared at version 2, as `EntityViewVersionSuite` does) and "carts is emptied" → "every watch ends" within a second (SC-004) "told the view was rebuilt"; "a handler watching … served by the first instance" then "the first instance stops" (`kit.stopService()`) → `WatchEnded(InstanceStopping)`; "as many open watches as the installation's watch bound" (`configure` sets `ankka.view.watch-bound = 3` for the suite's second kit, or a per-scenario kit) then one more → `Unavailable` naming "watch bound"; "watched … and stopped" then "reads the service's topology" (the kit's observability document, as `TopologySteps` reads it) → "one observed call from the handler to carts, handled as ok" and marked `streaming`; "due-carts … whose match depends on the time" then "comes to match by the passing of time alone" (a `dueAt` in the near past with no write) → "nothing for c1 until carts writes the row c1 again"; "declares the query cart-count … newest-carts … a handler that watches" then "shop is started" (a kit over `RefusedWatched`) → "does not start" and the refusal text names "gives the view's rows" and "has no limit", and "can still be asked whole" (the same statements declared without `.watched` on `CartsView` answer `ask`); fails until T033–T035
- [ ] T033 [US4] Make `ViewWatches` (from T017) end every watch of a view on `Rebuilt` and every watch on `stop()`, and refuse above the bound, if any of the three was deferred; make the `KeyedBuffer`'s bound the watch's `Watching.unread` (T025); confirm the peer instance's writes announce and the first instance's listener receives (nothing to code if Phase 2 holds — a failing step here names a real gap)
- [ ] T034 [US4] Key an observed pair by `(caller, callee, handler, streaming)` in `RT/CallCounts.scala` (today `streaming` is a flag set on the pair, so a whole `ask` and a `watch` of one query would share a pair and the flag would paint both as streams); `TopologyMerge` (controlplane) sums by the same key; `TopologyJson` renders two pairs; a case in `RTT/CallCountsSuite.scala` (existing or new) that `made(open-carts, streaming = false)` and `made(open-carts, streaming = true)` from one caller produce two pairs with their own counts, and that a pair that never streamed renders exactly as before; then place a declared query's name on the view's node in `RT/TopologyJson.scala` (l.161–170 today uses `ViewQueries.Names` only) so a stream or watch named by its query lands on the view it read (V4); a case in `RTT/TopologyJsonSuite.scala` (existing or new) that a pair `endpoint → carts` under handler `open-carts` with `streaming = true` renders on the view node with `"streaming": true` (FR-013)
- [ ] T035 [US4] Record the time-dependent edge case as a step that is honest about its clock: the `due-carts` statement compares `dueAt` to `now()`, the row is written with `dueAt` two seconds ahead, the step waits three seconds and asserts nothing arrived, then writes the row again and asserts the `Row`; T032 green; `sbt 'testkit/testOnly *ViewStreamSteps*'` runs all three suites green

**Checkpoint**: every promise of `watch-rules.feature` is a test that fails without it.

---

## Phase 7: User Story 5 — A process streams and a module is told it cannot (Priority: P2)

**Goal**: protocol 1.15, the sidecar's two rpcs and refusals, the Python and TypeScript surfaces, the module's refusal, the conformance cases.

**Independent Test**: `sbt 'sidecar/testOnly *ConformanceSuite -- "*view.*"'` green in process; `cd sdks/python && uv run conformance` and `cd sdks/typescript && npm run conformance` green on the `view.*` cases; `sbt 'sidecar/testOnly *WasmHostSuite -- "*watched*"'` holds the module refusal; `cd sdks/rust && ./conformance.sh` green with the streaming cases skipped.

### The protocol (R10)

- [ ] T036 [US5] Edit `PROTO/client.proto`: `rpc QueryStream (QueryRequest) returns (stream RowFrame)`, `rpc Watch (WatchRequest) returns (stream RowFrame)`, messages `RowFrame`, `Row`, `Removed`, `Ended`, `WatchRequest`, `Named`, enum `Overflow` exactly as protocol.md gives them, each with its `1.15` comment; `PROTO/discovery.proto`: `DeclaredQuery.watched` (next free field number) with its comment; `protocol/README.md`'s "## Version" gains 1.15's paragraph (two rpcs, one flag, `Query`'s limit unchanged, `QueryStream`'s absence meaning none); `sbt protocol/compile`
- [ ] T037 [US5] Set the version to `1.15` in `RT/remote/Conversation.scala` (`WireProtocol.Version`), `SC/Discovery.scala` (`ProtocolVersion` is derived; the doc comment gains 1.14 and 1.15 lines), `controlplane-api/src/main/scala/…/api/Compatibility.scala` (`ProtocolVersion(1, 15)` and the history line), `PY/src/ankka/service.py` (`PROTOCOL_VERSION`), `TS/src/spec.ts`, `sdks/rust/ankka/README.md`; `sbt controlPlaneApi/test` green (the compatibility suites pin the number); note in the commit that 046 and 048 take the same number and the second to merge renumbers

### The sidecar

- [ ] T038 [US5] Add to `SC/ClientLogic.scala`: `queryStream(request): Source[RowFrame, NotUsed]` (`all` → `allStream(limit)`, `get|by-id|by-key` → one row, a declared name → `askStream`, each `Row` frame carrying the key and the stored JSON as `Payload("application/json", "row", …)`, a `CommandError` → `failed`), `watch(request): Source[RowFrame, NotUsed]` (`named` → `watch(query, Watching(unread_bound, overflow), values)`, `key` → `watchRow`; `WatchEnded` → `ended(reason.wire)`; `CommandError` → `failed`), both inside `asCaller(metadata)` so the process's handler is the origin; `WatchedSince = "1.15"` with the per-call refusal text for a process declaring an earlier version (`ClientLogic.refusal`'s pattern); `declared`'s `QueryCheck.checkedAll` passes `watched` through so the sidecar refuses a bad watched statement at discovery
- [ ] T039 [US5] Serve both rpcs in `SC/ClientService.scala` with the ready-aware loop: run the `Source` into `Sink.queue` with `inputBuffer(1, 1)`, loop on a virtual thread waiting on `out.isReady` / cancellation, `onNext` per frame, `onCompleted` after `ended`/`failed` or completion (V8: reuse `grpc`'s `Streams.drain` if its signature allows, else lift the loop into a shared helper in `sidecar`); a cancelled call cancels the queue and the `Source`
- [ ] T040 [US5] In `SC/Discovery.scala`: read `DeclaredQuery.watched` into `RemoteViewDescriptor`/`RemoteKeyedViewDescriptor`'s queries; refuse a `Spec` whose `protocolVersion` minor is below 15 and declares a watched query with "view '…': query '…' is watched, which needs protocol 1.15; the SDK speaks …" (the `SocketsSince` pattern, `minorOf`); a case in `SCT/DiscoverySuite.scala` (existing) for the refusal and for a watched statement failing the watched rules through `QueryCheck`
- [ ] T041 [US5] In `SC/wasm/WasmDiscovery.scala`'s `validate`: refuse a watched query with "view '…': query '…' is watched, and a module reads a view whole; declare it without watched" beside the streaming-route refusals; a case in `SCT/wasm/WasmHostSuite.scala` loading a module whose discovery declares `watched = true` (a WebAssembly-text guest in the suite, as `WasmImportsSuite` writes them, or the Rust reference under a conformance switch) and asserting the refusal; `protocol/WASM-ABI.md`'s discovery refusals list gains it and the `query` row says "a module reads a view whole; there is no stream import"; `WasmHostSuite`'s import-list equality unchanged (no import added)

### Conformance

- [ ] T042 [US5] Add to `SCT/conformance/ConformanceReference.scala`: a `conformance-orders` view (or extend the tree view) declaring `all-rows` (`SELECT row_key, payload …`) and a watched `open-rows`; routes `GET /conformance/views/stream` (`askStream(allRows).map(json)` as `sse`), `GET /conformance/views/stream/{customer}` (the declared query with a value), `GET /conformance/views/watch` (`watch(openRows).asSse`), `GET /conformance/views/watch/{key}` (`watchRow(key).asSse`), and `POST /conformance/views/rows/{key}` to write rows through the view's entity; then cases in `SCT/conformance/ConformanceSuite.scala`: `view.stream-all` (5 000 rows written, the SSE body holds 5 000 `data:` lines in order, and the first line arrives before the reference's `GET /conformance/views/stream/progress` reports the last row read — SC-001's measure, the same bounded-production proxy `RowStreamFeatures` uses), `view.stream-named` (3 000 of one value, 2 000 of another, the stream gives 3 000), `view.watch-named` (two rows now, `caught-up`, then a third written during the read), `view.watch-row` (now, three versions in order, a removal), each behind `onlyWhereStreaming()`; `languages.feature`'s "a runtime from before view streams refuses a program that asks for one" is `ranElsewhere` from this suite naming the two SDK unit tests of T043 and T045 and the sidecar's `DiscoverySuite` case of T040 (the Scala in-process target performs no discovery against a sidecar, so it cannot be a conformance case); `sbt 'sidecar/testOnly *ConformanceSuite -- "*view.*"'` green in process; `discovery.lists-every-component` updated for the new view

### Python

- [ ] T043 [US5] In `PY/src/ankka/client.py`: `Views.stream(view_id, name, row, values=None, *, limit=None) -> AsyncIterator[Row]` over `self._stub.QueryStream`, `Views.watch(view_id, name, row, values=None, *, unread=None, overflow=Overflow.DROP_HEAD) -> AsyncIterator[Row | Removed | CaughtUp]` and `Views.watch_row(view_id, key, row, *, unread=None, overflow=…)` over `Watch`, decoding `row` frames with `default_codec_for(row)`, raising `WatchEnded(reason)` on `ended`, `CommandError` on `failed`, and the `UNIMPLEMENTED` mapping "the runtime beside this process does not offer view streams, which need protocol 1.15 (this SDK speaks 1.15)" (the `ScheduleRecurring` pattern); dataclasses `Row`, `Removed`, `CaughtUp`, `Overflow` (an `Enum` mirroring the proto), `WatchEnded` in `PY/src/ankka/views.py` (new) re-exported from `ankka`; `sse_events(watch)` in `PY/src/ankka/endpoint.py` yielding `SseEvent("row", …)`, `("removed", …)`, `("caught-up", "{}")`, `("ended", …)`; `query(name, statement, watched=False)` in the view declaration carried into discovery; `PY/src/ankka/server.py`'s discovery refusal for a watched query against a sidecar below 1.15 ("Run a sidecar speaking 1.15 or later."); `uv run python scripts/proto.py`; unit tests under `PY/tests/` for the frame decoding, the enum, `sse_events`, and — named for the scenario — "a runtime from before view streams refuses a program that asks for one": `Discover` from a fake sidecar stating `1.14` against a service declaring `watched=True` aborts `FAILED_PRECONDITION` naming 1.15; `uv run pytest -q && uv run mypy` green
- [ ] T044 [US5] Add the five routes and the view of T042 to `PY/examples/shopping_cart/conformance.py` (the watched declaration inside `# docs:start watched-query` markers for US6, the stream route inside `# docs:start stream`, the watch route inside `# docs:start watch`), the stream route emitting each row as it arrives (SC-001's measure in a process is the same as Scala's: `view.stream-all` asserts the first `data:` line is received before the reference has read the last row, through a `GET /conformance/views/stream/progress` route the reference answers with how many rows it has read so far); `sbt sidecar/Docker/publishLocal` then `cd sdks/python && ANKKA_SIDECAR_IMAGE=ankka-sidecar:<this commit's tag> uv run conformance` green on `view.*` (the image is this branch's — `.claude/rules/testing.md`)

### TypeScript

- [ ] T045 [US5] In `TS/src/client.ts`: `Views.stream<Row>(viewId, name, row, values?, { limit? }): AsyncIterable<Row>`, `Views.watch<Row>(viewId, name, values, row, { unread?, overflow? }): AsyncIterable<WatchEvent<Row>>`, `Views.watchRow<Row>(viewId, key, row, opts)` with `WatchEvent<Row> = { kind: "row", key, row } | { kind: "removed", key } | { kind: "caughtUp" }`, `class WatchEnded extends Error { reason }`, `Overflow` as a string union `"dropHead" | "dropTail" | "dropNew" | "dropAll" | "fail"` mapped to the proto enum (erasable syntax only — no `enum`), the `UNIMPLEMENTED` mapping; `sseEvents(watch)` in `TS/src/routes.ts`; `watched?: boolean` on the declared-query shape carried into discovery; `TS/src/server/discovery.ts`'s `watchedQueryRefusal` beside `declaredQueryRefusal`; `npm run proto && npm run typecheck`; tests under `TS/test/` for the decoding, `sseEvents`, and — named for the scenario — "a runtime from before view streams refuses a program that asks for one": `refusal(spec, "1.14")` for a spec with `watched: true` names 1.15; `npm test` green
- [ ] T046 [US5] Add the routes and view of T042 to `TS/examples/shopping-cart/conformance.ts` (with `// docs:start watched-query`, `stream`, `watch` markers); `cd sdks/typescript && ANKKA_SIDECAR_IMAGE=… npm run conformance` green on `view.*`; `npm run test:slow` green

### Rust

- [ ] T047 [P] [US5] `sdks/rust/ankka/README.md` states 1.15 and that a module reads a view whole; `cd sdks/rust && cargo test --workspace && ./conformance.sh` green with the `view.*` streaming cases reported skipped by `onlyWhereStreaming` and `view.watched-refused-in-module` green through `WasmHostSuite` (T041)

**Checkpoint**: every language the spec names has what it promises; the module is refused with the words the scenario names.

---

## Phase 8: User Story 6 — The documentation says what a stream and a watch are (Priority: P3)

**Goal**: the six pages, the skill, the mirrors, and a suite that reads the statements the feature names.

**Independent Test**: `just docs` green (every variable described, every include fresh); `sbt 'testkit/testOnly *ViewStreamsDocumentationSuite'` finds every statement of `features/documentation/view-streams.feature`.

- [ ] T048 [US6] Write `TKT/ViewStreamsDocumentationSuite.scala` first, the `TimersDocumentationSuite.says(path, statements*)` pattern over whitespace-normalised page text, one test per scenario: `DOCS/build/views.md` describes a query answered as a stream of rows and a watched query in each of Scala, Python and TypeScript (the three tab headings present in both new sections); says a watch is live and not a record, how a watch ends (the four reasons), and that a reader who must see every change reads the source with a consumer; `DOCS/reference/limitations.md` says a module reads a view whole and a watch may not give a watcher every change; fails until T049–T050
- [ ] T049 [US6] Write `DOCS/build/views.md`'s new sections "Streaming a query" (after "Querying a view in Python": includes `stream-all`/`stream-named` from `ViewStreamSteps`, `stream` from the two SDK references, in three tabs; no limit unless given; the statement timeout per fetch and the runtime's not-read bound; a module reads a view whole) and "Watching a query" (the `.watched` declaration and what a watched statement must say — `row_key` beside `payload`, no limit, no aggregate; `watch`/`watchRow`; the three element kinds and `caught up`; live not a record, evaluated on writes for the written row, coalesced, the unread bound and the five overflow strategies with the dropped-removal consequence; the four endings and that a watcher who wants the rows watches again; a reader who must see every change reads the source with a consumer; "a cheap statement makes a cheap watch"; the `asSse` example and `GET /carts/open` from the sample; the gateway's HTTP route timeout bounds a response); amend "Limits" (the thousand-row limit is the whole form's); `DOCS/build/streaming.md`'s "Only agents stream" becomes "Agents stream their replies and views stream their rows" with a link; a page stands alone — no feature numbers, no "see above"
- [ ] T050 [P] [US6] `DOCS/reference/limitations.md`: under Components, "A module reads a view whole" and "A watch guarantees no delivery: it is live, coalesced, and ends with a reason; what was written while nobody watched is not replayed"; `DOCS/reference/akka-divergences.md`: a summary row (`QueryStreamEffect` and `streamUpdates` | a row stream and a watch, declared `watched`, with a caught-up marker and removals) and a section "Views stream and are watched" under the views section, plus the description line; `DOCS/reference/configuration.md`: prose for `ANKKA_VIEW_WATCH_BOUND`, `ANKKA_VIEW_UNREAD_BOUND`, `ANKKA_VIEW_FETCH_SIZE` (Database) and `ANKKA_SSE_HEARTBEAT` (HTTP) — the coverage check fails without each; `DOCS/reference/wasm-abi.md`: the new discovery refusal and the `query` row's note
- [ ] T051 [US6] Add the view-streams rules to `tools/docs/skill/ankka-views-consumers/SKILL.md` (declare `.watched` and select `row_key`; a watch is live; choose `fail` to know; read the request before returning the `Source`); run `just docs-sync` (includes, the generated configuration table, both skill mirrors under `marketplace/plugins/ankka/skills/` and `ankka.g8/src/main/g8/.claude/skills/`) and `just docs`; T048 green; `sbt 'cli/testOnly *TemplateSuite'` still green (the Giter8 `$` escaping of the mirrored pages)

**Checkpoint**: a developer who reads the views page knows everything the spec promises, and a test says so.

---

## Phase 9: Polish — the glossary, the rules, the verifications, the platform

- [ ] T052 Remove `*Proposed.*` from `caught up`, `removal`, `watch bound`, `unread bound` and `overflow strategy` in `GLOSSARY.md` (leaving 048's `idle timeout` and `heartbeat` as they are unless 048 has merged); `just features` reports 0 findings over every spec
- [ ] T053 Move each of V1–V8 in `research.md`'s "Verify first, gathered" to a "Verified during implementation" list with what the database, the driver or pekko-http actually did (the per-fetch timeout measured, the connection count after fifty cancellations, the heartbeat seen on the wire, `CartRow`'s fields), and any fact that turned out otherwise with the change it forced
- [ ] T054 [P] Add to `.claude/rules/messaging.md`: the announcement (every write through `ViewWrites`, `pg_notify` on commit, `!rebuilt`, the 7 900-byte key limit), the unpooled listener and why, "a watched query selects `row_key`", and any trap the suites taught; to `.claude/rules/runtime.md`: `statement_timeout` is per portal fetch so a slow reader is bounded by `backpressureTimeout`, `Database.stream`'s connection life, the SSE heartbeat and its startup check; to `.claude/rules/sidecar.md`: the ready-aware loop for server-streaming rpcs
- [ ] T055 [P] Run `python3 .github/ci-coverage.py` (every new file claimed, every pattern matched) and `sbt scalafmtCheckAll scalafmtSbt`; `sbt -Dankka.cluster.tests=off test` green under `caffeinate -i`
- [ ] T056 Run `gh workflow run cluster --ref 047-streaming-view-queries-impl -f suite=SidecarClusterSuite` and confirm green: the one run where `ViewListener` connects over the database's TLS with a client certificate (V1's platform half); read the sidecar's log for `view listener: listening`
- [ ] T057 Reword the spec's remaining open question if the gateway's SSE bound was touched (it is not planned to be), and confirm `specs/047-streaming-view-queries/spec.md`'s edge cases match what was built: "Backpressure on a one-shot stream" names the runtime's bound (done in planning), "A watch through the gateway" still true

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 (Setup)**: no dependencies; T003–T006 parallel after T001–T002.
- **Phase 2 (Foundational)**: depends on Phase 1. Within it: T007→T008→T009 (the stream); T010→T011→T012 (the announcement); T013→T014 (the listener, needs T010's channel name); T015→T016 (the buffer, independent); T017 needs T008, T014, T016; T018 needs T017. The three test-first pairs (T007/T008, T011/T012, T013/T014, T015/T016) can run in parallel across files.
- **Phase 3 (US2)**: needs T008, T009. Can start before T010–T018 are done.
- **Phase 4 (US1)**: needs all of Phase 2 and T020 (rows-now is a row stream). T022/T023 and T027 are independent of T024–T026 and can run in parallel with them.
- **Phase 5 (US3)**: needs T025 (shares the watch pipeline). T031 needs T030.
- **Phase 6 (US4)**: needs Phases 4 and 5 (the scenarios exercise both forms). T034 is independent of T032/T033.
- **Phase 7 (US5)**: needs T020, T025, T030 (the sidecar translates them); T036–T037 can start as soon as Phase 1 is done. T043/T044 and T045/T046 are parallel with each other after T042; T047 after T041.
- **Phase 8 (US6)**: needs the include markers (T021, T028, T031, T044, T046); T050 parallel with T049; T051 last.
- **Phase 9**: after everything.

### User story dependencies

- **US2** (row stream): Phases 1–2's stream half only — the MVP.
- **US1** (watch + SSE): all of Phase 2, plus US2's `askStream` for the rows now.
- **US3** (one row): US1's pipeline.
- **US4** (rules): US1 and US3.
- **US5** (languages): US1, US2, US3's Scala forms; independent of US4.
- **US6** (docs): the markers of every other story.

### Parallel example — Phase 2

```
Agent A: T007 → T008 → T009           (the stream)
Agent B: T010 → T011 → T012           (the announcement)
Agent C: T013 → T014                  (the listener; needs T010's channel name — a one-line agreement)
Agent D: T015 → T016                  (the buffer)
then one agent: T017 → T018
```

### Parallel example — Phase 7

```
Agent A: T036 → T037 → T038 → T039 → T040 → T042
Agent B (after T037): T041 → T047
Agent C (after T042): T043 → T044
Agent D (after T042): T045 → T046
```

## Implementation Strategy

**MVP first**: Phase 1, the stream half of Phase 2 (T007–T009), Phase 3. A Scala handler reads every
row of a view in order with no limit, and `row-streams.feature` is green — US2 whole, deliverable alone.

**Then the watch**: the rest of Phase 2, Phase 4 — US1, the feature's headline — then Phase 5 (one
small addition to the pipeline, and the sample stops polling) and Phase 6 (the promises, each a test).

**Then the languages**: Phase 7 is the largest phase and the most parallel; it depends on nothing in
Phases 5–6 but the two Scala forms, so it can start as soon as T025 and T030 exist.

**Then the words**: Phase 8 reads every marker the earlier phases left; Phase 9 closes.

**Independent test criteria**, per story, are the "Independent Test" lines at the head of each phase;
each is one command whose green cannot be explained by the feature's absence (a `GherkinSuite` fails a
directory with no scenarios, a munit filter with a leading wildcard, a count taken before the reader).

**Notes**
- Tests come first in every pair and fail (or fail to compile) until the code task lands.
- Commit after each checkpoint; the k3s suite (T056) runs in CI, never on the laptop.
- The sidecar image the SDK test kits start is `ankka-sidecar:latest` unless `ANKKA_SIDECAR_IMAGE`
  names this branch's; another worktree's `docker:publishLocal` replaces `latest`.
- 046 and 048 also move the protocol to 1.15; the second to merge renumbers.
