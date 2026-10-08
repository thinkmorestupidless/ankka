# Fill a graph store

> Register the graph sink in a service to keep a graph store in step with a delta topic, choose the store it writes to, or deploy a ready sink image from ankka-contrib; rebuild the store from the topic by raising the sink's version.

Source: https://docs.ankka.cloud/deploy/graph-sink/
A [graph consumer](../build/graph.md) publishes a service's entities as graph deltas to a topic.
The **graph sink** reads that topic into a graph store and keeps it in step: it applies each delta
only when the delta's version is newer than the element's in the store, so a delta delivered twice
changes nothing the second time, and the store is the graph however often the service restarts or
replays. The sink and its rules are the platform's, in the module `ankka-graph-sink`. The store it
writes to is yours to choose: the in-memory store the module ships, a store over a database from
[ankka-contrib](https://github.com/thinkmorestupidless/ankka-contrib), or one you write against the
store interface. ankka-contrib also publishes a ready image, the sink into Neo4j, to deploy into a
project like any service.

## Declare the topic compacted

The topic a graph consumer publishes to must be compacted: the broker then keeps the latest record
under every key, and every delta's key is its element, so the topic holds each element's latest state
however long the service runs. Declare it on the project with `--compacted`, before anything publishes
to it:

```bash
ankka projects topics set cart-graph --partitions 3 --compacted -p checkout
```

A topic already made uncompacted is made compacted when its declaration says so.

## Register the sink in a service

The sink is a consumer. Register it beside the service's other components with the topic and the
store, and it reads the topic from its start, in parallel over the partitions the instance holds
unless told `parallel = false`:

```scala
import com.thinkmorestupidless.ankka.graph.sink.{GraphSink, InMemoryGraphStore}

val store = InMemoryGraphStore()

Ankka.service
  .register(GraphSink("cart-graph", store, version = 1).descriptor)
```

The in-memory store is the reference: it holds the graph for the service's own queries and for
tests, and is empty again when the service restarts, which the sink then fills from the topic. For
a store that outlives the service, pass one over a database. ankka-contrib's `neo4j-graph-store`
is one, for Neo4j 5.26 or later:

```scala
import com.thinkmorestupidless.ankka.contrib.neo4j.{Neo4jSettings, Neo4jStore}

val store = Neo4jStore(Neo4jSettings(uri, username, password))
```

A service that registers the sink needs a database of its own only if its other components do: a
service that is the sink and nothing else says `"database": "none"` in its descriptor.

## Write a store of your own

A store is one interface, `GraphStore`, with one operation: apply a delta. The rules it applies the
delta under are the sink's, stated on the interface and proven on the in-memory store against the
platform's own fixtures, and a store over any database implements them in that database's terms.

## What the store holds

| | Node | Edge |
|---|---|---|
| identity | `id`, unique among nodes | `id` within the edges of one type between one `from` and one `to` |
| labels or type | the delta's labels, replaced by every applied delta | the delta's type |
| properties | the delta's, replaced whole by every applied delta | the same |
| version | the version of the last applied delta; `-1` on a placeholder | the same |
| deleted | set after a tombstone, which also clears the labels and properties; cleared by a later applied delta | the same |

A **placeholder** is a node an edge names before the node's own delta has arrived: its `id`, the
version `-1` and nothing else. The node's first delta replaces it. For one element, by the incoming
version `v` against the stored version `s`:

| Stored | Incoming | Result |
|---|---|---|
| absent | delta | created at `v` |
| absent | tombstone | created, marked deleted, at `v` |
| `s` | delta with `v > s` | state replaced, version `v`, deleted cleared |
| `s` | tombstone with `v > s` | marked deleted, version `v`; labels and properties cleared |
| `s` | anything with `v ≤ s` | unchanged |

Each delta is applied whole, or not at all. A record that is not a delta, or breaks a delta's rules,
fails the change: the sink's log names the key and the rule, the status names it as what the sink is
failing on, and the record is handed to the sink again until it is handled, so it holds its partition
and no other. A record with no value is passed over. Two stores built from one topic hold the same
elements.

## Deploy the sink

A store over a database needs no service of yours to host the sink: ankka-contrib publishes the
image `ghcr.io/thinkmorestupidless/ankka-graph-sink-neo4j`, a service that registers one sink into
Neo4j from its environment. Its descriptor names the topic, the store, and a project secret holding
the store's credential, and says `"database": "none"`: a sink keeps no state of its own, and nothing
is provisioned for it.

```bash
ankka projects secrets set graph-store username=neo4j password=- -p checkout
```

```json title="cart-graph-sink.json"
{
  "name": "cart-graph-sink",
  "service": {
    "image": "ghcr.io/thinkmorestupidless/ankka-graph-sink-neo4j:0.1.0",
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
while a delta is being refused, what it is failing on. The image's variables, its version and what
it writes into Neo4j are documented with it, in ankka-contrib.

## Rebuild the store from the topic

The topic is the graph. To fill an empty store from it, with the service untouched:

1. Empty the store, or point the sink at an empty one.
2. Register or deploy the sink again with its version one higher.

The sink reads the topic again from its start under a new group, and every element the topic
describes comes back at its latest version, deleted ones marked deleted. What the broker has
compacted away is what the store does not need: the latest delta under every key is still there.
