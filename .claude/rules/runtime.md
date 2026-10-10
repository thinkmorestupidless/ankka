---
paths:
  - "modules/core/**"
  - "modules/sdk/**"
  - "modules/runtime/**"
  - "modules/http/**"
  - "modules/testkit/**"
  - "samples/**"
---

# The runtime: Pekko, entities, workflows and endpoints

## Endpoints receive `EndpointClients`, not a `ComponentClient`

`HttpServer.of` / `.at` take `EndpointClients => HttpEndpoint`, bundling `componentClient`
and `viewClient` — an endpoint that lists things needs the read side too. Registration is
an explicit lambda:

```scala
HttpServer.of(clients => ShoppingCartEndpoint(clients.componentClient))
```

The lambda cannot be shortened to `ShoppingCartEndpoint(_.componentClient)`: the
placeholder binds to the *inner* application, so that parses as passing a function where
a `ComponentClient` is expected. A bundle was chosen over an overload because two
factory shapes would break lambda parameter inference at every call site.

A handler's return value decides the response through `ToResponse`, which since feature 011
also carries headers: `Respond(body, status, headers)` wraps any body with a status and
headers of the handler's choosing, `Respond.redirect` is a 303 with a `Location`, `Html` is a
page and `Bytes` names its own content type. That is the whole of what a website (`ankka-cloud`)
needs from the module beyond a JSON API — `Set-Cookie` and `Location` — and it is deliberately
not a template engine, a session store or a cookie API: those belong to the application.

## Every component but an entity and a view calls another service, through one client

`AnkkaService.services` is the one `HttpServiceClients`, made before the components (lazily — it reads
the certificate on first call) and handed to endpoints, workflow steps, consumers, timed actions and
agents (feature 025). An entity's and a view's contexts have no `services`; a workflow's is
`StepScope.stepsOnly`, as its secret store is. A Python or TypeScript process asks its sidecar with
`Client.Request` (protocol 1.8) — a module through its `request` import (1.10, `.claude/rules/wasm.md`) —
and `ClientLogic.request` makes the call with the same client, as the
handler the forwarded `ankka-caller` names — believed only when declared, so it can refuse an entity's
handler and a workflow's command handler. What every door shares is the client's, not the sidecar's:
`ServiceUnanswered` for no answer, `ankka.service-client.timeout` (`ANKKA_SERVICE_CLIENT_TIMEOUT`, a
platform setting), the header rule (`OutboundHeaders`, held to the proxy's list by
`OutboundHeadersSuite`) and a span per call (`Observability.calling`). `ScriptedService` (a stand-in on
loopback) and `ScriptedServices` (a unit double) are the test kit's; `AnkkaTestKit.start(…,
localServices = …)` sets `ankka.local-services` for one service rather than the whole JVM.

## Timers: one row each, and the sweeper changes only the row it read

A timer is a row of `ankka_timers` (feature 032). One that fires once has a finite `due_at`; a recurring
one has `due_at = 'infinity'`, its next run in `fire_at`, its period in `period_millis`, and the due a run
is for in `due_for`, which a handler is told (`TimedActionContext.dueTime`, `ankka.due`). `Cadence.next`
is the one place a next due is worked out — the due just run plus a period, or the first cadence point
still to come when that has passed — and `TimerStore` holds every statement. A recurring timer set again
for the same handler and period keeps its next due, in one upsert, so a service sets its timers at start.
A recurring timer whose handler this instance lacks is deferred, not dropped: an older version of the
service may hold the sweeper during a deploy that adds the handler. `TimerProbe` (testkit) is what a test
reads due times from.

## Traps

- **A recurring timer's `due_at` is `'infinity'` on purpose.** A runtime from before recurring timers
  selects `due_at <= now` and deletes by name after a run, so a finite `due_at` is fired once and deleted
  by the old sweeper — which during a rolling update is the one running, on the oldest pod. Never "tidy" it
  into a nullable column. One row per name is also what keeps a name to one timer while old and new
  instances both write it. `LegacyTimers` (testkit tests) is `v0.10.0`'s statements verbatim and
  `TimerUpgradeFeatures` holds the table to them; never update it to match `TimerStore`.
- **The sweeper changes only the row it read.** Every statement after a handler returns is matched on the
  due time it read, not the name alone: a handler that set its own timer again, cancelled it or replaced
  it rewrote that row first, and a delete by name deleted what the handler had just set — the only way to
  recur, before feature 032, removed its own next run, and no test had a handler that set its own timer.
- **A local database keeps the timers table its volume was created with.** On one from before recurring
  timers the new due query fails with SQLSTATE `42703`; the sweeper and the scheduler fall back to the
  previous release's statements (`TimerStore.legacy*`), so timers that fire once keep working and only a
  recurring timer is refused. Found by a run by hand: as first built, every timer stopped there.

- **The JDK's HTTP client sends a `GET` or a `HEAD` twice when its connection closes before any answer.**
  It reads the closed connection as an expired pooled one and retries an idempotent-by-name method once;
  no setting turns that off (`jdk.httpclient.disableRetryConnect` covers only a refused connection). A
  `POST`, `PUT`, `DELETE` or `PATCH` is sent once. `ServiceClientSuite` pins both counts, and the service
  client's documentation says it; "no retries" means none of ankka's.

- **`Sink.last`, not `Sink.head`, on r2dbc connection publishers.** `head` cancels
  upstream on the first element; cancelling mid-handover means the pool never gets the
  connection back, and a few queries drain it.
- **Do not tune `pekko.persistence.r2dbc.behind-current-time` down.** It guards against
  reading events whose commit timestamp is still in flight. Setting it to zero made a
  suite 15× *slower*, not faster.
- **Guard global timeouts on `startedAtMillis > 0`.** A workflow that has not started has
  no start time, and treating `0` as one makes `elapsed` the whole Unix epoch — firing
  the timeout instantly and failing the workflow before its first step runs.
- **Never touch `ActorContext` (including `ctx.log`, `ctx.system`) from a `Future`
  callback.** It is not thread-safe. Doing so in the timer sweeper made every reschedule
  throw, silently turning "retry with backoff" into "retry immediately, forever, with the
  attempt counter stuck at zero". Capture what async work needs while inside the actor.
- **SSE payloads must be JSON-encoded.** Raw text in a `data:` field loses a leading
  space to the protocol's own rules, and a newline inside a token splits the frame — both
  silent corruptions that only appear on text a model happened to generate.
- **Literal route segments outrank parameters.** Without explicit specificity ordering,
  `/chat/{session}` swallows `/chat/awkward` purely by declaration order.
- **A `CommandError` thrown from a workflow command handler left the caller unanswered** until it timed
  out: the engine replied only to returned effects. It is now answered as the refusal it is. An entity
  handler that throws still fails the command differently — prefer returning `effects.error`.
- **An extension looked up in a constructor breaks every suite that builds the class without a system.**
  `ViewQueries` read `Observability(system)` eagerly, and `ControlPlaneRoutesReferenceSuite` builds the
  endpoints with no actor system to list their routes, so it failed with a `NullPointerException` deep in
  `OrganizationEndpoint`. A lookup that is only needed when a query runs is a `lazy val`.
- **A key value deletion is a persisted state, never a row delete.** `Stored(empty, deleted = true)`
  at the next revision. Removing the row (`PekkoEffect.delete`) is what it did before: the plugin
  emits nothing for it, so no view's row was removed and no consumer's deletion handler ran, and
  after a restart the revision began again from one — an entity created again looked older than
  its own deletion. `KeyValueDeletionSuite` showed five of its eight cases failing on that code.
  A deleted record is never decoded: a remote host writes one with no payload.
- **`testkit` depends on `agent`,** so a suite that needs `EventSourcedTestKit`, `TestTransport` or
  `AnkkaTestKit` for an agent-module type lives in `testkit/src/test`. `EntityRouter` there routes real
  calls to real entity test kits by id, which is how client-side orderings are tested without a runtime.
- **Two sharded kinds may not share a component id.** Sharding keys by the id alone, so an agent and an
  autonomous agent both named `helper` would share one region; `ComponentRegistry` refuses it.
- **On SIGTERM every JVM shutdown hook runs at once, and Pekko's terminates the actor system.**
  A service's own hook calling `terminate()` raced Pekko's coordinated shutdown: a gRPC stream
  still inside the server's shutdown grace lost its materializer and ended `INTERNAL` instead of
  `UNAVAILABLE`, on some k3s runs and not others. Coordinated shutdown now *starts* stopping the
  extensions in its first phase and *waits* for them in its last (`AnkkaService.registerShutdown`;
  the stop runs once, whoever asks first), that phase's timeout raised to 20s in `reference.conf`;
  `ShutdownOrderSuite` (testkit) runs only coordinated shutdown and fails without it. **Do not make
  the first phase wait**: holding the cluster leave and shard handoff until every extension had
  stopped made `ExposureClusterSuite`'s rolling restart under load time out, every run.
- **The in-process event sourced host resurrected deleted state**, found by the conformance suite's
  `es.delete-then-fresh`: the fold applied the first event after a deletion marker onto the kept
  old value while the handler had been shown `emptyState`, so a deleted entity written to again
  answered with both lives' events. The fold now starts from `emptyState` after a deletion or an
  expiry. The remote host never had the bug: it drops the session and re-opens with no snapshot.
- **A Pekko stash is dropped when the actor stops**, and a remote entity waiting on its process is
  exactly the actor that stops mid-command in a hand-off: every stashed caller would time out with
  no answer. The remote hosts keep an explicit queue in the actor's state and answer it
  `Unavailable` from `PostStop`; the client service retries `Unavailable` briefly.
- **Sharding drops a call that reaches a leaving region after its shard's hand-off began.**
  `ShardRegion` on `HandOff` discards what it buffered for the shard "to avoid re-ordering" and logs
  `Dropping [n] buffered messages to shard [s] during hand off`; the caller gets no answer at all and
  times out at `ankka.ask-timeout`. It cost the sidecar's rolling-restart cases about one call in five
  hundred, on half the runs, JVM services being exposed exactly the same way. A query goes through
  `CallTransport.askQuery`, which `ShardingTransport` sends again every `ankka.query-resend-after` within
  the one ask timeout (`QueryResendSuite`); a command is never sent again, since a dropped command and a
  slow one look alike, and `docs/reference/limitations.md` says so. Retrying `Unavailable` longer does
  not help: nothing comes back to retry.
- **`snapshotWhen` sees the state *before* the event it is asked about**, and Pekko may snapshot at
  a sequence the host did not expect. `RemoteStateRecord` carries absolute positions and the
  predicate accepts the process's snapshot at its own sequence or the next one — which is why a
  process target's snapshot row sits at 3 *or* 4 where the in-process one sits at 3.
- **pekko-http's public WebSocket API can send two close codes.** A handler's stream completing closes
  1000 and failing closes 1011 "internal error"; nothing chooses another. "Going away", "too large",
  "unread" and "not text" go through `AnkkaSocketUpgrade`, the one file in `modules/http` that lives in
  pekko's `impl.engine.ws` package to reach `private[http]` frame-level upgrade. It uses five internal
  names and rewrites only the close frame ankka's side sends; `SocketSuite` (testkit) asserts every code
  on the wire, so a pekko-http upgrade that breaks it is red. Keep the internals in that file.
- **pekko-http ends a connection idle for sixty seconds, upgraded or not.** A socket nobody writes to is
  cut off after a minute on a laptop, gateway or no gateway, unless it is pinged. The shim builds pekko's
  message stack itself, so the keep-alive is the `WebSocketSettings` handed to it — setting it on the
  server binding does nothing — and a keep-alive not shorter than the idle timeout fails startup.
- **A span begun and held open is lost.** The recorder skips a slot still in flight and reuses it once
  enough newer spans exist, so a socket's span is recorded whole when it closes (`Recorder.reserve` and
  `record`), and while it is open its handler's calls sit in a trace whose root is not there yet.
- **The pool must replace a dead connection before a caller sees it** (feature 041, R18): after a
  failover every pooled connection to the old primary is dead, and the plugin's defaults hand one to the
  next caller. `validation-query = "SELECT 1"` and `acquire-retry = 20` on the connection factory, and
  `TCP_KEEPALIVE` from `DatabaseTls`.
- **`HistoryLines` records the database's line at start, before any projection publishes**, and a
  database without the table keeps random message ids rather than failing the start. On the platform the
  line is the cluster `ANKKA_DB_HOST` names (`ankka-db-rw`), since naming it on the pod would roll every
  service on upgrade; a switched service is given `ANKKA_DB_LINE`.
