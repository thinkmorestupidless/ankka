# Data Model: Managed Broker

What gains a field or a type, layer by layer. The broker's own state is Strimzi's resources; the
project's declared topics are events of the `Project` entity.

*Revised 2026-10-05: topics are declared on the project (research R20–R23). A descriptor declares
none, and `TopicDeclaration`, `ServiceSpec.topics`, `TopicEntry` on `AnkkaServiceSpec` and
`ServiceStatus.topics` are removed.*

## Wire (`controlplane-api`)

**`ProjectTopics`**, new, the rules the CLI and the control plane both apply to a declaration:

- a name is lower-case letters, digits, `-` and `.`, starts and ends with a letter or digit, 1 to
  100 characters;
- partitions are 1 to 1000.

Fewer partitions than the project declares is the entity's rule (below), since only it knows.

**`ProjectTopic(name: String, partitions: Int, phase: Option[String], detail: Option[String])`**,
what `GET /projects/{id}/topics` answers per topic. `phase` is a phrase as a database's is —
`waiting for broker`, `provisioned`, `recovered`, `failed` — absent before the operator has
reported.

**`TopicDeclarationRequest(partitions: Int)`**, the body of `PUT /projects/{id}/topics/{name}`.

**`ServiceSpec.suppliesBroker: Boolean`**, derived and unchanged: any `env` name starts
`ANKKA_KAFKA_`.

**`ServiceStatus.broker: Option[String] = None`**: the service's credential, a phrase — `supplied`,
`waiting for broker`, `provisioned`, `recovered`, `broker provisioning failed`. Absent when the
resource reports none.

**`ServiceStatus.undeclaredTopics: Option[Vector[String]] = None`**: topics the service's
components use that its project does not declare, by the names the components gave them. Absent when
the service's topology could not be read, or on a listing row; `Some(Vector.empty)` when it was read
and every topic is declared.

## Control plane (`controlplane`)

**`Project.topics: Map[String, DeclaredTopic]`**, with `DeclaredTopic(partitions: Int, declaredAt:
Instant)`, folded from two new events, each with `actor` and `at` defaults as every event has:

- `ProjectTopicDeclared(name, partitions, actor, at)`: the declaration made or raised. Declaring the
  same count again records nothing.
- `ProjectTopicRemoved(name, actor, at)`.

Commands `declare-topic` and `remove-topic` on the `Project` entity; `declare-topic` refuses fewer
partitions than the project declares, and both refuse a project that does not exist.

**`Service.broker: Option[String]`**, folded from `ServiceObserved.broker`, as before; the observed
topic list is gone from both.

**`ProjectProjection`**, new beside `ServiceProjection`: the project's declarations as an
`AnkkaProjectSpec`, written when they change and by the sweep.

## Resources (`crd`)

**`AnkkaProject`**, new, namespaced, short name `aproj`, one per project namespace, named for the
project:

| Field | Type | Meaning |
|---|---|---|
| `spec.projectId` | `String` | the project |
| `spec.topics` | `List[ProjectTopicEntry]` | the declared topics |
| `status.topics` | `List[ProjectTopicStatus]` | each topic's phase |

**`ProjectTopicEntry(name: String, partitions: Int, declaredAt: String)`** (RFC 3339).

**`ProjectTopicStatus(name: String, phase: String, partitions: Option[Int], detail: Option[String])`**,
`phase` one of `Waiting`, `Provisioned`, `Recovered`, `Failed`.

**`AnkkaServiceSpec`**: `provisionBroker: Boolean = true` stays; `topics` is removed.

**`BrokerStatus`** on `AnkkaServiceStatus`: `phase` (`Supplied`, `Waiting`, `Provisioned`,
`Recovered`, `Failed`), `recovered: Boolean`, `detail: Option[String]`; `topics` is removed.

`kustomization/components/crd/` declares both schemas; `CrdSchemaSuite` holds each to its case
classes in both directions.

## Operator (`operator`)

**`Settings.broker: Option[BrokerSettings]`**, unchanged.

**`BrokerObservation`** for a service: `user: StrimziObjectState`, `resourceCreatedAt`. Topics
leave it.

**`TopicObservation`** for a project: per declared topic, `TopicState(state, partitions)`.

**`BrokerPlan`** (a service's credential): `NotNeeded`, `Supplied`, `Waiting(detail)`,
`Ready(recovered)`, `Failed(problems)`.

**`TopicPlan`** (one declared topic): `Waiting(detail)`, `Ready(recovered)`, `Failed(problems)`;
research R22.

**`ProjectTopics.topicsToRender(spec, broker, observed)`**: the declared topics, less any whose
`KafkaTopic` already asks for more partitions.

**`ProjectReconciler`**, new beside `ServiceReconciler`: watches `AnkkaProject`, observes the
project's `KafkaTopic`s, renders `EnsureKafkaTopic` actions, writes the status.

**`BrokerNames`**, `StrimziObjectState.found`, the typed Strimzi resources and the actions are
unchanged.

## Runtime (`runtime`)

Unchanged by the revision: `KafkaConnection`, `KafkaTls`, the prefix, the bounded wait.

## States

A declared topic:

```text
declared ──▶ Waiting ──▶ Provisioned ──▶ (declaration removed; topic kept on the broker)
                │              │                         │
                ▼              ▼            declared again ▼
              Failed   (raised: Waiting               Recovered
                        until grown)
```

`Failed` clears itself when what caused it does: a broker installed, or the topic's resource
corrected by whoever administers the installation.

A service's credential:

```text
(none) ── the installation has a broker ──▶ Waiting ──▶ Provisioned
                                                │             │ (service deleted; user kept)
                                                ▼             ▼ applied again
                                              Failed       Recovered
Supplied: the descriptor gave an ANKKA_KAFKA_* variable; no other state is entered.
```
