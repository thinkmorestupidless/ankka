# Contract: The Topology Document

One shape, rendered by one function in `runtime` (`TopologyJson.render`) and served in two places:

| Where | Route | Reader |
|---|---|---|
| a local process | `GET /observability/topology` on the loopback `ObservabilityEndpoint` | `ankka local console`, through `Source.topology` |
| a pod | `GET /observability/topology` on port 7628 `observe` (see `observe-port.md`) | the control plane only |

`GET /observability/service` keeps its current shape. Its `routes` entries gain one field,
`"endpoint"`, the node id of the endpoint that serves the route (research R4). Every existing field
is unchanged, so the local console from an older CLI still works.

## `GET /observability/topology`

```json
{
  "service": { "name": "cart", "runtime": "0.10.0", "instance": "48213",
               "startedAt": "2026-10-01T09:12:03Z" },
  "window": { "seconds": 600, "since": "2026-10-01T09:12:03Z", "calls": 14 },
  "nodes": [
    { "id": "endpoint:/carts", "kind": "Endpoint", "layer": 0, "platform": false,
      "handlers": [ { "name": "GET /carts/{cartId}", "type": "route", "streaming": false },
                    { "name": "POST /carts/{cartId}/items", "type": "route", "streaming": false } ] },
    { "id": "shopping-cart", "kind": "EventSourcedEntity", "layer": 2, "platform": false,
      "handlers": [ { "name": "add-item", "type": "command" },
                    { "name": "get-cart", "type": "query" } ] },
    { "id": "carts-by-customer", "kind": "View", "layer": 3, "platform": false,
      "handlers": [ { "name": "on-change", "type": "update" } ] },
    { "id": "topic:checkouts", "kind": "Topic", "layer": 4 },
    { "id": "unknown", "kind": "UnknownCaller", "layer": 0 }
  ],
  "declared": [
    { "from": "shopping-cart", "to": "carts-by-customer", "kind": "events" }
  ],
  "calls": [
    { "from": "endpoint:/carts", "to": "shopping-cart",
      "pairs": [
        { "caller": "POST /carts/{cartId}/items", "callee": "add-item",
          "handled":    { "ok": 9, "refused": 1, "failed": 0 },
          "unanswered": { "timedOut": 0, "undelivered": 0 },
          "durationMillis": { "p50": 1.9, "p99": 7.8, "max": 7.8, "bucketed": true },
          "histogram": [0,0,0,0,3,5,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
          "streaming": false } ] }
  ]
}
```

A handler's `type` is one of `command`, `query`, `step`, `stream`, `action` (run by a timer),
`update` (a view's or a consumer's one handler) and `route`. A route also says whether it is
`streaming`. Nodes are in order of layer, then id; an endpoint's routes are in order of path, then
method; a component's handlers are in order of name.

## Rules a reader may rely on

1. **`declared` is complete.** Every source and destination of every registered view and consumer
   appears exactly once. A reader may say "nothing else feeds this view".
2. **`calls` is observed.** It holds pairs seen in the window and nothing else. A reader **must not**
   present it as complete. Both consoles show the window and the word *observed* wherever call edges
   are drawn (FR-015).
3. **`unknown` is a node, not a guess.** It appears only when some call had no validated caller.
   Its edges list callees only.
4. **No unbounded value appears anywhere.** That covers entity ids, session ids and filled-in paths.
   A route appears as its template. External services appear by name up to an admission limit, as
   `service:<project>/<name>`; calls to any further service appear under the one node
   `service:(other)`. A callee handler the component does not declare appears as `(undeclared)`,
   never by the name that was sent.
5. **`handled` and `unanswered` are two viewpoints and are not summed.** `undelivered` means the
   handler never ran: the platform answered instead, or no host was reached. Unanswered counts are
   attempts, so a retried call counts once per attempt. A handler that threw counts
   once in `handled.failed` and once in `unanswered.timedOut`. The document has no field that adds
   them together.
6. **`histogram`** has a fixed number of log-scale buckets. Bucket `i` covers
   `[2^(i-10), 2^(i-9))` ms, and bucket 0 also takes everything below. It is present so that
   instances can be merged. Percentiles are read from it and are marked `bucketed`.
7. **`layer`** decides columns in every console. A reader does not compute its own layout rule.
8. **`platform: true`** marks components the platform registered for itself. Hiding them is a
   reader's choice. When a reader hides one, it must keep the edges to it, folded onto the node that
   uses it.
9. **Node ids are stable** across reads and instances of the same build. That is what makes the
   merge in `control-plane-route.md` a union by id.
10. **The window is stated.** `since` is later than `now - seconds` when the process started inside
    the window, and a reader says "since the service started" in that case.
11. **Reading is not calling.** A read the local console makes of an entity or a session appears
    on no edge. A reader may poll this document and query entities without changing what it shows.

## Errors

| Status | When |
|---|---|
| 200 | always, for a running service: a service with nothing registered returns empty arrays |
| 404 | a path that is not this route |
| 405 | anything but GET |
