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

## Traps

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
  `Unavailable` from `PostStop`; the client service retries `Unavailable` briefly, so a rolling
  replacement refuses nothing.
- **`snapshotWhen` sees the state *before* the event it is asked about**, and Pekko may snapshot at
  a sequence the host did not expect. `RemoteStateRecord` carries absolute positions and the
  predicate accepts the process's snapshot at its own sequence or the next one — which is why a
  process target's snapshot row sits at 3 *or* 4 where the in-process one sits at 3.
