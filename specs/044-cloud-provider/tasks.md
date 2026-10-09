# Tasks: The Cloud Provider — What Touches a Cloud Account Runs Outside the Operator

**Input**: Design documents from `/specs/044-cloud-provider/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and research's *Verify first, gathered* list turns each fact
assumed about a real API server into a case before code relies on it. The scenarios are in
`features/cloud-provider/`; research R12 says where each is run. Where a task says "case", it
means a `test(...)` in the named suite, named for the scenario or the rule it holds.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a service asks for a bucket and the provider makes it), US2 (a credential is
  written once and never read by anyone but the pod), US3 (no provider is installed, and the
  platform says so), US4 (each feature's need is one request kind), US5 (the platform's own suites
  need no cloud)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CRD`/`CRDT` =
`crd/src/{main,test}/scala/…/crd`; `CORE`/`CORET` = `modules/core/src/{main,test}/scala/…/core`;
`API`/`APIT` = `controlplane-api/src/{main,test}/scala/…/controlplane/api`; `CP`/`CPT` =
`controlplane/src/{main,test}/scala/…/controlplane`; `OP`/`OPT` =
`operator/src/{main,test}/scala/…/operator`; `CLI`/`CLIT` = `cli/src/{main,test}/scala/…/cli`;
`KUST` = `kustomization`; `DOCS` = `docs`; `FEAT` = `features/cloud-provider`. "R*n*" is a section
of `research.md`; a contract is named by its file under `contracts/`.

The branch `044-cloud-provider-impl` exists, in the worktree
`.claude/worktrees/044-cloud-provider-impl`. Work there. Every `sbt` command takes
`-Dankka.cluster.tests=off` unless the task names a k3s suite; a k3s suite is run on demand with
`gh workflow run cluster --ref 044-cloud-provider-impl -f suite=<Name>` rather than on the laptop,
and a local run belongs under `caffeinate -i`. sbt's project ids are `crd`, `core`,
`controlPlaneApi`, `controlPlane`, `operator`, `cli`.

---

## Phase 1: Setup — the words, the switch, and the names nothing else may take

**Purpose**: what every later task spells the same way.

- [X] T001 Add `CloudProvider`, `CloudAccount`, `CloudLocation`, `CloudKmsKey`, `CloudAcknowledgementBound`, `CloudRotationGrace` (the `ANKKA_CLOUD_*` names), `CloudProviderNone = "none"` and `CloudProviders: Set[String] = Set("gcp")` to `CORE/PlatformVariables.scala`, importing nothing; add the six names to `PlatformOnly` with a comment naming this feature (installation.md "The platform's variables")
- [X] T002 Extend `CORET/PlatformVariablesSuite.scala`: the `PlatformOnly` pin grows by six ("exactly the twenty-four"), a case that each `ANKKA_CLOUD_*` is `platformOnly` and not `runtimeOnly`, a case that `CloudProviders` is exactly `Set("gcp")`; run `sbt core/test` and `sbt 'controlPlane/testOnly *PlatformDeclarationSuite'` green (the list lives where the suite allows it)
- [X] T003 [P] Add `"cloud-provider"` to `Names.ReservedProjectIds` in `OP/Names.scala` and to `ProjectId.Reserved` in `API/descriptors.scala`, with a comment naming the namespace it protects; run `sbt 'controlPlane/testOnly *ReservedProjectIdsSuite'` green (R10)
- [X] T004 [P] Forward `ankka.cloud.external` into the forked test JVM in `build.sbt`'s `Test / javaOptions` beside `ankka.cluster.tests`, and add a comment that it names a kubeconfig (R13; CLAUDE.md's first trap)

---

## Phase 2: Foundational — the resource, the settings, the decision, the identity, the fake

**Purpose**: the contract both processes speak and the fake that answers it. No story's k3s
scenario runs without all of this.

**⚠️ CRITICAL**: no story phase begins until T005–T030 are green.

### The resource (`crd`)

- [X] T005 Write `CRDT/CloudResourceCodecSuite.scala` first: round trips of a full `CloudResource` through `AnkkaSerialization`, every spec and status default, a `null` status, `observedGeneration` read back as a `Long` (the `contentAs` trap in `.claude/rules/kubernetes.md`), `parameters` and `outputs` as `Map[String, String]`; it fails to compile until T006
- [X] T006 Create `CRD/CloudResource.scala`: `CloudSubject(project: String, service: String = "")`, `CloudResourceSpec(provider, kind, subject, credentialGeneration: Long = 0L, parameters: Map[String, String] = Map.empty)`, `CloudResourceStatus(observedGeneration: Option[Long] = None, phase = "", detail: Option[String] = None, account = "", location = "", credentialGeneration: Option[Long] = None, recovered = false, providerVersion = "", outputs = Map.empty)`, every case class `@JsonInclude(NON_ABSENT)` and the two `Option[Long]`s `@JsonDeserialize(contentAs = classOf[java.lang.Long])`; `class CloudResource extends CustomResource[CloudResourceSpec, CloudResourceStatus] with Namespaced` annotated `@Group("ankka.thinkmorestupidless.com") @Version("v1alpha1") @Kind("CloudResource") @Plural("cloudresources") @ShortNames(Array("cres"))` with a `null` `initStatus`; a companion `apply(namespace, name, spec)`; `object CloudKinds` with the six kind strings and the four phase strings (data-model.md "Cloud request", cloud-resource.md)
- [X] T007 Add `storageCredentialGeneration: Long = 1L` to `AnkkaServiceSpec` in `CRD/AnkkaService.scala` with a doc comment (R7), and a round-trip and default case in `CRDT/AnkkaServiceCodecSuite.scala`; run `sbt crd/test` green
- [X] T008 Write `KUST/components/crd/cloudresource.yaml`: `apiextensions.k8s.io/v1`, `scope: Namespaced`, names `CloudResource`/`CloudResourceList`/`cloudresources`/`cloudresource`/`cres`, label `app.kubernetes.io/managed-by: ankka`, one served and stored version `v1alpha1` with `subresources.status: {}`, a closed `openAPIV3Schema` declaring exactly the fields of T006 (`parameters` and `outputs` as `type: object` with `additionalProperties: {type: string}`, `kind` and `phase` as enums), printer columns `Kind`, `Provider`, `Phase`, `Age`; list it in `KUST/components/crd/kustomization.yaml`
- [X] T009 Add the symlink `operator/src/main/resources/ankka/crd/cloudresource.yaml -> ../../../../../../kustomization/components/crd/cloudresource.yaml` (copy nothing over an existing path; the load-restrictor trap) and extend `OPT/CrdSchemaSuite.scala`: `declaredIn(cloudCrd, "spec") == fieldsOf(CloudResourceSpec)`, likewise `spec.subject` and `status`, the `kind` enum equals `CloudKinds.all`, the `phase` enum equals `CloudKinds.phases`, and `storageCredentialGeneration` now declared on `ankkaservice.yaml` (add it there); run `sbt 'operator/testOnly *CrdSchemaSuite'` green

### The operator's words and settings

- [X] T010 Write cases in `OPT/SettingsSuite.scala` first: no `ANKKA_CLOUD_PROVIDER` gives `cloud = None`; `none` gives `None`; `gcp` with account and location gives `CloudSettings("gcp", account, location, None, 2.minutes, 1.hour)`; `gcp` without an account refuses to start naming `ANKKA_CLOUD_ACCOUNT`; `aws` refuses naming the known names; the two durations parsed from `30s` and `20s`; the key present when set
- [X] T011 Add `CloudSettings` and `CloudSettings.read` to `OP/Settings.scala` (operator.md "Settings"), read as `BrokerSettings.read` reads, with `name -> value` pairs and no literal list of `ANKKA_` names; `Settings.cloud: Option[CloudSettings]`; run `sbt 'operator/testOnly *SettingsSuite'` green
- [X] T012 [P] Add `Names.cloudRequest(subject: CloudSubject, kind: String, purpose: String): String` to `OP/Names.scala` producing the table in cloud-resource.md "Names" (a project's with a dot), with a case per row in `OPT/CloudRequestsSuite.scala` (created here, grown in T014) and one proving `cloudRequest(service "shop-backup", bucket) != cloudRequest(project "shop", backup bucket)` (R3)

### Rendering a request and deciding on its answer

- [X] T013 Write `OPT/CloudRequestsSuite.scala` cases first for the three renderers US1 needs: `CloudRequests.identity(subject, serviceAccount)` yields `<service>-identity` with the parameter `serviceAccount` only, identity labels and the owner reference; `CloudRequests.bucket(...)` yields a `CloudResource` named `<service>-bucket`, `spec.provider` from the settings, `spec.kind = "bucket"`, `spec.subject`, `credentialGeneration = 0`, parameters exactly `purpose, location, versioning, softDeleteDays, corsOrigins, kmsKey`, identity labels, and the owner reference of the `AnkkaService`; `CloudRequests.bucketCredential(...)` likewise with `bucket`, `identity` (the identity fulfilment's output), `secretName` and `credentialGeneration` from `AnkkaServiceSpec.storageCredentialGeneration`; `location` is the settings' string verbatim
- [X] T014 Create `OP/CloudRequests.scala`: `object Keys` (every parameter and output key per kind as constants), `identity`, `bucket`, `bucketCredential`, and a private `request(resource or project, kind, name, parameters, generation)` building the object with `Labels.ownerReference` (operator.md "Rendering a request"); run `sbt 'operator/testOnly *CloudRequestsSuite'` green
- [X] T015 Write `OPT/CloudProvisioningSuite.scala` first, one case per row of research R5's table: absent → `Waiting(None)`; no `observedGeneration` within the bound → `Waiting(None)`; past the bound → `Waiting(Some("no provider for gcp has answered"))`; `observedGeneration` behind → `Waiting(Some("waiting on the provider for generation 2"))`; `Waiting` with detail; `Ready` with outputs and `credentialGeneration`; `Recovered` → `Ready(recovered = true)`; `Failed` with detail word for word; the clock and the bound are parameters
- [X] T016 Create `OP/CloudProvisioning.scala`: `CloudObservation(generation: Long, createdAt: Instant, status: Option[CloudResourceStatus])`, `enum CloudPlan { Waiting(detail), Ready(outputs, recovered, credentialGeneration), Failed(detail) }`, `decide(request: CloudResource, observed: Option[CloudObservation], now: Instant, bound: FiniteDuration): CloudPlan`, pure; run `sbt 'operator/testOnly *CloudProvisioningSuite'` green
- [X] T017 Add `Action.EnsureCloudResource(resource: CloudResource)` to `OP/Action.scala` with a `describe` line (`cloud request <kind> <namespace>/<name>`), `Executor.observeCloudResource(namespace, name): Option[CloudObservation]` (default `None`) to `OP/Executor.scala`, and in `Fabric8Executor`: the apply through the shared `serverSideApply` path, the observe through `ifTypeExists` reading `metadata.generation`, `creationTimestamp` and `status`; add the case to `RenderingGoldenSuite.objectOf` in `OPT/RenderingGoldenSuite.scala`

### The identity and the component

- [X] T018 Create `KUST/components/cloud-provider/{kustomization.yaml,namespace.yaml,rbac.yaml,settings.yaml}`: Namespace `ankka-cloud-provider`; ServiceAccount, ClusterRole and ClusterRoleBinding `ankka-cloud-provider` with exactly `cloudresources` get/list/watch, `cloudresources/status` get/update/patch, `secrets` create/patch, each rule commented with why it is there and what is withheld (provider.md "The grant"); ConfigMap `ankka-cloud` with the six keys at the component defaults (installation.md "The `cloud-provider` component")
- [X] T019 Add to the operator's ClusterRole in `KUST/components/operator/operator.yaml`: `cloudresources` get/list/watch/create/patch and `cloudresources/status` get, commented (no `delete`, no status write); add six `ANKKA_CLOUD_*` env literals to the operator's container (`none`, empty ×3, `2m`, `1h`) in the same block as `ANKKA_OTLP_ENDPOINT`
- [X] T020 Add `cloudProvider: none`, `cloudAccount: ""`, `cloudLocation: ""`, `cloudKmsKey: ""`, `cloudAcknowledgementBound: "2m"`, `cloudRotationGrace: "1h"` to `KUST/overlays/local/platform-configmap.yaml`; the same keys with `# SET` comments (provider `none`) to `KUST/overlays/cloud/platform-configmap.yaml`; list `../../components/cloud-provider` after `operator` in both `kustomization.yaml`s; add `replacements` in both copying each key onto the operator's container, the control plane's container (its env literals added in T049) and `ankka-cloud`'s data, in the shape of the `otlpEndpoint` replacement
- [X] T021 Pre-apply `crd/cloudresource.yaml` beside `ankkaservice.yaml` in `KUST/deploy-local.sh`; in `CPT/RemoteOverlaySuite.scala` add cases for both overlays: each `ANKKA_CLOUD_*` exactly once on the operator's single container; `ankka-cloud` carries the six keys; ClusterRole `ankka-cloud-provider` has no `delete` verb anywhere and no `get` on `secrets`; the cloud overlay's `cloudProvider` is `none`; run `sbt 'controlPlane/testOnly *RemoteOverlaySuite'` green (needs `kubectl` on PATH; it skips otherwise, so run it where it does not)

### The scripted cloud provider

- [X] T022 Write `OPT/ScriptedCloudProviderSuite.scala` first, against the pure fulfilment: each of the six kinds given a spec answers `Ready` with `observedGeneration` = the generation given, `account` and `location` from its settings, `providerVersion` starting `scripted`, and the outputs of provider.md "The scripted cloud provider"; a second answer for the same `(kind, subject)` is `Recovered` with `recovered = true` (`made` is keyed by `(kind, subject)` and holds the account and location); a kind `managed-database` answers `Failed` with `kind managed-database is not implemented by scripted <version>`; a spec whose account differs from what it remembers answers `Failed` with `made in another account or location`; a request named in `failing` answers `Failed` with the script's reason; a `bucket-credential` whose `bucket` is a backup bucket and whose `identity` is not the project's database's answers `Failed` with `a backup bucket is granted only to its project's database`; `reached` is zero after all of it
- [X] T023 Create `OPT/cloud/ScriptedFulfilment.scala`: `final class ScriptedFulfilment(provider, account, location, kmsKey, grace, clock)` with `made`, `issued`, `ended`, `failing`, `reached` (data-model.md "In the scripted provider's memory") and `fulfil(name, spec, generation, secret: SecretOutcome => ...)`: returns a `CloudResourceStatus` and, for a credential kind, the `Secret` to offer and what to do on `Created` or `Exists` (the credential table is US2's, T037; here `Created` only); run `sbt 'operator/testOnly *ScriptedCloudProviderSuite'` green for the cases of T022
- [X] T024 Create `OPT/cloud/ScriptedCloudProvider.scala`: `final class ScriptedCloudProvider(client: KubernetesClient, fulfilment: ScriptedFulfilment)` with `start()`/`stop()`; an informer on `CloudResource` in all namespaces (a `watched` namespace filter like `Operator`'s), acting only on `spec.provider == fulfilment.provider`, skipping any whose `status.observedGeneration == metadata.generation`, and writing with `editStatus`, `secrets().resource(s).create()` (409 → `Exists`) and a `patch` of the Secret's data and nothing else (provider.md "Watching", "Fulfilling")
- [X] T025 Create `OPT/cloud/CloudProviderStack.scala`: `install(k3s, admin)` applies `/ankka/crd/cloudresource.yaml` and the `cloud-provider` component's `namespace.yaml` and `rbac.yaml` from the classpath (add a symlink `operator/src/main/resources/ankka/install/cloud-provider/` into the component, like `install/operator.yaml`), waits for the CRD to be established with `kubectl` on the node; `token(k3s)` mints `kubectl create token ankka-cloud-provider -n ankka-cloud-provider --duration=30m`; `client(k3s, token)` builds a fabric8 client `withOauthToken` on the node's address with `AnkkaSerialization` (the pattern of `OperatorClusterSuite` test 20)

### Verify first, on a real API server

- [X] T026 Add to `OPT/OperatorClusterSuite.scala` (k3s), in a new case "the operator's token writes a cloud request and nothing more": after `CloudProviderStack.install`, under the operator's minted token `Fabric8Executor.execute(EnsureCloudResource(...))` succeeds the first time (a PATCH on an absent object) and the object carries the owner reference; a `delete` of it is a 403; an `editStatus` on it is a 403; `observeCloudResource` reads it back with `generation == 1` and no status
- [X] T027 Add a case "the provider's token writes a status and a Secret and reads neither": under `CloudProviderStack.token`, `editStatus` setting `observedGeneration = 1, phase = Ready` succeeds and `metadata.generation` is still 1; a `create` of Secret `probe-storage` succeeds and a second `create` is a 409; a `patch` of its data succeeds; a `get` of it is a 403; a `list` of secrets is a 403; a `patch` of the request's `spec` is a 403 (credential.feature "the cloud provider can write a secret and cannot read one back"; the two negative halves are what SC-002 means)
- [X] T028 Add a case "a spec change bumps the generation and a status write does not": patch the spec's `credentialGeneration` under the operator's token and read `metadata.generation == 2`; write a status under the provider's token and read it still 2 (research "Verify first", item 2)
- [X] T029 Add a case "a cloud request goes with its service and its Secret stays": delete the `AnkkaService` with the admin client, wait for the `CloudResource` to be gone, and assert `probe-storage` still exists (R14)
- [X] T030 Run `gh workflow run cluster --ref 044-cloud-provider-impl -f suite=OperatorClusterSuite` and record under research's "Verified during implementation" what each of T026–T029 showed; fix the YAML or the executor until green

**Checkpoint**: the resource exists on a real API server, both identities hold exactly their
verbs, the fake fulfils every kind offline, and nothing renders differently yet.

---

## Phase 3: User Story 1 — A service asks for a bucket and the provider makes it (Priority: P1) 🎯 MVP

**Goal**: `provisionObjectStorage` on an installation whose object store is its cloud account's
renders a `bucket` and a `storage-credential` request, the provider fulfils them, the operator
gives the service its five variables and reports `Provisioned` with the provider's bucket name.

**Independent Test**: `FEAT/bucket.feature`, all four scenarios, in `CloudProviderClusterFeatures`
against the scripted provider under its minted token; `RenderingUnchangedSuite` untouched.

### Deciding and rendering, offline

- [X] T031 [US1] Write cases in `OPT/ObjectStorageSuite.scala` first (the table in operator.md "Deciding"): all three plans `Ready` → `ObjectStoragePlan.Ready(recovered = bucket's, cloud = Some(CloudBucket(bucket, endpoint, region, generation)))`; any of the three `Failed` → `Failed(Vector(detail))`; any `Waiting` → `Waiting(first detail)`; the credential plan absent (identity not yet `Ready`) → `Waiting`; with Garage settings present the cloud input is ignored and the Garage rows still hold; `ObjectStorage.status` on the cloud path carries the provider's bucket name and `recovered`
- [X] T032 [US1] Extend `OP/ObjectStorage.scala`: `ObjectStoragePlan.Ready(recovered, cloud: Option[CloudBucket] = None)`, `CloudBucket(bucket, endpoint, region, credentialGeneration)`, `decide(spec, settings, observed, cloud: Option[CloudBucketPlans])` with `CloudBucketPlans(identity: CloudPlan, bucket: CloudPlan, credential: Option[CloudPlan])`, `takesCloudPath(spec, settings) = provisionObjectStorage && settings.objectStore.isEmpty && settings.cloud.isDefined` (R6); run `sbt 'operator/testOnly *ObjectStorageSuite'` green
- [X] T033 [US1] Write cases in `OPT/ObjectStorageRenderingSuite.scala` first: on the cloud path with `Waiting` the actions hold `EnsureCloudResource(identity)`, `EnsureCloudResource(bucket)` and, only once the identity plan is `Ready`, `EnsureCloudResource(storage-credential)` with `identity` = its output, and no `ApplyDeployment` with `withheld` carrying the detail, no `EnsureBucket`, no `EnsureStorageCredential`; with `Ready(cloud = Some(...))` the Deployment's developer container has `ANKKA_S3_ENDPOINT`, `_REGION`, `_BUCKET` as values from the outputs and `_ACCESS_KEY`, `_SECRET_KEY` from Secret `<service>-storage`, and the pod template annotation `ankka.thinkmorestupidless.com/storage-credential-generation: "1"`; with `Failed` the Deployment is applied with no `ANKKA_S3_` variable and no annotation; the variables go to the developer's program in every hosting and never the sidecar or the proxy (as 034's cases do); on the Garage path no annotation and no `EnsureCloudResource`
- [X] T034 [US1] Implement the cloud bucket path in `OP/Rendering.scala`: `cloudBucketActions` (operator.md "Actions, in order"), `storageEnv` from `CloudBucket`, the annotation, and the withheld Deployment while `Waiting` (as built: `ObjectStorage.withheld` is the one rule `render` and the reconciler ask, and `render` takes the requests as `cloudRequests`, keeping its type); add a `cloud-bucket` case and golden file `operator/src/test/resources/golden/cloud-bucket.txt` to `OPT/RenderingGoldenSuite.scala` (with `-Dankka.golden.update=true` once, then read the file and check it says what T033 says); run `sbt 'operator/testOnly *ObjectStorageRenderingSuite *RenderingGoldenSuite *RenderingUnchangedSuite'` green with **no repin** of `RenderingUnchangedSuite`

### Wiring the reconciler and the informer

- [X] T035 [US1] In `OP/ServiceReconciler.scala`: when `ObjectStorage.takesCloudPath`, render the identity and bucket requests with `CloudRequests` and the credential request once the identity plan is `Ready`, observe each with `executor.observeCloudResource`, decide each with `CloudProvisioning.decide(…, Instant.now, settings.cloud.get.acknowledgementBound)`, pass the pair into `ObjectStorage.decide`, and after the pass call `queue.enqueueAfter(ref, bound)` when either observation has no `observedGeneration`; the status fold reports `lifecycle = UpdateInProgress, detail = withheld` while the Deployment is withheld (operator.md "Re-queueing", "Status"); extend `OPT/RenderingSuite.scala` or a new `OPT/ServiceReconcilerCloudSuite.scala` with an `Executor` stub returning scripted observations to prove the fold and the re-queue
- [X] T036 [US1] In `OP/Operator.scala`: a third informer on `CloudResource` (wrapped in `try`/`NonFatal` like the `AnkkaProject` one, logging once that no cloud request is watched when the type is absent) whose handler reads the controller owner reference and enqueues an `AnkkaService` owner on `queue` and an `AnkkaProject` owner on `projectQueue`, for namespaces `watched(ns)`; a handler-level case in `OPT/OperatorSuite.scala` (new): call the informer's handler with built `CloudResource`s carrying an `AnkkaService` and an `AnkkaProject` owner reference and assert each lands on the right queue, with no API server

### The k3s suite and the bucket scenarios

- [X] T037 [US1] Create `CPT/CloudProviderClusterFeatures.scala` as `GherkinSuite("../features/cloud-provider") with LogCapturing`, `munitTimeout 15.minutes`, ignored under `ankka.cluster.tests=off`, modelled on `ObjectStorageClusterFeatures`: k3s `rancher/k3s:v1.35.1-k3s1`, the three CRDs, `PkiStack.install`, CNPG, the Gateway API CRDs, `CloudProviderStack.install`, the operator in-process with `Settings` carrying `cloud = Some(CloudSettings("gcp", "scripted-account", "scripted-location", None, 30.seconds, 20.seconds))` and `objectStore = None`, the scripted provider in-process on `CloudProviderStack.client(token)` with the same account, location and grace; every service a `pause` descriptor with `"http": false` and `provisionObjectStorage: true`, named `reports-<n>` per scenario; `ranElsewhere` naming every scenario of the directory not run here (R12), each with the suite that runs it
- [X] T038 [US1] Steps for `FEAT/bucket.feature` scenario 1 ("a descriptor that asks for a bucket becomes a bucket request and a bucket credential request"): apply through the control plane in-process, then read the three `CloudResource`s in the project's namespace with the admin client and assert name, owner, kind, the identity request's `serviceAccount`, `purpose: service`, `location` = the settings' string, the descriptor's bucket settings, the credential's `bucket`, `identity` (the identity fulfilment's output) and `secretName = reports-<n>-storage`
- [X] T039 [US1] Steps for scenario 2 ("a service is given its bucket when the cloud provider fulfils the requests"): all three statuses `observedGeneration == 1` and `phase == Ready`, the bucket output named by the fake, the credential output `secretName`; the Deployment's developer container carries the five variables with the three values from the outputs and the two from the Secret; `services get` shows `object storage: Provisioned` and the fake's bucket name, within 60s of apply (SC-001); the Secret was read by neither process — asserted by the two 403 cases of T027 and the fake's `reached == 0`
- [X] T040 [US1] Steps for scenario 3 (outline, two rows: "the name of the bucket is taken in another account", "the location is refused"): script `fulfilment.failing(name -> reason)` before apply; the service's `objectStorage.phase` is `Failed` with the reason word for word; the Deployment has no `ANKKA_S3_` variable
- [X] T041 [US1] Steps for scenario 4 ("a service deleted and applied again under the same name is given the bucket it had"): delete through the control plane, wait for the `CloudResource`s to be gone and the Secret to remain, apply again; the operator writes the same three names; the bucket status is `Recovered` with `recovered = true` and the same bucket name; the fake's `issued` grew by one and `ended` by one with `why = conflict` (it offered, met the 409, ended what it made); `services get` reports `Recovered`
- [X] T042 [US1] Run `gh workflow run cluster --ref 044-cloud-provider-impl -f suite=CloudProviderClusterFeatures`; fix until the four scenarios are green; record anything a real API server taught under research's "Verified during implementation" and, if it is a trap, in `.claude/rules/kubernetes.md`

**Checkpoint**: US1 is the MVP. A service on a cloud installation has a bucket it did not make,
through a request it never saw a cloud in.

---

## Phase 4: User Story 2 — A credential is written once and never read by anyone but the pod (Priority: P1)

**Goal**: the provider's credential rule holds under its real grant: once by `create`, a 409 ends
the new one, a raised generation patches the same Secret and rolls the service, the old credential
ends after the grace.

**Independent Test**: `FEAT/credential.feature` scenarios 1, 2, 5, 6 in
`CloudProviderClusterFeatures`; scenarios 3 and 4 are the 403 cases of T027 and T026 (named in
`ranElsewhere`).

- [X] T043 [US2] Write the credential cases in `OPT/ScriptedCloudProviderSuite.scala` first (provider.md "A credential, written once", "A credential replaced"): `Created` → `issued` has one entry at generation 1 and the status says `credentialGeneration = 1`; `Exists` → `issued` has one, `ended` has one with `why = conflict`, the status is `Ready` naming the Secret with the generation it already held; a second `fulfil` of the same generation issues nothing (the controller skips it, but the fulfilment is also idempotent on `(name, generation)`); generation 2 → a second `issued`, a `patch` of the two entries, the status `credentialGeneration = 2` with the time; `endDue(now)` ends generation 1 only once `now >= fulfilledAt + grace`, recorded `why = rotated`; a credential issued and never written is in `ended` at the next pass
- [X] T044 [US2] Implement those rules in `OPT/cloud/ScriptedFulfilment.scala` and the `create`/409/`patch` branches and a `endDue` tick (every 5s on a virtual thread) in `OPT/cloud/ScriptedCloudProvider.scala`; run `sbt 'operator/testOnly *ScriptedCloudProviderSuite'` green
- [X] T045 [US2] The operator's half of a bump: `CloudRequests.bucketCredential` takes `credentialGeneration` from `AnkkaServiceSpec.storageCredentialGeneration` (T014 placeholder → real), the rendered annotation carries `status.credentialGeneration` (T033/T034 already assert the annotation; add a case that a status generation of 2 renders `"2"` and a changed value changes the pod template and nothing else); the control plane's `ServiceProjector` in `CP/deploy/ServiceProjector.scala` does **not** set `storageCredentialGeneration` (R7: a field its manager never owns cannot be reverted by a re-projection, and the operator reads 1 when it is absent), with a case in `CPT/ServiceProjectorSuite.scala` that the projected object carries no such field
- [X] T046 [US2] Steps for `FEAT/credential.feature` scenario 1 ("fulfilled by offering the secret once") and scenario 2 ("whose secret is already there ends the credential just made"): for 2, pre-create `reports-<n>-storage` with the admin client before apply, then assert the fake's `ended` holds one `conflict`, the status names the Secret, and the Secret's data is what was pre-created
- [X] T047 [US2] Steps for scenario 5 ("raising the credential generation replaces the credential and ends the old one after the rotation grace"): patch the `AnkkaService`'s `spec.storageCredentialGeneration` to 2 with the admin client (as the control plane will); assert the `CloudResource` spec generation 2, the status `credentialGeneration = 2`, the Secret's data changed, the Deployment's annotation `"2"` and a new ReplicaSet whose pods are ready before the old ones are gone (the roll's shape; `pause` serves nothing, so serving through a roll is `RollingUpdate`'s property, proven by `MultiNodeClusterSuite`), then `services restart` through the control plane (a re-projection) and the resource still carrying 2, and that `ended` holds generation 1 with `why = rotated` no earlier than 20s after the status's report and within 20s + 30s (SC-006 scaled to the suite's grace); the service's status never leaves `Ready`/`UpdateInProgress`
- [X] T048 [US2] Steps for scenario 6 ("a cloud request that goes with its service deletes nothing"): delete the service; the `CloudResource`s are gone, the Secret remains, the fake's `made` still holds the bucket and `ended` gained nothing; run the suite on the `cluster` workflow and record in research

**Checkpoint**: both halves of FR-008 and FR-009 are proven under the real grant and the fake's
records; US1 and US2 together are what 039 needs from this feature.

---

## Phase 5: User Story 3 — No provider is installed, and the platform says so (Priority: P2)

**Goal**: an unanswered request is reported after the bound and recovers when answered; an
installation with no provider is refused what needs one and renders exactly what it did before;
a member can read the installation's cloud settings.

**Independent Test**: `FEAT/absent.feature` scenarios 1 and 2 in `CloudProviderClusterFeatures`;
scenario 3's four rows in `CloudProviderNeededSuite`; scenario 4 in `RenderingUnchangedSuite`
and `RenderingGoldenSuite`.

### The control plane and the CLI

- [X] T049 [US3] Add `ankka.controlplane.cloud { provider, account, location, kms-key }` with `${?ANKKA_CLOUD_*}` to `controlplane/src/main/resources/reference.conf` (control-plane.md "Settings"); `CP/CloudConfig.scala` with `read(config): Option[CloudConfig]` refusing an unknown provider naming the known ones; four `ANKKA_CLOUD_*` env literals on the control plane's container in `KUST/components/controlplane/deployment.yaml` (the replacements of T020 already target them); a case in `CPT/RemoteOverlaySuite.scala` that each appears once on the control plane's container in both overlays
- [X] T050 [P] [US3] Write `APIT/CloudProviderNeededSuite.scala` first with the four rows of `FEAT/absent.feature` scenario 3 as cases, each in the row's words: `problem("keeping the project secrets of \"shop\" in the cloud account", "gcp", None)` is `Some("… needs the cloud provider gcp, and the installation has none")`, likewise the object store, the backups and the wrapping key; with `Some("gcp")` each is `None`; with `Some("aws")` the message names both; then create `API/CloudProviderNeeded.scala` (control-plane.md "`CloudProviderNeeded`") and run `sbt controlPlaneApi/test` green
- [X] T051 [P] [US3] Add `Installation(platformVersion: String, cloud: Option[CloudInstallation])` and `CloudInstallation(provider, account, location, kmsKey: Option[String] = None)` to `API/descriptors.scala` with codecs in `Wire`, and a round-trip case (with and without `kmsKey`) in `APIT/WireSuite.scala` (or the suite that holds the other wire types)
- [X] T052 [US3] Write `CPT/InstallationRouteSuite.scala` first (the pattern of the other endpoint suites, `HttpServer.at("127.0.0.1", 0)`, the fake issuer): `GET /installation` as a member of no organization with provider `none` answers `platformVersion` and no `cloud`; with `gcp` answers the three fields and no `kmsKey`; as an owner of an organization answers `kmsKey`; unauthenticated is 401; then create `CP/InstallationEndpoint.scala` (`Acl.Authenticate`; the owner check reads the caller's organizations in the endpoint, never a handler) and register it in `CP/ControlPlane.scala`; run the suite green
- [X] T053 [US3] Add `ankka installation` to the CLI in `CLI/` (the command, `Output.installation` table with `platform`, `provider`, `account`, `location`, `kms key` rows, `--json`), `CLIT/InstallationCommandSuite.scala` against a stub server, and regenerate `DOCS/reference/cli.md` and `DOCS/reference/control-plane-api.md` with `just docs-reference` (control-plane.md "`ankka installation`")

### The operator says what is missing

- [X] T054 [US3] Steps for `FEAT/absent.feature` scenario 1 ("a cloud request nobody acknowledges is reported after the acknowledgement bound"): a second k3s fixture phase in `CloudProviderClusterFeatures` with the scripted provider **stopped**; apply; within the bound the `objectStorage.phase` is `Waiting` with no "no provider" detail; after 30s + 30s it is `Waiting` with `no provider for gcp has answered` and `services get` shows it (SC-004: within bound + 30s)
- [X] T055 [US3] Steps for scenario 2 ("the status recovers when a cloud provider answers"): start the scripted provider; within 30s the phase is `Provisioned` and the bucket named (the informer of T036 is what makes 30s possible; a resync alone would fail this case)
- [X] T056 [P] [US3] Cases for scenario 4 ("an installation with no cloud provider serves everything itself"): in `OPT/RenderingGoldenSuite.scala` assert no golden file but `cloud-bucket.txt` contains `cloud request`; in `OPT/ObjectStorageRenderingSuite.scala` a case that with `cloud = None` and Garage settings the actions are exactly 034's; `RenderingUnchangedSuite` green with no repin is the proof for the pinned fixtures; name both in `ranElsewhere`
- [X] T057 [US3] Run `CloudProviderClusterFeatures` on the `cluster` workflow; record in research

**Checkpoint**: nothing hangs silently, and a member can read what the installation named.

---

## Phase 6: User Story 4 — Each feature's need is one request kind (Priority: P2)

**Goal**: the four renderers 038, 041 and 042 will call exist, with the contract's keys and
nothing cloud-shaped, so each consumer adds a setting and a call, never a kind.

**Independent Test**: `FEAT/kinds.feature` scenarios 1–6 as cases in `CloudRequestsSuite`, each
named for its scenario, each named in `ranElsewhere` with the consumer that will re-point it.

- [X] T058 [P] [US4] Cases first in `OPT/CloudRequestsSuite.scala`: scenario 1 — `CloudRequests.identity` (already in T014) beside `CloudRequests.secretAccess(subject, identity, own, read)` is `<service>-secret-access` with `identity`, `own` = the service's secret ids comma-joined, `read` = the project's; scenario 2 — `CloudRequests.secretSync(project, "checkout", entries = Map("STRIPE_KEY" -> id, "WEBHOOK_KEY" -> id), entryGeneration)` is `<project>.secret-sync` with `secretName`, `entries` as `NAME=id,NAME=id` in key order, `entryGeneration`; scenario 3 — `CloudRequests.bucket(project subject, purpose = "backup", …)` is `<project>.backup-bucket` and `CloudRequests.bucketCredential(project subject, bucket, identity = the database's, secretName = the database's backup Secret)` is `<project>.backup-credential`, and `Rendering` given a backup bucket plan renders no `ANKKA_S3_` variable naming it and no route (a case in `OPT/ObjectStorageRenderingSuite.scala`); scenario 4 — `CloudRequests.wrappingKey(subject, identity, key)` is `<project>.wrapping-key` with `identity`, `key`; scenario 5 — with `settings.cloud.kmsKey = None`, `CloudRequests.wrappingKey` is not callable without a key (the renderer takes a `String`, and the caller's refusal is `CloudProviderNeeded`'s, T050); scenario 6 (outline, six rows) — for each renderer, the parameter key set equals `Keys.<kind>` exactly, no key or value contains `google`, `gcs`, `gcp`, `aws`, `s3`, `azure`, `iam` or a region-looking token, and `location` equals the settings' string verbatim
- [X] T059 [US4] Implement `secretAccess`, `secretSync`, `wrappingKey` and the backup-purpose naming in `OP/CloudRequests.scala` and `OP/Names.scala`; `Keys` complete for all six kinds and their outputs; run `sbt 'operator/testOnly *CloudRequestsSuite *ObjectStorageRenderingSuite'` green
- [X] T060 [US4] Extend `OPT/ScriptedCloudProviderSuite.scala` and `ScriptedFulfilment` so the four kinds' outputs are exactly the contract's (`identity`, none, `entryGeneration` echoed, `key` echoed), and `secret-sync` writes the named Secret by `patch` with the entries' made-up values; the `kinds.feature` consumer scenarios are listed in `CloudProviderClusterFeatures.ranElsewhere` as "`CloudRequestsSuite`, until feature 038/041/042 adds the setting that writes it"

**Checkpoint**: the contract is whole: six renderers, six fulfilments, no seventh kind anywhere.

---

## Phase 7: User Story 5 — The platform's own suites need no cloud (Priority: P3)

**Goal**: the k3s suite is proven to reach no cloud, the same scenarios can be pointed at a real
provider, and a second provider needs only its name.

**Independent Test**: `FEAT/providers.feature` scenario 1 in `CloudProviderClusterFeatures`,
scenario 3 in `SettingsSuite` and `CloudRequestsSuite`, scenario 2 by hand in external mode and
nightly in `ankka-gcp` (named in `ranElsewhere` as not run here).

- [X] T061 [US5] Steps for `FEAT/providers.feature` scenario 1 ("the scripted cloud provider fulfils every cloud request without a cloud"): at the suite's `afterAll`, assert `fulfilment.reached == 0` and that every scenario the suite ran passed; make `reached` count any `java.net` connection attempt the fulfilment or controller would make (there are none; the counter is the proof that stays)
- [X] T062 [P] [US5] Cases for scenario 3 ("a cloud provider for another cloud needs only its name known to the platform"): in `OPT/SettingsSuite.scala`, with `CloudProviders` temporarily widened in the test through a seam (`CloudSettings.read(known = Set("gcp", "other"))`), `ANKKA_CLOUD_PROVIDER=other` is accepted; in `OPT/CloudRequestsSuite.scala`, two renders differing only in the provider differ only in `spec.provider`; a comment in `CORE/PlatformVariables.scala` says adding a name there is the whole change on ankka's side
- [X] T063 [US5] External mode in `CPT/CloudProviderClusterFeatures.scala`: when `ankka.cloud.external` names a kubeconfig, start no k3s and no scripted provider, build the admin client from that file, read `ANKKA_CLOUD_*` from the environment into the operator's settings, run the same steps, and replace the fake's record assertions with the observable ones (the Secret's data changed, the status's generation, the roll) under a `fakeRecords: Boolean` guard that is `false` externally; `ranElsewhere` names scenario 2 as `ankka-gcp`'s; document the mode in provider.md's "Running the features against a real provider" and in `DOCS/platform/cloud-provider.md`
- [ ] T064 [US5] Run the suite once by hand in external mode against the local kind cluster with `cloudProvider: gcp` set and no provider deployed, to prove the mode starts, deploys, and reports the absence (the only external run this repository can make); record the command and the outcome in research

**Checkpoint**: ankka's CI never sees a cloud; `ankka-gcp` has a suite to run.

---

## Phase 8: Polish — documentation, rules, the whole run

- [X] T065 [P] Write `DOCS/platform/cloud-provider.md` from cloud-resource.md, provider.md and installation.md: for an installer (the six settings, the component, what a provider's install adds, what an installation without one does) and for a provider's author (the resource, the kinds, the credential rule, the grant, the scripted provider and the feature files, "your cloud roles are owner-equivalent and that is why the operator does not hold them"); add it to `mkdocs.yml`'s nav under the platform section
- [X] T066 [P] Amend `DOCS/platform/object-storage.md` with "On an installation whose object store is its cloud account's" (the five variables set from the fulfilments, `Provisioned` and `Recovered` through a provider, no rotation command yet), `DOCS/platform/install-cloud.md` ("Apply it, in order" gains the component and the settings), `DOCS/reference/configuration.md` ("Set by the platform" names `ANKKA_CLOUD_*`), `DOCS/reference/limitations.md` (one provider per installation; a storage credential's rotation has no command; nothing in a cloud is ever deleted; no `managed-database` kind)
- [X] T067 [P] Add a section "A cloud request is rendered like a `KafkaTopic` and answered by a provider" to `.claude/rules/kubernetes.md` (the resource, the two grants, the informer, the withheld Deployment, the dotted project name, the `enqueueAfter`), and every trap T030, T042, T048, T057 taught
- [X] T068 `just docs` and `just features` green; `sbt scalafmtAll scalafmtSbt`; `sbt -Dankka.cluster.tests=off test` green on the laptop
- [ ] T069 `gh workflow run cluster --ref 044-cloud-provider-impl` (every suite) green, in particular `OperatorClusterSuite`, `CloudProviderClusterFeatures`, `ObjectStorageClusterFeatures` (Garage unchanged) and `EndToEndClusterSuite`; move every row of research's "Verify first, gathered" to "Verified during implementation"
- [ ] T070 Review `spec.md` against what was built: the checklist's stale notes (the marker, the hook), SC-003 as reworded (no cloud SDK in any module's compile scope, image or main source; the S3 test client stays), SC-005's claim stays a claim on 038/039/041/042 until they are amended; open the pull request with the summary of plan.md and the attribution lines

---

## Dependencies & Execution Order

- **Phase 1 → Phase 2 → Phase 3** in order: the words, then the resource and the fake, then the
  bucket path. T030 (the first real-API-server run) gates Phase 3's k3s steps but not its offline
  tasks (T031–T036), which can start once T017 is in.
- **Phase 4 (US2)** needs Phase 3's suite (T037) and the fake (T023–T024); its offline tasks
  (T043–T045) need only Phase 2.
- **Phase 5 (US3)** needs the suite for T054–T057; the control plane tasks (T049–T053) need only
  Phase 1 and can run beside Phase 3.
- **Phase 6 (US4)** needs only Phase 2 and can run beside Phases 3–5.
- **Phase 7 (US5)** needs Phases 3 and 5 (the suite and the absence steps).
- **Phase 8** last; T065–T067 can be drafted any time after Phase 2.

### Within Phase 2

T005→T006→T007→T008→T009 (the resource), T010→T011, T012, T013→T014, T015→T016, T017 (the
operator's seams, after the resource), T018–T021 (the YAML, independent of the Scala), T022→T023→
T024→T025 (the fake, after T006), T026–T029 (after T017, T018, T025), T030 (after all).

## Parallel Execution Examples

- After T006: T010/T011 (settings), T012 (names), T013/T014 (renderers), T015/T016 (decision),
  T018–T020 (YAML), T022/T023 (fulfilment) are six independent tracks.
- After Phase 2: T031–T036 (US1 offline), T043–T045 (US2 offline), T049–T053 (US3 control plane),
  T058–T060 (US4) are four independent tracks; T065–T067 (docs) a fifth.
- k3s runs (T030, T042, T048, T057, T069) are serial by nature and go to the `cluster` workflow.

## Implementation Strategy

- **MVP = Phases 1–3**: the contract, the fake, and a bucket a service gets from a provider. That
  is what 039 cannot ship without, and it already proves the two grants under real identities.
- **Then US2**, which is the reason the provider exists, and **US3**, which is the failure mode of
  every split design; together they close FR-004, FR-008 and FR-009.
- **US4 and US5** complete the contract for the consumers and for `ankka-gcp`; neither changes
  what US1–US3 built.
- Every k3s run goes to the `cluster` workflow on the branch, one suite at a time while iterating
  and the whole matrix before the pull request.
