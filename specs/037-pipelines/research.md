# Research: Pipelines Are Services

Every decision below was taken against ankka at `origin/main` (`7b81d38e`) and ankka-flow at
`28d6f1a`. Line numbers are those commits'. `R` is `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime`,
`S` is `modules/sdk/.../sdk`, `O` is `operator/src/main/scala/.../operator`, `SC` is
`sidecar/src/main/scala/.../sidecar`, `CP` is `controlplane/src/main/scala/.../controlplane`.

## The runtime, the protocol and the SDKs

### R1. A topic's options ride beside the source, never as new positional fields

**Decision**: `ChangeSource.Topic` (`S/ChangeSource.scala:44-49`) gains one field,
`options: TopicOptions = TopicOptions()`, with `TopicOptions(contract: Option[Contract],
broker: Option[String], parallel: Boolean)`. `fromTopic` gains an overload taking the options.
A consumer's publication becomes a value: `def produces: Option[Publication] =
produceTo.map(Publication(_))`, with `Publication(topic, contract: Option[Contract],
broker: Option[String])`; `produceTo` stays as the short form. `ConsumerDescriptor` carries
`produces`. The remote descriptors do the same: `RemoteSource.Topic(name, startFrom, options)`
(`R/remote/RemoteDescriptors.scala:29-33`) and `RemoteConsumerDescriptor.produces`
(`:135-141`), with `producesTo` kept for a Spec that states only the topic.

**Rationale**: `ChangeSource.Topic(topic, _, startFrom)` is matched positionally in six places
(`ProjectionRuntime` 538/599/654, `DeclaredConnections` 64, `KeyedView` 69, and the remote path);
a nested value changes none of them. `DeclaredConnections.DeclaredSource.Topic`
(`R/DeclaredConnections.scala:18-26`) and `destinationOf` (`:42-59`) are the one reading the
topology and the start-time rules share, so contract and broker are added there once.

**Alternatives**: new positional fields (every match site changes; a mistake compiles as a
wrong arity error far from the cause); options on the component rather than the source (a
view with several sources could not say which topic the broker is for).

### R2. A contract is a name and the fingerprint of a canonical schema document

**Decision**: `Contract(name: String, fingerprint: String)` in `core`. The fingerprint is
`sha256:` plus the hex SHA-256 of the schema document serialised under the JSON Canonicalization
Scheme (RFC 8785). Each SDK offers `Contract.fromSchema(name, bytes)` (Scala), `Contract.from_file`
(Python), `Contract.fromFile` (TypeScript), `Contract::from_file` (Rust); none derives a schema
from a type. `protocol/fixtures/contracts/fingerprints.json` holds rows `{"name", "schema",
"fingerprint"}` written by `ContractFixturesSuite` in `core`, and every SDK's conformance tests
fingerprint each row's schema and must produce the row's fingerprint. The control plane
fingerprints a declared schema the same way (it is Scala, so it uses `core`'s) and refuses a
declaration whose document is not JSON.

**Rationale**: the spec's answer is a document the developer fetched and keeps; JCS is the one
canonical form with an RFC and an implementation in every language the SDKs use (`rfc8785` on
PyPI, `canonicalize` on npm, `serde_jcs` on crates.io; Scala over jsoniter's parsed tree, as
`GraphJson` already walks one). Whitespace and key order in a saved file then do not change the
fingerprint; a changed field does.

**Alternatives**: hash the bytes as saved (a reformatted file is "another schema"); hash a
schema derived from the type (four derivations that must agree, and a fetched document that
would then be ignored); the name alone (the spec chose otherwise).

### R3. The check runs in the runtime at start, from a file the operator writes

**Decision**: `ProjectReconciler.actions` (`O/ProjectReconciler.scala:40-55`) renders a
ConfigMap `ankka-project` in the project's namespace holding `topics.json`: every declared
topic with its partitions, whether it is compacted, and its contract name and fingerprint; and
every declared broker with its address and shape. A new `Action.EnsureProjectConfig`, applied
server-side like `EnsureSchemaConfig` (`O/Executor.scala:514-521`). `Rendering.deployment`
mounts it read-only and `optional: true` at `/var/run/ankka/project` on the platform container
(the main container for embedded hosting, the sidecar for process and module hosting; never the
process), beside the TLS volumes (`O/Rendering.scala:1045-1046, 1596-1600`), and sets
`ANKKA_PROJECT_DECLARATIONS=/var/run/ankka/project/topics.json`.

The runtime reads the file once, in `ProjectionRuntime.start` before `rejectUnsupported`
(`R/ProjectionRuntime.scala:152-187`): for each component's topic source and publication, the
contract stated is compared with the declaration's. A difference in name or fingerprint, or no
statement where the declaration has a contract, is a problem named like the existing ones
(`cannot start ankka projections: - consumer 'relay' publishes to 'orders' as 'order.v2'; the
project declares 'order.v1'`). Without the variable, or with a topic the file does not list,
nothing is checked. The sidecar reaches the same code (`SC/Main.scala:175` builds the
`ProjectionRuntime` from env).

**Rationale**: the operator already watches `AnkkaProject` and already mounts one ConfigMap
(`ankka-schema`, `O/SchemaInit.scala:73-79`); a ConfigMap volume without `subPath` updates in
place, so a changed declaration is seen at the next start and restarts nothing, which is what the
spec says. `optional: true` means a project with no `AnkkaProject` yet, or a cluster without the
type, starts services as it does today. No new path between the control plane and a running
service is needed.

**Alternatives**: the control plane checks from topology after start (the component has already
read or published; chosen only as the listing's view, R5); the runtime fetches declarations from
the control plane (a new credentialled path from every service to the control plane, which today
only pulls); env variables (a changed declaration would roll every pod in the project).

### R4. A refusal at start reaches `services get` through the termination message

**Decision**: `DeclaredGrpc`'s write to `/dev/termination-log` (`R/DeclaredGrpc.scala:22-51`)
becomes a shared `StartRefusal.report(reason)` used by `ServiceBuilder.host`'s validation
failure (`R/Ankka.scala:202-208`), `ProjectionRuntime.rejectUnsupported` and
`rejectUnsupportedRemote`, and the sidecar's `Main.runProcess` on a discovery refusal
(`SC/Main.scala:73-93`). The operator sets `terminationMessagePolicy: FallbackToLogsOnError` on
the embedded and sidecar containers as it does on the module container
(`O/Rendering.scala:1264-1268`). `PodProblem.of` (`O/ClusterSnapshot.scala:46-58`) already reads
the message, `LifecycleRules.observe` puts it in `detail`, and `services get` prints it.

**Rationale**: the precedent exists and its scaladoc promises exactly this; the readiness
endpoints carry no reason and a refused service exits, so the termination message is the one
channel that survives the exit. The same change makes every existing start-time refusal visible
in `services get`, which `TopicSourceRules` violations are not today.

**Alternatives**: a status write from the runtime (the runtime has no API-server access, by
design); the operator doing the check (it does not know a service's components until they run).

### R5. The listing's view: topology edges carry the contract, the control plane compares

**Decision**: the topology's `topic-subscription` and `topic-publication` edges
(`R/TopologyJson.scala:300-334`) gain `contract` (name and fingerprint, when stated) and
`broker` (when named). `ServiceEndpoint.withUndeclaredTopics` (`CP/api/ServiceEndpoint.scala:74-94`)
grows into `withTopicChecks`: for each edge on a declared topic it compares the stated contract
with the project's; a service whose instance `startedAt` is before the declaration's `declaredAt`
is "not yet checked against it". `GET /projects/{id}/topics` lists, per topic, the services
stating it, each `checked`, `unchecked` or `mismatch`, and `ankka projects topics list` prints
it. A topic on a declared broker has node id `topic:<broker>/<name>` and is excluded from
`undeclaredTopics`.

**Rationale**: the spec's edge case asks for the listing to name services not yet checked; the
topology is what the control plane already reads per instance, and `startedAt` is in it
(`service.startedAt`, `TopologyJson.scala:71-113`).

**Alternatives**: none that avoids restarting services or a push from the runtime.

### R6. The wire carries the contract's name as `ce-type`

**Decision**: `KafkaPublisher.apply` (`R/Kafka.scala:208-209`) stops passing `_ => "message"`;
`ProjectionSupport.publishAll` and `applyConsumer` (`R/ProjectionSupport.scala:162-268`), the one
place messages are published, set `Metadata.CeType` to the publication's contract name when the
metadata does not already carry one, as `GraphConsumer` sets `ankka.graph-delta.v1` today
(`S/GraphConsumer.scala:186`). A publication without a contract keeps `message`. Nothing on the
subscribing side reads `ce-type`.

**Rationale**: one place, already the rule (`messaging.md`); readers outside ankka get the type
the spec promises; a graph consumer behaves exactly as before.

### R7. A declared broker is a second connection, chosen per topic by name

**Decision**: `ProjectionRuntime` holds the installation's publisher and subscriber as today and
a `Map[String, (MessagePublisher, MessageSubscriber)]` of declared brokers, built from
`KafkaConnection.named(env, name)`, which reads `ANKKA_TOPIC_BROKER_<NAME>_BOOTSTRAP_SERVERS`,
`ANKKA_TOPIC_BROKER_<NAME>_SHAPE` (`certificate` or `sasl`) and
`ANKKA_TOPIC_BROKER_<NAME>_SECRET_DIRECTORY`, where the project secret is mounted:
`ca.crt` with `tls.crt` and `tls.key` for a certificate, `ca.crt` with `username` and
`password` for SASL (SCRAM-SHA-512 by default, `mechanism` in the secret selects PLAIN or
SCRAM-SHA-256). `KafkaTls.clientProperties` (`R/KafkaTls.scala:83-87`) knows only
`security.protocol=SSL` from a directory today; it becomes `KafkaCredential.properties`, with
the SASL case adding `SASL_SSL`, the mechanism and the JAAS line. A declared broker's connection
has no topic prefix. `subscribeTopic` and the
publication path pick the connection by the source's or publication's `broker`; a name no
connection was built for is a start-time problem (R3). Consumer groups keep
`ConsumerGroups.name`. The operator mounts each declared broker's secret read-only at
`/var/run/secrets/ankka/brokers/<name>` on the platform container of every service in the
project, and sets the three variables; `ANKKA_TOPIC_BROKER_` joins `RuntimeOnlyPrefixes`
(`modules/core/.../PlatformVariables.scala:101-102`) so the process never sees them.

**Rationale**: `ANKKA_KAFKA_*` cannot be reused: any variable with that prefix makes the service
"supply its own broker" (`descriptors.scala:262`, `ServiceProjection.scala:155-156`) and is
shared with the process container. The operator renders a service before its components are
known, so every service of the project is given every declared broker; a project is already the
boundary project secrets are scoped to. The runtime's one subscriber per service becomes one per
broker, and `TopicSubscription` stays broker-agnostic.

**Alternatives**: a broker named in the descriptor (per service, and the whole service moves
to it, which is today's path and not ingestion); a broker name in the topic string (`legacy/
events`; ambiguous with a topic that contains `/`).

### R8. Parallel partitions are a partitioned source with one lane per partition

**Decision**: `TopicSubscription` (`R/MessageSubscriber.scala:26`) gains `parallel: Boolean`.
When set, `KafkaSubscriber.subscribe` (`R/Kafka.scala:241-311`) uses
`Consumer.committablePartitionedSource`, runs each partition's sub-stream through its own
`mapAsync(1)` and a handler of its own, and merges the committable offsets into one
`Committer.flow`. `ConsumerTopicHandler` and `ViewTopicHandler` (`R/TopicHandlers.scala`) are
created per partition rather than once per subscription, because each holds one component
instance it mutates per message (`:97-98, 103, 116`). A remote consumer's handler calls the
process concurrently, one in flight per partition; the conformance suite gains a parallel
consumer case so every SDK's server is proven to handle it. `InMemoryBroker` has one partition
and honours the flag by doing nothing. The per-partition suite is a `KafkaSuite` case over four
partitions.

**Rationale**: pekko-connectors-kafka's partitioned source gives per-partition ordering and
per-partition failure isolation for free; one component instance per partition is the smallest
change that removes the shared mutable instance.

**Alternatives**: `mapAsyncPartitioned` over one source (a failed message would still block the
committer for every partition behind it); a thread pool per message (loses per-partition
ordering).

### R9. Lag is polled from the broker and rides on the topic source status

**Decision**: `MessageSubscriber` gains `lag(subscription): Future[Option[Long]]`.
`KafkaSubscriber` answers with a raw consumer, as `earliestRetained` does (`R/Kafka.scala:318-361`):
`endOffsets` of the topic's partitions minus `committed` under the subscription's group, summed;
`InMemoryBroker` answers `logOf(topic).size - position`. `TopicSources` polls every subscription
every 30 seconds and records `lag` on `TopicSourceStatus` (`R/TopicSources.scala:17-27`), which
already feeds `ankka_topic_source_*` metrics (a third series, `ankka_topic_source_lag`) and
`/observability/service`'s `topicSources` (`R/ObservabilityDocuments.scala:64-73`).
`TopicSourceStatus` also gains `failing: Option[String]`, the reason of the change currently
being redelivered, set by the topic handlers on failure and cleared on success.
`InstanceTopologies` (`CP/deploy/InstanceTopologies.scala:50-110`) fetches
`/observability/service` beside the topology, and `ServiceStatus` gains `topicSources`, printed
by `services get`, shown by the console's service page, and present in the MCP `get_service`
answer because it returns the status.

**Rationale**: nothing computes lag today and the consumer control is hidden inside
`RestartSource`; a raw consumer under the group reads committed offsets without joining it.
`messaging.md` already says the topic sources belong in `services get` "over the observe port".

### R10. A consumer-only service has no database, and says so

**Decision**: the descriptor gains `"database": "none"` (today's choices are the platform's,
the default, or a supplied one through `ANKKA_DB_*`). The control plane projects it as
`provisionDatabase = false` and `database = "none"` on the resource; `Provisioning.decide`
(`O/Provisioning.scala:87-126`) returns `NotNeeded`, which exists for web hosting, so no
schema-init container, no credential and no certificate are rendered, and the status's database
phase is absent. The operator sets `ANKKA_DATABASE=none`; with it, the runtime refuses at start
(R4) an entity, a view, a workflow or a timed action ("this service declares no database"),
uses `SecretStore.unavailable` (`R/Ankka.scala:442`), and never constructs `Database()`
(`R/Ankka.scala:245, 305`, `R/ProjectionRuntime.scala:96`, `R/TimerRuntime.scala:44`,
`SC/ClientLogic.scala:114` become lazy). A `KafkaSuite` case starts a consumer-only service with
`ANKKA_DATABASE=none` and `ANKKA_DB_HOST` pointing at a closed port, and proves it reads its
topic.

**Rationale**: absent `ANKKA_DB_*` means `localhost:5432` (`reference.conf:179-215`), and
nothing today proves a consumer-only service never touches it; the r2dbc pool's laziness is
unverified. An explicit mode is honest about what the service may contain and lets the operator
render nothing for it.

**Alternatives**: infer from the components (unknown until the service runs); keep provisioning
a database nobody opens (the cost the spec removes).

### R11. The process container is sized by the descriptor

**Decision**: `ServiceSpec.resources` gains `process: {cpu, memory}` as Kubernetes quantities;
the control plane projects `processCpuMillis` and `processMemoryMiB` onto the resource (both
schema'd in `ankkaservice.yaml`, pinned by `CrdSchemaSuite`), defaulting to today's 100 and 128;
`Rendering.containersFor`'s process branch (`O/Rendering.scala:1290-1337`, the app container at
`1321-1326`) uses them with requests equal to limits, as the platform container does.

**Rationale**: the fixed numbers are one map, `AppQuantities` (`O/Rendering.scala:186-188`,
"until the descriptor can size it"); the rest of the path exists for the platform container.
Defaulting the new fields to 100 and 128 keeps `RenderingGoldenSuite` and
`RenderingUnchangedSuite` green for every existing descriptor.

### R12. The protocol grows to 1.14

**Decision**: in `discovery.proto`, `Source` (`:69-75`) gains `optional Contract contract = 4`,
`optional string broker = 5`, `optional bool parallel = 6`; `ConsumerDetail` (`:105-109`) gains
`optional Publication produces = 4` (`topic`, `contract`, `broker`), with `produces_to` kept;
`message Contract { string name = 1; string fingerprint = 2; }`. `protocol/README.md`'s version
section records 1.14. The sidecar's `Discovery.source` (`SC/Discovery.scala:416-444`) reads the
new fields and `ConsumerDetail.produces` over `produces_to`; a Spec from an earlier minor states
nothing new, which is a topic with no contract, the installation's broker and no parallelism, so
the sidecar accepts it. `WireProtocol.Version`, `Compatibility.version`, and the four SDKs'
constants move to 1.14; each SDK's `Consumer`/`View` declarations take `contract`, `broker` and
`parallel`, and `produces_to` takes a `Publication` or a plain topic.

**Rationale**: `protocol/README.md:38-44`: optional fields are a minor; the field-gating pattern
is `SocketsSince` (`SC/Discovery.scala:137-143`), but nothing here needs refusing an older SDK.

## The sink

### R13. The sink is a consumer in a module of its own, and an image built from it

**Decision**: a published module `ankka-graph-neo4j` (`modules/graph-neo4j`, depending on `sdk`
and the Neo4j Java driver 5.28.5, as ankka-flow's `Dependencies.scala:39`), holding
`Neo4jSink`: a `Consumer[GraphDelta]` over `ChangeSource.fromTopic(topic, deltaSerializer,
StartFrom.Earliest)` with `version` from its settings, whose handler applies one delta in one
transaction with the statements ankka-flow's `Neo4jMergeStage` runs (`UNWIND` over a list of
one: node merge, edge merge with placeholder endpoints, node and edge tombstones, all guarded by
`_version`), and whose `GraphRules` check (`core/graph/GraphRules.scala`) refuses a malformed
delta by failing the change, so it is redelivered and the status's `failing` names it (R9). A
delete marker is applied as a tombstone of the key. An sbt project `graphSink` (`graph-sink/`,
`publish / skip`, `DockerPlugin`, image `ankka-graph-sink`) registers one `Neo4jSink` from
`ANKKA_GRAPH_SINK_TOPIC`, `ANKKA_GRAPH_SINK_VERSION`, `NEO4J_URI`, `NEO4J_USERNAME`,
`NEO4J_PASSWORD`, `NEO4J_DATABASE` and `ANKKA_GRAPH_SINK_TRANSACTION_TIMEOUT`, with
`"database": "none"` in the descriptor the documentation gives. The `images` job publishes it and
checks it is public like the others (`release.yml:117-215`).

Carried from ankka-flow with their tests: the Cypher and the version rules
(`Neo4jMergeStage.scala`, `Neo4jMergeSuite.scala`, 416 assertions over a Neo4j container), the
delta reading where `core/graph` does not already cover it (`Deltas.scala`'s key and marker
rules), the secret redaction rule (no password in any message). Left behind: batching (one
transaction per delta here; `parallel` gives partitions at once), the stall warning (the
`failing` status replaces it), the stage protocol.

**Rationale**: the spec's answer; ankka-flow's sink is 350 lines of stage plus the Cypher, and
its correctness lives in `Neo4jMergeSuite`; `core/graph` already holds the delta model, keys and
rules, so the sink in ankka is smaller than in ankka-flow.

**Alternatives**: apply a batch per poll (needs batches in the consumer model, out of scope).

### R14. The graph fixtures become ankka's own

**Decision**: `protocol/fixtures/graph-deltas/keys.json` and `deltas.json` are written by a
`GraphFixturesSuite` in `core` from the builder in `sdk`, under `-Dankka.fixtures.regenerate=on`
(a forwarded switch, like `ankka.golden.update`), and refused when they differ; `SOURCE.md` says
they are ankka's; `GraphFixtures` (`core/.../graph/GraphFixtures.scala`) and every SDK keep
reading them; the sink's suite reads the same rows, which is what made them proof before.
`graph.md`'s "Where ankka's part ends" and `limitations.md:184` are rewritten (R18).

### R15. Neo4j in tests

**Decision**: `-Dankka.neo4j.image=neo4j:5.26-community` set and forwarded in `build.sbt` like
`ankka.sample.image`; `testcontainersNeo4j` in `graph-neo4j`'s test scope; the k3s proof of the
sink deployed into a project is a `BrokerClusterFeatures`-style file, `features/graph-deltas/
sink.feature`, run by `GherkinSuite` with Neo4j deployed from a manifest in the suite, under
`ankka.cluster.tests`.

## The control plane, the resources, the CLI and the console

### R16. A declaration's schema is written to the cluster first; the journal records its fingerprint

**Decision**: `PUT /projects/{id}/topics/{name}` takes `{"partitions", "compacted",
"contract": {"name", "schema": <JSON Schema document>}}`. The endpoint fingerprints the
document (R2), writes it to a ConfigMap `ankka-project-schemas` in the project's namespace under
the key `<fingerprint>` (a new `ProjectSchemaWriter` on the projector, server-side applied;
`configmaps` `get/create/patch` added to the control plane's grants), and only then records
`DeclareTopic(name, partitions, compacted, contract: Option[Contract(name, fingerprint)])` on
`ProjectEntity`. `DeclaredTopic` (`CP/domain/model.scala:304`) gains `compacted: Boolean = false`
and `contract: Option[Contract] = None`; `ProjectTopicDeclared` (`CP/domain/events.scala:217-222`)
gains the same with defaults, and `EventCompatibilitySuite` gains a pin for the event's wire form
(there is none today). `GET /projects/{id}/topics/{name}/schema` reads the ConfigMap by the
recorded fingerprint and answers the document; `ankka projects topics schema get <name>` prints
it. A schema is at most 64 KiB (`ProjectTopics.MaxSchemaBytes`, as `ProjectSecrets.MaxValueBytes`).
"Never fewer partitions" stays; `compacted` and the contract may change freely (a changed
contract is a new fingerprint, every side is checked at its next start).

**Rationale**: `events.scala:193-200` says the journal has "no field that could hold" a large
value, and the registry and secrets precedents write the cluster first and record names only;
`ProjectEntity.topics` is read on every `services get` (`CP/api/ServiceEndpoint.scala:88-93`)
and the whole `Project` is snapshotted every 100 events, so documents in the entity would ride on
every status read and cross Pekko's 256 KiB frame as the project grows.

**Alternatives**: the document in the entity (the costs above); in `AnkkaProject` (the operator
has no use for it and etcd bounds the object).

### R17. Compaction and brokers reach the operator through `AnkkaProject`

**Decision**: `ProjectTopicEntry` (`crd/.../AnkkaProject.scala:24`) gains `compacted: Boolean =
false`, `contractName: Option[String]` and `contractFingerprint: Option[String]`, flat so
`CrdSchemaSuite` (`operator/.../CrdSchemaSuite.scala:102-113`, which does not recurse) checks
them; `AnkkaProjectSpec` gains `brokers: List[ProjectBrokerEntry(name, bootstrap, shape,
secretName, declaredAt)]`, with a new `spec.brokers.items` assertion. `StrimziRendering.topic`
(`O/StrimziRendering.scala:68-82`) writes `config: Option[Map[String, String]]` on
`KafkaTopicSpec` (`O/strimzi/KafkaTopicResource.scala:14-16`), `Some(Map("cleanup.policy" ->
"compact"))` when compacted and `None` otherwise, so an existing topic's applied object does not
change (`NON_ABSENT` would still write `{}`); `Executor.observeTopics` (`O/Executor.scala:737-747`)
reads `spec.config` so a topic made uncompacted is patched when its declaration changes;
`TopicProvisioning.status` reports `compacted`. `ProjectReconciler.actions` adds the
`ankka-project` ConfigMap (R3) from the same spec. The control plane's `ProjectEntity` gains
`DeclaredBroker(bootstrap, shape, secretName, declaredAt)` with `ProjectBrokerDeclared`/`Removed`
events (added to `ProjectRows.scala:12-45`'s exhaustive match and to
`ProjectTopicsTrigger.scala:23-27`), routes `PUT/DELETE /projects/{id}/brokers/{name}` and
`GET /projects/{id}/brokers`, and `ankka projects brokers set <name> --bootstrap <addr> --shape
certificate|sasl --secret <project secret>`, `unset`, `list`. The endpoint refuses a declaration
whose secret lacks the shape's keys by reading the entries the entity recorded for the project
secret (`ProjectSecretRef.entries`, `CP/domain/model.scala:293-297`); the secret's name must be a
project secret, never a reserved form.

**Rationale**: `AnkkaProject` is the one thing the operator reads about a project; the trigger
and projection path exists; project secret entries are already recorded by name so the check
needs no cluster read.

### R18. Routes, fixtures, the console and the generated pages

**Decision**: every new route has a hand-written section in `docs/reference/control-plane-api.md`
and is exercised by the console's e2e suite (`console/e2e/parity.ts` fails otherwise), so the
console's project page gains contract, compaction and brokers, its service page gains topic
sources with lag and `failing`, and `console/package/src/testing/fake-control-plane.ts` serves
them; `ControlPlaneFixturesSuite` fixtures for the new wire types are regenerated with
`just docs-reference` alongside `CliReferenceSuite` and `ControlPlaneRoutesReferenceSuite`.
`ServiceStatus` gains `topicSources: Option[Vector[TopicSourceStatus]]` and `topicChecks`
(R5), printed by `Output.service` (`cli/.../Output.scala:174-206`) as `topic sources` lines and
shown by the MCP `get_service`, whose description string names them. The pages that state what
this feature changes (`build/topics.md:373-374, 427-428, 484-488`, `build/graph.md:735-767`,
`platform/broker.md:69, 76-77, 119-120`, `reference/service-descriptor.md:348-381`,
`deploy/scaling-and-rollouts.md:55-56`, `reference/limitations.md:37-40, 199-204`,
`platform/secrets.md:87-88`) are rewritten; `build/topics.md` gains sections on contracts,
declared brokers and parallel partitions; `build/graph.md` tells the story to the store with a new
`deploy/graph-sink.md` page (deploying the image, the descriptor, rebuilding at a higher version),
in `mkdocs.yml`'s nav and in `ankka-views-consumers` and `ankka-deploy`'s `pages:`; the three
language skills keep `build/graph.md`.

### R19. The build, the release and CI

**Decision**: `modules/graph-neo4j` joins the aggregate and the published set (the "ten modules"
become eleven in `release.yml:1` and `build-and-release.md:22-40`; `templateArtifacts` stays at
eight, a template has no sink); `graph-sink/` is a new top-level directory, so `.github/ci.yml`'s
`scala` filter and `.github/ci-coverage.py` learn it; the `images` job's `IMAGES` and sbt line
(`release.yml:141, 163`) add `ankka-graph-sink` and `graphSink/Docker/publish`, and the first
release fails at the public-pull check until the package is made public once, as the comment
there says; `-Dankka.neo4j.image` joins the forwarded switches (`build.sbt:140-170`).
`build-and-release.md`'s image list, already stale, is corrected with the sink added.
