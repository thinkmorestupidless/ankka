# Quickstart: proving Topic Retention

Prerequisites: Docker (Kafka, Postgres and k3s containers), `uv` (the features check), `kubectl` on
PATH (the overlay suites), Node 24 (the console); a kind cluster for the walk-through (`just deploy`).

## Offline, in minutes

```bash
sbt 'controlPlaneApi/testOnly *TopicSettingsSuite *ProjectTopicsSuite *ControlPlaneFixturesSuite'
                                   # the parser and units, filling, bounds, the diff and the removal text; fixtures regenerated with -Dankka.docs.update=true
sbt 'controlPlane/testOnly *TopicPolicySuite *EventCompatibilitySuite *ProjectTopicsFeature *TopicSettingsSweepSuite *TopicSourcesReportSuite *ServiceWarningsSuite'
                                   # the policy's refusals at start; the journals replay; owner, acknowledgement, fixed copies, records nothing;
                                   # the sweep fills a 037 journal once; the gap merged per partition; the warning follows the declaration
sbt 'operator/testOnly *StrimziModelsSuite *ProjectRenderingSuite *TopicProvisioningSuite *CrdSchemaSuite *RenderingGoldenSuite'
                                   # the seven keys and replicas rendered; the pre-sweep entry rendered as before; Failed above the node count; the schema
sbt 'runtime/testOnly *TopicConfigsSuite' 'testkit/testOnly *InMemoryBrokerSuite *ViewVersionSuite *MetricsSuite'
                                   # the cache; keyless to compacted refused in memory; the gap after drop(); the three series
sbt 'testkit/testOnly *KafkaSuite'  # a dropped segment reports a gap; a compacted topic refuses a keyless record and compacts within the lag; acks=1 refused at start
sbt 'controlPlane/testOnly *BrokerShapeSuite *RemoteOverlaySuite'   # the three-node component by value; the one-node overlay still 1 everywhere (needs kubectl)
sbt 'cli/testOnly *CliReferenceSuite *McpServerSuite' 'controlPlane/testOnly *ControlPlaneRoutesReferenceSuite'   # pages regenerated with -Dankka.docs.update=true
just features                       # the five living features, the glossary and the specs
just test-console                   # the project page's settings, the removal dialog, the history, the service page's gap and warning
just docs-reference && just docs
```

Expected: every suite green; `GherkinSuite` reports each scenario of `changing.feature` and
`gap.feature` it runs by name, and marks the k3s ones as run elsewhere.

## On k3s (`-Dankka.cluster.tests` on, never on a pull request)

```bash
caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' \
  'controlPlane/testOnly *BrokerRetentionFeatures *BrokerCleanupFeatures *BrokerChangingFeatures'
caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *BrokerCopiesFeatures'   # three broker nodes
gh workflow run cluster --ref 043-topic-retention-impl -f suite=BrokerCopiesFeatures                                 # the same, on a runner
```

Expected: a topic declared with partitions alone shows every setting marked as the installation's
default and the `KafkaTopic` names each; a declaration past the longest retention is refused at the
control plane with nothing on the broker; a change to the installation's default (the kit restarted
with another variable) changes no topic; a 037-shaped journal is filled on the first start, once;
compact and compact,delete topics are compacted on Strimzi; a change lands on the broker with no pod
restarted and one attributed history entry; on three nodes every replication setting reads 3 or 2,
five copies is `Failed` naming three broker nodes with nothing made, two hundred messages are read
after one node is stopped between two hundreds, a minimum of three refuses until the node returns,
and a second declaration of copies is refused with nothing else applied; the operator's token is
granted `get` and `list` on node pools and nothing more than before.

## By hand, on kind

```bash
just deploy
ankka projects topics set transactions --partitions 12 --retention 90d -p checkout
ankka projects topics list -p checkout                      # 90d, delete*, copies 1* (single copy), in-sync 1*
ankka projects topics set notices --partitions 3 -p checkout
ankka projects topics list -p checkout                      # every value starred
kubectl -n ankka-broker get kafkatopic checkout.notices -o yaml   # spec.config names retention.ms, retention.bytes, cleanup.policy, ...
ankka projects topics set transactions --partitions 12 --retention 30d -p checkout
                                                            # "this declaration removes messages older than 30 days. Remove them? [y/N]"
ankka projects history -p checkout                          # topic-changed transactions retention 90d → 30d, by you
ankka services apply -f samples/shopping-cart/service.json -p checkout
ankka services get shopping-cart -p checkout                # topic sources with "retained: ..."; a warning for a view over a topic under 30d
```

A deploy token (`organizations tokens create`) is a member: lowering a retention with it answers 403.
