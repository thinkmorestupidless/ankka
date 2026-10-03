# Research: Replayable Topic Sources

Decisions for [plan.md](plan.md), each with what in the repository it rests on. File references
are to this branch's base (`bfa402f5`). "Verify first" marks a claim read from code or a
dependency and not yet run; the task that touches it starts with a test that would show it false.

## R1. A deployed workload reads its project and name from its certificate

**Decision**: `ServiceIdentity(project: Option[String], service: Option[String])` is resolved once
at startup and carried on `AnkkaService`. In `kubernetes` mode both come from
`RotatingTls.identity`, the `ankka://<project>/<service>` URI in the workload's own certificate. In
`local` mode there is no project and the service is `ankka.service.name`
(`ANKKA_SERVICE_NAME`). The operator renders nothing new.

**Rationale**: The spec assumed the operator already told a workload its project. It does not:
`clusterEnv` (`operator/.../Rendering.scala:824-842`) carries the service name as
`ANKKA_CLUSTER_SERVICE` and no project, and that variable is read only by the Kubernetes overlay
for bootstrap's service name. The certificate already says both
(`ZeroTrust.identityUri`, `ZeroTrust.scala:85`; read back at `RotatingTls.scala:59`), and it is the
one statement of identity the workload cannot have written for itself. A variable can be shadowed:
`ClusterConfig.load` puts a service's own `application.conf` above the overlay, so
`ankka.service.project = "someone-else"` would name another project's groups, and until
027-managed-broker grants by prefix nothing would refuse it. A new variable on the pod template
would also roll every pod of every service the moment the operator was upgraded, for a value the
pod already holds.

**Alternatives considered**: `ANKKA_PROJECT` and `ANKKA_SERVICE_NAME` rendered by the operator and
reserved in `ServiceSpec.PlatformEnvVars` — simple, and rejected for the two reasons above. The
pod's namespace less `ANKKA_NAMESPACE_PREFIX` — derivable, but a second derivation of something
the certificate states outright.

**Verify first**: that `RotatingTls.identity` is populated in Kubernetes mode before
`ProjectionRuntime.start` runs, and that a k3s-deployed pod's certificate carries the URI in the
form `identityOf` parses. `ServiceIdentitySuite` reads a certificate built as the operator's
`Certificate` resource asks for one.

## R2. One function names every group, and the version sits beside the kind

**Decision**: `ConsumerGroups.name` as [contracts/group-names.md](contracts/group-names.md) gives
it. The four `processName` uses for topic sources in `ProjectionRuntime.scala` (`:225`, `:265`,
`:306`, `:347`) call it; the entity branches keep `processName`.

**Rationale**: A component id may contain `.`, `-` and `_` (`core/.../ids.scala:15-33`), and it is
the only one of the three names that may; project ids and service names are DNS labels
(`descriptors.scala:13-43`, `:55-96`). So the id must be the last thing in the name, and a version
cannot be attached to it without another id being able to spell the result. The kind is a closed
vocabulary in a fixed segment, which no id can reach.

**Alternatives considered**: a suffix with a character outside the id alphabet
(`…summary@v2`) — one shape for all three forms, but outside the alphabet Kafka holds topic names
to, and 027 will hand these names to whatever its broker's operator uses for access rules. A
version on every name including version 1 — changes the name the spec's acceptance scenario
states. Refusing a version on an unnamed local service, to avoid the third form's own version
shape — a rule and an error message in exchange for a prettier string nobody chose.

## R3. The start position is resolved and committed at first assignment

**Decision**: `KafkaSubscriber` attaches a `PartitionAssignmentHandler`. For each assigned
partition with no committed offset it resolves the declared start to an offset, seeks, and commits
that offset synchronously, before the stream reads. `auto.offset.reset` stays `earliest`.

**Rationale**: `auto.offset.reset` covers `earliest` and `latest` and nothing else, and it applies
whenever a partition has no commit, not only the first time. A group started at `latest` that has
read nothing from a partition has no commit for it, so a restart resets it to the new end and
skips what was published while the service was down. Committing the resolved offset at assignment
makes "applied when the group has no committed offset" true once and for all per partition, and
makes the three positions one code path. The connector exposes exactly what this needs:
`RestrictedConsumer` has `committed`, `beginningOffsets`, `endOffsets`, `offsetsForTimes`, `seek`
and `commitSync` (read from `pekko-connectors-kafka_3-1.2.0.jar` with `javap`).

**Alternatives considered**: `auto.offset.reset` for the two named positions and a handler only
for a time — two paths, and the `latest` restart gap stays. `auto.offset.reset = none` so that a
partition the handler missed fails loudly — it also fails for ever on a group whose commit has
aged out of the topic, which today recovers by reading what remains.

**Verify first**: that `commitSync` inside `onAssign` is accepted by the broker during a rebalance
and visible to the next assignment. `KafkaSuite` starts a `latest` view, publishes nothing, stops
it, publishes ten, restarts it and expects ten rows; without the commit it holds none.

## R4. `MessageSubscriber.subscribe` changes its signature rather than gaining an overload

**Decision**: As [contracts/subscriber.md](contracts/subscriber.md): one `subscribe` taking a
`TopicSubscription`, returning a `Subscribed`, and `earliestRetained`.

**Rationale**: The trait is public and has two implementations here. A default method that
delegated to the old one would drop the start position for any implementation that had not been
updated, and it would go on starting where it always had. `MessagePublisher`'s keyed `publish`
set the precedent: it fails by default on purpose, for the same reason. Here there is no
sensible default to fail from, so the old method goes.

## R5. `InMemoryBroker` learns what a group is

**Decision**: Positions per group, one delivery per group, backlog before live, a settable clock,
positions kept across `stop()`.

**Rationale**: It stores `groupId` and never reads it (`MessageSubscriber.scala:105-108`): every
subscription is handed every message, and a subscription that starts late is handed nothing that
came before. Every start-position and version scenario at the `AnkkaTestKit` level, and every new
conformance case, runs on it (`ConformanceTarget.scala:58`), so without this the fast suites could
show nothing this feature does. It is a second implementation of the subscriber's contract and
will drift like any fake; the contract's four obligations are a shared suite run against both it
and Kafka.

**Verify first**: that no existing suite depends on two subscribers of one group each receiving a
message. `PeerSuite` and `RemoteProjectionSuite` are the candidates.

## R6. The recorded version is a table the runtime creates beside the view tables

**Decision**: `ankka_view_versions`, created in the transaction `ProjectionRuntime.start` already
creates view tables in, under the same advisory lock, only when the service has a topic-sourced
view. Not a file under `kustomization/components/postgres/ddl/`.

**Rationale**: View row tables are not in the DDL directory either; the runtime creates them
(`ProjectionRuntime.scala:85-96`), and this table exists for them. The DDL files reach a
developer's database through `docker-entrypoint-initdb.d`, which runs once, when the volume is
empty: a table added there would be missing from every existing local database, and the first
versioned view would fail on a relation that does not exist. Created at startup it is there for
every service that needs it and absent from every one that does not, and it is additive, which is
what FR-010 asks.

**Alternatives considered**: `40-view-versions-postgres.sql` in the DDL directory, as
`ankka_timers` is — correct for a table a `RuntimeExtension` needs unconditionally, wrong for one
that follows views.

## R7. A rebuild and a write exclude each other with a pair of advisory locks

**Decision**: A rebuild runs in one transaction that takes
`pg_advisory_xact_lock(ViewVersions.LockClass, hashtext(component id))`, re-reads the version,
and if it is still lower truncates the table and records the new one. A row write runs in one
transaction that takes `pg_advisory_xact_lock_shared` on the same key and then writes with
`WHERE EXISTS (SELECT 1 FROM ankka_view_versions WHERE component_id = … AND version = …)`,
reporting whether it wrote.

**Rationale**: The requirement is that no row a lower version writes survives a truncation. A
conditional write alone does not give it: under `READ COMMITTED` a write that decided the version
matched, and then waited for the truncation's table lock, completes after it. With the shared
lock taken first, a write either finishes before the rebuild can start or begins its second
statement after the rebuild has committed, and a new statement in `READ COMMITTED` sees what was
committed. Both are primitives this code already uses: `Database.executeAllInTransaction` and
`ViewStore.schemaLock`. A hash collision between two component ids makes them wait on each other
and nothing else.

**Alternatives considered**: `SELECT … FOR SHARE` on the version row inside the write — one
statement, but it rests on how Postgres re-evaluates a locking subquery after a concurrent
update, which is easier to get wrong than to explain. A table per version — no fencing needed and
the old view keeps serving whole during the roll, but queries would have to choose a table per
instance and the spec says the table is emptied.

**Verify first**: `ViewVersionSuite` races a writer at version 1 against a rebuild to version 2
many times and asserts no version-1 row remains; with the shared lock removed it must fail.
`Database` has no call that runs a transaction and returns a row count, so one is added.

## R8. A view that is behind stops its own subscription

**Decision**: `subscribe` returns `Subscribed`. When a guarded write reports that it wrote
nothing, the handler stops its subscription, fails the message so its offset is not committed,
logs L3 and sets the behind flag.

**Rationale**: A failing handler alone would be restarted by `RestartSource` with backoff for
ever, re-reading one message. The kill switch is already per subscription (`Kafka.scala:157`); it
only needs handing back. An instance that finds itself behind at startup never subscribes.

## R9. A view is not emptied until the broker has answered

**Decision**: The rebuild asks `earliestRetained` first and proceeds only on an answer, retrying
with the subscriber's backoff. The view's table and its recorded version are untouched until then.

**Rationale**: FR-015 needs the timestamps before the truncation, and the same call answers a
question nobody asked: is there a broker to rebuild from. Truncating first would leave a view
empty for as long as the broker was unreachable, when the old rows were sitting there.

## R10. Rules are one function, applied to both registries

**Decision**: `TopicSourceRules.problems` in `runtime`, called by `ComponentRegistry.validate`
(`Ankka.scala:111`) over in-process and remote descriptors, and by `Discovery.validate`
(`sidecar/.../Discovery.scala:120-271`).

**Rationale**: "Refused at registration, in Scala and through the sidecar alike" is one rule.
`RemoteSource.Topic` gains the start position and the two remote descriptors gain the version, so
the function reads the same facts whichever way a component arrived. `Discovery.validate` calls it
so the process hears through `ReportError`, with its other problems, in one report.

## R11. Protocol 1.4, and which side refuses what

**Decision**: [contracts/protocol.md](contracts/protocol.md). A runtime at 1.4 does not hold a
consumer from an SDK below 1.4 to rule T1; an SDK at 1.4 that declares a start position or a
version refuses a sidecar below 1.4.

**Rationale**: A process-hosted service's runtime is the platform's sidecar image
(`ANKKA_SIDECAR_IMAGE`) and its SDK is in its own image. If the 1.4 runtime refused a 1.3 consumer
over a topic, that service could not restart after a platform upgrade — a node drain would take
it down — and its developer could not have prepared, because a 1.4 SDK that declares a start
position must refuse a 1.3 runtime that would ignore it. The runtime knows what the SDK speaks
(`Spec.protocol_version`), so it can tell "did not say" from "could not say". The SDK's refusal is
the `produce_all` rule again (`RemoteProjection.consumerMetadata`): what an older runtime would
drop silently is refused by the side that knows it was said.

**Verify first**: that `SidecarInfo.protocol_version` reaches each SDK before it serves, and that
the control plane's existing check of a descriptor's declared `protocol` against
`Protocol.version` already refuses a 1.4 module on a 1.3 platform, which is the only guard a
module has.

## R12. Rust gets both declarations

**Decision**: Provided methods `start_from()` and `version()` on the three traits. `Source` is
not changed.

**Rationale**: The spec named three languages. A module can already declare
`Source::topic(...)` (`sdks/rust/ankka/src/components/view.rs:44`, `consumer.rs:27`), so under
rule T1 a Rust consumer over a topic could not be registered at all. `Source::Topic(String)` is a
public tuple variant; a field on it breaks every match.

## R13. Conformance grows two components and three cases

**Decision**: `ConformanceReference` and each SDK's conformance service gain a view over
`conformance-topic` declared at version 2 with no start position, and a consumer over it declared
`latest`. The target publishes to that topic before the service starts. Cases:
`topic.view-starts-earliest`, `topic.consumer-starts-latest`, `topic.version-names-the-group`.

**Rationale**: No conformance case reads a topic today; the broker in
`ConformanceTarget` is only published to. `discovery.lists-every-component` compares the id set
(`ConformanceReference.scala:630-642`), so all four implementations change together or the suite
says which did not. What conformance can show is that each language's declaration *arrives*: a
conformance service declares one thing for its whole life, so a rebuild, which is the same
component declared twice, is `RemoteProjectionSuite`'s, against the scriptable process double.
The refusals are not conformance cases either: a service that is refused does not start, so they
are `ProtocolSuite` cases on the sidecar and a declaration test in each SDK.

## R14. The upgrade re-reads, and a time is how to cut over cleanly

**Decision**: No offsets are carried from the old group to the new one. The upgrading page gives
the recipe: a consumer whose side effects must not repeat declares `StartFrom.At` the moment of
the cut-over; for a process-hosted service, that is deployed after the platform upgrade, with the
service paused across it if what it would otherwise re-send matters.

**Rationale**: The spec assumes the one-time re-read and this plan does not reopen it. It does
bind the release: the group rename (User Story 1) is never released without the start position
(User Story 2), because the recipe needs `StartFrom.At` and without it every topic consumer would
be redelivered everything retained with no way to decline. What
planning adds is its sharpest edge: a process-hosted consumer on a 1.3 SDK is restarted under its
new group by the platform upgrade itself, at `earliest`, before its developer can declare
anything (R11). `services pause` exists and is the spec saying zero instances; a time is exact
where `latest` would lose what was published while it was paused.

**Alternatives considered**: seeding the new group from the old group's commits at first
assignment — an `AdminClient` call per subscription and a rule for the case the defect produced,
where two services shared the old group. It would remove the re-read for everyone, and it is the
thing to build if this recipe proves too sharp in use.

## R15. Templates state the name where each already states its configuration

**Decision**: `ankka.service.name = "$name$"` in the Giter8 template's `application.conf`;
`ANKKA_SERVICE_NAME: {{name}}` on the `sidecar` and `runtime` services of
`cli/src/main/templates/common/docker-compose.yml`.

**Rationale**: `{{name}}` and `$name$` are the project's name, held to `ServiceDescriptor`'s rule
by `ankka init` already (`descriptors.scala:31`). The compose file is the one place all three
polyglot templates share. The local console announces a service by its actor system's name,
which is `ankka` for every service (`Ankka.scala:125-132`, `sidecar/.../Main.scala:44`); having it
announce the stated name instead is a one-line change this feature does not make, and the first
thing to do with `ServiceIdentity` after it.

## R16. What is proved where

**Decision**: No new k3s suite. The deployed form of a group name is proved by the pure function,
by `ServiceIdentity` read from a real certificate, and by `KafkaSuite` handed a deployed identity.

**Rationale**: Nothing puts a broker in a k3s cluster today, and a suite that did would add
Kafka's start to three suites that already run for minutes. 027-managed-broker brings a broker to
the cluster and is where a deployed service's group is first read off one. Until then the gap is
stated: nothing here runs a topic source on k3s.

## R17. `services get` is not in this feature

**Decision**: A topic source is visible in the service's log and in two metric series. The
`status.feature` scenarios describe those.

**Rationale**: Planning looked for the path the clarification asked for and found none. The
operator makes no call to a pod (nothing in `operator/src/main` imports an HTTP client); the
management port admits only a peer carrying the workload's own identity
(`ClusterFormation.scala:59-100`), and a network policy admits only pods of the same service to it
(`ZeroTrust.scala:140-172`); the probe port is open and answers `GET /ready` with one bit
(`ProbeEndpoint.scala:22-75`). Every way to carry a workload's own facts to the platform is a
decision about who may ask a workload what, and belongs in a spec that makes it on purpose.
Decided with the user on 2026-10-03.

## R18. `local` is a reserved project id

**Decision**: `"local"` joins `ProjectId.Reserved` in `controlplane-api` and
`Names.ReservedProjectIds` in `operator`.

**Rationale**: A named local run's group is `ankka.local.<service>.…`, which is exactly what a
deployed service in a project called `local` would be given. The contract's first property, that
distinct inputs give distinct names, is false while such a project can exist. The mechanism is the
one `platform` uses: the control plane refuses to create or project the id
(`descriptors.scala:79-89`), the operator refuses to render it (`Names.scala:39-49`), and
`ReservedProjectIdsSuite` holds the two sets equal, so one cannot be changed without the other.
The cost is stated on the upgrading page: an installation that already has a project called
`local` can no longer project it, and a project id cannot be renamed.

**Alternatives considered**: a prefix for local runs that no project could produce
(`ankka-local.<service>.…`) — nothing reserved and nothing broken, at the price of the form
agreed in clarification and of a second prefix for 027's access rules to know about. Decided with
the user on 2026-10-03.
