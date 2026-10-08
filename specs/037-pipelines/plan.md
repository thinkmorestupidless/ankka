# Implementation Plan: Pipelines Are Services

**Branch**: `037-pipelines` | **Date**: 2026-10-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/037-pipelines/spec.md`

## Summary

A pipeline is a service with consumers. This feature gives ankka the seven things ankka-flow had
that a consumer lacks, so that ankka-flow can retire: a contract (a name and a schema the project
holds, fingerprinted) on a declared topic, checked by the runtime at a service's start from a file
the operator writes; compaction as a topic setting; the graph merge sink as a consumer in a module
of its own and an image built from it; a declared broker a component may name for one topic, with a
certificate or SASL credential in a project secret that reaches only the platform's container;
partitions handled in parallel when a topic source asks; the process container sized by the
descriptor and a service that declares no database; and lag per topic source in the status, the
CLI, the console and the MCP server. The protocol grows to 1.14 and every SDK learns the three
options. Nothing of ankka-flow's machinery comes across. [research.md](research.md) holds the
nineteen decisions; [data-model.md](data-model.md) the shapes; [contracts/](contracts/) what a
member, a developer and the operator see.

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21 (the platform); Python 3.12, TypeScript on Node 24,
Rust (the SDKs); TypeScript/React Router (the console).

**Primary Dependencies**: Pekko 1.7 and pekko-connectors-kafka 1.2 (the runtime); fabric8 7.9
(operator, control plane); Strimzi 1.2 / Kafka 4.3 (the broker); the Neo4j Java driver 5.28.5
(new, in `graph-neo4j` only); a JCS implementation per SDK (`rfc8785`, `canonicalize`,
`serde_jcs`; Scala over jsoniter); the JSON Schema format for a contract's document.

**Storage**: Postgres (the control plane's journal records a contract's name and fingerprint,
compaction and declared brokers); a ConfigMap per project for schema documents and one for the
declarations the operator writes; Neo4j 5.26+ (the sink's store, in tests a container).

**Testing**: munit through `AnkkaTestKit` and the in-memory broker (offline); `KafkaSuite` over a
Kafka container for partitions, lag and the no-database case; a Neo4j container for the sink
(`-Dankka.neo4j.image`); the conformance suite for the SDKs' new declarations and the parallel
consumer; `GherkinSuite` over the seven living features, the cluster ones on k3s under
`ankka.cluster.tests`; `RenderingGoldenSuite`, `CrdSchemaSuite`, `EventCompatibilitySuite`,
`ControlPlaneRoutesReferenceSuite`, `CliReferenceSuite`, `ControlPlaneFixturesSuite`, the
console's Playwright suite and `parity.ts`.

**Target Platform**: Kubernetes (k3s in tests, kind locally, the cloud overlay); the SDKs' own
runtimes.

**Project Type**: platform: libraries, images, a CLI, a console.

**Performance Goals**: SC-004: a parallel consumer over N partitions with a handler of fixed cost
handles N messages in the time it handled one (N = 4 in the suite). Lag polled every 30 s per
subscription with one raw consumer per poll.

**Constraints**: no change in behaviour for a project without contracts, compaction or declared
brokers (FR-012; the operator's goldens and the rendering pin are regenerated once for the
additive fields every Deployment gains, the termination policy and the project mount, and the
diff is reviewed to be those alone); no credential of a declared broker in the
process container, the journal or any log; no message checked against a schema as it flows; the
operator keeps its render/execute split and the control plane its "write the cluster first"
rule; a start-time refusal reaches `services get`.

**Scale/Scope**: a project with up to 1000-partition topics and tens of declared topics and a
handful of declared brokers; schemas at most 64 KiB; the change touches core, sdk, runtime,
sidecar, protocol and four SDKs, crd, operator, controlplane-api, controlplane, cli, console, the
new `graph-neo4j` module and `graph-sink` image, the docs and the release workflow.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; the gates are the project's own rules
in `CLAUDE.md` and `.claude/rules/`:

| Rule | How this plan keeps it |
|---|---|
| Reconciliation is split; the operator is not an ankka application | The operator renders two ConfigMaps and a volume from `AnkkaProject`; the control plane writes `AnkkaProject` and the schema ConfigMap; neither reads the other (R3, R16, R17). |
| `Action` values are inert; `Fabric8Executor` performs them | `EnsureProjectConfig` is an action; `observeTopics` reads `config` (R17). |
| Pure deciders | `Provisioning.decide` gains `NotNeeded` for `database: none`; `TopicProvisioning` compares compaction; `Rendering.render` stays pure and takes the project's declared brokers as an input beside `databasePlan` (R7, R10, R11, R17). |
| No secret value in the journal; write the cluster first | A schema goes to a ConfigMap first and the entity records its fingerprint; a declared broker records a secret's name, never a value (R16, R17). |
| `ANKKA_KAFKA_*` means "supplies its own broker" and is shared with the process | Declared brokers use `ANKKA_TOPIC_BROKER_*`, runtime-only, and a mounted secret (R7). |
| Forked tests do not inherit `-D` | `ankka.neo4j.image` and `ankka.fixtures.regenerate` are forwarded (R15, R14). |
| A protocol minor adds optional fields; an older SDK is accepted | 1.14 adds optional fields only; absent fields mean today's behaviour (R12). |
| `protocol/fixtures` belongs to a suite that generates it | `ContractFixturesSuite` and `GraphFixturesSuite` write and refuse (R2, R14). |
| A new route is documented and exercised by the console | R18. |
| A page stands alone; samples come from tested code; generated reference | R18; the sink's descriptor in `docs/deploy/graph-sink.md` is a `service.json` block `DocumentationDescriptorsSuite` validates. |
| Living features | Seven features written; each scenario ends as a test that fails without the feature (the plan names the suite per feature below). |
| Only a tag publishes; placeholders are `0.0.0` | The sink image and the eleventh module ride the existing jobs (R19). |

No gate fails. Re-checked after Phase 1: unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/037-pipelines/
├── plan.md
├── research.md          # R1–R19
├── data-model.md        # contract, schema, declared topic, declared broker, topic source status, the sink's settings
├── quickstart.md        # the proofs, by story
├── contracts/
│   ├── project-topics.md    # PUT/GET topics with contract, compaction and schema; CLI; refusals
│   ├── project-brokers.md   # PUT/DELETE/GET brokers; the secret's shapes; CLI
│   ├── declarations.md      # what a component states (four SDKs), the protocol 1.14 fields, the start-time check and its refusal text
│   ├── operator.md          # the two ConfigMaps, the mounts, the variables, KafkaTopic config, process resources, database: none
│   ├── service-status.md    # topicSources, topicChecks, failing; services get, console, MCP
│   └── graph-sink.md        # the module's component, the image's variables and descriptor, the rules it keeps
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
modules/core/…/core/
├── Contract.scala                 # name + fingerprint; JCS canonicalisation; fromSchema
└── graph/GraphFixtures.scala      # reads what GraphFixturesSuite now writes
modules/sdk/…/sdk/
├── ChangeSource.scala             # Topic.options: TopicOptions(contract, broker, parallel)
└── Consumer.scala                 # produces: Option[Publication]
modules/runtime/…/runtime/
├── ProjectionRuntime.scala        # per-broker connections; the start-time contract and broker checks; parallel subscriptions
├── Kafka.scala                    # KafkaConnection.named; KafkaCredential (SSL, SASL); partitioned source; lag; ce-type from the publication
├── MessageSubscriber.scala        # TopicSubscription.parallel; lag; InMemoryBroker
├── TopicHandlers.scala            # a handler per partition; failing
├── TopicSources.scala             # lag, failing; the poll
├── ObservabilityDocuments.scala   # topicSources with lag and failing
├── TopologyJson.scala             # contract and broker on edges; broker-qualified topic ids
├── DeclaredConnections.scala      # DeclaredSource.Topic(name, contract, broker)
├── ProjectDeclarations.scala      # reads ANKKA_PROJECT_DECLARATIONS
├── StartRefusal.scala             # the termination-log write, shared
├── Ankka.scala                    # ANKKA_DATABASE=none; lazy Database(); refusals
└── remote/RemoteDescriptors.scala # RemoteSource.Topic.options; produces
modules/graph-neo4j/…/graph/neo4j/
├── Neo4jSink.scala                # the consumer; the four statements; one delta per transaction
├── Neo4jSettings.scala
└── (tests) Neo4jSinkSuite, Neo4jMergeSuite (carried), Neo4jFixturesSuite
graph-sink/…/
└── Main.scala                     # registers one Neo4jSink from env; database: none
protocol/
├── src/main/protobuf/ankka/protocol/v1/discovery.proto   # Contract, Publication, Source 4–6, ConsumerDetail 4
├── README.md                      # 1.14
└── fixtures/{contracts/fingerprints.json, graph-deltas/*}
sidecar/…/sidecar/
├── Discovery.scala                # reads the new fields
└── Main.scala                     # refusal to the termination log
sdks/{python,typescript,rust}/     # Contract.from_file; contract=, broker=, parallel=; produces_to a Publication; 1.14; conformance cases
crd/…/crd/
├── AnkkaProject.scala             # compacted, contractName, contractFingerprint; brokers
└── AnkkaService.scala             # processCpuMillis, processMemoryMiB, database
kustomization/components/crd/{ankkaproject.yaml,ankkaservice.yaml}
kustomization/components/{controlplane/controlplane-rbac.yaml, operator/operator.yaml}
operator/…/operator/
├── ProjectReconciler.scala        # EnsureProjectConfig; compaction
├── StrimziRendering.scala, strimzi/KafkaTopicResource.scala   # config
├── TopicProvisioning.scala, Executor.scala                     # observe config; execute the action
├── Rendering.scala                # the ankka-project mount; broker secret mounts and variables; process sizes; FallbackToLogsOnError; NotNeeded
├── Provisioning.scala             # database: none → NotNeeded
└── Action.scala
controlplane-api/…/api/descriptors.scala   # TopicDeclarationRequest, ProjectTopic, Contract, BrokerDeclaration, ServiceResources.process, database, ServiceStatus.topicSources/topicChecks
controlplane/…/controlplane/
├── domain/{model,events}.scala    # DeclaredTopic, DeclaredBroker, events
├── application/{ProjectEntity,ProjectRows,ProjectTopicsTrigger}.scala
├── api/{ProjectEndpoint,ServiceEndpoint}.scala     # topics with contract/schema, brokers, topicChecks, topicSources
└── deploy/{ProjectProjection,ServiceProjection,ServiceProjector,InstanceTopologies,Fabric8AnkkaServiceClient}.scala
cli/…/cli/{Main,Output,ControlPlaneClient}.scala, mcp/AnkkaTools.scala
console/package/src/{client/schemas.ts, routes/{project,service}.tsx, testing/fake-control-plane.ts}, console/e2e/
docs/{build/topics.md, build/graph.md, deploy/graph-sink.md (new), platform/broker.md, platform/secrets.md,
      reference/{service-descriptor,control-plane-api,cli,limitations}.md, deploy/scaling-and-rollouts.md}
tools/docs/skill/{ankka-views-consumers,ankka-deploy,ankka-platform}/SKILL.md; mkdocs.yml
features/{topics/{contracts,brokers,parallelism,status}.feature, broker/compaction.feature,
          graph-deltas/{sink,documentation}.feature, deploying/process-resources.feature}
build.sbt, .github/{workflows/{release,ci}.yml, ci-coverage.py}, .claude/rules/{build-and-release,messaging,kubernetes}.md
```

**Structure Decision**: everything lands where its kind already lives; the two new homes are
`modules/graph-neo4j` (a published library, like the other nine) and `graph-sink/` (an image
project, like `operator/`).

## Order of work

Each step is offline before the k3s features run; a story's living feature is its proof.

1. **Contracts in the platform** (R2, R16, R17 for topics): `Contract` and the fixtures suite;
   the wire types and the `PUT` with schema; the entity, events and the compatibility pin; the
   schema ConfigMap and the `GET …/schema` route; `AnkkaProject` entries; CLI `topics set
   --contract --schema`, `topics schema get`; the console's project page; generated pages.
   `features/topics/contracts.feature` scenarios 1, 2 and the listing scenario.
2. **The declaration and the check** (R1, R3, R4, R12): `TopicOptions`, `Publication`,
   `DeclaredSource`; the operator's `ankka-project` ConfigMap and mount; `ProjectDeclarations`;
   the start-time check in `ProjectionRuntime`; `StartRefusal` and `FallbackToLogsOnError`;
   protocol 1.14 and the sidecar; the four SDKs. Contracts scenarios 3–6; `ce-type` (R6).
3. **The listing's view** (R5): edges with contracts; `topicChecks`; `topics list` columns.
4. **Compaction** (R17): `KafkaTopicSpec.config`, observe, provisioning, status, CLI, console.
   `features/broker/compaction.feature` on k3s in `BrokerClusterFeatures`.
5. **Parallel partitions** (R8): `TopicSubscription.parallel`, the partitioned source, a handler
   per partition; the conformance case. `features/topics/parallelism.feature` in `KafkaSuite`.
6. **Lag and failing** (R9): the subscriber's `lag`, the poll, the status, `/observability/
   service` read by the control plane, `ServiceStatus.topicSources`, CLI, console, MCP.
   `features/topics/status.feature`'s two new scenarios.
7. **The sink** (R13, R14, R15): the module, the carried suites, the fixtures made ankka's,
   the image project, the release jobs; `features/graph-deltas/sink.feature` offline over the
   in-memory broker and a Neo4j container, and on k3s with the image deployed into the shopping
   cart's project; the documentation (R18) and `documentation.feature`.
8. **Declared brokers** (R7, R17 for brokers): the entity, routes, CLI, `AnkkaProject.brokers`,
   the operator's mounts and variables, `KafkaCredential` with SASL, per-broker connections in
   the runtime, the SDK and protocol fields (already in 1.14). `features/topics/brokers.feature`
   in a `KafkaSuite` case with a second Kafka container under SASL, and on k3s.
9. **Sizing and no database** (R10, R11): `resources.process`, `database: none`, the CRD fields,
   `NotNeeded`, the runtime's refusals, the `KafkaSuite` no-database case.
   `features/deploying/process-resources.feature`.
10. **Pages, rules and the retirement's prerequisites** (R18, R19): every sentence named in
    R18, the skills, `limitations.md`, `build-and-release.md`, `messaging.md`; `grep -ri
    ankka-flow docs/` finds only the contributing page's note (SC-005).

## Complexity Tracking

| Choice | Why the simpler one does not do |
|---|---|
| A second ConfigMap per project (`ankka-project-schemas`) beside the operator's (`ankka-project`) | The schema is the control plane's to write and read; the declarations file is the operator's rendering. One object with two writers would be a conflict under server-side apply. |
| Every service in a project mounts every declared broker's secret | The operator renders a service before its components are known; a project is already the boundary project secrets are scoped to. |
| A new `graph-sink/` image project rather than a `samples/` entry | The sink is a platform deliverable a member deploys, versioned and published with the platform; a sample is not. |
