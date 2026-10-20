# Quickstart: validating the storage client

The runs that prove the feature, cheapest first. Each names what it shows and what a pass cannot
be mistaken for. Contracts are in [contracts/](contracts/); the data is in
[data-model.md](data-model.md).

## 0. Before anything: the spikes

- **S1** — `sbt 'runtime/testOnly *S3BucketSuite'` against `dxflrs/garage:v2.3.0` by testcontainers:
  a `PutObject` with `x-amz-content-sha256: UNSIGNED-PAYLOAD` and a `Content-Length` is accepted;
  a query-signed `GET` and `PUT` URL work from a plain `HttpClient`; a `ListObjectsV2` pages. Then
  the same three by hand against the prepared Google bucket with `ANKKA_GCS_*` (039's
  `GcsCompatibilitySuite` setup). If Garage refuses `UNSIGNED-PAYLOAD`, R3's fallback (hash through
  a temporary file) is built before anything above it.
- **S2** — on the local k3s overlay with the `garage` component's web listener: a bucket named
  `t.svc.photos` with website access on answers an object at `GET /` with `Host: t.svc.photos`
  on port 3902 with the kept `Content-Type`; an `HTTPRoute` with `URLRewrite` reaches it from the
  gateway at `https://storage.<base>/t.svc.photos/<name>`. If Garage does not match a dotted
  `Host`, R9 becomes a reader of the platform's own before anything is rendered.

## 1. Pure, offline (`sbt -Dankka.cluster.tests=off test`, about a minute per module)

| Suite | Shows |
|---|---|
| `ObjectRulesSuite` (sdk) | every row of `protocol/fixtures/storage/rules.json` accepted or refused with the fixture's words |
| `StorageAvailabilitySuite` (sdk) | `compileErrors`: an entity's command handler and a view cannot name `storage` |
| `StorageSettingsSuite` (runtime) | the backend defaults; the `ANKKA_S3_*` families parsed, `<NAME>` derived; `s3` without an endpoint and an unwritable directory stop the start naming the variable |
| `MemoryBucketSuite`, `DirectoryBucketSuite` (runtime) | the contract against both stores: put/get with content type, declared length short → failure and the old object kept, list pages and orders, stat, delete twice, early close |
| `SigV4Suite` (runtime) | the AWS test vectors for a signed header and a presigned URL |
| `DirectoryStorageEndpointSuite` (runtime) | tokens: valid, expired, wrong, public bucket unsigned, non-public unsigned → 403 |
| `StepScopeSuite` (runtime) | a workflow command handler calling `storage` is refused with the step's words |
| `StreamedBodySuite` (http, `HttpServer.at(127.0.0.1, 0)`) | a 2 GiB `putStream` through a handler into a counting sink with `-Xmx512m`; a `Streamed` response of the same; `413` above `max-stream-bytes`; strict routes unchanged |
| `ReservedPathSuite` (http) | an endpoint with prefix `_ankka` refused at `validate`; `/_ankka/storage` 404 with no directory store |
| `ObjectStorageDescriptorSuite`, `DescriptorSuite`, `DocumentationDescriptorsSuite` | the `buckets` fields, every refusal in the contract, defaults omitted on the wire, every `service.json` in `docs/` valid |
| `ServiceProjectionSuite`, `ServiceEntitySuite`, `EventCompatibilitySuite`, `StatusIngestSuite` | `buckets` projected; reissue and move gated on "any made bucket"; `ServiceObserved.buckets` defaults; `BucketReport` read from the status |
| `ReservedSecretNamesSuite` | a supplied bucket's credential cannot be named `*-storage` |
| `CrdSchemaSuite` | `spec.buckets.items` and `status.objectStorage.buckets.items` field for field, both ways |
| `ObjectStorageSuite`, `ObjectStorageRenderingSuite`, `RenderingGoldenSuite` | the plan per bucket and exposure; the variables per bucket; the public route's rewrite; `SetWebsiteAccess` only when it differs; `Kept` from the status; `golden/storage-client.txt` |
| `RenderingUnchangedSuite` | a service without `buckets` renders exactly what it rendered — **no repin** |
| `StorageCredentialSuite` | ensure, reissue, pause, resume on a list of buckets; the key named for the service with no unnamed bucket |
| `CloudRequestsSuite`, `ScriptedCloudProviderSuite` | `name` and `public` on the bucket request, `buckets` on the credential request, `publicUrl` answered; `Keys.parameters` extended |
| `MoverSuite` (storage-mover, two Garage containers) | parallel lists; a failure names the pair; the old single names accepted |
| `AnkkaTestKitStorageSuite` (testkit) | `kit.storage` seeds and reads; survives `restartService()`; dropped on `stop()`; two kits in one JVM apart; `declareBucket` |
| `StorageClientFeatures` (testkit, `GherkinSuite`) | `features/storage-client/{client,contexts,testkit,local}.feature` on the memory and directory stores, in-process; the scenarios that need a cluster are `ranElsewhere` |
| CLI `OutputSuite`, console unit tests | one line and one row per bucket, `kept` shown |

## 2. The store, offline (`sbt 'runtime/testOnly *S3BucketSuite' 'operator/testOnly *GarageStoreSuite'`)

S1's cases, and `GarageStoreSuite`'s new `setWebsite` case, as part of the suites from now on.

## 3. The polyglot SDKs (each end to end, as `CLAUDE.md` lists)

- Python: `uv run pytest -q` (rules fixture, `InMemoryBuckets`, `DirectoryBuckets`, SigV4 vectors,
  the `HasStorage` kinds, the workflow guard), `uv run mypy`, `uv run conformance` with the
  `storage.*` cases against the reference's memory store, and against Garage with
  `ANKKA_CONFORMANCE_STORAGE=garage`.
- TypeScript: `npm test` (the same), `npm run test:slow`, `npm run conformance`.
- Rust: `cargo test --workspace` (the native `HashMap` host, the rules fixture, `TooLarge`),
  `./conformance.sh` (the `storage` import through the sidecar's `ModuleTarget`),
  `wasm-objdump -j Import` shows `storage` only in a module that uses it.
- `sbt 'sidecar/testOnly *ConformanceSuite'` in-process, then with `-Dankka.conformance.target`
  at each reference; `ANKKA_CONFORMANCE_ONLY='*storage.*'` (leading wildcard).

## 4. k3s (`caffeinate -i sbt 'controlPlane/testOnly *StorageClientClusterFeatures'`, or `gh workflow run cluster -f suite=StorageClientClusterFeatures`)

`features/storage-client/buckets.feature` and `signed-urls.feature` on the local overlay with
the `garage` component's web listener: every bucket given, named bucket reached, unknown name
refused, a public object read from the host with `curl` and no signature, a private one
refused, a signed bucket's URL, exposure refusals, a supplied bucket against a second Garage the
suite starts, the credential refused across services, the status lines, deletion keeping,
`Kept` after un-naming, and the move with the scripted provider in its Garage-backed mode
(`ObjectStorageGcsClusterFeatures` extended for the move's bucket list). The Google-only
scenarios (a public bucket readable by anyone on Google, `publicUrl`) are `ranOutside` for
ankka-gcp's nightly suite, by name.

## 5. Documentation (`just docs-sync && just docs`)

`docs/build/object-storage.md` builds with every sample included from tested code; the
configuration table covers `ankka.storage`; the descriptor reference's `buckets` table; the
limitations page's new lines; the skills rendered.

## 6. By hand, on a laptop

```bash
ankka init listings --language scala && cd listings
docker compose up -d && sbt run                     # no ANKKA_S3_*: the directory store
curl -X PUT --data-binary @front.jpg -H 'Content-Type: image/jpeg' localhost:9000/photos/42/front
find .ankka/storage/photos -type f                  # objects/listings%2F42%2Ffront.jpg and meta/listings%2F42%2Ffront.jpg.json
curl -s localhost:9000/photos/42/front/url           # http://127.0.0.1:9000/_ankka/storage/photos/listings/42/front.jpg
curl -I "$(curl -s localhost:9000/photos/42/front/url)"   # 200 image/jpeg
```

Then `ankka services apply` the same service with the descriptor above on the local
installation and read `ankka services get listings -p shop`: five bucket lines, and the public
address answers from a browser with no signature.
