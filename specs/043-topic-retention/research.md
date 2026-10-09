# Research: Topic Retention

Every decision below was taken against this worktree at `00cdbfbd` (main after specs 038–044;
feature 037, "Pipelines are services", is an ancestor). Line numbers are that commit's. `R` is
`modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime`, `O` is
`operator/src/main/scala/com/thinkmorestupidless/ankka/operator`, `CP` is
`controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane`, `API` is
`controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api`, `CRD` is
`crd/src/main/scala/com/thinkmorestupidless/ankka/crd`, `T` is
`modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit`.

## What the spec did not know

Three facts found in the tree change the shape of the work and are recorded first.

- **Compaction already exists as one boolean.** Feature 037 merged after the spec was drafted:
  `TopicDeclarationRequest(partitions, compacted, contract)` (`API/descriptors.scala:1431`),
  `DeclaredTopic.compacted` (`CP/domain/model.scala:308`), `ProjectTopicEntry.compacted`
  (`CRD/AnkkaProject.scala:31`), rendered as `cleanup.policy: compact` (`O/StrimziRendering.scala:28,92`),
  observed back (`O/Executor.scala:776`), a `--compacted` flag (`cli/.../Main.scala:416`), a console
  checkbox, `features/broker/compaction.feature` and `docs/build/graph.md:735`. The spec's "nothing can
  be compacted" is therefore "a topic is compacted or not, and nothing else is said". This feature
  generalises the boolean to a cleanup policy and keeps the boolean on every wire as a derived alias
  (R1), so nothing 037 shipped stops working. The spec's Context is corrected to say so.
- **The operator never reads the broker's shape.** Its Role covers `kafkatopics` and `kafkausers` only
  (`kustomization/components/broker/operator-role.yaml:13-16`); nothing reads `Kafka` or
  `KafkaNodePool`. Learning the node count is new (R9).
- **The runtime has no admin client**, no `acks` and no `min.insync.replicas` anywhere; the producer
  is built from `ProducerSettings(system, …)` with TLS or SASL properties only (`R/Kafka.scala:212-230`),
  and a keyless record is built at `R/Kafka.scala:246` (`key.orElse(metadata.subject).orNull`). The
  broker read the clarify session chose (a topic's configuration from the broker) is new code (R11).

## The declaration

### R1. Settings are flat optional fields on the request, typed values in the entity, and `compacted` stays as an alias

**Decision**: `TopicDeclarationRequest` gains eight optional fields beside `partitions`, `compacted`
and `contract`: `retention`, `retentionSize`, `cleanup`, `tombstoneWindow`, `minCompactionLag`,
`maxCompactionLag`, `copies`, `minInSync`, and one more, `removes` (R5). Every value is a string on the
wire (`"90d"`, `"50GiB"`, `"compact,delete"`, `"everything"`, `"none"`) or an integer (`copies`,
`minInSync`); absent means "the installation's default" on a first declaration and "unchanged" on a
redeclaration (the analysis session's answer: changing one setting never resets another, and
`partitions` may be left out of a redeclaration). `compacted: true` with no `cleanup` is read as
`cleanup: "compact"`; both given and disagreeing is a 400. `ProjectTopic` (the listing row) carries a
`settings: TopicSettingsView` object whose every field has a value and a `defaulted: Vector[String]`
naming the fields the installation supplied, `copiesHeld` (what the broker holds, from the status),
plus `compacted` derived from the cleanup policy so the console and CLI column of 037 still read. One
037 behaviour changes: a redeclaration without `compacted` no longer clears compaction; `compact` to
`delete` is said with `cleanup: "delete"` and is a removal (R5). The CLI keeps `--compacted` as an alias of
`--cleanup compact` and gains `--retention`, `--retention-size`, `--cleanup`, `--tombstone-window`,
`--min-compaction-lag`, `--max-compaction-lag`, `--copies`, `--min-in-sync`.

**Rationale**: jsoniter reads a JSON `null` on an `Option` as absent and applies the default
(CLAUDE.md), so "keep everything" and "no size" must be positive values, not `None`; a string with a
unit is also what a member types. Flat fields keep `ControlPlaneFixturesSuite`'s and the console's
Zod schemas simple, and the derived `compacted` costs nothing. The typed form,
`TopicSettings` in `controlplane-api` (R2), is what the entity holds and the control plane checks, so
the string form is parsed exactly once, by the same code in the CLI and the server
(`ProjectTopics.problems` already runs in both, `cli/.../Main.scala:430`, `CP/api/ProjectEndpoint.scala:252`).

**Alternatives**: milliseconds and bytes on the wire (unreadable in a listing, and `-1` for "everything"
is Kafka's convention leaking); a nested `settings` object on the request (a partial object is harder
to say "absent means default" about; the response has one because every field is filled there);
removing `compacted` (breaks the 037 console, CLI output and docs for no gain).

### R2. One parser and one set of units, in `controlplane-api`

**Decision**: `TopicSettings` and its value types live beside `ProjectTopics` in `API/descriptors.scala`:

- `RetentionTime`: `Bounded(FiniteDuration)` or `Everything`; wire `"<n><unit>"` with units `ms`, `s`,
  `m`, `h`, `d`, or `"everything"`.
- `RetentionSize`: `Bounded(bytes: Long)` or `NoLimit`; wire `"<n>(B|KiB|MiB|GiB|TiB)"` or `"none"`.
- `CleanupPolicy`: `Delete`, `Compact`, `CompactDelete`; wire `"delete"`, `"compact"`, `"compact,delete"`.
- `tombstoneWindow`, `minCompactionLag`: `FiniteDuration`; `maxCompactionLag`: `Bounded` or `NoLimit`
  (Kafka's default is `Long.MaxValue`, which is "none").
- `copies: Int`, `minInSync: Int`.

Each value type has an explicit string `JsonValueCodec` **in its companion object** (the fieldless-enum
trap in CLAUDE.md), and `TopicSettings.toKafka: Map[String, String]` is the rendering to Kafka's
keys (`retention.ms`, `retention.bytes`, `cleanup.policy`, `delete.retention.ms`,
`min.compaction.lag.ms`, `max.compaction.lag.ms`, `min.insync.replicas`; `-1` for everything and none,
`Long.MaxValue` for no maximum lag). The operator does not depend on `controlplane-api`, so the CRD
entry carries the Kafka-shaped numbers (R8) and the operator builds the config map from them with a
copy of the same seven-line mapping; one test on each side (`TopicSettingsSuite` in
`controlplane-api`, `StrimziModelsSuite` in the operator) renders the same fixture
(`protocol/fixtures/topics/settings.json`, written by the `controlplane-api` suite) to the same map.

**Rationale**: a duration with a unit is what every page of the docs already writes ("7 days",
"50 GiB"); Kafka's keys are an implementation detail the member never sees. Two renderings of a
seven-key map pinned to one fixture are cheaper than a module dependency the architecture forbids
(`crd` depends on nothing).

**Alternatives**: ISO-8601 durations (`P90D` reads worse and no page uses it); Kafka's keys on the
wire (the spec's "no topic setting anyone has to look up in Kafka").

### R3. The installation's defaults and bounds are one `TopicPolicy`, read from the control plane's configuration

**Decision**: `CP/tenancy/TopicPolicy.scala`, read like `OrganizationPolicy` (`CP/tenancy/OrganizationPolicy.scala:52-64`)
from `ankka.controlplane.topics.*` in `controlplane/src/main/resources/reference.conf`, each key
overridable by a variable:

| Setting | Variable | Shipped |
|---|---|---|
| default retention time | `ANKKA_TOPIC_DEFAULT_RETENTION` | `7d` |
| default retention size | `ANKKA_TOPIC_DEFAULT_RETENTION_SIZE` | `none` |
| default cleanup policy | `ANKKA_TOPIC_DEFAULT_CLEANUP` | `delete` |
| default tombstone window | `ANKKA_TOPIC_DEFAULT_TOMBSTONE_WINDOW` | `1d` (Kafka's) |
| default minimum compaction lag | `ANKKA_TOPIC_DEFAULT_MIN_COMPACTION_LAG` | `0s` (Kafka's) |
| default maximum compaction lag | `ANKKA_TOPIC_DEFAULT_MAX_COMPACTION_LAG` | `none` (Kafka's) |
| default copies | `ANKKA_TOPIC_DEFAULT_COPIES` | `1` |
| default minimum in-sync copies | `ANKKA_TOPIC_DEFAULT_MIN_IN_SYNC` | `1` |
| longest retention time | `ANKKA_TOPIC_LONGEST_RETENTION` | `everything` |
| largest retention size per partition | `ANKKA_TOPIC_LARGEST_RETENTION_SIZE` | `none` |
| most copies | `ANKKA_TOPIC_MOST_COPIES` | `3` |
| warning threshold | `ANKKA_TOPIC_WARNING_THRESHOLD` | `30d` |

`TopicPolicy.from(config)` fails at start, naming the key and the variable, on a value the parser of R2
refuses or on a default outside its bound. It is threaded through `ControlPlane.endpoints` into
`ProjectEndpoint` and `ServiceEndpoint` (the warning, R14) and into the sweep (R7). The one-node
values are written literally on the control plane's Deployment
(`kustomization/components/controlplane/deployment.yaml:88-144`), and the three-node component (R10)
patches `ANKKA_TOPIC_DEFAULT_COPIES=3` and `ANKKA_TOPIC_DEFAULT_MIN_IN_SYNC=2` onto it. The variable
names are constants on `TopicPolicy`, in `controlplane`, which `PlatformDeclarationSuite` does not scan
(`controlplane/src/test/.../PlatformDeclarationSuite.scala:82-90` scans `operator`, `sidecar` and
`controlplane-api` for lists of `ANKKA_` literals); `PlatformVariables` lists service-container
variables only and gains nothing. `docs/reference/configuration.md`'s hand-written prose names each
(the generated block covers the service's variables, not the control plane's;
`docs/platform/identity.md:78-84` is the precedent).

**Rationale**: the clarify session's answer (platform variables on the control plane, set by the
overlay); `OrganizationPolicy` is the pattern and the test shape. Refusing at start on a default
outside a bound is the cheapest proof the twelve values agree.

**Alternatives**: a settings record in the control plane's database (a new CLI surface and a second
audit trail); a ConfigMap the control plane reads (a Kubernetes read it does not have).

### R4. Filling, checking and the diff are pure functions in `controlplane-api`, and the entity holds the result

**Decision**: `ProjectTopics.fill(request, policy.defaults): (TopicSettings, defaulted: Set[Setting])`
for a first declaration, `ProjectTopics.merge(request, current: DeclaredTopic)` for a redeclaration
(absent fields from the current declaration, their marks kept), and `ProjectTopics.problems(name, request, policy.bounds)` (an overload beside today's two,
`API/descriptors.scala:1545,1556`) run in `ProjectEndpoint` before anything is written; the bounds
problems read "retention time 2 years is longer than the installation's longest, 1 year", "copies 5 is
more than the installation's most, 3", "minimum in-sync copies must be between 1 and the copies".
`ProjectTopics.changes(before, after): Vector[SettingChange]` lists each differing setting with its old
and new value in wire form, and `ProjectTopics.removal(changes): Option[String]` says what a change
removes: a shorter retention time, a smaller size, or a cleanup policy that loses `compact`.
`DeclaredTopic` gains `settings: Option[TopicSettings]` (`None` for a topic declared before this
feature, until the sweep fills it) and `defaulted: Set[Setting]`.

The events: `ProjectTopicDeclared` gains `settings: Option[TopicSettings] = None`, `defaulted:
Set[String] = Set.empty` and `changes: Vector[SettingChange] = Vector.empty` (empty on a first
declaration); a new `ProjectTopicSettingsFilled(name, settings, defaulted, at)` is the sweep's record
(R7). `EventCompatibilitySuite` pins the bare and full wire shapes as it does for 037's fields
(`controlplane/src/test/.../EventCompatibilitySuite.scala:211-245`). `ProjectEntity.declareTopic`
(`CP/application/ProjectEntity.scala:150-178`) refuses a change to `copies` or `minInSync` with
`Conflict` naming the rule and applies nothing else, and records nothing when `changes` is empty and
partitions and contract are unchanged.

**Rationale**: one parser, one filler, one differ, shared by the CLI and the server. The entity
records the full settings on every declaration, so a fold needs no diff to reconstruct state, and the
diff rides on the same event so the history (R6) needs no second one. A pre-feature topic is
`settings = None`, not a guessed value: the sweep fills it and says so.

**Alternatives**: a separate `ProjectTopicChanged` event (two folds for one state); a diff computed in
the listing from consecutive events (a replay for every `history` call).

### R5. A destructive change needs an owner and an acknowledgement that names the removal

**Decision**: in `ProjectEndpoint`'s `PUT`, after `authz.project(principal, projectId, write = true)`
(`CP/api/ProjectEndpoint.scala:248`) and the bounds check, the endpoint reads the topic's current
settings (the entity's `topics` query, as the listing does at `:320`) and computes
`ProjectTopics.removal(changes)`. With a removal: the actor must hold `Role.Owner` in the organization
(`access.role`, `CP/auth/Authorization.scala:12`; a platform administrator passes as today) or the
answer is 403 `owner role required: this declaration removes messages older than 30 days`; and the
request's `removes` must equal the removal's text or the answer is 400 `this declaration removes
messages older than 30 days; a declaration that removes messages says so with "removes"`. A
`removes` on a declaration that removes nothing is a 400 too. The CLI sends the declaration without
`removes`, and on that 400 prints the text, asks `Remove them? [y/N]` on the terminal (refusing
without a terminal unless `--removes "<text>"` was given, the scripted form), and resends with the
text. The console does the same with a dialog. The refusal texts are the removal's words:
`messages older than 30 days`, `messages beyond 10 GiB on a partition`, `every message but the last
under each key` (compact → delete).

**Rationale**: the clarify session's answer. Restating the removal, rather than a boolean, means a
client that computed the change against a stale listing acknowledges the wrong thing and is refused;
and the protocol is stateless: refuse, show, resend. The owner check is the endpoint's, never the
entity's (cross-entity checks live in endpoints). A deploy token is a member, so it can never lower
retention (control-plane.md), which is right for a credential a machine holds.

**Alternatives**: `confirm: true` (a stale client confirms whatever is there now); a two-call protocol
with a token (state to keep and expire); client-side only (the clarify session rejected it).

### R6. The project gains a history, as the service has one

**Decision**: `Project.history: Vector[ProjectHistoryEntry]`, newest first, capped at `Project.HistoryLimit`
(the service's model, `CP/domain/model.scala:461-509`), with entries `topic-declared`, `topic-changed`
(with `changes`), `topic-filled` (the sweep's), `topic-removed`; a query `history` on `ProjectEntity`;
`GET /projects/{id}/history` (member) answering `Vector[ProjectHistoryEntry(kind, topic, actor:
Option[HistoryActor], at, changes: Vector[SettingChange])]`; `ankka projects history -p money`; the
console's project page gains a "History" section as the service page has
(`console/package/src/routes/service-history.tsx`); the MCP server gains `project_history`.
`ControlPlaneRoutesReferenceSuite` and `CliReferenceSuite` regenerate their pages.

**Rationale**: FR-010 and US4 ask who changed what from what, and the journal is not a thing a member
reads. The service history is the shape and the cap; broker declarations (037) could join it later
and the entry's `kind` leaves room.

**Alternatives**: showing the last change on the topic row only (US4 asks for every change).

### R7. The sweep is a control plane extension that runs once per start

**Decision**: `CP/application/TopicSettingsSweep.scala`, a `RuntimeExtension` registered by
`ControlPlane.builder`, which after the service is ready lists every organization's projects
(the organizations listing view, then each organization's `ProjectRows`) and sends each `ProjectEntity`
a `FillTopicSettings(defaults)` command. The entity
persists one `ProjectTopicSettingsFilled` per topic whose `settings` is `None`, with the installation's
defaults for retention time and size, cleanup (`compact` when the 037 boolean said so, else the
default), tombstone window and the lags, every field marked defaulted, and **no copies** (`copies`
and `minInSync` stay unstated, R8); a project with nothing to fill records nothing, so a second start
is a no-op. The history entry is `topic-filled` with no actor.

**Rationale**: the clarify session's answer; the operator cannot reach the control plane. A command
per project through the ordinary client keeps the fold the only writer. Running on every start costs
one query per project and makes the upgrade need no step.

**Alternatives**: filling lazily on the next declaration (SC-001 would hold only for topics touched
since); a one-off migration script (a step an installation can forget).

## The resource and the operator

### R8. The CRD entry carries the settings as flat typed numbers; status carries what the broker holds

**Decision**: `ProjectTopicEntry` (`CRD/AnkkaProject.scala:25-38`) gains `retentionMs: Option[Long]`,
`retentionBytes: Option[Long]`, `cleanupPolicy: Option[String]`, `deleteRetentionMs: Option[Long]`,
`minCompactionLagMs: Option[Long]`, `maxCompactionLagMs: Option[Long]`, `replicas: Option[Int]`,
`minInsyncReplicas: Option[Int]`, each with `@JsonDeserialize(contentAs = …)` (the erasure trap in
kubernetes.md), declared in `kustomization/components/crd/ankkaproject.yaml` so `CrdSchemaSuite` passes;
`compacted` stays and is written as "cleanup contains compact" so the `ankka-project` ConfigMap and
`ProjectDeclarations` (`R/ProjectDeclarations.scala:32-37`) are unchanged. `ProjectTopicStatus` gains
`replicas: Option[Int]` (what the `KafkaTopic` resource says) and `config: Option[Map[String, String]]`
(its `spec.config` as applied), so the listing (R13) can say what the broker holds; `AnkkaProjectStatus`
gains `brokerNodes: Option[Int]` (R9). `ProjectProjection` (`CP/deploy/ProjectProjection.scala:13-39`)
maps `TopicSettings.toKafka` onto the numbers; a topic with `settings = None` is projected as today
(every new field absent), so a pre-sweep resource is byte-for-byte what 037 wrote.

**Rationale**: `CrdSchemaSuite` checks one level (`operator/src/test/.../CrdSchemaSuite.scala:103-118`),
which is why 037 flattened the contract; typed numbers are what `KafkaTopicSpec.config` needs and
what the status can compare. Keeping `compacted` keeps the runtime's declarations file stable.

**Alternatives**: a `config` map on the entry (an untyped bag the schema cannot check); dropping
`compacted` (a changed file every service reads at start).

### R9. The operator reads the broker's node count from its `KafkaNodePool`s

**Decision**: a fabric8 model `O/strimzi/KafkaNodePoolResource.scala` (`kafka.strimzi.io/v1`,
`kafkanodepools`, `spec.replicas`, `spec.roles`), `get` and `list` on `kafkanodepools` added to
`kustomization/components/broker/operator-role.yaml`, and `Executor.brokerNodes(namespace, cluster):
Option[Int]`, the sum of `spec.replicas` over the pools labelled `strimzi.io/cluster=<cluster>`
whose roles include `broker`, `None` when the type is absent (as `observeTopics` treats a missing
`KafkaTopic` type). `ProjectReconciler.reconcile` reads it once per pass beside `observeTopics` and
hands it to `TopicProvisioning.decide(entry, state, nodes)` and `topicsToRender`: a declared `replicas`
above the count is `Failed("topic 'money.transactions' asks for 5 copies and the broker has 3 broker
nodes")` and is not rendered, as a shrink is today (`O/TopicProvisioning.scala:53-81,103-111`). The
RBAC pin in `BrokerClusterFeatures.scala:1366-1398` gains the two verbs; `OperatorClusterSuite`'s
withheld-verb proof is unchanged. The count is written to `AnkkaProjectStatus.brokerNodes`, which the
listing shows once and `copies.feature`'s first scenario reads.

**Rationale**: the clarify session's answer: only the operator knows the broker; the node pool's
`spec.replicas` is the installation's statement of its shape and needs no broker connection. `get` and
`list` on a pool is a read of the shape, not of a credential, so the "no `get` on Secrets" rule is
untouched.

**Alternatives**: `Kafka.status.kafkaNodePools` (a status that lags a change); counting broker pods
(pods come and go; the shape is the pool); the control plane knowing (it never sees the broker).

### R10. The `KafkaTopic` states every setting, replicas included, and the three-node broker is a component

**Decision**: `KafkaTopicSpec(partitions, replicas: Option[Int], config)` (`O/strimzi/KafkaTopicResource.scala:9-24`);
`StrimziRendering.topic` renders `replicas = entry.replicas` and `config = Some(toKafka(entry))` whenever the
entry carries settings, and exactly what it renders today (no replicas, `None` or the compaction map)
when it does not, so `ProjectRenderingSuite:41-54,100-114` keeps its pins for the pre-sweep shape and
gains the full one. `observeTopics` reads `spec.replicas` and `spec.config` back; `decide` answers
`Ready` only when partitions, replicas (where declared) and the config map all equal the declaration,
`Waiting` otherwise, so the status is right while Strimzi applies a change, and `Failed` on a permanent
Strimzi reason as today (a replication change the control plane did not catch, since Strimzi without
Cruise Control reports it `NotSupported`). The comment that said replicas are left out is replaced by
one saying why they are stated and fixed.

The three-node broker is `kustomization/components/broker-three-nodes/`: a Component with a patch of
`KafkaNodePool/dual` to `replicas: 3` and of `Kafka/ankka`'s config to `default.replication.factor: 3`,
`min.insync.replicas: 2`, `offsets.topic.replication.factor: 3`, `transaction.state.log.replication.factor:
3`, `transaction.state.log.min.isr: 2`, and of the control plane's Deployment to
`ANKKA_TOPIC_DEFAULT_COPIES=3`, `ANKKA_TOPIC_DEFAULT_MIN_IN_SYNC=2`. It is listed by no overlay;
`overlays/cloud/kustomization.yaml` names it in a comment beside `broker-size.yaml`, whose own comment
and `docs/platform/broker.md:128-133` say "list the component for a new installation; it is not a
conversion". `kustomization/tests/broker-three-nodes/` renders the local overlay plus the component,
and `BrokerShapeSuite` (beside `RemoteOverlaySuite`, skipping without `kubectl`) asserts every
setting that counts copies reads 3 or 2, that the control plane carries the two variables, and that the
rendered one-node overlay still says 1 everywhere: the shape a patch must produce rather than the
presence of a string (kubernetes.md).

**Rationale**: FR-005 and FR-014. A Component is how every optional part of an installation is
listed, and a kustomize test directory is how an unlisted one is rendered in CI
(`kustomization/tests/otel-collector`).

**Alternatives**: `SET` placeholders in `broker-size.yaml` for the replication keys (a person remembers
five numbers and a control plane variable; the comment that asked for that is what this feature
retires); a `Kafka` patch in the cloud overlay (every cloud installation would be three nodes).

## The runtime

### R11. The runtime reads a topic's configuration from the broker through one admin client, cached per topic

**Decision**: `R/TopicConfigs.scala`: `TopicConfig(cleanup: Set[String], minInSync: Int)`, read with
`Admin.describeConfigs(ConfigResource(TOPIC, qualified))` over the connection's properties
(`KafkaConnection.properties`, `R/Kafka.scala:91`), held per qualified topic with the time read, and
refreshed when older than `ConfigInterval` (60 s) by a read started off the publishing thread, so a
publish never waits past the first; a read that fails keeps the last value and logs once. `KafkaPublisher`
owns one (`R/Kafka.scala:203-296`), and `publish` (`:239-270`), **before** building the record at `:246`,
fails with `KeylessPublication(topic)` (an `IllegalStateException` in `R`, message `topic
'deltas' is compacted and a message published to it must carry a key or a subject`) when the key
would be `null` and `cleanup` contains `compact`. The failure propagates as any refused publish does:
a consumer's change is redelivered (`R/TopicHandlers.scala:86-120`; the projection's backoff for an
entity source), the error names the topic. `InMemoryBroker` gains `compact(topic)` and applies the
same rule in `publish` (`R/MessageSubscriber.scala:186-199`), so the offline suites prove it and the
Kafka suite proves it against a broker.

**Rationale**: the clarify session's answer (the broker's configuration, refreshed on an interval). The
publisher is where every path meets, single `Produce` included (`R/ProjectionSupport.scala:268-278`,
`R/remote/RemoteProjection.scala:421`); a check in `publishAll` would miss it. kafka-clients is
already on the classpath through pekko-connectors-kafka; `Admin` adds no dependency.

**Alternatives**: the declarations file (`compacted` is there, but read once at start; a topic
compacted later would not be known until a restart, which FR-006 forbids); `describeConfigs` on every
publish (a round trip per message).

### R12. The gap is read where `earliestRetained` already reads, and reported on a slower poll than lag

**Decision**: `MessageSubscriber.earliestRetained` keeps its name and gains its answer:
`Future[Map[Int, Retained]]` with `Retained(beginning: Long, end: Long, earliestAt: Option[Instant])`
(`R/MessageSubscriber.scala:73`; `R/Kafka.scala:462-505` already computes `beginning` and discards it).
`TopicSourceStatus` (`R/TopicSources.scala:17-34`) gains `gap: Option[RetentionGap]`:
`RetentionGap(partitions: Vector[PartitionGap(partition, beginning, earliestAt: Option[Instant])],
compacted: Boolean, gone: Boolean, readAt: Instant)`, where `gone` is `beginning > 0` on any partition
or `earliestAt` later than the view's `since`, and `compacted` comes from `TopicConfigs` (R11),
in which case `gone` is `false` and the report says compacted. The view's `since` is the instant
its version was built: `ankka_view_versions.built_at`, which `rebuild` already writes (found when
implementing; an earlier draft added a `recorded_at` column for the same fact) (`ViewVersions.createTable`,
`R/ViewVersions.scala:40`, with `ALTER TABLE … ADD COLUMN IF NOT EXISTS` on `ensure`; the table is the
runtime's, not the DDL directory's) and `rebuild` writes it; a consumer has no version and `gone` is
the beginning rule alone. `ProjectionRuntime` reports the gap when a topic source subscribes
(`subscribeTopic`, `R/ProjectionRuntime.scala:391-444`), on a rebuild (`retainedThenRebuild`, `:558-596`,
which already waits for the answer; the log line stays and gains the beginning) and every
`GapInterval` (5 minutes; `LagInterval` is 30 s, and reading a first record per partition is dearer
than `endOffsets`), through `TopicSources.update`. `InMemoryBroker` gains `drop(topic, n)`, which
removes the oldest `n` entries and raises the beginning, so `ViewVersionSuite` proves the gap offline;
`KafkaSuite` proves it against a topic created with `retention.ms` and `segment.ms` small enough for
the broker to drop a segment (`T/KafkaSuite.scala:567-588` creates topics with an `Admin`).

**Rationale**: FR-016, the clarify session's wording of the gap, and 024's R9 (a view is not emptied
until the broker answers). The one call that reads the beginning is the one that reports it. `ProjectionRuntime` owns one
`TopicConfigs` per `KafkaConnection` and hands it to the publisher and the subscriber alike.

**Alternatives**: a new `reach` method (two consumers for one answer); reporting on the lag poll (a
record read every 30 s per topic per instance); storing the view's start in the status only (lost on
restart).

### R13. The gap rides every surface the lag rides, and is merged per partition, never summed

**Decision**: `ObservabilityDocuments` (`R/ObservabilityDocuments.scala:65-77`) and `TopologyJson`
(`R/TopologyJson.scala:127-135`) write `gap` on each topic source (one function from now on, in
`TopicSources`, replacing the duplicate); `Metrics.render` (`R/ObservabilityRoute.scala:47-139`) adds
`ankka_topic_source_beginning{kind,component,topic,partition}`,
`ankka_topic_source_earliest_retained_seconds{…,partition}` and `ankka_topic_source_gone{kind,component,topic}`.
`TopicSourceReport` (`API/descriptors.scala:1280-1293`) gains `gap: Option[RetentionGapReport]`;
`ServiceEndpoint.topicSourcesOf` (`CP/api/ServiceEndpoint.scala:386-399`) merges by partition: each
partition's row from the instance that reported it, `gone` any, `compacted` all, which is what "merged
across instances by partition" means and what the lag's sum is not. The project listing's topic row
says what the broker holds from the status's `replicas` (`copies: 1 (single copy)`, or `copies: 1,
below the installation's default of 3`). `ankka services get` prints under the source line `retained:
p0 from 1240 (2026-09-01T10:00Z), p1 from 0 (2026-06-02T…); earlier messages gone`, or `retained:
everything`, or `compacted`; the web console's topic-sources table
(`console/package/src/routes/service.tsx:168-205`) gains a column and a per-partition detail. The
local console's service document carries it (the local console page renders no topic sources today
and this feature does not add the table; `topicSources` in its JSON is the local console's surface the
spec names).

**Rationale**: FR-017; the same list of surfaces 037 used for lag, minus a sum that would be wrong.
The lag's over-count across instances (every instance reports every partition) is noted for a later
fix and not widened here.

### R14. The warning is computed by the service endpoint from the current declaration, on every read

**Decision**: `ServiceStatus` gains `warnings: Option[Vector[ServiceWarning]]` with
`ServiceWarning(kind: String, component: String, topic: String, message: String)`; in
`ServiceEndpoint.withUndeclaredTopics` (`CP/api/ServiceEndpoint.scala:74-99`), which already holds the
project's declared topics and the instances' topic sources, a `kind = "retention"` warning is added for
every view topic source whose declared topic has a bounded retention time below `TopicPolicy.warningThreshold`
and a cleanup policy without `compact`: `view 'entries' reads topic 'transactions', which keeps 7 days;
the installation warns below 30 days`. Nothing is stored: the next read of the status sees the next
declaration, which is what "follows the current declaration" needs. The CLI prints a `warnings` block
in `services get`, the console a notice on the service page, and the MCP tool's description names it.

**Rationale**: FR-020 and the clarify session's answer. The endpoint is the one place both halves are
already in hand; computing on read is recomputation on every change for free. The warning appears once
an instance reports its topic sources (`readyInstances >= 1`, as the sources do), which is SC-007's
"within one report interval of deploying".

**Alternatives**: a stored warning on the service entity (a recompute on every topic change across
every service of the project).

### R15. The `acks` check is a start-time refusal over the producer's effective configuration

**Decision**: in `ProjectionRuntime.start` beside `rejectUndeclared` (`R/ProjectionRuntime.scala:167`):
read the producer's effective `acks` from the settings the publisher is built with
(`ProducerSettings.properties`, which carries `pekko.kafka.producer.kafka-clients.acks` from a
service's own configuration; the environment gives the producer TLS and SASL only, so the spec's
"connection's properties" is in practice a service's `application.conf`), and when it is `0` or `1`
and any topic the service publishes to (`DeclaredConnections.publicationOf`, `R/DeclaredConnections.scala:67`)
has `minInSync > 1` by `TopicConfigs`, refuse through `StartRefusal` with `the producer's acks is 1
and topic 'transactions' needs 2 in-sync copies; set acks=all`, which reaches `services get` as the
detail and keeps the pod from readiness (the termination log, kubernetes.md). A topic the broker does
not know yet is not checked (it does not exist; the publish waits as today).

**Rationale**: FR-022. A start refusal is the shape every other "must not become ready" has
(`rejectUnsupported`, the no-database case), it is read in `services get`, and the `readiness` flag of
an extension is registered before `start` (`R/Ankka.scala:226`), so a refusal at start is simpler than
a flag flipped later.

**Alternatives**: forcing `acks=all` on the producer regardless (hides a service's own configuration
rather than refusing it; the spec chose refusal).

## Tests, documentation and the rest

### R16. Where each feature runs

| Feature | Suite |
|---|---|
| `features/broker/retention.feature` | `BrokerRetentionFeatures` (k3s, a `BrokerClusterFeatures` subclass) for the broker scenarios, and `TopicRetentionOfflineFeature` (a `GherkinSuite` over the same file against the test kit's control plane, the k3s scenarios `ranElsewhere`, and the reverse in the k3s suite) for the refusal and keep-everything scenarios; the k3s suite reads the `KafkaTopic` config with the node's `kubectl` and the broker's `kafka-configs.sh --describe` through `BrokerProbe`; the "keeps a message until" scenario asserts the configuration, not the passing of 90 days, and the step says so; the sweep scenario restarts the control plane kit over a journal holding a 037-shaped declaration |
| `features/broker/cleanup-policy.feature` | `BrokerCleanupFeatures` (k3s) for the configuration scenarios; the keyless outline and the lag scenario in `KafkaSuite` against a compacted topic (`segment.ms` small, `min.cleanable.dirty.ratio` 0, so the log cleaner runs within the test) |
| `features/broker/copies.feature` | `BrokerCopiesFeatures` (k3s) with `BrokerStack.install(k3s, nodes = 3)`, `TopicCopiesOfflineFeature` for the fixed-copies outline, and the single-copy scenario run in `BrokerRetentionFeatures` (one node) and `ranElsewhere` here: the same `kafka.yaml` with `replicas: 3`, the five replication keys and a smaller heap, ephemeral storage as today; a broker node is stopped by scaling the cluster operator to zero (`stopStrimzi`, `BrokerClusterFeatures.scala:585-644`) then deleting the pod, so the `StrimziPodSet` does not recreate it until `startStrimzi`; the operator's refusal scenario and the RBAC pin run here |
| `features/broker/changing.feature` | `BrokerChangingFeatures` (k3s) for the in-place change, the compaction change and the no-restart assertions; `TopicChangingOfflineFeature` for the owner, acknowledgement, member and records-nothing scenarios against the test kit's control plane, where they are fast |
| `features/topics/gap.feature` | `ViewVersionSuite` (in memory, `drop`) and `KafkaSuite` (a real dropped segment) for the gap; `TopicSourcesReportSuite` and a `ServiceWarningsSuite` for the merge and the warning; the console's Playwright suite for "shown wherever" |

`.github/cluster-suites.py` picks up every new concrete class unlisted. The new k3s suites run nightly
and on demand (`gh workflow run cluster -f suite=BrokerCopiesFeatures`), never on a pull request.

### R17. Documentation

Pages: `docs/build/topics.md` (declaring: every setting, its default and bound, changing one, what a
shorter retention removes and the owner's acknowledgement, copies fixed; rebuilding: the gap in the
status, the warning); `docs/platform/broker.md` (the settings on the `KafkaTopic`, the node count, the
three-node component and its cost, not a conversion, the shipped seven days as a laptop's and the
first-day instruction); `docs/platform/install-cloud.md` (the component in the table at `:48`);
`docs/reference/configuration.md` (the twelve control plane variables, hand-written beside the
identity ones); `docs/reference/control-plane-api.md` (the `PUT` body, the `removes` rule, the
history route; the generated table from `ControlPlaneRoutesReferenceSuite`); `docs/reference/cli.md`
(generated); `docs/build/graph.md:735-738` and `docs/deploy/graph-sink.md:29` (`--cleanup compact`,
with `--compacted` named as the short form); `docs/reference/limitations.md:202-207` rewritten as FR-019
says, plus "copies cannot change" and "a single-node broker is not grown in place". Every page already
sits in `mkdocs.yml`'s nav and a skill's `pages:` list. `features/graph-deltas/store.feature:20`, which
still says a pipeline declares its topic, is corrected to the project-declared model.

### R18. What stays out, and why

- **040's grantee listing (FR-013)** is not implemented: no grant exists in the tree. The listing row's
  `settings` object is what a grantee listing will show; the dependency note in the spec stands.
- **A pre-feature topic's copies** stay unstated (R7, R8): the listing shows "copies: as the broker
  holds (1)" from the observed replicas, and a declaration that states copies for it is refused as a
  change of copies.
- **The lag over-count** (every instance reports every partition and the control plane sums) is noted
  in messaging.md as a trap and left.
- **The local console's page** renders no topic sources; the gap is in its document (R13).
