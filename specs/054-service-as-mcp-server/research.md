# Research: A Service as an MCP Server

Decisions for [plan.md](plan.md), each with what in the repository, in the pinned dependencies or
in the protocol's text it rests on. File references are to this branch's base (`b331c9a9`).
"Verify first" marks a claim read from code or documentation and not yet run; the task that touches
it starts with a test or a spike that would show it false. The words are the glossary's: an **MCP
endpoint** offers **tools**; a **client key** is the service's own credential; **brokered sign-in**
is what a service opts an MCP endpoint into so that the platform runs its **sign-in broker**.

Protocol facts were read from the MCP specification, revision 2025-06-18, on 2026-10-19: the
Streamable HTTP transport, the authorization flow and the tools page. Where this document says "the
protocol says", that is the source.

Seven things planning found that the spec did not have are amended into the spec and listed in
`plan.md`'s Complexity Tracking: where the tools appear in the control plane (R14), the one extra
registration a service with client keys makes (R8), a client key being issued for one MCP endpoint
(R9), brokered sign-in being per MCP endpoint with the broker's client registered at the issuer
(R11), how arguments are checked (R5), what a route's body is as an argument (R6) and the dependency
the sign-in broker brings (R12).

## R1. A new module, `ankka-mcp`, above `http` and `agent`

**Decision**: `modules/mcp`, artifact `ankka-mcp`, `.dependsOn(core, sdk, runtime, http, agent,
authOidc, testkit % Test, authOidc % "test->test")`. It holds the endpoint (`McpEndpoint`), the
tools and their builder, the protocol kernel, the client keys (`ClientKeys`, the entity, the index),
the sign-in broker and the `McpRuntime` extension. `sidecar` and `shoppingCart` depend on it;
`testkit` does not (the direction `grpc` has: `build.sbt:332-339`). The twelfth published artifact;
`templateArtifacts` stays at eight, with `ankka-mcp` a commented line in the template beside
`ankka-agent` (`ankka.g8/src/main/g8/build.sbt:41`).

**Rationale**: the endpoint needs two things no one module has. From `http`: `Acl`, `AuthDecision`,
`Principal`, `Caller`, `RequestContext`, `EndpointClients` and the server that mounts an endpoint
(`modules/http/.../HttpEndpoint.scala:55-131`, `HttpServer.scala:35-43`). From `agent`: `Json`, the
schema derivation `SchemaType` and `ToolOutput` (`modules/agent/.../SchemaType.scala:12-115`), the
case-class derivation `JsonSchema.derived` (`agent/autonomous/JsonSchema.scala:71`), and the
`McpClient` the tests drive it with (`agent/mcp/McpClient.scala`). `http` and `agent` are siblings
under `runtime` (`CLAUDE.md`, module direction) and neither may gain the other. The spec calls the
MCP endpoint "the third kind of endpoint, beside HTTP and gRPC", and gRPC is a module of its own
above `http` for exactly this reason (`.claude/rules/grpc.md`).

**Alternatives considered**: the endpoint in `agent` with `agent → http` added — rejected: it
changes a published POM and the module direction for a reason that is not the agent's, and every
agent service would carry the server. In `http`, moving `Json` and `SchemaType` down — rejected:
`Json` is public API of `ankka-agent` (`FunctionTool.invoke(Json)`, `ToolSpec`) and `SchemaType` is
what agents are documented with; moving them is a compatibility change for a feature that does not
need it. A Pekko-free protocol kernel in `core` shared with the CLI's `ankka mcp` — rejected for
this feature: the spec leaves `ankka mcp` unchanged, the CLI's kernel keeps field order and exact
ids on its own `Json` (`cli/.../mcp/Json.scala:10-12, 55-60`) for reasons of its own, and nothing
here needs the CLI; the two kernels are a refactor for a later feature, noted in the limitations as
nothing.

## R2. The MCP endpoint is an `HttpEndpoint` underneath, mounted like any other

**Decision**: `abstract class McpEndpoint(val path: String = "/mcp") extends HttpEndpoint(path)`,
`final override`-ing `routes` with the protocol's three: `POST <path>` (the messages), `GET <path>`
(405) and `DELETE <path>` (405), each with the endpoint's ACL; `streamRoutes` and `socketRoutes`
empty. A developer writes `tools`, `acl`, optionally `name`, `version` and `brokered`, and mounts it
with `HttpServer.of(clients => CartMcp(clients))` as any endpoint. The inherited verbs are not for
an MCP endpoint: `problems` (R4) refuses one whose route buffer is not empty, "an MCP endpoint
declares tools, not routes".

**Rationale**: `HttpServer.serve` gives an `HttpEndpoint` matching, the ACL, the `Caller`, the
`RequestScope`, the virtual thread, the request span, error mapping and `ServedRoute` reporting
(`HttpServer.scala:139-271, 644-728`). The sidecar's `RemoteEndpoint` is already an endpoint whose
routes are built, not declared (`sidecar/.../RemoteEndpoint.scala:49-70`). The alternative, a new
factory type in `HttpServer.of` and a new `Matched` case, touches `Matched`, `admitted`, `served`,
`validate`, the startup logs and `namesCallers` (`HttpServer.scala:379-403, 460-491`) for a thing
that is, on the wire, three routes.

**What `http` gains, generically**: two seams and one combinator, none of which name MCP.

- `private[ankka] def rootRoutes: Vector[Route] = Vector.empty` on `HttpEndpoint`: routes at
  absolute paths, served ahead of prefix matching beside `/_ankka/health` (`HttpServer.scala:458,
  468`). `validate` refuses two endpoints claiming one root route. The MCP endpoint's resource
  metadata and sign-in broker live here (R10, R11), because the protocol puts them under
  `/.well-known/` and the broker under a path that is not the endpoint's.
- `private[ankka] def problems(mounting: Mounting): Vector[String] = Vector.empty` on
  `HttpEndpoint`, with `Mounting(registry: ComponentRegistry, endpoints: Vector[HttpEndpoint],
  service: AnkkaService)`, called from `HttpServer.serve` before binding and folded into the one
  `invalid ankka http configuration` refusal (`HttpServer.scala:335-359`). References are resolved
  here (R4), the path clash is found here, and an authenticator that needs the service is bound
  here (R8).
- `Acl.AnyOf(acls: Vector[Acl])`, written `Acl.anyOf(first, rest*)`: admitted when any member
  admits, in order; the challenge of a refusal is the first `Unauthenticated` member's. A new case
  touches every exhaustive match: `HttpServer.admit` (8 cases), `grpc/Admission.decide` (6),
  `ControlPlaneAcl` (2) and `RemoteEndpoint.aclOf`; the compiler finds each under `-Werror`. It is
  how one MCP endpoint admits client keys and tokens (FR-006), and `allowCallers` with either, which
  no function inside `Acl.Authenticate` could do, since matching a caller needs the server's
  identity and grants (`Caller.scala:100-148`).

## R3. The protocol kernel: stateless, JSON only, tools only

**Decision**: `mcp/Protocol.scala`, a pure function from a request (`Json`, headers) to a response
(status, `Json`), with no `HttpExchange`, actor or I/O in it. Its rules, each from the protocol's
text:

| Request | Answer |
|---|---|
| `POST` with a JSON-RPC *request* | `200 application/json`, one JSON-RPC response. Never `text/event-stream`: the protocol lets a server choose, and this one has nothing to stream. |
| `POST` with a *notification* or *response* | `202 Accepted`, no body (`notifications/initialized`, `notifications/cancelled`) |
| `POST` whose body is not JSON | `400`, JSON-RPC error `-32700` with no `id` |
| `POST` whose body is a batch array | `400`, `-32600`: batches left the protocol at 2025-06-18 |
| `GET <path>` | `405 Method Not Allowed`: "the server does not offer an SSE stream at this endpoint" |
| `DELETE <path>` | `405`: sessions are not offered, so none can be ended |
| `MCP-Protocol-Version` absent | treated as `2025-03-26`, as the protocol says; nothing here differs between the two |
| `MCP-Protocol-Version` not `2025-03-26` or `2025-06-18` | `400` |
| `Mcp-Session-Id` present | ignored; the server never issues one, so none is required |
| `Origin` present and not the request's own host | `403`: the protocol's DNS-rebinding rule |
| `initialize` | `protocolVersion` the client's when supported, else `2025-06-18`; `capabilities: {tools: {listChanged: false}}`; `serverInfo: {name, version}`; no `Mcp-Session-Id` header |
| `ping` | `{}` |
| `tools/list` | every tool the caller may call (R7), each `{name, description, inputSchema}`; no `nextCursor`, since a service's tools are a page |
| `tools/call` | see R5 and R7 |
| any other method | `-32601` |

Protocol errors and tool errors are kept apart as the protocol keeps them: an unknown tool, a tool
the caller may not call, and arguments the tool's types refuse are JSON-RPC errors (`-32602`,
message naming the tool or the argument); a handler that throws or refuses is a result with
`isError: true` whose one text part is the message (FR-004). A refusal by the endpoint's ACL is not
the kernel's: the router answered `401` or `403` before it ran.

**Rationale**: the agent-side client copes with no session (`McpClient.scala:111-128`: `session`
stays `None`), never sends `GET` or `DELETE`, and reads `application/json` or a one-event stream
alike (`answerTo`, :143-155); the CLI's kernel already negotiates versions this way
(`cli/.../mcp/McpServer.scala:144-207, 220`). `TestMcpServer` is "a second implementation of the
protocol's rules beside the platform's client" on purpose (`TestMcpServer.scala:15-18`) and stays
one: a server tested only against a client written by the same hand proves agreement, not
correctness, so the kernel's suite also replays recorded exchanges from the protocol's examples and
`McpServerSpike`'s pattern is turned round (`agent/mcp/McpServerSpike.scala:12-25`): an outside
client against this server, under `-Dankka.spikes=on` (S2).

**Alternatives considered**: lifting `TestMcpServer.serve` — rejected: it mints sessions, answers
404 for an unknown one, has no `ping`, no version negotiation and no `Origin` check, and is written
against `HttpExchange` (`TestMcpServer.scala:122-164`). Answering `text/event-stream` to mirror what
some clients expect — rejected: the protocol makes the client accept both, and one JSON object is
what a stateless server has.

## R4. Tools: a builder like the agent's, and references resolved where the service is known

**Decision**: a tool is an `McpTool(spec: ToolSpec, acl: Option[Acl], run: ToolCall => ToolAnswer)`
where `ToolCall(arguments: Json, context: RequestContext)` and `ToolAnswer(text: String, structured:
Option[Json])`. The builder mirrors `FunctionTool`'s (`agent/FunctionTool.scala:168-254`), so a
developer who has written an agent tool has written one of these:

```scala
tool("get_cart").describedAs("Reads one cart")
  .param[String]("cartId", "The cart's id")
  .handle { cartId => clients.componentClient.forEventSourcedEntity(cartId).call(ShoppingCart.getCart).invoke() }

tool("add_item").describedAs("Adds an item").arguments[AddItemArgs].handle { a => … }   // a case class, JsonSchema.derived

tool("book_job").describedAs("Books a job").command(Job.book, id = "jobId")                // a reference
tool("get_job").describedAs("Reads one job").query(Job.get, id = "jobId")
tool("todays_jobs").describedAs("The day's jobs").route(JobsEndpoint, "GET", "/jobs/{day}")
tool("close_books").describedAs("…").withAcl(Acl.allowCallers(Callers.service("office"))).command(…)
```

`param[A]` takes `SchemaType[A]` (string, boolean, double, int, long, `Option`, `List`); `arguments[A]`
takes a `JsonSchema[A]` and a `JsonValueCodec[A]`, both derived for a case class, so a Scala tool
whose argument type has no schema does not compile, which is the startup refusal the spec asks for,
earlier. `handle` takes `ToolOutput[R]`, extended with `Done` ("done"). The run function runs on the
request's virtual thread inside the request span, so `clients.componentClient.invoke` parks and the
entity's span nests under the tool's (`HttpServer.scala:689-701`).

A **reference** is resolved against the service, not by name at call time:

- `command(handle: CommandHandle[C, I, O], id: String)` and `query(…)` need `ToolInput[I]` and
  `ToolOutput[O]`. `ToolInput[I]` is derived for a product type (its fields become the tool's
  properties beside the id, from `JsonSchema.derived` and the codec) and must be named for a scalar
  (`command(Job.book, id = "jobId", input = "slot")`, from `SchemaType`); a `NoArgHandle` has the id
  alone. The call decodes `I` from the arguments, encodes it with **the handle's own
  `inputSerializer`** and sends it through `transportRef.askHandler(readOnly, componentId, id, name,
  bytes, Trace.outbound(Metadata.empty))` (`sdk/ComponentClient.scala:41, 105`), kind-agnostic, so
  an entity, a key value entity and a workflow are one code path; the reply is
  `outputSerializer.fromBytes` rendered by `ToolOutput[O]`.
- `route(endpoint: HttpEndpoint, method, template)` is resolved in `problems(mounting)` to the
  endpoint's `Route` by `(method, template.render)`; its arguments are the template's placeholders
  as strings and, when `needsBody`, `body` (R6); the call runs `route.run(args, bytes)` in-process
  under the tool's ACL and the MCP request's context; a status under 400 is the result (text, and
  `structuredContent` when it parses as JSON), 400 and above a tool error carrying the body.
- `problems(mounting)` refuses, naming the endpoint and the tool: a name outside `[a-z0-9_-]+`
  (the agent side's rule, `McpTools.MaxToolName`, `McpServer.NamePattern`), two tools under one
  name, a blank description, a command whose component is not in `mounting.registry` or whose
  wire name the component lacks (`ObservabilityDocuments.handlersOf`, `runtime/ObservabilityDocuments.scala:93-99`),
  a route no mounted endpoint has, an MCP endpoint path that renders equal to a mounted route's path
  (FR-001), and a `ClientKeys.authenticate()` with no `McpRuntime` to bind to (R8).

**Rationale**: the spec's "the arguments are the handler's input type with the entity's id added"
needs a schema for `I`, and a `CommandHandle` carries only a `Serializer[I]`
(`sdk/CommandHandle.scala`): the schema has to come from the declaration, where the compiler can
demand it. Encoding with the handle's serializer rather than passing JSON through is forced by
`core/Serializers.scala`: `string`, `int` and friends are `text/plain`, not JSON, so a model's
`"slot": "thursday"` would reach a `String` handler as a quoted string. Resolving at the server's
start is where every other declaration is checked (`HttpServer.validate`), and `Mounting` carries
the registry the check needs.

**Alternatives considered**: references by wire name as strings (`command("job", "book")`) —
rejected for Scala: the typed handle is in hand and gives the serializers and the compile-time
schema; the SDKs, which have no typed handles across components, do take names (R13). Calling the
referenced route through loopback HTTP — rejected: a second hop, a second ACL evaluation and a port
the test kit must know; the `Route` is a function.

## R5. Arguments are checked by decoding them, not by a JSON Schema validator

**Decision**: `tools/call` checks arguments by decoding them into the tool's types: each `param`
through `SchemaType.decode` (`SchemaType.scala:12-20`, which already reports `parameter 'x':
<reason>`), a case class through its jsoniter codec, a reference's id as a non-blank string.
Failure is `-32602` naming the argument. No JSON Schema validator is added.

**Rationale**: the schema the client was shown is *derived from* the types, so the types are the
schema; a validator would be a dependency for a second statement of the same thing. This is what
`FunctionTool.build` does for an agent's tool (`FunctionTool.scala:113-153`). Spec FR-004 is amended
from "against the tool's schema" to "against the tool's argument types, which the schema describes".

## R6. A route's body is one argument, `body`, as JSON

**Decision**: a tool that references a route with a body offers `body` as `{"type": "object"}`,
described as the route's body, and sends it encoded as JSON bytes with `Content-Type:
application/json`. A route whose body is text is not a tool a reference can declare; the
documentation says so and the developer writes a handler of two lines instead.

**Rationale**: a `Route` knows only `needsBody` (`HttpEndpoint.scala:140`); the body's type was
erased into `run` when the route was declared, so there is nothing to derive a schema from. Every
sample and documented route body is JSON (`FromBody.json`, `codecs.scala`). The alternative, a
`bodySchema` recorded on `Route` at declaration from the `FromBody` instance — rejected for this
feature: `FromBody` has no schema and giving it one touches every body codec for one reference kind.

## R7. Who may call: the ACL, then the key's scope, then the tool's ACL

**Decision**: the router admits the request by the endpoint's ACL as for any route. Inside the
kernel, a `tools/call` is then checked against the tool's own ACL when it states one (evaluated
with the same `admit` the router uses, exposed as `private[ankka] Admission.decide(acl, context,
mounting)`), and against the client key's scope when the principal came from a key. `tools/list`
applies the same two filters to the listing, so a client sees what it may call (FR-007). A
`tools/call` refused by either is `-32602` `tool not available: <name>`, the same words for "no
such tool", so a listing's absence and a call's refusal agree and neither says which it was.

**Rationale**: the route's ACL replaces the endpoint's in HTTP (`HttpEndpoint.scala:226-230`); a
tool's does the same and reuses the decision function rather than a third copy of it (the sidecar
and gRPC already have two: `grpc/Admission.scala:106-131`). Filtering the list by ACL means
evaluating an `Authenticate` per tool; tokens are verified once per request by the router, so a
tool-level `Oidc.authenticate()` re-verifies a cached JWKS result, which is microseconds.

## R8. Client keys: the control plane's deploy-token design, moved into a service

**Decision**: a client key is `ankka_ck_<16 hex id><64 hex secret>`, presented as
`Authorization: Bearer ankka_ck_…`. The shape, the SHA-256 digest of the secret half and the
constant-time compare move from `controlplane/.../auth/DeployTokens.scala:23-69` into one
`http.OpaqueCredentials(prefix)`, which `DeployTokens` then delegates to with its suite pinning the
format unchanged. It is a `Bearer` because that is the one header every MCP client can send.

The platform holds a key as an event sourced entity `ankka-client-key` in the service's database,
platform-flagged as `ankka-agent-instance` is (`agent/autonomous/InstanceEntity.scala:256-263`,
`sdk/EventSourcedEntity.scala:188`): events `ClientKeyIssued`, `ClientKeyUsed`, `ClientKeyRevoked`
(data-model.md). No DDL: the journal is keyed by entity type
(`kustomization/components/postgres/ddl/10-journal-postgres.sql`).

Verification runs on the dispatcher, so it reads a per-node **`ClientKeyIndex`**, a
`RuntimeExtension` built as `DeployTokenIndex` is (`controlplane/.../auth/DeployTokenIndex.scala:52-272`):
replay `currentEventsBySlices` for the entity type, then follow `eventsBySlices`; `Unavailable`
until caught up and readiness held; write-through `admit` on issue and `evict` on revoke on the
local node, revoked ids remembered so a replayed creation cannot bring one back; `touch` in memory
and a sweep each minute that sends `record-use` only when the recorded use is older than a minute.
Unknown, revoked and wrong-secret keys answer one text, `client key not recognised`; an expired one
answers `client key expired` (FR-010).

**The one-second promise.** The runtime's r2dbc plugin polls every 3s by default
(`pekko-persistence-r2dbc 1.2.0` `reference.conf:251`); the control plane sets 500ms globally
(`controlplane/src/main/resources/reference.conf:24`). A service should not pay that for every
projection, so the index follows through a query plugin config of its own,
`ankka.mcp.client-keys.query`, a copy of `pekko.persistence.r2dbc.query` with `refresh-interval =
500ms` sharing the connection factory. **Verify first** (spike S1): `readJournalFor[R2dbcReadJournal]("ankka.mcp.client-keys.query")`
with `use-connection-factory` pointing at the shared one works in 1.2.0 and does not open a second
pool. Fallback if it does not: a cluster `Topic` broadcasting evictions beside the stream at the
default interval, with the promise worded "within a second, or the next poll".

**Issuing, listing and revoking** go through the client: `EndpointClients` gains `clientKeys:
ClientKeys` (a trait in `http`: `issue`, `list`, `revoke`), bound by `HttpServer.start` from the
service's extensions through a `ClientKeysProvider` trait `McpRuntime` implements; without an
`McpRuntime`, `clients.clientKeys` refuses naming it. `ClientKeys.authenticate()` returns an
`Acl.Authenticate` whose function also implements `http.NeedsService`, which `HttpServer.serve`
binds to the service during `problems(mounting)`; an unbound one fails the start, "add
`McpRuntime()` to the service". So a service with tools alone adds nothing but the endpoint
(SC-001); one with client keys or brokered sign-in adds `.registerAll(McpRuntime.descriptors)
.withExtension(McpRuntime())`, as a service with agents adds `AgentRuntime`
(`samples/shopping-cart/src/main/scala/Main.scala:149-150`). Spec SC-001 is amended to say so.

**Rationale**: the design is the one `.claude/rules/control-plane.md:77-90, 207-219` records as
having been got right the hard way: synchronous ACL, per-node index, write-through for
create-then-use, remembered revocations, no secret in the journal, one answer for every failure.
Reusing it as code, not as a pattern, is why `OpaqueCredentials` moves down.

**Alternatives considered**: a key per service rather than per endpoint (R9). Verifying by entity
query per request as `MachineEntity.checkSecret` does — rejected: the ACL runs on the dispatcher
and may not wait (`control-plane.md:83-84`). Keys as rows in the secret store — rejected: a key is a
record with a lifecycle and a listing, which is an entity.

## R9. A client key is issued for one MCP endpoint

**Decision**: `clientKeys.issue(subject, expiry, endpoint = …, scope, attributes, label)`; the
endpoint path defaults when the service has one MCP endpoint and must be named when it has several
(refused otherwise, naming the paths). The key admits at that endpoint and no other, and its scope
names that endpoint's tools, checked at issue (FR-009). Spec FR-009 and the key entity are amended.

**Rationale**: a scope is a list of tool names, and tool names are unique per endpoint, not per
service; a key "scoped to no tool, meaning every tool of the endpoint" has to know which endpoint.
A product with two MCP endpoints, one for its customers and one for its office, issues a key for
one of them, which is what it means.

## R10. Resource metadata: published from the ACL, with no new declaration

**Decision**: `http` gains `trait TokenAuthenticator extends (RequestContext => AuthDecision) {
def authorizationServers: Vector[String] }`; `Oidc.authenticate(config)` returns
`Acl.Authenticate(authenticator)` where the function is a `TokenAuthenticator` naming each
configured issuer's URL (`auth-oidc/.../Oidc.scala:34-80`, `Issuer.issuer`). An MCP endpoint reads
its ACL tree: when any member is a `TokenAuthenticator`, the endpoint "admits tokens" and
contributes a root route `GET /.well-known/oauth-protected-resource<path>` answering

```json
{"resource": "<scheme>://<host><path>", "authorization_servers": ["https://auth.example/realms/shop"],
 "bearer_methods_supported": ["header"]}
```

and its `401` challenge is `Bearer resource_metadata="<scheme>://<host>/.well-known/oauth-protected-resource<path>"`,
built from the request's `Host` header (the protocol's RFC 9728 path form for a resource with a
path). When `brokered`, `authorization_servers` is the sign-in broker's issuer alone (R11). An
endpoint admitting no token publishes nothing there and challenges with `Bearer realm="…"` as today.
Scheme: `https` when the server has TLS or the caller is the gateway, `http` for a local run.

**Rationale**: the protocol makes the metadata mandatory for a server that takes tokens and makes
the client find the authorization server from it; the issuers are already stated once, in
`ANKKA_AUTH_ISSUERS` (`Issuer.scala:47-61`), and a second statement on the endpoint would drift.
Marking the function rather than changing `Acl.Authenticate`'s shape keeps every existing match
compiling.

## R11. Brokered sign-in: a per-endpoint authorization server at `/_ankka/sign-in<path>`

**Decision**: `override def brokered = true` on an MCP endpoint turns on a **sign-in broker** for
it, served as root routes under the reserved prefix the health route already owns:

| Route | Standard | Does |
|---|---|---|
| `GET /.well-known/oauth-authorization-server/_ankka/sign-in<path>` | RFC 8414 | issuer `<scheme>://<host>/_ankka/sign-in<path>`, the four endpoints below, `code_challenge_methods_supported: ["S256"]`, `grant_types_supported: ["authorization_code","refresh_token"]`, `response_types_supported: ["code"]`, `token_endpoint_auth_methods_supported: ["none"]` |
| `POST /_ankka/sign-in<path>/register` | RFC 7591 | registers a public client: `redirect_uris` (each `https` or `http://localhost`/`127.0.0.1`), `client_name`; answers `client_id`; kept 30 days from last use |
| `GET /_ankka/sign-in<path>/authorize` | OAuth 2.1 | requires `client_id`, a registered `redirect_uri`, `code_challenge` (S256), `state`, `resource` naming this endpoint; records the pending grant; redirects the person to the service's issuer's `authorization_endpoint` with the broker's own `client_id`, `redirect_uri=…/callback`, `scope=openid`, a nonce and a state of the broker's |
| `GET /_ankka/sign-in<path>/callback` | OIDC code flow | exchanges the issuer's code at its `token_endpoint` over TLS, verifies the `id_token` with `OidcVerifier` against the issuer's JWKS (nonce, audience the broker's client id), takes `sub` and the claims `Principals.from` takes, mints the broker's authorization code, redirects to the client's `redirect_uri` with `code` and the client's `state` |
| `POST /_ankka/sign-in<path>/token` | OAuth 2.1 | `authorization_code` with `code_verifier` and `resource`, or `refresh_token` (rotated, 30 days); answers an RS256 access token (1 hour, `iss` the broker, `aud` the `resource`, `sub` the issuer's subject, the claims) and a refresh token; `Cache-Control: no-store` |
| `GET /_ankka/sign-in<path>/jwks.json` | RFC 7517 | the service's current broker public keys |

The broker is an OIDC relying party of the service's issuer: `ANKKA_AUTH_<NAME>_CLIENT_ID` and,
for a confidential client, `ANKKA_AUTH_<NAME>_CLIENT_SECRET` join the issuer's variables
(`Issuer.scala:78-112`), and the issuer's endpoints are read once from
`<issuer>/.well-known/openid-configuration`. A brokered endpoint whose issuers carry no client id
fails the start naming the variable. The broker holds no account and no password: it never sees
the issuer's login page.

The endpoint verifies a broker token as a second `TokenAuthenticator` beside the service's issuers:
RS256 with the JDK, as `http.MachineTokens` does (`MachineTokens.scala:42-115`), from a per-node
cache of the broker's public keys (R12), `aud` whose path is this endpoint's, `exp`. A token minted
for one service fails another's signature check by construction, since every service has its own
keys.

State lives in the service's database as platform entities: `ankka-sign-in-record`, a key value
entity whose state is one of `Client`, `Grant` (pending authorization, then code), `RefreshToken`,
keyed `client:<id>`, `grant:<code>`, `refresh:<digest>`, each deleted at its expiry by the platform
timed action `ankka-sign-in-expiry` scheduled when the record is written, as `ankka-approval-expiry`
does (`agent/ApprovalExpiry.scala:37-66`); and `ankka-sign-in-keys`, one event sourced entity per
service holding the key ids and public keys (events `SignInKeyAdded`, `SignInKeyRemoved`), with
each private key in the service's secret store under `ankka-sign-in-key-<kid>` (AES-GCM under
`ANKKA_SECRET_KEY`, `.claude/rules/secrets.md`). `McpRuntime` rotates every 30 days and keeps a key
31 days, the control plane's cadence (`MachineKeys.scala:163-166`). The `ClientKeyIndex`'s follower
reads both entity types, so every node learns a new public key within the second (R8).

Brokered sign-in is per endpoint because the opt-in is (spec FR-008a), the issuer URL then owns
every broker route, two brokered endpoints never claim one root route (R2), and the token's `aud` is
the endpoint the client asked for (RFC 8707). Spec FR-008a is amended with the per-endpoint issuer
and the client registration the issuer needs.

**Rationale**: the protocol requires dynamic client registration or a client id the user types;
the issuers products use refuse unknown clients, which is the whole reason for the broker (spec,
Clarifications). The broker's own flow is the plainest OIDC code flow, and verifying the issuer's
`id_token` is what `OidcVerifier` already does for a bearer (`OidcVerifier.scala:38-79`). The
secret store is where the platform already keeps a service's secret material encrypted; the
control plane's "never in a journal" rule (`MachineKeys.scala:38`) is honoured by putting only
public keys in the journal.

**Alternatives considered**: one broker per service at `/_ankka/sign-in` — rejected: two brokered
endpoints would both contribute it, and `aud` could not name the endpoint. No refresh tokens —
rejected: an assistant would sign the person in every hour; OAuth 2.1 requires rotation for public
clients, so the record is one more state kind, not a new mechanism. Trusting the issuer's `id_token`
by the TLS back channel alone, with no signature check (OIDC Core 3.1.3.7 allows it) — rejected:
the spec says "verifiably signed", and the verifier exists.

## R12. `ankka-mcp` depends on `ankka-auth-oidc`

**Decision**: `mcp` depends on `authOidc` (R1), so the sign-in broker verifies the issuer's token
with `OidcVerifier`, and so `Oidc.authenticate()` is in scope wherever an MCP endpoint is written.
`.claude/rules/control-plane.md:103-114` stays true: nimbus lives in `auth-oidc` and no other
module; `ankka-mcp` reaches it through that module's POM. A service with client keys only carries
nimbus unused.

**Rationale**: the alternatives are a JDK-only `id_token` verifier beside `MachineTokens` (a second
JWT verifier in the tree, which `auth-oidc` exists to avoid), or the broker living in `auth-oidc`
behind a seam the MCP endpoint fills (two modules and a trait for one feature, and `auth-oidc` would
then depend on `mcp` for the endpoint's types, or vice versa). An MCP endpoint that admits tokens
already needs `auth-oidc`; the dependency makes that the default. **This is the one dependency
decision worth a second look before tasks**: if an `ankka-mcp` free of nimbus matters, the broker
moves to `auth-oidc` under a `SignIn` seam and `brokered` becomes `brokered = Some(SignIn.fromEnv())`.

## R13. Behind the sidecar: a new discovery entry, a new rpc, a minor bump to 1.20

**Decision**: protocol `1.20` (`WireProtocol.Version`, `Conversation.scala:199`;
`Compatibility.version`, `Compatibility.scala:80`; `protocol/README.md:16`; the three SDK constants
and the three pin tests; the three proto copy scripts). In `discovery.proto`, `Spec` gains
`repeated McpEndpoint mcp_endpoints = 5` with `McpEndpoint { id, path, name, version, McpAcl acl,
repeated McpTool tools, bool brokered }`, `McpTool { name, description, input_schema_json,
optional McpAcl acl }` and `McpAcl { allow_all, deny_all, tokens, client_keys, repeated
CallerMatcher callers }` (any-of). A new `mcp.proto`: `service Mcp { rpc InvokeTool
(McpToolRequest) returns (McpToolReply) }` with `McpToolRequest { endpoint_id, tool,
arguments_json, optional Principal principal, optional Caller caller, Metadata metadata }` and
`McpToolReply { oneof { McpToolAnswer ok | string error | Failure failure } }`. `client.proto`'s
`Client` gains `IssueClientKey`, `ListClientKeys`, `RevokeClientKey`, each with an `Error` in the
reply (`client.proto:17-19`'s rule). The sidecar's `Discovery` gains `McpSince = 20` and refuses a
`Spec` with MCP endpoints under an older minor; each SDK refuses discovery from a runtime below
1.20 when it declares one (Python `server.py:81-93`, TypeScript `server/discovery.ts:153-166`,
Rust `service.rs:170-183`), last in the refusal order.

The sidecar's `RemoteMcpEndpoint extends McpEndpoint` builds its tools from the spec with a run
function `conversation.invokeMcpTool(…)` (a new `Conversation` method with plain-value types in
`runtime/remote`, as `handleHttp` has; `GrpcConversation` sends the rpc, `WasmConversation` calls
`fresh("mcp_tool", …)`). The process receives the typed arguments as the JSON the kernel already
checked, the principal and the caller, and never the credential (FR-014). **References in an SDK
are the SDK's**: Python, TypeScript and Rust resolve `tool.command(Job, "book", id="job_id")` at
registration into an ordinary tool whose run function calls the component through the SDK's own
client with the SDK's own encoding, and refuse at registration a handler or route the registered
service lacks; the sidecar sees `McpTool`s and checks them as it checks an agent's
(`Discovery.scala:310-322`). A module's MCP endpoint is the same with a new export `ankka1_mcp_tool`
(in `ModuleLoader.Exports`, `CallSite.Permitted`, `WASM-ABI.md`, `needs()`, the Rust `Export` enum
and `service!` macro) and three imports `issue_client_key`, `list_client_keys`,
`revoke_client_key`, each in a link block of its own as `call_secret` is.

**Rationale**: `.claude/rules/sidecar.md`: "a behaviour an older runtime must refuse is a new call,
not a new field", and both ends gate on the minor. The sidecar cannot encode a process's input type
(`Route` bodies are opaque bytes, and the SDK primitives are `text/plain`), so a reference can only
be resolved by the side that owns the encoding: the SDK. The verification stays in the platform's
program, as token verification does today (`RemoteEndpoint.forwardOf`, `Translate.toHttpRequest`).

## R14. Everything that shows a service shows its tools, through the topology

**Decision**: the MCP endpoint reports one `ServedRoute(method = "MCP", path = "<path> <tool>",
streaming = false, endpoint = endpointId(path))` per tool. That one record reaches: the local
console's service document and topology (`ObservabilityDocuments.scala:55-62`, `TopologyJson.scala:103-111,
326-342`, handlers of `type: "tool"`); `describe_local_service` (its description names tools,
`cli/.../mcp/AnkkaTools.scala:383`); the local console's routes panel, listed as the gRPC and socket
rows are, without an invoke form (`cli/src/main/resources/console/app.js:205-257`,
`ConsoleServer.scala:275-306`); the control plane's `services topology` and the console's topology
page through `TopologyHandler` (`controlplane-api/.../descriptors.scala:1392-1398`, whose comment
already lists "a tool"); and the names table (`DeclaredNames.of`, `CallOrigin.scala:95-111`), so a
tool's origin is believed across processes. `services get` is not changed: it lists no routes today
(`cli/.../Output.scala:589-655`), and FR-016 is amended from "`services get`" to "the service's
topology, which `services topology`, the console and the local console show".

## R15. A tool call is a span under `mcp`, and a span now carries its subject

**Decision**: component `"mcp"`, handler `"<path> <tool>"`, `SpanKind.Server`, continuing an
incoming `traceparent`, origin `CallOrigin(endpointId(path), "MCP <path> <tool>")` set inside the
virtual thread as `Tracing.request` does (`HttpServer.scala:1058-1083`); a tool error completes
`Failed`, an ACL refusal `Refused` by the router before the kernel runs. `RecordedSpan` gains
`subject: Option[String]` (`Recorder.scala:415-427`), set from the request's principal for MCP tool
spans and for HTTP route spans alike, never interned (a subject is unbounded), shown by the local
console's span detail (`ObservabilityEndpoint.scala:152-173`, `app.js:517-555`) and exported by
`telemetry-otlp` as `enduser.id`. A client key's id goes on the span as `client_key`; its secret
appears nowhere, which `McpEndpointFeatures` proves by scanning the recorder and the captured log.

## R16. Where each scenario runs

| Feature file | Suite | Why there |
|---|---|---|
| `tools.feature` (14 of 17), `client-keys.feature` (14 of 15), `access.feature` (8), `brokered-sign-in.feature` (7) | `modules/mcp/src/test/.../McpEndpointSteps` and one `GherkinSuite` subclass per file, on `AnkkaTestKit` with the test issuer (`authOidc % "test->test"`) and the agent's `McpClient` as the MCP client | the module can see `testkit`, `auth-oidc`'s `TestIssuer` and the client; `startPeer` gives a second node for the index's propagation |
| `tools.feature`: three instances, every hostname, paused; `client-keys.feature`: revoked on every instance within one second | `controlplane/src/test/.../McpEndpointsClusterFeatures` on k3s, deploying the sample, marked `ranElsewhere` in the module suite | needs the gateway, a custom hostname and three pods |
| `languages.feature` (3 outlines) and the conformance row | `sidecar/.../conformance/ConformanceSuite`: `mcp-endpoint.*` cases against all four references, marked `ranElsewhere` | the suite is where every SDK is proved against the Scala reference |
| `observing.feature`: the report | `controlplane/src/test/.../DeployedTopologyFeatures` extended | scripted instance topologies against a real control plane |
| `observing.feature`: the trace | `McpEndpointSteps` | the recorder is in the kit |
| `agents/mcp-servers.feature`: the two sibling scenarios | `modules/mcp/src/test/.../SiblingAgentSuite`, named for the scenarios | two kits, `McpServer.service("cart", …)` and `localServices`, the client of feature 029 unchanged |
| `local-console/services.feature`: the new scenario | `cli/src/test/.../ConsoleTopologyJsSuite` extended | it already reads the local console's page |
| `documentation/mcp-endpoints.feature` (4) | `controlplane-api/src/test/.../McpEndpointsDocumentationSuite`, as `GrpcDocumentationSuite` | reads the pages |

## R17. SC-002 is measured, under a switch

**Decision**: `McpLatencySuite` in `modules/mcp`, gated on `-Dankka.spikes=on` (already forwarded to
the forked JVM), reads one entity 200 times through a route and through a tool and asserts the
tools' median within 1.5× the routes'. It is not in CI's path: a latency assertion on a shared
runner is the flake `CLAUDE.md` warns of. The quickstart runs it by hand.

## R18. Documentation and the skill

**Decision**: `docs/build/mcp-endpoints.md` (kind `guide`, `languages: [scala, python, typescript,
rust]`, `components: [mcp-endpoint]`), in `mkdocs.yml`'s nav after `MCP servers` and in the
`ankka-endpoints` skill's pages with its description extended (`tools/docs/skill/ankka-endpoints/SKILL.md:3-16`);
`mcp-endpoint` added to the closed `components` facet (`mkdocs.yml:84-95`);
`modules/mcp/src/main/resources/reference.conf` added to `generated.configuration.files`
(`mkdocs.yml:107-115`) with prose for each key under a new `### MCP endpoints` heading in
`docs/reference/configuration.md`; every sample from `samples/shopping-cart`'s `CartMcpEndpoint`
and the three SDK examples' MCP endpoints through `<!-- include -->` regions. `docs/reference/limitations.md`
gains, under Components: tools only; stateless, no session and no `list_changed`; one MCP endpoint
per service's tools, no door for a project (spec 055); no authorization server for a service that
did not opt in; a route reference's body is JSON; and the "Two protocols" entry gains a clause that
MCP rides on the HTTP port. `docs/reference/akka-divergences.md` gains a row and a section: Akka's
MCP endpoint offers tools, resources and prompts through annotations; ankka's offers tools, built
and referenced, with client keys and brokered sign-in it does not have. `docs/build/mcp-servers.md`
gains a paragraph: an agent lists a sibling's MCP endpoint with `McpServer.service`. The CLI's
`describe_local_service` description and `docs/reference/runtime-endpoints.md`'s service-document
row name MCP endpoints.

## R19. Could this check pass while the thing it checks is false?

Seven, and how each is sharpened:

1. The kernel's suite driven only by the platform's own client agrees with itself: S2 drives an
   outside client, and the suite replays the protocol page's own request and response examples.
2. A `tools/list` filtered by scope that lists the right tools because the key has none: every
   scope scenario also asserts a tool absent (`client-keys.feature` does).
3. "Revoked within one second on every instance" proved on one node: `McpEndpointSteps` uses
   `startPeer` and calls the second node; the k3s suite calls three pods by their addresses.
4. "No secret in any log, trace or listing" proved by searching for a string the test chose: the
   search is for the key the platform minted, read from the issue reply, across the recorder,
   `LogCapturing`'s buffer and the listing's JSON.
5. A reference refused at startup for the wrong reason: each refusal scenario asserts the text names
   the endpoint and the tool, and a passing reference beside it starts.
6. The `405` on `GET` proved only for `GET`: `DELETE` and a `POST` batch are cases too.
7. The brokered flow proved with the broker trusting the issuer's token unverified: a
   `brokered-sign-in` case has the test issuer answer the code exchange with an `id_token` signed by
   another key and asserts no token is minted.

## Spikes, first

- **S1** (`modules/mcp`, `-Dankka.spikes=on`): a second r2dbc query plugin config at 500ms sharing
  the connection factory; measure the eviction delay across two kit nodes (R8).
- **S2** (`modules/mcp`, `-Dankka.spikes=on` and `-Dankka.mcp.spike.client=…`): the endpoint
  started on loopback, driven by `npx @modelcontextprotocol/inspector` or Claude Code pointed at
  it, listing and calling a tool; then with brokered sign-in against `TestIssuer`, the inspector's
  OAuth flow end to end (R3, R11).
