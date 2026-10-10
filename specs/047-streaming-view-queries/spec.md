# Feature Specification: Streaming View Queries — Rows a Part at a Time, and a Query Kept Open

**Feature Branch**: `047-streaming-view-queries`

**Created**: 2026-10-10

**Status**: Draft

**Input**: User description: "Akka's SDK lets a view query answer as a stream: `QueryStreamEffect`
with `queryStreamResult()` streams the rows instead of collecting them, and `@Query(streamUpdates =
true)` lists the result and then keeps the stream open, emitting rows as they come to match. ankka's
view client answers every query whole, with a limit of a thousand rows, and nothing answers after the
rows are read. Let a view's query be answered as a stream of rows, and let a declared query be watched,
so that an endpoint can serve a live listing as server-sent events and a handler can read a large
result without holding it."

## Context

A view's rows live in one Postgres table, one row per row key, the row as JSON. They are read through
`ViewQueries` (`modules/runtime/.../ViewClient.scala`): `get(key)`, `where(condition, limit = 1000)`,
`ordered(condition, order, limit = 1000)`, `all(limit = 1000)`, `count(condition)` and
`ask(declaredQuery, values*)`, each with an `…Async` twin returning a `Future[Vector[Row]]`. A
declared query (`DeclaredQuery(view, name, statement)`, feature 031) is checked once at start by
`QueryCheck` and run by `ViewQueries.askNamed` in a read-only transaction with a statement timeout;
the sidecar's `Client.Query` rpc answers a process `QueryReply { Payload rows }`, whole, for `get`,
`all` and a declared query with `values` and an optional `limit` (1.13). A module's `query` import
does the same. Every answer is a `Vector` in memory before the first row reaches the caller, and every
answer stops at its limit.

The platform already streams, elsewhere. An agent's reply streams token by token
(`docs/build/streaming.md`, "Only agents stream"); an HTTP endpoint serves `sse(template) { () =>
Source[...] }` and `sseBody`; a gRPC endpoint's `serverStream` answers `Req => Source[Res, ?]`, and
the shopping cart's `WatchCart` is a server stream that polls the entity "after each change, for as
long as the caller watches" — the sample itself doing by hand, against an entity, what a watched view
query would do once; a socket route holds a connection for its life. A stream moves no faster than the
side reading it (`features/grpc/streaming.feature`). None of these can be fed from a view: the view
client has nothing that returns a `Source`.

Two things stand between a view and a stream. The first is in-memory collection and the limit: a
report over a hundred thousand rows cannot be read at all. The second is that a view writes its rows
and tells nobody. `ViewStore.upsert` and `ViewStore.delete` are the two statements every view's writes
go through — a plain view's in `ProjectionSupport`, a keyed view's in `KeyedViewHandlers`, a topic
view's under `ViewGuard`, every one in the change's own transaction — so there is exactly one place a
write could be announced, and nothing is announced. A view is also written by any instance of the
service (a plain view in slices, a keyed view under its lock), so a reader on one instance must learn
of a write on another; the instances share the view's database and form a cluster, and the plan
chooses between the two roads.

Akka offers both forms and says of the second that it is "not meant for service-to-service
propagation of updates" and "does not guarantee delivery". The honest form of that caveat in ankka's
words is that a watched query is live, not a record: it shows what the view writes while the watcher is
reading, and a reader that must see every change reads the source — an entity's events, a topic — with
a consumer. A rebuild (024, 031) empties a view under its exclusive lock and reads every source again;
a watcher of an emptied view holds rows that are gone.

A module cannot stream (`docs/reference/wasm-abi.md`: a module answers every call whole, and a route
or handler declared streaming is refused at start), so a module reads a view as it does today; a
process behind the sidecar can, over a server-streaming rpc, as `InvokeStream` already is.

This feature makes these decisions.

- **A query is answered as a stream where it is answered whole today.** A declared query, `all`
  and, in Scala, a condition can be asked for as a stream of rows: each row reaches the caller as the
  database yields it, in the statement's order, no row held before the first is delivered, and no
  limit unless the caller gives one. A stream is bounded by the same statement timeout a whole answer
  is; one the database ends early ends in a failure the reader sees, never silently short.
- **A declared query, or one row, can be watched.** A watch first delivers every row the query
  matches now, then, for as long as the watcher reads, each row that comes to match, matches again
  after a change, or stops matching or is deleted — the last as a removal naming the row key. A watch
  of one row by key delivers the row now and then each version of it. A watch ends when the watcher
  stops reading, when the view is emptied for a rebuild, or when the instance serving it stops; it
  ends with the reason, and a watcher who still wants the rows watches again.
- **Live, coalesced, in order per row.** A watcher is never given an older version of a row after a
  newer one, and a row that changes faster than the watcher reads reaches it fewer times than it
  changed. A change written on any instance reaches a watcher on any instance. What happened while a
  watcher was not reading is not replayed, and the documentation says so beside how to read a source
  instead.
- **Served as any stream is.** A row stream and a watch are Pekko Streams `Source`s in Scala, so
  `sse`, `serverStream` and a socket route carry them unchanged; a Python or TypeScript process reads
  them as an async iterable over a server-streaming rpc; a module is refused a stream at start, as its
  routes are, and reads a view whole.
- **Bounded on the instance.** Open watches on an instance are bounded by a platform setting; a watch
  beyond it is refused naming the bound. A watch is one call in the topology, counted as handled
  when it ends, and the ACL that admitted the route or method admits the watch.

What this feature is not: a change to how a view is written, a change to what a declared query may
read (its checks gain one rule, for a watched query only), a cross-view or cross-table query, a stream
of an entity's changes to a caller (that is a consumer, or a watch over a view of it), or a guarantee
of delivery to a watcher. It is not offered to a module.

## Clarifications

### Session 2026-10-10

- Q: Which declared queries may be watched — any, or only one whose results are rows of the view? →
  A: Only a statement whose results are rows of the view and that has no `LIMIT` is watchable; `ORDER
  BY` without a limit is allowed and orders the rows-now phase only. A watch of any other declared
  query (an aggregate, a `GROUP BY`, a limit) is refused at start by the declared query check, naming
  the reason; the query stays askable whole or as a stream.
- Q: Does a watcher learn when it has the rows now? → A: Yes. A watch has three element kinds — a
  row, a removal and one caught-up marker — and the marker is given once, after the rows now and
  before any change, at once when the query matches nothing now. A watch of one row gives it after
  the row now, or at once when the row does not exist yet.
- Q: What happens when a watcher stops reading while distinct rows pile up? → A: Each watch holds its
  unread rows, one per row key (coalesced), up to an unread bound set for the platform with a
  shipped default and settable per watch. Overflow follows a strategy the watcher chooses from
  drop-head (the default), drop-tail, drop-new, drop-all and fail; backpressure is not offered,
  since a watcher must never slow a view's writes. Under a drop strategy a dropped removal leaves the
  watcher holding a row that is gone until that row is next written or the watcher watches again, and
  the documentation says so; `fail` ends the watch with the reason "unread" for a watcher that would
  rather know.
- Q: Does a removal carry the row's last version? → A: No. A removal is the row key alone, whether
  the row stopped matching or was deleted; a watcher that wants the last version holds the one it was
  given.
- Q: What are the shipped defaults of the watch bound and the unread bound? → A: 1000 open watches
  per instance, 256 unread rows per watch.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A page shows the open carts as they change (Priority: P1)

A shop's back office lists open carts. The endpoint serves `GET /carts/open` as server-sent events
from a watch of the `open-carts` declared query: the browser first receives every open cart, then a
cart as it is opened, again as items are added, and a removal when it is checked out. The developer
wrote one route and no polling.

**Why this priority**: This is Akka's `streamUpdates` use case and the one the sample does by hand
today against an entity. A live listing from a view is the feature.

**Independent Test**: In the test kit, watch `open-carts` through an SSE route; open two carts, add an
item to one, check the other out; assert the events arrive in that order with the rows as written and
the checked-out cart's removal naming its key, and that nothing arrives for a cart opened after the
watcher stops reading.

**Acceptance Scenarios**:

- added `features/view-streams/watching.feature`: a watcher is first given every row the query matches now
- added `features/view-streams/watching.feature`: a watcher is told it is caught up after the rows now and before any change
- added `features/view-streams/watching.feature`: a watcher of a query that matches nothing now is told it is caught up at once
- added `features/view-streams/watching.feature`: a watcher is given a row that comes to match
- added `features/view-streams/watching.feature`: a watcher is given a row it has that is written again
- added `features/view-streams/watching.feature`: a watcher is given a removal for a row it has that stops matching
- added `features/view-streams/watching.feature`: no removal is given for a row the watcher never had
- added `features/view-streams/watching.feature`: an HTTP endpoint serves a watch as server-sent events
- added `features/view-streams/watching.feature`: a watch ends when the watcher stops reading
- added `features/view-streams/watching.feature`: what was written while nobody watched is not given to a later watcher

---

### User Story 2 - A handler reads a large result a row at a time (Priority: P1)

A nightly export reads every row of an `orders` view, two hundred thousand of them, into a file. The
timed action asks the declared query `all-orders` as a stream and writes each row as it arrives; the
service's memory does not grow with the result, and the stream has no limit.

**Why this priority**: The thousand-row limit is a wall a real service hits in its first month; a
stream is the only honest answer to "every row".

**Independent Test**: Fill a view with more rows than the default limit; ask a declared query and
`all` as streams and assert every row arrives in the statement's order; ask the same query whole and
assert it stops at the limit. With a statement timeout the stream cannot meet, assert the stream ends
in a failure naming the timeout rather than ending short.

**Acceptance Scenarios**:

- added `features/view-streams/row-streams.feature`: every row is given as a stream, past the limit of a whole answer
- added `features/view-streams/row-streams.feature`: a declared query answered as a stream gives every matching row in the statement's order
- added `features/view-streams/row-streams.feature`: a whole answer keeps its limit
- added `features/view-streams/row-streams.feature`: a stream the database ends early ends in a failure and not as a shorter answer
- added `features/view-streams/row-streams.feature`: a stream is produced no faster than it is read
- added `features/view-streams/row-streams.feature`: a stream stops being produced when the handler goes away

---

### User Story 3 - One row is watched by its key (Priority: P2)

A cart page watches its own cart: the endpoint serves `GET /carts/{id}/live` from a watch of the row
`id`, so the page shows the cart as the view writes it, and is told when the cart's row is deleted.

**Why this priority**: The commonest live page is one thing's, and a watch of one row needs no
declared query.

**Independent Test**: Watch the row "c1"; write it three times and delete it; assert the watcher is
given the row now, versions in order and never an older after a newer, and a removal last.

**Acceptance Scenarios**:

- added `features/view-streams/watching.feature`: one row watched by its row key is given now and then each version written
- added `features/view-streams/watching.feature`: a watched row that is deleted is given as a removal
- added `features/view-streams/watching.feature`: a watched row that does not exist yet is given when it is written

---

### User Story 4 - A watch is honest about what it is (Priority: P2)

A watcher on one instance sees a row written on another. A watcher who reads slowly is given the
latest version of a row rather than every version. A view emptied for a rebuild ends every watch of it
with the reason. An instance refuses a watch beyond its bound, naming it. The topology counts a watch
as one call.

**Why this priority**: A live stream without these rules is one a developer learns not to trust; each
is cheap to state and to test, and expensive to discover.

**Independent Test**: Two instances of a service: watch on one, write on the other, assert delivery.
Watch with a reader that does not read; write a row fifty times; assert the reader is given the last
version and fewer than fifty. Raise the view's version; assert every watch ends naming the rebuild.
Open watches to the bound and one more; assert the refusal names the bound.

**Acceptance Scenarios**:

- added `features/view-streams/watch-rules.feature`: a row written on another instance reaches a watcher
- added `features/view-streams/watch-rules.feature`: a watcher that reads slowly is given the last version of a row and fewer than were written
- added `features/view-streams/watch-rules.feature`: a watcher with more unread rows than its unread bound loses the oldest unless it chose otherwise
- added `features/view-streams/watch-rules.feature`: a watcher that chose to fail on overflow is told its watch ended unread
- added `features/view-streams/watch-rules.feature`: every watch of a view ends when the view is emptied for a rebuild
- added `features/view-streams/watch-rules.feature`: a watch ends when the instance serving it stops
- added `features/view-streams/watch-rules.feature`: a watch beyond the instance's bound is refused naming the bound
- added `features/view-streams/watch-rules.feature`: a watch is one observed call in the topology
- added `features/view-streams/watch-rules.feature`: a watch is evaluated when a row is written
- added `features/view-streams/watch-rules.feature`: a watch of a declared query whose results are not the view's rows is refused at start

---

### User Story 5 - A process streams and a module is told it cannot (Priority: P2)

A Python or TypeScript service asks a declared query as a stream and watches one, reading an async
iterable; a Rust module that declares a streaming route or asks for a stream is refused at start, as a
streaming route is today, and the documentation says a module reads a view whole.

**Why this priority**: The SDKs promise the same component in any language; a stream only Scala can
read breaks that for the two languages that can, and must be stated for the one that cannot.

**Independent Test**: The conformance suite gains a streamed query and a watch in each SDK; the module
suite asserts the refusal names the stream.

**Acceptance Scenarios**:

- added `features/view-streams/languages.feature`: every row is given as a stream in every language that can read one
- added `features/view-streams/languages.feature`: a watcher is given the rows now and then a row that comes to match in every language that can read one
- added `features/view-streams/languages.feature`: a runtime from before view streams refuses a program that asks for one
- added `features/view-streams/languages.feature`: a module that would stream a view is refused when it is started

---

### User Story 6 - The documentation says what a stream and a watch are (Priority: P3)

The views guide shows a streamed query and a watch in each language, states that a watch is live and
not a record, says how a watch ends, and points a reader who needs every change to a consumer; the
limitations page says a module reads a view whole and a watch guarantees no delivery; the
divergences page notes the forms match Akka's.

**Acceptance Scenarios**:

- added `features/documentation/view-streams.feature`: the documentation of views describes a query answered as a stream and a watched query
- added `features/documentation/view-streams.feature`: the documentation says a watch is live and not a record
- added `features/documentation/view-streams.feature`: the documentation says what view streams do not do

---

### Edge Cases

- **A watch of a query whose match depends on the time.** A statement that reads `now()` can come to
  match a row nobody wrote; the watch evaluates a row when it is written, so such a row arrives on
  its next write or never. The documentation says a watched query is evaluated on writes.
- **A row that stops matching and the watcher never had it.** No removal is sent: a removal names a
  row the watcher was given.
- **A watch of a query with a limit or an aggregate.** Refused at start: a limit makes "stops
  matching" undecidable from one row, and an aggregate's results have no row key to coalesce or
  remove by. The query is still asked whole or as a stream.
- **A row written and deleted before the watcher reads.** Coalescing delivers the removal alone.
- **A removal dropped by the overflow strategy.** The watcher keeps a row that is gone until that row
  is next written or it watches again; a row dropped that it never had does not appear until its next
  write. The documentation says a watcher that falls behind may hold rows that are gone, and that
  `fail` is for a watcher that would rather know.
- **A watch across a service's instance stopping.** The stream ends with the reason; an SSE client
  reconnects and is given the rows now, so a page shows the current listing again.
- **A keyed view's row written by two sources at once.** Each write is announced; the watcher is given
  the later version, possibly only it.
- **A stream from a view behind its recorded version.** An instance behind reads nothing and writes
  nothing (024); its queries still answer from the table, and a stream does too; a watch on it is
  given what other instances write.
- **A watch through the gateway.** The gateway's HTTP route timeout bounds a response; a watch served
  as SSE through it is subject to the same bound an agent's SSE stream is today, and the documentation
  says so. Lifting the bound is not this feature's. A quiet watch served as SSE is kept open by the
  service's heartbeat, so a view that writes nothing for a minute does not end the watch without a
  reason.
- **Backpressure on a one-shot stream.** A reader that stops reading holds a database cursor; the
  runtime ends a stream not read for the statement timeout, and the reader sees the failure naming it.
- **A watch and the ACL.** The route or method's ACL is checked once, when the watch opens; an ACL
  changed later does not end an open watch, as it does not end an open socket.
- **A watch of a view over a topic.** The same: topic views write through the same store.

## Requirements *(mandatory)*

### Functional Requirements

**Streaming a query**

- **FR-001**: A declared query and `all` MUST be askable as a stream of rows in Scala, Python and
  TypeScript, and a Scala condition and an ordered condition likewise. Each row MUST reach the caller
  as the database yields it, in the statement's order, with no row held back for the rest.
- **FR-002**: A streamed query MUST have no limit unless the caller gives one; a whole query keeps its
  limit.
- **FR-003**: A streamed query MUST run under the same read-only transaction and statement timeout a
  whole query does — the timeout bounds each fetch of the stream's rows, not the stream, which may
  run longer — and when the database ends the statement, or the reader has not read for that same
  timeout, the stream MUST end in a failure that says so, never as a shorter answer.
- **FR-004**: A stream MUST be produced no faster than it is read, and MUST stop being produced when
  the reader goes away.

**Watching**

- **FR-005**: A declared query MUST be watchable: the watcher is first given every row the query
  matches now, then told once that it is caught up, then each row that comes to match or matches
  again after it is written, and a removal naming the row key when a row it was given stops matching
  or is deleted. The caught-up marker MUST be given at once when nothing matches now.
- **FR-006**: One row MUST be watchable by its row key: the row now if it exists, then that the
  watcher is caught up (at once when the row does not exist yet), then each version written, and a
  removal when it is deleted.
- **FR-007**: A watcher MUST never be given an older version of a row after a newer one, and MAY be
  given fewer versions than were written.
- **FR-007a**: A watch MUST hold its unread rows one per row key, up to an unread bound that is a
  platform setting shipped at 256 and that a watcher MAY override. On overflow the watch MUST apply
  the watcher's overflow strategy — drop-head unless the watcher chooses drop-tail, drop-new,
  drop-all or fail — and MUST NOT slow the view's writes. Under `fail` the watch ends with the
  reason "unread". A Python or TypeScript watcher chooses the strategy in its request.
- **FR-008**: A row written by any instance of the service MUST reach a watcher on any instance.
- **FR-009**: A watch MUST end, with the reason, when the watcher stops reading, when the view is
  emptied for a rebuild, or when the instance serving it stops. What was written while nobody was
  reading MUST NOT be replayed to a later watch.
- **FR-010**: A watch MUST be evaluated on the view's writes: a row's match is decided when the row is
  written, by the watched statement with the watcher's values.
- **FR-010a**: Only a declared query whose results are rows of the view, with no `LIMIT`, MAY be
  watched; `ORDER BY` is allowed and orders the rows now. A query is declared watchable; a service
  that declares any other query watchable MUST be refused at start, with the reason, and the query
  MUST stay askable whole or as a stream. A watch of a query not declared watchable MUST be refused
  at the call, naming the declaration.
- **FR-011**: Open watches on an instance MUST be bounded by a platform setting shipped at 1000, and
  a watch beyond the bound MUST be refused naming it.

**Serving**

- **FR-012**: A row stream and a watch MUST be servable by an SSE route, a gRPC server stream and a
  socket route with no adaptation beyond the one a handler writes for any stream, and the route's or
  method's ACL MUST admit the watch as it admits the call.
- **FR-013**: A watch MUST be one observed call in the topology, marked as a stream and counted as
  handled when it ends, and the topology MUST count a stream or a watch of a query apart from a whole
  answer of the same query from the same caller. The trace a watch belongs to is the one of the route
  or method that served it; no span is held open for the watch's life.

**Languages**

- **FR-014**: The sidecar protocol MUST carry a streamed query and a watch to a process as a
  server-streaming rpc at the next protocol version; a runtime at an earlier version MUST refuse a
  process that asks for one, naming the version.
- **FR-015**: A module MUST be refused at start when it declares anything that would stream a view,
  with the reason that a module reads a view whole; a module's `query` import is unchanged.

**Testing**

- **FR-016**: The Scala test kit MUST let a test watch a view and read a stream from it; the Python and
  TypeScript test kits MUST do the same through the sidecar; the conformance suite MUST hold a streamed
  query and a watch.

**Documentation**

- **FR-017**: The views guide MUST describe the streamed query and the watch in each language that has
  them, say a watch is live and not a record, how it ends, and where to read every change instead; the
  limitations page MUST say a module reads a view whole and a watch guarantees no delivery.

### Key Entities

- **Row stream**: a query's rows delivered one at a time as the database yields them, in the
  statement's order, bounded by its statement timeout, with no limit unless given.
- **Watch**: a row stream that stays open after the rows now, delivering rows as they come to match
  and removals as they stop, live and coalesced, until it ends with a reason.
- **Removal**: what a watcher is given for a row it had that no longer matches or was deleted: the row
  key and no row, whichever the cause; the watcher holds the last version it was given.
- **Caught up**: what a watcher is told once, after the rows now and before any change, so a page
  knows it holds the whole listing.
- **Watch bound**: the platform setting for how many watches one instance holds open; 1000 as shipped.
- **Unread bound**: how many rows one watch holds for a watcher that has not read them, one per row
  key; 256 as shipped, set for the platform and overridable per watch.
- **Overflow strategy**: what a watch does when it holds as many unread rows as its unread bound and
  another arrives: drop-head (default), drop-tail, drop-new, drop-all or fail.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A handler reads every row of a view holding more than the whole-query limit, in order,
  with the service's memory not growing with the result, in Scala, Python and TypeScript.
- **SC-002**: A browser reading an SSE route sees a row the view wrote on any instance within one
  second of the write, without the endpoint polling.
- **SC-003**: Under a writer faster than its reader, a watcher is given the last version of every row
  and never an older one after a newer, on every run.
- **SC-004**: Every watch of a view ends, naming the rebuild, within one second of the view being
  emptied.
- **SC-005**: The shopping cart's `WatchCart` server stream can be written as a watch of a view,
  with no polling, and the sample's gRPC streaming feature still passes.

## Assumptions

- The r2dbc driver yields a query's rows as a reactive stream, so a `Source` over them with a fetch
  size costs no in-memory collection; the database's statement timeout bounds each fetch of the
  stream's rows, so the runtime itself bounds a reader that does not read.
- Every write of a view's row goes through `ViewStore.upsert` or `ViewStore.delete` in the change's
  transaction, so an announcement made there is made for every write and only after a committed one
  when it is made on commit.
- A watched statement can be evaluated for one row by wrapping the checked statement as a subquery
  filtered by row key, with the watcher's values, which is why a watched statement selects `row_key`
  beside `payload` and is declared watched; `QueryCheck` already proves the statement reads the
  view's own table alone.
- Instances can tell each other of a write through the shared database or the cluster; the plan
  chooses, and the spec needs only that they do.
- A watch's ending for a rebuild is signalled where `ViewVersions` empties the table under the
  exclusive lock.

## Dependencies

- 031-multi-source-views: declared queries and `QueryCheck`, which a watch evaluates per row.
- 024-replayable-topics: the rebuild that ends a watch.
- 028-websocket-routes and the SSE routes: what carries a watch to a client.
- 020-grpc-endpoint: `serverStream`, and the sample's `WatchCart` this feature can replace.
- 009-polyglot-runtimes: the `Client.Query` rpc gains a streaming twin.
- 016-wasm-hosting: the module's refusal of streams, extended to views.

## Open Questions

- Whether the gateway's HTTP route timeout should be lifted for SSE as it is for gRPC; it bounds an
  agent's stream today and is a platform question wider than this feature.
