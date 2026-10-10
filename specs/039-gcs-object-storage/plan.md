# Implementation Plan: Object Storage on Google Cloud Storage

**Branch**: `039-gcs-object-storage-impl` (the spec was authored on `039-gcs-object-storage`) | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/039-gcs-object-storage/spec.md`

## Summary

An installation may keep its buckets in Google Cloud Storage. A service's descriptor and code do
not change: it asks for a bucket as it does on Garage and is given the same five variables. What
touches Google is the cloud provider of feature 044, outside the operator; the operator renders
three requests per service (an identity, a bucket, a credential), copies what the fulfilments
report onto the service's status and into its variables, and holds no Google client, credential
or permission. The first user keeps identity documents under regulation, so every bucket is
versioned with soft delete and no retention policy, is made in its project's or the
installation's location, and is encrypted with the installation's wrapping key when it names
one. A credential can be issued again, on both stores. A service's objects can be moved from
Garage to Google Cloud Storage by a mover the operator runs as a Job, with a bounded write pause
enforced by a read-only credential. Local development and every suite that runs without Google
are unchanged.

Technically (research R1 to R15): **044 lands first** and this feature consumes its contract,
asking five amendments of its spec (R1; 044 merged without them, and R1a says what 039 does
instead). The backend is one installation setting beside the
Garage settings, so both stores can be configured at once (R2). On GCS `ObjectStorage.decide` and
`Rendering` emit `CloudResource`s instead of calling `ObjectStore`; `GarageStore` is untouched
except for two operations the admin API already offers, a read-only grant and CORS rules (R3,
R6). Re-issue is a counter on the resource; Garage keys carry their generation in their name and
expire by the store's own clock (R5). The mover is a module and image of its own with the AWS SDK,
run as a Job the operator renders per phase, and the move is a state machine whose state the
operator keeps in the status (R7, R8). The k3s suite runs the fake provider with Garage standing
in for Google, and a nightly workflow proves the S3-client assumptions against a real bucket
(R12).

Planning found four things the spec left to it, each decided in research and none changing the
spec: who ends the old Garage key (Garage, by expiry — R5); how CORS is set on Garage (the admin
API — R6); where the move's state lives (the status — R8); and that the Google-only scenarios
are proved by ankka-gcp's nightly suite and named as such in this repository's (R12).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`core`, `crd`, `controlplane-api`, `controlplane`,
`operator`, `cli`, the new `storage-mover`); TypeScript on Node ≥ 22 (`console/package`); YAML
(kustomize, CRD schema, GitHub workflows); Gherkin

**Primary Dependencies**: none added to the operator, `crd`, `core`, `controlplane-api` or the
control plane. The new `storage-mover` module depends on the AWS SDK for Java's sync `s3` client
(already a test dependency of `operator` and `controlplane`) and logback. Test only: the AWS SDK
in `controlplane` for `GcsCompatibilitySuite`. No Google library anywhere (044 FR-014).

**Storage**: `CloudResource`s (044's) per service on GCS; Kubernetes Secrets `<service>-storage`
(Garage) and `<service>-cloud-storage` (GCS); `batch/v1` Jobs for a move. `AnkkaServiceSpec` gains
six fields, `AnkkaProjectSpec` one, `ObjectStorageStatus` five, with the schema. The control
plane's journal gains defaulted fields on `ServiceObserved`, three new events on `Service` and one
on `Project`. No DDL.

**Testing**: munit in `crd`, `core`, `controlplane-api`, `operator`, `controlplane`, `cli`,
`storage-mover`; `GarageStoreSuite` extended against the real image; the mover against two Garage
containers; one new `GherkinSuite` on k3s over `features/object-storage-gcs/` with the fake
provider; `ObjectStorageClusterFeatures` extended with the steps for 034's changed scenarios;
`GcsCompatibilitySuite` against a real bucket, nightly; the console's unit and Playwright tests;
the docs build; the features check.

**Target Platform**: an installation's cluster: kind locally with the fake provider for tests,
GKE with Workload Identity Federation for GKE and `ankka-gcp` for Google. A service on a
developer's machine is given no bucket, as before.

**Project Type**: a platform's operator, control plane, CLI and console; a new platform image; a
contract consumed from another feature; documentation

**Performance Goals**: a reconcile of a service on Garage makes the calls it made before and no
request; one on GCS makes no call to any store and reads three `CloudResource`s it is informed
of. A fulfilment reaches a reconcile within seconds through 044's informer. A move of 10,000
objects completes with every hash verified (SC-006), its write pause bounded by what the member
asked, 10 minutes as shipped.

**Constraints**: no secret in an action, a log line, a status, the journal, a Job's spec or a wire
type (a Job names Secrets; it carries no value); the operator's dependencies unchanged and its
ClusterRole gaining only `batch/jobs` here (044 adds `cloudresources`); nothing rendered for a
service on Garage differs from before, so upgrading rolls no pod (`RenderingUnchangedSuite`); the
platform deletes no bucket, no object and no Secret; a stored journal decodes unchanged;
warning-free; `Test / parallelExecution := false` stays; no suite binds a fixed port or names an
image by a literal tag; no suite of ankka reaches Google except `GcsCompatibilitySuite` under its
own workflow.

**Scale/Scope**: about 14 new Scala source files and 26 changed across seven modules; one new sbt
module and image; about 11 new suites and 19 changed; 1 new workflow; 4 kustomize files changed;
3 console files; 2 new docs pages and about 7 changed; 7 feature files already written (41
scenarios) and 2 changed in `features/object-storage/`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` and the rule files state, as 034's plan read them:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; one thing performs them | pass | `EnsureCloudResource`, `EnsureMoveJob`, `EnsureReadOnlyCredential`, `ReissueStorageCredential` are descriptions with no secret; `Fabric8Executor` performs them (R3, R5, R7) |
| Where two parties must agree on a derivation, there is one | pass | the GCS name is derived nowhere in ankka's main code: the provider reports it, the status carries it, the control plane shows it (R1, R11); the fake's `BucketNames` is test code holding the contract's rule |
| Module dependency direction | pass | `crd` depends on nothing; the operator on `crd`; `storage-mover` on nothing of ankka; `controlplane-api` on `core` |
| The operator cannot reach into a cloud, and depends on as little as possible | pass | no library added; Google is reached by the provider alone (R3); the mover's S3 client is in its own image (R7) |
| A field on the resource needs the schema | pass | twelve new fields across the two resources' spec and status, `CrdSchemaSuite` in both directions |
| Never render what must not roll | pass | a service on Garage renders what it rendered; the credential annotation changes only when a new key is in the Secret (R5); the move's rollouts are the two the spec names |
| No secret value in the control plane's journal | pass | the control plane journals phases, states, a name and an address; never a key |
| A credential is written where the pod reads it and never read back | pass | the provider writes `<service>-cloud-storage` by `create`; the operator patches `<service>-storage` with a key it just issued and reads neither; the mover reads both as the pod does, by `secretKeyRef` (R3, R5, R7) |
| Nothing the platform does may destroy data | pass | no bucket, object or Secret is deleted; a Garage key is deleted only when expired or never written to a Secret; the Garage bucket outlives the move (R5, R8) |
| Stored forms stay readable both ways | pass | every new journaled field defaults; `EventCompatibilitySuite` pins each new event and field |
| Which variables are the platform's is said once | pass | three new names in `PlatformVariables`, compiled into the operator as today (R2) |
| Could this check pass while the thing it checks is false? | pass | R12 names the Google-only scenarios and skips them *by name with a reason*, never silently; `ranOutside` fails on a scenario that does not exist; the write pause scenario asserts a refused write, not a status; the mover's verify re-reads the target |
| Each acceptance scenario ends as a test that fails without the feature | pass | 41 scenarios: 31 by the k3s suite, 6 by `GcsCompatibilitySuite` or ankka-gcp (named), 4 by tests named for them (quickstart). FR-006, FR-009, the retention-policy report of FR-011, FR-019's binding, SC-002, the provider half of SC-003 and the Google half of SC-009 are proved only by ankka-gcp's nightly suite; this repository names each in `ranOutside` |
| Docs: pages stand alone, samples from tested code, new pages in nav and a skill | pass | R14; the S3 snippets come from `GcsCompatibilitySuite` |
| Every tracked file claimed by a CI path filter | pass | `storage-mover/**` added to `scala-build` and `scala`; `gcs.yml` under `unchecked` (R13) |
| Every port a workload has is mutual TLS | pass | `storage.googleapis.com` is HTTPS; Garage's in-cluster HTTP is 034's recorded limitation, unchanged |
| A feature consumes another's contract, never a copy of it | pass | nothing of 044 is reimplemented; five amendments are asked of its spec (R1) |

**Violations to justify**: none. Two departures from the spec's letter are in *Complexity
Tracking*.

**Post-design re-check**: unchanged. The contracts add one module with one third-party dependency
in its own image, one RBAC rule, and fields the schema declares.

## Project Structure

### Documentation (this feature)

```text
specs/039-gcs-object-storage/
├── plan.md              # this file
├── research.md          # R1–R15: decisions with evidence; five things to verify first
├── data-model.md        # the resources' fields, the Secrets, the Jobs, the requests, what is journaled
├── quickstart.md        # the validation runs: offline → Garage → k3s with the fake → Google → docs → by hand
├── contracts/
│   ├── cloud-requests.md          # the three requests as 039 renders them; the fake; the amendments asked of 044
│   ├── descriptor-and-status.md   # the three fields, the refusals, the routes, what a member reads
│   ├── operator.md                # settings, the plan per store, actions, re-issue, the move's states, the Job
│   ├── mover.md                   # the image: environment, algorithm, result, exit codes
│   └── installation.md            # the ConfigMap settings, GKE, the workflow, CI
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/object-storage-gcs/` (seven files) and two changed
scenarios in `features/object-storage/`, in the words of `GLOSSARY.md`.

### Source Code (repository root)

```text
modules/core/…/core/PlatformVariables.scala                    # ObjectStoreBackend, ObjectStorePrefix, ObjectStoreSoftDeleteDays

crd/…/crd/AnkkaService.scala                                   # six spec fields; ObjectStorageStatus + MoveStatus
crd/…/crd/AnkkaProject.scala                                   # bucketLocation
crd/…/crd/Buckets.scala                                        # cloudSecret, gcsPublicAddress; name rule stays Garage's
kustomization/components/crd/ankkaservice.yaml, ankkaproject.yaml   # the same, declared

controlplane-api/…/api/descriptors.scala                       # ServiceSpec's three fields and rules; ServiceStatus's four fields;
                                                               # StorageMoveRequest, SetProjectLocation
controlplane/…/controlplane/api/ServiceEndpoint.scala          # storage-credential, storage/move, storage/settings; the refusals per backend
controlplane/…/controlplane/api/ProjectEndpoint.scala          # PUT /{id}/location
controlplane/…/controlplane/application/ServiceEntity.scala, ProjectEntity.scala, ServiceRows.scala
controlplane/…/controlplane/domain/events.scala, model.scala   # three service events, one project event; phrases; bucketOf from status
controlplane/…/controlplane/deploy/ServiceProjection.scala     # the six fields; name-length rule only on garage
controlplane/…/controlplane/deploy/ProjectTopicsTrigger.scala  # bucketLocation on AnkkaProject
controlplane/…/controlplane/deploy/StatusIngest.scala          # store, bucket, address, move
controlplane/…/controlplane/deploy/DeployConfig.scala          # the backend, prefix, soft-delete days, cloud settings

operator/…/operator/Settings.scala, ObjectStoreSettings.scala  # backend, prefix, softDeleteDays, gcsEndpoint, moverImage
operator/…/operator/ObjectStore.scala, GarageStore.scala       # allow(write), setCors
operator/…/operator/StorageCredential.scala                    # reissue, readOnly, expireBelow, deleteExpired
operator/…/operator/ObjectStorage.scala                        # decide per store; the move's next state; status
operator/…/operator/StorageMove.scala                          # new: the state machine, pure
operator/…/operator/CloudRequests.scala                        # new: the three requests rendered as CloudResource (044's types)
operator/…/operator/Action.scala, Executor.scala               # EnsureCloudResource (044's), EnsureMoveJob, EnsureReadOnlyCredential,
                                                               # ReissueStorageCredential, SetBucketCors; observeMoveJob, observeCloudRequests
operator/…/operator/Rendering.scala                            # objectStorageActions per store; variables per store; the credential annotation;
                                                               # ServiceAccount annotations; the Job
operator/…/operator/ServiceReconciler.scala                    # the move's transition per pass; project location
operator/src/test/…/cloud/ScriptedFulfilment.scala             # 044's, given a Garage-backed mode: buckets and keys in Garage under BucketNames
operator/src/test/…/BucketNames.scala                          # the contract's name rule, test code
operator/src/test/resources/golden/object-storage-gcs.txt      # new

storage-mover/src/main/scala/…/mover/Mover.scala, Main.scala   # new module and image
storage-mover/src/test/…/MoverSuite.scala                      # two Garage containers

kustomization/components/operator/operator.yaml                # batch/jobs; ANKKA_STORAGE_MOVER_IMAGE
kustomization/overlays/cloud/platform-configmap.yaml           # the three settings as SET placeholders
kustomization/overlays/local/platform-configmap.yaml           # backend garage

cli/…/cli/Main.scala, ControlPlaneClient.scala, Output.scala   # services storage reissue|move|reapply-settings; projects location set; the lines
cli/…/cli/mcp/AnkkaTools.scala                                 # the tools' descriptions

console/package/src/client/schemas.ts, routes/service.tsx, testing/fake-control-plane.ts
console/package/fixtures/control-plane/                        # written again

controlplane/src/test/…/ObjectStorageGcsClusterFeatures.scala  # new: the steps, the fake, Garage as Google
controlplane/src/test/…/ObjectStorageClusterFeatures.scala     # steps for the two changed 034 scenarios
controlplane/src/test/…/GcsCompatibilitySuite.scala            # new: against a real bucket, gated
modules/testkit/…/testkit/GherkinSuite.scala                   # ranOutside

.github/workflows/gcs.yml                                      # new: nightly and on demand
.github/workflows/ci.yml                                       # storage-mover/** in the filters; gcs.yml unchecked
build.sbt, project/Dependencies.scala                          # storageMover; ankka.gcs.tests forwarded; the image in the cluster tests' task

docs/platform/object-storage.md                                # "On Google Cloud Storage"
docs/platform/install-gke.md                                   # new
docs/reference/service-descriptor.md, limitations.md; docs/operate/status-and-history.md;
docs/platform/install-cloud.md; mkdocs.yml; the rendered skill
```

**Structure Decision**: one module is added, `storage-mover`, because the mover needs an S3
client and the operator may not carry one; it depends on nothing of ankka and is named by the
operator through a setting, as the sidecar image is. Everything else goes where its kind lives:
the state machine beside `ObjectStorage` in the operator, pure; the requests' rendering beside
`CnpgRendering`; the routes beside `restart` and `rollback`; the fields beside 034's. The Google
suite lives in `controlplane`'s tests because the AWS SDK is already there and the docs' snippets
are taken from it.

## Order of work

Cut by user story, tests before the code they hold. Slices 1 to 4 need no cluster and no 044.

0. **044, and the spikes** — 044 is planned and built on its own branch with the four amendments
   (R1, five with the identity request); this feature's slices 5 onward wait for it. S1 (Garage's CORS, read-only grant and expiry
   against the pinned image) is three tests in `GarageStoreSuite` and runs now; S2 (the XML API by
   hand with an HMAC key) runs now with a bucket prepared for it; S3 and S4 are proved by the
   suites that need them.
1. **Settings, names and the descriptor** (FR-001, FR-005's control-plane half, FR-015's field,
   FR-017's field, FR-020's field). `PlatformVariables`, `Settings` with the backend, the
   descriptor's three fields and refusals, the projection, the schema. Offline.
2. **Garage gains what the spec asks of both stores** (FR-010 on Garage, FR-015 on Garage,
   FR-023's credential). `ObjectStore.allow(write)`, `setCors`, `StorageCredential.reissue` with
   generation-named keys and expiry, the read-only key, the credential annotation, the steps for
   the two changed 034 scenarios. Offline plus `GarageStoreSuite`.
3. **The mover** (FR-022's copy and verify). The module, its suite against two Garage containers,
   the image, the build. Offline.
4. **The control plane's actions and status** (FR-010's audit, FR-012's location, FR-014,
   FR-021's status). The three service routes, the project location route, the events, the
   history kinds, the wire fields, the CLI, the console. Offline.
5. **The GCS path** (FR-002, FR-003, FR-004, FR-005, FR-006, FR-009, FR-011, FR-012, FR-019,
   FR-020). `CloudRequests`, `ObjectStorage.decide` per store, the variables, the ServiceAccount
   annotations, `StatusIngest`, the fake extended with Garage buckets, the k3s suite for
   provisioning, names, isolation (its fake half), keyless and retention's location scenarios.
   Needs 044.
6. **Re-issue end to end and exposure** (FR-010, FR-015, FR-018). The credential generation on
   both stores on k3s; origins and the public endpoint on GCS; `GcsCompatibilitySuite` and
   `gcs.yml`.
7. **The move** (FR-021, FR-022, FR-023). `StorageMove`, the Job actions, the pause, the bound,
   the switch, the failure path; the k3s move scenarios.
8. **Documentation** (FR-025), then the whole build.

Slices 1 to 4 are independent of each other except that 4 needs 1's fields. Slice 7 needs 2, 3
and 5. The Google-only scenarios are green when ankka-gcp's nightly is (044 SC-007).

## Complexity Tracking

No constitution principle is departed from. Two places depart from the spec's letter, and one
widens the platform's surface, each with the simpler thing rejected.

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| The GCS credential Secret is `<service>-cloud-storage`, where 034 named `<service>-storage` (R3) | a moving service holds its Garage key in `<service>-storage` while the provider writes the new bucket's; the provider's 409 rule would otherwise end the key it just made | naming per situation ("`-storage` unless moving") is two rules where one will do; the suffix keeps the Secret reserved |
| The operator reads its own status as memory for a move's state (R8) | the facts it would derive — which key a Secret holds, whether a Job that TTL removed succeeded — it may not read or cannot | keeping the state in the control plane makes every transition a round trip through a status it is reading from; a long Job doing the whole move needs the operator's powers |
| A new module and image, `storage-mover` (R7) | the operator may carry no S3 client, and a second main in its image puts one on its classpath | a SigV4 client over the JDK's HTTP in the operator is a second S3 client in this repository, for one job |
| `GherkinSuite.ranOutside` (R12) | six scenarios in this repository's features are provable only against Google, by ankka-gcp | `ranElsewhere` fails on a suite it cannot find; `@ignore` hides the scenario's name from the report, which is how a skipped check becomes a forgotten one |
| An `ANKKA_OBJECT_STORE_GCS_ENDPOINT` setting with Google's address as its default (R12) | the k3s suite points the GCS path at Garage so the move and the keyless rendering run with no Google | a test-only hook under a system property is a setting nobody documents; a setting with the right default is honest and lets a future provider's endpoint be named |
| Objects over 5 GiB fail a move (R7) | one `PutObject` is what both stores accept at that size | a multipart path for an object the first user will never have is code with no test that could fail |

**Operational consequences** to announce in the release:

- A GCS installation needs `ankka-gcp` deployed and the three `ANKKA_OBJECT_STORE_*` settings on
  the `ankka-platform` ConfigMap beside 044's; the install page for GKE says so.
- The operator's ClusterRole gains `batch/jobs`; an installation that pins RBAC must take it.
- A Garage key issued before this release is read as generation 0 and is replaced on the first
  re-issue, never before.
- `features/object-storage/durability.feature` (041's) has no steps and fails
  `ObjectStorageClusterFeatures` on the nightly matrix until 041's plan gives it some; it is not
  this feature's, and is noted so nobody reads the red as 039's.
