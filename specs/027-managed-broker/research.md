# Research: Managed Broker

Decisions the plan rests on. **Measured** means run on 2026-10-04 in a throwaway k3s node
(`rancher/k3s:v1.35.1-k3s1`, the image the suites use) with Strimzi 1.2.0 and Kafka 4.3.1;
**verified** means read in the code at the commit this branch started from; anything else is a
decision.

## R1. Strimzi is the broker, and what it costs

**Decision**: Strimzi 1.2.0 (API `kafka.strimzi.io/v1`, Kafka 4.3.1, KRaft, one `KafkaNodePool`
with both roles) stays, as the clarification chose.

**Measured**:

| Step | Time |
|---|---|
| Cluster operator applied to Ready | 27 s (8 s of it pulling a 213 MB image) |
| `Kafka` + `KafkaNodePool` applied to `Ready` | 56 s (9 s pulling a 395 MB image) |
| One `KafkaTopic` and two `KafkaUser`s applied to `Ready` | 1 s |

A suite that installs the broker pays about a minute and a half once, beside the CNPG and
cert-manager installs it already pays. That is acceptable: only the suites that need a broker
install it (R18), and CI runs no k3s suite.

Resident memory, the four JVMs Strimzi runs: cluster operator 249 MiB, Kafka 454 MiB, topic
operator 201 MiB, user operator 204 MiB — about 1.1 GiB. This is the real cost, on a laptop's
local platform above all. The component bounds each (R19).

**Alternatives**: a plain broker with ankka's operator speaking the admin protocol — no extra
operator and several hundred MiB less, at the price of the operator's first non-declarative work.
Rejected by the clarification; the measurement does not reopen it.

## R2. The broker knows a service by its certificate's subject, and today it has none

**Verified**: `ZeroTrust.serviceCertificate` sets URIs and DNS names and no `commonName`; its
subject is empty. Kafka names a TLS client by the certificate's subject and nothing else: the
default principal builder reads the distinguished name, and `ssl.principal.mapping.rules` rewrite
only that. A subject alternative name cannot be read without a principal builder class added to the
broker's image.

**Measured**: a certificate from the trusted authority with an empty subject and the URI
`ankka://money/wallet` authenticates as the principal `User:` and is denied everything.

**Decision**: when the installation has a broker, the operator renders the service certificate with
`commonName: <project>.<service>`. The credential is then the certificate the service already
holds, as the spec says: no second certificate, no new Secret, no new volume. A project id and a
service name are DNS labels, so the dot cannot be ambiguous.

**Consequences**: every service's `Certificate` object changes once, when the broker is installed;
cert-manager reissues and `RotatingTls` picks the new files up with no restart. Without a broker the
certificate is rendered exactly as before. The pinned renderings change only in the case with a
broker.

**Alternatives**: a broker certificate per service, as the database has (`<svc>-database-tls`,
common name the role) — another `Certificate`, Secret, volume and mount for an identity the service
certificate already carries, from the same authority. A Strimzi-issued user certificate — Strimzi
writes its Secret in the broker's namespace, so ankka's operator would read a key and copy it across
namespaces, which the secret rule forbids.

## R3. The broker trusts ankka's service authority by its certificate alone

**Decision**: one internal listener, `tls` on 9093.

- Its server certificate is a cert-manager `Certificate`, `ankka://platform/broker`, issued by the
  `ankka-service` ClusterIssuer, named to Strimzi with `configuration.brokerCertChainAndKey`. A
  service verifies the broker with the `ca.crt` it already mounts.
- Clients are authenticated with `authentication.type: custom`, `sasl: false`, and
  `listenerConfig` `ssl.client.auth: required`, `ssl.truststore.type: PEM`,
  `ssl.truststore.location` naming the `ca.crt` of that same Secret, mounted into the broker with
  `template.pod.volumes` and `template.kafkaContainer.volumeMounts`.

**Measured**: Strimzi accepts this and renders it as `listener.name.tls-9093.ssl.*`; clients with
certificates from the authority connect; `auto.create.topics.enable=false` is honoured.

**Why not Strimzi's own client authority**: a `tls` listener trusts Strimzi's clients CA, and
supplying one's own means handing Strimzi the authority's private key, with which it could mint any
service's identity. The custom listener needs only the public certificate.

**Measured**: replacing the listener certificate's Secret makes Strimzi replace the broker pod,
within 12 s. A 24-hour certificate, which every other workload has, would restart the broker daily.
The broker's certificate is therefore long-lived (one year, renewed 30 days before), and its renewal
is a rolling restart Strimzi performs.

**Security**: Strimzi's super users are subjects with `O=io.strimzi`; ankka's subjects are a common
name alone and cannot equal one. A mount certificate, the gateway's and the platform's own have no
subject and are `User:`, with no permission.

## R4. A user and its permissions are one resource

**Decision**: per service, one `KafkaUser` named `<project>.<service>` in the broker's namespace,
`authentication.type: tls-external`, `authorization.type: simple` with two rules:

| Resource | Pattern | Operations |
|---|---|---|
| topic `<project>.` | prefix | Read, Write, Describe |
| group `ankka.<project>.<service>.` | prefix | Read |

No `Create`, on anything.

**Measured** (`status.username` is `CN=money.wallet`):

| Attempt | Outcome |
|---|---|
| `money.wallet` publishes to and reads `money.transactions` under `ankka.money.wallet.view.entries` | succeeds |
| `money.wallet` reads under the group `somebody-else` | `GroupAuthorizationException` |
| `casino.lobby` publishes to `money.transactions` | `TopicAuthorizationException` |
| `casino.lobby` reads `money.transactions` | `TopicAuthorizationException` |
| `money.wallet` publishes to `money.undeclared` | no topic is made; the producer waits |

The group prefix is feature 024's qualified group id. Until 024 is on `main` the runtime's group ids
are `ankka-view-<component>` and the broker refuses them, so this feature's read scenarios need 024
(R17).

## R5. A declared topic is one resource, owned by nothing

**Decision**: per declared topic, one `KafkaTopic` named `<project>.<name>` in the broker's
namespace with `spec.partitions` and no `spec.replicas`, so the installation's
`default.replication.factor` decides replication and the operator never states it.

**Measured**: raising partitions is applied and stays `Ready`; lowering gives `Ready=False`,
`reason=NotSupported`, `message=Decreasing partitions not supported`.

The name must be a Kubernetes name, so a declared topic name is lower-case letters, digits, `-` and
`.`, starting and ending with a letter or digit, at most 100 characters; with the project and the
dot it is far inside Kafka's 249.

## R6. Where the resources live, and who may write them

**Decision**: `KafkaTopic` and `KafkaUser` live in the broker's namespace, where Strimzi's topic
and user operators watch. They carry ankka's managed-by label and **no owner reference**: an owner
reference cannot cross namespaces, and nothing here is ever deleted (FR-007). The operator's grant
is a `Role` in the broker's namespace, shipped by the broker component — `kafkatopics` and
`kafkausers`: get, list, watch, create, patch; no delete — so an installation without the component
grants nothing.

They are modelled as typed fabric8 resources in `operator/strimzi/`, as CNPG's are in
`operator/cnpg/`: the status the decision reads is parsed by a class a unit test round-trips, not
picked out of a generic map.

## R7. The operator learns there is a broker from its own settings

**Decision**: three settings on the operator's Deployment, written by the broker component's patch:
`ANKKA_BROKER_BOOTSTRAP` (`ankka-kafka-bootstrap.ankka-broker.svc:9093`), `ANKKA_BROKER_NAMESPACE`
and `ANKKA_BROKER_CLUSTER`. All three or none; one or two is a startup failure naming what is
missing. None means the installation has no broker, and every service renders as before this
feature.

## R8. The decision is a pure function, in the database's shape

**Decision**: `BrokerProvisioning.decide(spec, settings.broker, observed): BrokerPlan`, beside
`Provisioning.decide`:

| Rule, in order | Plan | Reported phase |
|---|---|---|
| web-hosted | `NotNeeded` | none |
| the descriptor gave an `ANKKA_KAFKA_*` variable | `Supplied` | `Supplied` |
| no broker, no declared topic | `NotNeeded` | none |
| no broker, a declared topic | `Failed("the installation has no broker")` | `Failed` |
| a topic or the user is `Ready=False` with reason `NotSupported` or `InvalidRequest` | `Failed(messages)` | `Failed` |
| a topic exists with more partitions than declared | `Failed(...)`, and that topic is not applied | `Failed` |
| the user or any declared topic is absent, unobserved or not ready | `Waiting(detail)` | `Waiting` |
| otherwise | `Ready(recovered)` | `Provisioned` or `Recovered` |

`recovered` is true when the service declares a topic, and its user and every topic it declares
were made before the service's resource was: the service was here before. Any other not-ready
condition is transient, as CNPG's are.

## R9. What a service is told, and what installing a broker does to running ones

**Decision**: the operator injects three variables into every service the plan is `Waiting` or
`Ready` for:

| Variable | Value |
|---|---|
| `ANKKA_KAFKA_BOOTSTRAP_SERVERS` | the operator's `ANKKA_BROKER_BOOTSTRAP` |
| `ANKKA_KAFKA_TLS_DIRECTORY` | `/var/run/secrets/ankka/service`, where the service certificate is mounted |
| `ANKKA_KAFKA_TOPIC_PREFIX` | `<project>.` |

All three start `ANKKA_KAFKA_`, which `PlatformVariables.SharedPrefixes` already gives to both
programs of a process-hosted service; no list changes. A descriptor that gives any `ANKKA_KAFKA_*`
variable is `Supplied` and gets none of them, so the two never mix. None holds a secret.

**Consequence**: three new variables change every service's pod template, so installing the broker
rolls every service of the installation once. A rolling replacement refuses no request; the upgrade
page says so.

## R10. The runtime connects with the certificate it has, and keeps up with its renewal

**Decision**: when `ANKKA_KAFKA_TLS_DIRECTORY` is set, the runtime's Kafka clients use
`security.protocol=SSL` and `ssl.engine.factory.class` naming a class in `runtime` that implements
Kafka's `SslEngineFactory` over a `RotatingTls` of that directory: every new connection takes an
engine from the current context, in client mode, with the broker's host name checked. The service
certificate is renewed every eight hours, and a keystore read once would be presenting an expired
certificate within a day.

**To verify first** (a gate task): that the Kafka client version `pekko-connectors-kafka` brings
accepts a custom engine factory with no keystore configured, and that a renewed certificate is used
by the next connection. If it does not, the fallback is PEM keystores re-read by recreating the
client on authentication failure, inside the restart the subscriber already has.

## R11. A component names a topic; the runtime adds the project

**Decision**: `ANKKA_KAFKA_TOPIC_PREFIX` is applied where the runtime hands a topic to Kafka, in
`KafkaPublisher.publish` and `KafkaSubscriber.subscribe`, and nowhere else. A component's declared
connections, the topology and the logs keep the name the component wrote, with the log line for a
broker error giving both. A supplied broker has no prefix and behaves exactly as today.

## R12. A topic nobody declared

**Measured**: a publish to an undeclared topic of the service's own project makes no topic; the
producer waits for metadata (`max.block.ms`) and fails. A listing with the service's own
certificate shows only the declared topic.

**Decision**: nothing changes at the broker. In the runtime: a failed publish is logged at warn
with the topic's name and the reason, and fails the delivery, which the projection retries with the
backoff it has; a subscriber to a missing topic logs the name once per backoff and goes on asking.
Consumers get `metadata.max.age.ms` of 30 seconds, so a topic declared later is found within that.
The service's readiness is untouched.

## R13. A service that never touches a topic connects to nothing

**Verified**: `KafkaPublisher.apply` builds its `SendProducer` when the runtime starts, and a
producer connects to its bootstrap address at once.

**Decision**: the producer is made on first publish. With every service of an installation now
told where the broker is, an eager producer would be one idle connection per instance.

## R14. Three checks, in three places

- **In the descriptor** (`ServiceSpec.problems`, so the CLI and control plane agree): a topic's name
  and partitions; a name twice; `topics` with web hosting; `topics` beside any `ANKKA_KAFKA_*`.
- **In the control plane's endpoint**, since an entity cannot see another: a topic another service
  of the project declares with different partitions is refused, naming that service; and partitions
  lower than the service's own applied descriptor are refused. The first reads the services
  listing, which lags.
- **In the operator**, because of that lag: it never applies fewer partitions than a topic has, and
  reports the service `Failed` with the count (R8).

## R15. The status, end to end

**Decision**: the resource's status gains `broker`, absent when there is none to report:
`phase`, `topics` (the qualified names), `recovered`, `detail`. The control plane keeps the phase
and topics on the service as it keeps the database's phase, and `ServiceStatus` gains
`broker: Option[String]`, a phrase as `database` is, and `topics: Vector[String]`. `ankka services
get` prints a `broker` line and the topics; the console's schema and service page gain both, held
to the codec by `ControlPlaneFixturesSuite`.

## R16. An installation that keeps its own Kafka

**Verified**: Strimzi 1.2.0's release ships `strimzi-topic-operator-1.2.0.yaml` and
`strimzi-user-operator-1.2.0.yaml`, the two operators as standalone deployments pointed at a
bootstrap address. So Strimzi can manage topics and users on a Kafka it did not create.

**Decision**: this feature ships the one component that runs the broker. The standalone route is
described in the platform documentation as possible and is not built or tested here; the operator's
three settings (R7) are all it would need.

## R17. Feature 024 comes first

The permission on consumer groups is 024's `ankka.<project>.<service>.` prefix, kept in one constant
beside the user's rendering. This branch is rebased onto `main` once 024 is merged; the proof of
what is already built (User Story 1) and everything on the operator's and control plane's side can
be built before that, and the scenarios in which a view reads a provisioned topic cannot pass
without it.

## R18. Brokers in the k3s suites

- **A plain broker, for User Story 1**: one `apache/kafka` pod with a plaintext listener and a
  Service, stood up by a test helper. It is "a broker in the cluster that is not the
  installation's", it needs no Strimzi, and it lets FR-001 merge alone.
- **The installation's broker, for the rest**: `BrokerStack.install`, in the operator's test
  sources beside `PkiStack`, applies the broker component's own manifests with ephemeral storage
  and waits for `Kafka` to be `Ready`. One new suite in the control plane's tests runs
  `features/broker/` against it; no existing suite installs it.

## R19. The component bounds what the broker takes

**Decision**: the component sets the Kafka container's heap (`-Xms256m -Xmx512m` locally) and
memory requests and limits for it and for the entity operator's two containers; the cloud overlay
leaves replicas, storage size and those bounds as placeholders marked `SET`. Storage is a
persistent claim in both, so a restarted broker keeps its topics; the suites alone use ephemeral.
