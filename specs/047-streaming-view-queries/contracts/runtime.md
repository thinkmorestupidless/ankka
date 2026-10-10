# Contract: the runtime's announcements, listener, watches and settings

Internal to `modules/runtime` and `sidecar`; what a test, an operator or a later feature relies on.

## The announcement

Every write of a view's row runs, in the write's own transaction or projection session, after the
`INSERT … ON CONFLICT` or `DELETE`:

```sql
SELECT pg_notify('ankka_views', $1 || '|' || $2)      -- $1 the table, $2 the row key
```

and a rebuild runs, inside `ViewVersions.rebuild`'s transaction after `TRUNCATE`:

```sql
SELECT pg_notify('ankka_views', $1 || '|!rebuilt')
```

| Property | Holds because |
|---|---|
| delivered only for a committed write | Postgres delivers on commit |
| delivered to every instance | one channel per service database; every instance with a watch listens |
| not delivered to an instance with no watch | it holds no `LISTEN` connection |
| a row written twice in one transaction is announced once | Postgres folds identical payloads in a transaction (V7) |
| a key is at most 7 900 bytes | longer: not announced, logged once per view, the write unaffected |
| the paths | `ProjectionSupport.applyView`, `ViewStateHandler`, `RemoteViewStateHandler`, `ViewVersions.guarded` (`ViewGuard.write`), `KeyedViewEventHandler`, `KeyedViewStateHandler` — all through `ViewWrites` |

`ViewAnnounceSuite` writes through each path on a `LISTEN`ing test connection and asserts the payload.

## The listener

One `PostgresqlConnection` per instance, outside the pool, built from `pekko.persistence.r2dbc.
connection-factory` (`host`, `port`, `database`, `user`, `password`) through `DatabaseTls`'s customizer so
a TLS-only database (`ANKKA_DB_SSL_MODE`, client certificate) is reached the same way. Opened on the first
watch; `LISTEN ankka_views`; `getNotifications()` consumed as a `Source`. On the connection's failure
every open watch ends `ListenerLost` and the next watch reconnects (backoff 1 s doubling to 30 s). The
runtime logs `view listener: listening`, `view listener: lost (…)`, `view listener: reconnected`.

## Watches on an instance

| Setting | Key | Variable | Default |
|---|---|---|---|
| watch bound | `ankka.view.watch-bound` | `ANKKA_VIEW_WATCH_BOUND` | `1000` |
| unread bound | `ankka.view.unread-bound` | `ANKKA_VIEW_UNREAD_BOUND` | `256` |
| fetch size | `ankka.view.fetch-size` | `ANKKA_VIEW_FETCH_SIZE` | `256` |

A watch is refused above the watch bound with `Unavailable` naming it. Evaluation on a notification
`t|k`: the view's pending set gains `k` (once); the view's evaluator runs, per distinct target among
its open watches, `SELECT payload FROM (<sql>) ankka_watched WHERE ankka_watched.row_key = $n` (named)
or `ViewStore.selectByKey` (by key), in one read-only transaction under the statement timeout; each
watch is offered `Row`, `Removed` or nothing. A statement that fails during evaluation (a timeout, a
lost database) is logged and the key is dropped for that round — a watch is live, not a record.

The extension stops in coordinated shutdown's first phase: every watch ends `InstanceStopping`, the
listener connection closes.

## The SSE heartbeat (`modules/http`)

| Setting | Key | Variable | Default |
|---|---|---|---|
| heartbeat | `ankka.http.sse.heartbeat` | `ANKKA_SSE_HEARTBEAT` | `15s` |

Every event stream a route serves (`sse`, `sseBody`, `sseEvents`, `sseEventsBody`, and a process's
`HandleStream`) emits a comment line (`ServerSentEvent.heartbeat`) when nothing else has been sent for the
heartbeat. A heartbeat not shorter than `pekko.http.server.idle-timeout` fails startup, as a socket
keep-alive does (`SocketSettings.problems`), with "the SSE heartbeat (…) must be shorter than the server's
idle timeout (…)".

## Topology

A row stream or a watch is one observed call `caller → view` named by the query (`all`, `where`,
`ordered`, the declared name, or `get` for a row), `streaming: true`, counted when the stream ends:
ok for completion and for `Rebuilt`/`InstanceStopping`; failed for `ListenerLost`, `Unread` and a
timeout. `Observability.made(…, streaming = true)`. A pair is keyed by `(caller, callee, handler,
streaming)`, so a whole `ask(open-carts)` and a `watch(open-carts)` from one handler are two pairs
with their own counts; `TopologyMerge` sums by the same key; a pair that never streamed is unchanged
in the JSON.

## `PlatformVariables`

`RuntimeOnlyPrefixes` gains `ANKKA_VIEW_` and `ANKKA_SSE_`: a descriptor may give them; they reach the
platform's container only; a module's `config` answers them absent. `PlatformVariablesSuite` pins both.
