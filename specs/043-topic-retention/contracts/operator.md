# Contract: the resource and the operator

What the control plane writes and what the operator makes of it. Held by `ProjectRenderingSuite`,
`TopicProvisioningSuite`, `StrimziModelsSuite`, `CrdSchemaSuite` and `features/broker/copies.feature`.

## `AnkkaProject.spec.topics[]`

```yaml
- name: transactions
  partitions: 12
  declaredAt: "2026-10-08T10:00:00Z"
  compacted: false                 # unchanged: cleanup contains compact
  retentionMs: 7776000000          # 90d; -1 for everything
  retentionBytes: 53687091200      # 50GiB; -1 for none
  cleanupPolicy: delete
  deleteRetentionMs: 86400000
  minCompactionLagMs: 0
  maxCompactionLagMs: 9223372036854775807
  replicas: 3                      # absent for a topic declared before this feature
  minInsyncReplicas: 2             # absent likewise
```

A topic whose declaration has no settings yet carries none of the eight new fields and is rendered
exactly as before.

## `KafkaTopic` rendered

```yaml
spec:
  partitions: 12
  replicas: 3                      # only when the entry carries replicas
  config:
    retention.ms: "7776000000"
    retention.bytes: "53687091200"
    cleanup.policy: "delete"
    delete.retention.ms: "86400000"
    min.compaction.lag.ms: "0"
    max.compaction.lag.ms: "9223372036854775807"
    min.insync.replicas: "2"       # only when the entry carries minInsyncReplicas
```

Every setting of a declaration is on the topic explicitly, so the broker's configuration of the topic
names each and no topic takes a value from the broker's defaults. A redeclaration changes `config` in
place; Strimzi's topic operator applies it without touching any service.

## `AnkkaProject.status`

```yaml
brokerNodes: 3                     # the sum of the broker pools' replicas; absent without the type
topics:
- name: transactions
  phase: Provisioned               # Waiting | Provisioned | Recovered | Failed
  partitions: 12
  replicas: 3                      # what the KafkaTopic resource says
  config: {retention.ms: "7776000000", ...}
  detail: null
```

## Decisions (`TopicProvisioning.decide`, in order)

1. no broker → `Failed: the installation has no broker`
2. declared `replicas` above `brokerNodes` → `Failed: topic 'money.transactions' asks for 5 copies and
   the broker has 3 broker nodes`; the topic is not rendered, so nothing is made on the broker
3. observed partitions above declared → `Failed` (the shrink, as today)
4. a permanent Strimzi reason (`NotSupported`, `InvalidRequest`) → `Failed` with its message; a
   replication change Strimzi cannot make without Cruise Control lands here if it ever reaches the
   broker, which the control plane's refusal prevents
5. ready, partitions, replicas (where declared) and config equal → `Provisioned` or `Recovered`
6. otherwise `Waiting` (a change not yet applied)

A broker shrunk below a topic's copies makes that topic `Failed` under rule 2 on the next pass and
the broker reports it under-replicated; nothing in ankka refuses the shrink.

## The node count

`Executor.brokerNodes(namespace, cluster)`: `list` of `kafkanodepools` labelled
`strimzi.io/cluster=<cluster>`, the sum of `spec.replicas` over those whose `spec.roles` include
`broker`. Read once per project reconcile. `None` when the type is not installed, in which case rule
2 does not apply and the topic is rendered.

## The `ankka-project` ConfigMap

Unchanged: `topics.json` carries `name`, `partitions`, `compacted` and the contract, as 037 wrote it.
The runtime learns a topic's cleanup policy and minimum in-sync copies from the broker, not from
this file ([publishing.md](publishing.md)).
