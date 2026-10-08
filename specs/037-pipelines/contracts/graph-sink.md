# Contract: the graph merge sink

Held by `features/graph-deltas/sink.feature`, `Neo4jMergeSuite` (carried from ankka-flow with
every assertion), `Neo4jSinkSuite` and `GraphFixturesSuite`.

## The component (`ankka-graph-neo4j`)

```scala
import com.thinkmorestupidless.ankka.graph.neo4j.{Neo4jSink, Neo4jSettings}
builder.register(Neo4jSink("cart-graph", Neo4jSettings(uri, username, password, database = "neo4j", transactionTimeout = 30.seconds), version = 1, parallel = true))
```

A `Consumer[GraphDelta]` whose source is the topic from `Earliest` under the service's usual
group, keyed by the delta's element key. One delta is one write transaction:

| Stored `_version` | Incoming | Result |
|---|---|---|
| absent | merge | created at `v` |
| absent | tombstone | created, `_deleted = true`, at `v` |
| `s` | merge, `v > s` | state replaced, `_version = v`, `_deleted` cleared |
| `s` | tombstone, `v > s` | `_deleted = true`, labels and properties cleared, `_version = v` |
| `s` | `v ≤ s` | unchanged |

An edge merges both endpoints as placeholders (`_version = -1`) when absent. A delete marker (a
record with no value) is a tombstone of its key. A record that is not a delta, or whose key is
not its element key (`GraphRules`), fails the change: it is logged with its key and the rule,
reported as `failing` on the topic source, and redelivered. The driver retries a transient
failure; a transaction past the timeout fails the change. No message, log line or status ever
contains the password.

## The image (`ghcr.io/thinkmorestupidless/ankka-graph-sink:<version>`)

Deployed into a project like any service:

```json
{"name": "cart-graph-sink",
 "service": {"image": "ghcr.io/thinkmorestupidless/ankka-graph-sink:0.9.0", "http": false, "database": "none",
   "env": [{"name": "ANKKA_GRAPH_SINK_TOPIC", "value": "cart-graph"},
           {"name": "ANKKA_GRAPH_SINK_VERSION", "value": "1"},
           {"name": "NEO4J_URI", "value": "neo4j://neo4j.graph.svc:7687"},
           {"name": "NEO4J_USERNAME", "secretKeyRef": {"name": "graph-store", "key": "username"}},
           {"name": "NEO4J_PASSWORD", "secretKeyRef": {"name": "graph-store", "key": "password"}}]}}
```

```bash
ankka projects topics set cart-graph --partitions 3 --compacted -p shop
ankka projects secrets set graph-store username=neo4j password=- -p shop
ankka services apply -f cart-graph-sink.json -p shop
ankka services get cart-graph-sink -p shop        # topic sources: cart-graph … lag 0
```

Rebuild: empty the store, apply again with `ANKKA_GRAPH_SINK_VERSION` one higher; the sink
re-reads from the start under a new group. The image's service registers the sink and nothing
else, needs no database, and serves no HTTP.

## Fixtures

`protocol/fixtures/graph-deltas/{keys,deltas,refused}.json` are written by `GraphFixturesSuite`
(`-Dankka.fixtures.regenerate=on`) and refused when they differ; `SOURCE.md` says so. The sink's
suite applies every `deltas.json` row and reads back what `reads` says.
