# Contract: what the operator renders and does

Decisions are in [research.md](../research.md) (R2, R3, R5 to R9). Data is in
[data-model.md](../data-model.md). 034's contract holds for Garage unless named here.

## Settings

Beside 034's five `ANKKA_OBJECT_STORE_*` and 044's `ANKKA_CLOUD_*`:

| Variable | Default | Meaning |
|---|---|---|
| `ANKKA_OBJECT_STORE_BACKEND` | `garage` when the admin URL is set, else none | the store new buckets are made in |
| `ANKKA_OBJECT_STORE_PREFIX` | — (required with `gcs`) | the GCS bucket name prefix, a DNS label |
| `ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS` | `7` | 7 to 90 |
| `ANKKA_OBJECT_STORE_GCS_ENDPOINT` | `https://storage.googleapis.com` | given to workloads on GCS as `ANKKA_S3_ENDPOINT`; the k3s suite points it at Garage |
| `ANKKA_STORAGE_MOVER_IMAGE` | `ankka-storage-mover:<BuildInfo tag>` | the mover's image, as `ANKKA_SIDECAR_IMAGE` |

`gcs` with `ANKKA_CLOUD_PROVIDER` unset or `none`, or without the prefix, is a startup failure
naming the missing setting. Each has a system property, as every operator setting has.

## The store's interface

```scala
trait ObjectStore:
  // 034's six, and:
  def allow(bucketId: String, accessKeyId: String, write: Boolean): Unit   // read always; owner iff write
  def setCors(bucketId: String, origins: Seq[String]): Unit               // replaces the rules; empty removes them
  def expire(accessKeyId: String, at: Instant): Unit                       // UpdateKey.expiration
  def keyInfo(accessKeyId: String): KeyInfo                                // name, expired; never the secret

final case class KeyInfo(accessKeyId: String, name: String, expired: Boolean)
```

`GarageStore` maps them to `POST /v2/AllowBucketKey` (`read: true, write, owner: write`),
`POST /v2/UpdateBucket` with `corsRules` (one rule: the origins, `GET` `PUT` `HEAD`, `*` headers,
`ETag` exposed, `maxAge` 3600), `POST /v2/UpdateKey` with `expiration`, and `GET /v2/GetKeyInfo`
without `showSecretKey`. 034's `allow(bucket, key)` becomes `allow(bucket, key, write = true)`.

## The plan, per store

`ObjectStorage.decide(spec, settings, project, observed)`; `observed` now carries either the
Garage observation (034) or the three requests' statuses (044). First match wins:

| # | When | Plan | Phase |
|---|---|---|---|
| 1–2 | does not ask / supplies its own | 034's | — / `Supplied` |
| 3 | backend `gcs` and no cloud provider named | `Failed("object storage 'gcs' needs a cloud provider")` | `Failed` |
| 4 | the service's store is `garage` (data-model, "where the store comes from") | 034's rows 3–8, plus: `objectStorageCredential: false` → `Failed("a bucket in Garage is reached only with a storage credential")` | 034's |
| 5 | store `gcs`, a request unacknowledged past the bound | `Waiting(Some("no provider for gcp has answered"))` | `Waiting` |
| 6 | store `gcs`, a request `Waiting` | `Waiting(Some(detail))` | `Waiting` |
| 7 | store `gcs`, a request `Failed` | `Failed(detail)` | `Failed` |
| 8 | store `gcs`, requests `Ready`, bucket recovered | `Ready(recovered = true)` | `Recovered` |
| 9 | store `gcs`, requests `Ready` | `Ready(recovered = false)` | `Provisioned` |

The name-length rule (034's row 3) applies on `garage` only.

## Actions

| Action | Describes | Performed as |
|---|---|---|
| `EnsureCloudResource(resource)` | 044's | 044's server-side apply |
| `EnsureServiceAccountAnnotations(ns, name, annotations)` | the identity fulfilment's map | merged onto the ServiceAccount the operator already renders (part of `EnsureServiceAccount`'s object; no new verb) |
| `SetBucketCors(bucket, origins)` | Garage; the descriptor's origins when exposed, else empty | `ObjectStore.setCors` on every pass that renders the bucket |
| `ReissueStorageCredential(ns, secretName, labels, bucket, generation)` | Garage; no key | `StorageCredential.reissue` below |
| `EnsureReadOnlyCredential(ns, secretName, labels, bucket, move)` | Garage; a move's pause | `StorageCredential.readOnly` below |
| `RestoreWritingCredential(ns, secretName, labels, bucket, generation)` | Garage; a move's failure | `StorageCredential.reissue` with the next generation |
| `ExpireKeysBelow(bucket, generation)`, `DeleteExpiredKeys(bucket)` | Garage | `expire` on every key of a lower generation; `deleteKey` on each `expired` |
| `EnsureMoveJob(job)` | a `batch/v1 Job` | server-side apply; RBAC `batch/jobs` get list watch create patch |

`describe` prints namespaces, names, buckets, generations and states. No action, log line or
status holds a secret.

### `StorageCredential.reissue(namespace, secretName, labels, bucket, generation)`

| Step | Does |
|---|---|
| 1 | `createKey(s"$bucket#$generation")`, `allow(bucket, key, write = true)` |
| 2 | `secrets.patch(namespace, secretName, entries)` — the Secret exists (034 made it); a patch of an absent Secret is a `create` |
| 3 | `expire(k, now + grace)` for every key named `bucket` or `bucket#m` with `m < generation` that is not yet expiring |
| 4 | returns `generation`; the reconciler records it in the status and on the pod template |

Idempotent: a pass that finds `bucket#generation` already among `keysNamed` does steps 3 and 4
only. `grace` is 044's rotation grace. A later pass's `DeleteExpiredKeys` removes what Garage
reports expired.

### `StorageCredential.readOnly(namespace, secretName, labels, bucket, move)`

`createKey(s"$bucket#ro$move")`, `allow(bucket, key, write = false)`, patch the Secret; the
reconciler bumps the credential annotation (to `<generation>-ro<move>`) so the service rolls
onto it. Expired with the rest when a writing generation follows.

## Rendering, per store

Order within `render`: storage actions after the secret key's and before zero-trust, as 034.

| Store | Plan | Rendered |
|---|---|---|
| `garage` | as 034 | 034's actions, plus `SetBucketCors` with `EnsureBucket`, plus `ReissueStorageCredential` when `spec.storageCredentialGeneration` > the status's, plus `ExpireKeysBelow`/`DeleteExpiredKeys` |
| `gcs` | `Waiting`, `Ready` | the identity and bucket requests; the credential request once both are `Ready` and a credential is taken; the annotations once the identity is `Ready`; `RemoveHttpRoute` |
| `gcs` | `Failed` | `RemoveHttpRoute` only |

### The developer's container

| Store | `envFrom` | `ANKKA_S3_ENDPOINT` | `ANKKA_S3_REGION` | `ANKKA_S3_BUCKET` | `ANKKA_S3_PUBLIC_ENDPOINT` |
|---|---|---|---|---|---|
| `garage` | `<service>-storage` | the store's | `garage` | `<project>.<service>` | `https://storage.<base>` when exposed |
| `gcs` | `<service>-gcs-storage`, or none when the credential is declined | `ANKKA_OBJECT_STORE_GCS_ENDPOINT` | `auto` | the fulfilment's name | `https://storage.googleapis.com` when exposed |

On `gcs` the variables are rendered only once the bucket request is `Ready` (the name is not
known before), and the `envFrom` once the credential request is; on `garage` as 034. The
hostings are 034's: the developer's container only.

### The pod template's credential annotation

`ankka.thinkmorestupidless.com/storage-credential-generation: <generation in place>` — the
status's `credentialGeneration` on either store (the fulfilment's on `gcs`, `reissue`'s on
`garage`), or `<generation>-ro<move>` during a pause. Absent for a service without a bucket the
platform made, so nothing already rendered changes.

### The ServiceAccount

The service's ServiceAccount (already rendered) carries the identity fulfilment's
`serviceAccountAnnotations`, merged with the platform's own. Absent until the identity is
`Ready`; a service on Garage has none.

## The move

`StorageMove.next(spec.objectStorageMove, status.move, observed)` is pure and returns the next
state and the actions for this pass; `observed` carries the requests' statuses, the Job's
condition and termination report, the rollout snapshot and the clock.

| From | Observed | To | Actions |
|---|---|---|---|
| none, spec has a request `n` > status's | — | `Requested` | the three GCS requests (as a `gcs` service's) |
| `Requested` | all `Ready` | `Copying` | `EnsureMoveJob(copy)` |
| `Requested` | a request `Failed` | `Failed` | — |
| `Copying` | Job succeeded | `Pausing` | `EnsureReadOnlyCredential`; records `pauseStartedAt`, `pauseBound` |
| `Copying` | Job failed | `Failed` | — (the service still holds its writing key) |
| `Pausing` | rollout complete on the read-only annotation | `Verifying` | `EnsureMoveJob(verify, deadline = bound − elapsed)` |
| `Pausing` | `now > pauseStartedAt + bound` | `Failed` | `RestoreWritingCredential` |
| `Verifying` | Job succeeded | `Switched` | the GCS variables and `envFrom` (rolls the service); `store = gcs` |
| `Verifying` | Job failed or deadline | `Failed` | `RestoreWritingCredential`; `detail` from the termination report |
| `Failed`, spec request `n+1` | — | `Requested` | as the first row |

"Rollout complete": `ClusterSnapshot` with no `rolloutPending`, `updatedReplicas == specReplicas`,
`totalReplicas == updatedReplicas`, `readyReplicas == specReplicas`, and the Deployment's
template carrying the read-only annotation. The verify Job's `activeDeadlineSeconds` is what
remains of the bound, at least 1; Kubernetes fails it at the deadline, which the next pass reads.
A `Failed` move's `RestoreWritingCredential` is `reissue` at `max(status generation, spec
generation) + 1`, so the service rolls onto a writing key and the read-only key expires.

During `Pausing` and `Verifying` the operator ignores a raised `storageCredentialGeneration` (the
control plane refuses it anyway) and keeps rendering Garage's variables; after `Switched` it
renders GCS's and never Garage's again for this service.

## The Job

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: kyc-move-1-copy            # or -verify
  namespace: ankka-casino
  labels: { the service's identity labels, ankka.thinkmorestupidless.com/role: storage-mover }
  ownerReferences: [ the AnkkaService ]
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 86400
  activeDeadlineSeconds: 540        # verify only: the bound's remainder
  template:
    metadata: { labels: { the zero-trust labels a service pod carries } }
    spec:
      restartPolicy: Never
      serviceAccountName: kyc
      containers:
        - name: mover
          image: ankka-storage-mover:<tag>
          imagePullPolicy: IfNotPresent
          terminationMessagePolicy: FallbackToLogsOnError
          args: [copy]              # or verify
          env:
            - { name: MOVER_SOURCE_ENDPOINT, value: "http://garage.garage-system.svc.cluster.local:3900" }
            - { name: MOVER_SOURCE_REGION,   value: "garage" }
            - { name: MOVER_SOURCE_BUCKET,   value: "casino.kyc" }
            - { name: MOVER_SOURCE_ACCESS_KEY, valueFrom: { secretKeyRef: { name: kyc-storage, key: ANKKA_S3_ACCESS_KEY } } }
            - { name: MOVER_SOURCE_SECRET_KEY, valueFrom: { secretKeyRef: { name: kyc-storage, key: ANKKA_S3_SECRET_KEY } } }
            - { name: MOVER_TARGET_ENDPOINT, value: "https://storage.googleapis.com" }
            - { name: MOVER_TARGET_REGION,   value: "auto" }
            - { name: MOVER_TARGET_BUCKET,   value: "ankka-casino-kyc-3f9a1c2e" }
            - { name: MOVER_TARGET_ACCESS_KEY, valueFrom: { secretKeyRef: { name: kyc-gcs-storage, key: ANKKA_S3_ACCESS_KEY } } }
            - { name: MOVER_TARGET_SECRET_KEY, valueFrom: { secretKeyRef: { name: kyc-gcs-storage, key: ANKKA_S3_SECRET_KEY } } }
          resources: { requests: { cpu: 250m, memory: 256Mi }, limits: { memory: 512Mi } }
```

The source Secret during `Copying` holds the writing key and during `Verifying` the read-only
one; the mover reads either way. The Job runs under the service's ServiceAccount so the zero-trust
policies that admit the service's pods to Garage and the internet admit it; it holds no
Kubernetes permission. The operator observes `status.succeeded`, `status.failed`, and the mover
pod's `lastState.terminated.message`.

## Grants

The operator's ClusterRole gains, for this feature:

```yaml
- apiGroups: ["batch"]
  resources: ["jobs"]
  verbs: ["get", "list", "watch", "create", "patch"]
```

044 adds `cloudresources`. Nothing on Secrets changes: `create` and `patch`, never `get`.
