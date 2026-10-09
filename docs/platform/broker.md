---
title: The installation's broker
description: The Kafka an installation provides for every project — what the component installs, how a service is known by its certificate, why a project's topics are its own, what is kept, sizing it, and running without it.
kind: guide
related: [build/topics.md, platform/databases.md, platform/networking.md, platform/install-cloud.md]
---

# The installation's broker

An ankka installation has one Kafka broker, which it provides for every project, as it provides each
project a database. A service is told where the broker is with nothing in its descriptor, connects with
the certificate the platform already issued it, and reaches the topics of its own project and nothing
else. A topic exists because its project declares it; [Broker topics](../build/topics.md) describes
declaring one and using it from a service.

The broker is run by [Strimzi](https://strimzi.io/), a Kubernetes operator for Kafka.

## What the broker component installs

The `broker` component of `kustomization/components/` installs, in the namespace `ankka-broker`:

- **Strimzi's cluster operator** and its resource types, at a pinned release.
- **One Kafka**, `ankka`, in KRaft mode: a node pool, `dual`, whose nodes are both controller and broker,
  one node on a local platform, with a persistent volume. Topics are never made by publishing to them.
- **Strimzi's entity operator**, which turns the platform's `KafkaTopic` and `KafkaUser` resources into
  topics and permissions on the broker.
- **The broker's certificate**, `ankka-broker`, naming `ankka://platform/broker`, issued from the
  installation's service authority.
- **What the ankka operator needs**: permission to write `KafkaTopic` and `KafkaUser` resources in
  `ankka-broker`, and never to delete one, and the three settings that tell it where the broker is.

An installation without the component has no broker, and its operator renders nothing of one.

## The listener

Services connect to one listener, `tls` on port 9093, at `ankka-kafka-bootstrap.ankka-broker.svc:9093`.

- It serves the broker's certificate, so a service verifies the broker with the authority certificate it
  already holds.
- It requires a client certificate from the installation's service authority, and trusts that authority
  by its certificate alone. The broker is never given the authority's key, so it cannot issue an identity.
- Only pods of ankka's workloads, in namespaces the platform made, may connect at all. Anything else in
  the cluster is refused by the listener's network policy before TLS begins.

The broker's certificate lasts a year and is renewed a month before it expires. Strimzi replaces the
broker's pod whenever the listener's certificate changes, so a certificate that renewed daily, as every
workload's does, would restart the broker daily. On one node that renewal is a restart of a few
seconds, during which publishing waits and is retried.

## How a service is known

Kafka knows a client by its certificate's subject. When the installation has a broker, the certificate
the platform issues every service carries the common name `<project>.<service>`, beside its
`ankka://<project>/<service>` identity. There is no other credential: no password, no second
certificate, nothing more mounted.

For each service with components the platform writes one `KafkaUser`, named `<project>.<service>`, in
`ankka-broker`, with two permissions:

| Resource | Name | Operations |
|---|---|---|
| topics | everything starting `<project>.` | read, publish, describe |
| consumer groups | everything starting `ankka.<project>.<service>.` | read |

It can make nothing, and it can do nothing to the cluster itself. So a project is a boundary the broker
keeps: a service of one project is refused another project's topics by the broker, whatever its code
does, and its consumer groups are its own.

A service whose descriptor names a broker of its own is given nothing here: no user and no certificate
name. A web-hosted service has no components and is given nothing either.

### Declared brokers

A project may declare brokers beside the installation's, each with its address, the shape of its
credential and the project secret holding it, and a component names one for a single topic. The
declarations reach the cluster on the project's `AnkkaProject` resource, and for each the operator gives
every service of the project the secret, mounted read-only at `/var/run/secrets/ankka/brokers/<name>` on
the platform's container, with the variables that name the broker's address, shape and directory. A
process-hosted service's own container gets neither. A broker declared or removed changes each service's
pod template once, so each is rolled once. The platform makes nothing on a declared broker: its topics
are its owner's, and a service reaches them with the credential the project gave it. How a component
names one is on [Broker topics](../build/topics.md#a-topic-on-another-broker).

## Topics

A member declares a project's topics on the project, once each, with their partitions, their settings
and the contract they carry. The declarations reach the cluster as an `AnkkaProject` resource in the
project's namespace, written by the control plane. For each, the operator writes a `KafkaTopic` named
`<project>.<name>` with the declared partitions, never fewer than the topic already has, its copies, and a
`config` that names every setting: `retention.ms`, `retention.bytes`, `cleanup.policy`,
`delete.retention.ms`, `min.compaction.lag.ms`, `max.compaction.lag.ms` and `min.insync.replicas`. No topic
takes a setting from the broker's own defaults. A changed setting is applied to the topic in place, by
Strimzi's topic operator, with nothing else touched; the operator reports a topic `waiting for broker`
until its resource holds every setting declared. The name the broker holds is what the broker's own tools
list; a service's code uses the declared name.

A topic's copies are fixed when it is declared: Strimzi changes a topic's replication only through Cruise
Control, which the platform does not install. The operator reads how many broker nodes the broker has from
its node pools, and a topic asking for more copies than that is not made and is reported `failed`, naming
the count. A topic declared before topics stated their copies has none on its resource, so the broker's
`default.replication.factor` decided them when it was made.

The values a topic's first declaration is given for a setting it leaves out, and the bounds every
declaration is held to, are the control plane's, set as variables on its Deployment:

| Variable | Means | Shipped |
|---|---|---|
| `ANKKA_TOPIC_DEFAULT_RETENTION` | Retention time of a declaration that gives none. | `7d` |
| `ANKKA_TOPIC_DEFAULT_RETENTION_SIZE` | Retention size per partition, likewise. | `none` |
| `ANKKA_TOPIC_DEFAULT_CLEANUP` | Cleanup policy, likewise. | `delete` |
| `ANKKA_TOPIC_DEFAULT_TOMBSTONE_WINDOW` | Tombstone window, likewise. | `1d` |
| `ANKKA_TOPIC_DEFAULT_MIN_COMPACTION_LAG` | Minimum compaction lag, likewise. | `0s` |
| `ANKKA_TOPIC_DEFAULT_MAX_COMPACTION_LAG` | Maximum compaction lag, likewise. | `none` |
| `ANKKA_TOPIC_DEFAULT_COPIES` | Copies, likewise. | `1` |
| `ANKKA_TOPIC_DEFAULT_MIN_IN_SYNC` | Minimum in-sync copies, likewise. | `1` |
| `ANKKA_TOPIC_LONGEST_RETENTION` | The longest retention time a declaration may ask. | `everything` |
| `ANKKA_TOPIC_LARGEST_RETENTION_SIZE` | The largest retention size per partition. | `none` |
| `ANKKA_TOPIC_MOST_COPIES` | The most copies. | `3` |
| `ANKKA_TOPIC_WARNING_THRESHOLD` | The retention time below which a view reading a topic is warned. | `30d` |

The shipped seven days is a laptop's. A view built from a topic reaches only what the topic kept, so an
installation serving facts that must outlive a week raises `ANKKA_TOPIC_DEFAULT_RETENTION` and
`ANKKA_TOPIC_LONGEST_RETENTION` before its first topic is declared: a change later changes no topic
already declared, each of which keeps the value it was given. A value the control plane cannot read, or a
default outside its own bound, stops the control plane naming the variable.

When the control plane starts, it fills the settings of any topic declared before topics stated them from
the defaults then in force, and records on the project that the platform did; such a topic's copies stay
what the broker gave it.

A contract is not the broker's business: the operator writes the project's declarations, each topic's
contract name and the fingerprint of its schema among them, into a `ConfigMap` named `ankka-project` in
the project's namespace, which every service's platform container mounts at `/var/run/ankka/project`,
and the runtime checks what its components state against it when the service starts. The schema
documents themselves are held in the `ankka-project-schemas` `ConfigMap` beside it, written by the
control plane, one entry per fingerprint. How a contract is declared and checked is on
[Broker topics](../build/topics.md#contracts).

Installing the broker on an installation that already runs services gives every one of them the broker
on the operator's next pass: each is told where the broker is, and its certificate is reissued with its
name. That changes each service's pod template once, so each service is rolled once, without refusing a
request.

## What is kept

The platform never removes a topic, what was published to it, or a service's user. Neither has an owner
in the project's namespace, so deleting a service, removing a topic's declaration or deleting the project
leaves them, as a service's database is left. A service applied again under its old name finds its user
and reports it recovered; a topic declared again finds what was published to it and reports itself
recovered.

Removing a topic is for whoever runs the installation. First a member stops declaring it, or the
platform makes it again, empty, on its next pass; then delete its resource, and Strimzi deletes the
topic and everything on it:

```bash
kubectl -n ankka-broker delete kafkatopic money.transactions
```

A user is removed the same way, `kubectl -n ankka-broker delete kafkauser money.wallet`. If the service
still exists, the platform writes both again on its next pass.

## Sizing the broker

The component's values suit a laptop: one node, 2Gi of storage, a 512MB heap and 1Gi of memory. The
example cloud overlay patches the node pool with `broker-size.yaml`, whose every value is marked `SET`:
the node count, each node's storage, the heap and the memory and CPU it is given.

A new installation that wants its topics on three nodes lists the `broker-three-nodes` component after
`broker` and `controlplane`. It sets the node pool to three, `default.replication.factor` and both
internal topics' factors to three, `min.insync.replicas` and `transaction.state.log.min.isr` to two, and
the control plane's default copies to three with two in sync, all together. One node can then stop
without losing a message the broker acknowledged or refusing a publication. It holds three times the
storage of one node, since every partition is on every node.

It is a shape for a new installation, not a way to grow a running one. The broker's node is both
controller and broker, so changing how many there are changes the controller quorum, which the platform
does not do; growing a running broker is done by Strimzi's own procedure, and every topic made before keeps
the copies it was made with, which its row in `ankka projects topics list` says has fewer copies than the
installation's default.

Strimzi's images come from quay.io. A cluster that pulls only through a cache of its own needs that
cache to mirror quay.io/strimzi too.

## An installation without a broker

An installation may leave the component out, for instance to keep a Kafka it already runs. Then:

- a service is told of no broker, and one that needs a broker names it in its descriptor's `env` with
  `ANKKA_KAFKA_BOOTSTRAP_SERVERS`, as [Broker topics](../build/topics.md) describes, or a project declares
  one and its components name it per topic;
- a topic a project declares is reported failed, because the installation has no broker to make it
  on;
- everything else is deployed exactly as it would be with no broker feature at all.
