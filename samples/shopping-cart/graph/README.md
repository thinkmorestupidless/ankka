# The carts as a graph, with no mapper

The shopping cart publishes its carts as a graph itself: `CartGraph` and `CartContentsGraph`
(`src/main/scala/shoppingcart/application/`) are graph consumers over the cart's events, and each
publishes graph deltas to the topic `cart-graph`. The pipeline that writes them into a graph
database is [ankka-flow](https://flow.ankka.cloud)'s built-in merge sink and nothing else:
`blueprint.conf` here has one streamlet and no image of ours.

```text
shopping cart ──▶ cart-graph (compacted) ──▶ ankka-flow: builtin/neo4j-merge-sink ──▶ Neo4j
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

Needs the cluster `kustomization/deploy-local.sh` deploys to, with ankka-flow 0.3.0 or later
installed beside ankka and its development Kafka and Neo4j (`just deploy` and `just neo4j-up` in
the ankka-flow repository), and ankka-flow's `flow` command.

**The pipeline first.** It declares `cart-graph` as its own topic, so ankka-flow creates it
compacted. ankka creates no topics: on a broker that creates topics on first use, a service that
published first would get an uncompacted one, which the pipeline then reports as
`TopicNotCompacted` and leaves as it is.

```bash
cd samples/shopping-cart/graph
flow verify blueprint.conf --conf k8s/in-cluster.conf
flow generate blueprint.conf --conf k8s/in-cluster.conf -n shop | kubectl apply -f -
kubectl -n shop get ankkaflow cart-graph            # Ready
```

```text
note: Topic 'cart-graph' carries graph deltas and is compacted (cleanup.policy = compact).
verified: 1 streamlets, 1 topics
```

**Then the service**, with a broker named, which is what registers the two graph consumers:

```json
{
  "name": "shopping-cart",
  "service": {
    "image": "sample-shopping-cart:latest",
    "env": [{ "name": "ANKKA_KAFKA_BOOTSTRAP_SERVERS", "value": "kafka.kafka.svc:9092" }]
  }
}
```

```bash
ankka services apply -f shopping-cart.json --project checkout
```

**Drive it and look:**

```bash
./drive.sh https://shopping-cart-checkout.127.0.0.1.sslip.io:8443
kubectl -n neo4j exec neo4j-0 -- cypher-shell -u neo4j -p flow-local-password --format plain \
  "MATCH (n:Element) RETURN n.id AS id, labels(n) AS labels, n._version AS version, n._deleted AS deleted ORDER BY id"
```

## What the graph should hold after `drive.sh`

| Element | Version | State |
|---|---|---|
| `cart:cg-1` | 4 | `checkedOut: true` |
| `cart:cg-2` | 2 | `checkedOut: true` |
| `cart:cg-3` | 3 | deleted |
| `cart:cg-4` | 4 | `checkedOut: false` — live again, above its tombstone at 3 |
| `cart:cg-5` | 1 | `checkedOut: false` |
| `checkout:cg-1`, `checkout:cg-2` | 4, 2 | |
| `cart-contents:cg-1` | 4 | `lines: 1`, `quantity: 2` |
| `cart-contents:cg-2` | 2 | `lines: 1`, `quantity: 1` |
| `cart-contents:cg-3` | 3 | deleted |
| `cart-contents:cg-4` | 4 | `lines: 1`, `quantity: 3` |
| `cart-contents:cg-5` | 1 | `lines: 1`, `quantity: 4` |
| edges `checked-out:cg-1`, `checked-out:cg-2` | 4, 2 | `CHECKED_OUT`, cart → checkout |

Twelve nodes, two of them marked deleted, and two edges.

## Rebuilding the graph

The topic is the graph: compacted, it keeps the latest delta under every element's key. To fill
an empty database from it, reset only the sink, as ankka-flow's guide
[Rebuild a graph from its delta topic](https://flow.ankka.cloud/deploy/rebuild-a-graph/)
describes. The service is not involved.

## Run on the local cluster, 2026-10-02

With ankka-flow 0.3.0's operator and sink beside this build of ankka:

1. **The pipeline first.** `cart-graph` was created by ankka-flow with `cleanup.policy=compact`,
   under exactly the name the service publishes to; the pipeline was `Ready` with one pod, the sink.
2. **The service**, with the broker named: its registry listed `cart-graph` and
   `cart-contents-graph`, and after `drive.sh` the graph held the twelve nodes and two edges of the
   table, at those versions, with `cart:cg-3` and `cart-contents:cg-3` marked deleted and
   `cart:cg-4` live at 4, above its tombstone at 3.
3. **The topic**: every record's key was its element key, its `ce-type` `ankka.graph-delta.v1`, its
   `ce-subject` the cart's id.
4. **A restart of the service straight after a second set of carts** (`drive.sh … rs`): the graph
   for that set was identical to the first, and the sink never failed a batch.
5. **A replay**: the two graph consumers' progress was erased and the service restarted, so both
   read every cart's events again. The topic went from 88 records to 176; the sink wrote none of
   them (88 more counted stale, none failed) and the graph did not change.
6. **A rebuild**: the sink scaled to zero, the database emptied, `flow reset cart-graph --streamlet
   graph`, the sink scaled up. Every live element came back identical, and every deleted one
   marked deleted at its version, with no request made to the service.
7. **The same script against the Python example behind a sidecar, and against the Rust example as
   a WebAssembly module**, each publishing to the same topic under its own cart ids: the graph of
   each was the same as the Scala service's.

One thing to know when comparing two graphs: an element that is **marked deleted** may keep
whatever labels and properties it had when its tombstone was applied, and that depends on how the
sink happened to batch the records — a node and its tombstone read in one batch leave a bare
marker, read in two they leave the node's last properties under the marker. Its id, its version
and its mark are always the same. Live elements are identical however they were batched.
