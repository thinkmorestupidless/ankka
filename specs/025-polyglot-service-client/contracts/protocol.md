# Contract: the sidecar protocol at 1.7

What a process sees. Decisions are in [research.md](../research.md) (R5 to R9).

## `client.proto`

```protobuf
service Client {
  // … the eight calls of 1.6, unchanged …

  // A call to another service, made by the runtime as this service (protocol 1.7). The runtime
  // resolves the service, presents this service's certificate and checks the other's; the
  // process never holds a key. A refusal or a fault is in the reply, never a gRPC status, so
  // that a module's import can carry it the same way.
  rpc Request (ServiceRequest) returns (ServiceReply);
}

message ServiceRequest {
  string service = 1;                         // the service's name
  optional string project = 2;                // absent: this service's own project
  string method = 3;                          // "GET", "POST", …
  string path = 4;                            // starts with "/", query included
  repeated HttpRequest.Pair headers = 5;      // in order; the platform's own are replaced
  optional string content_type = 6;
  optional bytes body = 7;                    // absent: no body
  Metadata metadata = 8;                      // what the handler asking was given
}

// Exactly one case is set. A reply with none is a fault.
message ServiceReply {
  oneof result {
    HttpResponse response = 1;                // the service answered, whatever its status
    ServiceFailure failure = 2;               // the call got no answer from the service
    Error error = 3;                          // the runtime refused to make the call
  }
}

message ServiceFailure {
  Reason reason = 1;
  string detail = 2;                          // what was tried, for the developer
  enum Reason {
    UNANSWERED = 0;                           // refused connection, or no answer in time
    UNRESOLVABLE = 1;                         // no such service was found; nothing was sent
    IDENTITY_MISMATCH = 2;                    // what answered is not the service; nothing was sent
  }
}
```

`HttpRequest.Pair` and `HttpResponse` are `endpoint.proto`'s, unchanged: a status, a content type,
a body and headers. `client.proto` imports `endpoint.proto`.

`UNANSWERED` is the zero value on purpose: an enum value a reader does not know is read as zero,
and "no answer" is the one reading of an unknown failure that sends nothing twice.

### The three results

| Result | When | What the SDK does |
|---|---|---|
| `response` | the service answered, with any status | the raw request returns it; a typed helper raises *call failed* for a status outside 2xx |
| `failure` | `UNRESOLVABLE`, `IDENTITY_MISMATCH`, `UNANSWERED` | raises the error of that name |
| `error` | the runtime refused before resolving anything | raises `CommandError` with the code |

### Refusals (`error`)

| Case | `ErrorCode` | The message names |
|---|---|---|
| the caller is an entity's handler | `BAD_REQUEST` | the component and its kind |
| the caller is a workflow's command handler | `BAD_REQUEST` | "a step" |
| the service name or the project is not a valid name; the path does not start with `/`; the method is empty | `BAD_REQUEST` | the field |
| the request's body is over the limit | `BAD_REQUEST` | the limit |
| the answer's body is over the limit | `INTERNAL` | the limit and the service |
| the process declared a protocol below 1.7 | `BAD_REQUEST` | both versions |

### Who is calling

`metadata` is what the handler was given, forwarded unchanged, as on `InvokeRequest`. The runtime
reads the caller from `ankka-caller` and believes it only when it is a component and handler this
service declared:

- a declared handler of an **entity**, or a **command** handler of a workflow: refused, above;
- any other declared handler: the call is made as that handler — counted from it, and traced
  under its span when the metadata carries a trace;
- no caller, or a name the service did not declare: the call is made, and counted from the
  unknown caller. A call made outside any handler is this case.

### Headers

Before the request is sent the runtime removes, whoever set them and in any letter case: every
header whose name starts `X-Ankka-`; `Forwarded` and every `X-Forwarded-*`; `Host`,
`Content-Length` and `Expect`; and the hop-by-hop headers (`Connection`, `Keep-Alive`,
`Proxy-Authenticate`, `Proxy-Authorization`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`).
`Content-Type` is taken from `content_type` and not from `headers`. Every other header is sent
as given, in order. The answer's headers are returned as the service sent them.

### Bounds

- A request's body and an answer's body are each at most 4,000,000 bytes. The protocol's messages
  are bounded at 4 MiB by every gRPC implementation in use, and the remainder is room for
  headers.
- The whole body is sent and the whole answer read; there is no stream in either direction.
- One call, never repeated and never redirected. How long it waits is the service's
  `ankka.service-client.timeout`; the request carries no timeout.

## Version

- The protocol version is `1.7`. A 1.6 process runs unchanged.
- A process built for 1.7 calling a 1.6 runtime gets gRPC `UNIMPLEMENTED`; each SDK reports it as
  the runtime's protocol being too old to call another service, naming both versions.
- A process that declared a version below 1.7 in discovery and sends `Request` all the same is
  answered `error` with `BAD_REQUEST`, naming the version it declared and `1.7`.
- No import is added to the `ankka1` module. A module cannot call another service until the
  import that carries `ServiceRequest` and `ServiceReply` is added; these two messages are the
  ones it will carry.

## The sidecar

`ClientLogic` gains `request`, over `AnkkaService.services`. It runs on a virtual thread, as the
secret store's calls do, inside the caller and the trace the metadata names. `ClientService`
calls it and nothing else. The certificate is read by the runtime in the sidecar's container; no
message carries it and the process is given none.

## Conformance cases

Each drives a route of the reference service's `ConformanceEndpoint`, which makes the call it is
asked for through the SDK's client and answers what it got. The suite starts the **scripted
service** — an HTTP server on loopback that records each request and answers as the case told it
— before the target, and the target's runtime is started knowing where it is
(`ankka.local-services`). For a process target that runtime is the sidecar's wiring in the
suite's own JVM, so nothing crosses a container.

| Case | Shows |
|---|---|
| `service.request-reaches-target` | the scripted service received the method, path, query, content type, body and a header the handler set |
| `service.answer-reaches-handler` | the handler received the status, content type, body and a header the scripted service set |
| `service.refusal-is-the-answer` | a 403 with a body reaches the handler as status and body through the raw request; the typed helper raises *call failed* carrying both |
| `service.unresolvable` | a name the runtime was told nothing about raises *unresolvable*, naming it; the scripted service received nothing |
| `service.unanswered` | a name told to be at a port nothing listens on raises *unanswered*, not *unresolvable* |
| `service.platform-headers-replaced` | an `X-Ankka-Caller` and a `Host` the handler set did not reach the scripted service |
| `service.identity-mismatch` | a service whose certificate names another raises *identity mismatch*; the scripted service received nothing |
| `service.counted-from-handler` | the service's topology counts the call from the route that made it |

The cases run against the in-process Scala reference and against a process; a module target skips
them (`assume(!target.isModule, …)`), because a module has no import for the call yet.

The refusal of an entity's handler is not a conformance case. No SDK gives an entity a client to
call with, so a reference could only reach it by going round its own SDK; the rule is the
sidecar's, and `ClientRequestSuite` (`sidecar`) sends the request an entity's handler would send
and reads the refusal.

`service.identity-mismatch` cannot be produced with certificates, because a conformance run has
none. The target is started with a client that answers for the name `impostor` by raising the
runtime's own identity mismatch and passes every other name to the real client
(`ServiceBuilder.withServices`), so what is proven is what the case is for: that the failure
crosses the sidecar and reaches the handler under its own name in every language. The handshake
that raises it for real is `ServiceClientSuite`'s.

The reference service gains one route in each language, declared in the conformance contract's
route table: `POST /conformance/service-call?service=…&method=…&path=…&mode=raw|typed`, whose body
and `X-Conformance-*` headers are sent on, and which answers `200` with a JSON record of what the
client returned or raised: `{"outcome": "response" | "failed" | "unresolvable" | "mismatch" |
"unanswered" | "refused", "status": …, "contentType": …, "body": …, "headers": {…}, "message": …}`.

`discovery.lists-every-component` is unchanged: no component is added.
