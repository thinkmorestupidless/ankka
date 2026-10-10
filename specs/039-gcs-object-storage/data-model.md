# Data Model: Object Storage on Google Cloud Storage

What exists, who writes it, and what each state means. Decisions are in
[research.md](research.md); the wire and the rendered objects are in [contracts/](contracts/).
Everything 034 defined stays as it was unless named here.

## In Google Cloud (made by the cloud provider, never by ankka)

### Bucket

| | |
|---|---|
| Name | `<prefix>-<project>-<service>-<digest8>`, by the contract's rule ([cloud-requests.md](contracts/cloud-requests.md)); reported, never derived in ankka |
| Made by | the cloud provider, fulfilling the service's `bucket` request |
| Settings | object versioning on; soft delete at the installation's days; public access prevention enforced; uniform bucket-level access; no retention policy; CORS from the request's origins; a lifecycle rule deleting noncurrent versions older than `noncurrentVersionDays` when given; the wrapping key when the installation names one |
| Location | the project's, else the installation's; fixed at creation |
| Deleted by | never the platform |

### Cloud identity

| | |
|---|---|
| One per | service with a bucket on GCS |
| Granted on | that bucket, and nothing else |
| Bound to | the service's Kubernetes ServiceAccount, through the annotations the identity fulfilment returns |
| Owns | the service's HMAC key, when the descriptor takes one |

### HMAC key

| | |
|---|---|
| Per identity | one in place; one more between a re-issue and the end of the rotation grace; never more than ten |
| Secret | returned once to the provider, written into `<service>-cloud-storage`, held nowhere else |
| Ended | by the provider, one rotation grace after the fulfilment that replaced it |

## In Garage (made by the operator, as 034)

### Access key

| | |
|---|---|
| Name | `<bucket>` for generation 0 (every key made before this feature), `<bucket>#<n>` for generation `n` |
| Permission | `read`, `write`, `owner`; during a move's write pause the key in place has write and owner taken from it, and `read` alone |
| Expiry | none while in place; `now + rotation grace` once a higher generation is in the Secret |
| Deleted | by the operator, only when Garage reports it `expired`, or when it was issued and never written to a Secret |

### CORS rules

Set by the operator from the descriptor's origins on every pass that renders the bucket; an empty
list clears them.

## In the cluster

### Secrets

| Secret | Store | Entries | Written by | Patched when |
|---|---|---|---|---|
| `<service>-storage` | Garage | `ANKKA_S3_ACCESS_KEY`, `ANKKA_S3_SECRET_KEY` | the operator, `create` | a re-issue; a move's pause (read-only key); a move's failure (a writing key) |
| `<service>-cloud-storage` | GCS | the same two | the cloud provider, `create` | a credential generation raised |

Neither is ever read, listed or deleted by the operator, the provider or the control plane. Both
end in `-storage`, so no descriptor can name them.

### `CloudResource`s of a service on GCS (044's resource)

| Name | Kind | Owner | Exists when |
|---|---|---|---|
| `<service>-identity` | `identity` | the `AnkkaService` | the service asks for a bucket |
| `<service>-bucket` | `bucket` | the `AnkkaService` | the service asks for a bucket |
| `<service>-storage-credential` | `bucket-credential` | the `AnkkaService` | both above are `Ready` and the descriptor takes a credential |

Parameters and outputs are in [cloud-requests.md](contracts/cloud-requests.md).

### Jobs of a move

| Name | Mode | `activeDeadlineSeconds` | Exists when |
|---|---|---|---|
| `<service>-move-<n>-copy` | copy | none | the move is `Copying` |
| `<service>-move-<n>-verify` | verify | what remains of the write pause bound | the move is `Verifying` |

Both: `backoffLimit: 0`, `ttlSecondsAfterFinished: 86400`, owned by the `AnkkaService`, the
image from `ANKKA_STORAGE_MOVER_IMAGE`, the two credentials by `secretKeyRef`. Spec in
[mover.md](contracts/mover.md).

### `AnkkaService` — spec

| Field | Type | Default | Set by | Meaning |
|---|---|---|---|---|
| `provisionObjectStorage`, `exposeObjectStorage` | boolean | `false` | 034 | unchanged |
| `objectStorageOrigins` | list of string | empty | the descriptor | the origins the bucket admits from a browser; empty is no rule |
| `objectStorageCredential` | boolean | `true` | the descriptor | `false` declines the key |
| `objectStorageVersionAgeDays` | int, optional | absent | the descriptor | noncurrent versions older than this are deleted; no effect on Garage |
| `storageCredentialGeneration` | int | `0` | a member's re-issue | raised by one per re-issue |
| `objectStorageSettingsGeneration` | int | `0` | a member's reapply | raised by one per reapply of the installation's bucket settings |
| `objectStorageMove` | object, optional | absent | a member's move | `generation: int` (raised per request), `writePauseBound: string` (a duration, `10m` when the member gave none) |

### `AnkkaProject` — spec

| Field | Type | Default | Meaning |
|---|---|---|---|
| `bucketLocation` | string, optional | absent | the location of the project's new buckets; absent means the installation's |

### `AnkkaService` — `status.objectStorage`

034's `phase`, `bucket`, `publicAddress`, `recovered`, `detail`, and:

| Field | Type | Meaning |
|---|---|---|
| `store` | `garage` \| `gcs` | where this service's bucket is |
| `location` | string, optional | the bucket's location, as the fulfilment reported it (GCS only) |
| `softDeleteDays` | int, optional | the bucket's soft-delete window (GCS only) |
| `credentialGeneration` | int | the generation of the credential in the Secret, which the pod template carries |
| `move` | object, optional | below |

`bucket` and `publicAddress` on GCS carry the fulfilment's name and `https://storage.googleapis.com/<bucket>`.

### `status.objectStorage.move`

| Field | Type | Meaning |
|---|---|---|
| `generation` | int | the request this state belongs to |
| `state` | `Requested`, `Copying`, `Pausing`, `Verifying`, `Switched`, `Failed` | the state machine's position |
| `startedAt` | RFC 3339 | when the move was first seen |
| `pauseStartedAt` | RFC 3339, optional | when the read-only key was written |
| `pauseBound` | string, optional | the bound in force |
| `counted`, `copied`, `verified` | int, optional | the last Job's report |
| `failedObject` | string, optional | the object the verify named |
| `detail` | string, optional | why it failed, or what it waits on |

### Phases of a service's storage on GCS

```text
   descriptor asks ─▶ Waiting ("no provider for gcp has answered", or a request Waiting with its detail)
                         │
                         ├──▶ Failed    a request Failed: the name is held, a permission is missing, a retention policy was found,
                         │              a location differs; or the backend needs a provider the installation has not named
                         ├──▶ Provisioned   every request Ready, the bucket made for this incarnation
                         └──▶ Recovered     every request Ready, the bucket found already made
```

`Supplied` and `NotAsked` are 034's and unchanged. On Garage the phases are 034's.

### Where the store of a bucket comes from

| The service | `store` |
|---|---|
| has a move block `Switched` | `gcs` |
| was reported made (`Provisioned` or `Recovered`) with a `store` | that store |
| was reported made before stores were named (no `store`) | `garage`, the one store there was |
| otherwise, the installation's backend (`Settings.bucketBackend`: the one named, else Garage when installed, else the cloud when a provider is named) is `gcs` | `gcs` |
| otherwise | `garage` |

Read from the status the operator last wrote, never by asking a store (research R1a D1).

## In the control plane

### The descriptor (`ServiceSpec`)

| Field | Type | Default |
|---|---|---|
| `objectStorageOrigins` | list of string | empty |
| `objectStorageCredential` | boolean | `true` |
| `objectStorageVersionAgeDays` | int, optional | absent |

Journaled inside `ServiceApplied`; a field at its default is omitted.

### Events

| Event | Fields | Folds to |
|---|---|---|
| `StorageCredentialReissued` | `generation`, `actor`, `at` | `storageCredentialGeneration + 1`; history `storage-credential-reissued` |
| `StorageMoveRequested` | `generation`, `writePauseBound`, `actor`, `at` | `objectStorageMove`; history `storage-moved` |
| `StorageSettingsReapplied` | `generation`, `actor`, `at` | `objectStorageSettingsGeneration + 1`; history `storage-settings-reapplied` |
| `ProjectLocationSet` (on `Project`) | `location`, `actor`, `at` | `bucketLocation`; the project's history |
| `ServiceObserved` gains | `objectStore`, `bucket`, `bucketAddress`, `storageMove`, `storageMoveDetail`, `softDeleteDays`, each `Option`, default `None` | the service's status |

### What a member reads (`ServiceStatus`), beyond 034's three

| Field | When present | Value |
|---|---|---|
| `objectStore` | the operator reported one | `garage` \| `gcs` |
| `bucketLocation` | GCS | the location |
| `softDeleteDays` | GCS | the window |
| `storageMove` | a move exists | `copying`, `write pause`, `verifying`, `moved`, `move failed`, with `detail` |

`bucket` and `bucketAddress` are the status's values when the operator reported them.

## Rules, in one place

| Rule | Held by |
|---|---|
| the GCS name is reported, never derived | no code in a main source set computes it; `BucketNames` is test code |
| `gcs` needs a cloud provider | `Settings` at startup; `DeployConfig`/`ServiceEndpoint` at apply |
| the name-length refusal is Garage's only | `ServiceEndpoint`, `ServiceProjection`, `ObjectStorage.decide`, each on `backend == garage` |
| declining a credential needs `gcs` | `ServiceEndpoint` at apply; `ObjectStorage.decide` |
| a re-issue or a second move is refused during a move | `ServiceEntity` (409 with the reason) |
| the write pause bound is always present | `ServiceEntity` fills `10m` |
| soft-delete days are 7 to 90 | `DeployConfig` at startup; the fake and the provider refuse others |
| a credential is written once, read never | `StorageCredential`, 044 FR-008 |
| an old Garage key ends by expiry | `StorageCredential.expireBelow`; `deleteExpired` |
| a service on Garage renders what it rendered | `RenderingUnchangedSuite` |
| every new field is in the schema | `CrdSchemaSuite`, both resources, inside `objectStorage` and `move` |
| every new journaled field defaults | `EventCompatibilitySuite` |
