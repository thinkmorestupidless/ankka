# Tasks: A Storage Client in the SDKs

**Input**: Design documents from `/specs/055-storage-client/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature. The scenarios are already written in `features/storage-client/`
(54 across seven files; eight are outlines, so more rows when run); quickstart says which suite runs
each file and what makes it red. Where a task says "bind", it means a `Given`/`When`/`Then` step in
the named `GherkinSuite` so the scenario runs; where it says "case", a `test(...)` named for the rule
it holds. A bound scenario must be red before the implementation task that makes it green: run it
once and read the failure.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a service keeps and reads objects through the client), US2 (the client is
  offered where a blocking call belongs and nowhere else), US3 (a browser reads and keeps objects
  through URLs the client signs), US4 (buckets named in the descriptor, each reached in its own way),
  US5 (the same service runs on a laptop and in a test), US6 (every SDK offers the client)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `SDK`/`SDKT` = `modules/sdk/src/{main,test}/scala/…/sdk`;
`RT`/`RTT` = `modules/runtime/src/{main,test}/scala/…/runtime`; `STO` = `RT/storage` (new directory);
`HTTP`/`HTTPT` = `modules/http/src/{main,test}/scala/…/http`; `AG` = `modules/agent/src/main/scala/…/agent`;
`TK`/`TKT` = `modules/testkit/src/{main,test}/scala/…/testkit`; `SC`/`SCT` =
`sidecar/src/{main,test}/scala/…/sidecar`; `PROTO` = `protocol/src/main/protobuf/ankka/protocol/v1`;
`PY` = `sdks/python`; `TS` = `sdks/typescript`; `RS` = `sdks/rust`; `API` =
`controlplane-api/src/main/scala/…/controlplane/api`; `APIT` = its test tree; `CRD` =
`crd/src/main/scala/…/crd`; `CP`/`CPT` = `controlplane/src/{main,test}/scala/…/controlplane`;
`OP`/`OPT` = `operator/src/{main,test}/scala/…/operator`; `CLI` = `cli/src/main/scala/…/cli`;
`MOVER` = `storage-mover/src/main/scala/…/mover`; `KUST` = `kustomization/components`; `CON` =
`console/package`; `DOCS` = `docs`; `SKILL` = `tools/docs/skill`; `FEAT` = `features/storage-client`.
"R*n*" is a section of `research.md`; a contract is named by its file under `contracts/`.

Work on branch `055-storage-client` in the worktree `.claude/worktrees/055-storage-client`, or in an
`055-storage-client-impl` worktree off it, as the project's habit is. Every `sbt` command takes
`-Dankka.cluster.tests=off` except the k3s tasks, which say so. Long suites run under
`caffeinate -i`. sbt's project ids are `core`, `sdk`, `runtime`, `http`, `agent`, `testkit`,
`sidecar`, `controlPlaneApi`, `crd`, `controlPlane`, `operator`, `cli`, `storageMover`. munit's `--`
filter needs a leading `*`. The protocol minor is 1.20 and the ABI 1.20: the branch is on 1.19;
renumber if anything merges first. The service in the features is `listings`, its buckets `photos`
(public), `exports` (signed), `documents` (private) and `archive` (supplied); the project is `shop`.

The glossary's words, which every identifier and message here follows: **storage client**, **named
bucket**, **exposure** (`private`, `signed`, `public`; never visibility or access level), **supplied
bucket**, **public bucket**, **public address**, **content type**, **prefix** (never folder),
**content delivery network**, **directory store**, and the unnamed bucket. The platform's own program
is "the sidecar" only in code and rule files, never in a feature step.

---

## Phase 1: Setup — the spikes, the rule, the signer, the settings, the suites bound red

**Purpose**: the two things research could not decide from the tree (S1, S2) decided by tests that
stay; the one rule every SDK reads; the signer nothing else in the tree has; the variables declared
once; and every scenario bound to a suite and red before a line of the client exists.

- [X] T001 [P] Write `protocol/fixtures/storage/rules.json` as contracts/sdk.md "The rules fixture"
  gives it: `objectNames` rows (at the limit of 1024 bytes accepted; 1025 refused "longer than the
  limit"; leading `/`; empty segment `a//b`; segments `.` and `..`; a multi-byte name whose byte
  length, not character length, crosses the limit; `a/b/c.jpg` accepted), `bucketNames` rows
  (`photos`, `a-1` accepted; `Photos`, `photos_`, 33 characters, empty refused with the descriptor's
  words from contracts/descriptor-and-status.md), and `signedUrl { maxSeconds: 604800, defaultSeconds: 900 }`,
  each refused row carrying the `why` every SDK must say. Copy it beside `secrets/rules.json` into
  the three SDKs with `PY/scripts/proto.py`, `npm run proto` in `TS` and `RS/scripts/proto.sh` (extend
  each script's fixture copy if it names `secrets` alone); `diff -r` the copies against `protocol/fixtures/storage`
- [X] T002 [P] In `CORE/PlatformVariables.scala` declare `ANKKA_STORAGE_BACKEND` and
  `ANKKA_STORAGE_DIR` as the developer's program's, in no routing list, with the one-line comment
  `ANKKA_S3_` carries (R15), and a pure `def bucketVariableSuffix(name: String): String` (upper-case,
  `-` to `_`) beside the `ANKKA_S3_` prefix so the operator and the runtime derive `<NAME>` from one
  function; standard library only, since the operator compiles this file. Cases in `CORET`:
  `bucketVariableSuffix("old-photos") == "OLD_PHOTOS"`, and the two names are neither platform-only
  nor runtime-only. `PlatformDeclarationSuite` in `CPT` stays green
- [X] T003 [P] Write `STO/SigV4.scala`: `SigV4.sign(request: HttpRequest.Builder, method, uri, headers,
  payloadHash, credentials, region, service = "s3", now)` adding `Authorization`, `x-amz-date` and
  `x-amz-content-sha256`, and `SigV4.presign(method, uri, credentials, region, expiresSeconds, now): URI`
  with `X-Amz-Algorithm`, `-Credential`, `-Date`, `-Expires`, `-SignedHeaders=host`, `-Signature`;
  canonical request, string to sign and signing key over `javax.crypto.Mac` and `MessageDigest`,
  path-style, URI-encoding each path segment and query value as S3 requires (R3). Cases in
  `RTT/storage/SigV4Suite.scala`: the AWS SigV4 test vectors for a signed `GET` with headers and
  for a presigned URL (the documented `get-vanilla-query` and the presigned `GET` example, their
  expected signatures copied in), and a space and a `+` in a key encoded as `%20` and `%2B`
- [X] T004 [P] Add `ankka.storage` to `modules/runtime/src/main/resources/reference.conf` exactly as
  contracts/runtime-and-protocol.md gives it (`backend`, `directory`, `timeout = 30s`,
  `max-signed-url = 7d`, `default-signed-url = 15m`, the `${?ANKKA_STORAGE_BACKEND}` and
  `${?ANKKA_STORAGE_DIR}` overrides). Write `STO/StorageSettings.scala`: `StorageSettings.from(config: Config, env: Map[String, String])`
  answering `backend: Backend` (`S3 | Directory | Memory | Unavailable`; `""` chooses `S3` when
  `ANKKA_S3_ENDPOINT` or a supplied bucket is set, `Directory` when run locally — formation not
  `bootstrap` — else `Unavailable`), `directory: Path`, the three durations, `unnamed: Option[S3Target]`
  (`endpoint, region, bucket, credential: Option[(accessKey, secretKey)], publicEndpoint: Option[String]`;
  absent endpoint means none; endpoint and bucket with no key means a target with no credential),
  and `named: Vector[NamedTarget]` read from `ANKKA_S3_BUCKET_<NAME>` with `ANKKA_S3_EXPOSURE_<NAME>`
  and `ANKKA_S3_PUBLIC_URL_<NAME>` sharing the unnamed target's endpoint, region and key, and from the
  five `ANKKA_S3_*_<NAME>` of a supplied bucket with exposure `supplied` (data-model "StorageSettings");
  `<NAME>` is undone with `PlatformVariables.bucketVariableSuffix` by scanning the `ANKKA_S3_BUCKET_`
  prefix. `s3` with no endpoint and no supplied bucket is a `Left` naming `ANKKA_S3_ENDPOINT`; an
  unknown `backend` word is a `Left` naming the three. Cases in `RTT/storage/StorageSettingsSuite.scala`:
  the defaults with an empty environment run locally are `Directory` at `.ankka/storage`, and
  under the `bootstrap` formation `Unavailable`; an endpoint alone
  chooses `S3`; `ANKKA_STORAGE_BACKEND=memory` wins; the unnamed family; `PHOTOS` public with a URL;
  `OLD_PHOTOS` undone to `old-photos`; a supplied `ARCHIVE` with its own endpoint and key; endpoint
  and bucket without keys is a target with no credential; the two refusals name their variables
- [X] T005 S1, the first spike, as the first three cases of `RTT/storage/S3BucketSuite.scala`:
  start `dxflrs/garage:v2.3.0` by testcontainers as `OPT/GarageStoreSuite.scala` does (copy its
  layout and key bootstrap; the suite is tagged so `-Dankka.cluster.tests=off` leaves it running,
  since it needs Docker only), make a bucket and a key through the admin API, then with
  `java.net.http.HttpClient` and T003 alone: a `PUT` with `x-amz-content-sha256: UNSIGNED-PAYLOAD`
  and `Content-Length` of a 3 MiB body is `200` and a signed `HEAD` answers that length and the
  `Content-Type` sent; a presigned `GET` and a presigned `PUT` (`X-Amz-Expires=60`) work from a
  client that adds no header; a `ListObjectsV2` with `max-keys=2` over five objects pages through
  `NextContinuationToken` in name order. Run it: if the `PUT` is refused, write the outcome in R3 and
  make T020's put hash through a temporary file first. Then the same three by hand against the
  prepared Google bucket with `ANKKA_GCS_*` as `CPT/GcsCompatibilitySuite.scala` is run, and record
  the result in research.md R3
- [X] T006 [P] The `garage` component's web listener (R9, contracts/operator-and-cloud.md): in
  `KUST/garage/config.yaml` add `[s3_web] bind_addr = "[::]:3902"`, `index = "index.html"`, and
  `root_domain = ".web.ankka.invalid"` (Garage requires one; R9), and rewrite the leading comment to say what the listener serves and that nothing is
  served without website access on the bucket; in `KUST/garage/service.yaml` the port `web` 3902; in
  `KUST/garage/zero-trust.yaml` a rule admitting the gateway's proxy pods (the selector the S3 rule
  uses for the gateway namespace) to 3902 and no workload namespace; in `KUST/garage/operator-patch.yaml`
  `ANKKA_OBJECT_STORE_WEB_SERVICE: garage-system/garage:3902`; `KUST/garage-replicated/config-patch.yaml`
  and its `kustomization.yaml` carry the same listener and port. `kubectl kustomize` of each
  component builds
- [X] T007 S2, the second spike, as a k3s case that stays: `CPT/GarageWebClusterSuite.scala` (in the control plane's tests, which see the runtime's signer) (new,
  reading `ankka.cluster.tests` like `OPT/OperatorClusterSuite.scala`, so `.github/cluster-suites.py`
  lists it): on the local overlay with T006 applied, make a bucket `t.svc.photos` with
  `websiteAccess { enabled: true, indexDocument: "index.html" }` through the admin API, put one
  object with `Content-Type: image/png` through the S3 port, then `GET /<name>` on port 3902 with
  `Host: t.svc.photos` from a pod in the store's namespace and assert `200`, the body and
  `Content-Type: image/png`; then apply an `HTTPRoute` written by hand as contracts/operator-and-cloud.md
  "The public route" gives it and read `https://storage.<base>/t.svc.photos/<name>` through the
  gateway from the host with `curl -k` and no credential, asserting the same. Run it with
  `caffeinate -i sbt 'operator/testOnly *GarageWebClusterSuite'`. If Garage does not match a dotted
  `Host`, stop and record it in R9 before T064; the public route is then a reader of the platform's
  own and the plan changes
- [ ] T008 Bind every in-process scenario red: `TKT/storage/StorageClientFeatures.scala` (new,
  modelled on `TKT/secrets/BackendFeatures.scala`), `extends GherkinSuite("../../features/storage-client")`
  with `LogCapturing`, steps for every `Given`/`When`/`Then` of `FEAT/client.feature`,
  `contexts.feature`, `testkit.feature` and `local.feature` written against a fixture service the
  suite boots twice per scenario where the step says "on every object store" (memory and directory),
  `ranElsewhere` naming `S3BucketSuite` for the `garage` row, `StorageClientClusterFeatures` for the
  `supplied` row and for `local.feature`'s "a deployed service serves no signed URL and no public
  address itself", and `ranOutside` for the `gcs` row (ankka-gcp's nightly suite, by name); and
  `CPT/StorageClientClusterFeatures.scala` (new, `munitIgnore` on `ankka.cluster.tests` as
  `CPT/ObjectStorageClusterFeatures.scala`, reusing its `ServicePods` helpers) over
  `FEAT/buckets.feature` and `signed-urls.feature`, `ranElsewhere` for the rows
  `StorageClientFeatures` runs and `ranOutside` for the two Google-only ones ("a browser reads an
  object in a public bucket without a signed URL" on `gcs`, `publicUrl`), each mapping saying which
  test holds it. Every step compiles against nothing yet and fails on its first `Then`. Run
  `sbt 'testkit/testOnly *StorageClientFeatures'`: it reports the scenario count the files hold and
  every one red; a count short of the files' is a step that matched nothing

---

## Phase 2: Foundational — the trait, the memory store, the service's `storage`, the kit's store, the fixture service

**Purpose**: what every story's test needs: a client a handler can hold, a store that needs no
Docker, the service carrying it as it carries `secrets`, and a kit that seeds it. Nothing here is a
story on its own.

- [X] T009 Write `SDK/ObjectStorage.scala` as contracts/sdk.md "Scala" gives it: `trait ObjectStorage`
  (`default`, `bucket(name)`, `buckets`), `trait Bucket` (the ten operations with the documented
  scaladoc on each; `signedUrl`'s span defaulting to `ObjectRules.DefaultSignedUrl`), `enum BucketExposure
  { Private, Signed, Public, Supplied }` with a string `JsonValueCodec` in its companion (the fieldless
  enum trap in CLAUDE.md), `enum SignedAccess { Read, Keep }`, `StoredObject` (`AutoCloseable`, closing
  the body), `ObjectInfo`, `ObjectEntry`, `object ObjectRules` (`nameProblem`, `prefixProblem` — a
  prefix may be empty or end in `/` and follows the name rule otherwise — `MaxNameBytes = 1024`,
  `MaxSignedUrl = 7.days`, `DefaultSignedUrl = 15.minutes`, `bucketNameProblem` with the descriptor's
  words) and `ObjectStorage.unavailable`, every call of which throws `CommandError(Unavailable)`
  naming `provisionObjectStorage`, with `buckets` empty. No Pekko, no I/O. Cases in
  `SDKT/ObjectRulesSuite.scala` (modelled on `SDKT/SecretRulesSuite.scala`): every row of
  `protocol/fixtures/storage/rules.json` accepted or refused with the fixture's `why` contained in
  the message, read from the file so a fixture row added later is a case added; `unavailable`'s code
  and message
- [X] T010 [P] Write `STO/MemoryBucket.scala`: `MemoryObjectStorage(buckets: Map[String, BucketExposure], unnamed: Boolean, publicBase: String => String)`
  over one `TrieMap[(String, String), Stored(contentType, lastKept, bytes)]`, each `MemoryBucket`
  implementing `Bucket` whole: `put` reads exactly `length` bytes and fails with `Internal` naming the
  shortfall leaving the old entry; `get`/`stat` answer `None`; `list` orders by name and filters by
  prefix; `delete` of nothing succeeds; `signedUrl` on a `Signed` bucket answers a computed
  `http://memory/<bucket>/<name>?access=…&expires=…` and `url` on a `Public` one
  `publicBase(bucket) + "/" + name`, each refused `BadRequest` naming the bucket's exposure
  otherwise; `bucket(name)` unknown is `Unavailable` naming the known names; `default` with
  `unnamed = false` is `Unavailable` naming `provisionObjectStorage`; the name and prefix rules
  checked before any lookup. `def clear()` and `def seed(bucket, name, contentType, bytes)` for the
  kit. Cases in `RTT/storage/MemoryBucketSuite.scala` through a shared `BucketContract` trait
  (`RTT/storage/BucketContract.scala`, new) that T020 and T077 reuse: put and get with the content
  type, declared length short leaves the old object, list pages and orders under a prefix and under
  an empty prefix, stat, delete twice, early close of a `get` body, every refusal's code and the
  word it names
- [X] T011 Write `STO/ObjectStorages.scala`: `ObjectStorages.build(settings: StorageSettings, bucketFactory): ObjectStorage`,
  the one place a backend is chosen: `Memory` is the kit's and never built here (a `Left` naming
  `ServiceBuilder.withStorage`); `Directory` builds T077's (until then a `Left` saying so);
  `S3` builds one `S3Bucket` per target from T020 (until then the same). The named map: the
  unnamed target as `default`, each `NamedTarget` under its name with its exposure; a target with no
  credential is a `Bucket` whose every call is `Unavailable` naming `objectStorageCredential`; a
  `signedUrl` on the unnamed bucket without `publicEndpoint` is `BadRequest` naming
  `exposeObjectStorage`. Write `STO/Refusals.scala` holding the messages once (unknown name lists
  the known; no bucket names `provisionObjectStorage`; no credential; exposure refusals; the bound),
  so the three stores and the directory endpoint say the same words. Case in
  `RTT/storage/ObjectStoragesSuite.scala`: the no-credential bucket's refusal; an unknown name's
  message lists `photos, exports`; `Memory` refused by name
- [X] T012 In `RT/Ankka.scala` beside `SecretStores.build` (`:324`): read `StorageSettings.from`,
  build `ObjectStorages.build` into `val storage: ObjectStorage`, a `Left` stopping the start through
  `StartRefusal` naming the variable or directory; `AnkkaService` gains `val storage: ObjectStorage`
  beside `secrets` (`:567`), `ServiceBuilder` gains `withStorage(store: ObjectStorage)`; when it was
  given, the settings are not read and no store is built (the kit's hook, R13), and `storage` is passed wherever `secrets` is passed into
  `initWorkflow` (`:366`), `ProjectionRuntime` (`:379`), `TimerRuntime`, `AgentRuntime` and
  `HttpServer` (`:415`), each taking it as a parameter and holding it unused until Phase 4 wires the
  contexts. In `HTTP/EndpointClients.scala` add `storage: ObjectStorage = ObjectStorage.unavailable`
  beside `secrets` (`:38`) with the scaladoc line, and `HTTP/HttpServer.scala` builds it from the
  service's (`:125–133` region). Case in `RTT/StartRefusalSuite.scala`: a `backend = s3` with no
  endpoint refuses the start naming `ANKKA_S3_ENDPOINT`
- [X] T013 [P] Write `STO/LocalBuckets.scala`: a small reader of `service.json` beside the working
  directory (or `ANKKA_SERVICE_JSON` if that override exists; else the working directory only)
  answering `Option[(unnamed: Boolean, buckets: Map[String, BucketExposure])]` from
  `service.provisionObjectStorage` and `service.buckets[]` (`name`, `exposure` defaulting to
  `private`, `supplied` as `Supplied`) with jsoniter and no `controlplane-api` dependency (R12), used
  by the directory store and the kit. Case in `RTT/storage/LocalBucketsSuite.scala`: the descriptor
  from contracts/descriptor-and-status.md read as four buckets and the unnamed one; a missing file
  is `None`; a file without `buckets` is the unnamed bucket alone
- [X] T014 The kit's store (R13): `TK/StorageBackendChoice.scala` (new) with `Memory(unnamed: Boolean = true, buckets: Map[String, BucketExposure] = Map.empty)`
  (explicit buckets win over `service.json`; holding a `MemoryObjectStorage` built from `LocalBuckets` when a `service.json` is found, else the unnamed
  bucket alone, every call to an undeclared name refused as the runtime's is) and `FromEnvironment`
  (the runtime decides from the variables); `TK/AnkkaTestKit.scala` gains `storage: StorageBackendChoice = StorageBackendChoice.Memory()`
  on `start` beside `secretBackend` (`:56`), `def storage: ObjectStorage` answering the `Memory`
  store (or the service's when `FromEnvironment`), `def declareBucket(name: String, exposure: BucketExposure): Unit`
  adding to the memory store's map before or after start, and the hook: `configure` (`:33`)
  composes `withStorage(store)` so `restartService()` re-hands the same store and `stop()` calls
  `clear()`; two kits in one JVM hold two stores. `TK/InMemoryObjectStorage.scala` (new): a public
  `def apply(buckets: (String, BucketExposure)*): MemoryObjectStorage` for the unit kits. Cases in
  `TKT/storage/AnkkaTestKitStorageSuite.scala`: `kit.storage` seeds and the service reads
  (through T015's endpoint); survives `restartService()`; empty after `stop()` and a new kit; two
  kits apart; `declareBucket("photos", Public)` then `bucket("photos").url(...)` answers; an
  undeclared name refused naming the declared ones
- [X] T015 Write the fixture service every in-process scenario drives, `TKT/storage/StorageComponents.scala`
  (new; its `// docs:start`/`// docs:end` regions are the docs' samples, R17): a `Listing` event
  sourced entity keeping an object's *name* (`PhotoRecorded(name)`), a `PhotosEndpoint` with
  `putStream`/`get` routes (strict until T023, then streamed) that keep through
  `clients.storage.default.put`, read back with `Streamed`, list, stat, delete, `signedUrl`,
  `url`, and a `bucket(<name>)` route; a `ThumbnailWorkflow` keeping in a step and a command
  handler that tries to; an `UploadConsumer`, a `SweepTimedAction` and a `CaptionAgent` each
  touching `context.storage`; and the compile-time negatives in a separate
  `TKT/storage/StorageAvailabilityComponents.scala`. Then make every step of T008's
  `StorageClientFeatures` drive it. `sbt 'testkit/testOnly *StorageClientFeatures'`: `client.feature`
  and `testkit.feature` scenarios that need only the memory store and the strict routes are green;
  everything else still red and named

**Checkpoint**: a service holds `storage`, a test seeds it; the trait is final.

---

## Phase 3: User Story 1 — A service keeps and reads objects through the client (Priority: P1) 🎯 MVP

**Goal**: put, get, list, stat and delete against the platform's store over the JDK's HTTP client,
and an object larger than the heap through an endpoint.

**Independent Test**: `S3BucketSuite` against Garage; `StreamedBodySuite` with a 2 GiB body under
`-Xmx512m`; `client.feature` green in `StorageClientFeatures` on the memory store; the `garage` row
of "same on every object store" in the cluster suite.

- [X] T016 [US1] Bind the rest of `FEAT/client.feature` in `TKT/storage/StorageClientFeatures.scala`:
  "an object larger than an instance's memory passes through the storage client" through the
  endpoint with a body of `Runtime.maxMemory * 2` from a counting `InputStream`, asserted by a
  counting sink, and the suite failing by name when `maxMemory` is above 1 GiB (so a big heap
  cannot make it pass); "a storage client call is refused with a reason when the object store cannot
  be reached" against `ObjectStorages.build` with an endpoint on a closed loopback port, asserting
  `Unavailable` and the endpoint in the message; "a keep that fails part way leaves no partial
  object" with a stream that ends at half its declared length, then `get` answering the old object.
  Red
  *Done as:* the store in memory holds what it keeps and the test kit's JVM has a quarter of the
  machine's memory, so "larger than an instance's memory" is `StreamedBodySuite`'s last case (http,
  512 MiB heap): 2 GiB kept through `putStream` into the directory store and read back as `Streamed`.
  `StorageClientFeatures` names it in `ranElsewhere`. The in-process features run one suite per file.
- [X] T017 [P] [US1] Write `STO/S3Bucket.scala` (R3, R5): `S3Bucket(target: S3Target, bucket: String, name: Option[String], exposure, settings, client: HttpClient, clock)`
  implementing `Bucket`: `put` as `PUT /<bucket>/<key>` with `Content-Type`, `Content-Length`,
  `x-amz-content-sha256: UNSIGNED-PAYLOAD` (or the temporary-file hash if T005 said so) and
  `BodyPublishers.ofInputStream` wrapped to count and fail short of `length`; `get` as `GET` with
  `BodyHandlers.ofInputStream`, `None` on 404, `StoredObject` whose `close` closes the stream and
  cancels the response's subscription so the connection is released (the `runtime.md` trap);
  `stat` as `HEAD`; `delete` as `DELETE` (204 and 404 both succeed); `list(prefix)` as
  `ListObjectsV2` with `list-type=2`, `prefix`, `continuation-token`, parsed with the JDK's
  `XMLStreamReader`, an `Iterator` fetching the next page when the current is drained; every request
  signed by T003 for the target's region, path-style, with the request timeout from settings;
  `signedUrl` and `url` per T011's rules against `publicEndpoint` and the named bucket's public base;
  status 403 and 5xx and `IOException` mapped to `Unavailable` naming the endpoint, a body the parser
  cannot read to `Internal`. Google's `<Generation>` is never requested. Then `ObjectStorages.build`
  builds it for `S3`
- [X] T018 [US1] Finish `RTT/storage/S3BucketSuite.scala` with T010's `BucketContract` against Garage
  plus: a 20 MiB put from a counting stream reads the stream once (the counter equals the length);
  a listing of 1,003 objects pages twice, Garage answering at most 1,000 keys a page (count the requests through a counting `HttpClient`
  wrapper); a `get` body closed after one byte lets the next request proceed within the timeout;
  a 403 from a wrong key is `Unavailable` naming the endpoint. Then the `garage` row of
  `client.feature`'s "same on every object store" is this suite's, named in `ranElsewhere`
- [X] T019 [P] [US1] Streamed request bodies in `ankka-http` (R4): in `HTTP/codecs.scala` or
  `HTTP/HttpEndpoint.scala` add `final case class StreamedBody(contentType: String, length: Option[Long], body: InputStream)`
  and on `HttpEndpoint` `postStream[Out: ToResponse](path)(handler: StreamedBody => Out)` and
  `putStream`, a route kind `needsBody = false, streams = true`; in `HTTP/HttpServer.scala`
  `Router.dispatch` (`:683`, `:746`) skips `toStrict` for a stream route and hands
  `entity.dataBytes.runWith(StreamConverters.asInputStream(bodyTimeout))` on the handler's virtual
  thread, with `withSizeLimit(maxStreamBytes)` so a larger body is `413`; `ankka.http.max-stream-bytes = 5g`
  in `modules/http/src/main/resources/reference.conf` read beside the strict limit. Nothing changes for
  strict routes
- [X] T020 [P] [US1] Streamed responses: in `HTTP/responses.scala` add
  `final case class Streamed(contentType: String, length: Option[Long], body: InputStream)` with a
  `ToResponse` given marshalling `HttpEntity.Default(contentType, length, StreamConverters.fromInputStream(() => body))`
  when the length is known, else `HttpEntity.Chunked`, closing the stream when the response completes
  or the client goes away (`watchTermination`); `EncodedResponse` (`HTTP/HttpEndpoint.scala:133`)
  gains the streamed case beside strict. The sidecar's `Router` (`SC/Main.scala`) serves a process's
  routes strict as before: a stream route is Scala's, said in T106
- [X] T021 [US1] Cases in `HTTPT/StreamedBodySuite.scala` (new, `HttpServer.at("127.0.0.1", 0)`):
  a 2 GiB `putStream` from a counting `InputStream` into a counting sink, the suite refusing to
  run when `Runtime.getRuntime.maxMemory > 1 GiB` and naming `http / Test / javaOptions += "-Xmx512m"`
  (add that option in `build.sbt` for the `http` project's tests, forwarded as every `-D` must be);
  a `Streamed` response of the same size read by the JDK client as a stream and counted; a body
  above `max-stream-bytes` (set to `1m` in the suite's config) is `413` and the handler never runs;
  a strict route beside a stream route behaves as before; an aborted client closes the handler's
  stream (the counting sink sees `close`). Then T015's `PhotosEndpoint` uses `putStream` and
  `Streamed`, and `client.feature`'s "larger than an instance's memory" is green
- [X] T022 [US1] `sbt -Dankka.cluster.tests=off 'runtime/testOnly *storage.*' 'http/testOnly *StreamedBodySuite' 'testkit/testOnly *StorageClientFeatures'`:
  every `client.feature` scenario green except the `gcs` and `supplied` rows, named in the maps;
  break `S3Bucket.put`'s `Content-Length` once and watch `S3BucketSuite` go red

**Checkpoint**: the MVP. A service keeps and reads objects against Garage and in a test, and a 2 GiB
object passes an endpoint with a 512 MiB heap.

---

## Phase 4: User Story 2 — The client is offered where a blocking call belongs and nowhere else (Priority: P1)

**Goal**: `storage` on every context that may block, absent from the rest, refused in a workflow's
command handler, and a reason for a service with no bucket or no credential.

**Independent Test**: `StorageAvailabilitySuite`'s `compileErrors`; `StepScopeSuite`;
`contexts.feature` green in `StorageClientFeatures`.

- [X] T023 [US2] In `SDK/contexts.scala` add `def storage: ObjectStorage` beside `secrets` on
  `WorkflowContext` (with the scaladoc "steps only; a command handler's call is refused"),
  `ConsumerContext` and whatever context `GraphConsumer` reads; in `SDK/TimedAction.scala` on
  `TimedActionContext`; in `AG/Agent.scala` on `AgentContext`, `AG/autonomous/AutonomousAgent.scala`
  on `AutonomousAgentContext`, `AG/blueprint/RunContext.scala` or wherever `BlueprintContext`
  declares `secrets`; and nowhere on `EntityContext`, `CommandContext`, `ViewComponentContext` or
  `KeyedView`'s. Every in-tree implementation of these contexts gets the field (the compiler lists
  them)
- [X] T024 [US2] In `RT/StepScope.scala` add `def stepsOnly(underlying: ObjectStorage): ObjectStorage`
  beside the secret store's (`:31`): every `Bucket` operation outside a step is `BadRequest` with the
  step's words (the same message the secret store's refusal uses, with `storage` in place of
  `secrets`), `signedUrl` and `url` included. Wire it: `RT/WorkflowHost.scala` builds the context's
  `storage` through it; `RT/ProjectionRuntime.scala` (`:231` region) and `RT/TopicHandlers.scala` hand
  the service's `storage` to consumer contexts; `RT/TimerSweeper.scala` and `RT/TimerRuntime.scala` to
  the timed action's; `AG/AgentRuntime.scala` (`:208`, `:262`, `:435`, `:548`), `AG/autonomous/AutonomousAgentHost.scala`
  and `AG/blueprint/BlueprintRegistry.scala` to the agents'. Case in `RTT/StepScopeSuite.scala`
  (extend or create beside the secret case): a `put` outside a step refused with the step's words; inside, passed through
- [X] T025 [P] [US2] The unit kits: `TK/ConsumerTestKit.scala` (`:31`, `:126`) and every other unit
  kit that takes `secrets: InMemorySecretStore = InMemorySecretStore()` (grep `InMemorySecretStore()`
  under `TK/`: the endpoint, workflow, timed action, agent and autonomous agent kits) gain
  `storage: ObjectStorage = InMemoryObjectStorage()` handed to the context the same way; the kit
  exposes it as `kit.storage`. Case in `TKT/ConsumerTestKitSuite.scala`: a consumer's put is read
  back from `kit.storage`
- [X] T026 [P] [US2] Write `SDKT/StorageAvailabilitySuite.scala` modelled on
  `SDKT/SecretStoreAvailabilitySuite.scala`: `compileErrors` proving an event sourced entity's
  command handler, a key value entity's, a view's and a keyed view's cannot name `storage`, each
  assertion containing `storage is not a member`; and that an endpoint's `clients.storage`, a
  workflow step's, a consumer's, a timed action's and an agent's compile. Each `compileErrors` must be
  non-empty for the negatives: assert on the text, never on emptiness
- [X] T027 [US2] Bind `FEAT/contexts.feature` in `StorageClientFeatures`: the outline rows over the
  five kinds through T015's components; "an entity and a view have no storage client" delegates to
  `StorageAvailabilitySuite`'s assertions, named in `ranElsewhere`; "a workflow keeps an object in a
  step and not in a command" through `ThumbnailWorkflow`; "a service that has no bucket" by starting
  a kit with `StorageBackendChoice.Memory(unnamed = false)` and no declared bucket, asserting the
  refusal names `provisionObjectStorage`; "reaches its bucket as its workload identity" by starting
  with `FromEnvironment` and `ANKKA_S3_ENDPOINT` + `ANKKA_S3_BUCKET` and no key in the kit's
  environment overlay, asserting `objectStorageCredential` in the refusal; "an entity keeps an
  object's name and the bucket keeps the object" through `Listing`. Green:
  `sbt 'testkit/testOnly *StorageClientFeatures -- *contexts*'`

**Checkpoint**: US1 and US2 hold; the client is final on every Scala context.

---

## Phase 5: User Story 3 — A browser reads and keeps objects through URLs the client signs (Priority: P2)

**Goal**: `signedUrl` for `Read` and `Keep`, against `ANKKA_S3_PUBLIC_ENDPOINT`, bounded, by
computation alone, refused with `exposeObjectStorage` when the bucket is not reachable.

**Independent Test**: `signed-urls.feature` on k3s with `exposeObjectStorage`, read and keep from the
host through the gateway; the refusal without.

- [X] T028 [US3] Cases in `RTT/storage/S3BucketSuite.scala`: `signedUrl(name, Read, 1.minute)`
  against a `publicEndpoint` that differs from `endpoint` answers a URL on the public host whose
  presigned `GET` works against Garage when the suite rewrites the host back (proving the signature
  is over the public host); `Keep` answers a `PUT` URL a plain client keeps through with the
  `Content-Type` it sends; a span above 7 days is `BadRequest` naming the bound; the default span is
  900 seconds in `X-Amz-Expires`; a target with no `publicEndpoint` is `BadRequest` naming
  `exposeObjectStorage`; "made without a request": the counting client saw zero requests
- [ ] T029 [US3] Bind `FEAT/signed-urls.feature` in `CPT/StorageClientClusterFeatures.scala`: deploy
  T015's components as a service image (the suite builds it as `ObjectStorageClusterFeatures` builds
  its probe, or extends `samples/shopping-cart` with a storage endpoint if that is what the k3s
  suites deploy — read `CPT/ServicePods.scala` and choose the one already built, saying which in the
  suite's scaladoc) with `provisionObjectStorage` and `exposeObjectStorage`; the service signs a read
  URL and a keep URL through its endpoint; the host reads and keeps through the gateway with `curl -k`
  and no credential; a URL signed for 2 seconds is `403` after 3; a service deployed without
  `exposeObjectStorage` answers the refusal from its endpoint naming `exposeObjectStorage`; "made
  without a request" is `ranElsewhere` to T028. Run with
  `caffeinate -i sbt 'controlPlane/testOnly *StorageClientClusterFeatures -- *signed*'` or
  `gh workflow run cluster --ref 055-storage-client -f suite=StorageClientClusterFeatures`

**Checkpoint**: signed URLs on the unnamed bucket are the platform's, not the service's arithmetic.

---

## Phase 6: User Story 4 — Buckets named in the descriptor, each reached in its own way (Priority: P2)

**Goal**: `buckets` in the descriptor, projected, declared, made, granted, routed, reported and
reached by name; a public bucket readable by anyone; a supplied bucket the platform touches not at
all; the move and the status covering every bucket.

**Independent Test**: the descriptor refusals offline; `buckets.feature` on k3s against Garage; the
move in `ObjectStorageGcsClusterFeatures`; the Google-only rows `ranOutside`.

### 6a. The descriptor, the resource, the control plane, the client by name (offline)

- [X] T030 [US4] In `API/descriptors.scala` add `Exposure` (`private | signed | public`, string
  codec in its companion; named apart from the client's four-valued `BucketExposure`), `SuppliedBucket(endpoint, region, bucket, credential)`,
  `BucketSpec(name, exposure: BucketExposure = Private, origins: Vector[String] = Vector.empty, supplied: Option[SuppliedBucket] = None)`
  and `ServiceSpec.buckets: Vector[BucketSpec] = Vector.empty` beside `provisionObjectStorage`
  (`:270`), with scaladoc from contracts/descriptor-and-status.md; `ServiceSpec.problems` (`:473`
  region) refuses each row of that contract's "Refusals, at apply" table with its exact message
  (repeated name, malformed name through the same rule `ObjectRules.bucketNameProblem` states — copy
  the rule, since `controlplane-api` sees `core` only, and pin both to the fixture in T001's
  `bucketNames`; `origins` on a bucket not `signed`; `exposure` with `supplied`; `supplied` missing a
  field; a credential failing `ProjectSecrets.nameProblems`; any `ANKKA_S3_` entry in `env` when a
  bucket is named); `buckets` without
  `provisionObjectStorage` accepted; `ServiceStatus` (`:1006` region) gains
  `buckets: Vector[BucketReport] = Vector.empty` with
  `BucketReport(name, phase, store: Option[String], bucket: Option[String], exposure, publicAddress: Option[String], kept: Boolean, detail: Option[String])`.
  Cases in `APIT/ObjectStorageDescriptorSuite.scala`: every refusal by message; the four-bucket
  descriptor accepted; defaults omitted on the wire (`DescriptorSuite`'s round-trip style); a
  `BucketReport` round-trips; `DocumentationDescriptorsSuite` still reads every `service.json` under
  `DOCS/` (T106 adds one with buckets)
- [X] T031 [P] [US4] In `CRD/AnkkaService.scala` add `BucketSpec` and `SuppliedBucket` to
  `AnkkaServiceSpec` as `buckets: List[BucketSpec] = Nil` (`:185` region) and
  `BucketStatus(name, phase, store, bucket, exposure, publicAddress, kept: Boolean = false, detail)`
  as `ObjectStorageStatus.buckets: List[BucketStatus] = Nil` (`:320`), phases `Waiting | Provisioned | Recovered | Supplied | Failed | Kept`
  as strings; in `CRD/Buckets.scala` add `def named(projectId, serviceName, bucket): String = s"$projectId.$serviceName.$bucket"`,
  `def keyName(projectId, serviceName): String` (the existing `name`, now documented as the
  service's key name, R8) and `def problems(projectId, serviceName, bucket)` applying the 63-character
  rule to the named form with the bucket named in the message. Declare both in
  `KUST/crd/ankkaservice.yaml` under `spec.buckets.items` and `status.objectStorage.buckets.items`,
  field for field with descriptions. Cases in `CRD/BucketsSuite.scala` (named form, the 63-char
  refusal naming the bucket) and `CRD/AnkkaServiceCodecSuite.scala` (a resource with buckets and a
  status with `Kept` round-trips; an old resource without `buckets` decodes to `Nil`); extend
  `OPT/CrdSchemaSuite.scala` so the nested `items` of both lists are compared both ways, and scope its
  "phases equal to the database's" check to the unnamed block so `Kept` is not a finding
- [X] T032 [US4] Control plane projection and journal: `CP/deploy/ServiceProjection.scala` projects
  `buckets` into the resource field for field; `CP/domain/events.scala` `ServiceObserved` (`:491`)
  gains `buckets: Vector[BucketReport] = Vector.empty` and `CP/domain/model.scala` `Service.buckets`;
  `CP/deploy/StatusIngest.scala` (`:127`) reads `status.objectStorage.buckets` into `BucketReport`s;
  `CP/application/ServiceEntity.scala` gates `reissue` and `move` on "any made bucket" (unnamed or
  named and not supplied) with the message `no bucket the platform made` (409); `CP/api/ServiceEndpoint.scala`
  checks a supplied bucket's credential names a project secret with entries `access-key` and
  `secret-key` by name through the project's secrets, never by value, with the contract's message,
  and applies `Buckets.problems` for each named bucket on `garage`; `ServiceStatus.buckets` is
  served. Cases: `CPT/ServiceProjectionSuite.scala` (four buckets projected; a service with none
  projects `Nil` and the resource is byte-identical to before); `CPT/EventCompatibilitySuite.scala`
  (an old `ServiceObserved` decodes with empty `buckets`); `CPT/StatusIngestSuite.scala` (a status
  with `Kept` read); `CPT/ServiceEntitySuite.scala` (reissue refused with no made bucket, allowed
  with only a named one); `CPT/ReservedSecretNamesSuite.scala` (a supplied credential named
  `*-storage` refused as the reserved names are); an endpoint case for the missing-entry message
- [X] T033 [P] [US4] `CLI/Output.scala` (`:622` region) prints one `bucket <name> <exposure|supplied|kept> <bucket> [<address>] [(no longer named)]`
  line per `BucketReport` after the unnamed bucket's lines as contracts/descriptor-and-status.md
  "What a member reads" shows; `CLI/ControlPlaneClient.scala` decodes `buckets`; `CLI/mcp/AnkkaTools.scala`'s
  `get_service` includes them. Case in the CLI's `OutputSuite`: the five lines from the contract's
  example, `kept` with its note, `services get` for a service without buckets unchanged
- [X] T034 [P] [US4] Console: `CON/src/client/schemas.ts` (`:228` region) adds `buckets` as an
  optional array of the report; `CON/src/routes/service.tsx` lists them under the bucket fact as a
  small table (name, exposure, store bucket, address as a link when present, "kept, no longer
  named"); `CON/src/testing/fake-control-plane.ts` and the fixtures under
  `CON/fixtures/control-plane/` carry a service with four buckets and one kept; a unit test for the
  rows and a Playwright assertion in the service page's spec. `just test-console`
- [X] T035 [US4] The client by name (FR-011): T017's `S3Bucket.url` for a `Public` named bucket is
  `publicBase + "/" + name` with the name's segments URI-encoded, no request; T011's
  `ObjectStorages.build` maps every `NamedTarget` (made ones over the unnamed target's endpoint and
  key, supplied ones over their own); the refusals by exposure name the bucket's exposure
  (`photos is public; signedUrl is for a signed bucket`). Cases in `RTT/storage/ObjectStoragesSuite.scala`:
  a settings map with `PHOTOS` public, `EXPORTS` signed, `ARCHIVE` supplied builds three buckets
  and the unnamed one; `url` on `exports` and `signedUrl` on `photos` refused naming the exposure;
  `url("listings/42/front jpg")` encodes the space; the same code against two values of
  `ANKKA_S3_PUBLIC_URL_PHOTOS` (Garage's and a CDN's) answers each base unchanged. Bind in
  `StorageClientFeatures` the `buckets.feature` scenarios that need no cluster: "reaches a named
  bucket", "a bucket name the descriptor does not give is refused", the exposure-refusal outline, "the
  address … without a request", the CDN outline, through `declareBucket` and the memory store; green

### 6b. The operator on Garage

- [X] T036 [P] [US4] `OP/Settings.scala` and `OP/ObjectStoreSettings.scala` gain
  `webService: Option[String]` from `ANKKA_OBJECT_STORE_WEB_SERVICE` (`namespace/service:port`,
  parsed as `ANKKA_OBJECT_STORE_SERVICE` is); case in `OPT/SettingsSuite.scala`: present, absent, a
  malformed value refused naming the variable
- [X] T037 [P] [US4] `OP/ObjectStore.scala` gains `def setWebsite(bucketId: String, enabled: Boolean): Unit`
  and `BucketInfo.website: Boolean` (`:98`); `OP/GarageStore.scala` implements it with
  `POST /v2/UpdateBucket?id=` and `websiteAccess { enabled, indexDocument: "index.html" }`, and reads
  `websiteAccess.enabled` into `BucketInfo` from `GetBucketInfo`. Case in `OPT/GarageStoreSuite.scala`
  against the container: off by default, on after `setWebsite(true)`, read back; the in-memory
  `ObjectStore` double in `OPT/ObjectStoreSuite.scala` records it
- [X] T038 [US4] Actions (contracts/operator-and-cloud.md "Actions"): in `OP/Action.scala`
  `EnsureBucket(bucket, keyName)` (`:152`), `EnsureStorageCredential(ns, secret, labels, keyName, buckets: List[String], generation)`
  (`:159`), `ReissueStorageCredential(…, keyName, buckets, generation)`, `SetWebsiteAccess(bucket, enabled)`,
  `PauseWrites(buckets, generation)` and `ResumeWrites(buckets, generation)` as lists, each with its
  `describe` line (`:347` region); `OP/Executor.scala` performs them: `EnsureBucket` allows `keyName`
  when it is not among the bucket's allowed keys; the credential actions issue once and allow on every
  bucket in the list; `SetWebsiteAccess` writes only when `BucketInfo.website` differs;
  `observeObjectStorage(buckets: Vector[String], keyName)` answers one observation per bucket with
  `keyAllowed` meaning *the service's key* is allowed (R8, which also stops `garage-copy`'s key
  reading as the service's). `OP/StorageCredential.scala` `ensure`, `reissue`, `pauseWrites`,
  `resumeWrites` take the list. Cases in `OPT/StorageCredentialSuite.scala` and `OPT/ObjectStoreSuite.scala`:
  ensure on three buckets allows the one key on each; reissue denies and re-allows on each; pause
  denies on each; a bucket allowed to another key reads `keyAllowed = false`; `SetWebsiteAccess`
  against the double is a no-op when equal
- [X] T039 [US4] `OP/ObjectStorage.scala` `decide` (`:293`) is called per bucket with its exposure,
  answering the plan and a `BucketStatus` for a named bucket; a `public` bucket with no `webService`
  is made with its credential and is `Failed` naming `ANKKA_OBJECT_STORE_WEB_SERVICE`; a bucket in the status and not in the spec
  is written back `Kept` with its last store, bucket and address (R7); a supplied bucket's status is
  `Supplied` with the entry's bucket and no store. Cases in `OPT/ObjectStorageSuite.scala`: each
  exposure's plan; `Kept` from a status after un-naming, and kept across a second pass; `Failed`
  naming the setting; supplied touches no store (the double records no call)
- [X] T040 [US4] `OP/Rendering.scala`: `bucketsActions` per named made bucket (`EnsureBucket(named, keyName)`,
  `SetWebsiteAccess(named, exposure == public)`, `SetBucketCors` for `signed` from `origins`, the
  route for `signed` as `bucketRoute` (`:658`) renders it named `<service>-storage-<name>`, the
  public route as contracts/operator-and-cloud.md "The public route" gives it, `URLRewrite` with
  `hostname = named` and `replacePrefixMatch = /`, to the web Service's port, `timeouts.request: 0s`);
  `RemoveHttpRoute(ns, <service>-storage-<name>, uid)` for a status bucket that is `Kept` or whose
  exposure dropped below what needs a route; the credential action over the list of every made
  bucket (`:420`); the variables per bucket on the Deployment (`ANKKA_S3_BUCKET_<NAME>`,
  `ANKKA_S3_EXPOSURE_<NAME>`, `ANKKA_S3_PUBLIC_URL_<NAME>` = `https://storage.<base>/<named>` on
  Garage; the five `ANKKA_S3_*_<NAME>` for supplied, the two keys by `secretKeyRef` on the project
  secret's entries `access-key`/`secret-key`; the unnamed family's endpoint, region and key rendered
  for a service with only named buckets; `ANKKA_S3_PUBLIC_ENDPOINT` for any `signed`), `<NAME>` from
  `PlatformVariables.bucketVariableSuffix`; the Deployment withheld while any made bucket is
  `Waiting`. `OP/ServiceReconciler.scala` `decideObjectStoragePlan` (`:234`) observes the list of
  named buckets and decides each. Cases in `OPT/ObjectStorageRenderingSuite.scala`: the four-bucket
  service's actions and variables exactly; the public route YAML field for field; the route removal
  from a `Kept` status; `SetWebsiteAccess(false)` for a private named bucket; the supplied bucket's
  `secretKeyRef`s; `OPT/RenderingGoldenSuite.scala` gains `operator/src/test/resources/golden/storage-client.txt`
  rendered from the four-bucket service
- [X] T041 [US4] `sbt 'operator/testOnly *RenderingUnchangedSuite'` after T040 with no change to its
  pins: green. If it is red, the render for a service without `buckets` changed and T040 is wrong,
  not the pin (plan: "no repin"). Then `sbt operator/test` offline
- [ ] T042 [US4] Bind `FEAT/buckets.feature` in `CPT/StorageClientClusterFeatures.scala` on the local
  overlay with T006: "a service is given every bucket its descriptor names" (the pod's environment
  has the four families); "a browser reads an object in a public bucket without a signed URL"
  (`curl -k` from the host at the address the service's endpoint answered, no header, `200` and the
  content type); the private outline (an unsigned read of the unnamed bucket and of `documents`
  through the gateway is refused whatever `photos` admits); "signs a URL for an object in a signed
  bucket"; "a supplied bucket is reached … and the platform makes nothing for it" against a second
  Garage the suite starts in a namespace of its own, with the credential in a project secret made
  through the CLI, asserting the first Garage's admin API lists no bucket for it; "the storage
  credential … reaches every bucket the platform made for it and no other service's" (a second
  service's key is refused on the first's named bucket, `403`); "a descriptor that names two buckets
  alike" and the malformed outline refused at `apply` with the messages; "the status of a service
  names each of its buckets" through `ankka services get`; "deleting a service keeps every bucket";
  "a service that stops naming a bucket keeps it and loses its variables" (reapply without `photos`,
  then the status says `kept`, the pod has no `ANKKA_S3_BUCKET_PHOTOS`, the objects are still in
  Garage, and the route is gone); the `gcs` rows `ranOutside`; "moved together" bound in T047. Run
  with `caffeinate -i sbt 'controlPlane/testOnly *StorageClientClusterFeatures'` or the `cluster`
  workflow with `-f suite=StorageClientClusterFeatures`

### 6c. The cloud path and the move

- [ ] T043 [P] [US4] `OP/CloudRequests.scala`: the bucket request's `Keys.parameters` (`:50`) gains
  `name` and `public`, its outputs `publicUrl`; the credential request's `bucket` (`:40`) becomes
  `buckets` (comma-separated); a named bucket's request is `Requester.name("bucket-<name>")` so it
  is `<service>-bucket-<name>`; renderers in `OPT/CloudRequestsSuite.scala` hold the closed set.
  Cases: a named public bucket's request field for field; the credential request over three
  buckets; the unnamed bucket's request unchanged byte for byte
- [ ] T044 [P] [US4] The scripted provider implements both amendments: `OPT/cloud/ScriptedFulfilment.scala`
  and `OPT/cloud/ScriptedCloudProvider.scala` make a bucket per `name`, mark `public` and answer
  `publicUrl` (Garage-backed mode: the web route's address; pure mode: `https://storage.googleapis.com/<bucket>`),
  grant the one identity on every bucket in `buckets`; `OPT/BucketNames.scala` derives the cloud
  bucket's name for a named bucket as 039 derives the unnamed one's with `-<name>` appended within
  the limit. Cases in `OPT/ScriptedCloudProviderSuite.scala`
- [ ] T045 [US4] `OP/ServiceReconciler.scala` `decideCloudBucket` (`:253`) issues one bucket request
  per named made bucket with `name` and `public`, the credential request over the list, and reads
  `publicUrl` into `ANKKA_S3_PUBLIC_URL_<NAME>` and `BucketStatus.publicAddress`; a bucket named
  during a move is `Waiting` until `Switched`, then made in the target store (R11). Cases in
  `OPT/ServiceReconcilerCloudSuite.scala`: three requests for three buckets; the credential's list;
  `publicUrl` into the variable; `Waiting` during a move
- [ ] T046 [P] [US4] The move over every bucket: `OP/StorageMove.scala` holds the list of made
  buckets in its state and `Act.Copy(buckets)`; `OP/Rendering.scala`'s move job renders
  `MOVER_SOURCE_BUCKETS` and `MOVER_TARGET_BUCKETS` (parallel, the unnamed first when present);
  `MOVER/Main.scala` reads the lists, accepting the old single names as one-element lists; `MOVER/Mover.scala`
  copies then verifies pair by pair and names the pair in a failure. Cases in `OPT/StorageMoveSuite.scala`
  (pause denies on every bucket, `Switched` switches all), `OPT/MoveJobRenderingSuite.scala` (the
  two lists), `MOVER/MoverSuite.scala` against two Garage containers (three pairs copied and
  verified; a missing target bucket names the pair; the old names still work)
- [ ] T047 [US4] Extend `CPT/ObjectStorageGcsClusterFeatures.scala` with the scripted provider in its
  Garage-backed mode: a service with the unnamed bucket and two named ones moved to the cloud as one
  move, every object present in each target bucket, the status naming each bucket's new store; bind
  `buckets.feature`'s "a service's buckets are moved together" there and name it in
  `StorageClientClusterFeatures`'s `ranElsewhere`. `ranOutside` for "a public bucket readable by
  anyone on Google" and `publicUrl`, by name, for ankka-gcp's nightly suite
- [ ] T048 [US4] Write the amendments into `DOCS/platform/cloud-provider.md`'s request tables
  (`bucket`: `name`, `public`, `publicUrl`; `bucket-credential`: `buckets`; the public bucket's
  access settings; `publicUrl` as Google's address or the installation's content delivery network's)
  so `ankka-gcp` reads its contract from the page, and note under the provider's obligations that an
  older provider answers a named bucket's request `Failed` with its reason. `just docs`

**Checkpoint**: named buckets end to end on Garage, the cloud contract amended, the move whole.

---

## Phase 7: User Story 5 — The same service runs on a laptop and in a test (Priority: P2)

**Goal**: the directory store, the service serving its own signed and public addresses at the
reserved path, and the kit's store proven across a restart.

**Independent Test**: `DirectoryBucketSuite`, `DirectoryStorageEndpointSuite`, `ReservedPathSuite`;
`local.feature` and `testkit.feature` green in `StorageClientFeatures`; quickstart §6 by hand.

- [X] T049 [P] [US5] Write `STO/DirectoryBucket.scala` (R12): `DirectoryObjectStorage(root: Path, buckets, unnamed, addressBase: () => String, signer)`,
  one directory per bucket (`default/` for the unnamed), an object at `<root>/<bucket>/<name>` with
  `<name>.meta.json` (`contentType`, `lastKept`) beside it; `put` writes `<name>.part` then
  `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` so a short stream leaves the old object; `list` walks
  under the prefix in name order; the name rule keeps `..` out, and the resolved path is checked to
  stay under the bucket's directory all the same; `signedUrl` is `addressBase() + "/_ankka/storage/<bucket>/<name>?access=…&expires=…&token=…"`
  with T051's token, `url` on a `Public` bucket the same without a token; the buckets are
  `LocalBuckets`' when `service.json` is beside the working directory, else any name asked for as
  `Private`. Then `ObjectStorages.build` builds it for `Directory`, creating the root and refusing the
  start with a `Left` naming the directory and the cause when it cannot be created or written.
  Cases in `RTT/storage/DirectoryBucketSuite.scala`: `BucketContract` over a temp directory; the
  `.part` file gone after a failed put and the old object readable; the meta file's content; a
  read-only root refused naming it; a name resolving outside the bucket refused
- [X] T050 [P] [US5] `HTTP/HttpServer.scala`: `validate` refuses an endpoint whose prefix is `_ankka`
  with `the /_ankka prefix is the runtime's`; `Router.handle` (`:460–491`, beside `/_ankka/health`)
  admits runtime-registered routes under `/_ankka/storage` only when one is registered, else `404`.
  Cases in `HTTPT/ReservedPathSuite.scala`: the refusal at `validate`; `/_ankka/storage/x/y` is `404`
  with nothing registered; `/_ankka/health` unchanged
- [X] T051 [US5] Write `STO/DirectoryStorageEndpoint.scala`: an endpoint the runtime registers at
  `/_ankka/storage` only when the backend is `Directory` (`RT/Ankka.scala`, beside the HTTP server's
  registration), serving `GET /_ankka/storage/<bucket>/<name>` for a valid `token` or on a `Public`
  bucket (the object, its content type, `Content-Length`, as `Streamed`), `PUT` for a `Keep` token
  (the body kept with its `Content-Type`, `200`), `403` for no token on a non-public bucket, an
  expired or a wrong one, `404` for an object not there; the token
  `HMAC-SHA256(key, "<bucket>/<name>|<access>|<expires>")` hex over a key generated at start and kept
  in `<root>/.key` (mode `600`) so a restart keeps URLs valid; the address base
  `http://<ANKKA_HTTP_INTERFACE or 127.0.0.1>:<bound port>` read from the HTTP server's bound
  address after start (a `Promise` the server completes, since the port may be `0`). Cases in
  `RTT/storage/DirectoryStorageEndpointSuite.scala` through `HttpServer.at("127.0.0.1", 0)`: valid
  read; valid keep then read; expired `403`; wrong token `403`; public bucket unsigned `200`;
  private unsigned `403`; `404`; a restarted server with the same root honours a URL made before
- [X] T052 [US5] Bind `FEAT/local.feature` in `StorageClientFeatures` with a kit started as
  `StorageBackendChoice.FromEnvironment` and `ANKKA_STORAGE_DIR` at a temp directory: the objects
  under the directory; a signed URL on the service's own port read with the JDK client; a keep
  through a signed URL; an unsigned request `403`; the public address served; "a service given the
  variables of an object store uses it rather than a directory" (an unreachable endpoint in the
  variables: the put is `Unavailable` naming it and the directory stays empty); "a service whose
  directory store cannot be written does not start" (a read-only root: the kit's start fails naming
  it); "a deployed service serves no signed URL and no public address itself" in-process with the
  `s3` backend against a fake endpoint: `/_ankka/storage/...` is `404`, and `ranElsewhere` for the
  k3s copy in `StorageClientClusterFeatures` (a deployed service's pod answers `404` there). Green:
  `sbt 'testkit/testOnly *StorageClientFeatures -- *local*'`
- [X] T053 [US5] Bind `FEAT/testkit.feature` the same way over T014: seed and read; read what the
  service kept; survive `restartService()`; gone when the kit stops (a second kit on the same
  descriptor sees nothing); two kits apart; "a test reaches a service's public bucket through the
  storage client" through `declareBucket("photos", Public)` and `kit.storage.bucket("photos")`.
  Green: `sbt 'testkit/testOnly *StorageClientFeatures -- *test*'`. Then the whole suite: every
  in-process scenario green, the rest named in the two maps, the count equal to the files'
- [X] T054 [P] [US5] The templates: if `ankka.g8` or `ankka init`'s Scala, Python, TypeScript and
  Rust templates (`cli/…/Scaffold.scala`, `ankka.g8/`) write a `.gitignore`, add `.ankka/` to it so a
  laptop's directory store is never committed; the template suites (`*TemplateSuite`) assert the
  line. If no template writes one, record that in `DOCS/build/object-storage.md`'s laptop section
  (T106) instead

**Checkpoint**: a service runs on a laptop with no variables and in a test with no Docker.

---

## Phase 8: User Story 6 — Every SDK offers the client (Priority: P3)

**Goal**: the same client in Python and TypeScript speaking S3 themselves, in Rust through the
`storage` import, each with the memory and directory stores, the fixture read by all four, the
conformance suite checking the rule and the codes.

**Independent Test**: each SDK's suite; `ConformanceSuite`'s `storage.*` cases against the four
references; `languages.feature` `ranElsewhere`.

- [ ] T055 [US6] Protocol 1.20: in `PROTO/client.proto` add `StorageRequest`, `StorageReply`,
  `StoredObject`, `ObjectInfo`, `ObjectEntries`, `ObjectEntry` exactly as
  contracts/runtime-and-protocol.md "Protocol" gives them, each field with a `// 1.20:` comment;
  `protocol/WASM-ABI.md` lists `storage(ptr, len) -> i64` since 1.20 from the same `CallSite`s as
  `request` with the 4,000,000-byte bound; `protocol/README.md` `## Version` (`:16`) says 1.20 and a
  line what it added. Bump the pins as 051's T007 did: `WireProtocol.Version` in
  `RT/remote/Conversation.scala`, `ProtocolVersion(1, 20)` in `API/Compatibility.scala:80`,
  `PROTOCOL_VERSION` in `PY/src/ankka/service.py:28`, `TS/src/spec.ts:28`, `RS/ankka/src/service.rs`;
  the test pins in `SCT/ProtocolSuite.scala`, `APIT/HostingSuite.scala`, `APIT/CompatibilitySuite.scala`
  (1.20 supported, 1.21 not), `TS/test/contracts.test.ts`, `RS/ankka/tests/contracts_discovery.rs`.
  Copy to the three SDKs with their proto scripts; `diff -r`
- [ ] T056 [US6] The sidecar answers the import: `SC/wasm/HostImports.scala` (`:83`) adds
  `bytes("storage")(storage)` and `SC/wasm/ModuleLoader.scala` (`:74`) lists it, an older runtime's
  refusal of an unknown import naming it as it names any; `SC/ClientLogic.scala` gains
  `storage(request: StorageRequest): StorageReply` answering from `service.storage` on
  `AnkkaExecutors.virtual`: `PUT` from `body` (above `MaxBodyBytes`, `:721`, `BAD_REQUEST` naming the
  bound), `GET` after a `stat` whose length above the bound is `BAD_REQUEST` before a byte is read,
  `DELETE`, `LIST` joining every page into `entries` (with `continuation` for a caller that pages),
  `STAT`, `SIGNED_URL`, `URL`, every `CommandError` to `error { code, message }` through
  `Translate.toCode`. Cases in `SCT/ClientStorageSuite.scala` (new, modelled on
  `SCT/ClientSecretsSuite.scala`): each op against a memory store; the bound both ways; an unknown
  bucket's error code and message
- [ ] T057 [US6] The sidecar admits a process's directory endpoint: `SC/Main.scala`'s `Router` admits
  one endpoint whose prefix is `_ankka/storage` when the process declares it (the SDK registers it
  only when its store is a directory), refusing any other `_ankka` prefix as T050 does; `SC/Discovery.scala`
  or the descriptor check that refuses reserved prefixes lets that one through. Case in
  `SCT/RemoteEndpointSuite.scala`: a process declaring `/_ankka/storage` is served; `/_ankka/other`
  refused naming the prefix
- [ ] T058 [US6] The Scala reference's conformance routes: `SCT/conformance/ConformanceReference.scala`
  gains `/conformance/storage?op=…&bucket=…&name=…` routes over the reference's `storage` (put from
  the body with a content type, get, delete, list, stat, signed-url, url, and a `rules` op that
  tries a name and answers the refusal's code and message); `SCT/conformance/ConformanceSuite.scala`
  gains `storage.*` cases: round trip with content type, list under a prefix, stat, delete twice,
  every row of the rules fixture refused with the fixture's `why` and `BAD_REQUEST`, an unknown
  bucket `UNAVAILABLE`, `url` on the declared public bucket; `SCT/conformance/ConformanceTarget.scala`
  sets `ANKKA_STORAGE_BACKEND=memory` for a process target by default and, under
  `-Dankka.conformance.storage=garage` (forwarded in `sidecar / Test / javaOptions` — the forked-JVM
  trap), the `ANKKA_S3_*` of a Garage the suite starts; the memory store declares `photos` public
  for every target through the environment overlay. In-process first:
  `sbt 'sidecar/testOnly *ConformanceSuite -- *storage.*'` reports the case count, not zero
- [ ] T059 [P] [US6] Python (R14): `PY/src/ankka/storage.py` with `Buckets`, `Bucket`, `StoredObject`,
  `ObjectInfo`, `ObjectEntry`, `ObjectRules` (the name rule, bucket rule, the bound), `S3Buckets`
  built from `os.environ` exactly as T004 reads the families, `InMemoryBuckets`, `DirectoryBuckets(path)`
  (the layout of T049, the `.part` rename, the meta file), each `async` as contracts/sdk.md "Python"
  gives; `PY/src/ankka/sigv4.py` with `sign` and `presign` over `hashlib`/`hmac`; the S3 calls over
  `http.client` in `asyncio.to_thread`, `Content-Length` set, the body streamed from an async iterable
  through a thread-safe queue, `UNSIGNED-PAYLOAD`; errors `CommandError` with the codes; `HasStorage`
  mixin on `Endpoint`, `Consumer`, `GraphConsumer`, `TimedAction`, `Agent`, `AutonomousAgent`
  (`endpoint.py`, `consumer.py`, `graph.py`, `timed_action.py`, `agent.py`, `autonomous.py`);
  `Workflow.storage` guarded by `_in_step` (`workflow.py`); `server.py` (`:875–886` region) sets
  `instance.storage` from the environment's backend, and registers the directory endpoint at
  `/_ankka/storage` (tokens as T051) when the store is a directory; `__init__.py` exports. Tests in
  `PY/tests/test_storage.py`: the fixture rows (read as `test_secrets.py:26` reads its fixture), the
  SigV4 vectors of T003, the contract over `InMemoryBuckets` and `DirectoryBuckets`, the six kinds
  hold `storage`, the workflow guard, the directory endpoint's four answers. `uv run mypy` clean
- [ ] T060 [P] [US6] Python kits and conformance: `PY/src/ankka/testkit/unit.py` kits take
  `storage: Buckets = InMemoryBuckets()` and expose it; `PY/src/ankka/testkit/integration.py`'s
  `AnkkaTestKit.start` sets `ANKKA_STORAGE_BACKEND=memory` in the process's environment and offers
  `kit.storage`; `PY/examples/shopping_cart/conformance.py` gains the `/conformance/storage` routes
  of T058 in a `# docs:start storage` region and `service.json` declares `photos` public;
  `uv run conformance` against the Scala reference's cases green. Case in `PY/tests/test_storage.py`:
  a unit kit's endpoint puts and the test reads `kit.storage`
- [ ] T061 [P] [US6] TypeScript (R14): `TS/src/storage.ts` with the interfaces of contracts/sdk.md
  "TypeScript" (no `enum`), `ObjectRules`, `S3Buckets` from `process.env`, `InMemoryBuckets`,
  `DirectoryBuckets`, streams as `ReadableStream<Uint8Array>`; `TS/src/sigv4.ts` over `node:crypto`;
  the S3 calls over `node:http`/`node:https` with `Content-Length` set and the body piped (never
  `fetch`, which chunks); getter/setter `storage` on endpoint, consumer, graph consumer, timed
  action, agent, autonomous agent (`endpoint.ts`, `consumer.ts`, `graph.ts`, `timedAction.ts`,
  `agent.ts`, `autonomous.ts`) and the workflow guarded by `#inStep` (`workflow.ts`); the server
  (`TS/src/server/`) sets it from the backend and registers the directory endpoint when the store is
  a directory; `index.ts` exports. Tests in `TS/test/storage.test.ts`: the fixture rows (read as
  `secrets.test.ts:24` reads its fixture), the SigV4 vectors, the contract over both stores, the
  kinds, the guard, the directory endpoint. `npm run typecheck` clean under type stripping
- [ ] T062 [P] [US6] TypeScript kits and conformance: `TS/src/testkit/kinds.ts` and `unit.ts` take
  `storage` defaulting to `new InMemoryBuckets()`; `TS/src/testkit/integration.ts` sets
  `ANKKA_STORAGE_BACKEND=memory` and offers `kit.storage`; `TS/examples/shopping-cart/conformance.ts`
  gains the routes in a `// docs:start storage` region and its `service.json` declares `photos`
  public; `npm run conformance` green; a `test:slow` case against a Garage container if the slow
  suite already starts containers, else against the directory store
- [ ] T063 [P] [US6] Rust (R6): `RS/ankka/src/abi/imports.rs` adds `storage(ptr, len) -> u64` to the
  `ankka1` block (`:149`) and `Import::Storage` (`:219`), called through a `call_storage` beside
  `call_secret`; `RS/ankka/src/storage.rs` with `Storage`, `Bucket`, `StoredObject`, `ObjectInfo`,
  `ObjectEntry`, `SignedAccess`, `StorageError` (`TooLarge` naming the bound, `BadRequest`,
  `Unavailable`, `Internal`), each call one `StorageRequest` encoded with `prost` and one
  `StorageReply` decoded, `put` and `get` refusing a body above 4,000,000 bytes before the call;
  `RS/ankka/src/context.rs` `with_storage()` on the contexts that have `with_secrets()` and
  `ctx.storage() -> Option<Storage>`; the component modules (`components/consumer.rs`, `endpoint.rs`,
  `workflow.rs` steps only, `timed_action.rs`, `agent.rs`, `autonomous.rs`) carry it; `prelude.rs`
  and `lib.rs` export. Natively, `RS/ankka/src/testkit/unit.rs`'s `NativeHost` answers `storage`
  from a `HashMap<(String, String), (String, Vec<u8>)>` with `photos` declarable public, and
  `RS/ankka/src/testkit/integration.rs`'s kit mounts a host directory as the container's
  `ANKKA_STORAGE_DIR` and reads and seeds the files there in the directory store's layout. Tests in `RS/ankka/tests/storage.rs`:
  the fixture rows (read as `secrets.rs:33` reads its fixture), the contract over the native host,
  `TooLarge` both ways, a compile-fail case in `RS/ankka/tests/compile_fail/` for `storage()` on an
  entity context; `cargo test --workspace`
- [ ] T064 [US6] Rust conformance and the import's presence: `RS/examples/shopping-cart/src/conformance.rs`
  gains the routes in a `// docs:start storage` region and the example's `service.json` declares
  `photos` public; `./conformance.sh` green through the sidecar's `ModuleTarget` (T056);
  `WasmHostSuite` (`SCT/WasmHostSuite.scala`) gains a case loading a module that uses the client and
  one that does not, asserting through the loader's import list that `storage` appears only in the
  first (`wasm-objdump -j Import` by hand confirms; the case holds it in the build)
- [ ] T065 [US6] `languages.feature`: in `TKT/storage/StorageClientFeatures.scala`'s `ranElsewhere`
  name, per outline row, the suite that holds it (`PY/tests/test_storage.py`,
  `TS/test/storage.test.ts`, `RS/ankka/tests/storage.rs`, `ConformanceSuite`'s `storage.*` cases for
  "every SDK refuses the same object names for the same reasons", the directory cases of each for
  "keeps its objects in a directory", and for "no object passes through the platform's own program"
  a case in `SCT/ClientStorageSuite.scala` asserting that a Python and a TypeScript conformance
  target's put of a 5 MB object succeeds while the sidecar's rpc log shows no storage call, and that
  a module's is refused at the bound). Then the four end-to-end commands from `CLAUDE.md` for the
  SDKs, and `sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.target=…` against each
  reference

**Checkpoint**: one client in four languages; protocol 1.20.

---

## Phase 9: Polish — documentation, traps, the whole build

**Purpose**: the pages that stand alone, the skills, the limitations page's honest lines, the traps
written the day they cost time, and every suite once.

- [ ] T066 [P] Write `DOCS/build/object-storage.md` (new, after `secrets.md` in the Build section):
  what the client is (the ten operations, the contexts, by name), what it is not (no directories,
  rename, copy, append or partial writes; a put declares its length), the four languages' samples
  included from the `// docs:start` regions of `TKT/storage/StorageComponents.scala`,
  `PY/examples/shopping_cart/conformance.py`, `TS/examples/shopping-cart/conformance.ts`,
  `RS/examples/shopping-cart/src/conformance.rs`; a streamed endpoint; signed URLs; `url` on a
  public bucket; the laptop (the directory, the reserved path, `ANKKA_STORAGE_DIR`, `.ankka/` in
  `.gitignore` or T054's note); the test kit's store, `declareBucket`; errors. Add it to
  `mkdocs.yml`'s nav (`:177` region) and to `SKILL/ankka-endpoints/SKILL.md`, `SKILL/ankka-workflows/SKILL.md`
  and the three language skills' page lists
- [ ] T067 [P] `DOCS/platform/object-storage.md` gains "Named buckets" (the descriptor, the
  exposures, the variables per bucket, the one credential, the status lines) and "A public bucket"
  (the web listener, the route, the address, the content delivery network note), and its "what every
  client must do" section says the SDK's client does it; `DOCS/reference/service-descriptor.md` gains
  the `buckets` table and refusals from contracts/descriptor-and-status.md with a `service.json`
  example `DocumentationDescriptorsSuite` reads; `DOCS/build/http-endpoints.md` gains streamed
  bodies (`postStream`, `putStream`, `Streamed`, `max-stream-bytes`, "a process's routes are
  strict"); `DOCS/operate/status-and-history.md` the bucket lines; `DOCS/reference/configuration.md`'s
  prose the `ankka.storage` keys and `ankka.http.max-stream-bytes` (the generated table follows
  `just docs-sync`)
- [ ] T068 [P] `DOCS/reference/limitations.md` (`:94`): drop "No storage client in the SDKs" and
  add the lines from R17 (a keep through a signed URL on a developer's machine is at most
  4,000,000 bytes for Python and TypeScript; no directories, rename, copy, append or partial writes; a put declares its
  length; a module's put and get carry a whole object of at most 4,000,000 bytes; a signed URL spans
  at most seven days; a gRPC endpoint has no client; a supplied bucket is checked on its first call;
  a process's endpoint routes are strict). `SKILL/ankka-platform/SKILL.md` says named buckets and
  the public bucket in a paragraph
- [ ] T069 `just docs-sync && just docs`: every included sample found, the configuration table
  regenerated, the skills rendered; `just features`: 0 findings with the scenario count unchanged
- [ ] T070 [P] Write the traps that cost time into the rule files, dated: `.claude/rules/runtime.md`
  (the JDK client's connection on a closed body stream; `UNSIGNED-PAYLOAD` on Garage, whichever way
  T005 went; the reserved path and the bound port promise), `.claude/rules/kubernetes.md` (the web
  listener and `ANKKA_OBJECT_STORE_WEB_SERVICE`; `Kept` as the operator's memory; the key is the
  service's, not the bucket's), `.claude/rules/sidecar.md` (the `storage` import and the bound; the
  one admitted `_ankka` prefix), `.claude/rules/testing.md` (`StreamedBodySuite`'s heap guard)
- [ ] T071 `sbt scalafmtAll scalafmtSbt`, then `caffeinate -i sbt -Dankka.cluster.tests=off buildAll`,
  `just test-console`, the SDK commands, `sbt 'storageMover/test'`; then
  `git diff main -- project/Dependencies.scala` shows no new `runtime` or `sdk` library (SC-003);
  `gh workflow run cluster --ref 055-storage-client` for the whole matrix (`StorageClientClusterFeatures`,
  `GarageWebClusterSuite`, `ObjectStorageGcsClusterFeatures`, `ObjectStorageClusterFeatures`,
  `OperatorClusterSuite` with the component change) and read every suite's own count
- [ ] T072 Quickstart §6 by hand: `ankka init listings --language scala`, `sbt run` with no
  variables, the `curl` round trip, the files under `.ankka/storage/`, the public address answering
  `200 image/jpeg`; then `ankka services apply` on the local installation and `ankka services get`
  showing five bucket lines. Record what differed from the page in T066's text
- [ ] T073 The pull request: the operational consequences from plan.md's "Complexity Tracking"
  (the component's web listener and the operator setting an installation must take together; ankka-gcp's
  four amendments before a public or named bucket works on Google; protocol 1.20 and the `storage`
  import; the limitations lines) in the description, and the two `ranOutside` scenarios named for
  ankka-gcp's suite

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001–T004 and T006 together; T005 after T003; T007 after T006 and needs
  k3s; T008 last, after the fixture exists
- **Foundational (Phase 2)**: after Phase 1 (T005's answer shapes T017, not Phase 2). T009 first;
  T010, T013 beside it; T011 after T009 and T010; T012 after T011; T014 after T012 and T013; T015
  after T014
- **US1 (Phase 3)**: after Phase 2. T017 and T019–T020 in parallel; T018 after T017 and T005; T021
  after T019–T020; T022 last
- **US2 (Phase 4)**: after Phase 2; independent of Phase 3 except T027's fixture components
- **US3 (Phase 5)**: after Phase 3 (T017's `signedUrl`); T029 needs k3s
- **US4 (Phase 6)**: 6a after Phase 2 (T035 after T017); 6b after 6a and T006–T007; 6c after 6b.
  T042 and T047 need k3s
- **US5 (Phase 7)**: after Phase 2; T051 after T050 and T049; T052–T053 after T051; independent of
  Phases 3 to 6
- **US6 (Phase 8)**: after Phases 3, 4 and 7 (the S3 client's behaviour, the contexts, the directory
  layout the SDKs copy); T056–T058 after T055; T059–T064 in parallel after T058
- **Polish (Phase 9)**: after every story; T066–T068 and T070 in parallel; T069, T071–T073 in order

### User Story Dependencies

- **US1**: Phase 2 only.
- **US2**: Phase 2 only; its fixture components are shared with US1.
- **US3**: US1 (the S3 store signs).
- **US4**: US1 for the client by name; otherwise the platform side is independent of the client.
- **US5**: Phase 2 only.
- **US6**: US1, US2 and US5 for what it ports.

### Parallel Opportunities

- Phase 1: T001, T002, T003, T004, T006 together; T005 and T007 beside each other once their
  prerequisites hold.
- Phase 3: the S3 store (T017–T018) and the streamed bodies (T019–T021) are two trees.
- Phases 4, 5 and 7 (US2, US3, US5) are three trees after Phase 3's T017 and can run side by side.
- Phase 6: 6a's T031, T033, T034 beside T030; 6b's T036, T037 beside each other; 6c's T043, T044,
  T046 together.
- Phase 8: T059–T060 (Python), T061–T062 (TypeScript), T063–T064 (Rust) are three languages in
  three trees after T058.
- Phase 9: T066, T067, T068, T070 together.

---

## Parallel Example: Phase 3, the two tracks

```bash
# After Phase 2 (the trait, the memory store, the service's storage, the kit):
Task: "T017–T018 S3Bucket over SigV4 and the JDK client; S3BucketSuite against Garage with the contract"
Task: "T019–T021 StreamedBody, postStream/putStream, Streamed, max-stream-bytes; StreamedBodySuite under -Xmx512m"
# Then T022: client.feature green in StorageClientFeatures, the garage row in S3BucketSuite.
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1: the spikes decide R3 and R9 before anything rests on them; every scenario bound red.
2. Phase 2: a service holds `storage`; a test seeds it.
3. Phase 3: put, get, list, stat and delete against Garage; a 2 GiB object through an endpoint with
   a 512 MiB heap; break the `Content-Length` once and watch it go red.
4. Stop and demo: a test keeps an object with no Docker; the same service round-trips against Garage.

### Incremental Delivery

1. US2 holds the rule about where a blocking call belongs, by `compileErrors`.
2. US3 makes signed URLs the platform's.
3. US4 names buckets in the descriptor and makes, grants, routes and reports them; the cloud
   contract amended for ankka-gcp; the move whole.
4. US5 runs the service on a laptop and proves the kit across a restart.
5. US6 ports the settled interface to three languages under protocol 1.20.
6. Phase 9 writes it down and runs everything.

### Notes

- A bound scenario is red before its implementation task; a scenario that passes before the code
  exists is bound to nothing.
- `sbt 'testkit/testOnly *StorageClientFeatures'` must report the scenario count the four files
  hold, with the `ranElsewhere` and `ranOutside` ones ignored by name; a green run with fewer
  matched nothing.
- `RenderingUnchangedSuite` is never repinned by this feature (T041).
- Every new test switch (`-Xmx512m` for `http`, `ankka.conformance.storage`) is forwarded in the
  project's `Test / javaOptions`, or it is a no-op in a forked JVM.
- The protocol minor is 1.20 until something merges first; then every pin in T055 moves.
- No secret value reaches an action, a status, the journal or a log line; the supplied bucket's
  keys are `secretKeyRef`s the pod reads.
- Commit after each phase at least; never `git stash` bare in this worktree.
