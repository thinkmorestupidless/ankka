# Research: A Storage Client in the SDKs

Decisions, each with what it rests on. File references are to the worktree at the plan's date;
`RT` is `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/`, `HTTP` is
`modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/`, `OP` is
`operator/src/main/scala/com/thinkmorestupidless/ankka/operator/`, `CP` is
`controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/`.

Three things the survey found that the spec did not know, each decided below and written back
into the spec as a planning amendment: a module cannot reach an S3 endpoint at all (R6); the
HTTP layer has no streaming body in either direction (R4); and nothing in the tree signs an S3
request (R3).

## R1. The client is a trait in `sdk`, with one runtime implementation per store

**Decision**: `ObjectStorage` (the client) and `Bucket` (one bucket's operations) are traits in
`modules/sdk` beside `SecretStore`, with no Pekko and no I/O. `modules/runtime` holds three
implementations of `Bucket`: `S3Bucket` over the JDK's `HttpClient`, `DirectoryBucket` over
`java.nio.file`, and `MemoryBucket` over a `TrieMap`; and one `ObjectStorage` that maps names to
buckets. `ObjectStorage.unavailable` refuses every call, as `SecretStore.unavailable` does.

**Evidence**: `SecretStore` is the precedent: the trait in `sdk` (`SecretStore.scala`), the
backends chosen in one place (`RT/secrets/SecretStores.build`), `unavailable` for contexts with no
service behind them (`EndpointClients`'s default), and the rules in a companion held to a fixture
(`SecretRules`, `protocol/fixtures/secrets/rules.json`). The testkit's `ConsumerTestKit` takes an
`InMemorySecretStore` the same way.

**Alternatives**: one trait with the bucket as a parameter on every call (`put(bucket, name, …)`)
reads worse at every call site and makes "which buckets exist" a runtime check on each call; a
`Bucket` handle answers it once.

## R2. The client reaches each context exactly as the secret store does

**Decision**: `storage: ObjectStorage` is added beside `secrets: SecretStore` on
`WorkflowContext`, `ConsumerContext`, `TimedActionContext`, `AgentContext`,
`AutonomousAgentContext`, `BlueprintContext` and `EndpointClients`, and nowhere else. The workflow's
command handler is refused by a second `StepScope.stepsOnly` overload. `AnkkaService` carries
`storage` as it carries `secrets`, built in `ServiceBuilder.host` beside `SecretStores.build`.
`GrpcServer` builds `EndpointClients` without `secrets` today and is left so: a gRPC endpoint gets
`unavailable` for both, which is a known gap, not this feature's.

**Evidence**: the survey's trace of `secrets` through `RT/Ankka.scala` (lines 303–421),
`WorkflowHost.behavior`, `StepScope`, `ProjectionRuntime` (line 231 and the handler kinds),
`TimerSweeper`, `AgentRuntime` (lines 208, 262, 435, 548) and `HTTP/HttpServer.scala` (lines
125–133). `SecretStoreAvailabilitySuite` proves by `compileErrors` that an entity or a view cannot
name `secrets`; a `StorageAvailabilitySuite` does the same.

## R3. One SigV4 signer in the runtime, over the JDK's `HttpClient`

**Decision**: `RT/storage/SigV4.scala` signs a request (headers) and a URL (query string) for
`AWS4-HMAC-SHA256` with `javax.crypto.Mac` and `MessageDigest`, about 150 lines; `S3Bucket` uses
it for `PutObject`, `GetObject`, `HeadObject`, `DeleteObject` and `ListObjectsV2`, path-style, for
the given region, with `x-amz-content-sha256: UNSIGNED-PAYLOAD` on a put so a stream is sent
once and never hashed. No `aws-chunked`, no checksum header. Google's `<Generation>` quirk does
not arise: the client never lists versions.

**Evidence**: no SigV4 exists in any main source; every data-plane S3 call in the tree is the AWS
SDK, in `storage-mover` and test scopes only (`project/Dependencies.scala` line 170). The
operator's `GarageStore` uses Garage's admin API with a bearer token, so there is nothing to
share. `docs/platform/object-storage.md` lists what every client must do (path style, the region,
checksums only when required, `x-amz-content-sha256` required by Garage). `SecretManagerStore`
reaching Google over the JDK client with no library is the same choice made once already.

**To verify first (S1)**: that Garage `v2.3.0` and Google's XML API accept `UNSIGNED-PAYLOAD` on
a `PutObject` with `Content-Length`, and a query-signed URL for `GET` and `PUT`. If Garage refuses
`UNSIGNED-PAYLOAD`, the put hashes through a temporary file first, which costs a pass over the
object and nothing in the API.

**Alternatives**: the AWS SDK for Java in `runtime` puts about 40 jars on every service's
classpath for five operations, and 034's research already named it as the thing a client must be
configured around; Pekko HTTP's client exists on the classpath, but the JDK's is what
`ServiceClient` and `SecretManagerStore` use and its streaming body publishers fit `InputStream`.

## R4. Streaming bodies in `ankka-http`, both ways

**Decision**: `ankka-http` gains one request body kind and one response kind. `postStream` and
`putStream` routes hand the handler a `StreamedBody(contentType, length: Option[Long],
body: InputStream)` from the entity's `dataBytes` through `StreamConverters.asInputStream` on the
handler's virtual thread, with no `toStrict`; the size limit is `ankka.http.max-stream-bytes`
(default 5 GiB, the stores' single-put bound). `Streamed(contentType, length, body: InputStream)`
is a response value the server marshals as a streamed `HttpEntity` of known length (chunked when
the length is unknown). Everything else keeps the strict path.

**Evidence**: `HTTP/HttpServer.scala` `Router.dispatch` (lines 675–728) does
`request.entity.toStrict(bodyTimeout)` for every body route, with pekko's 8 MiB default limit;
`EncodedResponse` (`HttpEndpoint.scala` line 133) is always a strict `HttpEntity`; `FromBody` has
only `json` and `text`, so a binary upload today arrives as a `String`. SC-002 (2 GiB through an
endpoint with a 512 MiB heap) cannot hold without this. The virtual thread makes a blocking
`InputStream` the natural shape, as `ComponentClient.invoke` already is.

**Alternatives**: scoping the feature to signed URLs for anything large keeps the endpoint path
at 8 MiB and makes story 1's "larger than an instance's memory" scenario false through an
endpoint; a `Source[ByteString]` in the handler's hands puts Pekko streams into every user's
signature where the rest of the SDK shows none.

## R5. Streams in the client's API are `InputStream` with a declared length

**Decision**: `put(name, body: InputStream, length: Long, contentType: String)`; `get(name):
Option[StoredObject]` where `StoredObject(contentType, length, lastKept: Instant, body:
InputStream)`; `stat(name): Option[ObjectInfo]`; `list(prefix): Iterator[ObjectEntry]`, paging
behind the iterator; `delete(name): Unit`. A put whose stream ends before `length` fails and
leaves the old object (S3's put is atomic; the directory store writes to a sibling file and
renames). A `get` body the caller closes early closes the connection.

**Evidence**: S3's `PutObject` needs `Content-Length`; a put of unknown length needs
`aws-chunked`, which is the encoding Garage's trap is about. The scenario "a keep that fails
part way leaves no partial object" names the declared length. The JDK client does not close a
connection when its body stream is closed (`runtime.md`), so `StoredObject.body` is a wrapper
whose `close` cancels the response's subscription.

**Alternatives**: `Array[Byte]` overloads are added as conveniences (`putBytes`, `getBytes`) since
most objects are small; they are the streaming calls underneath.

## R6. A module reaches its bucket through a host import answered by the runtime's client

**Decision**: The Rust SDK's client calls a new `storage` import in the `ankka1` module (ABI 1.20),
one `StorageRequest` in and one `StorageReply` out as protobuf, for `put`, `get`, `delete`,
`list`, `stat`, `signed_url` and `url`, bucket by name or unnamed. The sidecar's `HostImports`
answers it from `service.storage`, the runtime's own client, so a module's objects go through
the platform's program whole, bounded by `ClientLogic.MaxBodyBytes` (4,000,000 bytes) as every
module call is. The spec is amended: FR-014's "no object passes through the sidecar's protocol"
holds for Python and TypeScript; for a module the import is the only program there is.

**Evidence**: feature 030's `request` import takes a service's *name* and a path, never a URL
(`protocol/.../client.proto` line 214 `ServiceRequest`, `sidecar/.../ClientLogic.scala` lines
699–760 `isName`), and a module has no WASI (`docs/contributing/language-sdks.md`). There is no
way to open a socket from a module. The same import shape serves `get_secret`/`put_secret`
(`HostImports.scala` lines 82–85, `ModuleLoader.scala` line 74).

**Alternatives**: a general outbound HTTP import for modules is a design change to the zero-trust
model (every port is mutual TLS, no egress) and belongs to no feature yet; leaving Rust out was
declined in clarification.

## R7. Named buckets: the descriptor, the resource, the status

**Decision**: `ServiceSpec.buckets: Vector[BucketSpec]` with `BucketSpec(name, exposure:
BucketExposure = Private, origins: Vector[String] = Nil, supplied: Option[SuppliedBucket])`,
`SuppliedBucket(endpoint, region, bucket, credential: String)` naming a project secret whose
entries are `access-key` and `secret-key`. `AnkkaServiceSpec.buckets` mirrors it with the schema;
`ObjectStorageStatus.buckets: List[BucketStatus(name, phase, store, bucket, publicAddress,
exposure, supplied, kept, detail)]`. The status is also the operator's memory of a bucket that is
no longer named: a bucket in the status and not in the spec is reported `Kept` and its route and
variables are withdrawn, which answers "nothing records a bucket that stopped being named".

**Evidence**: the survey's single-valued spots: `Buckets.name(project, service)` in about twenty
places, `ObjectStorageStatus` one block, `StorageReport` one case class, `ServiceRows` one
`bucket`, `CrdSchemaSuite` testing nested blocks only where named. 039's R8 read the status as
memory for a move for the same reason.

**Rules at apply** (`ServiceSpec.problems`): a name is `[a-z0-9-]{1,32}`, unique; `origins` only
with `signed`; `exposure` not with `supplied`; `supplied` needs all four fields and a credential
that passes `ProjectSecrets.nameProblems`; the derived Garage name `<project>.<service>.<name>`
within 63 characters (control plane, since it knows the project, as `Buckets.problems` today).
`buckets` without `provisionObjectStorage` is accepted. A supplied bucket's secret is checked at
apply for the two entries by name through the project's secrets, never by value.

## R8. One storage credential per service, named for the service, granted on every made bucket

**Decision**: The Garage key keeps its name, `<project>.<service>` (`#<n>` after re-issue), which
`Buckets.keyName` now declares as the *service's key name*, no longer the unnamed bucket's name
that happens to equal it. `StorageCredential.ensure`, `reissue`, `pauseWrites` and `resumeWrites`
take the list of the service's made buckets and allow, deny or re-allow on each. `EnsureBucket`
carries the key name, so the executor's "no key allowed ⇒ allow" allows *that* key, and
`ObjectStorageObservation.keyAllowed` becomes "the service's key is among the allowed", which also
stops `garage-copy`'s hourly key from reading as the service's. A service with named buckets and
no unnamed one has a key all the same.

**Evidence**: the survey's traps (a) to (e) on `OP/StorageCredential.scala` and
`OP/Executor.scala`; `kustomization/components/garage-copy/cronjob.yaml` lines 85–91 allowing a
key on every bucket.

**On Google Cloud Storage**: the `bucket-credential` request's `bucket` parameter becomes
`buckets`, comma-separated: one identity granted on each, one HMAC key (R10).

## R9. A public bucket on Garage is the web listener behind a rewriting route

**Decision**: The `garage` component gains `[s3_web] bind_addr = "[::]:3902"`, a `web` port on its
Service and a NetworkPolicy rule admitting the gateway to it; the operator gains
`ANKKA_OBJECT_STORE_WEB_SERVICE` beside `ANKKA_OBJECT_STORE_SERVICE`. For a `public` bucket the
operator calls `UpdateBucket` with `websiteAccess { enabled: true }` (a new `ObjectStore.setWebsite`)
and renders an `HTTPRoute` at `storage.<base>` with `PathPrefix /<bucket>/`, a `URLRewrite` filter
setting the hostname to the bucket's name and replacing the prefix with `/`, to the web port. The
public address is `https://storage.<base>/<bucket>`. Nothing else in the config changes: no
`root_domain`, so no bucket is reachable by a hostname of its own.

**Evidence**: `kustomization/components/garage/config.yaml` has no `[s3_web]` on purpose ("a
bucket is served to nobody without a signature"); Garage's web endpoint picks the bucket by the
`Host` header, matching the bucket's name itself when `root_domain` does not apply
([Exposing buckets as websites](https://garagehq.deuxfleurs.fr/documentation/cookbook/exposing-websites/));
Gateway API's `URLRewrite` has `hostname` and `path.replacePrefixMatch`, and Envoy Gateway
implements both. `OP/Rendering.bucketRoute` (line 658) already renders a path-prefixed route to
the S3 port for a signed bucket.

**To verify first (S2)**: a `Host` with dots (`shop.listings.photos`) is matched by Garage's web
endpoint as a bucket name, and Envoy rewrites it; and that the web endpoint answers an object's
`Content-Type` as kept.

**Alternatives**: a reader of the platform's own (a small image signing reads) is a fifth image
for what the store does already; a hostname per bucket needs a certificate per bucket, which the
wildcard does not cover (034's reasoning).

## R10. A public bucket on Google Cloud Storage is the provider's, by a parameter

**Decision**: The `bucket` request gains `name` (the bucket's descriptor name, absent for the
unnamed bucket) and `public` (`true`/`false`); a public fulfilment also answers `publicUrl`. The
provider makes a public bucket with public access prevention `inherited` and `allUsers` as
`roles/storage.objectViewer` under uniform bucket-level access, and answers `publicUrl` as
Google's address or, when the installation configures a content delivery network in front, that
network's. The `bucket-credential` request's `bucket` becomes `buckets`. A service's named bucket
request is `<service>-bucket-<name>`. These are amendments to 044's contract
(`docs/platform/cloud-provider.md`) and to `ankka-gcp`, which this repository cannot build; the
scripted provider implements them, and `GherkinSuite.ranOutside` names the Google-only scenarios
for ankka-gcp's nightly suite as 039 did.

**Evidence**: `OP/CloudRequests.Keys.parameters` is a closed set per kind with no exposure
(`CloudRequestsSuite` holds renderers to it); 039's FR-009 makes public access prevention the
provider's unconditional rule; `CloudSubject(project, service)` names one service, so a second
bucket needs its own request name (`Requester.name(suffix)`). The scripted provider is keyed by
`(kind, subject)` and is test code (`operator/src/test/.../cloud/`).

## R11. A move moves every made bucket, as one move

**Decision**: `StorageMove` keeps one state for the service; the mover takes parallel lists
(`MOVER_SOURCE_BUCKETS`, `MOVER_TARGET_BUCKETS`, comma-separated, the shared endpoints and the
one credential each side) and copies then verifies each pair in turn; the pause is `DenyBucketKey`
on every bucket; `Switched` switches them all. A bucket named during a move is made in the target
store once the move has switched, and is `Waiting` until then.

**Evidence**: `OP/StorageMove.scala` is a pure state machine with one `Act.Copy(bucket)`;
`storage-mover/Main.scala` reads one `MOVER_SOURCE_*`/`MOVER_TARGET_*` pair; the spec's FR-010.

## R12. The directory store, and the service serving its own addresses

**Decision**: With no `ANKKA_S3_ENDPOINT` and formation not `bootstrap` (the runtime's test for
"running locally", `RT/Ankka.scala` lines 443–451), `ankka.storage.backend` defaults to
`directory`, at `ANKKA_STORAGE_DIR` or `.ankka/storage/` under the working directory, one
directory per bucket (`default/` for the unnamed one), each object as `<name>` with a sidecar
`<name>.meta.json` holding content type and kept-at. The buckets a local service has are the
descriptor's when `service.json` is beside the working directory (read with a small reader in
`runtime`, not `controlplane-api`), else any name asked for. Signed URLs and public addresses are
`http://<interface>:<port>/_ankka/storage/<bucket>/<name>?…` with an HMAC token for a signed one
and none for a public one, served by a built-in endpoint `DirectoryStorageEndpoint` at the
reserved prefix `/_ankka/storage`, registered by the runtime only when the backend is `directory`.
A deployed service has no such endpoint, so the path answers 404 there.

**Evidence**: `ankka local` runs no JVM service (`cli/.../Main.scala` lines 1516–1540: `web` and
`console` only), so "on a laptop" is `sbt run`; `/_ankka/health` is the one reserved path today
(`Router.handle` lines 460–491); feature 054 plans `rootRoutes` on `HttpEndpoint` for absolute
paths, and this endpoint merges onto that seam when both land. Python and TypeScript do the same
in the SDK: their directory store's endpoint is an ordinary endpoint the SDK registers at
`/_ankka/storage`, served through the sidecar like any route, so no sidecar change is needed; the
sidecar's `Router` must admit that one prefix.

**Alternatives**: `ankka local` serving the directory from its own port was clarified away; a
`file://` address is useless to a page.

## R13. The in-memory store in the test kits

**Decision**: `AnkkaTestKit.start(…, storage: StorageBackendChoice = Memory)` where `Memory`
holds a `MemoryObjectStorage` the kit owns and hands to each `ServiceBuilder` through `configure`
(`ServiceBuilder.withStorage`), so it survives `restartService()` and is dropped by `stop()`.
`kit.storage` is the same `ObjectStorage` the service holds; `kit.declareBucket(name, exposure)`
adds buckets when no `service.json` is read. The Python and TypeScript kits hold an
`InMemoryBuckets` the test assigns as `component.storage = …` (unit) and the integration kits set
`ANKKA_STORAGE_BACKEND=memory` in the process's own environment. The Rust integration kit's
module reaches the runtime image's store, which is a directory inside the container; the unit
kit's `NativeHost` answers the `storage` import from a `HashMap`.

**Evidence**: `AnkkaTestKit.restartService` rebuilds `AnkkaService` on the same database, and
in-memory state survives only outside it, re-handed through `configure` (survey §5);
`InMemorySecretStore` and `ConsumerTestKit.of(…, secrets = …)` are the unit precedent; the Rust
unit kit's `with_config` installs a `NativeHost` answering only `Config` and panics on the rest.

## R14. The Python and TypeScript clients

**Decision**: Python: `ankka/storage.py` with `Buckets`, `Bucket` (async: `put`, `get`, `delete`,
`list`, `stat`, `signed_url`, `url`), SigV4 over `http.client` run with `asyncio.to_thread`,
`hashlib` and `hmac` from the standard library, streams as async iterables of `bytes`;
`InMemoryBuckets`; `DirectoryBuckets`; the `HasStorage` mixin on the six kinds `HasSecrets` is
on; `Workflow.storage` guarded by `_in_step`. TypeScript: `storage.ts` with the same names, SigV4
over `node:http`/`node:https` with `Content-Length` set and the body piped, `node:crypto`'s
`createHmac`, streams as `ReadableStream<Uint8Array>`; getter/setter pairs as `secrets` has. No
dependency is added to either package. The rules fixture is `protocol/fixtures/storage/rules.json`
(object names, bucket names, the signed-URL bound), read by each SDK's tests as `secrets/rules.json`
is.

**Evidence**: Python's core dependencies are `grpcio`, `protobuf`, `rfc8785` and the SDK is
asyncio; `httpx` is a testkit extra only. TypeScript already uses `node:crypto` and global
`fetch`; a streamed upload with `fetch` needs `duplex: 'half'` and sends chunked, which S3's
`PutObject` refuses, so `node:http` with a known length. `HasSecrets` is mixed into `Endpoint`,
`Consumer`, `GraphConsumer`, `TimedAction`, `Agent`, `AutonomousAgent`; `server.py` lines 875–886
set `instance.secrets`.

## R15. Settings and variables

**Decision**: runtime `reference.conf` gains `ankka.storage { backend = ${?ANKKA_STORAGE_BACKEND}
(s3 | directory | memory; default s3 when ANKKA_S3_ENDPOINT is set, else directory), directory =
${?ANKKA_STORAGE_DIR}, timeout = 30s, max-signed-url = 7d, default-signed-url = 15m }`. The S3
settings are read from the environment through a `StorageSettings.from(config, env)` as
`ReplicationSettings.from` and `KafkaConnection.declaredFromEnv` read their families:
`ANKKA_S3_{ENDPOINT,REGION,BUCKET,ACCESS_KEY,SECRET_KEY,PUBLIC_ENDPOINT}` for the unnamed bucket,
`ANKKA_S3_BUCKET_<NAME>`, `ANKKA_S3_EXPOSURE_<NAME>` (`private`, `signed`, `public`),
`ANKKA_S3_PUBLIC_URL_<NAME>` for a made named bucket, and the five `ANKKA_S3_*_<NAME>` for a
supplied one. `PlatformVariables` declares `ANKKA_STORAGE_BACKEND` and `ANKKA_STORAGE_DIR` as the
developer's program's (in no routing list, like `ANKKA_S3_`), so the operator renders them nowhere
and `PlatformDeclarationSuite` is satisfied. A named bucket's exposure travels as a variable
because the client refuses a signed URL or `url` by exposure without a round trip.

**Evidence**: no runtime `Settings` class exists; HOCON with `${?ENV}` is the convention (survey
§2); `PlatformVariables.scala` is compiled into the operator by `unmanagedSources` and must stay
standard-library only; the new names fall outside `runtimeOnly` so they reach the process and
not the sidecar, which is right.

## R16. Errors, names and the bound

**Decision**: `BadRequest` for a name, a prefix, a length or a span that breaks a rule, and for a
signed URL or `url` the bucket's exposure forbids; `Unavailable` when the store cannot be reached,
for `ObjectStorage.unavailable`, for a service with no bucket (naming `provisionObjectStorage`),
for a name the descriptor does not give (naming the names it does), and for a service with no
credential (naming `objectStorageCredential`); `Internal` for an answer the client cannot parse.
`get` and `stat` answer `None` on 404, never throw. The object name rule is the spec's FR-003;
the signed-URL bound is 7 days and the default 15 minutes. No new `ErrorCode`.

**Evidence**: `SecretStore`'s codes; a new `ErrorCode` case breaks three exhaustive matches
(`HttpProblem.from`, gRPC, the sidecar's `pb.ErrorCode`).

## R17. What moves to the limitations page, and the docs

**Decision**: `docs/build/object-storage.md` (new, in the Build section after `secrets.md`, in the
`ankka-endpoints` and `ankka-workflows` skills' pages) with samples from
`modules/testkit/src/test/.../StorageComponents.scala` and the three SDK conformance examples;
`docs/platform/object-storage.md` gains "Named buckets" and "A public bucket"; the descriptor
reference gains the `buckets` table; the limitations page drops "No storage client in the SDKs"
and adds: no directories, rename, copy, append or partial writes; a put declares its length; a
module's put and get carry a whole object of at most 4,000,000 bytes; a signed URL spans at most
seven days; a gRPC endpoint has no client; a supplied bucket is checked only on its first call.
`docs/reference/configuration.md`'s generated table gains the `ankka.storage` keys and the prose
mentions each.

**Evidence**: `docs.md`'s rules; the limitations page's current line 94.

## R18. CI, the conformance suite and the k3s suites

**Decision**: `ConformanceSuite` gains `storage.*` cases driving `/conformance/storage?…` routes
in the four references against the Scala reference's memory store in-process, and against a
Garage container for the `s3` backend under `-Dankka.conformance.storage=garage`; the sidecar
`ConformanceTarget` for a process sets `ANKKA_STORAGE_BACKEND` and the `ANKKA_S3_*` variables.
`features/storage-client/` is run by a new `StorageClientFeatures` in `testkit`'s tests (memory
and directory, every scenario that needs no cluster), `StorageClientClusterFeatures` in
`controlplane`'s tests on k3s (named buckets, public buckets on Garage, the move, the status; it
runs unlisted through `.github/cluster-suites.py`), and `ranOutside` for the Google-only ones.
`ci-coverage.py` needs no new top-level directory. The S3 client's own suite, `S3BucketSuite`,
runs against the Garage image testcontainers starts, in `runtime`'s tests.

**Evidence**: `.claude/rules/testing.md` on living features and `GherkinSuite`; the survey's map of
which suite runs `features/object-storage/`; the cluster matrix is derived from the files.
