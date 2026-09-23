# Contract: the sidecar protocol, `ankka.protocol.v1`

**Feature**: [spec.md](../spec.md) | **Data model**: [data-model.md](../data-model.md) | **Research**: R2–R7, R10, R13

The protocol is the platform's promise to every SDK. It is versioned on its own (`1.0` here),
carried in discovery, and the sidecar refuses a major it does not speak. Within a major, fields
are only ever added, with defaults that mean "as before". The artifact is the `protocol/`
directory: `src/main/protobuf/ankka/protocol/v1/*.proto`, `ENCODING.md` and `fixtures/`, copied
verbatim into every SDK.

## Directions and addresses

| service | implemented by | dialled by | address |
|---|---|---|---|
| `Discovery`, `EventSourced`, `KeyValue`, `Workflow`, `View`, `Consumer`, `TimedAction`, `Endpoint`, `Agent` | the developer's process | the sidecar | `127.0.0.1:${ANKKA_PROCESS_PORT}` (default 9010) |
| `Client` | the sidecar | the developer's process | `127.0.0.1:${ANKKA_SIDECAR_PORT}` (default 9011) |

Both servers bind loopback only. A connection from any other address is refused at bind time,
not filtered later (FR-009).

## `payload.proto`

```proto
syntax = "proto3";
package ankka.protocol.v1;

message Payload {
  string content_type = 1;   // application/json, text/plain, application/octet-stream (ENCODING.md)
  string manifest = 2;       // the serializer's manifest; what JournalRecord stores
  bytes  data = 3;           // never inspected by the sidecar
}

message Metadata { repeated Entry entries = 1; message Entry { string key = 1; string value = 2; } }

enum ErrorCode {
  INTERNAL = 0; BAD_REQUEST = 1; UNAUTHORIZED = 2; FORBIDDEN = 3;
  NOT_FOUND = 4; CONFLICT = 5; TIMEOUT = 6; UNAVAILABLE = 7;
}

message Error { string message = 1; ErrorCode code = 2; }

// A fault in the process — the handler threw, the payload could not be decoded. Not a refusal.
message Failure { int64 command_id = 1; Error error = 2; }

// The three cases of core.effect.Outcome, as data.
message Outcome {
  oneof outcome {
    Reply   reply    = 1;
    NoReply no_reply = 2;
    Error   error    = 3;   // a refusal: nothing is persisted, the caller sees the code
  }
  message Reply   { Payload payload = 1; Metadata metadata = 2; }
  message NoReply {}
}

message Retention {
  oneof retention { DeleteNow delete_now = 1; ExpireAfter expire_after = 2; }
  message DeleteNow {}
  message ExpireAfter { int64 millis = 1; }
}

message Empty {}
```

## `discovery.proto`

```proto
service Discovery {
  rpc Discover (SidecarInfo) returns (Spec);
  rpc ReportError (Problem) returns (Empty);   // why the sidecar refused; lands in the process's log
}

message SidecarInfo { string protocol_version = 1; string runtime_version = 2; }

message Spec {
  string protocol_version = 1;             // "1.0"
  SdkInfo sdk = 2;
  repeated Component components = 3;
  repeated Endpoint endpoints = 4;
}
message SdkInfo { string name = 1; string version = 2; }

enum Kind { EVENT_SOURCED_ENTITY = 0; KEY_VALUE_ENTITY = 1; WORKFLOW = 2; VIEW = 3;
            CONSUMER = 4; TIMED_ACTION = 5; AGENT = 6; }

message Component {
  Kind   kind = 1;
  string id = 2;
  repeated Handler handlers = 3;
  oneof detail {
    EventSourcedDetail event_sourced = 10;
    KeyValueDetail     key_value     = 11;
    WorkflowDetail     workflow      = 12;
    ViewDetail         view          = 13;
    ConsumerDetail     consumer      = 14;
    TimedActionDetail  timed_action  = 15;
    AgentDetail        agent         = 16;
  }
}
message Handler { string name = 1; bool read_only = 2; bool streaming = 3; }

message EventSourcedDetail { int32 snapshot_every = 1; }          // 0: never
message KeyValueDetail {}
message WorkflowDetail { repeated string steps = 1; }
message Source { oneof source { ComponentRef component = 1; string topic = 2; }
                 message ComponentRef { Kind kind = 1; string id = 2; } }
message ViewDetail { Source source = 1; string row_manifest = 2; repeated string queries = 3; }
message ConsumerDetail { Source source = 1; optional string produces_to = 2; }
message TimedActionDetail {}
message AgentDetail {
  string role = 1; int32 max_tool_call_steps = 2;
  repeated Tool tools = 3; repeated string guardrails = 4;
}
message Tool { string name = 1; string description = 2; string input_schema_json = 3; }

message Endpoint {
  string id = 1;                 // for log lines and the console; unique within the Spec
  string prefix = 2;             // as HttpEndpoint's prefix: "/carts"
  Acl    acl = 3;
  repeated Route routes = 4;
  enum Acl { ALLOW_ALL = 0; DENY_ALL = 1; AUTHENTICATED = 2; }
}
message Route {
  string id = 1;                 // unique within the endpoint; the sidecar sends it back on each request
  string method = 2;             // GET, POST, PUT, DELETE, PATCH
  string template = 3;           // HttpEndpoint's syntax: "/{cartId}/items"; relative to prefix
  bool   has_body = 4;
  bool   streaming = 5;          // served as SSE via Endpoint.HandleStream
}
message Problem { string message = 1; }
```

Discovery is retried by the sidecar with backoff until it succeeds; the sidecar is not ready until
it has (FR-013). A `Spec` the sidecar refuses ends the sidecar process after `ReportError`, with
every problem in the message (S1.7). Route templates are validated with the same parser and the
same conflict rules `HttpServer.validate` applies to a Scala endpoint.

## `event_sourced.proto`

```proto
service EventSourced { rpc Handle (stream EventSourcedIn) returns (stream EventSourcedOut); }

message EventSourcedIn {
  oneof message { Init init = 1; Event event = 2; Command command = 3; }
  message Init {
    string component_id = 1; string entity_id = 2;
    optional Snapshot snapshot = 3;             // absent: fresh, or deleted, or expired
  }
  message Snapshot { int64 sequence = 1; Payload payload = 2; }
  message Event    { int64 sequence = 1; Payload payload = 2; }
  message Command  {
    int64 id = 1;                               // per stream, from 1, monotonic
    string name = 2;                            // the handler's wire name
    Payload payload = 3; Metadata metadata = 4;
    bool snapshot_requested = 5;                // include `snapshot` in the reply
  }
}

message EventSourcedOut {
  oneof message { Reply reply = 1; Failure failure = 2; }
  message Reply {
    int64 command_id = 1;
    repeated Payload events = 2;
    optional Retention retention = 3;
    Outcome outcome = 4;
    optional Payload snapshot = 5;              // the state after `events`, when requested
  }
}
```

Sequence of one stream: `Init`, zero or more `Event` in ascending sequence, then `Command`s, each
answered before the next is sent. The sidecar closes the stream on passivation; the process
releases the state on close. On a `Failure` or a violation the sidecar fails the command, closes
the stream and restarts the instance, which replays. A reply to a command the sidecar has already
timed out is ignored.

**Rules the sidecar enforces**: `command_id` equals the id in flight; `events` empty when the
handler was discovered `read_only`; `outcome.error` with non-empty `events` is a violation (a
refusal persists nothing, by construction in Scala and by rule here); `snapshot` present only when
requested.

## `key_value.proto`

```proto
service KeyValue { rpc Handle (stream KeyValueIn) returns (stream KeyValueOut); }

message KeyValueIn {
  oneof message { Init init = 1; Command command = 2; }
  message Init    { string component_id = 1; string entity_id = 2; optional Payload state = 3; }
  message Command { int64 id = 1; string name = 2; Payload payload = 3; Metadata metadata = 4; }
}
message KeyValueOut {
  oneof message { Reply reply = 1; Failure failure = 2; }
  message Reply {
    int64 command_id = 1;
    optional Payload new_state = 2;             // absent: unchanged
    optional Retention retention = 3;
    Outcome outcome = 4;
  }
}
```

## `workflow.proto`

```proto
service Workflow { rpc Handle (stream WorkflowIn) returns (stream WorkflowOut); }

message WorkflowIn {
  oneof message { Init init = 1; Command command = 2; RunStep run_step = 3; }
  message Init    { string component_id = 1; string entity_id = 2; optional Payload state = 3; }
  message Command { int64 id = 1; string name = 2; Payload payload = 3; Metadata metadata = 4; }
  message RunStep { int64 id = 1; string step = 2; optional Payload input = 3; }
}
message WorkflowOut {
  oneof message { Reply reply = 1; StepReply step_reply = 2; Failure failure = 3; }
  message Reply {                               // a command handler's WorkflowEffect
    int64 command_id = 1; optional Payload new_state = 2;
    optional StepRef transition = 3; Outcome outcome = 4;
  }
  message StepReply {                           // a step's WorkflowStepEffect
    int64 command_id = 1; optional Payload new_state = 2; StepOutcome next = 3;
  }
}
message StepRef { string step = 1; optional Payload input = 2; }
message StepOutcome {
  oneof outcome { StepRef transition_to = 1; Pause pause = 2; End end = 3; Error fail = 4; }
  message Pause { optional int64 after_millis = 1; optional StepRef on_timeout = 2; }
  message End {}
}
```

The sidecar's `WorkflowEngine` is unchanged: it journals the transition, then sends `RunStep`; a
step that produces no reply within the step timeout is `StepTimedOut` exactly as today; the
process is expected to call other components *during* a step through `Client.Invoke`, on its own
tasks, and to reply when the step is done. A step is never run twice concurrently on one stream —
but a *command* may arrive while a step is running, since the engine keeps answering commands
(a status query during a long step is the point of steps being asynchronous), so one command and
one step may be in flight at once. The process answers such a command from its state as it was
before the step; the step's `new_state` applies when the step replies, as the engine journals it.
`WorkflowDetail.settings` in discovery carries the timeouts and recovery the engine enforces, since
the process cannot: absent, the engine's defaults (no overall limit, 30s a step, a failed step fails
the workflow); a `failover_to` must name a declared step, which runs with no input.

## `view.proto`, `consumer.proto`, `timed_action.proto`

```proto
service View { rpc Handle (ViewRequest) returns (ViewEffect); }
message ViewRequest { string component_id = 1; Payload event = 2; Metadata metadata = 3;
                      optional Payload row = 4;                        // the current row, if any
                      bool deleted = 5; }                              // the source was deleted; no event
message ViewEffect { oneof effect { Payload update_row = 1; Empty delete_row = 2; Empty ignore = 3; } }

service Consumer { rpc Handle (ConsumerRequest) returns (ConsumerEffect); }
message ConsumerRequest { string component_id = 1; Payload message = 2; Metadata metadata = 3;
                          bool deleted = 4; }
message ConsumerEffect { oneof effect { Produce produce = 1; Empty done = 2; Empty ignore = 3; }
                         message Produce { Payload payload = 1; Metadata metadata = 2; } }

service TimedAction { rpc Invoke (TimedActionRequest) returns (TimedActionEffect); }
message TimedActionRequest { string component_id = 1; string name = 2; Payload payload = 3; Metadata metadata = 4; }
message TimedActionEffect { oneof effect { Empty done = 1; Error fail = 2; } }
```

A gRPC error (as opposed to an `Error` in the effect) from any of these is a fault: the projection
retries with the backoff it uses for a thrown handler today; the timed action is retried on the
sweeper's schedule with the attempt counter incremented.

## `endpoint.proto`

```proto
service Endpoint {
  rpc Handle       (HttpRequest) returns (HttpReply);
  rpc HandleStream (HttpRequest) returns (stream StreamFrame);
}

message HttpRequest {
  string endpoint_id = 1; string route_id = 2;       // from discovery
  repeated string path_args = 3;                     // in template order
  repeated Pair query = 4;                           // repeatable, in request order
  repeated Pair headers = 5;
  string content_type = 6; bytes body = 7;           // empty when the route has no body
  optional Principal principal = 8;                  // when the ACL is AUTHENTICATED
  Metadata metadata = 9;                             // trace and span ids for this request's span
  message Pair { string name = 1; string value = 2; }
}
message Principal { string subject = 1; optional string name = 2; optional string email = 3;
                    bool email_verified = 4; repeated string roles = 5; }
message HttpReply {
  oneof message { HttpResponse response = 1; Failure failure = 2; }
}
message HttpResponse { int32 status = 1; string content_type = 2; bytes body = 3; repeated HttpRequest.Pair headers = 4; }
message StreamFrame { oneof frame { string text = 1; Empty completed = 2; Error failed = 3; } }
```

The sidecar's `Router` does everything it does for a Scala endpoint before forwarding: matches the
route by specificity, applies the ACL (401/403/503 never reach the process), opens the request
span, and only then calls `Handle` on a virtual thread with the request's arguments. An
`HttpResponse` is returned as-is, whatever its status; a `Failure` is a 500 carrying the message;
a gRPC error or a timeout is a 503 and the span is `Failed`. A streaming route's `text` frames are
JSON-encoded into SSE by the existing path, so a process never has to know the SSE rules. The
process is expected to call components during a handler through `Client.Invoke` with the
request's `metadata`, so the entity's span is the request span's child.

## `agent.proto`

```proto
service Agent {
  rpc Plan           (PlanRequest)      returns (PlanReply);
  rpc InvokeTool     (ToolRequest)      returns (ToolResult);
  rpc CheckGuardrail (GuardrailRequest) returns (GuardrailResult);
}

message PlanRequest { string component_id = 1; string session_id = 2; string name = 3;
                      Payload payload = 4; Metadata metadata = 5; }
message PlanReply {
  oneof message { AgentPlan plan = 1; Failure failure = 2; }
}
message AgentPlan {                             // AgentEffect, as data
  optional string model = 1;                    // a name in the sidecar's configuration
  optional string system = 2; optional string user = 3;
  repeated string context = 4;
  Memory memory = 5;
  repeated string tools = 6;                    // names declared in discovery
  ResponseShape response_shape = 7;
  repeated string guardrails = 8;               // names declared in discovery
  optional Error failure = 9;                   // effects.error(...) — a refusal before any model call
  enum Memory { SESSION = 0; NONE = 1; }
  message ResponseShape { oneof shape { Empty text = 1; Json json = 2; }
                          message Json { string schema_hint = 1; } }
}
message ToolRequest  { string component_id = 1; string session_id = 2; string tool = 3; string arguments_json = 4; }
message ToolResult   { oneof result { string ok = 1; string error = 2; } }   // FunctionTool.invoke's Either
message GuardrailRequest { string component_id = 1; string session_id = 2; string guardrail = 3;
                           Stage stage = 4; string text = 5; enum Stage { INPUT = 0; OUTPUT = 1; } }
message GuardrailResult  { oneof result { Empty pass = 1; string block = 2; } }
```

The sidecar runs the loop: it calls the model, dispatches each tool call to `InvokeTool`, feeds the
result back, checks guardrails at the declared stages, keeps the session in its own memory
entity, compacts, and counts tokens. A streaming handler (declared `streaming` in discovery) is
planned the same way; tokens go to the caller through the sidecar, never to the process.

## `client.proto` (served by the sidecar)

```proto
service Client {
  rpc Invoke       (InvokeRequest) returns (InvokeReply);
  rpc InvokeStream (InvokeRequest) returns (stream StreamToken);
  rpc Query        (QueryRequest)  returns (QueryReply);
  rpc Schedule     (ScheduleRequest) returns (Empty);
  rpc Cancel       (CancelRequest)   returns (Empty);
}
message InvokeRequest { Kind kind = 1; string component_id = 2; string entity_id = 3;
                        string name = 4; Payload payload = 5; Metadata metadata = 6; }
message InvokeReply   { oneof reply { Outcome.Reply reply = 1; Error error = 2; } }
message StreamToken   { oneof token { string text = 1; Empty completed = 2; Error failed = 3; } }
message QueryRequest  { string view_id = 1; string name = 2; Payload payload = 3; }
message QueryReply    { oneof reply { Payload rows = 1; Error error = 2; } }
message ScheduleRequest { string timer_id = 1; int64 delay_millis = 2; Kind kind = 3;
                          string component_id = 4; optional string entity_id = 5;
                          string name = 6; Payload payload = 7; }
message CancelRequest { string timer_id = 1; }
```

`metadata` carries the trace and span ids the SDK received with the command or request it is
handling, so the sidecar records the nested call as a child span. An SDK that drops them produces
an orphan span, which the console shows as such and never re-parents (feature 007's rule).

## `ENCODING.md` and `fixtures/`

Part of the protocol artifact, versioned with it. `ENCODING.md` is the table in
[data-model.md](../data-model.md#encoding-protocolencodingmd-r13). Every SDK's default codec
passes every fixture; a new fixture within a major is a minor.

## Versioning

`protocol_version` is `MAJOR.MINOR`. The sidecar supports one major; a `Spec` with another is
refused with both versions named (FR-008, S5.2). A new optional field, message, rpc or fixture is
a minor. Renaming, removing or changing the meaning of anything, or changing the encoding of a
shape the fixtures already cover, is a major, which this feature never does.
