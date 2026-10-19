# Contract: the sidecar protocol

What a process or module sees. One minor version, `1.20`. Decision R13 in
[../research.md](../research.md). Every change is an addition; field numbers are the next free ones
and are fixed when the slice is written. The version is bumped in `WireProtocol.Version`,
`Compatibility.version`, `protocol/README.md`, the three SDK constants, the three pin tests, and
the three proto copies are regenerated.

## `discovery.proto`

```protobuf
message Spec {
  // … 1–4 unchanged …
  repeated McpEndpoint mcp_endpoints = 5;         // 1.20
}

message McpEndpoint {                             // 1.20
  string id = 1;                                  // the SDK's name for it; reported, never routed on
  string path = 2;                                // "/mcp"
  string name = 3;                                // serverInfo.name; empty: the service's
  string version = 4;                             // serverInfo.version; empty: the image tag
  McpAcl acl = 5;                                 // required
  repeated McpTool tools = 6;                     // at least one
  bool brokered = 7;                              // brokered sign-in
}

message McpTool {                                 // 1.20
  string name = 1;                                // [a-z0-9_-]+, at most 128
  string description = 2;                         // non-blank
  string input_schema_json = 3;                   // a JSON Schema object; the SDK derived it, or the developer wrote it
  optional McpAcl acl = 4;                        // unset: the endpoint's
}

message McpAcl {                                  // 1.20: admitted when any set member admits
  bool allow_all = 1;
  bool deny_all = 2;
  bool tokens = 3;                                // Oidc.authenticate(): needs an issuer on the sidecar
  bool client_keys = 4;                           // ClientKeys.authenticate()
  repeated CallerMatcher callers = 5;             // allowCallers
}
```

The sidecar refuses in discovery's report what the Scala endpoint refuses in `problems`: a path
that is reserved or claimed by a route or another MCP endpoint, no tool, a bad or repeated tool
name, a blank description, a schema that is not a JSON object, an `McpAcl` with nothing set or
with `deny_all` beside anything, `tokens` with no issuer configured (as `AUTHENTICATED` is refused
today, `Discovery.scala:400-407`), `brokered` without `tokens` or without a client id for an issuer.
An SDK whose declared minor is below `1.20` and declares an `mcp_endpoints` entry is refused with
`McpSince` wording, last in the refusal order. A reference the SDK resolved into a tool is, to the
sidecar, a tool.

## `mcp.proto` (new)

```protobuf
syntax = "proto3";
package ankka.protocol.v1;

service Mcp {                                     // served by the process; called by the sidecar
  rpc InvokeTool (McpToolRequest) returns (McpToolReply);
}

message McpToolRequest {
  string endpoint_id = 1;
  string tool = 2;
  string arguments_json = 3;                      // a JSON object the sidecar already checked against input_schema_json's types
  optional Principal principal = 4;               // when the ACL authenticated: a token's or a client key's; never the credential
  optional Caller caller = 5;
  Metadata metadata = 6;                          // the trace and the tool as ankka-caller
}

message McpToolReply {
  oneof reply {
    McpToolAnswer ok = 1;
    string error = 2;                             // a tool error: the message the model reads
    Failure failure = 3;                          // the process could not run it; the sidecar answers a tool error naming the tool and notes the handler in the report
  }
}

message McpToolAnswer {
  string text = 1;
  optional string structured_json = 2;            // when the SDK's result type is a record or map
}
```

`Principal` and `Caller` are `endpoint.proto`'s. A client key's principal arrives as `subject`,
`name` (the label), `claims` (the attributes plus `kind = client-key` and `client_key = <id>`) and
`issuer = "client-key"`.

## `client.proto`

```protobuf
service Client {
  // … unchanged …
  rpc IssueClientKey  (IssueClientKeyRequest)  returns (IssueClientKeyReply);    // 1.20
  rpc ListClientKeys  (ListClientKeysRequest)  returns (ListClientKeysReply);    // 1.20
  rpc RevokeClientKey (RevokeClientKeyRequest) returns (RevokeClientKeyReply);   // 1.20
}

message IssueClientKeyRequest {
  string subject = 1;
  oneof expiry { int64 after_millis = 2; int64 at_epoch_millis = 3; bool never = 4; }   // one must be set
  optional string endpoint = 5;                   // the MCP endpoint's path; required when the service has several
  repeated string scope = 6;
  map<string, string> attributes = 7;
  optional string label = 8;
  Metadata metadata = 9;
}
message IssueClientKeyReply  { oneof reply { Issued issued = 1; Error error = 2; } message Issued { string id = 1; string client_key = 2; } }
message ListClientKeysRequest  { Metadata metadata = 1; }
message ListClientKeysReply    { repeated ClientKeySummary keys = 1; Error error = 2; }   // unset error: success
message ClientKeySummary {
  string id = 1; string subject = 2; map<string, string> attributes = 3; optional string label = 4;
  string endpoint = 5; repeated string scope = 6; int64 issued_at_millis = 7; optional int64 expires_at_millis = 8;
  optional int64 last_used_millis = 9; optional int64 revoked_at_millis = 10;
}
message RevokeClientKeyRequest { string id = 1; Metadata metadata = 2; }
message RevokeClientKeyReply   { Error error = 1; }
```

A refusal or a fault is an `Error` in the reply, never a gRPC status, so a module's import carries
it the same way (`client.proto:17-19`). An older runtime answers `UNIMPLEMENTED`, which each SDK
reports as the runtime being too old for client keys, as `secrets.py:61-68` does for secrets.

## The WebAssembly ABI (`protocol/WASM-ABI.md`)

| Export | Takes | Returns |
|---|---|---|
| `ankka1_mcp_tool(ptr, len)` | `McpToolRequest` | `McpToolReply` |

Added to `ModuleLoader.Exports`, to `CallSite.Permitted` (a tool may call other services), to
`WasmDiscovery.needs()` (required when the module declares an MCP endpoint), and to the Rust
`Export` enum and `service!` macro. A module without the export that declares an MCP endpoint is
refused at discovery.

| Import | Takes | Returns |
|---|---|---|
| `issue_client_key(ptr, len)` | `IssueClientKeyRequest` | `IssueClientKeyReply` |
| `list_client_keys(ptr, len)` | `ListClientKeysRequest` | `ListClientKeysReply` |
| `revoke_client_key(ptr, len)` | `RevokeClientKeyRequest` | `RevokeClientKeyReply` |

Each in a link block of its own in the Rust crate (`abi/imports.rs`, as `call_secret`), so a module
that issues no key imports none. `WasmImportsSuite` fails until each is in a list.

## The sidecar's part

`RemoteMcpEndpoint extends McpEndpoint(spec.path)`: the ACL from `McpAcl` (`tokens` →
`Oidc.authenticate(settings.auth)`; `client_keys` → `ClientKeys.authenticate()`; `callers` →
`Acl.allowCallers`; several → `Acl.anyOf`), tools from `McpTool` with `FunctionTool.raw`-style
specs and a run function calling `conversation.invokeMcpTool(RemoteMcpCall(endpointId, tool,
argumentsJson, principal, caller, metadata))` with `settings.requestTimeout`. `Main.build` mounts it
with the other endpoints; `McpRuntime` is added to the sidecar's extensions when any MCP endpoint
admits client keys or is brokered, with its descriptors registered. The `Client` rpcs are served by
`ClientLogic` through `service.extension[McpRuntime]`'s `ClientKeys`. The conformance target does the
same in `Sidecar` and `ModuleTarget`, `SidecarExtension` still first.
