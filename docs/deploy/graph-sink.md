---
title: Fill a graph store
description: Deploy the platform's graph sink into a project to keep a Neo4j store in step with a delta topic, or register the sink in a service of your own; rebuild the store from the topic by raising the sink's version.
kind: guide
components: [consumer]
related: [build/graph.md, build/topics.md, deploy/deploy-a-service.md, platform/secrets.md]
---

# Fill a graph store

A [graph consumer](../build/graph.md) publishes a service's entities as graph deltas to a topic.
The **graph sink** reads that topic into a Neo4j store and keeps it in step: it applies each delta
only when the delta's version is newer than the element's in the store, so a delta delivered twice
changes nothing the second time, and the store is the graph however often the service restarts or
replays. The sink is the platform's. Deploy its image into the project like any service, or register
the same sink in a service of your own.

## Declare the topic compacted

The topic a graph consumer publishes to must be compacted: the broker then keeps the latest record
under every key, and every delta's key is its element, so the topic holds each element's latest state
however long the service runs. Declare it on the project with `--compacted`, before anything publishes
to it:

```bash
ankka projects topics set cart-graph --partitions 3 --compacted -p checkout
```

A topic already made uncompacted is made compacted when its declaration says so.

## Deploy the sink

The sink is the image `ghcr.io/thinkmorestupidless/ankka-graph-sink`, at the platform's version. Its
descriptor names the topic, the store, and a project secret holding the store's credential, and says
`"database": "none"`: a sink keeps no state of its own, and nothing is provisioned for it.

```bash
ankka projects secrets set graph-store username=neo4j password=- -p checkout
```

```json title="cart-graph-sink.json"
{
  "name": "cart-graph-sink",
  "service": {
    "image": "ghcr.io/thinkmorestupidless/ankka-graph-sink:0.9.0",
    "http": false,
    "database": "none",
    "env": [
      { "name": "ANKKA_GRAPH_SINK_TOPIC", "value": "cart-graph" },
      { "name": "ANKKA_GRAPH_SINK_VERSION", "value": "1" },
      { "name": "NEO4J_URI", "value": "neo4j://neo4j.graph.svc:7687" },
      { "name": "NEO4J_USERNAME", "secretKeyRef": { "name": "graph-store", "key": "username" } },
      { "name": "NEO4J_PASSWORD", "secretKeyRef": { "name": "graph-store", "key": "password" } }
    ]
  }
}
```

```bash
ankka services apply -f cart-graph-sink.json -p checkout
ankka services get cart-graph-sink -p checkout
```

The status lists the sink's topic source with its group, its version and how far behind it is, and,
while a delta is being refused, what it is failing on.

| Variable | Meaning | Default |
|---|---|---|
| `ANKKA_GRAPH_SINK_TOPIC` | the delta topic, by its declared name | required |
| `ANKKA_GRAPH_SINK_VERSION` | the sink's version; a higher one reads the topic again from its start | `1` |
| `ANKKA_GRAPH_SINK_PARALLEL` | read the partitions an instance holds at once, each in order | `true` |
| `NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD` | the store | required |
| `NEO4J_DATABASE` | the database in it | `neo4j` |
| `ANKKA_GRAPH_SINK_TRANSACTION_TIMEOUT` | how long one delta's transaction may take | `30s` |

The store must be Neo4j 5.26 or later: the sink's statements replace a node's labels with dynamic
labels, which earlier versions do not have. The password reaches no log line, status or message.

## What the store holds

| | Node | Edge |
|---|---|---|
| identity | `id`, unique among nodes; every node carries the label `Element` | `id` within the edges of one type between one `from` and one `to` |
| labels or type | `Element` plus the delta's labels, replaced by every applied delta | the delta's type |
| properties | the delta's, replaced whole by every applied delta | the same |
| `_version` | the version of the last applied delta; `-1` on a placeholder | the same |
| `_deleted` | `true` after a tombstone, which also clears the labels and properties; cleared by a later applied delta | the same |

A **placeholder** is a node an edge names before the node's own delta has arrived: `Element`, its
`id`, `_version = -1` and nothing else. The node's first delta replaces it. For one element, by the
incoming version `v` against the stored `_version` `s`:

| Stored | Incoming | Result |
|---|---|---|
| absent | delta | created at `v` |
| absent | tombstone | created, marked deleted, at `v` |
| `s` | delta with `v > s` | state replaced, `_version = v`, `_deleted` cleared |
| `s` | tombstone with `v > s` | `_deleted = true`, `_version = v`; labels and properties cleared |
| `s` | anything with `v ≤ s` | unchanged |

Each delta is one write transaction. A record that is not a delta, or breaks a delta's rules, fails
the change: the sink's log names the key and the rule, the status names it as what the sink is failing
on, and the record is handed to the sink again until it is handled, so it holds its partition and no
other. A record with no value is passed over.

## Rebuild the store from the topic

The topic is the graph. To fill an empty store from it, with the service untouched:

1. Empty the store, or point the sink at an empty one.
2. Apply the sink's descriptor again with `ANKKA_GRAPH_SINK_VERSION` one higher.

The sink reads the topic again from its start under a new group, and every element the topic
describes comes back at its latest version, deleted ones marked deleted. What the broker has
compacted away is what the store does not need: the latest delta under every key is still there.

## The sink in a service of your own

The module `ankka-graph-neo4j` holds the same sink as a component, for a service that wants it
beside its other components:

```scala
import com.thinkmorestupidless.ankka.graph.neo4j.{Neo4jSettings, Neo4jSink}

Ankka.service
  .register(Neo4jSink("cart-graph", Neo4jSettings(uri, username, password), version = 1).descriptor)
```

It reads the topic from its start, in parallel over the partitions the instance holds unless told
`parallel = false`, and refuses what the image refuses. A service that registers it needs a
database only if its other components do.
