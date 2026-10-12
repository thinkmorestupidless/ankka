# Contract: the Scala API

What a Scala service sees. Everything here is additive; no existing signature changes.

## Declaring

```scala
object CartRows extends View.Companion[CartRowsView, CartEvent, CartRow]("carts"):
  val openCarts = query("open-carts")(
    s"SELECT row_key, payload FROM $table WHERE (payload::jsonb ->> 'checkedOut') = 'false'"
  ).watched                                   // may be watched; checked at start (below)
  val byCustomer = query("by-customer")(s"SELECT payload FROM $table WHERE … = :customer")
                                              // askable, streamable, not watchable
```

`DeclaredQuery.watched` is `false` unless `.watched` is called. A keyed view declares the same way.

### What a watched statement may say

Checked at start by `QueryCheck`, after every rule a declared query already meets. Each refusal is the
service not starting, with the message shown:

| Rule | Refusal text (after `view 'v' declares the watched query 'q', `) |
|---|---|
| the outermost select names `row_key` and `payload` (or `*`) | `which does not select row_key and payload; a watched query gives the view's rows` |
| no `LIMIT`, `OFFSET`, `FETCH` at the outermost level | `which has a limit; a watched query decides each row alone` |
| no `GROUP BY`, `DISTINCT`, aggregate function at the outermost level | `which aggregates; a watched query gives the view's rows` |

`ORDER BY`, `WITH` (recursive included), joins of the view's own table and subqueries are allowed.

## Asking as a stream

On `ViewQueries[Row]` (reached as today through `viewClient.forView(Companion)`), beside each existing
form:

```scala
def allStream(limit: Option[Int] = None): Source[Row, NotUsed]
def whereStream(condition: SqlFragment, limit: Option[Int] = None): Source[Row, NotUsed]
def orderedStream(condition: SqlFragment, order: SqlFragment, limit: Option[Int] = None): Source[Row, NotUsed]
def askStream(query: DeclaredQuery, values: (String, String)*): Source[Row, NotUsed]
def askStream(query: DeclaredQuery, limit: Int, values: (String, String)*): Source[Row, NotUsed]
```

Rules:
- Each row reaches the reader as the database yields it, in the statement's order; nothing is held for
  the rest. No limit unless given.
- One read-only transaction, the same statement timeout a whole query has; a statement the database
  ends fails the stream with `CommandError(Timeout, …)`; a reader that does not read for the statement
  timeout fails it with `CommandError(Timeout, "the stream was not read for …")`. Never a shorter answer.
- Cancelling the stream (the reader goes away) ends the statement and returns the connection.
- The whole forms keep their limit of 1000 (`a whole answer keeps its limit`).
- The blocking `ask`/`all`/… and their `…Async` twins are unchanged.

## Watching

```scala
def watch(query: DeclaredQuery, values: (String, String)*): Source[WatchEvent[Row], NotUsed]
def watch(query: DeclaredQuery, watching: Watching, values: (String, String)*): Source[WatchEvent[Row], NotUsed]
def watchRow(key: String, watching: Watching = Watching()): Source[WatchEvent[Row], NotUsed]
```

Rules:
- Elements: every `Row` the query matches now (in the statement's order), then `CaughtUp` once, then
  for as long as the reader reads: `Row(k, row)` when `k` is written and matches, `Removed(k)` when `k`
  was given and is written so it no longer matches or is deleted. A key never given is never removed.
- `watch` of a query not declared `.watched` fails the stream at once with `CommandError(BadRequest,
  "view 'v' declares the query 'q' without watched; declare it .watched to watch it")`.
- A watch beyond the instance's bound fails at once with `CommandError(Unavailable, "this instance
  holds 1000 open watches, its watch bound")`.
- The stream completes normally only when the reader cancels. It fails with `WatchEnded(reason)` for
  `Rebuilt`, `InstanceStopping`, `ListenerLost`, and `Unread` (only under `Overflow.Fail`).
- Coalesced per key under `watching.unread` (default `ankka.view.unread-bound`) with
  `watching.overflow` (default `DropHead`); never an older version after a newer one.
- The values are the query's declared values, as for `ask`; a missing or extra value is `BadRequest`.
- Attribution: the watch is one observed call from the calling handler to the view, named by the
  query (or `get` for `watchRow`), marked streaming, counted when it ends.

## Serving

```scala
extension [M](source: Source[WatchEvent[Row], M]) def asSse(using JsonValueCodec[Row]): Source[SseEvent, M]
```

Maps `Row` → event `row` with `{"key":"c1","row":{…}}`, `Removed` → `removed` with `{"key":"c1"}`,
`CaughtUp` → `caught-up` with `{}`, and recovers `WatchEnded(r)` into a final event `ended` with
`{"reason":"rebuilt"}` (`"instance-stopping"`, `"listener-lost"`, `"unread"`) then completes. Any other
failure is left to fail the stream. Used as:

```scala
sseEvents("/carts/open")(_ => views.watch(CartRows.openCarts).asSse)
sseEvents("/carts/{id}/live")((id: String) => views.watchRow(id).asSse)
serverStream(METHOD_WATCH_CART)(req => views.watchRow(req.cartId).collect { case WatchEvent.Row(_, r) => toProto(r) })
socket("/carts/live") { s => views.watch(CartRows.openCarts).asSse.map(_.data).runWith(Sink.foreach(s.send)); … }
```

A route handler reads the request (caller, path values) *before* returning the `Source`: the stream is
drained on another thread later, where `request` is not available. The route's ACL admits the watch
when it admits the request; nothing re-checks it while the watch is open.

## Test kit

`AnkkaTestKit` needs no new method: a test calls `kit.service.viewClient.forView(…).watch(…)` and
reads with a `TestSink` / `Sink.queue`. `kit.startPeer()` gives the second instance for the
cross-instance scenarios; `kit.stopService()` the stopping instance.

## `Observability`

`made(origin, callee, handler, outcome, durationNanos, streaming: Boolean = false)` — the one new
parameter; `CallCounts` already carries `streaming`.
