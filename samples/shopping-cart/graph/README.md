# The carts as a graph, with no mapper

The shopping cart publishes its carts as a graph itself: `CartGraph` and `CartContentsGraph`
(`src/main/scala/shoppingcart/application/`) are graph consumers over the cart's events, and each
publishes graph deltas to the topic `cart-graph`. What writes them into a graph database is the
platform's graph sink into Neo4j, the image `ankka-graph-sink-neo4j` from ankka-contrib, deployed into
the project like any service: `cart-graph-sink.json` here is its descriptor, and there is no image of
ours.

```text
shopping cart ──▶ cart-graph (declared compacted) ──▶ ankka-graph-sink-neo4j ──▶ Neo4j
```

## What is published

| Element | Written by | When | Properties |
|---|---|---|---|
| node `cart:<id>`, label `Cart` | `CartGraph` | an item added or removed, a checkout | `cartId`, `checkedOut` |
| node `checkout:<id>`, label `Checkout` | `CartGraph` | a checkout | `cartId` |
| edge `checked-out:<id>`, type `CHECKED_OUT`, cart → checkout | `CartGraph` | a checkout | none |
| node `cart-contents:<id>`, label `CartContents` | `CartContentsGraph` | an item added or removed, a checkout | `cartId`, `lines`, `quantity` |

Every element's version is the sequence number of the cart's event it was published for. A
discarded cart is deleted, and its `cart` and `cart-contents` nodes are tombstoned at the
deletion's sequence number: they stay in the graph, marked `_deleted`. A cart started again under
the same id publishes above its tombstones and is live again.

## Run it on the local cluster

Needs the cluster `kustomization/deploy-local.sh` deploys to, with its broker, and a Neo4j 5.26 or
later the sink can reach (here one in the namespace `neo4j`, as `NEO4J_URI` in the descriptor
names it).

**The topic first**, declared on the project and compacted, so that it holds every element's
latest delta however long the service runs:

```bash
ankka projects topics set cart-graph --partitions 3 --compacted -p checkout
```

**Then the store's credential and the sink**, with `database: none`: a sink keeps no state of its
own.

```bash
ankka projects secrets set graph-store username=neo4j password=- -p checkout   # the password from standard input
ankka services apply -f cart-graph-sink.json -p checkout
ankka services get cart-graph-sink -p checkout        # topic sources: cart-graph … lag 0
```

**Then the service**, as it is: the two graph consumers register themselves.

```bash
ankka services deploy shopping-cart sample-shopping-cart:latest -p checkout
```

**Drive it and look:** add items, check out and discard a few carts through the service's
endpoints, then:

```bash
kubectl -n neo4j exec neo4j-0 -- cypher-shell -u neo4j -p "$NEO4J_PASSWORD" --format plain \
  "MATCH (n:Element) RETURN n.id AS id, labels(n) AS labels, n._version AS version, n._deleted AS deleted ORDER BY id"
```

A checked-out cart is a `Cart` node with `checkedOut: true`, a `Checkout` node and a
`CHECKED_OUT` edge between them; a discarded cart is a `Cart` node marked deleted at the
version of its deletion; a cart started again under the same id is live again, above its
tombstone.

## Rebuilding the graph

The topic is the graph: compacted, it keeps the latest delta under every element's key. To fill
an empty database from it, empty the store and apply the sink again with
`ANKKA_GRAPH_SINK_VERSION` one higher: it reads the topic again from its start under a new
group. The service is not involved. See the graph sink page of the documentation.
