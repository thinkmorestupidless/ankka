# Contract: the `CloudResource` and its six kinds

The one resource a provider implements. The operator writes `spec`; a provider writes `status`.
This page is what `docs/platform/cloud-provider.md` will say to a provider's author, in the
platform's words and no cloud's.

## The resource

```yaml
apiVersion: ankka.thinkmorestupidless.com/v1alpha1
kind: CloudResource
metadata:
  name: reports-bucket                 # <service>-<suffix>, or <project>.<suffix>
  namespace: ankka-shop
  labels:
    app.kubernetes.io/managed-by: ankka
    ankka.thinkmorestupidless.com/project: shop
    ankka.thinkmorestupidless.com/service: reports
  ownerReferences:
    - apiVersion: ankka.thinkmorestupidless.com/v1alpha1
      kind: AnkkaService
      name: reports
      uid: …
      controller: true
      blockOwnerDeletion: true
spec:
  provider: gcp
  kind: bucket
  subject:
    project: shop
    service: reports
  parameters:
    purpose: service
    location: europe-west2
    versioning: "false"
    softDeleteDays: "7"
    corsOrigins: ""
    kmsKey: ""
status:
  observedGeneration: 1
  phase: Ready
  account: my-account
  location: europe-west2
  recovered: false
  providerVersion: "scripted 0.1.0"
  outputs:
    bucket: my-account-shop-reports
    endpoint: https://storage.scripted.invalid
    region: europe-west2
```

The schema is closed. `parameters` and `outputs` are maps of string to string; a list is one
string, comma-separated, and an entry of `secret-sync` is `NAME=id`. A boolean is `"true"` or
`"false"`; a number is its decimal text.

## Names

| Subject | Kind | Name |
|---|---|---|
| a service | `identity` | `<service>-identity` |
| a service | `secret-access` | `<service>-secret-access` |
| a service | `bucket` (`purpose: service`) | `<service>-bucket` |
| a service | `bucket-credential` | `<service>-storage-credential` |
| a project | `secret-sync` | `<project>.secret-sync.<secret>`, one per project secret |
| a project | `bucket` (`purpose: backup`) | `<project>.backup-bucket` |
| a project | `bucket-credential` (for the backup bucket) | `<project>.backup-credential` |
| a project or the platform | `wrapping-key` | `<project>.wrapping-key` |

A service's name has no dot, so a project's request can never share a name with a service's.

## The kinds

### `identity` — a cloud identity for a Kubernetes ServiceAccount

| Parameter | Meaning |
|---|---|
| `serviceAccount` | the ServiceAccount's name, in the request's namespace |

| Output | Meaning |
|---|---|
| `identity` | what the account knows the ServiceAccount as, to be given to other requests |

### `secret-access` — what a cloud identity may do to which secrets

| Parameter | Meaning |
|---|---|
| `identity` | an `identity` request's output |
| `own` | secret id prefixes under which the identity may read, write and remove, comma-separated (may be empty) |
| `read` | secret id prefixes under which the identity may read, comma-separated (may be empty) |

No outputs. Ready means the grants are in place.

Prefixes, not ids: a service names its secrets while it runs, so no request can list them, and an IAM
condition on a name cannot limit a create — the secret does not exist when the create is authorised.
`own` is therefore create on the account and everything else under the prefix (feature 038,
`DerivedIds`: `s_<project>_<service>_` for a service's own, `p_<project>_` for its project's).

### `secret-sync` — a Kubernetes Secret kept in step with a project's entries

| Parameter | Meaning |
|---|---|
| `secretName` | the Secret to keep, in the request's namespace |
| `entries` | `NAME=id` pairs, comma-separated: each Secret key and the secret id its value comes from |
| `entryGeneration` | the generation of the entries as the platform holds them; raised on any change |

| Output | Meaning |
|---|---|
| `entryGeneration` | the generation whose values are in the Secret |

The provider patches the Secret within one minute of a change and reports the generation it
synced; the operator holds a rollout until the output equals the parameter.

### `bucket` — a bucket

| Parameter | Meaning |
|---|---|
| `purpose` | `service` or `backup` |
| `location` | the installation's or the project's location string, verbatim |
| `versioning` | `"true"` or `"false"` |
| `softDeleteDays` | days a deleted object is still recoverable; `"0"` for none |
| `corsOrigins` | origins allowed to read the bucket from a browser, comma-separated; empty for none |
| `kmsKey` | the installation's wrapping key, or empty for the cloud's own encryption |

| Output | Meaning |
|---|---|
| `bucket` | the bucket's name as the provider made it |
| `endpoint` | the S3-compatible endpoint a service reaches it at |
| `region` | the region a client must sign for |

A `backup` bucket is named by the provider so that no `service` bucket can collide with it.

### `bucket-credential` — a credential by which one identity reaches one bucket

| Parameter | Meaning |
|---|---|
| `bucket` | a `bucket` request's output |
| `identity` | an `identity` request's output, or a project's database's identity (041) |
| `secretName` | the Secret to write, in the request's namespace |

`spec.credentialGeneration` is `1` when first written and raised to ask for a new credential. The
status says the generation in place, `credentialGeneration`, and when it was reported,
`credentialReportedAt` (RFC 3339), from which the rotation grace counts.

| Output | Meaning |
|---|---|
| `secretName` | the Secret that holds it (the parameter, echoed) |

The Secret's entries are `ANKKA_S3_ACCESS_KEY` and `ANKKA_S3_SECRET_KEY`. The provider's rule for
writing it is in [provider.md](provider.md).

### `wrapping-key` — a key an identity may wrap with

| Parameter | Meaning |
|---|---|
| `identity` | an `identity` request's output |
| `key` | the installation's wrapping key |

| Output | Meaning |
|---|---|
| `key` | the key the identity may now wrap with (the parameter, echoed) |

## What the operator renders in this feature

Only the bucket path renders requests in this feature: `identity`, `bucket` (`purpose: service`)
and `bucket-credential` for a service with `provisionObjectStorage` on an installation whose cloud
provider is named and whose Garage store is not. The other three kinds, and the backup bucket, have
their renderers in `CloudRequests` with offline tests, and are written by features 038, 041 and 042
when they add the settings that call for them.

## Phases

| `status.phase` | Meaning | What the operator does |
|---|---|---|
| *(no `observedGeneration`)* | no provider has read it | waits; past the acknowledgement bound, reports "no provider for `<provider>` has answered" |
| `observedGeneration` behind | the provider has not read this generation | waits, saying so |
| `Waiting` | the provider is working or blocked, `detail` says what on | waits, with the detail |
| `Ready` | done; `outputs` are the answer | proceeds |
| `Recovered` | done, and the thing was already there; `recovered: true` | proceeds, and says recovered |
| `Failed` | cannot; `detail` says why, in the provider's words | reports it, word for word |

## Failures a provider must report, with these details

| Situation | `phase` | `detail` |
|---|---|---|
| a kind this provider does not implement | `Failed` | `kind <kind> is not implemented by <providerVersion>` |
| the spec's account or location differs from the status's | `Failed` | `made in another account or location` |
| the cloud refuses | `Failed` | the cloud's reason, with nothing secret in it |
| a `bucket-credential` on a `backup` bucket for any identity but its project's database's | `Failed` | `a backup bucket is granted only to its project's database` |
