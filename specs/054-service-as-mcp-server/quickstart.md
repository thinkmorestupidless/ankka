# Quickstart: validating a service as an MCP server

The runs that show the feature works, cheapest first. Docker is required from step 2. Contracts:
[scala-api](contracts/scala-api.md), [http-wire](contracts/http-wire.md),
[protocol](contracts/protocol.md), [sdk-apis](contracts/sdk-apis.md). Data:
[data-model.md](data-model.md).

Every command switches the k3s suites off unless it is one; a k3s run is minutes and belongs under
`caffeinate -i` on a laptop. munit's filter needs a leading wildcard.

## 0. The spikes, once

```bash
sbt -Dankka.cluster.tests=off -Dankka.spikes=on 'mcp/testOnly *ClientKeyIndexSpike'   # S1: a second r2dbc query plugin at 500ms, two kit nodes
sbt -Dankka.cluster.tests=off -Dankka.spikes=on 'mcp/testOnly *OutsideClientSpike'    # S2: prints the URL; run the inspector against it
npx @modelcontextprotocol/inspector --cli http://127.0.0.1:<port>/mcp --method tools/list
```

Expect, from S1: a revoke on one node refused on the other within a second, printed; one
connection pool. From S2: the inspector lists and calls `get_cart`; with `-Dankka.mcp.spike.brokered=on`,
its OAuth flow registers, is sent to the test issuer's page (the spike's issuer auto-approves) and
calls a tool with the minted token.

## 1. Pure: the kernel, the builder, the credential, the ACL combinator

```bash
sbt -Dankka.cluster.tests=off \
    'mcp/testOnly *ProtocolSuite *ToolBuilderSuite *ToolInputSuite *McpEndpointProblemsSuite *ClientKeyEntitySuite *ClientKeyEventsSuite *ClientKeyIndexSuite *ClientKeysImplSuite *ResourceMetadataSuite *SignInRecordSuite *BrokerTokenSuite *IssuerClientSuite' \
    'http/testOnly *OpaqueCredentialsSuite *AclAnyOfSuite *RootRoutesSuite' \
    'controlPlane/testOnly *DeployTokensSuite'
```

Expect: every row of the http-wire table from one kernel with no server; the protocol page's own
examples replayed; a scalar `command` reference without `input` not compiling (a `compileErrors`
case); each refusal text of the scala-api contract; the deploy token format unchanged after the
move; `Acl.anyOf` admitting on the first member and challenging with the first challenge; a root
route served ahead of a prefix and two claiming one path refused.

## 2. The living features, in the test kit

```bash
sbt -Dankka.cluster.tests=off 'mcp/testOnly *ToolsFeatures *ClientKeysFeatures *AccessFeatures *BrokeredSignInFeatures *ObservingFeatures *SiblingAgentSuite'
```

Expect: 14 of 17 `tools.feature` scenarios green and 3 reported as run elsewhere; 14 of 15
`client-keys.feature`; all 8 of `access.feature`, with the test issuer and the key authenticator in
one ACL; all 7 of `brokered-sign-in.feature`, the broker driven by the agent's `McpClient` plus a
small OAuth client in the suite against `TestIssuer`'s code flow; the trace scenario of
`observing.feature`; the two sibling scenarios of `agents/mcp-servers.feature` with two kits. The
revocation scenario runs on `startPeer`'s second node. Every log, span and listing searched for the
minted key and clean.

## 3. The report, the local console, the documentation suites

```bash
sbt -Dankka.cluster.tests=off 'controlPlane/testOnly *DeployedTopologyFeatures' 'controlPlaneApi/testOnly *McpEndpointsDocumentationSuite *ControlPlaneFixturesSuite' \
    'cli/testOnly *ConsoleTopologyJsSuite *McpServerSuite'
```

Expect: a scripted instance topology with `type: "tool"` handlers read back through the control
plane as the member's view; the local console's routes panel listing `MCP /mcp get_cart` with no
form; `describe_local_service`'s description naming tools; the four documentation scenarios.

## 4. The sidecar and the three SDKs

```bash
sbt -Dankka.cluster.tests=off 'sidecar/testOnly *ProtocolSuite *DiscoverySuite *RemoteMcpEndpointSuite *WasmImportsSuite' \
    'sidecar/testOnly *ConformanceSuite -- *mcp-endpoint.*'                  # the Scala reference, in-process
cd sdks/python && uv sync && uv run pytest -q && uv run mypy && uv run conformance
cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && npm run conformance
cd sdks/rust && cargo test --workspace && ./conformance.sh
```

Expect: `Discovery.ProtocolVersion` is `1.20` and each SDK's constant with it; a spec declaring an
MCP endpoint under `1.19` refused naming `McpSince`; the eleven `mcp-endpoint.*` cases green against
the Scala reference, then against each SDK's example (`sdks/*/examples/shopping-cart/conformance.*`),
module shape included; `uv run pytest`, `npm test` and `cargo test` each carrying unit cases for the
builder, the reference resolution refusals and the too-old runtime.

## 5. On k3s

```bash
caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *McpEndpointsClusterFeatures'
gh workflow run cluster --ref 054-service-as-mcp-server -f suite=McpEndpointsClusterFeatures
```

Expect: the sample deployed with three instances, exposed with a custom hostname; `tools/list` at
the derived hostname and at the custom one; each pod answering a `tools/call` by address with no
session; a key revoked through the sample's route refused at every pod within one second; the
paused service answering nothing at `/mcp`.

## 6. The documentation, the features, the whole build

```bash
just docs-sync && just docs
just features
sbt -Dankka.cluster.tests=off buildAll
```

Expect: `build/mcp-endpoints.md` in the nav and the `ankka-endpoints` skill, every include region
found, the configuration table carrying `ankka.mcp.*` with prose for each; the checker at 0
findings; the build warning-free with twelve artifacts on `publishLocal`.

## 7. By hand

```bash
docker compose up -d
sbt shoppingCart/run                                                       # /mcp with get_cart, add_item, checkout
claude mcp add --transport http cart http://localhost:9000/mcp              # refused: the cart's /mcp admits client keys only
curl -s -X POST localhost:9000/mcp-keys -H 'Content-Type: application/json' \
  -d '{"subject":"acme","scope":["get_cart"]}'                              # the sample's issuing route; shows the key once
claude mcp add --transport http cart-acme http://localhost:9000/mcp --header "Authorization: Bearer ankka_ck_…"
```

Expect: Claude Code's `/mcp` showing `cart` failed or needing authentication (a 401:
the endpoint admits client keys only), and `cart-acme` connected with one tool,
`get_cart`, answered from the entity; `ankka local console` showing the tool's span with the
subject `acme` and the entity's call beneath it, and no key anywhere in it.
