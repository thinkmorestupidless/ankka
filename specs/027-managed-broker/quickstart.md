# Quickstart: proving the managed broker works

How to run each story's proof. Contracts are in [contracts/](contracts/); the scenarios are in
`features/broker/`.

## Prerequisites

Docker; `kubectl` on PATH (for `RemoteOverlaySuite`); for the last section, `kind` and `just`.
Feature 024 on the branch for the scenarios in which a view reads a provisioned topic.

## The gate: the runtime's TLS to a broker

```bash
sbt -Dankka.spikes=on 'testkit/testOnly *KafkaTlsSpike'
```

Expected: a Kafka client configured with the runtime's engine factory connects to a TLS broker in a
container with a certificate from a test authority, is refused with another authority's, and a
renewed certificate is the one the next connection presents. If this fails, research R10's
fallback is taken before anything else is built.

## Story 1: a process-hosted service with a topic, on a cluster

```bash
caffeinate -i sbt 'sidecar/testOnly *SidecarClusterSuite -- "*topic*"'
```

Expected: the Python sample, deployed with `ANKKA_KAFKA_BOOTSTRAP_SERVERS` naming a plain broker in
the cluster, is ready, and what its consumer publishes is read from the topic.

## Offline: the declarations, the decisions and the rendering

```bash
sbt -Dankka.cluster.tests=off 'controlPlaneApi/testOnly *ProjectTopicsSuite' \
    'operator/testOnly *BrokerProvisioningSuite *TopicProvisioningSuite *BrokerRenderingSuite *ProjectRenderingSuite *StrimziModelsSuite *CrdSchemaSuite *RenderingGoldenSuite *RenderingUnchangedSuite' \
    'controlPlane/testOnly *ProjectTopicsFeature *ProjectProjectionSuite *EventCompatibilitySuite *RemoteOverlaySuite *ReservedProjectIdsSuite' \
    'testkit/testOnly *KafkaConnectionSuite' 'cli/testOnly *OutputSuite'
```

Expected: every refusal of `contracts/project-topics.md` in the same words from the CLI and the
control plane; every row of `contracts/operator.md`'s tables rendered as stated; a service of an
installation with no broker rendered as before, with no fixture rewritten.

## Stories 2 to 4: on a cluster with the installation's broker

```bash
caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *BrokerClusterFeatures'
```

Expected, in a k3s node with Strimzi installed by `BrokerStack` (about a minute and a half on top
of the usual installs): `declaring.feature`'s cluster scenarios, `topics.feature`,
`isolation.feature`, `kept.feature` and the cluster scenarios of `supplied.feature` and
`installation.feature` pass. Among them: `money.transactions` exists with 12 partitions once
declared and 24 once raised; a service of `casino` holding its own certificate is refused it by the
broker; a service using an undeclared topic names it in its status until it is declared; a deleted
and re-applied service reports its credential recovered and reads what was published before.

## Story 5: the local platform

```bash
just up
ankka login
ankka organizations create acme --name Acme && ankka projects create shop --name Shop -O acme
ankka config set project shop
ankka projects topics set cart-graph --partitions 3
echo '{"name":"cart","service":{"image":"sample-shopping-cart:latest"}}' | ankka services apply -f -
ankka projects topics list         # cart-graph  3  provisioned
ankka services get cart            # broker  provisioned;  undeclared topics  cart-checkouts
kubectl -n ankka-broker get kafkatopic,kafkauser
```

Expected: `shop.cart-graph` and the user `shop.cart` exist; the cart's graph consumers, which the
sample registers wherever it is told of a broker, publish to it with nothing about a broker in its
descriptor; and the cart's status names `cart-checkouts`, which its checkout notices use and `shop`
has not declared.

## The checks that gate a merge

```bash
sbt scalafmtCheckAll scalafmtSbtCheck
sbt -Dankka.cluster.tests=off -Dankka.template.tests=off test
caffeinate -i sbt test                      # once, for every k3s suite
.github/features-check.sh                   # nothing in features/broker or this spec
just docs
just test-console
```
