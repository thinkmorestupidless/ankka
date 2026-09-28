# Research: A Console for a Deployed Installation

**Feature**: `017-control-plane-console` | **Date**: 2026-09-28

Each entry resolves an unknown the plan depends on. Facts about the platform were read from this
repository; facts about libraries were checked against their current documentation on 2026-09-28.

## R1. Framework: React Router 8 in framework mode

**Decision**: React Router **8.4** (released 2026-09-15; v8.0 on 2026-06-17) in framework mode, on
Vite 7, React 19 and Node 24, published ESM only. Middleware is always on in v8 (the `future.v8_middleware`
flag is gone), and `context` in loaders, actions and middleware is a `RouterContextProvider`. Route
modules are split by default (`splitRouteModules: true`).

**Rationale**: The spec's shape — server-rendered routes, forms as the unit of mutation, client-side
navigation after hydration, a plain Node server — is what framework mode is. Middleware is where the
session, the CSRF origin check and the host's extension registry are put onto every request without
each route repeating it. v8's minimums (Node ≥ 22.22, React ≥ 19.2.7) are compatible with the
repository's Node line (22 floor, 24 documented).

**Alternatives considered**: Next.js (hosting assumptions, opaque caching, heavy self-hosting);
SvelteKit (equally capable, but a second component model in a repository whose local console and
SDK examples are plain JavaScript and whose product will hire against React); a static bundle
(cannot hold a bearer token safely, no first paint without scripts).

## R2. Routes from a package: `relative()` and absolute file paths

**Decision**: The package exports `consoleRoutes(): RouteConfigEntry[]`, built with
`@react-router/dev/routes`' `relative(dir)` helper over the package's own published `dist/routes/`
directory, so each entry's `file` is an absolute path into the installed package. The host's
`routes.ts` is `layout("./layout.tsx", [...prefix(mount, consoleRoutes())])` — the layout and the
mount prefix are the host's, the entries are the package's. The host lists the package under Vite's
`ssr.noExternal` so its route modules are bundled like the host's own.

**Rationale**: Route config entries are file paths, not module specifiers; the upstream proposal to
accept module specifiers (react-router discussion #13747, "Rails Engines") is open with maintainer
support and no implementation. `relative()` exists precisely to build entries from another
directory, and an absolute path is what it produces. This is the smallest mechanism that keeps the
route *files* in the package.

**Risk and fallback**: The first task of implementation is a spike proving the mechanism in the
fixture host — entries from `node_modules`, split route modules, HMR in dev, a production build. If
the Vite plugin refuses files outside the app directory, the fallback is one-line re-export route
files in the host, one per page (about twenty lines in total), generated once by a script the
package ships; the package's route modules and helper stay as designed and only the host's
`routes.ts` changes shape.

## R3. Server: `node:https` with `createRequestListener`, no Express

**Decision**: `@react-router/node`'s `createRequestListener` on a `node:https` server in a cluster
and `node:http` locally. Two listeners in a cluster: the serving port `9000` (TLS, client
certificate required) and the readiness port `7627` (plain HTTP, `GET /ready`), named `http` and
`probe` as on every ankka workload.

TLS: `tls.createSecureContext` from the mounted certificate files, `server.setSecureContext()` when
their mtime changes (polled every 30 seconds — the same rule as the runtime's `RotatingTls`),
`requestCert: true`, `rejectUnauthorized: true` against the service authority's `ca.crt`, and a
per-request check that the peer certificate's SAN carries `URI:ankka://gateway` (from
`socket.getPeerCertificate().subjectaltname`); anything else is `403`. The client to the control
plane is an `https.Agent` built from the same files, rebuilt on the same rotation, with
`ca` the service authority and `servername` the control plane's service name.

**Rationale**: Express adds nothing the console uses, and a bare Node server is what
`setSecureContext` and client-certificate inspection need direct access to. `/ready` answers `200`
only once the secure context has loaded and the serving port is bound (FR-032); until the
certificate exists it answers `503`, which is what holds the pod un-ready.

**Alternatives considered**: `react-router-serve` (no TLS, no rotation); Express or Hono (an
adapter over the same listener, one more dependency for no capability).

## R4. Sign-in: `openid-client` 6, two addresses, one configuration

**Decision**: `openid-client` 6.x. At startup the console reads the control plane's `GET /auth`
(issuer, audience) so the issuer it expects is the one the control plane expects, then runs
`discovery()` against the **backchannel** address (`ANKKA_CONSOLE_AUTH_BACKCHANNEL_URL`, the
in-cluster Keycloak service, verified against `ANKKA_CONSOLE_AUTH_CA`; locally unset, so the issuer
itself). Keycloak answers discovery with its configured external hostname in every URL, as it does
for the control plane's key fetch. The console then builds a `Configuration` from that metadata with
the `token_endpoint`, `jwks_uri`, `revocation_endpoint` and `end_session_endpoint` origins rewritten
to the backchannel address and the `issuer` and `authorization_endpoint` left external. Client
authentication is `ClientSecretBasic` with `ANKKA_CONSOLE_CLIENT_SECRET`; the flow is
`buildAuthorizationUrl` with S256 PKCE, `state` and `nonce`, `authorizationCodeGrant` with
`pkceCodeVerifier`, `expectedState`, `expectedNonce` and `idTokenExpected`, `refreshTokenGrant`,
`tokenRevocation` and `buildEndSessionUrl` with `id_token_hint` and `post_logout_redirect_uri`.
Locally, `allowInsecureRequests` for the compose Keycloak's plain HTTP.

**Rationale**: The two-address problem is the control plane's own (`issuer` vs `jwks-url`) and this
is the same answer. Reading the issuer from `GET /auth` removes a setting and a way to disagree.
`openid-client` is the maintained, certified client; hand-rolling code flow, PKCE and id-token
verification is the kind of code that is wrong in ways tests do not find.

**Realm client**: `ankka-console`, confidential (`publicClient: false`, `secret`), standard flow on,
`pkce.code.challenge.method: S256`, default scope `ankka-controlplane` (so the access token carries
the control plane's audience and claims — the realm has no built-in scopes, and the console asks for
`openid` only, as ankka-cloud learned), `redirectUris: ["https://CONSOLE_AUTHORITY/*",
"http://localhost:3000/*"]`, `postLogoutRedirectUris` the same, `webOrigins: ["+"]`. The refresh
token policy is the realm's default (`revokeRefreshToken` unset, so a refresh token is reusable
until it expires), which R5 depends on.

## R5. Session: sealed cookie, in-memory access token cache, write-back on refresh

**Decision**: Cookie `__Host-ankka_console` in a cluster (`Secure; HttpOnly; SameSite=Lax; Path=/`),
`ankka_console` locally over plain HTTP (same flags minus `Secure`). The value is
AES-256-GCM over `{ "v": 1, "rt": "<refresh token>", "iat": <epoch seconds> }` with a random
96-bit nonce and the cookie name as additional authenticated data, the key derived by HKDF-SHA256 from
`ANKKA_CONSOLE_SESSION_SECRET` with the info string `ankka-console session v1`, encoded base64url.
Web Crypto from `node:crypto`, no library. A Keycloak refresh token for this realm is 600–900 bytes,
so the sealed cookie is about 1.2 KB — under SC-003's 2 KB, and it does not grow with the access
token's claims.

Each instance keeps `Map<sha256(refreshToken), { accessToken, expiresAt, refreshToken }>`, bounded
(10,000 entries, least recently used evicted). A request with a cookie whose entry is missing or
expired performs `refreshTokenGrant`; Keycloak answers with a new access token and, by default, a
new refresh token whose `exp` is the session idle timeout from *now*. The console caches under both
the old and the new token's digest and writes the new one back into the cookie on that response, so
the cookie's lifetime slides with use and a concurrent request on another instance holding the old
cookie still refreshes successfully until the old token's own `exp`. A refresh refused with
`invalid_grant` ends the session: cookie cleared, redirect to sign-in with the return-to path.

The pending sign-in is a second sealed cookie, `__Host-ankka_console_login`, holding
`{ state, nonce, verifier, returnTo }` with a ten-minute lifetime, consumed by the callback.

**Rationale**: This is clarification 1. The refresh token is the smallest thing that can re-derive a
session; the access token is the largest and the most dangerous to persist. The cache is an
optimisation the design does not depend on: a cold instance simply refreshes.

**Session store interface** (FR-044): `SessionStore` with `read(request) → Session | null`,
`write(session, response)`, `clear(response)`; `SealedCookieSessionStore` is the default. A host
with a database implements the three methods over its own store and the sign-in machinery is
unchanged.

## R6. CSRF: same-site cookies plus an origin check in middleware

**Decision**: Every mutation is a `POST` form or fetch to the console's own origin. Middleware
refuses a non-`GET` request whose `Sec-Fetch-Site` is `cross-site`, or whose `Origin` header is
present and is not the console's own origin (`ANKKA_CONSOLE_AUTHORITY` in a cluster, the request's
host locally). `SameSite=Lax` on the session cookie means a cross-site `POST` carries no session
anyway; the header check is the second lock. No synchroniser token is needed, and none is used, so
the forms stay plain.

## R7. The package boundary and its extension points

**Decision**: One npm workspace at `console/` with three members: `console/package` (published as
`ankka-console`), `console/host` (private, the image) and `console/e2e` (private, Playwright). The
package exports:

- `consoleRoutes()` — the route entries (R2).
- `consoleMiddleware(options)` — a middleware for the host's root route that reads the session,
  refreshes tokens, checks origins on mutations and puts a `ConsoleContext` on the request's
  `RouterContextProvider`.
- `SessionStore`, `SealedCookieSessionStore`, `TokenSource` — the session and token interfaces and
  their defaults (R5).
- `ConsoleExtensions` — `{ panels, actions, hidden }` registered by the host and carried on the
  context: panels are React components with an optional `load(context, entity)` run in the page's
  loader under `Promise.allSettled`, rendered inside an error boundary; actions are `{ operation,
  label, href | onSubmit }` shown beside a named operation; `hidden` is a set of operation names.
- `ControlPlaneClient` and the wire types (R8).
- Test doubles: `fakeControlPlane()` and `fakeIssuer()` (R11), exported from `ankka-console/testing`.

Links inside pages are built by `useHref()` over the mount prefix the middleware records from the
matched route's `pathname` base — never a literal absolute path (FR-042). Page styles are shipped as
one stylesheet with `ac-` prefixed classes and CSS custom properties on `.ac-root`, so a host
overrides colours and type without forking (Assumptions).

## R8. The client and the fixtures the platform emits

**Decision**: `ControlPlaneClient` is a `fetch`-based class taking `{ baseUrl, agent?, token }` and
one method per route in `contracts/control-plane-routes.md`. Responses are decoded with **zod 4**
schemas mirroring the Scala wire types in `controlplane-api`'s `descriptors.scala`, with unknown
keys stripped (FR-046). Scala `Option` fields are optional; `Instant` is an ISO-8601 string;
`LocalDate` is `YYYY-MM-DD`; enums (`Role`, `ServiceLifecycle`) are their string words.

A new suite in `controlplane-api`, `ControlPlaneFixturesSuite`, writes one JSON file per wire codec
into `console/package/fixtures/control-plane/` — `{ "type": "ServiceStatus", "json": {…} }` with a
sample value that fills every optional field — and fails when a regenerated file differs from the
committed one, exactly as `EncodingFixturesSuite` holds `protocol/fixtures/`. A `Wire` codec without
a fixture fails that suite (SC-010). The package's test decodes every file with its schema. The CI
`scala` job's filter gains `console/package/fixtures/**` so a fixture edit re-runs the suite.

## R9. Streaming: one resource route, server-sent events, polling as the person

**Decision**: A resource route `GET <mount>/stream/services/:projectId/:name?status&logs=<params>`
returning `text/event-stream` from a `ReadableStream`. Every 2 seconds the server reads the service
(and, when `logs` is asked for, its logs with the chosen instance, `tail` and a `since` of 4
seconds) with the session's current access token, refreshing as any request does. It sends
`event: status` with the `ServiceStatus` JSON when it differs from the last sent, `event: logs`
with `{ instance, lines }` for lines not yet sent (R10), a `: keepalive` comment every 15 seconds,
and `event: session-ended` then closes when the token cannot be refreshed. A listing page opens
one stream per page on `GET <mount>/stream/projects/:projectId` with `event: services`. The browser
uses `EventSource` (same-origin, cookie sent); its built-in reconnect covers a rolling replacement,
and every stream begins by sending the current state, so nothing is carried over.

**Rationale**: Clarification 2. The interval and the one-read-per-page budget are SC-012's. Every
event's `data` is a JSON document (the SSE trap in CLAUDE.md). Envoy streams a chunked response
without buffering, and HTTP/1.1 to the backend is enough for SSE.

## R10. Following logs without a cursor

**Decision**: Per stream and instance, the server keeps the last `tail` lines it sent. Each read
takes the window `since=4s` (twice the interval), splits it into lines, finds the longest suffix of
the sent lines that is a prefix of the new window, and sends the remainder. A line repeated
identically within one window may be dropped or doubled; the docs say so and recommend timestamps
in a service's own log lines (spec edge case). The page shows "following" and a pause control that
closes the stream; the server never holds more than the window (FR-017a).

## R11. Test doubles: a fake control plane and a fake issuer, in the package

**Decision**: `fakeControlPlane()` is a Node `http` server implementing every route in
`contracts/control-plane-routes.md` over in-memory state with the control plane's status codes and
error bodies, seeded from a fixture and scriptable (a route can be told to answer `503` once, or a
service to move through lifecycle states on a timer). `fakeIssuer()` is an OpenID Connect provider
with `jose`: discovery, an authorization endpoint that serves an HTML form and redirects with a code,
token (code and refresh grants, with rotation), JWKS, revocation, end-session, and a switch to
expire a session. Both run in-process in unit tests and as processes for Playwright's fake target.

**Rationale**: FR-037a's continuous-integration setting needs a control plane and an identity
provider without a JVM; the fixture host (FR-048) needs the same two; ankka-cloud's tests already use
an in-process issuer for the same reason.

## R12. Playwright: two projects, two targets, an axe audit

**Decision**: `@playwright/test` with projects `scripts-on` and `scripts-off`
(`javaScriptEnabled: false`), Chromium only in CI; `CONSOLE_E2E_TARGET=fake` (default; the suite
starts the fakes and the host itself) or `compose` (the compose Keycloak on 8081 and a control plane
on 9000, started by the developer or `just`). `@axe-core/playwright` runs on every page a test
visits, failing on any WCAG 2.1 AA violation (SC-013); a `keyboard` tagged subset completes each
operation with keyboard events only. The suite exports `scenarios.ts` mapping spec scenario ids
(`US1-3`, `US3-4a`) to test titles, and a test fails when a listed scenario has no test (SC-011).
Traces and screenshots are kept on failure. Timing (SC-004) is read from the host's request log and
from `performance.getEntriesByType("navigation")` on the compose target.

## R13. Proving the deployed console: one case in the end-to-end cluster suite

**Decision**: `EndToEndClusterSuite` gains a case that installs the console component, waits for its
rollout, and drives it from the host with `curl` as a subprocess — cookie jar, `--cacert` the
exported root, `--resolve` for `console.test.local` and `auth.test.local` onto the mapped HTTPS port
— exactly as the deploy script's smoke test and the CLI's `--resolve` proof do. Keycloak's login is
a form: the case fetches the console's sign-in redirect, follows to Keycloak, extracts the form's
`action` (unescaping `&amp;`), posts the development user's credentials, follows the redirect chain
back through the callback, then reads the organizations page, posts the create-organization form
and reads the project's service listing (FR-039). A Playwright run inside a JVM suite was rejected:
a browser in the k3s suite is a second harness for what `curl` proves.

## R14. Deployment: a component, a Secret with development values, one new ConfigMap key

**Decision**: `kustomization/components/console/` with namespace `ankka-console` (labelled
`app.kubernetes.io/managed-by: ankka`, which is what the control plane's HTTP network policy
already admits, so it needs no change), a ServiceAccount with no bindings, a Deployment (2 replicas,
`RollingUpdate` 1/0, `preStop.sleep: 5s`, `terminationGracePeriodSeconds: 30`, readiness `httpGet`
`/ready` on port `probe`, `fsGroup` for the `0440` certificate mount, non-root), a Service on
`9000`, an `HTTPRoute` for `console.BASE_DOMAIN`, a `Certificate` from `ankka-service` with URI
`ankka://platform/console` and the Service's DNS names, a `BackendTLSPolicy`, a NetworkPolicy
admitting the gateway's proxy pods on `9000` and anyone on `7627`, and a Secret
`ankka-console-secrets` with `clientSecret: dev` and a development `sessionSecret`.

The console needs its own external authority for redirect URIs and the origin check, and the realm
client's redirect URI needs it too. A kustomize replacement can substitute one delimited segment,
not compose domain and port, so `ankka-platform` gains one key written beside the other two:
`consoleAuthority: console.127.0.0.1.sslip.io:8443` locally, `console.example.com` in the cloud
overlay. Replacements copy it into the Deployment's `ANKKA_CONSOLE_AUTHORITY`, the `HTTPRoute`'s
hostname is still derived from `baseDomain` as the others are, and the realm client's
`redirectUris.0` and `postLogoutRedirectUris.0` (`https://CONSOLE_AUTHORITY/*`, delimiter `/`,
index 2). `RemoteOverlaySuite` asserts `consoleAuthority` equals `console.<baseDomain>` with the
port when it is not 443, in both overlays, so the derived key cannot disagree with the two it is
derived from.

The cloud overlay deletes `ankka-console-secrets` (the identity provider's admin secret's pattern),
patches the realm client's `secret` to `SET`, and replaces its redirect list so `localhost` is gone;
its `images:` block gains `ankka-console`. The realm import is one-shot, so `platform/console.md`
gives the `kcadm` command that adds the client to an installation imported before this feature.

## R15. Image, local deploy and release

**Decision**: `console/Dockerfile`, multi-stage on `node:24-bookworm-slim`, running as uid 1000,
`npm ci --workspaces` then `react-router build` for the host, production image with the host's
`build/` and its production dependencies only. `deploy-local.sh` builds it with `docker build -t
ankka-console:latest console` after the sbt images and `kind load`s it; it prints the console's
address beside the control plane's. The release's `images` job adds a `docker build` and push of the
same image and `ankka-console` to `IMAGES`, so the public-pullable check and the cache warm cover it.
`sbt buildAll` is unchanged; `just build-console` and `just test-console` wrap the npm commands, one
command each.

## R16. Package publication: `ankka-console` on npm, at the platform's version

**Decision**: Unscoped `ankka-console` (the SDK holds `ankka`; a scoped `@ankka/*` name needs an
organization on npm that is not held). Version `0.0.0` in the tree; the release's `sdk-typescript`
job's shape is copied as a `console-package` job: `npm version` from the tag, build, pack, install
into an empty directory, import the entry points, publish under trusted publishing on Node 24. The
first publish is by hand, as the SDK's was, because npm cannot create a package through trusted
publishing. Ankka-cloud pins `ankka-console` at the platform version it runs.

## R17. Graceful shutdown and observability of the console itself

**Decision**: On `SIGTERM` the server stops accepting, sends `event: server-closing` on every open
stream and ends them, calls `server.close()` and `closeIdleConnections()`, and exits within 10
seconds — inside the `preStop` sleep plus grace period. Every request writes one JSON line to stdout
with method, path (parameters unfilled), status, duration and the session's subject; never a
cookie, header or token. `/ready` is the only health endpoint; no metrics in this feature.

## R18. Documentation

**Decision**: Three new pages — `operate/console.md` (using it, kind `guide`), `platform/console.md`
(installing, configuring, removing, adding the client to an older realm, kind `guide`) and
`reference/console-package.md` (for host developers, kind `reference`) — plus edits to
`reference/limitations.md`, `platform/identity.md`, `platform/install-local.md`,
`platform/install-cloud.md` and the table in `concepts/observability.md`. New pages go in
`mkdocs.yml`'s nav and in the `ankka-platform` and `ankka-deploy` skills' `pages:` lists. The CI
`docs` filter gains `console/**` because the reference page includes samples from the package.
