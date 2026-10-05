---
paths:
  - "modules/grpc/**"
  - "modules/grpc-fixtures/**"
  - "samples/shopping-cart-api/**"
  - "features/grpc/**"
  - "features/grpc-deployed/**"
---

# gRPC endpoints

## Traps

- **A cluster IP balances connections, and a gRPC channel keeps one for minutes.** Every call from one
  caller would reach one instance, and a new instance would see none. The platform's client resolves
  the headless `<service>-grpc-peers` and balances per call (`round_robin`); the server's two-minute
  connection age is what makes it re-resolve. That name can be another service's address, and Services
  are applied with forced ownership, so it goes through `EnsureGrpcPeers`, which reads first and never
  takes over an object it does not own.
- **grpc-java answers a request marshaller that throws with `UNKNOWN: Application error processing
  RPC`.** Every method is re-bound with a pass-through byte marshaller and ankka parses, so a request
  that is not one is `INVALID_ARGUMENT` and no handler runs. `ServerServiceDefinition` insists on the
  descriptor's own method instances, so the descriptor is rebuilt, schema descriptor kept for reflection.
- **An `UNAVAILABLE` raised by the transport is not the called service's refusal.** A handshake that
  failed ends a call `UNAVAILABLE` too; reading every such status as `CommandError(Unavailable)` handed
  a handler a refusal nobody made. Only a status with no local cause — one the service sent — is a
  refusal. And under BoringSSL a trust manager's "peer identity" reason sits beneath a handshake failure
  whose own message is "General OpenSslEngine problem", so the whole cause chain is read.
- **`concat(Source.failed(…))` fails the stream before the parts ahead of it exist.** `concat`
  materializes its second source at once, and a failed source fails at once; in Pekko a failure also
  travels ahead of parts still in flight. A fixture that means "these parts, then a refusal" refuses when
  the next part is asked for — throwing in a `map` over one more element — as a stream over a component
  that refuses mid-stream does. Two runs were spent blaming the platform's queue sink for this.
- **The HTTP/2 window is counted in bytes and grows to megabytes.** A stream of tiny parts can rightly be
  produced whole before anyone reads it, so a backpressure test that counts tiny parts fails a correct
  server. The flow-control cases use 16 KiB parts.
- **A deadline spent connecting never reaches a handler.** A 200 ms deadline on a channel's first call
  can expire during the handshake, so a case about a handler outliving its caller opens the connection
  with one call first.
- **A bound address is read as an HTTP address by every reader** — the local console's invoke panel, the
  HTTP service client's local lookup — so gRPC's is `RuntimeExtension.grpcAddress`, beside it.
