# Data Model: A Service as an MCP Server

What is declared, recorded and reported, and in what shape. Decisions are in
[research.md](research.md); the wire forms are in [contracts/](contracts/).

## The MCP endpoint (declared, R2)

| Field | Type | Rule |
|---|---|---|
| `path` | string | starts with `/`, no trailing `/`, not `/_ankka` or under it, not `/.well-known` or under it; default `/mcp`; unique among the service's endpoints' prefixes and not equal to any mounted route's rendered path |
| `acl` | `Acl` | required; `Acl.anyOf` combines kinds |
| `name`, `version` | string | the handshake's `serverInfo`; default the service's name and image tag (`BuildInfo`), else `"ankka"` and `"0.0.0"` locally |
| `tools` | `Vector[McpTool]` | at least one; names unique |
| `brokered` | boolean | default false; true needs a `TokenAuthenticator` in the ACL and an issuer with a client id |

Derived: `admitsTokens` (any `TokenAuthenticator` in the ACL tree), `authorizationServers` (the
issuers' URLs, or the broker's issuer when brokered), `admitsClientKeys` (any
`ClientKeyAuthenticator`).

## The tool (declared, R4)

| Field | Type | Rule |
|---|---|---|
| `name` | string | `[a-z0-9_-]+`, at most 128 characters (`McpTools.MaxToolName`) |
| `description` | string | non-blank |
| `inputSchema` | `Json` | `{"type":"object","properties":{…},"required":[…],"additionalProperties":false}`, from `SchemaType`s, a `JsonSchema.derived`, or a reference's input with the id added |
| `acl` | `Option[Acl]` | replaces the endpoint's for this tool |
| `kind` | `Handler` / `Command(handle, id, input)` / `Query(handle, id, input)` / `Route(endpoint, method, template)` | a reference is resolved in `problems(mounting)` |
| `run` | `ToolCall => ToolAnswer` | runs on the request's virtual thread in the request scope |

`ToolCall(arguments: Json.Obj, context: RequestContext)`; `ToolAnswer(text: String, structured:
Option[Json])`. A `ToolOutput[R]` that is JSON gives both; a scalar or string gives text alone.

## The client key (R8, R9)

### The credential

`ankka_ck_` + 16 lower-case hex (the id) + 64 lower-case hex (the secret). Stored as
`sha256(secret)` in lower-case hex. Compared with `MessageDigest.isEqual`. Presented as
`Authorization: Bearer ankka_ck_…`. `OpaqueCredentials("ankka_ck_")` in `http` mints, parses,
digests and compares; `DeployTokens` becomes `OpaqueCredentials("ankka_")` with its tests pinning
the shape.

### The entity `ankka-client-key` (event sourced, platform)

Entity id: the key's id. Wire names: `issue`, `record-use`, `revoke`, `get` (query).

| Event | Fields |
|---|---|
| `ClientKeyIssued` | `digest`, `subject` (non-blank), `attributes: Map[String, String]` (≤ 16 entries, each name and value ≤ 256 characters), `label: Option[String]`, `endpoint: String` (the MCP endpoint's path), `scope: Vector[String]` (tool names; empty means every tool), `issuedAt: Instant`, `expiresAt: Option[Instant]` (`None` is "never"; the *command* carries `Expiry = After(duration) | At(instant) | Never` and the handler refuses an absent one, so "none given" and "never" are distinct on the way in and only "never" is stored) |
| `ClientKeyUsed` | `at: Instant` (persisted only when later than the held value by a minute or more) |
| `ClientKeyRevoked` | `at: Instant` |

State: the issued fields plus `lastUsed: Option[Instant]`, `revokedAt: Option[Instant]`. `issue`
on an existing id, or any command on an unknown id but `issue`, is a conflict / not found. A
revoked key accepts `record-use` as a no-op and `revoke` again as a no-op.

Refusals at `issue` (in the handler, as the entity's own state, and before it in `ClientKeys.issue`
for what needs the endpoint): blank subject; expiry absent; expiry in the past; more than 16
attributes or one too long (naming the attributes); a scope naming a tool the endpoint lacks
(naming the tool); several MCP endpoints and no `endpoint` given (naming the paths).

### The index (per node, in memory)

`ClientKeyIndex extends RuntimeExtension`, name `"client-keys"`. `Live(digest, subject, attributes,
label, endpoint, scope, expiresAt, persistedLastUsed, touched: AtomicReference[Option[Instant]])`
keyed by id; a `revoked: Set[String]`; `caughtUp: Boolean` (readiness). Fed by
`currentEventsBySlices` then `eventsBySlices` for entity type `ankka-client-key` through the query
plugin `ankka.mcp.client-keys.query` (R8); `admit` and `evict` write through on the issuing and
revoking node; a sweep every `ankka.mcp.client-keys.last-use-interval` (1 minute) sends
`record-use` for touched keys whose persisted use is older than the interval.

### What a listing shows

`ClientKeySummary(id, subject, attributes, label, endpoint, scope, issuedAt, expiresAt: Option,
lastUsed: Option, revokedAt: Option)`. Never the digest, never the secret. Listed through the
`ankka-client-keys` view? No: through the index on the local node (it holds every key, caught up),
so a listing is a read of memory and needs no view table; `revoked` keys are listed from the index's
remembered set with their last state. The Python, TypeScript and Rust clients receive the same
record over `ListClientKeys`.

## The principal a client key sets (R8)

`Principal(subject = key.subject, name = key.label, claims = key.attributes + ("kind" ->
"client-key") + ("client_key" -> key.id), issuer = Some("client-key"))`. A handler reads
`principal.subject` and `principal.claims("person")` exactly as for a token.

## The sign-in broker (R11)

### `ankka-sign-in-record` (key value entity, platform)

Entity id: `client:<client_id>`, `grant:<code or state>`, `refresh:<digest>`. One state, a sum type:

| State | Fields | Lifetime |
|---|---|---|
| `Client` | `clientId`, `redirectUris: Vector[String]`, `clientName: Option[String]`, `registeredAt`, `lastUsedAt` | 30 days from last use |
| `Grant` | `clientId`, `redirectUri`, `codeChallenge`, `resource`, `clientState`, `brokerState`, `nonce`, then after the callback `subject`, `claims`, `code` (one-shot), `issuedAt` | 10 minutes |
| `RefreshToken` | `digest`, `clientId`, `subject`, `claims`, `resource`, `issuedAt`, `expiresAt` | 30 days; replaced on use (rotation) |

Each record schedules the platform timed action `ankka-sign-in-expiry` (payload: the record's id)
at its expiry, which deletes it. `Grant` and `RefreshToken` are deleted on use as well.

### `ankka-sign-in-keys` (event sourced, platform; one per service, id `keys`)

| Event | Fields |
|---|---|
| `SignInKeyAdded` | `kid` (`yyyyMMddHHmmss` + 4 hex), `publicKeyJwk: String`, `createdAt` |
| `SignInKeyRemoved` | `kid` |

State: the live keys, newest last. The private key of each is in the service's secret store under
`ankka-sign-in-key-<kid>` (PKCS#8 PEM), written before the event is persisted and deleted after
`SignInKeyRemoved`. Rotation every 30 days, kept 31; never fewer than one.

### The broker's token

JWT, RS256, header `{"alg":"RS256","kid":…,"typ":"JWT"}`, claims `iss` (the broker's issuer URL),
`sub` (the issuer's subject), `aud` (the `resource`), `iat`, `exp` (1 hour), `jti`, `typ: "Bearer"`,
`sign_in_issuer` (the service's issuer name), plus the claims `Principals.from` kept from the
issuer's `id_token` (`name`, `email`, `email_verified`, roles). Verified at the endpoint by the
broker's `TokenAuthenticator` from the per-node public-key cache; the principal is
`Principals.from`'s shape with `issuer = Some("sign-in")`.

## What the service reports (R14)

One `ServedRoute` per tool:

| Field | Value |
|---|---|
| `method` | `"MCP"` |
| `path` | `"<endpoint path> <tool name>"`, e.g. `"/mcp get_cart"` |
| `streaming` | `false` |
| `endpoint` | `"endpoint:<endpoint path>"` |
| `grantable` | whether the endpoint's or tool's ACL names `Callers.granted` |

In the topology the endpoint node `endpoint:/mcp` carries handlers `{"name": "get_cart", "type":
"tool"}`; the service document's `routes` carries the records above; `TopologyHandler.type` is
`"tool"`.

## The span (R15)

`RecordedSpan` gains `subject: Option[String]`. An MCP tool span: `component = "mcp"`, `handler =
"<path> <tool>"`, `kind = Server`, `subject = principal.subject` when there is one, and, for a key,
the attribute `client_key = <id>`. The entity's span is its child.

## Configuration (`modules/mcp/src/main/resources/reference.conf`)

| Key | Default | Meaning |
|---|---|---|
| `ankka.mcp.client-keys.last-use-interval` | `1 minute` | how often a touched key's use is recorded |
| `ankka.mcp.client-keys.query` | the r2dbc query config at `refresh-interval = 500ms` | the index's follower |
| `ankka.mcp.sign-in.token-lifetime` | `1 hour` | the broker's access token |
| `ankka.mcp.sign-in.refresh-lifetime` | `30 days` | the broker's refresh token |
| `ankka.mcp.sign-in.grant-lifetime` | `10 minutes` | a pending authorization or code |
| `ankka.mcp.sign-in.client-lifetime` | `30 days` | a registered client unused |
| `ankka.mcp.sign-in.rotate-every` / `keep-for` | `30 days` / `31 days` | the broker's keys |

No new `ANKKA_` variable: the broker's client at the issuer is `ANKKA_AUTH_<NAME>_CLIENT_ID` and
`ANKKA_AUTH_<NAME>_CLIENT_SECRET` under the existing `ANKKA_AUTH_` family, which `PlatformVariables`
already routes to the platform's program only.
