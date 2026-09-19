# Contract: What a Running Service Exposes

Two exposures of one recorder, chosen by where the process runs (R1) — the same shape as cluster
formation's overlay.

| mode | where | who reads it | why not the other |
|---|---|---|---|
| `local` | an ephemeral port, address published to the registry file | `ankka local console` | management does not run locally, by design: it binds a fixed port two services would fight over |
| `kubernetes` | the management port, via a `ManagementRouteProvider` registered in the overlay | a metrics scraper | there is no registry file in a pod, and management is already running and already named in the pod spec |

## Local endpoint

Served by `jdk.httpserver`, bound to **loopback only**, on an ephemeral port. Loopback is the whole
of the access control, and is why the console is local-only (FR-018).

### `GET /observability/service`

Identity and inventory. What the console's service list and component browser are built from.
`instances` always holds exactly one entry locally — see "One contract, two sources" below for why
it is a list anyway.

```json
{
  "name": "orders",
  "runtime": "0.1.0",
  "instances": [ { "id": "48213", "startedAt": "2026-09-19T13:22:41Z",
                   "http": { "address": "http://127.0.0.1:9000" } } ],
  "components": [
    { "kind": "EventSourcedEntity", "id": "item",
      "handlers": [ { "name": "add-item", "kind": "command" },
                    { "name": "get-item", "kind": "query" } ] },
    { "kind": "HttpEndpoint", "id": "item-endpoint",
      "routes": [ { "method": "POST", "path": "/items/{id}" },
                  { "method": "GET",  "path": "/items" } ] }
  ]
}
```

An instance's `http` is absent when the service declared `"http": false`. The console must then say there is
nothing to invoke rather than render an unusable panel.

### `GET /observability/traces`

The recent window, newest first. Each entry is a summary; spans come from the detail route.

```json
{ "capacity": 1024, "held": 1024, "oldestOverwritten": true,
  "traces": [ { "traceId": "…", "startedAt": "…", "durationMillis": 412,
                "entry": "POST /items/{id}", "outcome": "ok", "spans": 4 } ] }
```

`oldestOverwritten` is what lets the console tell the truth required by FR-017 — this is a window,
not a history.

### `GET /observability/traces/{traceId}`

```json
{ "traceId": "…", "durationMillis": 412, "partial": false,
  "unattributedMillis": 388,
  "spans": [ { "spanId": "…", "parentSpanId": null,
               "component": "item-endpoint", "handler": "POST /items/{id}",
               "durationMillis": 412, "outcome": "ok",
               "children": [ { "component": "item", "handler": "add-item",
                               "durationMillis": 21, "outcome": "ok", "children": [] } ] } ] }
```

- `unattributedMillis` is reported, never redistributed into spans (R4). It is where an external
  model call shows up, and where work handed to another thread shows up.
- `partial: true` when this window does not hold the whole trace. Locally the only cause is
  eviction from the ring; the flag deliberately does not say so (see "One contract, two sources").
  The tree is still returned; it is labelled.
- An orphan span — a parent that is gone or was never recorded — is returned at the root with its
  parent marked unknown. **It is never reattached by guessing.**

### `GET /observability/entities/{component}/{id}`

Current state as the platform holds it, for the inspection panel (FR-014). `404` when no such
entity exists — an entity that has never been created must not be shown as an empty one that looks
real.

### `GET /observability/sessions/{sessionId}`

An agent session's stored memory and its usage (FR-015, FR-016):

```json
{ "sessionId": "…", "messages": [ … ],
  "usage": { "inputTokens": 8120, "outputTokens": 640,
             "cost": { "amount": 0.0341, "currency": "USD" } } }
```

`cost` is `{ "unknown": true }` when the model has no configured price — never `0` (R9).

### `GET /observability/usage`

Per-service totals, by model and by session.

## Kubernetes exposure

A `ManagementRouteProvider` on the management port, registered in `ankka-cluster-kubernetes.conf`
beside `ankka-version`, following `VersionRoute` exactly.

### `GET /ankka/metrics`

Prometheus text exposition, hand-written (R2 — no dependency may be added to a published artifact):

```
ankka_invocations_total{component="item",handler="add-item",outcome="ok"} 1042
ankka_invocation_duration_seconds_bucket{component="item",handler="add-item",le="0.05"} 1011
ankka_model_tokens_total{model="…",direction="input"} 81200
ankka_model_cost_total{model="…",currency="USD"} 3.41
```

A service that has served nothing responds with zeroed series, not an error (P3 scenario 3).

**Cost is absent, not zero, for a model with no configured price** — an absent series is honest;
`0` would be charted as free.

## One contract, two sources

The JSON shapes above are **the** contract, defined independently of where they came from. This
feature ships one producer of them — a service's own local endpoint, read by `LocalSource`. A later
console over a deployed installation is a second producer of the same shapes, served by the control
plane, and must not need the UI or the aggregation API to change.

Three rules keep that true, and they cost this feature almost nothing:

1. **A service has instances, plural.** Locally the list always holds exactly one; a deployed
   service holds one per pod. Every shape that carries a service carries a list, never a single
   address:

   ```json
   { "name": "orders", "instances": [ { "id": "local", "http": { "address": "…" }, "startedAt": "…" } ] }
   ```

   `id` is the pid locally and the pod name deployed. A shape with one address would be the
   expensive thing to change later; a list of one is free now.

2. **`partial` means "this window does not hold all of it"** — not "spans aged out". Locally the
   only cause is eviction from the ring. Deployed, the other cause is spans living on another
   instance. One flag with one meaning, so a deployed source needs no second one and the UI needs
   no new case.

3. **No shape assumes co-location.** A trace does not assume its spans came from one process, and
   a usage total does not assume one ring produced it. Merging them across instances is *not* in
   this feature — but nothing here forbids it, which is the whole point.

What is deliberately **not** designed now: how disagreeing instances are reconciled, how a fan-out
read is bounded when a service has fifty pods, and what a trace means when half its spans are
evicted on one pod and live on another. Those are the deployed console's problems, and guessing at
them here would be designing a feature nobody has specified.

## Invariants

1. **Read-only.** Nothing here mutates state, triggers a handler or writes an event. The one way
   to cause behaviour from the console is the invoke panel, which is an ordinary HTTP request to
   the service's own port (R5) — not a route here.
2. **No new port in Kubernetes**, and no new credential in either mode.
3. **The local endpoint binds loopback.** It is not exposed by the operator, not named in a pod
   spec and not reachable from another machine.
4. **Both exposures read the same records.** The metrics endpoint aggregates what the console
   reads; there is no second accounting path to drift.
