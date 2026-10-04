# Tasks: Managed Broker — Topics Provisioned, Secured and Injected the Way Databases Are

**Input**: Design documents from `/specs/027-managed-broker/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature. The scenarios are in `features/broker/`; where a task says
"case", it means a `test(...)` in the named suite, named for the scenario it holds.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a process-hosted service with a topic is proved on a cluster), US2 (a service
  declares a topic and the platform provides it), US3 (a project is the boundary, enforced by the
  broker), US4 (nothing is destroyed), US5 (the local installation has a broker)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `RT` =
`modules/runtime/src/main/scala/…/runtime`; `TKT` = `modules/testkit/src/test/scala/…/testkit`;
`SCT` = `sidecar/src/test/scala/…/sidecar`; `OP`/`OPT` = `operator/src/{main,test}/scala/…/operator`;
`CRD`/`CRDT` = `crd/src/{main,test}/scala/…/crd`; `API`/`APIT` =
`controlplane-api/src/{main,test}/scala/…/controlplane/api`; `CP`/`CPT` =
`controlplane/src/{main,test}/scala/…/controlplane`; `CLI`/`CLIT` = `cli/src/{main,test}/scala/…/cli`;
`K` = `kustomization`; `F` = `features/broker`.

Offline runs always pass `-Dankka.cluster.tests=off`. A k3s run is `caffeinate -i sbt 'set
controlPlane / Test / logBuffered := false' …`, so a scenario reports as it ends.

---

## Phase 1: Setup

**Purpose**: the branch is where the work can be done.

- [X] T001 Bring `main` onto the branch: `git fetch origin && git rebase origin/main`. If feature 024 (pull request #62) is merged, note in `specs/027-managed-broker/research.md` R17 the group id form it shipped and that T008's group prefix matches it; if it is not, note that T028, T033 and T037's reading scenarios are blocked until it is. Verify: `sbt -Dankka.cluster.tests=off compile` is clean.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: the gate the plan rests on, the types every story shares, and a broker in the k3s
suites.

**⚠️ CRITICAL**: T002 is a gate. If it fails, take research R10's fallback and amend R10 before
anything in Phases 4 to 7 is built.

- [X] T002 The gate (research R10). Add `testPki % Test` to `testkit` in `build.sbt`. Write `RT/KafkaTls.scala`: a class implementing Kafka's `org.apache.kafka.common.security.auth.SslEngineFactory` over a `RotatingTls` of a directory named in the client's config (`ankka.tls.directory`), client engines with endpoint identification `HTTPS`, no keystore or truststore of Kafka's. Write `TKT/KafkaTlsSpike.scala`, behind `-Dankka.spikes=on`: an `apache/kafka` container with one TLS listener requiring client certificates, its certificate and trust from `TestPki`; assert (a) a consumer and producer configured with `security.protocol=SSL` and `ssl.engine.factory.class` connect and round-trip a record, (b) a client whose directory holds another authority's certificate is refused, (c) after the directory's certificate is replaced with a newly issued one, the next new connection presents it (read the serial the broker saw, or revoke trust in the old). Record the outcome in `research.md` R10. Verify: `sbt -Dankka.spikes=on 'testkit/testOnly *KafkaTlsSpike'`.
- [X] T003 [P] `OP/BrokerNames.scala`: `user(project, service)`, `topic(project, name)`, `topicPrefix(project)`, `groupPrefix(project, service)` exactly as `data-model.md` states. `OPT/BrokerNamesSuite.scala`: each form; a hyphenated project and service cannot collide (`a-b`/`c` against `a`/`b-c`); the group prefix is 024's `ankka.<project>.<service>.`. Verify: `sbt 'operator/testOnly *BrokerNamesSuite'`.
- [X] T004 [P] `OP/Settings.scala`: `BrokerSettings(bootstrap, namespace, cluster)` and `Settings.broker: Option[BrokerSettings]` from `ANKKA_BROKER_BOOTSTRAP`, `ANKKA_BROKER_NAMESPACE`, `ANKKA_BROKER_CLUSTER` (and matching system properties, as every setting has); none is `None`, all three is `Some`, one or two throws naming the missing ones. Cases in `OPT/SettingsSuite.scala` for each. Verify: `sbt 'operator/testOnly *SettingsSuite'`.
- [X] T005 [P] `OP/strimzi/StrimziDefinitions.scala`, `KafkaTopicResource.scala`, `KafkaUserResource.scala`: fabric8 `CustomResource`s for `kafka.strimzi.io/v1` `KafkaTopic` (spec `partitions`; status `conditions`, `topicName`) and `KafkaUser` (spec `authentication.type`, `authorization.type`, `authorization.acls` with `resource{type,name,patternType}` and `operations`; status `conditions`, `username`), modelled as `OP/cnpg/` models CNPG's. `OPT/strimzi/StrimziModelsSuite.scala`: each round-trips the exact YAML of `contracts/operator.md`; a resource with no status parses; a condition `Ready=False, reason=NotSupported, message=Decreasing partitions not supported` is read with its reason and message. Verify: `sbt 'operator/testOnly *StrimziModelsSuite'`.
- [X] T006 [P] The resource (data-model.md §Resource). `CRD/AnkkaService.scala`: `TopicEntry(name, partitions)`, `AnkkaServiceSpec.topics: List[TopicEntry] = Nil`, `provisionBroker: Boolean = true`, `BrokerStatus(phase, topics, recovered, detail)`, `AnkkaServiceStatus.broker: Option[BrokerStatus] = None`, and `sameReport` comparing it. `K/components/crd/ankkaservice.yaml`: declare all three, `status.broker.phase` an enum of the five phases. Cases in `CRDT/AnkkaServiceCodecSuite.scala`: round trips; absent fields decode to their defaults and are omitted when written. Verify: `sbt 'crd/test' 'operator/testOnly *CrdSchemaSuite'`.
- [X] T007 The broker component (contract `installation.md`, research R3, R19): `K/components/broker/` — `namespace.yaml` (`ankka-broker`); `strimzi/kustomization.yaml`, a nested plain Kustomization over Strimzi 1.2.0's cluster operator pinned by URL with its own `namespace:` transformer and the RoleBinding subjects patched by hand where the transformer does not reach (as `K/components/keycloak-operator/manifests` does); `certificate.yaml` (`ankka-broker`, URI `ankka://platform/broker`, DNS names of `ankka-kafka-bootstrap` and `*.ankka-kafka-brokers` in the namespace, ClusterIssuer `ankka-service`, `duration: 8760h`, `renewBefore: 720h`); `kafka.yaml` (`KafkaNodePool` `dual`, one node, both roles, a persistent claim; `Kafka` `ankka` with the one `tls` listener of R3, `authorization: simple`, `auto.create.topics.enable: false`, replication settings of 1, heap `-Xms256m -Xmx512m`, memory requests and limits for Kafka and both entity operator containers, topic and user operators); `network-policy.yaml` (9093 from pods of ankka workloads in any ankka namespace and from the namespace itself); `operator-role.yaml` (`Role` and `RoleBinding` for the `ankka-operator` ServiceAccount: `kafkatopics`, `kafkausers`: get, list, watch, create, patch); and a patch setting the three `ANKKA_BROKER_*` variables on the operator's container, by the container's real name. Not yet named by any overlay. Verify: `kubectl kustomize` of a scratch overlay naming `pki`, `operator` and `broker` renders; the operator Deployment has one container carrying the three variables.
- [X] T008 `OPT/BrokerStack.scala`, beside `PkiStack`: `install(k3s, k8s, repoRoot)` applies the component's Strimzi part server-side with the node's `kubectl` and waits for `strimzi-cluster-operator`, then applies the rest with the node pool's storage replaced by `ephemeral`, and waits for `kafka/ankka` to be `Ready` with `kubectl … -o jsonpath` on the node; it returns the `BrokerSettings` a suite gives its operator. Assumes `PkiStack.install` has run. Add build hints for nothing: both images are pulled by the node. Verify: compiled and exercised by T028.

**Checkpoint**: the gate has passed, the shared types compile, and a suite can stand up the
installation's broker.

---

## Phase 3: User Story 1 — A process-hosted service with a topic is proved on a cluster (Priority: P1) 🎯 MVP

**Goal**: what `main` already does is held by a suite: a process-hosted service whose descriptor
names a broker becomes ready and publishes.

**Independent Test**: `caffeinate -i sbt 'sidecar/testOnly *SidecarClusterSuite -- "*broker its descriptor names*"'`

- [ ] T009 [P] [US1] `OPT/PlainKafka.scala`: `install(k3s, namespace)` applies one `apache/kafka` pod (KRaft, one plaintext listener advertised as its Service's cluster DNS name) and a Service, waits for it to be ready, and returns its bootstrap address; `read(k3s, namespace, topic, max)` runs the pod's own console consumer from the beginning and returns the values. No Strimzi. Verify: compiled and exercised by T010.
- [ ] T010 [US1] Two cases in `SCT/SidecarClusterSuite.scala`, named for `F/supplied.feature`'s first two scenarios: deploy the Python sample (`sample-shopping-cart-python`) with `ANKKA_KAFKA_BOOTSTRAP_SERVERS` in its descriptor naming `PlainKafka`'s address; assert the pod is ready with both containers; add an item to a cart through the service and assert `PlainKafka.read` returns what the sample's graph consumer published to `cart-graph`. Break it once to see it fail: remove `ANKKA_KAFKA_` from `PlatformVariables.SharedPrefixes` locally and watch the first case time out, then restore. Verify: the Independent Test.
- [ ] T011 [P] [US1] A case in `OPT/ProcessHostingRenderingSuite.scala` named for `F/supplied.feature`'s third scenario, "a broker variable is given to both programs of a service hosted as a process", asserting the variable on the sidecar container and on the app container of the rendered Deployment (the existing assertion, given the scenario's name). Verify: `sbt -Dankka.cluster.tests=off 'operator/testOnly *ProcessHostingRenderingSuite'`.

**Checkpoint**: FR-001 holds and can be merged alone.

---

## Phase 4: User Story 2 — A service declares a topic and the platform provides it (Priority: P1)

**Goal**: a descriptor's `topics` become topics on the installation's broker under the project's
name; every service with a runtime is told where the broker is and is known to it by its
certificate; the status says how far that has got.

**Independent Test**: offline, `quickstart.md` §"Offline"; on a cluster,
`controlPlane/testOnly *BrokerClusterFeatures -- "*topics.feature*"`.

### Tests for User Story 2 (written first; each fails until its implementation task)

- [ ] T012 [P] [US2] `APIT/TopicsDescriptorSuite.scala`: one case per row of `contracts/descriptor.md` §"Refusals from the descriptor's own rules", asserting the exact message; every problem reported at once for a descriptor with three; a descriptor with no `topics` writes no `topics` key; `suppliesBroker` is decided by name, so a `secretKeyRef` counts. Verify: fails to compile until T017.
- [X] T013 [P] [US2] `OPT/BrokerProvisioningSuite.scala`: one case per row of research R8's table, in its order, each with the reported phase; a `Ready=False` with any other reason is `Waiting`; `recovered` true only when the user and every declared topic predate the resource and at least one topic is declared; the decision is the same when asked twice. Include cases named for `F/topics.feature`'s outline rows and `F/installation.feature`'s "no broker" outline rows. Verify: fails to compile until T020.
- [X] T014 [P] [US2] `OPT/BrokerRenderingSuite.scala`: one case per row of `contracts/operator.md` §"What is rendered for whom", asserting the objects, never a string's presence: the `KafkaUser` equals the contract's YAML (exactly two rules, no `Create`); a `KafkaTopic` per declared topic with `partitions` and no `replicas`; neither carries an owner reference and both are in the broker's namespace; the three variables on the one container of an embedded and a wasm service and on both of a process-hosted one; the service `Certificate`'s `commonName` is `<project>.<service>` with a broker and absent without; no volume, mount, Secret or `Certificate` is added; no action that removes a topic or a user exists for any plan. Include cases named for `F/topics.feature`'s "told where the installation's broker is", "both programs…", "declares no topic…" and "a web-hosted service is given nothing…". Verify: fails to compile until T021 and T022.
- [X] T015 [P] [US2] `TKT/KafkaConnectionSuite.scala`, on the Kafka container `KafkaSuite` uses: `ProjectionRuntime.fromEnv` reads the three variables of research R9 into a `KafkaConnection`, and only the bootstrap address gives today's behaviour; with a prefix, a publish to `transactions` lands on `money.transactions` and a subscription to `transactions` reads it, while the component's declared name is what the topology shows; no connection is opened to the broker until the first publish (assert with a bootstrap address nothing listens on and a service that starts cleanly); a publish to a topic that does not exist, on a broker with auto-creation off, fails the delivery, logs a warning naming the topic, and succeeds once the topic is created. Verify: fails to compile until T023.
- [ ] T016 [P] [US2] `CPT/BrokerDescriptorFeature.scala`: a `GherkinSuite` over `../features/broker/descriptor.feature` against the fast harness, each descriptor applied through the CLI's `Main.run` and as a raw request, both refusals carrying the same words (the shape of `CPT/DescriptorFeatures.scala`); the two-services and partitions scenarios drive real applies in one project. Verify: fails until T017 and T019.

### Implementation for User Story 2

- [ ] T017 [US2] `API/descriptors.scala`: `TopicDeclaration`, `ServiceSpec.topics`, its rules in `ServiceSpec.problems` with the contract's messages, `suppliesBroker`, `ServiceStatus.broker: Option[String]` and `topics: Vector[String]`, and their codecs in `Wire`. Every `service.json` block in `docs/` still validates. Verify: `sbt -Dankka.cluster.tests=off 'controlPlaneApi/test'` with T012 green.
- [ ] T018 [US2] `CP/deploy/ServiceProjection.scala`: `topics` and `provisionBroker = !isWebHosted && !suppliesBroker` onto the resource. `CP/deploy/StatusIngest.scala`, `CP/domain/events.scala`, `CP/domain/model.scala`, `CP/application/ServiceRows.scala`: the observation, `ServiceObserved` and `Service` gain `broker` and `topics` with defaults; `toStatus` gives the phrases of `contracts/descriptor.md`; the row carries the declared topics with their partitions. Cases in `CPT/ServiceProjectionSuite.scala` (by name, so a `secretKeyRef` counts; web is never provisioned), `CPT/ServiceEntitySuite.scala` (each phrase) and `CPT/EventCompatibilitySuite.scala` (a `ServiceObserved` written before this feature replays). Verify: those three suites.
- [ ] T019 [US2] `CP/api/ServiceEndpoint.scala`: on apply, refuse a topic another service of the project declares with different partitions, naming that service, and refuse fewer partitions than the service's own applied descriptor, both as conflicts with the contract's messages and before anything is recorded; the first reads the services listing. Cases in `CPT/ControlPlaneHttpSuite.scala`, each retrying on the listing where a write precedes it. Verify: `sbt -Dankka.cluster.tests=off 'controlPlane/testOnly *ControlPlaneHttpSuite *BrokerDescriptorFeature'` with T016 green.
- [X] T020 [US2] `OP/BrokerProvisioning.scala`: `BrokerPlan` with `reportedPhase`, `BrokerObservation`, `StrimziObjectState`, `TopicState` and `decide(spec, broker, observed)` per research R8. Verify: T013 green.
- [X] T021 [US2] `OP/StrimziRendering.scala`: `user(spec, broker)` and `topic(spec, entry, broker)` from `BrokerNames`, labelled managed-by ankka and `strimzi.io/cluster`, no owner reference. `OP/Action.scala`: `EnsureKafkaUser`, `EnsureKafkaTopic` with `describe`. `OP/Executor.scala`: both applied server-side as `EnsureDatabase` is; `observeBroker(namespace, cluster, user, topics)` reading each resource's `Ready` condition, reason, message, `spec.partitions` and creation time into a `BrokerObservation`, a 404 on the resource type read as absent. Verify: compiles; exercised by T014 and T028.
- [X] T022 [US2] `OP/Rendering.scala`: `brokerActions(plan)` (the user and each topic for `Waiting` and `Ready`, never a topic with fewer partitions than observed, nothing for the other plans) and the three variables of research R9 on the containers `PlatformVariables.shared` already selects. `OP/ZeroTrust.scala`: `serviceCertificate` takes whether there is a broker and sets `commonName` then. `OP/LifecycleRules.scala`: `brokerStatus(plan, topics)`, and the service's `detail` carrying a failed broker's reason without changing its lifecycle. `OP/ServiceReconciler.scala`: observe, decide, render, report, with no Strimzi read at all for `NotNeeded` and `Supplied`. `RenderingGoldenSuite` and `RenderingUnchangedSuite` pass with no fixture rewritten; add one golden case with a broker. Verify: `sbt -Dankka.cluster.tests=off operator/test` with T014 green.
- [X] T023 [US2] `RT/Kafka.scala`: `KafkaConnection(bootstrapServers, tlsDirectory, topicPrefix)`; the prefix applied in `KafkaPublisher.publish` and `KafkaSubscriber.subscribe` and nowhere else; the producer made on first publish; a failed publish logged at warn with the declared and the qualified name; consumers given `metadata.max.age.ms` of 30 seconds; with a TLS directory, both clients configured with T002's engine factory. `RT/ProjectionRuntime.scala`: `fromEnv` reads the three variables. Verify: T015 green; `sbt 'testkit/testOnly *KafkaSuite *TopicSourceSuite'` unchanged.
- [ ] T024 [P] [US2] `CLI/Output.scala`: a `broker` line and a `topics` block after `database` in `ankka services get`, omitted when empty; the same in `CLI/mcp/AnkkaTools.scala`'s text. Cases in `CLIT/OutputSuite.scala`. Verify: `sbt -Dankka.cluster.tests=off 'cli/testOnly *OutputSuite'`.
- [ ] T025 [P] [US2] The console: `broker` and `topics` in `console/package/src/client/schemas.ts`, shown on `console/package/src/routes/service.tsx`, in `console/package/fixtures/control-plane/ServiceStatus.json` and in `src/testing/fake-control-plane.ts`. Verify: `sbt -Dankka.cluster.tests=off 'controlPlaneApi/testOnly *ControlPlaneFixturesSuite'` and `just test-console`.
- [ ] T026 [US2] The shopping cart sample gains a view that reads the topic its checkout notices are published to (`CART_CHECKOUTS_TOPIC` names another, so a second deployment can read a first's), registered where they are (only when the environment names a broker): `samples/shopping-cart/src/main/scala/shoppingcart/application/CheckoutsSeen.scala`, a route to read it (`shoppingcart/api/CheckoutsSeenEndpoint.scala`), its registration in `samples/shopping-cart/src/main/scala/Main.scala`, and a scenario in the sample's own features with the sample's glossary. It is what a cluster suite reads to show a service consuming a provisioned topic. Verify: `sbt -Dankka.cluster.tests=off shoppingCart/test`.
- [ ] T027 [P] [US2] `CPT/BrokerProbe.scala`: runs a pod of the broker's own image in a project's namespace mounting a named service's `-service-tls` Secret, and with it publishes to, reads from (under a named group) and lists topics on the installation's broker by their full names, returning what the broker's tools printed. It is "something holding the credential of" a service. Verify: compiled and exercised by T029.
- [ ] T028 [US2] `CPT/BrokerClusterFeatures.scala`: a `GherkinSuite` over `../features/broker/topics.feature` on k3s — `PkiStack`, CNPG, `BrokerStack` (T008), the operator in-process with the broker's settings, the control plane and CLI as `WebHostingClusterFeatures` runs them. Steps deploy the cart sample under the scenario's service names with `topics` declared; assert the `KafkaTopic` and its partitions with the node's `kubectl`; read what was published with `BrokerProbe` (T027); read the reader service's view through `InPod.curl`. A plain case beside the scenarios installs a service before the operator is given a broker, gives it one, and asserts the service is rolled once, its certificate reissued with the common name, and no request refused meanwhile (research R9). Needs feature 024 for the view's group. Verify: `caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *BrokerClusterFeatures'`.

**Checkpoint**: a declared topic exists on the broker for its project, a service publishes and
another reads, and `ankka services get` says `provisioned`.

---

## Phase 5: User Story 3 — A project is the boundary, enforced by the broker (Priority: P1)

**Goal**: a service's certificate reaches its own project's topics and nothing else, shown by the
broker's own refusal.

**Independent Test**: `controlPlane/testOnly *BrokerClusterFeatures -- "*isolation*"`

- [ ] T029 [US3] `CPT/BrokerClusterFeatures.scala` also runs `../features/broker/isolation.feature`: a service of `casino` reads nothing of `money`'s topic and its log names the topic `casino` has not declared; the probe holding `lobby`'s certificate is refused `money.transactions` to read and to publish, with the broker's authorization error asserted; the probe's listing shows only `casino`'s topics, and a group that is not the service's is refused. Verify: the Independent Test; then prove it can fail by widening the rendered topic prefix to the empty string locally and watching the refusal cases go red.
- [ ] T030 [P] [US3] Cases named for `F/supplied.feature`'s fourth scenario in `OPT/BrokerProvisioningSuite.scala` and `OPT/BrokerRenderingSuite.scala` (a descriptor that gives a broker variable is `Supplied`, no user is rendered, its own variables reach both programs) and a cluster step in `CPT/BrokerClusterFeatures.scala` asserting no `KafkaUser` exists for it. Verify: both offline suites; the cluster suite.
- [ ] T031 [P] [US3] A case in `CPT/BrokerClusterFeatures.scala` minting a token for the operator's own ServiceAccount under the component's `Role`, asserting the API server allows it to create and patch a `KafkaTopic` and refuses it a delete of a `KafkaTopic` and of a `KafkaUser`. Verify: the cluster suite.

**Checkpoint**: isolation is the broker's, proved with real certificates.

---

## Phase 6: User Story 4 — Nothing is destroyed, and a re-applied service recovers its topic (Priority: P2)

**Goal**: a deleted service's topic, what was published to it and its user remain; the same name
applied again reports recovered.

**Independent Test**: `controlPlane/testOnly *BrokerClusterFeatures -- "*kept*"`

- [ ] T032 [P] [US4] Cases in `OPT/BrokerRenderingSuite.scala` and `OPT/BrokerProvisioningSuite.scala` named for `F/kept.feature`: no rendered broker object has an owner reference; `Action` has no case that removes a `KafkaTopic` or `KafkaUser`; a user and topic made before the resource give `Recovered`. Verify: both suites.
- [ ] T033 [US4] `CPT/BrokerClusterFeatures.scala` also runs `../features/broker/kept.feature`: publish, delete the service, assert the `KafkaTopic` and `KafkaUser` remain and the probe still reads what was published; apply again and assert `recovered existing topics`; the reader view shows each published thing once after its service is deleted and applied again (needs 024's group ids); delete the project and assert its topics remain. Verify: the Independent Test.

**Checkpoint**: the database's rule holds for the broker.

---

## Phase 7: User Story 5 — The local installation has a broker (Priority: P2)

**Goal**: the local platform comes up with a broker; the example cloud overlay carries the same
component with its sizing left to whoever installs it.

**Independent Test**: `just up`, then `quickstart.md` §"Story 5".

- [ ] T034 [US5] Enable the component in `K/overlays/local/kustomization.yaml` (after `operator`, so its patch lands) with a 2Gi claim. `K/deploy-local.sh`: apply the component's Strimzi part server-side and wait for its operator before the overlay, beside the CNPG step and with the same comment's reasoning; wait for `kafka/ankka` to be `Ready` before the closing smoke test; nothing else, so `kubectl apply -k` of the overlay is still the whole deploy once the CRDs exist. Verify: `kubectl kustomize K/overlays/local` renders the broker and the operator's three settings.
- [ ] T035 [P] [US5] Enable the component in `K/overlays/cloud/kustomization.yaml` with node count, storage size, heap and memory bounds as placeholders marked `SET`. Cases in `CPT/RemoteOverlaySuite.scala`: both overlays render the same `Kafka` listener and authorization; each `SET` placeholder is present in the cloud overlay; the operator Deployment has one container and the three settings once each; the broker's `Certificate` names an issuer that exists. `CPT/ReservedProjectIdsSuite.scala` passes with `ankka://platform/broker` among the identities it reads. Verify: `sbt -Dankka.cluster.tests=off 'controlPlane/testOnly *RemoteOverlaySuite *ReservedProjectIdsSuite'`.
- [ ] T036 [P] [US5] Cases named for `F/installation.feature`'s "an installation with no broker" outline in `OPT/BrokerRenderingSuite.scala` (no topic: rendered exactly as before, compared against the golden record) and `OPT/BrokerProvisioningSuite.scala` (a topic: `Failed`, "the installation has no broker"). Verify: both suites.
- [ ] T037 [US5] `CPT/BrokerClusterFeatures.scala` also runs `../features/broker/installation.feature`'s cluster scenarios: the cart sample with a declared topic and no broker variable is ready and what it publishes is read; a pod in a namespace that is not ankka's cannot connect to the broker's port (the connection is refused or times out, asserted from that pod); the broker's certificate carries `ankka://platform/broker`. Verify: `controlPlane/testOnly *BrokerClusterFeatures`, whole.
- [ ] T038 [US5] Run `quickstart.md` §"Story 5" on a fresh kind cluster with nothing done by hand beyond its lines; record in `research.md` R1 the resident memory of the broker's pods with the component's bounds applied. Verify: `ankka services get cart` shows `broker  provisioned` and `shop.cart-graph`.

**Checkpoint**: a new local platform has a working broker.

---

## Phase 8: Polish & cross-cutting

- [ ] T039 [P] Documentation (FR-010). New page `docs/platform/broker.md` (what the component installs, the listener and why the broker's certificate is long-lived, how a service is known, removing a topic by hand, the standalone-operators route for a Kafka the installation keeps), in `mkdocs.yml`'s `nav` and in `tools/docs/skill/ankka-platform/SKILL.md`'s `pages:`. Update `docs/build/topics.md` (declaring, the project-qualified name, a topic nobody declared, the escape hatch; drop "the platform provides no broker"), `docs/reference/service-descriptor.md` (`topics`, every refusal of `contracts/descriptor.md`), `docs/reference/configuration.md` (the three runtime variables, the operator's three settings), `docs/reference/limitations.md` (one broker per installation; no cross-project grant; nothing is removed; one node locally), `docs/platform/networking.md` (the broker's port and policy, the common name), `docs/platform/install-cloud.md` (the component, its `SET` values, Strimzi's CRDs in the ordered steps, that its images come from quay.io), `docs/deploy/upgrading.md` (installing the broker rolls every service once), `docs/reference/glossary.md` (broker, partition, declared topic, broker variable). Each page stands alone, with no feature number. Run `just docs-sync` and `just docs-reference`. Verify: `just docs` and `sbt -Dankka.cluster.tests=off 'controlPlaneApi/testOnly *DocumentationDescriptorsSuite'`.
- [ ] T040 [P] `CLAUDE.md`: the broker in "Architecture" beside the database's provisioning, the commands for its suites, and the traps this feature found, each as a property of the system — among them that Kafka knows a TLS client by its certificate's subject alone, that Strimzi replaces the broker when its listener certificate changes, and that a custom listener trusts an authority by its certificate where Strimzi's own client authority wants the key. `README.md`: one line where databases are described.
- [ ] T041 Run everything (`quickstart.md` §"The checks that gate a merge"): format check; the offline tests; `caffeinate -i sbt test` once; `.github/features-check.sh` with nothing in `features/broker` or this spec; `just docs`; `just test-console`. Then re-read `spec.md`'s FR-001 to FR-011 and SC-001 to SC-004 against the suites and note any with no test in `specs/027-managed-broker/research.md`.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (T001)**: first.
- **Foundational (T002–T008)**: T002 gates Phases 4 to 7. T003–T006 are independent of each other.
  T008 needs T007.
- **US1 (T009–T011)**: needs only T001. It can be built, and merged, before anything else.
- **US2 (T012–T028)**: needs Phase 2. T017 before T018 and T019; T020, T021 before T022; T023 needs
  T002; T028 needs T008, T018, T022, T023, T026, T027 and, for its reading scenarios, feature 024.
- **US3 (T029–T031)**: needs US2's rendering (T021, T022) and T028's suite.
- **US4 (T032–T033)**: needs US2 and its suite (T028).
- **US5 (T034–T038)**: T034 and T035 need T007; T037 needs T028's suite; T038 needs T034.
- **Polish (T039–T041)**: after the stories it describes.

### Within a story

Tests first (T012–T016 before T017–T023), then descriptor to resource (T017, T018, T019), the
operator (T020, T021, T022), the runtime (T023), what a member reads (T024, T025), and the cluster
suite last.

### Parallel opportunities

- Phase 2: T003, T004, T005 and T006 together; T007 beside them.
- US1: T009 and T011 together.
- US2: all five test tasks (T012–T016) together; T024 and T025 beside the operator work; T026
  beside anything.
- US3: T030 and T031 are different cases; T029 after T028.
- US5: T035 and T036 together.
- Polish: T039 and T040 together.

## Parallel example: User Story 2's tests

```text
Task: "TopicsDescriptorSuite — every refusal of contracts/descriptor.md"            (T012)
Task: "BrokerProvisioningSuite — research R8's table, rule by rule"                 (T013)
Task: "BrokerRenderingSuite — contracts/operator.md's table, object by object"      (T014)
Task: "KafkaConnectionSuite — variables, prefix, lazy producer, a missing topic"    (T015)
Task: "BrokerDescriptorFeature — descriptor.feature through the CLI and raw"        (T016)
```

## Implementation strategy

**MVP: User Story 1 alone** (T001, T009–T011). It changes no behaviour and closes the gap that let
a real defect ship unseen; it merges by itself.

Then, incrementally:

1. The gate and the foundation (T002–T008).
2. User Story 2 offline (T012–T027): a descriptor's topics reach the resource, the operator
   decides and renders, the runtime connects — all without a cluster.
3. User Story 2 on k3s (T028), then User Stories 3 and 4 in the same suite (T029–T033).
4. User Story 5 (T034–T038): the local platform and the example overlay.
5. Documentation and the full run (T039–T041).

Feature 024 must be on the branch before the scenarios in which a view reads a provisioned topic
(parts of T028, T033, T037) can pass; everything else is independent of it.
