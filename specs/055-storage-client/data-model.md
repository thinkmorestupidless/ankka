# Data Model: A Storage Client in the SDKs

What exists, who writes it, and what each state means. Decisions are in
[research.md](research.md); the wire shapes and the API are in [contracts/](contracts/).
Everything 034, 039 and 044 defined stays as it was unless named here.

## In the SDK (values a service's code sees)

### Object name

| | |
|---|---|
| Rule | UTF-8, 1 to 1024 bytes; segments separated by `/`; no leading `/`; no empty segment; no segment `.` or `..` |
| Declared | `ObjectRules` in `sdk`, held to `protocol/fixtures/storage/rules.json`; the same rule in every SDK |
| Refused | before any request, `BadRequest`, naming the rule |

### StoredObject (a `get`)

| Field | Meaning |
|---|---|
| `contentType` | as kept; `application/octet-stream` when the store reports none |
| `length` | bytes |
| `lastKept` | when the object was last put |
| `body` | an `InputStream` the caller must close; closing early releases the connection |

### ObjectInfo (a `stat`) and ObjectEntry (a `list` element)

`name`, `length`, `lastKept`, and for `stat` also `contentType`. A listing is an iterator that
pages behind the caller's back, ordered as the store orders names.

### Bucket (a handle)

| Field | Meaning |
|---|---|
| `name` | `None` for the unnamed bucket, else the descriptor's name |
| `exposure` | `Private`, `Signed`, `Public`, or `Supplied` |
| operations | `put`, `putBytes`, `get`, `getBytes`, `delete`, `list`, `stat`; `signedUrl(name, Read \| Keep, span)` on `Signed`; `url(name)` on `Public` |

### ObjectStorage (the client)

`default: Bucket` (the unnamed bucket; refuses with `Unavailable` naming `provisionObjectStorage`
when there is none) and `bucket(name): Bucket` (refuses with `Unavailable` naming the buckets the
descriptor gives). `buckets: Vector[Bucket]`. `ObjectStorage.unavailable` refuses everything.

## In the runtime

### StorageSettings

| Key | Source | Meaning |
|---|---|---|
| `backend` | `ANKKA_STORAGE_BACKEND`, else `s3` when `ANKKA_S3_ENDPOINT` is set, else `directory` | which `Bucket` implementation stands behind every bucket |
| `directory` | `ANKKA_STORAGE_DIR`, else `.ankka/storage` | the directory store's root |
| `timeout` | `ankka.storage.timeout`, 30s | one request to the store |
| `maxSignedUrl`, `defaultSignedUrl` | 7d, 15m | the bound and the default span |
| unnamed bucket | `ANKKA_S3_{ENDPOINT,REGION,BUCKET,ACCESS_KEY,SECRET_KEY,PUBLIC_ENDPOINT}` | absent endpoint means no unnamed bucket on `s3` |
| named bucket, made | `ANKKA_S3_BUCKET_<NAME>`, `ANKKA_S3_EXPOSURE_<NAME>`, `ANKKA_S3_PUBLIC_URL_<NAME>` | shares the unnamed bucket's endpoint, region and key |
| named bucket, supplied | `ANKKA_S3_{ENDPOINT,REGION,BUCKET,ACCESS_KEY,SECRET_KEY}_<NAME>` | its own store and key; exposure `supplied` |

`<NAME>` is the bucket's name upper-cased with `-` as `_`. A made named bucket with no unnamed
bucket still finds its endpoint, region and key in the unnamed variables: the operator renders
them for any service with a made bucket.

### Directory store layout

```text
.ankka/storage/
├── default/                    # the unnamed bucket
│   └── listings/42/photos/front.jpg
│   └── listings/42/photos/front.jpg.meta.json   # {"contentType":"image/jpeg","lastKept":"…"}
└── photos/                     # a named bucket, by its descriptor name
```

A put writes `<name>.part` beside and renames it over `<name>` so a failed put leaves the old
object. The buckets a directory store has are the descriptor's when `service.json` is beside the
working directory, else any name asked for, as `private`.

### Memory store

A `TrieMap[(bucket, name), (contentType, lastKept, bytes)]` owned by the test kit and handed to
every service it starts.

## In the descriptor and the resource

### BucketSpec (descriptor `buckets[]`, resource `spec.buckets[]`)

| Field | Type | Rule |
|---|---|---|
| `name` | string | `[a-z0-9-]{1,32}`, unique in the list |
| `exposure` | `private` \| `signed` \| `public` | default `private`; not with `supplied` |
| `origins` | list of string | each an origin; only with `signed` |
| `supplied` | object | `endpoint`, `region`, `bucket`, `credential` (a project secret's name with entries `access-key` and `secret-key`) |

### BucketStatus (resource `status.objectStorage.buckets[]`, written by the operator)

| Field | Meaning |
|---|---|
| `name` | the descriptor's name |
| `phase` | `Waiting`, `Provisioned`, `Recovered`, `Supplied`, `Failed`, `Kept` |
| `store` | `garage` or `gcs`; absent for supplied |
| `bucket` | the bucket's name in the store (reported, as 039) |
| `exposure` | as the spec, or `supplied` |
| `publicAddress` | the signed address for `signed`, the public base for `public` |
| `kept` | `true` once the descriptor no longer names it; the operator's memory |
| `detail` | why, when `Waiting` or `Failed` |

The unnamed bucket's status block is unchanged. `Kept` is a phase the database's set does not
have; `CrdSchemaSuite`'s "phases equal to the database's" check is scoped to the unnamed block.

## In the installation (Garage)

### Named bucket

| | |
|---|---|
| Name | `<project>.<service>.<name>`, within 63 characters (`Buckets.named`) |
| Made by | the operator, `EnsureBucket(bucket, keyName)` |
| Key | the service's one key, `Buckets.keyName(project, service)`, allowed `read, write, owner` |
| Website access | on for `public`, off otherwise (`SetWebsiteAccess`) |
| CORS | from `origins` for `signed` |
| Route | `signed`: as 034, to the S3 port, `PathPrefix /<bucket>`; `public`: to the web port with `URLRewrite(hostname = bucket, replacePrefixMatch = "/")`; named `<service>-storage-<name>` |
| Removed | the route only, when the bucket is `Kept` or its exposure drops below what needs it |

### The web listener

`[s3_web] bind_addr = "[::]:3902"`; Service port `web`; NetworkPolicy admits the gateway's proxy
pods to 3902; `ANKKA_OBJECT_STORE_WEB_SERVICE = garage-system/garage:3902` on the operator.

## In Google Cloud (made by the cloud provider)

### Bucket request, amended

| Parameter | Meaning |
|---|---|
| `name` | the bucket's descriptor name; absent for the unnamed bucket |
| `public` | `true` for a public bucket |

Outputs gain `publicUrl` for a public bucket. A public bucket has public access prevention
`inherited` and `allUsers: roles/storage.objectViewer`; every other bucket keeps it enforced.
The request is named `<service>-bucket-<name>`.

### Bucket credential request, amended

`buckets` (comma-separated) replaces `bucket`: one identity granted on each, one HMAC key.

## In the control plane

### Journal

`ServiceObserved.buckets: Vector[BucketReport]` (defaulted empty; `EventCompatibilitySuite`), where
`BucketReport(name, phase, store, bucket, exposure, publicAddress, kept, detail)`.
`Service.buckets` mirrors it. No new event kind: `buckets` is spec, projected as the other fields
are, and the storage actions (`reissue`, `move`) are gated on "any made bucket" instead of
`provisionObjectStorage`.

### Wire (`ServiceStatus`)

`buckets: Vector[BucketReport]` beside `bucket`, `bucketAddress`, `objectStore`. The listing row
is unchanged.

## Protocol

### `storage` import (ABI 1.20), `StorageRequest` → `StorageReply`

| Field | Meaning |
|---|---|
| `bucket` | optional name; absent is the unnamed bucket |
| `op` | `PUT`, `GET`, `DELETE`, `LIST`, `STAT`, `SIGNED_URL`, `URL` |
| `name`, `prefix` | the object's name, or the prefix for `LIST` |
| `content_type`, `body` | for `PUT`; `body` at most 4,000,000 bytes |
| `mode`, `expires_seconds` | for `SIGNED_URL` |
| `continuation` | for `LIST` paging |

A reply is `object { content_type, body, length, last_kept }`, `info`, `entries { … , next }`,
`url`, `done`, or `error { code, message }` with `pb.ErrorCode`.
