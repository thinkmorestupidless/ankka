# Tasks: Object Storage on Google Cloud Storage

**Input**: Design documents from `/specs/039-gcs-object-storage/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: included. `CLAUDE.md` asks that every acceptance scenario end as a test that fails
without the feature, and the plan cuts each slice "tests before the code they hold". A test task
is written first and must fail before its implementation task passes it.

**Organization**: by user story, in the spec's priority order (US1, US2, US3 at P1; US4, US6 at
P2; US5 at P3). Phase 2 holds what every story needs. Feature 044 gates Phase 3 onward (research
R1): Phases 1 and 2 need neither 044 nor a cluster, except Phase 2's last two tasks (T024, T025),
which extend 044's fake and are the first work after the gate.

**Re-planned 2026-10-10 against 044 as merged** (#101, research R1a). 044 built the cloud path for a
bucket, `CloudResource` and a scripted provider, and made none of the amendments R1 asked; the open
tasks below are rewritten onto what it built, and T087–T091 make the amendments and settle the
differences (decisions D1–D11).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story the task belongs to (US1–US6)
- Paths are from the repository root. `OP` = `operator/src/main/scala/com/thinkmorestupidless/ankka/operator/`,
  `OPT` = `operator/src/test/scala/com/thinkmorestupidless/ankka/operator/`,
  `CP` = `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/`,
  `CPT` = `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/`,
  `API` = `controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/`,
  `CRD` = `crd/src/main/scala/com/thinkmorestupidless/ankka/crd/`,
  `CLI` = `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/`.

---

## Phase 1: Setup (the gate, the spikes, the new module)

**Purpose**: settle what the plan rests on before anything is built on it (research "Verify
first"), and create the one new module.

- [X] T001 Confirm feature 044 is merged. *Done:* merged as #101 (`a5b4b06c`) without the amendments; research R1a records each difference from what this plan assumed and its decision (D1–D11), and T087–T091 carry them out.
- [X] T002 [P] Spike S1: add three cases to `OPT/GarageStoreSuite.scala` against the pinned `dxflrs/garage:v2.3.0` proving `POST /v2/UpdateBucket` with `corsRules` answers a preflight for a named origin and not an unnamed one, `AllowBucketKey` with `write: false` refuses a `PutObject` and serves a `GetObject`, and `UpdateKey` with a past `expiration` makes `GetKeyInfo` report `expired` and the key refused. If any fails, amend R5/R6 in `research.md` before T030.
- [ ] T003 [P] Spike S2: prepare a test bucket by hand (versioning on, soft delete 7 days, public access prevention, uniform access, CORS admitting `https://play.example`, an HMAC key of a service account with `roles/storage.objectUser`) and record the project, bucket and secret names (never the key) in `specs/039-gcs-object-storage/contracts/installation.md` under "The bucket is prepared once". Run T066's suite by hand once it exists.
- [X] T004 [P] Create the `storage-mover` module: `lazy val storageMover = project.in(file("storage-mover"))` in `build.sbt` with `dockerSettings`, `Docker / packageName := "ankka-storage-mover"`, `libraryDependencies ++= Seq(awsS3, logback)`, no ankka dependency; add it to `root`'s aggregate and to `buildAll`'s image list; `storage-mover/src/main/scala/com/thinkmorestupidless/ankka/mover/Main.scala` printing its version and exiting 2.
- [X] T005 [P] Add `storage-mover/**` to the `scala-build` and `scala` filters in `.github/workflows/ci.yml`; run `python3 .github/ci-coverage.py` to confirm every file is claimed.
- [X] T006 [P] Add `ankka.gcs.tests` to the forwarded `-D` list in `build.sbt` (`Test / javaOptions`, lines ~140–173) and confirm with a throwaway test that the forked JVM sees it.

---

## Phase 2: Foundational (settings, fields, the store's new operations, the test scaffolding)

**Purpose**: what every story renders against. No cluster, no 044, except the two tasks under
"After the gate".

**⚠️ CRITICAL**: Phases 3–8 depend on this phase.

### Settings and names

- [X] T007 [P] Declare `ObjectStoreBackend`, `ObjectStorePrefix`, `ObjectStoreSoftDeleteDays` in `modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/PlatformVariables.scala`, in none of the lists (they are installation settings, never rendered on a pod; `PlatformOnly` is pinned as what is); extend `modules/core/src/test/scala/com/thinkmorestupidless/ankka/core/PlatformVariablesSuite.scala` and `CPT/PlatformDeclarationSuite.scala`.
- [X] T008 [P] Test in `OPT/SettingsSuite.scala`: `ANKKA_OBJECT_STORE_BACKEND` defaults to `garage` when the admin URL is set and to none otherwise; `gcs` without `ANKKA_CLOUD_PROVIDER` or without `ANKKA_OBJECT_STORE_PREFIX` fails at startup naming the setting; `ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS` defaults to 7 and refuses 6 and 91; `ANKKA_OBJECT_STORE_GCS_ENDPOINT` defaults to `https://storage.googleapis.com`; `ANKKA_STORAGE_MOVER_IMAGE` defaults to `ankka-storage-mover:<BuildInfo tag>`.
- [X] T009 Implement T008 in `OP/Settings.scala` and `OP/ObjectStoreSettings.scala`: `ObjectStoreBackend` enum (`Garage`, `Gcs`), `GcsSettings(prefix, softDeleteDays, endpoint)`, `moverImage`; each with its `ankka.operator.*` system property.
- [X] T010 [P] Create `CPT/DeployConfigSuite.scala` (none exists; `DeployConfig` is read only through the cluster suites today): the control plane reads the three settings and the cloud provider; `gcs` with provider `none` is a startup failure; soft-delete days outside 7–90 refused.
- [X] T011 Implement T010 in `CP/deploy/DeployConfig.scala`: `objectStoreBackend`, `objectStorePrefix`, `softDeleteDays`, `cloudProvider`, and `canMoveStorage` (backend `gcs` and a Garage store named).
- [X] T012 [P] Add `cloudSecret(service) = s"$service-cloud-storage"` and `gcsPublicAddress(bucket)` to `CRD/Buckets.scala`; extend `crd/src/test/scala/com/thinkmorestupidless/ankka/crd/BucketsSuite.scala`; confirm `CPT/ReservedSecretNamesSuite.scala` still refuses the new suffix form (it ends in `-storage`).

### The resources

- [X] T013 Add to `CRD/AnkkaService.scala`: spec `objectStorageOrigins: List[String] = Nil`, `objectStorageCredential: Boolean = true`, `objectStorageVersionAgeDays: Option[Int] = None`, `storageCredentialGeneration: Int = 0`, `objectStorageSettingsGeneration: Int = 0`, `objectStorageMove: Option[ObjectStorageMoveRequest] = None` (`generation: Int`, `writePauseBound: String`); status `ObjectStorageStatus` gains `store: String`, `location: Option[String]`, `softDeleteDays: Option[Int]`, `credentialGeneration: Int = 0`, `move: Option[MoveStatus]` with the fields of `data-model.md`; `@JsonDeserialize(contentAs = classOf[java.lang.Long])` is not needed (no `Option[Long]`), but `generation` fields are `Int`.
- [X] T014 Add `bucketLocation: Option[String] = None` to `AnkkaProjectSpec` in `CRD/AnkkaProject.scala`.
- [X] T015 Declare every field of T013 and T014 in `kustomization/components/crd/ankkaservice.yaml` and `ankkaproject.yaml`; extend `OPT/CrdSchemaSuite.scala` to compare inside `objectStorage.move` and `objectStorageMove` in both directions. The suite must fail if a field is missing from either side.

### The descriptor

- [X] T016 [P] Test in `controlplane-api/src/test/scala/com/thinkmorestupidless/ankka/controlplane/api/ObjectStorageDescriptorSuite.scala`: the three fields default and are omitted at default; `objectStorageCredential: false` survives a JSON round trip; each needs `provisionObjectStorage`; an origin that is not `https://host[:port]`, `http://host[:port]` or `*` is refused naming the value; `objectStorageVersionAgeDays: 0` refused.
- [X] T017 Implement T016 in `API/descriptors.scala`: the three `ServiceSpec` fields, `objectStorageProblems` extended, `Origins.problems`; `ServiceStatus` gains `objectStore`, `bucketLocation`, `softDeleteDays`, `storageMove: Option[String]`; new wire types `StorageMoveRequest(writePauseBound: Option[String])` and `SetProjectLocation(location: String)`.
- [X] T018 [P] Test in `CPT/ServiceProjectionSuite.scala`: the six spec fields projected; the name-length refusal applies only when the backend is `garage`; `objectStorageMove` projected with the bound.
- [X] T019 Implement T018 in `CP/deploy/ServiceProjection.scala` (project the fields; gate the `Buckets.problems` call on `config.objectStore == Garage`; `ServiceProjection.objectStorageProblems`, shared with the apply in `ServiceEndpoint`, also refuses a declined credential on Garage and `gcs` with no cloud provider). Writing `bucketLocation` onto `AnkkaProject` needs the project's location event and moved to T053.

### The store's new operations

- [X] T020 [P] Test in `OPT/ObjectStoreSuite.scala` and `OPT/GarageStoreSuite.scala` (beyond T002): `allow(bucket, key, write = false)` grants read only; `setCors(bucket, Seq("https://play.example"))` then `setCors(bucket, Nil)` clears; `expire(key, at)`; `keyInfo(key)` returns name and `expired` and never sends `showSecretKey` (assert with `onRequest`).
- [X] T021 Implement T020 in `OP/ObjectStore.scala` (`allow` with `write`, `setCors`, `expire`, `keyInfo`, `KeyInfo`) and `OP/GarageStore.scala`; 034's `allow(bucket, key)` callers pass `write = true`; update the in-memory `World` double in `OPT/StorageCredentialSuite.scala` to model permissions, CORS, expiry.

### Test scaffolding

- [X] T022 [P] Add `ranOutside: Map[String, String]` (scenario name → reason) to `modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit/GherkinSuite.scala`: a listed scenario is reported as skipped with its reason, never run; a listed name that matches no scenario fails the suite; two cases in `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/GherkinSuiteSuite.scala` prove both.
- [X] T023 [P] `OPT/BucketNames.scala` (test sources): the contract's name rule (`<prefix>-<project>-<service>-<digest8>`, SHA-256 over `<project>.<service>`, shortening from the right of the service then the project, 63 characters, `[a-z0-9-]`); `OPT/BucketNamesSuite.scala`: the two colliding pairs of `names.feature` differ, a long pair fits 63 with its digest intact.
### After the gate (T001)

- [X] T087 [P] What 039 adds to 044's contract (R1a D3, D4, D6): in `OP/CloudRequests.scala`, `bucket` takes `namePrefix` and `noncurrentVersionDays` (`Keys.parameters`; `BucketAsk` gains both) and `identity` answers `serviceAccountAnnotations` (`Keys.outputs`, `key=value` pairs comma-separated); `OPT/CloudRequestsSuite.scala`'s exact-keys cases follow; `docs/platform/cloud-provider.md` names both keys, the output and the bucket name rule of `contracts/cloud-requests.md`; `OPT/cloud/ScriptedFulfilment.scala` names a service's bucket by `BucketNames` and answers the annotations. `CloudProviderClusterFeatures` stays green.
- [X] T088 [P] One rotation grace (R1a D8): `OP/Settings.scala` reads `rotationGrace` from `ANKKA_CLOUD_ROTATION_GRACE` through `CloudSettings.duration`, with or without a provider named, and `ANKKA_ROTATION_GRACE_SECONDS` goes; `OPT/SettingsSuite.scala` (the default, a value, a malformed value refused); `CPT/ObjectStorageClusterFeatures.scala` sets its 30-second grace by the new name.
- [X] T089 Where a bucket is (R1a D1, D11): `OP/ObjectStorage.scala` `storeOf(spec, settings, observed, status)` per `data-model.md`'s table replaces `takesCloudPath` at every call (`decide`, `withheld`, `publicAddress`, `status`, `cloudRequests`, `Rendering.objectStorageActions`, `Rendering.storageEnv`, `ServiceReconciler`); `OP/Settings.scala`'s backend left unset is `garage` with Garage installed, else `gcs` with a provider named, else none; `GcsSettings.endpoint` and `ANKKA_OBJECT_STORE_GCS_ENDPOINT` go. Tests in `OPT/ObjectStorageSuite.scala` and `OPT/SettingsSuite.scala`: one case per row of the table, and an installation as 044 served it (a provider, no Garage, no backend) rendering what it rendered: `RenderingGoldenSuite`'s `cloud-bucket.txt` unchanged by this task. *Done as* `takesCloudPath(spec, settings, reported)` *and* `Settings.bucketBackend`*: the store is read from the status the operator last wrote, not by asking Garage, so the function every caller already used keeps its name and the compiler found every caller.*
- [X] T090 The cloud's Secret (R1a D2): `CRD/Buckets.scala` `gcsSecret` becomes `cloudSecret` = `<service>-cloud-storage` (`BucketsSuite`, `CPT/ReservedSecretNamesSuite.scala`, the move Job in `OP/Rendering.scala` and `OPT/MoveJobRenderingSuite.scala` follow); the credential request's `secretName` and the developer's `envFrom` on `gcs` are it; `cloud-bucket.txt` repinned for that one name and nothing else; `features/cloud-provider/bucket.feature` and `credential.feature` say `reports-cloud-storage`, and `CPT/CloudProviderClusterFeatures.scala` follows.
- [X] T091 [P] The descriptor's bucket asks reach the request (R1a D3): `ObjectStorage.cloudRequests` takes the spec, the installation's settings and the project's location, and fills `BucketAsk`: `versioning: true`, `softDeleteDays` from `GcsSettings`, `corsOrigins` = `objectStorageOrigins` while `exposeObjectStorage`, else none, `namePrefix`, `noncurrentVersionDays` = `objectStorageVersionAgeDays`, `location` = the project's `bucketLocation`, else `ANKKA_CLOUD_LOCATION`. Tests in `OPT/CloudRequestsSuite.scala`. (T048 and T052 add the rule that keeps the window and the key after the first render.)
- [X] T024 Give 044's `OPT/cloud/ScriptedFulfilment.scala` a Garage-backed mode (R1a D7), chosen by `CloudProviderStack`: a `bucket` request makes a Garage bucket (through `GarageStore`) named by `BucketNames`, applies `corsOrigins` with `setCors`, and answers Garage's S3 port as `endpoint` and `garage` as `region`; a `bucket-credential` request mints a Garage key allowed on it and writes `secretName` by `create` (409 → end the key, report the existing Secret); a raised `credentialGeneration` mints a new key, patches the Secret, reports the generation, and expires the old key after `ANKKA_CLOUD_ROTATION_GRACE`; an `identity` request answers `identity` and `serviceAccountAnnotations` `scripted.example/identity=<service>`; its scripted `Failed` and `Waiting` answers work in this mode too. The made-up mode stays as 044's suite uses it. Unit cases in `OPT/ScriptedCloudProviderSuite.scala` against the `World` double of `OPT/StorageCredentialSuite.scala`.
- [X] T025 Create `CPT/ObjectStorageGcsClusterFeatures.scala` extending `GherkinSuite("../features/object-storage-gcs") with LogCapturing`, gated by `ankka.cluster.tests` as `ObjectStorageClusterFeatures` is: the stack (PKI, `ObjectStoreStack`, 044's `CloudProviderStack` in the Garage-backed mode of T024, the operator with `ANKKA_OBJECT_STORE_BACKEND=gcs`, `ANKKA_OBJECT_STORE_PREFIX=t`, `ANKKA_CLOUD_PROVIDER=gcp` with an account and location, `ANKKA_CLOUD_ROTATION_GRACE=20s`, the sample image by `BuildInfo.imageTag`), the `ranOutside` map of quickstart.md §3 with reasons, `ranElsewhere` for the scenarios 044's `CloudProviderClusterFeatures` already runs, and the shared `Given` steps (`an installation whose object store is Google Cloud Storage`, `a descriptor for a service … that asks for a bucket`, `a deployed service … with a bucket`). Every step the feature files use and no story has yet implemented fails the scenario naming it.

**Checkpoint**: `sbt -Dankka.cluster.tests=off test` is green; `RenderingUnchangedSuite` unchanged (no object differs for a service on Garage); 044 merged.

---

## Phase 3: User Story 1 — A service on a GCS installation gets a bucket its existing code can use (Priority: P1) 🎯 MVP

**Goal**: a descriptor unchanged from Garage is given a bucket in GCS through the three requests; the five variables; the status names the bucket, store and location as the provider reported them; a held name is `Failed`, an unreachable Google `Waiting`; two colliding names get two buckets.

**Independent Test**: `ObjectStorageGcsClusterFeatures` runs every scenario of `provisioning.feature` and `names.feature` with the fake and Garage standing in for Google (quickstart §3); by hand on GKE, quickstart §6 steps 1–2.

### Tests for User Story 1

- [X] T026 [P] [US1] Test in `OPT/CloudRequestsSuite.scala` (044's, extended): from a spec, settings and a project, the `bucket` request's parameters as T091 fills them; the `bucket-credential` request absent until the identity and the bucket are `Ready`, then present with `bucket`, `identity`, `secretName: <service>-cloud-storage` and `credentialGeneration` = the spec's count plus one; the `settings-generation` annotation. *Done:* Covered in `OPT/ObjectStorageRenderingSuite.scala` (the bucket's asks, the credential's Secret and generation, the settings annotation) beside 044's `CloudRequestsSuite` cases.
- [X] T027 [P] [US1] Test in `OPT/ObjectStorageSuite.scala` (044's `fold` cases stand: no answer past the bound, `Waiting`, `Failed`, `Ready` recovered or not): `status` on `gcs` carries `store: gcs`, the reported `bucket`, `location` and `softDeleteDays` from the bucket fulfilment, and `publicAddress` `<endpoint>/<bucket>` when exposed; the name-length rule is not applied on `gcs`. *Done:* Covered in `OPT/ObjectStorageRenderingSuite.scala` and `OPT/ObjectStorageSuite.scala`.
- [X] T028 [P] [US1] Test in `OPT/ObjectStorageRenderingSuite.scala` (044's `cloud:` cases stand: the requests, no instance while waiting, the variables from the fulfilment, no route): `EnsureServiceAccountAnnotations` once the identity is `Ready`; `envFrom: <service>-cloud-storage`; a service whose `status.objectStorage.store` is `garage` stays on Garage on a `gcs` installation; the hostings as 034 (embedded, process `<service>-app`, web, wasm). *Done:* Covered by 044's `cloud:` cases and the ServiceAccount, Secret and store cases added beside them.
- [X] T029 [P] [US1] Test in `CPT/StatusIngestSuite.scala`: `store`, `bucket`, `publicAddress`, `location`, `softDeleteDays` read from the status onto `ServiceObserved`; `detail` prefixed `object storage:`; `CPT/EventCompatibilitySuite.scala` pins the new `Option` fields defaulting to `None`.

### Implementation for User Story 1

- [X] T030 [US1] `OP/Action.scala` gains `EnsureServiceAccountAnnotations(namespace, name, annotations)` and `OP/Executor.scala` performs it by merge patch (`describe` prints the names); `OP/Rendering.scala` renders it from the identity fulfilment's `serviceAccountAnnotations`. (044 supplies `CloudRequests` and `EnsureCloudResource`; T091 fills the bucket's asks.) *Done as* `render(…, serviceAccountAnnotations)` *onto the ServiceAccount the operator already applies, rather than a second action patching it: one writer of the object, and the annotations read from the identity's answer whenever it is ready.*
- [X] T031 [US1] `OP/ObjectStorage.scala`: `status` on `gcs` with `location`, `softDeleteDays` and `publicAddress` per T027 (044's `CloudBucketPlans` and `fold` stand; T089 supplies `storeOf`).
- [X] T032 [US1] `OP/Rendering.scala`: the developer's `envFrom` on `gcs` is `Buckets.cloudSecret` (T090); `ANKKA_S3_PUBLIC_ENDPOINT` on an exposed bucket on `gcs` is the fulfilment's `endpoint`; nothing else of 044's cloud rendering changes.
- [X] T033 [US1] `OP/ServiceReconciler.scala` passes the project's `bucketLocation` into `ObjectStorage.cloudRequests` (read through `Executor.projectBrokers`'s sibling, `projectLocation`, with a default and the Fabric8 override in `OP/Executor.scala`), and the identity fulfilment's annotations into rendering; 044 already observes the three requests and writes the plan's status. *Done:* `Executor.projectLocation`, read in `decideCloudBucket`; the identity's annotations passed to `render`.
- [X] T034 [US1] `OPT/RenderingGoldenSuite.scala`'s `cloud-bucket.txt` (044's: a service on the cloud path at each phase) is repinned only for T090's Secret name and T030's annotations action, each named in its commit; `RenderingUnchangedSuite` passes with no repin. *Done:* `cloud-bucket.txt` repinned for exactly the named changes; `RenderingUnchangedSuite` never repinned.
- [X] T035 [US1] `CP/deploy/StatusIngest.scala`, `CP/domain/events.scala` (`ServiceObserved` fields), `CP/domain/model.scala` (`objectStore`, `bucketOf`/`bucketPathOf` answer from the observed values when present, else derive Garage's; the phrase for `objectStore`), `CP/application/ServiceEntity.scala`, `CP/application/ServiceRows.scala`, `CP/api/ServiceEndpoint.scala` (the status's new fields; `bucketAddress` from the status on `gcs`; refuse a `gcs` apply when the provider is `none`, naming it).
- [X] T036 [US1] `CLI/Output.scala` prints `object store`, `bucket location`, `soft delete`; `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/OutputSuite.scala` covers them.
- [ ] T037 [US1] (Written; waits on a green k3s run.) Steps in `CPT/ObjectStorageGcsClusterFeatures.scala` for every scenario of `features/object-storage-gcs/provisioning.feature` and `names.feature`: apply, the pod's variables (`InPod`), a put and a get from the pod with the docs' client settings, `services get`, the scripted provider told to answer `Failed` (name held) and `Waiting` (cannot be reached), the two colliding pairs. Run the suite on k3s; every scenario green or in `ranOutside`.

**Checkpoint**: US1 scenarios pass on k3s with the fake; a service on Garage renders what it rendered.

---

## Phase 4: User Story 2 — A credential reaches one bucket, and the operator touches no Google (Priority: P1)

**Goal**: the operator holds no Google client, credential or permission and reads only statuses; neither it nor the provider reads a credential back; a credential can be issued again on both stores, the service restarts on the new one, and the old one is refused once the rotation grace has passed.

**Independent Test**: `isolation.feature`'s operator and re-issue scenarios on k3s with the fake (quickstart §3); the Google-refusal, workload-identity and grant scenarios in `ranOutside`, proved by ankka-gcp's nightly; 034's changed re-issue scenario on Garage in `ObjectStorageClusterFeatures`; quickstart §6 step 3 by hand.

### Tests for User Story 2

- [X] T038 [P] [US2] Test in `OPT/StorageCredentialSuite.scala` against the `World` double: `reissue(…, generation = 2)` creates `<bucket>#2` allowed read/write/owner, patches the Secret, sets expiry `now + grace` on `<bucket>` and `<bucket>#1` and not on `#2`; a second call with the same generation issues nothing and only re-expires; `deleteExpired` removes keys the store reports expired and no other; nothing reads the Secret.
- [X] T039 [P] [US2] Test in `OPT/ObjectStorageRenderingSuite.scala`: the pod template carries `ankka.thinkmorestupidless.com/storage-credential-generation` = the status's generation in place only for a service with a platform bucket (absent otherwise, so nothing already rendered changes); on `garage`, `ReissueStorageCredential` rendered when the spec's generation exceeds the status's, then `ExpireKeysBelow`, `DeleteExpiredKeys`; on `gcs` the credential request's `credentialGeneration` = the spec's count plus one and the annotation = the fulfilment's. *Done:* the Google Cloud Storage half by 044's `cloud:` cases, kept in the rebase.
- [X] T040 [P] [US2] Test in `CPT/ServiceEntitySuite.scala`: `reissueStorageCredential` persists `StorageCredentialReissued(generation + 1, actor, at)`, history `storage-credential-reissued`, `storageCredentialGeneration` folds; refused with "no storage credential to reissue" without `provisionObjectStorage`; refused with "storage is moving" during a move; `CPT/EventCompatibilitySuite.scala` pins the event.
- [X] T041 [P] [US2] Test in `CPT/ControlPlaneHttpSuite.scala`: `POST /services/{p}/{n}/storage-credential` needs write access, answers 202 with the status, 409 with the reason when refused.

### Implementation for User Story 2

- [X] T042 [US2] `OP/StorageCredential.scala`: `reissue`, `expireBelow`, `deleteExpired`, key names `<bucket>#<n>` (`<bucket>` is generation 0); `OP/Action.scala` gains `ReissueStorageCredential`, `ExpireKeysBelow`, `DeleteExpiredKeys`; `OP/Executor.scala` performs them with the rotation grace from 044's settings.
- [X] T043 [US2] `OP/Rendering.scala`: the credential annotation on the pod template; the Garage re-issue actions; `OP/ServiceReconciler.scala` records `credentialGeneration` in the status (the fulfilment's on `gcs`, `reissue`'s on `garage`). *Done:* the Google Cloud Storage half in the rebase of 044 (`ObjectStorage.cloudGeneration`).
- [X] T044 [US2] `CP/domain/events.scala` (`StorageCredentialReissued`), `CP/domain/model.scala` (fold, `remember`), `CP/application/ServiceEntity.scala` (`reissueStorageCredential`, the two refusals), `CP/api/ServiceEndpoint.scala` (`post("/{projectId}/{name}/storage-credential")` beside `restart`), `CP/deploy/ServiceProjection.scala` (`storageCredentialGeneration`).
- [X] T045 [US2] `CLI/Main.scala` `services storage reissue`, `CLI/ControlPlaneClient.scala` `action(…, "storage-credential")`, `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/mcp/AnkkaTools.scala` description; a case in `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/CliReferenceSuite.scala` (the generated reference gains the command) and the routes reference in `CPT/ControlPlaneRoutesReferenceSuite.scala`.
- [ ] T046 [US2] Steps in `CPT/ObjectStorageGcsClusterFeatures.scala` for `isolation.feature`'s re-issue scenarios on `gcs`: a member's `services storage reissue`, a new key in `<service>-cloud-storage`, the service restarted on the fulfilment, the first key refused by Garage 20 s after, a pod held back (a readiness gate the suite sets) refused once the grace has passed. `ranElsewhere` for "neither the operator nor the cloud provider can read a storage credential back" and "the operator holds no Google client …" (044's `credential.feature` in `CloudProviderClusterFeatures`, and `OperatorClusterSuite`'s minted-token cases); `ranOutside` for the Google-only scenarios with their reasons.
- [X] T047 [US2] Steps in `CPT/ObjectStorageClusterFeatures.scala` for 034's changed/added scenarios in `features/object-storage/isolation.feature` ("a storage credential is made once and replaced only when a member asks"; "a storage credential issued again at a member's asking replaces the one the service had") on Garage: the second key, the restart, the old key refused after the suite's shortened grace, the history line.

**Checkpoint**: re-issue works on both stores on k3s; `OperatorClusterSuite`'s RBAC case still green.

---

## Phase 5: User Story 3 — Documents are kept against accident and every version can be deleted (Priority: P1)

**Goal**: every GCS bucket is versioned with the installation's soft-delete window and no retention policy, encrypted with the wrapping key when named, made in its project's or the installation's location; the status says the window and the location; the window is reapplied only on request; a project's location is a member's to set.

**Independent Test**: the location scenarios of `retention.feature` on k3s with the fake (the request's `location` and the status); the versioning, deletion and retention-policy scenarios by `GcsCompatibilitySuite` against the real bucket (nightly) and ankka-gcp's nightly; quickstart §4.

### Tests for User Story 3

- [X] T048 [P] [US3] Test in `OPT/CloudRequestsSuite.scala`: `softDeleteDays` and `kmsKey` taken from the installation at first render and kept from the existing request afterwards; rewritten only when `objectStorageSettingsGeneration` exceeds the request's annotation, which is then stamped; `location` never rewritten once the request exists.
- [X] T049 [P] [US3] Test in `CPT/TenancyEntitySuite.scala` (where the `Project` entity is tested): `setLocation` persists `ProjectLocationSet(location, actor, at)`, history `location-set`; `clearLocation`; `CPT/ServiceEntitySuite.scala`: `reapplyStorageSettings` persists `StorageSettingsReapplied`, history `storage-settings-reapplied`, refused when the bucket is not on `gcs`; `EventCompatibilitySuite` pins both events.
- [X] T050 [P] [US3] Test in `CPT/ControlPlaneHttpSuite.scala`: `PUT`/`DELETE /projects/{id}/location` (409 on a non-`gcs` installation; the project's history); `POST /services/{p}/{n}/storage/settings`.
- [X] T051 [P] [US3] Written (runs against Google only with T003's bucket; skips otherwise). Create `CPT/GcsCompatibilitySuite.scala`: `assume`-skipped without `ANKKA_GCS_BUCKET`, `_ACCESS_KEY`, `_SECRET_KEY`, failing instead under `CI` with `-Dankka.gcs.tests=on`; never reads `ankka.cluster.tests`; first a case proving a SigV4 request with region `auto` in its scope is accepted (S2; if refused, the region becomes a setting and FR-004 is amended); then tests named for the `retention.feature` scenarios: an overwritten object readable as a noncurrent version (`?versions`), a deleted one too, deleting every version (`DELETE ?generation=`) leaves none listed; the client built with the docs' settings (`forcePathStyle`, `WHEN_REQUIRED` both ways, region `auto`), the snippet marked for the docs' include.

### Implementation for User Story 3

- [X] T052 [US3] `OP/ObjectStorage.scala` `cloudRequests`: the settings-generation rule of T048, reading the existing bucket request's spec; `OP/ServiceReconciler.scala` passes `objectStorageSettingsGeneration` and that spec (through 044's `observeCloudResource`, extended to return the spec).
- [X] T053 [US3] `CP/domain/events.scala` (`ProjectLocationSet`, `StorageSettingsReapplied`), `CP/domain/model.scala` (folds; `Project.bucketLocation`), `CP/application/ProjectEntity.scala`, `CP/application/ServiceEntity.scala`, `CP/api/ProjectEndpoint.scala` (`PUT`/`DELETE /{projectId}/location`), `CP/api/ServiceEndpoint.scala` (`POST …/storage/settings`), `CP/deploy/ProjectTopicsTrigger.scala` (`bucketLocation` onto the resource), `CP/deploy/ServiceProjection.scala` (`objectStorageSettingsGeneration`).
- [X] T054 [US3] `CLI/Main.scala` `projects location set|clear`, `services storage reapply-settings`; `CLI/ControlPlaneClient.scala`; the MCP descriptions; cases in `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/CliReferenceSuite.scala` and `CPT/ControlPlaneRoutesReferenceSuite.scala`.
- [ ] T055 [US3] Steps in `CPT/ObjectStorageGcsClusterFeatures.scala` for `retention.feature`'s "a bucket is made in the location the installation names", "… its project names", "the status of a bucket says how long a deleted object can still be recovered", and "a bucket is encrypted with the installation's key" (the request's `kmsKey` and the request as the scripted provider received it); `ranOutside` for the versioning, deletion and retention-policy scenarios naming `GcsCompatibilitySuite`'s tests.

**Checkpoint**: locations and settings on k3s; `GcsCompatibilitySuite` green by hand against the prepared bucket (T003).

---

## Phase 6: User Story 4 — A browser uploads a document through a URL the service signed (Priority: P2)

**Goal**: the descriptor names the origins; an exposed bucket's CORS rule admits exactly them on both stores, set by the provider on GCS and the operator on Garage; `ANKKA_S3_PUBLIC_ENDPOINT` and the public address on GCS; no route on GCS; an unsigned request refused everywhere; a service sets no rule.

**Independent Test**: `reachable.feature`'s first three scenarios on k3s with the fake applying `corsOrigins` to Garage (the preflight from the named and an unnamed origin); 034's changed origins scenario on Garage in `ObjectStorageClusterFeatures`; the unsigned-request and real preflight scenarios in `GcsCompatibilitySuite`; quickstart §4.

### Tests for User Story 4

- [ ] T056 [P] [US4] (Garage half done.) Test in `OPT/ObjectStorageRenderingSuite.scala`: on `gcs`, the bucket request's `corsOrigins` = the origins when exposed, empty otherwise; `ANKKA_S3_PUBLIC_ENDPOINT` = the fulfilment's `endpoint` and `publicAddress` = `<endpoint>/<bucket>` when exposed; no `EnsureHttpRoute`/`EnsureReferenceGrant` on `gcs` (044's case).
- [X] T057 [P] [US4] Written (runs with T003's bucket; the unnamed origin is asserted inside the named-origin test). Add to `CPT/GcsCompatibilitySuite.scala` tests named for `reachable.feature`'s "a request without a signed URL is refused by every bucket" and "a browser on an origin the descriptor does not name cannot send an object": an unsigned GET refused; a presigned PUT's preflight allowed from `ANKKA_GCS_ORIGIN` and refused from another; and one proving the AWS SDK's `PutBucketCors` is refused by GCS (FR-016's reason).

### Implementation for User Story 4

- [ ] T058 [US4] (Garage half done.) The Google Cloud Storage half of T056: `corsOrigins` is T091's; `ANKKA_S3_PUBLIC_ENDPOINT` and `publicAddress` are T031's and T032's. Nothing more than those tasks; tick this when T056 passes.
- [ ] T059 [US4] Steps in `CPT/ObjectStorageGcsClusterFeatures.scala` for `reachable.feature`'s "told the address of its bucket on the internet", "a browser on an origin the descriptor names keeps an object through a signed URL" (the preflight against Garage's rule from the scripted provider's `corsOrigins`, then the PUT), and the outline "an origin the descriptor does not name cannot send" (the service's own hostname and `https://elsewhere.example` refused on preflight); `ranOutside` for the unsigned-request scenario naming T057's test.
- [X] T060 [US4] Steps in `CPT/ObjectStorageClusterFeatures.scala` for 034's changed scenario "a browser on an origin the descriptor names keeps an object through a signed URL without the service setting a rule on its bucket" on Garage: the operator's rule, the preflight, the PUT, and the service's code setting nothing.

**Checkpoint**: origins work on both stores; `SC-005`'s "no CORS call in the service's code" is the sample's code unchanged.

---

## Phase 7: User Story 6 — An existing service's objects move from Garage to GCS (Priority: P2)

**Goal**: a member moves one service's bucket; the mover copies and verifies; the write pause is a read-only credential, bounded; the switch is at the next rollout; the Garage bucket is kept; a stopped move is finished by running it again; a failed move gives writes back on Garage.

**Independent Test**: every scenario of `move.feature` on k3s with two Garage buckets (quickstart §3); `MoverSuite` offline against two Garage containers; quickstart §6 step 4 by hand.

### Tests for User Story 6

- [X] T061 [P] [US6] `storage-mover/src/test/scala/com/thinkmorestupidless/ankka/mover/MoverSuite.scala` against two `dxflrs/garage:v2.3.0` containers: the eight cases of `contracts/mover.md` "Tests" (round trip with metadata and `x-amz-meta-sha256`; a second copy uploads nothing; a changed source object is copied; an altered target makes `verify` fail naming it; an object added mid-copy is on the target after `verify`; a read-only source key still copies; exit 2 naming a missing variable; the result JSON's fields).
- [X] T062 [P] [US6] Test in `OPT/StorageMoveSuite.scala`: every row of `contracts/operator.md` "The move" through `StorageMove.next` with a fixed clock: `Requested` on a new generation; `Copying` once all `Ready`; `Pausing` on the copy Job's success with `pauseStartedAt` and `pauseBound`; `Verifying` once the rollout carries the read-only annotation; the verify Job's deadline = the bound's remainder (at least 1); `Switched` on success; `Failed` on a failed Job, the deadline, or the bound passing in `Pausing`, each with `RestoreWritingCredential` at `max(status, spec) + 1`; `Failed` → `Requested` on a new generation; a raised credential generation ignored during `Pausing`/`Verifying`.
- [X] T063 [P] [US6] Test in `OPT/ObjectStorageRenderingSuite.scala`: the Job per phase as `contracts/operator.md` renders it (name `<service>-move-<n>-copy|verify`, owner, labels, `backoffLimit: 0`, TTL, `activeDeadlineSeconds` on verify only, the ten `MOVER_*` variables with `secretKeyRef`s to the two Secrets, the image from settings, `terminationMessagePolicy`); after `Switched` the GCS variables and `envFrom`, never Garage's again.
- [X] T064 [P] [US6] (`StorageCredentialSuite`'s write-pause case and `GarageStoreSuite`'s deny case are written: the pause takes write from the key in place, research R8's revision.) `CPT/ServiceEntitySuite.scala`: `moveStorage(bound)` persists `StorageMoveRequested(generation + 1, bound or "10m")`, history `storage-moved`; refused while in progress, when the bucket is not on Garage, or when the installation cannot move; a `Failed` move may be requested again; bounds outside 1 minute–24 hours refused. `CPT/ControlPlaneHttpSuite.scala`: `POST …/storage/move` with and without a body. `CPT/StatusIngestSuite.scala`: `storageMove` and its detail from `status.objectStorage.move`.

### Implementation for User Story 6

- [X] T065 [US6] `storage-mover/src/main/scala/com/thinkmorestupidless/ankka/mover/Mover.scala` and `Main.scala` per `contracts/mover.md`: the two clients, `copy` (list, HEAD, hash, PutObject with metadata and `x-amz-meta-sha256`, size check, 5 GiB refusal), `verify` (copy, key sets, hash both sides), the result JSON to stdout and `/dev/termination-log`, exit codes, `MOVER_CONCURRENCY`; `storage-mover/src/main/resources/logback.xml`.
- [ ] T066 [US6] (Partly done: see the note under Phase 7.) `OP/ServiceReconciler.scala` calls `StorageMove.next` once per pass, its `Requests` from 044's `CloudBucketPlans` for the target bucket and credential; `MoveObservation` as built; the rest of the state machine, the actions and `observeMoveJob` are done.
- [ ] T067 [US6] (Partly done: see the note under Phase 7.) `OP/Rendering.scala`: after `Switched`, the variables and `envFrom` are the cloud bucket's (`storeOf` answers `gcs` from the status); `OP/ServiceReconciler.scala` writes `status.objectStorage.move` and sets `store` on `Switched`/`Failed`.
- [ ] T068 [US6] (Partly done: the grant and the image variable are in both overlays.) `OPT/OperatorClusterSuite.scala`: the operator's minted token may create a Job and still not `get` a Secret, run on k3s.
- [X] T069 [US6] `CP/domain/events.scala` (`StorageMoveRequested`), `CP/domain/model.scala` (fold; `storageMovePhrase`: `copying`, `write pause`, `verifying`, `moved`, `move failed`), `CP/application/ServiceEntity.scala` (`moveStorage` and its refusals), `CP/api/ServiceEndpoint.scala` (`postBody("/{projectId}/{name}/storage/move")`), `CP/deploy/ServiceProjection.scala` (`objectStorageMove`), `CP/deploy/StatusIngest.scala` (`storageMove`, detail).
- [X] T070 [US6] `CLI/Main.scala` `services storage move [--write-pause-bound]`, `CLI/ControlPlaneClient.scala`, `CLI/Output.scala` `storage move` line, MCP description; cases in `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/OutputSuite.scala`, `CliReferenceSuite.scala` and `CPT/ControlPlaneRoutesReferenceSuite.scala`.
- [ ] T071 [US6] `build.sbt`: the cluster tests' image task builds `storageMover/docker:publishLocal` beside the sample's, gated on `ankka.cluster.tests`; `ObjectStorageGcsClusterFeatures` loads `ankka-storage-mover:<BuildInfo.imageTag>` into k3s as it loads the sample and passes `ANKKA_STORAGE_MOVER_IMAGE`.
- [ ] T072 [US6] Steps in `CPT/ObjectStorageGcsClusterFeatures.scala` for every scenario of `move.feature`: the installation with both stores (`ANKKA_OBJECT_STORE_ADMIN_URL` and backend `gcs`); objects seeded in the Garage bucket; `services storage move`; the copy Job's completion; a write from the pod refused and a read served during the write pause (`InPod` with the docs' client); the status's `write pause since … at most …`; the bound at 1 minute reached by holding the verify Job (the target credential's request scripted `Waiting`), the move `Failed`, a write served again; a move stopped part way (the Job deleted mid-run) finished by a second request; an object altered on the target making the verify fail naming it; the switch rolling the pods onto the new variables and the objects read back; the Garage bucket unchanged; a service not moved still on Garage; the mover's Job holding only the two `secretKeyRef`s, and the scripted provider's record showing it was asked for nothing in Garage's name.

**Checkpoint**: the whole move on k3s; SC-006's 10,000-object move timed by hand on a real installation and recorded in `quickstart.md`.

**Progress note (2026-10-09).** Done without 044: the state machine (`StorageMove.next`, every
transition), the mover's report read from its termination message, the Job as rendered
(`Rendering.moveJob`), the actions `EnsureMoveJob`, `PauseWrites`, `ResumeWrites` and the executor's
`observeMoveJob`, the operator's `batch/jobs` grant and `ANKKA_STORAGE_MOVER_IMAGE` in both overlays
and the release, and the control plane's request, refusals, status and CLI. Waiting on 044: calling
`StorageMove.next` from `ServiceReconciler` (its `Requests` come from the bucket and credential
requests' fulfilments), the GCS variables after `Switched`, and `OperatorClusterSuite`'s Job grant case
on k3s (T068). *2026-10-10:* 044 is merged; T066–T068 are rewritten onto its plans.

---

## Phase 8: User Story 5 — A service reaches its bucket with no key at all (Priority: P3)

**Goal**: a descriptor may decline the credential; no credential request, no key variables, no key issued; the ServiceAccount carries the identity's annotations; refused on Garage at apply.

**Independent Test**: `keyless.feature`'s "declines a storage credential is given none" and "refused on … Garage" on k3s; the two Google-refusal scenarios in `ranOutside`, proved by ankka-gcp's nightly.

### Tests for User Story 5

- [X] T073 [P] [US5] Test in `OPT/CloudRequestsSuite.scala` and `OPT/ObjectStorageRenderingSuite.scala`: with `objectStorageCredential: false` no `bucket-credential` request, no `envFrom`, no `ANKKA_S3_ACCESS_KEY`/`SECRET_KEY`, the other three variables present, the annotations present; `OPT/ObjectStorageSuite.scala`: 044's `fold` answers `Ready` with no credential plan for such a service (today it waits for one), and `decide` answers `Failed("a bucket in Garage is reached only with a storage credential")` on `garage`.
- [X] T074 [P] [US5] (`ServiceProjectionSuite`'s case; the endpoint applies the same `objectStorageProblems`.) Test in `CPT/ControlPlaneHttpSuite.scala` and `CPT/ServiceProjectionSuite.scala`: an apply with `objectStorageCredential: false` is refused on a `garage` installation with that message and accepted on `gcs`.

### Implementation for User Story 5

- [X] T075 [US5] `OP/ObjectStorage.scala` (`cloudRequests` without the credential, `fold` without waiting for it), `OP/Rendering.scala` per T073; `CP/api/ServiceEndpoint.scala` and `CP/deploy/ServiceProjection.scala` per T074.
- [ ] T076 [US5] Steps in `CPT/ObjectStorageGcsClusterFeatures.scala` for "a service that declines a storage credential is given none" (the pod's env, the scripted provider's issue record empty for the service, the ServiceAccount's annotation) and "a descriptor that declines a storage credential is refused on an installation whose object store is Garage" (the same steps against the `garage` stack); `ranOutside` for the two workload-identity scenarios.

**Checkpoint**: all 41 scenarios green or in `ranOutside` on k3s; `GcsCompatibilitySuite` green by hand.

---

## Phase 9: Polish and cross-cutting

- [X] T077 [P] `.github/workflows/gcs.yml` per `contracts/installation.md` (nightly 03:30 and `workflow_dispatch`; the five `ANKKA_GCS_*` from secrets; `sbt -Dankka.gcs.tests=on 'controlPlane/testOnly *GcsCompatibilitySuite'` under `CI`); add it to `ci.yml`'s `unchecked` list; `gh workflow run gcs` once and record the run in `quickstart.md` §4.
- [X] T078 [P] `kustomization/overlays/cloud/platform-configmap.yaml` gains `ANKKA_OBJECT_STORE_BACKEND`, `ANKKA_OBJECT_STORE_PREFIX`, `ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS` as `SET` placeholders and `overlays/local/platform-configmap.yaml` `ANKKA_OBJECT_STORE_BACKEND: garage`; `CPT/RemoteOverlaySuite.scala` asserts the three keys by shape.
- [X] T079 [P] Console: `console/package/src/client/schemas.ts` (the four status fields, the move request), `console/package/src/routes/service.tsx` (the store beside the bucket, the location, the move line, the three actions with the bound field shown only when the installation can move), `console/package/src/testing/fake-control-plane.ts` (the routes and fields), regenerate `console/package/fixtures/control-plane/`, extend `console/e2e/tests/services.spec.ts`; `just test-console`. *Done:* the console cannot see the installation's store, so the move is offered for any bucket not in Google Cloud Storage and the control plane's refusal says why on Garage; the browser cases are in `console/e2e/tests/storage.spec.ts`, and the project page sets and clears the location.
- [ ] T080 [P] `docs/platform/object-storage.md`: the section "On Google Cloud Storage" per research R14, with the snippets included from `GcsCompatibilitySuite` (T051, T057) and FR-018's words.
- [ ] T081 [P] `docs/platform/install-gke.md`: the eight sections of `contracts/installation.md` "A GKE installation"; `mkdocs.yml` nav beside `install-cloud.md`; `docs/platform/install-cloud.md` links to it and names the three settings.
- [ ] T082 [P] `docs/reference/service-descriptor.md` (the three fields), `docs/reference/limitations.md` (Garage keeps one version, so a deletion there is final at once; an object over 5 GiB does not move; in-cluster Garage traffic unchanged), `docs/operate/status-and-history.md` (the new lines and the four history kinds).
- [ ] T083 `just docs-sync` and `just docs` (the rendered skill and the marketplace copies regenerate); `just features`.
- [ ] T084 Update `.claude/rules/kubernetes.md`'s object storage and cloud request sections: which store a bucket is in (`storeOf`), the `-cloud-storage` Secret, the move's state in status, the mover image, the one rotation grace, and any trap found on k3s; `.claude/rules/build-and-release.md` with the new image.
- [ ] T085 Release notes in `docs/deploy/upgrading.md`: `ankka-gcp` and the three settings; `ANKKA_CLOUD_ROTATION_GRACE` now governs Garage's keys too; the operator's `batch/jobs`; a pre-existing Garage key is generation 0; a bucket already in Garage stays there when the backend becomes `gcs`; 041's `durability.feature` is not this feature's.
- [ ] T086 `sbt scalafmtAll scalafmtSbt`, then `sbt buildAll`; `gh workflow run cluster --ref <branch> -f suite=ObjectStorageGcsClusterFeatures` and `-f suite=ObjectStorageClusterFeatures`; both green before the pull request.

---

## Dependencies and execution order

```text
Phase 1 (T001–T006) ─┬─▶ Phase 2 (T007–T025, T087–T091; T087 before T024, T089 and T090 before US1)
                     │
Phase 2 ─────────────┼─▶ US1 (Phase 3)  needs T001
                     │        │
                     │        ├─▶ US2 (Phase 4)  needs US1's decide/rendering (T031, T032)
                     │        ├─▶ US3 (Phase 5)  needs T030; T051 needs only T003 and T006
                     │        ├─▶ US4 (Phase 6)  needs T032, T021
                     │        ├─▶ US5 (Phase 8)  needs T030, T032
                     │        └─▶ US6 (Phase 7)  needs US1, US2's reissue (T042), T004; T061/T065 need only T004
                     │
                     └─▶ Polish (Phase 9) after every story; T077 after T051/T057; T079 after T044, T053, T069
```

- US2, US3, US4 and US5 are independent of each other once US1 is in.
- US6 needs US2's `reissue` (its failure path restores a writing key) and US1's requests.
- The mover (T004, T061, T065) and `GcsCompatibilitySuite` (T051, T057) need no 044 and no
  cluster and can start on day one.

## Parallel opportunities

- **Phase 1**: T002, T003, T004, T005, T006 together.
- **Phase 2**: T007, T008, T010, T012, T016, T018, T020, T022, T023 together; then T009, T011,
  T013–T015, T017, T019, T021; T024 and T025 last.
- **US1**: T026–T029 together; then T030–T033 in order; T034–T036 together; T037.
- **US2**: T038–T041 together; T042–T045 in order; T046 and T047 together.
- **US3**: T048–T051 together; T052–T054; T055.
- **US4**: T056, T057 together; T058; T059, T060 together.
- **US6**: T061–T064 together (T061 and T065 can run from Phase 1); T065–T071; T072.
- **Polish**: T077–T082 together.

## Implementation strategy

1. **MVP = Phase 1, Phase 2, US1.** A descriptor unchanged from Garage gets a bucket on GCS with
   the same variables and the status names it. Deliverable on its own once 044 is in.
2. **Then US2 and US3** (both P1): isolation and re-issue; versioning, location and the window.
   With these the first user can keep documents.
3. **Then US4** (the browser upload) and **US6** (the move, which the live casino needs).
4. **US5 last** (keyless), smallest and needed by nobody yet.
5. **Polish** throughout where a task is independent (docs, workflow, console), and the whole
   build at the end.

Every task that touches `OP/Rendering.scala` must leave `RenderingUnchangedSuite` green with no
repin: a service on Garage renders what it rendered, or the upgrade rolls every pod.
