# Quickstart: proving Pipelines Are Services

Prerequisites: Docker (Kafka, Postgres, Neo4j and k3s containers), `uv`, Node 24, cargo; a kind
cluster for the manual walk-through (`just deploy`).

## Offline, in minutes

```bash
sbt 'core/testOnly *ContractFixturesSuite *GraphFixturesSuite'     # fingerprints and the graph fixtures are ankka's own
sbt 'runtime/testOnly *ProjectDeclarationsSuite *TopicSourceRulesSuite'   # the start-time check: the table in contracts/declarations.md
sbt 'controlPlane/testOnly *ProjectTopicsSuite *ProjectBrokersSuite *EventCompatibilitySuite'
sbt 'operator/testOnly *ProjectRenderingSuite *TopicProvisioningSuite *RenderingGoldenSuite *CrdSchemaSuite'
sbt 'graphNeo4j/test'                                               # Neo4jMergeSuite (carried) and Neo4jSinkSuite, Neo4j in a container
sbt 'testkit/testOnly *KafkaSuite'                                  # parallel partitions, lag, the no-database service, a SASL broker
sbt 'sidecar/testOnly *ConformanceSuite'                            # the SDKs' contract, broker and parallel declarations
cd sdks/python && uv run pytest -q && uv run conformance            # likewise typescript, rust
just features                                                       # the seven living features name only glossary words
just docs-reference && just docs                                    # cli.md, control-plane-api.md, fixtures, every page
```

Expected: every suite green; `GherkinSuite` reports each scenario of the seven features by name.

## On k3s (`-Dankka.cluster.tests` on)

```bash
caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *BrokerCompactionFeatures *TopicContractsFeatures *GraphSinkFeatures'
```

Expected: a topic declared compacted is compacted on Strimzi; a service stating the wrong
contract shows `Failed` with the refusal as its detail in `services get`; the sink image deployed
into the shopping cart's project fills Neo4j with every cart and item and refills it at version 2.

## By hand, on kind

```bash
just deploy && just neo4j-up
ankka projects topics set cart-graph --partitions 3 --compacted -p checkout
ankka projects topics set orders --partitions 3 --contract order.v1 --schema samples/shopping-cart/schemas/order.v1.json -p checkout
ankka projects topics schema get orders -p checkout | diff - samples/shopping-cart/schemas/order.v1.json   # identical
ankka services apply -f samples/shopping-cart/graph/cart-graph-sink.json -p checkout
ankka services get cart-graph-sink -p checkout        # topic sources: cart-graph … lag 0 after the cart publishes
ankka services get cart -p checkout                   # topic checks: orders: … checked
```

Then change the contract's schema and apply `cart` again: `services get cart` shows `Failed` and the
refusal naming `order.v1` twice with different fingerprints. Declare a broker with
`ankka projects brokers set legacy --bootstrap … --shape sasl --secret legacy-credential -p checkout`
and deploy the `intake` sample consumer: messages produced on the outside Kafka appear on `orders`.

## Done when

- SC-001–SC-006 of the spec hold; `grep -ri ankka-flow docs/` finds only `contributing/documentation.md`.
- `RenderingGoldenSuite` and `RenderingUnchangedSuite` pass without regenerating.
- The release workflow's `images` job lists `ankka-graph-sink` and the public-pull check passes once the package is public.
