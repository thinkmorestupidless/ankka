# Tasks: A Platform a Hosted Product Can Provision

**Input**: Design documents from `/specs/011-hosted-ankka/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: included. The spec's success criteria are stated as suites (SC-001, SC-002, SC-005),
and this repository's rule is that a feature is proven by the tests that would catch its
regression. Tests are written beside the code they prove, in the same task group.

**Organization**: by user story. US1 and US2 are both P1 and independent of each other after the
foundational phase; US3 and US4 are P2 and independent of everything but the foundation.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 creation policy, US2 create for an owner, US3 publish, US4 spoke

## Path Conventions

Multi-module sbt build at the repository root: `controlplane-api/`, `controlplane/`, `cli/`,
`docs/`, `build.sbt`. Package root `com.thinkmorestupidless.ankka`, written `…` below.

---

## Phase 1: Setup

No new module, dependency or tool. Nothing to do.

---

## Phase 2: Foundational (blocking prerequisites)

The wire type both P1 stories decode, and the way a policy reaches the endpoint.

- [x] T001 Add `Owner(subject, email = None, display = None)` and `owner: Option[Owner] = None` on
      `CreateOrganization` in `controlplane-api/src/main/scala/…/controlplane/api/descriptors.scala`;
      the existing `createOrgCodec` (`Codecs.make`) covers it — add a `Codecs.make[Owner]` given
      beside it only if derivation asks for one. Confirm `sbt controlPlaneApi/compile cli/compile`.
- [x] T002 Add `OrganizationPolicy` in
      `controlplane/src/main/scala/…/controlplane/tenancy/OrganizationPolicy.scala`: enum
      `OrganizationCreation { Open, PlatformAdmin }`, case class `OrganizationPolicy(creation,
      signupUrl: Option[String])`, `default`, `refusal: String`, and `from(config: Config)` reading
      `ankka.controlplane.organizations.{creation, signup-url}` and throwing
      `IllegalArgumentException` for any other `creation` value, naming the key,
      `ANKKA_ORGANIZATION_CREATION` and both accepted values (data-model.md, research R1).
- [x] T003 Add the `organizations { creation, signup-url }` section with `${?ANKKA_ORGANIZATION_CREATION}`
      and `${?ANKKA_SIGNUP_URL}` overrides and a comment saying what each is for, in
      `controlplane/src/main/resources/reference.conf` (contracts/configuration.md).
- [x] T004 Thread the policy: `OrganizationEndpoint(clients, acl, policy: OrganizationPolicy =
      OrganizationPolicy.default, clock)` in
      `controlplane/src/main/scala/…/controlplane/api/OrganizationEndpoint.scala`;
      `ControlPlane.endpoints(acl, deploy, auth, policy = OrganizationPolicy.default)` and
      `ControlPlane.builder` loading `OrganizationPolicy.from(config)` in
      `controlplane/src/main/scala/…/controlplane/ControlPlane.scala`. Every existing caller
      compiles unchanged (the parameter has a default).

**Checkpoint**: `sbt -Dankka.cluster.tests=off controlPlane/test` is green with no test edited —
the default policy is today's behaviour (SC-001).

---

## Phase 3: User Story 1 — Only the administrator creates organizations here (P1)

**Goal**: under `ANKKA_ORGANIZATION_CREATION=platform-admin`, a non-administrator's create is a
403 with the documented message (and sign-up URL); an administrator's succeeds; every other route
is unchanged; a bad value refuses startup.

**Independent test**: `sbt 'controlPlane/testOnly *OrganizationPolicySuite
*OrganizationCreationPolicySuite'`, then the by-hand run in quickstart.md §2.

- [x] T005 [P] [US1] Write `controlplane/src/test/scala/…/controlplane/OrganizationPolicySuite.scala`:
      `from` of a config with no section override is `default`; `creation = "platform-admin"` and
      `signup-url = "https://ankka.cloud"` load; `refusal` with and without a URL is the exact
      documented text; `creation = "anyone"` throws with a message containing
      `ANKKA_ORGANIZATION_CREATION`, `open` and `platform-admin` (FR-001, FR-003, FR-005).
- [x] T006 [US1] Enforce the policy in `postBody("/{organizationId}")` of
      `controlplane/src/main/scala/…/controlplane/api/OrganizationEndpoint.scala`: when
      `policy.creation == PlatformAdmin` and `!authz.isAdmin(principal)`, throw
      `CommandError(policy.refusal, ErrorCode.Forbidden)` before any entity call; update the
      route's doc comment (FR-002, FR-004). Order relative to the owner check: research R2.
- [x] T007 [US1] Write `controlplane/src/test/scala/…/controlplane/OrganizationCreationPolicySuite.scala`
      on the pattern of `AuthorizationMatrixSuite` (one `AnkkaTestKit`, `TestIdentity`, endpoints
      with `OrganizationPolicy(PlatformAdmin, Some("https://ankka.cloud"))`): alice's
      `POST /organizations/acme` is 403 with the message and the URL and `GET /organizations/acme`
      as carol (admin) is then 404; carol's create is 204; alice, made a member of a carol-created
      organization by invitation claim, can list, get, rename as owner after a role change, manage
      members, create a project and apply a service exactly as under `open` (spec US1 scenarios
      2–5, SC-002).
- [x] T008 [US1] Document the policy: a "Who may create organizations" section on
      `docs/platform/identity.md` naming `ANKKA_ORGANIZATION_CREATION` (both values, default
      `open`) and `ANKKA_SIGNUP_URL`, saying which shape of installation sets them and that
      nothing but creation changes (FR-006); one sentence in the `POST /organizations/{organizationId}`
      prose of `docs/reference/control-plane-api.md` for the policy refusal (contracts/http-api.md).

**Checkpoint**: US1 is demonstrable by hand against compose with the two variables set.

---

## Phase 4: User Story 2 — The administrator creates an organization for its owner (P1)

**Goal**: an administrator's create naming an owner seats that subject as the only owner, records
the administrator as actor, lists for the owner, replays; a non-administrator naming an owner is
403.

**Independent test**: `sbt 'controlPlane/testOnly *TenancyEntitySuite *AuthorizationMatrixSuite
*EventCompatibilitySuite'`, then quickstart.md §3 by hand.

- [x] T009 [US2] Extend the event and the domain command in
      `controlplane/src/main/scala/…/controlplane/domain/events.scala`:
      `OrganizationCreated(name, actor = None, at = None, owner: Option[Owner] = None)` with a doc
      comment on `owner`; add `final case class CreateForOwner(name: String, owner: Owner)` beside
      `AddMember` (data-model.md).
- [x] T010 [US2] Fold the named owner in `Organization.onCreated(name, creator, at, owner)` in
      `controlplane/src/main/scala/…/controlplane/domain/model.scala`: `owner = Some(o)` seats
      `o.subject → Member(Role.Owner, o.email.map(Organization.key), o.display, at, addedBy =
      creator.map(_.subject))`; `None` is today's derivation, untouched (FR-008, FR-009, FR-011).
- [x] T011 [US2] Mirror the fold in the listing row: `OrganizationRows`'s `OrganizationCreated`
      case in `controlplane/src/main/scala/…/controlplane/application/OrganizationRows.scala`
      puts the named owner's subject in `members` and `owners` (else the owner's `GET
      /organizations` omits it — research R4).
- [x] T012 [US2] Add the entity command in
      `controlplane/src/main/scala/…/controlplane/application/OrganizationEntity.scala`:
      `createForOwner(request: CreateForOwner)` with the same refusals as `create` (deleted →
      conflict, known → conflict, empty name → error, empty subject → error), persisting
      `OrganizationCreated(name, actor, at, Some(request.owner))`; `applyEvent` passes the fourth
      field; companion gains `given Serializer[CreateForOwner] =
      Codecs.serializer[CreateForOwner]("create-for-owner")` and `val createForOwner =
      command("create-for-owner")(_.createForOwner)`. `create` keeps its `String` payload and wire
      name (research R3).
- [x] T013 [US2] Add `Authorization.administrator(principal): Metadata` in
      `controlplane/src/main/scala/…/controlplane/auth/Authorization.scala` — throws the same
      `Forbidden` as `requireAdmin` when the role is absent, otherwise
      `attribution(principal, administrative = true)` (research R6).
- [x] T014 [US2] Route the owner path in
      `controlplane/src/main/scala/…/controlplane/api/OrganizationEndpoint.scala`: if
      `request.owner.isDefined` and `!authz.isAdmin(principal)` → `CommandError("platform
      administrator role required to name an owner", Forbidden)` (checked first, before the
      policy); if defined and admin → `entity(id).call(OrganizationEntity.createForOwner)
      .withMetadata(authz.administrator(principal)).invoke(CreateForOwner(request.name, owner))`;
      otherwise the existing `createOrganization` call (FR-007, FR-010).
- [x] T015 [P] [US2] Extend `controlplane/src/test/scala/…/controlplane/TenancyEntitySuite.scala`
      with `EventSourcedTestKit` cases: `create-for-owner` by an administrative actor yields one
      event with `owner = Some` and actor = administrator; the resulting state has exactly one
      member, the owner, with email keyed and `addedBy` the administrator; folding the same events
      from `emptyState` reproduces the members (US2 scenario 7); an owner with subject only
      succeeds (scenario 6); a known or deleted id is a conflict; naming the administrator's own
      subject yields the administrator as sole owner (edge case).
- [x] T016 [P] [US2] Extend `controlplane/src/test/scala/…/controlplane/EventCompatibilitySuite.scala`:
      the pattern for `OrganizationCreated` gains the fourth field and asserts `owner == None` for
      the pinned pre-feature JSON; add a pinned JSON *with* an owner so the new shape is fixed too
      (FR-009).
- [x] T017 [US2] Extend `controlplane/src/test/scala/…/controlplane/AuthorizationMatrixSuite.scala`
      (default policy): carol creates `wonderland` naming alice (subject, email, display) → 204;
      members are exactly alice as owner with those details and `addedBy` carol; carol is not a
      member and `GET /organizations/wonderland` as carol shows no role; alice lists it, renames
      it, invites bob; bob naming an owner → 403 with the documented message and no organization
      created; carol naming nobody → carol is owner (US2 scenarios 1–5, SC-003).
- [x] T018 [US2] CLI: `--owner`, `--owner-email`, `--owner-name` on `organizations create` in
      `cli/src/main/scala/…/cli/Main.scala` (the two display options refused by the parser without
      `--owner`; success message `organization '<id>' created for <subject>` when named) and
      `createOrganization(id, name, owner: Option[Owner])` in
      `cli/src/main/scala/…/cli/ControlPlaneClient.scala` (contracts/cli-commands.md, FR-012).
- [x] T019 [US2] Regenerate the CLI reference:
      `sbt -Dankka.docs.update=true 'cli/testOnly *CliReferenceSuite'`, then add one hand-written
      sentence under `### ankka organizations create` in `docs/reference/cli.md` saying `--owner`
      is for platform administrators; update the `POST /organizations/{organizationId}` prose in
      `docs/reference/control-plane-api.md` with the body, the owner option and its 403
      (contracts/http-api.md), and run `sbt 'controlPlane/testOnly
      *ControlPlaneRoutesReferenceSuite'` to confirm the table is unchanged.

**Checkpoint**: an administrator provisions an organization for a customer in one CLI command
and the customer sees it on their next `ankka organizations list`.

---

## Phase 5: User Story 3 — A client outside this repository speaks the protocol (P2)

**Goal**: `ankka-controlplane-api` is the seventh published artifact, depending on `core` alone,
and the documentation says so.

**Independent test**: quickstart.md §4 — `sbt publishLocal` lists seven, a scratch project
depending on the one artifact compiles `ServiceSpec.problems` with no Pekko on the classpath.

- [x] T020 [US3] Remove `publish / skip := true` from `controlPlaneApi` in `build.sbt`; extend its
      doc comment to say it is published for clients of the control plane (the product's
      provisioner is the first) and still depends on `core` only; update the `templateArtifacts`
      comment ("six of the seven — the template is a service and does not need the wire library").
      Verify with `sbt publishLocal` and `ls ~/.ivy2/local/com.thinkmorestupidless/` (FR-013,
      FR-015).
- [x] T021 [US3] Prove the dependency edge: `sbt 'show controlPlaneApi/libraryDependencies'` and
      `sbt 'controlPlaneApi/dependencyTree'` (or `evicted`) show `ankka-core` and jsoniter and no
      pekko, r2dbc or fabric8 artifact; record the command in `specs/011-hosted-ankka/quickstart.md`
      §4 if it differs from what is written (FR-014).
- [x] T022 [P] [US3] Documentation of the seventh artifact: `docs/reference/scala-sdk.md` gains a
      short "For clients of the control plane" paragraph naming `ankka-controlplane-api`, its
      package `…controlplane.api`, what it holds (request and response types, `Role`,
      `ServiceLifecycle`, descriptor validation) and that it depends on `ankka-core` alone;
      `docs/get-started/install.md` says `sbt publishLocal` publishes the six a service depends on
      plus the control plane's wire library; `docs/reference/control-plane-api.md` gains a
      "From Scala" section pointing at it (FR-015).
- [x] T023 [P] [US3] `CLAUDE.md` *Publishing*: seven modules, the seventh named with why it is
      published and why `templateArtifacts` still lists six; *Module dependency direction*
      unchanged.

**Checkpoint**: a scratch project outside the repository builds against the published artifact.

---

## Phase 6: User Story 4 — An installation trusts an identity provider hosted elsewhere (P2)

**Goal**: proven on k3s that a control plane with an explicit issuer and key source on another
host accepts that realm's tokens, advertises that issuer, and refuses another issuer's token.

**Independent test**: `caffeinate -i sbt 'controlPlane/testOnly *EndToEndClusterSuite'` — the
new case.

- [x] T024 [US4] Add the spoke to
      `controlplane/src/test/scala/…/controlplane/EndToEndClusterSuite.scala`: in `beforeAll`,
      after the hub is up, start a second `AnkkaTestKit` with `ControlPlane.components` plus
      `ProjectionRuntime()` and an `HttpServer.at("127.0.0.1", 0)` of
      `ControlPlane.endpoints(ControlPlane.aclFor(spokeAuth), spokeDeploy, Some(spokeAuth))`,
      where `spokeDeploy = DeployConfig.default.copy(namespacePrefix = "spoke", baseDomain =
      Some(s"spoke.$BaseDomain"))` and `spokeAuth = auth.copy()` (the hub's issuer and forwarded
      JWKS URL, explicitly); stop it in `afterAll`. Add a test case: `GET /auth` on the spoke
      advertises the hub's issuer and not `AuthConfig.derivedIssuer(s"spoke.$BaseDomain",
      httpsPort)`; `GET /auth/whoami` with `Token` is 200 and the subject matches the hub's answer;
      a token minted by a fresh `TestIdentity("https://auth.other.test/realms/ankka")` is 401
      (US4 scenarios 1–4, FR-016 to FR-018, SC-005). Keep the case independent of the numbered
      service cases so it can be run alone by name.
- [x] T025 [P] [US4] Document the shape: "A spoke installation" section on
      `docs/platform/install-cloud.md` — omit the `keycloak-operator` and `keycloak` components
      from the overlay, set `ANKKA_AUTH_ISSUER` and `ANKKA_AUTH_JWKS_URL` on the control plane
      Deployment to the hub realm's public issuer and key URL, keep the spoke's own
      `ANKKA_BASE_DOMAIN`; and one sentence on `docs/platform/identity.md` under "The issuer the
      control plane expects" saying that an explicit issuer is how an installation trusts another
      installation's realm (FR-019).

**Checkpoint**: the end-to-end suite passes with the spoke case, awake, under `caffeinate`.

---

## Phase 7: Polish & cross-cutting

- [x] T026 Run the full fast set: `sbt scalafmtAll scalafmtSbt` then
      `sbt -Dankka.cluster.tests=off test` and `just docs`; every reference page is current
      (`CliReferenceSuite`, `ControlPlaneRoutesReferenceSuite` green without the update flag),
      `docs check` passes (SC-006).
- [x] T027 Run the k3s suites touched: `caffeinate -i sbt 'controlPlane/testOnly
      *EndToEndClusterSuite *ControlPlaneClusterSuite'`.
- [x] T028 Mark the treatment: in `docs/design/hosted-ankka.md` add a one-line status note under
      **Status** that the platform slice is feature 011 on this branch; leave the product sections
      as they are.

---

## Dependencies

```text
Phase 2 (T001–T004) ── blocks everything
   ├── US1 (T005–T008)   independent of US2/US3/US4
   ├── US2 (T009–T019)   independent of US1/US3/US4; T009 → T010,T011,T012 → T014; T013 → T014;
   │                      T018 → T019; T015,T016 parallel with T012–T014
   ├── US3 (T020–T023)   independent; T020 → T021; T022,T023 parallel
   └── US4 (T024–T025)   independent; T025 parallel with T024
Phase 7 after all four.
```

US1 and US2 both edit `OrganizationEndpoint.scala`'s create route (T006, T014) — do them in one
sitting or in sequence, not in parallel by two agents.

## Parallel execution examples

- After Phase 2: T005 (policy unit suite), T009 (event), T020 (build), T024 (spoke) touch four
  different files and can start together.
- Within US2: once T009–T012 land, T015, T016 and T017 are three different test files.
- Documentation tasks T008, T022, T023, T025 are all different pages.

## Implementation strategy

**MVP**: Phase 2 + US1 + US2. That is the whole of what the product needs to provision a customer:
a restricted installation and a one-request create for an owner. US3 makes the product's client
honest and US4 makes the second installation possible; both can follow in the same branch, but
neither blocks the first paying customer on a single hub.

Deliver in the order written; each checkpoint is green tests plus a by-hand run from
quickstart.md.
