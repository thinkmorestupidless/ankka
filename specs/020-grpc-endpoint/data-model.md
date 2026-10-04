# Data Model: gRPC Endpoints

Nothing here is stored. The feature adds no entity, no event, no table and no DDL: a gRPC endpoint
holds no state, as an HTTP endpoint holds none. What it adds is declared values, two fields of
desired state, and what a process says about itself. Decisions are in [research.md](research.md).

## Declared at registration (in memory, `ankka-grpc`)

### GrpcEndpoint

| Attribute | Type | Rule |
|---|---|---|
| `service` | `io.grpc.ServiceDescriptor` | its name is unique among a server's endpoints |
| `acl` | `Acl` | must be stated; no default |
| methods | `Vector[DeclaredMethod]` | exactly one per method of `service` |

### DeclaredMethod

| Attribute | Type | Rule |
|---|---|---|
| `descriptor` | `MethodDescriptor[Req, Res]` | one of `service`'s own |
| `kind` | unary, server stream, client stream, bidirectional | equals `descriptor.getType` |
| `acl` | `Option[Acl]` | `None` means the endpoint's |
| `run` | the handler, erased to bytes in and a result out | — |

**Validation** is at startup, all problems at once (contract `scala-api.md`). A `DeclaredMethod`'s
full name, `<service definition>/<method>`, is the only gRPC name ever interned by the recorder.

### Reflection (optional, one per server)

| Attribute | Type | Rule |
|---|---|---|
| `acl` | `Acl` | must be stated to opt in |

## A call (per call, never stored)

| Attribute | Source |
|---|---|
| `caller: Caller` | the client certificate under TLS; otherwise `Local`, or the caller a test names |
| `principal: Option[Principal]` | an `Acl.Authenticate` that allowed the call |
| `metadata` | the call's text metadata |
| trace id, span id | minted for the call; the root of what it causes |
| deadline | the caller's, enforced by grpc-java |

**How it ends** — one of: `OK`; a refusal (the eight statuses of research R7, or an ACL's);
`UNIMPLEMENTED`; `RESOURCE_EXHAUSTED`; `DEADLINE_EXCEEDED`; `CANCELLED` (the caller left);
`INTERNAL`. Recorded as `SpanOutcome.Ok`, `.Refused` or `.Failed`: a refusal is `Refused`,
whoever gave it.

**A stream's states**:

```text
outbound:  waiting for the caller ⇄ sending  →  completed | failed | cancelled
inbound:   idle  →(next)→  asked for one  →(part)→  idle  …  →  ended | cancelled
```

A part is never taken from a handler's stream while the caller cannot receive it, and never asked
of a caller until the handler asks.

## Desired state

### ServiceSpec (`controlplane-api`), two new fields

| Field | Type | Default | Rule |
|---|---|---|---|
| `grpc` | `Boolean` | `false` | refused with `process` or `wasm` hosting, and with a declared `runtime` below `Compatibility.GrpcSince` |
| `grpcPort` | `Int` | `9090` | 1–65535; differs from `port` when both are served; `ANKKA_GRPC_PORT` may not be declared |

Derived: `resolvedGrpcPort: Option[Int] = Option.when(grpc)(grpcPort)`.

Both fields have defaults the shared codec omits, so a descriptor stored before this feature
decodes unchanged and re-encodes to the same bytes. `ServiceApplied` events in the control plane's
journal carry a `ServiceSpec`; `EventCompatibilitySuite` pins that an old event still replays.

### AnkkaServiceSpec (`crd`), one new field

| Field | Type | Default | Rule |
|---|---|---|---|
| `grpcPort` | `Option[Int]` | `None` | declared in `ankkaservice.yaml`, or the API server refuses the resource |

`NON_ABSENT` inclusion means a resource without it is written exactly as before.

**Relationship**: `ServiceProjection.project` copies `resolvedGrpcPort` into `grpcPort`, beside
`port = resolvedPort`. The operator renders from `grpcPort` and knows nothing of `grpc`.

## Observed

Nothing new is reported by the operator. An exposed service's route is one object, so
`status.route` already says whether the gateway accepted it.

## What a running process says (`/observability/service`)

| Addition | Shape |
|---|---|
| per instance | `"grpc": {"address": "<host>:<port>"}`, absent when it serves none |
| per method | a `routes` entry with `"method": "GRPC"` and `"path": "<service definition>/<method>"` |

## Errors a caller of another service can be given

| Type | Module | When |
|---|---|---|
| `ServiceUnresolvable(service, reason)` | `sdk`, existing | no such service |
| `ServiceServesNoGrpc(service)` | `sdk`, new | the service exists and has no gRPC address |
| `ServiceIdentityMismatch(service, detail)` | `sdk`, existing | what answered is not that service |
| `StatusRuntimeException` with a `CommandError` cause | grpc-java / `core` | the called service refused |
