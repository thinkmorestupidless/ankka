# Research: Deploy from GitHub Actions

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each finding says whether it was verified against this repository or against upstream sources fetched
on 2026-09-25, or is an assumption that a named task settles at implementation. The list at the end
collects the latter.

## R1 — What a machine can do today, and why it is not enough

**Verified in this repository.** `Session.bearer` presents `--token` / `ANKKA_TOKEN` verbatim and
reads no saved login; `docs/deploy/ci.md` and `docs/platform/identity.md` document the machine route:
a confidential Keycloak client with service accounts enabled and the `ankka-controlplane` scope (the
scope carries the subject mapper — without it a token has no `sub`, a trap `CLAUDE.md` records), and
"give its service-account user an email address marked verified" so an owner can invite it.
`InvitationClaim.verifiedEmail` is why: only a principal with `email_verified` claims an invitation.
The other route is `POST /organizations/{id}/members/{subject}/repair`, `authz.requireAdmin`.

**Decision**: the spec's premise holds with the corrected wording (both routes need an installation
administrator in Keycloak's console or the `platform-admin` role; an organization's owner cannot give a
pipeline access on their own), and the Keycloak route stays documented as the alternative for an
installation that wants every principal in its identity provider. `ANKKA_TOKEN` is the delivery
mechanism for the deploy token too: the CLI changes nothing about *presenting* a credential.

**Alternatives considered**: extending `InvitationClaim` to claim by client id rather than email —
still needs the console to create the client. A Keycloak admin credential in the control plane so it
could create clients itself — the design since feature 008 is that it holds none, and the whole point
of the token is to keep it that way.

## R2 — Where a second credential kind plugs in

**Verified in this repository.** `ControlPlane.aclFor(auth)` is one line:
`ControlPlaneAcl.oidc(TokenVerifier.remote(auth), auth)`. `ControlPlaneAcl.oidc` is an
`Acl.Authenticate` reading the `Authorization: Bearer` header and mapping `Verification` to
`AuthDecision`. `TokenVerifier.verify` refuses anything without exactly two dots before any parsing
("shared tokens are no longer accepted; run 'ankka login'"), so a deploy token must be recognised
*before* it reaches the verifier, by inspection of the presented string (FR-006).

**Decision**: `ControlPlaneAcl.composite(index: DeployTokenIndex, oidc: Acl)`: an `Acl.Authenticate`
that reads the bearer once; if it starts with the deploy-token prefix (R4) it is verified against the
index, otherwise the existing OIDC decision is returned unchanged. `ControlPlane.aclFor` builds the
composite; `ControlPlane.builder` registers the index extension. `modules/http` is untouched. A
principal from the token path is `Principal(subject = "token:<id>", name = Some(label), claims =
Map("kind" -> "deploy-token", "organization" -> orgId))` — no email, so `InvitationClaim` never
consults it, and `Principals.display` yields the label for attribution.

**Alternatives considered**: a second `HttpEndpoint` with its own ACL — the routes are the same ones;
two ACLs on one route is not a thing the router offers, by design (one ACL per matched route since
feature 012). Making `TokenVerifier` itself understand both — it is nimbus-specific and offline by
construction; keeping it ignorant of a second format is what keeps its threat model simple.

## R3 — Verifying without I/O: a per-node index, not a consumer

**Verified in this repository.** `Router.handle` calls `admit(...)` on the pekko-http dispatcher
before dispatching to a virtual thread; `Acl.Authenticate`'s function is synchronous. `TokenVerifier`
is offline after the first JWKS fetch for exactly this reason. And — the finding that changes the spec's
Context wording — every ankka `Consumer` and `View` runs as a `ShardedDaemonProcess`: "the cluster
splits the source's id space into slice ranges and hands each range to exactly one node". A consumer
cannot keep an index warm on *every* node, and the ACL runs on whichever node received the request.

**Decision**: `DeployTokenIndex` is a `RuntimeExtension` registered with `ControlPlane.builder`. On
`start` it runs, on *this* node, a Pekko Projection over
`EventSourcedProvider.eventsBySlices[JournalRecord](system, R2dbcReadJournal.Identifier,
"deploy-token", 0, 1023)` — the same source `ProjectionRuntime.eventSource` builds, over the full
slice range rather than a sharded sub-range — with an in-memory offset store, so each node replays the
token journal from the beginning at startup and keeps its own `ConcurrentHashMap[TokenId, Live]`.
`readiness` is `Some(() => caughtUp)`, true once the initial pass has reached the journal's head, so a
pod is never `Ready` with a cold index (spec edge case: "must work on the first request after a
restart"). The token journal is small (tokens, not requests), so the replay is milliseconds.

Revocation and expiry: `DeployTokenRevoked` removes the entry on every node as the stream delivers it;
in addition the endpoint that handled the revoke evicts from its own node's map immediately
(write-through), so on that node the *next* request is refused and on the others the next request
after propagation is. Propagation is the read journal's `behind-current-time` plus stream latency —
`CLAUDE.md` records that tuning `behind-current-time` down made a suite 15× slower, so the delay is
measured, not shortened; the HTTP suite polls up to five seconds and asserts the token is never accepted
again. Expiry needs no event: the map entry carries `expiresAt` and the lookup compares it with the
clock.

Last use (clarification Q3): `index.touch(id)` records today's date in the entry, in memory, at zero
cost. A scheduled task on each node, once a minute, finds entries whose in-memory date is later than
the persisted date it has seen for them and sends `DeployTokenEntity.recordUse(date)` through the
component client — off the request thread. The entity refuses a date not later than the one it holds,
so two nodes racing on the same day produce one event; the event returns through the stream and every
node updates its persisted view. One write per token per day, at most.

**Alternatives considered**: a `Consumer` — runs on one node (above). Cluster pub-sub from the
handling node to the rest — a second delivery mechanism to keep consistent with the journal, and no
answer for a node that started after the message. Distributed Data — replicated state with its own
semantics for the one map the journal already describes. A control-plane-signed JWT verified offline
plus a broadcast revocation list — key management (generation, rotation, storage) for a credential
whose only consumer is the control plane itself, and revocation would *still* need the broadcast; the
journal-fed index is the same offline verification with less machinery. A blocking entity read per
request — forbidden by FR-004 and the reason the constraint exists.

## R4 — The credential: format, generation, digest

**Verified upstream**: GitHub's fine-grained tokens use a recognisable prefix (`github_pat_`) so
secret scanners and servers can classify a string on sight; the value after the prefix is
high-entropy and stored hashed. **Verified in this repository**: `TokenVerifier.verify` treats
*exactly two dots* as "a JWT", so the format must contain no dots.

**Decision**: `ankka_<id>_<secret>`, where `id` is 16 lowercase hex characters (64 bits from
`SecureRandom`, unique enough for a per-installation table and short enough to type) and `secret` is
64 lowercase hex characters (256 bits from `SecureRandom`). Prefix `ankka_` is the classification
(FR-006); hex keeps it free of `.`, `+`, `/` and `=`, so it survives every shell, YAML and `curl -d`
unquoted. Stored as the SHA-256 digest of the *secret* part, hex; compared with
`MessageDigest.isEqual` (constant time). No salt or pepper: the secret has 256 bits of entropy, so a
dictionary or rainbow attack on the digest is not a threat, and a key-derivation function would only
add a tunable cost to every request that FR-004 wants at zero. The digest table is not a password
table.

**Alternatives considered**: base64url — `-` and `_` collide with the separator and a leading `-`
breaks unquoted CLI use. Argon2 or bcrypt — for low-entropy secrets; wrong tool. An HMAC with a server
key — key management for no gain over a plain digest of a random value.

## R5 — The token is an entity, and its subject is a member

**Verified in this repository.** `Authorization.require` answers from
`OrganizationEntity.roleOf(subject)` and nothing else; `Attribution` carries `subject` and `display`
as metadata; `Organization.members` is `Map[String, Member]` keyed by subject; `MemberAdded` exists and
`OrganizationEntity.addMember` writes it, refusing a duplicate subject. `OrganizationRows` keeps
`members`/`owners` for the listing and `Authorization.visible`. `ServiceEvent`s and `Service.history`
record `actor.subject` and `display`.

**Decision**: `DeployTokenEntity` (component id `deploy-token`, entity id = token id) with events
`DeployTokenCreated(organizationId, label, digest, expiresAt, actor, at)`, `DeployTokenUsed(date)`,
`DeployTokenRevoked(actor, at)`; state `DeployToken(id, organizationId, label, digest, createdBy,
createdAt, expiresAt, lastUsed: Option[LocalDate], revoked)`. `DeployTokenRows` view: one row per
live token with everything but the digest, deleted on revoke; queried by `organizationId` for the
listing. The endpoint, on create, issues two commands in this order: `DeployTokenEntity.create`
(the token exists but is a member of nothing — `Authorization.require` answers 404 for it
everywhere), then `OrganizationEntity.addMember(AddMember("token:<id>", Role.Member, display =
Some(label)))`. A failure between the two leaves a harmless token, visible in the listing, that the
owner revokes or ignores. On revoke: `DeployTokenEntity.revoke` first (the index evicts; the ACL now
refuses), then `OrganizationEntity.removeMember` (the organization no longer lists it). Each refusal is
independent, so any partial failure still denies.

`AddMember` gains nothing; `Member.display` is the label and `subject` carries the `token:` prefix, so
`members list` and `whoami`-style listings show a token as what it is with no new field. FR-010's
"cannot manage tokens" falls out of `requireOwner` on the token routes and the token's fixed `member`
role. The 90-day default (clarification Q1) is applied by the endpoint from its clock so the entity's
event carries an absolute `expiresAt`; "never" is `None`.

**Alternatives considered**: tokens as a `Map` on the `Organization` entity — every token verification
would then need the organization's state on every node, and the organization's journal would gain a
write per token per day; a separate entity keeps the organization's fold as it is and the index's
source small. A dedicated `DeployTokenAdded` organization event — nothing downstream would treat it
differently from `MemberAdded`.

## R6 — What the ACL answers for a token

**Verified in this repository.** `AuthDecision` has four cases; `ControlPlaneAcl.oidc` maps a
`Rejected` to `Unauthenticated` with `error="invalid_token"` and the reason in `error_description`;
the CLI turns a 401 with `--token` set into "the token was rejected: <detail>".

**Decision**: unknown id, or digest mismatch → `Unauthenticated(realm, error="invalid_token",
error_description="deploy token not recognised")`. Known, expired → `Unauthenticated(...,
error_description="deploy token expired on <date>")`. Known, revoked but not yet propagated to this
node — cannot happen for a lookup, since a revoked entry is removed; the window is the propagation
delay in R3. Index not caught up (only possible before readiness, which the platform never routes to)
→ `Unavailable("deploy tokens cannot be verified yet on this node")`, a 503 with `Retry-After`. A
valid token → `Allow(principal)` as R2 describes, plus `index.touch(id)`.

## R7 — Registry credentials: a Secret the control plane writes, a name the operator renders

**Verified in this repository.** The operator renders `imagePullPolicy: IfNotPresent` on both pod
shapes and nothing sets `imagePullSecrets` (grepped `crd/`, `operator/`, `kustomization/`, `docs/`).
The control plane's `Fabric8AnkkaServiceClient.ensureNamespace` creates a project's namespace with
server-side apply, so it already writes into project namespaces; its ClusterRole grants `namespaces:
create, patch` for that and nothing on `secrets`. The operator has `secrets: get, create, patch` for
database credentials and — by design — no `list` or `delete`. `AnkkaServiceSpec` is a flat case class
with defaults, decoded by Jackson with unknown fields tolerated (`AnkkaServiceCodecSuite`), so a new
`Option[String]` field is backward compatible in both directions. `LifecycleRules` already prefers a
pod container's waiting reason for `detail` ("ImagePullBackOff: manifest …" is its own example), which
is FR-029's requirement met by existing code, to be confirmed by the k3s case.

**Decision**: registry credentials belong to the **project**, set with
`PUT /projects/{projectId}/registry` `{server, username, password}` and cleared with `DELETE`. The
endpoint (`authz.project(..., write = true)`) writes a Secret named `ankka-registry` of type
`kubernetes.io/dockerconfigjson` into the project's namespace through a new
`AnkkaServiceClient.ensurePullSecret`, creating the namespace first as the projector does, then
records `ProjectEvent.RegistryConfigured(server, username, actor, at)` on the project entity — the
server and username, never the password. `ProjectDetail` gains `registry: Option[RegistrySummary
(server, username, setAt, setBy)]` so `projects get` shows that credentials exist without disclosing
them (FR-025, FR-030). `Projection.projectOne` reads `ProjectEntity.get` before projecting and passes
the registry to `ServiceProjection.project`, which sets `imagePullSecret = Some("ankka-registry")`
when present. The operator renders `.withImagePullSecrets(new LocalObjectReference(name))` on both pod
shapes when the field is present. RBAC: the control plane's ClusterRole gains `secrets: create, patch`
— no `get`, `list`, `delete` — with a comment saying why this widens disclosure by nothing (it can
write a credential and never read one) and power by nothing (it already chooses every workload's
image).

Namespace loss: the platform never deletes a project namespace, but if one is deleted by hand the
Secret goes with it and the project's record says credentials exist that do not. Re-running
`projects registry set` restores them; `services get` shows the pull failure in `detail` meanwhile. A
control plane that cannot reach the cluster refuses `registry set` with a 503 rather than recording a
credential it did not write — the write is synchronous in the handler, on a virtual thread, as
`services logs` already reads pods synchronously.

**Alternatives considered**: the password in the project's journal so the projector can re-create the
Secret — the first secret *value* in the control plane's database, for a recovery case the platform
never causes; rejected. Credentials in the descriptor — per service rather than per project, in a file
that is checked in, and a secret value on the wire and in the journal; rejected. The operator writing
the Secret from a value in the `AnkkaService` spec — the value would be readable by everything that
can read the resource, including the control plane, and it would be in etcd twice; rejected. A user
running `kubectl create secret docker-registry` — contradicts the feature's premise that deploying
needs no cluster credential.

## R8 — `ankka services deploy`, after Akka

**Verified upstream** (doc.akka.io, 2026-09-25): `akka services deploy SERVICE IMAGE [flags]` takes
the image positionally with no descriptor file, every other setting as a flag, and `--push` to the
Akka Container Registry; `akka services apply -f` takes the image from the YAML with no override.
**Verified in this repository**: `Main.scala`'s `apply` reads the descriptor with `Descriptors.read`
(file or stdin) and calls `client.applyService(project, descriptor)`; `ServiceDescriptor.problems`
validates before the round trip; the template's `service.json` says `"<name>:latest"`.

**Decision**: `Opts.subcommand("deploy", ...)` with `Opts.argument[String]("service")`,
`Opts.argument[String]("image")` and `-f/--file` defaulting to `service.json`. It reads the
descriptor, refuses when `descriptor.name != service` ("descriptor names 'x', not 'y'" — US3
scenario 4: the workflow deploys the wrong service or the wrong file and stops), replaces
`service.image` with `ServiceDescriptor.withImage` (a pure function in `controlplane-api`, unit
tested), validates with `problems`, and calls the existing `applyService`. The file on disk is never
written. Output and exit codes are `apply`'s. There is no `--push`, and the help text says why in one
line: ankka runs no registry.

**Alternatives considered**: in the clarification session (Q4) — `--image` on `apply`, a `${VAR}`
placeholder in the descriptor, no image in the descriptor, or `jq` in the workflow.

## R9 — The action: composite, versioned like the formula, released like the template

**Verified in this repository.** The release's `cli` job builds `cli/Universal/packageBin`, attaches
`ankka-cli-<version>.zip` to the tag's GitHub release with `gh release upload`, and computes its
SHA-256 for the Homebrew formula — but attaches no checksum file. The `template`, `marketplace` and
`cli` jobs each `sed` a version into a tracked file, commit locally, `git subtree split --prefix
<dir>` and force-push the split to its own repository, then move the tag. `HomebrewFormulaSuite` pins
the placeholders the `sed` rewrites so a formula it cannot rewrite fails `cli/test`. `docs/deploy/ci.md`
already shows the manual install: `curl` the zip, `unzip`, append `bin` to `$GITHUB_PATH`, needing a
JDK 21. **Verified upstream**: a composite action's `runs.steps` may use `shell: bash`, may read
`inputs.*`, and appends to `$GITHUB_PATH` and `$GITHUB_ENV` for later steps of the *same job*;
`::add-mask::` masks a value in the log for the rest of the job; a `uses:` reference resolves
`owner/repo@ref`, so the action must be a repository root.

**Decision**: `action/action.yml` (composite) with inputs `version` (default `0.0.0`, rewritten at
release so `@v0.5.0` installs CLI 0.5.0), `url` (required), `token` (required), `project` (optional),
`ca` (optional, PEM text). Steps, all `shell: bash` with `set -euo pipefail`: (1) `java -version`
present and major ≥ 21, else fail naming `actions/setup-java` (clarification Q5, FR-015); (2)
download `ankka-cli-$V.zip` and `ankka-cli-$V.zip.sha256` from the release, `sha256sum --check`,
unzip into `$RUNNER_TEMP/ankka-cli`, append its `bin` to `$GITHUB_PATH`; (3) write `ANKKA_URL`,
`ANKKA_PROJECT` (when given) to `$GITHUB_ENV`, `::add-mask::` the token then write `ANKKA_TOKEN`, and
when `ca` is given write it to `$RUNNER_TEMP/ankka-ca.crt` and set `ANKKA_CA`; (4) `ankka whoami -o
json` as the smoke test, so a missing or rejected token fails here with the CLI's own message rather
than in a later step. `GITHUB_ENV` is scoped to the job and cleared with the runner's workspace, which
is what FR-014 allows; nothing is written to the checkout. The release's `cli` job additionally
attaches `ankka-cli-<version>.zip.sha256`, which closes the supply-chain item the clarification
session deferred. A new `action` job in the release: `sed` the version into `action.yml`, commit,
`subtree split --prefix action`, push to `thinkmorestupidless/ankka-action` and move both `vX.Y.Z`
and the major tag `vX`. `ActionSuite` (in `cli`, beside `HomebrewFormulaSuite`) parses `action.yml`
and pins the download URL pattern and the `0.0.0` placeholder.

**Alternatives considered**: a Docker action — a container pull per job for a script; a JavaScript
action — a Node toolchain for `curl` and `unzip`. Installing Java inside the action — rejected in
clarification Q5. A wrapper input per CLI command — rejected by FR-016; the action leaves the CLI on
`PATH` and a step runs whatever it likes.

## R10 — The template's workflows, under Giter8

**Verified in this repository.** `CLAUDE.md`: "Giter8 reads `$` as template syntax, so the skill's
copy in the template is written with every `$` escaped (`\$`)". `TemplateSuite` expands the template
through the real `ankka init` into a temp directory and runs the expansion's own `sbt test` and
`sbt Docker/publishLocal`. The template's `build.sbt` sets no `version` (sbt's default
`0.1.0-SNAPSHOT`) and no `dockerRepository`; the platform's own `dockerSettings` reads
`DOCKER_REPOSITORY` from the environment. `ci.yml` in this repository is the model for the
template's: `actions/checkout`, `actions/setup-java` (temurin 21, `cache: sbt`), `sbt/setup-sbt`,
`sbt test`. **Verified upstream**: the `secrets` context is not available in a job-level `if:` on
every runner generation, so the robust pattern for "skip when a secret is absent" is a first job whose
step tests the secret and sets an output, and a second job with `needs:` and `if:
needs.check.outputs.deploy == 'true'`; GHCR accepts the job's own `GITHUB_TOKEN` with
`packages: write` via `docker/login-action`.

**Decision**: two files, every GitHub expression written `\${{ … }}`:

- `ci.yml`: on `push` and `pull_request`; checkout, `setup-java` 21, `setup-sbt`, `sbt test`. No
  secrets, green on the first push (FR-019).
- `deploy.yml`: on `push` to tags `v*` and `workflow_dispatch`. Job `check` sets `deploy=true` when
  `ANKKA_TOKEN` is non-empty, else prints why it is skipping. Job `deploy` (`needs: check`, the `if`
  above): checkout, `setup-java`, `setup-sbt`, `docker/login-action` to `ghcr.io` with the job's
  token, `SERVICE_VERSION=<tag without v>` and `DOCKER_REPOSITORY=ghcr.io/<owner>` in the
  environment, `sbt Docker/publish`, then `thinkmorestupidless/ankka-action@v<major>` with the
  secrets, then `ankka services deploy <name> ghcr.io/<owner>/<name>:<version>` and `ankka services
  get <name>` (FR-020, FR-021). The three secrets are `ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_PROJECT`
  (URL and project are not secrets, but one screen to fill in is the promise SC-002 makes); `ANKKA_CA`
  optional. The template's `build.sbt` gains `version := sys.env.getOrElse("SERVICE_VERSION",
  "0.1.0-SNAPSHOT")` and `dockerRepository := sys.env.get("DOCKER_REPOSITORY")` (FR-024), and the
  generated README gains a section naming the three secrets and the commands that produce each
  (FR-022). `TemplateSuite` gains a case: the expansion's two workflow files contain `${{` and no
  `\${{`, and parse as YAML (FR-023).

**Alternatives considered**: `sbt-dynver` in the template for the version — a plugin and a
`fetch-depth: 0` for a value the tag already states; the environment variable is one line. A separate
`build.yml` and `test.yml` — one CI workflow is what the repository itself has.

## R11 — Where the Java check comes from

**Verified upstream**: `java -version` prints to stderr, `openjdk version "21.0.2"`; the major is the
first number, or the number after `1.` for the 8-era format. **Decision**: parse that, require ≥ 21,
fail with the exact `actions/setup-java` snippet in the message. Verified at implementation against
`ubuntu-latest`'s preinstalled JDKs.

## R12 — Documentation, and what moves where

**Verified in this repository.** `docs/deploy/ci.md` is the Keycloak-client recipe end to end,
including an example job that installs the CLI by hand. `docs/platform/identity.md` has a "Machine
accounts" section with the same recipe. `docs/concepts/tenancy-and-access.md` describes members,
invitations and attribution. `docs/reference/control-plane-api.md` and `docs/reference/cli.md` carry
generated tables that fail the build until every route and command has a hand-written section.
`docs/reference/limitations.md` says a cluster that pulls "needs a registry configured".

**Decision**: `ci.md` is rewritten around the deploy token and the action — create a token, add three
secrets, tag — with the Keycloak client moved to `identity.md` as "the alternative, for an
installation that wants every principal in its identity provider". `identity.md`'s machine-accounts
section leads with the token. `tenancy-and-access.md` gains "Deploy tokens are members". `images.md`
gains "Private registries". The two reference pages gain sections for `/organizations/{id}/tokens`,
`/projects/{id}/registry`, `organizations tokens *`, `projects registry *`, `services deploy`.
`limitations.md`'s registry line is replaced. `akka-divergences.md` records `services deploy`
without `--push`. A new page is not needed: `ci.md` is the end-to-end page FR-031 asks for.

## R13 — Proving it: which tier proves what

**Verified in this repository.** `ControlPlaneHttpSuite` boots `ControlPlane.components` and
`ControlPlane.endpoints` with `TestIdentity` against a real Postgres; `VerificationOverheadBenchmark`
measures one request twice; `EndToEndClusterSuite` runs the CLI against a real control plane in k3s;
`TemplateSuite` expands the template and runs its build; k3s suites import images into the node's
containerd `k8s.io` namespace.

**Decision** (the tiers in `quickstart.md`): unit suites for the entity folds, the index's fold and
readiness, the secret format and digest, `withImage`, and the action's placeholders; the HTTP suite for
the token lifecycle (create → use → forbidden as owner → revoke → refused within five seconds → never
accepted again; expiry with an injected clock; a token for one organization is a 404 in another; the
secret appears in no listing or JSON) and the registry routes against `FakeAnkkaServiceClient`; the
benchmark for SC-003; the k3s suite for the pull secret — a `registry:2` with `htpasswd` deployed into
the k3s cluster, the sample image pushed to it from the node under this build's version, credentials
registered through the CLI, the service reaching `Ready`, and the same descriptor *without* the
credentials showing the pull failure in `detail`; `TemplateSuite` for the workflows; and a manual tier
that a person runs once against a real GitHub repository, because this repository's CI cannot create
one.

## R14 — Write-through works in both directions, and the create direction was missed

**Found by implementation**, in `ControlPlaneHttpSuite`. The design gave the *revoke* path a
write-through `evict` so that "revoke, then the next call is refused" holds on the node a CLI is
talking to, and left creation to arrive through the journal like any other event. That makes a token
unusable on the very node that minted it until the next read-refresh, which breaks the obvious
script — `ankka organizations tokens create`, then use the secret — and would have been a confusing
`401` a user could not explain.

**Decision**: the create path calls `index.admit(...)` symmetrically. Other nodes still learn from
the journal in both directions; the asymmetry that remains is the safe one, since a node that has
not yet heard of a token refuses it rather than accepting something it cannot check.

**Also found**: the create route answers `200`, not the `201` the contract first specified. Every
route in this API answers `200` or `204`, and `ToResponse` has no way to express a `201` — the spec
had imported a REST convention this codebase does not use. The contract and the API reference now
say `200`.

## R15 — The SC-005 benchmark was not measuring what it claimed

**Found by implementation.** Adding a third arm to `VerificationOverheadBenchmark` made the existing
two-arm assertion fail at +21.9%, then +11.4%. Before changing anything of anyone else's, the
original file was restored from `c8b8fb3` and run unmodified on the same machine: it reported
**-11.96%** and *passed*.

That is the finding. Its assertion is `overhead < 0.05` over a measurement whose run-to-run noise on
this machine is about ±20%, so it passes whenever the noise lands negative and fails whenever it
lands positive — in neither case because of the thing it is supposed to guard. A credential check is
tens of microseconds against a request of roughly six hundred; the signal was always an order of
magnitude below the noise.

**Decision**, in two parts. The harness is made less noisy: one test kit and three servers rather
than a fresh test kit per measurement (nine Postgres containers for three arms over three rounds),
and the arm order rotates per round, because a fixed order moved the bias from whichever arm ran
first to whichever ran last — that is what reported the cheapest check as making a request 21%
*faster* than doing nothing. And the assertion becomes an absolute bound, 50%, chosen for the
regression that matters: an ACL that reads a row adds a millisecond or more, which is an order of
magnitude above the noise, while every arithmetic check is far below it. The printed numbers are the
measurement; the assertion is the alarm.

This is feature 007's lesson arriving from a new direction. That one was about choosing a
denominator that is the thing the criterion names. This one is about a *ratio of two noisy
measurements* being a worse instrument than an absolute bound when the signal is small — and about a
one-sided threshold hiding it.

## Verify at implementation

- **V1 — ANSWERED, and better than the fallback.** `R2dbcReadJournal` offers *two* slice queries:
  `currentEventsBySlices`, which is bounded and **completes**, and `eventsBySlices`, which is live.
  So the index needs no count and no offset store: on start it runs the bounded query from
  `NoOffset` to completion — that completion *is* the caught-up signal `readiness` reports — and
  then runs the live query from that query's last offset. Measured in `IndexProjectionSpike`: a cold
  replay delivered the 3 events then in the journal and completed; a second cold replay delivered
  all 5; resuming from the first replay's last offset delivered only the 2 new ones. (R3)
- **V2 — ANSWERED, and it contradicts the spec's first wording.** Persist → seen by a live
  `eventsBySlices` stream, 200 samples on a laptop against the test kit's Postgres: **p50 3,021 ms,
  p99 3,048 ms, max 3,056 ms**. That is not `behind-current-time` (100ms, the correctness guard
  `CLAUDE.md` says not to tune) — it is `pekko.persistence.r2dbc.refresh-interval`, whose default is
  exactly `3s`: the live query *polls*, and an event lands one poll after it is written. The spec's
  "well under a second" is wrong as measured.

  Two consequences. The **revoking node** is unaffected: the endpoint evicts its own map
  write-through, so a `DeployTokenRevoked` is refused there on the very next request, which is the
  whole of the single-instance case and of any CLI revoke followed by a CLI call. **Other nodes**
  see it one poll later. `refresh-interval` is an ordinary polling knob, not the guard, so shortening
  it for the control plane is available and cheap — its journal is tenancy changes, not traffic — at
  the cost of more queries from every live stream it runs (the index and the three views). See the
  decision recorded in the clarification session. The HTTP suite's bound follows the setting chosen,
  with headroom for the test's own scheduling. (R3)
- **V3** — the `secrets` context in a job-level `if:` on current GitHub-hosted runners; the two-job
  pattern is used regardless, so this only decides whether the comment can say "could be one job". (R10)
- **V4** — `docker/login-action` to `ghcr.io` with `GITHUB_TOKEN` and `packages: write` from a
  tag-triggered workflow in a *private* repository creates a private package by default. (R10, R7)
- **V5** — `LifecycleRules` surfaces `ErrImagePull` / `ImagePullBackOff` from a private registry as
  the service's `detail` with no change; if it does not, the k3s case will say so. (R7)
- **V6 — ANSWERED: no hint needed.** `imagePullSecret: Option[String] = None` round-trips and
  decodes from a sparse document with no `@JsonDeserialize(contentAs = …)`. The hint was needed for
  `Option[Int]` because it erases to `Option[Object]` and Jackson takes the boxed type from the
  JSON; a `String` is not boxed. All 24 cases in `AnkkaServiceCodecSuite` pass with the field in the
  fully-populated spec. (R7)
- **V7 — ANSWERED.** `action/scripts/java-major.sh` takes the first quoted field of `java -version`'s
  first stderr line and prints the leading number, or the number after `1.` for the 8-era format.
  Verified against Temurin 21 (`21`), Temurin 17 (`17`), Zulu 11 (`11`), Oracle `1.8.0_392` (`8`),
  early access `25-ea` (`25`), and no `java` on `PATH` (empty). (R11)
- **V8** — server-side apply of a `kubernetes.io/dockerconfigjson` Secret with only `create` and
  `patch` granted, against the k3s API server: a PATCH that creates must not need `get`. (R7)
