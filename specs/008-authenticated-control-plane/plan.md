# Implementation Plan: An Authenticated, Multi-User Control Plane

**Branch**: `008-authenticated-control-plane` | **Date**: 2026-09-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-authenticated-control-plane/spec.md`, and the
design treatment it was written from, `docs/design/authenticated-control-plane.md`.

## Summary

Replace the shared bearer token with verified OpenID Connect identity from a Keycloak instance
that is part of the installation, and make organizations a real boundary. Keycloak is the only
authenticator: it decides who is a user and signs tokens. The control plane is the only authorizer:
it verifies tokens offline against the realm's cached keys, takes the token's stable subject as the
caller, and answers every membership and role question from the `Organization` entity's own
event-sourced state. Membership, invitations, the organization's disabled flag and the actor on
every command-produced event are all facts in the journal, so they replay, audit and enforce the
way everything else in the control plane already does.

The CLI logs in with the OAuth 2.0 device authorization grant, keeps a renewable login per
installation in a private file, and re-uses the existing token flag for non-interactive clients.
Keycloak is deployed through the Keycloak operator as a kustomize component with its own namespace,
CNPG cluster and route at `auth.<base>`, so that a later feature can provision identity for hosted
services by adding a realm resource rather than replacing a deployment mechanism.

Disabling an organization suspends every service in its projects. The fan-out follows the pattern
the projector already uses: a consumer on the organization's events is the latency half, and the
thirty-second sweep is the correctness half.

## Technical Context

**Language/Version**: Scala 3.9.0, JDK 21, sbt 1.12.15 (unchanged).

**Primary Dependencies**: one new library, `com.nimbusds:nimbus-jose-jwt` 10.9.1, in
`controlplane` only. `modules/http` (published) gains a principal type and an authenticating ACL
case with **no** new dependency; `cli` gains nothing (device flow and discovery are the JDK HTTP
client and jsoniter). Keycloak 26.7.4 and its operator at the same version, pinned by exact
release the way CNPG and cert-manager are.

**Storage**: the control plane's existing journal and view tables. New events on
`organization-event` and `service-event`; one extended view row (`organization-rows`) so listings
can be scoped without touching an entity. No DDL change: view tables are created by the runtime.
Keycloak gets a CNPG `Cluster` of its own in its own namespace.

**Testing**: munit. A verifier unit suite with an in-process signer; the HTTP authorization matrix
on `AnkkaTestKit` with an in-process JWKS; one testcontainers Keycloak suite that mints real
tokens from the shipped realm; a device-flow suite against a scripted authorization server; the
existing k3s end-to-end suite extended to deploy the Keycloak component and log in; the existing
overlay-rendering suite extended for the new component and the remote overlay's deletions.

**Target Platform**: unchanged — the control plane in Kubernetes behind the platform Gateway, the
CLI on a developer's machine or in CI, `sbt controlPlane/run` against docker-compose locally.

**Project Type**: an additive change to a published library (`http`), a substantial change to one
application (`controlplane`), new commands in `cli`, one new kustomize component, changes to the
deploy script, docker-compose and both overlays, and documentation.

**Performance Goals**: SC-005 — verification adds no network call after the first and costs under
1% of a real request (measured the way feature 007 measured recording: HTTP in, entity, journal,
reply). SC-003 — removal takes effect on the next request; disablement in Keycloak within one
access-token lifetime (300s).

**Constraints**: the control plane holds no Keycloak admin credential (FR-011); `http` gains no
dependency and no knowledge of JWTs; `cli` still depends on `controlplane-api` alone; no
compatibility mode for the old token (FR-007); existing journals replay (FR-024); the operator is
untouched — it never sees a user.

**Scale/Scope**: ~35 files touched or added. The largest pieces are the authorization matrix
suite and the Keycloak component with its deploy-script wiring; the riskiest is the `http` change,
because it is published.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; `CLAUDE.md` governs.

| Principle | How this plan keeps it |
|---|---|
| Effects are inert data | membership, invitations, disabling and suspension are all events; the endpoint decides *who may*, the entity decides *whether the state allows*, and neither performs I/O to decide |
| Cross-entity checks live in the endpoint, never a handler | project → organization → role is resolved in the endpoint from entity queries (R8); `OrganizationEntity` never sees a project and `ServiceEntity` never sees an organization |
| Module dependency direction | the verifier lives in `controlplane`; `http` learns what a principal is, not what a JWT is (R7); `cli` gains no dependency |
| Explicit registration, bounded interning | two new components are registered in `ControlPlane.components`; subject ids are never interned into the recorder's name table (they are unbounded) |
| Single copy | one `realm.json`, used by the operator's import resource and by docker-compose (R2); one hostname derivation, unchanged; one issuer string derived from the same base domain and HTTPS port the control plane already carries |
| Withhold verbs rather than promise restraint | the control plane's ClusterRole is unchanged; it gains no right to read Secrets in Keycloak's namespace, and the admin secret is never mounted into it |
| The remote overlay deletes, never overrides, a development secret | the development user is created only by the local deploy script and compose, and the bootstrap admin secret is `$patch: delete`d exactly as the token secret was (R2) |
| Verify on a real cluster | the k3s end-to-end suite deploys the Keycloak component and logs in through the gateway; the deploy script's smoke test authenticates for real (R12) |
| Tests must never name an image by literal tag | the Keycloak image tag is one constant shared by the component's resource and the suites, pinned like CNPG's |
| Never touch `ActorContext` from a `Future` callback | the sweep extension (R10) is a pure function of view rows and entity calls, on the same virtual-thread executor the projector uses |
| A `var` assigned and never read is a lifecycle that never runs | the JWKS cache has a refresh schedule that is started and stopped by the extension, and `ServiceRegistrationSuite`'s lesson is applied: one test drives start → verify → rotate → stop |

**One tension, named**: `CLAUDE.md` says the control plane "holds no credential able to create a
workload". Keycloak's admin credential is a different power but the same shape of risk, and the
invitation design (R9) exists so the control plane never needs it. If a later change finds itself
adding a Keycloak admin client to the control plane, that is the design being abandoned, not
extended.

No violations.

## Project Structure

### Documentation (this feature)

```text
specs/008-authenticated-control-plane/
├── plan.md
├── research.md          # R1–R12, and the "verify at implementation" list
├── data-model.md        # principal, organization state and events, service changes, view rows
├── quickstart.md        # tiers 1–5 and the reviewer's checklist
├── contracts/
│   ├── http-api.md             # authentication semantics, new and changed routes
│   ├── cli-commands.md         # login/logout/whoami, members, disable/enable, history
│   ├── keycloak-component.md   # the component, the realm, the overlays, compose
│   └── http-principal.md       # the additive change to the published http module
└── checklists/requirements.md
```

### Source Code (repository root)

```text
modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/
├── HttpEndpoint.scala          # Acl.Authenticate, AuthDecision, Principal (R7)
├── RequestContext.scala        # + principal: Option[Principal]
└── HttpServer.scala            # 401 with WWW-Authenticate vs 403; principal into the context

controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/
├── auth/
│   ├── AuthConfig.scala        # issuer, jwks-url, audience, client-id; refuses to start unset
│   ├── TokenVerifier.scala     # nimbus: JWKS cache + refresh, checks (R4, R5)
│   ├── Principals.scala        # claims → Principal; platform-admin from realm roles
│   ├── Authorization.scala     # roleIn(org), requireMember/Owner, project→org resolution (R8)
│   └── InvitationClaim.scala   # claim-on-refusal and claim-on-listing (R9)
├── api/
│   ├── ControlPlaneAcl.scala   # bearer(token) REMOVED; oidc(verifier) ADDED
│   ├── AuthEndpoint.scala      # NEW: GET /auth (discovery), GET /auth/whoami
│   ├── OrganizationEndpoint.scala  # scoped list, members routes, disable/enable, 404-not-403
│   ├── ProjectEndpoint.scala   # membership on every route; refusals while disabled
│   └── ServiceEndpoint.scala   # membership on every route; refusals while disabled; history
├── application/
│   ├── OrganizationEntity.scala    # members, invitations, disabled; last-owner rule
│   ├── OrganizationRows.scala      # + members, invitations, disabled on the row (R8)
│   ├── ServiceEntity.scala         # suspend/reinstate, history, actor
│   ├── ProjectEntity.scala         # actor on events
│   └── SuspensionTrigger.scala     # NEW: consumer on organization events (R10)
├── deploy/ServiceProjector.scala   # sweep also suspends running services in disabled orgs (R10)
├── domain/{events,model}.scala     # Actor, new events, Member, Invitation, history
├── ControlPlane.scala              # aclFrom(config) builds the verifier; new components
└── resources/reference.conf        # ankka.controlplane.auth.{issuer,jwks-url,audience,client-id}

controlplane-api/src/main/scala/.../controlplane/api/descriptors.scala
                                    # Member, Invitation, Whoami, AuthDiscovery, HistoryEntry,
                                    # ServiceLifecycle.Suspended, OrganizationSummary.disabled

cli/src/main/scala/com/thinkmorestupidless/ankka/cli/
├── Main.scala                  # login, logout, whoami, organizations members …, disable/enable,
│                               # services history; 401 → "run ankka login"
├── Settings.scala              # unchanged shape; token flag semantics documented
├── Credentials.scala           # NEW: ~/.ankka/credentials.json per url, 0600, refresh
├── DeviceFlow.scala            # NEW: discovery, device authorization, polling (R6)
├── ControlPlaneClient.scala    # bearer from Credentials or token; new calls
└── Output.scala                # members, whoami, history tables

kustomization/components/keycloak/
├── kustomization.yaml          # namespace, operator (remote, pinned), CNPG cluster, Keycloak CR,
│                               # admin secret, HTTPRoute
├── namespace.yaml              # ankka-auth
├── postgres.yaml               # CNPG Cluster for Keycloak, bootstrap.initdb (no schema refs)
├── keycloak.yaml               # Keycloak CR: db, hostname, http, proxy, bootstrapAdmin
├── admin-secret.yaml           # development bootstrap admin (deleted by the remote overlay)
├── httproute.yaml              # auth.BASE_DOMAIN
└── realm.json                  # the one realm, no users (R2)
kustomization/components/controlplane/
├── token-secret.yaml           # REMOVED
├── kustomization.yaml          # − token-secret
└── deployment.yaml             # ANKKA_AUTH_ISSUER, ANKKA_AUTH_JWKS_URL; − token env
kustomization/overlays/{local,remote}/kustomization.yaml
                                # + keycloak component; replacements for auth.BASE_DOMAIN and the
                                # issuer; remote deletes the admin secret
kustomization/deploy-local.sh   # operator install step; realm import CR from realm.json; dev user
                                # and smoke client via the admin API; smoke test logs in
docker-compose.yml              # + keycloak (start-dev, --import-realm), + dev-user init
README.md, CLAUDE.md
```

**Structure Decision**: the verifier and everything that interprets a token live in
`controlplane/auth`, a package rather than a module, because nothing else consumes it yet and
`controlplane` is not published. The `http` change is deliberately the smallest thing that lets an
ACL say "unauthenticated" rather than "forbidden" and hand a principal to the handler; it does not
know what a JWT is, so `runtime` and `http` gain no JOSE dependency. The Keycloak component follows
`components/postgres` and `components/controlplane` in shape and `components/cnpg` in how it pins
an upstream controller.

## Complexity Tracking

No constitution violations to justify. Two additions that could look like scope and are not:

| Addition | Why it is in this feature |
|---|---|
| Service `history` in entity state (FR-025) | the spec's P5; bounded to 50 entries so state stays small; the alternative (a journal read API) is a runtime feature nobody else needs yet |
| Sweep-driven suspension (R10) | the only way FR-033's edge case ("an apply in flight during a disable") closes without a distributed lock; the sweep already exists and already enumerates every service |
