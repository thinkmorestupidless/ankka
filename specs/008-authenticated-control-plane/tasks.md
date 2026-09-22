# Tasks: An Authenticated, Multi-User Control Plane

**Input**: Design documents from `/specs/008-authenticated-control-plane/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included. This repository's suites are the proof for every previous feature, the spec's
success criteria name what must be proven against a real cluster, and research R12 names the
suites.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (lock the door), US2 (tenancy holds), US3 (administer), US4 (machines),
  US5 (audit trail)

Paths are repository-relative. `CP` below abbreviates
`controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane`, `CPT` its test twin
`controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane`, `CLI`
`cli/src/main/scala/com/thinkmorestupidless/ankka/cli`, `CLIT` its test twin, `HTTP`
`modules/http/src/main/scala/com/thinkmorestupidless/ankka/http`, and `API`
`controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api`.

---

## Phase 1: Setup — verify first (research "verify at implementation" 1–5)

**Purpose**: settle the assumptions the design rests on before building on them. Each spike's
*answer* is the deliverable; the code is throwaway unless it becomes the suite named.

- [X] T001 Write `kustomization/components/keycloak/realm.json` per [contracts/keycloak-component.md](./contracts/keycloak-component.md): realm `ankka`, `registrationAllowed: false`, lifetimes, realm role `platform-admin`, client scope `ankka-controlplane` (audience, email, email-verified, realm-roles mappers), public client `ankka-cli` with `oauth2.device.authorization.grant.enabled: "true"` and default scope `ankka-controlplane`, optional `offline_access`, **no users**.
- [X] T002 Spike the realm on a real image: create `CPT/KeycloakRealmSuite.scala` starting `quay.io/keycloak/keycloak:26.7.4 start-dev --import-realm` via testcontainers `GenericContainer` with `realm.json` mounted, create a test user and a confidential test client through the admin REST API with the bootstrap admin, and assert a device-code request is accepted, a password-grant token for the test client carries `aud: ["ankka-controlplane"]`, `email_verified`, `realm_access.roles` and `typ: Bearer`, and `offline_access` yields `refresh_expires_in: 0`. Record the answers in [research.md](./research.md) verify items 3 and 4. Gate the suite on Docker like the Postgres suites.
- [X] T003 In the same suite, set a verified email on the test client's service-account user and assert its client-credentials token carries `email` and `email_verified: true` — research verify item 5. If false, `members add --subject` must be added to the spec before US4.
- [X] T004 Spike the operator on k3s in a throwaway test: install `github.com/keycloak/keycloak-k8s-resources/kubernetes?ref=26.7.4` into `ankka-auth` with the node's `kubectl`, apply a CNPG `Cluster` and the `Keycloak` CR from [contracts/keycloak-component.md](./contracts/keycloak-component.md) with `httpEnabled: true`, `hostname.strict: false`, no `tlsSecret`, and proxy headers via `spec.proxy.headers`; record whether the CRD accepts that field (else `additionalOptions`) and whether the instance reaches `Ready` without TLS — research verify items 1 and 2. Keep the working manifests for T041.
- [X] T005 [P] Add `nimbusJoseJwt = "com.nimbusds" % "nimbus-jose-jwt" % "10.9.1"` to `project/Dependencies.scala` and to `controlPlane`'s `libraryDependencies` in `build.sbt` **only**; add a `keycloakVersion` constant in `build.sbt` exported through the existing `BuildInfo` so suites never name the image by a literal tag.
- [X] T006 [P] Add `ankka.controlplane.auth.{issuer, jwks-url, audience, client-id, realm-hint, clock-skew}` to `controlplane/src/main/resources/reference.conf` per [data-model.md](./data-model.md), and **remove** `auth.token` and its `ANKKA_CONTROLPLANE_TOKEN` line.
- [X] T007 [P] Create the SC-005 harness `CPT/VerificationOverheadBenchmark.scala` on `AnkkaTestKit`: one real request (HTTP in, entity, journal, reply) with `Acl.AllowAll` and with `Acl.Authenticate` against an in-process JWKS, reporting the ratio; gated on `-Dankka.benchmarks` like feature 007's. It runs at T031 and again at T081.

**Checkpoint**: T002–T004 answered and recorded. If T003 is false, amend the spec (FR-029) before
Phase 6. If T004 needs `additionalOptions`, T041 uses it.

---

## Phase 2: Foundational — a principal in `http`, a verifier in the control plane, an actor on every event

**Purpose**: what every story needs. Blocks all stories.

### The published module (contracts/http-principal.md)

- [X] T008 Add `Principal` and `AuthDecision` to `HTTP/HttpEndpoint.scala` and the `Acl.Authenticate(RequestContext => AuthDecision)` case; add `protected def principal: Principal` to `HttpEndpoint`, throwing `IllegalStateException` when absent.
- [X] T009 Add `principal: Option[Principal]` to `RequestContext` in `HTTP/RequestContext.scala` (default `None` on `SimpleRequestContext`), and in `HTTP/HttpServer.scala` fold `Authenticate`: `Allow` puts the principal on the shared context before dispatch; `Unauthenticated` → 401 with `WWW-Authenticate: Bearer <challenge>`; `Forbidden` → 403; `Unavailable` → 503 with `Retry-After: 5`. The three existing cases are untouched.
- [X] T010 [P] Write `modules/http/src/test/scala/com/thinkmorestupidless/ankka/http/AclSuite.scala`: each decision's status and headers; `request.principal` visible inside the handler; absent under `AllowAll`; `principal` throws under `AllowIf`; the health route still exempt.

### The verifier (research R4, R5)

- [X] T011 Create `CP/auth/AuthConfig.scala` reading the keys from T006; `jwks-url` defaults to `<issuer>/protocol/openid-connect/certs`; empty issuer throws at construction with a message naming `ANKKA_AUTH_ISSUER`.
- [X] T012 Create `CP/auth/TokenVerifier.scala` over nimbus: a remote JWK source at `jwks-url` with cache and rate-limited refresh, asymmetric algorithms only, claims verifier for `iss`, `aud` contains the audience, `typ == "Bearer"`, `exp`/`nbf` with the configured skew, `sub` present. Returns `Verified(claims)`, `Rejected(reason)` or `Unavailable(reason)` — never throws to the caller.
- [X] T013 [P] Create `CP/auth/Principals.scala`: claims → `Principal` (`sub`, `name`/`preferred_username`, `email`, `email_verified` default false, `realm_access.roles`, remaining claims stringified); `isPlatformAdmin` reads the `platform-admin` role.
- [X] T014 Replace `ControlPlaneAcl.bearer` in `CP/api/ControlPlaneAcl.scala` with `ControlPlaneAcl.oidc(verifier, config): Acl.Authenticate` mapping the verifier's outcomes onto `AuthDecision`, with the challenge `realm="<realm-hint>"` and, for a rejected token, `error="invalid_token", error_description="<reason>"`; a token that is not a JWT at all gets the description `shared tokens are no longer accepted; run 'ankka login'`. Delete `bearer`.
- [X] T015 Update `CP/ControlPlane.scala`: `aclFrom(config)` builds `AuthConfig` and `TokenVerifier` and returns `oidc(...)`; `builder` and `endpoints` unchanged in signature.
- [X] T016 [P] Create the test signer `CPT/TestIdentity.scala`: generates an RSA key pair, serves a JWKS on `jdk.httpserver` at loopback, mints tokens with chosen `sub`/`email`/`email_verified`/roles/`aud`/`iss`/expiry/`kid`, and can rotate keys and go "unavailable". Shared with `cli` suites through `test->test` if needed.
- [X] T017 [P] Write `CPT/TokenVerifierSuite.scala` per [quickstart.md](./quickstart.md) tier 1: every rejection reason in [contracts/http-api.md](./contracts/http-api.md); `none` and HMAC refused with no key fetch; unknown `kid` → exactly one refetch; rotation accepted; JWKS unavailable with nothing cached → `Unavailable`; with a cache → still verifies.
- [X] T018 Switch `CPT/ControlPlaneHttpSuite.scala` and `CPT/CliEndToEndSuite.scala` from `ControlPlaneAcl.bearer(Token)` to `oidc` against `TestIdentity`, sending a minted token per request; keep "health needs no token, everything else does" and make it assert 401 with the `WWW-Authenticate` header. Every other suite that built the control plane with a token (`grep -rl 'bearer(' controlplane/src/test`) follows.

### Actor and time on events (research R11)

- [X] T019 Add `Actor(subject, display, administrative)` to `CP/domain/model.scala` and `actor: Option[Actor] = None, at: Option[Instant] = None` to every command-produced case of `OrganizationEvent`, `ProjectEvent` and `ServiceEvent` in `CP/domain/events.scala` (`ServiceObserved` excluded); add `Actor` and an `Instant` to the command payloads (`ApplyService` gains `actor`, `at`; new `Attributed(actor, at)` wrapper for the parameterless commands) so handlers persist what the endpoint supplies.
- [X] T020 [P] Add a pre-feature journal fixture: `controlplane/src/test/resources/journal/pre-008-events.json` holding one JSON sample of every existing event case in the shape the journal held before this feature, and `CPT/EventCompatibilitySuite.scala` asserting each decodes through the current codec with `actor == None` and `at == None` (FR-024).
- [X] T021 Thread the actor from `request.principal` into every command in `CP/api/OrganizationEndpoint.scala`, `CP/api/ProjectEndpoint.scala` and `CP/api/ServiceEndpoint.scala` with `at = Instant.now()` (a `Clock` on the endpoint, injectable for tests); `administrative` is `false` here and set by US2's `Authorization` (T046).

**Checkpoint**: `sbt controlPlane/test` green with every endpoint behind `oidc` and every write
attributed; `sbt http/test` green; no token string remains in `controlplane/src`.

---

## Phase 3: User Story 1 — Lock the door (P1) 🎯 MVP

**Goal**: no request succeeds without a verified token from the installation's Keycloak; a
developer logs in from the CLI with the device flow; the identity provider is part of the
installation; the shared token is gone everywhere.

**Independent Test**: [quickstart.md](./quickstart.md) tiers 1, 3 and 4 — every route 401
without a token through the real address; `ankka login` from a fresh machine to `services list`
in under two minutes; the deploy script's smoke test authenticates for real.

### Discovery and identity routes (contracts/http-api.md `/auth`)

- [X] T022 [P] [US1] Add `AuthDiscovery(issuer, clientId, audience)` and `Whoami(subject, name, email, emailVerified, platformAdmin, organizations)` to `API/descriptors.scala` with jsoniter codecs in `Wire`.
- [X] T023 [US1] Create `CP/api/AuthEndpoint.scala` at prefix `/auth`: `GET /` with `Acl.AllowAll` returning `AuthDiscovery` from `AuthConfig`; `GET /whoami` behind `oidc` returning the principal with an empty organizations list (US2 fills it). Register it in `ControlPlane.endpoints`. **The discovery route is a second endpoint at `/auth`, so `HttpEndpoint` must allow per-route ACLs or `/auth` splits into two endpoints (`/auth` open, `/auth/whoami` — check literal-segment precedence per `CLAUDE.md`)**; choose the split and document it in the class comment.
- [X] T024 [US1] Extend `CPT/ControlPlaneHttpSuite.scala`: `GET /auth` needs no token and reveals only the three fields; `GET /auth/whoami` needs one and echoes the principal; an expired, wrong-issuer, wrong-audience and pre-feature-shaped token each get 401 with the tabled `error_description` (S1.1, S1.2, S1.7).

### CLI login (contracts/cli-commands.md, research R6)

- [X] T025 [P] [US1] Create `CLI/Credentials.scala`: `credentials.json` beside `Settings.path`, mode 0600 via `PosixFilePermissions` where supported, keyed by URL, entries `{issuer, clientId, refreshToken, accessToken, expiresAt}`; `load`, `save(url, entry)`, `remove(url)`, `clear()`; never logged.
- [X] T026 [P] [US1] Create `CLI/DeviceFlow.scala` on the JDK client with `Trust.sslContext` from settings: `discover(issuer)` → device, token and optional revocation endpoints; `start(clientId)` → `DeviceAuthorization(verificationUri, verificationUriComplete, userCode, deviceCode, interval, expiresIn)`; `poll(...)` honouring `interval`, `+5s` on `slow_down`, `authorization_pending`, expiry; `refresh(refreshToken)`; `revoke(...)` if an endpoint exists. Requests `scope=openid offline_access`.
- [X] T027 [US1] Resolve the bearer in `CLI/ControlPlaneClient.scala` per [contracts/cli-commands.md](./contracts/cli-commands.md): `--token`/`ANKKA_TOKEN` verbatim; else saved access token with >30 s left; else refresh and save; else fail `not logged in to <url>; run 'ankka login'`. Map 401 → `<url> rejected the login; run 'ankka login'`, 403 → `not permitted: <message>`; both exit 1, no retry loop.
- [X] T028 [US1] Add `login [--no-browser]`, `logout [--all]` and `whoami` to `CLI/Main.scala` per the contract's printed lines, using `java.awt.Desktop`-free browser opening (`open`/`xdg-open`/`rundll32` subprocess, failure ignored); `config get` prints `login: saved|none` and never the contents; add `Output.whoami` to `CLI/Output.scala`.
- [X] T029 [P] [US1] Write `CLIT/DeviceFlowSuite.scala` against a scripted authorization server on `jdk.httpserver` (discovery, device, token, revocation): happy path; `authorization_pending` then success; `slow_down` lengthens the interval; expiry fails with the tabled message; `offline_access` is in the requested scope; `logout` revokes then deletes.
- [X] T030 [P] [US1] Write `CLIT/CredentialsSuite.scala`: 0600; per-URL keying (two URLs, two logins, `logout` removes one); `-Dankka.config` relocates the credentials file too; `--token` bypasses a saved login (S4.3 pinned early); the token never appears in `config get` output in either format.
- [X] T031 [US1] Run `VerificationOverheadBenchmark` (T007) once the ACL is `oidc`; record the ratio in [research.md](./research.md) R12. If it exceeds 1%, the JWK source's cache configuration is the first suspect, not the design.

### The Keycloak component (contracts/keycloak-component.md, research R1–R3)

- [X] T032 [P] [US1] Create `kustomization/components/keycloak/namespace.yaml` (`ankka-auth`, gateway-selector label as `ankka-controlplane` carries), `postgres.yaml` (CNPG `Cluster` `ankka-keycloak-db`, `initdb` database/owner `keycloak`, no SQL refs), `admin-secret.yaml` (`ankka-keycloak-admin`, `admin`/`admin`, comment mirroring `token-secret.yaml`'s), `httproute.yaml` (`auth.BASE_DOMAIN` → `ankka-keycloak-service:8080`, same `parentRefs` as the control plane's).
- [X] T033 [US1] Create `kustomization/components/keycloak/keycloak.yaml` from T004's working manifest (`db` from `ankka-keycloak-db-app`, `hostname.hostname: https://auth.BASE_DOMAIN:8443`, `strict: false`, `httpEnabled`, proxy headers as T004 found, `ingress.enabled: false`, `bootstrapAdmin.user.secret: ankka-keycloak-admin`) and `kustomization.yaml` (Component; `namespace: ankka-auth`; resources: namespace, the pinned operator reference, postgres, admin secret, keycloak, httproute).
- [X] T034 [US1] Remove `kustomization/components/controlplane/token-secret.yaml` and its entry in `kustomization/components/controlplane/kustomization.yaml`; in `deployment.yaml` drop the token env and add `ANKKA_AUTH_ISSUER` (placeholder `https://auth.BASE_DOMAIN:8443/realms/ankka`) and `ANKKA_AUTH_JWKS_URL` (`http://ankka-keycloak-service.ankka-auth.svc:8080/realms/ankka/protocol/openid-connect/certs`).
- [X] T035 [US1] Add the component to `kustomization/overlays/local/kustomization.yaml` and `replacements` targets for the `HTTPRoute` hostname, the `Keycloak` hostname and the control plane's `ANKKA_AUTH_ISSUER` (base domain, label after the first dot) and for the `httpsPort` into the `Keycloak` hostname and the issuer; extend `deploy-local.sh`'s `sed` anchors if kustomize cannot substitute inside the URL.
- [X] T036 [US1] Add the component to `kustomization/overlays/remote/kustomization.yaml` with the same replacements, and a `$patch: delete` for `Secret ankka-keycloak-admin` with the out-of-band `kubectl create secret` line in its comment, mirroring the token patch — which is removed.
- [X] T037 [US1] Extend `kustomization/deploy-local.sh`: install the Keycloak operator in the controllers step; after the overlay, wait for `cluster/ankka-keycloak-db` and `keycloak/ankka-keycloak` `Ready`; render the `KeycloakRealmImport` from `realm.json` (JSON indented under `spec.realm:` with `sed`, the ConfigMap technique) and apply it, waiting for `Done`; create user `dev` and client `ankka-local-smoke` idempotently through the admin API via the gateway with `--cacert`; replace the smoke test with a client-credentials token and `GET /organizations` requiring 200, with the 401 hint from the contract; print the `ankka login` line.
- [X] T038 [US1] Extend `docker-compose.yml` with `keycloak` (`start-dev --import-realm`, `realm.json` mounted, admin env, port 8081, healthcheck on `/realms/ankka`) and `keycloak-init` (`kcadm.sh` creating `dev` with `platform-admin`, depends on healthy `keycloak`).
- [X] T039 [P] [US1] Extend `CPT/RemoteOverlaySuite.scala`: both overlays render the keycloak component and route `auth.<base>`; the remote render contains no `Secret` named `ankka-keycloak-admin` and no `ANKKA_CONTROLPLANE_TOKEN` anywhere; the issuer replacement lands in the control plane's env; the operator reference, the compose image and `BuildInfo`'s Keycloak version agree.
- [X] T040 [US1] Create `operator/src/test/scala/com/thinkmorestupidless/ankka/operator/KeycloakStack.scala` beside `GatewayStack`: installs the operator from the pinned reference with the node's `kubectl`, applies the component's manifests with the base domain filled, renders and applies the realm import, waits for `Ready`/`Done`, and creates a test client via the admin API — shared with the control plane suites through `test->test`.
- [X] T041 [US1] Extend `CPT/EndToEndClusterSuite.scala`: install `KeycloakStack`; configure the in-JVM control plane with the real issuer and the in-cluster JWKS URL reached through the node; through the gateway from the host with `curl --cacert --resolve`: every route 401 without a token (SC-001), 200 with a token from the suite's client; run the real CLI `login` against the scripted flow is **not** attempted here — instead `--token` with the minted token drives `organizations list` end to end.
- [X] T042 [US1] Update `README.md` ("Control plane and CLI", the local and cluster walkthroughs, "The CLI is a thin client", the authentication paragraph, the "Not implemented" list) and `CLAUDE.md` (commands, the token trap becomes "the token is gone", the new env vars, the realm-import one-shot trap, the two-issuer trap) for the login flow and the component; `git grep dev-local-token` must return nothing.

**Checkpoint**: the MVP. A deployed control plane refuses everything without a token; `ankka
login` works; the deploy script proves it. Organizations still bound nothing — that is US2.

---

## Phase 4: User Story 2 — Tenancy holds (P2)

**Goal**: an organization is a boundary: members see and change it, non-members cannot see it
exists; invitations by verified email; removal is immediate.

**Independent Test**: `AuthorizationMatrixSuite` (tier 2) with Alice and Bob; and by hand in
tier 4 with a second Keycloak user.

### Organization state, events and queries (data-model.md)

- [X] T043 [US2] Extend `Organization` in `CP/domain/model.scala` with `members`, `invitations`, `disabled` and `Member`, `Invitation`, `Role`; add the events `MemberInvited`, `InvitationRevoked`, `InvitationClaimed`, `MemberAdded`, `MemberRemoved`, `MemberRoleChanged` to `CP/domain/events.scala` and `owner: Option[Actor]` on `OrganizationCreated`; folds per the data model, tolerant of a journal that violates the last-owner rule.
- [X] T044 [US2] Extend `CP/application/OrganizationEntity.scala`: `create` records the creator as owner; commands `invite`, `revokeInvitation`, `claimInvitation`, `removeMember`, `changeRole` with the handler rules (FR-016, FR-017 verified-email, FR-018 last owner); queries `roleOf(subject): MembershipAnswer`, `pendingFor(email)`, `members`; `delete` clears members and invitations in the fold.
- [X] T045 [P] [US2] Extend `CPT/TenancyEntitySuite.scala` with `EventSourcedTestKit` cases: creator is owner; invite/claim/revoke; unverified email cannot claim; last owner cannot be removed or demoted; deleted organization has no members; replay of the T020 fixture leaves an organization with no members and no owner (pre-feature).
- [X] T046 [US2] Create `CP/auth/Authorization.scala`: `roleIn(principal, organizationId)` via `OrganizationEntity.roleOf`; `resolveProject(projectId)` → organization id via `ProjectEntity.get`; `requireMember`/`requireOwner` returning an `Authorized(actor)` whose `administrative` flag is set when only `platform-admin` sufficed; non-membership → `CommandError(NotFound, <the same message as a missing id>)`; disabled → `Conflict("organization '<id>' is disabled")` for writes.
- [X] T047 [US2] Create `CP/auth/InvitationClaim.scala` (research R9): `claimIfPending(principal, organizationId)` called by `Authorization` before refusing; `claimAll(principal)` for listings and `whoami`, using the view rows from T048.

### Scoped listings (research R8)

- [X] T048 [US2] Extend `OrganizationRow` in `CP/application/OrganizationRows.scala` with `disabled`, `members: Vector[String]`, `invitations: Vector[String]`, folding every new event; add `SqlSyntax.jsonContains(field, value)` in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/sql.scala` rendering `(payload::jsonb) @> <json>`, with a unit case in the runtime's SQL suite.
- [X] T049 [US2] Add `disabled: Boolean = false` and `role: Option[Role] = None` to `OrganizationSummary`, `OrganizationMembership` for `Whoami`, and `Role` with a string codec in its companion, in `API/descriptors.scala`; `OrganizationSummary.of` never exposes the subject list.

### Every route authorized (contracts/http-api.md)

- [X] T050 [US2] Rewrite `CP/api/OrganizationEndpoint.scala`'s existing routes: `GET /` lists the caller's organizations (all for platform admin) with `role`, after `claimAll`; `GET /{id}` requires member (404 otherwise); `POST /{id}` any authenticated, creator as owner; `PUT …/name` owner; `DELETE` owner with the existing projects rule.
- [X] T051 [US2] Rewrite `CP/api/ProjectEndpoint.scala`: `GET /` restricted to the caller's organizations (an `?organization=` outside them yields an empty list); `GET /{id}` member (404); `POST /{id}` member of `organizationId` (404 if not visible); rename and delete member; all writes refused while disabled.
- [X] T052 [US2] Rewrite `CP/api/ServiceEndpoint.scala`: every route resolves project → organization → role through `Authorization`; reads member (404 otherwise); writes member and refused while disabled; `logs` allowed while disabled.
- [X] T053 [US2] Fill `Whoami.organizations` in `CP/api/AuthEndpoint.scala` from the view after `claimAll`.
- [X] T054 [US2] Write `CPT/AuthorizationMatrixSuite.scala` on `AnkkaTestKit` + `TestIdentity`: Alice and Bob, `acme` and `globex`, platform admin Carol; every role × every route in the contract's tables, asserting status codes and that 404 messages for "not a member" and "never existed" are identical; S2.1–S2.8 verbatim, including claim on a refused write and claim on listing, unverified email not claiming, and removal refused on the very next request; listings never show the other organization's ids.
- [X] T055 [P] [US2] CLI: `ROLE` and `STATE` columns on `organizations list`/`get` in `CLI/Output.scala`; `whoami` prints organizations with roles; extend `CLIT/OutputSuite.scala`.
- [X] T056 [US2] Extend `CPT/CliEndToEndSuite.scala` with two identities: Bob's `organizations list` is empty until invited; after Alice's invite (via T060's command in US3, or the HTTP route directly here) Bob's next list shows `acme` as member.

**Checkpoint**: tenancy is a boundary. An organization still cannot be administered beyond
invite/remove — US3 completes the surface.

---

## Phase 5: User Story 3 — Administer without the control plane holding a credential (P3)

**Goal**: owners manage members from the CLI; the last owner is protected; platform admins act on
any organization, repair ownerless ones, and disable/enable organizations, suspending their
services.

**Independent Test**: S3.1–S3.12 in `AuthorizationMatrixSuite` and `SuspensionSuite`; tier 4 by
hand for disable/enable against a running sample.

### Membership routes and CLI

- [X] T057 [P] [US3] Add `Invite`, `RoleChange`, `MemberSummary`, `InvitationSummary`, `MembersResponse` to `API/descriptors.scala`.
- [X] T058 [US3] Add the members routes to `CP/api/OrganizationEndpoint.scala`: `GET /{id}/members` (member), `POST /{id}/members` (owner, 409 if member or pending), `DELETE /{id}/members/{subject}` (owner, 409 last owner), `PUT /{id}/members/{subject}/role` (owner, 409 last owner), `DELETE /{id}/invitations/{email}` (owner), `POST /{id}/members/{subject}/repair` (platform admin only, allowed while disabled, recorded administrative).
- [X] T059 [P] [US3] Add `listMembers`, `invite`, `removeMember`, `changeRole`, `revokeInvitation`, `repairMember`, `disableOrganization`, `enableOrganization` to `CLI/ControlPlaneClient.scala`.
- [X] T060 [US3] Add `organizations members list|add|remove|role|repair`, `organizations invitations revoke`, `organizations disable|enable` to `CLI/Main.scala` with `Output.members` (`SUBJECT ROLE EMAIL SINCE`, then `EMAIL ROLE INVITED BY`) in `CLI/Output.scala`; 409 messages printed as sent.
- [X] T061 [US3] Extend `CPT/AuthorizationMatrixSuite.scala` with S3.1–S3.8 and S3.12: listing shows members and pending; inviting a member is 409; revoke prevents a later claim; demotion removes member management but not deploy; last-owner refusals name the reason; a member cannot invite; platform admin lists all and repairs an ownerless organization, recorded administrative; owner cannot disable.

### Disabling an organization and suspension (research R10)

- [X] T062 [US3] Add `OrganizationDisabled`/`OrganizationEnabled` events, `disable`/`enable` commands and the disabled-refusal rule on every other mutating command (except `enable`, `delete`, repair) to `CP/domain/events.scala` and `CP/application/OrganizationEntity.scala`; fold `disabled` into `OrganizationRows`.
- [X] T063 [US3] Add `suspended`, `onSuspended`, `onReinstated`, `targetInstances` change and `Suspended` lifecycle handling to `Service` in `CP/domain/model.scala`; `ServiceSuspended`/`ServiceReinstated` events; idempotent `suspend`/`reinstate` commands in `CP/application/ServiceEntity.scala`; `ServiceLifecycle.Suspended` and `ServiceStatus.suspended` in `API/descriptors.scala`.
- [X] T064 [P] [US3] Extend `CPT/ServiceEntitySuite.scala`: suspend from running → `Suspended`, 0 target instances; suspend a paused service keeps `paused`; reinstate restores `Paused` or `UpdateInProgress`; suspend twice persists nothing; delete clears `suspended`.
- [X] T065 [US3] Create `CP/application/SuspensionTrigger.scala`, a `Consumer[OrganizationEvent, Nothing]` on `OrganizationEntity` (companion instance like `ProjectionTrigger`): on `OrganizationDisabled`/`OrganizationEnabled` enumerate the organization's projects (`ProjectRows`) and services (`ServiceRows`) and command `suspend`/`reinstate`; ignore everything else; register in `ControlPlane.componentsWith`.
- [X] T066 [US3] Extend the sweep in `CP/deploy/ServiceProjector.scala`: for every service row, read its organization's row; running-and-not-suspended in a disabled organization → `suspend`; suspended in an enabled one → `reinstate`; before projecting. No `ActorContext` access — the same executor and shape as the existing sweep work.
- [X] T067 [US3] Add `POST /{id}/disable` and `POST /{id}/enable` (platform admin, 409 if already in that state) to `CP/api/OrganizationEndpoint.scala`; confirm every write in `ProjectEndpoint`/`ServiceEndpoint` answers 409 while disabled and reads and `logs` succeed (S3.10).
- [X] T068 [US3] Write `CPT/SuspensionSuite.scala` on `AnkkaTestKit` with `FakeAnkkaServiceClient`: disable stops the running service and leaves the paused one paused (S3.9); enable restores exactly the running one (S3.11); the lagging-view case — apply a service, disable before its row exists (hold the projection with the fake), assert the next sweep suspends it; enable/disable are idempotent under redelivery.
- [X] T069 [P] [US3] `Suspended` in `CLI/Output.scala`'s lifecycle rendering and a `services list` case in `CLIT/OutputSuite.scala`.

**Checkpoint**: an organization can be run for years by its owners and shut off by an
administrator, with nothing in the control plane able to touch Keycloak.

---

## Phase 6: User Story 4 — Machines log in the same way (P4)

**Goal**: a CI job authenticates with a client-credentials token through the existing token flag,
is a member like anyone else, and is named as the actor.

**Independent Test**: `KeycloakRealmSuite` service-account case and `CredentialsSuite`'s
precedence case; by hand, the deploy script's smoke client.

- [X] T070 [US4] Extend `CPT/KeycloakRealmSuite.scala` (T002/T003): a confidential client with a verified email on its service-account user obtains a client-credentials token, the verifier accepts it, an invitation for that email is claimed on its first refused write, and the resulting `ServiceApplied` names the client's subject (S4.1, S4.2).
- [X] T071 [P] [US4] (covered by `CLIT/DeviceFlowSuite.scala`'s explicit-token case against the scripted control plane) Assert in `CLIT/CredentialsSuite.scala` and `CPT/CliEndToEndSuite.scala` that with `ANKKA_TOKEN` set no login is attempted, no credentials file is read or written, and a non-member client's apply is refused as any non-member's (S4.2, S4.3).
- [X] T072 [P] [US4] Document the CI client in `README.md` (creating a confidential client in the console, assigning `ankka-controlplane`, setting a verified email on its service account, obtaining a token with `curl`, passing it as `ANKKA_TOKEN`) and in [contracts/keycloak-component.md](./contracts/keycloak-component.md) if the realm needs a change from T070.

**Checkpoint**: automation deploys with no prompt and no second credential type.

---

## Phase 7: User Story 5 — Read the audit trail (P5)

**Goal**: who applied, paused, restarted, exposed or deleted a service, and when; who invited
whom; pre-feature history visible as unattributed.

**Independent Test**: S5.1–S5.3 in `AuthorizationMatrixSuite`; `services history` by hand.

- [X] T073 [US5] Add `HistoryEntry` and `history: Vector[HistoryEntry]` (last 50, newest first) to `Service` in `CP/domain/model.scala`, appended by every command-produced fold and not by `onObserved`; `history` query in `CP/application/ServiceEntity.scala`.
- [X] T074 [P] [US5] Add `HistoryEntry` wire type to `API/descriptors.scala`; `invitedBy`/`addedBy` on `MemberSummary` and `InvitationSummary` if not already carried from T057.
- [X] T075 [US5] Add `GET /services/{project}/{name}/history` (member) to `CP/api/ServiceEndpoint.scala`.
- [X] T076 [US5] Add `services history <name>` to `CLI/Main.scala`, `serviceHistory` to `CLI/ControlPlaneClient.scala` and `Output.history` (`WHEN KIND GEN BY (admin)`, `-` for unattributed) to `CLI/Output.scala`.
- [X] T077 [US5] Extend `CPT/AuthorizationMatrixSuite.scala`: apply by Alice, pause by Bob → history names each with a time (S5.1); a platform-admin action shows `(admin)`; the T020 fixture replayed into a service shows entries with no actor (S5.2); the T070 client appears as actor (S5.3); the members listing shows `invitedBy`.
- [X] T078 [P] [US5] Extend `CPT/ServiceEntitySuite.scala`: history is capped at 50 and newest first; observations do not add entries.

**Checkpoint**: "the journal is the audit trail" is true with many users.

---

## Phase 8: Polish and cross-cutting

- [X] T079 [P] Re-run `RemoteOverlaySuite` and `EventCompatibilitySuite`; run the reviewer's checklist in [quickstart.md](./quickstart.md) and fix anything it catches (`grep` for the old token, `dev-local-token`, a checked-in `KeycloakRealmImport`, users in `realm.json`, dependency lists).
- [X] T080 [P] Run `sbt scalafmtAll scalafmtSbt` and `sbt compile` warning-free (`-Wunused`).
- [X] T081 Re-run `VerificationOverheadBenchmark` after every route is authorized and the invitation claim exists on the refusal path; confirm the member hot path is unchanged from T031 and record both numbers in [research.md](./research.md).
- [X] T082 [P] Update `docs/design/authenticated-control-plane.md`'s "Things clarify should settle" and "Follow-ons" to point at the spec and mark the k3s decision (research R12) settled.
- [ ] T083 **Not run — the machine's kubectl context was a GKE production cluster and no `ankka` kind cluster existed; creating one switches the active context, which was left to the user.** Run tier 4 by hand ([quickstart.md](./quickstart.md)) on a fresh kind cluster, including the second-user walk-through and the remote overlay's admin-secret behaviour (research verify item 7), and record the outcome in [research.md](./research.md).
- [X] T084 (run piecewise, awake: the full fast suite, then every k3s suite; `MultiNodeClusterSuite`'s crash case failed once on its own kill mechanics — the container it picked had already been replaced — and `ExposureClusterSuite` passed 9/9 on a clean rerun after two load-induced failures) `caffeinate -i sbt test` once, awake, and read any k3s failure's duration before blaming the platform (`CLAUDE.md`).

---

## Dependencies

```
Phase 1 (verify) ─→ Phase 2 (foundational) ─→ US1 ─→ US2 ─→ US3 ─→ US5
                                                       │        └──→ US4 (after T047's claim path)
                                                       └─ US4 needs US2 (membership) only
Phase 8 after everything.
```

- **US1** depends only on Phase 2. It is the MVP and independently deployable: every route locked,
  login working, organizations still unbounded.
- **US2** depends on US1's `AuthEndpoint` (for `whoami`) and on Phase 2's actor threading.
- **US3** depends on US2's `Authorization` and organization state; its suspension half (T062–T068)
  depends on nothing in its membership half and can run in parallel with T057–T061.
- **US4** depends on US2's invitation claim (T047); T071 and T072 can run as soon as US1 is done.
- **US5** depends on Phase 2's actor on events; T073–T076 could start after Phase 2, but the
  assertions in T077 need US2–US4's actors, so it is sequenced last.

## Parallel execution examples

- Phase 1: T001 → T002 → T003 in sequence (one suite), while T004 runs on k3s and T005–T007 are
  independent edits.
- Phase 2: T008–T010 (`http`) in parallel with T011–T013 (`auth/`); T016, T017, T020 in parallel
  once T012's outcomes are named.
- US1: the CLI (T025–T030) and the component (T032–T039) are disjoint trees and can proceed
  side by side once T022–T024 fix the discovery contract; T040–T041 wait for T033 and T037.
- US3: T057–T061 (membership) alongside T062–T068 (suspension).

## Implementation strategy

1. **Phase 1 first, honestly.** Three of its seven tasks can invalidate the design (T002–T004).
   Do not build the verifier on an assumed `aud` or the component on an assumed CRD field.
2. **MVP = Phase 2 + US1.** A deployed installation with login and no tenancy is already safer
   than today's and can be shipped to the kind cluster and used.
3. **US2 before anything else in the tenancy line.** It is the story the treatment exists for.
4. **US3's two halves in parallel**, then US4 and US5, which are mostly tests and CLI surface over
   state the earlier stories wrote.
5. **Every story ends green in `sbt controlPlane/test`** with the cluster suites off, and US1 and
   Phase 8 each run them on.
