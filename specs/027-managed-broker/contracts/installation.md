# Contract: the broker component

`kustomization/components/broker/`, enabled by the local overlay and by the example cloud overlay.
Held by `RemoteOverlaySuite`, `ReservedProjectIdsSuite` and the broker's k3s suite.

## What it installs

| Object | In | Notes |
|---|---|---|
| Namespace `ankka-broker` | — | |
| Strimzi 1.2.0's cluster operator and CRDs | `ankka-broker` | pinned by version, in a nested Kustomization so its namespace transformer touches nothing else; it watches its own namespace only |
| `Certificate` `ankka-broker` | `ankka-broker` | `ankka://platform/broker`, DNS names of the bootstrap and broker Services, issued by `ankka-service`, one year, renewed 30 days before |
| `KafkaNodePool` `dual` | `ankka-broker` | one node, both roles, a persistent claim |
| `Kafka` `ankka` | `ankka-broker` | one `tls` listener on 9093 as research R3; `authorization: simple`; `auto.create.topics.enable: false`; topic and user operators |
| the listener's `networkPolicyPeers` | `ankka-broker` | 9093 from pods of ankka workloads in ankka's namespaces; nothing else. Strimzi writes the listener's network policy itself and admits every pod without this, and a second, stricter policy could not narrow it, since policies only add |
| `Role` and `RoleBinding` | `ankka-broker` | the ankka operator's grant on topics and users |
| a patch | the ankka operator's Deployment | the three `ANKKA_BROKER_*` settings |

## The two overlays

| | local | cloud (example) |
|---|---|---|
| nodes | 1 | `SET` |
| storage | 2Gi persistent claim | `SET` |
| Kafka heap and memory bounds | 256–512 MiB heap | `SET` |
| replication | 1 | follows the node count, `SET` |

`RemoteOverlaySuite` asserts the cloud overlay renders the same component, that each of those is a
placeholder there, and that the operator's container is the one the patch sets the three settings
on, once.

## Order of installation

Strimzi's CRDs must exist before a `Kafka` can be applied, as CNPG's must before a `Cluster`.
`deploy-local.sh` applies the Strimzi part of the component first, server-side, and waits for its
operator, as it does for CNPG, cert-manager and Envoy Gateway; then the overlay as a whole. The
installation guide's ordered steps gain the same line.

## Upgrading an installation that gains the broker

Every service of the installation is rolled once, because each is told where the broker is; and
each service certificate is reissued once, with a common name. No request is refused by either. A
service that names its own broker is unaffected.

## Removing a topic

Nothing the platform does removes a topic, a user or what was published. Whoever runs the
installation removes a topic by deleting its `KafkaTopic` in `ankka-broker`, which Strimzi turns
into the topic's deletion.
