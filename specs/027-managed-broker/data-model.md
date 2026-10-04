# Data Model: Managed Broker

What gains a field or a type, layer by layer. Nothing is stored in a database by this feature; the
broker's own state is Strimzi's resources.

## Descriptor (`controlplane-api`)

**`TopicDeclaration(name: String, partitions: Int)`**, new.

**`ServiceSpec.topics: Vector[TopicDeclaration] = Vector.empty`**, new. The shared codec omits a
field at its default, so an existing descriptor's wire form is unchanged.

Rules (`ServiceSpec.problems`, every problem at once; messages in
[contracts/descriptor.md](contracts/descriptor.md)):

- a name is lower-case letters, digits, `-` and `.`, starts and ends with a letter or digit, 1 to
  100 characters;
- partitions are 1 to 1000;
- a name is declared once;
- `topics` is refused for web hosting;
- `topics` is refused beside any variable starting `ANKKA_KAFKA_`.

**`ServiceSpec.suppliesBroker: Boolean`**, derived: any `env` name starts `ANKKA_KAFKA_`. By name,
never value, as the database's escape hatch is.

## Status as members read it (`controlplane-api`)

**`ServiceStatus.broker: Option[String] = None`**: a phrase, as `database` is — `supplied`,
`waiting for broker`, `provisioned`, `recovered existing topics`, `broker provisioning failed`.
Absent when the resource reports none.

**`ServiceStatus.topics: Vector[String] = Vector.empty`**: the qualified names of the topics the
service declares, as the broker holds them.

## Control plane (`controlplane`)

**`Service.broker: Option[String]`** and **`Service.topics: Vector[String]`**, folded from
`ServiceObserved`, which gains `broker: Option[String] = None` and `topics: Vector[String] =
Vector.empty` with defaults so journals written before this feature replay
(`EventCompatibilitySuite`). The listing row carries the declared topics with their partitions, for
the endpoint's check across services.

## Resource (`crd`)

**`AnkkaServiceSpec`** gains:

| Field | Type | Default | Meaning |
|---|---|---|---|
| `topics` | `List[TopicEntry]` | `Nil` | the declared topics |
| `provisionBroker` | `Boolean` | `true` | false when the descriptor supplies its own broker |

**`TopicEntry(name: String, partitions: Int)`**.

**`AnkkaServiceStatus.broker: Option[BrokerStatus] = None`**.

**`BrokerStatus`**: `phase` (`Supplied`, `Waiting`, `Provisioned`, `Recovered`, `Failed`),
`topics: List[String]`, `recovered: Boolean`, `detail: Option[String]`.

Each is declared in `kustomization/components/crd/ankkaservice.yaml`; `CrdSchemaSuite` holds the
schema and the case classes to each other in both directions.

## Operator (`operator`)

**`Settings.broker: Option[BrokerSettings]`**, with `BrokerSettings(bootstrap, namespace,
cluster)`, from `ANKKA_BROKER_BOOTSTRAP`, `ANKKA_BROKER_NAMESPACE`, `ANKKA_BROKER_CLUSTER`.

**`BrokerObservation`**: `user: StrimziObjectState`, `topics: Map[String, TopicState]`,
`resourceCreatedAt: Option[Instant]`.

- `StrimziObjectState(exists, ready, reason: Option[String], message: Option[String], createdAt:
  Option[Instant])`
- `TopicState(state: StrimziObjectState, partitions: Option[Int])`

**`BrokerPlan`**: `NotNeeded`, `Supplied`, `Waiting(detail)`, `Ready(recovered)`,
`Failed(problems)`, with `reportedPhase` as `ProvisioningPlan` has. The rules are research R8.

**`BrokerNames`**: `user(project, service) = "<project>.<service>"`, `topic(project, name) =
"<project>.<name>"`, `topicPrefix(project) = "<project>."`, `groupPrefix(project, service) =
"ankka.<project>.<service>."`. One place, read by the rendering and by the tests that assert it.

**Typed Strimzi resources** (`operator/strimzi/`): `KafkaTopicResource` (spec `partitions`; status
`conditions`, `topicName`) and `KafkaUserResource` (spec `authentication`, `authorization.acls`;
status `conditions`, `username`), group `kafka.strimzi.io`, version `v1`.

**Actions**: `EnsureKafkaTopic(KafkaTopicResource)`, `EnsureKafkaUser(KafkaUserResource)`, both
server-side apply. There is no action that removes either.

## Runtime (`runtime`)

**`KafkaConnection(bootstrapServers: String, tlsDirectory: Option[Path], topicPrefix: String)`**,
read by `ProjectionRuntime.fromEnv` from the three variables of research R9; `topicPrefix` is `""`
and `tlsDirectory` is `None` for a supplied broker, which is today's behaviour.

## States

A service's broker phase:

```text
(none) ── declares a topic or the installation has a broker ──▶ Waiting ──▶ Provisioned
                                                                   │              │
                                                                   ▼              ▼
                                                                 Failed      (service deleted;
                                                                              user and topics kept)
                                                                                  │
                                              applied again under the same name   ▼
                                                                              Recovered
Supplied: the descriptor gave an ANKKA_KAFKA_* variable; no other state is entered.
```

`Failed` clears itself when what caused it does: a descriptor applied again with partitions the
topic can have, or a broker installed.
