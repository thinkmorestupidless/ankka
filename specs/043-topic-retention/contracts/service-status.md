# Contract: the retention gap and the retention warning

What a topic source reports and where it is shown. Held by `features/topics/gap.feature`,
`ViewVersionSuite`, `KafkaSuite`, `TopicSourcesReportSuite`, `ServiceWarningsSuite`, `MetricsSuite`
and the console's Playwright suite.

## The runtime's document (`/observability/service`, `/observability/topology`, the local console)

```json
"topicSources": [{
  "kind": "view", "component": "entries", "topic": "transactions", "group": "...", "start": "earliest",
  "version": 2, "recordedVersion": 2, "behind": false, "broker": null, "contract": null, "lag": 0, "failing": null,
  "gap": {
    "partitions": [{"partition": 0, "beginning": 1240, "earliestRetained": "2026-09-01T10:00:00Z"},
                   {"partition": 1, "beginning": 0,    "earliestRetained": "2026-06-02T08:15:00Z"},
                   {"partition": 2, "beginning": 0,    "earliestRetained": null}],
    "compacted": false, "gone": true, "readAt": "2026-10-08T10:00:00Z"
  }
}]
```

- `beginning` is the earliest offset the broker still holds; `earliestRetained` when it was published,
  `null` for a partition holding nothing. Neither claims to know the first offset ever written.
- `gone` is true when the topic is not compacted and some partition's beginning is above 0, or some
  earliest retained time is later than when the view's version was recorded.
- A compacted topic reports `compacted: true` and `gone: false`, whatever its beginnings.
- Reported when the source subscribes, when its view is rebuilt, and every five minutes. `gap` is
  `null` until the first answer.
- The `view rebuild:` log line stays and gains each partition's beginning.

## Metrics (`/ankka/metrics`)

```text
ankka_topic_source_beginning{kind="view",component="entries",topic="transactions",partition="0"} 1240
ankka_topic_source_earliest_retained_seconds{kind="view",component="entries",topic="transactions",partition="0"} 1756720800
ankka_topic_source_gone{kind="view",component="entries",topic="transactions"} 1
```

The time series is absent for a partition holding nothing.

## `GET /services/{project}/{name}` (`ServiceStatus`)

```json
"topicSources": [{..., "gap": {...as above, merged by partition across instances...}}],
"warnings": [{"kind": "retention", "component": "entries", "topic": "transactions",
              "message": "view 'entries' reads topic 'transactions', which keeps 7 days; the installation warns below 30 days"}]
```

- Merge: each partition's row from the instance that reported it (never summed); `gone` if any
  instance says so; `compacted` if all do; the latest `readAt`.
- `warnings` is present once an instance has reported its topic sources, and is recomputed on every
  read from the project's current declarations: a topic lowered below the threshold warns every view
  reading it; one raised, or declared compacted or to keep everything, clears it. `null` when there is
  nothing to warn about.

## `ankka services get ledger -p money`

```text
topic sources:
  entries: transactions  group ankka.money.ledger.view-v2.entries  v2
    retained: p0 from 1240 (2026-09-01T10:00:00Z), p1 from 0 (2026-06-02T08:15:00Z), p2 holds nothing; earlier messages gone
  deltas: cart-graph  group ...  v1
    retained: compacted
warnings:
  retention: view 'entries' reads topic 'transactions', which keeps 7 days; the installation warns below 30 days
```

`retained: everything` when no partition's beginning is above 0 and nothing is gone.

## The web console

The service page's topic-sources table gains a "Retained" column (`everything`, `compacted`, or
`gone since <earliest>` with the per-partition rows in a disclosure, `data-gap`), and a warning notice
above the table (`data-warning="retention"`). The project page's topics table gains the settings
columns, a `*` on a defaulted value, the copies note, and a "History" section; declaring a change that
removes messages opens a dialog with the control plane's text and resends with `removes` on
confirmation.

## The MCP server

`get_service`'s description names `warnings` and the topic sources' `gap`; a new `project_history`
tool returns the project's history.
