# Research: Streaming View Queries

**Feature**: 047 | **Date**: 2026-10-10 | **Spec**: [spec.md](spec.md), clarified 2026-10-10

Every "Decision" below was taken against the code as it stands on `main` at `c1cc0f78`; file paths are
repository-relative, `R` is `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime`.
Items under "Verify first" are facts the plan rests on that a running database or a library has to
confirm; `tasks.md` turns each into a task that runs before the code depending on it.

## R1. A row stream is one r2dbc statement held open under a portal

**Decision**: `Database` gains a streaming form beside its `Future` forms:
`stream(timeout)(statement): Source[A, NotUsed]`. It acquires a connection (`Source.fromPublisher
(factory.create()).runWith(Sink.last)` — `last`, never `head`, per `.claude/rules/runtime.md`),
begins a transaction, runs `SET TRANSACTION READ ONLY` and `SET LOCAL statement_timeout`, creates the
statement with `fetchSize(ankka.view.fetch-size = 256)`, and returns
`Source.fromPublisher(statement.execute()).flatMapConcat(r => Source.fromPublisher(r.map(mapper)))`
with `watchTermination` that commits or rolls back and closes the connection on completion, failure
*and* cancellation. Every `ViewQueries` method that answers a `Vector` today gains a `…Stream` twin
returning `Source[Row, NotUsed]`: `allStream`, `whereStream`, `orderedStream`, `askStream`, with no
`LIMIT` unless given. `queryUpTo` stays for the whole forms.

**Rationale**: the driver already yields rows as a reactive stream; `Database.queryUpTo` (`R/Database.scala`
l.179–192) builds exactly this `Source` and then collects it with `Sink.seq`. The only missing piece is a
connection whose life is the stream's rather than a `Future`'s; `withConnection` closes the connection
when its `Future` completes (l.32–43), which for a stream is before the first row is read.

**A fact that changes the spec's edge case**: Postgres measures `statement_timeout` per protocol
`Execute`, and a portal fetched in batches of `fetchSize` sends one `Execute` per batch. So the timeout
bounds *each fetch*, not the stream, which is what lets a two-hundred-thousand-row export run longer than
the ask timeout (US2) — and it means a reader that stops reading does **not** hold a cursor the database
ends, as the spec's "Backpressure on a one-shot stream" edge case assumed. The runtime bounds that itself:
`.backpressureTimeout(statementTimeout)` on every row stream, failing with `CommandError(Timeout, "the
stream was not read for …")`. A statement the database ends (SQLSTATE `57014`, a slow fetch) fails
through the existing `refusal` as `ErrorCode.Timeout`. Both are a failure the reader sees, never a
shorter answer (FR-003), and the spec's edge case is reworded by the plan to say the runtime's bound.

**Alternatives considered**: Pekko Persistence R2DBC's `R2dbcSession` — it is for projection
handlers and returns `Future`s. `Source.unfoldResourceAsync` — it hands out one element per pull and
would need the driver's own cursor API, which r2dbc hides behind the publisher. A `LIMIT`-paged loop of
whole queries — no snapshot across pages, and the point of the feature is the row the database just
yielded.

## R2. What may be watched, and how a row's match is decided

**The problem**: a declared query is only required to return `payload`
(`docs/build/views.md` l.432; `Nodes.ofKind` in `modules/testkit/src/test/.../views/ViewsKit.scala` is
`SELECT payload FROM $table WHERE …`). A watch must decide, on a write of row `k`, whether `k` is in the
query's result; from a result that carries no key, it cannot.

**Decision**: a declared query is **watched by declaration**, as Akka's `@Query(streamUpdates = true)`
is: `query("open-carts")(statement).watched` in Scala, `watched=True` / `{ watched: true }` in Python
and TypeScript, carried in discovery as `DeclaredQuery.watched` (R10). `QueryCheck` applies three more
rules to a watched statement, at start, in the parser (it decides what a statement *says*): the
outermost select's items name `row_key` and `payload` (or `*`); no `LIMIT`, `OFFSET` or `FETCH`; and no
`GROUP BY`, `DISTINCT` or aggregate function at the outermost level. `ORDER BY` is allowed. A `WITH`
(recursive or not), a join or a subquery is allowed, because the evaluation re-runs the whole statement.
A watch of a query not declared watched is refused at the call (`ErrorCode.BadRequest`, "declare it
watched"), so the start-time refusal has something to attach to. `ViewQueries.watch(query, values*)` and
`watchRow(key)` are the two forms (FR-005, FR-006).

**Evaluation**: on an announcement for row `k` of view `V` (R3), for each distinct `(query, values)`
among `V`'s open watches, the runtime runs
`SELECT payload FROM (<checked sql>) ankka_watched WHERE ankka_watched.row_key = $n+1` in a read-only
transaction under the statement timeout, with the watcher's values and `k` bound. A row answers "matches,
and here it is as the query sees it"; none answers "does not match". For a watch of one row the
statement is `ViewStore.selectByKey`. For a plain filter Postgres pushes `row_key = $k` into the
subquery (a primary-key lookup); for a recursive statement it walks the statement — "a cheap statement
makes a cheap watch", which the documentation says. The decision is per written row only: a tree row
whose membership changes because its *parent* moved is not re-evaluated, exactly as FR-010 states.

**Rationale**: requiring `row_key` is one line in a statement and makes the match exact for every
statement shape the check admits; a `LIMIT` makes "stopped matching" undecidable from one row (the row
another pushed out is never written), and an aggregate's results have no key. Evaluating in the
database keeps SQL semantics SQL's. The alternative of shadowing the view's table with a one-row CTE
(`WITH <table> AS (SELECT $k, $payload …) <statement>`) needs no `row_key` and no table read, but is
wrong for any statement that reads more than the written row (a recursive walk, a join), so it would
have had to refuse `WITH` — the recursive queries feature 031 added are the ones a tree page wants to
watch.

**Alternatives considered**: evaluating every watch's whole statement on every write and diffing (Akka's
shape is closer to this): O(result) per write per watch, and a diff that needs the previous result held.
A `watch` of `all`: not offered (every row of a busy view is a firehose, and a declared `SELECT row_key,
payload FROM t` says the same thing deliberately).

## R3. A write is announced by the database, on commit: `NOTIFY`

**Decision**: every write of a view row is followed, in the same transaction, by
`SELECT pg_notify('ankka_views', $table || '|' || $key)` (`ViewStore.announce(table, key)`), and a
rebuild's `TRUNCATE` by `pg_notify('ankka_views', $table || '|!rebuilt')` inside
`ViewVersions.rebuild`'s transaction. Each instance that holds a watch keeps **one** dedicated
connection in `LISTEN ankka_views` (`ViewListener`), reading
`PostgresqlConnection.getNotifications()` (a Reactor `Flux`, carried by `Source.fromPublisher`;
r2dbc-postgresql 1.0.7 on the classpath). The connection is opened on the first watch of the instance's
life and kept; it is built from the same configuration block the pool reads
(`pekko.persistence.r2dbc.connection-factory`: host, port, database, user, password) with the
`DatabaseTls` customizer applied, as an *unpooled* `PostgresqlConnectionFactory`, because a pooled
connection is evicted when idle and `LISTEN` is lost with it.

**Rationale**: Postgres delivers a notification **when the transaction that raised it commits**, and
never for one that rolled back. That is exactly FR-008's "a row written by any instance reaches a
watcher on any instance" and the spec's assumption that an announcement "is made only after a committed
one when it is made on commit" — and it solves the one problem the write paths otherwise pose: a plain
view's and a keyed view's event handlers write through a projection's `R2dbcSession`
(`ProjectionSupport.applyView`, `KeyedViewEventHandler`), whose transaction Pekko commits *after* the
handler's `Future` completes, outside ankka's code. An announcement sent from that `Future` reaches a
watcher before the commit, and a watcher that then reads the table reads the previous version and never
hears of the new one. `pg_notify` inside the statement list rides the same commit. A notification is
also what ends every watch on every instance for a rebuild within a second (SC-004, FR-009) without a
cluster message, and a key-only payload (≤ 8000 bytes) stays under the limit for any row key the
platform writes.

**The write paths**, each gaining the announcement beside the write (the table in the spec's Context is
the one the explorer confirmed): `ProjectionSupport.applyView` (plain views, in process and remote,
session); `ViewStateHandler` / `RemoteViewStateHandler` (`database.inTransaction`); `ViewGuard.write` →
`ViewVersions.guarded` (topic views, in process and remote); `KeyedViewCore.writes` run by
`KeyedViewEventHandler` (session) and `KeyedViewStateHandler` (transaction). A `ViewWrites` helper turns
a row write into the pair of fragments so no path can take one without the other, and
`ViewAnnounceSuite` writes through every path and reads the notification.

**Trap carried over**: a projection's `R2dbcSession` reads a row count from every statement, and a
`SET` reports none (`.claude/rules/messaging.md`). `pg_notify` is a `SELECT`, run through `selectOne`.

**Alternatives considered**: Pekko typed `Topic` (cluster pub-sub, `pekko-cluster-typed` is already a
dependency, and the test kit forms a real two-node cluster): no commit timing — the announcement would
have to carry the row itself to be safe, which meets Artery's 256 KiB frame for a large row, and it
cannot say that a rebuild's `TRUNCATE` committed. Polling `updated_at`: a round trip per view per
interval on every instance, and no deletes. A Kafka topic: a broker the installation may not have.

## R4. One listener, one evaluator, bounded watches per instance

**Decision**: `ViewWatches` (runtime) is the instance's registry: `Map[view, Set[Watch]]`, bounded by
`ankka.view.watch-bound` (1000, `ANKKA_VIEW_WATCH_BOUND`); a watch beyond it is refused at open with
`CommandError(Unavailable, "this instance holds 1000 open watches, its watch bound")`. On a
notification `table|key` for a view with watches, the key goes into that view's **pending set**; one
evaluator per view drains it — a key notified again while pending is evaluated once, reading the latest
row — and runs R2's statement once per distinct `(query, values)` group and once for each watched key,
then offers each watch its element: `Row(key, row)` when it matches, `Removed(key)` when it does not and
the watch has given that key, nothing otherwise. Each watch remembers the keys it has given
(`HashSet[String]`, proportional to the listing). `table|!rebuilt` ends every watch of the view with
`WatchEnded(Rebuilt)`; the extension's stop ends every watch with `WatchEnded(InstanceStopping)`
(coordinated shutdown's first phase, `AnkkaService.registerShutdown`); a lost listener connection ends
every watch with `WatchEnded(ListenerLost)` and reconnects with backoff for the next one — the watcher
who wants the rows watches again, which re-reads the rows now, so nothing missed is silently missing.

**Rationale**: the spec's "live, not a record". Ending on a lost listener is the honest answer to "what
did I miss while the connection was down" — nothing can say.

## R5. Rows now, then caught up, then live — and why older never follows newer

**Decision**: a watch's `Source` is `register ++ rowsNow ++ Source.single(CaughtUp) ++ live`, in that
order: the watch is registered with `ViewWatches` (so notifications are collected from this moment) and
the listener is confirmed `LISTEN`ing *before* the rows-now statement runs as an R1 row stream of the
query (its values bound, no limit, in one read-only transaction); then the marker; then the live
elements, which have been accumulating in the watch's keyed buffer (R6) meanwhile.

**Why no older version follows a newer one**: a notification is delivered only for a commit after
`LISTEN` took effect, and each evaluation reads the table *after* its notification arrived, so it sees a
version at least as new as the one notified. The rows-now snapshot is taken after `LISTEN`; any commit
after the snapshot is notified and re-read at or after its commit. A row may therefore be given twice
(once now, once live, same or newer version) and is never given older after newer. The one thing this
does not order is two *different* instances writing the same key in a slice hand-off, which the
projection itself serialises; the documentation says a watch is coalesced and live, and the spec's
keyed-view edge case already says "the later version, possibly only it".

**Rationale**: the marker (clarification 2) costs one element kind and gives a page "I have the whole
listing"; an empty rows-now gives `CaughtUp` at once.

## R6. The unread bound is a keyed buffer with Pekko's overflow strategies

**Decision**: a `GraphStage`, `KeyedBuffer(bound, strategy)`, between the live evaluations and the
watcher: a `LinkedHashMap[key, WatchEvent]` where a new element for a key replaces the pending one in
place and moves it to the tail (coalescing: FR-007), and when the map holds `bound` keys and a new key
arrives the strategy decides — `dropHead` drops the least recently changed key (default), `dropTail` the
most recent, `dropNew` the arrival, `dropAll` everything pending (Pekko's `dropBuffer`, renamed
because `buffer` is a word the glossary reserves for the trace window), `fail` ends the stream with
`WatchEnded(Unread)`. The bound is `ankka.view.unread-bound` (256, `ANKKA_VIEW_UNREAD_BOUND`) unless
the watcher gives one; the strategy is the watcher's (`Watching(unread = 256, overflow =
Overflow.DropHead)`), carried over the protocol as an enum (R10). `backpressure` is not offered:
upstream is the evaluator, and a watcher must never slow it or the view's writes (FR-007a).

**Rationale**: Pekko's `Source.queue` strategies are per element, not per key, so coalescing needs a
stage of its own; the strategies' *names* are Pekko's so a Scala developer knows them. `KeyedBufferSuite`
drives every strategy with a reader that does not pull.

## R7. Settings and the platform's variables

**Decision**: three settings in `modules/runtime/src/main/resources/reference.conf`, each with a variable,
each described on `docs/reference/configuration.md` (the coverage check fails otherwise):
`ankka.view.watch-bound = 1000` (`ANKKA_VIEW_WATCH_BOUND`), `ankka.view.unread-bound = 256`
(`ANKKA_VIEW_UNREAD_BOUND`), `ankka.view.fetch-size = 256` (`ANKKA_VIEW_FETCH_SIZE`); and one in
`modules/http`'s: `ankka.http.sse.heartbeat = 15s` (`ANKKA_SSE_HEARTBEAT`, R12). `ANKKA_VIEW_` and
`ANKKA_SSE_` join `PlatformVariables.RuntimeOnlyPrefixes` beside `ANKKA_SOCKET_`, so a descriptor may
give them and they reach the platform's container, never the process; `PlatformVariablesSuite` gains the
two prefixes. No `PlatformOnly` entry: a developer may set a bound.

## R8. Serving: `Source` in, nothing new out — except a heartbeat

**Decision**: `watch` and `watchRow` answer `Source[WatchEvent, NotUsed]`; the row streams answer
`Source[Row, NotUsed]`. `WatchEvent.asSse` (an extension on `Source[WatchEvent, M]`) maps each element to
a named `SseEvent` — `row` `{"key":…,"row":…}`, `removed` `{"key":…}`, `caught-up` `{}` — and turns a
`WatchEnded` failure into a final `ended` event `{"reason":"rebuilt"}` before completing, so an
`EventSource` client is told why (FR-009) and reconnects; a handler writes
`sseEvents("/carts/open")(_ => views.watch(CartRows.openCarts).asSse)`. A gRPC `serverStream` takes the
`Source` mapped to the method's message, as `WatchCart` does today; a socket handler runs it with
`Sink.foreach(socket.send)` (the socket API is push, there is no adapter and none is added). The ACL is
checked once, when the route opens (`Router.handle` → `admit` → `dispatchStream`), which is FR-012's
"admits the watch as it admits the call" and the spec's ACL edge case.

**Request context and trace**: `dispatchStream` scopes `RequestScope` and the trace around *building*
the `Source` (HttpServer.scala l.581–582), and the `Source` is drained later on pekko-http's thread. The
watch therefore reads everything it needs from the request — caller, values — while being built, and
captures `Trace.currentOrigin` then (R9). The documentation says a handler reads the request before
returning the stream, which it already must for any SSE route.

## R9. A stream is one call, counted when it ends

**Decision**: `Observability.made` gains `streaming: Boolean` (it passes `false` today,
`R/Observability.scala` l.124–133) and `ViewQueries.counted` gains a streaming twin that captures the
origin at creation and counts on `watchTermination` with the stream's outcome (ok, failed; a
`WatchEnded` for a rebuild or a stop is `ok` — the call was answered — and `Unread` is `failed`), so a
watch is one observed call marked `(stream)` in the topology (FR-013; the console already prints
`streaming`). The row stream likewise. **Counted apart**: today `CallCounts.Edge.streaming` is a flag
set on the pair, so an `ask(openCarts)` and a `watch(openCarts)` from one handler would share one pair
and the flag would paint the whole answers as streams. The pair key gains `streaming`
(`CallCounts.Pair(caller, callee, handler, streaming)`), so the topology shows `open-carts` and
`open-carts (stream)` as two pairs with their own counts and durations; `TopologyMerge` sums pairs by
the same key, and the console's `describePair` already appends `(stream)`. An existing pair's JSON is
unchanged (`streaming: false`). No span is begun for a view query today and none is added: a span
held open for a watch's life would be lost (`Recorder.reserve` trap), and the trace of the *route* that
served it is the trace that exists.

**Verify first**: `TopologyJson` (l.161–170) places a call on a view node when the handler is in
`ViewQueries.Names`; a declared query's name must land on the view node too — the explorer reads that
as not covered. The 031 suites may already pin it; if not, the watch-rules topology scenario is the
test that does.

## R10. Protocol 1.15: two server-streaming rpcs and one discovery flag

**Decision**: `Client` gains `rpc QueryStream (QueryRequest) returns (stream RowFrame)` and
`rpc Watch (WatchRequest) returns (stream RowFrame)`; `discovery.proto`'s `DeclaredQuery` gains
`bool watched = N`. `RowFrame` is `oneof { Row row; Removed removed; Empty caught_up; Ended ended;
Error failed }` with `Row { string key; Payload payload }`, `Removed { string key }`,
`Ended { string reason }`; the sidecar sends `ended` or `failed` and then completes, as `StreamToken`
does. `WatchRequest` is `view_id`, `metadata`, `oneof target { Named named { string name; map values }
; string key }`, `optional uint32 unread_bound`, `Overflow overflow` (an enum: `DROP_HEAD = 0` the
default, `DROP_TAIL`, `DROP_NEW`, `DROP_ALL`, `FAIL`). The version is `1.15` in the seven places the
explorer listed (`WireProtocol.Version`, `Discovery`'s comment, `Compatibility.Protocol.version`,
`protocol/README.md`, the Python `PROTOCOL_VERSION`, the TypeScript `spec.ts`, the Rust README).
**Feature 048 also bumps the protocol** (`Error` gains fields); whichever merges second takes `1.16`,
or both take `1.15` in one release — a note for the implementer, not a conflict in the wire format.

**Refusals**: an older runtime answers the new rpcs `UNIMPLEMENTED`, which each SDK reports as "the
runtime beside this process does not offer view streams, which need protocol 1.15" — the
`ScheduleRecurring` pattern (`.claude/rules/sidecar.md`: a behaviour an older runtime must refuse is a new
call). A `watched` query reaches an older runtime as absent, so the SDKs refuse discovery from a sidecar
stating a version before 1.15 when any view declares a watched query (the `declaredQueryRefusal`
pattern), and the sidecar refuses a `Spec` under minor 15 declaring one (the `SocketsSince` pattern).
That is `languages.feature`'s "does not start": the plan rewords its two refusal scenarios' `Given`
from "whose handler asks a view for a stream" to "whose view declares a watched query", because a
handler's *call* cannot be known at start — only a declaration can.

**Backpressure over gRPC**: `ClientService` serves both rpcs with the ready-aware loop `grpc`'s
`Streams.drain` uses (pull, wait for `isReady`, send), not `InvokeStream`'s unbounded `onNext`, so a
process reading slowly slows the row stream (FR-004) rather than filling the sidecar.

**SDKs**: Python `client.views.stream(view_id, name, row, values=…)` and `watch(view_id, name, row,
values=…, unread=…, overflow=…)` / `watch_row(view_id, key, row, …)` as async generators of `Row |
Removed | CaughtUp`, raising `WatchEnded(reason)`; TypeScript the same as `AsyncIterable<WatchEvent<Row>>`
with `WatchEnded`. Each SDK's test kit needs nothing new: it starts `ankka-sidecar:latest`, which must be
this branch's image (`ANKKA_SIDECAR_IMAGE`, `.claude/rules/testing.md`).

## R11. A module reads a view whole

**Decision**: `WasmDiscovery.validate` refuses a `DeclaredQuery.watched` with "view '…': query '…' is
watched, and a module reads a view whole; declare it without watched". No `query_stream` or `watch`
import is added to `ModuleLoader.Imports` or `HostImports`, so the Rust crate offers neither (there is
nothing to call), and `WASM-ABI.md` says so beside `invoke_stream`'s "delivered whole". The `query`
import is unchanged (FR-015). `languages.feature`'s module scenario is reworded as R10's is.

## R12. A quiet watch must not die at sixty seconds: the SSE heartbeat

**Decision**: pekko-http ends a connection idle for `pekko.http.server.idle-timeout` (60 s) whether
upgraded or not (`.claude/rules/runtime.md`), so a watch of a quiet view served as SSE would end after a
minute with no reason — a violation of FR-009 nothing in this feature's own code could prevent.
`dispatchStream` adds `.keepAlive(heartbeat, () => ServerSentEvent.heartbeat)` to every event stream:
a comment line an `EventSource` never surfaces, every `ankka.http.sse.heartbeat` (15 s), refused at
start when not shorter than the idle timeout, as the socket keep-alive is (`SocketSettings.problems`).
Agent streams gain it too, harmlessly. The word is the glossary's **heartbeat** (feature 048 settled
it; `keep-alive` and `ping` are refused synonyms). The gateway's HTTP route timeout still bounds a
response (the spec's open question); the documentation says so beside the SSE example.

## R13. The sample's `WatchCart` becomes a watch of one row

**Decision**: `CartStreamsEndpoint.WatchCart` becomes
`views.forView(CartRowsView).watchRow(request.cartId).collect { case WatchEvent.Row(_, row) =>
toProto(row) }`, no polling (SC-005), inside the same `docs:start server-stream` markers so
`docs/build/grpc-endpoints.md` shows the new shape. **Verify first**: `CartRow(cartId, quantities,
checkedOut)` must carry what the `Cart` proto message needs; if it does not, the row gains the fields,
which is a view change the sample's own features cover.

## R14. Tests, by scenario

- `features/view-streams/row-streams.feature`, `watching.feature`, `watch-rules.feature` →
  `ViewStreamSteps` (`modules/testkit/src/test/.../views/`), a `GherkinSuite` per file on one
  `AnkkaTestKit` with `ProjectionRuntime()`, `LogCapturing`, values prefixed per scenario as
  `QuerySteps` does; the two-instance scenarios through `kit.startPeer`, "the instance stops" through
  `kit.stopService`; "fewer than 1000 rows produced" counted by a `map` stage before a reader that
  takes ten and holds (the measure is the runtime's, as `features/grpc/streaming.feature` measures its
  parts); the slow-statement scenario through a declared query over `pg_sleep` per row.
- `languages.feature` → named cases in `ConformanceSuite` (`view.stream-all`, `view.watch-named`,
  `view.watched-refused-by-old-runtime` through a fake sidecar version, `view.watched-refused-in-module`
  in `WasmHostSuite`), with the routes added to the Scala, Python, TypeScript and Rust references
  (`discovery.lists-every-component` holds them equal; the Rust reference declares no watched query and
  is what the module refusal case loads).
- `features/documentation/view-streams.feature` → `ViewStreamsDocumentationSuite`, one test per scenario
  over the rendered page text (the `TimersDocumentationSuite.says` pattern).
- Units: `DatabaseStreamSuite` (completion, failure, cancellation each return the connection — fifty
  cancelled streams then a query, against a pool of ten; the backpressure timeout), `KeyedBufferSuite`
  (every strategy), `QueryCheckSuite` (the watched rules, each refused with its words),
  `ViewAnnounceSuite` (every write path raises the notification; a rebuild raises `!rebuilt`),
  `ViewListenerSuite` (a dropped connection ends the watches and the next watch reconnects),
  `SseHeartbeatSuite` (a quiet stream outlives the idle timeout; the refused setting).

## R15. Documentation

`docs/build/views.md` gains "Streaming a query" and "Watching a query" (three language tabs, samples
included from `ViewStreamSteps`' fixtures and the two SDK references), the watched declaration under
"Declared queries", and the rules in prose: live not a record, evaluated on writes for the written row,
coalesced, the unread bound and strategies with the dropped-removal consequence, how a watch ends, a
reader who needs every change reads the source with a consumer, "a cheap statement makes a cheap
watch", the gateway's route timeout. `docs/build/streaming.md`: "Only agents stream" becomes "Agents
stream their replies and views stream their rows". `docs/reference/limitations.md`: a module reads a
view whole; a watch guarantees no delivery. `docs/reference/akka-divergences.md`: a row for
`QueryStreamEffect`/`streamUpdates` and a section. `docs/reference/configuration.md`: four variables.
`docs/reference/wasm-abi.md` and `protocol/README.md`: 1.15. `tools/docs/skill/ankka-views-consumers/
SKILL.md` gains the rules; `just docs-sync` refreshes the mirrors; `GLOSSARY.md`'s proposed terms lose
their marker when the feature ships.

## Verify first, gathered

| # | Fact the plan rests on | How to verify |
|---|---|---|
| V1 | An unpooled `PostgresqlConnectionFactory` can be built from the pool's config block with `DatabaseTls` applied, and `getNotifications()` delivers while the connection runs nothing else | `ViewListenerSuite` against `SharedPostgres`; the k3s `SidecarClusterSuite` for the TLS path |
| V2 | `statement_timeout` is per `Execute` under a portal, so a slow *reader* is not ended by the database | `DatabaseStreamSuite`: a stream with `fetchSize 2` read slowly for longer than the timeout completes; a `pg_sleep` statement fails `57014` |
| V3 | `watchTermination` fires on downstream cancel and the connection returns to the pool | `DatabaseStreamSuite`'s fifty cancelled streams |
| V4 | A declared query's call lands on the view's node in `TopologyJson` | the watch-rules topology scenario |
| V5 | `ServerSentEvent.heartbeat` through `keepAlive` reaches the client as a comment the browser ignores and resets pekko-http's idle timer | `SseHeartbeatSuite` with a 2 s idle timeout |
| V6 | `CartRow` carries what the `Cart` proto needs | reading both; `CartGrpcMoreSuite` |
| V7 | Postgres folds identical `pg_notify` payloads in one transaction, so a row written twice in one projection batch is announced once | `ViewAnnounceSuite` |
| V8 | The sidecar's `Streams.drain` loop is reusable from `ClientService` (it lives in `grpc`, which `sidecar` depends on) | compile |
| V9 | `QueryCheck` admits `pg_sleep` (its refused list is by name and prefix: `set_config`, `pg_read_file`, `pg_advisory*`, `dblink*`, `lo_*`), so the slow-statement fixture can be `…, pg_sleep(0.05)`; if not, the fixture is a self cross join large enough to outlast the timeout | `QueryCheckSuite` |

## Verified during implementation

| # | What the database, the driver or Pekko actually did |
|---|---|
| V1 | An unpooled `PostgresqlConnectionFactory` built from the pool's block through `DatabaseTls` listens and receives (`ViewAnnounceSuite`, every case). The TLS path itself is the k3s `SidecarClusterSuite`'s, still to run in CI. |
| V2 | Held. `statement_timeout` is per portal `Execute`: a stream fetched two rows at a time, read one row per 300 ms under a 1 s timeout, completes; a `pg_sleep(1)` per row under 200 ms fails `57014`, which arrives wrapped by Reactor (`DatabaseStreamSuite`). |
| V3 | Held. Fifty streams cancelled after ten rows each leave a ten-connection pool answering at once. |
| V4 | Held where the view's id is its own; where an entity shares it, a declared query's name now places the call on the view (`TopologyJson.declaredQueries`). |
| V5 | Pekko's `ServerSentEvent.heartbeat` is an event with **no data**, not a comment line; an `EventSource` does not dispatch it. And a heartbeat that refused to start beside a short idle timeout broke the socket suites, which run with a 3 s idle timeout: the heartbeat is now the configured interval or half the idle timeout, whichever is sooner, and never refuses. |
| V6 | Did not hold. `CartRow` kept quantities, not names; it gained `names: Map[String, String] = Map.empty`, which a row written before reads as no names. |
| V7 | Held. Two upserts of one key in one transaction are announced once. |
| V8 | Did not hold: the sidecar does not depend on `grpc`. `ClientService.drain` is the same ready-aware loop, small, on `ServerCallStreamObserver`. |
| V9 | Held: `QueryCheck` admits `pg_sleep`. |

## Changed from the plan, and why

- **The announcement is in the write's own statement**, a data-modifying CTE (`WITH written AS (INSERT …
  RETURNING row_key) SELECT count(pg_notify(…)) FROM written`) in `ViewStore.upsert` and `delete`, not a
  second statement paired by a `ViewWrites` helper. Every path that writes a row announces it, projection
  sessions included, and none can take one without the other. A key of 7 900 bytes or more is written and
  not announced, in SQL.
- **The listener calls each watch's registry directly**, registered before the rows now are read; a
  `BroadcastHub` attaches a consumer asynchronously, and a write committed in that gap would be missed.
- **The keyed buffer is the watch's last stage**, passing the rows now through with backpressure and
  bounding only what follows the caught-up marker. Pekko's eager concatenation pulls one element early
  from its next part, which let one row past the bound when the buffer sat ahead of it.
- **Streamed calls are pairs of their own** in `CallCounts` (a second map under the same key), since the
  packed key has no bit to spare.
- **A reader of server-sent events that goes away** is learned of only when the server next writes, so a
  quiet watch served that way is held until a heartbeat or two after its page closes. The documentation says
  so.
- **The row frame is `WatchedRow`**, not `Row`, so that no language's generated code holds a bare `Row`.
- **`DeclaredQuery.watchable`** is the field; `.watched` is the companion's declaration, since a case-class
  member of that name would shadow it.
- **Conformance** holds `view.stream-named` and `view.watch-named` in the Scala, Python and TypeScript
  references. The thousand-row past-the-limit measure and the watch of one row are held by the Scala
  features rather than by the conformance suite, whose cases run through each language's HTTP routes.

