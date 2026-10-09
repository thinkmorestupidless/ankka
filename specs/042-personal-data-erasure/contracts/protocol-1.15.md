# Contract: protocol 1.15

Held by `features/erasure/languages.feature` and the sidecar's `personal.*` conformance cases. A
minor bump: every message of 1.14 is unchanged; an SDK on an older sidecar reports `UNIMPLEMENTED`
on the new rpcs as "sidecar too old", and the sidecar refuses a `Spec` declaring an erasure handler
under 1.14.

## `client.proto` (the sidecar serves the process)

```proto
service Client {
  // 1.15
  rpc SubjectKeys(stream KeyChannelIn) returns (stream KeyChannelOut);
  rpc LookupToken(LookupTokenRequest) returns (LookupTokenReply);
  rpc EraseObjects(EraseObjectsRequest) returns (EraseObjectsReply);
}
message KeyChannelIn  { oneof in  { Fetch fetch = 1; Ack ack = 2; Completed completed = 3; } }
message KeyChannelOut { oneof out { Key key = 1; Destroyed destroyed = 2; Refused refused = 3; Apply apply = 4; Closed closed = 5; } }
message Fetch     { string subject = 1; string project = 2; bool create = 3; }
message Key       { string subject = 1; string project = 2; bytes key = 3; int64 expires_millis = 4; }
message Destroyed { string subject = 1; string project = 2; string erasure_id = 3; }
message Refused   { string subject = 1; string project = 2; string reason = 3; }
message Apply     { string erasure_id = 1; string subject = 2; bool reapply = 3; }
message Ack       { string erasure_id = 1; }
message Completed { string erasure_id = 1; ErasureHandleReply handler = 2; }
message Closed    { string reason = 1; }
message LookupTokenRequest { bytes plaintext = 1; }
message LookupTokenReply   { string token = 1; }
message EraseObjectsRequest { string subject = 1; }
message EraseObjectsReply   { oneof result { Erased erased = 1; Error error = 2; } }
message Erased { int64 count = 1; int64 final_at_millis = 2; }
```

- One `SubjectKeys` stream per process for its life; the sidecar's `KeyringClient` is the cache and
  the channel; `Key.expires_millis` is the sidecar's cache expiry so the process's mirror expires
  with it.
- `Apply` is pushed only to a process whose `Spec` declared `erasure_handler`; the sidecar does the
  platform's duties itself and sends `Completed` after the process's `Handle` answers (or at once
  when the process has no handler).

## `erasure.proto` (the process serves the sidecar)

```proto
service Erasure { rpc Handle(ErasureHandleRequest) returns (ErasureHandleReply); }
message ErasureHandleRequest { string subject = 1; string erasure_id = 2; bool reapply = 3; map<string,string> metadata = 4; }
message ErasureHandleReply   { oneof outcome { Done done = 1; Failed failed = 2; } }
message Done   { string detail = 1; Erased objects = 2; }
message Failed { string reason = 1; }
```

`discovery.proto`: `Spec.erasure_handler = 30` (bool). A reply is one `Handle` per application; a
throw or a timeout (`ankka.erasure.handler-timeout`, 5 minutes) is a failed application retried
on the next sweep.

## WASM-ABI (modules)

Imports, each `(ptr, len) -> i64` carrying the protobuf messages above, each in its own `extern`
block in the crate: `subject_key` (`Fetch` → `Key | Refused`; the host holds the channel and the
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
