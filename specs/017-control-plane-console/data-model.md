# Data Model: A Console for a Deployed Installation

**Feature**: `017-control-plane-console` | **Date**: 2026-09-28

The console holds no durable state. Everything below is either a value that lives in a cookie, in an
instance's memory for the life of a request or a stream, or a mirror of a control plane wire type.
The control plane's types are named as `descriptors.scala` in `controlplane-api` declares them; the
package's schemas mirror them field for field and are held to the fixtures of R8.

## Session and sign-in

### Session

What the sealed cookie carries. Written by the callback and by a refresh that returned a new token;
cleared by sign-out and by a refused refresh.

| Field | Type | Meaning |
|---|---|---|
| `v` | `1` | Format version; a cookie with another version is treated as absent. |
| `rt` | string | The refresh token the identity provider issued for this browser. |
| `iat` | integer, epoch seconds | When this value was written; a value older than the realm's session maximum is treated as absent without a call. |

Invariants: never contains an access token, an identity token, a subject or an email. Its lifetime is
the identity provider's, not the console's — the cookie's `Max-Age` is the realm's SSO maximum and
what makes it valid is the refresh token still being honoured.

### PendingLogin

The second sealed cookie, written when a person is sent to sign in and consumed by the callback.

| Field | Type | Meaning |
|---|---|---|
| `state` | string, 32 random bytes base64url | Must equal the callback's `state`. |
| `nonce` | string, 32 random bytes | Must equal the identity token's `nonce`. |
| `verifier` | string | PKCE code verifier for S256. |
| `returnTo` | string | A path under the console's mount, `/` when the requested one was not. |
| `iat` | integer | Ten minutes, then refused. |

### AccessTokenEntry (in memory, per instance)

Keyed by `sha256(refresh token)`.

| Field | Type | Meaning |
|---|---|---|
| `accessToken` | string | The bearer for control plane calls. |
| `expiresAt` | epoch milliseconds | From the token response's `expires_in`, less 30 seconds of skew. |
| `refreshToken` | string | The newest refresh token for this session; equals the key's when no rotation has happened. |

Bounded to 10,000 entries, least recently used evicted. Losing an entry costs one refresh.

### Principal (per request)

Derived from the access token's claims once per request, for display and for the request log; never
a key the console decides anything by.

| Field | Source claim |
|---|---|
| `subject` | `sub` |
| `name` | `name` |
| `email` | `email` |
| `platformAdmin` | `realm_access.roles` contains `platform-admin` |

The page-level "who am I" is the control plane's `Whoami`, read by the front page, which is the
authority; the principal above only exists so a page can say who is signed in without a call.

## Host configuration and extensions

### ConsoleOptions (given to `consoleMiddleware`)

| Field | Type | Meaning |
|---|---|---|
| `controlPlane` | `{ url, tls?: { cert, key, ca, servername } }` | Where the API is and how to reach it. |
| `auth` | `{ clientId, clientSecret, backchannelUrl?, ca?, allowInsecure? }` | The realm client; the issuer and audience come from `GET /auth`. |
| `publicOrigin` | string | `https://<consoleAuthority>`; the origin the redirect URI is built from and the origin check enforces. |
| `session` | `SessionStore` | Default `SealedCookieSessionStore({ secret, secureCookies })`. |
| `tokens` | `TokenSource` | Default: the sign-in machinery over `session`. |
| `extensions` | `ConsoleExtensions` | Default empty. |

### ConsoleExtensions

| Field | Type | Meaning |
|---|---|---|
| `panels.organization` | `Panel<OrganizationDetail>[]` | Rendered on an organization's page, in order. |
| `panels.project` | `Panel<ProjectDetail>[]` | |
| `panels.service` | `Panel<ServiceStatus>[]` | |
| `actions[operation]` | `Action[]` | Rendered beside the named operation's control. |
| `hidden` | `Set<Operation>` | Operations whose control is not rendered; the control plane's refusal still applies if one is posted. |

`Panel<E>`: `{ id, title, load?(context, entity) → Promise<unknown>, Component({ entity, data }) }`.
`Action`: `{ id, label, href?(entity), method?: "GET" | "POST" }`.
`Operation`: the closed set in `contracts/control-plane-routes.md` (`organization.create`,
`organization.rename`, …, `service.logs`).

### ConsoleContext (on the request's `RouterContextProvider`)

| Field | Meaning |
|---|---|
| `principal` | `Principal | null` |
| `client` | A `ControlPlaneClient` bound to this request's token source. |
| `mount` | The path the routes are mounted under, from the matched route's base. |
| `extensions` | The host's `ConsoleExtensions`. |
| `signInUrl(returnTo)` | Where to send an unauthenticated request. |

## Streams

### StreamSubscription (per open connection, per instance)

| Field | Type | Meaning |
|---|---|---|
| `kind` | `service | project` | Which route opened it. |
| `target` | `{ projectId, name? }` | |
| `wants` | `{ status: boolean, logs?: LogsQuery }` | |
| `lastStatus` | `ServiceStatus | ServiceStatus[] | null` | Last sent, compared structurally. |
| `sentLines` | `Map<instance, string[]>` | The last `tail` lines sent per instance (R10). |
| `interval` | 2000 ms | |

`LogsQuery`: `{ instance?, previous?: boolean, tail: number, since: number }` — the CLI's four.

### Stream events (wire form in `contracts/stream.md`)

`status`, `services`, `logs`, `session-ended`, `server-closing`, plus a `: keepalive` comment.

## Mirrors of control plane wire types

The package declares a zod schema and a TypeScript type for each of the following, named exactly as
the Scala type, and the fixtures suite emits one file per name.

| Type | Used by |
|---|---|
| `AuthDiscovery` | startup |
| `Whoami`, `OrganizationMembership`, `Role` | front page, layout |
| `OrganizationSummary`, `OrganizationDetail`, `Quota`, `Usage`, `CreateOrganization`, `Owner`, `Rename` | organizations |
| `MembersResponse`, `MemberSummary`, `InvitationSummary`, `Invite`, `RoleChange`, `Repair` | members |
| `DeployTokenSummary`, `DeployTokenCreated`, `CreateDeployToken` | tokens |
| `ProjectSummary`, `ProjectDetail`, `CreateProject`, `SetRegistry`, `RegistrySummary` | projects |
| `ServiceStatus`, `ServiceLifecycle`, `ServiceDescriptor`, `ServiceSpec` (opaque: forwarded as text), `HistoryEntry`, `HistoryActor`, `LogsResponse`, `InstanceLogs` | services |
| `ErrorBody` | every refusal — the control plane's `{ "status": 409, "error": "…" }` shape as `reference/control-plane-api.md` documents it; `error` is the reason shown verbatim (FR-022) |

Mapping rules: `Option[A]` → optional field; `Instant` → ISO-8601 string; `LocalDate` → `YYYY-MM-DD`
string; `Vector[A]` → array; a Scala enum → its word (`Owner`, `Member`; the lifecycle words); an
unknown key is dropped on decode.

## State transitions

**Session**: `absent → pending` (sent to sign in) `→ established` (callback verified) `→ established`
(refresh, cookie rewritten) `→ absent` (sign-out, refused refresh, stale `iat`, changed secret).

**Stream**: `open → sending` (first state sent immediately) `→ sending` (every interval) `→ closed`
(client closed, session ended, server closing). A closed stream holds nothing.

Every other state is the control plane's, and the console shows it as reported.
