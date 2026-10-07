# Tasks: Pipelines Are Services — What ankka Gains So That ankka-flow Can Retire

**Input**: Design documents from `/specs/037-pipelines/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature. The scenarios are in `features/topics/`, `features/broker/`,
`features/graph-deltas/` and `features/deploying/`; where a task says "case", it means a
`test(...)` in the named suite, named for the scenario it holds; where it says "steps", it means
the Gherkin step definitions a `GherkinSuite` runs the feature file through.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a declared topic carries a contract, every side checked), US2 (a declared
  topic has settings), US3 (the graph merge sink is ankka's), US4 (a topic on another broker),
  US5 (a topic source reads its partitions in parallel), US6 (a process is sized by its
  descriptor, a stage needs no database), US7 (a service says how far behind each topic source is)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `SDK` = `modules/sdk/src/main/scala/…/sdk`; `RT`/`RTT`
= `modules/runtime/src/{main,test}/scala/…/runtime`; `TKT` = `modules/testkit/src/test/scala/…/testkit`;
`SC`/`SCT` = `sidecar/src/{main,test}/scala/…/sidecar`; `OP`/`OPT` =
`operator/src/{main,test}/scala/…/operator`; `CRD`/`CRDT` = `crd/src/{main,test}/scala/…/crd`;
`API`/`APIT` = `controlplane-api/src/{main,test}/scala/…/controlplane/api`; `CP`/`CPT` =
`controlplane/src/{main,test}/scala/…/controlplane`; `CLI`/`CLIT` = `cli/src/{main,test}/scala/…/cli`;
`GN`/`GNT` = `modules/graph-neo4j/src/{main,test}/scala/…/graph/neo4j`; `GS` =
`graph-sink/src/main/scala/…/graphsink`; `PY` = `sdks/python/src/ankka`; `TS` = `sdks/typescript/src`;
`RS` = `sdks/rust/ankka/src`; `CON` = `console/package/src`; `K` = `kustomization`; `F` = `features`.

Offline runs always pass `-Dankka.cluster.tests=off`. A k3s run is `caffeinate -i sbt 'set
controlPlane / Test / logBuffered := false' …`, so a scenario reports as it ends. ankka-flow's
sources to carry are at `../ankka-flow` (`28d6f1a`), `sidecar/src/{main,test}/scala/…/flow/sidecar/`.

## Phase 1: Setup

- [ ] T001 Add `modules/graph-neo4j` (`graphNeo4j`, published as `ankka-graph-neo4j`, depends on `sdk`; `neo4jDriver` 5.28.5 compile, `testcontainersNeo4j` test) and `graph-sink/` (`graphSink`, `publish / skip`, `JavaAppPackaging` + `DockerPlugin`, `dockerSettings`, main `…graphsink.Main`, image `ankka-graph-sink`) to `build.sbt`, both in `root.aggregate`; add `V.neo4jDriver`, `V.neo4jImage = "neo4j:5.26-community"` and the two dependencies to `project/Dependencies.scala`
- [ ] T002 Forward `ankka.neo4j.image` and `ankka.fixtures.regenerate` in `build.sbt`'s `Test / javaOptions` switch list, and set `-Dankka.neo4j.image=${V.neo4jImage}` as a fixed option on `graphNeo4j`'s tests
- [ ] T003 [P] Add `graph-sink/**` and `modules/graph-neo4j/**` to `.github/workflows/ci.yml`'s `scala` filter and satisfy `.github/ci-coverage.py`; add `ankka-graph-sink` to the `images` job's `IMAGES` and `graphSink/Docker/publish` to its sbt line in `.github/workflows/release.yml`; make the header say eleven modules
- [ ] T004 [P] Add JCS dependencies: `rfc8785` to `sdks/python/pyproject.toml`, `canonicalize` to `sdks/typescript/package.json`, `serde_jcs` to `sdks/rust/ankka/Cargo.toml`

## Phase 2: Foundational

Shared by every story: the contract value and its fixtures, the protocol, the declaration options
in the SDK and the remote descriptors, the declarations file, the refusal path.

- [ ] T005 [P] `CORET/ContractSuite.scala`: `fromSchema` canonicalises (key order and whitespace do not change the fingerprint; a changed field does), refuses non-JSON, enforces the name rule, fingerprints a 64 KiB document in under ten milliseconds; `CORET/ContractFixturesSuite.scala` writes `protocol/fixtures/contracts/fingerprints.json` under `-Dankka.fixtures.regenerate=on` and refuses a difference otherwise
- [ ] T006 `CORE/Contract.scala`: `Contract(name, fingerprint)`, `Contract.fromSchema(name, bytes)` over jsoniter's tree with RFC 8785 serialisation (sorted keys, no whitespace, ES6 number formatting), `NameRule`; commit the fixture file with at least six rows (an object, nested objects, arrays, numbers with exponents, unicode keys, an empty object)
- [ ] T007 [P] `protocol/src/main/protobuf/ankka/protocol/v1/discovery.proto`: `message Contract`, `message Publication`, `Source` fields 4–6, `ConsumerDetail.produces = 4`; `protocol/README.md` version section states 1.14 and what it adds; `WireProtocol.Version` in `RT/remote/Conversation.scala` and `Compatibility.version` in `API/Compatibility.scala` to 1.14; copy the protocol into the three SDKs with their scripts (`sdks/python/scripts/proto.py`, `npm run proto`, `sdks/rust/scripts/proto.sh`) and run their generation
- [ ] T008 [P] `SDK/ChangeSource.scala`: `TopicOptions(contract, broker, parallel)` and `Topic.options`, `fromTopic` overloads; `SDK/Consumer.scala`: `Publication(topic, contract, broker)`, `def produces: Option[Publication] = produceTo.map(Publication(_))`, `ConsumerDescriptor.produces`, descriptor refusal when `produces` and `produceTo` name different topics; `SDK/View.scala` unchanged beyond `Topic.options`
- [ ] T009 [P] `RT/remote/RemoteDescriptors.scala`: `RemoteSource.Topic.options`, `RemoteConsumerDescriptor.produces`; `RT/DeclaredConnections.scala`: `DeclaredSource.Topic(name, contract, broker)`, `destinationOf` returns the `Publication`; `RT/TopicSourceRules.scala`: refuse `parallel` on a component source, refuse `produces`/`produceTo` disagreement (remote), with cases in `RTT/TopicSourceRulesSuite.scala`
- [ ] T010 `SC/Discovery.scala`: read `Source.contract/broker/parallel` and `ConsumerDetail.produces` into the remote descriptors (`source(...)` at 416-444, the consumer case at 231-240); `SCT/DiscoverySuite.scala` cases: a 1.14 Spec with every field, a 1.13 Spec (nothing new, accepted), `produces` and `produces_to` disagreeing (refused)
- [ ] T011 [P] `RT/ProjectDeclarations.scala`: read `ANKKA_PROJECT_DECLARATIONS` (the `topics.json` shape in data-model.md) into `ProjectDeclarations(topics: Map[String, DeclaredTopicView], brokers: Map[String, DeclaredBrokerView])`, `None` when the variable is unset or the file absent; `RTT/ProjectDeclarationsSuite.scala`
- [ ] T012 [P] `RT/StartRefusal.scala`: `report(reason: String)` writes the termination log (path from `ankka.termination.log`, default `/dev/termination-log`) and logs, extracted from `RT/DeclaredGrpc.scala:22-51`, which now calls it; `ServiceBuilder.host`'s validation failure (`RT/Ankka.scala:202-208`), `ProjectionRuntime.rejectUnsupported`/`rejectUnsupportedRemote` and `SC/Main.runProcess`'s discovery refusal call it; `RTT/StartRefusalSuite.scala` reads the file back
- [ ] T013 `OP/Rendering.scala`: `terminationMessagePolicy: FallbackToLogsOnError` on the embedded container and the sidecar container (as the module container at 1264-1268); regenerate goldens with `-Dankka.golden.update=true` once and review the diff is that one field; `OPT/RenderingSuite.scala` case asserting the policy
- [ ] T014 [P] Python SDK: `PY/contract.py` (`Contract.from_file`, `Contract.from_bytes`, JCS via `rfc8785`), `topic(name, start=, contract=, broker=, parallel=)` and `Publication` in `PY/consumer.py`/`PY/view.py`, discovery carries them, `PROTOCOL_VERSION = "1.14"` in `PY/service.py`; tests in `sdks/python/tests/test_contract.py` against `fingerprints.json` and `tests/test_discovery.py` for the fields
- [ ] T015 [P] TypeScript SDK: `TS/contract.ts` (`Contract.fromFile`, `canonicalize`), `topic({...})` options and `Publication` in `TS/consumer.ts`/`TS/view.ts`, `TS/spec.ts` to 1.14; tests `sdks/typescript/test/contract.test.ts`, `discovery.test.ts`
- [ ] T016 [P] Rust SDK: `RS/contract.rs` (`Contract::from_file`, `serde_jcs`), `Source::topic(...).contract(..).broker(..).parallel()` and `Publication` in `RS/consumer.rs`/`RS/view.rs`, `PROTOCOL_VERSION` in `RS/service.rs`; tests in `sdks/rust/ankka/tests/contract.rs`, `discovery.rs`
- [ ] T017 Conformance: the reference streamlets in `SCT/conformance/` and each SDK's conformance service declare a consumer with a contract, a broker and `parallel`; `SCT/ConformanceSuite.scala` cases assert discovery carries them for every SDK (`ankka.conformance.shape`)

**Checkpoint**: `sbt core/test runtime/test sidecar/test` green; the three SDKs' tests green; every discovery path carries the new fields; nothing checks them yet.

## Phase 3: User Story 1 — A declared topic carries a contract, every side checked (P1) 🎯 MVP

**Goal**: a member declares a contract with its schema; a component states it; a disagreeing
service is refused at start with both sides named in `services get`; the listing shows checks.

**Independent test**: `F/topics/contracts.feature` through `TopicContractsSuite` (offline, the
in-memory broker and a declarations file) and `TopicContractsFeatures` (k3s).

### Tests

- [ ] T018 [P] [US1] `APIT/ProjectTopicsSuite.scala` cases: a declaration with `contract {name, schema}` is accepted; a name outside the rule, a non-JSON schema, a schema over 65 536 bytes, a name without a schema, a schema without a name are each refused with the message in contracts/project-topics.md
- [ ] T019 [P] [US1] `CPT/EventCompatibilitySuite.scala`: pin `ProjectTopicDeclared`'s wire form with and without `compacted` and `contract`; a pre-037 event decodes with `compacted = false`, `contract = None`; the journal fixture gains one such event
- [ ] T020 [P] [US1] `RTT/ProjectionRuntimeSuite.scala` (or a new `RTT/ContractCheckSuite.scala`): the seven rows of the table in contracts/declarations.md, each a case, run against a temp declarations file; the refusal text matches the contract's lines exactly
- [ ] T021 [P] [US1] `TKT/TopicContractsSuite.scala`: steps for `F/topics/contracts.feature` offline: "a ready service whose consumer publishes … stating the contract" starts an `AnkkaTestKit` service with `ANKKA_PROJECT_DECLARATIONS` pointing at a file the steps write; "the message on the topic has the type" reads `ce-type` from the in-memory broker; the refused scenarios assert the start failure's text and the termination-log file
- [ ] T022 [P] [US1] `CPT/TopicContractsFeatures.scala` (k3s, `ankka.cluster.tests`): the same feature on a cluster with Strimzi: declare through the control plane, deploy the shopping cart sample variant stating `order.v2`, assert `services get` shows `Failed` with the refusal as `detail`, then the matching variant is `Ready`; the listing's `checks` column

### Implementation

- [ ] T023 [US1] `API/descriptors.scala`: `TopicDeclarationRequest(partitions, compacted = false, contract: Option[ContractDeclaration(name, schema: JsonValue)])`, `ProjectTopic` gains `compacted`, `contract: Option[Contract]`, `checks: Vector[TopicCheck]`; `ProjectTopics.problems` extended; `ProjectTopics.MaxSchemaBytes = 65536`; codecs in `Wire`
- [ ] T024 [US1] `CP/domain/model.scala` `DeclaredTopic.compacted/contract`, `CP/domain/events.scala` `ProjectTopicDeclared` fields with defaults; `CP/application/ProjectEntity.scala` `declareTopic` persists them (no event when nothing changed); `CP/application/ProjectRows.scala` and `ProjectTopicsTrigger.scala` unchanged in shape but reviewed
- [ ] T025 [US1] `CP/deploy/AnkkaServiceClient.scala` `ProjectSchemaWriter { def putSchema(namespace, fingerprint, document): Unit; def getSchema(namespace, fingerprint): Option[Array[Byte]] }`, implemented in `CP/deploy/Fabric8AnkkaServiceClient.scala` on ConfigMap `ankka-project-schemas` (server-side apply, merge one key); `K/components/controlplane/controlplane-rbac.yaml` grants `configmaps` get/create/patch
- [ ] T026 [US1] `CP/api/ProjectEndpoint.scala`: `PUT …/topics/{name}` fingerprints with `Contract.fromSchema`, writes the schema first, then declares; `GET …/topics/{name}/schema` answers the document (404 when the topic has no contract); `GET …/topics` carries `compacted`, `contract`, `checks` (the checks from T030)
- [ ] T027 [US1] `CP/deploy/ProjectProjection.scala` and `CRD/AnkkaProject.scala`: `ProjectTopicEntry.compacted/contractName/contractFingerprint`; `K/components/crd/ankkaproject.yaml` schema; `CRDT/CrdSchemaSuite.scala` (operator) passes
- [ ] T028 [US1] `OP/Action.scala` `EnsureProjectConfig(configMap)`, `describe`; `OP/Executor.scala` execute (server-side apply); `OP/ProjectReconciler.scala` `actions` renders ConfigMap `ankka-project` with `topics.json` from the spec (`OP/ProjectConfigRendering.scala`); `OPT/ProjectRenderingSuite.scala` asserts the file's content for a spec with a contract, a compacted topic and none
- [ ] T029 [US1] `OP/Rendering.scala`: mount `ankka-project` read-only, `optional: true`, at `/var/run/ankka/project` on the platform container of every hosting but web, with `ANKKA_PROJECT_DECLARATIONS`; `OPT/RenderingSuite.scala` cases; goldens regenerated once and the diff reviewed
- [ ] T030 [US1] `RT/ProjectionRuntime.scala`: load `ProjectDeclarations` in `start`, add the contract comparison to `rejectUnsupported` and `rejectUnsupportedRemote` for every topic source and publication, text as in contracts/declarations.md; `RT/TopologyJson.scala` edges carry `contract` and `broker`
- [ ] T031 [US1] `RT/ProjectionSupport.scala` `publishAll`/`applyConsumer`: set `Metadata.CeType` to the publication's contract name when absent; `RT/Kafka.scala` `KafkaPublisher.apply` no longer forces `message`; `RTT/PublishAllSuite.scala` cases: with a contract, without, with a `GraphConsumer` (unchanged `ankka.graph-delta.v1`)
- [ ] T032 [US1] `CP/api/ServiceEndpoint.scala`: `withTopicChecks` (from the topology's edges and the project's declarations, `unchecked` when `startedAt < declaredAt`), `ServiceStatus.topicChecks`; `GET …/topics`' `checks` built from every service's topology (through `ServiceProjector`/`InstanceTopologies`); `API/descriptors.scala` `TopicCheck`
- [ ] T033 [US1] `CLI/Main.scala` `projects topics set --compacted --contract <name> --schema <file|->`, `projects topics schema get <name>`; `CLI/ControlPlaneClient.scala` `declareTopic(request)`, `topicSchema`; `CLI/Output.scala` `projectTopics` columns COMPACTED, CONTRACT, CHECKS and `service` prints `topic checks`; `CLIT/ProjectTopicsCommandSuite.scala`
- [ ] T034 [US1] `CON/client/schemas.ts` `projectTopicSchema` (compacted, contract, checks) and `serviceStatusSchema.topicChecks`; `CON/routes/project.tsx` declares with a contract and a schema file and lists them; `CON/routes/service.tsx` shows topic checks; `CON/testing/fake-control-plane.ts` serves the schema route; `console/e2e/` exercises `PUT/GET topics`, `GET …/schema` so `parity.ts` passes
- [ ] T035 [US1] `just docs-reference`: `docs/reference/cli.md`, `docs/reference/control-plane-api.md` (hand-written section for `GET /projects/{id}/topics/{name}/schema` and the changed `PUT`), `ControlPlaneFixturesSuite` fixtures; `docs/build/topics.md` gains "Contracts" (declaring, fetching, stating in four languages from tested samples, the refusal, `ce-type`); `docs/platform/broker.md:76-77` names the contract

**Checkpoint**: `F/topics/contracts.feature` green offline; `TopicContractsFeatures` green on k3s; SC-001 holds.

## Phase 4: User Story 2 — A declared topic has settings (P1)

**Goal**: `--compacted` makes a topic compacted on the installation's broker, new or existing.

**Independent test**: `F/broker/compaction.feature` through `BrokerCompactionFeatures` (k3s, Strimzi).

### Tests

- [ ] T036 [P] [US2] `OPT/ProjectRenderingSuite.scala` cases: a compacted entry renders `config: Some(Map("cleanup.policy" -> "compact"))`, an uncompacted one renders no `config` (the applied JSON has no `config` key); `OPT/strimzi/StrimziModelsSuite.scala` round-trips `config`
- [ ] T037 [P] [US2] `OPT/TopicProvisioningSuite.scala` cases: a topic observed uncompacted whose declaration is compacted is rendered again; the reverse; status carries `compacted`
- [ ] T038 [P] [US2] `CPT/BrokerCompactionFeatures.scala` (k3s): steps for `F/broker/compaction.feature`: read the `KafkaTopic`'s `spec.config` and the broker's `cleanup.policy` through Strimzi; publish three messages under one key, wait for compaction (`segment.ms`/`min.cleanable.dirty.ratio` set low on the test topic's config), read from the start

### Implementation

- [ ] T039 [US2] `OP/strimzi/KafkaTopicResource.scala` `KafkaTopicSpec(partitions, config: Option[Map[String, String]] = None)`; `OP/StrimziRendering.scala` `topic` sets it when compacted; `OP/Executor.scala` `observeTopics` reads `spec.config`, `OP/BrokerProvisioning.scala` `TopicState.compacted`; `OP/TopicProvisioning.scala` decides a re-apply on a compaction difference and reports `compacted` in `ProjectTopicStatus`
- [ ] T040 [US2] `CP/api/ProjectEndpoint.scala` `GET …/topics` and `CLI/Output.scala` show `compacted` from the status; `CON/routes/project.tsx` a "compacted" toggle on declaration and a column
- [ ] T041 [US2] `docs/platform/broker.md:72-84` "Topics" describes compaction; `docs/build/graph.md:728-745` says the project declares the delta topic compacted; `docs/reference/limitations.md:199-204` rewritten (partitions and compaction; no retention); `F/graph-deltas/documentation.feature:15` already says so (T062 runs it)

**Checkpoint**: `F/broker/compaction.feature` green on k3s.

## Phase 5: User Story 3 — The graph merge sink is ankka's (P1)

**Goal**: `Neo4jSink` in `ankka-graph-neo4j`, the `ankka-graph-sink` image, the fixtures ankka's own,
the graph documentation complete without ankka-flow.

**Independent test**: `F/graph-deltas/sink.feature` through `GraphSinkSuite` (offline: in-memory
broker, Neo4j container) and `GraphSinkFeatures` (k3s: the image in the shopping cart's project).

### Tests

- [ ] T042 [P] [US3] `GNT/Neo4jMergeSuite.scala`: carried from `../ankka-flow/sidecar/src/test/…/Neo4jMergeSuite.scala` with every assertion, rewritten against `Neo4jSink.apply(delta)` on a `Neo4jContainer(sys.props("ankka.neo4j.image"))`: the version table, placeholders, tombstones, labels replaced, two graphs from one topic compare equal
- [ ] T043 [P] [US3] `GNT/Neo4jSinkSuite.scala`: steps for `F/graph-deltas/sink.feature`'s offline scenarios over `AnkkaTestKit` with the in-memory broker and a Neo4j container: fills the store, newer-only, a refused delta fails the change and is redelivered (`InMemoryBroker.failNext`-style assertion on redelivery and `failing` on the topic source status), version 2 re-reads; the password never appears in the log capture
- [ ] T044 [P] [US3] `CORET/GraphFixturesSuite.scala`: writes `protocol/fixtures/graph-deltas/{keys,deltas,refused}.json` from the `sdk` builder under `-Dankka.fixtures.regenerate=on`, refuses a difference otherwise; the sink's suite (T043) applies every `deltas.json` row and reads back what `reads` says
- [ ] T045 [P] [US3] `CPT/GraphSinkFeatures.scala` (k3s): deploys Neo4j from `K/overlays/neo4j/neo4j.yaml` (carried from ankka-flow's), declares `cart-deltas` compacted, deploys the shopping cart and the sink image by its descriptor, asserts the store through the driver, redeploys at version 2 after emptying the store

### Implementation

- [ ] T046 [US3] `GN/Neo4jSettings.scala`, `GN/Neo4jSink.scala`: a `Consumer[GraphDelta]` over `fromTopic(topic, GraphDelta.serializer, Earliest, TopicOptions(parallel = settings.parallel))` with `version`; the four statements carried from `../ankka-flow/…/Neo4jMergeStage.scala` over one delta; delete markers as tombstones; `GraphRules` applied, a failure thrown with the key and rule; driver per instance, discarded on failure; `transactionTimeout`; redaction of the password from every message
- [ ] T047 [US3] `GS/Main.scala`: reads the variables in data-model.md, registers one `Neo4jSink`, `http = false`; `graph-sink/src/main/resources/` logback; the descriptor in contracts/graph-sink.md saved as `samples/shopping-cart/graph/cart-graph-sink.json` replacing `blueprint.conf`, `drive.sh` and `k8s/in-cluster.conf`; `samples/shopping-cart/graph/README.md` rewritten
- [ ] T048 [US3] `protocol/fixtures/graph-deltas/SOURCE.md` says the files are ankka's, written by `GraphFixturesSuite`; `.claude/rules/messaging.md` bullet on the fixtures rewritten
- [ ] T049 [US3] `docs/deploy/graph-sink.md` (new: the image, the descriptor as a validated `service.json` block, the project secret, `topic sources` and `failing`, rebuilding at a higher version, the component for a service of one's own); `docs/build/graph.md:18-21, 728-770` rewritten to end at the store; `docs/reference/limitations.md:184` rewritten; `mkdocs.yml` nav under "Run and deploy"; `tools/docs/skill/{ankka-views-consumers,ankka-deploy}/SKILL.md` `pages:`; `just docs-sync`
- [ ] T050 [US3] `CPT/GraphDocumentationFeatures.scala` (or the existing suite running `F/graph-deltas/documentation.feature`): the changed and the added scenario pass against the pages (`grep` for `flow.ankka.cloud` finds nothing under `docs/` but `contributing/documentation.md`)

**Checkpoint**: `sbt graphNeo4j/test` green with Neo4j in a container; `GraphSinkFeatures` green on k3s; SC-002 holds; the image builds with `sbt graphSink/docker:publishLocal`.

## Phase 6: User Story 4 — A topic on another broker (P2)

**Goal**: a project declares a broker with a certificate or SASL credential in a project secret; a
component names it for one topic; the credential reaches only the platform container.

**Independent test**: `F/topics/brokers.feature` through `DeclaredBrokersSuite` (a second Kafka
container under SASL/SCRAM in `TKT`) and `DeclaredBrokersFeatures` (k3s).

### Tests

- [ ] T051 [P] [US4] `APIT/ProjectBrokersSuite.scala`: name, bootstrap, shape and secret refusals; the "lacks `ca.crt`" message from recorded entries
- [ ] T052 [P] [US4] `CPT/EventCompatibilitySuite.scala`: pins for `ProjectBrokerDeclared` and `ProjectBrokerRemoved`
- [ ] T053 [P] [US4] `TKT/DeclaredBrokersSuite.scala`: `TlsKafka`-style second container with SASL/SCRAM-SHA-512 and a certificate listener; steps for the feature: a consumer reads from "legacy" and publishes to the in-memory or first broker; an undeclared name is refused at start with the text in contracts/project-brokers.md; the process-container scenario asserts the rendered env and mounts (`OPT/RenderingSuite.scala` case) rather than a running process
- [ ] T054 [P] [US4] `CPT/DeclaredBrokersFeatures.scala` (k3s): a second Strimzi listener with SCRAM on the test broker as the "outside" broker; declare it; the `intake` sample consumer bridges to the project's topic

### Implementation

- [ ] T055 [US4] `API/descriptors.scala` `BrokerDeclarationRequest(bootstrap, shape, secret)`, `ProjectBroker`, `ProjectBrokers.problems`, `BrokerCredentialShape`; `CP/domain/{model,events}.scala` `DeclaredBroker`, `ProjectBrokerDeclared/Removed`; `CP/application/ProjectEntity.scala` commands (the secret check reads `secrets(name).entries`; `removeSecret` is refused with `Conflict` while a declared broker names the secret, and `APIT`/`CPT` cases prove it); `CP/application/ProjectRows.scala` match; `CP/application/ProjectTopicsTrigger.scala` reacts to broker events; `CP/api/ProjectEndpoint.scala` routes `PUT/DELETE /{projectId}/brokers/{name}`, `GET /{projectId}/brokers`
- [ ] T056 [US4] `CRD/AnkkaProject.scala` `ProjectBrokerEntry` and `AnkkaProjectSpec.brokers`; `K/components/crd/ankkaproject.yaml`; `CRDT`/`OPT/CrdSchemaSuite.scala` assertion for `spec.brokers.items`; `CP/deploy/ProjectProjection.scala` renders them; `OP/ProjectConfigRendering.scala` writes them into `topics.json`
- [ ] T057 [US4] `OP/Rendering.scala`: `render` takes the project's broker entries as an input beside `databasePlan` (read by `ServiceReconciler` from the `AnkkaProject` informer, `Nil` when there is none), and for each renders a Secret volume mounted read-only at `/var/run/secrets/ankka/brokers/<name>` on the platform container and the three `ANKKA_TOPIC_BROKER_<NAME>_*` variables, so a broker declared or removed rolls every service of the project once; `ServiceReconciler` requeues a project's services on an `AnkkaProject` event; `modules/core/…/PlatformVariables.scala` `RuntimeOnlyPrefixes += "ANKKA_TOPIC_BROKER_"`; `OPT/RenderingSuite.scala` cases (platform container has them, process container has neither)
- [ ] T058 [US4] `RT/Kafka.scala`: `KafkaCredential` (`Certificate(directory)`, `Sasl(directory)` → `SASL_SSL`, mechanism, JAAS) replacing the SSL-only `KafkaTls.clientProperties` use; `KafkaConnection.named(env, name)`; `RT/ProjectionRuntime.scala` per-broker publisher/subscriber map, chosen by `TopicOptions.broker`/`Publication.broker`, the undeclared-name refusal; `RT/TopologyJson.scala` topic ids `topic:<broker>/<name>` for declared brokers; `CP/api/ServiceEndpoint.scala` `undeclaredTopics` skips them; `RTT/KafkaCredentialSuite.scala`
- [ ] T059 [US4] `CLI/Main.scala` `projects brokers set|unset|list`, `CLI/ControlPlaneClient.scala`, `CLI/Output.scala`; `CON/routes/project.tsx` brokers section, `CON/client/schemas.ts`, `CON/testing/fake-control-plane.ts`; `console/e2e/` exercises the three routes
- [ ] T060 [US4] `just docs-reference`; `docs/build/topics.md` "A topic on another broker" and `:484-488` rewritten; `docs/platform/secrets.md:87-88` says a declared broker mounts a project secret; `docs/platform/broker.md:69, 119-120`; `docs/reference/limitations.md:37-40` rewritten; `F/broker/supplied.feature:2-3` wording checked against the pages

**Checkpoint**: `F/topics/brokers.feature` green offline and on k3s; SC-003 holds.

## Phase 7: User Story 5 — A topic source reads its partitions in parallel (P2)

**Goal**: `parallel = true` handles an instance's partitions at once, each in order, committed
after the message's publications; a source that does not ask reads as today.

**Independent test**: `F/topics/parallelism.feature` through `TKT/KafkaSuite.scala` cases over a
4-partition topic.

### Tests

- [ ] T061 [P] [US5] `TKT/KafkaSuite.scala` cases, one per scenario: four partitions handled within two seconds by a one-second handler; ten keyed messages in order; a failing message on partition 2 holds only partition 2; a stop between the handler and the broker's acceptance redelivers; a non-parallel consumer handles the four one after another
- [ ] T062 [P] [US5] `SCT/ConformanceSuite.scala` case: a parallel remote consumer receives two `Consumer.Handle` calls concurrently and every SDK's server answers both (`ankka.conformance.shape`)

### Implementation

- [ ] T063 [US5] `RT/MessageSubscriber.scala` `TopicSubscription.parallel`; `RT/Kafka.scala` `KafkaSubscriber.subscribe` uses `Consumer.committablePartitionedSource` when set, one `mapAsync(1)` per partition sub-stream, merged into one `Committer.flow`, still under `RestartSource`; `RT/TopicHandlers.scala` a `ConsumerTopicHandler`/`ViewTopicHandler` per partition (the handler factory takes the partition); `InMemoryBroker` ignores the flag
- [ ] T064 [US5] `RT/ProjectionRuntime.scala` passes `options.parallel` through `subscribeTopic`, `startTopicView`, `startConsumer`, `startRemoteConsumer`; `RT/remote/RemoteProjection.scala` allows concurrent `handle` per partition
- [ ] T065 [US5] `docs/build/topics.md` "Reading partitions in parallel" (what is ordered, what is not, how to ask in four languages from tested samples); `docs/build/consumers.md` cross-reference

**Checkpoint**: `F/topics/parallelism.feature` green; SC-004 holds in the suite.

## Phase 8: User Story 6 — A process is sized by its descriptor, a stage needs no database (P3)

**Independent test**: `F/deploying/process-resources.feature` through `OPT/RenderingSuite.scala`
cases and a `TKT/KafkaSuite.scala` no-database case.

### Tests

- [ ] T066 [P] [US6] `OPT/RenderingSuite.scala` cases: a resource with `processCpuMillis = 1000, processMemoryMiB = 1024` renders the app container with those requests and limits; defaults render 100m/128Mi unchanged (goldens untouched); `database = "none"` renders no schema-init, no DB env, no DB certificates, `ANKKA_DATABASE=none`
- [ ] T067 [P] [US6] `TKT/KafkaSuite.scala` case: a consumer-only service with `ANKKA_DATABASE=none` and `ANKKA_DB_HOST` at a closed port becomes ready and reads its topic; `RTT/AnkkaServiceSuite.scala` case: registering an entity under `ANKKA_DATABASE=none` is refused at start with "this service declares no database"
- [ ] T068 [P] [US6] `APIT/DescriptorSuite.scala` cases: `resources.process` quantities parsed and bounded (`cpu ≤ 8`, `memory ≤ 16Gi`), `database: "none"` accepted, `database: "none"` with `ANKKA_DB_*` env refused; `CPT/EventCompatibilitySuite.scala` descriptor-evolution pin

### Implementation

- [ ] T069 [US6] `API/descriptors.scala` `ServiceResources.process: Option[ProcessResources(cpu, memory)]`, `ServiceSpec.database: Option[String]`, problems; `CP/deploy/ServiceProjection.scala` projects `processCpuMillis`, `processMemoryMiB`, `database`; `CRD/AnkkaService.scala` fields with defaults; `K/components/crd/ankkaservice.yaml`; `CrdSchemaSuite` passes
- [ ] T070 [US6] `OP/Rendering.scala` process branch uses the sizes; `OP/Provisioning.scala` `decide` returns `NotNeeded` for `database == "none"`, `reportedPhase` and `LifecycleRules.databaseStatus` adjusted; `ServiceReconciler` skips the CNPG observation; `ANKKA_DATABASE=none` on the platform container
- [ ] T071 [US6] `RT/Ankka.scala`: `ANKKA_DATABASE=none` refuses entities, views, workflows and timed actions through `StartRefusal`, uses `SecretStore.unavailable`, constructs no `Database()`; `RT/ProjectionRuntime.scala:96`, `RT/TimerRuntime.scala:44`, `SC/ClientLogic.scala:114` make `Database()` lazy
- [ ] T072 [US6] `docs/reference/service-descriptor.md:348-381` Resources table gains `process`, a `database` row; `docs/deploy/scaling-and-rollouts.md:55-56` rewritten; `just docs-reference`

**Checkpoint**: `F/deploying/process-resources.feature` green; goldens unchanged.

## Phase 9: User Story 7 — A service says how far behind each topic source is (P3)

**Independent test**: `F/topics/status.feature`'s two new scenarios through `TKT/KafkaSuite.scala`
(lag against a real group) and the console's Playwright suite (the same JSON shown).

### Tests

- [ ] T073 [P] [US7] `TKT/KafkaSuite.scala` case: 100 messages, a handler that stops after 40, `lag == 60` on the topic source status within one poll; `RTT/TopicSourcesSuite.scala` cases for `failing` set and cleared
- [ ] T074 [P] [US7] `CPT/ServiceEndpointSuite.scala` case: `topicSources` assembled from two instances' `/observability/service` documents (lags summed, `failing` first non-null); `console/package` Playwright case: the service page shows the table from the fake's JSON; `CLIT/mcp/AnkkaToolsSuite.scala` case: `get_service` returns `topicSources` and `topicChecks` from a mock control plane

### Implementation

- [ ] T075 [US7] `RT/MessageSubscriber.scala` `lag(subscription)`; `RT/Kafka.scala` `KafkaSubscriber.lag` with a raw consumer (`endOffsets` − `committed` under the group); `InMemoryBroker.lag`; `RT/TopicSources.scala` `broker`, `contract` (from the declared source), `lag`, `failing`, a 30 s poll started by `ProjectionRuntime`; `RT/TopicHandlers.scala` sets and clears `failing`, and a subscription whose broker cannot be reached sets `failing` to the broker's name and the connection error on every source of that broker (a `TKT/DeclaredBrokersSuite.scala` case with the second container stopped); `RT/ObservabilityRoute.scala` series `ankka_topic_source_lag`; `RT/ObservabilityDocuments.scala` fields
- [ ] T076 [US7] `CP/deploy/InstanceTopologies.scala` fetches `/observability/service` too; `API/descriptors.scala` `TopicSourceReport`, `ServiceStatus.topicSources`; `CP/api/ServiceEndpoint.scala` assembles it; `CLI/Output.scala` `topic sources` lines; `CLI/mcp/AnkkaTools.scala` `get_service` description; `CON/routes/service.tsx` table, `CON/client/schemas.ts`, the fake
- [ ] T077 [US7] `docs/build/topics.md:368-380` "What a service says about its topic sources" rewritten around `services get`; `docs/reference/control-plane-api.md` service status section; `.claude/rules/messaging.md:35-37` updated

**Checkpoint**: `F/topics/status.feature` green; `services get` shows lag.

## Phase 10: Polish and the retirement's prerequisites

- [ ] T078 `docs/reference/limitations.md`: every sentence R18 names rewritten; `docs/build/graph.md` and `docs/platform/broker.md` reread whole; `grep -ri "ankka-flow\|flow.ankka.cloud" docs/` finds only `contributing/documentation.md` (SC-005)
- [ ] T079 [P] `.claude/rules/build-and-release.md`: eleven modules, the image list corrected with `ankka-graph-sink`; `.claude/rules/kubernetes.md`: the project ConfigMaps, declared-broker mounts and `database: none` noted in the broker section; `.claude/rules/messaging.md`: contracts, declared brokers, parallel partitions, lag
- [ ] T080 [P] `GLOSSARY.md`: settle the five proposed terms (`contract`, `schema`, `declared broker`, `lag`, `ankka-flow`) by removing `*Proposed.*` once the features pass; `just features` clean
- [ ] T081 `sbt -Dankka.cluster.tests=off buildAll`, then the four k3s features suites under `caffeinate`, then each SDK's `conformance`; `just docs`; `just test-console`
- [ ] T082 Prepare the retirement in ankka-flow (its own repository, not this branch): a note naming this feature's release as the successor; no change here

## Dependencies

- Phase 1 → Phase 2 → every story. Within Phase 2, T005–T009, T011, T012 and T014–T016 are
  parallel; T010 needs T007 and T009; T013 needs T012; T017 needs T010 and T014–T016.
- US1 (Phase 3) needs all of Phase 2. US2 (Phase 4) needs T027–T028 (the entry fields and the
  project config) from US1. US3 (Phase 5) needs US2 for the k3s proof (a compacted topic) and
  US5 for `parallel`, but its offline suite needs only Phase 2. US4 (Phase 6) needs T028–T030.
  US5 (Phase 7) needs only Phase 2. US6 (Phase 8) needs only Phase 1. US7 (Phase 9) needs T030
  (edges) and US5's `TopicSubscription` change.
- Polish needs every story.

## Parallel execution

- After Phase 2: US5 (T061–T065) and US6 (T066–T072) can run beside US1, since they touch the
  Kafka subscriber, the handlers, the operator's process branch and the provisioning decision,
  none of which US1 changes.
- Within US1: T018–T022 together; then T023–T024 → T025–T026, while T027–T029 (CRD and operator)
  and T030–T031 (runtime) proceed in parallel; T032–T035 last.
- Within US3: T042–T045 together; T046 → T047; T048–T050 beside T047.
- Within US4: T051–T054 together; T055–T056 → T057 and T058 in parallel → T059–T060.

## Implementation strategy

MVP is US1: a contract declared, stated, checked and shown. It is the idea ankka lacked and the
one every other story leans on for its plumbing (the project ConfigMap, the declarations file,
the edges). Then US2 and US3 together, because the sink's cluster proof needs a compacted topic
and the graph story is what retiring ankka-flow visibly breaks. US5 next, since the sink wants it
and it is self-contained in the runtime. US4, US6 and US7 in that order, each shippable alone.
