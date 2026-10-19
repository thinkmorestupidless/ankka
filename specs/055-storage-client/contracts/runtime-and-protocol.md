# Contract: the runtime, the HTTP bodies, the reserved path and the protocol

Decisions are in [research.md](../research.md) (R3, R4, R6, R12, R13, R15).

## Runtime configuration (`modules/runtime/src/main/resources/reference.conf`)

```hocon
ankka.storage {
  backend = ""                       # s3 | directory | memory; "" chooses s3 when ANKKA_S3_ENDPOINT (or a supplied bucket) is set, directory when run locally, else none
  backend = ${?ANKKA_STORAGE_BACKEND}
  directory = ".ankka/storage"
  directory = ${?ANKKA_STORAGE_DIR}
  timeout = 30s                      # one request to the store
  max-signed-url = 7d
  default-signed-url = 15m
}
```

`StorageSettings.from(config, env)` reads the `ANKKA_S3_*` families from the environment as
[descriptor-and-status.md](descriptor-and-status.md) lists them. A `backend` that is `s3` with no
`ANKKA_S3_ENDPOINT` and no supplied bucket stops the start naming the variable; `directory` whose
directory cannot be created or written stops the start naming it; `memory` is the test kit's. Run locally means the cluster formation is not `bootstrap`
(`RT/Ankka.scala`'s test); a deployed service with no variables is given
`ObjectStorage.unavailable` and serves nothing at `/_ankka/storage`. When
`ServiceBuilder.withStorage` was given, the runtime neither reads these settings nor builds a
store.

## `AnkkaService`

`val storage: ObjectStorage = ObjectStorage.unavailable`, built in `ServiceBuilder.host` beside
`secrets`, overridable by `ServiceBuilder.withStorage(store)` (the test kit's hook). Passed to
`initWorkflow`, `ProjectionRuntime`, `TimerRuntime`, `AgentRuntime` and `HttpServer` exactly where
`secrets` is passed.

## HTTP: streamed bodies (`modules/http`)

```scala
// request
final case class StreamedBody(contentType: String, length: Option[Long], body: InputStream)
def postStream[Out: ToResponse](path: String)(handler: StreamedBody => Out): Route
def putStream[Out: ToResponse](path: String)(handler: StreamedBody => Out): Route
// response
final case class Streamed(contentType: String, length: Option[Long], body: InputStream)   // ToResponse given
```

The router skips `toStrict` for a stream route and hands the entity's `dataBytes` as an
`InputStream` on the handler's virtual thread; `ankka.http.max-stream-bytes = 5g` bounds it, and a
larger body is `413`. A `Streamed` response is a `HttpEntity.Default` of the length when known,
else chunked; the stream is closed when the response completes or the client goes away. Nothing
changes for strict routes. The sidecar serves a process's endpoints through the same `Router`,
but a process's routes stay strict in this feature: a streamed route is Scala's, said in the docs.

## The reserved path

`/_ankka/storage/<bucket>/<name>` is served by `DirectoryStorageEndpoint`, an `HttpEndpoint` the
runtime registers when the backend is `directory`:

| Request | Answer |
|---|---|
| `GET` with a valid `?token=<hmac>&expires=<epoch>` or on a `public` bucket | the object, its content type, `Content-Length` |
| `PUT` with a valid token for `Keep` | keeps the body with its `Content-Type`; `200` |
| no token on a non-public bucket, expired or wrong token | `403` |
| not there | `404` |

The token is `HMAC-SHA256(key, "<bucket>/<name>|<access>|<expires>")` over a key the runtime
generates at start (and keeps in `.ankka/storage/.key` so a restart keeps URLs valid), hex. A
signed or public URL on a laptop is `http://<ANKKA_HTTP_INTERFACE or 127.0.0.1>:<port>/_ankka/storage/…`.
`HttpServer.validate` refuses a descriptor's endpoint whose prefix is `_ankka`. Python and
TypeScript register the same endpoint from the SDK when their store is a directory, served
through the sidecar as any route; the sidecar's `Router` admits the prefix for that endpoint.

## Protocol: the `storage` import (ABI 1.20, protocol 1.20)

`protocol/src/main/protobuf/ankka/protocol/v1/client.proto` gains:

```proto
message StorageRequest {
  optional string bucket = 1;
  enum Op { PUT = 0; GET = 1; DELETE = 2; LIST = 3; STAT = 4; SIGNED_URL = 5; URL = 6; }
  Op op = 2;
  string name = 3;                 // or the prefix for LIST
  string content_type = 4;
  optional bytes body = 5;         // at most 4000000 bytes
  enum Access { READ = 0; KEEP = 1; }
  Access access = 6;
  uint32 expires_seconds = 7;
  optional string continuation = 8;
}
message StorageReply {
  oneof reply {
    StoredObject object = 1; ObjectInfo info = 2; ObjectEntries entries = 3; string url = 4; Empty done = 5; Error error = 6;
  }
}
message StoredObject { string content_type = 1; bytes body = 2; uint64 length = 3; string last_kept = 4; }
message ObjectInfo { string name = 1; string content_type = 2; uint64 length = 3; string last_kept = 4; }
message ObjectEntries { repeated ObjectEntry entries = 1; optional string next = 2; }
message ObjectEntry { string name = 1; uint64 length = 2; string last_kept = 3; }
```

`protocol/WASM-ABI.md` lists `storage(ptr, len) -> i64` since 1.20, permitted from the same
`CallSite`s as `request`. `HostImports` answers it from `service.storage` on
`AnkkaExecutors.virtual`; `ModuleLoader.Imports` lists it; an older runtime refuses a module that
imports it at load, naming it. A `GET` of an object larger than the bound is `error` with
`BAD_REQUEST` naming the bound; the module is told before any byte is read (`stat` first).
Python and TypeScript do not use this rpc: they speak S3 from the variables.

## The test kits

| Kit | Hook |
|---|---|
| `AnkkaTestKit.start(…, storage = StorageBackendChoice.Memory)` | `kit.storage: ObjectStorage`; `kit.declareBucket(name, exposure)`; `Memory` survives `restartService()` |
| `ConsumerTestKit.of(…, storage = InMemoryObjectStorage())`, likewise the endpoint, workflow and timed-action kits | the double |
| Python `AnkkaTestKit.start(service)` | sets `ANKKA_STORAGE_BACKEND=memory` for the process; `kit.storage` |
| TypeScript `AnkkaTestKit` | the same |
| Rust `AnkkaTestKit::start` | the runtime image's directory store; `kit.storage()` reads it over `/_ankka/storage` |
