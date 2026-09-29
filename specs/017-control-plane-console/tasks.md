# Tasks: A Console for a Deployed Installation

**Input**: Design documents from `specs/017-control-plane-console/`

**Prerequisites**: plan.md, spec.md, research.md (R1–R18), data-model.md, contracts/ (package-api,
control-plane-routes, stream, configuration, fixtures), quickstart.md

**Tests**: Required by the spec — Playwright for every acceptance scenario in stories 1–4 and 7
(FR-037a, SC-011), a second host fixture (FR-048), and wire fixtures emitted by the platform
(FR-046, SC-010). Unit tests cover the pieces that need no browser. Test tasks come before the
implementation they hold, within each phase.

**Organization**: By user story, in the spec's priority order. Every path is repository-relative.
`console/package/src/...` is the published package; `console/host/...` the image; `console/e2e/...`
the Playwright suite.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable — different files, no dependency on an unfinished task
- **[Story]**: US1–US8 from spec.md; none for Setup, Foundational and Polish

---

## Phase 1: Setup (the workspace and the one spike)

**Purpose**: The three-member npm workspace, its toolchain, and proof that routes can be shipped in
a package (R2) before anything is built on it.

- [X] T001 Create the workspace: `console/package.json` (private, `"workspaces": ["package", "host", "e2e"]`, `engines.node >=24`, scripts `typecheck`, `test`, `e2e`, `build`, `dev` delegating to the members), `console/.gitignore` (`node_modules`, `build`, `dist`, `e2e/report`, `e2e/test-results`), `console/README.md` (one paragraph pointing at `docs/reference/console-package.md`)
- [X] T002 [P] Scaffold the package: `console/package/package.json` (name `ankka-console`, version `0.0.0`, `type: module`, `files: ["dist"]`, `exports` for `.`, `./client`, `./server`, `./testing`, `./styles.css` per contracts/package-api.md, `peerDependencies` on `react`, `react-dom`, `react-router`, `@react-router/dev`, `@react-router/node`; dependencies `openid-client`, `zod`; devDependencies `typescript`, `@types/node`, `@types/react`, `jose`), `console/package/tsconfig.json` (ESM, `erasableSyntaxOnly`, `rewriteRelativeImportExtensions`, `jsx: react-jsx`, `outDir dist`), `console/package/tsconfig.build.json`
- [X] T003 [P] Scaffold the host: `console/host/package.json` (private, dependencies `ankka-console` via `"file:../package"` workspace link, `react`, `react-dom`, `react-router`, `@react-router/node`, `@react-router/dev`, `vite`, `isbot`; scripts `dev` = `react-router dev`, `build` = `react-router build`, `start` = `node server.js`, `typecheck` = `react-router typegen && tsc`), `console/host/react-router.config.ts` (`ssr: true`, `appDirectory: app`), `console/host/vite.config.ts` (react-router plugin, `ssr.noExternal: ["ankka-console"]`), `console/host/tsconfig.json`
- [X] T004 [P] Scaffold the browser suite: `console/e2e/package.json` (private, `@playwright/test`, `@axe-core/playwright`; scripts `test` = `playwright test`), `console/e2e/playwright.config.ts` with projects `scripts-on` and `scripts-off` (`javaScriptEnabled: false`), Chromium, `trace: retain-on-failure`, `screenshot: only-on-failure`, `reporter` html to `console/e2e/report`, and a `globalSetup` that starts the fake target unless `CONSOLE_E2E_TARGET=compose`
- [X] T005 Run `npm install` in `console/` to produce `console/package-lock.json`; commit the lock; verify `npm ci` from clean succeeds
- [X] T006 [P] Add to `Justfile`: `just build-console` (`cd console && npm ci && npm run build`), `just test-console` (`cd console && npm ci && npm run typecheck && npm test && npm run e2e`, the fake target) and `just test-console-compose` (`cd console && CONSOLE_E2E_TARGET=compose npm run e2e`, documented as needing `docker compose up -d` and `sbt controlPlane/run` already running — the same precondition as `ankka login` against compose); add `test-console` to `just test-all`, each recipe one command; mention all three under *Commands* in `CLAUDE.md`
- [X] T007 Spike R2 in `console/package/src/routes.ts` and `console/package/test/fixture-host/`: a package `consoleRoutes()` built with `relative()` over `dist/routes/`, one placeholder route module `console/package/src/routes/front.tsx`, a fixture host `app/routes.ts` mounting it under `/x` inside `app/layout.tsx`; prove `react-router dev`, `react-router build` and a request to `/x` in the built server. If the Vite plugin refuses files outside `app/`, switch `consoleRoutes()` to emit one-line re-export files via `console/package/src/bin/write-routes.ts` and record the outcome in `specs/017-control-plane-console/research.md` under R2

**Checkpoint**: `npm ci && npm run typecheck` passes in `console/`; the spike's built host serves a page from a package route.

---

## Phase 2: Foundational (what every story needs)

**Purpose**: The client, the session machinery, the middleware, the server, the test doubles and the
host skeleton. No page in a later phase works without these.

### The client and the doubles

- [X] T008 [P] Write the wire schemas in `console/package/src/client/schemas.ts`: one zod schema and exported type per row of data-model.md's mirror table (`AuthDiscovery`, `Whoami`, `OrganizationSummary`, `OrganizationDetail`, `Quota`, `Usage`, `ProjectSummary`, `ProjectDetail`, `RegistrySummary`, `ServiceStatus`, `ServiceLifecycle`, `HistoryEntry`, `HistoryActor`, `LogsResponse`, `InstanceLogs`, `MembersResponse`, `MemberSummary`, `InvitationSummary`, `DeployTokenSummary`, `DeployTokenCreated`, `ErrorBody`, and the request bodies), `Option` → optional, `Instant` → string, `LocalDate` → string, enums → literal unions, unknown keys stripped
- [X] T009 [P] Write `console/package/src/client/errors.ts`: `ControlPlaneError { status, error, retryable }` from an `ErrorBody` or a bodiless status, and `console/package/src/client/control-plane.ts`: `ControlPlaneClient({ baseUrl, dispatcher?, token })` with one method per row of contracts/control-plane-routes.md, `Authorization: Bearer`, JSON in and out, `204` → `undefined`, `service.apply` taking the descriptor as text with `Content-Type: application/json`, logs taking `{ instance?, previous?, tail?, since? }`
- [X] T010 [P] Write the fake control plane in `console/package/src/testing/fake-control-plane.ts`: a `node:http` server over in-memory state implementing every route in contracts/control-plane-routes.md with the control plane's status codes and `{status,error}` bodies, roles (owner/member/admin from the token's claims), 404-not-403 for non-members, tombstoned ids on delete, `409` on non-empty delete, a `script()` hook to make the next answer for a route a given status, a `tick()` to advance a service through lifecycle states, and a `visited` set of `METHOD /route` templates for SC-002
- [X] T011 [P] Write the fake issuer in `console/package/src/testing/fake-issuer.ts` with `jose`: discovery, `/authorize` serving an HTML form (username, password) and redirecting with `code` and `state`, `/token` for `authorization_code` (PKCE verified) and `refresh_token` (rotating, old token valid until `exp`), `/jwks`, `/revoke`, `/logout` honouring `post_logout_redirect_uri`, tokens carrying `sub`, `name`, `email`, `email_verified`, `realm_access.roles`, `aud`; controls `expireSession(sub)`, `revokeAll()`, `failNext()`; users `dev` (platform-admin), `owner`, `member`, `outsider`
- [X] T012 Unit-test the client against the fake in `console/package/test/client.test.ts`: each method's request shape and decode, `403`/`404`/`409` → `ControlPlaneError` with the body's `error`, unknown fields ignored

### Session and sign-in

- [X] T013 [P] Write `console/package/src/session/seal.ts`: `seal(value, key, aad)` / `unseal(text, key, aad)` with AES-256-GCM (`node:crypto` webcrypto), 96-bit random nonce, base64url; `deriveKey(secret, info)` with HKDF-SHA256; unit test `console/package/test/seal.test.ts` (round trip, tamper → null, wrong aad → null, wrong key → null, size of a 900-byte payload under 1.4 KB)
- [X] T014 [P] Write `console/package/src/session/cookie-store.ts`: `SessionStore` interface from contracts/package-api.md and `SealedCookieSessionStore({ secret, secure, name? })` — cookie `__Host-ankka_console` when secure else `ankka_console`, `HttpOnly; SameSite=Lax; Path=/`, `Max-Age` = realm SSO max (36000), value `{v:1, rt, iat}`, `iat` older than `Max-Age` → absent; `PendingLoginStore` on the same seal with cookie `__Host-ankka_console_login`, 600 s; unit test `console/package/test/cookie-store.test.ts`
- [X] T015 [P] Write `console/package/src/session/token-cache.ts`: LRU `Map<digest, AccessTokenEntry>` capped at 10,000, `sha256` keys, 30 s skew on `expiresAt`; unit test `console/package/test/token-cache.test.ts` (eviction order, expiry)
- [X] T016 Write `console/package/src/auth/oidc.ts`: `configureIssuer({ issuer, audience, clientId, clientSecret, backchannelUrl?, ca?, allowInsecure })` — `discovery()` against `backchannelUrl ?? issuer` with `customFetch` using an `https.Agent` trusting `ca`, rewrite `token_endpoint`, `jwks_uri`, `revocation_endpoint`, `end_session_endpoint` origins to the backchannel, keep `issuer` and `authorization_endpoint`, `ClientSecretBasic`; `beginSignIn(config, returnTo)` (PKCE S256, state, nonce, `scope=openid`, `redirect_uri` = publicOrigin + mount + `/auth/callback`), `finishSignIn(config, url, pending)` (`authorizationCodeGrant` with all four checks), `refresh(config, rt)`, `revoke(config, rt)`, `endSessionUrl(config, idToken, postLogout)`; unit test against the fake issuer in `console/package/test/oidc.test.ts` including the backchannel rewrite and a mismatched `iss`
- [X] T017 Write `console/package/src/session/token-source.ts`: `TokenSource` interface and `SessionTokenSource({ store, cache, oidc })` — read session, cache hit → token; miss/expired → `refresh`, cache under old and new digests, write the new refresh token back through `store.write` onto the response headers; `invalid_grant` → `store.clear` and `null`; issuer unreachable → throw `IdentityProviderUnavailable`; unit test `console/package/test/token-source.test.ts` (miss refreshes once, concurrent old-cookie request still works, `invalid_grant` clears, unreachable keeps the cookie)

### Middleware, context, server, UI base

- [X] T018 Write `console/package/src/middleware.ts`: `consoleMiddleware(options: ConsoleOptions)` — resolve the mount from the matched route base; on non-`GET` refuse `Sec-Fetch-Site: cross-site` or a foreign `Origin` with `403`; build `ConsoleContext { principal, client, mount, extensions, signInUrl }` with a `ControlPlaneClient` whose `token` comes from `options.tokens` (default `SessionTokenSource`), set on the `RouterContextProvider` under an exported `consoleContext` key; `Cache-Control: no-store` on every response; a request log line per R17 via `options.log`; unit test `console/package/test/middleware.test.ts` for the origin rules and no-store
- [X] T019 [P] Write `console/package/src/ui/console-context.tsx`: `useConsole()` (mount, principal, extensions) from loader-provided root data, `href(path)` joining the mount, `Link`/`Form` wrappers that use it; `console/package/src/ui/errors.tsx`: `Refusal` (renders a `ControlPlaneError`'s `error` verbatim), `LostAccess` (404 as "you no longer have access"), `ControlPlaneUnavailable` (503/504 with retry); `console/package/src/ui/status.tsx`: lifecycle word with an icon and text, never colour alone; `console/package/src/styles.css` with `ac-` classes and custom properties on `.ac-root`, `prefers-color-scheme`, focus-visible outlines, 16 px gutter at phone width
- [X] T020 [P] Write `console/package/src/server/create-console-server.ts` (export `ankka-console/server`): `createConsoleServer(env)` reading contracts/configuration.md's variables; read `GET /auth` from the control plane at startup (refuse to start naming the URL on failure); `node:https` when `ANKKA_CONSOLE_TLS_DIR` is set (`createSecureContext` from `tls.crt`/`tls.key`/`ca.crt`, `requestCert`, `rejectUnauthorized`, `setSecureContext` on mtime change polled every 30 s, per-request `403` unless the peer SAN has `URI:ankka://gateway`) else `node:http`; `createRequestListener` from `@react-router/node` over the host's build; probe listener on `ANKKA_CONSOLE_PROBE_PORT` answering `200` only when the context is loaded and the main port bound, else `503`; an `https.Agent` for the control plane rebuilt on rotation with `servername` from the URL; `SIGTERM` handling per R17 (stop accepting, `server-closing` on streams, `close`, `closeIdleConnections`, exit ≤ 10 s); unit test `console/package/test/server.test.ts` for the probe states and the SAN check with a self-signed pair generated by `node:crypto`
- [X] T021 Write the host skeleton: `console/host/app/root.tsx` (`export const middleware = [consoleMiddleware(options from env)]`, `<Links/> <Meta/> <Outlet/> <Scripts/>`, `styles.css` import), `console/host/app/layout.tsx` (header with the product name, the signed-in person's name from `useConsole()`, a sign-out `Form method="post"`, `<Outlet/>`), `console/host/app/routes.ts` (`layout("./layout.tsx", [...consoleRoutes()])`, mounted at `/`), `console/host/app/entry.server.tsx` (default from the template), `console/host/server.ts` (`createConsoleServer(process.env)`), `console/host/.env.development` with contracts/configuration.md's local values
- [X] T022 [P] Write `console/package/src/testing/scenarios.ts`: the map of spec scenario ids (`US1-1`…`US1-8`, `US2-1`…`US2-8`, `US3-1`…`US3-8` and `US3-4a`, `US4-1`…`US4-8`, `US7-1`…`US7-4`) to Playwright test titles, exported from `ankka-console/testing`; and `console/e2e/tests/scenarios.spec.ts` that fails when a listed scenario has no test in the suite's test list (SC-011)
- [X] T023 Write `console/e2e/fixtures.ts`: a Playwright fixture that, for the `fake` target, starts `fakeIssuer()`, `fakeControlPlane()` seeded with two organizations, projects and services, and the built host on ephemeral ports with the env from contracts/configuration.md, exposes `signIn(page, user)` (fills the fake or Keycloak form), `cli`-equivalent helpers reading the fake's state; for `compose`, points at `localhost:3000`, `localhost:9000`, `localhost:8081` and `dev`/`dev`; and an `axe(page)` helper failing on any WCAG 2.1 AA violation, called by every test after each page load

**Checkpoint**: `npm test` passes in `console/`; the built host starts against the fakes and serves the layout at `/` with a sign-in redirect.

---

## Phase 3: User Story 1 — Sign in through the identity provider (Priority: P1) 🎯 MVP

**Goal**: Sign in, a session no script can read, invisible renewal, sign-out that ends the Keycloak session, expiry that returns to the page asked for.

**Independent Test**: Playwright scenarios US1-1…US1-8 against the fake target; the same against compose; inspection of cookies, storage and bodies finds no token (SC-003).

- [X] T024 [P] [US1] Write `console/e2e/tests/sign-in.spec.ts` covering US1-1 (deep link → sign-in → back on the deep link), US1-2 (no token in any cookie, `localStorage`, `sessionStorage`, or any response body captured via `page.on("response")`; session cookie `HttpOnly`, `SameSite=Lax`, `Secure` when the target is HTTPS, under 2 KB), US1-3 (sign out → issuer session gone → next visit asks to sign in), US1-4 (`expireSession` → next load redirects to sign-in and back), US1-5 (callback with a foreign `state` → 400, no cookie), US1-6 (`returnTo=https://evil.example` → front page), US1-7 (two host instances behind a round-robin proxy in the fixture; restart one; session continues), US1-8 (`failNext()` on the issuer during a refresh → "cannot be reached" page, cookie still present)
- [X] T025 [US1] Write `console/package/src/routes/auth.sign-in.ts`: loader — sanitise `returnTo` to a path under the mount (else `/`), `beginSignIn`, write the pending cookie, `302` to the authorization URL
- [X] T026 [US1] Write `console/package/src/routes/auth.callback.ts`: loader — read and clear the pending cookie (absent or stale → `400` page), `finishSignIn`, write the session (refresh token only), cache the access token, `303` to `returnTo`
- [X] T027 [US1] Write `console/package/src/routes/auth.sign-out.ts`: action (`POST` only) — read the session, `revoke` the refresh token, clear the cookie, `303` to `endSessionUrl` with `post_logout_redirect_uri` = the mount; a `GET` answers `405`
- [X] T028 [US1] Make the middleware's unauthenticated path concrete in `console/package/src/middleware.ts`: a `null` token on a page request → `302` to `signInUrl(currentPath)`; on a `POST` → `303` likewise; `IdentityProviderUnavailable` → render `ui/errors.tsx`'s unavailable page with `Retry-After: 5`, cookie untouched
- [X] T029 [US1] Write `console/package/src/routes/front.tsx` minimal for this story: loader reads `GET /auth/whoami`, renders the person's name, email and subject; the layout's sign-out form; this is the page the deep-link test lands on
- [X] T030 [US1] Add the two-instance fixture for US1-7 to `console/e2e/fixtures.ts`: start the host twice and a tiny round-robin `node:http` proxy in front, expose `restartInstance(i)`
- [X] T031 [US1] Run `npm run e2e -- sign-in` (`console/e2e/tests/sign-in.spec.ts`) on the fake target until green in both projects; then, with the compose stack (quickstart §1), `CONSOLE_E2E_TARGET=compose npm run e2e -- sign-in`; fix what differs in `console/package/src/auth/oidc.ts` and `console/e2e/fixtures.ts` (the real Keycloak's form field names, the `iss` port) rather than skipping

**Checkpoint**: Story 1 passes on both targets; the cookie is under 2 KB; no token anywhere in the browser.

---

## Phase 4: User Story 2 — Organizations and projects (Priority: P1)

**Goal**: The front page lists what the person sees; create, rename and delete organizations and projects with every refusal shown verbatim.

**Independent Test**: Playwright US2-1…US2-8 against the fake (roles seeded) and against compose with the CLI reading back.

- [X] T032 [P] [US2] Write `console/e2e/tests/organizations.spec.ts` for US2-1…US2-6 and US2-8 (the fake's `organizationPolicy = "platform-admin"` with a sign-up URL) and `console/e2e/tests/projects.spec.ts` for US2-7; each create asserts the landing page is the entity read by id (FR-021) even when the fake's listing is scripted to lag one poll
- [X] T033 [US2] Extend `console/package/src/routes/front.tsx`: loader reads `whoami` and `GET /organizations` (an administrator sees every organization, each marked "seen as administrator" when `role` is absent); render name, id, role; "Create an organization" link unless hidden by the host; empty state text when none
- [X] T034 [P] [US2] Write `console/package/src/routes/organization-new.tsx`: form (id, name), action `POST /organizations/{id}` then `303` to the organization page; on `409`/`403` re-render the form with values kept and the refusal verbatim, including the sign-up URL when the body carries one
- [X] T035 [P] [US2] Write `console/package/src/routes/organization.tsx`: loader reads `GET /organizations/{id}` and `GET /projects` filtered by `organizationId`, plus the host's organization panels' `load` under `allSettled`; renders detail (name, id, disabled, quota and usage), the projects table with links, rename form (owners), delete form (owners, disabled when projects > 0 with the reason shown after a refused attempt), "Create a project" link; `404` → `LostAccess`; actions dispatched by an `intent` field: `rename`, `delete` (→ front page)
- [X] T036 [P] [US2] Write `console/package/src/routes/project-new.tsx` (form: id, name; `POST /projects/{id}` with `organizationId`; `303` to the project page) and `console/package/src/routes/project.tsx` first cut: loader reads `GET /projects/{id}` and `GET /services/{id}`; renders detail, rename and delete forms with refusals, the services table (name, lifecycle, ready/desired, image, generation, hostname link), "Apply a descriptor" link; `delete` → `303` to the organization page
- [X] T037 [P] [US2] Add the registry credential to `console/package/src/routes/project.tsx`: show `registry` (server, username, set at, set by) when present; a form (server, username, password) posting `PUT /projects/{id}/registry` and a clear form posting `DELETE …/registry`; the password field is never pre-filled and the value never appears in any response; refusals verbatim; add a scenario to `console/e2e/tests/projects.spec.ts` that sets a registry, asserts the summary shows server and username and no response body contains the password, then clears it
- [ ] T038 [US2] Run `npm run e2e -- organizations projects` (`console/e2e/tests/organizations.spec.ts`, `console/e2e/tests/projects.spec.ts`) on both targets; on compose, verify with `ankka organizations list` and `ankka projects list` that what the console shows is what the CLI shows; fix the pages under `console/package/src/routes/` until both agree

**Checkpoint**: Stories 1–2 pass on both targets.

---

## Phase 5: User Story 3 — Services (Priority: P1)

**Goal**: Service listing and detail, descriptor apply with problems shown, the six operations, history, logs, and live updates over streams (clarifications 2 and 3).

**Independent Test**: Playwright US3-1…US3-8 and US3-4a against the fake (with `tick()` driving lifecycle) and compose (SC-012 timings on compose).

- [ ] T039 [P] [US3] Write `console/e2e/tests/services.spec.ts` for US3-1…US3-8 (including an invalid descriptor whose `400` body lists two problems both shown beside the textarea; a `Suspended` service whose operations are shown and refused) and `console/e2e/tests/streams.spec.ts` for US3-4a (scripts-on only: open the service page, `tick()` the fake, assert the lifecycle word changes without navigation within 5 s; the listing likewise; logs: append a line in the fake, assert it appears within 5 s; pause following stops updates; on compose, the same against a real apply and a real log line)
- [ ] T040 [P] [US3] Write `console/package/src/stream/sse.ts`: a `ReadableStream`-backed SSE writer (`event`, JSON `data`, `: keepalive` every 15 s, `close(reason)`), registered in a module-level set the server's shutdown drains with `server-closing`
- [ ] T041 [P] [US3] Write `console/package/src/stream/log-follow.ts`: `LogFollower(tail)` keeping sent lines per instance, `next(window: string[]) → string[]` returning the suffix after the longest overlap (R10); unit test `console/package/test/log-follow.test.ts` (no overlap, full overlap, partial, repeated identical line inside one window documented as dropped)
- [ ] T042 [US3] Write `console/package/src/stream/service-stream.ts` and the resource routes `console/package/src/routes/stream.service.ts` and `console/package/src/routes/stream.project.ts` per contracts/stream.md: every 2 s read status (and logs with `since=4`, the query's `instance`, `previous`, `tail`) through the request's token source; send `status`/`services` on structural change, `logs` via the follower, `session-ended` on a `null` token, `gone` on `404`; abort on client disconnect; one read per stream per interval
- [ ] T043 [US3] Write `console/package/src/ui/use-stream.ts`: `useServiceStream(projectId, name, { logs? })` and `useProjectStream(projectId)` — `EventSource` opened in an effect (never during render, so scripts-off pages open none), `status`/`services` replace loader data through `useState`, `logs` append and trim, `session-ended` navigates to `signInUrl(current)`, `gone` shows `LostAccess`, `server-closing` lets `EventSource` reconnect
- [ ] T044 [US3] Write `console/package/src/routes/service.tsx`: loader reads `GET /services/{p}/{n}` and `/history` and the service panels; renders every `ServiceStatus` field in words (`confirmed: false` as "last known, not confirmed"), the hostname as a link when exposed, history rows (kind, generation, actor display, administrative flag, time); operation forms `pause`, `resume`, `restart`, `expose`, `unexpose`, `delete` (each a `POST` with `intent`, refusals verbatim beside the button, host actions rendered beside each named operation, hidden operations omitted); after an operation the action re-reads the service and re-renders the state read back (FR-023); `useServiceStream` for live status
- [ ] T045 [P] [US3] Write `console/package/src/routes/service-apply.tsx`: form with name and a textarea (plus a file input that fills the textarea client-side and is a plain multipart upload with scripts off), action parses the text as JSON only to give an early "not JSON" message, forwards the text to `PUT /services/{p}/{name}`, `400`'s problems rendered as a list beside the textarea with the text kept, success → `303` to the service page
- [ ] T046 [P] [US3] Write `console/package/src/routes/logs.tsx`: form for instance (from the current status's instances list — from the logs response's `instances`), `previous`, `tail`, `since`; loader reads `GET …/logs` with those; renders per-instance output in `<pre>` with an instance's `error` inline; a "Follow" toggle (scripts on) opening `useServiceStream(..., { logs })` and a "Following — pause" control; scripts off: the bounded fetch and a refresh button
- [ ] T047 [US3] Finish `console/package/src/routes/project.tsx`: `useProjectStream` for the services table; a `Suspended` service reads `Suspended` from `suspended: true` and the table shows `paused` too
- [ ] T048 [US3] Run `npm run e2e -- services streams` on both targets; on compose, apply `samples/shopping-cart/service.json` and confirm with `ankka services get`; record SC-012 timings from the test output in the PR description

**Checkpoint**: Stories 1–3 pass on both targets. A developer can go from sign-in to a deployed service without the CLI.

---

## Phase 6: User Story 4 — Members, tokens and administrator operations (Priority: P2)

**Goal**: Members and invitations, roles, deploy tokens shown once, disable/enable, quota, repair.

**Independent Test**: Playwright US4-1…US4-8 against the fake (users `owner`, `member`, `dev`) and compose (with a second Keycloak user created through `kcadm` in the compose init, or the invited email being `dev`'s).

- [ ] T049 [P] [US4] Write `console/e2e/tests/members.spec.ts` (US4-1…US4-4; the last-owner refusal; a `token:<id>` member shown as a machine) and `console/e2e/tests/tokens.spec.ts` (US4-5: the secret is on the page after creation and absent after a reload, and in the `scripts-off` project the reload sends a `GET`, not a re-submitted `POST` — the fake's token count stays at one; US4-6: the revoked token is refused by the fake) and `console/e2e/tests/admin.spec.ts` (US4-7, US4-8 as `dev`)
- [ ] T050 [P] [US4] Write `console/package/src/routes/members.tsx`: loader reads `GET /organizations/{id}/members`; tables of members (subject, display, email, role, since, added by; `token:` subjects marked "machine") and invitations (email, role, invited at/by); owner-only forms: invite (email, role), change role, remove, withdraw; every refusal verbatim, the last-owner one beside the row; a non-owner sees the tables and no controls
- [ ] T051 [P] [US4] Write `console/package/src/routes/tokens.tsx`: loader lists tokens (id, label, subject, created, expires or "never", last used); owner form: label, optional `expiresIn`; the action is post-redirect-get: it seals the `DeployTokenCreated` into a one-time flash cookie (`__Host-ankka_console_flash`, 60 s, the seal of T013) and `303`s to the tokens page; the loader reads and clears that cookie and renders the secret once in a copyable block with the once-only warning; a reload is a plain `GET` with no flash cookie and shows the listing without the secret, with scripts on or off; revoke forms
- [ ] T052 [US4] Add the administrator's controls to `console/package/src/routes/organization.tsx`: when `principal.platformAdmin`, forms for disable/enable, quota set (three optional integers) and clear, and repair (subject, role) shown when the members listing has no owner; actions `POST …/disable|enable`, `PUT|DELETE …/quota`, `POST …/members/{subject}/repair`
- [ ] T053 [US4] Run `npm run e2e -- members tokens admin` (`console/e2e/tests/members.spec.ts`, `tokens.spec.ts`, `admin.spec.ts`) on both targets; on compose, use a created token with `ankka services list` and confirm revocation makes the CLI fail; fix `console/package/src/routes/members.tsx`, `tokens.tsx` and `organization.tsx` as needed

- [ ] T054 [US4] Write `console/e2e/tests/parity.spec.ts` (SC-002): read the route table from the generated block in `docs/reference/control-plane-api.md`, drop `GET /auth`, and after the whole suite has run (a Playwright `globalTeardown` reading the fake's `visited` set written to `console/e2e/test-results/visited.json`) assert every `METHOD /route` template was exercised, naming the ones that were not

**Checkpoint**: Stories 1–4 pass on both targets; every row of contracts/control-plane-routes.md has been visited by the fake.

---

## Phase 7: User Story 5 — A platform workload, deployed with the platform (Priority: P2)

**Goal**: The image, the component, the overlays, the realm client, the deploy script, the release, CI, and the k3s proof.

**Independent Test**: `deploy-local.sh` prints the console's address and it serves the sign-in; `RemoteOverlaySuite` and the new `EndToEndClusterSuite` case pass; a pod in another namespace is refused.

- [ ] T055 [P] [US5] Write `console/Dockerfile`: stage 1 `node:24-bookworm-slim` `npm ci --workspaces` and `npm run build -w host`; stage 2 the same base, `useradd -u 1000`, copy `console/host/build`, `console/host/server.js` (compiled), production `node_modules` (`npm ci --omit=dev -w host`), `USER 1000`, `EXPOSE 9000 7627`, `CMD ["node", "server.js"]`, `org.opencontainers.image.source` label; a `.dockerignore`
- [ ] T056 [P] [US5] Write `kustomization/components/console/` per contracts/configuration.md: `kustomization.yaml` (Component), `namespace.yaml`, `serviceaccount.yaml`, `secrets.yaml` (dev values, with a comment naming the cloud overlay's deletion), `deployment.yaml` (2 replicas, `RollingUpdate` 1/0, `automountServiceAccountToken: false`, `IfNotPresent`, `preStop.sleep: 5s`, `terminationGracePeriodSeconds: 30`, ports `http` 9000 and `probe` 7627, readiness `httpGet /ready` on `probe`, `securityContext` `runAsNonRoot`/`fsGroup: 1000`, certificate volume `defaultMode: 0440`, env from the table with `ANKKA_CONSOLE_AUTHORITY: CONSOLE_AUTHORITY` placeholder, pod label `app.kubernetes.io/managed-by: ankka`), `service.yaml`, `httproute.yaml` (`console.BASE_DOMAIN`), `zero-trust.yaml` (Certificate `ankka://platform/console`, NetworkPolicy, BackendTLSPolicy)
- [ ] T057 [P] [US5] Add the `ankka-console` client to `kustomization/components/keycloak/realm-import.json` exactly as contracts/configuration.md gives it (`secret: dev`, `redirectUris` with `https://CONSOLE_AUTHORITY/*` and `http://localhost:3000/*`, `post.logout.redirect.uris: "+"`, PKCE S256, default scope `ankka-controlplane`); confirm `docker compose up -d` imports it and `http://localhost:3000` signs in (quickstart §1)
- [ ] T058 [US5] Wire both overlays: add `consoleAuthority` to `kustomization/overlays/local/platform-configmap.yaml` (`console.127.0.0.1.sslip.io:8443`) and `kustomization/overlays/cloud/platform-configmap.yaml` (`console.example.com # SET`); add `../../components/console` to both `kustomization.yaml` component lists; add replacements: `baseDomain` → `HTTPRoute/ankka-console spec.hostnames.0` (`.`, 1); `consoleAuthority` → `Deployment/ankka-console` env `ANKKA_CONSOLE_AUTHORITY`, `KeycloakRealmImport/ankka-realm spec.realm.clients.[clientId=ankka-console].redirectUris.0` (`/`, 2); cloud only: `$patch: delete` on `Secret/ankka-console-secrets`, a patch on the realm import setting the client's `secret` to `SET` and `redirectUris` to `["https://CONSOLE_AUTHORITY/*"]`, and `images:` entry `ankka-console` → `ghcr.io/thinkmorestupidless/ankka-console` `0.0.0 # SET`; render both with `kubectl kustomize` and inspect
- [ ] T059 [US5] Extend `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/RemoteOverlaySuite.scala`: `consoleAuthority` equals `console.<baseDomain>` plus `:<httpsPort>` unless 443, in both renders; the realm client's first redirect URI is `https://<consoleAuthority>/*` in both and `localhost` survives only locally; `ankka-console-secrets` is absent from the cloud render; the console image is renamed to the registry; the console Deployment carries `preStop`, `IfNotPresent`, a `probe` port and no `management` port
- [ ] T060 [US5] Extend `kustomization/deploy-local.sh`: after the sbt images, `docker build -t ankka-console:latest console`; `kind load docker-image ankka-console:latest`; after the control plane's rollout wait, `kubectl -n ankka-console rollout status deployment/ankka-console --timeout=300s`; in the closing message print `Console: https://console.${BASE_DOMAIN}:${HTTPS_HOST_PORT}` and that the browser must trust `~/.ankka/local-ca.crt`; and add the console to the `rollout restart` list so a re-run rolls it onto the new image
- [ ] T061 [US5] Deploy on kind (quickstart §5): fix whatever the real cluster refuses — the certificate mount permissions, the SAN check against cert-manager's certificate (plan risk 2), discovery through the backchannel (risk 3), SSE through Envoy (risk 4: open a service page during a rollout and watch it update) — and record each finding under *Traps* in `CLAUDE.md` if it cost time
- [ ] T062 [US5] Add the console case to `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/EndToEndClusterSuite.scala`: build the console image in `sampleImageForClusterTests`' manner (a `consoleImageForClusterTests` task in `build.sbt` running `docker build` and importing into the k3s node, gated on `-Dankka.cluster.tests`), apply `components/console` with the suite's base domain and `consoleAuthority`, wait for the rollout; then with `curl` subprocesses (cookie jar, `--cacert`, `--resolve console.test.local:<port>:127.0.0.1` and `auth.test.local`): `GET /` → 302 to Keycloak; `GET` the login page and extract `action` (unescape `&amp;`); `POST` `dev`/`dev`; follow to `/auth/callback` and to `/`; assert the organizations page contains the seeded organization; `POST /organizations/new` with the form fields and assert the redirect lands on the organization; `GET` the project's page and assert the service listing; from a pod in another namespace, `curl --max-time 3 https://ankka-console.ankka-console.svc:9000/` is refused; a token minted with `kubectl create token ankka-console -n ankka-console` (the pod does not mount one) gets `403` from the API server for `get pods`, as `OperatorClusterSuite` proves for the operator's account; and SC-006: with the signed-in cookie jar, run a `curl` loop at one request a second against the organizations page while `kubectl -n ankka-console rollout restart deployment/ankka-console` completes, and assert every response was `200`
- [ ] T063 [P] [US5] Add the `console` job to `.github/workflows/ci.yml`: a `changes` filter `console` (`console/**`, `kustomization/components/console/**`, `kustomization/components/keycloak/realm-import.json`, `controlplane-api/**`, `docs/reference/control-plane-api.md`); steps on Node 24: `npm ci`, `npm run typecheck`, `npm test`, `npx playwright install --with-deps chromium`, `npm run e2e` (fake target), `npm run build`, `docker build console`; upload `console/e2e/report` on failure; add `console/package/fixtures/**` to the `scala` filter and `console/**` to the `docs` filter
- [ ] T064 [P] [US5] Extend `.github/workflows/release.yml`'s `images` job: `ankka-console` in `IMAGES`; a step `docker build -t ghcr.io/thinkmorestupidless/ankka-console:$VERSION -t …:latest console && docker push --all-tags` after the sbt publish, so the public-pullable check and the cache warm cover it
- [ ] T065 [US5] Update `docs/platform/install-cloud.md`'s placeholder list and `kustomization/overlays/cloud/kustomization.yaml`'s header comment to name the console's image, `consoleAuthority`, the deleted Secret and the realm client's `SET` secret; run `sbt 'controlPlane/testOnly *RemoteOverlaySuite'` and `sbt 'controlPlane/testOnly *EndToEndClusterSuite'` until green

**Checkpoint**: The console runs on kind and in the k3s suite; CI builds it on its own changes; the release would publish its image.

---

## Phase 8: User Story 6 — The console is a package a second host builds on (Priority: P2)

**Goal**: The extension points, the session store and token source seams proven by a second host, the wire fixtures the platform emits, and publication.

**Independent Test**: `console/package/test/fixture-host/` mounts the package under `/x` in its own layout with a memory session store, a panel and an action; every operation in stories 2–4 works there; `ControlPlaneFixturesSuite` and the TS fixtures test pass.

- [ ] T066 [P] [US6] Write `console/package/src/extensions/types.ts` (`ConsoleExtensions`, `Panel`, `Action`, `Operation` union of the 27 names) and `console/package/src/extensions/panels.tsx` (`Panels({ kind, entity, data })` rendering each registered panel inside an error boundary with the panel's title and "could not load: <message>" on rejection; `Actions({ operation, entity })`; `hidden.has(op)` helper); wire `load` into the organization, project and service loaders under `Promise.allSettled` and `Actions` beside every operation control
- [ ] T067 [P] [US6] Write the fixture host in `console/package/test/fixture-host/`: `app/routes.ts` with `layout("./layout.tsx", [...prefix("x", consoleRoutes())])` plus a host route `app/extra.tsx` at `/x/billing`; `app/layout.tsx` with a different chrome; a `MemorySessionStore` implementing `SessionStore` over a `Map` keyed by a random cookie id; extensions: an organization panel with `load` reading from the fake control plane, an action beside `organization.create` linking to `/x/billing`, `hidden: ["organization.delete"]`; a `TokenSource` of the host's own for one test; `console/package/test/fixture-host.test.ts` builds it, starts it against the fakes, and drives every operation of stories 2–4 through `fetch` with a cookie jar (no browser): links stay under `/x`, the panel renders and its failure is contained when the fake is scripted to 503, the hidden delete control is absent and a direct `POST` still reaches the control plane's refusal
- [ ] T068 [P] [US6] Write `controlplane-api/src/test/scala/com/thinkmorestupidless/ankka/controlplane/api/ControlPlaneFixturesSuite.scala` per contracts/fixtures.md: for every `given` in `Wire` plus the error body, a full sample and a minimal sample written to `console/package/fixtures/control-plane/<Type>.json` and `<Type>.minimal.json`; fail on drift with the type named; regenerate under `-Dankka.docs.update=true`; fail when a `Wire` codec has no sample: the suite holds a `Tuple` of every wire type, `summonAll` proves each has a `JsonValueCodec` in `Wire` at compile time, and a test asserts the tuple's size equals the count of `given` members in `Wire.scala` read from source (so a new `given` without a tuple entry fails with its name); run it once with the switch to commit the fixtures
- [ ] T069 [US6] Write `console/package/test/fixtures.test.ts`: for each fixture file, decode with the schema named by `type`, assert the full sample round-trips to the same canonical JSON and the minimal one decodes with every optional absent; and wire `console/package/src/client/schemas.ts` until it does
- [ ] T070 [P] [US6] Make the package publishable: `console/package/README.md` (mount, supply, extend, version rule, pointing at the docs page), `prepack` building `dist/`, `npm pack` into an empty directory and import `ankka-console`, `ankka-console/client`, `ankka-console/server`, `ankka-console/testing` in `console/package/test/pack.test.ts` (skipped unless `CONSOLE_PACK=1`, run in CI's console job)
- [ ] T071 [P] [US6] Add a `console-package` job to `.github/workflows/release.yml` modelled on `sdk-typescript`: `npm version $VERSION --no-git-tag-version -w package`, build, pack, install into an empty directory and import the entry points, `npm view ankka-console@$VERSION` guard, `npm publish ./console/package/dist-pack/ankka-console-$VERSION.tgz --access public --provenance` under trusted publishing on Node 24, environment `npm`; document the one-time first publish by hand in `CLAUDE.md` under *Publishing* beside the SDK's
- [ ] T072 [US6] Ship the styles as overridable: every colour, font and spacing in `console/package/src/styles.css` as a custom property on `.ac-root`; the fixture host's layout overrides two of them and `fixture-host.test.ts` asserts the rendered HTML links the package stylesheet and the host's after it

- [ ] T073 [P] [US6] Write `console/package/test/host-size.test.ts` (SC-009): count non-blank, non-comment lines under `console/host/app/` and `console/host/server.ts` (excluding `.css` and generated `+types`), fail above 500, and print the count so the number lands in the PR

**Checkpoint**: The fixture host passes every operation with zero package changes; the host is under 500 lines; fixtures hold both sides.

---

## Phase 9: User Story 7 — Fast, and working with scripts off (Priority: P3)

**Goal**: Server-rendered first paint, no document reloads with scripts, every operation with scripts off, no double submission, the timing budgets, keyboard completion, zero axe violations.

**Independent Test**: The `scripts-off` Playwright project passes stories 2–4; navigation timings on compose; axe on every page; keyboard-only runs.

- [ ] T074 [P] [US7] Write `console/e2e/tests/rendering.spec.ts`: US7-1 (`page.route` blocks every script and the organizations page still renders its table), US7-2 (scripts on: click a link, assert no `load` event and `performance.getEntriesByType("navigation").length` stays 1), US7-3 (the `scripts-off` project runs the organizations, projects, services, members and tokens specs — assert via `test.info().project.name` that they ran), US7-4 (double-click the pause button; the fake counted one `POST`)
- [ ] T075 [US7] Prevent double submission in `console/package/src/ui/console-context.tsx`'s `Form` wrapper: disable the submit control while `useNavigation().state !== "idle"` for that form's `intent`; with scripts off, the action is idempotent where the control plane is (pause twice is one pause) and the fake asserts the count for the operations that are not (create)
- [ ] T076 [US7] Add prefetching: `<Link prefetch="intent">` in the package's link wrapper in `console/package/src/ui/console-context.tsx` for links to organization, project and service pages
- [ ] T077 [P] [US7] Write `console/e2e/tests/a11y.spec.ts`: visit every route in contracts/package-api.md's table with seeded data and run `axe` (SC-013); and `console/e2e/tests/keyboard.spec.ts`: complete one instance of every operation in stories 2–4 with `keyboard.press` only (Tab, Enter, Space, typing), asserting focus is visible (`:focus-visible` computed outline) on each stop
- [ ] T078 [US7] Fix what T077 finds in `console/package/src/routes/*.tsx` and `console/package/src/styles.css`: labels on every control, `aria-describedby` for refusals, heading order, contrast, status words with text and icon
- [ ] T079 [US7] Write `console/e2e/tests/timing.spec.ts` (compose target only): 20 loads of the organizations page, median server `durationMs` from the host's request log under 500 ms; 20 client navigations organization → project, median under 300 ms via `performance.now()` around the click and the new heading's appearance (SC-004); record the numbers in the PR

**Checkpoint**: Both Playwright projects green on both targets; axe zero; keyboard suite green.

---

## Phase 10: User Story 8 — Documentation (Priority: P3)

**Goal**: Three new pages, five edited, nav and skills, `just docs` green.

**Independent Test**: `just docs` passes; the quoted limitation is gone; the pages are in nav and skills.

- [ ] T080 [P] [US8] Write `docs/operate/console.md` (kind `guide`): what the console is, signing in and out (the console's word is "sign in", matching Keycloak's page; the CLI's command stays `ankka login`, and the page says so once), the front page, organizations and projects, services (status words, apply, operations, history, logs and following, what "last known" means), members and tokens (the secret is shown once), the administrator's controls, what it does not show (traces and sessions: the local console), and how it relates to the CLI; frontmatter `related` to `operate/local-console.md`, `platform/organizations.md`, `reference/control-plane-api.md`
- [ ] T081 [P] [US8] Write `docs/platform/console.md` (kind `guide`): the component and its hostname `console.<base domain>`, `consoleAuthority`, the two Secrets and how to create them out of band, the realm client, adding the client to an installation whose realm was imported earlier (the `kcadm.sh create clients` command with every attribute), removing the console (delete the component from the overlay), local development against compose, what it needs from the cluster (network policy enforcement, cert-manager), and the request log
- [ ] T082 [P] [US8] Write `docs/reference/console-package.md` (kind `reference`): mounting (`docs:start mount` region in `console/package/test/fixture-host/app/routes.ts`), what a host supplies (`ConsoleOptions` table), `SessionStore` and `TokenSource` with the fixture host's `MemorySessionStore` as an included sample, the extension points with the fixture host's panel and action as samples, the operations list, the route table, the streams, versioning; run `just docs-sync`
- [ ] T083 [US8] Edit `docs/reference/limitations.md`: remove "The console is local only… the CLI is the only client for anything in a cluster" and add "The console shows the control plane's records; traces, sessions and entity state of a deployed service are not shown — see the local console"; edit `docs/concepts/observability.md`'s table row and the "No console for a deployed installation" bullet accordingly
- [ ] T084 [P] [US8] Edit `docs/platform/identity.md`: list the `ankka-console` client beside `ankka-cli` under *The realm* with what it is; note that the console reads the issuer from `GET /auth`; edit `docs/platform/install-local.md` to mention the printed console address and the browser trust step; `docs/platform/install-cloud.md` per T065
- [ ] T085 [US8] Add the three pages to `mkdocs.yml` `nav` (Observe and operate → The console; Run the platform → The console; Reference → Console package) and to `tools/docs/skill/ankka-platform/SKILL.md` (`platform/console.md`, `reference/console-package.md`) and `tools/docs/skill/ankka-deploy/SKILL.md` (`operate/console.md`); `just docs-sync`; `just docs` until green; commit the rendered skills under `marketplace/` and `ankka.g8/`

**Checkpoint**: `just docs` green; the limitation sentence is gone.

---

## Phase 11: Polish and cross-cutting

- [ ] T086 [P] Add `ankka-console` to the local development section of `docker-compose.yml`'s header comment and to `CLAUDE.md` *Commands* (`cd console && npm run dev`) and *Architecture* (a short "The console" paragraph: package and host, sealed cookie, streams by polling, `ankka://platform/console`)
- [ ] T087 [P] Add the traps found during T061 and T062 to `CLAUDE.md` *Traps* (client-certificate SAN inspection in Node, Keycloak discovery through the backchannel, SSE through Envoy, the realm's redirect replacement) — only the ones that cost time
- [ ] T088 Run the whole thing once as CI would: `just test-console`, `sbt -Dankka.cluster.tests=off test`, `just docs`; then `caffeinate -i sbt 'controlPlane/testOnly *EndToEndClusterSuite'`; fix anything red
- [ ] T089 Review the diff for tokens or cookies in any log line, any `console.log` left in the package, any absolute link in a page, and any `localhost` surviving in the cloud render; run `git grep -n "localhost" kustomization/overlays/cloud` expecting nothing
- [ ] T090 Write the pull request description as `specs/017-control-plane-console/pr.md` (used verbatim by `gh pr create --body-file`): the architecture in five bullets, the SC-004 and SC-012 numbers from T048 and T079, the first-publish-by-hand step for the package, and the `kcadm` step for existing installations

---

## Dependencies and execution order

- **Phase 1 → Phase 2 → Phase 3**: strictly sequential; T007's spike decides the shape of every route file.
- **Phases 3, 4, 5** (US1–US3) are sequential in value but the *page* tasks of US2 and US3 (T034–T037, T044–T046) can start once T018–T021 exist; their e2e runs need US1's sign-in.
- **US4** (Phase 6) needs US2's organization page (T035).
- **US5** (Phase 7) needs a buildable host (T021) and can run in parallel with Phases 4–6 by a second person; T061/T062 need US1–US3 for the assertions they make.
- **US6** (Phase 8) needs the pages (US2–US4) to have the loaders the panels hook into; T068 (the Scala fixtures suite) can start any time after T008.
- **US7** (Phase 9) needs every page; **US8** (Phase 10) needs the behaviour settled.
- **Polish** last.

```
Setup ─► Foundational ─► US1 ─► US2 ─► US3 ─► US4 ─► US6 ─► US7 ─► US8 ─► Polish
                                  └──────────► US5 (parallel from T021; proofs after US3)
                        T068 (fixtures suite) can run any time after T008
```

## Parallel execution examples

- **Foundational**: T008, T010, T011, T013, T014, T015 together (six files, no shared state); then T009 and T016; then T017; T019, T020, T022 together while T018 is written.
- **US3**: T039 (tests), T040, T041, T045, T046 together; T042 after T040/T041; T043 after T042; T044 after T043.
- **US5**: T055, T056, T057, T063, T064 together; T058 after T056/T057; T059 after T058; T060 after T055; T061 after T060; T062 after T061.
- **US6**: T066, T067, T068, T070, T071 together; T069 after T068.
- **US8**: T080, T081, T082, T084 together; T083 then T085.

## Implementation strategy

1. **MVP = Phases 1–3**: a person signs in through Keycloak and sees who they are, with no token in
   the browser, proven on the fake and on compose. That alone retires the riskiest design (sessions)
   and the spike.
2. **Phases 4–5** make it a console: organizations, projects, services with live updates. Stop and
   demo here; the CLI parity test tells you what is left.
3. **Phase 7** early if a second person is available: the deployment path has its own four risks
   and none depends on the pages being finished.
4. **Phases 6, 8** turn it into the package ankka-cloud can take; **Phase 9–10** finish the P3
   stories; Polish closes.

Each phase ends at a checkpoint that is a runnable proof from `quickstart.md`.
