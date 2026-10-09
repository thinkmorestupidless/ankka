# Contract: the requests 039 renders, and what it asks of 044

Feature 044 defines `CloudResource`, the six kinds, the credential rules and the fake provider.
This page says how 039 uses three of the kinds, the name rule every provider follows, how the
fake answers in this repository's suites, and the four amendments 039 asks of 044's spec
(research R1). Nothing here is implemented until 044 is.

## The three requests of a service on GCS

All in the project's namespace, owned by the `AnkkaService`, `spec.provider` copied from
`ANKKA_CLOUD_PROVIDER`, written by server-side apply on every reconcile pass.

### `<service>-identity` (`identity`)

| Parameter | Value |
|---|---|
| `serviceAccount` | the service's Kubernetes ServiceAccount name (the service's name) |

| Output | Used for |
|---|---|
| `principal` | the `bucket-credential` request's `principal` |
| `serviceAccountAnnotations` | copied onto the service's ServiceAccount verbatim (amendment 2) |

### `<service>-bucket` (`bucket`)

| Parameter | Value |
|---|---|
| `purpose` | `service` |
| `location` | the project's `bucketLocation`, else `ANKKA_CLOUD_LOCATION`; never rewritten |
| `namePrefix` | `ANKKA_OBJECT_STORE_PREFIX` (amendment 1) |
| `versioning` | `true`, always |
| `softDeleteDays` | `ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS` at first render; rewritten only on a settings reapply |
| `corsOrigins` | the descriptor's `objectStorageOrigins` when `exposeObjectStorage`, else empty; every pass |
| `noncurrentVersionDays` | the descriptor's `objectStorageVersionAgeDays`, absent when absent (amendment 1); every pass |
| `kmsKey` | `ANKKA_CLOUD_KMS_KEY` at first render; rewritten only on a settings reapply |

| Output | Used for |
|---|---|
| `bucket` | `ANKKA_S3_BUCKET`, `status.objectStorage.bucket`, the credential request |
| `location` | `status.objectStorage.location` |

Annotation `ankka.thinkmorestupidless.com/settings-generation`: the `objectStorageSettingsGeneration`
the `softDeleteDays` and `kmsKey` were last taken at.

### `<service>-storage-credential` (`bucket-credential`)

Rendered only once the two above are `Ready` and the descriptor's `objectStorageCredential` is
`true`.

| Parameter | Value |
|---|---|
| `bucket` | the bucket fulfilment's `bucket` |
| `principal` | the identity fulfilment's `principal` |
| `secretName` | `<service>-gcs-storage` |
| `credentialGeneration` | the resource's `storageCredentialGeneration` |

| Output | Used for |
|---|---|
| `secretName` | the developer's container's `envFrom` |
| `credentialGeneration` | `status.objectStorage.credentialGeneration` and the pod template's credential annotation: the service rolls when this changes |
| `credentialId` | nothing in ankka; the provider's own pruning (amendment 3) |

## Folding into the service's status

| Requests | `status.objectStorage.phase` | `detail` |
|---|---|---|
| any not acknowledged past the bound | `Waiting` | `no provider for gcp has answered` |
| any `Waiting` | `Waiting` | that request's detail, prefixed by its kind |
| any `Failed` | `Failed` | that request's detail, word for word |
| all `Ready`, bucket `recovered: false` | `Provisioned` | — |
| all `Ready`, bucket `recovered: true` | `Recovered` | — |

A keyless service has two requests to fold, not three.

## The bucket name rule (amendment 4)

Every provider — `ankka-gcp`, the fake, any other — names a `purpose: service` bucket:

```text
<namePrefix>-<project>-<service>-<digest>
```

- `digest` is the first eight lowercase hex characters of SHA-256 over `<project>.<service>`
  (034's name, already unique per pair), and is never shortened.
- The whole must fit 63 characters and contain only `[a-z0-9-]`; when it does not fit, the
  service is shortened from the right, then the project from the right, each kept at least one
  character.
- A name held by someone outside the installation's account is `Failed` naming the bucket; no
  provider adopts it.

`BucketNames` in `operator`'s test sources holds this rule for the fake and for the name
scenario; no main source set computes it (FR-005).

## The fake provider in 039's suites

044's fake fulfils every kind with dummy outputs. 039's k3s suite extends it so a `bucket`
request makes a real bucket in the installation's Garage under the rule above, and a
`bucket-credential` request mints a Garage key allowed on it and writes it into `secretName` by
`create`; a raised `credentialGeneration` mints a new key, patches the Secret, reports the
generation, and expires the old key after a grace the suite shortens to seconds. The operator's
`ANKKA_OBJECT_STORE_GCS_ENDPOINT` points at Garage's S3 port, so a service "on GCS" in the suite
reads and keeps objects for real, and a move copies between two Garage buckets. The fake applies
`corsOrigins` through Garage's admin API and ignores `versioning`, `softDeleteDays`, `kmsKey`
and `noncurrentVersionDays`, reporting them back unchanged.

## The amendments asked of 044's spec

1. **`bucket` gains `namePrefix` and `noncurrentVersionDays`** (FR-005 there). Without the
   prefix a provider needs configuration of its own; without the age FR-017 here has no carrier.
2. **`identity` returns `serviceAccountAnnotations`**, a map the operator copies onto the
   Kubernetes ServiceAccount. It keeps Google's annotation key out of the operator and lets a
   keyless service reach its bucket (FR-019 here) with no cloud-specific rendering.
3. **`bucket-credential` returns `credentialId`** (the access key id of the generation in
   place), so a provider can prune keys no Secret holds and stay under Google's ten per account.
4. **The name rule above is the contract's**, written into 044's `bucket` kind, and the fake
   follows it.
5. **A service with a bucket renders an `identity` request of its own**, beside the bucket and
   credential requests, so that a service that declines a credential (US5 here) still has a cloud
   identity bound to its ServiceAccount. 044's User Story 1 and
   `features/cloud-provider/bucket.feature` say "a bucket request and a bucket credential
   request", with the identity made inside the credential; they change to name all three, and the
   credential request takes the identity's `principal` rather than making one.

And one confirmation rather than a change: 044's rotation grace governs when an old credential
ends on every store, the operator rolls the service on the generation in place, and no
rollout-completion signal is added (039's clarify session, 2026-10-08).
