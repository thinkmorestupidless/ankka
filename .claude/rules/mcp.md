---
paths:
  - "modules/mcp/**"
  - "sidecar/**/RemoteMcpEndpoint.scala"
  - "protocol/**/mcp.proto"
  - "features/mcp-endpoints/**"
  - "samples/shopping-cart/src/main/scala/shoppingcart/api/CartMcpEndpoint.scala"
---

# A service as an MCP server

Feature 054. `ankka-mcp` serves a service's tools to any MCP client over Streamable HTTP. The agent
side, a service's agents *using* MCP servers, is feature 029's and lives in `agent/mcp`; the two
meet only on the wire, and `SiblingAgentSuite` holds them to each other.

## An MCP endpoint is an `HttpEndpoint` with three built routes

`McpEndpoint(path)` overrides `routes` with `POST <path>` (the messages), `GET` and `DELETE` (405,
`Allow: POST`), each behind the endpoint's ACL, so matching, admission, `RequestScope`, the virtual
thread and the request span are the server's as for any route. To every reader of a service's
routes it is one record per tool, `MCP <path> <tool>` (`ServesRoutes.served`), so grants, the
topology and rollouts see tools and need no MCP code. The inherited verbs (`get`, `sse`, ...) are
refused at start: `problems` reads `super.routes`.

The kernel is `Protocol.handle`, a pure function: stateless, JSON only, tools only.

| Request | Answer |
|---|---|
| `MCP-Protocol-Version` not `2025-06-18` or `2025-03-26` | `400`, `-32600`, id `null` |
| `Origin` present and not the `Host` | `403` |
| body not JSON | `400`, `-32700`, id `null` |
| a batch | `400`, `-32600` |
| a notification or a response | `202`, no body |
| `initialize` | the client's version when supported, else `2025-06-18`; `tools.listChanged: false`; no session id |
| `tools/list` | the tools the caller may call; no `nextCursor` |
| unknown tool, or one the caller may not call | `-32602 tool not available: <name>`, the same words for both |
| arguments the tool's types refuse | `-32602`, the decoder's reason (`parameter 'x': ...`) |
| a handler that throws or refuses | a result with `isError: true` and the message |

A tool's ACL is decided inside the kernel with `Admission.decide(acl, target)`, for the target
`Route("MCP", "<path> <tool>")`, the tool's own record, not the `POST` route it arrived on.

## Seen as tools by the topology, the console and the CLI

`TopologyJson` writes each tool record as a handler `{"name": "<tool>", "type": "tool"}` of the
`endpoint:<path>` node, with `grantable` as a route's. The local console lists an endpoint's tools under its
path and refuses to invoke an `MCP` record (400, as for `GRPC`); the deployed console and `services
topology` mark a tool `name (tool)`. Grants and rollouts have no MCP test of their own: the three built
routes are ordinary routes, and a tool is a served record like any other.

## A tool call is a child span

Decided for T033: a tool call is a span of its own under the request's (`mcp`, `<path> <tool>`,
`Server`), not the request span renamed, because the request span is recorded by the server before
the body is read and one request could in principle name no tool at all. Its origin is
`CallOrigin(endpoint:<path>, "MCP <path> <tool>")`, a name `served` declares, so a call the tool
makes is attributed to the tool. A tool error is `Failed`, refused arguments `Refused`. The span
carries the principal's subject. With no service mounted (a pure suite) nothing is traced.

## References are bound at mount

`command(handle, id)`, `query(handle, id)` and `route(prefix, method, path)` build a tool whose
`run` reads an `AtomicReference` that `problems(mounting)` fills: the service's transport for a
handle, the `Route` itself for a route. Unbound, a call answers a tool error rather than throwing.
A route reference is called in process (`route.run(args, body)`), never through loopback HTTP.

## Client keys are an index on every instance, fed by one entity's journal

`ankka-client-key` (event sourced, `platform`) keeps a key's digest, subject, attributes, label,
endpoint, scope and expiry; its id is the key's id, and every command carries the instant it is
about, so the entity never reads a clock. `McpRuntime` owns a `ClientKeyIndex` that replays then
follows that journal through `ankka.mcp.client-keys.query` and is the runtime's readiness; issue and
revoke write through on the instance that made them. `ClientKeys.authenticate()` is an
`Acl.Authenticate` whose function is `NeedsService`: bound to the service's `McpRuntime` when the
server starts (a service without one does not start), it reads the index on each request. A key is
admitted only at the endpoint it was issued for; its scope is applied by the endpoint
(`McpRuntime.scopeOf`), to listing and calling alike. `ClientKeyRules` holds the issue refusals once
for the entity and for `clients.clientKeys.issue`, which refuses what needs the endpoints first.
The key's id rides on the tool span as the attribute `client_key` (`Recorder.withAttribute`,
exported as `ankka.client_key`).

## Brokered sign-in is an authorization server at the service's own hostnames

`brokered = true` (research R11) adds six root routes under `/_ankka/sign-in<path>`, each
`Acl.AllowAll` and answering 503 until the endpoint is mounted: RFC 8414 metadata at
`/.well-known/oauth-authorization-server/_ankka/sign-in<path>`, `register` (RFC 7591, public
clients only, a redirect URI https or loopback http), `authorize`, `callback`, `token` and
`jwks.json`. The endpoint's ACL becomes `anyOf(declared, BrokerAuthenticator(path))`, and its
resource metadata names the broker alone, at the scheme and `Host` the client reached it by. No
service route may start `/_ankka/sign-in` (`HttpServer.validate`).

- **The broker is a relying party of the service's issuer, never an account store.** It sends the
  person to the first issuer in the declared ACL with a client id (`ANKKA_AUTH_<NAME>_CLIENT_ID`,
  and `_CLIENT_SECRET` for a confidential client; `Issuer.clientId`), exchanges the code with PKCE,
  verifies the ID token with `IdTokenVerifier` (audience the client id, the nonce checked), and keeps
  only `SignedIn`: subject, name, email, roles and the issuer's other claims. Every issuer in the ACL
  must have a client id, or the service does not start.
- **Its records are one key value entity, `ankka-sign-in-record`**, ids `client:<id>`,
  `grant:<state>`, `refresh:<sha256 of the token>`. Each write is `expireAfter` its lifetime, so a
  read after it sees nothing, and the timed action `ankka-sign-in-expiry` deletes it a minute later
  under one timer per record (`sign-in:<id>`). So brokered sign-in needs the service's
  `TimerRuntime`, and the service does not start without one. A code is `<state>.<secret>`, only the
  secret's digest kept; taking it deletes the grant, and a refresh token is replaced on use.
- **Its tokens are RS256, signed with the JDK** (`signin/Jwt`; nimbus stays in `auth-oidc`), `iss`
  the broker's URL, `aud` the endpoint's, `typ: Bearer`, the principal's issuer `sign-in` and the
  service's issuer under `sign_in_issuer`. `verify` requires `iss` and `aud` of one origin and the
  endpoint's own paths; a token of another service fails on the signature, since each service has its
  own keys.
- **The keys are `ankka-sign-in-keys` (event sourced, id `keys`), each private half a service secret
  `ankka-sign-in-key-<kid>`**, written before the key's event and deleted after its removal.
  `McpRuntime` runs `rotate()` hourly when an endpoint is brokered (`rotate-every`, `keep-for` under
  `ankka.mcp.sign-in`). **A private key is held in memory once read**, against the secrets rule that
  `get` is never cached: reading it per token would put a recorded read on every sign-in.
- **Deviations from the plan, decided while building it:** an instance that meets a key id it lacks
  reads the keys entity again, at most once a second, rather than following its journal as the client
  key index does; `RateLimit` (30 a minute per client, per node, on `token`) is a copy of the control
  plane's `TokenBucket`, which `mcp` cannot see; the issuer client ignores `issuer.ca`; and the key
  value entity gained a `platform` flag for the record entity.
- **S2 was not re-run against the broker.** The inspector's CLI mode has no OAuth flow; the in-suite
  `BrokerClient` (`BrokeredSignInFeatures`) stands in for a client, following every `Location` as a
  browser would. An interactive client against a real broker is the k3s suite's to record.

Traps:

- **The broker builds every URL from the request's scheme and `Host`.** A test that reaches it with
  and without the gateway header gets an `https` and an `http` issuer, and a code bound to one
  redirect URI is refused at the other. `BrokerClient` sends every request through the gateway and
  follows `https://127.0.0.1` links over plain http.
- **A test token with a one-hour lifetime cannot verify after a rotation the clock moved a month
  for:** it is refused as expired, not for its key.

## Traps

- **A reference's input is encoded by the handle's own serializer, never passed through as JSON.**
  `Serializers.string` is raw UTF-8, so a model's `"slot": "thursday"` sent as JSON would reach a
  `String` handler as `"thursday"`, quotes and all. `ToolInputSuite` asserts the bytes.
- **`ToolInput.derived` makes a codec when none is in scope.** A component usually holds only a
  `Serializer` from `Codecs.serializer`, so asking for a `JsonValueCodec` given failed every
  reference to a case-class command; it now summons one or derives one as `Codecs.make` does.
- **Locally a call between services carries no identity.** The service client strips every
  `X-Ankka-` header (`OutboundHeaders`), so the local caller header cannot be passed through an
  agent's MCP server declaration, and every local caller is `Caller.Local`, which every ACL admits.
  That an MCP endpoint is told the calling service is a k3s suite's to prove.
- **A handler's `request.headers` includes pekko-http's synthetic `Timeout-Access`.** Seen in S2; a
  tool that echoes or forwards headers should drop it.
- **The server reads an endpoint's `acl` on every request.** An authenticator bound at start (a
  client key's) would be fresh and unbound on each request under a `def acl`. `McpEndpoint` reads
  `acl` once into its three routes (`frozenAcl`), which the server binds; an ordinary endpoint that
  admits client keys must declare its ACL as a `val`, and an unbound one answers 503 saying so.
- **The issue answer is the one place a key is shown.** `McpEndpointSteps` scans everything a
  scenario read for every minted key, and leaves that one answer out; a step that echoes the issue
  answer anywhere else fails the scan.
- **A key issued through a Service is known at once only to the instance that took the issue.** The
  others follow the journal, about half a second behind, so "issue, then call a pod by its address"
  answered 401 on the first k3s run of both cluster suites, on whichever pod had not caught up.
  `McpEndpointsClusterSteps.issue` waits until every ready instance admits the key before handing it
  to a scenario; a client of a several-instance service should expect the same window.
- **A lambda answering `Done` infers `Done.type`, which has no `ToolOutput`.** Ascribe it,
  `() => (Done: Done)`; a reference whose handle answers `Done` is unaffected.

## Measured

- **S1** (`ClientKeyIndexSpike`, 2026-10-19): a write on one node reached a follower on the other
  through `ankka.mcp.client-keys.query` (the runtime's query plugin at a 500ms refresh) in 505 to
  662 ms over ten writes. The connection pool count was not measured.
- **S2** (`OutsideClientSpike`, 2026-10-19): the MCP inspector's CLI (`npx
  @modelcontextprotocol/inspector --cli <url> --transport http`) listed and called the tools with
  no change to the server. It sends `Accept: application/json, text/event-stream`,
  `mcp-protocol-version: 2025-06-18` and `sec-fetch-mode: cors`, no `Origin` and no session id, and
  needs nothing from `initialize` beyond the table above.
- **Revoke to refusal** (`ClientKeysFeatures`, three kit instances, 2026-10-19): 7 to 9 ms on the
  instance that revoked (the write-through), 507 to 536 ms on the other two, over two runs. On k3s
  (`McpClientKeysClusterFeatures`, three pods, 2026-10-19, the time a prober pod saw): 90/390/430 ms
  on arm64 and 110/140/380 ms on amd64, and 100/230/550 ms in an earlier amd64 run. Each run's figures
  are in the order the instances refused; the bound is one second.
- **Latency** (`McpLatencySuite`, `-Dankka.spikes=on`, 2026-10-19): one service on loopback, 200 reads
  of one cart after 100 to warm up, `GET /carts/{cartId}` median 0.89 ms against `get_cart` 0.67 ms
  (ratio 0.75; the bound is 1.5). The tool is not slower than the route it stands beside.
