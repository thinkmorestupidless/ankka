# Implementation Plan: Topic Retention

**Branch**: `043-topic-retention-impl` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/043-topic-retention/spec.md`

## Summary

A project's topic declaration gains everything a real-money installation must be able to say and
see about a topic: how long it keeps (time and size), how it is cleaned (`delete`, `compact`,
`compact,delete`, with the tombstone window and the compaction lags), and how many copies it has and
how many must acknowledge a write, each filled from the installation's defaults when a first
declaration leaves it out, marked as such, kept when a redeclaration leaves it out, bounded by the
installation, and written onto the broker explicitly so nothing inherits Kafka's defaults. Feature 037 already made compaction one boolean; this feature generalises it and
keeps the boolean as an alias. Copies are fixed at declaration; the operator learns the broker's node
count from its node pools and fails a topic that asks for more; a Component gives a new installation
a three-node broker. A change is a redeclaration applied in place with no service restarted, recorded
in a new project history with each setting's old and new value; one that removes messages needs an
organization owner and a request that names what it removes. The runtime reads a topic's cleanup
policy and minimum in-sync copies from the broker, refuses a keyless message to a compacted topic
before it is sent, and refuses to start a producer whose `acks` is below what a topic needs. A view's
topic source reports, per partition, the beginning position and the earliest retained time, on every
surface the lag rides, merged per partition; and the control plane warns a view declared over a topic
that keeps less than the installation's threshold, recomputed on every read. A sweep on the control
plane's first start fills every topic declared before this feature.

[research.md](research.md) holds the eighteen decisions; [data-model.md](data-model.md) the shapes;
[contracts/](contracts/) what a member, a platform administrator, the operator and a developer's
service see; [quickstart.md](quickstart.md) the proofs.

## Technical Context

**Language/Version**: Scala 3.9 on JDK 21 (the platform); TypeScript/React Router (the console).

**Primary Dependencies**: Pekko and pekko-connectors-kafka 1.2 with kafka-clients 3.x (the runtime;
`Admin` is new in main code, no dependency added); fabric8 7.9 (operator, control plane); Strimzi
1.2.0 / Kafka 4.3.1 (the broker, pinned in `kustomization/components/broker`); jsoniter (the wire).

**Storage**: Postgres (the control plane's journal records settings, the defaulted set, each change
and the sweep's fill; `ankka_view_versions` gains `recorded_at`, created and altered by the runtime,
not the DDL directory); the `AnkkaProject` resource and the `KafkaTopic` it renders; the
`ankka-project` ConfigMap unchanged.

**Testing**: munit through `AnkkaTestKit` with the in-memory broker (the gap after `drop`, the keyless
refusal) and `KafkaSuite` over a Kafka container (a dropped segment, a compacted topic, `acks`);
`EventCompatibilitySuite`, `ControlPlaneFixturesSuite`, `CrdSchemaSuite`, `ProjectRenderingSuite`,
`RenderingGoldenSuite` (unchanged objects), `BrokerShapeSuite` and `RemoteOverlaySuite` (kustomize,
need `kubectl`), `ControlPlaneRoutesReferenceSuite`, `CliReferenceSuite`, `McpServerSuite`, the
console's Playwright suite; `GherkinSuite` over the five living features, four new k3s suites under
`ankka.cluster.tests` (one on a three-node broker), discovered by `.github/cluster-suites.py`.

**Target Platform**: Kubernetes (k3s in tests, kind locally, the cloud overlay); the control plane,
operator and runtime images.

**Project Type**: platform: libraries, images, a CLI, a console.

**Performance Goals**: the gap read (one group-less consumer, one record per partition) every five
minutes per topic source per instance, on subscribe and on rebuild; the configuration read (one
`describeConfigs`) per topic per minute per publisher, off the publishing thread; the warning computed
on each `services get` from data the endpoint already holds; the sweep one command per project on
each control plane start.

**Constraints**: no change for a topic whose declaration has no settings until the sweep fills it
(the pre-sweep `AnkkaProject` entry and `KafkaTopic` are byte-for-byte 037's; `ProjectRenderingSuite`
pins both shapes); the 037 `compacted` boolean keeps working on every wire, in the CLI and the
console; every new event field defaults so the journals replay; the operator stays out of the control
plane and reads one new resource type (`kafkanodepools`, `get`/`list`); the control plane never claims
the broker's node count; `crd` depends on nothing, so the Kafka-key mapping is rendered on both sides
from one fixture; no service restarts on a settings change (`KafkaTopic.config` applied in place; the
project informer's requeue does not roll a pod for a topic change today and must not start to); a
refusal at start reaches `services get`; k3s suites never on a pull request.

**Scale/Scope**: a project with tens of topics and up to 1000 partitions each; the change touches
`controlplane-api`, `controlplane`, `crd`, `operator`, `modules/runtime`, `modules/testkit`'s suites,
`cli`, `console`, `kustomization` (a component, a role, the control plane's env, a test render), the
docs and `features/`.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; the gates are the project's rules in
`CLAUDE.md` and `.claude/rules/`:

| Rule | How this plan keeps it |
|---|---|
| Reconciliation is split; the operator is not an ankka application and cannot reach the control plane | The sweep is the control plane's (R7); the operator reads node pools and renders topics (R9, R10); the resource is the only thing between them (R8). |
| `Action` values are inert; `Fabric8Executor` performs them | `brokerNodes` is a read beside `observeTopics`; rendering stays pure with the count as an input (R9, R10). |
| Cross-entity checks live in endpoints, never handlers | The owner check and the acknowledgement are `ProjectEndpoint`'s; the entity refuses fixed copies and records the diff (R4, R5). |
| Write the cluster first, then the journal; no secret in the journal | Unchanged order (contracts/project-topics.md); no new secret anywhere. |
| jsoniter reads `null` on an `Option` as absent | "Everything" and "none" are positive string values; codecs in companions (R1, R2). |
| A fieldless enum needs an explicit codec in its companion | Every value type of `TopicSettings` (R2). |
| A field on a CRD case class is not a field until the YAML declares it | Eight entry fields, two status fields and `brokerNodes` in `ankkaproject.yaml`; `CrdSchemaSuite` (R8). |
| Jackson reads `Option[Long]` as `Integer` | `@JsonDeserialize(contentAs = …)` on every new `Option` number (R8). |
| `PlatformVariables` is the one list; no hand-kept `ANKKA_` lists in operator, sidecar, controlplane-api | The twelve variables are constants on `TopicPolicy` in `controlplane`; `PlatformVariables` unchanged (R3). |
| `ANKKA_KAFKA_` reaches the process | No new `ANKKA_KAFKA_` variable; `acks` is read from the producer's settings (R15). |
| A rule about keys belongs in one place | The keyless refusal is in the publisher, where single and several publishes meet (R11). |
| `earliestRetained` answers every partition; a view is not emptied until it has | The answer gains fields, the contract keeps its cases (R12). |
| An `eventually` waits for the thing it asserts | The k3s steps wait on the `KafkaTopic`'s `spec.config` and the project status's phase, not on a listing (R16). |
| Forked tests do not inherit `-D` | No new switch; `ankka.cluster.tests` and `ankka.docs.update` already forwarded. |
| Scaling a Deployment directly is undone within a resync | A broker node is stopped with the cluster operator scaled to zero first (R16). |
| A repin that changes an object is a service rolling on upgrade | `RenderingGoldenSuite` and `RenderingUnchangedSuite` are untouched; only project rendering gains cases (R10). |
| A page stands alone; generated reference; every page in nav and a skill | R17; no new page. |
| Living features | Five features; each scenario ends as a named test in the suite R16 assigns. |

No gate fails. Re-checked after Phase 1: unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/043-topic-retention/
├── plan.md
├── research.md                      # R1–R18
├── data-model.md                    # settings, policy, declared topic, events, history, CRD, KafkaTopic, config cache, gap, status
├── quickstart.md                    # the proofs, offline, on k3s, by hand
├── contracts/
│   ├── project-topics.md            # PUT with settings and removes; refusals; listing; history; CLI
│   ├── installation-settings.md     # the twelve variables; the three-node component; the operator's role
│   ├── operator.md                  # the entry, the KafkaTopic, the status, the decisions, the node count
│   ├── service-status.md            # the gap on every surface; the warning; services get; console; MCP
│   └── publishing.md                # the configuration read; keyless refused; acks refused at start
└── tasks.md                         # /speckit-tasks
```

### Source Code (repository root)

```text
controlplane-api/…/api/
└── descriptors.scala                # TopicSettings and value types + codecs; TopicDeclarationRequest fields; ProjectTopic.settings/replicas/brokerNodes;
                                     #   ProjectTopics.fill/problems(bounds)/changes/removal; TopicSettingsView; ProjectHistoryEntry; SettingChange;
                                     #   TopicSourceReport.gap; RetentionGapReport; ServiceStatus.warnings; ServiceWarning; Wire codecs
controlplane/…/controlplane/
├── tenancy/TopicPolicy.scala        # defaults, bounds, threshold from reference.conf; refuses at start
├── domain/{model,events}.scala      # DeclaredTopic.settings/defaulted; Project.history; ProjectTopicDeclared fields; ProjectTopicSettingsFilled
├── application/ProjectEntity.scala  # fixed copies refused; changes computed; FillTopicSettings; history query
├── application/TopicSettingsSweep.scala   # the extension that fills once per start
├── api/ProjectEndpoint.scala        # fill, bounds, removal, owner, removes; GET history
├── api/ServiceEndpoint.scala        # gap merged per partition; warnings
├── deploy/ProjectProjection.scala   # the eight entry fields from toKafka
├── ControlPlane.scala               # TopicPolicy threaded; the sweep registered
└── src/main/resources/reference.conf   # ankka.controlplane.topics.*
crd/…/crd/AnkkaProject.scala         # entry fields; status replicas/config; brokerNodes
kustomization/
├── components/crd/ankkaproject.yaml
├── components/broker/operator-role.yaml      # kafkanodepools get, list
├── components/broker-three-nodes/            # new Component: pool replicas 3, five Kafka keys, two control plane variables
├── components/controlplane/deployment.yaml   # the twelve variables, one-node values
├── overlays/cloud/{kustomization.yaml,broker-size.yaml}   # the comment names the component
└── tests/broker-three-nodes/        # local overlay + component, for BrokerShapeSuite
operator/…/operator/
├── strimzi/KafkaTopicResource.scala # replicas; the comment
├── strimzi/KafkaNodePoolResource.scala   # new model
├── StrimziRendering.scala           # the seven keys from the entry; replicas
├── TopicProvisioning.scala          # decide(entry, state, nodes); Failed above the count; Ready on config equality
├── BrokerProvisioning.scala         # TopicState.replicas/config
├── Executor.scala                   # observeTopics reads replicas/config; brokerNodes
└── ProjectReconciler.scala          # reads the count; status.brokerNodes
modules/runtime/…/runtime/
├── TopicConfigs.scala               # the Admin read and cache; KeylessPublication
├── Kafka.scala                      # KafkaPublisher refuses keyless to compacted; earliestRetained returns Retained; acks read
├── MessageSubscriber.scala          # Retained; InMemoryBroker.compact/drop
├── TopicSources.scala               # RetentionGap; one JSON rendering of a topic source
├── ProjectionRuntime.scala          # gap on subscribe/rebuild/GapInterval; the acks refusal at start
├── ViewVersions.scala               # recorded_at
├── ObservabilityDocuments.scala, TopologyJson.scala   # use TopicSources' rendering
└── ObservabilityRoute.scala         # the three series
modules/testkit/src/test/…/testkit/  # KafkaSuite, InMemoryBrokerSuite, ViewVersionSuite, MetricsSuite cases
cli/…/cli/{Main,Output,ControlPlaneClient}.scala, mcp/AnkkaTools.scala   # flags, the prompt, --removes, list columns, history, services get, project_history
console/package/src/{client/schemas.ts, routes/{project,service}.tsx, testing/fake-control-plane.ts}, fixtures/control-plane/, e2e/
docs/{build/topics.md, platform/{broker,install-cloud}.md, reference/{configuration,control-plane-api,cli,limitations}.md, build/graph.md, deploy/graph-sink.md}
features/{broker/{retention,cleanup-policy,copies,changing}.feature, topics/gap.feature, graph-deltas/store.feature}
controlplane/src/test/…/{BrokerRetentionFeatures,BrokerCleanupFeatures,BrokerCopiesFeatures,BrokerChangingFeatures,BrokerShapeSuite,ServiceWarningsSuite,TopicPolicySuite,TopicSettingsSweepSuite}.scala
operator/src/test/…/BrokerStack.scala   # install(k3s, nodes = 3)
.claude/rules/{messaging,kubernetes,control-plane}.md   # the traps this feature learns
```

**Structure Decision**: everything lands where its kind already lives; the two new homes are
`TopicConfigs` in the runtime (the first admin read) and the `broker-three-nodes` Component.

## Order of work

Each step is offline before the k3s features run; a story's living feature is its proof.

1. **The settings and the policy** (R1–R4): `TopicSettings`, the codecs, the fixture and
   `TopicSettingsSuite`; `TopicPolicy` and `reference.conf`; `ProjectTopics.fill/problems/changes/removal`;
   the entity, the events, `EventCompatibilitySuite`; the request and listing types, the fixtures; the
   CLI flags and columns; the console's form and table. `retention.feature`'s refusal and
   keep-everything scenarios and `copies.feature`'s fixed-copies outline run offline in `ProjectTopicsFeature`.
2. **The acknowledgement and the history** (R5, R6): the removal in `ProjectEndpoint`, the owner
   check, `removes`; `Project.history`, the route, `projects history`, the console section,
   `project_history`; the generated pages. `changing.feature`'s owner, acknowledgement and
   records-nothing scenarios offline.
3. **The resource and the operator** (R8–R10): the entry and status fields, the YAML, `CrdSchemaSuite`;
   `ProjectProjection`; `KafkaTopicSpec.replicas` and the seven keys, `StrimziModelsSuite` against the
   fixture; `KafkaNodePoolResource`, the role, `brokerNodes`; `decide` with the count and config
   equality; `ProjectRenderingSuite` and `TopicProvisioningSuite`; the `broker-three-nodes` Component,
   the control plane's variables, `kustomization/tests/broker-three-nodes`, `BrokerShapeSuite`.
4. **The sweep** (R7): `FillTopicSettings`, `ProjectTopicSettingsFilled`, `TopicSettingsSweep`,
   `TopicSettingsSweepSuite` over a 037-shaped journal, twice.
5. **The runtime's reads** (R11, R15): `TopicConfigs`, `KeylessPublication` in `KafkaPublisher` and
   `InMemoryBroker.compact`; the `acks` refusal; `KafkaSuite` and `InMemoryBrokerSuite` cases;
   `cleanup-policy.feature`'s keyless outline and lag scenario.
6. **The gap** (R12, R13): `Retained`, `RetentionGap`, `recorded_at`, the three reports, the one
   rendering, the series; `TopicSourceReport.gap` and the per-partition merge; `services get`; the
   console's column; `ViewVersionSuite` with `drop`, `KafkaSuite` with a dropped segment, `MetricsSuite`,
   `TopicSourcesReportSuite`. `gap.feature`'s first four scenarios.
7. **The warning** (R14): `ServiceWarning`, `withUndeclaredTopics`, `ServiceWarningsSuite`, the CLI
   block, the console notice, the MCP description. `gap.feature`'s last four scenarios.
8. **The k3s suites** (R16): `BrokerStack.install(k3s, nodes = 3)`, the stop of a node, the four
   `BrokerClusterFeatures` subclasses, the RBAC pin; run with `gh workflow run cluster`.
9. **Pages and rules** (R17): every page named in R17, `limitations.md`, the spec's Context note, the
   three rule files' traps (the lag over-count; `Admin` in the runtime; a node pool read in the
   operator; the `removes` protocol).

## Complexity Tracking

| Choice | Why the simpler one does not do |
|---|---|
| The Kafka-key mapping rendered on both sides from one fixture | `crd` and the operator may not depend on `controlplane-api`; a shared source file compiled twice (as `PlatformVariables`) would carry `TopicSettings`' value types and codecs into the operator, which has no jsoniter. |
| A project history beside the service's, rather than one history | The service's entries are a service's (generation, image, rollback); a project's are its topics'. One list with two shapes would make every reader branch. |
| `removes` restating the removal, not a boolean | A boolean confirms whatever the server computes now; the text makes a stale client's acknowledgement refused. |
| A new Component for the three-node broker, not `SET` placeholders | The placeholders are the comment this feature retires: five numbers and a control plane variable a person had to keep in step by hand. |
