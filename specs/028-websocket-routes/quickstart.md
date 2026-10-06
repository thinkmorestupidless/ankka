# Quickstart: validating socket routes

Each run proves one layer and is cheap enough to repeat. Run them in this order; a later one
means little while an earlier one is red. Docker is needed from run 3 on. Contracts are in
[contracts/](contracts/); what is stored (nothing) and the close reasons are in
[data-model.md](data-model.md).

## 0. The spikes, before anything is built

```bash
sbt -Dankka.spikes=on 'http/testOnly *SocketCloseCodeSpike'
```

Expect each of 1000, 1001, 1003, 1008, 1009 and 1011 read by a client from a server that chose
it, and a socket idle for 90 seconds still open with the keep-alive on and cut off with it off.
If the close codes cannot be sent, stop: research R4 names the fallback and it changes the spec.

## 1. The server alone

```bash
sbt http/test 'testkit/testOnly *SocketSuite'
```

Expect, in `http`, the route's declaration rules (arity, a duplicate template refused) and the
named-service cases over mutual TLS on loopback (`SocketCallerSuite`); and in `SocketSuite` the
upgrade's answers (401 with the challenge, 403, 426, 101), the subprotocol token, each close
reason's code, both limits and the keep-alive with its negative twin.

## 2. A whole service

```bash
sbt 'testkit/testOnly *SocketFeatures *ShutdownOrderSuite'
sbt 'sidecar/testOnly *SocketAccessFeatures'
```

Expect every scenario of `features/sockets/frames.feature` and `traces.feature`, and of
`access.feature` against the real token verifier, run by name, none ignored; a thousand parked handlers adding no platform
thread; and coordinated shutdown alone closing an open socket with 1001.

## 3. The protocol and the sidecar

```bash
sbt 'sidecar/testOnly *ProtocolSuite *RemoteEndpointSuite *WasmHostSuite'
sbt 'sidecar/testOnly *ConformanceSuite -- *socket.*'
```

Expect a socket relayed both ways through a real sidecar to the process double; the double
stopped mid-socket closing the client with 1011 and a socket opened after its restart served; a
socket route under protocol 1.6 refused naming the route and both versions; a module declaring
one refused naming the route; and the `socket.*` cases green against the Scala reference. The
leading `*` in the filter matters: without it munit matches nothing and reports green.

## 4. The SDKs

```bash
cd sdks/python && uv sync && uv run pytest -q && uv run mypy && ANKKA_CONFORMANCE_ONLY='*socket.*' uv run conformance
cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && ANKKA_CONFORMANCE_ONLY='*socket.*' npm run conformance
cd sdks/rust && cargo test --workspace && ./conformance.sh
```

Expect, per language: the handler's socket as an async iterator, discovery marking the route,
discovery refused against a runtime stating 1.6, and the same `socket.*` cases through a sidecar.
For Rust, expect nothing new to pass and nothing to break: the socket cases are skipped for a
module, and its protocol copy is identical to `protocol/`. Read each run's first lines: it says
which target and which shape it ran.

## 5. Offline, everything

```bash
sbt -Dankka.cluster.tests=off test
just features
just docs
```

Expect the build warning-free; `just features` with no finding in `features/sockets/`; the docs
build with the three included samples in step with their sources, the three variables mentioned
in the configuration page's prose, and `HandleSocket` in the generated protocol table.

## 6. The gateway (k3s)

```bash
caffeinate -i sbt -Dankka.spikes=on 'controlPlane/testOnly *GatewaySocketSpike'
caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *ExposureClusterSuite'
```

The spike first: a socket through the gateway carrying frames for a minute, then idle for six,
then a frame. Then the suite: a socket opened at the cart's hostname offering two subprotocols
and answered with `ankka.socket`, the handler answering that its caller is the gateway;
the socket opened at the suite's start still carrying a frame after ten idle minutes;
`services restart` closing it with 1001 and a socket opened at once answered; and nothing to
open on an unexposed service. Minutes, not seconds.

## 7. By hand

```bash
docker compose up -d
sbt shoppingCart/run
node -e 'const s = new WebSocket("ws://localhost:9000/carts/c1/watch"); s.onmessage = e => console.log(e.data); s.onopen = () => s.send("refresh"); s.onclose = e => console.log("closed", e.code)'
```

Expect the cart as JSON, and `closed 1001` when the service is stopped with Ctrl-C. In the local
console the route is listed beside the cart's others as a socket route,
with no form.
