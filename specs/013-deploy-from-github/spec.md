# Feature Specification: Deploy from GitHub Actions

**Feature Branch**: `013-deploy-from-github`

**Created**: 2026-09-25

**Status**: Draft

**Input**: User description: "i'd like to look at the ankka.g8 template — this currently provides the
basic scala project template — i'd like to add github actions to this so that a project created with
it can be committed to github and immediately be able to be built and managed via the cli in a github
action. I'm thinking for this we can create a github actions tool that can be referenced in the
template that actually does the work of downloading the cli, authenticating the user and the exposing
the cli commands for building images, pushing images to a registry and for deploying a service from
the project to an ankka cluster"

## Context

`ankka init` produces a project that builds, tests and runs on a laptop, and stops there. Getting it
into a cluster is a sequence a person performs by hand: build the image, get it somewhere the cluster
can pull from, `ankka login` in a browser, `ankka services apply -f service.json`. Every one of those
steps is scriptable, and none of them is scripted.

Three of the four pieces already exist and need only assembling. `ANKKA_TOKEN` is documented as the
way a CI job authenticates — the CLI presents it exactly as given, reads no saved login and writes
none. The release workflow already attaches `ankka-cli-<version>.zip` to a GitHub release, so
installing the CLI on a runner is a download and an unzip, needing nothing but a JDK 21. A descriptor's
`image` is already allowed to be a fully-qualified registry reference.

The fourth piece does not exist, and it is the one everything turns on: **there is no credential a
machine can hold.** `ankka login` is an OAuth 2.0 device grant — a person, a browser and a code. A
Keycloak service account cannot simply stand in for that person, because membership of an organization
begins when an invitation is claimed and `InvitationClaim` claims only for a *verified email*, which a
service account has none of unless someone gives it one. The two ways in today both need an
installation administrator: the documented one (`docs/deploy/ci.md`) has them create a confidential
client in Keycloak's console, attach the `ankka-controlplane` scope, and put a verified email on the
client's service-account user so an owner can invite it; the other is `ankka organizations members
repair --subject`, which only a platform administrator may run. A CI credential that requires an
installation administrator to intervene is not a CI credential, and an organization's owner should be
able to give a pipeline access to their own organization without asking anyone.

So the substance of this feature is a **deploy token**: a credential the control plane issues itself,
scoped to an organization, revocable, and attributable. The design falls out of what is already there
if one decision is made — *a deploy token's subject is an ordinary member of the organization*. Its
principal carries `subject = "token:<id>"`, it is recorded in the organization's members with the
`member` role, and from that moment every membership check, every attribution, every "what you cannot
see does not exist" 404 works unchanged, because none of them has ever cared how a subject was
authenticated.

One constraint shapes the implementation and is easy to get wrong. **The ACL cannot perform I/O.**
`Acl.Authenticate` is a synchronous function running on the server's own dispatcher, not on a handler's
virtual thread, and the OIDC path is deliberately offline: a signature checked against a cached JWKS.
A deploy token verified by reading a row would block a dispatcher thread on every request. The token
index must therefore be kept warm in memory on *every* node — each one replaying the token journal for
itself and then following it, the way the JWKS cache is kept warm — because the ACL runs on whichever
node took the request. It cannot be a `Consumer`, which the platform runs on one node only. The
consequence is a pleasant one: revocation reaches a node as an event rather than when a cache expires,
so the node that revoked refuses immediately and the rest follow within the refresh interval.

The second gap is narrower but will be met on the first run. The operator renders `imagePullPolicy:
IfNotPresent` and nothing anywhere supports `imagePullSecrets`, so a cluster can pull a *public* image
and nothing else. A GitHub Container Registry package created by a workflow is private by default. A
feature whose promise is "commit it and it deploys" cannot rest on the user first making their image
public.

What ties it together is a **composite GitHub Action**, held in this repository and pushed to its own
repository by the release workflow — the same arrangement as `ankka.g8`, `marketplace/` and
`homebrew/`, and for the same reason: a workflow can only reference an action as `owner/repo@ref`.

## Clarifications

### Session 2026-09-25

- Q: Should a deploy token expire? → A: Optional with a default of 90 days; a token can be made non-expiring explicitly, and the listing always shows which.
- Q: Which roles may a deploy token hold? → A: Member only. `owner` is never offerable, so a token can neither manage members nor delete the organization, and the last-owner rule needs no special case.
- Q: How precise must a token's recorded last use be, given that admission may not perform I/O on the request's thread? → A: Day-granular — held in memory and persisted only when the recorded day changes, so the listing shows the date of last use rather than the instant.
- Q: How does the image a workflow just pushed reach the descriptor it applies? → A: Mirror Akka's verb: `ankka services deploy <service> <image> [-f descriptor]`, the image positional as in Akka and every other setting from the descriptor. The CLI replaces that one field, validates the result as it validates any descriptor, and the checked-in file is never rewritten.
- Q: Revocation reaches other control-plane nodes one poll interval after the write — measured p50 3,021ms, because `pekko.persistence.r2dbc.refresh-interval` defaults to 3s. How should the window be set? → A: Shorten it for the control plane to 500ms, giving a sub-second window. It is the polling knob, not `behind-current-time`; the control plane's journal is tenancy changes, not traffic, so polling six times more often costs it nothing that matters.
- Q: SC-001 promised an *exposed* service reachable over HTTPS, but nothing deploys a route. Should the generated workflow run `ankka services expose`? → A: No. Making a service publicly reachable is a decision, not a build step. The criterion is narrowed to `Ready`; exposure is one `ankka services expose` a person runs once, and it survives every later deploy because `onApplied` does not touch the flag.
- Q: Whose job is the Java runtime the CLI needs? → A: The caller's. The action requires Java 21 or later on `PATH`, checks for it, and fails naming `actions/setup-java` when it is absent; it installs no runtime of its own. The template's workflows include the `setup-java` step.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A credential a machine can hold (Priority: P1)

An owner of an organization creates a deploy token, scoped to that organization, and is shown the
secret once. They put it in a GitHub secret. Nothing about the installation had to be administered,
no identity-provider client was created, and no platform administrator was involved.

**Why this priority**: Everything else in this feature is blocked on it, and it is the only part that
cannot be worked around. It is also useful on its own: any script, not only a GitHub workflow, can
hold one.

**Independent Test**: Create a token with the CLI, use it as `ANKKA_TOKEN` for `ankka services list`
against a real control plane, then revoke it and watch the next call be refused.

**Acceptance Scenarios**:

1. **Given** an owner of an organization, **When** they create a deploy token, **Then** the secret is
   printed once and never again, and a listing shows the token's label, who created it, when, and the
   date it was last used — but not the secret.
2. **Given** a deploy token's secret in `ANKKA_TOKEN`, **When** any management command runs, **Then** it
   is authorized exactly as a member would be, and the change is attributed to the token rather than to
   the person who created it.
3. **Given** a deploy token, **When** it attempts anything reserved to owners — inviting a member,
   renaming or deleting the organization, or managing deploy tokens — **Then** it is refused.
4. **Given** a deploy token, **When** it is revoked, **Then** the very next request presenting it to
   the node that revoked it is refused; every other node refuses it within a second, the platform's
   configured read-refresh interval rather than a cache's lifetime; and it is never accepted again.
5. **Given** a deploy token for one organization, **When** it is presented for another organization's
   resources, **Then** the answer is `404`, as it is for any non-member.
6. **Given** a request presenting a deploy token, **When** the control plane decides admission, **Then**
   it performs no database read and no network call on the request's thread.
7. **Given** a token created with no lifetime stated, **When** 90 days have passed, **Then** it is
   refused without anyone having revoked it; **and given** a token created as non-expiring, **When**
   the same time passes, **Then** it still works and the listing says it never expires.

---

### User Story 2 - A workflow can install and drive the CLI (Priority: P2)

A workflow author adds one step to a job. It installs a pinned version of the CLI, points it at their
control plane, authenticates with a deploy token, and leaves the CLI ready for any command the job
wants to run — including `ankka services deploy`.

**Why this priority**: It is the piece the request names, and once US1 exists it can be built and
proven on its own, against any repository, without the template changing at all.

**Independent Test**: A workflow in a scratch repository uses the action and runs `ankka whoami` and
`ankka services list` against a control plane, with only a URL and a token configured.

**Acceptance Scenarios**:

1. **Given** the action with a version and a token, **When** a job uses it, **Then** the CLI is on
   `PATH` for every later step in that job and reports the requested version.
2. **Given** the action, **When** a later step runs any `ankka` command, **Then** it is already
   authenticated and pointed at the configured control plane, with no `ankka login`.
3. **Given** an installation with a private certificate authority, **When** its root is supplied to the
   action, **Then** commands against that control plane succeed.
4. **Given** a token that is absent or invalid, **When** the action runs, **Then** it fails with a
   message naming what is wrong, rather than leaving a later step to fail obscurely.
5. **Given** the action pinned to a version, **When** that version's CLI cannot be fetched, **Then**
   the failure says so plainly and names the version it looked for.
6. **Given** a runner with no Java 21 on `PATH`, **When** the action runs, **Then** it fails before
   downloading anything, and the message names `actions/setup-java`.

---

### User Story 3 - A generated project ships with workflows that work (Priority: P3)

Someone runs `ankka init`, pushes the result to GitHub, and the project builds and tests itself on
every push and pull request with no configuration at all. When they are ready, they add three secrets
and tag a release, and the same project builds an image, pushes it and deploys itself.

**Why this priority**: It is the outcome the request asks for, and it is the story that turns the other
three into something a person receives rather than assembles.

**Independent Test**: Expand the template, push it to a repository with no secrets, and see a green
build; then add the secrets, tag, and see the service reach `Ready` in a cluster.

**Acceptance Scenarios**:

1. **Given** a freshly generated project pushed to GitHub with no secrets configured, **When** the
   workflows run, **Then** the build and test workflow passes and the deploy workflow does not fail —
   a first push never shows a red cross for a step the author has not reached yet.
2. **Given** the deploy secrets are configured, **When** a version tag is pushed, **Then** the image is
   built and pushed, the descriptor's image reference is the one just pushed, and the service is applied
   and reported.
3. **Given** the deploy workflow, **When** it is triggered by hand, **Then** it does the same thing for
   the commit it was run on.
4. **Given** the generated project, **When** its descriptor and its build disagree about the image,
   **Then** the workflow fails before deploying rather than deploying the wrong image.
5. **Given** the template's workflow files, **When** the template is expanded, **Then** every `$` that
   belongs to GitHub Actions rather than to Giter8 survives expansion intact.

---

### User Story 4 - A service can pull from a private registry (Priority: P4)

A project's images live in a private registry. Its owner registers the registry's credentials once for
the project, and every service in that project can pull from it.

**Why this priority**: Without it, US3's promise holds only for a public package, which is not the
default a GitHub user gets. It is last because it is separable — a public registry proves the whole
path end to end — and because it touches the descriptor, the custom resource and the operator, none of
which the rest of this feature needs.

**Independent Test**: Push an image to a private registry, register the credentials for a project,
apply a service naming that image, and watch it reach `Ready`.

**Acceptance Scenarios**:

1. **Given** registry credentials registered for a project, **When** a service in it is applied naming
   an image in that registry, **Then** the pod pulls it and becomes `Ready`.
2. **Given** no registry credentials, **When** a service names a public image, **Then** it behaves
   exactly as it does today.
3. **Given** a service that cannot pull its image, **When** its status is read, **Then** the reason
   says so, rather than reporting a generic failure to become ready.
4. **Given** registered credentials, **When** they are read back, **Then** the password is not
   disclosed.

---

### Edge Cases

- **A deploy token presented to a control plane that has restarted.** The warm index is rebuilt from
  the journal on startup; a token must work on the first request after a restart, not after a delay.
- **A deploy token whose organization is disabled or deleted.** It is refused exactly as a person's
  token would be.
- **A token that expires between one deploy and the next.** It is refused like any other expired
  credential, and nothing warns anyone beforehand — the listing is the only place an approaching expiry
  is visible. That is a consequence of defaulting to an expiry, accepted deliberately.
- **A token secret in a log.** No command, error message or listing may print a secret after creation,
  including in JSON output and including on failure.
- **Two tokens with the same label.** Labels are for people; the id is the identity.
- **An image tag that already exists in the registry.** Whether a deploy overwrites it is the
  registry's business, but the workflow must not silently deploy an older image of the same tag.
- **A pull secret for a registry the image does not come from.** It is unused, not an error.
- **The generated project's first push.** No secret exists, so the deploy workflow must decline to run
  rather than start and fail.

## Requirements *(mandatory)*

### Functional Requirements

**Deploy tokens**

- **FR-001**: An owner of an organization MUST be able to create a deploy token scoped to it, with a
  human label, and MUST be shown the secret exactly once. A deploy token always holds the `member`
  role; `owner` MUST NOT be offerable.
- **FR-002**: The control plane MUST store only a one-way transformation of the secret; a read of its
  storage MUST NOT yield a usable credential.
- **FR-003**: A request presenting a deploy token MUST be authorized by the same membership rules as a
  person's request, from the same organization state.
- **FR-004**: Admission MUST NOT perform a database read or a network call on the request's thread,
  matching the existing offline verification of identity-provider tokens.
- **FR-005**: Revocation MUST take effect on the next request that presents the token to the node
  that revoked it, and on every other node within one second. The delay is the platform's read-refresh
  interval — a propagation delay, not a cache expiry — and the tests MUST measure it rather than
  assume it.
- **FR-006**: A deploy token MUST be distinguishable from an identity-provider token by inspection of
  the presented credential alone, so the control plane never has to guess which it is holding.
- **FR-007**: Every change made with a deploy token MUST be attributed to that token, distinguishably
  from the person who created it.
- **FR-008**: A listing MUST show each token's label, creator, creation time, expiry, and the **date**
  it was last used, and MUST NOT show the secret. Last use MUST be recorded off the request's thread and
  persisted only when the recorded date changes, so that an authenticated request costs at most one
  write per token per day and none at all on the thread that admitted it.
- **FR-009**: A token MUST expire 90 days after creation unless a different lifetime, or none at all,
  is chosen at creation. An expired token MUST be refused with no action by anyone, and a listing MUST
  make clear which tokens expire and when, and which never will.
- **FR-010**: A deploy token MUST NOT be able to create, revoke or list deploy tokens. Because it is
  never an owner, it also cannot invite, remove or re-role members, rename the organization or delete
  it, and can never be the owner an organization is required to retain.
- **FR-011**: The CLI MUST offer creation, listing and revocation, and MUST NOT print a secret except
  at creation.

**The GitHub Action**

- **FR-012**: The action MUST install a caller-specified version of the CLI and put it on `PATH` for
  the rest of the job. It MUST NOT install a Java runtime: it requires Java 21 or later already on
  `PATH`, checks for it before anything else, and fails naming `actions/setup-java` when none is found.
- **FR-013**: The action MUST configure the control plane URL, the token and, where given, a
  certificate authority root, such that later steps run authenticated commands with no further setup.
- **FR-014**: The action MUST NOT write a token into any file that outlives the job, nor echo it.
- **FR-015**: The action MUST fail with a diagnostic naming the cause when Java is missing or too old,
  the version cannot be fetched, the token is absent, or the control plane cannot be reached.
- **FR-016**: The action MUST be usable for any CLI command, not only deployment, so a job can list,
  pause, expose or read history without the action growing a wrapper per command.
- **FR-017**: The action MUST live in this repository and be published to its own repository by the
  release workflow, with its version written at release time, as the template, marketplace and formula
  are.
- **FR-018**: The action MUST run on a standard GitHub-hosted Linux runner with no privileged
  configuration.

**The template's workflows**

- **FR-019**: A generated project MUST include a workflow that builds and tests it on push and pull
  request, requiring no secrets.
- **FR-020**: A generated project MUST include a deploy workflow triggered by a version tag and by
  manual dispatch, which MUST decline to run rather than fail when its secrets are absent. Both
  workflows MUST set up Java with `actions/setup-java`, since the action provides none.
- **FR-021**: The deploy workflow MUST build the project's image, push it to a registry, and deploy the
  service with `ankka services deploy`, passing exactly the image reference it pushed; it MUST NOT
  rewrite `service.json` to do so.
- **FR-022**: The generated project MUST document what secrets are required and how to obtain each.
- **FR-023**: Every `$` in the template's workflow files that belongs to GitHub Actions MUST survive
  Giter8 expansion, and the template's own test MUST cover the expanded workflows.
- **FR-024**: The template's build MUST be able to name a registry for its image without editing the
  build file by hand.
- **FR-025**: The CLI MUST offer `ankka services deploy <service> <image> [-f descriptor]`: the image
  taken from the command line as Akka's command takes it, every other setting taken from the descriptor
  (`service.json` by default), the one field replaced, and the result validated exactly as
  `services apply` validates a descriptor before anything is sent. The descriptor on disk MUST NOT be
  modified. Akka's `--push` has no counterpart, because ankka runs no registry.

**Private registries**

- **FR-026**: A project MUST be able to carry registry credentials, set and replaced through the CLI,
  and the password MUST NOT be readable back.
- **FR-027**: A service whose project has registry credentials MUST be able to pull from that registry.
- **FR-028**: A service with no registry credentials MUST behave exactly as it does today.
- **FR-029**: A service that cannot pull its image MUST report that as the reason.

**Documentation**

- **FR-030**: The deploy token MUST be documented in the tenancy and access concept page, the CLI
  reference and the control plane API reference.
- **FR-031**: A page MUST describe deploying from GitHub Actions end to end, from `ankka init` to a
  running service, with the samples taken from the template's own tested files.
- **FR-032**: The limitations page MUST be updated where it describes what the platform does not
  provide for services, and the Akka divergences page where relevant.

### Key Entities

- **Deploy token**: a credential belonging to an organization, always holding the `member` role.
  Carries an id, a label, a
  one-way transformation of its secret, its creator, its creation time, the date it was last used, and
  an expiry —
  90 days from creation by default, a different lifetime if one was chosen, or none where the creator
  deliberately asked for a token that never expires. Its id is also the subject it authenticates as.
- **Token index**: the in-memory projection of live tokens that admission consults, maintained from the
  token entity's events so that it is warm, correct after a restart, and immediately current on
  revocation.
- **Registry credentials**: a server, a username and a secret, held per project, never read back.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A person with an organization can go from `ankka init` to a service reported `Ready`
  in their cluster, having touched the cluster only through GitHub, and without any platform
  administrator involvement. Making it reachable from outside is deliberately *not* part of this:
  `ankka services expose` is a decision its owner takes once, and it survives every later deploy.
- **SC-002**: Creating a deploy token, deploying with it and revoking it takes three CLI commands and
  three GitHub secrets.
- **SC-003**: Admission of a request presenting a deploy token costs no I/O, demonstrated by the same
  kind of measurement already used for token verification overhead.
- **SC-004**: A revoked token is refused on the next request by the node that revoked it and within
  one second everywhere, demonstrated against a real control plane.
- **SC-005**: A freshly generated project pushed to GitHub with no secrets shows a passing build and no
  failing workflow.
- **SC-006**: A service deploys from a private registry and reaches `Ready`.
- **SC-007**: No secret appears in any command's output, in either output format, at any point after
  creation.
- **SC-008**: `sbt -Dankka.cluster.tests=off test`, the cluster suites, the Python SDK's checks and
  `just docs` all pass.

## Assumptions

- A deploy token authenticates as an ordinary member subject, so no authorization rule, attribution
  path or visibility rule needs a second code path for machines.
- The control plane continues to hold no identity-provider administrative credential; a deploy token is
  issued and verified by the control plane alone.
- Organization-level scope is enough for this feature. A token narrowed to a single project or service
  is a refinement that the role and membership model can carry later without a different shape.
- The action is a composite action running shell steps; it builds no container image of its own, so a
  job pays no image pull for it, and it installs no Java runtime — the caller's `actions/setup-java`
  step provides one, as it does for every other JVM tool on a GitHub runner.
- The template's deploy workflow targets the GitHub Container Registry by default, because it is the
  registry a GitHub user already has, while allowing any other.
- Runners are GitHub-hosted Linux. Self-hosted and other platforms are expected to work but are not
  proven by this feature.

## Out of Scope

- **Tokens scoped to a project or a single service.** Organization scope at the `member` role is the
  whole of this feature; narrower scopes are a later refinement.
- **Any identity-provider change.** No Keycloak client, realm role or mapper is added, and the
  service-account route into an organization stays as it is: available to a platform administrator
  through `organizations members repair`, and not the recommended path.
- **CI systems other than GitHub Actions.** The token is general and usable anywhere; only the action
  and the template's workflows are GitHub-specific, and no equivalent is built for anything else.
- **Building the image inside the action.** Image building is `docker/build-push-action` or an `sbt`
  invocation the project already has; wrapping it would hide a well-understood step behind an
  ankka-specific one.
- **Deployment environments, approvals and promotion.** One workflow deploys one service to one
  installation. Multi-environment promotion is a workflow-authoring concern, not a platform one.
- **Rotating a deploy token in place.** Creating a new token and revoking the old one is the rotation,
  and it is one more command than a rotation endpoint would be.
- **A registry the platform runs.** Credentials for a registry someone else runs, nothing more — so
  there is no counterpart to Akka's `--push`, and the workflow pushes with the registry's own tooling.
