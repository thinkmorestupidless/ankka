# Contract: the conformance suite

**Feature**: [spec.md](../spec.md) | **Research**: R12, R13

Two suites define a compatible SDK. `ConformanceSuite` (`sidecar/src/test`) drives a *reference
service* through its declared HTTP endpoints and reads the journal, never Scala types, so it can
be pointed at any process; it also runs against the Scala SDK in-process, so the two hosting modes
are held to one definition (FR-025). The *encoding fixtures* (`protocol/fixtures`) are run by
each SDK in its own test runner and prove its default codec (FR-028).

## The reference service

Every SDK ships one, with these components, wire names and routes. The Scala one is in
`sidecar/src/test` (`ConformanceReference`); the Python one is `examples/shopping_cart` plus a
`conformance` entity and endpoint.

| component | kind | handlers |
|---|---|---|
| `shopping-cart` | event sourced | `add-item`, `remove-item`, `checkout`, `get-cart` (query); `snapshotEvery = 3` |
| `conformance` | event sourced | `record` (persists one event per call), `record-many` (n events), `refuse` (error, no events), `no-reply`, `delete`, `expire` (1s), `count` (query), `misbehave` (throws) |
| `profile` | key value | `set`, `get` (query), `delete` |
| `checkout` | workflow | `start`, `status` (query); steps `reserve`, `charge`, `compensate`; `charge` fails when the input says so |
| `cart-rows` | view over `shopping-cart` | query `by-id` |
| `checkout-notifier` | consumer over `shopping-cart` | records checkouts to `conformance` via the client |
| `reminder` | timed action | `remind` (invokes `conformance/record`) |
| `assistant` | agent | `ask`, `stream` (streaming); tool `lookup` (calls `conformance/count`); guardrail `no-secrets` |

| endpoint | prefix | routes |
|---|---|---|
| `carts` | `/carts` | `POST /{cartId}/items`, `DELETE /{cartId}/items/{productId}`, `POST /{cartId}/checkout`, `GET /{cartId}`, `GET /{cartId}/rows` (the view), `GET /awkward` (a literal beside a parameter) |
| `conformance` | `/conformance` | `POST /{id}/{handler}` (a generic forwarder to the `conformance` entity, body passed through), `GET /{id}/count`, `POST /profile/{id}`, `GET /profile/{id}`, `POST /checkout/{id}`, `GET /checkout/{id}`, `POST /remind/{id}`, `POST /ask/{session}`, `GET /stream/{session}` (SSE), `GET /echo` (returns query parameters and two request headers as JSON), `GET /status/{code}` (answers that status), `GET /boom` (throws) |
| `private` | `/private` | `GET /` with `acl = AUTHENTICATED` |

## Targets

```bash
sbt 'sidecar/testOnly *ConformanceSuite'                                          # in-process Scala reference
sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.target=127.0.0.1:9010   # a process listening there
```

`-Dankka.conformance.target` is forwarded to the forked test JVM (`Test / javaOptions`, the trap
in CLAUDE.md). With a target, the suite starts the sidecar in-JVM against `AnkkaTestKit`'s
Postgres; without, it starts the Scala reference with `AnkkaTestKit`. Either way it talks to the
service over HTTP on the address the service reports. The agent behaviours use a scripted
`TestModelProvider` in both modes.

## Behaviours, by name

Each is one munit case whose name is the identifier below, so a failure names the behaviour.

**Discovery**
- `discovery.lists-every-component` — the `Spec` has all eight components and three endpoints, with the wire names and routes above.
- `discovery.read-only-flag` — `get-cart`, `count`, `get`, `status` are `read_only`.
- `discovery.refuses-wrong-major` (process targets only) — a sidecar started with protocol `99.0` refuses and the process's log shows the `ReportError`.

**Event sourced**
- `es.persist-and-reply` — `POST /carts/c1/items` answers `Done`; the journal has one row with manifest `shopping-cart-event`.
- `es.refusal-persists-nothing` — `refuse` answers its code; journal unchanged.
- `es.no-reply` — `no-reply` persists and answers 204.
- `es.recover-after-restart` — three items, restart, `GET /carts/c1` shows three.
- `es.snapshot-on-request` — four `record`s; the snapshot table has one row at sequence 3; restart; `count` is 4.
- `es.delete-then-fresh` — `delete`, then `count` is 0 and `record` works.
- `es.expire` — `expire`, wait 1.5s, `count` is 0.
- `es.serial-per-instance` — ten concurrent `record`s on one id yield `count` 10 and sequences 1..10.
- `es.parallel-instances` — ten ids at once complete within one command's latency ×3.
- `es.fault-is-failed-not-refused` — `misbehave` answers 500; the instance answers the next command; the span outcome is `Failed`.
- `es.late-reply-dropped` (process targets with a scriptable double only) — a reply after timeout does not persist.
- `es.journal-portable` — a journal written by the Scala reference is recovered by the target, and the reverse (SC-002).

**Key value**
- `kv.set-get`, `kv.recover-after-restart`, `kv.delete-then-fresh`.

**Workflow**
- `wf.runs-steps-in-order` — `start`, then `status` reaches `charged` with transitions journaled in order.
- `wf.step-failure-compensates` — `charge` fails; `compensate` runs; `status` is `compensated`.
- `wf.step-calls-client` — `reserve` invoked `profile/get` and its span is a child.
- `wf.survives-restart-mid-step` — restart during `charge`'s pause; the pending step resumes.

**View and consumer**
- `view.row-updated`, `view.query-by-id`, `view.row-deleted-on-checkout`.
- `consumer.at-least-once-in-order` — checkouts arrive in order per cart; `conformance/count` matches.

**Timed action**
- `timer.fires` — `remind` scheduled for 1s; `count` increments within 5s.
- `timer.fires-after-process-restart` (process targets) — scheduled, process restarted, still fires.

**HTTP endpoints**
- `http.path-params-bind` — `GET /carts/c1` reaches the handler with `c1`.
- `http.literal-beats-parameter` — `GET /carts/awkward` reaches the literal route, not `{cartId}`.
- `http.query-and-headers-cross` — `GET /conformance/echo?a=1&a=2&b=x` with two headers echoes both `a` values in order and both headers.
- `http.body-decoded-reply-encoded` — a JSON body decodes to the handler's type; the reply carries `application/json` and the encoding's shape.
- `http.status-passthrough` — `GET /conformance/status/418` answers 418.
- `http.handler-fault-is-500` — `GET /conformance/boom` answers 500 with the message; the span is `Failed`.
- `http.acl-deny-never-reaches-process` — `GET /private/` answers 401 (or 503 with no verifier) and the process saw no request.
- `http.sse-frames-json-encoded` — `GET /conformance/stream/s1` streams frames whose `data:` is JSON, including one with a leading space and one with a newline, intact.
- `http.request-span-parents-entity-span` — the entity span from `POST /carts/c1/items` is a child of the request span.
- `http.unready-process-is-503` (process targets) — with the process stopped, a forwarded request answers 503 within two seconds, and 200 once it is back.

**Agent**
- `agent.plans-and-replies` — scripted model text reaches the caller.
- `agent.tool-invoked-in-process` — the model calls `lookup`; the result text contains the count.
- `agent.tool-error-continues` — a tool error is fed back; the loop replies.
- `agent.stream` — tokens arrive in the SSE stream in order, JSON-encoded.
- `agent.guardrail-blocks` — `no-secrets` blocks an output containing `sk-`.
- `agent.session-survives-process-restart` (process targets) — a second turn after restart sees the first.

**Client**
- `client.trace-propagates` — a nested call's span has the handler's span as parent.
- `client.error-code-crosses` — a refusal from `refuse` reaches a caller's `error` with its code.

**Observability**
- `obs.one-span-per-invocation` — each behaviour above recorded exactly one component span per handler call and one request span per HTTP request (feature 007's double-span lesson).

## The encoding fixtures

`protocol/fixtures/<name>.json`, generated by `EncodingFixturesSuite` in `core` from the shopping
cart's, the planner's and the control plane's domain types and from every primitive serializer,
one file per shape in `ENCODING.md`. Each SDK runs all of them both ways in its own test runner
(`sdks/python/tests/test_encoding_fixtures.py`); a fixture skipped is a failure (SC-002). The
`core` suite fails when regeneration changes any file, so the fixtures cannot drift from the
codecs.

## Output

munit's own: each behaviour passes or fails by name. `uv run conformance` in the Python SDK exits
with sbt's status, so CI for the SDK is the fixtures plus the conformance suite.
