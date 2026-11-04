# Tasks: Personal Data Erasure

**Input**: Design documents from `/specs/042-personal-data-erasure/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a test
that fails without the feature. The scenarios are already written: 59 in `features/erasure/` (twelve
files) and four in `features/installation/facts.feature`. "Bind" means a `Given`/`When`/`Then` step in
the named `GherkinSuite` so the scenario runs; "case" means a `test(...)` named for the rule it holds.
A bound scenario must be red before the task that makes it green: run it once and read the failure.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel: different files, no dependency on an incomplete task
- **[Story]**: US1 (personal fields stored only encrypted), US2 (an erasure makes a subject unreadable
  everywhere), US3 (a restored backup cannot bring a subject back), US4 (an erasure waits for a hold),
  US5 (other projects, outside machines and followers read an erased subject as erased), US6 (an
  agent's conversation is forgotten), US7 (a subject's objects are erased), US8 (a granted service
  asks, and members work in every place)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `SDK` = `modules/sdk/src/main/scala/…/sdk`; `RT`/`RTT` =
`modules/runtime/src/{main,test}/scala/…/runtime`; `KR` = `RT/keyring` (new); `AG` =
`modules/agent/src/main/scala/…/agent`; `TK`/`TKT` = `modules/testkit/src/{main,test}/scala/…/testkit`;
`JAVA` = `modules/sdk-java/src/main/java/…/javasdk`; `KT` = `sdks/kotlin`; `SC`/`SCT` =
`sidecar/src/{main,test}/scala/…/sidecar`; `PROTO` = `protocol/src/main/protobuf/ankka/protocol/v1`;
`FIX` = `protocol/fixtures/personal` (new); `PY` = `sdks/python`; `TS` = `sdks/typescript`; `GO` =
`sdks/go`; `RS` = `sdks/rust/ankka`; `KEY`/`KEYT` = `keyring/src/{main,test}/scala/…/keyring` (new);
`API` = `controlplane-api/src/main/scala/…/controlplane/api`; `CRD` = `crd/src/main/scala/…/crd`;
`CP`/`CPT` = `controlplane/src/{main,test}/scala/…/controlplane`; `ER` = `CP/erasure` (new);
`OP`/`OPT` = `operator/src/{main,test}/scala/…/operator`; `CLI` = `cli/src/main/scala/…/cli`; `KUST` =
`kustomization/components`; `CON` = `console/package`; `FEAT` = `features/erasure`. "R*n*" is a
section of `research.md`; a contract is named by its file under `contracts/`.

Work on branch `042-personal-data-erasure-design`, or in a `042-personal-data-erasure-impl` worktree
off it. Write `.specify/feature.json` to `specs/042-personal-data-erasure` in a new worktree. Every
`sbt` command takes `-Dankka.cluster.tests=off` except the k3s tasks, which say so. Long suites run
under `caffeinate -i`. munit's `--` filter needs a leading `*`. The protocol minor is 1.23 (main is
1.22); renumber if anything merges first. The features' project is `brand` with the service
`players`, the other project `payments`, the data subject `player/8c1f`, the installations `north`
(home) and `south` (follower).

The glossary's words, which every identifier and message here follows: **data subject** (never
"subject" alone in a message a person reads: that is an issuer's id), **personal field**, **personal
envelope**, **subject key**, **keyring**, **lookup token**, **lookup key**, **erasure** (never
shredding), **erased**, **erasure request**, **not-before date**, **withdrawn**, **completion**,
**erasure handler**, **erasure log**, **erasure certificate**, **correlation id**, **decryption**,
**subject prefix**, **soft-delete window**, **switched**. A feature step says "asks for" an erasure
request; the CLI's verb `file` is the command's, not the feature's.

---

## Phase 1: Setup: the fixtures, the project, the settings, the suites bound red

**Purpose**: the one envelope every SDK must write, the new sbt project, the variables declared once,
and every scenario bound to a suite and red before a line of the feature exists.

- [X] T001 Write `FIX/subjects.json` (valid and invalid data subjects with FR-002's refusal message) and `FIX/envelopes.json` (fixed key, nonce, data subject, project, manifest, plaintext JSON, the exact envelope, the subject-only envelope, and one spliced envelope that must fail as corrupt), computed once by a small script under `protocol/fixtures/personal/README.md` that says how; add the directory to the four SDKs' copy scripts (`scripts/proto.*`) and to CI's `diff -r` checks in `.github/workflows/ci.yml`
- [X] T002 Declare `ErasureSetting` (`ANKKA_ERASURE`, `on`/`off`, default `off`), `KeyringInstances` (`ANKKA_KEYRING_INSTANCES`, default 2) and `KeyringAddress` (`ANKKA_KEYRING_ADDRESS`) in `modules/core/src/main/scala/…/core/PlatformVariables.scala` (standard library only), with cases in `PlatformVariablesSuite` and `PlatformDeclarationSuite` green
- [X] T003 Add the `keyring` sbt project to `build.sbt` (depends on `http` and `authOidc`, and `testPki % Test`; r2dbc-postgres from `project/Dependencies.scala`; image `ankka-keyring` with `sbt-native-packager` as `modelKeeper` is; built for cluster tests beside `modelKeeper` at the existing image task), an empty `KEY/Main.scala`, and the `keyring/**` filter in `.github/ci-coverage.py` and the `build` job of `.github/workflows/ci.yml` (the `rest` matrix entry runs it unlisted)
- [X] T004 Add `ankka.keyring` to `modules/runtime/src/main/resources/reference.conf` (`cache.max-keys = 10000`, `cache.ttl = 5m`, `cache.outage-bound = 15m`, `notice-bound = 60s`, `fetch-timeout = 2s`) and the settings case class `KR/KeyringSettings.scala` with `KeyringSettingsSuite`
- [X] T005 Create `CPT/ErasureFeatures.scala` (the control plane's tests, which see the kits, the CLI and the erasure requests), a `GherkinSuite` over `features/erasure/` (`facts.feature` stays with `InstallationFactsFeatures`, which runs it whole), with `ranElsewhere` naming the cluster scenarios (story 3's restore cases, `objects.feature`'s soft-delete case, the outside machine's three scenarios) and the language outlines (`languages.feature`), and `ranOutside` naming the Google-only object case; run it and confirm every in-process scenario is red with an undefined step
- [X] T006 [P] Create `CPT/ErasureClusterFeatures.scala` and `CPT/ErasureRestoreClusterFeatures.scala` (k3s, reading `ankka.cluster.tests`, so `.github/cluster-suites.py` picks them up unlisted) with the scenarios `ErasureFeatures` delegates, every step undefined; confirm `python3 .github/cluster-suites.py` lists them

**Checkpoint**: `just features` is 0 findings; `ErasureFeatures` runs and reports every scenario red.

---

## Phase 2: Foundational: `Personal`, the scope, the handle, the in-memory keyring

**Purpose**: what every story needs before it can write a personal field.

- [X] T007 Write `CORET/PersonalSuite.scala` first: the envelope fixtures round-trip byte for byte (nonce injected), the subject-only envelope decodes to `Erased`, the spliced envelope fails as `Corrupt` not `Erased`, `toString` is `Personal(player/8c1f)` for both cases, FR-002's rule against `FIX/subjects.json`, a nested `Personal` in an `Option`, a `List` and a nested case class is found by a derived codec, and an encode outside a `Serializer` is refused naming `Serializer`; red
- [X] T008 Add `FIX/edge.json` (the edge form of each fixture case) to T001's fixtures and copies
- [X] T009 Implement `CORE/Personal.scala`: `opaque type DataSubject` with FR-002's rule; `enum Personal[+A] { Present(subject, value); Erased(subject) }` with `orElse`; the companion's `given JsonValueCodec[Personal[A]]` writing and reading the envelope of `data-model.md`; `PersonalScope` (a `ThreadLocal` of the manifest and the handle's project, R2); the AES-256-GCM seal and open with the length-prefixed associated data of R11 via `javax.crypto`; `Corrupt` on an authentication failure; with no scope bound, the edge form of R26 both ways. `PersonalSuite` green, with the edge cases against `FIX/edge.json`
- [X] T010 Implement `CORE/Keyring.scala`: the handle trait (`key(subject, create): KeyAnswer`, `project`), `Keyring.unavailable` (refuses as `CommandError(Unavailable)` naming the keyring, as `SecretStore.unavailable` does), and `Keyring.current` per R3 (a `@volatile` default plus a thread-local override hosts bind around their serializer calls); case in `KeyringSuite` that two handles bound on two threads never cross
- [X] T011 Bind `PersonalScope` in `CORE/Serializer.scala`'s `JsonSerializer.toBytes`/`fromBytes` (manifest), with a case in `SerializerSuite` that a journal record without envelopes encodes byte-identically to before (the `JournalRecordCompatibilitySuite` fixtures stay untouched)
- [X] T012 [P] Implement `TK/InMemoryKeyring.scala`: keys, tombstones, `erase(subject)` (destroys at once and emits a notice), `outage(duration)`, a fetch counter per subject; plus `TK/PersonalAssertions.scala` with `assertNoPersonalValue(values*)` dumping every table of the service's database and every record of every topic the service published to, and failing with the table and column, or the topic and offset, where a value appears in plain, base64 or hex
- [X] T013 Install the keyring handle in `RT/Ankka.scala` at service start (a `KeyringHandle` over `InMemoryKeyring`-compatible `KeyStoreClient` trait, chosen by configuration: `in-memory`, `local`, `remote`), clear it at stop, and in `TK/AnkkaTestKit.scala` install `InMemoryKeyring` by default and swap it on `restartService` (runtime.md:282-285); case `AnkkaTestKitKeyringSuite` that a restarted kit's codec uses the new handle
- [X] T014 Implement `KR/KeyCache.scala` (bounded LRU, TTL while the keyring answers, outage bound, drop on notice, drop all) with `KeyCacheSuite` driven by a manual clock
- [X] T015 Wrap the event sourced, key value, workflow and agent hosts in `Behaviors.supervise(...).onFailure[KeyringUnavailable](SupervisorStrategy.restartWithBackoff(1.s, 30.s, 0.2))` (`RT/EventSourcedEntityHost.scala`, `RT/KeyValueEntityHost.scala`, `RT/WorkflowHost.scala`, `AG/AgentRuntime.scala`, `AG/autonomous/AutonomousAgentHost.scala`) and bind `Keyring.current` around their adapters' encode and decode; `RecoveryRetrySuite` that a recovery during a keyring outage is retried and succeeds when it returns, and never decodes `Erased`
- [X] T016 Make `Personal` printable only as `Personal(<subject>)` in the recorder and OTLP: drop any attribute whose value parses as a personal envelope in `RT/Recorder.scala` and `modules/telemetry-otlp/…/RecordedSpanData.scala`; `RecorderEnvelopeSuite`

**Checkpoint**: `sbt core/test runtime/test` green; a Scala service can construct a `Personal` and nothing yet persists one in a test.

---

## Phase 3: User Story 1: personal fields stored only encrypted (P1) 🎯 MVP

**Goal**: a Scala, Java and Kotlin service's journal, snapshot, key value state, view row and topic message hold personal fields only encrypted, and every reader sees the value.

**Independent test**: `ErasureFeatures` over `personal-fields.feature` and the JVM outline of `languages.feature`; `kit.assertNoPersonalValue` green after a write and red when the codec is bypassed.

- [X] T017 [US1] Bind `FEAT/personal-fields.feature`'s steps in `ErasureFeatures` with a `players` service whose event, snapshot, key value state, view row and published message carry `Personal` fields, and read the topic's records back raw as well as the tables; confirm each scenario fails for the right reason (plaintext found)
- [X] T018 [US1] Make the first write of a data subject create its key once whichever instance writes first (`InMemoryKeyring` and the `KeyStoreClient` contract's `create = true`), with a case racing two kits in one project
- [X] T019 [US1] Refuse a personal field where no service runs (a serializer encoding one in a process that started no service: the command line, a test with no test kit; the entity test kit encodes only inputs and replies) as unavailable naming the keyring; bind the outline's rows
- [X] T020 [US1] Prove `assertNoPersonalValue` can fail: `PersonalAssertionsSuite` keeps a value through a field that is not personal (its serializer writes it as given), and base64 and hex records on a topic, and asserts the assertion goes red naming the table, or the topic and offset
- [X] T021 [P] [US1] Java: add `Personal<A>` (sealed, `Present`/`Erased`), `DataSubject` and `orElse` in `JAVA/Personal.java`, `JAVA/DataSubject.java`; a `Plan.Personal` case in `JAVA/codec/Plan.java`, the `Planner` branch beside `Opt`, the cases in `JsonWriter`, `JsonReader` and `Schemas`, all reaching `core`'s seal and open through the bridge; `PersonalRecordCodecSuite` against `FIX/envelopes.json` and `edge.json`
- [X] T022 [P] [US1] Kotlin: `Personal` extensions and a `personal(subject, value)` builder in `KT/…/Personal.kt`; the Kotlin test against the fixtures
- [X] T023 [US1] Bind the JVM outline of `FEAT/languages.feature` (Java and Kotlin rows) through `modules/testkit-java` and the Kotlin kit; green
- [X] T024 [US1] The descriptor field: `personal: Boolean = false` on `ServiceSpec` in `API/descriptors.scala` (strict codec, `DescriptorSuite`), `spec.personal` on `CRD/AnkkaService.scala` and `KUST/crd/ankkaservice.yaml` (`CrdSchemaSuite` both ways), the control plane's projection carries it
- [X] T025 [US1] The installation setting: `InstallationEndpoint` in `CP/api/InstallationEndpoint.scala` reports `InstallationErasure(enabled, keyringInstances)` from `ANKKA_ERASURE`/`ANKKA_KEYRING_INSTANCES` instead of `None`; `PUT /services/...` in `CP/api/ServiceEndpoint.scala` refuses `"personal": true` with erasure off, naming `ANKKA_ERASURE`, beside `objectStorageProblems`; update `InstallationFactsFeatures` and bind the three `facts.feature` scenarios and `personal-fields.feature`'s refusal scenario
- [X] T026 [US1] Bind `FEAT/edge.feature` in `ErasureFeatures` with an HTTP endpoint of `players` (`HttpServer.at("127.0.0.1", 0)`); the reply and a posted body hold the edge form, no envelope; green
- [X] T027 [US1] `ErasureFeatures`: `personal-fields.feature`, `edge.feature` and the four `facts.feature` scenarios green

**Checkpoint**: story 1 is complete for the JVM; a Scala service stores no personal value in plain anywhere.

---

## Phase 4: User Story 2: an erasure makes a data subject unreadable everywhere (P1)

**Goal**: an erasure request with no not-before date destroys the subject key; every service of the project reads the data subject as erased within 60 seconds; rows are redacted, lookup tokens dropped, handlers run, completions recorded, a certificate fetched.

**Independent test**: `ErasureFeatures` over `erasing.feature`, two services in one project sharing an in-memory keyring; `PersonalRecoveryBenchmark` ≤ 1.3.

- [X] T028 [US2] Bind `FEAT/erasing.feature` (two services `players` and `wallet`, a view, a consumer); red
- [X] T029 [US2] `KR/KeyringClient.scala` over the `KeyStoreClient` trait: fetch through the cache with `fetch-timeout`, `Notices` held open from start, drop the whole cache when no confirmation arrives within `notice-bound`; `KeyringClientSuite` with a manual clock
- [X] T030 [US2] Refuse a write for an erased data subject in the codec (FR-013) naming the data subject; a tombstoned subject's first write refused (FR-005); bind the two scenarios *(the entity seals its events while handling the command, so the refusal is its reply; `PersonalWriteRefusalSuite`; the `erasing.feature` scenario is bound with its background in T041)*
- [X] T031 [US2] View metadata (R12): `SDK/PersonalFields.scala` with a `Mirror`-derived `PersonalFields.of[Row]`, `lookup(_.field)` on the view builder, the paths carried in the view's descriptor (`SDK/DeclaredQuery.scala`'s owner); `PersonalFieldsSuite` for nested and optional fields
- [X] T032 [US2] `RT/QueryCheck.scala`: refuse at start a predicate on a personal path not marked for lookup, naming the view, the query and the field; rewrite an equality on a lookup path to `lookup->>'<path>' = <token>`; `QueryCheckPersonalSuite`
- [X] T033 [US2] Lookup tokens (R13): the `lookup JSONB` column added by `RT/ViewStore.scala`'s start DDL for a view with lookup fields; tokens computed in `RT/ProjectionSupport.scala`'s row write with the project's lookup key (HMAC-SHA-256 of the canonical encoding); the row writer refuses an envelope at an undeclared path; `LookupTokenSuite`
- [X] T034 [US2] `KR/ViewRedaction.scala` (R14): the jsonpath `UPDATE` per view reducing the data subject's envelopes to the subject and nulling `lookup`; `ViewRedactionSuite` that another data subject's ciphertext in the same row survives
- [X] T035 [US2] `KR/ErasureDuties.scala`: on a notice drop the key, redact each view, mark agent sessions (a hook US6 fills), run the handler on the service's singleton (R16), then ack with a `Completion`; `ErasureDutiesSuite`
- [X] T036 [US2] The erasure handler in the SDK: `SDK/ErasureHandler.scala` (`ErasureHandler(ctx => subject => ErasureReport)`, context with `client`, `secrets`, `storage`), `withErasureHandler` on the service builder, the singleton in `RT/Ankka.scala` (as the timer sweeper's); a handler that throws is recorded as failed and run again on the next pass
- [X] T037 [US2] `RT/keyring/LocalKeyring.scala` (R20): keys and tombstones in `ankka_keyring` of the service's database, made by code; `DELETE` plus tombstone in one transaction; used under `sbt run` and by `AnkkaTestKit` on request; a `POST /_ankka/erasures` route on the local console (`RT/ObservabilityEndpoint.scala`, loopback only) that destroys a key in it and runs the service's duties, which `ankka local erasures file` calls; `LocalKeyringSuite`
- [X] T038 [US2] The erasure request in the control plane (R21): `ER/ErasureRequestEntity.scala` (events of `data-model.md`, each with `actor`/`at`), `ER/ErasureRequestsView.scala`, `EventCompatibilitySuite` fixtures for every event; `ErasureRequestEntitySuite` for every transition and refusal
- [X] T039 [US2] `ER/ErasureLog.scala`: the `erasure_log` table made by code and the bucket writer (`platform.erasure-log`, one object per entry, subject in the body only); `ER/KeyringAdmin.scala`: `Destroy`, `Resend`, `ErasureStatus` over the control plane's identity; apply = log both copies, then destroy, then poll status into `ServiceCompleted`; a failed log write leaves `Applying` and destroys nothing
- [X] T040 [US2] `CP/api/ErasureEndpoint.scala`: the routes of `contracts/control-plane-and-cli.md` (ask, list, get, certificate; withdraw, replace and override land in US4), members by `Authorization.project`; the certificate's JSON; every event of an erasure request shown in the project's history (`GET /projects/{id}/history`) with who and when (FR-016); `ErasureEndpointSuite`
- [X] T041 [US2] Wire the control plane in-process for `ErasureFeatures` (the endpoint and the entity against the kit's in-memory keyring through a test `KeyringAdmin`); bind the request, completion and certificate scenarios
- [X] T042 [US2] An erasure asked twice: the second for an applied data subject answers the applied request and asks for a re-send so every handler runs again (`erasing.feature`'s handler scenario)
- [ ] T043 [US2] `TKT/PersonalRecoveryBenchmark.scala` (SC-007, FR-023): 1,000 events of one data subject against `LocalKeyring`, recovered cold, ratio printed and asserted ≤ 1.3; one fetch per recovery asserted by the keyring's counter
- [ ] T044 [US2] `TKT/PersonalLeakSuite.scala` (SC-009): runs story 1's and story 2's flows with an appender that fails on any plain value they wrote, including a handler that logs a whole event with string interpolation
- [ ] T045 [US2] `ErasureFeatures`: `erasing.feature` green

**Checkpoint**: an erasure works end to end in-process for JVM services.

---

## Phase 5: The keyring (blocks US3, US5's outside machines, and the cluster)

**Purpose**: the standalone component of R10, R11 and R22, proven against Postgres before any cluster.

- [ ] T046 [P] Write `KEY/Wire.scala`: the JSON messages of `contracts/keyring-and-protocol.md` (key request and answer, notice, ack, hello, erasure, status, decrypt) with jsoniter codecs, shared with the runtime's client through `core` (`CORE/keyring/KeyringWire.scala`, no Pekko) so the two ends cannot disagree; `KeyringWireSuite` pins each message's JSON
- [ ] T047 `KEY/KeyStore.scala`: the tables of `data-model.md` made by code; `INSERT … ON CONFLICT DO NOTHING` then read back for a create; destroy = `DELETE` + tombstone + `applied_log` in one transaction, then `VACUUM subject_key`; `KeyStoreSuite` against Postgres by testcontainers, including that no dead tuple holding the key remains (`pageinspect` or a `pg_visibility` check)
- [ ] T048 `KEY/RootKey.scala` and `KEY/Wrapping.scala`: the root key from the keyring's own secret store on Postgres (038's `postgres` backend, made at first start) or KMS encrypt/decrypt through the `wrapping-key` output's key name; KEK and lookup key per project; `WrappingSuite`
- [ ] T049 `KEY/Admission.scala`: caller from the client certificate (`Caller.fromCertificate`), the control plane's identity limited to `Destroy`/`Resend`/`ErasureStatus`, a consumer admitted by a `decrypt` consume grant read from the `ankka-project` ConfigMaps' `grants.json`, a relayed installation admitted by the home's list, a machine by its bearer token for `Decrypt` only; every refusal recorded in `fetch_refusal`; `AdmissionSuite`
- [ ] T050 `KEY/Notices.scala`: the bidi stream per instance, backlog since the last ack on reconnect, acks into `notice_ack`, a service complete when every instance connected at the notice has acked or been replaced; `NoticesSuite`
- [ ] T051 `KEY/ErasureLogReplay.scala` (FR-021's keyring half): before serving, read `applied_log`'s last sequence, read both copies (`GET /internal/erasure-log?after=` and the bucket), apply the union in order, report a copy that was behind on `/status` and in the log; `/ready` holds until done; `ErasureLogReplaySuite` with one copy behind and with the keyring's database restored to before an erasure
- [ ] T052 `KEY/KeyringServer.scala` and `KEY/Main.scala`: pekko-http over mutual TLS as `ankka://platform/keyring` (routes and the notices WebSocket) (refuse to start on another identity, as the model keeper does), `/ready` and `/status` on the probe port; `KeyringServerSuite` end to end with `test-pki`
- [ ] T053 `KR/RemoteKeyStoreClient.scala`: the runtime's `KeyStoreClient` over the keyring's routes and notices WebSocket (pekko-http client) with the service's identity; `ErasureFeatures` gains a mode that starts a real keyring in-process against the shared Postgres and runs `erasing.feature` through it; green

**Checkpoint**: `sbt keyring/test` green; story 2 green against the real keyring.

---

## Phase 6: User Story 3: a restored backup cannot bring a data subject back (P1)

**Goal**: a project's database restored to before an erasure, and the keyring's database restored to before one, both leave the data subject erased; nothing answers before its replay.

**Independent test**: `ErasureRestoreClusterFeatures` (k3s) for the two cases; in-process cases for readiness and the log's two copies.

- [ ] T054 [US3] Bind `FEAT/restores.feature`'s in-process scenarios in `ErasureFeatures` (an entity recovered from a restored database reads erased; the union of both copies; a failed log write destroys nothing) and its cluster scenarios in `ErasureRestoreClusterFeatures`; red
- [ ] T055 [US3] A service switched to a restore holds `ExtensionsReadiness` as `erasure-replay` (`RT/ExtensionsReadiness.scala`, the secret-move precedent in `RT/Ankka.scala:387-390`) until it has applied every log entry of its project (R14 over each, agent sessions marked); the restore's status reports `erasureReplayedAt` (`CP/application/ProjectEntity.scala`'s `observeRestore`, the operator's project status)
- [ ] T056 [US3] `RestoreHold`'s reconciler for the erasure log (`CP/deploy/RestoreHold.scala`'s `reconcilers`): bring `erasure_log` up to date from the bucket before release; `RestoreHoldErasureSuite`
- [ ] T057 [US3] `ErasureRestoreClusterFeatures`: case A (the project database restored, the keyring untouched) and case B (the keyring's database restored) on k3s through `gh workflow run cluster --ref 042-personal-data-erasure-design -f suite=ErasureRestoreClusterFeatures`; green

**Checkpoint**: SC-002 and SC-003 hold on a cluster.

---

## Phase 7: User Story 4: an erasure waits for a hold (P1)

**Goal**: a held request is applied when its not-before date passes, can be withdrawn and replaced while held, and only an owner overrides it.

**Independent test**: `ErasureFeatures` over `holds.feature` with a test clock.

- [ ] T058 [US4] Bind `FEAT/holds.feature` with the control plane's clock injected; red
- [ ] T059 [US4] `ER/ErasureSweep.scala`: a cluster-singleton sweep every 5 minutes (as `RetentionSweeper`): apply lapsed holds, retry `Applying`, and every 15 minutes ask the keyring to re-send each erasure applied within `ANKKA_ERASURE_RERUN_WINDOW` (default 30 days, declared in `PlatformVariables`) so handlers run again, and none older (FR-015); a quiet run sets `lastRunAt`, a run that erased or failed records `HandlerRan` (FR-016); wired in `CP/ControlPlane.scala` after `hold`; `ErasureSweepSuite` with a manual clock (SC-006, the window's edge both sides)
- [ ] T060 [US4] The routes `PUT …/erasures/{rid}` (replace), `POST …/withdraw` (filer or member) and `POST …/override` (owner, `Authorization.projectOwner`, reason required) with 409 on an applied request and 403 for a member; cases in `ErasureEndpointSuite`
- [ ] T061 [US4] `ErasureFeatures`: `holds.feature` green

**Checkpoint**: the P1 stories are complete for JVM services.

---

## Phase 8: User Story 1 and 2 in every other language (P1, the languages outline)

**Goal**: Python, TypeScript, Go and Rust write the same envelope, read it back, and erase; the sidecar never reads it.

**Independent test**: each SDK's suite against `FIX/`; the conformance references' `personal.*` and `erasure.*` cases; `languages.feature`'s process and module rows.

- [ ] T062 Protocol 1.23: `GetSubjectKey`, `SubjectNotices` (server stream), `AckSubjectNotice`, `AskErasure` in `PROTO/client.proto`; `Spec.erasure_handler` in `PROTO/discovery.proto`; `PROTO/erasure.proto` with the `Erasure` service; the version in the eight places (`RT/remote/Conversation.scala`, `SC/Discovery.scala`, `controlplane-api/…/Compatibility.scala`, `PY/src/ankka/service.py`, `TS/src/spec.ts`, `GO/ankka.go`, `RS/src/service.rs`, `GO/examples/shopping-cart/service.json`) and the pinning tests; `protocol/README.md`'s 1.23 entry; the four SDK copies
- [ ] T063 Sidecar: `SC/ClientLogic.scala` and `SC/ClientService.scala` answer `GetSubjectKey` from the runtime's `KeyringClient`, relay notices through `ClientService.drain`, ack the keyring when every process instance has; `ErasureSince = 23` in `SC/Discovery.scala`, refusing `erasure_handler` below it; `Conversation.invokeErasure` (default throws), `SC/GrpcConversation.scala`'s stub and override, `SC/wasm/WasmConversation.scala`
- [ ] T064 Wasm ABI: `keyring_seal`, `keyring_open` in `SC/wasm/HostImports.scala` with their own `CallSite` permission (every call site), `storage`'s `erase` op, the `erase` export in `SC/wasm/ModuleLoader.scala`'s `Imports`/`Exports`, `WasmImportsSuite`, `WasmHostSuite`, `protocol/WASM-ABI.md`
- [ ] T065 [P] Python: `PY/src/ankka/personal.py` (`DataSubject`, `Personal`, `Present`, `Erased`, the codec branches in `codec.py`'s `to_json_value`/`from_json_value`), `keyring.py` (cache, prefetch before decode and encode, notices, ack), `erasure.py` (`@service.erasure_handler`, the `Erasure` servicer registered in `server.py`), `cryptography` (≥ 44, < 47) in `pyproject.toml`; `PY/tests/test_personal.py` against `FIX/` including `edge.json` for an endpoint's reply and body; refusal under a 1.22 runtime
- [ ] T066 [P] TypeScript: `TS/src/personal.ts`, `s.personal(...)` as a new `Shape` kind in `schema.ts` with cases in `json.ts`'s `write`/`decode` and `toJsonSchema`, `keyring.ts` (prefetch, cache, notices), `erasure.ts` and `server/erasure.ts`; `node:crypto`; `TS/test/personal.test.ts` against `FIX/` including `edge.json`
- [ ] T067 [P] Go: `GO/personal.go` (`Personal[T]`, `DataSubject`), the special type in `plan.go` with `json_write.go`, `json_read.go`, `schema.go`, `keyring.go` (prefetch, cache, notices), `erasure.go` and `internal/server/erasure.go`; `crypto/aes`; `GO/personal_test.go` against `FIX/` including `edge.json`; the unit kit's `Client` stand-in implements `GetSubjectKey`
- [ ] T068 [P] Rust: `RS/src/personal.rs` (`Personal<T>` with serde through `keyring_seal`/`keyring_open` dedicated `call_*` functions in `abi/imports.rs`, the native stub), `erasure.rs` and the `#[ankka::erasure_handler]` attribute exporting `erase`; `RS/tests/personal.rs` against `FIX/` including `edge.json` (a module's endpoint reply)
- [ ] T069 Conformance: `personal.*` and `erasure.*` cases in `SCT/conformance/ConformanceSuite.scala` and `ConformanceReference.scala` (write an envelope; read one back; erased decodes; a write for an erased data subject refused; the handler runs and reports; `personal.sidecar-reads-nothing`, which hands the sidecar's payload path bytes it could not parse as JSON and proves the event is journaled unchanged); the Python, TypeScript, Go and Rust references implement them
- [ ] T070 Bind and run `FEAT/languages.feature`'s process and module rows (`ranElsewhere` names the conformance runs); SC-008 green for every reference
- [ ] T071 Test kits: Python's, TypeScript's and Go's integration kits expose `keyring.erase`, `keyring.outage` and `assert_no_personal_value` through their sidecar; `testkit-java` exposes the JVM kit's

**Checkpoint**: every SDK marks, reads and erases.

---

## Phase 9: User Story 5: other projects, outside machines and followers (P2)

**Goal**: a consumer in another project decrypts under a `decrypt` grant and reads erased after the producing project's erasure; an outside machine reads erased or asks the keyring to decrypt; a follower of a replicated entity reads through the home's keyring.

**Independent test**: `ErasureFeatures` with two projects and two installations in one JVM; the machine cases on k3s.

- [ ] T072 [US5] Bind `FEAT/other-projects.feature` (in-process scenarios with two projects; the machine scenarios in `ErasureClusterFeatures`) and `FEAT/replication.feature` (two installations `north` and `south` in one JVM, `TestReplicationTransport`); red
- [ ] T073 [US5] A consumer in another project fetches the producing project's keys under its `decrypt` grant (admission in `KEY/Admission.scala`, and the in-memory keyring's equivalent); keeps the fields encrypted under the producing project's keys in its own stores; listens to the producing project's notices; a revoked grant refuses the next fetch
- [ ] T074 [US5] A consumer without `decrypt` reads every personal field as erased (the keyring answers `Refused`, the codec yields `Erased` for that grant's reads only, never caching a refusal as destroyed)
- [ ] T075 [US5] `KEY/DecryptRoute.scala`: `Decrypt` through the gateway for a machine token holding a decrypt grant, field by field, each recorded in `decryption`; refused once revoked or erased; the gateway route rendered by the operator when erasure is on
- [ ] T076 [US5] `RT/replication/KeyringRelay.scala` (R17): the ServiceLoader SPI and its default (`Unavailable` naming the relay); the keyring client asks the relay with the entity's home for a replicated component's data subject; `TK/TestKeyringRelay.scala` over `LocalInstallations`; the home's admission of a relayed installation
- [ ] T077 [US5] `ErasureFeatures`: `other-projects.feature`'s in-process scenarios and `replication.feature` green; `ErasureClusterFeatures`' machine scenarios green on k3s

---

## Phase 10: User Story 6: an agent's conversation is forgotten (P2)

**Goal**: a session or instance started with a data subject keeps its conversation encrypted; after an erasure it reads erased, a new turn starts with nothing, and an instance is stopped.

**Independent test**: `ErasureFeatures` over `agents.feature` with `TestModelProvider`.

- [ ] T078 [US6] Bind `FEAT/agents.feature`; red
- [ ] T079 [US6] `subject: Option[DataSubject]` on the session (`AG/SessionMemoryEntity.scala`'s `Started` and state) and on the instance (`AG/autonomous/InstanceEntity.scala`), `NON_ABSENT`; the agent client's `session(id, subject = …)` and `autonomous.start(id, subject = …)` in `SDK`; a later call naming another data subject refused; compatibility fixtures for the old shapes
- [ ] T080 [US6] Wrap `UserMessage.text`, `AiMessage.text`, tool call arguments, `ToolResultMessage.content`, `SummaryMessage.text`, `SuspendedTurn.payload` (`AG/memory.scala`), task instructions and results (`AG/autonomous/TaskEntity.scala`) and `ApprovalRequest.arguments` (`AG/autonomous/Approvals.scala`) in envelopes under the data subject; a reader accepts both shapes; agents.md's suspended-turn rule held
- [ ] T081 [US6] On a notice: a session of the data subject reads with an empty history and a new turn begins with nothing; an instance tagged with it is stopped by its host and reads as erased (the `ErasureDuties` hook from T035); `ObservabilityEndpoint` serves personal fields as `Personal(<subject>)`
- [ ] T082 [US6] The process SDKs' agent clients carry the data subject (Python, TypeScript, Go, Rust), one case each
- [ ] T083 [US6] `ErasureFeatures`: `agents.feature` green

---

## Phase 11: User Story 7: a data subject's objects are erased (P2)

**Goal**: `context.storage.erase(subject)` deletes every object (every version on GCS) under the subject prefix and reports its count and finality; a later object is removed on the next application.

**Independent test**: `ErasureFeatures` over `objects.feature` against Garage by testcontainers; the soft-delete case in `ObjectStorageGcsClusterFeatures`.

- [ ] T084 [US7] Bind `FEAT/objects.feature`; red
- [ ] T085 [US7] `erase(subject)` on `SDK`'s `Bucket`: `RT/storage/S3Bucket.scala` lists `subjects/<subject>/` and deletes each object, with `versions` listing and deletion by version id on GCS; `DirectoryBucket` and `MemoryBucket`; finality `Immediate` or `After(soft-delete days)` from `ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS`; `S3BucketEraseSuite` against Garage
- [ ] T086 [P] [US7] `erase` in Python's, TypeScript's and Go's storage clients and the module's `storage` import; one case each
- [ ] T087 [US7] The completion records the handler's count; the request's `objectsFinalAt` is the latest finality reported; a service with no bucket is refused (`storage.erase` on `ObjectStorage.unavailable`)
- [ ] T088 [US7] `ObjectStorageGcsClusterFeatures`: the soft-delete finality case (`ranOutside` for the Google-only listing); `ErasureFeatures`: `objects.feature` green

---

## Phase 12: User Story 8: a granted service asks, and members work in every place (P3)

**Goal**: a service with the `erasure` grant asks in its own project and another; requests list by correlation id across projects; the CLI, the console and the MCP server do everything a member and an owner may.

**Independent test**: `ErasureFeatures` over `asking.feature` and `places.feature` (CLI and MCP in-process); the console's Playwright suite.

- [ ] T089 [US8] Bind `FEAT/asking.feature` and `FEAT/places.feature`; red
- [ ] T090 [US8] `ctx.erasures.ask(...)` in `SDK/Erasures.scala` through the component client to the control plane's route with the service's certificate; the endpoint admits a service holding the `erasure` grant of the target project (rendered `grants.json`), names it as who asked, and records a refusal in the project's history; `AskErasure` for processes (T062)
- [ ] T091 [US8] `GET /erasures?correlation=` listing across the caller's projects
- [ ] T092 [US8] CLI: `ankka projects erasures file|list|get|replace|withdraw|override|certificate` in `CLI/Main.scala`'s `projectsCommand`, `CLI/ControlPlaneClient.scala`, `CLI/Output.scala`; `ankka local erasures file` in `CLI/local/LocalErasures.scala` against the local console's routes; `CliErasuresSuite` against a real control plane in-process
- [ ] T093 [US8] MCP tools `erasures_list`, `erasure_get`, `erasure_file`, `erasure_replace`, `erasure_withdraw`, `erasure_override`, `erasure_certificate` in `CLI/mcp/AnkkaTools.scala`; `AnkkaToolsSuite` cases, including an override refused to a member
- [ ] T094 [US8] Console: the *Erasures* section in `CON/src/routes/project.tsx` and a request page `CON/src/routes/erasure.tsx` (ask, withdraw, owner's override, certificate download), schemas in `CON/src/client/schemas.ts`, the client in `client/control-plane.ts`, the fake control plane and fixtures (`ControlPlaneFixturesSuite`), unit tests and Playwright cases per console.md (navigate by id after a write)
- [ ] T095 [US8] `ErasureFeatures`: `asking.feature` and `places.feature` green (`ranElsewhere` names the console scenarios for the Playwright suite)

---

## Phase 13: The platform on k3s

**Purpose**: the installation half: the component, the operator, the wrapping key, story 2 across real pods.

- [ ] T096 `KUST/keyring/`: Deployment (replicas from `ANKKA_KEYRING_INSTANCES` by replacement), CNPG `keyring-db` with backups to `platform.backups-keyring`, Certificate `ankka://platform/keyring`, NetworkPolicy (personal namespaces, the control plane, the gateway for `Decrypt`), RBAC to list `ankka-project` ConfigMaps, the patch setting `ANKKA_ERASURE=on` on the control plane and the operator; `RemoteOverlaySuite` renders a copy with distinct values; `ankka-platform` in `overlays/local` gains the settings with erasure off
- [ ] T097 Operator: for `spec.personal`, render `ANKKA_KEYRING_ADDRESS` and label the namespace `ankka.cloud/personal=true` (`OP/Rendering.scala`, `OP/ZeroTrust.scala`); ensure `platform.erasure-log` beside `PlatformBackups.actions` when erasure is on; golden render `OPT/resources/golden/personal-service.txt`; `RenderingUnchangedSuite` green with no repin
- [ ] T098 `Requester.Platform("keyring")` in `OP/CloudRequests.scala` and `CRD/CloudResource.scala`; render `wrappingKey` for it on a Google installation with erasure on; the scripted provider fulfils it (`OPT/cloud/ScriptedFulfilment.scala`); `CloudRequestsSuite`
- [ ] T099 `ErasureClusterFeatures`: the keyring deployed with two instances; story 2 across two services' pods and a consumer in a second project holding a decrypt grant (the 60-second bound for both, SC-004); the descriptor refused with erasure off on a second overlay; through `gh workflow run cluster --ref 042-personal-data-erasure-design -f suite=ErasureClusterFeatures`; green
- [ ] T100 `KeyringOutageSuite` (SC-010) in-process: with the keyring stopped, cached reads serve for the outage bound, no erasure applies, recovery resumes within one fetch of its return, no failed recovery lost

---

## Phase 14: Polish and cross-cutting

- [ ] T101 [P] `docs/build/personal-data.md` (new): marking, reading erased, the handler, objects and the subject prefix, agents, lookup tokens and what they leak (FR-012), asking from code, the checklist of what to mark, the data subject id as the accepted residual, events written before the feature; samples included from tested code
- [ ] T102 [P] `docs/platform/erasure.md` (new): turning erasure on, the keyring, root keys on Postgres and Google Cloud, restores and the erasure log, the 60-second bound, outside machines and what reaches a partner (FR-031), followers and the relay
- [ ] T103 [P] Changed pages: `docs/reference/limitations.md` (erasure stated; a live entity keeps what it decrypted until it is passivated or restarted; not physical deletion, not a classifier, not across installations beyond followers, not re-encryption, not rotation; the local keyring is for development; ankka-replication must implement the relay; a write under the subject prefix after the re-run window needs a new erasure request; what a model provider received is beyond the edge), `docs/reference/service-descriptor.md`, `docs/reference/configuration.md`, `docs/operate/backups.md`, `docs/build/views.md`, `docs/build/agents.md`, `docs/build/object-storage.md`, `mkdocs.yml`
- [ ] T104 [P] Skills: `ankka-entities`, `ankka-views-consumers`, `ankka-agents`, `ankka-platform`, `ankka-deploy` in `tools/docs/skill/*/SKILL.md`; `just docs-sync`
- [ ] T105 Rule-file traps learned during the build, in `.claude/rules/runtime.md`, `sidecar.md`, `kubernetes.md` and a new section in `control-plane.md` for erasure requests
- [ ] T106 `just features` (0 findings), `just docs`, `sbt scalafmtCheckAll`, then `caffeinate -i sbt buildAll`; the four SDKs' end-to-end commands from `CLAUDE.md`; `just test-console`; a full `gh workflow run cluster --ref 042-personal-data-erasure-design`

---

## Dependencies and execution order

- Phase 1 → Phase 2 → everything else.
- US1 (Phase 3) needs Phase 2. US2 (Phase 4) needs US1. The keyring (Phase 5) needs only Phase 2 for
  T046 to T052; T053 needs US2.
- US3 (Phase 6) needs Phase 5 and US2. US4 (Phase 7) needs US2's entity and routes.
- The languages (Phase 8) need Phase 2 and T035 (duties) and T036 (handler).
- US5 (Phase 9) needs Phase 5 and Phase 8's `GetSubjectKey`. US6 needs US2. US7 needs T036.
  US8 needs US4's routes and T062.
- The cluster (Phase 13) needs Phase 5, US1's descriptor and US2. Polish last.

## Parallel opportunities

- Phase 1: T006 beside T005; T001 to T004 touch different trees.
- Phase 2: T012 beside T014 and T016 once T009 and T010 land.
- Phase 3: T021 (Java) and T022 (Kotlin) beside T024 and T025.
- Phase 5 runs beside Phases 6 to 7's control plane work up to T053.
- Phase 8: T065, T066, T067 and T068 are four languages in four trees, after T062 to T064.
- Phases 10, 11 and 12 are independent of each other once US2 and Phase 8 land.
- Phase 14: T101 to T104 together.

## Implementation strategy

The person decided one pull request with every story. The order still delivers value at each
checkpoint and keeps the branch green throughout: Phase 3 is the minimum a service could build on
(personal fields stored encrypted), Phase 4 the act of erasure, Phases 5 to 7 the guarantees a
regulator asks about, Phase 8 every language, Phases 9 to 12 the reach, Phase 13 the installation.
Commit each task as it lands; the review runs after the last, before the pull request.
