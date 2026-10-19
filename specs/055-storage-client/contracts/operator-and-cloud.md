# Contract: the operator, the store and the cloud provider

What the operator renders and performs for named buckets, and what it asks of a provider.
Decisions are in [research.md](../research.md) (R8, R9, R10, R11).

## Settings (operator Deployment, from the `garage` component's patch)

| Variable | Value | New |
|---|---|---|
| `ANKKA_OBJECT_STORE_WEB_SERVICE` | `garage-system/garage:3902` | yes; absent means no public bucket on Garage (`Failed`, naming it) |
| the five of 034 | unchanged | |

## The `garage` component

- `config.yaml`: `[s3_web] bind_addr = "[::]:3902"`, no `root_domain`, `index = "index.html"`
  (Garage requires one; nothing serves it).
- `service.yaml`: port `web` 3902.
- `zero-trust.yaml`: the gateway's proxy pods admitted to 3902; no workload namespace is.
- `garage-replicated` lists the same.

## `ObjectStore` (the seam) gains

| Method | Garage admin call |
|---|---|
| `setWebsite(bucketId, enabled)` | `POST /v2/UpdateBucket?id=` with `websiteAccess { enabled, indexDocument: "index.html" }` |
| `allow(bucketId, keyId, …)` | unchanged, called per bucket |

`BucketInfo` gains `website: Boolean`.

## Actions (inert; `Fabric8Executor` performs them)

| Action | When | Does |
|---|---|---|
| `EnsureBucket(bucket, keyName)` | each made bucket, every pass | create if absent; allow `keyName` when not among the allowed keys |
| `EnsureStorageCredential(ns, secret, labels, keyName, buckets, generation)` | once per service | issue the key, allow on every bucket in the list, `create` the Secret |
| `ReissueStorageCredential(ns, secret, keyName, buckets, generation)` | generation raised | as 039, on every bucket |
| `SetBucketCors(bucket, origins)` | `signed` named bucket | as 034 |
| `SetWebsiteAccess(bucket, enabled)` | each made named bucket; `true` only for `public` | writes only when `BucketInfo.website` differs |
| `EnsureHttpRoute(route)` | `signed` or `public` named bucket | `signed`: 034's route named `<service>-storage-<name>`; `public`: the rewriting route below |
| `RemoveHttpRoute(ns, <service>-storage-<name>, uid)` | a bucket in the status that is `Kept`, or whose exposure no longer needs a route | nothing new is rendered for a service without named buckets |
| `PauseWrites(buckets, generation)`, `ResumeWrites(buckets, generation)` | a move | `DenyBucketKey` / `AllowBucketKey` on each |
| `EnsureMoveJob(job)` | a move | the mover with parallel bucket lists |

A service without `buckets` renders exactly what it rendered before: `RenderingUnchangedSuite`
is not repinned.

### The public route

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata: { name: listings-storage-photos, namespace: ankka-shop, ownerReferences: [<the AnkkaService>] }
spec:
  parentRefs: [<the installation's gateway>]
  hostnames: [storage.<base>]
  rules:
    - matches: [{ path: { type: PathPrefix, value: /shop.listings.photos/ } }]
      filters:
        - type: URLRewrite
          urlRewrite:
            hostname: shop.listings.photos
            path: { type: ReplacePrefixMatch, replacePrefixMatch: / }
      backendRefs: [{ name: garage, namespace: garage-system, port: 3902 }]
      timeouts: { request: 0s }
```

The `ReferenceGrant` 034 writes per project namespace covers it: it names the store's Service,
not a port.

## Observation and the plan

`observeObjectStorage(buckets: Vector[String], keyName)` answers one `ObjectStorageObservation`
per bucket, `keyAllowed` meaning *the service's key* is allowed. `ObjectStorage.decide` is called
per bucket with the bucket's exposure; a named bucket's plan feeds `BucketStatus`. The Deployment
is withheld while any made bucket is `Waiting` (the variables are not known), as for the unnamed
one. A `public` bucket on Garage without `ANKKA_OBJECT_STORE_WEB_SERVICE` is `Failed` naming the
setting.

## Cloud requests (amendments to 044's contract, `docs/platform/cloud-provider.md`)

| Request | Change |
|---|---|
| `bucket` | parameters gain `name` (the descriptor's bucket name; absent for the unnamed bucket) and `public` (`true`/`false`, default `false`); outputs gain `publicUrl` when `public`; a named bucket's request is `<service>-bucket-<name>` |
| `bucket-credential` | `bucket` becomes `buckets`, comma-separated; the identity is granted on each; one key |

A public bucket: public access prevention `inherited`, uniform bucket-level access on,
`allUsers: roles/storage.objectViewer`, versioning and soft delete as every bucket; `publicUrl`
is `https://storage.googleapis.com/<bucket>` unless the provider is configured with a content
delivery network's base, which is `ankka-gcp`'s setting and not this repository's. Every other
bucket keeps public access prevention enforced (039's FR-009, narrowed). The scripted provider
implements both amendments; `ankka-gcp` is told by the amendments and its nightly suite proves
them (`GherkinSuite.ranOutside`).

## The mover

`MOVER_SOURCE_BUCKETS` and `MOVER_TARGET_BUCKETS` (comma-separated, parallel; the unnamed bucket
first when present) replace `MOVER_SOURCE_BUCKET` / `MOVER_TARGET_BUCKET`; the endpoints, regions
and credentials are one per side. Copy and verify proceed pair by pair; the result names the
pair a failure was in. The old names stay accepted as a one-element list.

## The status

`status.objectStorage.buckets[]` as [data-model.md](../data-model.md). A bucket absent from the
spec and present in the status is written back as `Kept` with its last known store, bucket and
address, so the next pass can remove its route and withhold its variables; it is never deleted.
