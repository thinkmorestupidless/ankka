# Tasks: Replayable Topic Sources — Groups of Their Own, a Chosen Start, and Rebuild on Version Change

**Input**: Design documents from `/specs/024-replayable-topics/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" claims each become a test
before the code that relies on them. Where a task says "case", it means a `test(...)` in the named
suite (or its equivalent in the SDK's test runner). A case that proves a scenario of `features/`
is named with that scenario's name, so the two can be found from each other.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (two services reading one topic each see every message), US2 (a topic source
  declares where it starts), US3 (a changed view is rebuilt from the topic), US4 (the limits are
  documented)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `SDK` =
`modules/sdk/src/main/scala/…/sdk`; `RT`/`RTT` = `modules/runtime/src/{main,test}/scala/…/runtime`;
`TK`/`TKT` = `modules/testkit/src/{main,test}/scala/…/testkit`; `SC`/`SCT` =
`sidecar/src/{main,test}/scala/…/sidecar`; `CONF` = `SCT/conformance`; `CPA`/`CPAT` =
`controlplane-api/src/{main,test}/scala/…/controlplane/api`; `CLIT` = `cli/src/test/scala/…/cli`;
`PROTO` = `protocol/src/main/protobuf/ankka/protocol/v1`; `PY` = `sdks/python`; `TS` =
`sdks/typescript`; `RS` = `sdks/rust`; `FEAT` = `features/topics`; `DOCS` = `docs`. "R*n*" is a
section of `research.md`; "T*n*" and "L*n*" are a rule and a log line of
`contracts/topic-sources.md`; a contract is named by its file under `contracts/`.

The branch `024-replayable-topics` exists, in the worktree
`.claude/worktrees/024-replayable-topics`. `main`'s own working tree carries changes that are not
this feature's; work in the worktree. The feature files, the glossary, the spec and the plan
documents are written and uncommitted there.

---

## Phase 1: Setup — the build accepts what is already written

**Purpose**: `features/` and `GLOSSARY.md` are new at the repository's root, and CI refuses a
tracked file no job claims.

- [X] T001 Add `features/**` and `GLOSSARY.md` to the `unchecked` filter of `.github/workflows/ci.yml`, beside `specs/**`, each with its reason as a trailing comment (living scenarios and their vocabulary; held by the bdd checker, which no job runs yet). Run `python3 .github/ci-coverage.py` and confirm it claims both and that no pattern is left matching nothing.
- [X] T002 Record the baseline the feature must not disturb: run `sbt 'testkit/testOnly *TopicSourceSuite *KafkaSuite *PeerSuite' 'sidecar/testOnly *RemoteProjectionSuite *ProtocolSuite' 'runtime/testOnly *MetricsSuite'` and note in the pull request description which cases exist and that they pass, so a later failure in one of them is known to be this feature's.

**Checkpoint**: CI's coverage check passes with the new files present; the suites this feature changes are known green.

---

## Phase 2: Foundational — a service knows who it is

**Purpose**: `ServiceIdentity`, which every group name, log line and metric series is built from.

**⚠️ CRITICAL**: blocks every user story. It changes no behaviour: nothing reads the identity
until US1.

- [X] T003 Write `RTT/ServiceIdentitySuite.scala`, pure, failing: the three shapes of `data-model.md` and that a project without a service cannot be built; `ServiceIdentity.resolve` in `local` mode reads `ankka.service.name`, treats empty as none stated, and fails naming the value for a name that breaks `[a-z]([-a-z0-9]{0,61}[a-z0-9])?` (cases: a dot, upper case, 64 characters); in `kubernetes` mode it reads project and service from a certificate carrying `ankka://shop/orders` (build the certificate as `RotatingTlsSuite` builds its RSA fixtures, with the URI SAN the operator's `ZeroTrust.identityUri` renders) and **ignores** `ankka.service.name`; in `kubernetes` mode with no certificate identity it answers a `Left` whose sentence names the certificate directory; a certificate naming the project `local` is a `Left` too, and `ServiceIdentity.deployed("local", "orders")` cannot be built (R18). This is R1's "verify first".
- [X] T004 Add `ankka.service.name = ""` and `ankka.service.name = ${?ANKKA_SERVICE_NAME}` to `modules/runtime/src/main/resources/reference.conf`, commented as `data-model.md` "Configuration" says: for a local run, not read in `kubernetes` mode.
- [X] T005 Create `RT/ServiceIdentity.scala` per `contracts/group-names.md` "Where the identity comes from": the value, `local(name)`, `private[ankka] deployed(project, service)`, `unnamed`, and `resolve(config, tls: Option[RotatingTls]): Either[String, ServiceIdentity]`, as `data-model.md` "ServiceIdentity" says. T003 passes.
- [X] T006 In `RT/Ankka.scala`, resolve the identity once in `ServiceBuilder.start` (after the cluster config is loaded and the TLS material, where there is any, is read) and carry the `Either` on `AnkkaService` as `identity`; add a `private[ankka]` builder method that sets it outright, for tests. A `Left` is not refused here: only a service that has a topic source refuses it (T014), and a service with none must start exactly as it does today.
- [X] T007 In `TK/AnkkaTestKit.scala`, add `identity: ServiceIdentity = ServiceIdentity.unnamed` to both `start` overloads and to `startPeer`, passed through `hostService`, per `contracts/scala-api.md` "The test kit". Add one case to `TKT/ServiceRegistrationSuite.scala` or a new `TKT/IdentitySuite.scala` that a kit started with `ServiceIdentity.local("orders")` reports it from `testKit.service.identity`.

**Checkpoint**: `sbt 'runtime/testOnly *ServiceIdentitySuite' testkit/test` is green; no group name has changed.

---

## Phase 3: User Story 1 — Two services reading one topic each see every message (Priority: P1)

**Goal**: a topic source's consumer group is named for its service, so two services on one broker
with a view of the same id each hold every message.

**Independent Test**: `sbt 'testkit/testOnly *KafkaSuite'` — two services with distinct identities
and one view id over one topic each hold a hundred rows, and the broker lists two groups.

**Scenarios**: all ten of `FEAT/groups.feature`.

### Tests for User Story 1 (write first; they fail)

- [X] T008 [P] [US1] Write `RTT/ConsumerGroupsSuite.scala`, pure: the six cells of the table in `contracts/group-names.md` (scenarios "a deployed service's group is named for its project, its service, its kind and its id", "a local service that states its name has a group named for it", "a local service that states no name has a group named for its kind and id alone"); version 1 and no version give one name; **distinct inputs give distinct names**, over generated component ids that include `.`, `-`, `_` and the strings `v2`, `view`, `consumer`, `v2.summary`, `v2-summary` as ids and as parts of ids, at versions 1, 2 and 10, across all three identity shapes (a deployed project is any permitted project id, which `local` is not); every group of `deployed("shop","orders")` starts `ankka.shop.orders.` and none of `deployed("shop","orders2")` or `deployed("shop-orders","x")` does; the longest name (project 57, service 63, component id 128, `Int.MaxValue`) is 277 characters and every character is in `[a-zA-Z0-9._-]`.
- [X] T009 [P] [US1] In `CPAT/DescriptorSuite.scala`, add a case beside "the platform's cluster variables are refused by name": a descriptor that sets `ANKKA_SERVICE_NAME`, as a literal and as a secret, is refused with a message saying a deployed service's name comes from the platform and the variable is for a local run; `ANKKA_SERVICE_NAMES` is not refused. And for R18, scenario "no project is named "local"": in the same suite, `ProjectId.problems("local")` refuses it with a sentence saying the name is reserved for local runs, and `locale` and `local-shop` are accepted; in `operator/src/test/scala/…/operator/RenderingSuite.scala`, beside the case for `platform`, `Names.namespaceProblems` refuses a resource in project `local`; in both, the sentence gives `local`'s own reason and not `platform`'s ("reserved for the platform's own workloads" is untrue of it); `controlplane/src/test/scala/…/controlplane/ReservedProjectIdsSuite.scala` still holds the two sets equal (run it: it fails if only one was changed).
- [X] T010 [P] [US1] In `CLIT/PolyglotTemplateSuite.scala` (the assertions shared by the Python, TypeScript and Rust template suites) assert the rendered `docker-compose.yml` sets `ANKKA_SERVICE_NAME` to the project's name on both the `sidecar` and the `runtime` service; in `CLIT/TemplateSuite.scala` assert the expanded Scala project's `src/main/resources/application.conf` sets `ankka.service.name` to the project's name with no surviving `$`. Scenario: "a project made from a template states its service's name".
- [X] T011 [US1] In `TKT/KafkaSuite.scala`, create the topic the new cases use with **three partitions** through an `AdminClient` before any service starts, and add cases, each reading group names and assignments from the broker with the `AdminClient` and never from ankka's log: (a) two `AnkkaTestKit` services, `deployed("shop","orders")` and `deployed("shop","billing")`, each with a view of one component id over the topic; publish 100 messages with distinct subjects; each view holds 100 rows, and the broker lists `ankka.shop.orders.view.<id>` and `ankka.shop.billing.view.<id>` (scenario "two services reading one topic through views of the same id each hold every message"); (b) the same with `local("orders")` and `local("billing")` (scenario "two named local services reading one topic each hold every message"); (c) one service and a `startPeer`: the view holds 100 rows and `describeConsumerGroups` shows each of the three partitions assigned to exactly one member (scenario "the instances of one service read each partition of a topic once between them"); (d) a subscription under the 277-character name of T008 is accepted (scenario "the longest permitted ids make a group the broker accepts"); (e) commit offsets under `ankka-view-<id>` with a plain consumer, then start a `deployed` service: its view reads from the earliest message under the qualified group, and `listConsumerGroupOffsets("ankka-view-<id>")` is unchanged (scenario "an upgraded service starts again under its new group"). Case (a) is the one that fails today: each view holds about half.
- [X] T012 [P] [US1] In `TKT/TopicSourceSuite.scala`, add a case that a service whose identity is a `Left` (set through the kit's `private[ankka]` seam, as `kubernetes` mode with no certificate identity gives) and that has a topic-sourced view fails to start with one message naming the view and the certificate, and a case that the same service with no topic source starts.

### Implementation for User Story 1

- [X] T013 [US1] Create `RT/ConsumerGroups.scala`: `name(identity, kind, componentId, version)` per `contracts/group-names.md`, `private[ankka]`, pure. T008 passes.
- [X] T014 [US1] In `RT/ProjectionRuntime.scala`, replace `processName` as the group in the four topic branches (`startView`, `startConsumer`, `startRemoteView`, `startRemoteConsumer`) with `ConsumerGroups.name(service.identity, kind, componentId, version = 1)`; leave `processName` for the entity branches' `daemon` and `ProjectionId` exactly as it is. Extend `rejectUnsupported` and `rejectUnsupportedRemote` to refuse a topic source when the identity is a `Left`, with its sentence. T011 (a) to (e) and T012 pass.
- [X] T015 [P] [US1] In `CPA/descriptors.scala`, refuse `ANKKA_SERVICE_NAME` in `ServiceSpec.problems` with its own message (it is not in `PlatformEnvVars`: the platform does not set it); add `"local"` to `ProjectId.Reserved` there and to `Names.ReservedProjectIds` in `operator/src/main/scala/…/operator/Names.scala`, with the reason beside each, and make the refusal's sentence depend on which id it is. T009 passes.
- [X] T016 [P] [US1] Add `ANKKA_SERVICE_NAME: {{name}}` to the `environment` of the `sidecar` and `runtime` services in `cli/src/main/templates/common/docker-compose.yml`, and `ankka.service.name = "$name$"` to `ankka.g8/src/main/g8/src/main/resources/application.conf` under a comment saying what it names. Run `sbt -Dankka.template.tests=python,typescript,rust 'cli/testOnly *TemplateSuite'` and `sbt -Dankka.template.tests=scala 'cli/testOnly *TemplateSuite'`; T010 passes, and each run says it ran the languages asked for.
- [X] T017 [US1] Run `sbt 'testkit/testOnly *TopicSourceSuite *KafkaSuite *PeerSuite *ProduceAllEntitySourceSuite *GraphVersionsSuite' 'sidecar/testOnly *RemoteProjectionSuite *ConformanceSuite'`: every case that passed in T002 passes. Break `ConsumerGroups.name` to ignore the service once and confirm T011 (a) goes red.

**Checkpoint**: US1 is complete and mergeable. It is **not** releasable alone: the rename restarts every group, and until US2 a consumer cannot say where from. The subscriber's interface is unchanged, no protocol field is added, and the only behaviour that differs is the group's name.

---

## Phase 4: User Story 2 — A topic source declares where it starts (Priority: P1)

**Goal**: `earliest`, `latest` or a time, applied the first time a group is assigned a partition;
a consumer over a topic must declare one; in Scala, Python, TypeScript and Rust.

**Independent Test**: over a topic holding fifty messages with the last thirty after a known time,
views at `earliest`, `latest` and that time hold fifty, none and thirty rows; ten more are
published and they hold sixty, ten and forty.

**Scenarios**: all twelve of `FEAT/start-position.feature`.

**Depends on**: US1 (groups have their names).

### Tests for User Story 2 (write first; they fail)

- [X] T018 [P] [US2] Write `RTT/TopicSourceRulesSuite.scala`, pure, for T1 to T3 over both registries' descriptors: a Scala consumer over a topic with no start position is refused naming it; the same consumer with one is not; a view with none is not; a discovered consumer with none is refused when its SDK states 1.4 and **not** refused when it states 1.3; a start position on a discovered component that reads an entity is refused; a discovered start position that names nothing is refused. Each message is the sentence of `contracts/protocol.md`'s table. Scenario: "a consumer reading a topic must declare its start position".
- [X] T019 [P] [US2] Write `RTT/SubscriberContract.scala`, a trait of cases over a `MessageSubscriber` with a `MessagePublisher`, for the four obligations of `contracts/subscriber.md`: one group one delivery, every group a delivery; a group resumes after `stop()` and a new subscription; a start position of `Earliest`, `Latest` and `At` each begin where the contract's table says, and are not applied to a group that has been assigned; a failed handler's message is delivered again; `earliestRetained` answers for every partition, `None` for an empty one. Write `RTT/InMemoryBrokerSuite.scala` mixing it in, plus the broker's own: backlog is delivered before what is published meanwhile, and the clock a test sets decides `At`.
- [X] T020 [US2] In `TKT/TopicComponents.scala`, add what the cases need: a view and a consumer over one topic whose start position is a constructor argument of the companion, the consumer recording what it is delivered. In `TKT/TopicSourceSuite.scala`, add one case for each scenario of `FEAT/start-position.feature` that a single partition can show, named as the scenario: earliest holds every retained message; latest holds only what is published after; a time holds everything since; a time goes on reading; no start position starts at the earliest; a restarted view reads what was published while it was stopped (use `restartService()`); a restarted view is not delivered what it has already read (assert on the handler's deliveries, not on the row count, which a re-read would leave unchanged); earlier than every retained message starts at the earliest; later than now starts at the latest. And one case for `FEAT/status.feature` "a topic source says in the log how it subscribed": for the view and for the consumer, the captured log holds one L1 line naming the kind, the component id, the topic, the group `ConsumerGroups.name` gives, the start position and version 1.
- [X] T021 [US2] In `TKT/KafkaSuite.scala`, mix in `SubscriberContract` against `KafkaSubscriber`, and add the cases only a broker can show, on the three-partition topic: fifty messages with the last thirty published after a recorded instant, then views at `Earliest`, `Latest` and `At(instant)` hold 50, 0 and 30, and after ten more 60, 10 and 40; **a `Latest` view that has read nothing is stopped, ten messages are published, it is restarted, and it holds ten** (R3's "verify first": without the commit at assignment it holds none); after any of these, `listConsumerGroupOffsets` shows a committed offset for all three partitions of each group.
- [X] T022 [P] [US2] In `SCT/ProtocolSuite.scala`: `Discovery.ProtocolVersion` is `"1.4"`; a discovered view and consumer with `start_from` named and with `at_millis` become `RemoteSource.Topic` carrying `StartFrom.Earliest`, `Latest` and `At`; `Discovery.validate` reports each refusal of `contracts/protocol.md` with the service's other problems; a 1.3 `Spec` with a consumer over a topic and no start position validates clean. In `SCT/RemoteProjectionSuite.scala`, the scenario "a consumer whose service cannot declare a start position starts at the earliest message": a `ProcessDouble` stating 1.3, with a consumer over a topic that already holds messages, is delivered every one of them, and L5 is in the captured log. In `CPAT/CompatibilitySuite.scala` and `CPAT/HostingSuite.scala`, move the pinned protocol to 1.4, and add the case R11 asks to be verified first: a descriptor declaring protocol 1.4 against a platform at 1.3 is refused before anything is projected. If no existing rule refuses it, stop and say so: a module has no other guard.
- [X] T023 [P] [US2] In `CONF/ConformanceSuite.scala`, add `topic.view-starts-earliest` (the view `topic-rows` holds a row for each message `ConformanceTarget` published to `conformance-topic` before the service started) and `topic.consumer-starts-latest` (the consumer `topic-recorder` has recorded none of those, and records one published after); add `topic-rows` and `topic-recorder` to `ConformanceReference.ComponentIds`. Scenario: "a start position is honoured in every language a service is written in". They fail for every target until T031 to T034.

### Implementation for User Story 2

- [X] T024 [US2] In `SDK/ChangeSource.scala`: `enum StartFrom`, `Topic.startFrom: Option[StartFrom] = None`, the three-argument `fromTopic`, and `describe` saying the start position, per `contracts/scala-api.md`.
- [X] T025 [US2] Create `RT/TopicSourceRules.scala` with T1 to T3 and call it from `ComponentRegistry.validate` in `RT/Ankka.scala` for in-process and remote descriptors. In `RT/remote/RemoteDescriptors.scala`, give `RemoteSource.Topic` a `startFrom: Option[StartFrom]` and the two remote descriptors what T1 needs to know the SDK's protocol version. T018 passes.
- [X] T026 [US2] In `RT/MessageSubscriber.scala`: the interface of `contracts/subscriber.md` (`TopicSubscription`, `Subscribed`, `earliestRetained`), removing the three-argument `subscribe`; and `InMemoryBroker`'s positions per group, one delivery per group, backlog before live, positions kept across `stop()`, a settable clock and `earliestRetained`. T019 passes. This is R5's "verify first": run `sbt 'testkit/testOnly *PeerSuite *TopicSourceSuite *ProduceAllEntitySourceSuite *GraphVersionsSuite' 'runtime/testOnly *PublishAllSuite' 'sidecar/testOnly *RemoteProjectionSuite *ConformanceSuite'` and fix any case that relied on two subscribers of one group each receiving a message, by giving it what it meant, not by loosening the broker.
- [X] T027 [US2] In `RT/Kafka.scala`: the `PartitionAssignmentHandler` that resolves and commits a start position for each assigned partition with no commit (`contracts/subscriber.md` "Kafka"); `earliestRetained` with a groupless consumer, polled until every non-empty partition has answered or a deadline passes; `Subscribed` handing back the subscription's kill switch; `auto.offset.reset` left `earliest` with its comment rewritten to say what it is now for. T021 passes.
- [X] T028 [US2] In `RT/ProjectionRuntime.scala`: build a `TopicSubscription` for each of the four topic branches, a view that declares no start position taking `Earliest`; keep each `Subscribed`; log L1 for each. T020 passes.
- [X] T029 [US2] Protocol 1.4, all of it in one change so that US3 adds no second bump: in `PROTO/discovery.proto` add `StartFrom`, `Source.start_from = 3`, `ViewDetail.version = 4` and `ConsumerDetail.version = 3`, each marked `// 1.4`, per `contracts/protocol.md`; set 1.4 in `protocol/README.md`, `CPA/Compatibility.scala`, `RT/remote/Conversation.scala` (`WireProtocol.Version`) and the changelog in `SC/Discovery.scala`'s scaladoc; add the sentence to `protocol/WASM-ABI.md`; copy `protocol/` into the three SDKs (`cd sdks/python && uv run python scripts/proto.py`, `cd sdks/typescript && npm run proto`, `sdks/rust/scripts/proto.sh`) and confirm `diff -r` against each copy reports nothing, as CI does.
- [X] T030 [US2] In `SC/Discovery.scala`: map `start_from` into `RemoteSource.Topic`, carry `Spec.protocol_version` to the rules, call `TopicSourceRules.problems` from `validate`, and log L5 for a discovered consumer the rules exempt. T022 passes.
- [X] T031 [P] [US2] Scala conformance reference: in `CONF/ConformanceReference.scala` add `topic-rows` (a view over `conformance-topic`, no start position) and `topic-recorder` (a consumer over it, `StartFrom.Latest`, recording what it is delivered the way `checkout-recorder` does); in `CONF/ConformanceTarget.scala` publish the pre-start messages to `conformance-topic` on the target's `InMemoryBroker` before the service starts. T023 passes for the Scala target.
- [X] T032 [P] [US2] Python: create `PY/src/ankka/start_from.py` (`StartFrom.EARLIEST`, `LATEST`, `at(datetime)` refusing a naive datetime) and export it; in `PY/src/ankka/{view,consumer,graph}.py` add `start_from`, write it into `Source` in `_source_pb`, and check T1 to T3 in `__init_subclass__`; in `PY/src/ankka/service.py` set `PROTOCOL_VERSION = "1.4"` and refuse to serve when a component declares a start position and the sidecar's `SidecarInfo.protocol_version` is below 1.4, naming the component and both versions. Tests in a new `PY/tests/test_topic_sources.py`: each declaration's discovery bytes, each refusal, the naive datetime, and the old-sidecar refusal against a fake sidecar that reports 1.3 — asserting no handler was served before the refusal, which is R11's other "verify first" (the same assertion in T033). Add `topic-rows` and `topic-recorder` to `PY/examples/shopping_cart/conformance.py`. `uv run pytest -q && uv run mypy && uv run conformance` green, the conformance run naming the two `topic.` cases among those it ran.
- [X] T033 [P] [US2] TypeScript: create `TS/src/startFrom.ts` (frozen values, no `enum`) and export it from `TS/src/index.ts`; add `startFrom` to `ViewClass`, `ConsumerClass` and the graph consumer's class type in `TS/src/{view,consumer,graph}.ts`; collect T1 to T3 in `sourceOf` in `TS/src/service.ts`; write the field in `sourceInit` and set `PROTOCOL_VERSION = "1.4"` in `TS/src/spec.ts`; refuse an older sidecar as Python does. Tests in a new `TS/test/topic-sources.test.ts`. Add the two components to `TS/examples/shopping-cart/conformance.ts`. `npm run typecheck && npm test && npm run conformance` green on the two `topic.` cases.
- [X] T034 [P] [US2] Rust: create `RS/ankka/src/start_from.rs` (`StartFrom::{Earliest, Latest, At(SystemTime)}`, `at_millis`) and add it to `RS/ankka/src/prelude.rs`; add the provided method `start_from()` to `View`, `Consumer` and `GraphConsumer` in `RS/ankka/src/components/{view,consumer}.rs` and `RS/ankka/src/graph.rs`, written into discovery beside `Source::to_proto`; check T1 to T3 in each `problems()`; set `PROTOCOL_VERSION` to `"1.4"` in `RS/ankka/src/service.rs`. Cases in `RS/ankka/tests/registration.rs` and `RS/ankka/tests/kinds.rs`. Add the two components to `RS/examples/shopping-cart/src/conformance.rs`. `cargo test --workspace && ./conformance.sh` green, **both** runs of the script (read each run's first line: one says stateless, the other stateful).
- [X] T035 [US2] Run the whole of US2's proof: `sbt 'runtime/testOnly *TopicSourceRulesSuite *InMemoryBrokerSuite' 'testkit/testOnly *TopicSourceSuite *KafkaSuite' 'sidecar/testOnly *ProtocolSuite *ConformanceSuite -- *topic.*'`. Confirm the conformance filter matched (a filter that matches nothing reports the suite ignored and exits green). Remove the `commitSync` from the assignment handler once and confirm T021's restart case goes red.

**Checkpoint**: a start position is declared in four languages and honoured on a real broker; a consumer over a topic that does not say where it starts does not start. The protocol is 1.4 and already carries the version fields US3 reads. US1 and US2 together are the smallest thing that may be released, and only if US3 follows in the same release.

---

## Phase 5: User Story 3 — A changed view is rebuilt from the topic (Priority: P2)

**Goal**: a view declared at a higher version than the one recorded is emptied and read again from
its start position under a new group; an instance at a lower version stops writing; a consumer's
version changes its group; and the service says what it is doing.

**Independent Test**: a view at version 1 over a topic with fifty messages is restarted at version
2 with a handler that writes a different row; the table holds fifty rows of the new shape and none
of the old, and the broker shows a second group with the first group's offsets untouched.

**Scenarios**: all eleven of `FEAT/versions.feature` and all four of `FEAT/status.feature`.

**Depends on**: US2 (the subscriber's interface, the start position, protocol 1.4).

### Tests for User Story 3 (write first; they fail)

- [X] T036 [P] [US3] In `RTT/TopicSourceRulesSuite.scala`, add T4 and T5: `Some(2)` and `Some(1)` on a view and on a consumer that read an entity are each refused naming the component and what it reads; `Some(0)` and `Some(-1)` on a topic-sourced view are refused; the same four for discovered components. Scenarios: "a version on a view or consumer that reads an entity is refused", "a version that is not a positive whole number is refused".
- [X] T037 [US3] Write `TKT/ViewVersionSuite.scala` (Postgres and `InMemoryBroker`; the view's row carries the version of the handler that wrote it, which is what "written at version N" is asserted on), one case for each of these scenarios, named as the scenario: "a view at a higher version is rebuilt from every retained message" (no version-1 row, fifty version-2 rows, the group is `…view-v2.<id>`, the version-1 group's position is unchanged, `ankka_view_versions` says 2); "a view restarted at the same version is not rebuilt" (nothing delivered again); "a view rebuilt from the latest message is empty until a message is published"; "a view with no recorded version is taken to be at version 1" (delete the row from `ankka_view_versions`, restart at version 1, nothing delivered again, the row is back at 1); "a consumer at a higher version is delivered every retained message again"; "a service rolled back to a lower version leaves the view as it is" (fifty version-2 rows, no subscription, the service ready, L3 in the captured log); "a rebuild says how far back the broker retains before it removes a row" (L2 names the view, both versions and the timestamp, and is logged before the table is empty).
- [X] T038 [US3] In `TKT/ViewVersionSuite.scala`, the cases a single service cannot show, driving `ViewTopicHandler`s and `ViewVersions` directly against one database and one broker: "during a rolling update the higher version rebuilds the view once and the lower stops writing" (a version-1 handler subscribed; a version-2 start beside it; ten messages published; one truncation, the version-1 subscription stopped with its last message uncommitted, no version-1 row); "instances starting together at a higher version rebuild the view once" (two concurrent version-2 starts: one L2 followed by a truncation, one L4, fifty rows); **the race** — a version-1 writer in a loop against a rebuild to version 2, repeated at least two hundred times, never leaves a version-1 row (R7's "verify first"); and R9 — with a subscriber whose `earliestRetained` fails twice and then answers, the table still holds its version-1 rows and `ankka_view_versions` still says 1 until the third attempt.
- [X] T039 [US3] In `TKT/KafkaSuite.scala`: a view restarted at version 2 holds only version-2 rows, one for each retained message; the broker lists `…view-v2.<id>` beside `…view.<id>`; `listConsumerGroupOffsets` for the version-1 group is identical before and after; L2's timestamps are those of the partitions' first records, read back with a plain consumer. A consumer restarted at version 2 is delivered every retained message again under `…consumer-v2.<id>`.
- [X] T040 [P] [US3] In `RTT/MetricsSuite.scala`: `Metrics.render` writes both headers and no series for a service with no topic source; one `ankka_topic_source_info` series for each topic source with the labels of `contracts/topic-sources.md`; `ankka_topic_source_behind` is `0` for a view at its recorded version and `1`, with `declared` and `recorded`, for one behind. Scenarios: "a service's metrics list each topic source", "a view behind its recorded version is shown as behind in the metrics", "a service with no topic source lists none in its metrics". In `TKT/ConsoleEndpointSuite.scala`, `GET /observability/service` answers `topicSources` with the same facts.
- [X] T041 [P] [US3] In `SCT/ProtocolSuite.scala`: `ViewDetail.version` and `ConsumerDetail.version` reach `RemoteViewDescriptor.version` and `RemoteConsumerDescriptor.version`, absent as `None`; `Discovery.validate` reports T4 and T5. In `SCT/RemoteProjectionSuite.scala`: against the `ProcessDouble`, a remote view discovered at version 1 and then, after a restart, at version 2 is rebuilt, and a remote view's write is refused when the recorded version is higher than its own.
- [X] T042 [P] [US3] In `CONF/ConformanceSuite.scala`, add `topic.version-names-the-group`: `topic-rows`, now declared at version 2 in every reference, reads under a group whose kind segment is `view-v2` (read from the target's broker). It fails for every target until T050 to T053.

### Implementation for User Story 3

- [X] T043 [US3] In `SDK/View.scala` and `SDK/Consumer.scala`: `def version: Option[Int] = None` on each companion, carried to `ViewDescriptor.version` and `ConsumerDescriptor.version`; in `RT/remote/RemoteDescriptors.scala` the same field on the two remote descriptors, and in `SC/Discovery.scala` their mapping. Add T4 and T5 to `RT/TopicSourceRules.scala`. T036 and T041's first half pass.
- [X] T044 [US3] In `RT/Database.scala`, add a call that runs fragments in one transaction and returns the rows the last one affected, beside `executeAllInTransaction`.
- [X] T045 [US3] Create `RT/ViewVersions.scala` per `data-model.md` and `contracts/topic-sources.md`: the table's DDL; `ensure(componentId)` inserting version 1 where there is no row; `recorded(componentId)`; the lock key (`LockClass`, `hashtext` of the component id); `rebuild(table, componentId, declared)` as one transaction — exclusive lock, re-read, and only if still lower `TRUNCATE` and record — answering whether it truncated; `guardedUpsert` and `guardedDelete` as one transaction — shared lock, then the write `WHERE EXISTS` the recorded version equals the writer's — answering whether it wrote.
- [X] T046 [US3] In `RT/ProjectionRuntime.scala`: create `ankka_view_versions` in the transaction that creates the view tables, and `ensure` a row for each topic-sourced view there, only when there is one; replace the view branches' subscription with the start sequence of `contracts/topic-sources.md` "A view" — compare, then subscribe, rebuild (ask `earliestRetained` with backoff until it answers, log L2, `rebuild`, log L4 if it did not truncate, subscribe) or mark behind and log L3 — run off the start thread so a broker that is down does not hold up the service's start; pass the declared version to `ConsumerGroups.name` for views and consumers.
- [X] T047 [US3] In `RT/TopicHandlers.scala` and the remote view's topic handler in `RT/remote/RemoteProjection.scala`: write through `ViewVersions.guardedUpsert` and `guardedDelete`; when one answers that it did not write, stop the `Subscribed`, fail the message so its offset is not committed, log L3 and mark the source behind. One function for both handlers. T037, T038, T039 and T041 pass. Remove the shared lock once and confirm T038's race goes red.
- [X] T048 [US3] Create `RT/TopicSources.scala`: the `TopicSourceStatus` list of `data-model.md` as an extension the way the recorder is one, filled by `ProjectionRuntime` as each source subscribes, rebuilds or falls behind. Write the two series in `Metrics.render` in `RT/ObservabilityRoute.scala` from it, never through the recorder's name table, and `topicSources` in `RT/ObservabilityEndpoint.scala`'s `service` answer. Complete L1 with the version. T040 passes.
- [X] T049 [US3] Scala conformance reference: declare `topic-rows` at `Some(2)` in `CONF/ConformanceReference.scala` and give `ConformanceTarget` a way to read the groups its broker has seen. T042 passes for the Scala target.
- [X] T050 [P] [US3] Python: `version: ClassVar[int | None] = None` on the three classes in `PY/src/ankka/{view,consumer,graph}.py`, written into `ViewDetail` and `ConsumerDetail`, T4 and T5 checked in `__init_subclass__`, and the old-sidecar refusal extended to a declared version; cases in `PY/tests/test_topic_sources.py`; `topic-rows` at version 2 in `PY/examples/shopping_cart/conformance.py`. `uv run pytest -q && uv run mypy && uv run conformance` green.
- [X] T051 [P] [US3] TypeScript: `readonly version?: number` on the three class types, T4 and T5 in `TS/src/service.ts` with a version that is not a safe positive integer refused, written in `TS/src/spec.ts`, the old-sidecar refusal extended; cases in `TS/test/topic-sources.test.ts`; `topic-rows` at version 2 in `TS/examples/shopping-cart/conformance.ts`. `npm run typecheck && npm test && npm run conformance` green.
- [X] T052 [P] [US3] Rust: the provided method `version() -> Option<u32>` on the three traits, written into discovery, T4 and T5 in each `problems()`; cases in `RS/ankka/tests/registration.rs`; `topic-rows` at version 2 in `RS/examples/shopping-cart/src/conformance.rs`. `cargo test --workspace && ./conformance.sh` green, both runs.
- [X] T053 [US3] Run `sbt 'sidecar/testOnly *WasmHostSuite'` (it builds the Rust cart, so `cargo` must be on `PATH`): a module's start position and version arrive through `WasmDiscovery` as a process's do. Then US3's proof: `sbt 'runtime/testOnly *TopicSourceRulesSuite *MetricsSuite' 'testkit/testOnly *ViewVersionSuite *KafkaSuite *ConsoleEndpointSuite' 'sidecar/testOnly *ProtocolSuite *RemoteProjectionSuite *ConformanceSuite -- *topic.*'`.

**Checkpoint**: every scenario of `versions.feature` and `status.feature` is a passing case that fails without its code. US2 and US3 release together: protocol 1.4 carries both, and a runtime that had US2 alone would ignore a version an SDK declared.

---

## Phase 6: User Story 4 — The limits are documented (Priority: P3)

**Goal**: a developer reads what a group is called, where a source starts, what a version does,
that a rebuild reaches back only as far as the broker retains, and what an upgrade does.

**Independent Test**: `just docs` passes, and the three places named in `quickstart.md` "User Story
4" say what the three scenarios of `features/documentation/topic-sources.feature` say.

**Depends on**: US1 to US3, whose tested code its samples are copied from.

- [X] T054 [US4] Mark the regions the pages include, in tested code: in `TKT/TopicComponents.scala`, `// docs:start`/`// docs:end` around a view declared with a version and a consumer declared with a start position; the same around `topic-rows` and `topic-recorder` in `PY/examples/shopping_cart/conformance.py`, `TS/examples/shopping-cart/conformance.ts` and `RS/examples/shopping-cart/src/conformance.rs`.
- [X] T055 [US4] Rewrite the affected sections of `DOCS/build/topics.md`: under "Reading from a topic", the start position and its three values, with the included samples in each language and that a consumer must state one; a new section on versions — what a version is, what a higher one does, what an instance at a lower one does, that it only goes up, and that a rebuild reaches back only as far as the broker retains and the view is partial while it runs; under "Delivery and offsets", how a group is named in each of the three situations, that the start position is committed when a partition is first assigned, the partition-added-later case under `latest`, and where a topic source is visible (the log lines, the metric series, and who can read each today). The page must stand alone: no feature number, no "previously", no "see above". Scenario: "the documentation describes groups, start positions and versions, and what bounds a rebuild".
- [X] T056 [P] [US4] In `DOCS/reference/limitations.md`, replace the entry "Topic sources are at least once and cannot replay" with what bounds them now: at least once; a rebuild by version, bounded by the broker's retention; a view that reads an entity still does not rebuild when its code changes; only Kafka. In `DOCS/build/views.md` "Limits" and `DOCS/build/consumers.md` "Sources", say the same in a line each and link to the topics page. Scenario: "the documentation's limitations say what bounds a topic source's rebuild".
- [X] T057 [P] [US4] In `DOCS/deploy/upgrading.md`, a section on consumer groups: the name changed and what it is now; an upgraded view reads again from its start position; a consumer reading a topic must declare where it starts, and a process-hosted one built on an SDK that cannot say so keeps starting at the earliest message until its SDK is upgraded; that the platform's upgrade is what restarts a process-hosted service under its new group; and the cut-over recipe of R14 (pause, upgrade, deploy with a start time, resume). A view's first version bump follows the runtime upgrade and does not ride with it. That `local` is now a reserved project id, and what that means for an installation that has such a project. In `DOCS/concepts/polyglot.md`, where a module's runtime is described, one sentence: the runtime image must speak at least the protocol the crate does, because a module cannot ask its host and an older host ignores a start position and a version. Scenario: "the documentation says what an upgrade does to a service's groups".
- [X] T058 [US4] In `DOCS/reference/configuration.md`, say what `ANKKA_SERVICE_NAME` and `ankka.service.name` are, beside the generated table and under "Broker topics"; run `just docs-sync` so the table lists them and the rendered skills carry the changed pages (`marketplace/plugins/ankka/skills/` and `ankka.g8/src/main/g8/.claude/skills/`, the latter with every `$` escaped); run `just docs` and fix what it refuses. Run `sbt 'controlplane-api/testOnly *DocumentationDescriptorsSuite'`.

**Checkpoint**: `just docs` is green and the three documentation scenarios can be read off the pages.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T059 [P] Add to `CLAUDE.md`: under *Component hosting*, that a view or consumer over a topic reads under a group named for its service and version; a short section on topic sources (identity from the certificate, the start position committed at assignment, the two locks, the view that is behind, `ankka_view_versions` created by the runtime); and to the traps whatever implementation found, each written as the others are — what looked right, what it did, how it was seen.
- [ ] T060 [P] Settle the glossary with the user: every term of `GLOSSARY.md` is marked `*Proposed.*`. For each, the word, what it does not mean and the synonyms it refuses; remove the mark from those agreed. `group` refusing "consumer group", `message` refusing "event", and `behind`, `emptied` and `metrics` are the ones to ask about by name.
- [X] T061 Run `sbt scalafmtAll scalafmtSbt` and `sbt compile` warning-free; `cd sdks/python && uv run mypy`; `cd sdks/typescript && npm run typecheck`; `cd sdks/rust && cargo clippy --workspace --all-targets --features ankka/testkit -- -D warnings`.
- [X] T062 Run everything `quickstart.md` "Everything" lists: `sbt -Dankka.cluster.tests=off test`, `just docs`, the three SDKs end to end as `CLAUDE.md` gives their commands, `python3 .github/ci-coverage.py`, and the bdd checker, which must report nothing for this spec, these features or the glossary. Read what each run says it ran.
- [ ] T063 Walk `quickstart.md` by hand once against a local Kafka: two services started with different `ANKKA_SERVICE_NAME`s list two groups with `kafka-consumer-groups --list`; a view's version raised and the service restarted logs L2 and comes back with rows of the new shape; `GET /observability/service` shows `topicSources`. Record what was seen in the pull request description, including anything the page said that the run did not show.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: none.
- **Foundational (Phase 2)**: after Setup. Blocks every story.
- **US1 (Phase 3)**: after Foundational. Mergeable alone; released only with US2.
- **US2 (Phase 4)**: after US1 — it subscribes under US1's names.
- **US3 (Phase 5)**: after US2 — it stops a `Subscribed`, reads `earliestRetained`, and reads the protocol fields T029 added.
- **US4 (Phase 6)**: after US1 to US3.
- **Polish (Phase 7)**: after everything it describes.

### Within a story

Tests first, and red; then values (`sdk`), then rules and stores (`runtime`), then the runtime's
wiring, then the sidecar, then the SDKs, then the conformance run that holds them together. Each
story ends with a task that breaks the behaviour once and watches a named case go red.

### What a task waits for, where it is not the one before it

- T014 waits for T013 and T006. T011 and T012 are written before T014 and pass after it.
- T026 waits for T024 (`StartFrom`). T027 and T028 wait for T026. T030 waits for T025 and T029.
- T032, T033 and T034 wait for T029 (the copied protocol) and T030 (a sidecar that reads it).
- T046 waits for T045, which waits for T044. T047 waits for T045 and T046. T048 waits for T046.
- T050, T051 and T052 wait for T043 (the sidecar maps the field) and T049 (the suite can read groups).

## Parallel opportunities

- **US1**: T008, T009, T010 and T012 together (four files). After T013: T015 and T016 beside T014.
- **US2**: T018, T019, T022 and T023 together. After T030: T031, T032, T033 and T034 together — four languages, no shared file.
- **US3**: T036, T040, T041 and T042 together. After T049: T050, T051 and T052 together.
- **US4**: T056 and T057 beside T055.
- T020, T021, T037, T038 and T039 are **not** parallel with each other where they share `TKT/KafkaSuite.scala`, `TKT/TopicSourceSuite.scala` or `TKT/ViewVersionSuite.scala`.

```text
# US2, once the sidecar reads protocol 1.4 (T030):
T031  Scala conformance reference    sidecar/src/test/…/conformance/
T032  Python                         sdks/python/
T033  TypeScript                     sdks/typescript/
T034  Rust                           sdks/rust/
```

## Implementation Strategy

### Built in slices, released once

Phases 1 to 3 (T001 to T017) are the defect's fix, and the first thing to merge: one string per
subscription, no protocol field, no table, and the part 027-managed-broker waits for. It is
**not** a release. The rename restarts every group from its start position, and until US2 a
consumer cannot say where that is.

US1 and US2 are the smallest unit that could be released, and US3 must be in the same release,
because protocol 1.4 is written once (T029) with the version fields in it and a runtime that
ignored a declared version would be the silent difference the protocol's own rule forbids. So no
tag is cut until Phase 5's checkpoint. `main` may hold any prefix of the slices in the meantime.

If US3 has to follow in a later release after all, that is a change to this plan and not a
shortcut through it: T029 drops the two version fields, US3 adds them as 1.5, and the
compatibility table in `contracts/protocol.md` gains a row.

### What to watch

- The cases that prove this feature on a real broker are all in `KafkaSuite`, which grows by
  about a minute. Nothing runs a topic source on k3s; that is stated in the plan and is not a
  task here.
- Three tasks end by breaking the behaviour on purpose (T017, T035, T047). They are the proof
  that the cases before them can fail, and they are not optional.
- `MessageSubscriber`'s signature changes in T026. Anything outside this repository that
  implements it stops compiling, which is the intent; say so in the release notes.
