# Contract: the sidecar protocol at 1.15

"1.15" is the next minor as of planning. Features 046 and 047 take a minor too, on sibling
branches; the first to merge is 1.15 and the others renumber on rebase.

Canonical copy `protocol/src/main/protobuf/ankka/protocol/v1/`; the three SDK copies are kept
identical by CI's `diff -r`.

## client.proto

```proto
service Client {
  // ... as at 1.14 ...

  // Waits for a workflow's end and answers it: the final state as a reply, or an Error whose code
  // is WORKFLOW_FAILED with the step and the reason in details, or TIMEOUT when timeout_millis
  // passed first. A runtime older than 1.15 answers UNIMPLEMENTED; the SDK says which protocol
  // waiting needs. Since 1.15.
  rpc AwaitEnd (AwaitEndRequest) returns (InvokeReply);

  // The same wait as a stream: a heartbeat token while it waits, then one ended or failed token.
  // For an endpoint serving the wait as server-sent events past the runtime's idle timeout. Since 1.15.
  rpc AwaitEndStream (AwaitEndRequest) returns (stream StreamToken);
}

message AwaitEndRequest {
  string   component_id   = 1;   // the workflow's component id
  string   entity_id      = 2;   // the workflow id
  int64    timeout_millis = 3;   // the caller's; required, > 0
  Metadata metadata       = 4;   // the handler's, so the wait is attributed and traced
}

message StreamToken {
  oneof token {
    string          text      = 1;
    Empty           completed = 2;
    Error           failed    = 3;
    ApprovalAwaited approval  = 4;
    Empty           heartbeat = 5;   // 1.15: the wait goes on
    Payload         ended     = 6;   // 1.15: the workflow completed with this state
  }
}
```

## payload.proto

```proto
enum ErrorCode { INTERNAL = 0; BAD_REQUEST = 1; UNAUTHORIZED = 2; FORBIDDEN = 3; NOT_FOUND = 4; CONFLICT = 5; TIMEOUT = 6; UNAVAILABLE = 7; WORKFLOW_FAILED = 8; }

message Error {
  string              message = 1;
  ErrorCode           code    = 2;
  map<string, string> details = 3;   // 1.15: a WORKFLOW_FAILED carries step (when one failed), reason, deleted ("true")
}
```

## Semantics

- `AwaitEnd` answers `InvokeReply.reply` with the state exactly as the journal holds it: for a
  process's workflow, the `Payload` the process wrote as its last state; for a JVM workflow, its
  state serializer's bytes under its manifest and content type. The sidecar takes both from the
  engine's reply metadata (`ankka-manifest`, `content-type`), since the request carries no payload.
- `AwaitEnd` answers `InvokeReply.error`: `WORKFLOW_FAILED` with `details`; `TIMEOUT` when the
  caller's time passed (the workflow runs on); `BAD_REQUEST` for a non-positive timeout or an
  unknown component; `UNAVAILABLE` when every instance that could answer is from before 1.15.
- `AwaitEndStream` emits `heartbeat` every heartbeat interval (a third of the runtime's HTTP idle
  timeout), then exactly one `ended` or `failed` (`failed` carries `TIMEOUT` at the deadline), and
  completes. A client that cancels the stream ends the wait; the workflow runs on.
- The runtime's hold-and-re-ask loop is the runtime's; a process never sees `NotYet`.
- A runtime at 1.14 or earlier answers both rpcs with gRPC `UNIMPLEMENTED`. Each SDK maps that to
  its too-old error naming protocol 1.15, in the words it uses for recurring timers.
- Nothing about waiting is declared in `Spec`; discovery is unchanged at 1.15.

## Version

`WireProtocol.Version = "1.15"` (`Conversation.scala`), `Compatibility.version = ProtocolVersion(1,
15)`, `PROTOCOL_VERSION = "1.15"` in `service.py`, `spec.ts`, `service.rs`; `protocol/README.md`
"## Version" gains the 1.15 line; `docs/reference/sidecar-protocol.md` § Versioning likewise, and
its two sentences still saying `1.12` are corrected to the current version.

## The module's import (WASM-ABI.md)

| import | in | out | semantics |
|---|---|---|---|
| `await_end` | `AwaitEndRequest` | `InvokeReply` | `Client.AwaitEnd`; blocks the calling instance until answered, for the whole wait. Since protocol 1.15. |

In its own link block in the crate (`abi/imports.rs`), dispatched by `call_await`, so a module that
never waits does not import it. No stream import: a module cannot stream.
