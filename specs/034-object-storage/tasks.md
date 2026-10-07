# Tasks: Object Storage — A Bucket Per Service, Provisioned Like a Database

**Input**: Design documents from `/specs/034-object-storage/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" list turns each fact read from
documentation into a spike or a test before the code that relies on it. The scenarios are in
`features/object-storage/` and `features/web-hosting/object-storage.feature`; where a task says
"case", it means a `test(...)` in the named suite, named for the scenario or the rule it holds.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a service asks for a bucket and gets one it can use), US2 (a credential reaches
  one bucket and nothing else), US3 (deletion keeps the bucket and a supplied store opts out), US4
  (a browser reaches one object through a URL the service signed)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CRD`/`CRDT` =
`crd/src/{main,test}/scala/…/crd`; `CORE`/`CORET` = `modules/core/src/{main,test}/scala/…/core`;
`API`/`APIT` = `controlplane-api/src/{main,test}/scala/…/controlplane/api`; `CP`/`CPT` =
`controlplane/src/{main,test}/scala/…/controlplane`; `OP`/`OPT` =
`operator/src/{main,test}/scala/…/operator`; `SCT` = `sidecar/src/test/scala/…/sidecar`;
`CLI`/`CLIT` = `cli/src/{main,test}/scala/…/cli`; `KUST` = `kustomization`; `CON` =
`console/package`; `DOCS` = `docs`; `FEAT` = `features/object-storage`. "R*n*" is a section of
`research.md`; "S*n*" a row of its *Verify first, gathered* table; a contract is named by its file
under `contracts/`.

The branch `034-object-storage` exists, in the worktree `.claude/worktrees/034-object-storage`.
Work there. Every `sbt` command below takes `-Dankka.cluster.tests=off` unless the task names a k3s
suite or a spike; a k3s run belongs under `caffeinate -i`. sbt's project ids are `crd`, `core`,
`controlPlaneApi`, `controlPlane`, `operator`, `sidecar`, `cli`.

---

## Phase 1: Setup — the three things the design rests on, shown true before anything is built on them

**Purpose**: R2, R5 and R15 each rest on a fact read from documentation. A spike is a throwaway
suite that runs only under `-Dankka.spikes=on`, as `GatewayGrpcSpike` and `RememberEntitiesSpike`
do; each ends by writing what it found under a new heading, *Verified during implementation*, at
the end of `research.md`. If one is false, stop and change the decision it holds before going on.

- [X] T001 Add the AWS SDK for Java's S3 module as a test dependency: `val awsS3 = "software.amazon.awssdk" % "s3" % <current 2.x>` in `project/Dependencies.scala`, and `awsS3 % Test` on the `operator` and `controlPlane` projects in `build.sbt`. Confirm `sbt operator/Test/compile controlPlane/Test/compile` and that `sbt 'show operator/Compile/dependencyClasspath'` names no `awssdk` jar (it must be on no main classpath).
- [X] T002 [P] Spike S1 in `OPT/GarageSpike.scala`: start `dxflrs/garage:v2.3.0` with testcontainers' `GenericContainer`, the command `server --single-node`, a `garage.toml` exactly as `contracts/installation.md` gives it, and the RPC secret and admin token as files named by `GARAGE_RPC_SECRET_FILE` and `GARAGE_ADMIN_TOKEN_FILE`. Assert: `POST /v2/CreateBucket` succeeds with no layout step; `/garage status` run in the container exits non-zero before the node serves and 0 after; the admin API refuses a request with no token; nothing listens on 3902. Record the exact file-permission requirement for the secret files (Garage refuses world-readable ones unless told otherwise).
- [X] T003 [P] Spike S2 in the same file: create a bucket with `globalAlias` `shop.reports`; `POST /v2/CreateKey` with a name, and confirm the response carries `secretAccessKey` and that `GET /v2/ListKeys` returns names; `POST /v2/AllowBucketKey` twice with `read`, `write`, `owner` and confirm the second changes nothing; `GET /v2/GetBucketInfo?globalAlias=shop.reports` and confirm `created` and the allowed keys are in it. Then with the AWS SDK (`forcePathStyle`, region `garage`, endpoint override) put and get an object, presign a GET and a PUT and use both with `java.net.http`, and confirm a client left at `us-east-1` is refused with `AuthorizationHeaderMalformed`. Confirm `PutBucketCors` succeeds with an owner key.
- [X] T004 Spike S3 in `CPT/BucketRouteSpike.scala` on k3s with `GatewayStack`: the store from T002's manifest in a namespace `garage-system`; two namespaces labelled `app.kubernetes.io/managed-by: ankka`, each with an `HTTPRoute` on `storage.<base>` matching a different `PathPrefix` and naming the store's Service across namespaces; one `ReferenceGrant` per namespace in `garage-system`. Assert from the host with `curl --cacert --resolve` through the mapped port: each path reaches the store; a path with no route is the Gateway's 404; with a grant removed its route answers 500 and its condition is `RefNotPermitted`; and a URL presigned for `storage.<base>:<mapped port>` verifies, which shows Envoy forwards the authority with its port. Also assert `timeouts.request: "0s"` is accepted on the rule.

**Checkpoint**: three findings are written into `research.md`. Nothing else has changed.

---

## Phase 2: Foundational — names, fields, the store's client, the credential, the component

**Purpose**: what every story reads. Nothing here provisions anything yet.

**⚠️ CRITICAL**: a field on `AnkkaServiceSpec` or inside the status block that `ankkaservice.yaml` does not declare passes every offline suite and is refused by a real API server on every projection. T008 extends the one suite that sees it; show it failing before trusting it.

### Names and the declaration

- [X] T005 [P] Write `CRDT/BucketsSuite.scala` per `contracts/descriptor-and-status.md` "The names", red: `name("shop","reports") == "shop.reports"`; `secret("reports") == "reports-storage"`; `problems` empty at 63 characters and one problem at 64 whose message contains "63" and both the project and the service; `publicEndpoint` with port 443 has no port and with 8443 has `:8443`; `publicAddress` ends `/shop.reports`. Add a case to the existing hostnames suite that `Hostnames.StorageLabel == "storage"` and that it contains no hyphen.
- [X] T006 Create `CRD/Buckets.scala` and add `StorageLabel` and `storage(baseDomain)` to `CRD/Hostnames.scala` per R5. `crd` must still depend on nothing of ankka's. T005 green.
- [X] T007 [P] In `CORET/PlatformVariablesSuite.scala` add, red: `objectStorage("ANKKA_S3_BUCKET")` and `objectStorage("ANKKA_S3_ANYTHING")` are true, `objectStorage("ANKKA_DB_HOST")` is false, and for `ANKKA_S3_BUCKET` each of `platformOnly`, `runtimeOnly`, `shared` and `withheldFromModule` is false. Then add `ObjectStoragePrefix` and `objectStorage` to `CORE/PlatformVariables.scala` per R10, importing nothing. Run `sbt 'core/testOnly *PlatformVariablesSuite' 'controlPlane/testOnly *PlatformDeclarationSuite' operator/compile`.

### The resource

- [X] T008 In `CRDT/AnkkaServiceCodecSuite.scala` add, red: a resource with neither new spec field decodes with both `false`; both round-trip when `true`; an `ObjectStorageStatus` round-trips; a status with no `objectStorage` decodes to `None` and is omitted from the JSON, not written as null. In `OPT/CrdSchemaSuite.scala` add a case that compares the properties declared under `status.properties.objectStorage.properties` with `ObjectStorageStatus`'s fields **in both directions**, and the phase's enum with the five phases.
- [X] T009 Add `provisionObjectStorage: Boolean = false` and `exposeObjectStorage: Boolean = false` to `AnkkaServiceSpec`, `ObjectStorageStatus` and `objectStorage: Option[ObjectStorageStatus] = None` on `AnkkaServiceStatus` in `CRD/AnkkaService.scala` per R13, with comments that say what is true (absent only when the service neither asks nor supplies). Declare all three in `KUST/components/crd/ankkaservice.yaml`. T008 green. Then show `CrdSchemaSuite` failing twice and restore each: remove `exposeObjectStorage` from the yaml; remove `publicAddress` from inside the block.

### The store's client

- [X] T010 [P] In `OPT/SettingsSuite.scala` add, red: no admin URL gives `objectStore == None`; all five variables give `Some` with each value; an admin URL with the token missing fails naming `ANKKA_OBJECT_STORE_ADMIN_TOKEN`; `ANKKA_OBJECT_STORE_SERVICE` `garage-system/garage:3900` parses to its three parts and a malformed one fails naming the variable. Then add `ObjectStoreSettings` and `objectStore` to `OP/Settings.scala` per R3 and `contracts/operator.md` "Settings", each with its system property.
- [X] T011 [P] Create `OP/ObjectStore.scala` per `contracts/operator.md` "The store's interface": the trait, `BucketInfo`, `IssuedKey` whose `toString` prints the id only, and `ObjectStoreUnavailable`. Add a case in a new `OPT/ObjectStoreSuite.scala` that `IssuedKey("GK1","s3cret").toString` does not contain `s3cret`.
- [X] T012 Write `OPT/GarageStoreSuite.scala`, red, against the real image started as T002 found: `bucket` is `None` for a name never made; `createBucket("shop.reports")` then `bucket` returns its id, a `created` within the last minute and no allowed key; `createKey` returns an id and a secret; `keysNamed` matches the exact name and not a prefix of it; `allow` then `bucket` shows the key allowed, and a second `allow` changes nothing; a key allowed on one bucket is refused by another (asserting the store's 403, with the AWS SDK); `deleteKey` then the key is refused; a store at a port nothing listens on throws `ObjectStoreUnavailable`; a wrong token throws something that is not `ObjectStoreUnavailable`. Record every request the client makes and assert none has `showSecretKey` in it. The suite is ignored under `-Dankka.cluster.tests=off` only if it cannot be made to run in the offline build's time; prefer that it runs.
- [X] T013 Create `OP/GarageStore.scala` per R4 over `java.net.http.HttpClient` and Jackson, with a connect and a request timeout. T012 green. Delete `OPT/GarageSpike.scala`: the suite now holds what the spike found.

### The credential

- [X] T014 Write `OPT/StorageCredentialSuite.scala`, red, with an in-memory `ObjectStore` double and a `SecretWriter` double, one case per row of `contracts/operator.md` "`StorageCredential.ensure`, in full" and per row of R6's interruption table: nothing exists → one key, allowed, in the Secret; stale keys and no Secret → the new key in the Secret and the stale ones deleted; keys and a Secret → the speculative key deleted, the Secret untouched, no `patch`; no keys and a Secret → the Secret patched; a second call for the same Secret in one process makes no call at all; the key is allowed before the Secret's `create` (assert the order of calls); a store that throws leaves the Secret unwritten and the call unremembered. Assert the Secret written is `Opaque`, has the labels given, no owner reference, and exactly the entries `ANKKA_S3_ACCESS_KEY` and `ANKKA_S3_SECRET_KEY`.
- [X] T015 Create `OP/StorageCredential.scala` per R6: `trait SecretWriter { def create(secret: Secret): SecretWriter.Created.type | SecretWriter.Exists.type; def patch(namespace: String, name: String, entries: Map[String, String]): Unit }` (or an enum for the answer) and `ensure`. No log line or exception message in it may carry a secret key. T014 green.

### The component

- [X] T016 Create `KUST/components/garage/` per `contracts/installation.md`: `kustomization.yaml` (`kind: Component`), `namespace.yaml`, `config.yaml`, `statefulset.yaml`, `service.yaml`, `zero-trust.yaml`, `grants.yaml`, `secrets.yaml` and `operator-patch.yaml`, using what T002 found about the files' permissions. The patch names the container `ankka-operator`. The policy names the gateway's proxies as `KUST/components/controlplane/zero-trust.yaml` does and the operator's pods by namespace and name label. Add the component after `operator` in `KUST/overlays/local/kustomization.yaml` and `KUST/overlays/cloud/kustomization.yaml`, and in the cloud overlay add `$patch: delete` for `garage-secrets` and `ankka-object-store-admin` beside the Keycloak admin secret's.
- [X] T017 In `CPT/RemoteOverlaySuite.scala` add the cases `contracts/installation.md` lists: each of the five `ANKKA_OBJECT_STORE_*` variables is set exactly once on the operator's Deployment and the operator has one container, in both renders; the two Secrets are absent from the cloud render and present in the local one; the StatefulSet's image is `dxflrs/garage:v2.3.0`; the namespace `garage-system` has no `managed-by` label; the Role grants `referencegrants` `get`, `create`, `patch` and nothing else. Run with `kubectl` on the PATH and read that it ran. Show one case failing by renaming the container in the patch to `operator`, then restore it.
- [X] T018 [P] Create `OPT/ObjectStoreStack.scala` per `contracts/installation.md` "The k3s suites": `install(k3s, k8s, repoRoot): ObjectStoreSettings`, applying the component's files except the operator's patch with the node's `kubectl`, waiting for the StatefulSet, and returning settings whose admin URL a test in this JVM can reach (a port-forward, as `KeycloakStack.forwardService` does) beside the in-cluster endpoint a pod is given. Add to `KUST/deploy-local.sh` one wait for `statefulset/garage` in `garage-system`, beside the waits it has.

**Checkpoint**: `sbt 'crd/test' 'core/testOnly *PlatformVariablesSuite' 'operator/testOnly *SettingsSuite *GarageStoreSuite *StorageCredentialSuite *CrdSchemaSuite' 'controlPlane/testOnly *RemoteOverlaySuite *PlatformDeclarationSuite'` is green. `kubectl kustomize kustomization/overlays/local` renders the store. No service is given anything yet.

---

## Phase 3: User Story 1 — a service asks for a bucket and gets one it can use (Priority: P1) 🎯 MVP

**Goal**: `provisionObjectStorage: true` gives a service a bucket, a credential and five variables on the developer's container; the status says so to a member, in the CLI and the console; a service that does not ask is given nothing and nothing rendered for it changes.

**Independent Test**: `caffeinate -i sbt 'controlPlane/testOnly *ObjectStorageProvisioningFeatures'` runs `FEAT/provisioning.feature` whole on k3s: the variables, an object kept and read back from inside the pod, the status, nothing for a service that does not ask, no instance without a store, waiting while the store cannot be reached, and a name over the limit refused.

### Tests for User Story 1 (first, and red)

- [X] T019 [P] [US1] Write `APIT/ObjectStorageDescriptorSuite.scala`: a descriptor with neither field decodes with both `false`; `"provisionObjectStorage": true` round-trips and is omitted from the JSON at `false`; a JSON `null` for it decodes as `false`; a descriptor of each hosting, web included, with the field set has no problems. In `CPT/EventCompatibilitySuite.scala` add to the "decodes as before" case that the pre-feature `ServiceApplied` has both fields `false`, and a round trip of a `ServiceApplied` with both `true`.
- [X] T020 [P] [US1] In `CPT/ServiceProjectionSuite.scala` add: the field is projected as written for each hosting; `provisionDatabase` is unchanged by it; a project and service whose bucket name is 64 characters is `Left` with `Buckets.problems`' message. In the control plane's fast HTTP suite (`CPT/ControlPlaneHttpSuite.scala`) add the case named for the scenario "a descriptor that asks for a bucket is refused when the service's name cannot name one": a 400 whose body names the limit.
- [X] T021 [P] [US1] Write `OPT/ObjectStorageSuite.scala`, the rule table of `contracts/operator.md` "The plan", one case per row, plus: rule order (a name over the limit beats a missing store; a missing store beats an unreachable one); `recovered` is false with no timestamps; the reported phase of each plan; `status` for each plan per the contract's `status.objectStorage` table.
- [X] T022 [P] [US1] Write `OPT/ObjectStorageRenderingSuite.scala` per `contracts/operator.md`: for `Waiting(None)` and `Ready`, `EnsureBucket` then `EnsureStorageCredential`, both before `ApplyDeployment`; for `Waiting(Some(_))`, `Failed`, `NotAsked` and `Supplied`, neither; `describe` of the credential action contains the namespace and the name and nothing else of note. For each of the four hostings with the flag set: the container named in "The developer's container" has the `envFrom` and the three literals, and every other container in the pod has no variable beginning `ANKKA_S3_` and no `envFrom` of that Secret. For each hosting without the flag: no container has either. The `envFrom` is rendered when the plan is `Failed` too.
- [X] T023 [US1] Write `CPT/ObjectStorageClusterSteps.scala`, an abstract `GherkinSuite` modelled on `CPT/WebHostingClusterFeatures.scala`'s `WebHostingClusterSteps`: k3s, `GatewayStack`, CNPG, `ObjectStoreStack`, the sample image under this build's tag, the control plane and the operator in this JVM, and steps for every sentence of `FEAT/provisioning.feature`. A step that needs an installation with no object store, or one whose store cannot be reached, restarts the in-JVM operator with those settings. "Keeps the object" and "reads the object back" run the pod's own `curl --aws-sigv4` with the pod's own variables through `kubectl exec` (check first that the image's `curl` has the flag, S6). "No bucket exists" asks the store's admin API with the test's own `GarageStore`. "No instance starts" asserts no pod of the service has a running container, after the operator has reported `Failed`. Add `class ObjectStorageProvisioningFeatures extends ObjectStorageClusterSteps("../features/object-storage/provisioning.feature")`. It is red until T031.

### Implementation for User Story 1

- [X] T024 [US1] Add `provisionObjectStorage: Boolean = false` to `ServiceSpec` in `API/descriptors.scala` (the field only; its refusals are US3's and US4's). In `CP/deploy/ServiceProjection.scala` project it and add `Buckets.problems` to the problems when it is set. In `CP/api/ServiceEndpoint.scala`'s apply add `Buckets.problems(projectId, name)` to the descriptor's problems when it is set, so the refusal is the 400 `invalid descriptor: …`. T019 and T020 green.
- [X] T025 [US1] Create `OP/ObjectStorage.scala` per R7: `ObjectStoragePlan`, `ObjectStorageObservation`, `decide`, `reportedPhase` and `status(plan, spec, settings)`. It reads the prefix from `PlatformVariables.objectStorage` and the name from `Buckets`. T021 green.
- [X] T026 [US1] Add `EnsureBucket(bucket: String)` and `EnsureStorageCredential(namespace: String, secretName: String, labels: Map[String, String], bucket: String)` to `OP/Action.scala` with their `describe`. In `OP/Executor.scala`: `Fabric8Executor` takes `store: Option[ObjectStore]`; `observeObjectStorage(bucket: String): ObjectStorageObservation` reads the bucket and catches `ObjectStoreUnavailable` into `unreachable`; `EnsureBucket` reads, creates when absent, and allows every key named for the service when the bucket shows none allowed; `EnsureStorageCredential` calls `StorageCredential.ensure` with a `SecretWriter` over the client (a `create` whose `KubernetesClientException` code is 409 is `Exists`; `patch` is a JSON merge patch of `stringData`) and the per-process set `EnsureSecretKey` uses the shape of. Update every other `Executor` implementation in the tests so they compile.
- [X] T027 [US1] In `OP/Rendering.scala` add `objectStorageActions` after `secretKeyAction` per R8, and pass a `storageEnv` to the container that R9 names for each hosting: through the shared `container` builder for embedded and wasm, and onto `<service>-app` in the process and web cases. Nothing is added to any container of a service that does not ask. In `OP/ServiceReconciler.scala` decide the plan (observing only when the service asks and a store is configured) and set `status.objectStorage`; in `OP/LifecycleRules.scala` or beside it, build the block. In `OP/Operator.scala` build the `GarageStore` from the settings. T022 green.
- [X] T028 [US1] Golden and unchanged. Add a service with the flag to `OPT/RenderingGoldenSuite.scala` and write `operator/src/test/resources/golden/object-storage.txt` with `-Dankka.golden.update=true`; read it and confirm no secret key and no access key is in it. Then run `OPT/RenderingUnchangedSuite.scala` unedited and record what fails. If, and only if, the exposure removal of T049 is not yet in, it must pass with no file changed: that is the proof for this phase that a service that does not ask is rendered as before.
- [X] T029 [P] [US1] The status a member reads, tests then code, per R14 and `contracts/descriptor-and-status.md`. Tests: in `CPT/StatusIngestSuite.scala` the phase is carried from `Reported`, kept through `Unreachable`, `Refused` and `NoReport`, and a `Failed` or `Waiting` block's detail is folded as `object storage: <detail>`; in `CPT/ServiceEntitySuite.scala` each phase becomes its phrase, an observation that differs only in the phase is persisted and an identical one is not; a case that `GET /services/{project}` and `GET /services/{project}/{name}` return the same three values (the listing may lag: retry on the value, not on the row existing); in `CPT/EventCompatibilitySuite.scala` the pre-feature `ServiceObserved` decodes with `objectStorage == None`. Code: `objectStorage: Option[String] = None` on `ServiceObserved` and `ServiceObservation` in `CP/domain/events.scala` and on `Service` in `CP/domain/model.scala` with `Service.objectStoragePhrase` used by `toStatus`; carry it in `CP/deploy/StatusIngest.scala`, `CP/application/ServiceEntity.scala` and `CP/application/ServiceRows.scala` (which destructures the event positionally and will not compile until it does), using the one phrase function in both; add `objectStorage`, `bucket` and `bucketAddress` to `ServiceStatus` in `API/descriptors.scala`, filling `bucket` from `Buckets.name` when the descriptor asks.
- [X] T030 [P] [US1] The CLI: in `CLIT/OutputSuite.scala` add a case that a status with the fields prints `object storage` and `bucket` after `database`, and confirm the existing case that pins `database` as the last line of a service without them is unchanged. Then print them in `CLI/Output.scala`, each only when present, and mention the bucket in the `get` and `delete` tool descriptions in `CLI/mcp/AnkkaTools.scala`.
- [X] T031 [US1] Run `caffeinate -i sbt 'controlPlane/testOnly *ObjectStorageProvisioningFeatures'` and make it green. Then show three scenarios failing once each and restore: render the `envFrom` on the wrong container; make `decide` answer `Waiting` where it should answer `Failed` for no store; make the name's limit 64.
- [ ] T032 [US1] The process and the module, `FEAT/hostings.feature`: in `SCT/SidecarClusterSuite.scala` install `ObjectStoreStack` and add two tests named for the scenarios. For the process: apply the Python example with the flag, and with `kubectl exec -c <service>-app -- env` and `-c <service> -- env` assert the five variables are in the first and none beginning `ANKKA_S3_` is in the second. For the module: apply the Rust cart with the flag and assert through a route of the module that reads `config` (or, if the cart has none, a case in `sidecar`'s `WasmHostSuite` that `HostImports.lookup("ANKKA_S3_BUCKET")` answers the environment's value, named for the scenario, with the k3s case asserting the runtime container's `env`).
- [ ] T033 [US1] The web-hosted service, `features/web-hosting/object-storage.feature`: add `class WebHostingObjectStorageFeatures extends WebHostingClusterSteps("../features/web-hosting/object-storage.feature")` in `CPT/WebHostingClusterFeatures.scala`, with `ObjectStoreStack` installed for it and steps for the two scenarios (the process's `env` and the proxy's by `kubectl exec -c`; the round trip from the process's container, whose stand-in image must have `curl` with `--aws-sigv4` or be given it). Confirm `features/web-hosting/unchanged.feature`'s suite is still green.
- [X] T034 [P] [US1] The console, `FEAT/console.feature`: in `APIT/ControlPlaneFixturesSuite.scala` set the three fields in the full `ServiceStatus` sample and `provisionObjectStorage` in the full `ServiceSpec` sample, and write the fixtures again with `-Dankka.docs.update=true`. Add the three optional fields to `serviceStatusSchema` in `CON/src/client/schemas.ts`; add the `Object storage` fact after `Database` in `CON/src/routes/service.tsx` per `contracts/descriptor-and-status.md` "The console's service page", and the bucket to the delete text; derive the fields in `CON/src/testing/fake-control-plane.ts` from the descriptor it was given. In `console/e2e/tests/services.spec.ts` add `Object storage` to the label list and three tests named for the outline's rows. Run `just test-console`.

**Checkpoint**: User Story 1 is whole. A deployed service keeps and reads an object in a bucket of its own, and `ankka services get` and the console say which bucket. Nothing is reachable from outside the cluster and nothing yet refuses a descriptor that both asks and supplies.

---

## Phase 4: User Story 2 — a credential reaches one bucket and nothing else (Priority: P1)

**Goal**: a storage credential is refused by every other bucket, cannot be taken through a descriptor or a project secret's name, is written once, and cannot be read back by the operator.

**Independent Test**: `caffeinate -i sbt 'controlPlane/testOnly *ObjectStorageIsolationFeatures'` and the two cases of `OperatorClusterSuite` in T039.

### Tests for User Story 2 (first, and red)

- [X] T035 [P] [US2] In `APIT/ObjectStorageDescriptorSuite.scala` add the case named for "a descriptor cannot take a variable from the secret that holds a storage credential": a `secretKeyRef` to `exports-storage` and to the service's own `reports-storage` are each refused with the platform-issued message naming the variable and the secret, for every hosting. In `APIT/ProjectSecretsSuite.scala` add `payments-storage` and `payments-mount-tls` to the rows refused as "one the platform uses". In `CPT/ReservedSecretNamesSuite.scala` add `Buckets.secret` and the mount certificate's Secret to the names derived from the operator, so both lists are held to them.
- [X] T036 [US2] Add `class ObjectStorageIsolationFeatures extends ObjectStorageClusterSteps("../features/object-storage/isolation.feature")` in `CPT/ObjectStorageClusterSteps.scala` with its steps: two services with buckets in one project and in two; "reads the bucket of … with its own storage credential" runs the pod's `curl --aws-sigv4` against the other bucket's path and asserts the **store's** 403, not a failed `curl`; "the platform tries to read the storage credential" mints a token for the operator's ServiceAccount, as `OPT/OperatorClusterSuite.scala`'s case 20 does, and asserts a 403 from the API server on a `get` of the Secret; "the storage credential is the one it had before" compares the Secret's `resourceVersion` and `uid`, read by the test's own admin client, before and after the apply.

### Implementation for User Story 2

- [X] T037 [US2] In `API/descriptors.scala` add `"-storage"` to `ServiceSpec.PlatformSecretSuffixes` and `"-storage"` and `"-mount-tls"` to `ProjectSecrets.ReservedSuffixes` per R12. Mirror both in `CON/src/testing/fake-control-plane.ts`'s suffixes. T035 green. Run the console's unit tests.
- [ ] T038 [US2] Run `caffeinate -i sbt 'controlPlane/testOnly *ObjectStorageIsolationFeatures'`. The scenario "the platform cannot read a storage credential back" stays red until the prerequisite change of R19 is on this branch's base: check `git log origin/main -- kustomization/components/operator/operator.yaml` for it, rebase when it is there, and do not mark this task done by weakening the step. The other three scenarios must be green now.
- [ ] T039 [US2] In `OPT/OperatorClusterSuite.scala` add two cases: ten no-op applies of a service with a bucket leave `<service>-storage` at one `resourceVersion` (beside the case that does this for `<service>-db`); and, under the minted token of case 20, `new Fabric8Executor(restricted, Some(store))` performs `EnsureStorageCredential` for a new service and again for an existing one, which shows `create` and the 409 path need no `get` (S5).

**Checkpoint**: User Stories 1 and 2 hold together. One scenario may wait on the prerequisite, and says so.

---

## Phase 5: User Story 3 — deletion keeps the bucket and a supplied store opts out (Priority: P2)

**Goal**: a deleted service's bucket and objects remain and are recovered by the same name; a descriptor that gives `ANKKA_S3_` variables is given nothing and reported `supplied`; one that does both is refused.

**Independent Test**: `caffeinate -i sbt 'controlPlane/testOnly *ObjectStorageKeptFeatures *ObjectStorageOwnStoreFeatures'`.

### Tests for User Story 3 (first, and red)

- [X] T040 [P] [US3] In `APIT/ObjectStorageDescriptorSuite.scala` add a case per row of the outline "a descriptor cannot both ask for a bucket and give a variable of an object store of its own" — `ANKKA_S3_ENDPOINT`, `ANKKA_S3_SECRET_KEY`, `ANKKA_S3_REGION` — each refused with the contract's message naming that variable, whether the variable is a value or a `secretKeyRef`; and that a descriptor giving the variables without the flag has no problems for any hosting.
- [X] T041 [US3] Add `ObjectStorageKeptFeatures` and `ObjectStorageOwnStoreFeatures` in `CPT/ObjectStorageClusterSteps.scala` with their steps. "The object store still holds the bucket … with the object" asks the store with the test's own client, after the service's resource and its pods are gone. "Applies the descriptor … again" then asserts the object is read from inside the new pod and the status says `recovered existing bucket`. For the supplied store: "no bucket exists" and "the platform makes no storage credential" ask the store's admin API for the bucket and for keys of that name, and assert with the admin client that no `<service>-storage` Secret exists; the status says `supplied`.

### Implementation for User Story 3

- [X] T042 [US3] Add `objectStorageProblems` to `ServiceSpec.problems` in `API/descriptors.scala` with the first refusal of `contracts/descriptor-and-status.md`, reading the prefix from `PlatformVariables.objectStorage`. T040 green. Add one case to `CPT/DescriptorFeatures.scala`'s neighbours, or to the fast HTTP suite, that the refusal reaches a member as a 400 naming the variable.
- [X] T043 [US3] Run the two suites of T041 green. `decide`'s `Supplied` and `Ready(recovered = true)` are T025's; if either scenario is red, the fault is there or in T026's observation. Show the recovery scenario failing once by comparing against the wrong timestamp, and restore it.
- [ ] T044 [P] [US3] In `OPT/OperatorClusterSuite.scala` extend the deletion case (delete the `AnkkaService`, apply again, `Recovered`) or add one beside it: the `<service>-storage` Secret survives the deletion with the same `uid`, and the store has exactly one key named for the service afterwards.

**Checkpoint**: User Stories 1 to 3 hold. The bucket outlives its service, and a service with its own store is left alone.

---

## Phase 6: User Story 4 — a browser reaches one object through a URL the service signed (Priority: P2)

**Goal**: `exposeObjectStorage: true` makes one bucket reachable at `storage.<base>` through the Gateway, gives its service the public address, and nothing else becomes reachable.

**Independent Test**: `caffeinate -i sbt 'controlPlane/testOnly *ObjectStorageReachableFeatures'`.

### Tests for User Story 4 (first, and red)

- [X] T045 [P] [US4] In `APIT/ObjectStorageDescriptorSuite.scala` add: `exposeObjectStorage` decodes, defaults to `false` and is omitted at it; a case per row of the outline "only a bucket the platform made can be made reachable from the internet" — without `provisionObjectStorage`, and with `ANKKA_S3_ENDPOINT` given — each refused with the contract's second message; both fields together have no problems for any hosting. In `CPT/ServiceProjectionSuite.scala` the field is projected. In `CPT/ServiceEntitySuite.scala` or the fast HTTP suite, `bucketAddress` is `Buckets.publicAddress` when the descriptor exposes and the installation has a base domain, and absent otherwise.
- [X] T046 [P] [US4] In `OPT/ObjectStorageRenderingSuite.scala` add, per `contracts/operator.md` "The bucket's route": with the flag, a base domain and a plan of `Waiting(None)` or `Ready`, `EnsureReferenceGrant` then `EnsureHttpRoute`, field by field against the contract's two documents (the route owned by the resource, the grant with no owner, the backend's namespace and port from the settings, `timeouts.request` `"0s"`, no `BackendTLSPolicy` for it); `ANKKA_S3_PUBLIC_ENDPOINT` on the developer's container and on no other; `publicAddress` in the status. Without the flag: `RemoveHttpRoute` for `<service>-storage`, no grant, no variable. For a service that asks for nothing: `RemoveHttpRoute` and nothing else. With the flag and no base domain: no route and no variable. With the store unreachable: the route is still rendered.
- [X] T047 [US4] Add `class ObjectStorageReachableFeatures extends ObjectStorageClusterSteps("../features/object-storage/reachable.feature")` with its steps, in `CPT/ObjectStorageClusterSteps.scala`. A signed URL is made in the test with the AWS SDK's presigner from the credential read out of the pod's environment, against the pod's `ANKKA_S3_PUBLIC_ENDPOINT`, path-style. A browser is `curl --cacert --resolve` on the host through the mapped port; a request counts by its status, never by `curl`'s exit code. "Does not reach the object store" asserts the Gateway's 404 **and** that the store's log has no line for the path. "Without a signed URL is refused" asserts the store's 403. For "a browser keeps an object": first set a CORS rule on the bucket with the service's credential (R16), send the preflight `OPTIONS` with an `Origin` and `Access-Control-Request-Method: PUT` and require the allow headers, then send the object. Mark the region that configures the client and signs a URL with `// docs:start object-storage-client` and `// docs:end object-storage-client`.

### Implementation for User Story 4

- [X] T048 [US4] Add `exposeObjectStorage: Boolean = false` to `ServiceSpec` in `API/descriptors.scala` with the second refusal in `objectStorageProblems`; project it in `CP/deploy/ServiceProjection.scala`; fill `bucketAddress` where `withHostname` fills the hostname in `CP/api/ServiceEndpoint.scala`. T045 green.
- [X] T049 [US4] Add `EnsureReferenceGrant(grant: GenericKubernetesResource)` to `OP/Action.scala` and its server-side apply to `OP/Executor.scala`. In `OP/Rendering.scala` render the exposure actions of R15 inside `objectStorageActions`, the route's removal for every service that does not expose, and the sixth variable; set `publicAddress` in `OP/ObjectStorage.scala`'s status. T046 green. Then repin `OPT/RenderingUnchangedSuite.scala`'s fixtures with `-Dankka.rendering.pin=true` and read `git diff operator/src/test/resources/unchanged`: it must be one added `# RemoveHttpRoute` line (with its body, if a removal prints one) per fixture and no change to any object. If anything else differs, a service that does not ask has been changed: fix that, do not accept the pin. Add the feature to the suite's doc comment as the second deliberate repin and say what it added.
- [X] T050 [P] [US4] The CLI and the console: print `bucket address` in `CLI/Output.scala` with its `OutputSuite` case; show the address beneath the bucket's name in `CON/src/routes/service.tsx`, with the fake deriving it and an e2e test for it; set `exposeObjectStorage` in the full `ServiceSpec` fixture sample and write the fixtures again.
- [X] T051 [US4] Run `caffeinate -i sbt 'controlPlane/testOnly *ObjectStorageReachableFeatures'` green. Show three scenarios failing once each and restore: render the route for a service that did not ask (the "not reachable until its descriptor asks" scenario must fail); grant the key `read` and `write` only (the upload's preflight must fail); leave the route in place when the flag is dropped. Delete `CPT/BucketRouteSpike.scala`: the suite holds what the spike found.
- [ ] T052 [US4] In `CPT/RemoteOverlaySuite.scala` confirm the leak case still holds with the component in: nothing of the local overlay's addresses is in the cloud render, and no line contains `BASE_DOMAIN` except the variable's own name. Run `./kustomization/deploy-local.sh` against a kind cluster and walk `quickstart.md` step 7 by hand, including the `403` from the store and the `404` from the Gateway.

**Checkpoint**: all four stories hold. A bucket is private until its descriptor asks, and a signed URL works from a browser.

---

## Phase 7: Polish & cross-cutting

- [X] T053 [P] Write `DOCS/platform/object-storage.md` per R21: what is provisioned; the six variables and which hosting's container has them; path-style addressing and the region; what is never deleted and how a service is given its bucket back; that a credential is made once and not rotated; what the operator can and cannot reach, in R6's words; bringing your own store; making a bucket reachable and setting a CORS rule; that the default store is one replica. Include the client example with `<!-- include: controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/ObjectStorageClusterSteps.scala#object-storage-client -->`. The page stands alone: no feature numbers, no "see above". Add it to `mkdocs.yml`'s "Run the platform" nav after Databases and to `tools/docs/skill/ankka-platform/SKILL.md`'s `pages:`.
- [X] T054 [P] Change the pages R21 lists: `DOCS/reference/service-descriptor.md` (the two fields in the table, a section with the refusals' messages, a `service.json` block with both fields, and "A database" under *Fields you will not find* joined by nothing about storage); `DOCS/reference/limitations.md` (plain HTTP to the store inside the cluster, FR-018; one replica; no cloud provider's buckets; no SDK client; a service on a developer's machine is given no bucket); `DOCS/platform/secrets.md` (the two suffixes); `DOCS/platform/networking.md` (the store's policy and the bucket's route and grant); `DOCS/operate/status-and-history.md` (the phrases); `DOCS/deploy/upgrading.md` (a project secret named `…-storage`); `DOCS/platform/install-cloud.md` (the two Secrets to create before the store and the operator start).
- [ ] T055 Run `just docs-reference`, `just docs-sync` and `just docs`; fix what `docs check` refuses. Run `sbt 'controlPlaneApi/testOnly *DocumentationDescriptorsSuite'`. Confirm the rendered skills under `marketplace/plugins/ankka/skills/` and `ankka.g8/src/main/g8/.claude/skills/` changed and that `TemplateSuite`'s escaping rule holds for the template's copy.
- [X] T056 [P] Settle the six glossary terms marked *Proposed* in `GLOSSARY.md` (object store, bucket, object, storage credential, signed URL, recovered) with the person who owns the glossary: the word, what it does not mean, and an `Avoid:` line where a synonym should be refused. Remove the marks and restore the header's "none is at present". Run `just features`.
- [X] T057 [P] Add to `CLAUDE.md`: under *Architecture*, a short section on object storage (the seam, the credential issued without a read, the name in `crd`, the route per bucket and its grant, the plain-HTTP departure); the component in *Deploying locally*; and under *Traps*, whatever the spikes and the k3s suites cost that a reader would otherwise pay again. Add `sbt` lines for the new suites under *Commands* only if they are run differently from their neighbours.
- [ ] T058 Run the whole build: `sbt -Dankka.cluster.tests=off buildAll`, then `caffeinate -i sbt 'controlPlane/testOnly *ObjectStorage*Features *WebHostingObjectStorageFeatures' 'operator/testOnly *OperatorClusterSuite' 'sidecar/testOnly *SidecarClusterSuite'`, then `just test-console` and `just features`. Read what each run says it ran. `sbt compile` is warning-free.
- [ ] T059 Write the release notes' three operational consequences from `plan.md` into the pull request's description, and say there which scenario, if any, still waits on the prerequisite.

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1** has no dependency. T002 and T003 share a file and a container; T004 is independent of both.
- **Phase 2** depends on Phase 1's findings (T016 on T002; T012 on T002 and T003). It blocks every story.
- **US1 (Phase 3)** depends on Phase 2. It is the MVP.
- **US2 (Phase 4)** depends on US1's steps class (T023) and rendering (T027). T037 does not, and can be done any time after Phase 2.
- **US3 (Phase 5)** depends on US1. T042 does not, and can be done any time after T024.
- **US4 (Phase 6)** depends on US1 and on T004's finding.
- **Phase 7** depends on the stories it documents; T053's include depends on T047.

### Within a phase

Tests before the code they hold; a spike before the design it could overturn (T002 before T016, T003 before T012, T004 before T049).

- T005 → T006. T008 → T009. T012 → T013. T014 → T015. T016 → T017.
- T019–T022 before T024–T027. T024 → T025 → T026 → T027 → T028. T023 → T031.
- T029 depends on T025 (the block the operator reports) and blocks T030 and T034.
- T035 → T037. T036 → T038. T040 → T042. T041 → T043.
- T045 → T048. T046 → T049. T047 → T051.

### Parallel opportunities

- T002–T003 beside T004.
- T005, T007, T010 and T011 together; T018 beside T016–T017.
- T019, T020, T021 and T022 together.
- T029, T030 and T034 as one line beside T031–T033.
- US2's descriptor line (T035, T037) and US3's (T040, T042) beside anything on k3s.
- T045 and T046 together; T050 beside T051.
- T053, T054, T056 and T057 together.

### Parallel example: User Story 1 after the rendering is in

```text
Line A: T031 → T032 → T033            (k3s: the features, the process and the module, the web-hosted service)
Line B: T029 → T030                   (the status a member reads, the CLI)
Line C: T029 → T034                   (the console)
```

---

## Implementation Strategy

**MVP**: Phases 1, 2 and 3 — User Story 1. A service that asks is given a bucket it can use from inside its pod, and a member can see which. Nothing is reachable from outside the cluster.

**Then, each a mergeable step**:

1. US2 (Phase 4) — the reserved names and the isolation proofs. Merge it with US1 if at all possible: without it a descriptor can name a sibling's credential.
2. US3 (Phase 5) — recovery and the supplied store.
3. US4 (Phase 6) — a reachable bucket. This is the step that touches the Gateway and repins the unchanged fixtures.
4. Documentation and the whole build (Phase 7).

The prerequisite of R19 is its own branch and pull request, made from `main`. Nothing here waits on it except one scenario of US2 (T038).

## Notes

- A task that says "show it failing" is not done until it has been seen red and the break is confirmed gone (`git diff`, or a grep for what was changed).
- Read what a run says it ran. A munit filter needs its leading `*`; `RemoteOverlaySuite` skips without `kubectl`; a k3s suite that takes hours has been run on a sleeping laptop; sbt holds a suite's report until the suite ends.
- Never log, print, describe or journal a secret key. The suites assert it for an action's description, the golden file and `IssuedKey`'s `toString`; nothing asserts it for a log line, so it is the reviewer's to read for.
- A k3s node running several sample JVMs answers in seconds. Deploy the real image only for a service a scenario needs `Ready`; the store itself is one small container.
- A status assertion waits on the value it asserts, not on a row existing.
