# Contract: topic sources and checks in a service's status

Held by `features/topics/status.feature` and `features/topics/contracts.feature`'s listing.

`GET /services/{project}/{name}` gains, when at least one instance is ready:

```json
"topicSources": [
  {"kind": "consumer", "component": "relay", "topic": "events", "broker": "legacy", "contract": null,
   "group": "ankka.shop.intake.consumer.relay", "start": "earliest", "version": 1, "recordedVersion": null,
   "behind": false, "lag": 60, "failing": null}],
"topicChecks": [
  {"topic": "orders", "component": "relay", "direction": "publishes", "declared": "order.v1", "stated": "order.v1", "state": "checked"}]
```

Both come from `/observability/service` and `/observability/topology` read per instance over the
observe port; `lag` is the greatest over instances for a source (each instance reads its own
partitions, so the sum is the group's lag: the control plane sums them), `failing` the first
non-null.

`ankka services get intake -p shop` prints after `broker`:

```text
topic sources   relay: events@legacy  group ankka.shop.intake.consumer.relay  v1  lag 60
                orders-by-day: orders  group ankka.shop.intake.view.orders-by-day  v2  lag 0  failing: cannot decode offset 4711: …
topic checks    orders: relay publishes order.v1 — checked
```

The console's service page shows the same table; the MCP `get_service` tool returns the status
JSON and its description names `topicSources` and `topicChecks`.
