# Contract: protocol 1.15

Held by `features/erasure/languages.feature` and the sidecar's `personal.*` conformance cases. A
minor bump: every message of 1.14 is unchanged; an SDK on an older sidecar reports `UNIMPLEMENTED`
on the new rpcs as "sidecar too old", and the sidecar refuses a `Spec` declaring an erasure handler
under 1.14.

## `client.proto` (the sidecar serves the process)

```proto
service Client {
  // 1.15
  rpc FetchSubjectKey  (KeyFetch) returns (KeyAnswer);
  rpc SubjectKeyEvents (Empty)    returns (stream SubjectDestroyed);
  rpc LookupToken      (LookupTokenRequest)  returns (LookupTokenReply);
  rpc EraseObjects     (EraseObjectsRequest) returns (EraseObjectsReply);
}
message KeyAnswer { oneof out { SubjectKey key = 1; SubjectDestroyed destroyed = 2; SubjectRefused refused = 3; } }
message KeyFetch         { string subject = 1; string project = 2; bool create = 3; }
message SubjectKey       { string subject = 1; string project = 2; bytes key = 3; int64 expires_millis = 4; }
message SubjectDestroyed { string subject = 1; string project = 2; string erasure_id = 3; }
message SubjectRefused   { string subject = 1; string project = 2; string reason = 3; bool unavailable = 4; }
message LookupTokenRequest { bytes plaintext = 1; }
message LookupTokenReply   { oneof result { string token = 1; Error error = 2; } }
message EraseObjectsRequest { string subject = 1; }
message EraseObjectsReply   { oneof result { ErasedObjects erased = 1; Error error = 2; } }
message ErasedObjects { int64 count = 1; int64 final_at_millis = 2; }
```

- *Amended in implementation:* the bidirectional `SubjectKeys` stream of the plan became a unary
  `FetchSubjectKey` and a server stream `SubjectKeyEvents`. A codec is synchronous in every SDK, so
  a key it lacks is fetched with one blocking call (Python a synchronous gRPC stub, TypeScript a
  worker thread under `Atomics.wait`, a module the `subject_key` import); a stream cannot be read
  that way. An erasure reaches a process only through `Erasure.Handle`, so `Apply`, `Ack` and
  `Completed` were dropped: the sidecar does the platform's duties itself, calls the handler, and
  reports the completion on its own channel.
- An empty `project` in a fetch means the process's own; the answer names it, which is how a
  process learns its project. `create` is honoured only for the process's own project.
- `SubjectKey.expires_millis` is the sidecar's cache expiry, so the process's copy expires with it.
- `SubjectRefused.reason` is `unknown` for a subject never written asked without `create` (written
  as erased, never created by a read), a grant's refusal otherwise; `unavailable` marks an outage,
  which a codec reports as one and never as an erasure.

## `erasure.proto` (the process serves the sidecar)

```proto
service Erasure { rpc Handle(ErasureHandleRequest) returns (ErasureHandleReply); }
message ErasureHandleRequest { string subject = 1; string erasure_id = 2; bool reapply = 3; map<string,string> metadata = 4; }
message ErasureHandleReply   { oneof outcome { ErasureDone done = 1; ErasureFailed failed = 2; } }
message ErasureDone   { string detail = 1; ErasedObjects objects = 2; }
message ErasureFailed { string reason = 1; }
```

`discovery.proto`: `Spec.erasure_handler = 30` (bool). A reply is one `Handle` per application; a
throw or a timeout (`ankka.erasure.handler-timeout`, 5 minutes) is a failed application retried
on the next sweep.

## WASM-ABI (modules)

Imports, each `(ptr, len) -> i64` carrying the protobuf messages above, each in its own `extern`
block in the crate: `subject_key` (`KeyFetch` → `SubjectKeyReply`, `key | refused`; the host holds the channel and the
cache, so a destroyed subject answers `Refused("erased")`), `lookup_token`, `erase_objects`.
Export: `ankka1_erase(ptr, len) -> i64` (`ErasureHandleRequest` → `ErasureHandleReply`), listed in
`ModuleLoader.Exports` and called on the blocking pool with a `CallSite` that permits `request`.
`subject_key` is permitted from every call site (a codec runs everywhere); `erase_objects` only from
`ankka1_erase`. A module that imports `subject_key` is refused at load by a runtime before 1.15.

## Version sites

`protocol/README.md` (history: "1.15 — personal fields: `SubjectKeys`, `LookupToken`,
`EraseObjects`, the `Erasure` service, `Spec.erasure_handler`; imports `subject_key`, `lookup_token`,
`erase_objects`; export `ankka1_erase`"), `Compatibility.scala`, `Conversation.scala`,
`sdks/python/src/ankka/service.py`, `sdks/typescript/src/spec.ts`, `sdks/rust/ankka/src/service.rs`,
and the five tests that pin them; the three protocol copies through each SDK's copy script; the
template `service.json` through `{{protocol_version}}`.
