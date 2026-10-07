# Contract: the sidecar protocol

What a process sees. One minor version, written `1.N` here: 1.7, or 1.8 if
025-polyglot-service-client is on `main` when this slice starts. Decisions are in
[research.md](../research.md) (R18). Every change is an addition; field numbers are the next free
ones and are fixed when the slice is written.

## `discovery.proto`

```protobuf
message Tool {
  string name = 1; string description = 2; string input_schema_json = 3;
  optional Approval approval = 4;                 // 1.N: unset is "runs when called"
}

message Approval { optional int64 within_millis = 1; }   // unset: waits until decided

message McpServer {                               // 1.N
  string name = 1;                                // [a-z0-9-]+
  oneof address {                                 // unset: ANKKA_MCP_<NAME>_URL must be set
    string url = 2;
    Service service = 3;
  }
  optional Approval approval = 4;                 // every tool of the server
  repeated Header headers = 5;
  message Service { optional string project = 1; string name = 2; string path = 3; }
  message Header  { string name = 1; string variable = 2; }   // variable starts ANKKA_MCP_
}

message AgentDetail {
  // … 1–4 unchanged …
  repeated McpServer mcp_servers = 5;             // 1.N
  repeated string result_guardrails = 6;          // 1.N: names the process answers CheckGuardrail for
}

message AutonomousAgentDetail {
  // … 1–8 unchanged …
  repeated McpServer mcp_servers = 9;             // 1.N
  repeated string result_guardrails = 10;         // 1.N
}
```

The sidecar refuses, in discovery's report, what the Scala definition refuses where it is built
(a server's name, one name twice, a header's variable, a tool named `mcp__…`, a result guardrail
declared twice), and fails its start for what the Scala runtime fails its start.

## `agent.proto`

```protobuf
message GuardrailRequest {
  // … 1–6 unchanged …
  optional string tool = 7;                       // 1.N: with RESULT, the tool whose result this is
  enum Stage { INPUT = 0; OUTPUT = 1; RESULT = 2; }   // 1.N: RESULT
}
```

`AgentPlan` is unchanged: a plan names the process's own tools, and the runtime adds the MCP
servers' tools to every plan. `InvokeTool` is never sent for an MCP tool. A tool the process
declared with `approval` is invoked only after it is approved.

## `client.proto`

```protobuf
service Client {
  // … unchanged …
  rpc Decide (DecideRequest) returns (InvokeReply);   // 1.N
}

message InvokeReply {
  oneof result {
    Outcome.Reply reply = 1;
    Error error = 2;
    ApprovalAwaited approval = 3;                 // 1.N: the turn waits
  }
}

message ApprovalAwaited { repeated ApprovalRequest requests = 1; }

message ApprovalRequest {
  string id = 1; string tool = 2; string arguments_json = 3;
  int64 requested_at_millis = 4; optional int64 expires_at_millis = 5;
}

message DecideRequest {
  Kind kind = 1;                                  // AGENT or AUTONOMOUS_AGENT
  string component_id = 2;
  string entity_id = 3;                           // the session, or the instance
  string name = 4;                                // AGENT: the handler that was called
  string approval_id = 5;
  bool approved = 6;
  string by = 7;                                  // required
  optional string note = 8;
  Metadata metadata = 9;
}

message StreamToken {
  // … unchanged cases …
  ApprovalAwaited approval = N;                   // 1.N: terminal, in place of completed
}
```

For an autonomous agent `Decide` answers an empty reply once the decision is recorded. A refusal
is an `Error` in the reply with the codes of [scala-api.md](scala-api.md), never a gRPC status.

An older runtime answers `Decide` with gRPC `UNIMPLEMENTED`; each SDK reports that as needing
protocol 1.N, as it does for the secret calls. An SDK older than 1.N reads an `approval` reply as
no result; it cannot have declared a tool that requires approval, so it meets one only when it
calls an agent of a newer service.

## A process's stream route

**Verify first** (R7): the frame a process's stream route sends the sidecar, and whether it can
carry an event's name. If it cannot, the frame gains an optional name in this version, so a
process can send the approval request as the event `approval`, as a Scala endpoint does.

## Modules

No export or import is added, and `WASM-ABI.md` does not change. The Rust crate's copy of the
protocol changes with the canonical one, and its message literals name the new fields at their
defaults.

## Fixtures

`protocol/fixtures/autonomous/` gains `agent-notification-approval-requested.json`, and
`agent-state.json` gains `awaiting`, written by `AutonomousFixturesSuite` and copied to the three
SDKs.

## Conformance cases

Against the Scala reference and each of the Python and TypeScript references; a Rust module
skips them (`onlyForProcesses()`).

| Case | Holds |
|---|---|
| `approval.awaits` | a call to a tool with `approval` answers the caller with the request; the tool has not run |
| `approval.approved-runs-once` | approved after the process is restarted: the tool runs once, the model is sent its result |
| `approval.refused-tells-model` | the model's next request carries the refusal and the note |
| `approval.decided-once` | a second decision is `CONFLICT` |
| `approval.session-waits` | a new request on the session is `CONFLICT` |
| `approval.needs-a-name` | a decision with no `by` is `BAD_REQUEST` and decides nothing |
| `auto.approval.waits-without-budget` | no model call and no iteration while awaiting |
| `auto.approval.approved-continues` | the tool runs once and the task completes |
| `mcp.tools-offered` | the model's request lists `mcp__tickets__create` and `mcp__tickets__search` |
| `mcp.call-reaches-server` | the scripted server saw the arguments; the model was sent its answer |
| `mcp.server-approval` | a call to a server with `approval` waits; the server saw nothing |
| `mcp.result-guardrail-withholds` | the process's `RESULT` check blocks; the model is sent an error, not the text |
| `svc.tool-calls-service` | waits for 025: a tool calls a scripted service through `Request` |

The suite starts two `TestMcpServer`s and gives the sidecar their addresses as
`ANKKA_MCP_TICKETS_URL` and `ANKKA_MCP_GUARDED_URL`; each reference declares the servers
`tickets` and `guarded` by name with no address, `guarded` as requiring approval.

## Files a version bump touches

From feature 023's bump: `WireProtocol.Version`; `Discovery`'s comment and `ProtocolSuite`;
`Compatibility.Protocol` with `CompatibilitySuite` and `HostingSuite`; the three SDK constants
and their tests; `protocol/README.md` and its three copies; the three SDK copies of the protos
and fixtures; `docs/reference/sidecar-protocol.md` (two mentions, the generated rows, the history
paragraph) and the rendered skills' copies of it; each SDK's "since" constant for the new call.
