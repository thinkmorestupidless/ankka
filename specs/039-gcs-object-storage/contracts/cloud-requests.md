# Contract: the requests 039 renders, and what it asks of 044

Feature 044 defines `CloudResource`, the six kinds, the credential rules and the scripted
provider, and is merged (research R1a). This page says how 039 uses three of the kinds, the name
rule every provider follows, how the scripted provider answers in this repository's suites, and
what 039 adds to 044's contract itself, since 044 made none of the amendments R1 asked for.

## The three requests of a service on GCS

All in the project's namespace, owned by the `AnkkaService`, `spec.provider` copied from
`ANKKA_CLOUD_PROVIDER`, written by server-side apply on every reconcile pass.

### `<service>-identity` (`identity`)

| Parameter | Value |
|---|---|
| `serviceAccount` | the service's Kubernetes ServiceAccount name (the service's name) |

| Output | Used for |
|---|---|
| `identity` | the `bucket-credential` request's `identity` |
| `serviceAccountAnnotations` | `key=value` pairs, comma-separated, copied onto the service's ServiceAccount verbatim (added by 039, R1a D4) |

### `<service>-bucket` (`bucket`)

| Parameter | Value |
|---|---|
| `purpose` | `service` |
| `location` | the project's `bucketLocation`, else `ANKKA_CLOUD_LOCATION`; never rewritten |
| `namePrefix` | `ANKKA_OBJECT_STORE_PREFIX` (added by 039, R1a D3) |
| `versioning` | `true`, always |
| `softDeleteDays` | `ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS` at first render; rewritten only on a settings reapply |
| `corsOrigins` | the descriptor's `objectStorageOrigins` when `exposeObjectStorage`, else empty; every pass |
| `noncurrentVersionDays` | the descriptor's `objectStorageVersionAgeDays`, empty when absent (added by 039, R1a D3); every pass |
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
| `identity` | the identity fulfilment's `identity` |
| `secretName` | `<service>-cloud-storage` (R1a D2; 044 built `<service>-storage`) |
| `credentialGeneration` (the spec's field, not a parameter) | the resource's `storageCredentialGeneration` plus one: the member's count starts at 0, a provider's generations at 1 (R1a D9) |

| Output | Used for |
|---|---|
| `secretName` | the developer's container's `envFrom` |
| `credentialGeneration` (the status's field) | `status.objectStorage.credentialGeneration` and the pod template's credential annotation: the service rolls when this changes |

## Folding into the service's status

| Requests | `status.objectStorage.phase` | `detail` |
|---|---|---|
| any not acknowledged past the bound | `Waiting` | `no provider for gcp has answered` |
| any `Waiting` | `Waiting` | that request's detail, prefixed by its kind |
| any `Failed` | `Failed` | that request's detail, word for word |
| all `Ready`, bucket `recovered: false` | `Provisioned` | — |
| all `Ready`, bucket `recovered: true` | `Recovered` | — |

A keyless service has two requests to fold, not three.

## The bucket name rule (added by 039, R1a D6)

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

`BucketNames` in `operator`'s test sources holds this rule for the scripted provider and for
the name scenario; no main source set computes it (FR-005). `docs/platform/cloud-provider.md`'s
`bucket` kind states it for every provider.

## The scripted provider in 039's suites

044's `ScriptedCloudProvider` fulfils every kind with made-up outputs, which 044's own suite
keeps. 039 gives `ScriptedFulfilment` a Garage-backed mode (R1a D7): a `bucket` request makes a
real bucket in the installation's Garage under the rule above, and a `bucket-credential` request
mints a Garage key allowed on it and writes it into `secretName` by `create`; a raised
`credentialGeneration` mints a new key, patches the Secret, reports the generation, and expires
the old key after `ANKKA_CLOUD_ROTATION_GRACE`, which the suite shortens to seconds. The bucket's
`endpoint` output is Garage's S3 port, so a service "on GCS" in the suite reads and keeps objects
for real, and a move copies between two Garage buckets. The mode applies `corsOrigins` through
Garage's admin API, answers `identity` with `serviceAccountAnnotations`
`scripted.example/identity=<service>`, and ignores `versioning`, `softDeleteDays`, `kmsKey` and
`noncurrentVersionDays`, which it echoes in nothing.

## What 039 adds to 044's contract

044 is merged without the amendments R1 asked of its spec, so 039 makes them in 044's code and
documents (R1a):

1. **`bucket` takes `namePrefix` and `noncurrentVersionDays`** (D3), in `CloudRequests.Keys`,
   `docs/platform/cloud-provider.md` and the scripted provider.
2. **`identity` answers `serviceAccountAnnotations`** (D4), which the operator copies onto the
   service's ServiceAccount, keeping Google's annotation key out of the operator.
3. **The name rule above is the contract's** (D6).

R1's third amendment (`credentialId`) is dropped (D5); its fifth (a service's own `identity`
request) is what 044 built. 044's rotation grace governs when an old credential ends on every
store (D8), the operator rolls the service on the generation in place, and no rollout-completion
signal is added (039's clarify session, 2026-10-08).
