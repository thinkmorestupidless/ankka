# Contract: the sidecar protocol at 1.9

One edit to `protocol/src/main/protobuf/ankka/protocol/v1/`, copied into the three SDKs by their
own scripts and held identical by CI.

## `discovery.proto`

```proto
message Route {
  // … fields 1 to 7 unchanged
  bool socket = 8;   // 1.9: answered by opening a socket, via Http.HandleSocket
}
```

Rules, each a refusal at discovery naming the endpoint and the route:

- `socket` with a `method` other than `GET`, with `has_body`, or with `streaming`.
- `socket` under a `protocol_version` whose minor is below 9:
  "endpoint '<id>': route '<id>' is a socket route, which needs protocol 1.9; the SDK speaks <v>".
- in module mode, any `socket`:
  "endpoint '<id>': route '<id>' is a socket route, and a module answers a request whole; a
  module cannot hold a socket".

## `endpoint.proto`

```proto
service Http {
  rpc Handle       (HttpRequest) returns (HttpReply);
  rpc HandleStream (HttpRequest) returns (stream StreamFrame);
  rpc HandleSocket (stream SocketIn) returns (stream SocketOut);   // 1.9
}

message SocketIn {
  oneof message {
    HttpRequest  open   = 1;   // first, once: the request that opened the socket
    SocketFrame  frame  = 2;   // a frame the client sent
    SocketClosed closed = 3;   // last: the socket is closed; the runtime then half-closes
  }
}

message SocketOut {
  oneof message {
    SocketFrame frame     = 1;   // a frame for the client
    Empty       completed = 2;   // the handler returned: close "finished"
    Error       failed    = 3;   // the handler threw: close "failed"
  }
}

message SocketFrame  { oneof kind { string text = 1; } }
message SocketClosed { string reason = 1; }   // a close reason's name, or "client" for the client's own close
```

## The conversation

1. The runtime decides the ACL, upgrades, and opens the call. It sends `open`: `endpoint_id`,
   `route_id`, `path_args`, `query`, `headers`, `principal` when the ACL authenticated, `caller`,
   and `metadata` carrying the trace, `ankka-caller` and `ankka.protocol`. No `body`.
2. Each client frame is one `frame`, in order. The runtime sends the next only when the call can
   take it, so a process that does not read is not buffered for without bound.
3. Each `frame` from the process is one frame to the client, in order.
4. **The process ends it**: `completed`, then the stream's end. The client is closed "finished".
   Ending the stream without `completed` is the same. `failed`, or the call ending in an error,
   closes the client "failed".
5. **The other side ends it**: the runtime sends `closed` and half-closes. The process's handler
   is told the socket is closed; whatever it sends afterwards is discarded.
6. A message with no case set, in either direction, is a protocol violation: the socket is
   closed "failed". It is never skipped.

## Versions

- `WireProtocol.Version` and each SDK's `PROTOCOL_VERSION` are `1.9`;
  `Compatibility.Protocol.version` is `(1, 9)`.
- A 1.6 process declares no socket route and is hosted exactly as before.
- An SDK sent a `SidecarInfo.protocol_version` below 1.9 fails discovery when it has a socket
  route: "this runtime speaks protocol <v>; a socket route needs 1.9".
- An SDK asked to `Handle` or `HandleStream` a socket route answers a failure saying the same.

## Conformance cases

Run against the Scala reference, the Python and TypeScript examples through a sidecar, and
skipped for a module (`onlyWhereStreaming()`). A module's refusal is not a conformance case: no
module built with the crate can declare a socket route, so `WasmHostSuite` builds the declaration
by hand.

| Case | Asserts |
|---|---|
| `socket.frames-in-order` | ten frames each way, in order |
| `socket.request-context` | a path argument and a query parameter read after the third frame |
| `socket.principal-and-caller` | an authenticated route with the suite's issuer: subject, role, caller |
| `socket.token-as-subprotocol` | the same with no header and the token offered as a subprotocol |
| `socket.challenged-without-token` | 401 with the challenge; the reference's counter says no handler ran |
| `socket.closed-when-handler-returns` | close code 1000 |
| `socket.failed-when-handler-throws` | close code 1011 |
| `socket.handler-told-of-client-close` | the reference records the close; a following request reads it |

The reference and each example gain `/conformance/socket/{room}` (echoes each frame, answers
`context` with `<room> <tag>` from the opening request's query, and logs `closed:<room>` when told
the socket closed), `/conformance/socket-once` (reads one frame and returns),
`/conformance/socket-fail` (reads one frame and throws), `/conformance/socket-log` (the log, as a
JSON array of strings), and `/private/socket` (logs `private:<subject>`, then sends
`{"subject", "roles", "caller"}` once, with `caller` as `/callers/whoami` words it).
