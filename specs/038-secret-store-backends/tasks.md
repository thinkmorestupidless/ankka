# Tasks: Secret Store Backends — Google Secret Manager Beside Postgres

**Input**: Design documents from `/specs/038-secret-store-backends/`
**Prerequisites**: plan.md, spec.md (clarified 2026-10-08), research.md, data-model.md, contracts/, quickstart.md

**Tests**: included. The spec's Independent Tests and `features/secrets/*.feature` are the
deliverable's proof, and CLAUDE.md asks that every acceptance scenario end as a test that fails
without the feature. Each test task names the scenarios it covers; write it first and watch it fail.

**Organization**: by user story. Tasks marked **[044]** are Group B: they need spec 044's
`CloudResource`, the operator's request writing and its fake provider on `main` first, and are
written against `contracts/cloud-provider.md`. Everything else stands alone.

**Paths**: module roots are `modules/<name>/src/{main,test}/scala/com/thinkmorestupidless/ankka/<name>/`
(`core`, `sdk`, `http`, `runtime`, `testkit`), and `controlplane/`, `controlplane-api/`, `cli/`,
`crd/`, `operator/`, `sidecar/` at the top level with the same `src/…` shape.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US6 from spec.md
- Every task names the file(s) it touches

---

## Phase 1: Setup (settings, keys and switches)

**Purpose**: the names every later task reads, declared once.

- [ ] T001 Declare `ANKKA_SECRET_BACKEND`, `ANKKA_SECRET_MOVE`, `ANKKA_SECRET_RECORDS_URL`, `ANKKA_SECRET_VERSIONS_KEPT` (all in `PlatformOnly` and `RuntimeOnlyNames`) and 044's `ANKKA_CLOUD_PROVIDER`, `ANKKA_CLOUD_ACCOUNT`, `ANKKA_CLOUD_LOCATION` (044 FR-011 wording, `PlatformOnly`) in `modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/PlatformVariables.scala`; run `sbt 'controlPlane/testOnly *PlatformDeclarationSuite'` and `sbt operator/compile` (the operator compiles this file)
- [ ] T002 Add `ankka.secrets.{backend, timeout = 10s, record-timeout = 5s, records-url, move, versions-kept = 2}`, `ankka.secrets.secret-manager.{endpoint, token, identity}` and `ankka.cloud.{provider, account, location}` with their `${?ANKKA_…}` lines to `modules/runtime/src/main/resources/reference.conf`, each with the one-sentence comment the configuration reference generates from; set `ankka.secrets.records-url = "https://ankka-controlplane.ankka-controlplane.svc:9000"` in `modules/runtime/src/main/resources/ankka-cluster-kubernetes.conf` only (the local overlay leaves it empty), with a case in `modules/runtime/src/test/scala/com/thinkmorestupidless/ankka/runtime/ClusterConfigSuite.scala` that the Kubernetes overlay sets it and the local one does not
- [ ] T003 [P] Forward `ankka.conformance.secrets` in `Test / javaOptions` for `sidecar` in `build.sbt` beside `ankka.conformance.shape` (a switch not forwarded is a silent no-op — CLAUDE.md)
- [ ] T004 [P] Make `DatabaseSecretStore`'s timeout read `ankka.secrets.timeout` instead of the `10.seconds` constant in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/DatabaseSecretStore.scala`; generalise the scaladoc of `modules/sdk/src/main/scala/com/thinkmorestupidless/ankka/sdk/SecretStore.scala` from "the service's database" to "the installation's secret backend" with no API change

---

## Phase 2: Foundational (the seam, the ids, the client, the fake)

**Purpose**: what every story's store and test needs. Blocks Phases 3–8.

- [ ] T005 Create `SecretBackend` (`Postgres | SecretManager`, `parse(config): Either[String, SecretBackend]`, refusing an unknown word by name) in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/secrets/SecretBackend.scala`
- [ ] T006 [P] Create `DerivedIds` per data-model.md (service and project-entry ids, `servicePrefix`, `projectPrefix`, the `_p`/`_s` escape, the `__<sha256>` form past 255, the `ankka.thinkmorestupidless.com/name` annotation key) in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/secrets/DerivedIds.scala`
- [ ] T007 [P] Write `DerivedIdsSuite` in `modules/runtime/src/test/scala/com/thinkmorestupidless/ankka/runtime/secrets/DerivedIdsSuite.scala`: a property test that the tail is injective over `SecretRules`' alphabet, every id matches `[A-Za-z0-9_-]{1,255}`, no prefix is a prefix of another prefix (including `s_` vs `p_` and `cart` vs `cart2`), the digest marker never arises from an escaped name, and the edge-case names of the spec (253 characters, `.` and `/`) round-trip through the annotation
- [ ] T008 Create `AccessTokens` (`trait AccessTokens { def token(): String }`; `AccessTokens.metadata(httpClient)` calling `http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token` with `Metadata-Flavor: Google`, cached until 60s before expiry, any failure → `CommandError(Unavailable)`; `AccessTokens.fixed(token)`) in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/secrets/AccessTokens.scala`
- [ ] T009 Create the `SecretManager` REST client per `contracts/secret-manager.md` (`createSecret`, `addVersion`, `accessLatest`, `deleteSecret`, `listEnabledVersions`, `destroyVersion`, `disableVersion`; jsoniter codecs for the request and reply bodies and Google's error body; `SecretManagerError(status, code, message)`; one `ankka.secrets.timeout` bound end to end; the JDK `HttpClient` as `HttpServiceClients` builds it) in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/secrets/SecretManager.scala` (depends on T008)
- [ ] T010 Create `FakeSecretManager` per data-model.md and `contracts/secret-manager.md` (JDK `HttpServer` on `127.0.0.1:0` as `ScriptedService` is; identity from the bearer token; the secret access rule of research R3 applied before any state change; Google's error shapes; `unreachable`, `failNext`, `calls`, `snapshot`, `versionsOf`; `address`) in `modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit/FakeSecretManager.scala`
- [ ] T011 Write `FakeSecretManagerSuite` in `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/FakeSecretManagerSuite.scala` driving the fake with the real `SecretManager` client (T009): create/add/access/delete/list/destroy/disable round trips; `PERMISSION_DENIED` for another service's prefix, for a project secret written as a service, for an access as `fake:controlplane`; `ALREADY_EXISTS` on a second create; `INVALID_ARGUMENT` on a bad id; `latest` skipping a disabled version; `unreachable` and `failNext` surfacing as the client's errors
- [ ] T012 Add `secretBackend: SecretBackendChoice = SecretBackendChoice.postgres` to `AnkkaTestKit.start` (and `restartService`) in `modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit/AnkkaTestKit.scala`, with `SecretBackendChoice.secretManager(fake, identity = kit's serviceIdentity, account = "fake-account")` in `modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit/SecretBackendChoice.scala` layering `ankka.secrets.backend`, `.secret-manager.endpoint`, `.token = fake:<project>/<service>`, `.identity` and `ankka.cloud.account` into `settings`; expose `kit.fakeSecretManager: Option[FakeSecretManager]`
- [ ] T013 Turn `SecretStoreSuite` into an abstract `SecretStoreBehaviours(backend: SecretBackendChoice)` holding every existing case, with `PostgresSecretStoreSuite extends SecretStoreBehaviours(postgres)` keeping today's names, in `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/SecretStoreBehaviours.scala` and `PostgresSecretStoreSuite.scala` (every case still green on Postgres before any store exists)

**Checkpoint**: `sbt 'runtime/testOnly *DerivedIdsSuite' 'testkit/testOnly *FakeSecretManagerSuite *PostgresSecretStoreSuite'` green; nothing reaches a network.

---

## Phase 3: User Story 1 — A service keeps and reads secrets in Secret Manager without changing (P1) 🎯 MVP

**Goal**: with `ANKKA_SECRET_BACKEND=secret-manager`, an unchanged service's `put`/`get`/`delete` go to Secret Manager under the derived id, its database holds no row, every language reaches it through the sidecar, and the pod holds no Google credential.

**Independent Test**: `SecretManagerSecretStoreSuite` runs every 023 scenario against the fake and asserts `ankka_secrets` is empty; `ConformanceSuite` `secret.*` passes under `-Dankka.conformance.secrets=secret-manager` for the Scala reference and each SDK; operator rendering shows the literals on the platform container only and no Google credential anywhere.

### Tests for User Story 1

- [ ] T014 [P] [US1] Write `SecretManagerSecretStoreSuite extends SecretStoreBehaviours(secretManager)` in `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/SecretManagerSecretStoreSuite.scala` covering `secret-manager.feature`: kept by one component read by another and `ankka_secrets` empty; kept on one instance read on the peer (`startPeer`) with no restart; kept again read by every instance; never kept reads none; removed reads none and `versionsOf` is empty; the rules refused before the fake sees a call (`calls` unchanged); a `database: none` descriptor has a working store; SC-008: a secret put 100 times holds no more than the kept count of versions (`versionsOf`), destroyed oldest first, and every `get` interleaved with the puts returned the newest value; a version destroyed is never the one read
- [ ] T015 [P] [US1] Add the `secret.*` conformance cases' second run to `sidecar/src/test/scala/com/thinkmorestupidless/ankka/sidecar/conformance/ConformanceSuite.scala`: under `-Dankka.conformance.secrets=secret-manager` the suite starts a `FakeSecretManager`, configures the runtime for it, and asserts the fake's `calls` is non-empty after the cases (a switch that ran Postgres must be red); `secretRows` asserts zero rows on that backend
- [ ] T016 [P] [US1] Write `SecretsRenderingSuite` in `operator/src/test/scala/com/thinkmorestupidless/ankka/operator/SecretsRenderingSuite.scala` covering `grants.feature` "an instance holds no credential for Google Cloud and does not read its secret key" at the rendering level: with the backend set, `ANKKA_SECRET_BACKEND`, `ANKKA_CLOUD_ACCOUNT`, `ANKKA_SECRET_VERSIONS_KEPT` (when not 2) appear on the platform container of embedded, process and wasm hostings and on no other container; no env, volume or annotation names a Google credential, key file or `iam.gke.io` annotation; `ANKKA_CLOUD_LOCATION` appears when set; `ANKKA_SECRET_RECORDS_URL` is never rendered; with nothing set the rendered Deployment equals today's exactly; `<service>-secret-key` is still rendered on the Secret Manager backend

### Implementation for User Story 1

- [ ] T017 [US1] Create `SecretManagerStore(client, account, identity, versionsKept)` in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/secrets/SecretManagerStore.scala`: `put` → rules, `addVersion`, on 404 `createSecret` (annotations; replication `automatic`, or `userManaged` in `ankka.cloud.location` when set; KMS key when set) then `addVersion` once, then prune beyond `versionsKept` oldest first (a failed destroy warns, never fails the put); `get` → rules, `accessLatest`, none on 404, `latestSkipped` computed from the version name; `delete` → rules, `deleteSecret`, 404 is success; the error mapping of research R11; names at debug, values never
- [ ] T018 [US1] Choose the backend in `Ankka.host` in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/Ankka.scala`: `SecretBackend.parse`; `Postgres` keeps today's wiring; `SecretManager` builds `AccessTokens` (fixed when `ankka.secrets.secret-manager.token` is set, else metadata), the client, and `SecretManagerStore` with the identity from the certificate or `…identity`, refusing start naming whichever of account or identity is missing; `database: none` no longer makes the store unavailable on `SecretManager`
- [ ] T019 [US1] Add `ServiceBuilder.hosting(Hosting)` (`embedded | process | module`, default `embedded`) in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/Ankka.scala` and set it from the sidecar's `Main` (`process`, or `module` when `ANKKA_WASM_MODULE` is set) in `sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/Main.scala`, so the read record (US3) and the observe document name the hosting
- [ ] T020 [US1] Render the settings in the operator: fields `secretBackend`, `secretMove`, `versionsKept`, `cloudProvider`, `cloudAccount`, `cloudLocation` in `operator/src/main/scala/com/thinkmorestupidless/ankka/operator/Settings.scala` (read from `PlatformVariables` names); `secretsEnv` in `operator/src/main/scala/com/thinkmorestupidless/ankka/operator/Rendering.scala` rendering each literal on the platform container only when set (as `telemetryEnv` does), and refusing a descriptor that sets any of them through `PlatformOnly` (already enforced by `ServiceSpec.problems`); env placeholders in `kustomization/components/operator/operator.yaml`
- [ ] T021 [US1] Wire the settings through both overlays per `contracts/platform-settings.md`: keys in `kustomization/overlays/local/platform-configmap.yaml` and `kustomization/overlays/cloud/platform-configmap.yaml`, `replacements` into the operator's and the control plane's env in both `kustomization.yaml`s, placeholders in `kustomization/components/controlplane/deployment.yaml`; extend `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/RemoteOverlaySuite.scala` to assert each variable is set once and the placeholder gone
- [ ] T022 [US1] Refuse `secret-manager` under `ANKKA_CLOUD_PROVIDER=none` at control plane start (044 FR-012) in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/ControlPlane.scala` through a `SecretBackendConfig` (backend, provider, account, location) in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/secrets/SecretBackendConfig.scala`, with a `ControlPlaneSettingsSuite` case in `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/ControlPlaneSettingsSuite.scala`
- [ ] T023 [US1] Run the SDK conformance suites against the fake-backed sidecar: document in `sdks/python/README.md`, `sdks/typescript/README.md` and `sdks/rust/README.md` that `ANKKA_CONFORMANCE_SECRETS=secret-manager` selects the backend, and plumb that variable into each SDK's `conformance` runner (`sdks/python/src/ankka/testing/conformance.py`, `sdks/typescript/src/testing/conformance.ts`, `sdks/rust/conformance.sh`) so `languages.feature`'s scenario holds on both backends

**Checkpoint**: `sbt 'testkit/testOnly *SecretManagerSecretStoreSuite' 'sidecar/testOnly *ConformanceSuite -- *secret.*' 'operator/testOnly *SecretsRenderingSuite *RenderingUnchangedSuite'` green; the second conformance run under the switch green with the fake's `calls` non-empty.

---

## Phase 4: User Story 2 — A compromised service cannot read another service's secrets (P1)

**Goal**: a service's identity is admitted to its own prefix and its project's project-secret prefix and refused everything else, by Google Cloud (the fake offline), including listing and the squat.

**Independent Test**: `GrantsSuite` drives the fake with two services' identities and the control plane's; on GKE, `ankka-gcp`'s nightly mints a ServiceAccount token and asserts `PERMISSION_DENIED`.

### Tests for User Story 2

- [ ] T024 [P] [US2] Write `GrantsSuite` in `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/GrantsSuite.scala` covering `grants.feature` with two kits on one fake (`payments` and `wallet` in `spinvibe`, `ledger` in `bank`): another service's secret refused; another project's service secret and project secret entry refused; own project's entry readable; a write to an entry refused; list refused and no name shown; deleted and deployed again (`restartService` with the same identity) reads what it kept; the squat: `wallet` creates `s_spinvibe_payments_acme` directly through the client and is refused a read, a version and a delete, while `payments`' `put` and `get` succeed

### Implementation for User Story 2

- [ ] T025 [US2] Make `FakeSecretManager`'s secret access rule a value a test can withhold (`fake.access.withhold(identity)`, `restore`) in `modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit/FakeSecretManager.scala`, and map the store's 403 to `Internal` naming the missing grant and the derived id (research R11) in `SecretManagerStore.scala`
- [ ] T026 [US2] Document the exact IAM roles and the condition (project *number*, `resource.name.startsWith`, create unconditioned, no `secrets.list`), the squat and why it is harmless, in a "Secret access on Google Cloud" section of `docs/platform/secrets.md`, as the provider's contract until 044 lands
- [ ] T027 [US2] **[044]** Render the `secret-access` request per `contracts/cloud-provider.md` (principal from the identity request's output, `own: [DerivedIds.servicePrefix]`, `read: [DerivedIds.projectPrefix]`) in `operator/src/main/scala/com/thinkmorestupidless/ankka/operator/Rendering.scala`, and hold `ApplyDeployment` in `operator/src/main/scala/com/thinkmorestupidless/ankka/operator/ServiceReconciler.scala` until its status is `Ready` for the spec's generation, reporting `status.secretStore = Waiting` with the detail, `Failed` with the provider's; add the cases to `operator/src/test/scala/com/thinkmorestupidless/ankka/operator/SecretsRenderingSuite.scala` and a k3s case with 044's fake provider to `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/SecretsClusterFeatures.scala` (T042)

**Checkpoint**: `sbt 'testkit/testOnly *GrantsSuite'` green; T027 waits for 044.

---

## Phase 5: User Story 3 — Every read of a secret can be found afterwards (P1)

**Goal**: every `get`, `put` and `delete` on either backend writes a read record to the control plane over the service's certificate before any value is returned, refused as `Unavailable` otherwise; the control plane keeps the records in a database of its own, an owner lists them, and the retention sweeps them.

**Independent Test**: three gets from two components → three records with component and kind, none with the value; a scripted control plane that fails the record makes the next get `Unavailable` with no value; the records outlive the service's database; an owner lists by name and time, a deploy token is refused.

### Tests for User Story 3

- [ ] T028 [P] [US3] Write `ReadRecordSuite` in `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/ReadRecordSuite.scala` covering `read-record.feature` on both backends (parameterised over `SecretBackendChoice`): a read while serving a request records name, project, service, hosting, time, trace id, span id, component and kind; the record holds the value nowhere (grep `RecordedReads` and the captured log); `none` and `refused` outcomes; a `put` records `written` and a `delete` records `removed`, each before the call returns; on Secret Manager, the highest version disabled in the fake makes the next read's record say `latestSkipped`; a read through the sidecar hosting records no component (drive `ClientLogic` as `ClientSecretsSuite` does, in `sidecar/src/test/scala/com/thinkmorestupidless/ankka/sidecar/ClientSecretsSuite.scala`); with `ankka.secrets.records-url` pointing at a `ScriptedService`, a `failNext(503)` makes the next `get` `Unavailable` and returns no value, and a slow answer is awaited before the handler returns
- [ ] T029 [US3] Add the Secret Manager read-record outcome `refused` case to `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/ReadRecordSuite.scala` for "a read before its secret access was written": the fake with the service's secret access withheld (T025) answers 403 and the record names `refused`
- [ ] T030 [P] [US3] Write `SecretReadsSuite` in `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/SecretReadsSuite.scala` on `AnkkaTestKit` with the `InMemoryReadRecordStore` and, in a second suite class, the `PostgresReadRecordStore` on a second `SharedPostgres` database: `POST /secret-reads` as a local caller stores a row; a body naming another service than the caller is `403` (drive the `X-Ankka-Local-Caller` header); a bearer token is refused; `GET /projects/{id}/secret-reads` answers an owner newest first, filters by `service`, `name`, `from`, `to`, caps `limit`; a member and a deploy token get `403`; the sweep removes a row older than the retention and `GET /platform` shows the retention
- [ ] T031 [P] [US3] Add the "no event carries a value" assertion for any new control plane event to `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/EventCompatibilitySuite.scala` (there should be none: the record is a row, not an event — the case asserts the journal gained no event type)

### Implementation for User Story 3

- [ ] T032 [P] [US3] Create `ReadRecord` (fields of data-model.md, jsoniter codec omitting absent optionals, never a value field) in `modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/secrets/ReadRecord.scala`, and `SecretReadRecord`/`SecretReadsPage` wire types with `Wire` codecs in `controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/descriptors.scala`, pinned in `controlplane-api/src/test/scala/com/thinkmorestupidless/ankka/controlplane/api/ControlPlaneFixturesSuite.scala`
- [ ] T033 [P] [US3] Add `CallerMatcher.AnyService` and `Callers.anyService` (admits `Caller.Service(_, _)` and `Caller.Local`, never `Gateway`) in `modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/Caller.scala` with cases in `modules/http/src/test/scala/com/thinkmorestupidless/ankka/http/CallerSuite.scala`
- [ ] T034 [US3] Create `ReadRecorder` (`trait`; `ControlPlaneRecorder(url, tls: RotatingTls, timeout)` posting with the service's certificate and `contextRequiring(ankka://platform/controlplane)`, 2xx within `ankka.secrets.record-timeout` or `CommandError(Unavailable, "read record not acknowledged: …")`; `LocalRecorder` logging one info line per record and handing it to `RecordedReads`) in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/secrets/ReadRecorder.scala`, and `RecordedReads` in `modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit/RecordedReads.scala` exposed as `kit.recordedReads`
- [ ] T035 [US3] Create `RecordingSecretStore(underlying, recorder, identity, hosting, backend, registry)` in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/secrets/RecordingSecretStore.scala` per plan.md §3 (origin from `Trace.currentOrigin`, kind from `ComponentRegistry`, trace and span from `Trace.currentContext`; backend called, record written with the outcome, then the value returned; a backend failure recorded then rethrown; a record failure → `Unavailable` and the value dropped), and wrap both backends with it in `Ankka.host` (`modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/Ankka.scala`), choosing `ControlPlaneRecorder` when `ankka.secrets.records-url` is set and `LocalRecorder` otherwise
- [ ] T036 [US3] Create the record's database component `kustomization/components/secret-reads/` (CNPG `Cluster` `ankka-secret-reads-db` in `ankka-controlplane`, one instance, `bootstrap.initdb` with `ddl/10-secret-reads.sql` from data-model.md and a `99-grants.sql` literal in its `configMapGenerator`, as `components/postgres` does), list it in `kustomization/overlays/local/kustomization.yaml` and `kustomization/overlays/cloud/kustomization.yaml`, and give the control plane `ANKKA_SECRET_RECORDS_DB_{HOST,PORT,NAME,USER,PASSWORD}` from `ankka-secret-reads-db-app` in `kustomization/components/controlplane/deployment.yaml`
- [ ] T037 [US3] Create `ReadRecordStore` (`insert`, `list(project, service?, name?, from?, to?, limit)`, `deleteOlderThan(instant)`), `PostgresReadRecordStore` over a `Database` built from `ankka.controlplane.secret-records.connection-factory` (a copy of the r2dbc block fed by the `ANKKA_SECRET_RECORDS_DB_*` variables in `controlplane/src/main/resources/reference.conf`), and `InMemoryReadRecordStore` in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/secrets/ReadRecordStore.scala`, with `SecretRecordsConfig` (the retention and the pool's config path) in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/secrets/SecretRecordsConfig.scala`
- [ ] T038 [US3] Create `SecretReadsEndpoint` per `contracts/control-plane-api.md` (`POST /secret-reads` with `Acl.allowCallers(Callers.anyService)` and the caller check; `GET /projects/{projectId}/secret-reads` owner-only through `Authorization.organizationOf` + `requireOwner`, mapping NotFound as `project` does) in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/SecretReadsEndpoint.scala`, registered in `ControlPlane.endpoints` with the store in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/ControlPlane.scala`
- [ ] T039 [US3] Create `ReadRecordSweeper` (a `ClusterSingleton` with `startTimerWithFixedDelay`, daily, deleting rows older than the retention, the `ProjectionSweeper` shape with the work on `AnkkaExecutors.virtual`) in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/secrets/ReadRecordSweeper.scala`, registered as a `RuntimeExtension` in `ControlPlane.builder`
- [ ] T040 [US3] Add `ankka projects secret-reads list -p <project> [--service] [--name] [--from] [--to] [--limit] [-o json|table]` to `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/Main.scala`, `listSecretReads` to `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/ControlPlaneClient.scala`, `secretReads(rows, format)` (columns `AT SERVICE COMPONENT NAME OPERATION OUTCOME TRACE`) to `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/Output.scala`, with a case in `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/CliEndToEndSuite.scala`
- [ ] T041 [US3] Create `PlatformEndpoint` (`GET /platform` per `contracts/control-plane-api.md`: `secretBackend`, `cloudProvider`, `cloudAccount`, `cloudLocation`, `secretRecordRetention`, `auditLog = unknown` until a provider reports it; any authenticated principal; never the KMS key) in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/PlatformEndpoint.scala` registered in `ControlPlane.endpoints`, the `PlatformStatus` wire type in `controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/descriptors.scala`, `ankka platform status` in `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/Main.scala` with `ControlPlaneClient.platformStatus` and `Output.platformStatus`, and `PlatformStatusSuite` in `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/PlatformStatusSuite.scala` (the three values shown, the KMS key absent, a deploy token admitted)
- [ ] T042 [US3] Add the release note that the control plane is applied before any service is deployed again (a service on the new runtime against an old control plane has its secret reads refused until the control plane follows) to the pull request and `docs/platform/install-cloud.md`'s upgrade section, and write `SecretsClusterFeatures` in `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/SecretsClusterFeatures.scala` per quickstart.md §8 (the record's cluster installed from the component; a get inside the sample pod leaves a row; the CLI lists it to an owner and refuses a deploy token; the row survives `services delete`; a probe pod with its own certificate posting another service's record gets `403`), gated on `ankka.cluster.tests` so `.github/cluster-suites.py` picks it up

**Checkpoint**: `sbt 'testkit/testOnly *ReadRecordSuite' 'controlPlane/testOnly *SecretReadsSuite'` green; `gh workflow run cluster -f suite=SecretsClusterFeatures` green.

---

## Phase 6: User Story 4 — A project secret is kept in Secret Manager and reaches the service (P2)

**Goal**: on the Secret Manager backend a member's `secrets set` adds versions the control plane cannot read, the project's entries are projected onto `AnkkaProject` for the sync, and (with 044) the provider keeps the Kubernetes Secret in step and the operator holds a rollout until it has.

**Independent Test**: `ControlPlaneHttpSuite` against the fake asserts an added version per entry, a disabled version on unset, and the control plane's identity refused an access; `TenancyEntitySuite` asserts the projection; the k3s case with 044's fake provider asserts the variable's value inside the pod.

### Tests for User Story 4

- [ ] T043 [P] [US4] Add the Secret Manager cases of `synced-project-secrets.feature` to `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/ControlPlaneHttpSuite.scala` with the endpoints built over `SecretManagerProjectSecretWriter` on a `FakeSecretManager`: set → `p_<project>_checkout_STRIPE_KEY` has one enabled version and `calls` shows no access; the entity records the name and entry and the response holds the value nowhere; set again → two versions; unset → every version disabled; the list shows names and entries only; the fake refuses `fake:controlplane` an access (asserted directly)
- [ ] T044 [P] [US4] Add `AnkkaProject.spec.secrets` and `secretsGeneration` projection cases to `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/TenancyEntitySuite.scala` and to the trigger's suite (`ProjectTopicsTriggerSuite` or where `ProjectTopicsTrigger` is tested): a set and an unset each raise the generation and the resource names the entries

### Implementation for User Story 4

- [ ] T045 [US4] Create `SecretManagerProjectSecretWriter(client, account)` (`setEntries`: per entry `createSecret` ignoring 409 then `addVersion`; `removeEntry`: list enabled versions, `disableVersion` each; the control plane's token from `AccessTokens.metadata` or the fixed test token; failures → the route's existing `Unavailable` mapping, naming the grant) in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/secrets/SecretManagerProjectSecretWriter.scala`, chosen by `SecretBackend` in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/ControlPlane.scala` in place of the projector
- [ ] T046 [US4] Add `secrets: Map[String, Vector[String]]` and `secretsGeneration: Long` to `AnkkaProjectSpec` in `crd/src/main/scala/com/thinkmorestupidless/ankka/crd/AnkkaProject.scala` and `kustomization/components/crd/ankkaproject.yaml` (held by `operator/src/test/scala/com/thinkmorestupidless/ankka/operator/CrdSchemaSuite.scala`), and project them from the `Project` entity's secret events in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/deploy/ProjectTopicsTrigger.scala` (rename not required; note the broadened purpose in its scaladoc)
- [ ] T047 [US4] **[044]** Render one `secret-sync` request per project secret per `contracts/cloud-provider.md` from `AnkkaProject.spec.secrets` in the operator's `ProjectReconciler` (`operator/src/main/scala/com/thinkmorestupidless/ankka/operator/ProjectReconciler.scala`), hold a service's `ApplyDeployment` in `ServiceReconciler.scala` while any request its descriptor's `secretKeyRef`s depend on reports `syncedGeneration` behind `secretsGeneration`, with `status.secretStore.detail = "waits on the entry X of Y being synced"`; cases in `operator/src/test/scala/com/thinkmorestupidless/ankka/operator/SecretsRenderingSuite.scala` and in `SecretsClusterFeatures.scala` with 044's fake provider (the variable's value inside the pod; a removed entry leaves the service not ready with the reason)
- [ ] T048 [US4] Write the "Project secrets on Secret Manager" section of `docs/platform/secrets.md` (versions, the control plane's grant, the sync, the plain statement FR-016b requires: the value is also held in the cluster's Secret store and a pod-start read is the kubelet's, not recorded per read) and note in `docs/reference/limitations.md` that the sync and the hold need the installation's cloud provider

**Checkpoint**: `sbt 'controlPlane/testOnly *ControlPlaneHttpSuite *TenancyEntitySuite'` green; T047 waits for 044.

---

## Phase 7: User Story 5 — An installation moves from Postgres to Secret Manager (P2)

**Goal**: with the backend switched and `ANKKA_SECRET_MOVE` set, each service copies its rows at start (`copy`), reports the digest comparison (`check`) and removes its rows only when every name is equal (`remove`), reporting each in its status; switching back before removal is a rollback, after removal is refused.

**Independent Test**: `SecretMoveSuite` per quickstart.md §5, grepping every captured log line, status and document for every value.

### Tests for User Story 5

- [ ] T049 [P] [US5] Write `SecretMoveSuite` in `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/SecretMoveSuite.scala` covering `moving.feature`: ten secrets on Postgres; restart with `secretManager(fake)` and `ankka.secrets.move = copy` → not ready until the fake holds ten ids (a secret the fake already held is not overwritten), the table still has ten rows, the observe document reports `Copied` with every name `Equal`; `check` with one value changed in the fake → `Different` for that name; `remove` with a difference → rows intact, `Refused` naming it; `remove` with all equal → zero rows, `Removed`, and `keyRead = false` on the next start; `unreachable(true)` during `copy` and again during `remove` → not ready, the probe body names Secret Manager, rows intact, nothing destroyed; set back to Postgres before `remove` with a secret kept since → reads the table and the report names the secret; the suite greps `LogCapturing`'s log, every observe document and every status for every value and finds none
- [ ] T050 [P] [US5] Add the FR-021 refusal case to `operator/src/test/scala/com/thinkmorestupidless/ankka/operator/SecretsRenderingSuite.scala`: a resource whose `spec.secretsRemoved` is true rendered with backend `postgres` is `Left` naming the service, so the service is `Failed` with "secrets of <service> live only in Secret Manager"; and to `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/TenancyEntitySuite.scala`: an observe document reporting `Removed` folds to a `SecretsMoved` event and the projection sets `secretsRemoved`

### Implementation for User Story 5

- [ ] T051 [US5] Give `ExtensionsReadiness` a reason per not-ready extension (`RuntimeExtension.readiness` gains an overload returning `Either[String, Unit]`, the old `Boolean` form kept) in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/ExtensionsReadiness.scala` and `Ankka.scala`, and make `ProbeEndpoint` answer `503` with the reasons as its body in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/ProbeEndpoint.scala`, with a case in `modules/runtime/src/test/scala/com/thinkmorestupidless/ankka/runtime/ProbeEndpointSuite.scala`
- [ ] T052 [US5] Create `CopyCheck` (per row: SHA-256 of the decrypted value vs SHA-256 of `accessLatest`; `Equal | Different | MissingInSecretManager | MissingInDatabase`; never a value in any result, log or exception) and `SecretMove` (a `RuntimeExtension` running the phase once on `AnkkaExecutors.virtual` at start; `copy` puts every row the fake/Secret Manager has no enabled version of, then checks; `check`; `remove` checks and `DELETE FROM ankka_secrets` only when every name is `Equal`; readiness held until the phase has run to its end in every phase, and while Secret Manager is unreachable in any phase, with the reason, changing nothing; the `MoveReport` kept for the observe document) in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/secrets/SecretMove.scala`, registered by `Ankka.host` when `ankka.secrets.move` is set and the backend is `SecretManager` (any other combination refuses start naming it)
- [ ] T053 [US5] Add the `secretStore` section (`backend`, `keyRead`, `move: {phase, outcome, names}`) to the observe document in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/ObservabilityDocuments.scala`, with `keyRead` false after a `remove` and on a Secret Manager backend with no move; read it in the control plane's `InstanceTopologies`/`StatusIngest` (`controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/deploy/StatusIngest.scala`) into `ServiceStatus.secretStore` (wire type in `controlplane-api/.../descriptors.scala`, shown by `ankka services get`); on `Removed`, `StatusIngest` sends `ServiceEntity.secretsMoved` (wire name `secrets-moved`; event `SecretsMoved(at)`, no value, pinned in `EventCompatibilitySuite`) in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/application/ServiceEntity.scala`, and `ServiceProjection` projects `secretsRemoved: true` onto the `AnkkaService` spec in `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/deploy/ServiceProjection.scala`
- [ ] T054 [US5] Add `AnkkaServiceSpec.secretsRemoved: Boolean` (default false) and `SecretStoreStatus(phase, detail)` with `AnkkaServiceStatus.secretStore` in `crd/src/main/scala/com/thinkmorestupidless/ankka/crd/AnkkaService.scala` and `kustomization/components/crd/ankkaservice.yaml` (`CrdSchemaSuite`, the codec suite), fold the status in `operator/src/main/scala/com/thinkmorestupidless/ankka/operator/ServiceReconciler.scala`'s `status(...)` (`Supplied` on Postgres; `Waiting`/`Ready`/`Failed` are T027's), and refuse a render on the Postgres backend while `spec.secretsRemoved` is true in `Rendering.render`'s problems (T050)
- [ ] T055 [US5] Write "Moving an installation to Secret Manager" in `docs/platform/secrets.md`: the three phases as ConfigMap edits and rollouts, reading the status per service (`ankka services get`), the check's words, switching back before and after `remove`, and that no person, log or event sees a value

**Checkpoint**: `sbt 'testkit/testOnly *SecretMoveSuite' 'runtime/testOnly *ProbeEndpointSuite' 'operator/testOnly *SecretsRenderingSuite *CrdSchemaSuite'` green.

---

## Phase 8: User Story 6 — A developer and the test kit need no Google Cloud (P2)

**Goal**: a local service is on Postgres unless told otherwise; a test that asks for the Secret Manager backend gets the fake; the fake refuses what Google would; nothing reaches a network.

**Independent Test**: `BackendFeatures` runs `features/secrets/backend.feature` whole against the kit with no credential in the environment.

### Tests for User Story 6

- [ ] T056 [P] [US6] Write `BackendFeatures extends GherkinSuite("../../features/secrets/backend.feature")` with its steps in `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/secrets/BackendSteps.scala` and `BackendFeatures.scala`: unset backend → rows in the table encrypted with the key and the fake's `calls` empty (no fake started: assert no `secret-manager` endpoint configured); `secretManager(fake)` → the fake holds the ids and `ankka_secrets` is empty, `GOOGLE_APPLICATION_CREDENTIALS` and every `ANKKA_CLOUD_*` absent from the kit's environment; two kits on one fake → `wallet` refused `payments`' secret as Google would (reuse `GrantsSuite`'s steps through `ranElsewhere` where a scenario is proved there)

### Implementation for User Story 6

- [ ] T057 [US6] Make `LocalRecorder` the recorder and `postgres` the backend whenever the URL and the backend are unset, with a startup info line naming both, in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/Ankka.scala`; assert in `BackendSteps` that a local platform with `ANKKA_SECRET_BACKEND=secret-manager` and no account refuses start naming `ANKKA_CLOUD_ACCOUNT`
- [ ] T058 [US6] Write "On your own machine and in tests" for the backend in `docs/build/secrets.md` (the Postgres default, `SecretBackendChoice.secretManager` in `AnkkaTestKit`, `FakeSecretManager`'s controls, `RecordedReads`), with the snippet taken from `BackendSteps` through a `// docs:start`/`// docs:end` region and `just docs-sync`

**Checkpoint**: `sbt 'testkit/testOnly *BackendFeatures'` green with the network cable out.

---

## Phase 9: Polish & Cross-Cutting

- [ ] T059 [P] Add the generated rows and the hand-written sections for `POST /secret-reads` and `GET /projects/{projectId}/secret-reads` to `docs/reference/control-plane-api.md` (`sbt -Dankka.docs.update=true 'controlPlane/testOnly *ControlPlaneRoutesReferenceSuite'`, then the prose), and for `ankka projects secret-reads list` to `docs/reference/cli.md` (`just docs-reference`)
- [ ] T060 [P] Regenerate the configuration tables and write the sentence per new key in `docs/reference/configuration.md` (`just docs-sync`; the coverage check fails on a key the prose does not mention), and document the six platform variables in `docs/platform/install-cloud.md`'s ConfigMap section with the Google prerequisites of research R12 ("Verifying an installation" checklist)
- [ ] T061 [P] Write "Choosing a backend", "Secret Manager", "The read record" (what it holds, where it is kept, that a read depends on the record's keeper, the retention, listing it) in `docs/platform/secrets.md` and the service-side view (the record, the quota note of the edge case, no cache) in `docs/build/secrets.md`; add to `docs/reference/limitations.md`: Secret Manager on GKE with Workload Identity only, no per-secret kept count, no mountable project secret, the record not in the console, the sync and the rollout hold need the installation's cloud provider; mirror the changed pages into the skill copies with `just docs-sync`
- [ ] T062 [P] Add the traps learned to `.claude/rules/secrets.md` (the backend seam; the record is fail-closed and why; `DerivedIds` and why `_`; the fake's identity token; the record address as the Kubernetes overlay's default so nothing rolls; Group B's dependence on 044) and the record cluster to `.claude/rules/kubernetes.md`'s Schema section ("not one of the seven lists, and why")
- [ ] T063 Run `just features` and `just docs`, then the full offline set from quickstart.md §2–§7 under `caffeinate -i sbt -Dankka.cluster.tests=off test`, and `gh workflow run cluster --ref 038-secret-store-backends-impl -f suite=SecretsClusterFeatures`; record the SC-006 timing (both secret store suites against `SecretStoreSuite` on `main`, within +10%) in the pull request
- [ ] T064 Extend `.github/ci-coverage.py` for `kustomization/components/secret-reads/**` and the new test files, and `.github/cluster-suites.py`'s expectations if `SecretsClusterFeatures` needs its own runner time; update `README.md`'s secrets line if it names the backend
- [ ] T065 **[044]** Once 044 is on `main`: rebase, implement T027 and T047, add the Data Access audit-logging status (FR-014) read from the provider's status into the installation settings page and `services get`, and the control plane's own `secret-access` manifest in `kustomization/components/controlplane/` per `contracts/cloud-provider.md`; until then `docs/platform/secrets.md` states that the grant and the sync are made by the provider or by hand

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 → Phase 2 → stories.** T001 and T002 before everything (names and keys); T005–T013 before any story.
- **US1 (Phase 3)** needs Phase 2. **US2** needs US1's store (T017) and the fake's secret access rule (T010). **US3** needs T019 (hosting) and the kit (T012); its control plane half (T036–T040) is independent of US1. **US4** needs T009 (client) and T012. **US5** needs T017, T018 and the observe document; T054 needs T050. **US6** needs T012 and T034.
- **[044] tasks** (T027, T047, T065) wait for spec 044's implementation and are not on the MVP path.

### Story order

1. US1 (the store) → MVP
2. US3 (the record): a P1 the regulator needs; its control plane half can be built beside US1
3. US2 (grants): mostly proven by the fake once US1 exists; the operator half is 044's
4. US6 (local and test): largely done by Phase 2; the feature suite closes it
5. US5 (the move)
6. US4 (project secrets): the writer now; the sync with 044

### Parallel opportunities

- Phase 1: T003 ∥ T004 after T001.
- Phase 2: T006 ∥ T007 ∥ T008; T010 after T009; T012 and T013 ∥ T010.
- US1: T014 ∥ T015 ∥ T016 (tests first); then T017 → T018; T019 ∥ T020 ∥ T021 ∥ T022 ∥ T023.
- US3: T028 ∥ T030 ∥ T031; T032 ∥ T033; T034 → T035; T036 ∥ T037 → T038 → T039; T040 ∥ T042.
- US5: T049 ∥ T050; T051 ∥ T052 → T053 → T054.
- Polish: T059 ∥ T060 ∥ T061 ∥ T062.

### Parallel example: User Story 3

```bash
# tests first, in parallel
Task: "ReadRecordSuite in modules/testkit/src/test/.../testkit/ReadRecordSuite.scala"
Task: "SecretReadsSuite in controlplane/src/test/.../controlplane/SecretReadsSuite.scala"
# then the two halves side by side
Task: "ReadRecord in core + wire types in controlplane-api"       # T032
Task: "CallerMatcher.AnyService in http"                           # T033
Task: "components/secret-reads + control plane env"                # T036
```

## Implementation Strategy

**MVP = Phase 1 + Phase 2 + US1.** A service on an installation set to `secret-manager` keeps and
reads secrets in Secret Manager (the fake offline, Google on GKE with a grant made by hand), with
no change to its code, and every language through the sidecar. Stop, run quickstart.md §2–§3, and
measure SC-006.

**Second increment: US3.** The record on both backends, fail-closed, listed by an owner, swept —
the thing the first user's regulator asks for. It ships in a release of its own with the note that
the control plane is applied before any service is deployed again.

**Then US2 + US6** (cheap once the fake exists), **US5**, **US4's writer**; **the [044] tasks** when
044 lands, in a follow-up branch rebased on it.

## Notes

- A test that passes in seconds on both backends ran the fake for neither: every Secret Manager
  suite asserts the fake's `calls` is non-empty.
- Never log, assert on or print a value: the suites grep the captured log for every value they use.
- Render nothing when a setting is unset; an installation that sets nothing has no pod-template
  change at all (the record's address is the runtime overlay's default).
- Commit after each checkpoint; the squash-merge is of the branch.
