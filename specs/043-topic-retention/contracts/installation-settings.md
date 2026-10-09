# Contract: the installation's defaults, bounds and broker shape

What a platform administrator sets. Held by `features/broker/retention.feature` (the defaults and
the longest retention), `features/broker/copies.feature` (the three-node shape) and
`BrokerShapeSuite`.

## The control plane's variables

Set on the control plane's Deployment (`kustomization/components/controlplane/deployment.yaml`) and
read once at start. A value the parser refuses, or a default outside its bound, stops the control
plane with a message naming the variable.

| Variable | Means | Shipped |
|---|---|---|
| `ANKKA_TOPIC_DEFAULT_RETENTION` | retention time of a declaration that gives none | `7d` |
| `ANKKA_TOPIC_DEFAULT_RETENTION_SIZE` | retention size per partition likewise | `none` |
| `ANKKA_TOPIC_DEFAULT_CLEANUP` | cleanup policy likewise | `delete` |
| `ANKKA_TOPIC_DEFAULT_TOMBSTONE_WINDOW` | tombstone window likewise | `1d` |
| `ANKKA_TOPIC_DEFAULT_MIN_COMPACTION_LAG` | minimum compaction lag likewise | `0s` |
| `ANKKA_TOPIC_DEFAULT_MAX_COMPACTION_LAG` | maximum compaction lag likewise | `none` |
| `ANKKA_TOPIC_DEFAULT_COPIES` | copies likewise | `1` |
| `ANKKA_TOPIC_DEFAULT_MIN_IN_SYNC` | minimum in-sync copies likewise | `1` |
| `ANKKA_TOPIC_LONGEST_RETENTION` | the longest retention time a declaration may ask | `everything` |
| `ANKKA_TOPIC_LARGEST_RETENTION_SIZE` | the largest retention size per partition | `none` |
| `ANKKA_TOPIC_MOST_COPIES` | the most copies | `3` |
| `ANKKA_TOPIC_WARNING_THRESHOLD` | the retention time below which a view reading a topic is warned | `30d` |

The shipped values are a laptop's. An installation serving long-lived facts sets
`ANKKA_TOPIC_DEFAULT_RETENTION` and `ANKKA_TOPIC_LONGEST_RETENTION` before its first topic is
declared: a change later changes no topic already declared.

## The three-node broker

`kustomization/components/broker-three-nodes/`, a Component an overlay lists after `broker` and
`controlplane`:

```yaml
# KafkaNodePool dual: replicas 3
# Kafka ankka, config:
#   default.replication.factor: 3        min.insync.replicas: 2
#   offsets.topic.replication.factor: 3  transaction.state.log.replication.factor: 3
#   transaction.state.log.min.isr: 2
# Deployment ankka-controlplane, env:
#   ANKKA_TOPIC_DEFAULT_COPIES: "3"      ANKKA_TOPIC_DEFAULT_MIN_IN_SYNC: "2"
```

It is a shape for a new installation and holds three times the one-node shape's storage. It is not a
conversion: the shipped node pool's roles are `[controller, broker]`, so changing its replicas changes
the KRaft controller quorum, which the platform does not perform; an installation that grows a running
broker does so by Strimzi's own procedure, and its existing topics keep the copies they have.
`broker-size.yaml` in the cloud overlay keeps sizing storage, heap and resources, and its comment names
the component in place of the replication keys it used to ask a person to remember.

`kustomization/tests/broker-three-nodes/` renders the local overlay with the component for
`BrokerShapeSuite`, which asserts the five Kafka settings, the pool's replicas and the two control
plane variables by value, and that the plain overlay still reads 1 everywhere.

## The operator's role

`kustomization/components/broker/operator-role.yaml` gains `get` and `list` on `kafkanodepools`, the
read the operator makes once per project reconcile to learn the broker's node count. It gains no
other verb and no other resource.
