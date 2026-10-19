# Contract: the descriptor, the variables, and what a member reads

Decisions are in [research.md](../research.md) (R7, R8, R15). 034's and 039's contract holds for
the unnamed bucket and everything not named here.

## The descriptor

```json
{
  "name": "listings",
  "service": {
    "image": "registry.example.com/acme/listings:1.0.0",
    "provisionObjectStorage": true,
    "buckets": [
      { "name": "photos", "exposure": "public" },
      { "name": "exports", "exposure": "signed", "origins": ["https://app.example.com"] },
      { "name": "documents" },
      { "name": "archive", "supplied": {
          "endpoint": "https://s3.eu-west-2.amazonaws.com", "region": "eu-west-2",
          "bucket": "acme-archive", "credential": "archive-store" } }
    ]
  }
}
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `buckets` | list | `[]` | the service's named buckets, beside the unnamed one |
| `buckets[].name` | string | required | `[a-z0-9-]{1,32}`; the name the client reaches it by |
| `buckets[].exposure` | `private` \| `signed` \| `public` | `private` | how a bucket the platform makes is reached |
| `buckets[].origins` | list of string | `[]` | the origins a browser may send from; `signed` only |
| `buckets[].supplied` | object | absent | a bucket the developer made: where it is and the project secret holding its credential (entries `access-key`, `secret-key`) |

### Refusals, at apply

| The descriptor | Message | Where |
|---|---|---|
| repeats a name | `buckets: 'photos' is named twice` | `ServiceSpec.problems` |
| a name breaks the rule | `buckets: 'Photos' is not a bucket name: lower-case letters, digits and '-', 1 to 32 characters` | `ServiceSpec.problems` |
| `origins` on a bucket not `signed` | `buckets: 'photos' names origins but is not signed` | `ServiceSpec.problems` |
| `exposure` with `supplied` | `buckets: 'archive' is supplied; its exposure is the store's` | `ServiceSpec.problems` |
| `supplied` missing a field, or a credential that is not a project secret name | `buckets: 'archive' needs endpoint, region, bucket and credential` / `ProjectSecrets.nameProblems`'s words | `ServiceSpec.problems` |
| the Garage name `<project>.<service>.<name>` exceeds 63 characters, on `garage` | 034's words, with the bucket named | `ServiceEndpoint`, `ServiceProjection` |
| the credential's project secret has no `access-key` or `secret-key` entry | `buckets: 'archive': project secret 'archive-store' has no entry 'access-key'` | `ServiceEndpoint` |
| `provisionObjectStorage` with any `ANKKA_S3_` in `env` | unchanged | `ServiceSpec.problems` |

`buckets` without `provisionObjectStorage` is accepted. `objectStorageCredential: false` with a
made named bucket is accepted (the identity is granted on each); the client then refuses every
call, as the clarification says.

## The variables a service receives

| Variable | For | Value |
|---|---|---|
| `ANKKA_S3_ENDPOINT`, `_REGION`, `_ACCESS_KEY`, `_SECRET_KEY` | any made bucket | as 034; rendered for a service with only named buckets too |
| `ANKKA_S3_BUCKET` | the unnamed bucket | as 034; absent without `provisionObjectStorage` |
| `ANKKA_S3_PUBLIC_ENDPOINT` | any `signed` bucket or `exposeObjectStorage` | as 034 |
| `ANKKA_S3_BUCKET_<NAME>` | each made named bucket | its name in the store |
| `ANKKA_S3_EXPOSURE_<NAME>` | each made named bucket | `private`, `signed` or `public` |
| `ANKKA_S3_PUBLIC_URL_<NAME>` | each `public` bucket | `https://storage.<base>/<bucket>` on Garage; the provider's `publicUrl` on GCS |
| `ANKKA_S3_ENDPOINT_<NAME>`, `_REGION_<NAME>`, `_BUCKET_<NAME>`, `_ACCESS_KEY_<NAME>`, `_SECRET_KEY_<NAME>` | each supplied bucket | from the entry; the two keys by `secretKeyRef` on the project secret |

`<NAME>` is the name upper-cased with `-` as `_`. All go where 034 puts `ANKKA_S3_*`: the
developer's program in every hosting, never the sidecar or the proxy. On a laptop none is set and
`ANKKA_STORAGE_BACKEND` / `ANKKA_STORAGE_DIR` choose the directory store.

## What a member reads

`ankka services get listings -p shop` gains one line per named bucket after the unnamed
bucket's lines:

```text
bucket photos        public     shop.listings.photos      https://storage.example.com/shop.listings.photos
bucket exports       signed     shop.listings.exports     https://storage.example.com/shop.listings.exports
bucket documents     private    shop.listings.documents
bucket archive       supplied   acme-archive
bucket old-photos    kept       shop.listings.old-photos  (no longer named)
```

`ServiceStatus.buckets: [{ name, phase, store, bucket, exposure, publicAddress, kept, detail }]`
on the wire; the console's service page lists them under the bucket fact. `services storage
reissue` and `storage move` act on every made bucket and are refused when the service has none
(`409 no bucket the platform made`). History kinds are unchanged.

## The reserved path

`/_ankka/storage/<bucket>/<name>` on the service's HTTP port is served only by a service whose
store is a directory (a laptop). A deployed service answers 404 there, and a descriptor's
endpoint may not claim the `/_ankka` prefix.
