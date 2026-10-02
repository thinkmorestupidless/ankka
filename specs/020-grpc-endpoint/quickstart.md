# Quickstart: validating gRPC endpoints

The runs that show the feature works, cheapest first. Each names what it proves. Docker is needed
from run 3 on. Contracts are in [contracts/](contracts/); decisions in [research.md](research.md).

## 0. The three spikes, before anything is built on them

```bash
sbt 'sidecar/test'                                              # the bumped grpc-java and ScalaPB
cd sdks/python && uv run conformance; cd ../typescript && npm run conformance; cd ../rust && ./conformance.sh
sbt -Dankka.spikes=on 'grpc/testOnly *GrpcTlsSpike'            # mutual TLS with rotating managers
sbt -Dankka.spikes=on 'controlPlane/testOnly *GatewayGrpcSpike' # one hostname, both protocols (k3s)
```

**Expected**: the sidecar and all three SDKs pass unchanged on grpc-java 1.84.0 (R2); a handshake
that requires a client certificate, the peer read from the session, and a rotated certificate
served without a restart (R4); from the host, a unary call, a bidirectional call and a stream
longer than 15 seconds answered at a hostname that also answers an HTTP request (R8). A spike that
fails takes the fallback its research entry names, and the plan is amended before work continues.

## 1. One service, offline

```bash
sbt grpc/test
```

**Expected**: every scenario under `features/grpc/` passes through `GherkinSuite`, none ignored,
plus the unit suites
(startup validation, the status table both ways, flow control, the fallback registry). No fixed
port is bound. Seconds, not minutes.

To see a check fail: return `NOT_FOUND` for `Conflict` in `GrpcStatus` and watch the statuses
outline go red on one row.

## 2. The sample, by hand

```bash
docker compose up -d
sbt shoppingCart/run                                   # HTTP on :9000, gRPC on :9090
grpcurl -plaintext localhost:9090 list                  # the sample opts into reflection locally
grpcurl -plaintext -d '{"cartId":"c1"}' localhost:9090 shoppingcart.v1.CartService/GetCart
ankka local console                                     # the method is listed, with no way to call it
```

**Expected**: the service definition and its methods listed; a cart returned; an item the cart
refuses answered `FailedPrecondition` with the cart's message; the trace of the call in the local
console with the entity's span beneath a root named for the method.

## 3. The whole service under test

```bash
sbt shoppingCart/test
```

**Expected**: the sample's suite starts the service on a throwaway Postgres with
`GrpcServer.at("127.0.0.1", 0)` and calls it through `GrpcChannels.plaintext`.

## 4. Desired state and rendering

```bash
sbt controlPlaneApi/test operator/test -Dankka.cluster.tests=off
```

**Expected**: the descriptor's four new refusals; every `service.json` block in `docs/` still
valid; `CrdSchemaSuite` green with `grpcPort` declared; the rendering suites' new cases; and the
golden case — a resource without `grpcPort` renders what it rendered before, object for object.

## 5. A cluster

```bash
caffeinate -i sbt 'controlPlane/testOnly *GrpcClusterSuite'
```

**Expected**, each a test named for its scenario under `features/grpc-deployed/`, with
`calling.feature`'s repeated against real pods: a gRPC address other services reach; the caller read
from the certificate and not from metadata; a workload with no platform identity refused by the
network; an instance held un-ready until it can answer; a service that declares gRPC and registers
none reported `Failed` with the sentence of contract `descriptor-and-configuration.md`; one service
calling another as itself, and the three ways that fails; from the host, a call and a
bidirectional stream at the hostname; no call refused through a rolling replacement, inside or
outside; every instance answering within five minutes of a scale-out.

## 6. By hand on kind

```bash
just up
ankka services apply -f cart.json -p demo      # the sample's image, with "grpc": true
ankka services expose cart -p demo
grpcurl -cacert ~/.ankka/local-ca.crt cart-demo.127.0.0.1.sslip.io:8443 list
curl --cacert ~/.ankka/local-ca.crt https://cart-demo.127.0.0.1.sslip.io:8443/carts/c1
```

**Expected**: both answer at the one hostname.

## 7. Performance

```bash
sbt -Dankka.benchmarks=on 'grpc/testOnly *GrpcBenchmark'
```

**Expected**: a unary call that reaches one entity is no slower at the median than the same call
through the HTTP endpoint (SC-007); 100,000 parts in each direction to a slow reader with the
heap flat (SC-008).

## 8. Documentation and the living features

```bash
just docs
uvx --from "git+https://github.com/thinkmorestupidless/speckit-bdd@v0.2.0#subdirectory=checker" \
  speckit-bdd check --root . --glossary GLOSSARY.md --features features --specs specs --specs-from 019
```

**Expected**: the docs build passes with the new page in the nav and a skill, its samples in step
with the sample's code, and the configuration table's new keys described; the checker reports no
findings and at least one spec read.
