# Research: Object Storage on Google Cloud Storage

Decisions, each with what it rests on. File references are to the worktree at the plan's date;
`OP` is `operator/src/main/scala/com/thinkmorestupidless/ankka/operator/`, `CP` is
`controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/`.

## R1. Feature 044 is the contract, and it is not built yet

**Decision**: 039 is implemented *on top of* 044, which lands first on its own branch. This plan
names exactly what 039 consumes of 044 and the four amendments it asks of 044's spec, so 044's plan
can carry them. 039 adds no cloud code and no second contract.

**Evidence**: nothing of 044 exists in the tree — no `CloudResource` class, YAML, RBAC rule,
informer, fake or setting (`grep` over Scala, YAML and sbt for `CloudResource`, `cloudresource`,
`ANKKA_CLOUD_` finds only `specs/` and `features/cloud-provider/`). 038 is likewise unbuilt. 044's
own spec says (Dependencies) that 039's FR-001 and FR-002 are superseded by its FR-011 and FR-015.

**What 039 needs from 044**, as a checklist for 044's plan:

| 044 item | 039 uses it for |
|---|---|
| `CloudResource` CRD, its schema, `CrdSchemaSuite` coverage, operator RBAC (FR-001, FR-002) | every request below |
| the `identity`, `bucket` and `bucket-credential` kinds (FR-005) | one per service with a bucket on GCS |
| `observedGeneration` gating and the acknowledgement bound (FR-004) | `Waiting` naming the provider |
| the credential `create`/409 rule and `credentialGeneration` with the rotation grace (FR-008, FR-009) | the storage credential, re-issue |
| `ANKKA_CLOUD_PROVIDER`, `ANKKA_CLOUD_ACCOUNT`, `ANKKA_CLOUD_LOCATION`, `ANKKA_CLOUD_KMS_KEY` on the `ankka-platform` ConfigMap, read by the operator and the control plane (FR-011) | the account, the default location, the wrapping key |
| the operator folds a request's phase into the service's status (FR-018) | `status.objectStorage` on GCS |
| the fake provider in `operator`'s test sources, installed by k3s suites (FR-020) | every k3s scenario of 039 |
| the operator's `CloudResource` informer requeues the owning service | a fulfilment reaches a reconcile within seconds, not a resync |

**Amendments 039 asks of 044's spec** (its spec is `Draft`; these go into its clarification
session before its plan):

1. The `bucket` kind gains `namePrefix` (the installation's bucket name prefix, so a provider is
   stateless) and `noncurrentVersionDays` (FR-017 here: an age after which noncurrent versions are
   deleted; absent means never).
2. The `identity` kind's fulfilment gains `serviceAccountAnnotations: map`, which the operator
   copies onto the service's Kubernetes ServiceAccount verbatim. On GKE that is
   `iam.gke.io/gcp-service-account: <email>`; the operator never learns the key's meaning, so
   044's "no cloud-specific code" holds and 039's FR-019 (keyless access) is rendered by the
   operator without naming Google.
3. The `bucket-credential` fulfilment gains `credentialId` (the access key id of the generation
   in place; never the secret). With it a provider can tell which of an identity's keys no Secret
   holds and prune the rest, which is 039's "HMAC key limit" edge case (Google allows ten per
   service account).
4. The bucket name rule is the contract's, not a provider's: `<prefix>-<project>-<service>-<d>`
   with `d` the first eight hex characters of SHA-256 over `<project>.<service>`, the readable
   part shortened from the right of the service, then the project, to fit 63 characters, and the
   digest never shortened. Every provider — the fake included — names a bucket this way.
5. A service with a bucket renders an `identity` request beside the bucket and credential
   requests (044's story says two requests, with the identity made inside the credential); a
   keyless service needs the identity and no credential, and the credential request then names
   the identity's `principal`. 044's US1 scenario and `bucket.feature` change to say so.

## R2. The backend is an installation setting, and both stores may be configured at once

**Decision**: three new platform variables, declared in `core`'s `PlatformVariables` and set on the
`ankka-platform` ConfigMap as 044's are, given to the operator and the control plane:

| Variable | Values | Meaning |
|---|---|---|
| `ANKKA_OBJECT_STORE_BACKEND` | `garage` (default when `ANKKA_OBJECT_STORE_ADMIN_URL` is set), `gcs` | the store new buckets are made in |
| `ANKKA_OBJECT_STORE_PREFIX` | a DNS label, required with `gcs` | the GCS bucket name prefix |
| `ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS` | `7` as shipped; 7 to 90 | the soft-delete window of a new bucket |

`gcs` with `ANKKA_CLOUD_PROVIDER=none` is a startup failure in the operator (`Settings`, all-or-none
as `objectStore()` is today) and an apply-time refusal in the control plane naming the provider
needed (044 FR-012). The Garage settings (`ANKKA_OBJECT_STORE_{ADMIN_URL,…}`) are unchanged and may
be set beside `gcs`: that is FR-021's "both stores", and what a move needs.

**Rationale**: 044 says features define no cloud account, location or key setting of their own,
and 039 does not; but which store a bucket goes in, the name prefix and the soft-delete window are
the object store's, not the cloud's. The backend is a word, not a boolean, so a third store is a
value, not a field.

**Rejected**: inferring `gcs` from "a cloud provider is named" — an installation with a provider
for secrets (038) and Garage for objects is a real shape.

## R3. On GCS the operator renders three requests per service, and `GarageStore` stays Garage's

**Decision**: for a service that asks for a bucket on a `gcs` installation, `Rendering` emits, in
this order and each a server-side apply of a `CloudResource` owned by the `AnkkaService`:

| Request | Name | Parameters | Needs |
|---|---|---|---|
| `identity` | `<service>-identity` | `serviceAccount: <service>` | — |
| `bucket` | `<service>-bucket` | `purpose: service`, `location`, `namePrefix`, `versioning: true`, `softDeleteDays`, `corsOrigins`, `noncurrentVersionDays`, `kmsKey` | — |
| `bucket-credential` | `<service>-storage-credential` | `bucket` (from the bucket's fulfilment), `principal` (from the identity's), `secretName: <service>-gcs-storage`, `credentialGeneration` | both `Ready`; not rendered when the descriptor declines a credential |

The developer's container gets `envFrom: <service>-gcs-storage` and the literals `ANKKA_S3_ENDPOINT=
https://storage.googleapis.com` (an operator setting with that default, see R12),
`ANKKA_S3_REGION=auto`, `ANKKA_S3_BUCKET=<name from the bucket's fulfilment>`, and
`ANKKA_S3_PUBLIC_ENDPOINT=https://storage.googleapis.com` when exposed. No `HTTPRoute` and no
`ReferenceGrant`: the exposure actions on `gcs` are the same `RemoveHttpRoute` a service that does
not ask gets. The `ObjectStore` trait and `GarageStore` are untouched by the GCS path; the
backend choice is one `match` in `ObjectStorage.decide` and `Rendering.objectStorageActions`.

**The credential Secret is `<service>-gcs-storage`, always, on GCS.** One rule, no special case
for a moved service: during a move the service keeps `<service>-storage` (Garage) while the
provider writes the new bucket's key into `<service>-gcs-storage`, and the switch is an `envFrom`
change. The suffix `-storage` keeps the Secret under 034's reserved-name rule
(`PlatformSecretSuffixes`, `Buckets.SecretSuffix`), so no descriptor can read it.

**Rationale**: 044 FR-005's kinds carry everything the spec asks of a bucket (FR-009, FR-011,
FR-012, FR-015, FR-017) once amended per R1. A key needs a Google service account (HMAC keys exist
only for service accounts), so the identity request is not optional for a service that takes a
key; for a keyless service it is the whole grant.

**Rejected**: a `GcsStore implements ObjectStore` in the operator (the operator would hold IAM
power — the spec's review session settled this); the provider writing into `<service>-storage`
during a move (the 409 rule would end the key it just made and report the Garage Secret as the
credential).

## R4. Keyless access is the identity request plus annotations the operator copies

**Decision**: `objectStorageCredential: false` on the descriptor means no `bucket-credential`
request, no `envFrom`, no `ANKKA_S3_ACCESS_KEY`/`SECRET_KEY`; the service's ServiceAccount carries
the annotations the identity fulfilment returned (R1 amendment 2), and the bucket's grant to the
identity is the provider's. On a `garage` installation the control plane refuses the descriptor at
apply ("a bucket in Garage is reached only with a storage credential"), and `ObjectStorage.decide`
answers `Failed` with the same words if one reaches it anyway.

**Rationale**: FR-019, FR-020. The binding of a Kubernetes ServiceAccount to a cloud identity is
Google's vocabulary (an annotation key) and belongs in the fulfilment, not the operator.

## R5. Re-issue is one counter, and each store ends the old key its own way

**Decision**: `POST /services/{project}/{name}/storage-credential` (CLI `ankka services
storage reissue`) persists `StorageCredentialReissued(generation, actor, at)` on the `Service`
entity, remembered in history as `storage-credential-reissued`, and projects
`spec.storageCredentialGeneration` (an `Int`, like `restarts`) onto the resource. The operator:

- on `gcs`: copies it into the `bucket-credential` request's `credentialGeneration`; the provider
  issues, patches the Secret, reports the generation in place, and ends the old key after the
  rotation grace (044 FR-009).
- on `garage`: `StorageCredential.reissue` — keys are named `<bucket>#<generation>`
  (`<bucket>` alone is generation 0, which keeps every existing installation's key recognisable);
  it issues `<bucket>#<n>`, allows it read/write/owner, patches `<service>-storage`, sets
  `expiration = now + grace` on every key of a lower generation with `UpdateKey`, and on later
  passes deletes keys Garage reports `expired`. Nothing is read from the Secret: the generation in
  the key's name is what tells old from new.

Either way the operator writes the generation in place into `status.objectStorage.
credentialGeneration` and puts that value on the pod template as the annotation
`ankka.thinkmorestupidless.com/storage-credential-generation`, which rolls the service once the new
key is in the Secret and never before. The grace is 044's rotation grace (one hour) on both stores.
Re-issue is refused by the control plane (409, "storage is moving") while the service's move is in
any state before `Switched` or `Failed`, and ignored by the operator for a service in a pause.

**Evidence**: `Rendering.scala:1114-1127` puts `restarts` on the template; a changed template rolls
under `RollingUpdate`. Garage admin API v2 `UpdateKey` carries `expiration` and `GetKeyInfo`
reports `expired` (`garage-admin-v2.json`, schemas `UpdateKeyRequestBody`, `GetKeyInfoResponse`).
`StorageCredential.ensure` short-circuits on its per-process `ensured` set, which `reissue`
bypasses by generation.

**Rejected**: the operator timing the old key's deletion itself (a timer that survives a restart
needs state the operator does not keep; Garage's expiry is the store's clock, as the provider's
grace is the provider's).

## R6. The write pause and CORS use Garage operations the admin API already has

**Decision**: `ObjectStore` gains two operations, both implemented by `GarageStore` over the
existing admin client:

```scala
def allow(bucketId: String, accessKeyId: String, write: Boolean): Unit   // read always; owner = write
def setCors(bucketId: String, origins: Seq[String]): Unit               // UpdateBucket.corsRules; empty = none
```

A read-only credential is a key allowed with `read: true, write: false, owner: false`
(`AllowBucketKey`, `ApiBucketKeyPerm`). CORS on Garage is `POST /v2/UpdateBucket` with
`corsRules`: one rule per origin set, `GET`, `PUT`, `HEAD`, every header, `ETag` exposed. On GCS
CORS is the bucket request's `corsOrigins`. The service's key keeps `owner`, so a lifecycle rule a
service sets through S3 stays its own and non-portable (FR-016 narrows 034's FR-017; it does not
remove it).

**Evidence**: `garage-admin-v2.json` lists `corsRules` and `lifecycleRules` on
`UpdateBucketRequestBody`. **Verify first (S1)**: that the `v2.3.0` image the component pins
honours `corsRules` — the published spec is the current release's. `GarageStoreSuite` runs that
image, so the spike is one test.

**Rejected**: the operator signing an S3 `PutBucketCors` with a transient owner key (SigV4 in the
operator, for nothing the admin API lacks).

## R7. The mover is a module and an image of its own, run as a Job the operator renders

**Decision**: a new sbt project `storageMover` (`storage-mover/`), image `ankka-storage-mover`,
depending on the AWS SDK's sync `s3` client and nothing of ankka. Its program reads its two
credentials and both endpoints from its environment, lists the source bucket, copies every object
whose target is absent or whose size or SHA-256 differs (streaming the body through the hash on
the way up, then reading the target back and hashing it), carries `Content-Type` and user
metadata, and in a second mode verifies: lists both sides, hashes every object on both, and fails
naming the first object that differs or is missing. It writes one JSON line to
`/dev/termination-log` (`{"counted":…,"copied":…,"verified":…,"failed":"<key>","reason":…}`) and
exits 0, 1 (verification failed) or 2 (could not run). Every run is idempotent, so "resume" is
"run it again".

The operator renders a `batch/v1 Job` per phase — `<service>-move-<n>-copy`, `<service>-move-<n>-
verify`, `n` the move's generation — in the project's namespace, owned by the `AnkkaService`,
`backoffLimit: 0`, `ttlSecondsAfterFinished: 86400`, `activeDeadlineSeconds` = the write pause
bound on the verify Job, the service's identity labels, the platform's zero-trust labels, the
image from `ANKKA_STORAGE_MOVER_IMAGE` (as `ANKKA_SIDECAR_IMAGE`), and the credentials as
`env.valueFrom.secretKeyRef` from `<service>-storage` (source) and `<service>-gcs-storage`
(target) under distinct names. It observes the Job's `succeeded`/`failed` and the pod's
termination message, as `StartRefusal` reads a container's. New RBAC: `batch` `jobs`
`get, list, watch, create, patch`. An object is copied with one `PutObject`, which both stores
accept up to 5 GiB; the first user's objects are documents, so an object over that fails the move
naming the object, and the limitation is written down rather than a multipart path built for
nobody.

**Rationale**: the spec's clarify session settled that a job the operator renders does the move,
holding exactly the two credentials. The operator depends on `crd` alone and cannot carry an S3
client; a second main in the operator's image would put one on its classpath. The sidecar is the
precedent for a platform image the operator names by a setting.

**Verify first (S3)**: a pod in a project's namespace can reach `storage.googleapis.com` under the
zero-trust network policies — a GCS-backed service needs that too, so the suite that proves it
proves both.

## R8. The move is a state machine the operator drives, with its state in the status

**Decision**: the member's request is `spec.objectStorageMove: { generation: Int, writePauseBound:
String }` (projected from `StorageMoveRequested(generation, bound, actor, at)`, history
`storage-moved`); the bound is what the member gave or `10m`, filled in by the control plane so the
resource always carries a value. The operator keeps `status.objectStorage.move`:

```text
Requested ─▶ Copying ─▶ Pausing ─▶ Verifying ─▶ Switched
                │          │           │
                └──────────┴───────────┴──▶ Failed (detail; a fresh writing key on Garage)
```

| State | The operator does | Leaves on |
|---|---|---|
| `Requested` | renders the GCS identity, bucket and credential requests (R3) | all `Ready` → `Copying` |
| `Copying` | ensures `…-copy` Job | Job succeeded → `Pausing`; failed → `Failed` |
| `Pausing` | takes write from the key the service holds (`StorageCredential.pauseWrites`, Garage's `DenyBucketKey`), records `pauseStartedAt` and `pauseBound` | the next pass → `Verifying`; bound passed → `Failed` |
| `Verifying` | ensures `…-verify` Job with `activeDeadlineSeconds` = what remains of the bound | succeeded → `Switched`; failed or deadline → `Failed` |
| `Switched` | renders the GCS variables and `envFrom` (R3), which rolls the service; `status.objectStorage.store = gcs` | terminal |
| `Failed` | gives the key in place its writes back (`resumeWrites`); `store = garage`, `detail` says why | terminal; a new request restarts at `Requested` |

**Revised during implementation (2026-10-09).** The plan first issued a new read-only key
(`<bucket>#ro<n>`), patched the Secret and rolled the service onto it. That was unsound: the old
writing key went on working until the pods were replaced, so an instance not yet replaced could
write during the pause and the verify would miss it. Garage's admin API can take write from the
key in place (`DenyBucketKey`), which is in force at once for every instance, needs no rollout and
leaves reads working; `GarageStoreSuite` proves it against the pinned image. The pause is therefore
one admin call, and its end on failure is one more (`AllowBucketKey` with write).

A reconcile is level-triggered, so each pass reads the status's state, observes the Job and the
rollout, and applies one transition. The state survives operator restarts because the status
subresource is etcd's, not the process's; this is the one place the operator reads its own
status as memory, and the reason is written at the field: the facts it would otherwise derive
(which key the Secret holds, whether a Job that TTL removed succeeded) are facts it may not read
or that do not last. A service with no move block is on the store its bucket was made in:
`garage` when `ANKKA_OBJECT_STORE_ADMIN_URL` names a store and the service's bucket exists there,
`gcs` otherwise.

The control plane journals `objectStore`, `bucket`, `bucketAddress` and the move's state and
detail from the status (`ServiceObserved`, each `Option` defaulting to `None`;
`EventCompatibilitySuite`), shows them on `services get`, and refuses a second move request while
one is in progress and a re-issue during one.

**Evidence**: `ClusterSnapshot.scala:184-210` has `rolloutPending`, `updatedReplicas`,
`totalReplicas`; `LifecycleRules.observe` uses them in the order a rollout completes.

**Rejected**: the control plane driving the steps (it holds no credential and sees the cluster
only through the resource); a single long-running Job doing pause and switch itself (it would
need the operator's powers).

## R9. Bucket settings are taken once, and reapplied on request

**Decision**: the operator renders a bucket request's `softDeleteDays`, `kmsKey` and `location`
from the installation and the project the first time, and on every later pass from the request's
own current spec — it may read the `CloudResource` it wrote — unless `spec.objectStorageSettingsGeneration`
(a counter; `POST /services/{p}/{n}/storage/settings`, history `storage-settings-reapplied`) is
higher than the generation recorded on the request's `ankka.thinkmorestupidless.com/settings-generation`
annotation, when it rewrites `softDeleteDays` and `kmsKey` from the installation and stamps the
new generation. `location` is never rewritten: the provider refuses a changed location as `Failed`
"made in another location" (044 FR-017), and the status shows the location the bucket is in.
`corsOrigins` and `noncurrentVersionDays` follow the descriptor on every pass: they are the
service's, and a rule change is what the member asked for by applying.

**Rationale**: FR-012, FR-014; the edge case "the installation's soft-delete window is changed".

## R10. The project's location is an `AnkkaProject` field

**Decision**: `PUT /projects/{id}/location` with `{ "location": "europe-west6" }` (CLI `ankka
projects location set`), recorded on the `Project` entity (`ProjectLocationSet`, history), projected
as `AnkkaProject.spec.bucketLocation`; the service reconciler reads it as it reads
`declaredBrokers` (`Executor.projectBrokers`), and the project informer already requeues the
project's services. Absent means the installation's `ANKKA_CLOUD_LOCATION`. The control plane
refuses the route on a `garage`-only installation, naming the backend.

**Evidence**: `AnkkaProjectSpec` (`crd/…/AnkkaProject.scala:14-19`) holds `topics` and `brokers`;
`ankkaproject.yaml` declares them; the project informer requeues (`Operator.scala:100-130`).

## R11. The status a member reads comes from the resource on GCS

**Decision**: `ObjectStorageStatus` gains `store` (`garage` | `gcs`), `location`, `softDeleteDays`,
`credentialGeneration`, and `move: Option[MoveStatus]`; `bucket` and `publicAddress` keep their
names and on GCS carry what the fulfilment reported. `StatusIngest` copies `store`, `bucket`,
`publicAddress`, the move's state and detail onto `ServiceObserved`; `Service.bucketOf` and
`bucketPathOf` answer from the observed values when present and derive the Garage name only when
they are absent (an installation that upgraded with no new status yet). `ServiceStatus` on the wire
gains `objectStore`, `bucketLocation`, `softDeleteDays` ("a deleted object can be recovered for
7 days") and `storageMove` (phrase and detail); `ankka services get` prints each when present; the
console's `Object storage` fact gains the store and the move.

**Rationale**: FR-005 ("the control plane MUST show the name from the status and MUST NOT derive
it"), FR-013, FR-021, FR-023.

## R12. Tests: the fake provider and Garage stand in for Google in the k3s suite; Google is proved nightly

**Decision**, in three tiers:

1. **Offline** (`sbt -Dankka.cluster.tests=off test`): the pure functions — `ObjectStorage.decide`
   and `status` per store, `Rendering` for the three requests, the Job, the variables per store and
   the annotations, `StorageCredential.reissue` and the read-only key against the in-memory
   `World` double, `GarageStore`'s two new operations against the real image, the descriptor's
   rules, the projection, the events' compatibility, the CLI's output, the golden and unchanged
   fixtures, `CrdSchemaSuite` for every new field, the mover against two Garage containers.
2. **k3s** (`ObjectStorageGcsClusterFeatures`, a `GherkinSuite` over
   `features/object-storage-gcs/`, discovered by `cluster-suites.py` through its
   `ankka.cluster.tests` gate): the installation runs the `garage` component *and* the fake
   provider with `ANKKA_OBJECT_STORE_BACKEND=gcs`; the fake "makes" each requested bucket in that
   same Garage under the contract's name rule and mints a Garage key into `<service>-gcs-storage`;
   the operator's GCS endpoint setting (`ANKKA_OBJECT_STORE_GCS_ENDPOINT`, default
   `https://storage.googleapis.com`) points at Garage's S3 port. Every scenario about requests,
   status, names, keyless rendering, re-issue timing, the move's states, the write pause, its
   bound, and the kept Garage bucket runs end to end with no Google. Scenarios only Google can
   answer — "Google Cloud Storage refuses", public access prevention, versioning, soft delete,
   the provider's workload identity — are listed in the suite's `ranOutside` map with the reason,
   a small addition to `GherkinSuite` beside `ranElsewhere` that fails on a scenario that does
   not exist and reports each skipped one by name. They are ankka-gcp's nightly suite's (044
   FR-021).
3. **Google** (`GcsCompatibilitySuite` in `controlplane`'s tests, nightly and on demand in a new
   `.github/workflows/gcs.yml`, `assume`-skipped without `ANKKA_GCS_BUCKET`, `_ACCESS_KEY`,
   `_SECRET_KEY`; failing rather than skipping under `CI` in that workflow; the switch
   `ankka.gcs.tests` forwarded in `Test / javaOptions`): the S3-client assumptions the docs make,
   against a real bucket prepared once by hand with versioning and a 7-day soft delete — the
   checksum settings, path style, put/get/list/delete, a presigned PUT and GET, `?versions` and
   `DELETE ?generation=`, the CORS preflight for a named and an unnamed origin, an unsigned GET
   refused, and the AWS SDK's `PutBucketCors` *refused* (the reason FR-016 exists). The tests are
   named for the retention and reachable scenarios they prove, so `ranOutside` can point at them
   where a test in this repository exists.

**Rationale**: FR-024 and SC-007; the spec's independent tests all say "against a real Google
Cloud project", which only ankka-gcp and the nightly workflow can do; everything ankka itself
decides is provable with the fake.

**Evidence**: `cluster-suites.py` lists any class in a file naming `ankka.cluster.tests`; the
`gcs.yml` suites must not name it. `release.yml:193-198` already authenticates to Google with
Workload Identity Federation for tags only; `gcs.yml` uses repository secrets for the HMAC key
instead — a static key for one test bucket, in one project, not the release identity.
`AnthropicProviderSuite.scala:17-23` is the skip shape.

## R13. CI and the build

**Decision**: `storage-mover/` is a new top-level directory, claimed by the `scala-build` and
`scala` filters in `ci.yml` and built in the `rest` matrix by subtraction; its image is built by
`docker:publishLocal` and by the cluster tests' image task as the sample's is; `gcs.yml` and its
script go under `unchecked`; `ankka.gcs.tests` joins the forwarded `-D` list in `build.sbt`.
`cluster.yml` needs nothing: the new k3s suite is discovered.

## R14. Documentation

**Decision**: `docs/platform/object-storage.md` gains "On Google Cloud Storage" (what differs,
in FR-018's words: reachable by any valid signature, exposure decides the address and the CORS
rule; versioning and soft delete; no retention policy and why; the name; re-issue; origins;
declining a credential; the move); `docs/platform/install-gke.md` is new (GKE, Workload Identity,
the provider's identity and roles as ankka-gcp's README states them, the three settings, the
cloud settings, the move from Garage); `docs/reference/service-descriptor.md` the three fields;
`docs/reference/limitations.md` (Garage keeps one version; objects over 5 GiB do not move;
in-cluster Garage traffic unchanged); `docs/operate/status-and-history.md` the new lines and
history kinds; `docs/platform/backups.md` is 041's. `mkdocs.yml` nav and the rendered skill
through `just docs-sync`.

## R15. What this feature deliberately does not do

- No platform bucket is rendered here: 041 renders `purpose: backup` requests; 039 only keeps a
  service's identity from ever being named on one (the provider grants a `bucket-credential`'s
  principal on its bucket and nothing else).
- No versioning on Garage; `objectStorageVersionAgeDays` is accepted and has no effect there.
- No "delete every version" API of ankka's: a service on GCS does it through the XML API
  (`?versions`, `DELETE ?generation=`), which `GcsCompatibilitySuite` proves and the docs show;
  erasure across both stores is 042's.
- No SDK storage client, no S3 or Azure provider, no retention policy setting.

## Verify first, gathered

| # | What | How | Decision it holds up |
|---|---|---|---|
| S1 | Garage `v2.3.0` honours `UpdateBucket.corsRules`, `AllowBucketKey` with `write: false`, `UpdateKey.expiration` and reports `expired` | three cases in `GarageStoreSuite` against the pinned image | R5, R6 |
| S2 | GCS's XML API with an HMAC key: SigV4 with region `auto` in the credential scope, presigned PUT and GET, `?versions`, `DELETE ?generation=`, the preflight, the AWS SDK's `PutBucketCors` refused, the checksum settings | `GcsCompatibilitySuite` run once by hand against a prepared bucket before slice 6; if `auto` is refused, the region becomes a setting with the bucket's location as its value | R3, R12, R15, FR-003's and FR-004's assumptions |
| S3 | A pod in a project namespace reaches `storage.googleapis.com` under the zero-trust policies | `InPod.curl` from a deployed service in the k3s suite, and by hand on the production installation | R7 |
| S4 | The AWS SDK talks to two endpoints with two credentials in one JVM with `WHEN_REQUIRED` checksums (Garage source, GCS target) | the mover's own suite against two Garage containers; S2 for the GCS half | R7 |
| S5 | 044's identity fulfilment can carry the GKE annotation and the provider can bind the KSA (`roles/iam.workloadIdentityUser`) without the operator | ankka-gcp's; this repository only copies annotations | R4 |

## Verified during planning

- Google allows **ten HMAC keys per service account**; a new key may take up to 60 seconds to
  become usable; a deleted service account's keys may work for up to five minutes
  ([HMAC keys](https://docs.cloud.google.com/storage/docs/authentication/hmackeys)). The spec's
  "small number" is ten; the `credentialId` amendment (R1) is what lets a provider prune.
- Soft delete is **on by default at 7 days, settable 7 to 90 days, 0 disables**, and a changed
  policy applies only to objects deleted after the change
  ([soft delete](https://docs.cloud.google.com/storage/docs/soft-delete)). `7` is the shipped
  default and the control plane refuses a value outside 7 to 90.
- Garage does **not** support object versioning (`GetBucketVersioning` is a stub, `ListObjectVersions`
  missing) and does support `PutBucketCors` through S3 and `corsRules` through the admin API
  ([S3 compatibility](https://garagehq.deuxfleurs.fr/documentation/reference-manual/s3-compatibility/),
  [admin API v2](https://garagehq.deuxfleurs.fr/api/garage-admin-v2.json)).
- Google documents the XML API as the S3-compatible surface, reached at `storage.googleapis.com`
  with HMAC credentials and V4 signatures, and states that S3 signing workflows produce valid
  signed URLs against it ([interoperability](https://docs.cloud.google.com/storage/docs/interoperability)).
  Whether every operation the mover and the docs need behaves is S2's to show.
