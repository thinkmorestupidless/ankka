# Contract: the descriptor, the actions, and what a member reads

What a member writes and does, and what the control plane, the CLI and the console say back.
Decisions are in [research.md](../research.md) (R2, R4, R5, R8, R9, R10, R11). 034's contract
holds for everything not named here.

## The descriptor

```json
{
  "name": "kyc",
  "service": {
    "image": "registry.example.com/casino/kyc:1.0.0",
    "provisionObjectStorage": true,
    "exposeObjectStorage": true,
    "objectStorageOrigins": ["https://play.example"],
    "objectStorageVersionAgeDays": 365
  }
}
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `objectStorageOrigins` | list of string | `[]` | the origins a browser may send from to the bucket; the CORS rule's content on both stores; empty is no rule |
| `objectStorageCredential` | boolean | `true` | `false` declines the storage credential: no key is issued, `ANKKA_S3_ACCESS_KEY` and `ANKKA_S3_SECRET_KEY` are absent; the service reaches its bucket as its workload identity |
| `objectStorageVersionAgeDays` | integer ≥ 1 | absent | an age after which noncurrent versions are deleted; accepted and without effect on Garage |

Each needs `provisionObjectStorage`. The same descriptor is valid on either store; the control
plane's refusals that depend on the store are the installation's to make.

### Refusals, at apply

| The descriptor | Message | Where |
|---|---|---|
| sets any of the three without `provisionObjectStorage` | `objectStorageOrigins needs provisionObjectStorage` (likewise the other two) | `ServiceSpec.problems` |
| names an origin that is not an origin (`https://host[:port]` or `*`) | `objectStorageOrigins: '<value>' is not an origin` | `ServiceSpec.problems` |
| sets `objectStorageOrigins` without `exposeObjectStorage` | accepted; the rule is set only while the bucket is reachable | — |
| `objectStorageCredential: false` on an installation whose backend is `garage` | `objectStorageCredential: a bucket in Garage is reached only with a storage credential` | `ServiceEndpoint`, `ServiceProjection` |
| asks for a bucket whose Garage name is over 63 characters, on `garage` | 034's words | `ServiceEndpoint`, `ServiceProjection`, now only when the backend is `garage` |
| asks for a bucket on `gcs` when `ANKKA_CLOUD_PROVIDER` is `none` | `object storage 'gcs' needs a cloud provider, and the installation names none` | `ServiceEndpoint` |

## Actions on a service

All `POST`, write access on the project, recorded in the service's history, `202` with the
service's status. Each is wrapped in `withHostname` as `restart` is.

| Route | Body | Effect | Refused when |
|---|---|---|---|
| `/services/{projectId}/{name}/storage-credential` | none | `StorageCredentialReissued`; `storageCredentialGeneration + 1` | the service has no bucket the platform made (`409 no storage credential to reissue`); a move is in progress (`409 storage is moving`) |
| `/services/{projectId}/{name}/storage/move` | `{ "writePauseBound": "10m" }`, optional | `StorageMoveRequested(generation + 1, bound)`; the bound is `10m` when absent; a duration of 1 minute to 24 hours | the installation's backend is not `gcs` or names no Garage (`409 the installation keeps new buckets in <store>; nothing to move to/from`); the service's bucket is not on Garage (`409 the bucket of '<name>' is in Google Cloud Storage`); a move is in progress (`409 storage is moving`) |
| `/services/{projectId}/{name}/storage/settings` | none | `StorageSettingsReapplied`; `objectStorageSettingsGeneration + 1`: the bucket takes the installation's current soft-delete window and wrapping key | the service's bucket is not on `gcs` (`409 settings are reapplied on Google Cloud Storage only`) |

"In progress" is a move whose observed state is `Requested`, `Copying`, `Pausing` or `Verifying`.
A move that is `Failed` may be requested again; one that is `Switched` is refused as above.

History kinds: `storage-credential-reissued`, `storage-moved`, `storage-settings-reapplied`.

## A project's location

| Route | Body | Effect |
|---|---|---|
| `PUT /projects/{id}/location` | `{ "location": "europe-west6" }` | `ProjectLocationSet`; `AnkkaProject.spec.bucketLocation`; the project's history `location-set` |
| `DELETE /projects/{id}/location` | — | the installation's default applies to new buckets |

Refused with `409` when the installation's backend is not `gcs`. A location is a non-empty
string in the installation's own words (044); the provider says what it means. Changing it moves
no bucket; the status shows each bucket's.

## `PlatformVariables`

```scala
val ObjectStoreBackend: String = "ANKKA_OBJECT_STORE_BACKEND"           // garage | gcs
val ObjectStorePrefix: String = "ANKKA_OBJECT_STORE_PREFIX"
val ObjectStoreSoftDeleteDays: String = "ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS"
```

All three in `PlatformOnly`: a descriptor may not set them. `ANKKA_S3_` stays declared as 034
left it.

## The status a member reads (`ServiceStatus`)

034's `objectStorage`, `bucket`, `bucketAddress`, and:

| Field | Values |
|---|---|
| `objectStore` | `garage`, `gcs` |
| `bucketLocation` | the location, on GCS |
| `softDeleteDays` | the window, on GCS |
| `storageMove` | `copying`, `write pause`, `verifying`, `moved`, `move failed` |

`detail` carries, prefixed `object storage:`, a waiting or failed request's words, and during a
move the move's detail (`write pause since 2026-10-08T10:00:00Z, at most 10m`). The `bucket`
and `bucketAddress` are the operator's values on GCS: the reported name and
`https://storage.googleapis.com/<bucket>`.

## `ankka services get`

```text
database         provisioned
object storage   provisioned
object store     gcs
bucket           ankka-casino-kyc-3f9a1c2e
bucket address   https://storage.googleapis.com/ankka-casino-kyc-3f9a1c2e
bucket location  europe-west6
soft delete      7 days
storage move     write pause since 10:00:00Z, at most 10m
```

Each line only when its field is present. `--output json` prints the fields as the wire has them.

## CLI commands

```text
ankka services storage reissue  <name> -p <project>
ankka services storage move     <name> -p <project> [--write-pause-bound 10m]
ankka services storage reapply-settings <name> -p <project>
ankka projects location set <location> -p <project>
ankka projects location clear -p <project>
```

Each prints the status (or the project) as `services restart` does, and the MCP tools' descriptions
name them.

## The console's service page

The `Object storage` fact gains the store beside the bucket's name, the location beneath it on
GCS, and a line for a move in progress or failed with its detail. Three actions beside
`Restart`: *Reissue storage credential*, *Move storage to Google Cloud Storage* (with a bound
field defaulting to 10m, shown only when the control plane says the installation can move), and
*Reapply bucket settings* (GCS only). The fake control plane answers each.

## What is recorded

`ServiceObserved` gains `objectStore`, `bucket`, `bucketAddress`, `storageMove`,
`storageMoveDetail`, `softDeleteDays`, each `Option` defaulting to `None`. `Service` holds the
generations and the move request. No key, no secret, no credential id.
