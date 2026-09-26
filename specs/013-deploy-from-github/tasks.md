# Tasks: Deploy from GitHub Actions

**Input**: Design documents from `/specs/013-deploy-from-github/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included. Every previous feature's proof is its suites, the spec's success criteria name
what must be shown against a real control plane and a real cluster, and each contract ends with the
suites that pin it. Where a task says "case", it means a `test(...)` in the named suite.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a credential a machine can hold), US2 (a workflow can install and drive the
  CLI), US3 (a generated project ships with workflows that work), US4 (a service can pull from a
  private registry)

Paths are repository-relative. Abbreviations: `API` =
`controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api`, `APIT` its test
twin; `CP` = `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane`, `CPT` its
test twin; `CLI` = `cli/src/main/scala/com/thinkmorestupidless/ankka/cli`, `CLIT` its test twin;
`CRD` = `crd/src/main/scala/com/thinkmorestupidless/ankka/crd`; `OP` =
`operator/src/main/scala/com/thinkmorestupidless/ankka/operator`, `OPT` its test twin; `G8` =
`ankka.g8/src/main/g8`; `K` = `kustomization/components`.

---

## Phase 1: Setup — verify first (research "verify at implementation" V1, V2, V6, V7)

**Purpose**: settle the assumptions the token index and the pull secret rest on before building on
them. Each spike's *answer*, recorded in [research.md](./research.md), is the deliverable; the code
is throwaway unless it becomes the suite named.

- [X] T001 Spike V1 in `CPT/IndexProjectionSpike.scala` (gated `-Dankka.spikes=on`): against `AnkkaTestKit`, run a Pekko Projection over `EventSourcedProvider.eventsBySlices` for `OrganizationEntity` across slices 0–1023 with an in-memory offset store and a handler that appends to a list; persist three events before starting it and two after; confirm all five arrive in order and that a second start replays from the beginning. Find the "caught up" signal: whether the r2dbc source exposes one, or whether readiness must be "delivered the count the journal held at start" — record which in research V1. Delete the spike or keep it as the seed of T013's suite.
- [X] T002 Spike V2, in the same harness: persist an event and measure the time until the projection's handler sees it, 200 samples, p50 and p99, under the default `behind-current-time`; record the numbers in research V2. The five-second bound in `contracts/deploy-tokens.md` is confirmed or replaced by what was measured.
- [X] T003 [P] Spike V6 in `crd/src/test/scala/com/thinkmorestupidless/ankka/crd/AnkkaServiceCodecSuite.scala`: add `imagePullSecret: Option[String] = None` to `CRD/AnkkaService.scala` temporarily and confirm "a fully populated spec round-trips" and "a spec missing fields decodes to their defaults" pass without a `@JsonDeserialize(contentAs = …)` hint; record in research V6 whether the hint is needed. Revert the field (T038 adds it for real) or leave it if the answer is clean.
- [X] T004 [P] Spike V7 as a shell script under `action/scripts/java-major.sh`: given `java -version` output on stderr, print the major; test it by hand against `openjdk version "21.0.2"`, `openjdk version "17.0.9"`, `java version "1.8.0_392"`, and a missing `java`; record in research V7. The script is kept and inlined into T023.

**Checkpoint**: V1, V2, V6, V7 recorded. If V1 offers no caught-up signal, T013 counts; if V2's
p99 is over a second, the HTTP suite's bound in T017 is raised to what was measured and the spec's
S1.4 wording is checked against it.

---

## Phase 2: Foundational

**Purpose**: the one artifact every later phase fetches, and the two helpers every new suite uses.

- [X] T005 In `.github/workflows/release.yml`'s `cli` job, after `gh release upload`, write `ankka-cli-$version.zip.sha256` with `sha256sum "$zip" > "$zip.sha256"` (the bare `<hash>  <filename>` line `sha256sum --check` reads) and upload it with `--clobber` beside the zip; the formula's `SHA256` line keeps reading the same value.
- [X] T006 [P] Add `MutableClock` to `CPT/TestIdentity.scala` and a `clock` on `TestIdentity`, and give `ControlPlane.endpoints` a `clock` parameter it hands to all four endpoints (each already takes one; `endpoints` did not, so a suite could not set them together) so T017 can expire a token. **The fake client's half moved to T041**: `ensurePullSecret` cannot be added to `FakeAnkkaServiceClient` before the trait declares it, and the trait is T041's.

**Checkpoint**: `sbt controlPlane/Test/compile` passes; the release workflow is unchanged in
behaviour but for the extra asset.

---

## Phase 3: User Story 1 — A credential a machine can hold (P1) 🎯 MVP

**Goal**: an owner creates a deploy token, sees the secret once, and any script holding it acts as a
member of that organization; revocation and expiry are refused without I/O on the request thread.

**Independent Test**: `ankka organizations tokens create acme --label ci` → the secret in
`ANKKA_TOKEN` → `ankka services list -p checkout` succeeds → `tokens revoke` → the next call is
refused. Tiers 1, 2 and 4 of [quickstart.md](./quickstart.md).

### The credential and the entity (contracts/deploy-tokens.md, data-model.md)

- [X] T007 [US1] Create `CP/auth/DeployTokens.scala`: `Prefix = "ankka_"`; `mint(): Minted(id: String, secret: String, presented: String)` with 16 and 64 lowercase hex characters from `SecureRandom`; `digest(secret): String` (SHA-256, hex); `parse(presented): Option[(id, secret)]` matching `ankka_[0-9a-f]{16}_[0-9a-f]{64}` exactly; `matches(digest, secret): Boolean` via `MessageDigest.isEqual`. Write `CPT/DeployTokensSuite.scala`: the format, digest round-trip, one changed character fails, `parse` refuses a JWT-shaped string and a bare `ankka_`.
- [X] T008 [P] [US1] In `CP/domain/model.scala` add `DeployToken(id, organizationId, label, digest, createdBy: Option[Actor], createdAt: Option[Instant], expiresAt: Option[Instant], lastUsed: Option[LocalDate], revoked)` with `exists`, `expired(now)`, `subject`, and folds `onCreated`, `onUsed`, `onRevoked`; a `DeployToken.subjectOf(id) = s"token:$id"`. In `CP/domain/events.scala` add `enum DeployTokenEvent { DeployTokenCreated(organizationId, label, digest, expiresAt, actor, at); DeployTokenUsed(date: LocalDate); DeployTokenRevoked(actor, at) }`, `CreateToken(organizationId, label, digest, expiresAt)`, `DeployTokenDetail(...)` (everything but the digest), and a `given JsonValueCodec[LocalDate]` writing ISO-8601 in a companion so it is in scope wherever the date appears.
- [X] T009 [US1] Create `CP/application/DeployTokenEntity.scala` (component id `deploy-token`): `create` refusing an existing or revoked id; `recordUse(date)` replying `Done` without an event when `date` is not after `lastUsed`; `revoke` refusing a non-existent token with `404`; `get`. Serializers for the command and reply types. Add cases to `CPT/TenancyEntitySuite.scala` for each refusal, the once-per-date rule, and that a revoked id cannot be recreated; add `DeployTokenEvent` to `CPT/EventCompatibilitySuite.scala`'s pinned JSON.
- [X] T010 [P] [US1] Create `CP/application/DeployTokenRows.scala`: `DeployTokenRow(id, organizationId, label, createdBy: Option[String], createdAt, expiresAt, lastUsed)`; `Created` writes the row, `Used` updates `lastUsed`, `Revoked` deletes it; view `deploy-token-rows` over `ChangeSource.eventsOf(DeployTokenEntity)`.

### The index and the ACL (research R2, R3, R6)

- [X] T011 [US1] Create `CP/auth/DeployTokenIndex.scala`, a `RuntimeExtension` named `deploy-tokens`: on `start` run the full-range `eventsBySlices` projection from T001 over `deploy-token` with an in-memory offset, folding into `ConcurrentHashMap[String, Live(digest, organizationId, label, expiresAt, persistedLastUsed, touched: AtomicReference[Option[LocalDate]])]`; `caughtUp` per V1; `readiness = Some(() => caughtUp)`; `lookup(id): Option[Live]`; `touch(id)`; `evict(id)` for the write-through revoke; `stop()` cancels the stream. Add the once-a-minute last-use task using `system.scheduler` and the service's component client, sending `DeployTokenEntity.recordUse` for entries where `touched > persistedLastUsed` — never on the request thread. Expose `fold(map, event)` as a pure function.
- [X] T012 [US1] Write `CPT/DeployTokenIndexSuite.scala`: `fold` over the three events; `readiness` false until `caughtUp`; the last-use rule issues one command for a token touched today and none for one already persisted today (drive the task's body directly with a recording client); plus a case pinning that this module's `reference.conf` really does set `refresh-interval` to 500ms, since reference files are merged rather than layered and writing it down is not evidence. **The AnkkaTestKit case is not duplicated here**: `IndexProjectionSpike` proves the replay against a real journal and T017 proves a created token works end to end, which is the same claim with a caller attached.
- [X] T013 [US1] In `CP/api/ControlPlaneAcl.scala` add `composite(index: DeployTokenIndex, oidc: Acl, config: AuthConfig): Acl` — read the bearer once; `DeployTokens.parse` → the decision table in [contracts/deploy-tokens.md](./contracts/deploy-tokens.md#what-the-acl-decides), with `Principal("token:<id>", name = Some(label), claims = Map("kind" -> "deploy-token", "organization" -> orgId))` and `index.touch` on `Allow`; a bearer that starts with `ankka_` but does not parse is `401 error_description="not a deploy token"`; anything else falls through to `oidc`. In `CP/ControlPlane.scala`: `components` gains `DeployTokenEntity.descriptor` and `DeployTokenRows.descriptor`; `builder` registers the index extension and passes it to `aclFor`; `aclFor(auth, index)` builds the composite. Add cases to `CPT/ConsoleAclSuite.scala` or a new `DeployTokenAclSuite.scala` for every row of the decision table using a hand-built index.

### The routes, the wire types and the CLI

- [X] T014 [P] [US1] In `API/descriptors.scala` add `CreateDeployToken(label, expiresIn: Option[Long])`, `DeployTokenCreated(id, label, secret, subject, expiresAt)`, `DeployTokenSummary(id, label, subject, createdBy, createdAt, expiresAt, lastUsed: Option[LocalDate])` and their codecs in `Wire` (with the `LocalDate` codec beside `Role.codec`); a `DeployTokens.problems(label, expiresIn)` validation shared with the CLI: label non-empty, ≤ 100 characters, no newline; `expiresIn` absent, `0`, or `1..31536000`.
- [X] T015 [US1] Create `CP/api/DeployTokenEndpoint.scala` at prefix `/organizations` — or add the three routes to `OrganizationEndpoint` if the router's one-prefix rule makes that the only option (it does: two endpoints cannot share a prefix — add them to `OrganizationEndpoint`): `POST /{organizationId}/tokens` (requireOwner, validate, mint, `DeployTokenEntity.create`, then `OrganizationEntity.addMember(AddMember(subject, Role.Member, display = Some(label)))`, `201` `DeployTokenCreated`, the secret in no log line), `GET /{organizationId}/tokens` (requireOwner; the view by `organizationId`, newest first), `DELETE /{organizationId}/tokens/{tokenId}` (requireOwner; `revoke` then `index.evict(id)` then `removeMember`, tolerating `404` from the last). The endpoint receives the index through its constructor from `ControlPlane.endpoints`.
- [X] T016 [US1] CLI: in `CLI/ControlPlaneClient.scala` add `createDeployToken`, `listDeployTokens`, `revokeDeployToken`; in `CLI/Main.scala` add `organizations tokens {create --label [--expires-in <duration>|--never-expires], list, revoke <organization> <token-id>}` with a duration parser (`30d`, `12h`, `90d` default, over `365d` refused before the request); in `CLI/Output.scala` add the one-time secret block and the `ID LABEL CREATED BY EXPIRES LAST USED` table from the contract, `never` and `-` as specified; JSON forms are the wire types.

### Proof

- [X] T017 [US1] Add to `CPT/ControlPlaneHttpSuite.scala`: S1.1 create as owner → `201` with the secret, `GET` lists without it, no JSON body but the create's contains `ankka_`; S1.2 a project rename as the token → `services history`/the organization's history names `label (token:<id>)`; S1.3 `POST …/tokens`, `POST …/members`, `PUT …/name`, `DELETE /organizations/{id}` as the token → `403`; S1.4 revoke → `401` within the V2 bound and on every later call; S1.5 the token against another organization's project → `404`; S1.7 advance T006's clock 91 days → `401` naming the date; a `--never-expires` token still `200` and listed `never`; a disabled organization refuses create with `409`; `whoami` as the token shows the subject, the label, no email, one organization.
- [X] T018 [P] [US1] Add a `token` column to `CPT/AuthorizationMatrixSuite.scala`: member-level actions succeed, owner-level and token-management actions are `403`, another organization is `404`.
- [X] T019 [P] [US1] Add a third run to `CPT/VerificationOverheadBenchmark.scala`: `ControlPlaneAcl.composite` with a minted token in a hand-built index; print `one request, deploy token` and assert it is within the same budget as the OIDC run (SC-003).
- [X] T020 [US1] Add to `CPT/CliEndToEndSuite.scala`: `organizations tokens create` prints the secret once with the expiry date; the secret in `ANKKA_TOKEN` drives `services list`; `tokens list` shows the label and last-used date and not the secret in table and JSON; `tokens revoke` then the same command is `the token was rejected: …`; grep the suite's captured output for `ankka_` outside the create case (SC-007).
- [X] T021 [US1] Documentation: hand-written sections for the three routes in `docs/reference/control-plane-api.md` and for `organizations tokens *` in `docs/reference/cli.md`, then `sbt -Dankka.docs.update=true 'controlPlane/testOnly *ControlPlaneRoutesReferenceSuite *CliReferenceSuite'` to rewrite the tables; "Deploy tokens are members" in `docs/concepts/tenancy-and-access.md`; `docs/platform/identity.md`'s "Machine accounts" leads with the token and keeps the Keycloak client as the alternative; `just docs`. **`docs/deploy/ci.md` stays with T027**, which rewrites it around the action — it cannot be written before the action exists.
- [X] T022 [US1] Add to `CPT/EndToEndClusterSuite.scala`: a token created through the CLI deploys a service through the CLI against the real control plane; after `tokens revoke` the same command is refused — on a control plane with two instances if the suite runs one, so revocation crosses a node (V2 at cluster scale; if the suite runs one instance, say so in the case's comment and record the single-node number).

**Checkpoint**: tiers 1, 2 and 4 green; the token is usable from any script.

---

## Phase 4: User Story 2 — A workflow can install and drive the CLI (P2)

**Goal**: one `uses:` step installs a pinned CLI, points it at a control plane, authenticates it,
and leaves it on `PATH` for any command.

**Independent Test**: a workflow in a scratch repository uses the action and runs `ankka whoami`
and `ankka services list` with only a URL and a token configured (tier 6, steps 1–3); locally,
`ActionSuite` runs the install step against a zip this build made.

- [X] T023 [US2] Create `action/action.yml` per [contracts/action.md](./contracts/action.md): inputs `version` (default `"0.0.0"`), `url`, `token` (required), `project`, `ca`; composite steps — Java check (T004's script inlined; the verbatim `::error::` message naming `actions/setup-java`), install (`curl --fail -sSL` the zip and its `.sha256` from `https://github.com/thinkmorestupidless/ankka/releases/download/v$V/`, `sha256sum --check`, `unzip -q` into `$RUNNER_TEMP/ankka-cli`, `bin` → `$GITHUB_PATH`, `ankka version` equals `$V`), configure (`ANKKA_URL`, `ANKKA_PROJECT` → `$GITHUB_ENV`; `::add-mask::` then `ANKKA_TOKEN`; `ca` → `$RUNNER_TEMP/ankka-ca.crt` and `ANKKA_CA`; an empty `token` fails with the verbatim message), verify (`ankka whoami -o json > /dev/null`). Every step `shell: bash` with `set -euo pipefail`. Support `ANKKA_CLI_BASE_URL` as an override of the download base so T025 can point it at a `file://` directory.
- [X] T024 [P] [US2] Create `action/README.md` (three-line usage, the Java prerequisite, the inputs table, what the action does *not* do — build or push an image) and **no LICENSE** — the repository has none at its root and neither `homebrew/` nor `marketplace/` carries one; Apache 2.0 is declared in `build.sbt` and the README links it.
- [X] T025 [P] [US2] Create `CLIT/ActionSuite.scala` beside `HomebrewFormulaSuite`: line-based checks that `action/action.yml` has the five inputs with the stated `required`, the `version` default is `0.0.0`, and the install step's URL is `…/releases/download/v${ANKKA_VERSION}/ankka-cli-${ANKKA_VERSION}.zip` with `.sha256` beside it; plus a case that runs the install step's script (extracted from the YAML by its step name) with `ANKKA_CLI_BASE_URL=file://<cli/target/universal>` against the zip `cli/Universal/packageBin` produced and a `.sha256` the test writes, into a temp `RUNNER_TEMP`/`GITHUB_PATH`/`GITHUB_ENV`, and asserts `bin/ankka version` prints this build's version — gated like `TemplateSuite` on `unzip` and `sha256sum` being on `PATH`.
- [X] T026 [US2] Add the `action` job to `.github/workflows/release.yml` (`needs: [publish, cli]`, `if: startsWith(github.ref, 'refs/tags/v')`, `persist-credentials: false`): `sed -i 's/default: "0.0.0"/default: "'"$version"'"/' action/action.yml`, `grep` it back, commit, `git subtree split --prefix action -b ankka-action`, push to `thinkmorestupidless/ankka-action` with `TEMPLATE_REPO_TOKEN`, tag `v$version` and `v${version%%.*}` with `-f`, push both `--force`. Add `action/` to the `ci` workflow's checkout-and-lint if a YAML lint exists (none does: skip, and say so in the job comment).
- [X] T027 [US2] Rewrite `docs/deploy/ci.md` around the token and the action: create a token, three secrets, the `uses:` step, `services deploy`; keep the exit-codes and environment-variable tables; move the Keycloak-client recipe to `docs/platform/identity.md` under "A machine account in Keycloak" as the alternative; the example job now uses the action; `just docs`.

**Checkpoint**: `ActionSuite` green; the action's shell runs against a local zip; the release job
is in place for the next tag.

---

## Phase 5: User Story 3 — A generated project ships with workflows that work (P3)

**Goal**: `ankka init` → push → green `ci` and a cleanly skipped `deploy`; three secrets and a tag →
image built, pushed and deployed with `services deploy`.

**Independent Test**: `TemplateSuite`'s new cases locally (tier 4); tier 6 against a real repository.

### `services deploy` (contracts/services-deploy.md)

- [X] T028 [US3] Create `API/Deploy.scala` with `extension (d: ServiceDescriptor) def withImage(image: String): ServiceDescriptor` and `APIT/DeploySuite.scala`: only `service.image` changes; `problems` after equals `problems` before plus any the image introduces (an empty image adds one).
- [X] T029 [US3] In `CLI/Main.scala` add `services deploy <service> <image> [-f <file>]` (default `service.json`, `-` for stdin through `Console.in`): refuse `descriptor.name != service` with `error: <file> names '<x>', not '<y>'`, refuse an empty or whitespace image, `withImage`, `problems`, then `client.applyService`; output as `apply`. Help text: "Deploy a service: the descriptor's settings with this image. The image must already be where the cluster can pull it; ankka runs no registry." Add to `CPT/CliEndToEndSuite.scala`: the applied service reports the command-line image and `service.json` is byte-identical afterwards; a name mismatch exits `1` with no request made (the fake records none). Regenerate `docs/reference/cli.md` with its section; add the row to `docs/reference/akka-divergences.md` ("`akka services deploy … --push`" → "`ankka services deploy` with no `--push`: ankka runs no registry").

### The template (contracts/template-workflows.md, research R10)

- [X] T030 [P] [US3] In `G8/build.sbt` add `version := sys.env.getOrElse("SERVICE_VERSION", "0.1.0-SNAPSHOT")` and `Docker / dockerRepository := sys.env.get("DOCKER_REPOSITORY")` with the two comments from the contract; keep `Docker / version := version.value.replace('+', '-')`.
- [X] T031 [P] [US3] Create `G8/.github/workflows/ci.yml` exactly as the contract shows, with every `${{` written `\${{`.
- [X] T032 [US3] Create `G8/.github/workflows/deploy.yml` per the contract — `check` job testing `secrets.ANKKA_TOKEN` into an output with the `::notice::` when absent, `deploy` job with `needs`/`if`, `packages: write`, `SERVICE_VERSION` from the tag or SHA, `DOCKER_REPOSITORY=ghcr.io/<owner>`, `docker/login-action@v3`, `sbt Docker/publish`, the action with the four secrets, `ankka services deploy $name;format="norm"$ "$IMAGE"` and `services get` — every `${{` escaped, `$name;format="norm"$` unescaped where the service name goes (the one `$` that *is* Giter8's). Settle V3 here: if a job-level `if: ${{ secrets.ANKKA_TOKEN != '' }}` works on `ubuntu-latest`, keep the two-job shape anyway and note in the comment that it could be one.
- [X] T033 [P] [US3] Add "Deploy from GitHub" to `G8/README.md`: the secrets table with the command that produces each, the registry line (public package, or `projects registry set` from US4 — write it now, pointing at the command US4 adds), `git tag v0.1.0 && git push --tags`.
- [X] T034 [US3] Add to `CLIT/TemplateSuite.scala`: "6. the workflows survive expansion" — both files exist under `.github/workflows/`, contain `thinkmorestupidless/ankka-action@`, contain `${{` and no `\${{`, and name `probe` in the deploy step; "7. the build tags for a registry when told" — `sbt Docker/publishLocal` with `DOCKER_REPOSITORY=registry.example.test/acme SERVICE_VERSION=1.2.3` in the subprocess environment produces `registry.example.test/acme/probe:1.2.3` in `docker images`.
- [X] T035 [US3] Finish `docs/deploy/ci.md` as the end-to-end page: from `ankka init` to `Ready`, the first push with no secrets, the tag, the manual dispatch, what a mismatch looks like; **The workflows are deliberately not included**: the include mechanism copies the source verbatim, and in the template every `${{ … }}` is escaped for Giter8, so a reader would see `\${{ … }}` and copy something broken. The page describes the pieces and says so; `TemplateSuite`'s case 6 is what keeps the template's copies correct. `just docs-sync`.
- [ ] T036 [US3] Run tier 6 by hand against a reachable installation (research V4 answered here — a private repository's first `docker push` to GHCR yields a private package): steps 1–7 of [quickstart.md](./quickstart.md#tier-6--github-manual-once-per-release-candidate); record the run URLs and the V4 answer in research.md.

**T036 is NOT DONE, and is not a thing this repository can do to itself.** It needs two things
outside it: a GitHub repository to push a generated project to, and a control plane whose address
resolves and answers from a GitHub-hosted runner. The only installation that ever met the second
condition was the `arrakis` GKE cluster, destroyed for cost on 2026-09-23. Until one exists again,
this feature's GitHub half is proved *up to* the runner:

- the workflows expand correctly and keep their expressions (`TemplateSuite` cases 6 and 7, and the
  escaping trap is the whole reason those assert on the expanded files);
- the action's install and configure steps run as bash, against a real release zip and checksum
  (`ActionSuite`);
- the CLI commands the workflow calls do what it needs, through the real `Main.run` against a real
  control plane (`CliEndToEndSuite`, `EndToEndClusterSuite` case 12);
- a token deploys a real image into a real cluster and revocation stops the next call
  (`EndToEndClusterSuite` case 12).

What remains unproved is the join between them: that a GitHub runner, holding only three repository
secrets, reaches an installation over the public internet and deploys. Nothing in this repository
can stand in for that. To close it: rebuild an installation (`ankka-deployments/docs/decisions.md`),
push a generated project to a repository, add `ANKKA_URL`, `ANKKA_TOKEN` and `ANKKA_PROJECT`, tag,
and record the run URLs and the V4 answer in `research.md`.

**Checkpoint**: `TemplateSuite` green with the two new cases; tier 6 **outstanding**, for the reason
above.

---

## Phase 6: User Story 4 — A service can pull from a private registry (P4)

**Goal**: registry credentials registered once per project; every service in it pulls; nothing in
the journal or any API response holds the password.

**Independent Test**: tier 2's registry cases against the fake; tier 5's k3s case against
`registry:2` with basic auth.

### The resource and the operator

- [X] T037 [P] [US4] Add `imagePullSecret: Option[String] = None` to `CRD/AnkkaService.scala`'s `AnkkaServiceSpec` with a doc comment (set by the control plane from the project's registry; the operator names it and never reads it), the `contentAs` hint only if V6 said so; extend `AnkkaServiceCodecSuite`'s fully-populated spec.
- [X] T038 [P] [US4] In `OP/Rendering.scala` add `.withImagePullSecrets(new LocalObjectReference(name))` to both `PodSpecBuilder` sites when `spec.imagePullSecret` is present, refusing a name that is not a DNS label alongside the existing name checks; add to `OPT/RenderingSuite.scala` "a pull secret is named on the pod when the spec has one, and absent by default", and the same for the two-container shape in `OPT/ProcessHostingRenderingSuite.scala`.

### The project and the control plane

- [X] T039 [P] [US4] In `CP/domain/model.scala` add `RegistryRef(server, username, secretName, setBy: Option[Actor], setAt: Option[Instant])` and `Project.registry: Option[RegistryRef] = None` with `onRegistryConfigured`/`onRegistryCleared`; in `events.scala` add `ProjectEvent.RegistryConfigured(server, username, actor, at)` and `RegistryCleared(actor, at)`; in `CP/application/ProjectEntity.scala` add `configureRegistry(ConfigureRegistry(server, username))` and `clearRegistry` (both `404` on a missing project; `clear` `404` when none is set) and make `get` return the registry summary; `ProjectRows` carries it. Cases in `TenancyEntitySuite` and the events in `EventCompatibilitySuite`.
- [X] T040 [P] [US4] In `API/descriptors.scala` add `SetRegistry(server, username, password)`, `RegistrySummary(server, username, setAt, setBy)`, `registry: Option[RegistrySummary] = None` on `ProjectDetail` and `ProjectSummary`, codecs in `Wire`, and `Registries.problems(server, username, password)` shared with the CLI (the three messages in the contract).
- [X] T041 [US4] In `CP/deploy/AnkkaServiceClient.scala` add `def ensurePullSecret(namespace: String, server: String, username: String, password: String): Unit`; implement in `Fabric8AnkkaServiceClient.scala`: `ensureNamespace` first, then a fresh `Secret` named `ankka-registry`, type `kubernetes.io/dockerconfigjson`, `.dockerconfigjson` built from the standard `auths` document, labelled `app.kubernetes.io/managed-by: ankka`, server-side applied as `ankka-controlplane` with `forceConflicts`; never `get`. (T006 already gave the fake its recording form.)
- [X] T042 [US4] In `CP/api/ProjectEndpoint.scala` add `PUT /{projectId}/registry` (`authz.project(write = true)`, validate, `ensurePullSecret` — a failure is `503` with the reason and nothing recorded — then `configureRegistry`) and `DELETE /{projectId}/registry` (`clearRegistry`; the Secret is left, as the contract says). The endpoint needs the `AnkkaServiceClient`: pass it from `ControlPlane.endpoints` via the projector (`ServiceProjector` exposes its client), or a small `RegistryWriter` interface the projector implements — choose the smaller and say why in a comment.
- [X] T043 [US4] In `CP/deploy/ServiceProjection.scala` add a `registry: Option[RegistryRef]` parameter and set `imagePullSecret = registry.map(_.secretName)`; in `ServiceProjector.scala`'s `Projection.projectOne` read `ProjectEntity.get` (or the row) for the service's project before projecting and pass it; add to `CPT/ProjectorSuite.scala` and `CPT/ServiceProjectionSuite.scala`: the field follows the project's registry and is absent otherwise.
- [X] T044 [US4] In `K/controlplane/controlplane-rbac.yaml` add the `secrets: create, patch` rule with the contract's comment, and reword the header so "never reads a secret" and "no credential able to create a workload" stay true and say what changed; add the same grant to `controlplane/src/main/resources/ankka/install/` if it is not a symlink (it is: verify with `ls -la` and change nothing there).
- [X] T045 [US4] CLI: `projects registry set <project> --server --username (--password | --password-stdin)` and `projects registry clear <project>` in `CLI/Main.scala` and `ControlPlaneClient.scala` (`--password-stdin` reads one line from `Console.in`; the two flags are mutually exclusive); `projects get` and `projects list` show `registry: <server> as <user>, set <date> by <who>` or `none` in `Output.scala`; regenerate `docs/reference/cli.md` with the sections.

### Proof

- [X] T046 [US4] Add to `CPT/ControlPlaneHttpSuite.scala`: set as a member → the fake recorded one `ensurePullSecret` carrying the password → `GET /projects/{id}` shows server and username and no password in table and JSON (grep the body for the password) → a service in the project projects with `imagePullSecret: ankka-registry` (through `ProjectorSuite`'s path or the projector against the fake) → `DELETE` → the next projection has none; with `refuseSecrets` set, `PUT` is `503` and `GET` still says `none`; a deploy token (member) may set it (FR-026 with US1).
- [X] T047 [US4] Add to `CPT/EndToEndClusterSuite.scala`: deploy `registry:2` with an `htpasswd` secret into the k3s cluster (a `Deployment` and a `Service`, applied with the node's `kubectl` as the suites apply Gateway manifests), tag and push the sample image to it from the node under `BuildInfo.version` (with `ctr`/`crictl` as the suite already imports images), `projects registry set` through the CLI, `services deploy` naming the private image, `Ready` (SC-006); then `projects registry clear` and `services restart` → `services get` `detail` names the pull failure (V5 — if `LifecycleRules` does not surface it, extend it and its suite); and V8: with a token minted for the control plane's own ServiceAccount (as `OperatorClusterSuite` mints the operator's), `create` on the Secret succeeds and `get` is `403`.
- [X] T048 [US4] Documentation: "Private registries" in `docs/deploy/images.md` (the command, GHCR with a `read:packages` PAT, that the Secret is per project and never read back, that clearing leaves the Secret); replace the registry line in `docs/reference/limitations.md`; sections for the two routes in `docs/reference/control-plane-api.md` and the regenerated table; `docs/platform/organizations.md` mentions a project's registry; `just docs`.

**Checkpoint**: tiers 2 and 5 green for the registry; a private GHCR package deploys (tier 6 step 3).

---

## Phase 7: Polish and cross-cutting

- [X] T049 [P] Update `README.md`: the "deploy from GitHub" path in the getting-started flow, and the layout section gains `action/`.
- [X] T050 [P] Update `CLAUDE.md`: the module list gains `action/` beside `ankka.g8/`, `marketplace/` and `homebrew/` with the subtree rule; commands gain `sbt 'cli/testOnly *ActionSuite'`; the traps gain, at minimum, "a Consumer runs on one node — anything every node must know is a RuntimeExtension with its own local projection" (R3), "no secret value in the control plane's journal — a credential goes to the cluster and the journal records that it exists" (R7), "`${{` in a template file is `\${{` or it is gone" (R10), and whatever Phases 1–6 taught; the RBAC paragraph names the `secrets: create, patch` grant and why it keeps the invariant.
- [X] T051 [P] `just docs-sync`; confirm the rendered skills under `marketplace/` and `ankka.g8/src/main/g8/.claude/skills/` picked up every changed page; `sbt 'controlPlaneApi/testOnly *DocumentationDescriptorsSuite'`.
- [ ] T052 Re-run every tier of [quickstart.md](./quickstart.md) in order and the reviewer's checklist; then `sbt scalafmtCheckAll scalafmtSbtCheck`, `caffeinate -i sbt buildAll`, `sbt 'controlPlane/testOnly *EndToEndClusterSuite'`, `cd sdks/python && uv run pytest -q && uv run mypy` (SC-008); fix anything red; confirm the six published modules are untouched by this feature — against the **merge base**, not `main`: `git diff --stat $(git merge-base main HEAD) HEAD -- 'modules/**'`. Comparing with `main` directly reports every change *main* has made to a module since this branch started as though this feature had made it, which for this branch is `ankka-http` gaining `Respond`/`Html`/`Bytes` and reads as 153 deleted lines.

---

## Dependencies

```
Phase 1 (V1, V2, V6, V7) ─→ Phase 2 (checksum asset, test helpers)
                                 │
                                 ├─→ US1 (token: entity → index → ACL → routes → CLI → proof) 🎯 MVP
                                 │      │
                                 │      └─→ US2 (action) ─→ US3 (services deploy, template, tier 6)
                                 │
                                 └─→ US4 (registry: resource+operator ‖ project+control plane → k3s)
Phase 7 after everything.
```

- **US1** depends only on Phases 1–2 and is the MVP: any script can hold a token.
- **US2** needs T005 (the checksum asset) and a token to verify against; its local proof (T025) uses
  a `file://` zip and needs no control plane, so it can start once T005 lands, in parallel with US1.
  Tier 6 needs US1.
- **US3** needs US2's action and its own `services deploy` (T028–T029); the template files
  (T030–T033) can be written in parallel with US2 and only `TemplateSuite` (T034) waits.
- **US4** is independent of US1–US3 except for one HTTP case (a token setting the registry) and the
  README line in T033; its two halves (T037–T038 resource and operator; T039–T045 control plane and
  CLI) are disjoint until T043.

## Parallel execution examples

- Phase 1: T001 → T002 on the JVM; T003 and T004 beside them.
- Phase 2: T005 and T006 in parallel.
- US1: T007, T008 and T010 in parallel; T009 after T008; T011–T012 after T009 and T001; T013 after
  T011; T014 beside T011–T013; T015 after T013 and T014; T016 after T014; T017–T020 after T015–T016,
  T018 and T019 in parallel with T017; T021 after T015–T016; T022 last.
- US2: T023 after T004 and T005; T024 and T025 beside it; T026 after T023; T027 after T023 and T021.
- US3: T028 → T029; T030, T031, T033 in parallel with them; T032 after T023; T034 after T030–T032;
  T035 after T029 and T034; T036 after everything in US2 and US3.
- US4: T037 ‖ T038 ‖ T039 ‖ T040; T041 after T006; T042 after T039–T041; T043 after T037 and T039;
  T044 any time; T045 after T040; T046 after T042–T043; T047 after T044–T045; T048 after T045.
- Phase 7: T049–T051 in parallel; T052 last.

## Implementation strategy

1. **Phase 1 first.** T001 decides how the index knows it is warm, and T002 decides what the
   suites may promise about revocation. Neither is a thing to assume.
2. **MVP = Phase 2 + US1.** A token an owner mints and a script uses, refused within a measured
   bound after revocation, with no I/O on the request thread. Everything after it consumes the
   token; nothing changes it.
3. **Prove the action locally before it exists on GitHub.** T025 runs the real install script
   against a real zip from `file://`; the release job (T026) is then plumbing that has been proven
   for three siblings.
4. **US3 is where the promise is kept**, and tier 6 is the only proof of it: run it by hand, record
   the URLs, and do not mark T036 done on a green `TemplateSuite` alone.
5. **US4 can run beside US1** on a second branch of work; its k3s case is the most expensive single
   proof in the feature and should start early.
6. **Every phase ends green in `sbt -Dankka.cluster.tests=off test` and `just docs`**, with the
   cluster suites on for T022, T047 and T052.
