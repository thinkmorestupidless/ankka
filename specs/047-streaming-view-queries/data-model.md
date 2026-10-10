# Data Model: Streaming View Queries

No new table, no journal event, no DDL: a stream reads the view's table as a whole query does, and a
watch is runtime state on one instance. What follows are the values the feature adds, where each lives,
and the rules that hold them.

## In `sdk` (what a developer sees)

### `DeclaredQuery`

`final case class DeclaredQuery(view: ComponentId, name: String, statement: String, watchable: Boolean = false)`
— `watchable` is set by `.watched`, an extension on each companion that replaces the declared entry (a case-class member named `watched` would shadow it). Unchanged for every
query declared today (the default is `false`; descriptors and discovery read absent as `false`).

### `WatchEvent[Row]`

```
enum WatchEvent[+Row]:
  case Row(key: String, row: Row)      // the row as the query sees it now
  case Removed(key: String)            // the row key and no row: it stopped matching or was deleted
  case CaughtUp                        // once, after the rows now and before any change
```

Rules: `CaughtUp` is given exactly once per watch, after the last row now (at once when none);
`Removed(k)` is given only for a `k` the watch has given as a `Row`; no `Row(k, older)` follows a
`Row(k, newer)`.

### `Watching`

```
final case class Watching(unread: Option[Int] = None, overflow: Overflow = Overflow.DropHead)
enum Overflow: case DropHead, DropTail, DropNew, DropAll, Fail
```

`unread` overrides `ankka.view.unread-bound` (256) for this watch; must be ≥ 1.

### `WatchEnded`

`final case class WatchEnded(reason: WatchEnd) extends RuntimeException` with
`enum WatchEnd: case Rebuilt, InstanceStopping, ListenerLost, Unread`, each with a sentence the
developer reads (`"the view was emptied for a rebuild"`, `"the instance serving the watch stopped"`,
`"the instance lost its connection for view changes"`, `"the watcher did not read; the unread bound is
256"`). A watch that ends because the watcher stopped reading raises nothing (nobody is there).
Mapped for a client: `CommandError(ErrorCode.Unavailable, reason)` over gRPC and the protocol; an
`ended` event over SSE (contracts/serving.md).

## In `runtime`

### `CheckedQuery`

`final case class CheckedQuery(name, sql, values, watched: Boolean)` — `QueryCheck` sets `watched`
from the declaration and, when set, has already applied the watched rules (contracts/scala-api.md
"What a watched statement may say"). `ViewQueries.watch(q)` refuses `watched = false` at the call.

### `ViewStore.announce(table, key): SqlFragment`

`SELECT pg_notify('ankka_views', $1 || '|' || $2)` with `table` and `key` bound. The channel is one per
service database. The payload grammar is `<table>|<key>` for a write (upsert or delete; the receiver
reads the table to tell) and `<table>|!rebuilt` for a rebuild. A row key may not contain `|`? It may:
the receiver splits on the **first** `|`, and a table name holds only `[a-z0-9_]`. A payload beyond
7 900 bytes is not announced; the write still happens, and the runtime logs the key's length once per
view (a limit the documentation states).

### `ViewWrites`

The one function that turns a row write into its statements: `upsert(table, key, payload)` →
`Vector(ViewStore.upsert(…), ViewStore.announce(…))`, `delete(table, key)` →
`Vector(ViewStore.delete(…), ViewStore.announce(…))`. Every write path (research R3) runs both in its
own transaction or session.

### `ViewListener` (one per instance, lazily started)

| Field | Meaning |
|---|---|
| `connection: PostgresqlConnection` | dedicated, unpooled, `LISTEN ankka_views` |
| `state: Idle | Listening | Reconnecting(attempt)` | |
| `subscribers: Map[table, ViewWatches.Entry]` | which views have watches on this instance |

Transitions: `Idle → Listening` on the first watch; `Listening → Reconnecting` on the connection's
failure (every subscriber's watches end `ListenerLost`); `Reconnecting → Listening` with backoff
(`ankka.view.listener-backoff`, 1 s to 30 s, not a variable) on the next watch's demand;
`→ Idle` at the extension's stop (every watch ends `InstanceStopping`).

### `ViewWatches` (one per instance)

| Field | Meaning |
|---|---|
| `bound: Int` | `ankka.view.watch-bound`, 1000 |
| `open: AtomicInteger` | watches open on this instance, every view |
| `byView: Map[view, Entry]` | |
| `Entry.watches: Set[Watch]` | |
| `Entry.pending: LinkedHashSet[key]` | keys notified and not yet evaluated (coalesced) |
| `Entry.evaluating: Boolean` | one evaluator per view at a time |

Invariant: `open ≤ bound`; a `watch(...)` that would exceed it is refused before anything is
registered.

### `Watch` (one per open watch)

| Field | Meaning |
|---|---|
| `target: Named(query: CheckedQuery, values: Map[String, String]) | ByKey(key)` | |
| `given: HashSet[String]` | keys the watch has delivered as a `Row` (for removals) |
| `buffer: KeyedBuffer` | unread elements, one per key, with its `Overflow` |
| `origin: CallOrigin` | captured when the `Source` was built, for the count at the end |
| `phase: RowsNow | Live` | live elements wait in `buffer` while `RowsNow` runs |

Grouping for evaluation: `watches.groupBy(_.target)` — one statement per distinct target per
notification, however many watches share it.

### `KeyedBuffer`

A `GraphStage[FlowShape[WatchEvent, WatchEvent]]` over `LinkedHashMap[String, WatchEvent]`:

| On an element for key `k` | |
|---|---|
| `k` pending | replace in place, move to tail (coalesced) |
| `k` not pending, size < bound | append |
| `k` not pending, size = bound | `DropHead`: remove head, append · `DropTail`: remove tail, append · `DropNew`: ignore · `DropAll`: clear, append · `Fail`: fail `WatchEnded(Unread)` |

`CaughtUp` never passes through the buffer (it is concatenated ahead of the live source).

### Settings (`modules/runtime` and `modules/http` `reference.conf`)

| Key | Variable | Default | Meaning |
|---|---|---|---|
| `ankka.view.watch-bound` | `ANKKA_VIEW_WATCH_BOUND` | `1000` | open watches per instance |
| `ankka.view.unread-bound` | `ANKKA_VIEW_UNREAD_BOUND` | `256` | unread rows per watch unless the watcher gives one |
| `ankka.view.fetch-size` | `ANKKA_VIEW_FETCH_SIZE` | `256` | rows the database yields per fetch of a stream |
| `ankka.http.sse.heartbeat` | `ANKKA_SSE_HEARTBEAT` | `15s` | a comment line on every quiet event stream; must be shorter than the server's idle timeout |

`ANKKA_VIEW_` and `ANKKA_SSE_` are runtime-only prefixes (`PlatformVariables.RuntimeOnlyPrefixes`).

## In `protocol` (1.15)

See contracts/protocol.md for the messages. The values a process sees are the same three element
kinds, the same reasons and the same overflow names, under protobuf's spelling.

## State transitions of one watch

```
opened ──bound exceeded──▶ refused (Unavailable, names the bound)
opened ──registered──▶ RowsNow ──last row now──▶ CaughtUp ──▶ Live
RowsNow | Live ──watcher stops reading──▶ closed (counted ok)
RowsNow | Live ──!rebuilt──▶ ended Rebuilt (counted ok)
RowsNow | Live ──extension stop──▶ ended InstanceStopping (counted ok)
RowsNow | Live ──listener failure──▶ ended ListenerLost (counted failed)
Live ──buffer full, Overflow.Fail──▶ ended Unread (counted failed)
RowsNow ──statement ended / not read──▶ failed Timeout (counted failed)
```
