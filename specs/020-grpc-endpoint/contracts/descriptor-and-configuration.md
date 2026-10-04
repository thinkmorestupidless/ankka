# Contract: descriptor, resource and configuration

What a member writes, what the control plane refuses, and what a process reads. Decisions are in
[research.md](../research.md) (R10, R11, R16).

## Descriptor

```json
{
  "name": "cart",
  "service": {
    "image": "cart:1.4.0",
    "grpc": true,
    "grpcPort": 9090
  }
}
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `grpc` | boolean | `false` | the service serves gRPC |
| `grpcPort` | integer | `9090` | the port it serves gRPC on; ignored when `grpc` is false |

Absent means no gRPC. `http` and `port` are unchanged, and independent: `"http": false, "grpc": true`
is a service that serves gRPC only.

**Refused when applied**, each added to `ServiceSpec.problems`:

| Condition | Problem |
|---|---|
| `grpcPort` < 1 or > 65535 | `service grpcPort <n> is outside the range 1-65535` |
| `grpc` and `http`, `grpcPort == port` | `grpcPort <n> is also the service port; gRPC and HTTP are served on different ports` |
| an env var named `ANKKA_GRPC_PORT` | `env var 'ANKKA_GRPC_PORT' conflicts with the service grpcPort; declare the grpcPort instead` |
| `grpc` and a name over 52 characters | `service name '<n>' is <len> characters; a service that serves gRPC has a name of at most 52` |
| `grpc` with `hosting` `process` or `wasm` | `only an embedded service serves gRPC; remove "grpc" or use embedded hosting` |
| `grpc` with a declared `runtime` below `Compatibility.GrpcSince` | `runtime <declared> does not serve gRPC; it is served from <GrpcSince>` |

**Exposing**: `ExposureRules` refuses a service that serves neither HTTP nor gRPC —
`service '<n>' serves no HTTP and no gRPC; there is nothing to expose`. A service with gRPC and
no HTTP can be exposed.

`ServiceStatus` is unchanged. An exposed service's `hostname` is the one hostname, for both.

## Resource

`AnkkaServiceSpec.grpcPort: Option[Int] = None`, projected from `resolvedGrpcPort`, and declared in
`kustomization/components/crd/ankkaservice.yaml`:

```yaml
grpcPort:
  type: integer
  minimum: 1
  maximum: 65535
```

## What a process reads

| Environment | Configuration key | Default | Set by |
|---|---|---|---|
| `ANKKA_GRPC_PORT` | `ankka.grpc.port` | `9090` | the operator, from the resource; never a descriptor |
| `ANKKA_GRPC_INTERFACE` | `ankka.grpc.interface` | `0.0.0.0` | the developer |
| — | `ankka.grpc.max-message-size` | `4MiB` | the developer |
| — | `ankka.grpc.max-connection-age` | `2m` | the developer |
| — | `ankka.grpc.shutdown-grace` | `5s` | the developer |
| — | `ankka.grpc.keepalive-time` | `30s` | the developer |
| — | `ankka.grpc.keepalive-timeout` | `10s` | the developer |
| — | `ankka.local-grpc-services."<name>"` | unset | the developer: where a named service's gRPC is, on this machine |

The module's `reference.conf` declares all of them, so the generated configuration table lists
them and the page's prose must describe each.

**A process started with `ANKKA_GRPC_PORT` set and no `GrpcServer` registered does not start.** It
writes this to `/dev/termination-log` and to its log, and exits before forming a cluster:

```text
the descriptor declares gRPC (ANKKA_GRPC_PORT=9090) and this service registers no gRPC endpoint:
register GrpcServer.of(…), or remove "grpc" from the descriptor
```

## What a running service says about itself

`GET /observability/service` (the local console's inventory) gains, per instance, beside `http`:

```json
"grpc": { "address": "127.0.0.1:51234" }
```

and lists each gRPC method among `routes` as
`{"method":"GRPC","path":"<service definition>/<method>","streaming":<bool>}`. The local console
shows a `GRPC` row and offers no way to call it.
